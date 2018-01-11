/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.schedulers.TestScheduler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Assert;

public class ControllableKickable<T> extends KickableImpl<T, ControllableKickable<T>.State> {
  class State extends KickableImpl<T, State>.State {
    private int timesSubscribedByDownstream = 0;
    private final Set<KickableImpl<?, ?>> upstreamsToMaintainSubscriptionTo;

    // The subscription status is inherently sourced from other objects' states, yet we want
    // to be able to detect a transition.
    private boolean isSubscribedByAnyone;

    public State() {
      super();

      isSubscribedByAnyone = isSubscribedByAnyone();
      upstreamsToMaintainSubscriptionTo = new HashSet<>();
    }

    public State(State other) {
      super(other);

      this.timesSubscribedByDownstream = other.timesSubscribedByDownstream;
      isSubscribedByAnyone = other.isSubscribedByAnyone;
      upstreamsToMaintainSubscriptionTo = other.upstreamsToMaintainSubscriptionTo;
    }

    @Override
    public State clone() {
      return new State(this);
    }

    public void maintainSubscriptionTo(KickableImpl<?, ?> upstream) {
      this.upstreamsToMaintainSubscriptionTo.add(upstream);
    }
  }

  private final TestScheduler scheduler;

  public ControllableKickable(TestScheduler scheduler) {
    super();

    this.scheduler = scheduler;

    initState(new State());
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    nextState.isSubscribedByAnyone = nextState.isSubscribedByAnyone();
    if (!prevState.isSubscribedByAnyone && nextState.isSubscribedByAnyone) {
      nextState.timesSubscribedByDownstream++;
    }

    nextState.upstreamsToMaintainSubscriptionTo.forEach(nextState::subsribeToUpstream);
  }

  public <V> V readState(Function<State, V> reader) {
    Wait.forEventsPropagation(scheduler);
    AtomicReference<V> ref = new AtomicReference<>(null);
    scheduleMutateState(state -> ref.set(reader.apply(state)));
    eventLoop.flush();

    return ref.get();
  }

  public void produceValue(T value) {
    scheduleMutateState(state -> state.setValue(value));
  }

  public void invalidateCurrent() {
    scheduleMutateState(state -> state.setIsInvalidated());
  }

  public void complete() {
    scheduleMutateState(state -> state.setCompleted());
  }

  public void error() {
    scheduleMutateState(state -> state.setError(new Exception("Test error")));
  }

  public int getTimesSubscribedByDownstream() {
    return readState(state -> state.timesSubscribedByDownstream);
  }

  public void assertTimesSubscribedByDownstream(int expected) {
    Assert.assertEquals(expected, getTimesSubscribedByDownstream());
  }

  public boolean getIsCompleted() {
    return readState(state -> state.isCompleted());
  }

  public void assertNotCompleted() {
    Assert.assertFalse("Kickable should not have been completed", getIsCompleted());
  }

  public void assertCompleted() {
    Assert.assertTrue("Kickable should have been completed", getIsCompleted());
  }

  public void maintainSubscriptionTo(KickableImpl<?, ?> upstream) {
    scheduleMutateState(
        state -> {
          List<KickableImpl<?, ?>> upstreams = state.getUpstreams();
          if (upstreams.indexOf(upstream) == -1) {
            upstreams = new ArrayList<>(upstreams);
            upstreams.add(upstream);
            state.setUpstreams(upstreams);
          }
          state.maintainSubscriptionTo(upstream);
        });
  }
}
