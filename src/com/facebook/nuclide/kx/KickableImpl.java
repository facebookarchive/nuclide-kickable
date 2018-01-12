/*
 * Copyright (c) 2018-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import com.facebook.nuclide.logging.Log;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * This class contains the majority of the implementation for the Kickable concept. It contains all
 * of the infrastructure to make development of specialized Kickables easier and sets generic
 * patterns. This class is intentionally non-public. It's an implementation detail, its main
 * contract is towards the concrete levels and not user-facing.
 *
 * <p>Background:
 *
 * <p>A Kickable is an entity that provides access to a unit of managed state (here, of type T). The
 * granularity and type of this unit is completely up to the user to decide. Additionally each
 * Kickable instance can have a relation with other Kickable instances. These additional instances
 * are divided into two groups - upstream and downstream. The interaction between this instance, its
 * upstream instances and its downstream instances are part of a generic Kickable protocol. The end
 * goal of this interaction is to support building massive, scalable, highly dynamic and performant
 * applications.
 *
 * <p>The relations between Kickable instances must not form cycles. The upstream-downstream
 * relations are always symetric. I.e if instance A has instance B in its "upstreams", then instance
 * B has instance A in its "downstreams".
 *
 * <p>State & Threading:
 *
 * <p>Kickable instances are stateful. This, abstract, level defines the superclass for such state
 * the concrete implementations are expected to extend and expand it.
 *
 * <p>Each Kickable instance has two instances of the state at all times, "current" and "next".
 * Updates are written to the "next", batched and promoted in a centralized manner to "current" by
 * this abstract level.
 *
 * <p>Each concrete instance of Kickable CAN WRITE ONLY TO ITS OWN STATE. All cross-instance writing
 * are to be performed by the abstract level. A Kickable instance is allowed to read the comitted
 * state of its upstream instances. Additionally it can choose to update its own state sporadically,
 * based on external events, but such state updates must go through a mutating method defined on
 * this abstract level, the asynchronous `scheduleMutateState`. Any access to either its own and
 * other Kickables' state is SUBJECT TO SEVERE THREADING RESTRICTIONS and can only be performed from
 * the `graphMaintenanceThread` defined in this class.
 *
 * <p>An instance of state has an `isDirty` flag. Having this flag raised that one or more updates
 * were made to the "nextState" OR one of the upstreams has gone through a potentially important
 * update.
 *
 * <p>Each concrete level must impement a method `handleStateUpdate(prevState, nextState)` which
 * must handle the transition between one state to the other. "Handling" means making any kind of
 * modifications to the "nextState" and dispatching any kinds of events to the outside world.
 * handleStateUpdate is also the only way to clear the `isDirty` flag.
 *
 * <p>Note: `handleStateUpdate` runs in the graphMaintenanceThread, but any events dispatched from
 * it must NOT be handled in this thread. Any handling must switch threads so to not block the
 * `graphMaintenanceThread`.
 *
 * <p>Once a state was comitted to "current" it becomes "frozen" and can not be updated further. In
 * case of an update attempt an exception will be thrown.
 *
 * <p>value/error/isCompleted/isInvalidated:
 *
 * <p>Kickable's lifecycle has two terminal states:
 *
 * <p>1) The Kickable has completed ==> its state's `isCompleted` is set to true
 *
 * <p>2) The Kickable has erred ==> its state's `error` is set to non-null
 *
 * <p>The 1 & 2 are mutually exclusive. Kickable can't complete and then err or the other way
 * around.
 *
 * <p>When considering nullness of `value` and state of the `isInvalidated` the only invalid
 * combination is `value == null && isInvalidated == false`
 *
 * <p>Kickables, by default, start in a state of `value == null && isInvalidated == true`. Concrete
 * levels can set new, non-null value (which will set the `isInvalidated` to `false`) and can set
 * the `isInvalidated` to `true`.
 *
 * <p>When a value is set an equality with the previous value is performed. If the two are found to
 * be equal, the previous value is used instead of the new one (but the isInvalidated will be
 * cleared).
 *
 * <p>Kickables have a notion of an "upstream subscription". It means that this instance of Kickable
 * needs a value from one of its upstreams, which currently is in an invalidated state. This state
 * of being "subscribed" needs to be reaffirmed in each invocation of `handleStateUpdate`. The
 * upstream KickableImpl instances SHOUD take into account when there're downstreams subscribed to
 * them and take appropriate steps towards producing a value (and hence exiting the isInvalidated
 * state)
 *
 * <p>Notification about an update are sent to up/downstreams by setting their `isDirty` flag. It
 * too is only performed by this abstract level
 */
