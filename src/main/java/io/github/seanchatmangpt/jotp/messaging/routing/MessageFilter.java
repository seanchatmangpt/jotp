package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Message Filter pattern (EIP): selectively forwards messages that match a predicate; silently
 * drops messages that do not match.
 *
 * <p>Messages are processed asynchronously on a virtual-thread backed {@link Proc}. The filter
 * is thread-safe and can accept messages from concurrent producers.
 *
 * @param <M> the message type
 */
public final class MessageFilter<M> {

    /**
     * Functional interface for the downstream destination that receives filtered messages.
     *
     * @param <M> the message type
     */
    @FunctionalInterface
    public interface Destination<M> {
        /** Receive a filtered message. */
        void accept(M message);
    }

    private final Predicate<M> predicate;
    private final Destination<M> next;
    private final Proc<Void, M> proc;

    private MessageFilter(Predicate<M> predicate, Destination<M> next) {
        this.predicate = predicate;
        this.next = next;
        this.proc =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            if (predicate.test(msg)) {
                                next.accept(msg);
                            }
                            return state;
                        });
    }

    /**
     * Create a new MessageFilter.
     *
     * @param predicate the filter criterion; messages for which this returns {@code true} are
     *     forwarded
     * @param next the downstream destination for matching messages
     * @param <M> the message type
     * @return new MessageFilter
     * @throws IllegalArgumentException if predicate or next is null
     */
    public static <M> MessageFilter<M> create(Predicate<M> predicate, Destination<M> next) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        if (next == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        return new MessageFilter<>(predicate, next);
    }

    /**
     * Create a new MessageFilter whose downstream is a {@link Proc}.
     *
     * @param predicate the filter criterion
     * @param proc the target process
     * @param <S> process state type
     * @param <M> message type
     * @return new MessageFilter
     * @throws IllegalArgumentException if predicate or proc is null
     */
    public static <S, M> MessageFilter<M> create(
            Predicate<M> predicate, io.github.seanchatmangpt.jotp.Proc<S, M> proc) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        if (proc == null) {
            throw new IllegalArgumentException("next must not be null");
        }
        return new MessageFilter<>(predicate, proc::tell);
    }

    /**
     * Submit a message for filtering.
     *
     * @param message the message to evaluate (must not be null)
     * @throws IllegalArgumentException if message is null
     */
    public void filter(M message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        proc.tell(message);
    }

    /**
     * Returns the backing {@link Proc} for lifecycle management.
     *
     * @return the backing process
     */
    public Proc<Void, M> process() {
        return proc;
    }
}
