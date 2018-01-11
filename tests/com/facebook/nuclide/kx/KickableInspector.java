/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.functions.Function;
import io.reactivex.schedulers.TestScheduler;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;

public class KickableInspector<T> {
  private final KickableImpl<T, ? extends KickableImpl<T, ?>.State> inspected;
  private final TestScheduler scheduler;

  public KickableInspector(
      KickableImpl<T, ? extends KickableImpl<T, ?>.State> inspected, TestScheduler scheduler) {
    this.inspected = inspected;
    this.scheduler = scheduler;
  }

  public <V> V readState(Function<KickableImpl<T, ?>.State, V> reader) {
    Wait.forEventsPropagation(scheduler);
    AtomicReference<V> ref = new AtomicReference<>(null);
    inspected.scheduleMutateState(state -> ref.set(reader.apply(state)));
    KickableImpl.eventLoop.flush();

    return ref.get();
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

  public boolean getIsErred() {
    return readState(state -> state.getError() != null);
  }

  public void assertNotErred() {
    Assert.assertFalse("Kickable should not have erred", getIsErred());
  }

  public void assertErred() {
    Assert.assertTrue("Kickable should have erred", getIsErred());
  }

  public boolean getIsInvalidated() {
    return readState(state -> state.isInvalidated());
  }

  public void assertNotInvalidated() {
    Assert.assertFalse("Kickable should not have been invalidated", getIsInvalidated());
  }

  public void assertInvalidated() {
    Assert.assertTrue("Kickable should have been invalidated", getIsInvalidated());
  }

  public T getValue() {
    return readState(state -> state.getValue());
  }

  public void assertValueEquals(T expected) {
    Assert.assertEquals(expected, getValue());
  }
}
