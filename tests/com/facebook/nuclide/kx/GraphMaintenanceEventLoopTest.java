/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

public class GraphMaintenanceEventLoopTest {
  @Test
  public void testScheduledTaskIsInvoked() {
    AtomicBoolean loPriWasInvoked = new AtomicBoolean(false);
    runInEventLoop(loPri(() -> loPriWasInvoked.set(true)));

    Assert.assertTrue(loPriWasInvoked.get());

    AtomicBoolean hiPriWasInvoked = new AtomicBoolean(false);
    runInEventLoop(hiPri(() -> hiPriWasInvoked.set(true)));

    Assert.assertTrue(hiPriWasInvoked.get());
  }

  @Test
  public void testMultipleScheduledLoPriTasksArenvoked() {
    AtomicBoolean firstInvoked = new AtomicBoolean(false);
    AtomicBoolean secondInvoked = new AtomicBoolean(false);
    runInEventLoop(
        loPri(
            () -> {
              Assert.assertFalse(secondInvoked.get());
              firstInvoked.set(true);
            }),
        loPri(
            () -> {
              Assert.assertTrue(firstInvoked.get());
              secondInvoked.set(true);
            }));

    Assert.assertTrue(firstInvoked.get());
    Assert.assertTrue(secondInvoked.get());
  }

  @Test
  public void testMultipleScheduledHiPriTasksArenvoked() {
    AtomicBoolean firstInvoked = new AtomicBoolean(false);
    AtomicBoolean secondInvoked = new AtomicBoolean(false);
    runInEventLoop(
        hiPri(
            () -> {
              Assert.assertFalse(secondInvoked.get());
              firstInvoked.set(true);
            }),
        hiPri(
            () -> {
              Assert.assertTrue(firstInvoked.get());
              secondInvoked.set(true);
            }));

    Assert.assertTrue(firstInvoked.get());
    Assert.assertTrue(secondInvoked.get());
  }

  @Test
  public void testLoPriInvocationsPreserveOrder() {
    AtomicBoolean firstInvoked = new AtomicBoolean(false);
    AtomicBoolean secondInvoked = new AtomicBoolean(false);
    AtomicBoolean internalInvoked = new AtomicBoolean(false);

    runSafely(
        loop -> {
          loop.scheduleLoPri(
              () -> {
                Assert.assertFalse(secondInvoked.get());
                firstInvoked.set(true);

                // Schedule another task from the first invoked callback we expect this task to be
                // executed after the second task finishes.
                loop.scheduleLoPri(
                    () -> {
                      Assert.assertTrue(secondInvoked.get());
                      internalInvoked.set(true);
                    });
              });

          loop.scheduleLoPri(
              () -> {
                Assert.assertTrue(firstInvoked.get());
                Assert.assertFalse(internalInvoked.get());
                secondInvoked.set(true);
              });
        });
  }

  @Test
  public void testHiPriInvocationsPreserveOrder() {
    AtomicBoolean firstInvoked = new AtomicBoolean(false);
    AtomicBoolean secondInvoked = new AtomicBoolean(false);
    AtomicBoolean internalInvoked = new AtomicBoolean(false);

    runSafely(
        loop -> {
          loop.scheduleHiPri(
              () -> {
                Assert.assertFalse(secondInvoked.get());
                firstInvoked.set(true);

                // Schedule another task from the first invoked callback we expect this task to be
                // executed after the second task finishes.
                loop.scheduleHiPri(
                    () -> {
                      Assert.assertTrue(secondInvoked.get());
                      internalInvoked.set(true);
                    });
              });

          loop.scheduleHiPri(
              () -> {
                Assert.assertTrue(firstInvoked.get());
                Assert.assertFalse(internalInvoked.get());
                secondInvoked.set(true);
              });
        });
  }

  @Test
  public void testUncaughtExceptionsCauseErrors() {
    RuntimeException exceptionToThrow = new RuntimeException("JustSo");
    try {
      runInEventLoop(
          loPri(
              () -> {
                throw exceptionToThrow;
              }));
    } catch (Error e) {
      Assert.assertEquals(exceptionToThrow, e.getCause());
      return;
    }

    Assert.fail("Expected error was not thrown");
  }

  @Test
  public void testScheduledPriorityIsObeyed() {
    AtomicBoolean hiPriInvoked = new AtomicBoolean(false);
    AtomicBoolean loPriInvoked = new AtomicBoolean(false);

    runInEventLoop(
        loPri(
            () -> {
              Assert.assertTrue(hiPriInvoked.get());
              loPriInvoked.set(true);
            }),
        hiPri(
            () -> {
              Assert.assertFalse(loPriInvoked.get());
              hiPriInvoked.set(true);
            }));

    Assert.assertTrue(hiPriInvoked.get());
    Assert.assertTrue(loPriInvoked.get());
  }

  private void runInEventLoop(Runnable... runnables) {
    runSafely(
        loop -> {
          Stream.of(runnables).forEach(runnable -> scheduleByPri(loop, runnable));
        });
  }

  private void runSafely(Consumer<GraphMaintenanceEventLoop> codeToRun) {
    RuntimeException terminalException = new RuntimeException("Terminal exception");
    try {
      GraphMaintenanceEventLoop loop = new GraphMaintenanceEventLoop();
      codeToRun.accept(loop);

      loop.scheduleLoPri(
          () -> {
            throw terminalException;
          });

      loop.run();
    } catch (Error e) {
      if (e.getCause() != terminalException) {
        throw e;
      }
    }
  }

  // Wraps the given runnable in HiPri class, to be able to differentiate at runtime
  private Runnable hiPri(Runnable codeToRun) {
    return new HiPri() {
      @Override
      public void run() {
        codeToRun.run();
      }
    };
  }

  // Crate a non-hiPri task. Mostly for code readability purposes, as it does nothing
  private Runnable loPri(Runnable codeToRun) {
    return codeToRun;
  }

  private void scheduleByPri(GraphMaintenanceEventLoop loop, Runnable task) {
    if (task instanceof HiPri) {
      loop.scheduleHiPri(task);
    } else {
      loop.scheduleLoPri(task);
    }
  }

  private abstract class HiPri implements Runnable {}
}
