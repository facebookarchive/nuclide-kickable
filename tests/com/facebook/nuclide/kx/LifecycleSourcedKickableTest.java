/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.observers.TestObserver;
import io.reactivex.schedulers.TestScheduler;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import java.util.Optional;
import org.junit.Test;

public class LifecycleSourcedKickableTest {

  @Test
  public void testNoKickValueUntilSourceEmits() {
    Subject<Optional<String>> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested = new LifecycleSourcedKickable<>(source, scheduler);

    TestObserver<String> kick1Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertNoValues();
    kick1Subscriber.assertNotComplete();

    source.onNext(Optional.of("abc"));
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertValue("abc");
    kick1Subscriber.assertNotComplete();
  }

  @Test
  public void testInvalidationSignalInvalidates() {
    Subject<Optional<String>> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested = new LifecycleSourcedKickable<>(source, scheduler);

    TestObserver<String> kick1Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    source.onNext(Optional.of("abc"));
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertNotComplete();

    source.onNext(Optional.empty());
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertComplete();
  }

  @Test
  public void testNextValueInvalidates() {
    Subject<Optional<String>> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested = new LifecycleSourcedKickable<>(source, scheduler);

    TestObserver<String> kick1Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    source.onNext(Optional.of("abc"));
    Wait.forEventsPropagation(scheduler);
    kick1Subscriber.assertNotComplete();

    source.onNext(Optional.of("def"));
    Wait.forEventsPropagation(scheduler);

    kick1Subscriber.assertComplete();
  }

  @Test
  public void testNextValuePropagatesAndIsCached() {
    Subject<Optional<String>> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested = new LifecycleSourcedKickable<>(source, scheduler);

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    source.onNext(Optional.of("abc"));
    source.onNext(Optional.of("def"));

    TestObserver<String> kickSubscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);
    kickSubscriber.assertValue("def");
    kickSubscriber.assertNotComplete();
  }

  @Test
  public void testNotSubscribedUntilKick() {
    MonitoredSubject<Optional<String>> monitor = new MonitoredSubject<>();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested =
        new LifecycleSourcedKickable<>(monitor.observe(), scheduler);
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(0);

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(1);
  }

  @Test
  public void testInvalidationUnsubscribes() {
    MonitoredSubject<Optional<String>> monitor = new MonitoredSubject<>();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested =
        new LifecycleSourcedKickable<>(monitor.observe(), scheduler);

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(1);

    monitor.onNext(Optional.of("abc"));
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(0); // No new subscriptions were made
    monitor.assertUnsubscribesAndClear(0); // No one has unsubscribed yet

    monitor.onNext(Optional.empty());
    Wait.forEventsPropagation(scheduler);
    monitor.assertSubscribesAndClear(0); // No new subscriptions were made
    monitor.assertUnsubscribesAndClear(1); // The one subscription we had before unsubscribed
  }

  @Test
  public void testValueAfterInvalidation() {
    Subject<Optional<String>> source = PublishSubject.create();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested = new LifecycleSourcedKickable<>(source, scheduler);

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);
    source.onNext(Optional.of("abc"));
    source.onNext(Optional.empty());

    TestObserver<String> kick1Subscriber = tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    KickableInspector<String> inspector = new KickableInspector<>(tested, scheduler);
    inspector.assertInvalidated();

    source.onNext(Optional.of("def"));
    inspector.assertNotInvalidated();
    kick1Subscriber.assertValue("def");
  }

  @Test
  public void multipleSubscribersDoNotAffectUpstream() {
    MonitoredSubject<Optional<String>> monitor = new MonitoredSubject<>();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested =
        new LifecycleSourcedKickable<>(monitor.observe(), scheduler);

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(1);

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(0);

    monitor.onNext(Optional.of("abc"));
    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);
    monitor.assertUnsubscribesAndClear(0);
  }

  @Test
  public void testBeingSubscribedToCompletionSignalDoesNotKeepValuesSubscription() {
    MonitoredSubject<Optional<String>> monitor = new MonitoredSubject<>();
    TestScheduler scheduler = new TestScheduler();
    LifecycleSourcedKickable<String> tested =
        new LifecycleSourcedKickable<>(monitor.observe(), scheduler);
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(0);
    KickableInspector<String> closeTester = new KickableInspector<>(tested, scheduler);
    closeTester.assertNotCompleted();

    tested.kick(scheduler).test();
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(1);
    monitor.assertUnsubscribesAndClear(0);

    monitor.onNext(Optional.of("abc"));
    Wait.forEventsPropagation(scheduler);

    monitor.assertSubscribesAndClear(0);
    monitor.assertUnsubscribesAndClear(0);

    monitor.onNext(Optional.empty());
    Wait.forEventsPropagation(scheduler);
    monitor.assertSubscribesAndClear(0);
    monitor.assertUnsubscribesAndClear(1);
  }
}
