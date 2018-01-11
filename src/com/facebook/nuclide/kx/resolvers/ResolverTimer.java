/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import com.facebook.nuclide.kx.Kickable;

public interface ResolverTimer {

  <T> Kickable<T> timeKickable(Kickable<T> kickable);
}
