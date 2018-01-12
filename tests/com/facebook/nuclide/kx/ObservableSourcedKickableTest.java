/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.TestScheduler;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import org.junit.Test;

public class ObservableSourcedKickableTest {

  @Test
  public void testNoKickValueUntilSourceEmits() throws InterruptedException {
    Subject<String> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    ObservableSourcedKickable<String> tested = new ObservableSourcedKickable<>(source, scheduler);

    TestObserver<String> kick1Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertNoValues();
    kick1Subscriber.assertNotComplete();

    source.onNext("abc");
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertValue("abc");
    kick1Subscriber.assertNotComplete();

    source.onNext("def");
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertComplete();

    TestObserver<String> kick2Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    kick2Subscriber.assertValue("def");
    kick2Subscriber.assertNotComplete();
  }

  @Test
  public void testKicksDoNotCompleteUntilNextValue() throws InterruptedException {
    Subject<String> source = BehaviorSubject.createDefault("abc");
    TestScheduler scheduler = new TestScheduler();
    ObservableSourcedKickable<String> tested = new ObservableSourcedKickable<>(source, scheduler);

    TestObserver<String> kick1Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertValue("abc");
    kick1Subscriber.assertNotComplete();

    source.onNext("def");
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertComplete();

    TestObserver<String> kick2Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    kick2Subscriber.assertValue("def");
    kick2Subscriber.assertNotComplete();
  }

  @Test
  public void testValuesArrivingOutsideOfSubscriptionAreStillCached() throws InterruptedException {
    PublishSubject<String> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    ObservableSourcedKickable<String> tested = new ObservableSourcedKickable<>(source, scheduler);
    Wait.forEventsPropagation(scheduler);

    source.onNext("abc");
    Wait.forEventsPropagation(scheduler);

    TestObserver<String> tester = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    tester.assertValue("abc");
    tester.assertNotComplete();

    source.onNext("ghi");
    source.onNext("jkl");
    Wait.forEventsPropagation(scheduler);

    tester = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    tester.assertValue("jkl");
    tester.assertNotComplete();

    source.onComplete();
    Wait.forEventsPropagation(scheduler);

    tester.assertComplete();
  }
}
