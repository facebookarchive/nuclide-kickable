/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import com.facebook.nuclide.kx.Kickable;
import com.facebook.nuclide.kx.MonitoredSubject;
import com.facebook.nuclide.kx.Wait;
import io.reactivex.Completable;
import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.TestScheduler;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Assert;
import org.junit.Test;

public class StateBackedResolverTest {
  @Test
  public void testNoValueUntilAnUpdate() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    TestObserver<String> test = resolver.resolve("abc", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    resolver.assertFetchesAndClear(1);
    test.assertNoValues();

    resolver.update("abc", "def");

    Wait.forEventsPropagation(scheduler);
    test.assertValue("def");
  }

  @Test
  public void testNoFetchOnUpdateBeforeResolve() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");
    TestObserver<String> test = resolver.resolve("abc", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertValue("def");
    resolver.assertFetchesAndClear(0);
  }

  @Test
  public void testInvalidation() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");
    TestObserver<String> test = resolver.resolve("abc", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertNotComplete();

    resolver.invalidate("abc");

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();
  }

  @Test
  public void testInvalidationByValueUpdate() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");

    Kickable<String> kickable = resolver.resolve("abc", scheduler);
    TestObserver<String> test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertNotComplete();

    resolver.update("abc", "ghi");

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();

