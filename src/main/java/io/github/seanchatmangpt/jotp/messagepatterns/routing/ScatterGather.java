package io.github.seanchatmangpt.jotp.messagepatterns.routing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Scatter-Gather pattern: broadcasts a request to multiple recipients and aggregates the responses.
 *
 * <p>Enterprise Integration Pattern: <em>Scatter-Gather</em> (EIP §8.10). Combines a Recipient List
 * (scatter) with an Aggregator (gather) — the request is fanned out to N suppliers and responses
 * are collected until all arrive or a timeout elapses.
 *
 * <p>Erlang analog: fan-out via {@code [spawn(fun() -> Pid ! {self(), Request} end) || Pid <-
 * Suppliers]} followed by a receive loop collecting replies with {@code after Timeout -> done}.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka). In the original, {@code
 * MountaineeringSuppliesOrderProcessor} scatters {@code RequestPriceQuote} to multiple suppliers
 * and a {@code PriceQuoteAggregator} collects quotes with a 2-second timeout.
 *
 * @param <T> request type
 * @param <R> individual response type
 */
public final class ScatterGather<T, R> {

    private final List<Function<T, R>> handlers;
    private final Duration timeout;

    /**
     * Creates a scatter-gather.
     *
     * @param handlers the processing functions (one per recipient)
     * @param timeout maximum time to wait for all responses
     */
    public ScatterGather(List<Function<T, R>> handlers, Duration timeout) {
        this.handlers = List.copyOf(handlers);
        this.timeout = timeout;
    }

    /**
     * Scatter the request and gather responses.
     *
     * @param request the request to broadcast
     * @return the list of gathered responses (may be fewer than handlers if timeout)
     */
    public List<R> scatterAndGather(T request) {
        List<CompletableFuture<R>> futures =
                handlers.stream()
                        .map(handler -> CompletableFuture.supplyAsync(() -> handler.apply(request)))
                        .toList();

        var results = new ArrayList<R>();
        for (CompletableFuture<R> future : futures) {
            try {
                R result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                results.add(result);
            } catch (Exception e) {
                // timeout or error — skip this response
            }
        }
        return results;
    }

    /** Returns a builder for constructing a ScatterGather. */
    public static <T, R> Builder<T, R> builder() {
        return new Builder<>();
    }

    /** Fluent builder. */
    public static final class Builder<T, R> {
        private final List<Function<T, R>> handlers = new ArrayList<>();
        private Duration timeout = Duration.ofSeconds(5);

        public Builder<T, R> addHandler(Function<T, R> handler) {
            handlers.add(handler);
            return this;
        }

        public Builder<T, R> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public ScatterGather<T, R> build() {
            return new ScatterGather<>(handlers, timeout);
        }
    }
}
