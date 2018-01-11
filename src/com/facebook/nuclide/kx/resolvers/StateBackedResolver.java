/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx.resolvers;

import com.facebook.nuclide.kx.Kickable;
import com.facebook.nuclide.logging.Log;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.subjects.PublishSubject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This is an abstract resolver that makes it easy to build resolvers that rely on external sources
 * for the information produced by their Kickables. The abstract layer provides means for the
 * concrete implementation to both manipulate the state and lifts most of the burden associated with
 * the need to synchronize this management with the state of the Kickables.
 *
 * <p>For example, lets say we want to build a resolver that provides information about buck rules
 * Such resolver would have buck as the source of it. Such information will be stored in the state.
 * The state is mapped by an implementor-provided key. Upon the first call to the <code>resolve(key)
 * </code> method, a Kickable will be constructed for that specific key. All subsequent <code>
 * resolve</code> calls will reuse the same Kickable instance.
 *
 * <p>Should a <code>resolve(key)</code> be called for a key that is not in the state, the
 * implementor's <code>fetch(key)</code> method will be invoked to let the concrete level know that
 * it should make an effort to fetch the missing part of the state. Once the missing part is fetched
 * the flow is the same.
 *
 * <p>The managing of the state in the raw (non-Kickable) form allows building resolvers that
 * seemingly handle massive numbers of Kickables in a very efficient way. The memory overhead of
 * such state management is next to zero, whereas Kickables, if used for a too fine grained state
 * management become very costly. The savings stem from the fact that each live Kickable needs to
 * take care of its own subscription to its upstream, which means having many Observable and
 * Observer instances in memory. Each handling the notifications only related to the Kickable
 * itself. Whereas with this model, all of the state management is made in bulk by the resolver, and
 * only the (lazily created) Kickables that were explicitly created incur the subscription costs.
 *
 * <p>This class provides an extremely easy support for invalidation and completion of Kickables.
 * E.g. if, due to some file-change event we decided to invalidate (or complete) a certain key, or a
 * set of keys, it is just a matter of calling the appropriate method to update the state. Proper
 * action on the Kickables will be determined automatically.
 *
 * <p>This class is thread-safe.
 *
 * <p>TODO: Current implementation has three type arguments, K(ey), S(tate) and T(ype) of the
 * produced Kickable. It may turn out that S and T could in fact be made one. So far it's better to
 * have the extra flexibility. If after some time it'll turn out to be unnecessary I (advinsky) will
 * remove this.
 *
 * <p>TODO: While this class simplifies the building of stateful resolvers immensely, it is not
 * enough. Specifically, currently it does not provide ways to express the dependencies of the state
 * pieces. I.e. If we're building a buck rule managing resolver and the rule fetching depends on
 * buck configuration we should be able to express this dependency and invalidate automatically.
 * Currently it's up to the implementor to handle this.
 */
public abstract class StateBackedResolver<K, S, T> {
  private static final Log log = Log.get();

  // ---------------------------------------------------------------
  // ----------- Fields protected by lock on `this` ----------------

  // Represents the known state as was constructed by the stream of the update/invalidate/complete
  // method invocations.
  // For each key:
  //  Absent value means that the key was either not fetched yet or it was completed/errored since
  //  Optional.empty() - the value is invalidated
  //  Optional.of(<something>) - the value is current
  // Note, there can be (moreover, expected) significantly more entries in this map than the number
  // of Kickables handed out by this resolver
  private final Map<K, Optional<S>> stateMap = new HashMap<>();

  // The Kickables that were produced and are alive (not completed or errored)
  // The Kickables are constructed lazily upon a `resolve` call.
  private final Map<K, Kickable<T>> kickablesMap = new HashMap<>();

  // For JMX Console and debugging - the list of fetch operations that are currently subscribed
  private final List<K> fetchInProgress = new LinkedList<>();

  // ------- End of fields protected by lock on `this` -------------
  // ---------------------------------------------------------------

  // Requests for state updates. The actual change depends on the state at the moment of the
  // application
  // Instead of simply locking on updates, we queue them (via mutationRequests.serialize()) so that
  // all observers can tune in at any time and see a consistent set of updates. This guarantees that
  // no observer sees update N until all observers that were subscribed at the time saw N-1.
  private final PublishSubject<MutationRequest<K, S>> mutationRequests = PublishSubject.create();

