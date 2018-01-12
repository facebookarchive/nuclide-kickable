/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

public interface KickableEventListener<T> {
  default void onSubscribeByADownstream() {}

  default void onUnsubscribeByDownstreams() {}

  default void onProducedValue(T value) {}

  default void onInvalidated() {}

  default void onError(Exception error) {}

  default void onComplete() {}
}
