/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import com.facebook.nuclide.logging.Log;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import java.util.ArrayList;
import java.util.List;

/**
 * This type of Kickable is used to asynchronously transform the values from one type to another.
 *
 * <p>The mapping to is performed by the given mapping function on the given scheduler. The
 * subscription to the result is maintained only as long as the result is of interest. I.e if all
 * downstreams unsubsribe, or the upstream Kickable have signaled an invalidation or have updated
 * its value the previous Single received from the mapping function will be unsubscribed. Thus it is
 * beneficial (although not required) to make the mapping function return Single instances that
 * handle unsubscription and avoid doing extra work.
 */
class MappingKickable<U, T> extends KickableImpl<T, MappingKickable<U, T>.State> {
  private static final Log log = Log.get();

  class State extends KickableImpl<T, State>.State {
    private U subscribedValue;
    private Disposable mappingSubscription;

    public State(KickableImpl<U, ?> upstream) {
      List<KickableImpl<?, ?>> upstreams = new ArrayList<>(1);
      upstreams.add(upstream);
      setUpstreams(upstreams);
    }

    public State(State other) {
      super(other);

      subscribedValue = other.subscribedValue;
      mappingSubscription = other.mappingSubscription;
    }

    @Override
    public State clone() {
      return new State(this);
    }

    private void subscribeIfNeeded(U valueToSubscribe, Function<U, Disposable> subscriber) {
      prepareToUpdate();

      if (valueToSubscribe == subscribedValue) {
        return;
      }

      if (mappingSubscription != null) {
        log.finest(() -> getDebugKey() + " previous subscription is obsolete - disposing it");
        mappingSubscription.dispose();
      }

      subscribedValue = valueToSubscribe;
      try {
        log.finest(() -> getDebugKey() + " subscribing");
        mappingSubscription = subscriber.apply(valueToSubscribe);
      } catch (Exception e) {
        log.severe(() -> getDebugKey() + " Error while subscribing", e);
        setError(e);
      }
    }

    private void unsubscribeIfNeeded() {
      prepareToUpdate();

      if (subscribedValue != null) {
        subscribedValue = null;
      }

      if (mappingSubscription != null) {
        log.finest(() -> getDebugKey() + " subscription no longer needed - unsubscribing");
        mappingSubscription.dispose();
        mappingSubscription = null;
      }
    }

    public U getSubscribedValue() {
      return subscribedValue;
    }

    public void setValueIfSubscribed(U sourceValue, T resolvedValue) {
      prepareToUpdate();

      if (sourceValue == subscribedValue) {
        if (mappingSubscription != null) {
          mappingSubscription.dispose(); // Just in case
          mappingSubscription = null;
        }

        log.finest(() -> getDebugKey() + " resolved value from subscription");
        setValue(resolvedValue);
        return;
      }

      log.finest(() -> getDebugKey() + " resolved value was stale");
    }

    public void errorOutIfSubscribed(U sourceValue, Throwable error) {
      prepareToUpdate();

      if (sourceValue == subscribedValue) {
        if (mappingSubscription != null) {
          mappingSubscription.dispose(); // Just in case
          mappingSubscription = null;
        }

        setThrowable(error);
      }
    }
  }

  private final Function<? super U, Single<T>> mapper;
  private final Scheduler scheduler;

  public MappingKickable(
      KickableImpl<U, ? extends KickableImpl<U, ?>.State> upstream,
      Function<? super U, Single<T>> mapper,
      Scheduler scheduler) {
    this.mapper = mapper;
    this.scheduler = scheduler;
    initState(new State(upstream));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    if (nextState.isDone() || nextState.propagateErrorAndCompletion()) {
      nextState.unsubscribeIfNeeded();
      return;
    }

    KickableImpl<U, ?> upstream = nextState.getUpstreamUnsafe(0);

    if (upstream.isInvalidated()) {
      log.iff(!nextState.isInvalidated())
          .finest(() -> getDebugKey() + " Upstream invalidated - invalidating");
      nextState.unsubscribeIfNeeded();
      nextState.setIsInvalidated();
      nextState.propagateSubscriptionToUpstreams();
      return;
    }

    U upstreamValue = upstream.getValue().get();
    if (upstreamValue != nextState.getSubscribedValue()) {
      log.iff(!nextState.isInvalidated())
          .finest(() -> getDebugKey() + " Upstream changed value - invalidating");
      // Upstream value has changed, we need to invalidate and reevaluate our mapping
      nextState.unsubscribeIfNeeded();
      nextState.setIsInvalidated();
    }

    if (nextState.isSubscribedByAnyone()) {
      nextState.subscribeIfNeeded(upstreamValue, this::subscribeToMapper);
    } else {
      // Apparently nobody cares about the value any more - cancel the mapping subscription
      // but only if in invalidated state. Otherwise the subscription is already disposed anyway
      // yet we need to keep the source subscribedValue around to know whether we will need to
      // recreate the subscription in the future or not
      if (nextState.isInvalidated()) {
        nextState.unsubscribeIfNeeded();
      }
    }

    if (nextState.isInvalidated()) {
      nextState.propagateSubscriptionToUpstreams();
    }
  }

  private Disposable subscribeToMapper(U valueToMap) {
    return Observable.defer(() -> Observable.just(mapper.apply(valueToMap)))
        .subscribeOn(scheduler)
        .firstOrError()
        .flatMap(single -> single)
        .subscribe(
            value -> scheduleMutateState(state -> state.setValueIfSubscribed(valueToMap, value)),
            error -> scheduleMutateState(state -> state.errorOutIfSubscribed(valueToMap, error)));
  }
}
