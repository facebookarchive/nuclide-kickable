/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import com.facebook.nuclide.kx.Kickable;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public abstract class KickableResolver<K, T> {
  private final Cache<K, Kickable<T>> cache = CacheBuilder.newBuilder().weakValues().build();
  private final Optional<? extends ResolverTimer> timer;

  protected KickableResolver() {
    this(Optional.empty());
  }

  protected KickableResolver(Optional<? extends ResolverTimer> timer) {
    this.timer = timer;
  }

  protected abstract Kickable<T> build(K key);

  public Kickable<T> resolve(K key) {
    if (key == null) {
      throw new IllegalArgumentException("Trying to resolve a null key!");
    }
    try {
      return cache.get(
          key,
          () -> {
            Kickable<T> built =
                build(key).setDebugKey(this.getClass().getSimpleName() + " " + keyToString(key));
            Kickable<T> kickable = timer.map(t -> t.timeKickable(built)).orElseGet(() -> built);
            return kickable.doFinally(() -> cache.invalidate(key));
          });
    } catch (ExecutionException e) {
      throw new IllegalArgumentException("Failed to resolve the key " + key.toString(), e);
    }
  }

  protected String keyToString(K key) {
    return key.toString();
  }

  protected Optional<K> stringToKey(String strKey) {
    return Optional.empty();
  }

  public ResolverMXBean createBean() {
    return BeanBuilder.buildForCache(cache, this::keyToString, this::stringToKey);
  }
}
