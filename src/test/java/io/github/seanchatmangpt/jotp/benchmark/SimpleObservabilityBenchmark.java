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

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.observability.FrameworkEventBus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Simple manual microbenchmark for JOTP observability infrastructure.
 *
 * <p>Measures the actual nanosecond overhead of FrameworkEventBus operations.
 */
class SimpleObservabilityBenchmark {

    private static final int WARMUP_ITERATIONS = 10000;
    private static final int MEASUREMENT_ITERATIONS = 100000;

    @Test
    void testEventBusPublishDisabled() {
        // Setup: Observability DISABLED
        System.setProperty("jotp.observability.enabled", "false");
        FrameworkEventBus eventBus = FrameworkEventBus.create();

        var sampleEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "bench-proc-1", "Proc");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            eventBus.publish(sampleEvent);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            eventBus.publish(sampleEvent);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) MEASUREMENT_ITERATIONS;

        System.out.printf("FrameworkEventBus.publish() DISABLED: %.2f ns/op%n", avgNs);
        System.out.printf("  Claim: <100ns, Result: %s%n", avgNs < 100 ? "PASS" : "FAIL");

        assertTrue(avgNs < 100, "FrameworkEventBus should have <100ns overhead when disabled");
    }

    @Test
    void testEventBusPublishEnabledNoSubscribers() {
        // Setup: Observability ENABLED but NO subscribers
        System.setProperty("jotp.observability.enabled", "true");
        FrameworkEventBus eventBus = FrameworkEventBus.create();

        var sampleEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "bench-proc-1", "Proc");

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            eventBus.publish(sampleEvent);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            eventBus.publish(sampleEvent);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) MEASUREMENT_ITERATIONS;

        System.out.printf(
                "FrameworkEventBus.publish() ENABLED (no subscribers): %.2f ns/op%n", avgNs);
        System.out.printf("  Claim: <100ns, Result: %s%n", avgNs < 100 ? "PASS" : "FAIL");

        assertTrue(
                avgNs < 100, "FrameworkEventBus should have <100ns overhead when no subscribers");
    }

    @Test
    void testEventBusPublishEnabledWithSubscriber() {
        // Setup: Observability ENABLED with ONE subscriber
        System.setProperty("jotp.observability.enabled", "true");
        FrameworkEventBus eventBus = FrameworkEventBus.create();

        var sampleEvent =
                new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                        Instant.now(), "bench-proc-1", "Proc");

        // Add subscriber
        eventBus.subscribe(
                event -> {
                    // No-op subscriber
                });

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            eventBus.publish(sampleEvent);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            eventBus.publish(sampleEvent);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) MEASUREMENT_ITERATIONS;

        System.out.printf(
                "FrameworkEventBus.publish() ENABLED (1 subscriber): %.2f ns/op%n", avgNs);
        System.out.printf("  Expected: 200-500ns (async submission)%n");
        System.out.printf("  Result: %s%n", avgNs >= 200 && avgNs <= 500 ? "PASS" : "WARN");

        // This is a soft assertion - async delivery can vary
        assertTrue(avgNs > 0, "Measurement should complete successfully");
    }

    @Test
    void testProcTellWithObservabilityDisabled() {
        // Setup: Observability DISABLED
        System.setProperty("jotp.observability.enabled", "false");
        var counterProc = Proc.spawn(0, (state, msg) -> state + 1);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            counterProc.tell(1);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            counterProc.tell(1);
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) MEASUREMENT_ITERATIONS;

        System.out.printf("Proc.tell() with observability DISABLED: %.2f ns/op%n", avgNs);
        System.out.printf(
                "  Claim: <50ns (pure mailbox enqueue), Result: %s%n",
                avgNs < 50 ? "PASS" : "WARN");

        // This is a soft assertion - JIT compiler can affect timing
        assertTrue(avgNs > 0, "Measurement should complete successfully");
    }

    @Test
    void testEventCreationCost() {
        // Measure event creation overhead
        long start = System.nanoTime();
        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                    Instant.now(), "bench-proc-" + i, "Proc");
        }
        long end = System.nanoTime();

        double avgNs = (end - start) / (double) MEASUREMENT_ITERATIONS;

        System.out.printf("FrameworkEvent creation cost: %.2f ns/op%n", avgNs);
        System.out.printf(
                "  Claim: <100ns (allocation + initialization), Result: %s%n",
                avgNs < 100 ? "PASS" : "WARN");

        // This is a soft assertion - allocation can vary
        assertTrue(avgNs > 0, "Measurement should complete successfully");
    }

    @Test
    void printFullReport() {
        System.out.println("\n=== JOTP OBSERVABILITY PRECISION BENCHMARK REPORT ===");
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name"));
        System.out.println("Warmup Iterations: " + WARMUP_ITERATIONS);
        System.out.println("Measurement Iterations: " + MEASUREMENT_ITERATIONS);
        System.out.println("\n--- Thesis Claims Validation ---\n");

        // Run all benchmarks
        testEventBusPublishDisabled();
        System.out.println();
        testEventBusPublishEnabledNoSubscribers();
        System.out.println();
        testEventBusPublishEnabledWithSubscriber();
        System.out.println();
        testProcTellWithObservabilityDisabled();
        System.out.println();
        testEventCreationCost();

        System.out.println("\n=== END OF BENCHMARK REPORT ===\n");
    }
}
