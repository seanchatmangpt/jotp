package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.rocksdb.*;

/**
 * Persistent backend for the global process registry using RocksDB with atomic batch writes.
 *
 * <p>Stores registrations in a local RocksDB database, providing:
 *
 * <ul>
 *   <li><strong>Persistence:</strong> Registrations survive JVM restarts
 *   <li><strong>Performance:</strong> Fast lookups via LSM-tree storage
 *   <li><strong>Atomicity:</strong> Atomic writes via WriteBatch for crash safety
 *   <li><strong>Idempotence:</strong> Sequence number verification for crash recovery
 * </ul>
 *
 * <p><strong>Column families:</strong> Uses two dedicated column families:
 *
 * <ul>
 *   <li>{@code global_registry} — stores registry entries
 *   <li>{@code global_registry_ack} — stores ACK sequence numbers
 * </ul>
 *
 * <p><strong>Atomic Write Pattern:</strong>
 *
 * <pre>{@code
 * WriteBatch batch = new WriteBatch();
 * batch.put(registryHandle, key, serialize(ref));
 * batch.put(ackHandle, ackKey, String.valueOf(ref.sequenceNumber()));
 * db.write(new WriteOptions().setSync(true), batch);
 * }</pre>
 *
 * <p><strong>Idempotent Recovery:</strong> On startup and lookup, the system verifies that the
 * stored sequence number matches the ACK. If they differ, a partial write occurred (crash before
 * ACK), and the system recovers using the lower value.
 *
 * <p><strong>Serialization:</strong> Registrations are stored as JSON strings for portability:
 *
 * <pre>{@code
 * {
 *   "name": "my-service",
 *   "nodeName": "node-1",
 *   "sequenceNumber": 42,
 *   "registeredAt": "2024-01-15T10:30:00Z"
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> The {@code ProcRef} itself cannot be serialized. Instead, we store
 * metadata and the caller must provide the live {@code ProcRef} when re-registering after restart.
 * The {@link #lookup(String)} method returns a {@code GlobalProcRef} with a {@code null} localRef
 * for entries loaded from disk — callers should use {@link #rehydrate(String,
 * io.github.seanchatmangpt.jotp.ProcRef)} to attach a live reference.
 *
 * @see GlobalRegistryBackend
 * @see InMemoryGlobalRegistryBackend
 */
public final class RocksDBGlobalRegistryBackend implements GlobalRegistryBackend {

    private static final String REGISTRY_COLUMN_FAMILY = "global_registry";
    private static final String ACK_COLUMN_FAMILY = "global_registry_ack";
    private static final byte[] REGISTRY_CF_BYTES =
            REGISTRY_COLUMN_FAMILY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACK_CF_BYTES = ACK_COLUMN_FAMILY.getBytes(StandardCharsets.UTF_8);

    private final Path dbPath;
    private RocksDB db;
    private ColumnFamilyHandle registryHandle;
    private ColumnFamilyHandle ackHandle;
    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> watchers =
            new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, GlobalProcRef> liveRefs = new ConcurrentHashMap<>();
    private final Object casLock = new Object();
    private final AtomicLong globalSequenceCounter = new AtomicLong(0);

    /**
     * Create a new RocksDB-backed registry.
     *
     * @param dbPath directory for the RocksDB database files
     */
    public RocksDBGlobalRegistryBackend(Path dbPath) {
        this.dbPath = dbPath;
        RocksDB.loadLibrary();
    }

