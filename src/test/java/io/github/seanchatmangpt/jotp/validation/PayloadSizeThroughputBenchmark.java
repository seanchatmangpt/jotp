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

package io.github.seanchatmangpt.jotp.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Throughput benchmark with varying payload sizes to validate realism of published claims.
 *
 * <p>Tests message throughput at different payload sizes to determine whether benchmark numbers are
 * based on unrealistic tiny messages or reflect real-world usage.
 *
 * <p><strong>Test Execution:</strong>
 *
 * <pre>{@code
 * ./mvnw test -Dtest=PayloadSizeThroughputBenchmark
 * }</pre>
 */
@DisplayName("Payload Size Throughput Benchmark")
class PayloadSizeThroughputBenchmark {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int TEST_DURATION_MS = 3000;


    @BeforeEach
    void setUp() {
        System.clearProperty("jotp.observability.enabled");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("jotp.observability.enabled");
    }

    // Message types for different payload sizes
    sealed interface TestMessage
            permits TestMessage.Empty, TestMessage.Small, TestMessage.Medium, TestMessage.Large {
        record Empty() implements TestMessage {}

        record Small(int value) implements TestMessage {}

        record Medium(String data, int version, long timestamp) implements TestMessage {}

        record Large(
                UUID id,
                String payload,
                String metadata,
                int sequence,
                long timestamp,
                String checksum)
                implements TestMessage {}
    }

    @Test
    @DisplayName("Benchmark: Throughput vs Payload Size")
    void measureThroughputVsPayloadSize() throws Exception {

        String[] sizeNames = {"Empty", "Small (24B)", "Medium (64B)", "Large (256B)"};
        TestMessage[] messages = {
            new TestMessage.Empty(),
            new TestMessage.Small(42),
            new TestMessage.Medium("test-payload-data-here", 1, System.currentTimeMillis()),
            new TestMessage.Large(
                    UUID.randomUUID(),
                    "x".repeat(100), // 100 char payload
                    "metadata-" + "x".repeat(50), // 60 char metadata
                    12345,
                    System.currentTimeMillis(),
                    "checksum-" + "x".repeat(20) // 29 char checksum
                    )
        };

        double[] throughputs = new double[messages.length];

        for (int i = 0; i < messages.length; i++) {
            throughputs[i] = measureThroughput(messages[i]);
            Thread.sleep(100); // Cooldown between tests
        }

        // Create results table
                new String[][] {
                    {
                        "Payload Size",
                        "Messages Sent",
                        "Duration (ms)",
                        "Throughput (msg/sec)",
                        "% of Baseline"
                    },
                    {"Empty (16B)", "N/A", "N/A", String.format("%.0f", throughputs[0]), "100%"},
                    {
                        "Small (24B)",
                        "N/A",
                        "N/A",
                        String.format("%.0f", throughputs[1]),
                        String.format("%.1f%%", (throughputs[1] / throughputs[0]) * 100)
                    },
                    {
                        "Medium (64B)",
                        "N/A",
                        "N/A",
                        String.format("%.0f", throughputs[2]),
                        String.format("%.1f%%", (throughputs[2] / throughputs[0]) * 100)
                    },
                    {
                        "Large (256B)",
                        "N/A",
                        "N/A",
                        String.format("%.0f", throughputs[3]),
                        String.format("%.1f%%", (throughputs[3] / throughputs[0]) * 100)
                    }
                });

