/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import static org.junit.Assert.assertEquals;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.TestScheduler;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class MappingKickableTest {
  @SuppressWarnings("unchecked")
  @Test
  public void testKick() throws Exception {
    Object o1 = new Object();
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<Object> control = new ControllableKickable<>(scheduler);

    Function<Object, Single<String>> foo = Mockito.mock(Function.class);
    Mockito.when(foo.apply(o1)).thenReturn(Single.just("abc"));

    MappingKickable<Object, String> ssk = new MappingKickable<>(control, foo, scheduler);
    control.assertTimesSubscribedByDownstream(0);

    Observable<String> kickRes = ssk.kick(scheduler);
    control.assertTimesSubscribedByDownstream(1);
    Mockito.verify(foo, Mockito.never()).apply(Mockito.any());

    TestObserver<String> kickTester = kickRes.test();
    control.assertTimesSubscribedByDownstream(1);
    Mockito.verify(foo, Mockito.never()).apply(Mockito.any());

    control.produceValue(o1);
    Wait.forEventsPropagation(scheduler);

    Mockito.verify(foo, Mockito.times(1)).apply(o1);
    kickTester.assertValue("abc");
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testKickAfterInvalidation() throws Exception {
    Object o1 = new Object();
    Object o2 = new Object();
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<Object> control = new ControllableKickable<>(scheduler);

    Function<Object, Single<String>> foo = Mockito.mock(Function.class);
    Mockito.when(foo.apply(o1))
        .thenReturn(Single.just("abc"))
        .thenThrow(new RuntimeException("Can only be called once with this param"));
    Mockito.when(foo.apply(o2))
        .thenReturn(Single.just("def"))
        .thenThrow(new RuntimeException("Can only be called once with this param"));

    MappingKickable<Object, String> mk = new MappingKickable<>(control, foo, scheduler);
    Observable<String> kickRes1 = mk.kick(scheduler);
    TestObserver<String> kickTester1 = kickRes1.test();
    control.assertTimesSubscribedByDownstream(1);

    control.produceValue(o1);

    Wait.forEventsPropagation(scheduler);
    Mockito.verify(foo, Mockito.times(1)).apply(o1);

    kickTester1.assertValue("abc");
    kickTester1.assertNotComplete();

    control.invalidateCurrent();

    Wait.forEventsPropagation(scheduler);
    kickTester1.assertComplete();

    Observable<String> kickRes2 = mk.kick(scheduler);
    control.assertTimesSubscribedByDownstream(2);

    TestObserver<String> kickTester2 = kickRes2.test();

    Wait.forEventsPropagation(scheduler);
    kickTester2.assertNoValues();

    control.produceValue(o2);

    Wait.forEventsPropagation(scheduler);
    Mockito.verify(foo, Mockito.times(1)).apply(o1);
    Mockito.verify(foo, Mockito.times(1)).apply(o2);
    kickTester2.assertValue("def");
    kickTester2.assertNotComplete();

    control.complete();

    Wait.forEventsPropagation(scheduler);
    kickTester2.assertComplete();
  }

  @Test
  public void testNonInvocationWhenNoSubscribersArePresent() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    int[] invokeCounter = new int[] {0};
    MappingKickable<String, String> k =
        new MappingKickable<>(
            kSource,
            val -> {
              invokeCounter[0]++;
              return Single.just("v:" + val);
            },
            scheduler);

    k.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    assertEquals(0, invokeCounter[0]);

    kSource.produceValue("abc");

    Wait.forEventsPropagation(scheduler);
    assertEquals(1, invokeCounter[0]);

    kSource.invalidateCurrent();
    kSource.produceValue("def");

    Wait.forEventsPropagation(scheduler);
    Assert.assertEquals("Expected the function to be invoked", 1, invokeCounter[0]);
  }

  @Test
  public void testSourceClosureReponseNoItems() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    MappingKickable<String, String> k = new MappingKickable<>(kSource, Single::just, scheduler);
    Wait.forEventsPropagation(scheduler);

    KickableInspector<String> closeTest = new KickableInspector<>(k, scheduler);

    closeTest.assertNotCompleted();

    kSource.complete();

    closeTest.assertCompleted();
  }

  @Test
  public void testSourceClosureReponseItemPendingInvalidation() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    MappingKickable<String, String> k = new MappingKickable<>(kSource, Single::just, scheduler);

    KickableInspector<String> closeTest = new KickableInspector<>(k, scheduler);
    TestObserver<String> valuesTest = k.kick(scheduler).test();

    closeTest.assertNotCompleted();

    kSource.produceValue("abc");
    closeTest.assertNotCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest.assertValue("abc");
    valuesTest.assertNotComplete();

    kSource.complete();

    closeTest.assertCompleted();
    valuesTest.assertComplete();
  }

  @Test
  public void testSourceClosureReponseItemPendingResolution() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    MappingKickable<String, String> k =
        new MappingKickable<>(kSource, v -> Single.never(), scheduler);

    KickableInspector<String> closeTest = new KickableInspector<>(k, scheduler);
    TestObserver<String> valuesTest = k.kick(scheduler).test();

    closeTest.assertNotCompleted();

    kSource.produceValue("abc");

    closeTest.assertNotCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest.assertNoValues();
    valuesTest.assertNotComplete();

    kSource.complete();

    closeTest.assertCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest.assertComplete();
  }

  @Test
  public void testSourceClosureReponseItemInvalidated() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    MappingKickable<String, String> k = new MappingKickable<>(kSource, Single::just, scheduler);

    KickableInspector<String> closeTest = new KickableInspector<>(k, scheduler);
    TestObserver<String> valuesTest = k.kick(scheduler).test();

    closeTest.assertNotCompleted();

    kSource.produceValue("abc");

    closeTest.assertNotCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest.assertValue("abc");
    valuesTest.assertNotComplete();

    kSource.invalidateCurrent();

    closeTest.assertNotCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest.assertComplete();

    kSource.complete();
    closeTest.assertCompleted();
  }

  @Test
  public void testSourceClosureReponseMultipleConsumers() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    MappingKickable<String, String> k1 = new MappingKickable<>(kSource, Single::just, scheduler);
    MappingKickable<String, String> k2 = new MappingKickable<>(kSource, Single::just, scheduler);

    KickableInspector<String> closeTest1 = new KickableInspector<>(k1, scheduler);
    TestObserver<String> valuesTest1 = k1.kick(scheduler).test();
    KickableInspector<String> closeTest2 = new KickableInspector<>(k2, scheduler);
    TestObserver<String> valuesTest2 = k2.kick(scheduler).test();

    closeTest1.assertNotCompleted();
    closeTest2.assertNotCompleted();

    kSource.produceValue("abc");

    closeTest1.assertNotInvalidated();

    valuesTest1.assertValue("abc");

    valuesTest1.assertNotComplete();
    closeTest2.assertNotCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest2.assertValue("abc");
    valuesTest2.assertNotComplete();

    kSource.invalidateCurrent();

    Wait.forEventsPropagation(scheduler);
    valuesTest1.assertComplete();
    valuesTest2.assertComplete();

    closeTest1.assertNotCompleted();
    closeTest2.assertNotCompleted();

    kSource.complete();
    closeTest1.assertCompleted();
    closeTest2.assertCompleted();
  }

  @Test
  public void testSourceClosureReponseMultiLevel() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    MappingKickable<String, String> k1 = new MappingKickable<>(kSource, Single::just, scheduler);
    MappingKickable<String, String> k2 = new MappingKickable<>(k1, Single::just, scheduler);

    KickableInspector<String> closeTest1 = new KickableInspector<>(k1, scheduler);
    TestObserver<String> valuesTest1 = k1.kick(scheduler).test();
    KickableInspector<String> closeTest2 = new KickableInspector<>(k2, scheduler);
    TestObserver<String> valuesTest2 = k2.kick(scheduler).test();

    closeTest1.assertNotCompleted();
    closeTest2.assertNotCompleted();

    kSource.produceValue("abc");

    closeTest1.assertNotCompleted();

    Wait.forEventsPropagation(scheduler);
    valuesTest1.assertValue("abc");
    valuesTest1.assertNotComplete();

    closeTest2.assertNotCompleted();
    valuesTest2.assertValue("abc");
    valuesTest2.assertNotComplete();

    kSource.invalidateCurrent();
    Wait.forEventsPropagation(scheduler);

    valuesTest1.assertComplete();
    valuesTest2.assertComplete();
    closeTest1.assertNotCompleted();
    closeTest2.assertNotCompleted();

    kSource.complete();
    Wait.forEventsPropagation(scheduler);

    closeTest1.assertCompleted();
    closeTest2.assertCompleted();
  }

  @Test
  public void testInvalidationHandlingWhilstMapping() {
    TestScheduler scheduler = new TestScheduler();
    ControllableKickable<String> kSource = new ControllableKickable<>(scheduler);
    Map<String, MonitoredSubject<String>> subjects = new HashMap<>();
    subjects.put("abc", new MonitoredSubject<>());
    subjects.put("def", new MonitoredSubject<>());
    MappingKickable<String, String> k1 =
        new MappingKickable<>(
            kSource, val -> subjects.get(val).observe().firstOrError(), scheduler);

    TestObserver<String> tester = k1.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    subjects.get("abc").assertSubscribesAndClear(0);

    kSource.produceValue("abc");

    Wait.forEventsPropagation(scheduler);
    subjects.get("abc").assertSubscribesAndClear(1);
    subjects.get("abc").assertUnsubscribesAndClear(0);

    kSource.invalidateCurrent();

    Wait.forEventsPropagation(scheduler);
    subjects.get("abc").assertUnsubscribesAndClear(1);
    subjects.get("def").assertSubscribesAndClear(0);

    kSource.produceValue("def");

    Wait.forEventsPropagation(scheduler);
    subjects.get("def").assertSubscribesAndClear(1);

    tester.assertNoValues();

    subjects.get("def").onNext("result");

    Wait.forEventsPropagation(scheduler);
    tester.assertValue("result");
    subjects.get("def").assertUnsubscribesAndClear(1);
  }
}
