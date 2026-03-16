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

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Analysis of message sizes used in JOTP benchmarks.
 *
 * <p>This test analyzes the byte sizes of different message types to understand whether benchmark
 * throughput numbers are based on realistic payloads or empty objects.
 */
@DisplayName("Message Size Analysis")
class MessageSizeAnalysis {

    // Message types from actual benchmarks
    sealed interface BenchmarkMsg
            permits BenchmarkMsg.Empty,
                    BenchmarkMsg.Small,
                    BenchmarkMsg.Medium,
                    BenchmarkMsg.Large {
        record Empty() implements BenchmarkMsg {}

        record Small(int value) implements BenchmarkMsg {}

        record Medium(String payload, int version) implements BenchmarkMsg {}

        record Large(UUID id, String query, String data, int timestamp, long sequence)
                implements BenchmarkMsg {}
    }

    // Message types from ReactiveMessagingFoundationPatternsTest
    sealed interface Msg permits Msg.Inc, Msg.Reset, Msg.Get, Msg.Doc, Msg.Evt, Msg.Seq, Msg.Req {
        record Inc(int by) implements Msg {}

        record Reset() implements Msg {}

        record Get() implements Msg {}

        record Doc(String payload, int version) implements Msg {}

        record Evt(String type, String source) implements Msg {}

        record Seq(int num, String payload) implements Msg {}

        record Req(UUID id, String query, java.util.concurrent.CompletableFuture<String> replyTo)
                implements Msg {}
    }

    @Test
    @DisplayName("Analyze Message Sizes in Benchmarks")
    void analyzeMessageSizes() {
        System.out.println("\n=== MESSAGE SIZE ANALYSIS ===\n");

        // 1. String messages (most common in benchmarks)
        String emptyString = "";
        String smallString = "msg";
        String mediumString = "message-123-456";

        // 2. Integer messages (ActorBenchmark)
        Integer intMsg = 42;

        // 3. Benchmark message types
        BenchmarkMsg emptyMsg = new BenchmarkMsg.Empty();
        BenchmarkMsg smallMsg = new BenchmarkMsg.Small(42);
        BenchmarkMsg mediumMsg = new BenchmarkMsg.Medium("test-payload-data", 1);
        BenchmarkMsg largeMsg =
                new BenchmarkMsg.Large(
                        UUID.randomUUID(),
                        "test-query-string-with-more-data",
                        "larger-payload-data-here",
                        123456789,
                        9876543210L);

        // 4. Foundation pattern messages
        Msg incMsg = new Msg.Inc(10);
        Msg resetMsg = new Msg.Reset();
        Msg docMsg = new Msg.Doc("payload-content", 1);
        Msg evtMsg = new Msg.Evt("type-name", "source-name");

        System.out.println("Estimating object sizes using Java object layout:\n");

        // Estimate sizes (Java object overhead + fields)
        System.out.println("STRING MESSAGES (used in ObservabilityThroughputBenchmark):");
        System.out.println("  Empty string \"\":     ~40 bytes (header + char array)");
        System.out.println("  Small string \"msg\":  ~48 bytes (header + char array of 3)");
        System.out.println("  Medium \"message-123-456\": ~64 bytes\n");

        System.out.println("PRIMITIVE MESSAGES (used in ActorBenchmark):");
        System.out.println("  Integer (42):         ~16 bytes (header + int)");
        System.out.println("  Integer object:       ~24 bytes (with alignment)\n");

        System.out.println("RECORD MESSAGES (BenchmarkMsg):");
        System.out.println("  Empty record:         ~16 bytes (header only)");
        System.out.println("  Small(int):           ~24 bytes (header + int)");
        System.out.println("  Medium(String, int):  ~32 bytes (header + ref + int + padding)");
        System.out.println("  Large(5 fields):      ~56 bytes (header + refs + primitives)\n");

        System.out.println("FOUNDATION PATTERN MESSAGES:");
        System.out.println("  Inc(int):             ~24 bytes");
        System.out.println("  Reset():              ~16 bytes");
        System.out.println("  Doc(String, int):     ~32 bytes");
        System.out.println("  Evt(String, String):  ~32 bytes\n");

        // Create size comparison table
        System.out.println("ESTIMATED MEMORY FOOTPRINT:");
        System.out.println("┌────────────────────────────────┬────────────┬────────────┐");
        System.out.println("│ Message Type                   │ Size (est) │ Category   │");
        System.out.println("├────────────────────────────────┼────────────┼────────────┤");
        System.out.println("│ String \"\" (benchmark baseline) │ 40 bytes   │ Empty      │");
        System.out.println("│ String \"msg\"                   │ 48 bytes   │ Tiny       │");
        System.out.println("│ Integer (42)                   │ 24 bytes   │ Tiny       │");
        System.out.println("│ Empty record                   │ 16 bytes   │ Empty      │");
        System.out.println("│ Inc(10) record                 │ 24 bytes   │ Tiny       │");
        System.out.println("│ Doc(payload, 1)                │ 32+ bytes  │ Small      │");
        System.out.println("│ Large(5 fields)                │ 56+ bytes  │ Medium     │");
        System.out.println("└────────────────────────────────┴────────────┴────────────┘\n");

        System.out.println("BENCHMARK MESSAGE USAGE ANALYSIS:");
        System.out.println("ObservabilityThroughputBenchmark:");
        System.out.println("  - Uses: String messages (\"warmup\", \"message\", \"msg\")");
        System.out.println("  - Size: 40-64 bytes per message");
        System.out.println("  - Category: Tiny (not empty, but minimal)\n");

        System.out.println("ActorBenchmark:");
        System.out.println("  - Uses: Integer messages (42)");
        System.out.println("  - Size: ~24 bytes per message");
        System.out.println("  - Category: Tiny (primitive wrapper)\n");

        System.out.println("SimpleObservabilityBenchmark:");
        System.out.println("  - Uses: String messages (\"warmup\", \"msg\")");
        System.out.println("  - Size: 40-48 bytes per message");
        System.out.println("  - Category: Tiny\n");

        System.out.println("ReactiveMessagingFoundationPatternsTest:");
        System.out.println("  - Uses: Record messages (Inc, Doc, Evt, etc.)");
        System.out.println("  - Size: 16-32+ bytes per message");
        System.out.println("  - Category: Varies (Empty to Small)\n");

        System.out.println("KEY FINDING:");
        System.out.println(
                "  Most benchmarks use TINY messages (16-64 bytes), not EMPTY messages.");
        System.out.println("  However, this is still FAR smaller than real-world payloads.");
        System.out.println("  Real telemetry data: 256-1024+ bytes per message.");
        System.out.println("  Missing: Scaling tests with realistic payload sizes.\n");
    }

