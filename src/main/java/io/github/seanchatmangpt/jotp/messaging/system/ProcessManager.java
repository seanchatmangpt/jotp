package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.Result;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Process Manager — orchestrates multi-step stateful message workflows.
 *
 * <p>Implements the Process Manager EIP pattern (Hohpe &amp; Woolf, ch. 8). Each step is a pure
 * function that receives a context object and the previous step's output, and produces the next
 * step's output. A compensating function per step enables rollback on failure.
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * var pm = new ProcessManager<OrderContext>("order-workflow")
 *     .step("validate",  (ctx, input) -> validate(input),  (ctx, out) -> null)
 *     .step("charge",    (ctx, input) -> charge(input),    (ctx, out) -> rollbackCharge(out))
 *     .step("ship",      (ctx, input) -> ship(input),      (ctx, out) -> null);
 *
 * var result = pm.start(order).join();
 * result.fold(
 *     ctx -> "Completed: " + ctx.getExecutedSteps(),
 *     err -> "Failed: " + err
 * );
 * }</pre>
 *
 * @param <C> the workflow context type shared across all steps
 */
public final class ProcessManager<C> {

    private final String workflowName;
    private final List<StepDefinition> steps = new ArrayList<>();
    private final Map<String, Boolean> stepNames = new ConcurrentHashMap<>();

    private volatile WorkflowResult currentWorkflowState = null;

    /**
     * Creates a new ProcessManager with the given workflow name.
     *
     * @param workflowName unique name for this workflow instance
     */
    public ProcessManager(String workflowName) {
        this.workflowName = workflowName;
    }

    /**
     * Appends a step to this workflow.
     *
     * @param name unique name for this step within the workflow
     * @param action the step logic: receives (context, previousOutput), returns this step's output
     * @param compensate the rollback logic: receives (context, stepOutput), called on failure
     * @return {@code this} for fluent chaining
     * @throws IllegalArgumentException if a step with the same name already exists
     */
    public ProcessManager<C> step(
            String name,
            BiFunction<Object, Object, Object> action,
            BiFunction<Object, Object, Object> compensate) {
        if (stepNames.putIfAbsent(name, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "Step already defined: '" + name + "' in workflow '" + workflowName + "'");
        }
        steps.add(new StepDefinition(name, action, compensate));
        return this;
    }

    /**
     * Starts the workflow with the given initial input, running all steps in a virtual thread.
     *
     * @param input the initial input passed to the first step
     * @return a future that resolves with {@code Result.ok(WorkflowResult)} on success or
     *     {@code Result.err(reason)} on failure
     */
    public CompletableFuture<Result<WorkflowResult, String>> start(Object input) {
        if (steps.isEmpty()) {
            return CompletableFuture.completedFuture(
                    Result.err("No steps defined in workflow '" + workflowName + "'"));
        }

        var future = new CompletableFuture<Result<WorkflowResult, String>>();

        Thread.ofVirtual()
                .name("process-manager-" + workflowName)
                .start(
                        () -> {
                            var executedSteps = new ArrayList<String>();
                            var stepOutputs = new LinkedHashMap<String, Object>();
                            String lastError = null;

                            var workflowResult =
                                    new WorkflowResult(executedSteps, stepOutputs, lastError);
                            currentWorkflowState = workflowResult;

                            Object currentInput = input;
                            int executedCount = 0;

                            try {
                                for (var stepDef : steps) {
                                    Object output = stepDef.action().apply(null, currentInput);
                                    executedSteps.add(stepDef.name());
                                    stepOutputs.put(stepDef.name(), output);
                                    executedCount++;
                                    currentInput = output;
                                }
                                future.complete(Result.ok(workflowResult));
                            } catch (Exception ex) {
                                String reason =
                                        "Workflow failed at step '"
                                                + steps.get(executedCount).name()
                                                + "': "
                                                + (ex.getMessage() != null
                                                        ? ex.getMessage()
                                                        : ex.toString());

                                // Attempt compensation in reverse order for completed steps
                                for (int i = executedCount - 1; i >= 0; i--) {
                                    try {
                                        var s = steps.get(i);
                                        var out = stepOutputs.get(s.name());
                                        s.compensate().apply(null, out);
                                    } catch (Exception ignored) {
                                        // Best-effort rollback
                                    }
                                }

                                future.complete(Result.err(reason));
                            }
                        });

        return future;
    }

    /**
     * Returns the current workflow state if a workflow has been started.
     *
     * @return an Optional containing the live workflow result, or empty if not yet started
     */
    public Optional<WorkflowResult> currentState() {
        return Optional.ofNullable(currentWorkflowState);
    }

    /**
     * Returns the total number of registered steps.
     *
     * @return step count
     */
    public int stepCount() {
        return steps.size();
    }

    /**
     * Returns the step definition at the given index.
     *
     * @param index zero-based step index
     * @return the step definition, or empty if index is out of range
     */
    public Optional<StepDefinition> getStep(int index) {
        if (index < 0 || index >= steps.size()) {
            return Optional.empty();
        }
        return Optional.of(steps.get(index));
    }

    /**
     * Stops this process manager, releasing any resources. May be called after workflow completion.
     */
    public void stop() {
        currentWorkflowState = null;
    }

    /**
     * A step in the workflow, consisting of an action and a compensating function.
     *
     * @param name the step name
     * @param action the step logic
     * @param compensate the rollback logic
     */
    public record StepDefinition(
            String name,
            BiFunction<Object, Object, Object> action,
            BiFunction<Object, Object, Object> compensate) {}

    /**
     * Captures the live state of an executing or completed workflow.
     *
     * <p>This object is mutated as the workflow progresses (steps are appended to the executed
     * list and outputs are recorded). Callers should treat it as a snapshot at the point of
     * inspection.
     */
    public static final class WorkflowResult {

        private final List<String> executedSteps;
        private final Map<String, Object> stepOutputs;
        private String lastError;

        WorkflowResult(List<String> executedSteps, Map<String, Object> stepOutputs, String lastError) {
            this.executedSteps = executedSteps;
            this.stepOutputs = stepOutputs;
            this.lastError = lastError;
        }

        /**
         * Returns the ordered list of step names that have been executed so far.
         *
         * @return immutable snapshot of executed step names
         */
        public List<String> getExecutedSteps() {
            return Collections.unmodifiableList(executedSteps);
        }

        /**
         * Returns the output produced by the named step, or {@code null} if not yet executed.
         *
         * @param stepName the step name
         * @return the step output
         */
        public Object getStepOutput(String stepName) {
            return stepOutputs.get(stepName);
        }

        /**
         * Returns an {@link Optional} containing the last error message, or empty if no error
         * occurred.
         *
         * @return last error, or empty
         */
        public Optional<String> getLastError() {
            return Optional.ofNullable(lastError);
        }
    }
}
