/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.logging;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Provides the logging support for the entire app
 *
 * <p>The class is designed first and foremost to be convenient to use, then performant. These
 * priorities have shaped its API and made it somewhat different from "classic" logging libraries.
 *
 * <p>Log instances require minimal initialization. It's just a matter of:
 *
 * <pre>
 *   private static final Log log = Log.get();
 * </pre>
 *
 * NOTE: In most "classic" logging frameworks you'd have to supply the name of a class or a category
 * that owns the log instance, but here it is determined dynamically by examining the call stack.
 *
 * <p>The logging levels were chosen to be the same as in the standard `java.util.logging`.
 *
 * <p>One of the common performance costs of logging is associated with construction of the message
 * as such, to keep an app performant, a common pattern is to avoid a construction of a message
 * unless the currently configured logging level actually includes the level of the statement. I.e.
 * for a "FINEST" level, a log statement would typically be:
 *
 * <pre>
 * if (classicLogger.isLoggable(Level.FINEST)) {
 *   classicLogger.finest(constructLogMessage());
 * }
 * </pre>
 *
 * This, however, is too verbose, and discourages usage of logs. Instead this class receives an
 * instance of a supplier that is to construct a message, which, when using Java 8 lambdas, becomes
 *
 * <pre>
 *   log.finest(() -> constructLogMessage());
 * </pre>
 *
 * This approach is slightly more costly than the "classic" one, as we have to pay for the lambda
 * closure capture, but this is much cheaper than string copying around.
 *
 * <p>Additional convenience methods:
 *
 * <p>Various versions of `methodCall`. These shorthands are meant to easily log when a method is
 * called. To make the usage concise these logging methods will determine the name of the method
 * that was called automatically. Again, by inspecting the call stack.
 *
 * <p>The `iff()` method. Often you want to log only under certain conditions (such as only log
 * elements of a structure when it is not empty). Rather than write a verbose `if` statement just
 * for logs you can use the `iff()` like that:
 *
 * <pre>
 *    log.iff(conditionApplies).finest(() -> "Now that's interesting!");
 * </pre>
 */
public class Log {
  private static final Logger globalLogger = Logger.getGlobal();

  private static final NullLog nullLog = new NullLog();

  private final String sourceClassName;

  public Log(String sourceClassName) {
    this.sourceClassName = sourceClassName;
  }

  /**
   * Retrieves an instance of a logger which is automatically configured for the containing class
   * I.e, the name of the class will be deduced by inspecting the call stack and all logs performed
   * with this instance will contain it.
   */
  public static Log get() {
    // We know how the stack looks like, so we know exactly the amount of steps we need to take
    // back to determine the caller
    String callerClassName = Thread.currentThread().getStackTrace()[2].getClassName();
    return new Log(callerClassName);
  }

  /** Sets the minimal logging level to be written into a log file */
  public static void setLogLevel(Level level) {
    globalLogger.setLevel(level);
  }

  /**
   * DRAGONS AHEAD
   *
   * <p>This method analyzes the stack trace of the execution and returns a name of the method that
   * is a CONSTANT number of levels below. The envisioned usage is through the `log.methodCall()`
   * and alike.
   *
   * <p>Since the number of levels we look down in the stack is constant we must make sure that we
   * only invoke this method from the Log class' entry point methods, such as `methodCall()`,
   * `methodCall(Supplier<String>)` and alike, but NOT from methods that do something like
   * `methodCall() -- calls --> methodCall(Supplier<String>) -- calls --> getSourceMethodName()`
   */
  private String getSourceMethodName() {
    return Thread.currentThread().getStackTrace()[3].getMethodName();
  }

  /**
   * A convenience method to allow consise conditional logging statements. You can use this method
   * insted of an `if` statement if you only want to log a line under certain conditions.
   *
   * <p>For example:
   *
   * <pre>
   *   log.iff(!list.isEmpty()).finest(() -> "The list has some interesting elements!", list);
   * </pre>
   */
  public Log iff(boolean condition) {
    if (condition) {
      return this;
    }

    return nullLog;
  }

  protected void log(
      Level level,
      Supplier<String> messageSupplier,
      Optional<Throwable> thrown,
      Optional<String> sourceMethodName,
      Optional<Object[]> params) {
    if (globalLogger.isLoggable(level)) {
      LogRecord record = new LogRecord(level, messageSupplier.get());
      record.setSourceClassName(sourceClassName);
      thrown.ifPresent(record::setThrown);
      sourceMethodName.ifPresent(record::setSourceMethodName);
      params.ifPresent(record::setParameters);

      globalLogger.log(record);
    }
  }