abstract class KickableImpl<T, S extends KickableImpl<T, S>.State> extends Kickable<T> {
  protected static final GraphMaintenanceEventLoop eventLoop;
  private static final Thread graphMaintenanceThread;

  static {
    eventLoop = new GraphMaintenanceEventLoop();
    graphMaintenanceThread = new Thread(eventLoop, "KickableImpl graph maintenance");
    graphMaintenanceThread.setDaemon(true);
    graphMaintenanceThread.start();
  }

  private static final Log log = Log.get();

  // Begin fields that are only be accessed from the state maintenance thread
  private S currentState;
  private S nextState;
  private boolean stateUpdateScheduled = false;
  // End fields that are only be accessed from the state maintenance thread

  /**
   * An abstract base class reparesenting a state of a concrete Kickable implementation. The
   * concrete level is expected to extend this class adding whatever fields/methods it requires.
   *
   * <p>A concrete level is encouraged to override and throw an exception in methods that are not to
   * be ever invoked on it. For example, a Kickable that may never have its upstream list modified
   * should throw an exception in the `setUpstreams` method.
   *
   * <p>Concrete level is also encouraged to call to the `prepareToUpdate()` method prior to making
   * any internal state changes.
   */
  abstract class State {
    // Settable by the concrete level.
    // Indicates that processing by both the abstract and the concrete level is needed
    // TODO: Consider renaming to something better, like "shouldProcess"
    protected boolean isDirty = false;

    // Not settable by the concrete level
    // Indicates that this state should not be modified (it was already comitted to the "current"
    // state)
    private boolean isFrozen = false;

    // Settable by the concrete level.
    // The current/last value of the Kickable instance. Initially null. Will not be null again until
    // the Kickable either completes or errs. Then will be nulled to prevent memory leaks.
    // TODO: Consider wrapping with an Optional (currently is nullable to be more memory efficient)
    private T value;

    // Settable by the concrete level.
    // An error, if any. A non-null indicates that the Kickable has erred and is in its terminal
    // state. Mutually exclusive with "Completed" state
    // TODO: Consider wrapping with an Optional (currently is nullable to be more memory efficient)
    private Exception error;

    // Settable by the concrete level.
    // Whether the Kickable has completed. Mutually exclusive with erred state.
    private boolean isCompleted;

    // Settable by the concrete level.
    // Is the Kickable currently invalidated. Initially true, but may be overridden by concrete
    // implementations.
    // Note: even when a Kickable is invalidated it may still have a value. That is to support
    // emitting referentially identical value if a newly calculated value is `.equals` to the
    // previously emitted one.
    private boolean isInvalidated;

    // Settable by the concrete level.
    // The upstreams the Kickable depends on. The concrete implementation is free to assign
    // semantics to the position. For example, in the implementation of `.prefer` operator the
    // position would mean the preference level. And in an operator such as `.combineLatest` the
    // position would affect the placement of a value in a resulting value list.
    private List<KickableImpl<?, ?>> upstreams;

    // Not settable by the concrete level.
    // The set of downstreams. It is set as part of the processing performed by this abstract level
    private Set<KickableImpl<?, ?>> downstreams;

    // Settable by the concrete level.
    // The set of upstreams this Kickable is currently pending on value from.
    // Needs to be reaffirmed (set) on every processing round by the concrete level in the
    // handleStateUpdate overridden method
    private Set<KickableImpl<?, ?>> upstreamSubscriptions;

