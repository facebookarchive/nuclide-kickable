/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import com.facebook.nuclide.logging.Log;
import com.google.common.base.Preconditions;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import io.reactivex.functions.Function3;
import io.reactivex.schedulers.Schedulers;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class Kickable<T> {
  private static final Log log = Log.get();

  private AtomicReference<String> debugKey;

  // This is the default scheduler that will be used in scheduler-accepting operators.
  // This scheduler runs on a private thread pool constructed below.
  //
  // A private thread pool is used rather than Schedulers.io() to limit the number of
  // the created threads, as Schedulers.io() is unbound and under certain conditions can easily
  // grow to thousands of threads.
  public static final Scheduler defaultScheduler;

  static {
    ThreadPoolExecutor defaultExecuter;
    AtomicInteger threadCounter = new AtomicInteger(0);
    // The size of the thread pool is quite arbitrary. The core idea is to give enough room for
    // IO parallelism, yet not grow in an unbound fashion.
    //
    // Note: a usual strategy of creating a thread pool of size == CPU count may not be the best
    // choice here, as we expect the tasks to be IO bound and not necessarily computationally
    // intensive.
    defaultExecuter =
        new ThreadPoolExecutor(10, 50, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    defaultExecuter.setThreadFactory(
        runnable -> {
          Thread t = new Thread(runnable);
          t.setDaemon(true);
          t.setName("GraphHandling-" + threadCounter.getAndIncrement());
          return t;
        });
    defaultScheduler = Schedulers.from(defaultExecuter);
  }

  protected Kickable() {
    // Just a semi-random key to help tell the Kickables apart in the logs, can be overriden
    int randomId = (int) (Math.random() * 1000000);
    debugKey = new AtomicReference<>(this.getClass().getSimpleName() + "(" + randomId + ")");
  }

  /**
   * Produces an observable that will emit single value of this Kickable (when it becomes available)
   * and will complete when the value is invalidated (or the Kickable completes).
   *
   * <p>The values are emitted on the default scheduler.
   *
   * <p>Kickable errors, if happen before the invalidation, will be propagated onto the resulting
   * stream.
   */
  public final Observable<T> kick() {
    return kick(defaultScheduler);
  }

  /**
   * Produces an observable that will emit single value of this Kickable (when it becomes available)
   * and will complete when the value is invalidated (or the Kickable completes).
   *
   * <p>The values are emitted on the given scheduler.
   *
   * <p>Kickable errors, if happen before the invalidation, will be propagated onto the resulting
   * stream.
   */
  public abstract Observable<T> kick(Scheduler scheduler);

  /**
   * Kicks repeatedly and emits non-empty Optional value when the underlying Kickable produces value
   * and an empty Optional when it invalidates.
   *
   * <p>Will complete when the underlying Kickable completes and and will propagate its errors on
   * the resulting observable
   *
   * <p>Operates on the default scheduler
   */
  public final Observable<Optional<T>> autoKick() {
    return autoKick(defaultScheduler);
  }

  /**
   * Kicks repeatedly and emits non-empty Optional value when the underlying Kickable produces value
   * and an empty Optional when it invalidates.
   *
   * <p>Will complete when the underlying Kickable completes and and will propagate its errors on
   * the resulting observable
   *
   * <p>Operates on the given scheduler
   */
  public abstract Observable<Optional<T>> autoKick(Scheduler scheduler);

  public static <T> Observable<Optional<T>> autoKick(Kickable<T> kickable) {
    return kickable.autoKick(defaultScheduler);
  }

  public static <T> Observable<Optional<T>> autoKick(Kickable<T> kickable, Scheduler scheduler) {
    return kickable.autoKick(scheduler);
  }

  /**
   * Asynchronously maps values of the source Kickable into the result provided by the function.
   *
   * <p>The asynchrony comes from the usage of the Single class. If source Kickable invalidates or
   * produces a new value before the single from a previous iteration has finished its work it (the
   * previous Single) will be unsubscribed. Hence the code using this function is encouraged, but
   * not required, to make the produced Single instances cancelable on unsubscription.
   *
   * <p>The errors emitted on both the source Kickable and the mapped Singles are propagated to the
   * resulting Kickable.
   *
   * <p>This Kickable completes when its source completes.
   *
   * <p>The invocation of the given mapping function and subscription to the result is performed on
   * the default scheduler. The results are not observed on any specific scheduler.
   */
  public final <S> Kickable<S> map(Function<? super T, Single<S>> func) {
    return map(func, defaultScheduler);
  }

  /**
   * Asynchronously maps values of the source Kickable into the result provided by the function.
   *
   * <p>The asynchrony comes from the usage of the Single class. If source Kickable invalidates or
   * produces a new value before the single from a previous iteration has finished its work it (the
   * previous Single) will be unsubscribed. Hence the code using this function is encouraged, but
   * not required, to make the produced Single instances cancelable on unsubscription.
   *
   * <p>The errors emitted on both the source Kickable and the mapped Singles are propagated to the
   * resulting Kickable.
   *
   * <p>This Kickable completes when its source completes.
   *
   * <p>The invocation of the given mapping function and subscription to the result is performed on
   * the given scheduler. The results are not observed on any specific scheduler.
   */
  public abstract <S> Kickable<S> map(Function<? super T, Single<S>> func, Scheduler scheduler);

  /**
   * Quite similar to RX switchMap operator this one returns a Kickable that maps a value to another
   * Kickable instance and emits values produced by the mapped Kickable.
   *
   * <p>The errors from either the source or the currently active mapped Kickable propagate as
   * expected. But, the completion signal only propagates from the source Kickable. Completion of a
   * mapped Kickable is only treated as an invalidation signal. And mapping function will be
   * reinvoked should a downstream subscribe after such an event.
   *
   * <p>The mapping function is invoked on the default scheduler
   */
  public final <S> Kickable<S> switchMap(Function<? super T, Kickable<S>> func) {
    return switchMap(func, defaultScheduler);
  }

  /**
   * Quite similar to RX switchMap operator this one returns a Kickable that maps a value to another
   * Kickable instance and emits values produced by the mapped Kickable.
   *
   * <p>The errors from either the source or the currently active mapped Kickable propagate as
   * expected. But, the completion signal only propagates from the source Kickable. Completion of a
   * mapped Kickable is only treated as an invalidation signal. And mapping function will be
   * reinvoked should a downstream subscribe after such an event.
   *
   * <p>The mapping function is invoked on the given scheduler
   */
  public abstract <S> Kickable<S> switchMap(
      Function<? super T, Kickable<S>> func, Scheduler scheduler);

  /**
   * Produces that has a certain order of preference between its sources. The source (preferTo is
   * invoked on) is considere the most preferred one, but when its value is not available values of
   * other given Kickables will be considered in the order they appear.
   *
   * <p>The resulting Kickable will invalidate if none of the sources have a currenly valid value.
   *
   * <p>Should any of the sources err or complete the error or completion will be propagated to the
   * resulting Kickable.
   *
   * <p>This operator does not require any scheduler to operate
   */
  @SafeVarargs
  @SuppressWarnings("unchecked")
  public final Kickable<T> preferTo(Kickable<T>... others) {
    KickableImpl<T, ?>[] castOthers =
        Stream.of(others).map(other -> (KickableImpl<T, ?>) other).toArray(KickableImpl[]::new);

    return new PreferringKickable<>((KickableImpl<T, ?>) this, castOthers);
  }

  public Kickable<T> attachListener(KickableEventListener<? super T> listener) {
    return attachListener(listener, Schedulers.trampoline());
  }

  /**
   * Returns a Kickable that will (opportunistically) invoke callback methods of the given listener
   * as its internal state changes.
   *
   * <p>The invocation is performed on the given scheduler
   *
   * @see NotifyingKickable for more details.
   */
  public abstract Kickable<T> attachListener(
      KickableEventListener<? super T> listener, Scheduler scheduler);

  /**
   * Invokes the given action when the source Kickable either completes or errs. The invocation is
   * performed on the default scheduler.
   */
  public final Kickable<T> doFinally(Runnable action) {
    return doFinally(action, Schedulers.trampoline());
  }

  /**
   * Invokes the given action when the source Kickable either completes or errs. The invocation is
   * performed on the default scheduler.
   */
  public final Kickable<T> doFinally(Runnable action, Scheduler scheduler) {
    return attachListener(
        new KickableEventListener<T>() {
          @Override
          public void onError(Exception error) {
            action.run();
          }

          @Override
          public void onComplete() {
            action.run();
          }
        },
        scheduler);
  }

  /**
   * A debug utility that will produce log messages corresponding to the inner state transitions of
   * the (returned) Kickable instance.
   *
   * <p>The returned instance proxies the data coming from the source Kickable and strives (but can
   * not guarantee) to mimic its state
   */
  public final Kickable<T> debugWrap() {
    String name = getDebugKey();
    log.info(() -> name + " created");
    return attachListener(
        new KickableEventListener<T>() {
          @Override
          public void onSubscribeByADownstream() {
            log.info(() -> name + " got subscribed");
          }

          @Override
          public void onUnsubscribeByDownstreams() {
            log.info(() -> name + " got unsubscribed");
          }

          @Override
          public void onProducedValue(T value) {
            log.info(() -> name + " produced a value");
          }

          @Override
          public void onInvalidated() {
            log.info(() -> name + " invalidated");
          }

          @Override
          public void onError(Exception error) {
            log.info(() -> name + " errored out");
            error.printStackTrace();
          }

          @Override
          public void onComplete() {
            log.info(() -> name + " completed");
          }
        });
  }

  public Kickable<T> setDebugKey(String key) {
    this.debugKey.set(key);
    return this;
  }

  public String getDebugKey() {
    return debugKey.get();
  }

  @Override
  public String toString() {
    return getDebugKey();
  }

  /**
   * Combines and asynchronously maps the values from the given sources. The mapping function acts
   * exactly as in a `.map` operator, but receiving multiple inputs.
   *
   * <p>The mapping is only invoked when all of the inputs have current, non-invalidated, values.
   *
   * <p>An invalidation by any of the sources is propagated as an invalidation to the resulting
   * Kickable
   *
   * <p>Errors and completion of any of the sources is propagated to the resulting Kickable as well.
   *
   * <p>The combination step does not require a scheduler and the mapping step is running on a
   * default scheduler.
   */
  public static <S1, S2, T> Kickable<T> combineLatest(
      Kickable<S1> s1, Kickable<S2> s2, BiFunction<? super S1, ? super S2, Single<T>> func) {
    return combineLatest(s1, s2, func, defaultScheduler);
  }

  /**
   * Combines and asynchronously maps the values from the given sources. The mapping function acts
   * exactly as in a `.map` operator, but receiving multiple inputs.
   *
   * <p>The mapping is only invoked when all of the inputs have current, non-invalidated, values.
   *
   * <p>An invalidation by any of the sources is propagated as an invalidation to the resulting
   * Kickable
   *
   * <p>Errors and completion of any of the sources is propagated to the resulting Kickable as well.
   *
   * <p>The combination step does not require a scheduler and the mapping step is running on the
   * given scheduler.
   */
  public static <S1, S2, T> Kickable<T> combineLatest(
      Kickable<S1> s1,
      Kickable<S2> s2,
      BiFunction<? super S1, ? super S2, Single<T>> func,
      Scheduler scheduler) {
    return new CombiningKickable(unsafeUpstreamsAsList(s1, s2))
        .map(
            lstValues -> {
              S1 arg1 = unsafeGet(lstValues, 0);
              S2 arg2 = unsafeGet(lstValues, 1);

              return func.apply(arg1, arg2);
            },
            scheduler);
  }

  /**
   * Combines and asynchronously maps the values from the given sources. The mapping function acts
   * exactly as in a `.map` operator, but receiving multiple inputs.
   *
   * <p>The mapping is only invoked when all of the inputs have current, non-invalidated, values.
   *
   * <p>An invalidation by any of the sources is propagated as an invalidation to the resulting
   * Kickable
   *
   * <p>Errors and completion of any of the sources is propagated to the resulting Kickable as well.
   *
   * <p>The combination step does not require a scheduler and the mapping step is running on a
   * default scheduler.
   */
  public static <S1, S2, S3, T> Kickable<T> combineLatest(
      Kickable<S1> s1,
      Kickable<S2> s2,
      Kickable<S3> s3,
      Function3<? super S1, ? super S2, ? super S3, Single<T>> func) {
    return combineLatest(s1, s2, s3, func, defaultScheduler);
  }

  /**
   * Combines and asynchronously maps the values from the given sources. The mapping function acts
   * exactly as in a `.map` operator, but receiving multiple inputs.
   *
   * <p>The mapping is only invoked when all of the inputs have current, non-invalidated, values.
   *
   * <p>An invalidation by any of the sources is propagated as an invalidation to the resulting
   * Kickable
   *
   * <p>Errors and completion of any of the sources is propagated to the resulting Kickable as well.
   *
   * <p>The combination step does not require a scheduler and the mapping step is running on the
   * given scheduler.
   */
  public static <S1, S2, S3, T> Kickable<T> combineLatest(
      Kickable<S1> s1,
      Kickable<S2> s2,
      Kickable<S3> s3,
      Function3<? super S1, ? super S2, ? super S3, Single<T>> func,
      Scheduler scheduler) {
    return new CombiningKickable(unsafeUpstreamsAsList(s1, s2, s3))
        .map(
            lstValues -> {
              S1 arg1 = unsafeGet(lstValues, 0);
              S2 arg2 = unsafeGet(lstValues, 1);
              S3 arg3 = unsafeGet(lstValues, 2);

              return func.apply(arg1, arg2, arg3);
            },
            scheduler);
  }

  /**
   * Combines and asynchronously maps the values from the list of the given sources. The mapping
   * function acts exactly as in a `.map` operator, but receiving a list of inputs. The items in the
   * values list will correspond to the positions of items in the given list of Kickables.
   *
   * <p>The mapping is only invoked when all of the inputs have current, non-invalidated, values.
   *
   * <p>An invalidation by any of the sources is propagated as an invalidation to the resulting
   * Kickable
   *
   * <p>Errors and completion of any of the sources is propagated to the resulting Kickable as well.
   *
   * <p>The combination step does not require a scheduler and the mapping step is running on a
   * default scheduler.
   */
  public static <S, T> Kickable<T> combineLatest(
      List<? extends Kickable<S>> source, Function<? super List<S>, Single<T>> func) {
    return combineLatest(source, func, defaultScheduler);
  }

  /**
   * Combines and asynchronously maps the values from the list of the given sources. The mapping
   * function acts exactly as in a `.map` operator, but receiving a list of inputs. The items in the
   * values list will correspond to the positions of items in the given list of Kickables.
   *
   * <p>The mapping is only invoked when all of the inputs have current, non-invalidated, values.
   *
   * <p>An invalidation by any of the sources is propagated as an invalidation to the resulting
   * Kickable
   *
   * <p>Errors and completion of any of the sources is propagated to the resulting Kickable as well.
   *
   * <p>The combination step does not require a scheduler and the mapping step is running on the
   * given scheduler.
   */
  @SuppressWarnings("unchecked")
  public static <S, T> Kickable<T> combineLatest(
      List<? extends Kickable<S>> source,
      Function<? super List<S>, Single<T>> func,
      Scheduler scheduler) {
    List<KickableImpl<?, ?>> upstreams =
        source.stream().map(Kickable::checkAndCast).collect(Collectors.toList());
    return new CombiningKickable(upstreams)
        .map(lstValues -> func.apply((List<S>) lstValues), scheduler);
  }

  /** Converts a varargs of Kickables into a list consumable by the CombiningKickable. */
  private static List<KickableImpl<?, ?>> unsafeUpstreamsAsList(Kickable<?>... upstreams) {
    return Stream.of(upstreams).map(Kickable::checkAndCast).collect(Collectors.toList());
  }

  protected static KickableImpl<?, ?> checkAndCast(Kickable<?> upstream) {
    Preconditions.checkArgument(
        upstream instanceof KickableImpl, "Received an invalid instance of Kickable");
    return (KickableImpl<?, ?>) upstream;
  }

  @SuppressWarnings("unchecked")
  private static <V> V unsafeGet(List<?> values, int index) {
    return (V) values.get(index);
  }

  /**
   * Subscribes to the given source (on a default scheduler) and gets the values that the source
   * produces.
   *
   * <p>After the first value will be received from the source the resulting Kickable will never be
   * invalidated (but each next value will replace the previous one).
   *
   * <p>Errors and completion from the source propagate respectively
   */
  public static <T> Kickable<T> from(Observable<T> source) {
    return from(source, defaultScheduler);
  }

  /**
   * Subscribes to the given source on the given scheduler and gets the values that the source
   * produces.
   *
   * <p>After the first value will be received from the source the resulting Kickable will never be
   * invalidated (but each next value will replace the previous one).
   *
   * <p>Errors and completion from the source propagate respectively
   */
  public static <T> Kickable<T> from(Observable<T> source, Scheduler scheduler) {
    return new ObservableSourcedKickable<>(source, scheduler);
  }

  /**
   * Creates a Kickable with value lifecycles mirroring those received on the given observable.
   *
   * <p>A lifecycle of a value is expressed through the Optional wrapper. An empty Optional is
   * treated as an invalidation signal, and non-empty values are, well, values. Errors and
   * completion signal propagate as usual.
   *
   * <p>This operator lazily(!) subscribes to the given source of lifecycles. I.e it's fine to use
   * COLD sources. The subscription will only be made when a downstream subscribes and will be
   * retained until an invalidation signal is heard.
   *
   * <p>The lifecycles source is subscribed on a default scheduler. The values are not observed on
   * any specific scheduler.
   */
  public static <T> Kickable<T> fromLifecycles(Observable<Optional<T>> lifecycles) {
    return fromLifecycles(lifecycles, defaultScheduler);
  }

  /**
   * Creates a Kickable with value lifecycles mirroring those received on the given observable.
   *
   * <p>A lifecycle of a value is expressed through the Optional wrapper. An empty Optional is
   * treated as an invalidation signal, and non-empty values are, well, values. Errors and
   * completion signal propagate as usual.
   *
   * <p>This operator lazily(!) subscribes to the given source of lifecycles. I.e it's fine to use
   * COLD sources. The subscription will only be made when a downstream subscribes and will be
   * retained until an invalidation signal is heard.
   *
   * <p>The lifecycles source is subscribed on the given scheduler. The values are not observed on
   * any specific scheduler.
   */
  public static <T> Kickable<T> fromLifecycles(
      Observable<Optional<T>> lifecycles, Scheduler scheduler) {
    return new LifecycleSourcedKickable<>(lifecycles, scheduler);
  }

  /**
   * Produces a Kickable that never completes or errs and has a constant value (the argument)
   *
   * <p>TODO: Consider renaming to "just()" to maintain more similarity to RXJava
   */
  public static <T> Kickable<T> ofValue(T value) {
    return new ValueKickable<>(value);
  }
}
