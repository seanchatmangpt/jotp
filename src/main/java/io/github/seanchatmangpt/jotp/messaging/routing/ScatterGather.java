package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Scatter-Gather messaging pattern: fan-out a request to multiple recipients and collect all
 * replies.
 *
 * <p>Vernon: "Send a message to multiple recipients simultaneously, wait for all replies to arrive
 * before proceeding. If any recipient fails to reply within the timeout or responds with an error,
 * the entire gather operation fails (fail-fast semantics)."
 *
 * <p>Uses JOTP's {@link Parallel} for concurrent request-reply with automatic cancellation of
 * pending tasks on the first failure.
 *
 * <p>Example:
 *
 * <pre>{@code
 * var sg = new ScatterGather<>();
 * var request = new VoteRequest(proposalId, deadline);
 * var replies = sg.scatterGather(request, voterRefs, Duration.ofSeconds(5));
 * // Returns List<VoteReply> or fails fast on first timeout/error
 * }</pre>
 *
 * @param <Req> request message type
 * @param <Rep> reply message type
 * @param <S> process state type (generic, not used in scatter-gather but kept for flexibility)
 * @param <M> process message type
 */
public class ScatterGather<Req, Rep, S, M> {

    /**
     * Request wrapper: correlates outgoing requests with incoming replies via requestId.
     *
     * <p>Each request carries a unique ID that the recipient echoes back in the reply, enabling
     * correlation even if replies arrive out-of-order.
     *
     * @param <Req> request payload type
     */
    public record RequestWithId<Req>(String requestId, Req payload) {}

    /**
     * Reply wrapper: correlates with the original request via requestId.
     *
     * <p>The reply may succeed (carrying a value) or fail (carrying an error), signaling whether
     * this recipient's contribution to the gather is valid.
     *
     * @param <Rep> reply payload type
     */
    public record ReplyWithId<Rep>(String requestId, Result<Rep, String> result) {}