    public State() {
      value = null;
      error = null;
      isCompleted = false;
      isInvalidated = true;
      upstreams = Collections.emptyList();
      downstreams = new HashSet<>();
      upstreamSubscriptions = new HashSet<>();
    }

    public State(State other) {
      other.isFrozen = true;
      value = other.value;
      error = other.error;
      isCompleted = other.isCompleted;
      isInvalidated = other.isInvalidated;
      upstreams = new ArrayList<>(other.upstreams);
      downstreams = new HashSet<>(other.downstreams);
      upstreamSubscriptions = new HashSet<>(other.upstreamSubscriptions);
    }

    public final boolean isDirty() {
      return isDirty;
    }

    /** Helper method to throw an exception if an update is currently prohibited */
    protected final void prepareToUpdate() {
      Preconditions.checkState(!isFrozen, "Can't modify a frozen state");
    }

    public void setDirty() {
      prepareToUpdate();
      isDirty = true;
    }

    public final T getValue() {
      return value;
    }

    @Override
    public abstract S clone();

    public final T setValue(T value) {
      prepareToUpdate();
      Preconditions.checkArgument(value != null, "Kickable values can not be nulls");

      if (isDone()) {
        return null;
      }

      boolean equalsToCurrent = value.equals(this.value);
      if (equalsToCurrent && !isInvalidated) {
        return this.value;
      }

      if (!equalsToCurrent) {
        this.value = value;
      }
      isInvalidated = false;
      isDirty = true;
      return this.value;
    }

    public final void setValueWithCheapEqualityCheck(T value) {
      prepareToUpdate();
      Preconditions.checkArgument(value != null, "Kickable values can not be nulls");

      if (isDone()) {
        return;
      }

      if (this.value == value && !isInvalidated) {
        return;
      }

      this.value = value;
      isInvalidated = false;
      isDirty = true;
    }

    public final Exception getError() {
      return this.error;
    }

    public void setError(Exception error) {
      prepareToUpdate();

      if (this.error != null || this.isCompleted) {
        return;
      }

      this.error = error;
      isDirty = true;
      doFinishingCleanup();
    }

    public final void setThrowable(Throwable error) {
      if (error instanceof Error) {
        // Rethrow the errors - we shouldn't be handling them
        throw (Error) error;
      }

      setError((Exception) error);
    }

    public final boolean isCompleted() {
      return isCompleted;
    }

    public final boolean isDone() {
      return isCompleted || error != null;
    }

    /**
     * Often, a concrete Kickable wants to propagate an error and completion from its upstream(s)
     * this is a helper method that's doing that.
     *
     * <p>The return is whether an error/completion were propagated (i.e. indicates that some
     * upstream has either erred or completed)
     */
    public final boolean propagateErrorAndCompletion() {
      boolean completed = false;
      for (KickableImpl<?, ?> upstream : upstreams) {
        if (upstream.isCompleted()) {
          completed = true;
        }

        Optional<Exception> upstreamError = upstream.getError();
        if (upstreamError.isPresent()) {
          setError(upstreamError.get());
          return true;
        }
      }

      if (completed) {
        setCompleted();
        return true;
      }

      return false;
    }

    /**
     * Propagates an error/completion from a given upstream only
     *
     * <p>The return is whether an error/completion were propagated (i.e. indicates that the given
     * upstream has either erred or completed)
     */
    public final boolean propagateErrorAndCompletion(KickableImpl<?, ?> upstream) {
      Optional<Exception> upstreamError = upstream.getError();
      if (upstreamError.isPresent()) {
        setError(upstreamError.get());
        return true;
      }

      if (upstream.isCompleted()) {
        setCompleted();
        return true;
      }

      return false;
    }

    /**
     * Often, a concrete Kickable wants to propagate the invalidation of its upstream(s) this is a
     * helper method that's doing that.
     *
     * <p>The return is whether an invalidation were propagated (i.e. indicates that some upstream
     * has invalidated)
     */
    public final boolean propagateInvalidation() {
      if (isInvalidated()) {
        return false;
      }

      for (KickableImpl<?, ?> upstream : upstreams) {
        if (upstream.isInvalidated()) {
          setIsInvalidated();
          return true;
        }
      }

      return false;
    }

