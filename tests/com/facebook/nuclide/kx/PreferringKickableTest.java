/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.schedulers.TestScheduler;
import org.junit.Test;

public class PreferringKickableTest {
  @Test
  public void testSwitchingDown() {
    TestScheduler scheduler = new TestScheduler();
    Object o1 = "abc";
    Object o2 = "def";
    ControllableKickable<Object> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<Object> k2 = new ControllableKickable<>(scheduler);

    PreferringKickable<Object> pk = new PreferringKickable<>(k1, k2);
    k1.assertTimesSubscribedByDownstream(0);
    k2.assertTimesSubscribedByDownstream(0);

    KickableInspector<Object> inspector = new KickableInspector<>(pk, scheduler);

    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(pk);

    k1.assertTimesSubscribedByDownstream(1);
    k2.assertTimesSubscribedByDownstream(1);
    inspector.assertInvalidated();

    k2.produceValue(o2);

    inspector.assertNotInvalidated();
    inspector.assertValueEquals(o2);

    k1.produceValue(o1);

    inspector.assertNotInvalidated();
    inspector.assertValueEquals(o1);

    k1.invalidateCurrent();
    inspector.assertNotInvalidated();
    inspector.assertValueEquals(o2);
  }

  @Test
  public void testSourceCompletionPropagation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<Object> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<Object> k2 = new ControllableKickable<>(scheduler);

    PreferringKickable<Object> pk = new PreferringKickable<>(k1, k2);
    k1.assertTimesSubscribedByDownstream(0);
    k2.assertTimesSubscribedByDownstream(0);

    KickableInspector<Object> inspector = new KickableInspector<>(pk, scheduler);

    inspector.assertNotCompleted();

    k2.complete();

    inspector.assertCompleted();
  }
}
