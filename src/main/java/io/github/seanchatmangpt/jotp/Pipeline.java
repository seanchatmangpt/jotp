package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Type-safe dataflow pipeline that composes processing stages with back-pressure.
 *
 * <p>Inspired by Erlang process pipelines and Kafka Streams, but implemented in pure Java 26 with
 * virtual threads and sealed types. Each stage runs in its own virtual thread, communicating via
 * bounded queues for back-pressure.
 *
 * <p><strong>Key design decisions:</strong>
 *
 * <ul>
 *   <li>Each stage wraps a {@link Stage} function running on a virtual thread
 *   <li>Back-pressure is enforced via a {@link Semaphore} limiting in-flight messages
 *   <li>{@link #send(Object)} is fire-and-forget (non-blocking, subject to back-pressure)
 *   <li>{@link #ask(Object, Duration)} returns a {@link CompletableFuture} for request/response
 *   <li>{@link #close()} drains in-flight messages before shutdown
 * </ul>
 *
 * <p><strong>Example usage:</strong>
 *
 * <pre>{@code
 * Pipeline<String, String> pipeline = Pipeline.of(String::toUpperCase)
 *     .withBackpressure(100)
 *     .build();
 *
 * pipeline.send("hello");  // fire-and-forget
 * CompletableFuture<String> result = pipeline.ask("world", Duration.ofSeconds(5));
 * }</pre>
 *
 * @param <I> input type
 * @param <O> output type
 */
public final class Pipeline<I, O> implements AutoCloseable {

    /**
     * A single processing stage — pure function from input to output.
     *
     * @param <A> input type
     * @param <B> output type
     */
    @FunctionalInterface
    public interface Stage<A, B> {
        B process(A input) throws Exception;
    }

    /**
     * Envelope carrying a message through the pipeline with an optional reply handle and timing
     * information.
     */
    private record Envelope<T>(T value, CompletableFuture<Object> reply, Instant enqueuedAt) {
        Envelope(T value, CompletableFuture<Object> reply) {
            this(value, reply, Instant.now());
        }
    }

    /** Statistics snapshot for this pipeline. */
    public record PipelineStats(long processed, long failed, long inFlight, Duration avgLatency) {}

    // ── Internal state ──────────────────────────────────────────────────────────

    private final Stage<I, O> stage;
    private final int backpressureLimit;

    /** Bounded queue feeding the worker virtual thread. */
    private final BlockingQueue<Envelope<I>> inputQueue;

    /** Semaphore enforcing the in-flight limit (back-pressure). */
    private final Semaphore backpressure;

    private final LongAdder processed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();

    private volatile boolean closing = false;
    private final Thread workerThread;

    // ── Private constructor ─────────────────────────────────────────────────────

    private Pipeline(Stage<I, O> stage, int backpressureLimit) {
        this.stage = stage;
        this.backpressureLimit = backpressureLimit;
        this.inputQueue = new LinkedTransferQueue<>();
        this.backpressure = new Semaphore(backpressureLimit);
        this.workerThread = Thread.ofVirtual().name("pipeline-worker").start(this::loop);
    }

    // ── Worker loop ─────────────────────────────────────────────────────────────

    private void loop() {
        while (!closing || !inputQueue.isEmpty()) {
            try {
                Envelope<I> env = inputQueue.poll(50, TimeUnit.MILLISECONDS);
                if (env == null) continue;

                try {
                    O result = stage.process(env.value());
                    long latency = Duration.between(env.enqueuedAt(), Instant.now()).toNanos();
                    totalLatencyNanos.add(latency);
                    processed.increment();

                    if (env.reply() != null) {
                        env.reply().complete(result);
                    }
                } catch (Exception e) {
                    failed.increment();
                    if (env.reply() != null) {
                        env.reply().completeExceptionally(e);
                    }
                } finally {
                    backpressure.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Drain remaining items, completing replies exceptionally
        Envelope<I> leftover;
        while ((leftover = inputQueue.poll()) != null) {
            backpressure.release();
            if (leftover.reply() != null) {
                leftover.reply()
                        .completeExceptionally(
                                new IllegalStateException(
                                        "Pipeline closed before message was processed"));
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget: enqueue {@code input} for asynchronous processing.
     *
     * <p>Blocks only if the back-pressure limit is reached (caller is throttled). Once capacity is
     * available, the call returns immediately without waiting for the result.
     *
     * @param input the value to process
     * @throws IllegalStateException if the pipeline is closed
     */
    public void send(I input) {
        if (closing) throw new IllegalStateException("Pipeline is closed");
        acquireBackpressure();
        inputQueue.add(new Envelope<>(input, null));
    }

    /**
     * Request-response: enqueue {@code input} and return a future that completes with the result.
     *
     * <p>Blocks only if the back-pressure limit is reached, then returns immediately with a future.
     *
     * @param input the value to process
     * @param timeout maximum time to wait for the result
     * @return a {@link CompletableFuture} that completes with the output or times out
     * @throws IllegalStateException if the pipeline is closed
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<O> ask(I input, Duration timeout) {
        if (closing) throw new IllegalStateException("Pipeline is closed");
        acquireBackpressure();
        var reply = new CompletableFuture<Object>();
        inputQueue.add(new Envelope<>(input, reply));
        return reply.thenApply(o -> (O) o).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Returns a snapshot of current pipeline statistics.
     *
     * @return {@link PipelineStats} with counters and average latency
     */
    public PipelineStats stats() {
        long p = processed.sum();
        long f = failed.sum();
        long inFlight = backpressureLimit - backpressure.availablePermits();
        Duration avg = p > 0 ? Duration.ofNanos(totalLatencyNanos.sum() / p) : Duration.ZERO;
        return new PipelineStats(p, f, inFlight, avg);
    }

    /**
     * Gracefully shut down this pipeline.
     *
     * <p>Sets the closing flag so no new messages are accepted, then waits for the worker thread to
     * drain the remaining queue. Pending {@code ask} futures that haven't been processed are
     * completed exceptionally.
     */
    @Override
    public void close() {
        closing = true;
        try {
            workerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerThread.interrupt();
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private void acquireBackpressure() {
        try {
            backpressure.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for back-pressure capacity", e);
        }
    }

    // ── Static factories ────────────────────────────────────────────────────────

    /**
     * Create a single-stage pipeline from a pure function.
     *
     * <p>The pipeline uses a default back-pressure limit of {@code Integer.MAX_VALUE} (unbounded).
     * Use {@link Builder#withBackpressure(int)} to set a limit.
     *
     * @param transform the transformation function
     * @param <I> input type
     * @param <O> output type
     * @return a new {@link Builder} configured with this stage
     */
    public static <I, O> Builder<I, O> of(Stage<I, O> transform) {
        return new Builder<>(transform);
    }

    /**
     * Begin a fluent pipeline definition with an explicit input type token.
     *
     * <p>Use when you need to specify the input type explicitly, rather than inferring it from a
     * stage function.
     *
     * @param inputType class token for the input type (for type inference)
     * @param <I> input type
     * @return an intermediate {@link SourceBuilder} to add the first stage
     */
    public static <I> SourceBuilder<I> source(Class<I> inputType) {
        return new SourceBuilder<>();
    }

    /**
     * Create a two-stage pipeline by chaining two {@link Stage} functions.
     *
     * <p>The intermediate type {@code M} is inferred from the output of {@code stage1} and input of
     * {@code stage2}. Both stages run sequentially within the same virtual-thread worker.
     *
     * @param stage1 first stage: {@code I -> M}
     * @param stage2 second stage: {@code M -> O}
     * @param <I> input type
     * @param <M> intermediate type
     * @param <O> output type
     * @return a {@link Builder} for the composed pipeline
     */
    public static <I, M, O> Builder<I, O> chain(Stage<I, M> stage1, Stage<M, O> stage2) {
        Stage<I, O> composed = input -> stage2.process(stage1.process(input));
        return new Builder<>(composed);
    }

    // ── Builder ─────────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link Pipeline}.
     *
     * @param <I> pipeline input type
     * @param <O> pipeline output type
     */
    public static final class Builder<I, O> {
        private final Stage<I, O> stage;
        private int capacity = Integer.MAX_VALUE;

        private Builder(Stage<I, O> stage) {
            this.stage = stage;
        }

        /**
         * Set the maximum number of in-flight messages (back-pressure limit).
         *
         * <p>When the limit is reached, callers to {@link Pipeline#send} and {@link Pipeline#ask}
         * will block until capacity is available.
         *
         * @param capacity maximum in-flight messages (must be positive)
         * @return this builder
         */
        public Builder<I, O> withBackpressure(int capacity) {
            if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
            return this;
        }

        /**
         * Build and start the pipeline.
         *
         * @return a running {@link Pipeline}
         */
        public Pipeline<I, O> build() {
            return new Pipeline<>(stage, capacity);
        }
    }

    /**
     * Intermediate builder returned by {@link Pipeline#source(Class)}.
     *
     * @param <I> input type
     */
    public static final class SourceBuilder<I> {
        private SourceBuilder() {}

        /**
         * Add the single processing stage.
         *
         * @param transform the stage function
         * @param <O> output type
         * @return a {@link Builder} for the pipeline
         */
        public <O> Builder<I, O> map(Stage<I, O> transform) {
            return new Builder<>(transform);
        }
    }
}
