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

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Query API for JOTP event logs with idiomatic Java Streams and convenience filters.
 *
 * <p><b>Stream-based filtering:</b>
 * <pre>{@code
 * eventLog.query().events()
 *     .filter(e -> e.processId().equals("cache-service"))
 *     .filter(e -> e.type() == EventType.CRASH)
 *     .collect(toList());
 * }</pre>
 *
 * <p><b>Convenience queries:</b>
 * <pre>{@code
 * // All crashes for a process in the last hour
 * eventLog.query().crashesFor("cache-service", Duration.ofHours(1));
 *
 * // Messages sent from one process to another
 * eventLog.query().messagesFrom("service-a", "service-b");
 *
 * // Pagination
 * eventLog.query().events()
 *     .skip(1000)
 *     .limit(100)
 *     .collect(toList());
 * }</pre>
 *
 * <p><b>Subscriptions for real-time streaming:</b>
 * <pre>{@code
 * Subscription sub = eventLog.query().subscribe(EventType.CRASH, event -> {
 *     alerting.notify("Process " + event.processId() + " crashed");
 * });
 *
 * // Later, when no longer needed:
 * sub.unsubscribe();
 * }</pre>
 *
 * <p><b>Observability statistics:</b>
 * <pre>{@code
 * ProcessStatistics stats = eventLog.query().statistics("cache-service");
 * System.out.println("Crashes in lifetime: " + stats.totalCrashes());
 * }</pre>
 */
public interface EventQuery {

    // ── Stream API ──────────────────────────────────────────────────────────────

    /**
     * Returns a stream of all events in the log.
     *
     * <p>The stream is lazily evaluated. Use standard Stream operations:
     * <pre>{@code
     * eventLog.query().events()
     *     .filter(e -> e.type() == EventType.PROCESS_CRASHED)
     *     .map(Event::processId)
     *     .distinct()
     *     .collect(toSet());
     * }</pre>
     *
     * @return a stream of all events, in order
     */
    Stream<Event> events();

    /**
     * Returns a stream of events since a specific timestamp (exclusive).
     *
     * @param timestampNanos the cutoff timestamp (from System.nanoTime())
     * @return a stream of events with timestampNanos > the provided value
     */
    Stream<Event> eventsSince(long timestampNanos);

    /**
     * Returns a stream of events within a timestamp range.
     *
     * @param startNanos    the start of the range (inclusive)
     * @param endNanos      the end of the range (inclusive)
     * @return a stream of events where startNanos <= timestampNanos <= endNanos
     */
    Stream<Event> eventsBetween(long startNanos, long endNanos);

    /**
     * Returns a stream of events with a specific sequence number range.
     *
     * @param startSeq  the first sequence number (inclusive)
     * @param endSeq    the last sequence number (inclusive)
     * @return a stream of events in the specified sequence range
     */
    Stream<Event> eventsBySequence(long startSeq, long endSeq);

    // ── Convenience filters ──────────────────────────────────────────────────────

    /**
     * Returns a stream of events for a specific process.
     *
     * @param processId the process identifier to filter by
     * @return a stream of events where processId matches
     */
    Stream<Event> byProcessId(String processId);

    /**
     * Returns a stream of events of a specific type.
     *
     * @param type the event type to filter by
     * @return a stream of events matching the type
     */
    Stream<Event> byType(EventType type);

    /**
     * Returns a stream of events from a specific node.
     *
     * @param nodeId the node identifier to filter by
     * @return a stream of events from that node
     */
    Stream<Event> byNodeId(String nodeId);

    /**
     * Returns a stream of events matching a custom predicate.
     *
     * @param predicate the filtering predicate
     * @return a stream of events that match the predicate
     */
    Stream<Event> filter(Predicate<Event> predicate);

    // ── Common query patterns ───────────────────────────────────────────────────

    /**
     * Returns all crash events for a process within a time window.
     *
     * <p>Convenience method equivalent to:
     * <pre>{@code
     * long now = System.nanoTime();
     * long windowNanos = window.toNanos();
     * eventLog.query()
     *     .byProcessId(processId)
     *     .filter(e -> e.type() == EventType.PROCESS_CRASHED)
     *     .filter(e -> e.timestampNanos() >= (now - windowNanos))
     *     .collect(toList());
     * }</pre>
     *
     * @param processId the process identifier
     * @param window    the time window to look back (e.g., Duration.ofHours(1))
     * @return a list of crash events within the window, in order
     */
    List<Event> crashesFor(String processId, Duration window);

    /**
     * Returns all message events between two processes.
     *
     * <p>Includes MESSAGE_SENT, MESSAGE_RECEIVED, and MESSAGE_DELIVERED events
     * where either sourceId sent to targetId or vice versa.
     *
     * @param sourceId  the first process identifier
     * @param targetId  the second process identifier
     * @return a list of message events, in order
     */
    List<Event> messagesFrom(String sourceId, String targetId);

    /**
     * Returns all events since a specific timestamp.
     *
     * <p>Convenience method for streaming replicas and event tailing.
     *
     * @param timestampNanos the cutoff timestamp from System.nanoTime()
     * @return a list of all events newer than the timestamp
     */
    List<Event> eventsSinceTimestamp(long timestampNanos);

    /**
     * Computes statistics for a process from its event log entries.
     *
     * <p>Statistics are computed by:
     * - Counting MESSAGE_SENT/RECEIVED/DELIVERED events (totalMessages)
     * - Counting PROCESS_CRASHED events (totalCrashes)
     * - Counting PROCESS_RESTARTED events (totalRestarts)
     * - Computing elapsed time from first to last event
     * - Recording the most recent event timestamp (lastActive)
     *
     * @param processId the process identifier
     * @return statistics for the process, or a zero-filled record if process not found
     */
    ProcessStatistics statistics(String processId);

    // ── Subscriptions ───────────────────────────────────────────────────────────

    /**
     * Subscribes a handler to all events of a specific type.
     *
     * <p>The handler will be called asynchronously for each event:
     * <pre>{@code
     * Subscription sub = eventLog.query().subscribe(EventType.CRASH, event -> {
     *     alerting.notify("Process " + event.processId() + " crashed");
     * });
     *
     * // Later...
     * sub.unsubscribe();
     * }</pre>
     *
     * <p>Handler execution:
     * - Runs on a virtual thread to prevent blocking the event log
     * - Exceptions in the handler are logged but don't kill the subscription
     * - Events are delivered in order
     *
     * @param type    the event type to subscribe to
     * @param handler the callback invoked for each matching event
     * @return a subscription handle for unsubscribing
     */
    Subscription subscribe(EventType type, Consumer<Event> handler);

    /**
     * Subscribes a handler to events matching a custom predicate.
     *
     * <p>The handler will be called asynchronously for each event that matches:
     * <pre>{@code
     * Subscription sub = eventLog.query().subscribe(
     *     e -> e.processId().equals("cache-service") && e.type() == EventType.CRASH,
     *     event -> alerting.notify("Cache service crashed")
     * );
     * }</pre>
     *
     * <p>Same execution semantics as {@link #subscribe(EventType, Consumer)}.
     *
     * @param predicate the filtering predicate
     * @param handler   the callback invoked for each matching event
     * @return a subscription handle for unsubscribing
     */
    Subscription subscribe(Predicate<Event> predicate, Consumer<Event> handler);
}
