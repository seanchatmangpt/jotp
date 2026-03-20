package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Routing Slip pattern (EIP): attaches a sequence of processing steps to a message. The slip
 * travels with the message, allowing each processing node to forward to the next step.
 *
 * <p>Messages are routed through a predetermined sequence of hops (ProcRef instances). Each hop
 * receives the message with the slip, processes it, and forwards it to the next hop if the slip
 * is not yet complete.
 *
 * @param <P> the payload type
 * @param <R> the reply type (for synchronous execution)
 */
public final class RoutingSlip {

    private RoutingSlip() {}

    /**
     * A message with an attached routing slip.
     *
     * @param <P> payload type
     * @param <R> reply type for synchronous routing
     */
    public interface MessageWithSlip<P, R> {
        P payload();

        List<ProcRef<?, ?>> slip();

        int remainingHops();

        boolean isComplete();

        Optional<ProcRef<?, ?>> peekNext();
    }

    /** Default immutable implementation of MessageWithSlip. */
    private static final class MessageWithSlipImpl<P, R> implements MessageWithSlip<P, R> {
        private final P payload;
        private final List<ProcRef<?, ?>> slip;
        private final int startIndex;

        MessageWithSlipImpl(P payload, List<ProcRef<?, ?>> slip, int startIndex) {
            this.payload = payload;
            this.slip = slip;
            this.startIndex = startIndex;
        }

        @Override
        public P payload() {
            return payload;
        }

        @Override
        public List<ProcRef<?, ?>> slip() {
            return slip;
        }

        @Override
        public int remainingHops() {
            return Math.max(0, slip.size() - startIndex);
        }

        @Override
        public boolean isComplete() {
            return startIndex >= slip.size();
        }

        @Override
        public Optional<ProcRef<?, ?>> peekNext() {
            if (startIndex < slip.size()) {
                return Optional.of(slip.get(startIndex));
            }
            return Optional.empty();
        }
    }

    /**
     * Create a message with a routing slip.
     *
     * @param payload the message payload
     * @param hops the sequence of processing steps
     * @param <P> payload type
     * @param <R> reply type
     * @return a message with the routing slip attached
     * @throws IllegalArgumentException if hops is empty
     */
    public static <P, R> MessageWithSlip<P, R> withSlip(P payload, ProcRef<?, ?>... hops) {
        if (hops == null || hops.length == 0) {
            throw new IllegalArgumentException("Routing slip must have at least one hop");
        }
        return new MessageWithSlipImpl<>(payload, List.of(hops), 0);
    }

    /**
     * Create a message with a routing slip.
     *
     * @param payload the message payload
     * @param hops the sequence of processing steps
     * @param <P> payload type
     * @param <R> reply type
     * @return a message with the routing slip attached
     * @throws IllegalArgumentException if hops is empty
     */
    public static <P, R> MessageWithSlip<P, R> withSlip(P payload, List<ProcRef<?, ?>> hops) {
        if (hops == null || hops.isEmpty()) {
            throw new IllegalArgumentException("Routing slip must have at least one hop");
        }
        return new MessageWithSlipImpl<>(payload, hops, 0);
    }

    /**
     * Peek at the next hop without advancing the slip.
     *
     * @param message the message with slip
     * @param <P> payload type
     * @return the next hop, if available
     */
    public static <P> Optional<?> peekNext(MessageWithSlip<P, ?> message) {
        return message.peekNext();
    }

    /**
     * Advance the slip to the next hop, returning a new message with the advanced slip.
     *
     * @param message the message with slip
     * @param <P> payload type
     * @return a new message with the slip advanced
     * @throws IllegalStateException if the slip is already complete
     */
    @SuppressWarnings("unchecked")
    public static <P> MessageWithSlip<P, ?> popNext(MessageWithSlip<P, ?> message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message.isComplete()) {
            throw new IllegalStateException("Routing slip exhausted");
        }
        var impl = (MessageWithSlipImpl<P, ?>) message;
        return new MessageWithSlipImpl<>(
                impl.payload, impl.slip, impl.startIndex + 1);
    }

    /**
     * Execute the routing slip asynchronously (fire-and-forget).
     *
     * <p>Sends a {@link TestMessage.Process} wrapping an appropriately advanced view of the slip
     * to each hop in sequence. Each hop receives the slip advanced past all prior hops, so
     * {@link MessageWithSlip#peekNext()} returns the next hop (if any) when called from within
     * the hop's handler.
     *
     * @param message the message with slip
     * @param <P> payload type
     * @return a Result indicating success or failure
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <P> Result<?, Exception> executeSlip(MessageWithSlip<P, ?> message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            var impl = (MessageWithSlipImpl<P, ?>) message;
            List<ProcRef<?, ?>> allHops = impl.slip;
            // Send TestMessage.Process to each hop with the slip advanced past that hop
            for (int i = impl.startIndex; i < allHops.size(); i++) {
                final int idx = i;
                MessageWithSlipImpl<P, ?> advancedSlip =
                        new MessageWithSlipImpl<>(impl.payload, allHops, idx + 1);
                TestMessage.Process wrapped = new TestMessage.Process<>(advancedSlip);
                ((ProcRef) allHops.get(idx)).tell(wrapped);
            }
            return Result.ok(null);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Execute the routing slip synchronously with a timeout, collecting replies from all hops.
     *
     * @param message the message with slip
     * @param timeout maximum time to wait for completion
     * @param <P> payload type
     * @param <R> reply type
     * @return a Result containing a list of replies or an exception
     */
    public static <P, R> Result<List<R>, Exception> executeSlipSync(
            MessageWithSlip<P, R> message, Duration timeout) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        try {
            List<R> replies = new ArrayList<>();
            var impl = (MessageWithSlipImpl<P, R>) message;

            if (impl.isComplete() || impl.slip.isEmpty()) {
                return Result.ok(replies);
            }

            // Synchronously ask each hop in sequence using TestMessage.Process wrapper
            for (int i = impl.startIndex; i < impl.slip.size(); i++) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                var hop = (ProcRef) impl.slip.get(i);
                MessageWithSlipImpl<P, R> advancedSlip =
                        new MessageWithSlipImpl<>(impl.payload, impl.slip, i + 1);
                TestMessage.Process<P, R> wrapped = new TestMessage.Process<>(advancedSlip);
                try {
                    @SuppressWarnings("unchecked")
                    R reply = (R) hop.ask(wrapped, timeout).get();
                    replies.add(reply);
                } catch (Exception e) {
                    return Result.err(e);
                }
            }

            return Result.ok(replies);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Execute the routing slip asynchronously, returning an already-completed
     * {@link CompletableFuture}.
     *
     * @param message the message with slip
     * @param <P> payload type
     * @return a completed future containing the Result of {@link #executeSlip(MessageWithSlip)}
     */
    public static <P> CompletableFuture<Result<?, Exception>> executeSlipAsync(
            MessageWithSlip<P, ?> message) {
        Result<?, Exception> result = executeSlip(message);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Get the number of remaining hops.
     *
     * @param message the message with slip
     * @param <P> payload type
     * @return count of remaining hops
     */
    public static <P> int remainingHops(MessageWithSlip<P, ?> message) {
        return message.remainingHops();
    }

    /**
     * Check if the routing slip is complete.
     *
     * @param message the message with slip
     * @param <P> payload type
     * @return true if no more hops remain
     */
    public static <P> boolean isComplete(MessageWithSlip<P, ?> message) {
        return message.isComplete();
    }
}
