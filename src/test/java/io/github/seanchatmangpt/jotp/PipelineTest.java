package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PipelineTest {

    private Pipeline<?, ?> pipeline;

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.close();
        }
    }

    // ── 1. Basic transformation ─────────────────────────────────────────────────

    @Test
    void ofTransformsMessagesCorrectly() throws Exception {
        pipeline = Pipeline.of((String s) -> s.toUpperCase()).build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        CompletableFuture<String> result = p.ask("hello", Duration.ofSeconds(5));
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("HELLO");
    }

    @Test
    void ofTransformsMultipleMessages() throws Exception {
        pipeline = Pipeline.of((String s) -> s.length()).build();

        @SuppressWarnings("unchecked")
        Pipeline<String, Integer> p = (Pipeline<String, Integer>) pipeline;

        assertThat(p.ask("hi", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(p.ask("hello", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS)).isEqualTo(5);
        assertThat(p.ask("world!", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS)).isEqualTo(6);
    }

    // ── 2. ask() returns correct result within timeout ──────────────────────────

    @Test
    void askReturnsCorrectResultWithinTimeout() throws Exception {
        pipeline = Pipeline.of((Integer n) -> n * 2).build();

        @SuppressWarnings("unchecked")
        Pipeline<Integer, Integer> p = (Pipeline<Integer, Integer>) pipeline;

        CompletableFuture<Integer> result = p.ask(21, Duration.ofSeconds(5));
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(42);
    }

    @Test
    void askWithSlowStageReturnsResultBeforeTimeout() throws Exception {
        pipeline =
                Pipeline.of(
                                (String s) -> {
                                    Thread.sleep(50);
                                    return s.toUpperCase();
                                })
                        .build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        CompletableFuture<String> result = p.ask("slow", Duration.ofSeconds(5));
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("SLOW");
    }

    // ── 3. send() processes messages asynchronously (no blocking) ──────────────

    @Test
    void sendProcessesMessagesAsynchronously() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        pipeline =
                Pipeline.of(
                                (String s) -> {
                                    counter.incrementAndGet();
                                    latch.countDown();
                                    return s;
                                })
                        .build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        // send() should return immediately without blocking
        long before = System.nanoTime();
        p.send("a");
        p.send("b");
        p.send("c");
        long elapsed = System.nanoTime() - before;

        // All three sends should be near-instantaneous (< 100ms combined)
        assertThat(elapsed).isLessThan(Duration.ofMillis(100).toNanos());

        // But they should all be processed asynchronously
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter.get()).isEqualTo(3);
    }

    // ── 4. Back-pressure limits in-flight messages ──────────────────────────────

    @Test
    void backpressureLimitsInFlightMessages() throws Exception {
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);

        // Stage blocks until we release it
        pipeline =
                Pipeline.of(
                                (Integer n) -> {
                                    processingStarted.countDown();
                                    try {
                                        releaseProcessing.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return n;
                                })
                        .withBackpressure(2)
                        .build();

        @SuppressWarnings("unchecked")
        Pipeline<Integer, Integer> p = (Pipeline<Integer, Integer>) pipeline;

        // Send first message — it will block in the stage
        Thread sender1 = Thread.ofVirtual().start(() -> p.send(1));
        // Wait for first message to start processing
        assertThat(processingStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Send second message — it will queue (capacity = 2, 1 is in worker, 1 permit left)
        Thread sender2 = Thread.ofVirtual().start(() -> p.send(2));

        // Verify stats show in-flight messages
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> {
                            Pipeline.PipelineStats stats = p.stats();
                            return stats.inFlight() >= 1;
                        });

        // Release the stage
        releaseProcessing.countDown();
        sender1.join(5000);
        sender2.join(5000);
    }

    @Test
    void backpressureCapacityIsRespected() {
        pipeline = Pipeline.of((Integer n) -> n).withBackpressure(10).build();

        @SuppressWarnings("unchecked")
        Pipeline<Integer, Integer> p = (Pipeline<Integer, Integer>) pipeline;

        Pipeline.PipelineStats stats = p.stats();
        // Initially no in-flight messages
        assertThat(stats.inFlight()).isEqualTo(0);
    }

    // ── 5. close() waits for in-flight messages ─────────────────────────────────

    @Test
    void closeWaitsForInFlightMessagesToComplete() throws Exception {
        AtomicInteger processed = new AtomicInteger(0);

        pipeline =
                Pipeline.of(
                                (String s) -> {
                                    Thread.sleep(20);
                                    processed.incrementAndGet();
                                    return s;
                                })
                        .build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        p.send("a");
        p.send("b");
        p.send("c");

        p.close();
        // After close() returns, all in-flight messages should have been processed
        assertThat(processed.get()).isEqualTo(3);
    }

    @Test
    void closedPipelineRejectsSend() {
        pipeline = Pipeline.of((String s) -> s.toUpperCase()).build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        p.close();
        pipeline = null; // prevent double-close in @AfterEach

        assertThatThrownBy(() -> p.send("hello")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closedPipelineRejectsAsk() {
        pipeline = Pipeline.of((String s) -> s.toUpperCase()).build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        p.close();
        pipeline = null; // prevent double-close in @AfterEach

        assertThatThrownBy(() -> p.ask("hello", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── 6. chain() composes two stages ─────────────────────────────────────────

    @Test
    void chainComposesTwoStagesCorrectly() throws Exception {
        pipeline = Pipeline.chain((String s) -> s.toUpperCase(), (String s) -> s + "!").build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        CompletableFuture<String> result = p.ask("hello", Duration.ofSeconds(5));
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("HELLO!");
    }

    // ── 7. source() builder API ─────────────────────────────────────────────────

    @Test
    void sourceBuilderCreatesWorkingPipeline() throws Exception {
        pipeline = Pipeline.source(String.class).map((String s) -> s.toUpperCase()).build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        CompletableFuture<String> result = p.ask("test", Duration.ofSeconds(5));
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("TEST");
    }

    // ── 8. stats() returns meaningful data ─────────────────────────────────────

    @Test
    void statsTracksProcessedCount() throws Exception {
        pipeline = Pipeline.of((String s) -> s.toUpperCase()).build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        p.ask("a", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        p.ask("b", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        p.ask("c", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(p.stats().processed()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void statsFailedCountTracksExceptions() throws Exception {
        pipeline =
                Pipeline.of(
                                (String s) -> {
                                    if (s.equals("bad")) throw new RuntimeException("intentional");
                                    return s;
                                })
                        .build();

        @SuppressWarnings("unchecked")
        Pipeline<String, String> p = (Pipeline<String, String>) pipeline;

        // This should fail
        CompletableFuture<String> failing = p.ask("bad", Duration.ofSeconds(5));
        assertThatThrownBy(() -> failing.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(RuntimeException.class);

        // This should succeed
        p.ask("good", Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            Pipeline.PipelineStats stats = p.stats();
                            assertThat(stats.failed()).isGreaterThanOrEqualTo(1);
                            assertThat(stats.processed()).isGreaterThanOrEqualTo(1);
                        });
    }
}
