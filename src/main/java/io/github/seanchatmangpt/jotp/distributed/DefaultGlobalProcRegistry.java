package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Default implementation of {@link GlobalProcRegistry} with idempotent atomic writes.
 *
 * <p>Provides cluster-wide process registration using a pluggable {@link GlobalRegistryBackend}.
 * Features:
 *
 * <ul>
 *   <li><strong>Idempotent writes:</strong> All writes use atomic batch (state + ACK) with sequence
 *       numbers
 *   <li><strong>Local caching:</strong> Caches lookups for performance
 *   <li><strong>Node monitoring:</strong> Listens for node-down events to clean up dead
 *       registrations
 *   <li><strong>Shutdown integration:</strong> Unregisters processes on JVM shutdown
 * </ul>
 *
 * <p><strong>Idempotence Pattern:</strong>
 *
 * <pre>{@code
 * // Every registration gets a monotonically increasing sequence number
 * long seqNum = sequenceCounter.incrementAndGet();
 * GlobalProcRef globalRef = new GlobalProcRef(name, ref, nodeName, seqNum, Instant.now());
 *
 * // Backend stores both entry and ACK atomically
 * backend.storeAtomic(name, globalRef);
 *
 * // On recovery, verify consistency
 * Optional<GlobalProcRef> recovered = backend.verifyAndRecover(name);
 * }</pre>
 *
 * <p><strong>Singleton pattern:</strong> Use {@link #getInstance()} to obtain the global instance.
 * The default backend is {@link InMemoryGlobalRegistryBackend}; configure a persistent backend via
 * {@link #setBackend(GlobalRegistryBackend)}.
 *
 * <p><strong>Thread safety:</strong> All operations are thread-safe. Registration and lookup can be
 * called from any thread.
 *
 * @see GlobalProcRegistry
 * @see GlobalRegistryBackend
 */
public final class DefaultGlobalProcRegistry implements GlobalProcRegistry {

    private static final DefaultGlobalProcRegistry INSTANCE = new DefaultGlobalProcRegistry();

    private volatile GlobalRegistryBackend backend = new InMemoryGlobalRegistryBackend();
    private final ConcurrentHashMap<String, GlobalProcRef> localCache = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> localWatchers =
            new CopyOnWriteArrayList<>();
    private volatile String currentNodeName = "default-node";
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private DefaultGlobalProcRegistry() {
        // Register backend watcher to update local cache
        backend.watch(
                event -> {
                    switch (event.type()) {
                        case REGISTERED, TRANSFERRED ->
                                event.ref().ifPresent(ref -> localCache.put(ref.name(), ref));
                        case UNREGISTERED -> localCache.remove(event.name());
                    }
                });

        // Register shutdown hook to cleanup on JVM exit
        Runtime.getRuntime()
                .addShutdownHook(
                        Thread.ofVirtual()
                                .name("global-registry-shutdown")
                                .unstarted(
                                        () -> {
                                            // Unregister all processes for this node
                                            backend.cleanupNode(currentNodeName);
                                        }));
    }

    /**
     * Get the singleton instance.
     *
     * @return the global registry instance
     */
    public static DefaultGlobalProcRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Set the backend storage implementation.
     *
     * <p>Must be called before any registrations. Switching backends after registrations exist will
     * lose those registrations.
     *
     * @param newBackend the backend to use
     */
    public void setBackend(GlobalRegistryBackend newBackend) {
        this.backend = newBackend;
        this.localCache.clear();
        // Re-register watcher for new backend
        newBackend.watch(
                event -> {
                    switch (event.type()) {
                        case REGISTERED, TRANSFERRED ->
                                event.ref().ifPresent(ref -> localCache.put(ref.name(), ref));
                        case UNREGISTERED -> localCache.remove(event.name());
                    }
                });
    }

    /**
     * Set the current node name.
     *
     * <p>Used for registration metadata and cleanup on shutdown. Should match the {@link
     * DistributedNode} name if using distributed features.
     *
     * @param nodeName the node name
     */
    public void setCurrentNodeName(String nodeName) {
        this.currentNodeName = nodeName;
    }

    /**
     * Get the current node name.
     *
     * @return the node name
     */
    public String getCurrentNodeName() {
        return currentNodeName;
    }

    /**
     * Get the next sequence number for idempotent writes.
     *
     * @return a monotonically increasing sequence number
     */
    public long nextSequenceNumber() {
        return sequenceCounter.incrementAndGet();
    }

    @Override
    public void registerGlobal(String name, ProcRef<?, ?> ref, String nodeName) {
        // Check cache first
        GlobalProcRef existing = localCache.get(name);
        if (existing != null) {
            throw new IllegalStateException("Name already registered: " + name);
        }

        // Generate sequence number for idempotent write
        long seqNum = nextSequenceNumber();
        GlobalProcRef globalRef = new GlobalProcRef(name, ref, nodeName, seqNum, Instant.now());

        // Try atomic register
        boolean success = backend.compareAndSwap(name, Optional.empty(), globalRef);
        if (!success) {
            throw new IllegalStateException("Name already registered: " + name);
        }
    }

    @Override
    public Optional<GlobalProcRef> findGlobal(String name) {
        // Check local cache first
        GlobalProcRef cached = localCache.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Fall back to backend with idempotent verification
        Optional<GlobalProcRef> result = backend.verifyAndRecover(name);
        result.ifPresent(r -> localCache.put(name, r));
        return result;
    }

    @Override
    public void unregisterGlobal(String name) {
        // Use atomic remove for crash safety
        backend.removeAtomic(name);
        localCache.remove(name);
    }

    @Override
    public Map<String, GlobalProcRef> listGlobal() {
        return backend.listAll();
    }

    @Override
    public boolean registerGlobalIfAbsent(String name, ProcRef<?, ?> ref, String nodeName) {
        // Generate sequence number for idempotent write
        long seqNum = nextSequenceNumber();
        GlobalProcRef globalRef = new GlobalProcRef(name, ref, nodeName, seqNum, Instant.now());
        boolean success = backend.compareAndSwap(name, Optional.empty(), globalRef);
        if (success) {
            localCache.put(name, globalRef);
        }
        return success;
    }

    @Override
    public void transferGlobal(String name, String toNode) {
        Optional<GlobalProcRef> current = backend.verifyAndRecover(name);
        if (current.isEmpty()) {
            throw new IllegalStateException("Name not registered: " + name);
        }

        GlobalProcRef currentRef = current.get();
        // Increment sequence number for the transfer
        long newSeqNum = nextSequenceNumber();
        GlobalProcRef newRef =
                new GlobalProcRef(
                        name, currentRef.localRef(), toNode, newSeqNum, currentRef.registeredAt());

        boolean success = backend.compareAndSwap(name, current, newRef);
        if (!success) {
            throw new IllegalStateException(
                    "Transfer failed - name was modified concurrently: " + name);
        }
        localCache.put(name, newRef);
    }

    /**
     * Register a local watcher for registry events.
     *
     * <p>Watchers are notified on the calling thread and should not block.
     *
     * @param listener callback for registry events
     */
    public void watch(Consumer<RegistryEvent> listener) {
        localWatchers.add(listener);
        backend.watch(listener);
    }

    /**
     * Clean up all registrations for a dead node.
     *
     * <p>Called when a node is confirmed to be down. All registrations owned by that node are
     * removed from both the backend and local cache.
     *
     * @param nodeName the name of the dead node
     */
    public void cleanupNode(String nodeName) {
        backend.cleanupNode(nodeName);
        localCache.entrySet().removeIf(entry -> entry.getValue().nodeName().equals(nodeName));
    }

    /**
     * Clear the local cache.
     *
     * <p>Useful for testing or when the backend may have changed externally.
     */
    public void invalidateCache() {
        localCache.clear();
    }

    /**
     * Reset the registry entirely.
     *
     * <p>For testing only. Clears all registrations from both backend and cache.
     */
    public void reset() {
        if (backend instanceof InMemoryGlobalRegistryBackend inMemory) {
            inMemory.reset();
        }
        localCache.clear();
        sequenceCounter.set(0);
    }

    /**
     * Get the current sequence counter value.
     *
     * <p>Useful for testing and debugging idempotent writes.
     *
     * @return the current sequence number
     */
    public long getCurrentSequenceNumber() {
        return sequenceCounter.get();
    }
}