  // The stream of updates to the state - serves as the source of events for the live Kickables
  private final PublishSubject<StateUpdate<K, S>> stateUpdates = PublishSubject.create();

  private final Optional<? extends CacheManager<K, S>> cacheManager;

  public StateBackedResolver() {
    this(Optional.empty());
  }

  public StateBackedResolver(CacheManager<K, S> cacheManager) {
    this(Optional.of(cacheManager));
  }

  // Implementation remarks.
  // One of the reasons this class is somewhat complicated are the requirements on how we operate
  // with respect to the possible modes of threaded invocation.
  // One basic rule - the kickables we produce must not produce values/invalidate values or complete
  // while we're holding any lock. That is because these operations are mostly synchronous and
  // immediately propagate to far sections of the state graph (while potentially holding a lock)
  // Over invoking yielding control in such a complex system while holding a lock is a recipe for
  // deadlocks
  //
  // To address the serializability of invocations and state inspections the flow is the following
  // update/invalidate/complete methods place a mutation request into the `mutationRequests`
  // stream. Which is then mapped to the actual updates depending on the state (stateMap) at the
  // time of the handling of each request (rather than at the time of the respective method
  // invocation).
  public StateBackedResolver(Optional<? extends CacheManager<K, S>> cacheManager) {
    mutationRequests
        .serialize()
        .flatMapIterable(mutation -> mutation.toActualUpdates(stateMap, StateBackedResolver.this))
        .subscribe(
            update -> {
              synchronized (StateBackedResolver.this) {
                update.apply(getDebugName(), stateMap);
              }
              stateUpdates.onNext(update);
            });

    this.cacheManager = cacheManager;
    this.cacheManager.ifPresent(
        cm -> {
          stateUpdates.subscribe(
              update -> {
                if (update.getState().isPresent()) {
                  cm.updateCache(update.getKey(), update.getState().get());
                } else {
                  cm.clearCache(update.getKey());
                }
              });
        });
  }

  public synchronized Kickable<T> resolve(K key) {
    Kickable<T> cached = kickablesMap.get(key);
    if (cached != null) {
      // We have have a Kickable for this key - nothing else to do
      return cached;
    }

    Kickable<T> built =
        Kickable.fromLifecycles(lifecyclesOf(key))
            .switchMap(state -> buildFromStateCallerHoldsLock(key, state))
            .doFinally(() -> removeKickable(key)) // Separate function for an easier synchonization
            .setDebugKey(this.getClass().getSimpleName() + " " + key.toString());

    kickablesMap.put(key, built);
    return built;
  }

  /** Mostly meant for testing. Allows overriding the scheduler used for lifecycles. */
  protected synchronized Kickable<T> resolve(K key, Scheduler scheduler) {
    Kickable<T> cached = kickablesMap.get(key);
    if (cached != null) {
      // We have have a Kickable for this key - nothing else to do
      return cached;
    }

    Kickable<T> built =
        Kickable.fromLifecycles(lifecyclesOf(key), scheduler)
            .switchMap(state -> buildFromStateCallerHoldsLock(key, state), scheduler)
            .doFinally(
                () -> removeKickable(key),
                scheduler) // Separate function for an easier synchonization
            .setDebugKey(this.getClass().getSimpleName() + " " + key.toString());

    kickablesMap.put(key, built);
    return built;
  }

  private synchronized void removeKickable(K key) {
    kickablesMap.remove(key);
  }