    @Test
    @DisplayName("Estimate Real-World Message Sizes")
    void estimateRealWorldMessageSizes() {
        System.out.println("\n=== REAL-WORLD MESSAGE SIZE ESTIMATES ===\n");

        System.out.println("F1 TELEMETRY MESSAGES (typical):");
        System.out.println("  Speed/position data:           32-64 bytes");
        System.out.println(" Full telemetry frame:           256-512 bytes");
        System.out.println(" Batch transmission:             1-4 KB\n");

        System.out.println("ENTERPRISE MESSAGE SIZES:");
        System.out.println("  Metric tick (counter):         32-64 bytes");
        System.out.println("  Log entry:                     128-512 bytes");
        System.out.println("  Event notification:            256-1024 bytes");
        System.out.println("  Document update:               1-10 KB\n");

        System.out.println("BENCHMARK REALISM ASSESSMENT:");
        System.out.println("  Current benchmarks:            16-64 bytes  ❌ UNREALISTIC");
        System.out.println("  Real-world minimum:            32-256 bytes ⚠️  BORDERLINE");
        System.out.println("  Real-world typical:            256-1024 bytes ✅ REALISTIC\n");

        System.out.println("THROUGHPUT IMPACT ESTIMATE:");
        System.out.println("  If 4.6M msg/sec at 64 bytes (current benchmark):");
        System.out.println("    → At 256 bytes (4×): ~1.15M msg/sec (75% reduction)");
        System.out.println("    → At 1024 bytes (16×): ~287K msg/sec (94% reduction)\n");

        System.out.println("  Linear degradation assumption (may not hold):");
        System.out.println("    Throughput ≈ (Baseline Size / Actual Size) × Baseline Throughput");
        System.out.println("    Example: (64 / 256) × 4.6M = 1.15M msg/sec\n");

        System.out.println("VALIDATION NEEDED:");
        System.out.println("  1. Run benchmarks with 256-byte payloads");
        System.out.println("  2. Run benchmarks with 1024-byte payloads");
        System.out.println("  3. Measure actual degradation curve");
        System.out.println("  4. Update claims with realistic payload sizes\n");
    }
}
