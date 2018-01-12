/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.Scheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This Kickable propagates the events as is from its (only) upstream and notifies the given
 * listener about changes in its state.
 *
 * <p>Useful for debuggin purposes and to respond to transitions to states such as erred/completed.
 *
 * <p>Note, it can nob be used to reliably monitor intermediate transitions (such as value
 * production, invalidation and subscription status). The listener does have these methods but due
 * to the inherent batching of state updates, the methods will be invoked according to transitions
 * of *this* instance, rather than the one being monitored (the upstream).
 *
 * <p>I.e. it is possible that the upstream will invalidate a value and then will quickly replace it
 * (several times even) with referentially identical and this instance will miss it completely. Same
 * goes for downstream subscriptions. A downstream may subscribe and unsubscribe in close proximity
 * and due to batching an appropriate callback will NOT be invoked.
 *
 * <p>The error and compelete states, however, are terminal. So they are guaranteed to be detected.
 *
 * <p>The invocation of the callback methods are performed on the given scheduler.
 */
class NotifyingKickable<T> extends KickableImpl<T, NotifyingKickable<T>.State> {
  class State extends KickableImpl<T, State>.State {
    private boolean wasSubscribed;

    public State(KickableImpl<T, ? extends KickableImpl<T, ?>.State> upstream) {
      super();

      List<KickableImpl<?, ?>> upstreams = new ArrayList<>(1);
      upstreams.add(upstream);
      setUpstreams(upstreams);
      wasSubscribed = false;
    }

    public State(State other) {
      super(other);

      this.wasSubscribed = other.wasSubscribed;
    }

    @Override
    public State clone() {
      return new State(this);
    }

    public void setWasSubscribed(boolean wasSubscribed) {
      prepareToUpdate();

      this.wasSubscribed = wasSubscribed;
    }

    public boolean wasSubscribed() {
      return wasSubscribed;
    }
  }

  private final KickableEventListener<? super T> listeners;
  private final Scheduler scheduler;

  public NotifyingKickable(
      KickableImpl<T, ? extends KickableImpl<T, ?>.State> upstream,
      KickableEventListener<? super T> listener,
      Scheduler scheduler) {
    this.listeners = listener;
    this.scheduler = scheduler;
    initState(new State(upstream));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    if (nextState.isDone()) {
      return;
    }

    KickableImpl<T, ?> upsteam = nextState.getUpstreamUnsafe(0);
    Optional<Exception> upstreamError = upsteam.getError();
    if (upstreamError.isPresent()) {
      nextState.setError(upstreamError.get());
      scheduler.scheduleDirect(() -> listeners.onError(upstreamError.get()));

      return;
    }

    if (upsteam.isCompleted()) {
      nextState.setCompleted();
      scheduler.scheduleDirect(() -> listeners.onComplete());

      return;
    }

    Optional<T> upstreamValue = upsteam.getValue();
    upstreamValue.ifPresent(nextState::setValueWithCheapEqualityCheck);
    nextState.propagateInvalidation();

    // The subscription status is transient, we won't be able to just take it from the prev state's
    // isSubscribedByAnyone(), so we need to keep it around
    boolean isSubscribed = nextState.isSubscribedByAnyone();
    if (!nextState.wasSubscribed() && isSubscribed) {
      scheduler.scheduleDirect(() -> listeners.onSubscribeByADownstream());
      nextState.setWasSubscribed(isSubscribed);
    } else if (nextState.wasSubscribed() && !isSubscribed) {
      scheduler.scheduleDirect(() -> listeners.onUnsubscribeByDownstreams());
      nextState.setWasSubscribed(isSubscribed);
    }

    if (prevState.isInvalidated() && !nextState.isInvalidated()
        || prevState.getValue() != nextState.getValue()) {
      scheduler.scheduleDirect(() -> listeners.onProducedValue(nextState.getValue()));
    } else if (!prevState.isInvalidated() && nextState.isInvalidated()) {
      scheduler.scheduleDirect(() -> listeners.onInvalidated());
    }

    if (nextState.isInvalidated()) {
      nextState.propagateSubscriptionToUpstreams();
    }
  }
}
