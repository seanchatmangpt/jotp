/*
 * Copyright 2026 Sean Chat Mangpt
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

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Simple throughput benchmarks for JOTP observability infrastructure.
 *
 * <p>Measures actual operations per second for:
 *
 * <ul>
 *   <li>FrameworkEventBus publish throughput
 *   <li>Subscriber scalability (1-1000 subscribers)
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=SimpleThroughputBenchmark}
 */
@DisplayName("Simple Throughput Benchmarks")
class SimpleThroughputBenchmark {

    private FrameworkEventBus eventBus;

    @BeforeEach
    void setUp() {
        // Enable observability for benchmarks
        System.setProperty("jotp.observability.enabled", "true");
        eventBus = FrameworkEventBus.create();
    }

    @Test
    @DisplayName("Baseline: Proc message throughput")
    void baselineProcThroughput() throws Exception {
        int messageCount = 100_000;
        AtomicLong received = new AtomicLong(0);

        Proc<Void, String> proc =
                Proc.spawn(
                        () -> null,
                        (state, msg) -> {
                            received.incrementAndGet();
                            return null;
                        });

        long start = System.nanoTime();
        for (int i = 0; i < messageCount; i++) {
            proc.tell("msg-" + i);
        }
        long end = System.nanoTime();

        // Wait for processing
        Thread.sleep(100);
        long elapsedMs = (end - start) / 1_000_000;
        double opsPerSec = (messageCount * 1000.0) / elapsedMs;

        System.out.println("=== BASELINE PROC THROUGHPUT ===");
        System.out.println("Messages: " + messageCount);
        System.out.println("Received: " + received.get());
        System.out.println("Time: " + elapsedMs + " ms");
        System.out.println("Throughput: " + String.format("%.2f", opsPerSec) + " ops/sec");
        System.out.println();
    }

    @Test
    @DisplayName("FrameworkEventBus: Publish throughput with 1 subscriber")
    void frameworkEventBusThroughputOneSubscriber() throws Exception {
        int eventCount = 10_000;
        AtomicLong received = new AtomicLong(0);

        eventBus.subscribe(event -> received.incrementAndGet());

        long start = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            eventBus.publish(
                    new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                            Instant.now(), "proc-" + i, "test"));
        }
        long end = System.nanoTime();

        // Wait for async processing
        Thread.sleep(500);
        long elapsedMs = (end - start) / 1_000_000;
        double opsPerSec = (eventCount * 1000.0) / elapsedMs;

        System.out.println("=== FRAMEWORK EVENT BUS THROUGHPUT (1 subscriber) ===");
        System.out.println("Events: " + eventCount);
        System.out.println("Received: " + received.get());
        System.out.println("Time: " + elapsedMs + " ms");
        System.out.println("Throughput: " + String.format("%.2f", opsPerSec) + " ops/sec");
        System.out.println();
    }

    @Test
    @DisplayName("FrameworkEventBus: Subscriber scalability (1-1000 subscribers)")
    void frameworkEventBusSubscriberScalability() throws Exception {
        int[] subscriberCounts = {1, 10, 50, 100, 500, 1000};
        int eventsPerTest = 1_000;

        System.out.println("=== FRAMEWORK EVENT BUS SUBSCRIBER SCALABILITY ===");
        System.out.println("Testing " + eventsPerTest + " events per subscriber count");
        System.out.println();

        for (int numSubscribers : subscriberCounts) {
            // Create fresh event bus for each test
            FrameworkEventBus testBus = FrameworkEventBus.create();

            // Create subscribers
            List<AtomicLong> counters = new ArrayList<>();
            for (int i = 0; i < numSubscribers; i++) {
                AtomicLong counter = new AtomicLong(0);
                counters.add(counter);
                testBus.subscribe(event -> counter.incrementAndGet());
            }

            // Benchmark
            long start = System.nanoTime();
            for (int i = 0; i < eventsPerTest; i++) {
                testBus.publish(
                        new FrameworkEventBus.FrameworkEvent.ProcessCreated(
                                Instant.now(), "proc-" + i, "test"));
            }
            long end = System.nanoTime();

            // Wait for processing
            Thread.sleep(500);

            long elapsedMs = (end - start) / 1_000_000;
            double opsPerSec = (eventsPerTest * 1000.0) / elapsedMs;

            // Verify all subscribers received events
            long totalReceived = counters.stream().mapToLong(AtomicLong::get).sum();
            double avgReceived = (double) totalReceived / numSubscribers;

            System.out.println(
                    "Subscribers: "
                            + numSubscribers
                            + " | "
                            + "Time: "
                            + elapsedMs
                            + " ms | "
                            + "Throughput: "
                            + String.format("%.2f", opsPerSec)
                            + " ops/sec | "
                            + "Avg received/sub: "
                            + String.format("%.0f", avgReceived));

            // Cleanup
            testBus.shutdown();
            counters.clear();
            Thread.sleep(100); // Let event bus settle
        }

        System.out.println();
    }
}
