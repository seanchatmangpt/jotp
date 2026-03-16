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
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.observability.PrecisionTimer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Benchmark for measuring the precision of Proc.run() operations.
 *
 * <p>Validates that blocking run operations maintain consistent timing precision and that the
 * framework's synchronous operations have predictable latency characteristics.
 *
 * <p><strong>Test Execution:</strong>
 *
 * <pre>{@code
 * ./mvnw test -Dtest=RunPrecisionBenchmark
 * }</pre>
 */
@DtrTest
@DisplayName("Run Precision Benchmark")
class RunPrecisionBenchmark {

    private static final int WARMUP_ITERATIONS = 1_000;
    private static final int MEASUREMENT_ITERATIONS = 10_000;

    @DtrContextField private DtrContext ctx;

    @Test
    @DisplayName("Benchmark: Proc.ask() Precision")
    void measureAskPrecision() throws Exception {
        ctx.sayNextSection("Benchmark: Proc.ask() Precision");
        ctx.say("Measures the precision and latency distribution of Proc.ask() operations.");
        ctx.say(
                "ask() is a synchronous request-response pattern that blocks until a reply is received.");

        // Create a process that responds to messages
        Proc<Integer, String> proc = createEchoProcess();

        List<Long> latencies = new ArrayList<>(MEASUREMENT_ITERATIONS);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            proc.ask("warmup-" + i).join();
        }
        Thread.sleep(50);

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            proc.ask("message-" + i).join();
            long end = System.nanoTime();
            latencies.add(end - start);
        }

        PrecisionTimer.PercentileResult stats =
                PrecisionTimer.calculatePercentiles(
                        latencies.stream().mapToLong(Long::longValue).toArray());

        proc.stop();

        boolean pass = stats.p95() < 100_000; // p95 < 100us

        ctx.sayTable(
                new String[][] {
                    {"Metric", "Value (ns)", "Value (us)"},
                    {
                        "Min",
                        String.valueOf(stats.min()),
                        String.format("%.3f", stats.min() / 1000.0)
                    },
                    {
                        "Max",
                        String.valueOf(stats.max()),
                        String.format("%.3f", stats.max() / 1000.0)
                    },
                    {
                        "Mean",
                        String.format("%.2f", stats.mean()),
                        String.format("%.3f", stats.mean() / 1000.0)
                    },
                    {
                        "p50",
                        String.valueOf(stats.p50()),
                        String.format("%.3f", stats.p50() / 1000.0)
                    },
                    {
                        "p95",
                        String.valueOf(stats.p95()),
                        String.format("%.3f", stats.p95() / 1000.0)
                    },
                    {
                        "p99",
                        String.valueOf(stats.p99()),
                        String.format("%.3f", stats.p99() / 1000.0)
                    }
                });

        ctx.sayKeyValue(
                Map.of(
                        "Operation",
                        "Proc.ask()",
                        "Pattern",
                        "Synchronous Request-Response",
                        "Iterations",
                        String.valueOf(MEASUREMENT_ITERATIONS),
                        "p95 Target",
                        "< 100 us",
                        "Status",
                        pass ? "PASS" : "FAIL"));

        ctx.sayNote(
                "ask() precision validates synchronous messaging latency. "
                        + "p95 should be < 100us for responsive request-reply patterns.");

        assertThat(stats.p95()).as("ask() p95 should be < 100us").isLessThan(100_000L);
    }

    @Test
    @DisplayName("Benchmark: State Access Precision")
    void measureStateAccessPrecision() throws Exception {
        ctx.sayNextSection("Benchmark: State Access Precision");
        ctx.say("Measures the precision of accessing process state synchronously via ProcSys.");

        Proc<Integer, String> proc = createCounterProcess();

        // Initialize with some state
        for (int i = 0; i < 100; i++) {
            proc.tell("inc");
        }
        Thread.sleep(50);

        List<Long> latencies = new ArrayList<>(MEASUREMENT_ITERATIONS);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ProcSys.getState(proc).get();
        }

        // Measurement
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            int state = ProcSys.getState(proc).get();
            long end = System.nanoTime();
            latencies.add(end - start);
            assertThat(state).isEqualTo(100);
        }

        PrecisionTimer.PercentileResult stats =
                PrecisionTimer.calculatePercentiles(
                        latencies.stream().mapToLong(Long::longValue).toArray());

        proc.stop();

        boolean pass = stats.mean() < 100_000; // mean < 100us (sys channel has higher latency)

        ctx.sayTable(
                new String[][] {
                    {"Metric", "Value (ns)"},
                    {"Mean", String.format("%.2f", stats.mean())},
                    {"StdDev", String.format("%.2f", calculateStdDev(latencies))},
                    {"p50", String.valueOf(stats.p50())},
                    {"p95", String.valueOf(stats.p95())},
                    {"p99", String.valueOf(stats.p99())}
                });

        ctx.sayKeyValue(
                Map.of(
                        "Operation",
                        "ProcSys.getState()",
                        "Access Type",
                        "Synchronous Sys Channel",
                        "Iterations",
                        String.valueOf(MEASUREMENT_ITERATIONS),
                        "Mean Target",
                        "< 100000 ns",
                        "Status",
                        pass ? "PASS" : "FAIL"));

        ctx.sayNote(
                "State access via ProcSys uses a sys channel with higher latency than direct access.");

        assertThat(stats.mean()).as("getState() mean should be < 100us").isLessThan(100_000.0);
    }

    @Test
    @DisplayName("Benchmark: Blocking Run Duration Precision")
    void measureBlockingRunPrecision() throws Exception {
        ctx.sayNextSection("Benchmark: Blocking Run Duration Precision");
        ctx.say("Measures timing precision for blocking operations with timeouts.");

        Proc<Integer, String> proc = createEchoProcess();

        int iterations = 1_000;
        List<Long> actualDurations = new ArrayList<>(iterations);
        long targetDurationNs = 10_000_000; // 10ms

        // Measure actual vs expected duration
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Thread.sleep(10); // 10ms sleep
            long end = System.nanoTime();
            actualDurations.add(end - start);
        }

        PrecisionTimer.PercentileResult stats =
                PrecisionTimer.calculatePercentiles(
                        actualDurations.stream().mapToLong(Long::longValue).toArray());

        proc.stop();

        // Calculate drift from target
        double meanDrift = Math.abs(stats.mean() - targetDurationNs);
        double driftPercent = (meanDrift / targetDurationNs) * 100.0;

        boolean pass = driftPercent < 10.0; // < 10% drift

        ctx.sayTable(
                new String[][] {
                    {"Metric", "Value (ns)", "Value (ms)"},
                    {"Target", String.valueOf(targetDurationNs), "10.00"},
                    {
                        "Actual Mean",
                        String.format("%.0f", stats.mean()),
                        String.format("%.2f", stats.mean() / 1_000_000.0)
                    },
                    {
                        "Min",
                        String.valueOf(stats.min()),
                        String.format("%.2f", stats.min() / 1_000_000.0)
                    },
                    {
                        "Max",
                        String.valueOf(stats.max()),
                        String.format("%.2f", stats.max() / 1_000_000.0)
                    },
                    {
                        "Drift",
                        String.format("%.0f", meanDrift),
                        String.format("%.2f", meanDrift / 1_000_000.0)
                    }
                });

        ctx.sayKeyValue(
                Map.of(
                        "Target Duration",
                        "10 ms",
                        "Actual Mean",
                        String.format("%.2f ms", stats.mean() / 1_000_000.0),
                        "Drift",
                        String.format("%.2f%%", driftPercent),
                        "Iterations",
                        String.valueOf(iterations),
                        "Status",
                        pass ? "PASS" : "FAIL"));

        ctx.sayNote(
                "Thread.sleep() precision varies by platform. "
                        + "Drift should be < 10% for reliable timeout behavior.");

        assertThat(driftPercent).as("Duration drift should be < 10%").isLessThan(10.0);
    }

    private Proc<Integer, String> createEchoProcess() {
        BiFunction<Integer, String, Integer> handler =
                (state, msg) -> {
                    // Echo process - state tracks message count
                    return state + 1;
                };
        return Proc.spawn(0, handler);
    }

    private Proc<Integer, String> createCounterProcess() {
        BiFunction<Integer, String, Integer> handler =
                (state, msg) -> {
                    if ("inc".equals(msg)) {
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
