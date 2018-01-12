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
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This Kickable implementation is helping to bridge the world between that of Kickables and that of
 * the rest of the application and expose a single value of a single Kickable instance as an
 * observable.
 *
 * <p>It must not have any further downstreams - it's the last in line.
 *
 * <p>The values/errors will be emitted on the given scheduler.
 */
class KickDownstream<T> extends KickableImpl<T, KickDownstream<T>.State> {
  private static final Log log = Log.get();

  class State extends KickableImpl<T, State>.State {
    public State(KickableImpl<T, ? extends KickableImpl<T, ?>.State> upstream) {
      super();

      List<KickableImpl<?, ?>> upstreams = new ArrayList<>(1);
      upstreams.add(upstream);
      setUpstreams(upstreams);
    }

    public State(State other) {
      super(other);
    }

    @Override
    public State clone() {
      return new State(this);
    }

    @Override
    public void setIsInvalidated() {
      // This Kickable never invalides, it just completes
      throw new UnsupportedOperationException();
    }

    @Override
    public void addDownstream(KickableImpl<?, ?> downstream) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void removeDownstream(KickableImpl<?, ?> downstream) {
      throw new UnsupportedOperationException();
    }
  }

  private final Subject<T> subject = ReplaySubject.create();
  private final Scheduler scheduler;

  public KickDownstream(
      KickableImpl<T, ? extends KickableImpl<T, ?>.State> upstream, Scheduler scheduler) {
    this.scheduler = scheduler;
    initState(new State(upstream));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    // We may still be getting notification after we've done handling as it takes time for an
    // upstream clearing to be handled - just ignore these
    if (nextState.isDone()) {
      return;
    }

    // If our (only) upstream has erred or completed so should we (propagating an appropriate
    // event to the subject)
    if (nextState.propagateErrorAndCompletion()) {
      Exception error = nextState.getError();
      if (error != null) {
        log.warning(() -> getDebugKey() + " produced error", error);
        subject.onError(error);
      }

      if (nextState.isCompleted()) {
        subject.onComplete();
      }

      return;
    }

    // We know that there's only one upstream and we know it's of type T
    KickableImpl<T, ?> upstream = nextState.getUpstreamUnsafe(0);
    Optional<T> upstreamValue = upstream.getValue();
    T emittedValue = nextState.getValue();

    if (emittedValue == null) {
      // We have not ever emitted a value yet
      if (!upstreamValue.isPresent()) {
        // Upstream has no value -- indicate to it that we're interested in one
        nextState.subsribeToUpstream(upstream);
        return;
      }

      // Upstream has a value -- we need to emit it to the subject and remember that we've emitted
      // it
      subject.onNext(nextState.setValue(upstreamValue.get()));
    } else {
      // We have emitted a value before. If the upstream value is not (referentially) identical to
      // what we have emitted or if it has invalidated it is a sign for us to invalidate.
      boolean shouldInvalidate = emittedValue != upstreamValue.orElse(null);
      if (shouldInvalidate) {
        subject.onComplete();
        nextState.setCompleted();
      }
    }
  }

  public Observable<T> getObservable() {
    return subject.observeOn(scheduler);
  }
}