  private Observable<Optional<S>> lifecyclesOf(K key) {
    return Observable.defer(
        () -> {
          synchronized (StateBackedResolver.this) {
            Optional<S> currentState = stateMap.get(key);

            log.finest(
                () -> {
                  String valueStatus =
                      currentState == null
                          ? "absent"
                          : (currentState.isPresent() ? "resolved" : "invalidated");
                  return getDebugName() + " value is " + valueStatus + " for key " + key.toString();
                });

            // We got to a subscription hendling code here because an Observable returned from a
            // Kickable.kick() got subscribed. Such subscription reoccurs on every value (after
            // every invalidation), therefore we will likely get here several times for the same
            // instance of the Kickable that this resolver has produced.
            // At the moment of the subscription there is no ready state - we need to .ambWith
            // the fetch method.
            // In .ambWith the fetch method will always lose, as it does not produce any elements
            // the important part is for its output to be subscribed for as long as the result is
            // awaited. It is up to the implementing Resolver to call the `update` method when the
            // result is available and by that signal the value.
            if (currentState == null || !currentState.isPresent()) {
              if (currentState == null) {
                // if there's not even an invalidated state in the map
                // (a) no value has been loaded at all yet
                // (b) a previous value has been COMPLETEd
                // in either case, load a value from cache while fetching a correct answer.
                cacheManager.ifPresent(
                    cm -> cm.getCachedValue(key).subscribe(state -> weakUpdate(key, state)));
              }

              Observable<StateUpdate<K, S>> stateUpdatesForKey =
                  stateUpdates.filter(u -> u.key.equals(key));

              // run the fetch, but unsubscribe if anything but a weak update
              // comes in on stateUpdatesForKey
              Completable runFetch =
                  stateUpdatesForKey
                      .filter(u -> u.type != StateUpdateType.UPDATE_WEAK)
                      .ambWith(fetchInternal(key).toObservable())
                      .ignoreElements()
                      .doOnComplete(
                          () ->
                              log.finest(
                                  () -> getDebugName() + " fetch completed for " + key.toString()))
                      .doOnDispose(
                          () ->
                              log.finest(
                                  () ->
                                      getDebugName()
                                          + " disposing fetch fetch for "
                                          + key.toString()));

              // the state is already invalid; ignore INVALIDATE requests
              // until we have a valid state
              Observable<StateUpdate<K, S>> lifecycleUpdates =
                  stateUpdatesForKey.skipWhile(u -> u.type == StateUpdateType.INVALIDATE);

              return lifecycleUpdates
                  .mergeWith(runFetch.toObservable())
                  .takeWhile(u -> u.type != StateUpdateType.COMPLETE)
                  .map(u -> u.state)
                  .distinctUntilChanged();
            }

            // We actually have the match in our state at the time of the subscription, start with
            // it but continue on listening for state invalidations and completions
            return stateUpdates
                .filter(u -> u.key.equals(key))
                .takeWhile(u -> u.type != StateUpdateType.COMPLETE)
                .map(u -> u.state)
                .startWith(currentState)
                .distinctUntilChanged();
          }
        });
  }

