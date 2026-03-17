package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;

/**
 * Sealed type for saga transaction steps.
 *
 * <p>Each step represents an action and its compensation (rollback).
 */
public sealed interface SagaStep extends Serializable {

  String stepName();

  /**
   * Action step with forward action and compensation.
   */
  record Action(
      String stepName,
      Function<SagaContext, Boolean> execute,
      Function<SagaContext, Boolean> compensate)
      implements SagaStep {

    public Action {
      Objects.requireNonNull(stepName, "stepName must not be null");
      Objects.requireNonNull(execute, "execute must not be null");
      Objects.requireNonNull(compensate, "compensate must not be null");
    }
  }

  /**
   * Conditional step that branches based on state.
   */
  record ConditionalStep(
      String stepName,
      Function<SagaContext, Boolean> condition,
      SagaStep ifTrue,
      SagaStep ifFalse)
      implements SagaStep {

    public ConditionalStep {
      Objects.requireNonNull(stepName, "stepName must not be null");
      Objects.requireNonNull(condition, "condition must not be null");
      Objects.requireNonNull(ifTrue, "ifTrue must not be null");
      Objects.requireNonNull(ifFalse, "ifFalse must not be null");
    }
  }

  /**
   * Parallel steps executed concurrently.
   */
  record ParallelSteps(String stepName, SagaStep... steps) implements SagaStep {

    public ParallelSteps {
      Objects.requireNonNull(stepName, "stepName must not be null");
      if (steps.length == 0) {
        throw new IllegalArgumentException("Must have at least one parallel step");
      }
    }
  }

  /**
   * Sequential steps executed in order.
   */
  record SequentialSteps(String stepName, SagaStep... steps) implements SagaStep {

    public SequentialSteps {
      Objects.requireNonNull(stepName, "stepName must not be null");
      if (steps.length == 0) {
        throw new IllegalArgumentException("Must have at least one sequential step");
      }
    }
  }

  /**
   * Saga execution context carrying state through steps.
   */
  class SagaContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String sagaId;
    private final java.util.Map<String, Object> state;
    private final java.util.List<String> completedSteps;
    private final java.util.Map<String, Exception> failedSteps;

    public SagaContext(String sagaId) {
      this.sagaId = sagaId;
      this.state = new java.util.concurrent.ConcurrentHashMap<>();
      this.completedSteps = new java.util.concurrent.CopyOnWriteArrayList<>();
      this.failedSteps = new java.util.concurrent.ConcurrentHashMap<>();
    }

    public String sagaId() {
      return sagaId;
    }

    public Object get(String key) {
      return state.get(key);
    }

    public void put(String key, Object value) {
      state.put(key, value);
    }

    public java.util.Map<String, Object> getState() {
      return new java.util.HashMap<>(state);
    }

    public void markCompleted(String stepName) {
      completedSteps.add(stepName);
    }

    public void markFailed(String stepName, Exception ex) {
      failedSteps.put(stepName, ex);
    }

    public boolean hasCompleted(String stepName) {
      return completedSteps.contains(stepName);
    }

    public boolean hasFailed(String stepName) {
      return failedSteps.containsKey(stepName);
    }

    public Exception getFailure(String stepName) {
      return failedSteps.get(stepName);
    }

    public java.util.List<String> completedSteps() {
      return new java.util.ArrayList<>(completedSteps);
    }

    public java.util.Map<String, Exception> failedSteps() {
      return new java.util.HashMap<>(failedSteps);
    }
  }
}