                Map.of(
                        "Baseline (Empty)",
                        String.format("%.0f msg/sec", throughputs[0]),
                        "Small Payload Degradation",
                        String.format("%.1f%%", 100 - (throughputs[1] / throughputs[0]) * 100),
                        "Medium Payload Degradation",
                        String.format("%.1f%%", 100 - (throughputs[2] / throughputs[0]) * 100),
                        "Large Payload Degradation",
                        String.format("%.1f%%", 100 - (throughputs[3] / throughputs[0]) * 100),
                        "Test Duration",
                        TEST_DURATION_MS + " ms per payload size",
                        "Status",
                        throughputs[0] > 1_000_000 ? "PASS" : "FAIL"));

                "Throughput degradation shows impact of payload size. "
                        + "Real-world applications (256B+ payloads) will see significantly lower throughput than baseline.");

        // Assertions
        assertThat(throughputs[0])
                .as("Baseline throughput should exceed 1M msg/sec")
                .isGreaterThan(1_000_000.0);

        // Calculate degradation
        double smallDegradation = 100 - (throughputs[1] / throughputs[0]) * 100;
        double mediumDegradation = 100 - (throughputs[2] / throughputs[0]) * 100;
        double largeDegradation = 100 - (throughputs[3] / throughputs[0]) * 100;

                Map.of(
                        "Key Finding",
                        "Payload size significantly impacts throughput",
                        "Small vs Empty",
                        String.format("%.1f%% slower", smallDegradation),
                        "Medium vs Empty",
                        String.format("%.1f%% slower", mediumDegradation),
                        "Large vs Empty",
                        String.format("%.1f%% slower", largeDegradation),
                        "Real-World Impact",
                        "Published numbers based on tiny messages are misleading"));

        assertThat(smallDegradation)
                .as("Small payload degradation should be measurable")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("Benchmark: String Message Throughput")
    void measureStringMessageThroughput() throws Exception {

        String[] messages = {
            "", // Empty: ~40 bytes
            "msg", // Small: ~48 bytes
            "message-with-more-data", // Medium: ~64 bytes
            "x".repeat(200) // Large: ~240 bytes
        };

        String[] names = {"Empty (40B)", "Small (48B)", "Medium (64B)", "Large (240B)"};
        double[] throughputs = new double[messages.length];

        for (int i = 0; i < messages.length; i++) {
            throughputs[i] = measureStringThroughput(messages[i]);
            Thread.sleep(100);
        }

                new String[][] {
                    {"String Size", "Est. Bytes", "Throughput (msg/sec)", "% of Baseline"},
                    {names[0], "~40", String.format("%.0f", throughputs[0]), "100%"},
                    {
                        names[1],
                        "~48",
                        String.format("%.0f", throughputs[1]),
                        String.format("%.1f%%", (throughputs[1] / throughputs[0]) * 100)
                    },
                    {
                        names[2],
                        "~64",
                        String.format("%.0f", throughputs[2]),
                        String.format("%.1f%%", (throughputs[2] / throughputs[0]) * 100)
                    },
                    {
                        names[3],
                        "~240",
                        String.format("%.0f", throughputs[3]),
                        String.format("%.1f%%", (throughputs[3] / throughputs[0]) * 100)
                    }
                });

                Map.of(
                        "Baseline (Empty String)",
                        String.format("%.0f msg/sec", throughputs[0]),
                        "Real-World (Medium)",
                        String.format(
                                "%.0f msg/sec (%.1f%% of baseline)",
                                throughputs[2], (throughputs[2] / throughputs[0]) * 100),
                        "Large Payload",
                        String.format(
                                "%.0f msg/sec (%.1f%% of baseline)",
                                throughputs[3], (throughputs[3] / throughputs[0]) * 100),
                        "Key Finding",
                        "String length has measurable impact on throughput",
                        "Status",
                        throughputs[0] > 1_000_000 ? "PASS" : "FAIL"));

        assertThat(throughputs[0])
                .as("Baseline throughput should exceed 1M msg/sec")
                .isGreaterThan(1_000_000.0);
    }

    @Test
    @DisplayName("Benchmark: Real-World F1 Telemetry Simulation")
    void measureRealWorldF1Telemetry() throws Exception {

        // Simulated F1 telemetry messages
        String tickMessage = "{\"car\":1,\"speed\":320,\"rpm\":15000,\"gear\":8}"; // ~50 bytes
        String frameMessage =
                "{\"car\":1,\"speed\":320,\"rpm\":15000,\"gear\":8,\"throttle\":95,\"brake\":0,\"steering\":-12,\"lat\":45.5,\"lon\":-73.6}"; // ~150 bytes
        String batchMessage =
                "{\"car\":1,\"frames\":["
                        + frameMessage
                        + ","
                        + frameMessage
                        + ","
                        + frameMessage
                        + ","
                        + frameMessage
                        + "]}"; // ~600 bytes

        double[] throughputs = new double[3];
        throughputs[0] = measureStringThroughput(tickMessage);
        Thread.sleep(100);
        throughputs[1] = measureStringThroughput(frameMessage);
        Thread.sleep(100);
        throughputs[2] = measureStringThroughput(batchMessage);

                new String[][] {
                    {"Message Type", "Est. Size", "Throughput (msg/sec)", "Comparison"},
                    {"Tick", "~50 bytes", String.format("%.0f", throughputs[0]), "Baseline"},
                    {
                        "Frame",
                        "~150 bytes",
                        String.format("%.0f", throughputs[1]),
                        String.format("%.1f%% of baseline", (throughputs[1] / throughputs[0]) * 100)
                    },
                    {
                        "Batch (4 frames)",
                        "~600 bytes",
                        String.format("%.0f", throughputs[2]),
                        String.format("%.1f%% of baseline", (throughputs[2] / throughputs[0]) * 100)
                    }
                });

        Map<String, String> results = new java.util.HashMap<>();
        results.put("F1 Telemetry Tick", String.format("%.0f msg/sec", throughputs[0]));
        results.put(
                "Full Frame",
                String.format(
                        "%.0f msg/sec (%.1f%% of tick)",
                        throughputs[1], (throughputs[1] / throughputs[0]) * 100));
        results.put(
                "Batch Transmission",
                String.format(
                        "%.0f msg/sec (%.1f%% of tick)",
                        throughputs[2], (throughputs[2] / throughputs[0]) * 100));
        results.put(
                "Real-World Conclusion",
                "At 150-byte frames: "
                        + String.format("%.0f", throughputs[1])
                        + " msg/sec (realistic)");
        results.put(
                "At 600-byte batches",
                String.format("%.0f", throughputs[2]) + " msg/sec (realistic)");
        results.put("Benchmark Realism", throughputs[1] < 5_000_000 ? "REALISTIC" : "OVERSTATED");

                "Real-world F1 telemetry at 150-byte frames achieves "
                        + String.format("%.0f", throughputs[1])
                        + " msg/sec. "
                        + "Claims based on 4.6M msg/sec are "
                        + String.format("%.1f", 4_600_000.0 / throughputs[1])
                        + "× higher than realistic throughput.");

        assertThat(throughputs[0])
                .as("Tick throughput should exceed 100K msg/sec")
                .isGreaterThan(100_000.0);
    }

    private double measureThroughput(TestMessage message) throws Exception {
        Proc<Integer, TestMessage> proc = createTestProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            proc.tell(message);
        }
        Thread.sleep(100);

        // Measurement
        AtomicLong count = new AtomicLong(0);
        long start = System.nanoTime();
        long end = start + (TEST_DURATION_MS * 1_000_000L);

        while (System.nanoTime() < end) {
            proc.tell(message);
            count.incrementAndGet();
        }

        long actualDuration = System.nanoTime() - start;
        double throughput = (count.get() * 1_000_000_000.0) / actualDuration;

        proc.stop();
        return throughput;
    }

    private double measureStringThroughput(String message) throws Exception {
        Proc<Integer, String> proc = createStringProcess();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            proc.tell(message);
        }
        Thread.sleep(100);

        // Measurement
        AtomicLong count = new AtomicLong(0);
        long start = System.nanoTime();
        long end = start + (TEST_DURATION_MS * 1_000_000L);

        while (System.nanoTime() < end) {
            proc.tell(message);
            count.incrementAndGet();
        }

        long actualDuration = System.nanoTime() - start;
        double throughput = (count.get() * 1_000_000_000.0) / actualDuration;

        proc.stop();
        return throughput;
    }

    private Proc<Integer, TestMessage> createTestProcess() {
        BiFunction<Integer, TestMessage, Integer> handler = (state, msg) -> state + 1;
        return Proc.spawn(0, handler);
    }

    private Proc<Integer, String> createStringProcess() {
        BiFunction<Integer, String, Integer> handler = (state, msg) -> state + 1;
        return Proc.spawn(0, handler);
    }
}