    public void setCompleted() {
      prepareToUpdate();

      if (error != null || isCompleted) {
        return;
      }

      isCompleted = true;
      isDirty = true;
      doFinishingCleanup();
    }

    public final boolean isInvalidated() {
      return isInvalidated;
    }

    public void setIsInvalidated() {
      prepareToUpdate();

      if (error != null || isCompleted || isInvalidated) {
        return;
      }

      isInvalidated = true;
      isDirty = true;
    }

    public final List<KickableImpl<?, ?>> getUpstreams() {
      return this.upstreams;
    }

    public void setUpstreams(List<KickableImpl<?, ?>> upstreams) {
      prepareToUpdate();

      if (this.upstreams == upstreams) {
        return;
      }

      // If we're currently signalling an active subscription for an upstream that is no longer
      // in our list - unsubscribe
      Set<KickableImpl<?, ?>> upstreamsToUnsubscribe =
          Sets.difference(upstreamSubscriptions, new HashSet<>(upstreams));
      upstreamsToUnsubscribe.forEach(upstreamSubscriptions::remove);

      this.upstreams = upstreams;
      isDirty = true;
    }

    /** An unsafe (Type casting) method for getting an upstream with assumed type. */
    @SuppressWarnings("unchecked")
    public <V> KickableImpl<V, ?> getUpstreamUnsafe(int index) {
      Preconditions.checkArgument(
          upstreams.size() > index && index >= 0,
          "Requested an upstream with invalid index",
          index);
      return (KickableImpl<V, ?>) upstreams.get(index);
    }

    public final Set<KickableImpl<?, ?>> getDownstreams() {
      return this.downstreams;
    }

    /** Not to be called by a concrete level. */
    public void addDownstream(KickableImpl<?, ?> downstream) {
      prepareToUpdate();

      if (downstreams.add(downstream)) {
        isDirty = true;
      }
    }

    /** Not to be called by a concrete level. */
    public void removeDownstream(KickableImpl<?, ?> downstream) {
      prepareToUpdate();

      if (downstreams.remove(downstream)) {
        isDirty = true;
      }
    }

    /** Not to be called by a concrete level. */
    public final void clearDownstreams() {
      prepareToUpdate();

      if (downstreams.isEmpty()) {
        return;
      }

      downstreams.clear();
    }

    public void subsribeToUpstream(KickableImpl<?, ?> upstream) {
      prepareToUpdate();

      Preconditions.checkState(
          upstreams.contains(upstream), "Can only subscribe to a declared upstream");
      if (upstreamSubscriptions.add(upstream)) {
        isDirty = true;
      }
    }

    public void unsubscribeFromUpstream(KickableImpl<?, ?> upstream) {
      prepareToUpdate();

      if (upstreamSubscriptions.remove(upstream)) {
        isDirty = true;
      }
    }

    public Set<KickableImpl<?, ?>> getUpstreamSubscriptions() {
      return upstreamSubscriptions;
    }

    /**
     * A helper method that checks whether this KickableImpl instance is currently listed in the
     * upstreamSubscriptions of any of its downstreams
     */
    public final boolean isSubscribedByAnyone() {
      return downstreams
          .stream()
          .anyMatch(downstream -> downstream.isSubscribedTo(KickableImpl.this));
    }

    /**
     * A helper method that propagates the subscription status upwards. I.e. if this KickableImpl
     * instance is currently subscribed then it subscribes to its upstreams as well
     */
    public final void propagateSubscriptionToUpstreams() {
      prepareToUpdate();

      if (isSubscribedByAnyone()) {
        if (upstreamSubscriptions.addAll(upstreams)) {
          isDirty = true;
        }
      }
    }