  public void severe(Supplier<String> messageSupplier) {
    log(Level.SEVERE, messageSupplier, Optional.empty(), Optional.empty(), Optional.empty());
  }

  public void severe(Supplier<String> messageSupplier, Throwable thrown) {
    log(Level.SEVERE, messageSupplier, Optional.of(thrown), Optional.empty(), Optional.empty());
  }

  public void warning(Supplier<String> messageSupplier) {
    log(Level.WARNING, messageSupplier, Optional.empty(), Optional.empty(), Optional.empty());
  }

  public void warning(Supplier<String> messageSupplier, Throwable thrown) {
    log(Level.WARNING, messageSupplier, Optional.of(thrown), Optional.empty(), Optional.empty());
  }

  public void info(Supplier<String> messageSupplier) {
    log(Level.INFO, messageSupplier, Optional.empty(), Optional.empty(), Optional.empty());
  }

  public void fine(Supplier<String> messageSupplier) {
    log(Level.FINE, messageSupplier, Optional.empty(), Optional.empty(), Optional.empty());
  }

  public void finer(Supplier<String> messageSupplier, Object... params) {
    if (params.length == 0) {
      log(Level.FINER, messageSupplier, Optional.empty(), Optional.empty(), Optional.empty());
    } else {
      log(Level.FINER, messageSupplier, Optional.empty(), Optional.empty(), Optional.of(params));
    }
  }

  public void finest(Supplier<String> messageSupplier, Object... params) {
    if (params.length == 0) {
      log(Level.FINEST, messageSupplier, Optional.empty(), Optional.empty(), Optional.empty());
    } else {
      log(Level.FINEST, messageSupplier, Optional.empty(), Optional.empty(), Optional.of(params));
    }
  }

  /**
   * A convenience method to log an invocation of a method. The method name is determined
   * automatically by examining the call stack. The logging is performed with a `FINEST` level.
   */
  public void methodCall() {
    String sourceMethodName = getSourceMethodName();
    log(Level.FINEST, () -> "", Optional.empty(), Optional.of(sourceMethodName), Optional.empty());
  }

  /**
   * A convenience method to log an invocation of a method. The method name is determined
   * automatically by examining the call stack. The logging is performed with a `FINEST` level. The
   * method name is appended with a string produced by the given supplier
   */
  public void methodCall(Supplier<String> messageSupplier) {
    String sourceMethodName = getSourceMethodName();
    log(
        Level.FINEST,
        messageSupplier,
        Optional.empty(),
        Optional.of(sourceMethodName),
        Optional.empty());
  }

  /**
   * A convenience method to log an invocation of a method. The method name is determined
   * automatically by examining the call stack and logged with the given level.
   */
  public void methodCall(Level level) {
    String sourceMethodName = getSourceMethodName();
    log(level, () -> "", Optional.empty(), Optional.of(sourceMethodName), Optional.empty());
  }

  /**
   * A convenience method to log an invocation of a method. The method name is determined
   * automatically by examining the call stack. The message is apended with the message produced by
   * the supplier and the, logged at the given level. The optional additional params are serialized
   * with their a `toString` methods.
   */
  public void methodCall(Level level, Supplier<String> messageSupplier, Object... params) {
    String sourceMethodName = getSourceMethodName();
    if (params.length == 0) {
      log(
          level,
          messageSupplier,
          Optional.empty(),
          Optional.of(sourceMethodName),
          Optional.empty());
    } else {
      log(
          level,
          messageSupplier,
          Optional.empty(),
          Optional.of(sourceMethodName),
          Optional.of(params));
    }
  }

  private static class NullLog extends Log {
    public NullLog() {
      super("NullLog");
    }

    @Override
    protected void log(
        Level level,
        Supplier<String> messageSupplier,
        Optional<Throwable> thrown,
        Optional<String> sourceMethodName,
        Optional<Object[]> params) {}

    @Override
    public void severe(Supplier<String> messageSupplier, Throwable thrown) {}

    @Override
    public void severe(Supplier<String> messageSupplier) {}

    @Override
    public void warning(Supplier<String> messageSupplier, Throwable thrown) {}

    @Override
    public void warning(Supplier<String> messageSupplier) {}

    @Override
    public void info(Supplier<String> messageSupplier) {}

    @Override
    public void fine(Supplier<String> messageSupplier) {}

    @Override
    public void finer(Supplier<String> messageSupplier, Object... params) {}

    @Override
    public void finest(Supplier<String> messageSupplier, Object... params) {}

    @Override
    public void methodCall() {}

    @Override
    public void methodCall(Supplier<String> messageSupplier) {}

    @Override
    public void methodCall(Level level) {}

    @Override
    public void methodCall(Level level, Supplier<String> messageSupplier, Object... params) {}
  }
}
