/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.schedulers.TestScheduler;
import org.junit.Test;

public class SwitchMappingKickableTest {
  @Test
  public void testValuePropagation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> k2 = new ControllableKickable<>(scheduler);

    SwitchMappingKickable<String, String> sw =
        new SwitchMappingKickable<>(k1, val -> k2, scheduler);
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(sw);

    KickableInspector<String> tester = new KickableInspector<>(sw, scheduler);
    tester.assertInvalidated();

    k1.produceValue("abc");
    tester.assertInvalidated();

    k2.produceValue("def");
    tester.assertNotInvalidated();
    tester.assertValueEquals("def");
  }

  @Test
  public void testSourceInvalidation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> k2 = new ControllableKickable<>(scheduler);

    SwitchMappingKickable<String, String> sw =
        new SwitchMappingKickable<>(k1, val -> k2, scheduler);
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(sw);

    KickableInspector<String> tester = new KickableInspector<>(sw, scheduler);
    tester.assertInvalidated();

    k1.produceValue("abc");
    k2.produceValue("def");

    tester.assertNotInvalidated();

    k1.invalidateCurrent();
    tester.assertInvalidated();

    k1.produceValue("ghi");
    tester.assertNotInvalidated();
  }

  @Test
  public void testSourceCompletion() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> k2 = new ControllableKickable<>(scheduler);

    SwitchMappingKickable<String, String> sw =
        new SwitchMappingKickable<>(k1, val -> k2, scheduler);
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(sw);

    KickableInspector<String> tester = new KickableInspector<>(sw, scheduler);

    k1.produceValue("abc");
    k2.produceValue("def");

    tester.assertNotCompleted();

    k1.complete();
    tester.assertCompleted();
  }

  @Test
  public void testDestinationInvalidationAndReemit() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> k2 = new ControllableKickable<>(scheduler);

    SwitchMappingKickable<String, String> sw =
        new SwitchMappingKickable<>(k1, val -> k2, scheduler);
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(sw);

    KickableInspector<String> tester = new KickableInspector<>(sw, scheduler);

    k1.produceValue("abc");
    k2.produceValue("def");

    tester.assertNotInvalidated();
    tester.assertValueEquals("def");

    k2.invalidateCurrent();
    tester.assertInvalidated();

    k2.produceValue("ghi");
    tester.assertNotInvalidated();
    tester.assertValueEquals("ghi");
  }

  @Test
  public void testSourceInvalidationDuringDestinationResolution() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> k1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> k2 = new ControllableKickable<>(scheduler);

    SwitchMappingKickable<String, String> sw =
        new SwitchMappingKickable<>(k1, val -> k2, scheduler);
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(sw);

    KickableInspector<String> tester = new KickableInspector<>(sw, scheduler);

    k1.produceValue("abc");

    tester.assertInvalidated();

    k1.invalidateCurrent();
    tester.assertInvalidated();

    k2.produceValue("ghi");
    tester.assertInvalidated();
  }
}
