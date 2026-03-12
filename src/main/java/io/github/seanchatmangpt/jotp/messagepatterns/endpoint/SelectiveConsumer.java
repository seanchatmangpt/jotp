package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Selective Consumer pattern: a consumer that only accepts messages matching specific criteria.
 *
 * <p>Enterprise Integration Pattern: <em>Selective Consumer</em> (EIP §10.4). The consumer filters
 * incoming messages, processing only those it is interested in and rejecting the rest.
 *
 * <p>Erlang analog: selective receive with guard clauses — {@code receive Msg when Guard -> process(Msg)
 * end} — only messages satisfying the guard are dequeued.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * SelectiveConsumer} pattern-matches on message type and forwards to type-specific consumers.
 *
 * @param <T> base message type
 */
public final class SelectiveConsumer<T> {

    private record Selection<T>(Predicate<T> predicate, Consumer<T> consumer) {}

    private final List<Selection<T>> selections;
    private final Consumer<T> rejected;
    private final Proc<Void, T> proc;

    private SelectiveConsumer(List<Selection<T>> selections, Consumer<T> rejected) {
        this.selections = List.copyOf(selections);
        this.rejected = rejected;
        this.proc = new Proc<>(null, (state, msg) -> {
            for (Selection<T> selection : selections) {
                if (selection.predicate().test(msg)) {
                    selection.consumer().accept(msg);
                    return state;
                }
            }
            if (rejected != null) {
                rejected.accept(msg);
            }
            return state;
        });
    }

    /** Send a message to the selective consumer. */
    public void send(T message) {
        proc.tell(message);
    }

    /** Stop the consumer. */
    public void stop() {
        proc.stop();
    }

    /** Builder for SelectiveConsumer. */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent builder. */
    public static final class Builder<T> {
        private final List<Selection<T>> selections = new ArrayList<>();
        private Consumer<T> rejected;

        public Builder<T> accept(Predicate<T> predicate, Consumer<T> consumer) {
            selections.add(new Selection<>(predicate, consumer));
            return this;
        }

        public Builder<T> reject(Consumer<T> handler) {
            this.rejected = handler;
            return this;
        }

        public SelectiveConsumer<T> build() {
            return new SelectiveConsumer<>(selections, rejected);
        }
    }
}
