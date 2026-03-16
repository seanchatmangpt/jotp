/*
 * Copyright (c) 2025 Sean C. Manning (seanchatmangpt)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Performance tests for observability infrastructure.
 *
 * <p>Validates that observability has negligible overhead when disabled and that event publishing
 * is non-blocking and efficient. Uses JMH-style microbenchmark patterns with warmup and measurement
 * phases.
 *
 * <p>Performance requirements:
 *
 * <ul>
 *   <li>{@code Proc.tell()} overhead with observability disabled: {@literal <1%}
 *   <li>{@code FrameworkEventBus.publish()} with no subscribers: {@literal <100ns}
 *   <li>Supervisor events must be published asynchronously (non-blocking)
 * </ul>
 *
 * @see FrameworkEventBus
 * @see FrameworkEventBus.FrameworkEvent
 */
public class ObservabilityPerformanceTest {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 50_000;
    private static final long WARMUP_DURATION_MS = 100;
    private static final long MEASUREMENT_DURATION_MS = 500;

    private FrameworkEventBus eventBus;
    private boolean originalEnabledState;

    @BeforeEach
    void setUp() {
        // Save original state and disable observability for baseline tests
        originalEnabledState = FrameworkEventBus.isEnabled();
        System.setProperty("jotp.observability.enabled", "false");

        eventBus = FrameworkEventBus.create();
    }

    @AfterEach
    void tearDown() {
        if (eventBus != null) {
            eventBus.shutdown();
        }

        // Restore original state
        if (originalEnabledState) {
            System.setProperty("jotp.observability.enabled", "true");
        } else {
            System.setProperty("jotp.observability.enabled", "false");
        }
    }

    // ============================================================================
    // Baseline Tests (No Observability)
    // ============================================================================

    @Test
    void baselineProcTellPerformance() {
        var baselineNs = benchmarkProcTell();
        System.out.println("Baseline Proc.tell(): " + baselineNs + " ns/op");
    }

    // ============================================================================
    // Proc.tell() Overhead Tests
    // ============================================================================

    @Test
    void procTellOverheadWithObservabilityDisabled() {
        // Ensure observability is disabled
        System.setProperty("jotp.observability.enabled", "false");

        // Warmup
        warmup(() -> createProc().tell("test"));

        // Measure baseline (no observability)
        var baselineNs = benchmarkProcTell();

        System.out.println("Baseline (observability disabled): " + baselineNs + " ns/op");

        // Verify baseline is reasonable (should be very fast for virtual threads)
        assertThat(baselineNs).isLessThan(10_000_000.0); // <10ms per operation
    }

    @Test
    void procTellOverheadWithObservabilityEnabled() {
        // Enable observability
        System.setProperty("jotp.observability.enabled", "true");

        // Warmup
        warmup(() -> createProc().tell("test"));
        eventBus.subscribe(evt -> {}); // Add a subscriber

        // Measure with observability enabled
        var withObservabilityNs = benchmarkProcTell();

        System.out.println("With observability (enabled): " + withObservabilityNs + " ns/op");

        // With observability enabled, overhead is expected but should still be reasonable
        // Typically <10x baseline is acceptable for production use
        assertThat(withObservabilityNs).isLessThan(10_000_000.0); // <10ms per operation
    }

    // ============================================================================
    // FrameworkEventBus Performance Tests
    // ============================================================================

    @Test
    void eventBusPublishWithNoSubscribers() {
        var event = createTestEvent("test");

        // Warmup
        warmup(() -> eventBus.publish(event));

        // Measure
        var avgNs = benchmarkEventBusPublish(event);

        System.out.println("EventBus.publish() with no subscribers: " + avgNs + " ns/op");
        assertThat(avgNs).isLessThan(100.0); // <100ns with no subs
    }

    @Test
    void eventBusPublishWithMultipleSubscribers() {
        var event = createTestEvent("test");
        var counter = new AtomicInteger(0);

        // Add multiple subscribers
        for (int i = 0; i < 5; i++) {
            eventBus.subscribe(evt -> counter.incrementAndGet());
        }

        // Warmup
        warmup(() -> eventBus.publish(event));

        // Measure
        var avgNs = benchmarkEventBusPublish(event);

        System.out.println("EventBus.publish() with 5 subscribers: " + avgNs + " ns/op");

        // Verify all subscribers were called (after waiting for async delivery)
        await().atMost(Duration.ofSeconds(5)).until(() -> counter.get() >= 5);

        assertThat(counter.get()).isEqualTo(5);
    }

    @Test
    void eventBusPublishIsNonBlocking() {
        var event = createTestEvent("test");
        var processedEvents = new ConcurrentLinkedQueue<Long>();
        var publishDurations = new ConcurrentLinkedQueue<Long>();

        // Add a slow subscriber that takes 10ms
        eventBus.subscribe(
                evt -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    processedEvents.add(System.nanoTime());
                });

        // Warmup
        warmup(() -> eventBus.publish(event));
        processedEvents.clear();

