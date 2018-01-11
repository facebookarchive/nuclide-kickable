/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.Observable;
import io.reactivex.Scheduler;
import java.util.List;

/**
 * This kind of Kickable maintains a constant subscription to the given Observable and propagates
 * its values as its own. As one would expect from a Kickable, the values are cached and the
 * subscriptions are not multiplied with multiple downstreams.
 *
 * <p>However, the single subscription is maintained throughout the lifetime of the given source and
 * will only be completed/erred by completion/error of the source.
 *
 * <p>Such kind of Kickable is meant to be used with hot observable sources.
 */
class ObservableSourcedKickable<T> extends KickableImpl<T, ObservableSourcedKickable<T>.State> {
  class State extends KickableImpl<T, State>.State {
    public State() {
      super();
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
  }

  public ObservableSourcedKickable(Observable<? extends T> source, Scheduler scheduler) {
    initState(new State());
    source
        .subscribeOn(scheduler)
        .subscribe(
            value ->
                scheduleMutateState(
                    state -> {
                      state.setValue(value);
                    }),
            error -> {
              scheduleMutateState(state -> state.setThrowable(error));
            },
            () -> {
              scheduleMutateState(state -> state.setCompleted());
            });
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {}
}
