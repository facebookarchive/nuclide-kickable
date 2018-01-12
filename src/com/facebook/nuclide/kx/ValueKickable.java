/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import java.util.List;

/** A Kickable implementation that only has a single value. Never completes and never errors out. */
class ValueKickable<T> extends KickableImpl<T, ValueKickable<T>.State> {
  class State extends KickableImpl<T, State>.State {
    public State(T value) {
      setValue(value);
    }

    public State(State other) {
      super(other);
    }

    @Override
    public State clone() {
      return new State(this);
    }

    @Override
    public void setUpstreams(List<KickableImpl<?, ?>> upstreams) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setIsInvalidated() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setError(Exception error) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setCompleted() {
      throw new UnsupportedOperationException();
    }
  }

  public ValueKickable(T value) {
    initState(new State(value));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {}
}