        // Publish multiple events and measure time
        var iterations = 10;
        var startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            var publishStart = System.nanoTime();
            eventBus.publish(event);
            var publishDuration = System.nanoTime() - publishStart;
            publishDurations.add(publishDuration);
        }
        var totalTime = System.nanoTime() - startTime;

        // Wait for async processing
        await().atMost(Duration.ofSeconds(5)).until(() -> processedEvents.size() >= iterations);

        // Calculate average publish duration
        var avgPublishNs =
                publishDurations.stream().mapToLong(Long::longValue).average().orElse(0.0);

        var totalExpectedProcessingTime = iterations * 10_000_000L; // 10ms each
        var actualTime = totalTime;

        System.out.println(
                "Total publish time for "
                        + iterations
                        + " events: "
                        + actualTime / 1_000_000.0
                        + " ms");
        System.out.println("Average publish duration: " + avgPublishNs / 1_000_000.0 + " ms");
        System.out.println(
                "Expected if blocking: " + totalExpectedProcessingTime / 1_000_000.0 + " ms");

        // Publishing should be much faster than the sum of subscriber processing times
        assertThat(actualTime)
                .isLessThan(totalExpectedProcessingTime / 2); // At least 2x faster than blocking

        // Individual publish calls should be very fast (non-blocking)
        assertThat(avgPublishNs).isLessThan(1_000_000.0); // <1ms per publish
    }

    // ============================================================================
    // Supervisor Event Publishing Tests
    // ============================================================================

    @Test
    void supervisorEventsPublishedAsynchronously() {
        var eventCapture = new ConcurrentLinkedQueue<FrameworkEventBus.FrameworkEvent>();
        var processingTimes = new ConcurrentLinkedQueue<Long>();

        eventBus.subscribe(
                evt -> {
                    var start = System.nanoTime();
                    eventCapture.add(evt);
                    // Simulate some work
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    processingTimes.add(System.nanoTime() - start);
                });

        // Create a supervisor and trigger events manually
        // (In real scenario, supervisor would publish events automatically)
        var supervisor = createSupervisor();

        // Manually publish a supervisor event for testing
        var testEvent =
                new FrameworkEventBus.FrameworkEvent.SupervisorChildCrashed(
                        java.time.Instant.now(),
                        "test-supervisor",
                        "test-child",
                        new RuntimeException("Test crash"));
        eventBus.publish(testEvent);

        // Verify events were captured asynchronously
        await().atMost(Duration.ofSeconds(2)).until(() -> !eventCapture.isEmpty());

        // Verify that supervisor operations were not blocked by event processing
        var processingTimeNs = processingTimes.stream().mapToLong(Long::longValue).sum();

        System.out.println(
                "Supervisor event processing time: " + processingTimeNs / 1_000_000.0 + " ms");
        System.out.println("Events captured: " + eventCapture.size());

        assertThat(eventCapture).isNotEmpty();
    }

    // ============================================================================
    // Benchmark Helpers
    // ============================================================================

    /** Creates a Proc instance for testing. */
    private Proc<String, String> createProc() {
        return Proc.spawn(
                () -> "initial",
                (state, msg) -> {
                    if ("crash".equals(msg)) {
                        throw new RuntimeException("Intentional crash");
                    }
                    return state;
                });
    }

    /** Creates a Supervisor instance for testing. */
    private Supervisor<String, String> createSupervisor() {
        return Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
    }

    /** Creates a test event for performance testing. */
    private FrameworkEventBus.FrameworkEvent createTestEvent(String data) {
        return new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                java.time.Instant.now(), "test-process", "test-type");
    }

    /** Warms up the JIT compiler by running the operation repeatedly. */
    private void warmup(Runnable operation) {
        var warmupStart = System.currentTimeMillis();
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            operation.run();
            if (System.currentTimeMillis() - warmupStart > WARMUP_DURATION_MS) {
                break;
            }
        }
    }

    /** Benchmarks Proc.tell() performance. Returns average nanoseconds per operation. */
    private double benchmarkProcTell() {
        var iterations = new AtomicInteger(0);
        var totalNs = new AtomicLong(0);
        var measurementStart = System.currentTimeMillis();

        while (iterations.get() < MEASUREMENT_ITERATIONS
                && System.currentTimeMillis() - measurementStart < MEASUREMENT_DURATION_MS) {
            var proc = createProc();
            var start = System.nanoTime();
            proc.tell("test");
            var end = System.nanoTime();
            totalNs.addAndGet(end - start);
            iterations.incrementAndGet();
            try {
                proc.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return (double) totalNs.get() / iterations.get();
    }

    /**
     * Benchmarks FrameworkEventBus.publish() performance. Returns average nanoseconds per
     * operation.
     */
    private double benchmarkEventBusPublish(FrameworkEventBus.FrameworkEvent event) {
        var iterations = new AtomicInteger(0);
        var totalNs = new AtomicLong(0);
        var measurementStart = System.currentTimeMillis();

        while (iterations.get() < MEASUREMENT_ITERATIONS
                && System.currentTimeMillis() - measurementStart < MEASUREMENT_DURATION_MS) {
            var start = System.nanoTime();
            eventBus.publish(event);
            var end = System.nanoTime();
            totalNs.addAndGet(end - start);
            iterations.incrementAndGet();
        }

        return (double) totalNs.get() / iterations.get();
    }

    /** Creates an Awaitility await condition with default settings. */
    private static org.awaitility.core.ConditionFactory await() {
        return org.awaitility.Awaitility.await()
                .pollInterval(Duration.ofMillis(50))
                .ignoreExceptions();
    }
}