    public final void clearUpstreamSubscriptions() {
      prepareToUpdate();

      if (upstreamSubscriptions.isEmpty()) {
        return;
      }

      upstreamSubscriptions.clear();
      isDirty = true;
    }

    /**
     * Does cleanup stuff when the Kickable is errorring out or completes. Concrete level MAY
     * override this method, but MUST call it from the overriding method.
     */
    protected void doFinishingCleanup() {
      // Do not prevent garbage collection of the value
      value = null;
      upstreams.clear();
      upstreamSubscriptions.clear();

      // Do not clear downstreams so we could send out notifications last time
    }
  }

  protected KickableImpl() {}

  /**
   * Initializes the Kickable state. We would really prefer to have this in the constructor, alas,
   * we can not do that -- java does not allow instantiation of non-static private classes during
   * "explicit" constructor invocations such as super(new State()) or this(new State()) as such
   * classes instantiations implicitly reference (not yet constructed) `this`. We can't even pass
   * into the constructor an instance factory function for the same reason, and we can't pass a
   * static factory function because then we'd loose all the type information.
   *
   * <p>Our only options are either:
   *
   * <p>1) Make the State static. But then the types will become significantly more cumbersome
   *
   * <p>2) Initialize the states after the construction with this initState call
   *
   * <p>The concrete level must call this method before issuing any `mutateState` calls. Preferably
   * in the constructor
   */
  protected final void initState(S initialState) {
    // The init state is usually invoked during a Kickable creation, which happens outside of the
    // maintenance thread. Hence it takes lower precendence.
    eventLoop.scheduleLoPri(
        () -> {
          currentState = initialState;
          nextState = currentState.clone();

          // Trigger state update in the concrete level. Useful for non-finished initializations
          mutateState(state -> state.setDirty());

          currentState
              .getUpstreams()
              .forEach(upstream -> upstream.mutateState(state -> state.addDownstream(this)));
        });
  }

  /** Throws an exception is the currently executing thread is not `graphMaintenanceThread` */
  protected final void verifyMaintenanceThread() throws IllegalThreadStateException {
    if (Thread.currentThread() != graphMaintenanceThread) {
      IllegalThreadStateException e =
          new IllegalThreadStateException(
              "Kickable internal operation was called from an incorrect thread");
      log.severe(() -> "Violation of threading invariants", e);
      throw e;
    }
  }

  /** Throws and exception if currently executing thread IS graphMaintenanceThread */
  protected final void anythingButMaintenanceThread() throws IllegalThreadStateException {
    if (Thread.currentThread() == graphMaintenanceThread) {
      IllegalThreadStateException e =
          new IllegalThreadStateException(
              "Kickable internal operation was called from an incorrect thread");
      log.severe(() -> "Violation of threading invariants", e);
      throw e;
    }
  }

  /**
   * Accepts a consumer that will get an instance of the state and perform some mutations to it. If
   * the current thread is `graphMaintenanceThread` the mutation will be performed immediately, if
   * it is not, the mutation will be scheduled for the future.
   *
   * <p>In the state update is not a NoOp (if isDirty flag is set post-update) a call to
   * `handleStateUpdate` will be scheduled if it's not scheduled yet due to some previous update.
   */
  protected final void scheduleMutateState(Consumer<? super S> mutator) {
    if (Thread.currentThread() == graphMaintenanceThread) {
      mutateState(mutator);
      return;
    }

    // We're running in a non-maintanence thread. This means that this is an outside update
    // it takes lower precendence than internal graph updates, hence scheduled as a lowPri
    eventLoop.scheduleLoPri(() -> mutateState(mutator));
  }

  private final void mutateState(Consumer<? super S> mutator) {
    verifyMaintenanceThread();
    try {
      mutator.accept(nextState);
    } catch (Exception e) {
      nextState.setError(e);
    }
    if (nextState.isDirty() && !stateUpdateScheduled) {

      stateUpdateScheduled = true;
      // We're scheduling an update from graph maintanance thread -- it takes precendence
      // over schedules requested from the outside, hence we're usign the hiPri mode
      eventLoop.scheduleHiPri(
          () -> {
            stateUpdateScheduled = false;
            performStateMaintenance();
          });
    }
  }