    /**
     * Scatter-Gather: send request to all recipients and collect replies.
     *
     * <p>Sends {@code request} to each recipient in {@code recipients}, waits for all to reply
     * within {@code timeout}, and returns the results in the order of recipients. If any recipient
     * times out or responds with a failure result, the entire operation fails immediately
     * (fail-fast).
     *
     * @param request the request to scatter
     * @param recipients list of process references to send to
     * @param timeout maximum time to wait for all replies
     * @param requestMapper function to wrap the request with ID and send to recipient
     * @return {@code Result.success(replies)} with replies in recipient order, or {@code
     *     Result.failure(exception)} on first timeout/failure
     */
    public Result<List<Rep>, Exception> scatterGather(
            Req request,
            List<ProcRef<S, M>> recipients,
            Duration timeout,
            Function<RequestWithId<Req>, CompletableFuture<ReplyWithId<Rep>>> requestMapper) {
        try {
            var requestId = UUID.randomUUID().toString();
            var requestWithId = new RequestWithId<>(requestId, request);

            // Create tasks: each task sends the request and waits for the reply
            var tasks =
                    recipients.stream()
                            .map(
                                    recipient ->
                                            (java.util.function.Supplier<Rep>)
                                                    () -> {
                                                        try {
                                                            var replyFuture =
                                                                    requestMapper.apply(
                                                                            requestWithId);
                                                            var reply =
                                                                    replyFuture
                                                                            .orTimeout(
                                                                                    timeout
                                                                                            .toMillis(),
                                                                                    java.util
                                                                                            .concurrent
                                                                                            .TimeUnit
                                                                                            .MILLISECONDS)
                                                                            .join();

                                                            // Check if reply is successful
                                                            return switch (reply.result()) {
                                                                case Result.Ok<Rep, String> ok ->
                                                                        ok.value();
                                                                case Result.Err<Rep, String> err ->
                                                                        throw new RuntimeException(
                                                                                "Recipient error: "
                                                                                        + err
                                                                                                .error());
                                                            };
                                                        } catch (Exception e) {
                                                            if (e.getCause()
                                                                    instanceof
                                                                    java.util.concurrent
                                                                                    .TimeoutException
                                                                            te) {
                                                                throw new RuntimeException(
                                                                        "Reply timeout", te);
                                                            }
                                                            throw new RuntimeException(
                                                                    "Reply failed", e);
                                                        }
                                                    })
                            .toList();

            // Use Parallel.all for fail-fast semantics
            return Parallel.all(tasks);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Aggregate replies using a custom aggregator function.
     *
     * <p>After gathering all replies, apply a reduction function to combine them into a single
     * result. Useful for voting, consensus, or other aggregation patterns.
     *
     * <p>Example: sum votes, find consensus, validate quorum.
     *
     * @param replies list of gathered replies
     * @param identity initial aggregation value
     * @param aggregator {@code (accumulator, reply) -> nextAccumulator}
     * @param <Agg> aggregation result type
     * @return aggregated result
     */
    public <Agg> Agg collectWith(
            List<Rep> replies,
            Agg identity,
            java.util.function.BiFunction<Agg, Rep, Agg> aggregator) {
        var result = identity;
        for (Rep reply : replies) {
            result = aggregator.apply(result, reply);
        }
        return result;
    }

    /**
     * Scatter-Gather with an explicit correlation ID.
     *
     * @param correlationId the correlation ID to use for this request
     * @param request the request to scatter
     * @param recipients list of process references to send to
     * @param timeoutMillis maximum time to wait in milliseconds
     * @param requestMapper function to wrap the request with ID and send to recipient
     * @return {@code Result.success(replies)} or {@code Result.failure(exception)}
     */
    public static <Req, Rep, S, M> Result<List<Rep>, Exception> scatterGatherCorrelated(
            String correlationId,
            Req request,
            List<ProcRef<S, M>> recipients,
            long timeoutMillis,
            Function<RequestWithId<Req>, CompletableFuture<ReplyWithId<Rep>>> requestMapper) {
        var sg = new ScatterGather<Req, Rep, S, M>();
        return sg.scatterGather(
                request, recipients, Duration.ofMillis(timeoutMillis), requestMapper);
    }

    /**
     * Scatter-Gather with a custom aggregator function.
     *
     * @param request the request to scatter
     * @param recipients list of process references to send to
     * @param timeoutMillis maximum time to wait in milliseconds
     * @param aggregator function to aggregate replies into a single value
     * @param requestMapper function to wrap the request with ID and send to recipient
     * @return {@code Result.success(aggregated)} or {@code Result.failure(exception)}
     */
    public static <Req, Rep, S, M, Agg> Result<Agg, Exception> scatterGatherWith(
            Req request,
            List<ProcRef<S, M>> recipients,
            long timeoutMillis,
            java.util.function.BiFunction<String, List<Rep>, Agg> aggregator,
            Function<RequestWithId<Req>, CompletableFuture<ReplyWithId<Rep>>> requestMapper) {
        var sg = new ScatterGather<Req, Rep, S, M>();
        var requestId = UUID.randomUUID().toString();
        var requestWithId = new RequestWithId<>(requestId, request);
        var baseResult =
                sg.scatterGather(
                        request, recipients, Duration.ofMillis(timeoutMillis), requestMapper);
        return switch (baseResult) {
            case Result.Ok<List<Rep>, Exception> ok ->
                    Result.ok(aggregator.apply(requestId, ok.value()));
            case Result.Err<List<Rep>, Exception> err -> Result.failure(err.error());
        };
    }

    /**
     * Scatter-Gather with a custom aggregator and fallback on failure.
     *
     * @param request the request to scatter
     * @param recipients list of process references to send to
     * @param timeoutMillis maximum time to wait in milliseconds
     * @param aggregator function to aggregate replies into a single value
     * @param fallback function to produce a fallback value on failure
     * @param requestMapper function to wrap the request with ID and send to recipient
     * @return {@code Result.success(aggregated or fallback)}
     */
    public static <Req, Rep, S, M, Agg> Result<Agg, Exception> scatterGatherWithFallback(
            Req request,
            List<ProcRef<S, M>> recipients,
            long timeoutMillis,
            java.util.function.BiFunction<String, List<Rep>, Agg> aggregator,
            java.util.function.Function<Exception, Agg> fallback,
            Function<RequestWithId<Req>, CompletableFuture<ReplyWithId<Rep>>> requestMapper) {
        var sg = new ScatterGather<Req, Rep, S, M>();
        var requestId = UUID.randomUUID().toString();
        var baseResult =
                sg.scatterGather(
                        request, recipients, Duration.ofMillis(timeoutMillis), requestMapper);
        return switch (baseResult) {
            case Result.Ok<List<Rep>, Exception> ok ->
                    Result.ok(aggregator.apply(requestId, ok.value()));
            case Result.Err<List<Rep>, Exception> err -> Result.ok(fallback.apply(err.error()));
        };
    }
}