  /** Update the state of the given key to the given value */
  protected void update(K key, S state) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          return ImmutableList.of(StateUpdate.update(key, state));
        });
  }

  /** Update the state of the given key to the given value if none already exists */
  protected void weakUpdate(K key, S state) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          return ImmutableList.of(StateUpdate.weakUpdate(key, state));
        });
  }

  /**
   * Performs an incremental update for the given key. The mapper will be called with an Optional
   * representing the current state (or the lack of it) and is expected to provide an updated state
   * value. If the state provided by the mapper equals to the current state, the update is
   * effectively canceled
   *
   * <p>This is useful to handle new data that is an estimation, or, perhaps, incremental rather
   * than a hard value. This gives the implementor an opportunity to build a better state that is
   * based both on the discovered new information and the current state.
   *
   * <p>For example: Discoveries of mappings between a file and an owning buck rule are often
   * partial, since unless we actually invoke a `buck query owner` such discoveries will be made by
   * observing a rule that says it owns the file. In most cases the owner will be singular, but
   * sometimes there may be other rules that own the same file as well. In such cases we would want
   * to treat the new piece of information about a rule owning a file as an incremental update.
   */
  protected void updateIncremental(K key, Function<Optional<S>, S> mapperCallerHoldsLock) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          synchronized (lock) {
            Optional<S> current = stateMap.get(key);
            Optional<S> forMapper = current == null ? Optional.empty() : current;

            S mappedState = mapperCallerHoldsLock.apply(forMapper);

            // Empty update - do not propagate
            if (forMapper.isPresent() && forMapper.get().equals(mappedState)) {
              return ImmutableList.of();
            }

            return ImmutableList.of(StateUpdate.update(key, mappedState));
          }
        });
  }

  /** Ivalidates the Kickable attached to the key if there is one live at the moment */
  protected void invalidate(K key) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          synchronized (lock) {
            Optional<S> current = stateMap.get(key);

            // Either already invalidate or not even fetched - nothing to do
            if (current == null || !current.isPresent()) {
              return ImmutableList.of();
            }

            return ImmutableList.of(StateUpdate.invalidate(key));
          }
        });
  }

  /** Invalidates all Kickables that pass the filter */
  protected void invalidateAll(BiPredicate<K, S> filterCallerHoldsLock) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          synchronized (lock) {
            List<StateUpdate<K, S>> updates =
                stateMap
                    .entrySet()
                    .stream()
                    .filter(
                        entry -> {
                          if (!entry.getValue().isPresent()) {
                            return false;
                          }

                          K key = entry.getKey();
                          S state = entry.getValue().get();
                          return filterCallerHoldsLock.test(key, state);
                        })
                    .map(entry -> StateUpdate.<K, S>invalidate(entry.getKey()))
                    .collect(Collectors.toList());

            return updates;
          }
        });
  }

  /** Completes the Kickable attached to the key if there is one live at the moment */
  protected void complete(K key) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          synchronized (lock) {
            if (!stateMap.containsKey(key) && !kickablesMap.containsKey(key)) {
              return ImmutableList.of();
            }

            return ImmutableList.of(StateUpdate.complete(key));
          }
        });
  }

  /** Completes all Kickables who pass the given filter */
  protected void completeAll(BiPredicate<K, Optional<S>> filterCallerHoldsLock) {
    mutationRequests.onNext(
        (stateMap, lock) -> {
          synchronized (lock) {
            return Sets.union(stateMap.keySet(), kickablesMap.keySet())
                .stream()
                .filter(
                    key -> {
                      Optional<S> state = stateMap.get(key);
                      if (state == null) {
                        state = Optional.empty();
                      }
                      return filterCallerHoldsLock.test(key, state);
                    })
                .map(key -> StateUpdate.<K, S>complete(key))
                .collect(Collectors.toList());
          }
        });
  }

  /** Wraps the abstract fetch method result with state monitoring performing callbacks */
  private Completable fetchInternal(K key) {
    return fetchCallerHoldsLock(key)
        .doOnSubscribe(
            disposable -> {
              log.finest(() -> getDebugName() + " Subscribing to fetch of", key);
              synchronized (StateBackedResolver.this) {
                fetchInProgress.add(key);
              }
            })
        .doOnDispose(
            () -> {
              log.finest(() -> getDebugName() + " Unsubscribing from fetch of", key);
              synchronized (StateBackedResolver.this) {
                fetchInProgress.remove(key);
              }
            })
        .doOnTerminate(
            () -> {
              log.finest(() -> getDebugName() + " Finished the fetch of", key);
              synchronized (StateBackedResolver.this) {
                fetchInProgress.remove(key);
              }
            });
  }

  /** Signals the implementor that a new piece of state is needed. */
  protected abstract Completable fetchCallerHoldsLock(K key);

  /**
   * Expected to convert the intermediate state into a Kickable.
   *
   * <p>Has an additional functionality of signaling the implementor that a particular part of state
   * is not attached to a Kickable instance. The implementor can use this information for additional
   * state management
   *
   * <p>For example: proper management of buck rule resolver can likely require to maintain a list
   * of Watchman subscriptions to all of the buck project roots for the currently live Kickables yet
   * it is not something we'd want to keep as part of the S state object, because this information
   * may likely have a different key hierarchy
   */
  protected abstract Kickable<T> buildFromStateCallerHoldsLock(K key, S state);

  private String getDebugName() {
    return this.getClass().getSimpleName();
  }

  public StateBackedResolverMXBean createBean() {
    return new StateBackedResolverMXBean() {
      @Override
      public List<String> listStateKeys() {
        synchronized (StateBackedResolver.this) {
          return stateMap.keySet().stream().map(K::toString).collect(Collectors.toList());
        }
      }

      @Override
      public List<String> listKickableKeys() {
        synchronized (StateBackedResolver.this) {
          return kickablesMap.keySet().stream().map(K::toString).collect(Collectors.toList());
        }
      }

      @Override
      public List<String> listFetchInProgress() {
        synchronized (StateBackedResolver.this) {
          return fetchInProgress.stream().map(K::toString).collect(Collectors.toList());
        }
      }

      private void forStringKey(String key, Consumer<K> op) {
        List<K> keysToRunOpOn;
        synchronized (StateBackedResolver.this) {
          keysToRunOpOn =
              stateMap
                  .keySet()
                  .stream()
                  .filter(k -> k.toString().equals(key))
                  .collect(Collectors.toList());
        }

        keysToRunOpOn.forEach(op);
      }

      private void forAllKeys(Consumer<K> op) {
        List<K> keysToRunOpOn;
        synchronized (StateBackedResolver.this) {
          keysToRunOpOn = new ArrayList<>(stateMap.keySet());
        }

        keysToRunOpOn.forEach(op);
      }

      @Override
      public void invalidateKey(String key) {
        forStringKey(key, StateBackedResolver.this::invalidate);
      }

      @Override
      public void invalidateAllKeys() {
        forAllKeys(StateBackedResolver.this::invalidate);
      }

      @Override
      public int getStateKeyCount() {
        synchronized (StateBackedResolver.this) {
          return stateMap.keySet().size();
        }
      }

      @Override
      public int getKickableKeyCount() {
        synchronized (StateBackedResolver.this) {
          return kickablesMap.keySet().size();
        }
      }

      @Override
      public int getFetchInProgressCount() {
        synchronized (StateBackedResolver.this) {
          return fetchInProgress.size();
        }
      }

      @Override
      public void completeKey(String key) {
        forStringKey(key, StateBackedResolver.this::complete);
      }

      @Override
      public void completeAllKeys() {
        forAllKeys(StateBackedResolver.this::complete);
      }
    };
  }

  public static interface StateBackedResolverMXBean {
    List<String> listStateKeys();

    List<String> listKickableKeys();

    List<String> listFetchInProgress();

    int getStateKeyCount();

    int getKickableKeyCount();

    int getFetchInProgressCount();

    void invalidateKey(String key);

    void completeKey(String key);

    void invalidateAllKeys();

    void completeAllKeys();
  }

  private static interface MutationRequest<K, S> {
    Iterable<StateUpdate<K, S>> toActualUpdates(Map<K, Optional<S>> stateMap, Object lock);
  }

  private static enum StateUpdateType {
    UPDATE,
    UPDATE_WEAK,
    INVALIDATE,
    COMPLETE
  }

  private static class StateUpdate<K, S> {
    private final K key;
    private final StateUpdateType type;
    private final Optional<S> state;

    private StateUpdate(K key, StateUpdateType type, Optional<S> state) {
      this.key = key;
      this.type = type;
      this.state = state;
    }

    public static <K, S> StateUpdate<K, S> update(K key, S state) {
      return new StateUpdate<>(key, StateUpdateType.UPDATE, Optional.of(state));
    }

    public static <K, S> StateUpdate<K, S> weakUpdate(K key, S state) {
      return new StateUpdate<>(key, StateUpdateType.UPDATE_WEAK, Optional.of(state));
    }

    public static <K, S> StateUpdate<K, S> invalidate(K key) {
      return new StateUpdate<>(key, StateUpdateType.INVALIDATE, Optional.empty());
    }

    public static <K, S> StateUpdate<K, S> complete(K key) {
      return new StateUpdate<>(key, StateUpdateType.COMPLETE, Optional.empty());
    }

    public void apply(String debugName, Map<K, Optional<S>> stateMap) {
      switch (type) {
        case COMPLETE:
          log.finest(() -> debugName + " completing " + key.toString());
          break;
        case UPDATE:
          log.finest(() -> debugName + " updating value of " + key.toString());
          break;
        case INVALIDATE:
          log.finest(() -> debugName + " invalidating " + key.toString());
          break;
        case UPDATE_WEAK:
          if (!stateMap.containsKey(key)) {
            log.finest(() -> debugName + " weak-updating value of " + key.toString());
            break;
          }
      }

      if (type == StateUpdateType.COMPLETE) {
        stateMap.remove(key);
      } else if (type != StateUpdateType.UPDATE_WEAK || !stateMap.containsKey(key)) {
        stateMap.put(key, state);
      }
    }

    public K getKey() {
      return key;
    }

    public Optional<S> getState() {
      return state;
    }
  }
}
