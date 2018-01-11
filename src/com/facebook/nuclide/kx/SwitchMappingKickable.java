/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import com.google.common.base.Preconditions;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This type of Kickable allows the values from an upstream be mapped to another Kickable instance.
 * The values emitted will mirror these of the last Kickable that an upstream value was mapped to.
 *
 * <p>The mapping is performed on the given scheduler.
 *
 * <p>Completion of the source Kickable completes this Kickable as well. Completion of the last map
 * result invalidates last produced value.
 *
 * <p>Errors from both the upstream and the currently active mapped-to Kickables propagate as errors
 * of this instance.
 */
class SwitchMappingKickable<U, T> extends KickableImpl<T, SwitchMappingKickable<U, T>.State> {
  class State extends KickableImpl<T, State>.State {
    private U subscribedValue;
    private Disposable mappingSubscription;
    private KickableImpl<T, ?> resolvedSwitchDestination;

    public State(KickableImpl<U, ?> sourceUpstream) {
      List<KickableImpl<?, ?>> upstreams = new ArrayList<>(1);
      upstreams.add(sourceUpstream);
      setUpstreams(upstreams);
      resolvedSwitchDestination = null;
    }

    public State(State other) {
      super(other);

      subscribedValue = other.subscribedValue;
      mappingSubscription = other.mappingSubscription;
      resolvedSwitchDestination = other.resolvedSwitchDestination;
    }

    @Override
    public State clone() {
      return new State(this);
    }

    public KickableImpl<U, ?> getSource() {
      return getUpstreamUnsafe(0);
    }

    private void subscribeIfNeeded(U valueToSubscribe, Function<U, Disposable> subscriber) {
      prepareToUpdate();

      if (valueToSubscribe == subscribedValue) {
        return;
      }

      if (mappingSubscription != null) {
        mappingSubscription.dispose();
      }

      subscribedValue = valueToSubscribe;
      try {
        mappingSubscription = subscriber.apply(valueToSubscribe);
      } catch (Exception e) {
        setError(e);
      }
    }

    public U getSubscribedValue() {
      return subscribedValue;
    }

    private void unsubscribeIfNeeded() {
      prepareToUpdate();

      if (subscribedValue != null) {
        subscribedValue = null;
      }

      if (mappingSubscription != null) {
        mappingSubscription.dispose();
        mappingSubscription = null;
      }
    }

    private KickableImpl<T, ?> getResolvedSwitchDestination() {
      return resolvedSwitchDestination;
    }

    private void clearResolvedSwitchDestination() {
      prepareToUpdate();

      if (resolvedSwitchDestination == null) {
        return;
      }

      setIsInvalidated();

      removeSwitchDestinationFromUpstreams();
      resolvedSwitchDestination = null;
    }

    public void setSwitchDestinationIfSubscribed(U sourceValue, KickableImpl<T, ?> destination) {
      prepareToUpdate();

      if (subscribedValue != sourceValue) {
        return;
      }

      this.resolvedSwitchDestination = destination;

      List<KickableImpl<?, ?>> upstreams = getUpstreams();
      if (upstreams.size() == 2 && destination == upstreams.get(1)) {
        return;
      }

      List<KickableImpl<?, ?>> newUpstreams = new ArrayList<>(2);
      newUpstreams.add(getSource());
      newUpstreams.add(destination);
      setUpstreams(newUpstreams);

      propagateSubscriptionToUpstreams();
    }

    public void errorOutIfSubscribed(U sourceValue, Throwable error) {
      prepareToUpdate();

      if (subscribedValue != sourceValue) {
        return;
      }

      setThrowable(error);
    }

    private void removeSwitchDestinationFromUpstreams() {
      prepareToUpdate();

      List<KickableImpl<?, ?>> upstreams = getUpstreams();
      if (upstreams.size() == 1) {
        return;
      }

      List<KickableImpl<?, ?>> newUpstreams = new ArrayList<>(1);
      newUpstreams.add(getSource());
      setUpstreams(newUpstreams);
    }

    @Override
    protected void doFinishingCleanup() {
      super.doFinishingCleanup();
      unsubscribeIfNeeded();
    }
  }

  private final Function<? super U, Kickable<T>> mapper;
  private final Scheduler scheduler;

  public SwitchMappingKickable(
      KickableImpl<U, ? extends KickableImpl<U, ?>.State> upstream,
      Function<? super U, Kickable<T>> mapper,
      Scheduler scheduler) {
    this.mapper = mapper;
    this.scheduler = scheduler;
    initState(new State(upstream));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    if (nextState.isDone()) {
      return;
    }

    // We're not done yet, so we must have the source upstream
    KickableImpl<U, ?> source = nextState.getSource();
    if (nextState.propagateErrorAndCompletion(source)) {
      return;
    }

    if (source.isInvalidated()) {
      nextState.setIsInvalidated();
      // If the source is not currently valid we definitely can't have a live subscription
      nextState.unsubscribeIfNeeded();
      // If we had a resolved Kickable instance, but it may no longer be the one we need
      // until the new round of mapping is performed we no longer depend on it
      nextState.removeSwitchDestinationFromUpstreams();
      nextState.propagateSubscriptionToUpstreams();
      return;
    }

    U upstreamValue = source.getValue().get();
    if (upstreamValue != nextState.getSubscribedValue()) {
      // If the source value has changed we can't have a live subscription for the previous one
      nextState.unsubscribeIfNeeded();
      // If we had a resolved Kickable instance, but it may no longer be the one we need
      // until the new round of mapping is performed we no longer depend on it
      nextState.clearResolvedSwitchDestination();
    }

    KickableImpl<T, ?> resolvedDestination = nextState.getResolvedSwitchDestination();
    if (resolvedDestination != null && resolvedDestination.isDone()) {
      nextState.clearResolvedSwitchDestination();
      if (resolvedDestination.isErred()) {
        // But we do propagate errors
        nextState.setError(resolvedDestination.getError().get());
        return;
      }
      resolvedDestination = null;
    }

    if (resolvedDestination == null) {
      // We haven't resolved the destination Kickable, but can trigger the resolution if need be
      if (nextState.isSubscribedByAnyone()) {
        // We're subscribed, so we do need to resolve the destination
        nextState.subscribeIfNeeded(upstreamValue, this::subscribeToMapper);
      } else {
        // Nobody cares, do not invest unnecessary resources into the computation
        nextState.unsubscribeIfNeeded();
      }

      nextState.propagateSubscriptionToUpstreams();
      return;
    }

    // The destination is resolved and is current (matches the source value for the mapping)
    Optional<T> resolvedValue = resolvedDestination.getValue();
    if (resolvedValue.isPresent()) {
      nextState.setValueWithCheapEqualityCheck(resolvedValue.get());
    } else {
      nextState.setIsInvalidated();
      nextState.propagateSubscriptionToUpstreams();
    }
  }

  private Disposable subscribeToMapper(U valueToMap) {
    return Observable.defer(() -> Observable.just(mapper.apply(valueToMap)))
        .subscribeOn(scheduler)
        .firstOrError()
        .subscribe(
            value -> {
              Preconditions.checkState(value instanceof KickableImpl);
              @SuppressWarnings("unchecked")
              KickableImpl<T, ?> destination = (KickableImpl<T, ?>) value;
              scheduleMutateState(
                  state -> state.setSwitchDestinationIfSubscribed(valueToMap, destination));
            },
            error -> scheduleMutateState(state -> state.errorOutIfSubscribed(valueToMap, error)));
  }
}
