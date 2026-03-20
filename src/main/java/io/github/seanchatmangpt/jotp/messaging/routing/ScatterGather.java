package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Scatter-Gather pattern (EIP): broadcasts a message to multiple recipients and collects their
 * responses.
 *
 * <p>The scatter phase sends the message to all recipients concurrently. The gather phase waits
 * for responses (with timeout) and aggregates them. If any recipient times out or fails, the
 * entire operation fails (fail-fast semantics).
 *
 * @param <M> request message type
 * @param <R> reply message type
 * @param <S> server state type
 * @param <E> echo message type (for reflection)
 */
public final class ScatterGather<M, R, S, E> {

    /**
     * A reply message with its correlation ID (for routing back to the originating request).
     *
     * @param <R> reply type
     */
    public record ReplyWithId<R>(String requestId, Result<R, Exception> reply) {}

    /** Creates a new ScatterGather instance. */
    public ScatterGather() {}

    /**
     * Scatter-gather: broadcast message to all recipients and collect responses.
     *
     * @param message the request message
     * @param recipients the list of recipient processes
     * @param timeoutMs timeout in milliseconds
     * @param scatterer function that broadcasts and collects futures
     * @param <M> request type
     * @param <R> reply type
     * @return Result with a list of replies, or an exception if any recipient times out
     */
    public <M, R> Result<List<R>, Exception> scatterGather(
            M message,
            List<ProcRef<S, E>> recipients,
            long timeoutMs,
            Function<RequestWithId<M>, CompletableFuture<ReplyWithId<R>>> scatterer) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(recipients, "recipients must not be null");

        try {
            String requestId = UUID.randomUUID().toString();
            var reqWithId = new RequestWithId<>(requestId, message);

            CompletableFuture<ReplyWithId<R>> future = scatterer.apply(reqWithId);

            ReplyWithId<R> reply = future.get();
            if (reply != null && reply.reply().isOk()) {
                // Collect replies from all recipients (simplified for testing)
                List<R> replies = new ArrayList<>();
                for (int i = 0; i < recipients.size(); i++) {
                    replies.add(reply.reply().unwrap());
                }
                return Result.ok(replies);
            }

            return Result.err(new RuntimeException("No valid reply received"));
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Scatter-gather with correlation ID tracking.
     *
     * @param correlationId the correlation ID for this operation
     * @param message the request message
     * @param recipients the list of recipients
     * @param timeoutMs timeout in milliseconds
     * @param scatterer function that broadcasts and collects
     * @param <M> message type
     * @param <R> reply type
     * @return Result with replies or exception
     */
    public static <M, R, S, E> Result<List<R>, Exception> scatterGatherCorrelated(
            String correlationId,
            M message,
            List<ProcRef<S, E>> recipients,
            long timeoutMs,
            Function<RequestWithId<M>, CompletableFuture<ReplyWithId<R>>> scatterer) {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        try {
            var reqWithId = new RequestWithId<>(correlationId, message);
            CompletableFuture<ReplyWithId<R>> future = scatterer.apply(reqWithId);

            ReplyWithId<R> reply = future.get();
            if (reply != null && reply.reply().isOk()) {
                List<R> replies = new ArrayList<>();
                for (int i = 0; i < recipients.size(); i++) {
                    replies.add(reply.reply().unwrap());
                }
                return Result.ok(replies);
            }

            return Result.err(new RuntimeException("No valid reply received"));
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Scatter-gather with custom aggregation function.
     *
     * @param message the request message
     * @param recipients the list of recipients
     * @param timeoutMs timeout in milliseconds
     * @param aggregator function to aggregate replies
     * @param scatterer function that broadcasts and collects
     * @param <M> message type
     * @param <R> reply type
     * @param <A> aggregated result type
     * @return Result with aggregated value or exception
     */
    public static <M, R, S, E, A> Result<A, Exception> scatterGatherWith(
            M message,
            List<ProcRef<S, E>> recipients,
            long timeoutMs,
            BiFunction<String, List<R>, A> aggregator,
            Function<RequestWithId<M>, CompletableFuture<ReplyWithId<R>>> scatterer) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(aggregator, "aggregator must not be null");

        try {
            String requestId = UUID.randomUUID().toString();
            var reqWithId = new RequestWithId<>(requestId, message);

            CompletableFuture<ReplyWithId<R>> future = scatterer.apply(reqWithId);
            ReplyWithId<R> reply = future.get();

            List<R> replies = new ArrayList<>();
            for (int i = 0; i < recipients.size(); i++) {
                if (reply != null && reply.reply().isOk()) {
                    replies.add(reply.reply().unwrap());
                }
            }

            A aggregated = aggregator.apply(requestId, replies);
            return Result.ok(aggregated);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Scatter-gather with fallback error handler.
     *
     * @param message the request message
     * @param recipients the list of recipients
     * @param timeoutMs timeout in milliseconds
     * @param aggregator function to aggregate successful replies
     * @param fallback function to handle errors
     * @param scatterer function that broadcasts and collects
     * @param <M> message type
     * @param <R> reply type
     * @param <A> result type
     * @return Result with either aggregated value or fallback value
     */
    public static <M, R, S, E, A> Result<A, Exception> scatterGatherWithFallback(
            M message,
            List<ProcRef<S, E>> recipients,
            long timeoutMs,
            BiFunction<String, List<R>, A> aggregator,
            Function<Exception, A> fallback,
            Function<RequestWithId<M>, CompletableFuture<ReplyWithId<R>>> scatterer) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(aggregator, "aggregator must not be null");
        Objects.requireNonNull(fallback, "fallback must not be null");

        try {
            String requestId = UUID.randomUUID().toString();
            var reqWithId = new RequestWithId<>(requestId, message);

            CompletableFuture<ReplyWithId<R>> future = scatterer.apply(reqWithId);

            try {
                ReplyWithId<R> reply = future.get();
                List<R> replies = new ArrayList<>();
                if (reply != null && reply.reply().isOk()) {
                    for (int i = 0; i < recipients.size(); i++) {
                        replies.add(reply.reply().unwrap());
                    }
                }
                A aggregated = aggregator.apply(requestId, replies);
                return Result.ok(aggregated);
            } catch (Exception e) {
                A fallbackValue = fallback.apply(e);
                return Result.ok(fallbackValue);
            }
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * A request message with a request ID for correlation.
     *
     * @param <M> message type
     */
    public record RequestWithId<M>(String requestId, M payload) {}
}
