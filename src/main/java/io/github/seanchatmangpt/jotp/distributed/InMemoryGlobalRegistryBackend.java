package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * In-memory backend for the global process registry with idempotent atomic writes.
 *
 * <p>Simple {@link ConcurrentHashMap}-based implementation suitable for:
 *
 * <ul>
 *   <li>Single-node deployments (no distribution needed)
 *   <li>Testing and development
 *   <li>Temporary registrations that don't need to survive restarts
 * </ul>
 *
 * <p><strong>Idempotence Pattern:</strong> All writes store both the registry entry and an ACK
 * (sequence number) atomically. On read, the system verifies consistency to detect partial writes
 * caused by crashes.
 *
 * <p><strong>Thread safety:</strong> All operations are thread-safe. Compare-and-swap uses {@link
 * AtomicReference} for atomicity.
 *
 * <p><strong>Persistence:</strong> None. All registrations are lost when the JVM exits. For
 * persistent storage, use {@link RocksDBGlobalRegistryBackend}.
 *
 * @see GlobalRegistryBackend
 * @see RocksDBGlobalRegistryBackend
 */
public final class InMemoryGlobalRegistryBackend implements GlobalRegistryBackend {

    /** Per-entry wrapper for CAS operations with ACK tracking. */
    private static final class Entry {
        volatile GlobalProcRef ref;
        volatile long ackSequence;

        Entry(GlobalProcRef ref) {
            this.ref = ref;
            this.ackSequence = ref.sequenceNumber();
        }

        synchronized void set(GlobalProcRef newRef) {
            this.ref = newRef;
            this.ackSequence = newRef.sequenceNumber();
        }
    }

    private final ConcurrentHashMap<String, Entry> registry = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> watchers =
            new CopyOnWriteArrayList<>();
    private final AtomicLong globalSequenceCounter = new AtomicLong(0);

    /** Create a new in-memory backend. */
    public InMemoryGlobalRegistryBackend() {}

    @Override
    public Result<Void, RegistryError> store(String name, GlobalProcRef ref) {
        return storeAtomic(name, ref);
    }

    @Override
    public Result<Void, RegistryError> storeAtomic(String name, GlobalProcRef ref) {
        Entry entry = new Entry(ref);
        Entry previous = registry.put(name, entry);
        if (previous == null) {
            notifyWatchers(
                    new RegistryEvent(RegistryEvent.Type.REGISTERED, name, Optional.of(ref)));
        } else {
            // Overwriting existing registration - treat as transfer if node changed
            if (!previous.ref.nodeName().equals(ref.nodeName())) {
                notifyWatchers(
                        new RegistryEvent(RegistryEvent.Type.TRANSFERRED, name, Optional.of(ref)));
            }
        }
        return Result.ok(null);
    }

    @Override
    public Optional<GlobalProcRef> lookup(String name) {
        return verifyAndRecover(name);
    }

    @Override
    public Optional<GlobalProcRef> verifyAndRecover(String name) {
        Entry entry = registry.get(name);
        if (entry == null) {
            return Optional.empty();
        }

        synchronized (entry) {
            GlobalProcRef ref = entry.ref;
            long ackSeq = entry.ackSequence;

            // Verify consistency (handle crash during write simulation)
            if (ref.sequenceNumber() != ackSeq) {
                // Mismatch: use lower value (before crash) for idempotent recovery
                long recoveredSeq = Math.min(ref.sequenceNumber(), ackSeq);
                // In-memory doesn't actually crash, so this is just for API consistency
                return Optional.of(ref.withSequenceNumber(recoveredSeq));
            }

            return Optional.of(ref);
        }
    }

    @Override
    public Result<Void, RegistryError> remove(String name) {
        return removeAtomic(name);
    }

    @Override
    public Result<Void, RegistryError> removeAtomic(String name) {
        Entry removed = registry.remove(name);
        if (removed != null) {
            notifyWatchers(
                    new RegistryEvent(RegistryEvent.Type.UNREGISTERED, name, Optional.empty()));
        }
        return Result.ok(null);
    }

    @Override
    public Map<String, GlobalProcRef> listAll() {
        return registry.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().ref));
    }

    @Override
    public boolean compareAndSwap(
            String name, Optional<GlobalProcRef> expected, GlobalProcRef newValue) {
        if (expected.isEmpty()) {
            // Expecting absent - try to insert
            Entry newEntry = new Entry(newValue);
            Entry existing = registry.putIfAbsent(name, newEntry);
            if (existing == null) {
                notifyWatchers(
                        new RegistryEvent(
                                RegistryEvent.Type.REGISTERED, name, Optional.of(newValue)));
                return true;
            }
            return false;
        }

        // Expecting specific value - find and compare
        Entry entry = registry.get(name);
        if (entry == null) {
            return false;
        }

        synchronized (entry) {
            if (entry.ref.equals(expected.get())) {
                entry.set(newValue);
                if (!expected.get().nodeName().equals(newValue.nodeName())) {
                    notifyWatchers(
                            new RegistryEvent(
                                    RegistryEvent.Type.TRANSFERRED, name, Optional.of(newValue)));
                }
                return true;
            }
            return false;
        }
    }

    @Override
    public void watch(Consumer<RegistryEvent> listener) {
        watchers.add(listener);
    }

    @Override
    public void cleanupNode(String nodeName) {
        registry.entrySet()
                .removeIf(
                        entry -> {
                            boolean shouldRemove = entry.getValue().ref.nodeName().equals(nodeName);
                            if (shouldRemove) {
                                notifyWatchers(
                                        new RegistryEvent(
                                                RegistryEvent.Type.UNREGISTERED,
                                                entry.getKey(),
                                                Optional.empty()));
                            }
                            return shouldRemove;
                        });
    }

    /**
     * Get the next sequence number for idempotent writes.
     *
     * @return a monotonically increasing sequence number
     */
    public long nextSequenceNumber() {
        return globalSequenceCounter.incrementAndGet();
    }

    private void notifyWatchers(RegistryEvent event) {
        for (Consumer<RegistryEvent> watcher : watchers) {
            try {
                watcher.accept(event);
            } catch (Exception e) {
                // Swallow exceptions from watchers to prevent one bad listener from affecting
                // others
                Thread.currentThread()
                        .getThreadGroup()
                        .uncaughtException(
                                Thread.currentThread(),
                                new RuntimeException("Registry watcher threw exception", e));
            }
        }
    }

    /** Clear all registrations. For testing only. */
    public void reset() {
        registry.clear();
        globalSequenceCounter.set(0);
    }
}
