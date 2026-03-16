/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import io.github.seanchatmangpt.jotp.observability.FrameworkMetrics;
import io.github.seanchatmangpt.jotp.observability.PrecisionTimer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Simple observability benchmark for quick validation.
 *
 * <p>A lightweight benchmark that validates observability overhead in a single test run. Useful for
 * CI/CD pipelines where full benchmark suites are too slow.
 *
 * <p><strong>Test Execution:</strong>
 *
 * <pre>{@code
 * ./mvnw test -Dtest=SimpleObservabilityBenchmark
 * }</pre>
 */
@DtrTest
@DisplayName("Simple Observability Benchmark")
class SimpleObservabilityBenchmark {

    private static final int ITERATIONS = 50_000;

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        System.clearProperty("jotp.observability.enabled");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jotp.observability.enabled");
    }

    @Test
    @DisplayName("Benchmark: Quick Observability Overhead Check")
    void quickOverheadCheck() throws Exception {
        ctx.sayNextSection("Simple Observability Benchmark");
        ctx.say("Quick validation of observability overhead for CI/CD pipelines.");
        ctx.say("Compares Proc.tell() latency with and without observability.");

        // Phase 1: Baseline (disabled)
        Proc<Integer, String> proc1 = createTestProcess();
        List<Long> baselineLatencies = new ArrayList<>(ITERATIONS);

        // Warmup + measurement
        for (int i = 0; i < 5_000; i++) {
            proc1.tell("warmup");
        }
        Thread.sleep(50);

        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            proc1.tell("msg");
            baselineLatencies.add(System.nanoTime() - start);
        }

        PrecisionTimer.PercentileResult baseline =
                PrecisionTimer.calculatePercentiles(
                        baselineLatencies.stream().mapToLong(Long::longValue).toArray());

        proc1.stop();
        Thread.sleep(50);

        // Phase 2: With observability (enabled)
        System.setProperty("jotp.observability.enabled", "true");
        FrameworkMetrics metrics = FrameworkMetrics.create();
        Proc<Integer, String> proc2 = createTestProcess();
        List<Long> enabledLatencies = new ArrayList<>(ITERATIONS);

        // Warmup + measurement
        for (int i = 0; i < 5_000; i++) {
            proc2.tell("warmup");
        }
        Thread.sleep(50);

        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            proc2.tell("msg");
            enabledLatencies.add(System.nanoTime() - start);
        }

        PrecisionTimer.PercentileResult enabled =
                PrecisionTimer.calculatePercentiles(
                        enabledLatencies.stream().mapToLong(Long::longValue).toArray());

        metrics.close();
        proc2.stop();

        // Calculate overhead
        double overhead = enabled.mean() - baseline.mean();
        boolean pass = overhead < 100 && enabled.p95() < 1_000;

        ctx.sayTable(
                new String[][] {
                    {"Configuration", "Mean (ns)", "p50 (ns)", "p95 (ns)", "p99 (ns)"},
                    {
                        "Disabled",
                        String.format("%.2f", baseline.mean()),
                        String.valueOf(baseline.p50()),
                        String.valueOf(baseline.p95()),
                        String.valueOf(baseline.p99())
                    },
                    {
                        "Enabled",
                        String.format("%.2f", enabled.mean()),
                        String.valueOf(enabled.p50()),
                        String.valueOf(enabled.p95()),
                        String.valueOf(enabled.p99())
                    }
                });

        ctx.sayKeyValue(
                Map.of(
                        "Overhead",
                        String.format("%.2f ns", overhead),
                        "p95 Target",
                        "< 1000 ns",
                        "Overhead Target",
                        "< 100 ns",
                        "Iterations",
                        String.valueOf(ITERATIONS),
                        "Status",
                        pass ? "PASS" : "FAIL"));

        ctx.sayNote(
                "Simple benchmark validates zero-overhead principle: overhead < 100ns, p95 < 1us.");

        // Assertions
        assertThat(overhead).as("Overhead should be < 100ns").isLessThan(100.0);
        assertThat(enabled.p95()).as("p95 should be < 1us").isLessThan(1_000L);
    }

    @Test
    @DisplayName("Benchmark: Event Bus Publishing Overhead")
    void eventBusPublishOverhead() throws Exception {
        ctx.sayNextSection("Event Bus Publishing Overhead");
        ctx.say("Measures the overhead of publishing events to the FrameworkEventBus.");

        System.setProperty("jotp.observability.enabled", "true");
        FrameworkEventBus eventBus = FrameworkEventBus.getDefault();

        // Create sample event
        FrameworkEventBus.FrameworkEvent.ProcessCreated event =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        java.time.Instant.now(), "test-proc", "Proc");

        List<Long> latencies = new ArrayList<>(ITERATIONS);

        // Warmup
        for (int i = 0; i < 5_000; i++) {
            eventBus.publish(event);
        }
        Thread.sleep(50);

        // Measurement
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            eventBus.publish(event);
            latencies.add(System.nanoTime() - start);
        }

        PrecisionTimer.PercentileResult stats =
                PrecisionTimer.calculatePercentiles(
                        latencies.stream().mapToLong(Long::longValue).toArray());

        boolean pass = stats.mean() < 500;

        ctx.sayTable(
                new String[][] {
                    {"Metric", "Value (ns)"},
                    {"Mean", String.format("%.2f", stats.mean())},
                    {"p50", String.valueOf(stats.p50())},
                    {"p95", String.valueOf(stats.p95())},
                    {"p99", String.valueOf(stats.p99())},
                    {"Min", String.valueOf(stats.min())},
                    {"Max", String.valueOf(stats.max())}
                });

        ctx.sayKeyValue(
                Map.of(
                        "Event Type",
                        "ProcessCreated",
                        "Iterations",
                        String.valueOf(ITERATIONS),
                        "Subscriber Count",
                        String.valueOf(eventBus.getSubscriberCount()),
                        "Status",
                        pass ? "PASS" : "FAIL"));

        ctx.sayNote("Event bus publishing should be fast even with observability enabled.");

        assertThat(stats.mean()).as("Event bus publish should be < 500ns mean").isLessThan(500.0);
    }

    private Proc<Integer, String> createTestProcess() {
        BiFunction<Integer, String, Integer> handler = (state, msg) -> state + 1;
        return Proc.spawn(0, handler);
    }
}