    test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertValue("ghi");
  }

  @Test
  public void testEqualsValueDoesNotInvalidate() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");

    Kickable<String> kickable = resolver.resolve("abc", scheduler);
    TestObserver<String> test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertNotComplete();

    resolver.update("abc", new String("def"));

    Wait.forEventsPropagation(scheduler);
    test.assertNotComplete();
  }

  @Test
  public void testUnsubscriptionFromFetchUponValue() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();
    MonitoredSubject<Void> subj = new MonitoredSubject<>();
    resolver.useOnNextFetch(subj.observe().ignoreElements());

    TestObserver<String> test = resolver.resolve("abc", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    subj.assertSubscribesAndClear(1);
    subj.assertUnsubscribesAndClear(0);
    resolver.update("abc", "def");

    Wait.forEventsPropagation(scheduler);
    subj.assertSubscribesAndClear(0);
    subj.assertUnsubscribesAndClear(1);
    test.assertNotComplete();

    resolver.invalidate("abc");

    Wait.forEventsPropagation(scheduler);
    subj.assertSubscribesAndClear(0);
    subj.assertUnsubscribesAndClear(0);
  }

  @Test
  public void testKickAfterInvalidationRetriggersFetch() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");
    Kickable<String> kickable = resolver.resolve("abc", scheduler);
    TestObserver<String> test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    resolver.assertFetchesAndClear(0);

    resolver.invalidate("abc");

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();
    test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    resolver.assertFetchesAndClear(1);
  }

  @Test
  public void testTwoKicksDoNotTriggerExtraFetch() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    Kickable<String> kickable = resolver.resolve("abc", scheduler);
    kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    resolver.assertFetchesAndClear(1);
    kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    resolver.assertFetchesAndClear(0);
  }

  @Test
  public void testCompletion() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();
    AtomicInteger completeCounter = new AtomicInteger(0);

    resolver.update("abc", "def");
    Kickable<String> kickable = resolver.resolve("abc", scheduler);
    TestObserver<String> test =
        kickable.doFinally(completeCounter::incrementAndGet).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertNotComplete();
    Assert.assertEquals(0, completeCounter.get());

    resolver.complete("abc");

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();
    Assert.assertEquals(1, completeCounter.get());

    test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();
  }

  @Test
  public void testResolveAfterCompletion() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");
    Kickable<String> kickable = resolver.resolve("abc", scheduler);

    TestObserver<String> test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertNotComplete();

    resolver.complete("abc");

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();
    resolver.update("abc", "ghi");

    test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertComplete();

    kickable = resolver.resolve("abc", scheduler);
    test = kickable.kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    test.assertValue("ghi");
  }

  @Test
  public void testConditionalUpdate() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("abc", "def");

    AtomicBoolean calbackInvoked = new AtomicBoolean(false);

    resolver.updateIncremental(
        "abc",
        state -> {
          Assert.assertTrue(state.isPresent());
          Assert.assertEquals("def", state.get());
          calbackInvoked.set(true);
          return "ghi";
        });

    TestObserver<String> test = resolver.resolve("abc", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    Assert.assertTrue(calbackInvoked.get());
    test.assertValue("ghi");
  }

  @org.junit.Ignore
  @Test
  public void testInvalidateAll() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("a", "def");
    resolver.update("b", "ghi");

    TestObserver<String> testA = resolver.resolve("a", scheduler).kick(scheduler).test();
    TestObserver<String> testB = resolver.resolve("b", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    testA.assertNotComplete();
    testB.assertNotComplete();

    resolver.invalidateAll(
        (key, state) -> {
          if (key.equals("a")) {
            Assert.assertEquals("def", state);
            return true;
          }

          if (key.equals("b")) {
            Assert.assertEquals("ghi", state);
            return false;
          }

          Assert.fail("Unexpected key");
          return false;
        });

    Wait.forEventsPropagation(scheduler);
    testA.assertComplete();
    testB.assertNotComplete();
  }

  @Test
  public void testUpdateOneKeyDoesNotAffectTheOther() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    TestObserver<String> testA = resolver.resolve("a", scheduler).kick(scheduler).test();
    TestObserver<String> testB = resolver.resolve("b", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    testA.assertNoValues();
    testB.assertNoValues();

    resolver.update("a", "def");

    Wait.forEventsPropagation(scheduler);
    testA.assertValue("def");
    testB.assertNoValues();
    testA.assertNotComplete();
    testB.assertNotComplete();
  }

  @Test
  public void testInvalidaOneKeyDoesNotAffectTheOther() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("a", "def");
    resolver.update("b", "ghi");

    TestObserver<String> testA = resolver.resolve("a", scheduler).kick(scheduler).test();
    TestObserver<String> testB = resolver.resolve("b", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    testA.assertValue("def");
    testB.assertValue("ghi");
    testA.assertNotComplete();
    testB.assertNotComplete();

    resolver.invalidate("a");

    Wait.forEventsPropagation(scheduler);
    testA.assertComplete();
    testB.assertNotComplete();
  }

  @Test
  public void testCompleteOneKeyDoesNotAffectTheOther() {
    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    TestObserver<String> testA = resolver.resolve("a", scheduler).kick(scheduler).test();
    TestObserver<String> testB = resolver.resolve("b", scheduler).kick(scheduler).test();

    Wait.forEventsPropagation(scheduler);
    testA.assertNotComplete();
    testB.assertNotComplete();

    resolver.complete("a");

    Wait.forEventsPropagation(scheduler);
    testA.assertComplete();
    testB.assertNotComplete();
  }

  @Test
  public void testCompleteAllInvokesMapperWithCorrectValues() {
    AtomicBoolean aAsserted = new AtomicBoolean(false);
    AtomicBoolean bAsserted = new AtomicBoolean(false);
    AtomicBoolean cAsserted = new AtomicBoolean(false);
    AtomicBoolean dAsserted = new AtomicBoolean(false);
    AtomicBoolean eAsserted = new AtomicBoolean(false);
    Map<String, Consumer<Optional<String>>> keyAssertions = new HashMap<>();
    keyAssertions.put(
        "a",
        opVal -> {
          aAsserted.set(true);
          Assert.assertEquals("abc", opVal.get());
        });
    keyAssertions.put(
        "b",
        opVal -> {
          bAsserted.set(true);
          Assert.assertFalse(opVal.isPresent());
        });
    keyAssertions.put(
        "c",
        opVal -> {
          cAsserted.set(true);
          Assert.assertEquals("def", opVal.get());
        });
    keyAssertions.put(
        "d",
        opVal -> {
          dAsserted.set(true);
          Assert.assertEquals("ghi", opVal.get());
        });
    keyAssertions.put(
        "e",
        opVal -> {
          eAsserted.set(true);
          Assert.assertFalse(opVal.isPresent());
        });

    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("a", "abc");
    Wait.forEventsPropagation(scheduler);

    resolver.resolve("a", scheduler).kick(scheduler).test();
    resolver.resolve("b", scheduler).kick(scheduler).test();
    resolver.resolve("c", scheduler).kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    resolver.update("c", "def");
    resolver.update("d", "ghi");

    resolver.update("e", "jkl");
    resolver.invalidate("e");

    resolver.completeAll(
        (key, opVal) -> {
          keyAssertions.get(key).accept(opVal);
          return true;
        });

    Wait.forEventsPropagation(scheduler);
    Assert.assertTrue(aAsserted.get());
    Assert.assertTrue(bAsserted.get());
    Assert.assertTrue(cAsserted.get());
    Assert.assertTrue(dAsserted.get());
    Assert.assertTrue(eAsserted.get());
  }

  @Test
  public void testCompleteAllCompletesProperly() {
    Map<String, Boolean> keysToComplete = new HashMap<>();
    keysToComplete.put("a", true);
    keysToComplete.put("b", true);
    keysToComplete.put("c", false);

    TestResolver<String, String> resolver = new TestResolver<>();
    TestScheduler scheduler = new TestScheduler();

    resolver.update("a", "abc");

    AtomicBoolean aCompleted = new AtomicBoolean(false);
    AtomicBoolean bCompleted = new AtomicBoolean(false);
    AtomicBoolean cCompleted = new AtomicBoolean(false);

    TestObserver<String> testA =
        resolver
            .resolve("a", scheduler)
            .kick(scheduler)
            .doFinally(() -> aCompleted.set(true))
            .test();
    TestObserver<String> testB =
        resolver
            .resolve("b", scheduler)
            .kick(scheduler)
            .doFinally(() -> bCompleted.set(true))
            .test();
    TestObserver<String> testC =
        resolver
            .resolve("c", scheduler)
            .kick(scheduler)
            .doFinally(() -> cCompleted.set(true))
            .test();

    resolver.update("c", "def");

    Wait.forEventsPropagation(scheduler);
    testA.assertNotComplete();
    testB.assertNotComplete();
    testC.assertNotComplete();

    resolver.completeAll(
        (key, opVal) -> {
          return keysToComplete.get(key);
        });

    Wait.forEventsPropagation(scheduler);
    testA.assertComplete();
    testB.assertComplete();
    testC.assertNotComplete();

    Assert.assertTrue(aCompleted.get());
    Assert.assertTrue(bCompleted.get());
    Assert.assertFalse(cCompleted.get());
  }

  private static class TestResolver<K, T> extends StateBackedResolver<K, T, T> {
    private Completable nextFetch = Completable.never();
    private int fetchesDone = 0;

    public void useOnNextFetch(Completable nextFetch) {
      this.nextFetch = nextFetch;
    }

    @Override
    protected Completable fetchCallerHoldsLock(K key) {
      fetchesDone++;
      return nextFetch;
    }

    public void assertFetchesAndClear(int expected) {
      Assert.assertEquals(expected, fetchesDone);
      fetchesDone = 0;
    }

    @Override
    protected Kickable<T> buildFromStateCallerHoldsLock(K key, T state) {
      return Kickable.ofValue(state);
    }
  }
}