  private void performStateMaintenance() {
    // The state update handlers must reaffirm their interest in the upstream value on each cycle
    nextState.clearUpstreamSubscriptions();
    handleStateUpdate(currentState, nextState);
    handleStateUpdateInternal(currentState, nextState);
    currentState = nextState;
    nextState = currentState.clone();
  }

  /**
   * Performs a step in the state maintenance. Runs in the graphMaintenanceThread.
   *
   * <p>During the method invocation the updates to the "nextState" will be committed to become the
   * new "currentState". Concrete level will be given an opportunity to respond to changes just
   * prior to the commit.
   */
  private void handleStateUpdateInternal(S currentState, S nextState) {
    verifyMaintenanceThread();
    Set<KickableImpl<?, ?>> currentUpstreams = new HashSet<>(currentState.getUpstreams());
    Set<KickableImpl<?, ?>> nextUpstreams = new HashSet<>(nextState.getUpstreams());

    Set<KickableImpl<?, ?>> retainedUpstreams = Sets.intersection(currentUpstreams, nextUpstreams);
    Set<KickableImpl<?, ?>> newUpstreams = Sets.difference(nextUpstreams, currentUpstreams);
    Set<KickableImpl<?, ?>> removedUpstreams = Sets.difference(currentUpstreams, nextUpstreams);

    newUpstreams.forEach(
        newUpstream -> newUpstream.mutateState(state -> state.addDownstream(this)));
    log.iff(!newUpstreams.isEmpty())
        .finest(() -> getDebugKey() + " Added new upstreams", newUpstreams);

    removedUpstreams.forEach(
        droppedUpstream -> droppedUpstream.mutateState(state -> state.removeDownstream(this)));
    log.iff(!removedUpstreams.isEmpty())
        .finest(() -> getDebugKey() + " Removed upstreams", newUpstreams);

    // New upstreams will get state change notification from a downstream added to them
    // removed upstreams will get the notification from the downstream removed from them
    // but there're also upstreams which potentially had their subsribedTo status changed
    // because of the change in our `upstreamSubscriptions`, but they were not added/removed
    // we need to notify them explicitly
    Set<KickableImpl<?, ?>> newlySubscribedUpstreams =
        Sets.intersection(
            Sets.difference(
                nextState.getUpstreamSubscriptions(), currentState.getUpstreamSubscriptions()),
            retainedUpstreams);
    log.iff(!newlySubscribedUpstreams.isEmpty())
        .finest(() -> getDebugKey() + " Subscribed to", newlySubscribedUpstreams);
    Set<KickableImpl<?, ?>> unsubscribedUpstreams =
        Sets.intersection(
            Sets.difference(
                currentState.getUpstreamSubscriptions(), nextState.getUpstreamSubscriptions()),
            retainedUpstreams);
    log.iff(!unsubscribedUpstreams.isEmpty())
        .finest(() -> getDebugKey() + " Unsubscribed from", unsubscribedUpstreams);
    Set<KickableImpl<?, ?>> upstreamsWithAffectedSubscriptionStatus =
        Sets.union(newlySubscribedUpstreams, unsubscribedUpstreams);
    upstreamsWithAffectedSubscriptionStatus.forEach(
        upsteam -> upsteam.mutateState(state -> state.setDirty()));

    boolean errorHappened = currentState.getError() == null && nextState.getError() != null;
    boolean valueChanged = nextState.getValue() != currentState.getValue();
    boolean invalidationChanged = currentState.isInvalidated() != nextState.isInvalidated();
    boolean wasCompleted = !currentState.isCompleted() && nextState.isCompleted();

    boolean needToNotifyDownstreams =
        errorHappened || valueChanged || invalidationChanged || wasCompleted;
    log.iff(needToNotifyDownstreams)
        .finest(
            () ->
                getDebugKey()
                    + " Need to notify dowstreams errorHappened("
                    + errorHappened
                    + ") valueChanged("
                    + valueChanged
                    + ") invalidationChanged("
                    + invalidationChanged
                    + ") wasCompleted("
                    + wasCompleted
                    + ")");
    if (needToNotifyDownstreams) {
      // A visible change occurred between the two states - notify all downstreams
      nextState
          .getDownstreams()
          .forEach(downstream -> downstream.mutateState(state -> state.setDirty()));
    } else {
      // No visible change, but maybe a new downstream were added - notify only these
      Set<KickableImpl<?, ?>> newDownstreams =
          Sets.difference(nextState.getDownstreams(), currentState.getDownstreams());
      newDownstreams.forEach(downstream -> downstream.mutateState(state -> state.setDirty()));
    }

    if (nextState.isDone()) {
      log.finest(() -> getDebugKey() + " Completed!");
      nextState.clearDownstreams();
    }
  }

