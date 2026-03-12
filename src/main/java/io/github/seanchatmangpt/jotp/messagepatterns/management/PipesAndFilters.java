package io.github.seanchatmangpt.jotp.messagepatterns.management;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Pipes and Filters pattern: a chain of processing steps where each filter transforms the message
 * and forwards it to the next.
 *
 * <p>Enterprise Integration Pattern: <em>Pipes and Filters</em> (EIP §3.1). Each filter is a
 * separate actor (Proc) connected in a pipeline. Messages flow from the first filter through each
 * subsequent filter to the final endpoint.
 *
 * <p>Erlang analog: a chain of spawned processes where each sends to the next — {@code Pid3 =
 * spawn(Filter3), Pid2 = spawn(Filter2, Pid3), Pid1 = spawn(Filter1, Pid2)}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, the
 * pipeline is: OrderAcceptanceEndpoint → Decrypter → Authenticator → Deduplicator →
 * OrderManagementSystem.
 *
 * @param <T> message type flowing through the pipeline
 */
public final class PipesAndFilters<T> {

    /** A named filter stage. */
    public record Filter<T>(String name, UnaryOperator<T> transform) {}

    private final List<Proc<Void, T>> filterProcs;

    @SuppressWarnings("unchecked")
    private PipesAndFilters(List<Filter<T>> filters, Consumer<T> endpoint) {
        this.filterProcs = new ArrayList<>();

        // Build the chain in reverse: endpoint ← lastFilter ← ... ← firstFilter
        Consumer<T> next = endpoint;
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter<T> filter = filters.get(i);
            Consumer<T> downstream = next;
            var proc = new Proc<Void, T>(null, (state, msg) -> {
                T transformed = filter.transform().apply(msg);
                if (transformed != null) {
                    downstream.accept(transformed);
                }
                return state;
            });
            filterProcs.addFirst(proc);
            next = proc::tell;
        }
    }

    /** Send a message into the first filter of the pipeline. */
    public void process(T message) {
        if (!filterProcs.isEmpty()) {
            filterProcs.getFirst().tell(message);
        }
    }

    /** Stop all filter processes. */
    public void stop() {
        for (Proc<Void, T> proc : filterProcs) {
            proc.stop();
        }
    }

    /** Builder for constructing a pipeline. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent builder. */
    public static final class Builder<T> {
        private final List<Filter<T>> filters = new ArrayList<>();
        private Consumer<T> endpoint;

        public Builder<T> filter(String name, UnaryOperator<T> transform) {
            filters.add(new Filter<>(name, transform));
            return this;
        }

        public Builder<T> endpoint(Consumer<T> endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public PipesAndFilters<T> build() {
            if (endpoint == null) {
                throw new IllegalStateException("Endpoint must be set");
            }
            return new PipesAndFilters<>(filters, endpoint);
        }
    }
}
