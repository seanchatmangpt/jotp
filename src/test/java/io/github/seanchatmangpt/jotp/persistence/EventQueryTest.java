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

package io.github.seanchatmangpt.jotp.persistence;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test suite for EventQuery API covering:
 * - Stream-based filtering
 * - Convenience query methods
 * - Subscriptions for real-time updates
 * - Pagination with skip/limit
 * - Statistics calculation
 * - Concurrent subscribers
 */
class EventQueryTest {

    private RocksDBEventLog eventLog;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        eventLog = new RocksDBEventLog(tempDir);
    }

    // ── Stream API tests ────────────────────────────────────────────────────────

    @Test
    void testEventsStreamReturnsAllEvents() {
        // Arrange
        long now = System.nanoTime();
        Event e1 = new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1);
        Event e2 = new Event("e2", now + 1000, "p2", "node1", EventType.MESSAGE_SENT, null, -1);
        Event e3 = new Event("e3", now + 2000, "p1", "node1", EventType.PROCESS_CRASHED, null, -1);

        eventLog.append(e1);
        eventLog.append(e2);
        eventLog.append(e3);

        // Act
        var events = eventLog.query().events().collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(3);
        assertThat(events.get(0).id()).isEqualTo("e1");
        assertThat(events.get(1).id()).isEqualTo("e2");
        assertThat(events.get(2).id()).isEqualTo("e3");
    }

    @Test
    void testEventsAreOrderedBySequenceNumber() {
        // Arrange
        long now = System.nanoTime();
        Event e1 = new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1);
        Event e2 = new Event("e2", now + 1, "p1", "node1", EventType.MESSAGE_SENT, null, -1);
        Event e3 = new Event("e3", now + 2, "p1", "node1", EventType.MESSAGE_RECEIVED, null, -1);

        eventLog.append(e1);
        eventLog.append(e2);
        eventLog.append(e3);

        // Act
        var events = eventLog.query().events().collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(3);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequenceNumber()).isEqualTo(i + 1);
        }
    }

    @Test
    void testFilterByProcessId() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "cache-service", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "other-service", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "cache-service", "node1", EventType.MESSAGE_SENT, null, -1));

        // Act
        var events = eventLog.query().byProcessId("cache-service").collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.processId().equals("cache-service"));
    }

    @Test
    void testFilterByType() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "p2", "node1", EventType.PROCESS_CRASHED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "p3", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Act
        var events = eventLog.query().byType(EventType.PROCESS_CRASHED).collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.type() == EventType.PROCESS_CRASHED);
    }

    @Test
    void testFilterByNodeId() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "p2", "node2", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "p3", "node1", EventType.MESSAGE_SENT, null, -1));

        // Act
        var events = eventLog.query().byNodeId("node1").collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.nodeId().equals("node1"));
    }

    @Test
    void testEventsSinceTimestamp() {
        // Arrange
        long baseTime = System.nanoTime();
        long cutoff = baseTime + 1500;

        eventLog.append(new Event("e1", baseTime, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", baseTime + 1000, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e3", baseTime + 2000, "p1", "node1", EventType.MESSAGE_RECEIVED, null, -1));
        eventLog.append(new Event("e4", baseTime + 3000, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Act
        var events = eventLog.query().eventsSince(cutoff).collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(2);
        assertThat(events.get(0).id()).isEqualTo("e3");
        assertThat(events.get(1).id()).isEqualTo("e4");
    }

    @Test
    void testEventsBetweenTimestamps() {
        // Arrange
        long baseTime = System.nanoTime();
        long startTime = baseTime + 1000;
        long endTime = baseTime + 3000;

        eventLog.append(new Event("e1", baseTime, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", baseTime + 1500, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e3", baseTime + 2500, "p1", "node1", EventType.MESSAGE_RECEIVED, null, -1));
        eventLog.append(new Event("e4", baseTime + 4000, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Act
        var events = eventLog.query().eventsBetween(startTime, endTime).collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(2);
        assertThat(events.get(0).id()).isEqualTo("e2");
        assertThat(events.get(1).id()).isEqualTo("e3");
    }

    @Test
    void testEventsBySequenceRange() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e3", now + 2000, "p1", "node1", EventType.MESSAGE_RECEIVED, null, -1));
        eventLog.append(new Event("e4", now + 3000, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Act
        var events = eventLog.query().eventsBySequence(2, 3).collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(2);
        assertThat(events.get(0).sequenceNumber()).isEqualTo(2);
        assertThat(events.get(1).sequenceNumber()).isEqualTo(3);
    }

    @Test
    void testComposedFilters() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "cache-service", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "cache-service", "node1", EventType.PROCESS_CRASHED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "cache-service", "node2", EventType.PROCESS_RESTARTED, null, -1));
        eventLog.append(new Event("e4", now + 3000, "other-service", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Act
        var events = eventLog.query().events()
            .filter(e -> e.processId().equals("cache-service"))
            .filter(e -> e.type() == EventType.PROCESS_CRASHED)
            .collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.get(0).id()).isEqualTo("e2");
    }

    @Test
    void testPaginationWithSkipAndLimit() {
        // Arrange
        long now = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            eventLog.append(new Event(
                "e" + i,
                now + (i * 1000L),
                "p1",
                "node1",
                EventType.MESSAGE_SENT,
                null,
                -1
            ));
        }

        // Act
        var events = eventLog.query().events()
            .skip(3)
            .limit(4)
            .collect(Collectors.toList());

        // Assert
        assertThat(events).hasSize(4);
        assertThat(events.get(0).id()).isEqualTo("e3");
        assertThat(events.get(3).id()).isEqualTo("e6");
    }

    // ── Convenience query methods ───────────────────────────────────────────────

    @Test
    void testCrashesForWithinWindow() {
        // Arrange
        long now = System.nanoTime();
        long oneHourNanos = Duration.ofHours(1).toNanos();

        // Recent crash (within 1 hour)
        eventLog.append(new Event("e1", now - 1000, "cache-service", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Old crash (more than 1 hour ago)
        eventLog.append(new Event("e2", now - oneHourNanos - 1000, "cache-service", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Non-crash event
        eventLog.append(new Event("e3", now - 500, "cache-service", "node1", EventType.PROCESS_STARTED, null, -1));

        // Act
        var crashes = eventLog.query().crashesFor("cache-service", Duration.ofHours(1));

        // Assert
        assertThat(crashes).hasSize(1);
        assertThat(crashes.get(0).id()).isEqualTo("e1");
    }

    @Test
    void testMessagesFromBetweenProcesses() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "serviceA", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e2", now + 1000, "serviceB", "node1", EventType.MESSAGE_RECEIVED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "serviceB", "node1", EventType.MESSAGE_DELIVERED, null, -1));
        eventLog.append(new Event("e4", now + 3000, "serviceC", "node1", EventType.MESSAGE_SENT, null, -1));

        // Act
        var messages = eventLog.query().messagesFrom("serviceA", "serviceB");

        // Assert
        assertThat(messages).hasSize(3);
        assertThat(messages).allMatch(e -> e.type() == EventType.MESSAGE_SENT ||
                                            e.type() == EventType.MESSAGE_RECEIVED ||
                                            e.type() == EventType.MESSAGE_DELIVERED);
    }

    @Test
    void testEventsSinceTimestamp() {
        // Arrange
        long baseTime = System.nanoTime();
        long cutoff = baseTime + 1500;

        eventLog.append(new Event("e1", baseTime, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", baseTime + 1000, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e3", baseTime + 2000, "p1", "node1", EventType.MESSAGE_RECEIVED, null, -1));

        // Act
        var events = eventLog.query().eventsSinceTimestamp(cutoff);

        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.get(0).id()).isEqualTo("e3");
    }

    // ── Statistics ──────────────────────────────────────────────────────────────

    @Test
    void testStatisticsCalculation() {
        // Arrange
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e3", now + 2000, "p1", "node1", EventType.MESSAGE_RECEIVED, null, -1));
        eventLog.append(new Event("e4", now + 3000, "p1", "node1", EventType.MESSAGE_DELIVERED, null, -1));
        eventLog.append(new Event("e5", now + 4000, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));
        eventLog.append(new Event("e6", now + 5000, "p1", "node1", EventType.PROCESS_RESTARTED, null, -1));

        // Act
        var stats = eventLog.query().statistics("p1");

        // Assert
        assertThat(stats.totalMessages()).isEqualTo(3);
        assertThat(stats.totalCrashes()).isEqualTo(1);
        assertThat(stats.totalRestarts()).isEqualTo(1);
        assertThat(stats.totalDurationMs()).isGreaterThan(0);
    }

    @Test
    void testStatisticsForNonExistentProcess() {
        // Act
        var stats = eventLog.query().statistics("non-existent");

        // Assert
        assertThat(stats.totalMessages()).isEqualTo(0);
        assertThat(stats.totalCrashes()).isEqualTo(0);
        assertThat(stats.totalRestarts()).isEqualTo(0);
        assertThat(stats.totalDurationMs()).isEqualTo(0);
    }

    // ── Subscriptions ───────────────────────────────────────────────────────────

    @Test
    void testSubscribeToEventType() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(2);
        List<Event> received = new ArrayList<>();

        Subscription sub = eventLog.query().subscribe(EventType.PROCESS_CRASHED, event -> {
            received.add(event);
            latch.countDown();
        });

        // Act
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e4", now + 3000, "p2", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Assert
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
        assertThat(received).allMatch(e -> e.type() == EventType.PROCESS_CRASHED);
        assertThat(sub.isActive()).isTrue();
    }

    @Test
    void testSubscribeWithCustomPredicate() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        List<Event> received = new ArrayList<>();

        Subscription sub = eventLog.query().subscribe(
            e -> e.processId().equals("cache-service") && e.type() == EventType.PROCESS_CRASHED,
            event -> {
                received.add(event);
                latch.countDown();
            }
        );

        // Act
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "cache-service", "node1", EventType.PROCESS_STARTED, null, -1));
        eventLog.append(new Event("e2", now + 1000, "cache-service", "node1", EventType.PROCESS_CRASHED, null, -1));
        eventLog.append(new Event("e3", now + 2000, "other-service", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Assert
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).processId()).isEqualTo("cache-service");
    }

    @Test
    void testUnsubscribe() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        List<Event> received = new ArrayList<>();

        Subscription sub = eventLog.query().subscribe(EventType.PROCESS_CRASHED, event -> {
            received.add(event);
            latch.countDown();
        });

        // Act
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();

        // Unsubscribe and send another event
        sub.unsubscribe();
        assertThat(sub.isActive()).isFalse();

        eventLog.append(new Event("e2", now + 1000, "p2", "node1", EventType.PROCESS_CRASHED, null, -1));
        Thread.sleep(100); // Give virtual thread time to attempt delivery

        // Assert
        assertThat(received).hasSize(1); // Only the first event
    }

    @Test
    void testConcurrentSubscribers() throws InterruptedException {
        // Arrange
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        List<Event> sub1Events = new ArrayList<>();
        List<Event> sub2Events = new ArrayList<>();

        Subscription sub1 = eventLog.query().subscribe(EventType.PROCESS_CRASHED, event -> {
            sub1Events.add(event);
            latch1.countDown();
        });

        Subscription sub2 = eventLog.query().subscribe(EventType.PROCESS_CRASHED, event -> {
            sub2Events.add(event);
            latch2.countDown();
        });

        // Act
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.PROCESS_CRASHED, null, -1));

        // Assert
        assertThat(latch1.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(latch2.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(sub1Events).hasSize(1);
        assertThat(sub2Events).hasSize(1);
        assertThat(sub1Events.get(0).id()).isEqualTo(sub2Events.get(0).id());
    }

    @Test
    void testSubscriberErrorDoesNotKillSubscription() throws InterruptedException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(2);
        List<Event> received = new ArrayList<>();

        Subscription sub = eventLog.query().subscribe(EventType.MESSAGE_SENT, event -> {
            if (event.id().equals("e1")) {
                throw new RuntimeException("Intentional error in handler");
            }
            received.add(event);
            latch.countDown();
        });

        // Act
        long now = System.nanoTime();
        eventLog.append(new Event("e1", now, "p1", "node1", EventType.MESSAGE_SENT, null, -1));
        eventLog.append(new Event("e2", now + 1000, "p1", "node1", EventType.MESSAGE_SENT, null, -1));

        // Assert
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).id()).isEqualTo("e2");
        assertThat(sub.isActive()).isTrue();
    }
}
