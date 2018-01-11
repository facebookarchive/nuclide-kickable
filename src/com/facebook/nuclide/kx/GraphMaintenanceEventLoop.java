/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import com.facebook.nuclide.logging.Log;
import java.util.LinkedList;

/**
 * This class implements an event loop to be used in a thread. It can accept schedule requests to be
 * run, and executes them in-order.
 *
 * <p>The requests can be scheduled from any thread, and will be processed by a single one.
 */
class GraphMaintenanceEventLoop implements Runnable {
  private static final Log log = Log.get();

  private final Object lock = new Object();
  private final LinkedList<Runnable> hiPriTasks = new LinkedList<>();
  private final LinkedList<Runnable> loPriTasks = new LinkedList<>();
  private boolean isRunning = false;

  @Override
  public void run() {
    while (true) {
      Runnable nextTask = nextTaskToRun();

      try {
        nextTask.run();
      } catch (Exception e) {
        log.severe(() -> "Uncaught exception in the event loop", e);

        // Things are seriously messed up -- terminate the process
        throw new Error("Uncaught exception in the event loop", e);
      }
    }
  }

  private Runnable nextTaskToRun() {
    synchronized (lock) {
      isRunning = false;
      lock.notifyAll();

      while (hiPriTasks.isEmpty() && loPriTasks.isEmpty()) {
        try {
          lock.wait();
        } catch (InterruptedException e) {
          // Terminate the process
          throw new Error("Kickable graph maintenance thread got interrupted");
        }
      }

      isRunning = true;
      Runnable taskToRun =
          !hiPriTasks.isEmpty() ? hiPriTasks.removeFirst() : loPriTasks.removeFirst();
      lock.notifyAll();
      return taskToRun;
    }
  }

  public void scheduleHiPri(Runnable task) {
    synchronized (lock) {
      hiPriTasks.addLast(task);
      lock.notifyAll();
    }
  }

  public void scheduleLoPri(Runnable task) {
    synchronized (lock) {
      loPriTasks.addLast(task);
      lock.notifyAll();
    }
  }

  public boolean flush() {
    synchronized (lock) {
      if (hiPriTasks.isEmpty() && loPriTasks.isEmpty() && !isRunning) {
        return true;
      }

      while (!hiPriTasks.isEmpty() || !loPriTasks.isEmpty() || isRunning) {
        try {
          lock.wait();
        } catch (InterruptedException e) {
          // Terminate the process
          throw new Error("Kickable graph maintenance thread got interrupted");
        }
      }

      return false;
    }
  }
}
