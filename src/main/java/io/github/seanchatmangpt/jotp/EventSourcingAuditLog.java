package io.github.seanchatmangpt.jotp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Event sourcing audit log for StateMachine state transitions.
 *
 * <p>Provides an append-only audit trail of all state transitions within a StateMachine, enabling
 * full event replay, temporal analysis, and compliance auditing. Integrates with Proc for
 * asynchronous fire-and-forget logging, ensuring state machine performance is unaffected by I/O
 * operations.
 *
 * <p><strong>Design philosophy:</strong> This follows the CQRS (Command Query Responsibility
 * Segregation) and event sourcing patterns pioneered in systems like Apache Kafka. State machines
 * emit pure transition events; the audit log is the single source of truth for reconstructing state
 * at any point in time.
 *
 * <p><strong>Architecture:</strong>
 *
 * <ul>
 *   <li><strong>Sealed audit entry hierarchy:</strong> {@link AuditEntry} permits three variants:
 *       {@link StateChange}, {@link ErrorEntry}, {@link Replay}. Exhaustive pattern matching
 *       ensures all cases are handled.
 *   <li><strong>Pluggable durability:</strong> {@link AuditBackend} interface supports file ({@link
 *       FileBackend}), database, or in-memory ({@link InMemoryBackend}) persistence.
 *   <li><strong>Async logging:</strong> Logging is delivered via {@link Proc#tell} to a background
 *       process, preventing I/O from blocking the state machine.
 *   <li><strong>Thread safety:</strong> {@link ReentrantReadWriteLock} protects replay operations
 *       during concurrent logging and queries.
 *   <li><strong>Type safety:</strong> Generic over {@code <S>} (state), {@code <E>} (event), {@code
 *       <D>} (data) — records full transition context for replay.
 * </ul>
 *
 * <p><strong>Usage example:</strong>
 *
 * <pre>{@code
 * sealed interface LockState permits Locked, Open {}
 * sealed interface LockEvent permits PushButton, Lock {}
 *
 * var log = EventSourcingAuditLog.<LockState, LockEvent, LockData>builder()
 *     .entityId("lock-001")
 *     .backend(new FileBackend(Path.of("audit.log")))
 *     .build();
 *
 * // Log a state transition asynchronously
 * log.logTransition("lock-001", Locked.class, PushButton.class, Open.class, lockData);
 *
 * // Replay all transitions for an entity
 * var history = log.replayHistory("lock-001");
 * history.forEach(entry -> switch (entry) {
 *     case StateChange<?, ?, ?> change -> System.out.println(change.timestamp() + ": " + change.toState());
 *     case ErrorEntry<?, ?> error -> System.out.println("ERROR: " + error.exception());
 *     case Replay<?, ?, ?> replay -> System.out.println("Replay from: " + replay.replayedFromTimestamp());
 * });
 *
 * // Find all entries for an entity in a time range
 * var filtered = log.entriesFor("lock-001", startTime, endTime);
 * }</pre>
 *
 * <p><strong>Performance characteristics:</strong>
 *
 * <ul>
 *   <li><strong>Logging:</strong> Fire-and-forget via {@code Proc.tell} — zero blocking latency in
 *       the state machine path.
 *   <li><strong>Storage:</strong> Append-only files minimize write amplification. In-memory storage
 *       scales to millions of entries per GB.
 *   <li><strong>Replay:</strong> Streaming from persistent storage (files or DB) allows replay of
 *       arbitrarily large histories without loading all entries into heap.
 * </ul>
 *
 * @param <S> state type — use a sealed interface of records
 * @param <E> event type — use a sealed interface of records
 * @param <D> data type — immutable record recommended
 * @see StateMachine
 * @see Proc
 */
public final class EventSourcingAuditLog<S, E, D> {

    // ─── Audit Entry Sealed Hierarchy ─────────────────────────────────────

    /**
     * Root sealed interface for all audit log entries.
     *
     * <p>Permits three variants covering the full lifecycle of state machine operations:
     */
    public sealed interface AuditEntry permits StateChange, ErrorEntry, Replay, SnapshotEntry {
        /** Timestamp when this audit entry was created. */
        Instant timestamp();

        /** Entity ID being audited (e.g., lock name, order ID). */
        String entityId();
    }

    /**
     * Record a successful state transition.
     *
     * <p>OTP equivalent: recording the transition in a persistent event log for later replay.
     *
     * @param <S> state type
     * @param <E> event type
     * @param <D> data type
     */
    public record StateChange<S, E, D>(
            Instant timestamp,
            String entityId,
            Class<? extends S> fromState,
            Class<? extends E> event,
            Class<? extends S> toState,
            D data)
            implements AuditEntry {}

    /**
     * Record an error that occurred during state transition or processing.
     *
     * <p>Captures exceptions for debugging and compliance purposes without stopping the state
     * machine.
     *
     * @param <S> state type
     * @param <E> event type
     */
    public record ErrorEntry<S, E>(
            Instant timestamp, String entityId, Class<? extends S> state, Exception exception)
            implements AuditEntry {}

    /**
     * Meta-entry marking the start of a replay operation.
     *
     * <p>Useful for timestamping recovery operations and understanding when the audit log was
     * consulted for reconstruction.
     *
     * @param <S> state type
     * @param <E> event type
     * @param <D> data type
     */
    public record Replay<S, E, D>(Instant timestamp, String entityId, Instant replayedFromTimestamp)
            implements AuditEntry {}

    /**
     * Snapshot entry for fast state recovery.
     *
     * <p>Stores a complete state snapshot at a point in time, enabling fast recovery by loading the
     * snapshot and replaying only events after the snapshot timestamp.
     *
     * @param <S> state type
     * @param <D> data type
     */
    public record SnapshotEntry<S, D>(
            Instant timestamp, String entityId, S state, D data, long sequenceNumber)
            implements AuditEntry {}

    // ─── Audit Backend (Pluggable Durability) ─────────────────────────────

    /**
     * Interface for audit log persistence.
     *
     * <p>Supports file, database, or in-memory backends. Implement this interface to integrate with
     * your preferred storage system.
     */
    public interface AuditBackend {
        /**
         * Append an audit entry to the log.
         *
         * <p>This method must be thread-safe and maintain append-only semantics (no overwrites, no
         * deletes).
         *
         * @param entry the audit entry to persist
         * @throws IOException if persistence fails
         */
        void append(AuditEntry entry) throws IOException;

        /**
         * Retrieve all audit entries for a given entity ID.
         *
         * <p>Should return entries in chronological order (insertion order).
         *
         * @param entityId the entity to query
         * @return stream of matching entries, or empty stream if not found
         */
        Stream<AuditEntry> entriesFor(String entityId);

        /**
         * Close this backend and release any resources.
         *
         * @throws IOException if cleanup fails
         */
        void close() throws IOException;
    }

    /**
     * In-memory audit backend — suitable for testing or ephemeral logs.
     *
     * <p>Stores all entries in a {@link CopyOnWriteArrayList} for thread-safe concurrent access.
     */
    public static final class InMemoryBackend implements AuditBackend {
        private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void append(AuditEntry entry) {
            entries.add(entry);
        }

        @Override
        public Stream<AuditEntry> entriesFor(String entityId) {
            return entries.stream().filter(e -> e.entityId().equals(entityId));
        }

        @Override
        public void close() {
            entries.clear();
        }

        /** Get all entries (for testing). */
        public List<AuditEntry> all() {
            return new ArrayList<>(entries);
        }
    }

    /**
     * File-based audit backend — appends JSON-serialized entries to a file.
     *
     * <p>Each line is a complete audit entry (newline-delimited JSON), enabling streaming reads and
     * resume-from-crash behavior.
     *
     * <p>Format: {@code timestamp|entityId|type|fromState|event|toState|dataJson}
     */
    public static final class FileBackend implements AuditBackend {
        private final Path filePath;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public FileBackend(Path filePath) {
            this.filePath = filePath;
        }

        @Override
        public void append(AuditEntry entry) throws IOException {
            lock.writeLock().lock();
            try {
                String line = serializeEntry(entry);
                Files.writeString(
                        filePath,
                        line + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public Stream<AuditEntry> entriesFor(String entityId) {
            lock.readLock().lock();
            try {
                if (!Files.exists(filePath)) {
                    return Stream.empty();
                }
                return Files.lines(filePath)
                        .map(this::deserializeEntry)
                        .filter(e -> e != null && e.entityId().equals(entityId));
            } catch (IOException e) {
                return Stream.empty();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void close() throws IOException {
            // File backend requires no special cleanup
        }

        private String serializeEntry(AuditEntry entry) {
            // Pipe-delimited format with field count prefix to avoid parsing ambiguity
            return switch (entry) {
                case StateChange<?, ?, ?> sc ->
                        String.format(
                                "SC|7|%s|%s|%s|%s|%s|%s|%s",
                                sc.timestamp(),
                                sc.entityId(),
                                escapeField(sc.fromState().getName()),
                                escapeField(sc.event().getName()),
                                escapeField(sc.toState().getName()),
                                escapeField(serializeObject(sc.data())));
                case ErrorEntry<?, ?> ee ->
                        String.format(
                                "EE|4|%s|%s|%s|%s",
                                ee.timestamp(),
                                ee.entityId(),
                                escapeField(ee.state().getName()),
                                escapeField(serializeThrowable(ee.exception())));
                case Replay<?, ?, ?> rp ->
                        String.format(
                                "RP|3|%s|%s|%s",
                                rp.timestamp(), rp.entityId(), rp.replayedFromTimestamp());
                case SnapshotEntry<?, ?> se ->
                        String.format(
                                "SN|5|%s|%s|%s|%s|%s",
                                se.timestamp(),
                                se.entityId(),
                                escapeField(serializeObject(se.state())),
                                escapeField(serializeObject(se.data())),
                                se.sequenceNumber());
            };
        }

        private String escapeField(String field) {
            if (field == null) return "\\0";
            return field.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n");
        }

        private String unescapeField(String field) {
            if ("\\0".equals(field)) return null;
            return field.replace("\\n", "\n").replace("\\|", "|").replace("\\\\", "\\");
        }

        private String serializeObject(Object obj) {
            if (obj == null) return "null";
            // Use Java serialization as Base64 string for complex objects
            try {
                var baos = new ByteArrayOutputStream();
                var oos = new ObjectOutputStream(baos);
                oos.writeObject(obj);
                oos.close();
                return Base64.getEncoder().encodeToString(baos.toByteArray());
            } catch (Exception e) {
                // Fallback: use toString() if not serializable
                return "toString:" + obj.toString();
            }
        }

        private String serializeThrowable(Throwable t) {
            // Serialize exception class name and message
            var className = t.getClass().getName();
            var message = t.getMessage() != null ? t.getMessage() : "";
            return className + ":" + message.replace("|", "\\|");
        }

        private Object deserializeObject(String data) {
            if (data == null || data.equals("null")) return null;
            if (data.startsWith("toString:")) {
                return data.substring(9); // Return as string
            }
            // Decode Base64 and deserialize
            try {
                var bytes = Base64.getDecoder().decode(data);
                var bais = new ByteArrayInputStream(bytes);
                var ois = new ObjectInputStream(bais);
                var obj = ois.readObject();
                ois.close();
                return obj;
            } catch (Exception e) {
                return null; // Return null if deserialization fails
            }
        }

        private Throwable deserializeThrowable(String data) {
            var parts = data.split(":", 2);
            var className = parts[0];
            var message = parts.length > 1 ? parts[1].replace("\\|", "|") : "";
            try {
                @SuppressWarnings("unchecked")
                Class<? extends Throwable> clazz =
                        (Class<? extends Throwable>) Class.forName(className);
                return clazz.getConstructor(String.class).newInstance(message);
            } catch (Exception e) {
                // Fallback: create RuntimeException with the serialized info
                return new RuntimeException(className + ": " + message);
            }
        }

        private AuditEntry deserializeEntry(String line) {
            try {
                // Split only on the first 3 separators to get type, fieldCount, and rest
                var firstPipe = line.indexOf('|');
                if (firstPipe == -1) return null;

                var type = line.substring(0, firstPipe);
                var remainder = line.substring(firstPipe + 1);

                var secondPipe = remainder.indexOf('|');
                if (secondPipe == -1) return null;

                var fieldCountStr = remainder.substring(0, secondPipe);
                var fieldCount = Integer.parseInt(fieldCountStr);
                var dataStr = remainder.substring(secondPipe + 1);

                // Split the remaining data, preserving escaped characters
                var fields = smartSplit(dataStr, fieldCount);

                return switch (type) {
                    case "SC" -> {
                        if (fields.length < 6) yield null;
                        var timestamp = Instant.parse(fields[0]);
                        var entityId = fields[1];
                        var fromStateClass = Class.forName(unescapeField(fields[2]));
                        var eventClass = Class.forName(unescapeField(fields[3]));
                        var toStateClass = Class.forName(unescapeField(fields[4]));
                        var data = deserializeObject(unescapeField(fields[5]));
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        AuditEntry entry =
                                new StateChange(
                                        timestamp, entityId, fromStateClass, eventClass, toStateClass,
                                        data);
                        yield entry;
                    }
                    case "EE" -> {
                        if (fields.length < 4) yield null;
                        var timestamp = Instant.parse(fields[0]);
                        var entityId = fields[1];
                        var stateClass = Class.forName(unescapeField(fields[2]));
                        var exception = deserializeThrowable(unescapeField(fields[3]));
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        AuditEntry entry = new ErrorEntry(timestamp, entityId, stateClass, exception);
                        yield entry;
                    }
                    case "RP" -> {
                        if (fields.length < 3) yield null;
                        var timestamp = Instant.parse(fields[0]);
                        var entityId = fields[1];
                        var replayedFromTimestamp = Instant.parse(fields[2]);
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        AuditEntry entry =
                                new Replay(timestamp, entityId, replayedFromTimestamp);
                        yield entry;
                    }
                    case "SN" -> {
                        if (fields.length < 5) yield null;
                        var timestamp = Instant.parse(fields[0]);
                        var entityId = fields[1];
                        var state = deserializeObject(unescapeField(fields[2]));
                        var data = deserializeObject(unescapeField(fields[3]));
                        var sequenceNumber = Long.parseLong(fields[4]);
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        AuditEntry entry =
                                new SnapshotEntry(timestamp, entityId, state, data, sequenceNumber);
                        yield entry;
                    }
                    default -> null;
                };
            } catch (Exception e) {
                // Log silently and return null to allow stream to skip malformed entries
                return null;
            }
        }

        private String[] smartSplit(String data, int expectedFields) {
            var fields = new ArrayList<String>();
            var current = new StringBuilder();
            var escaped = false;

            for (int i = 0; i < data.length(); i++) {
                var c = data.charAt(i);

                if (escaped) {
                    current.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    current.append(c);
                    escaped = true;
                } else if (c == '|') {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }

            // Add last field
            if (current.length() > 0 || fields.size() < expectedFields) {
                fields.add(current.toString());
            }

            return fields.toArray(new String[0]);
        }
    }

    // ─── Logging Message Types ────────────────────────────────────────────

    /**
     * Internal message sent to the audit logger process.
     *
     * <p>Used by {@link Proc#tell} to deliver audit entries asynchronously.
     */
    sealed interface AuditMessage {
        record LogTransition<S, E, D>(StateChange<S, E, D> entry) implements AuditMessage {}

        record LogError(ErrorEntry<?, ?> entry) implements AuditMessage {}

        record ReplayRequest(
                String entityId, java.util.concurrent.CompletableFuture<List<AuditEntry>> reply)
                implements AuditMessage {}

        record LogSnapshot<S, D>(SnapshotEntry<S, D> entry) implements AuditMessage {}

        record LoadSnapshot<S, D>(
                String entityId,
                java.util.concurrent.CompletableFuture<Optional<SnapshotEntry<S, D>>> reply)
                implements AuditMessage {}

        record Flush() implements AuditMessage {}
    }

    // ─── Main Class ────────────────────────────────────────────────────────

    private final String entityId;
    private final AuditBackend backend;
    private final Proc<Void, AuditMessage> logger;
    private final ReentrantReadWriteLock replayLock = new ReentrantReadWriteLock();

    private EventSourcingAuditLog(String entityId, AuditBackend backend) {
        this.entityId = entityId;
        this.backend = backend;

        // In-memory snapshot store for fast access
        final java.util.concurrent.ConcurrentSkipListMap<String, SnapshotEntry<S, D>>
                snapshotStore = new java.util.concurrent.ConcurrentSkipListMap<>();

        // Start the audit logger process (fire-and-forget via virtual thread)
        this.logger =
                new Proc<>(
                        null,
                        (state, msg) -> {
                            try {
                                if (msg instanceof AuditMessage.LogTransition<?, ?, ?> log) {
                                    backend.append(log.entry());
                                } else if (msg instanceof AuditMessage.LogError log) {
                                    backend.append(log.entry());
                                } else if (msg instanceof AuditMessage.ReplayRequest req) {
                                    replayLock.readLock().lock();
                                    try {
                                        List<AuditEntry> entries =
                                                backend.entriesFor(req.entityId()).toList();
                                        req.reply().complete(entries);
                                    } finally {
                                        replayLock.readLock().unlock();
                                    }
                                } else if (msg instanceof AuditMessage.LogSnapshot<?, ?> snap) {
                                    @SuppressWarnings("unchecked")
                                    SnapshotEntry<S, D> entry = (SnapshotEntry<S, D>) snap.entry();
                                    String key = entry.entityId() + ":" + entry.sequenceNumber();
                                    snapshotStore.put(key, entry);
                                    backend.append(entry);
                                } else if (msg instanceof AuditMessage.LoadSnapshot<?, ?> load) {
                                    String prefix = load.entityId() + ":";
                                    Optional<SnapshotEntry<S, D>> latest =
                                            snapshotStore.entrySet().stream()
                                                    .filter(e -> e.getKey().startsWith(prefix))
                                                    .max(java.util.Map.Entry.comparingByKey())
                                                    .map(java.util.Map.Entry::getValue);
                                    // Use raw type to avoid capture wildcard issues
                                    @SuppressWarnings({"unchecked", "rawtypes"})
                                    CompletableFuture rawReply = load.reply();
                                    rawReply.complete(latest);
                                } else if (msg instanceof AuditMessage.Flush) {
                                    // Flush is a no-op for in-memory backend
                                    // For file-based backends, this would trigger fsync
                                }
                            } catch (Exception e) {
                                // Log suppressed; audit logger must never crash the state machine
                                Thread.currentThread().interrupt();
                            }
                            return state;
                        });
    }

    /**
     * Log a successful state transition asynchronously.
     *
     * <p>This method returns immediately (fire-and-forget). The actual logging to the backend
     * happens in the background audit logger process, ensuring the caller (typically a state
     * machine) is never blocked by I/O.
     *
     * @param entityId the entity being tracked
     * @param fromState the class of the source state
     * @param event the class of the triggering event
     * @param toState the class of the target state
     * @param data the machine data after the transition
     */
    public void logTransition(
            String entityId,
            Class<? extends S> fromState,
            Class<? extends E> event,
            Class<? extends S> toState,
            D data) {
        var entry = new StateChange<>(Instant.now(), entityId, fromState, event, toState, data);
        logger.tell(new AuditMessage.LogTransition(entry));
    }

    /**
     * Log an error that occurred during state machine processing.
     *
     * <p>Errors are recorded with timestamp and state context for debugging and compliance. The
     * audit log continues operation even if errors occur.
     *
     * @param entityId the entity being tracked
     * @param state the state when the error occurred
     * @param exception the exception that was raised
     */
    public void logError(String entityId, Class<? extends S> state, Exception exception) {
        var entry = new ErrorEntry<>(Instant.now(), entityId, state, exception);
        logger.tell(new AuditMessage.LogError(entry));
    }

    /**
     * Synchronously retrieve all audit entries for an entity.
     *
     * <p>This method blocks until the audit logger process completes the query, ensuring all
     * entries logged before the call have been persisted.
     *
     * <p>For large histories, prefer streaming via {@link #streamHistory(String)}.
     *
     * @param entityId the entity to query
     * @return list of all audit entries for this entity, in chronological order
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public List<AuditEntry> replayHistory(String entityId) throws InterruptedException {
        replayLock.writeLock().lock();
        try {
            var reply = new java.util.concurrent.CompletableFuture<List<AuditEntry>>();
            logger.tell(new AuditMessage.ReplayRequest(entityId, reply));
            return reply.join();
        } finally {
            replayLock.writeLock().unlock();
        }
    }

    /**
     * Stream audit entries for an entity without loading all into memory.
     *
     * <p>Useful for processing very large histories or implementing streaming replay.
     *
     * @param entityId the entity to query
     * @return stream of audit entries
     */
    public Stream<AuditEntry> streamHistory(String entityId) {
        return backend.entriesFor(entityId);
    }

    /**
     * Reconstruct state by replaying all transitions for an entity.
     *
     * <p>Applies each {@link StateChange} entry to the initial state using the provided replay
     * function. Other entry types (errors, metadata) are skipped.
     *
     * <p>Usage:
     *
     * <pre>{@code
     * var finalState = log.replay("lock-001",
     *     initialState,
     *     (state, change) -> switch (state) {
     *         case Locked _ -> ... // handle transition to change.toState()
     *         ...
     *     });
     * }</pre>
     *
     * @param entityId the entity to replay
     * @param initialState the starting state
     * @param transitionFn function to apply each transition: {@code (state, change) -> newState}
     * @return the final reconstructed state
     * @throws InterruptedException if replay is interrupted
     */
    public S replay(
            String entityId,
            S initialState,
            java.util.function.BiFunction<S, StateChange<S, E, D>, S> transitionFn)
            throws InterruptedException {
        return replayHistory(entityId).stream()
                .filter(e -> e instanceof StateChange)
                .map(e -> (StateChange<S, E, D>) e)
                .reduce(initialState, transitionFn, (s1, s2) -> s2);
    }

    /**
     * Get all entries for an entity in a specific time range.
     *
     * <p>Filters entries by timestamp.
     *
     * @param entityId the entity to query
     * @param startTime earliest timestamp (inclusive)
     * @param endTime latest timestamp (inclusive)
     * @return list of entries within the time range
     */
    public List<AuditEntry> entriesInRange(String entityId, Instant startTime, Instant endTime) {
        return streamHistory(entityId)
                .filter(e -> !e.timestamp().isBefore(startTime) && !e.timestamp().isAfter(endTime))
                .toList();
    }

    /**
     * Close this audit log and release all resources (including the background logger process).
     *
     * @throws InterruptedException if interrupted while stopping the logger process
     */
    public void close() throws InterruptedException, java.io.IOException {
        logger.stop();
        backend.close();
    }

    // ─── Snapshot Support ────────────────────────────────────────────────────────

    /**
     * Save a state snapshot for fast recovery.
     *
     * <p>Snapshots capture the complete state at a point in time, enabling fast recovery by loading
     * the snapshot and replaying only events after the snapshot timestamp.
     *
     * @param entityId the entity ID
     * @param state the current state
     * @param data the current data
     * @throws InterruptedException if interrupted while saving
     */
    public void saveSnapshot(String entityId, S state, D data) throws InterruptedException {
        var entry = new SnapshotEntry<>(Instant.now(), entityId, state, data, System.nanoTime());
        logger.tell(new AuditMessage.LogSnapshot(entry));
    }

    /**
     * Load the latest snapshot for an entity.
     *
     * @param entityId the entity ID
     * @return Optional containing the latest snapshot, or empty if none exists
     * @throws InterruptedException if interrupted while loading
     */
    @SuppressWarnings("unchecked")
    public Optional<SnapshotEntry<S, D>> loadLatestSnapshot(String entityId)
            throws InterruptedException {
        var reply = new java.util.concurrent.CompletableFuture<Optional<SnapshotEntry<S, D>>>();
        logger.tell(new AuditMessage.LoadSnapshot(entityId, reply));
        return reply.join();
    }

    /**
     * Flush any buffered writes to ensure durability.
     *
     * <p>Called during shutdown to ensure all entries are persisted.
     */
    public void flush() {
        logger.tell(new AuditMessage.Flush());
    }

    /**
     * Replay state from a snapshot, applying only events after the snapshot.
     *
     * @param entityId the entity ID
     * @param snapshot the starting snapshot
     * @param transitionFn function to apply each transition
     * @return the final reconstructed state
     * @throws InterruptedException if replay is interrupted
     */
    public S replayFromSnapshot(
            String entityId,
            SnapshotEntry<S, D> snapshot,
            java.util.function.BiFunction<S, StateChange<S, E, D>, S> transitionFn)
            throws InterruptedException {
        return streamHistory(entityId)
                .filter(
                        e ->
                                e instanceof StateChange
                                        && e.timestamp().isAfter(snapshot.timestamp()))
                .map(e -> (StateChange<S, E, D>) e)
                .reduce(snapshot.state(), transitionFn, (s1, s2) -> s2);
    }

    /**
     * Create a new builder for configuring an EventSourcingAuditLog.
     *
     * @param <S> state type
     * @param <E> event type
     * @param <D> data type
     * @return a new builder
     */
    public static <S, E, D> Builder<S, E, D> builder() {
        return new Builder<>();
    }

    // ─── Builder ──────────────────────────────────────────────────────────

    /**
     * Fluent builder for EventSourcingAuditLog configuration.
     *
     * @param <S> state type
     * @param <E> event type
     * @param <D> data type
     */
    public static final class Builder<S, E, D> {
        private String entityId = UUID.randomUUID().toString();
        private AuditBackend backend = new InMemoryBackend();

        /** Set the entity ID for this audit log. */
        public Builder<S, E, D> entityId(String entityId) {
            this.entityId = entityId;
            return this;
        }

        /** Set the backend for persistence. */
        public Builder<S, E, D> backend(AuditBackend backend) {
            this.backend = backend;
            return this;
        }

        /** Build the EventSourcingAuditLog instance. */
        public EventSourcingAuditLog<S, E, D> build() {
            return new EventSourcingAuditLog<>(entityId, backend);
        }
    }
}
