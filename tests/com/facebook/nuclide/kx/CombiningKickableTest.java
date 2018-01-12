/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.schedulers.TestScheduler;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

public class CombiningKickableTest {
  @Test
  public void testEmptyUpstreamsProducingEmptyList() {
    CombiningKickable combined = new CombiningKickable(combine());
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    inspector.assertNotInvalidated();
    Assert.assertTrue("Expected an empty list", inspector.getValue().isEmpty());
  }

  @Test
  public void testSingleUpstreamPropagation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    inspector.assertInvalidated();

    upstream.produceValue("abc");
    inspector.assertNotInvalidated();
    assertListEquals(inspector.getValue(), "abc");

    upstream.produceValue("def");
    inspector.assertNotInvalidated();
    assertListEquals(inspector.getValue(), "def");

    upstream.invalidateCurrent();
    inspector.assertInvalidated();
  }

  @Test
  public void testSingleUpstreamErrorsOutBeforeEmittance() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    inspector.assertInvalidated();
    inspector.assertNotCompleted();
    inspector.assertNotErred();

    upstream.error();
    inspector.assertErred();
  }

  @Test
  public void testSingleUpstreamErrorsOutAfterEmittance() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    upstream.produceValue("abc");

    inspector.assertNotInvalidated();
    inspector.assertNotCompleted();
    inspector.assertNotErred();

    upstream.error();
    inspector.assertErred();
  }

  @Test
  public void testSingleUpstreamCompletionPropagation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    upstream.produceValue("abc");

    inspector.assertNotCompleted();
    inspector.assertNotErred();

    upstream.complete();
    inspector.assertCompleted();
  }

  @Test
  public void testNonPropagationUntilAllUpstreamsHaveSettled() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> upstream2 = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream1, upstream2));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    inspector.assertInvalidated();

    upstream1.produceValue("abc");
    inspector.assertInvalidated();

    upstream1.invalidateCurrent();
    upstream2.produceValue("def");
    inspector.assertInvalidated();

    upstream1.produceValue("ghi");
    inspector.assertNotInvalidated();
    assertListEquals(inspector.getValue(), "ghi", "def");

    upstream2.invalidateCurrent();
    inspector.assertInvalidated();
  }

  @Test
  public void testMultiUpstreamErrorsOut() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> upstream2 = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream1, upstream2));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    upstream1.produceValue("abc");
    upstream2.produceValue("def");

    inspector.assertNotInvalidated();
    inspector.assertNotCompleted();
    inspector.assertNotErred();

    upstream2.error();
    inspector.assertErred();
  }

  @Test
  public void testMultiUpstreamCompletionPropagation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> upstream2 = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream1, upstream2));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    upstream1.produceValue("abc");
    upstream2.produceValue("def");

    inspector.assertNotInvalidated();
    inspector.assertNotCompleted();
    inspector.assertNotErred();

    upstream2.complete();
    inspector.assertCompleted();
  }

  @Test
  public void testPreferenceOfErrorsOverCompletion() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> upstream1 = new ControllableKickable<>(scheduler);
    ControllableKickable<String> upstream2 = new ControllableKickable<>(scheduler);
    CombiningKickable combined = new CombiningKickable(combine(upstream1, upstream2));
    ControllableKickable<Void> downstream = new ControllableKickable<>(scheduler);
    downstream.maintainSubscriptionTo(combined);
    KickableInspector<List<?>> inspector = new KickableInspector<>(combined, scheduler);

    inspector.assertInvalidated();

    // TODO: Ideally we'd like to suspend the graph processing thread here to guarantee that
    // the following error and completion always race and hence both are being handled in the
    // same handleStateUpdate call.
    upstream1.error();
    upstream2.complete();

    inspector.assertErred();
  }

  private List<KickableImpl<?, ?>> combine(KickableImpl<?, ?>... upstreams) {
    return Stream.of(upstreams).collect(Collectors.toList());
  }

  private void assertListEquals(List<?> lst, Object... expected) {
    Assert.assertEquals(expected.length, lst.size());
    for (int i = 0; i < lst.size(); i++) {
      Assert.assertEquals(expected[i], lst.get(i));
    }
  }
}
