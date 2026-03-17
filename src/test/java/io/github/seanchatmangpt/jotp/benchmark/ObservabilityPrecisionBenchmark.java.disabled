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
 * Precision timing benchmark for observability infrastructure.
 *
 * <p>Measures nanosecond-level precision of timing operations with and without observability
 * enabled to validate the zero-overhead principle.
 *
 * <p><strong>Test Execution:</strong>
 *
 * <pre>{@code
 * ./mvnw test -Dtest=ObservabilityPrecisionBenchmark
 * }</pre>
 */
@DisplayName("Observability Precision Benchmark")
class ObservabilityPrecisionBenchmark {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 100_000;


    private List<Long> disabledLatencies;
    private List<Long> enabledLatencies;

    @BeforeEach
    void setUp() {
        disabledLatencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
        enabledLatencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
        System.clearProperty("jotp.observability.enabled");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jotp.observability.enabled");
    }

    @Test
    @DisplayName("Benchmark: Observability Precision - Disabled vs Enabled")
    void measurePrecision() throws Exception {

        // Phase 1: Baseline with observability disabled
        System.clearProperty("jotp.observability.enabled");
        Proc<Integer, String> baselineProc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            baselineProc.tell("warmup");
        }
        Thread.sleep(100);

        // Measurement - disabled
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            baselineProc.tell("message-" + i);
            long end = System.nanoTime();
            disabledLatencies.add(end - start);
        }

        PrecisionTimer.PercentileResult disabledStats =
                PrecisionTimer.calculatePercentiles(
                        disabledLatencies.stream().mapToLong(Long::longValue).toArray());

        baselineProc.stop();
        Thread.sleep(100);

        // Phase 2: With observability enabled
        System.setProperty("jotp.observability.enabled", "true");
        FrameworkEventBus eventBus = FrameworkEventBus.getDefault();
        FrameworkMetrics metrics = FrameworkMetrics.create();
        Proc<Integer, String> enabledProc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            enabledProc.tell("warmup");
        }
        Thread.sleep(100);

        // Measurement - enabled
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            enabledProc.tell("message-" + i);
            long end = System.nanoTime();
            enabledLatencies.add(end - start);
        }

        PrecisionTimer.PercentileResult enabledStats =
                PrecisionTimer.calculatePercentiles(
                        enabledLatencies.stream().mapToLong(Long::longValue).toArray());

        metrics.close();
        enabledProc.stop();

        // Calculate overhead
        double overhead = enabledStats.mean() - disabledStats.mean();
        boolean pass = overhead < 100;

                new String[][] {
                    {"Operation", "Mean (ns)", "StdDev", "p50", "p95", "p99"},
                    {
                        "Disabled path",
                        String.format("%.2f", disabledStats.mean()),
                        String.format("%.2f", calculateStdDev(disabledLatencies)),
                        String.valueOf(disabledStats.p50()),
                        String.valueOf(disabledStats.p95()),
                        String.valueOf(disabledStats.p99())
                    },
                    {
                        "Enabled path",
                        String.format("%.2f", enabledStats.mean()),
                        String.format("%.2f", calculateStdDev(enabledLatencies)),
                        String.valueOf(enabledStats.p50()),
                        String.valueOf(enabledStats.p95()),
                        String.valueOf(enabledStats.p99())
                    }
                });

                Map.of(
                        "Precision",
                        "Nanosecond",
                        "Overhead",
                        String.format("%.2f ns", overhead),
                        "Iterations",
                        String.valueOf(MEASUREMENT_ITERATIONS),
                        "Status",
                        pass ? "PASS" : "FAIL"));

                "Precision timing validated for observability metrics. "
                        + "Zero-overhead principle requires overhead < 100 ns.");

        // Assertion
        assertThat(overhead)
                .as("Observability overhead should be < 100ns for zero-overhead principle")
                .isLessThan(100.0);
    }

    @Test
    @DisplayName("Benchmark: System.nanoTime() Precision")
    void measureNanoTimePrecision() {

        int iterations = 1_000_000;
        long[] latencies = new long[iterations];

        // Measure nanoTime() overhead
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            long end = System.nanoTime();
            latencies[i] = end - start;
        }

        PrecisionTimer.PercentileResult stats = PrecisionTimer.calculatePercentiles(latencies);

                new String[][] {
                    {"Metric", "Value (ns)"},
                    {"Min", String.valueOf(stats.min())},
                    {"Max", String.valueOf(stats.max())},
                    {"Mean", String.format("%.2f", stats.mean())},
                    {"p50", String.valueOf(stats.p50())},
                    {"p95", String.valueOf(stats.p95())},
                    {"p99", String.valueOf(stats.p99())}
                });

                Map.of(
                        "Timer Resolution",
                        "Nanosecond",
                        "Iterations",
                        String.valueOf(iterations),
                        "Platform",
                        System.getProperty("os.name"),
                        "Java",
                        System.getProperty("java.version")));

                "System.nanoTime() provides nanosecond precision for accurate timing measurements.");

        // nanoTime() should be sub-microsecond
        assertThat(stats.mean()).as("nanoTime() overhead should be minimal").isLessThan(500.0);
    }

    private Proc<Integer, String> createTestProcess() {
        BiFunction<Integer, String, Integer> handler =
                (state, msg) -> {
                    if (msg.startsWith("message-")) {
                        return state + 1;
                    }
                    return state;
                };
        return Proc.spawn(0, handler);
    }

    private double calculateStdDev(List<Long> values) {
        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double variance =
                values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0.0);
        return Math.sqrt(variance);
    }
}
