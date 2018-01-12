/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import io.reactivex.Maybe;
import java.util.Optional;

public interface CacheManager<K, S> {

  void updateCache(K key, S state);

  void clearCache(K key);

  Optional<S> loadCache(K key);

  Maybe<S> getCachedValue(K key);
}
