/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * This Kickable type propagates the values from the given list of upstreams. The position in the
 * list specifies the order of preference (earliest Kickables are the most preferred). When an
 * upstream invalidates this Kickable will automatically switch to the next most preferred one.
 *
 * <p>If none of the upstreams have a value it'll invalidate as well.
 *
 * <p>The errors/completion is propagated from all of the given upstreams
 */
class PreferringKickable<T> extends KickableImpl<T, PreferringKickable<T>.State> {
  class State extends KickableImpl<T, State>.State {
    @SafeVarargs
    public State(KickableImpl<T, ?> base, KickableImpl<T, ?>... others) {
      List<KickableImpl<?, ?>> combined = new ArrayList<>(others.length + 1);
      combined.add(base);
      Stream.of(others).forEach(other -> combined.add(other));

      setUpstreams(combined);
    }

    public State(State other) {
      super(other);
    }

    @Override
    public State clone() {
      return new State(this);
    }
  }

  @SafeVarargs
  public PreferringKickable(KickableImpl<T, ?> base, KickableImpl<T, ?>... others) {
    initState(new State(base, others));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    if (nextState.isDone() || nextState.propagateErrorAndCompletion()) {
      return;
    }

    for (KickableImpl<?, ?> upstream : nextState.getUpstreams()) {
      // We only propagate the subscription up till the first upstream that has a value
      // The rest of them are less preferred any way, so we won't use their value any way
      @SuppressWarnings("unchecked")
      Optional<T> value = (Optional<T>) upstream.getValue();
      if (value.isPresent()) {
        nextState.setValue(value.get());
        return;
      }

      if (nextState.isSubscribedByAnyone() || !nextState.isInvalidated()) {
        nextState.subsribeToUpstream(upstream);
      }
    }

    nextState.setIsInvalidated();
  }
}