  /**
   * This is the main method that a concrete level must override. The prevState argument is the
   * immutable (frozen) instance of the state the Kickable is moving from. The nextState is a
   * MUTABLE state the Kickable is moving to.
   *
   * <p>The concrete level is given an opportunity to react to the changes both in the state itself
   * and in the state of, potentially, its upstreams.
   *
   * <p>The "react" part should be in the form of:
   *
   * <p>1) Mutating the `nextState` (setting value, subscriptions, upstreams, etc.)
   *
   * <p>2) Subscribing/unsubscribing to events from the outside world. NOTE: this MUST NOT block the
   * graph maintenance thread.
   */
  protected abstract void handleStateUpdate(S prevState, S nextState);

  protected final boolean isInvalidated() {
    verifyMaintenanceThread();

    return currentState.isInvalidated();
  }

  protected final boolean isErred() {
    verifyMaintenanceThread();

    return currentState.getError() != null;
  }

  protected final boolean isCompleted() {
    verifyMaintenanceThread();

    return currentState.isCompleted();
  }

  protected final boolean isDone() {
    verifyMaintenanceThread();

    return currentState.isDone();
  }

  protected final Optional<T> getValue() {
    verifyMaintenanceThread();

    return currentState.isInvalidated() ? Optional.empty() : Optional.of(currentState.getValue());
  }

  protected final Optional<Exception> getError() {
    verifyMaintenanceThread();

    return Optional.ofNullable(currentState.getError());
  }

  protected final boolean isSubscribedTo(KickableImpl<?, ?> upstream) {
    verifyMaintenanceThread();

    return currentState.getUpstreamSubscriptions().contains(upstream);
  }

  protected final boolean isSubscribedByAnyone() {
    verifyMaintenanceThread();

    if (currentState.isCompleted() || !currentState.isInvalidated()) {
      return false;
    }

    return currentState.isSubscribedByAnyone();
  }

  @Override
  public final Observable<T> kick(Scheduler scheduler) {
    anythingButMaintenanceThread();

    KickDownstream<T> kickDownstream = new KickDownstream<>(this, scheduler);
    // Do not let the KickDownstream instance escape - it is only its observable that is to be
    // seen by the outside world
    return kickDownstream.getObservable();
  }

  @Override
  public final Observable<Optional<T>> autoKick(Scheduler scheduler) {
    return kick(scheduler)
        .map(value -> Optional.of(value))
        .concatWith(Observable.just(Optional.empty()))
        .concatWith(Observable.defer(() -> autoKick(scheduler)));
  }

  @Override
  public final <U> Kickable<U> map(Function<? super T, Single<U>> func, Scheduler scheduler) {
    return new MappingKickable<T, U>(this, func, scheduler);
  }

  @Override
  public final <U> Kickable<U> switchMap(
      Function<? super T, Kickable<U>> func, Scheduler scheduler) {
    return new SwitchMappingKickable<T, U>(this, func, scheduler);
  }

  @Override
  public final Kickable<T> attachListener(
      KickableEventListener<? super T> listener, Scheduler scheduler) {
    return new NotifyingKickable<>(this, listener, scheduler);
  }
}
