package io.github.seanchatmangpt.jotp.enterprise.saga;

import java.util.function.Function;

/**
 * Sealed interface for saga execution steps.
 *
 * <p>Represents forward actions and compensating actions for saga transactions.
 */
public sealed interface SagaStep
        permits SagaStep.Action, SagaStep.Compensation, SagaStep.Conditional {

    /**
     * Forward action: Execute this step.
     *
     * @param name Step identifier
     * @param task Function from input to output
     */
    record Action<I, O>(String name, Function<I, O> task) implements SagaStep {}

    /**
     * Compensation action: Undo this step on rollback.
     *
     * @param name Step identifier
     * @param task Function to execute on rollback
     */
    record Compensation<S>(String name, java.util.function.Consumer<S> task) implements SagaStep {}

    /**
     * Conditional step: Execute one branch or another.
     *
     * @param name Step identifier
     * @param condition Predicate to check
     * @param ifTrue Step to execute if condition is true
     * @param ifFalse Step to execute if condition is false
     */
    record Conditional(
            String name,
            java.util.function.Predicate<?> condition,
            SagaStep ifTrue,
            SagaStep ifFalse)
            implements SagaStep {}

    default String name() {
        return switch (this) {
            case Action(var n, _) -> n;
            case Compensation(var n, _) -> n;
            case Conditional(var n, _, _, _) -> n;
        };
    }
}
