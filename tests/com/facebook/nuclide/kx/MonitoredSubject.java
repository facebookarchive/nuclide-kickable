/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import org.junit.Assert;

public class MonitoredSubject<T> {
  private int valueCounter = 0;
  private int subscribeCounter = 0;
  private int unsubscribeCounter = 0;
  private final PublishSubject<T> base = PublishSubject.create();

  public Observable<T> observe() {
    return base.doOnNext(val -> valueCounter++)
        .doOnSubscribe(disposable -> subscribeCounter++)
        .doOnDispose(() -> unsubscribeCounter++);
  }

  public void onNext(T value) {
    base.onNext(value);
  }

  public void onComplete() {
    base.onComplete();
  }

  public void assertValuesAndClear(int count) {
    Assert.assertEquals("Value count", count, valueCounter);
    valueCounter = 0;
  }

  public void assertSubscribesAndClear(int count) {
    Assert.assertEquals("Subscribe count", count, subscribeCounter);
    subscribeCounter = 0;
  }

  public void assertUnsubscribesAndClear(int count) {
    Assert.assertEquals("Unsubscribe count", count, unsubscribeCounter);
    unsubscribeCounter = 0;
  }

  public void assertAllAndClear(int values, int subscribes, int unsubscribes) {
    assertValuesAndClear(values);
    assertSubscribesAndClear(subscribes);
    assertUnsubscribesAndClear(unsubscribes);
  }
}
