/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import java.util.List;

public interface ResolverMXBean {
  List<String> listAllKeys();

  int getKeyCount();

  void invalidateKey(String key);

  void invalidateAllKeys();
}
