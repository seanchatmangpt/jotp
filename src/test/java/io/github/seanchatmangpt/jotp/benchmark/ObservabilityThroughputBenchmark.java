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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Throughput benchmark for observability infrastructure.
 *
 * <p>Measures message throughput with and without observability enabled to validate that the async
 * event bus design does not impact hot path throughput.
 *
 * <p><strong>Test Execution:</strong>
 *
 * <pre>{@code
 * ./mvnw test -Dtest=ObservabilityThroughputBenchmark
 * }</pre>
 */
@DisplayName("Observability Throughput Benchmark")
class ObservabilityThroughputBenchmark {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int MEASUREMENT_ITERATIONS = 1_000_000;
    private static final int TEST_DURATION_MS = 5000;


    @BeforeEach
    void setUp() {
        System.clearProperty("jotp.observability.enabled");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jotp.observability.enabled");
    }

    @Test
    @DisplayName("Benchmark: Throughput - Disabled vs Enabled")
    void measureThroughput() throws Exception {

        // Phase 1: Baseline with observability disabled
        System.clearProperty("jotp.observability.enabled");
        Proc<Integer, String> baselineProc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            baselineProc.tell("warmup");
        }
        Thread.sleep(100);

        // Throughput measurement - disabled
        AtomicLong disabledCount = new AtomicLong(0);
        long disabledStart = System.nanoTime();
        long disabledEnd = disabledStart + (TEST_DURATION_MS * 1_000_000L);

        while (System.nanoTime() < disabledEnd) {
            baselineProc.tell("message");
            disabledCount.incrementAndGet();
        }

        long disabledActualDuration = System.nanoTime() - disabledStart;
        double disabledThroughput =
                (disabledCount.get() * 1_000_000_000.0) / disabledActualDuration;

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

        // Throughput measurement - enabled
        AtomicLong enabledCount = new AtomicLong(0);
        long enabledStart = System.nanoTime();
        long enabledEnd = enabledStart + (TEST_DURATION_MS * 1_000_000L);

        while (System.nanoTime() < enabledEnd) {
            enabledProc.tell("message");
            enabledCount.incrementAndGet();
        }

        long enabledActualDuration = System.nanoTime() - enabledStart;
        double enabledThroughput = (enabledCount.get() * 1_000_000_000.0) / enabledActualDuration;

        metrics.close();
        enabledProc.stop();

        // Calculate throughput degradation
        double degradationPercent =
                ((disabledThroughput - enabledThroughput) / disabledThroughput) * 100.0;
        boolean pass = degradationPercent < 5.0; // Less than 5% degradation

                new String[][] {
                    {"Configuration", "Messages", "Duration (ms)", "Throughput (msg/sec)"},
                    {
                        "Disabled",
                        String.valueOf(disabledCount.get()),
                        String.format("%.2f", disabledActualDuration / 1_000_000.0),
                        String.format("%.0f", disabledThroughput)
                    },
                    {
                        "Enabled",
                        String.valueOf(enabledCount.get()),
                        String.format("%.2f", enabledActualDuration / 1_000_000.0),
                        String.format("%.0f", enabledThroughput)
                    }
                });

                Map.of(
                        "Disabled Throughput",
                        String.format("%.0f msg/sec", disabledThroughput),
                        "Enabled Throughput",
                        String.format("%.0f msg/sec", enabledThroughput),
                        "Degradation",
                        String.format("%.2f%%", degradationPercent),
                        "Status",
                        pass ? "PASS" : "FAIL"));

                "Throughput benchmark validates async event bus design. "
                        + "Degradation should be < 5% for zero-overhead observability.");

        // Assertion - degradation should be minimal
        assertThat(degradationPercent).as("Throughput degradation should be < 5%").isLessThan(5.0);
    }

    @Test
    @DisplayName("Benchmark: Batch Throughput")
    void measureBatchThroughput() throws Exception {

        System.setProperty("jotp.observability.enabled", "true");
        FrameworkMetrics metrics = FrameworkMetrics.create();
        Proc<Integer, String> proc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            proc.tell("warmup");
        }
        Thread.sleep(100);

        // Batch throughput measurement
        int batchSize = 10_000;
        int numBatches = 100;

        long start = System.nanoTime();
        for (int batch = 0; batch < numBatches; batch++) {
            for (int i = 0; i < batchSize; i++) {
                proc.tell("message-" + batch + "-" + i);
            }
        }
        long end = System.nanoTime();

        long totalMessages = (long) batchSize * numBatches;
        double durationSec = (end - start) / 1_000_000_000.0;
        double throughput = totalMessages / durationSec;

        metrics.close();
        proc.stop();

                new String[][] {
                    {"Metric", "Value"},
                    {"Batch Size", String.valueOf(batchSize)},
                    {"Num Batches", String.valueOf(numBatches)},
                    {"Total Messages", String.valueOf(totalMessages)},
                    {"Duration (s)", String.format("%.3f", durationSec)},
                    {"Throughput (msg/sec)", String.format("%.0f", throughput)}
                });

                Map.of(
                        "Total Messages",
                        String.valueOf(totalMessages),
                        "Throughput",
                        String.format("%.0f msg/sec", throughput),
                        "Batch Size",
                        String.valueOf(batchSize)));


        // Throughput should be substantial
        assertThat(throughput).as("Throughput should exceed 1M msg/sec").isGreaterThan(1_000_000.0);
    }

    private Proc<Integer, String> createTestProcess() {
        BiFunction<Integer, String, Integer> handler =
                (state, msg) -> {
                    if (msg.startsWith("message")) {
                        return state + 1;
                    }
                    return state;
                };
        return Proc.spawn(0, handler);
    }
}
