/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import io.reactivex.schedulers.TestScheduler;

public class Wait {
  private Wait() {
    // Private constructor to prevent instantiation of utility-method class
  }

  /**
   * TL;DR - invoke this method before you make any assertion on result of Kickable actions.
   *
   * <p>This helper method is intended to make tests of Kickable instances consistent.
   *
   * <p>The Kickables are mutli-threaded now, which does not play nicely with a single thraded
   * JUnit. Operations performed on Kickables can result in code execution on:
   *
   * <p>1) Kickable internal graph maintenance thread.
   *
   * <p>2) In whatever thread created/provided by a scheduler passed into an invoked operator.
   *
   * <p>So, to make a test consistent, we must be able to make sure that both of the above two
   * execution paths have settled before we make any assertion.
   *
   * <p>Luckily, RX has a type of scheduler that is built to be triggerable by a method call, the
   * TestScheduler. And Kickable's eventLoop (that wraps the graph maintenance thread) was designed
   * to be flushable.
   *
   * <p>The event loop's flush method acts as a barrier that exits when the internal queues are
   * empty. Additionally it returns a boolean saying whether any code was actually executed during
   * the wait. To know reliably that all of the in-flight events have settled we need to wait
   * repeatedly on both the test scheduler and the event loop, as events in scheduler can queue
   * execution on the graph maintenance loop and vice versa.
   *
   * <p>We start with the scheduler, and keep on triggering both the event loop's flush and the
   * scheduler until the graph maintenance loop reports that it had nothing more to run in the last
   * execution. If in the last flush nothing was executed it means that nothing could have been
   * scheduled to the test scheduler and thus all of the events have been successfully propagated.
   */
  public static void forEventsPropagation(TestScheduler scheduler) {
    scheduler.triggerActions();

    while (!KickableImpl.eventLoop.flush()) {
      scheduler.triggerActions();
    }
  }
}