    /** Start the database. Must be called before any operations. */
    public synchronized void start() throws RocksDBException {
        if (db != null) return;

        // Default column family options
        final ColumnFamilyOptions cfOpts =
                new ColumnFamilyOptions().optimizeUniversalStyleCompaction();

        // List of column family descriptors - must include default
        final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts));
        cfDescriptors.add(new ColumnFamilyDescriptor(REGISTRY_CF_BYTES, cfOpts));
        cfDescriptors.add(new ColumnFamilyDescriptor(ACK_CF_BYTES, cfOpts));

        final DBOptions dbOpts =
                new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);

        final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
        db = RocksDB.open(dbOpts, dbPath.toString(), cfDescriptors, cfHandles);

        // Find our column family handles
        for (int i = 0; i < cfDescriptors.size(); i++) {
            byte[] cfName = cfDescriptors.get(i).getName();
            if (Arrays.equals(cfName, REGISTRY_CF_BYTES)) {
                registryHandle = cfHandles.get(i);
            } else if (Arrays.equals(cfName, ACK_CF_BYTES)) {
                ackHandle = cfHandles.get(i);
            }
        }

        if (registryHandle == null || ackHandle == null) {
            throw new RocksDBException("Failed to create column family handles");
        }

        // Load persisted entries into live cache (with null ProcRefs to be rehydrated)
        loadPersistedEntries();
    }

    /** Stop the database. */
    public synchronized void stop() {
        if (db != null) {
            if (registryHandle != null) {
                registryHandle.close();
            }
            if (ackHandle != null) {
                ackHandle.close();
            }
            db.close();
            db = null;
            registryHandle = null;
            ackHandle = null;
        }
    }

    private void loadPersistedEntries() throws RocksDBException {
        long maxSeq = 0;
        try (RocksIterator it = db.newIterator(registryHandle)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                String name = new String(it.key(), StandardCharsets.UTF_8);
                String json = new String(it.value(), StandardCharsets.UTF_8);
                GlobalProcRef ref = deserialize(name, json);

                // Verify consistency with ACK
                byte[] ackKey = ackKey(name);
                byte[] ackBytes = db.get(ackHandle, ackKey);

                if (ackBytes != null) {
                    long ackSeq = Long.parseLong(new String(ackBytes, StandardCharsets.UTF_8));
                    if (ref.sequenceNumber() != ackSeq) {
                        // Mismatch: crash during write - use lower value for idempotent recovery
                        long recoveredSeq = Math.min(ref.sequenceNumber(), ackSeq);
                        ref = ref.withSequenceNumber(recoveredSeq);
                    }
                }

                liveRefs.put(name, ref);
                maxSeq = Math.max(maxSeq, ref.sequenceNumber());
            }
        }
        globalSequenceCounter.set(maxSeq);
    }

    @Override
    public Result<Void, RegistryError> store(String name, GlobalProcRef ref) {
        return storeAtomic(name, ref);
    }

    @Override
    public Result<Void, RegistryError> storeAtomic(String name, GlobalProcRef ref) {
        ensureStarted();
        try (WriteBatch batch = new WriteBatch()) {
            byte[] key = name.getBytes(StandardCharsets.UTF_8);
            byte[] ackKey = ackKey(name);

            // Write 1: Registry entry
            batch.put(registryHandle, key, serialize(ref));

            // Write 2: ACK (sequence number)
            batch.put(
                    ackHandle,
                    ackKey,
                    String.valueOf(ref.sequenceNumber()).getBytes(StandardCharsets.UTF_8));

            // Atomic write with sync for durability
            db.write(new WriteOptions().setSync(true), batch);

            liveRefs.put(name, ref);
            notifyWatchers(
                    new RegistryEvent(RegistryEvent.Type.REGISTERED, name, Optional.of(ref)));
            return Result.ok(null);
        } catch (RocksDBException e) {
            return Result.failure(RegistryError.TIMEOUT);
        }
    }

    @Override
    public Optional<GlobalProcRef> lookup(String name) {
        return verifyAndRecover(name);
    }

    @Override
    public Optional<GlobalProcRef> verifyAndRecover(String name) {
        ensureStarted();

        // Check live cache first
        GlobalProcRef cached = liveRefs.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Load from disk and verify
        try {
            byte[] key = name.getBytes(StandardCharsets.UTF_8);
            byte[] refBytes = db.get(registryHandle, key);

            if (refBytes == null) {
                return Optional.empty();
            }

            GlobalProcRef ref = deserialize(name, new String(refBytes, StandardCharsets.UTF_8));

            // Verify consistency with ACK
            byte[] ackBytes = db.get(ackHandle, ackKey(name));
            if (ackBytes != null) {
                long ackSeq = Long.parseLong(new String(ackBytes, StandardCharsets.UTF_8));
                if (ref.sequenceNumber() != ackSeq) {
                    // Mismatch: crash during write - use lower value for idempotent recovery
                    long recoveredSeq = Math.min(ref.sequenceNumber(), ackSeq);
                    ref = ref.withSequenceNumber(recoveredSeq);
                }
            }

            liveRefs.put(name, ref);
            return Optional.of(ref);
        } catch (RocksDBException e) {
            return Optional.empty();
        }
    }

    /**
     * Rehydrate a persisted entry with a live ProcRef.
     *
     * <p>After a restart, entries loaded from disk have null {@code localRef}. This method attaches
     * a live reference so the process can receive messages.
     *
     * @param name the global name
     * @param localRef the live process reference
     * @return the updated global reference, or empty if not found
     */
    public Optional<GlobalProcRef> rehydrate(
            String name, io.github.seanchatmangpt.jotp.ProcRef<?, ?> localRef) {
        ensureStarted();
        GlobalProcRef current = liveRefs.get(name);
        if (current == null) {
            return Optional.empty();
        }
        GlobalProcRef updated = current.withLocalRef(localRef);
        liveRefs.put(name, updated);
        // Also update persisted entry atomically
        try (WriteBatch batch = new WriteBatch()) {
            byte[] key = name.getBytes(StandardCharsets.UTF_8);
            byte[] ackKey = ackKey(name);

            batch.put(registryHandle, key, serialize(updated));
            batch.put(
                    ackHandle,
                    ackKey,
                    String.valueOf(updated.sequenceNumber()).getBytes(StandardCharsets.UTF_8));

            db.write(new WriteOptions().setSync(true), batch);
        } catch (RocksDBException e) {
            // Log but don't fail - live ref is still usable
        }
        return Optional.of(updated);
    }

    @Override
    public Result<Void, RegistryError> remove(String name) {
        return removeAtomic(name);
    }

    @Override
    public Result<Void, RegistryError> removeAtomic(String name) {
        ensureStarted();
        try (WriteBatch batch = new WriteBatch()) {
            byte[] key = name.getBytes(StandardCharsets.UTF_8);
            byte[] ackKey = ackKey(name);

            // Remove both registry entry and ACK atomically
            batch.delete(registryHandle, key);
            batch.delete(ackHandle, ackKey);

            db.write(new WriteOptions().setSync(true), batch);

            GlobalProcRef removed = liveRefs.remove(name);
            if (removed != null) {
                notifyWatchers(
                        new RegistryEvent(RegistryEvent.Type.UNREGISTERED, name, Optional.empty()));
            }
            return Result.ok(null);
        } catch (RocksDBException e) {
            return Result.failure(RegistryError.TIMEOUT);
        }
    }

    @Override
    public Map<String, GlobalProcRef> listAll() {
        ensureStarted();
        return new HashMap<>(liveRefs);
    }

    @Override
    public boolean compareAndSwap(
            String name, Optional<GlobalProcRef> expected, GlobalProcRef newValue) {
        ensureStarted();
        // Use synchronized block for CAS since we need atomic read-modify-write
        synchronized (casLock) {
            try {
                byte[] key = name.getBytes(StandardCharsets.UTF_8);
                byte[] refBytes = db.get(registryHandle, key);

                GlobalProcRef currentRef = null;
                if (refBytes != null) {
                    currentRef = deserialize(name, new String(refBytes, StandardCharsets.UTF_8));

                    // Verify consistency with ACK
                    byte[] ackBytes = db.get(ackHandle, ackKey(name));
                    if (ackBytes != null) {
                        long ackSeq = Long.parseLong(new String(ackBytes, StandardCharsets.UTF_8));
                        if (currentRef.sequenceNumber() != ackSeq) {
                            // Mismatch: use lower value for idempotent recovery
                            long recoveredSeq = Math.min(currentRef.sequenceNumber(), ackSeq);
                            currentRef = currentRef.withSequenceNumber(recoveredSeq);
                        }
                    }
                }

                if (expected.isEmpty()) {
                    // Expecting absent
                    if (currentRef != null) {
                        return false;
                    }
                } else {
                    // Expecting specific value
                    if (!Objects.equals(currentRef, expected.get())) {
                        return false;
                    }
                }

                // Perform the swap atomically with WriteBatch
                try (WriteBatch batch = new WriteBatch()) {
                    batch.put(registryHandle, key, serialize(newValue));
                    batch.put(
                            ackHandle,
                            ackKey(name),
                            String.valueOf(newValue.sequenceNumber())
                                    .getBytes(StandardCharsets.UTF_8));

                    db.write(new WriteOptions().setSync(true), batch);
                }

                liveRefs.put(name, newValue);
                notifyWatchers(
                        new RegistryEvent(
                                expected.isEmpty()
                                        ? RegistryEvent.Type.REGISTERED
                                        : RegistryEvent.Type.TRANSFERRED,
                                name,
                                Optional.of(newValue)));
                return true;
            } catch (RocksDBException e) {
                return false;
            }
        }
    }

    @Override
    public void watch(Consumer<RegistryEvent> listener) {
        watchers.add(listener);
    }

    @Override
    public void cleanupNode(String nodeName) {
        ensureStarted();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, GlobalProcRef> entry : liveRefs.entrySet()) {
            if (entry.getValue().nodeName().equals(nodeName)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String name : toRemove) {
            try (WriteBatch batch = new WriteBatch()) {
                byte[] key = name.getBytes(StandardCharsets.UTF_8);
                byte[] ackKey = ackKey(name);

                batch.delete(registryHandle, key);
                batch.delete(ackHandle, ackKey);

                db.write(new WriteOptions().setSync(true), batch);

                liveRefs.remove(name);
                notifyWatchers(
                        new RegistryEvent(RegistryEvent.Type.UNREGISTERED, name, Optional.empty()));
            } catch (RocksDBException e) {
                // Continue cleaning up other entries
            }
        }
    }

    /**
     * Get the next sequence number for idempotent writes.
     *
     * @return a monotonically increasing sequence number
     */
    public long nextSequenceNumber() {
        return globalSequenceCounter.incrementAndGet();
    }

    private byte[] ackKey(String name) {
        return ("ack:" + name).getBytes(StandardCharsets.UTF_8);
    }

    private void ensureStarted() {
        if (db == null) {
            throw new IllegalStateException("RocksDB backend not started. Call start() first.");
        }
    }

    private byte[] serialize(GlobalProcRef ref) {
        // Simple JSON serialization
        String json =
                String.format(
                        "{\"name\":\"%s\",\"nodeName\":\"%s\",\"sequenceNumber\":%d,\"registeredAt\":\"%s\"}",
                        escapeJson(ref.name()),
                        escapeJson(ref.nodeName()),
                        ref.sequenceNumber(),
                        ref.registeredAt().toString());
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private GlobalProcRef deserialize(String name, String json) {
        // Simple JSON parsing (no external dependencies)
        String nodeName = extractJsonString(json, "nodeName");
        long sequenceNumber = extractJsonLong(json, "sequenceNumber");
        String registeredAtStr = extractJsonString(json, "registeredAt");
        Instant registeredAt = Instant.parse(registeredAtStr);
        // localRef is null - needs rehydration
        return new GlobalProcRef(name, null, nodeName, sequenceNumber, registeredAt);
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) throw new PersistenceException("JSON key not found: " + key);
        start += pattern.length();

        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        if (start >= json.length() || json.charAt(start) != '"') {
            throw new PersistenceException("Invalid JSON format for key: " + key);
        }
        start++; // Skip opening quote

        int end = json.indexOf("\"", start);
        if (end < 0) throw new PersistenceException("Unclosed JSON string for key: " + key);
        return unescapeJson(json.substring(start, end));
    }

    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();

        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void notifyWatchers(RegistryEvent event) {
        for (Consumer<RegistryEvent> watcher : watchers) {
            try {
                watcher.accept(event);
            } catch (Exception e) {
                Thread.currentThread()
                        .getThreadGroup()
                        .uncaughtException(
                                Thread.currentThread(),
                                new RuntimeException("Registry watcher threw exception", e));
            }
        }
    }
}
