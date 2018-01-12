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
import io.reactivex.disposables.Disposable;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * This kind of Kickable dynamically creates a subscription to the given lifecycle providing
 * Observable and propagates its values as its own. As one would expect from a Kickable, the values
 * are cached and the subscriptions are not multiplied with multiple downstreams.
 *
 * <p>The dynamicity of subscription means that the lifecycles source is only subscribed when
 * there's need to produce values. This means that it is suitable for high-price cold Observables,
 * such as process invocations.
 */
class LifecycleSourcedKickable<T> extends KickableImpl<T, LifecycleSourcedKickable<T>.State> {
  private static final Log log = Log.get();

  class State extends KickableImpl<T, State>.State {
    private Optional<Disposable> lifecyclesSubscription = Optional.empty();

    public State() {
      super();
    }

    public State(State other) {
      super(other);

      lifecyclesSubscription = other.lifecyclesSubscription;
    }

    @Override
    public State clone() {
      return new State(this);
    }

    @Override
    public void setUpstreams(List<KickableImpl<?, ?>> upstreams) {
      throw new UnsupportedOperationException();
    }

    public void subscribeIfNeeded(Supplier<Disposable> supplier) {
      prepareToUpdate();

      if (lifecyclesSubscription.isPresent()) {
        return;
      }

      log.finest(() -> getDebugKey() + " subscribing to lifecycles");
      setDirty();
      lifecyclesSubscription = Optional.of(supplier.get());
    }

    public void unsubscribe() {
      prepareToUpdate();

      lifecyclesSubscription.ifPresent(
          subscription -> {
            log.finest(() -> getDebugKey() + " unsubscribing from lifecycles");
            setDirty();
            subscription.dispose();
            lifecyclesSubscription = Optional.empty();
          });
    }

    public void upstreamInvalidated() {
      if (isInvalidated()) {
        return;
      }

      setIsInvalidated();
      unsubscribe();
    }
  }

  private final Observable<Optional<T>> lifecycles;
  private final Scheduler scheduler;

  public LifecycleSourcedKickable(Observable<Optional<T>> lifecycles, Scheduler scheduler) {
    this.lifecycles = lifecycles;
    this.scheduler = scheduler;
    initState(new State());
  }

  private Disposable subscribeToLifecycles() {
    return lifecycles
        .subscribeOn(scheduler)
        .subscribe(
            value ->
                scheduleMutateState(
                    state -> {
                      if (value.isPresent()) {
                        state.setValue(value.get());
                      } else {
                        state.upstreamInvalidated();
                      }
                    }),
            error -> scheduleMutateState(state -> state.setError((Exception) error)),
            () -> scheduleMutateState(state -> state.setCompleted()));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    if (nextState.isCompleted()
        || nextState.getError() != null
        || (nextState.isInvalidated() && !nextState.isSubscribedByAnyone())) {
      nextState.unsubscribe();
      return;
    }

    if (nextState.isSubscribedByAnyone()) {
      nextState.subscribeIfNeeded(this::subscribeToLifecycles);
    }
  }
}
