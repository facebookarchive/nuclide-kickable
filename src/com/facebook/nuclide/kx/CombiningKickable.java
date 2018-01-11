/*
 * Copyright (c) 20xx-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.nuclide.kx;

import com.google.common.base.Preconditions;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

class CombiningKickable extends KickableImpl<List<?>, CombiningKickable.State> {
  class State extends KickableImpl<List<?>, State>.State {

    public State(List<KickableImpl<?, ?>> upstreams) {
      setUpstreams(upstreams);
    }

    public State(State other) {
      super(other);
    }

    @Override
    public State clone() {
      return new State(this);
    }
  }

  public CombiningKickable(List<KickableImpl<?, ?>> upstreams) {
    initState(new State(upstreams));
  }

  @Override
  protected void handleStateUpdate(State prevState, State nextState) {
    if (nextState.isDone() || nextState.propagateErrorAndCompletion()) {
      return;
    }

    List<?> upstreamValues = getValuesFromUpstreamsWithNulls(nextState);
    boolean allResolved = upstreamValues.stream().allMatch(v -> v != null);
    if (allResolved) {
      setValues(nextState, upstreamValues);
    } else {
      nextState.setIsInvalidated();
      nextState.propagateSubscriptionToUpstreams();
    }
  }

  private List<?> getValuesFromUpstreamsWithNulls(State nextState) {
    return nextState
        .getUpstreams()
        .stream()
        .map(upstream -> upstream.getValue().orElse(null))
        .collect(Collectors.toList());
  }

  private void setValues(State nextState, List<?> newValues) {
    List<?> prevValues = nextState.getValue();

    if (valueListsReferentiallyEqual(prevValues, newValues)) {
      nextState.setValueWithCheapEqualityCheck(prevValues);
    } else {
      nextState.setValueWithCheapEqualityCheck(newValues);
    }
  }

  private boolean valueListsReferentiallyEqual(List<?> prevValues, List<?> newValues) {
    if (prevValues == null) {
      return false;
    }

    Preconditions.checkState(
        newValues.size() == prevValues.size(), "Value lists of mismatching sizes");

    Iterator<?> itNew = newValues.iterator();
    Iterator<?> itPrev = prevValues.iterator();

    while (itNew.hasNext()) {
      Object vNew = itNew.next();
      Object vPrev = itPrev.next();

      if (vNew != vPrev) {
        return false;
      }
    }

    return true;
  }
}
