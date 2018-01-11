/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import com.google.common.cache.Cache;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BeanBuilder {
  public static <K, R> Function<K, Optional<R>> optionalize(Function<K, R> resolver) {
    return value -> Optional.of(resolver.apply(value));
  }

  public static <K> ResolverMXBean buildForCache(Cache<K, ?> cache) {
    return buildForCache(cache, Object::toString, key -> Optional.empty());
  }

  public static <K> ResolverMXBean buildForCache(
      Cache<K, ?> cache,
      Function<K, String> keyToString,
      Function<String, Optional<K>> stringToKey) {
    return new ResolverMXBean() {

      @Override
      public List<String> listAllKeys() {
        return cache.asMap().keySet().stream().map(keyToString).collect(Collectors.toList());
      }

      @Override
      public void invalidateKey(String strKey) {
        stringToKey.apply(strKey).ifPresent(cache::invalidate);
      }

      @Override
      public void invalidateAllKeys() {
        cache.invalidateAll();
      }

      @Override
      public int getKeyCount() {
        return (int) cache.size();
      }
    };
  }
}
