package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Routing Slip pattern: a message carries its own routing information, defining the sequence of
 * processing steps.
 *
 * <p>Enterprise Integration Pattern: <em>Routing Slip</em> (EIP §8.8). Each processing step reads
 * the next destination from the slip, processes the message, and forwards it.
 *
 * <p>Erlang analog: a message carrying a list of Pids — each process pops the head, processes, and
 * sends to the next: {@code [Next | Rest] = Slip, Next ! {msg, Data, Rest}}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * RegistrationProcess} maintains a vector of {@code ProcessStep} entries; each actor calls {@code
 * advance()} to move to the next step.
 *
 * @param <T> message payload type
 */
public final class RoutingSlip<T> {

    /** A single step in the routing slip. */
    public record Step<T>(String name, UnaryOperator<T> processor) {}

    private final List<Step<T>> steps;

    /**
     * Creates a routing slip with the given processing steps.
     *
     * @param steps the ordered list of processing steps
     */
    public RoutingSlip(List<Step<T>> steps) {
        this.steps = List.copyOf(steps);
    }

    /**
     * Execute the routing slip, passing the message through each step in order.
     *
     * @param message the initial message
     * @return the message after all processing steps
     */
    public T execute(T message) {
        T current = message;
        for (Step<T> step : steps) {
            current = step.processor().apply(current);
        }
        return current;
    }

    /**
     * Execute only the remaining steps starting from the given index.
     *
     * @param message the message
     * @param fromStep the step index to start from (0-based)
     * @return the message after remaining steps
     */
    public T executeFrom(T message, int fromStep) {
        T current = message;
        for (int i = fromStep; i < steps.size(); i++) {
            current = steps.get(i).processor().apply(current);
        }
        return current;
    }

    /** Returns the number of steps in the routing slip. */
    public int stepCount() {
        return steps.size();
    }

    /** Returns the step names. */
    public List<String> stepNames() {
        return steps.stream().map(Step::name).toList();
    }

    /** Builder for constructing a RoutingSlip. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent builder. */
    public static final class Builder<T> {
        private final java.util.ArrayList<Step<T>> steps = new java.util.ArrayList<>();

        public Builder<T> step(String name, UnaryOperator<T> processor) {
            steps.add(new Step<>(name, processor));
            return this;
        }

        public RoutingSlip<T> build() {
            return new RoutingSlip<>(steps);
        }
    }
}
