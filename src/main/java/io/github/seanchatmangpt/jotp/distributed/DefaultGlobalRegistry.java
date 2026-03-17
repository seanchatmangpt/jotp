package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link GlobalRegistry}.
 *
 * <p>Delegates to {@link GlobalProcRegistry} while providing a simpler API with metadata support.
 */
public final class DefaultGlobalRegistry implements GlobalRegistry {

    private final GlobalProcRegistry delegate;
    private final NodeId nodeId;
    private final Map<String, Map<String, String>> metadataStore;
    private volatile boolean closed = false;

    /**
     * Create a new GlobalRegistry.
     *
     * @param backend the backend storage
     * @param nodeId the node identifier
     */
    public DefaultGlobalRegistry(GlobalRegistryBackend backend, NodeId nodeId) {
        // Create a custom registry that uses the provided backend
        this.delegate = new DefaultGlobalProcRegistry(backend, nodeId);
        this.nodeId = nodeId;
        this.metadataStore = new ConcurrentHashMap<>();
    }

    @Override
    public ProcessInfo register(String name, NodeId nodeId, ProcRef<?, ?> ref) {
        ensureOpen();
        delegate.registerGlobal(name, ref, nodeId.name());
        Map<String, String> metadata = Map.of("registeredBy", nodeId.name());
        metadataStore.put(name, metadata);
        return ProcessInfo.withRef(name, nodeId, ref, metadata);
    }

    @Override
    public ProcessInfo register(String name, NodeId nodeId, Map<String, String> metadata) {
        ensureOpen();
        // Register without a ProcRef (for distributed coordination)
        var globalRef = new GlobalProcRef(name, null, nodeId.name(), 0, java.time.Instant.now());
        // Store metadata for later retrieval
        metadataStore.put(name, metadata);
        return ProcessInfo.withoutRef(name, nodeId, metadata);
    }

    @Override
    public Optional<ProcessInfo> lookup(String name) {
        ensureOpen();
        Optional<GlobalProcRef> globalRef = delegate.findGlobal(name);
        if (globalRef.isEmpty()) {
            return Optional.empty();
        }

        GlobalProcRef ref = globalRef.get();
        Map<String, String> metadata = metadataStore.getOrDefault(name, Map.of());
        NodeId foundNodeId = NodeId.of(ref.nodeName());

        return Optional.of(ProcessInfo.withoutRef(name, foundNodeId, metadata));
    }

    @Override
    public void unregister(String name) {
        ensureOpen();
        delegate.unregisterGlobal(name);
        metadataStore.remove(name);
    }

    @Override
    public Map<String, ProcessInfo> list() {
        ensureOpen();
        return delegate.listGlobal().entrySet().stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> {
                                    GlobalProcRef ref = e.getValue();
                                    Map<String, String> metadata =
                                            metadataStore.getOrDefault(e.getKey(), Map.of());
                                    NodeId foundNodeId = NodeId.of(ref.nodeName());
                                    return ProcessInfo.withoutRef(
                                            e.getKey(), foundNodeId, metadata);
                                }));
    }

    @Override
    public void close() {
        closed = true;
        metadataStore.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Registry is closed");
        }
    }

    /** Custom DefaultGlobalProcRegistry that uses a pluggable backend. */
    private static class DefaultGlobalProcRegistry implements GlobalProcRegistry {
        private final GlobalRegistryBackend backend;
        private final String currentNodeName;

        DefaultGlobalProcRegistry(GlobalRegistryBackend backend, NodeId nodeId) {
            this.backend = backend;
            this.currentNodeName = nodeId.name();
        }

        @Override
        public void registerGlobal(String name, ProcRef<?, ?> ref, String nodeName) {
            GlobalProcRef globalRef =
                    new GlobalProcRef(name, ref, nodeName, 0, java.time.Instant.now());
            backend.storeAtomic(name, globalRef);
        }

        @Override
        public Optional<GlobalProcRef> findGlobal(String name) {
            return backend.verifyAndRecover(name);
        }

        @Override
        public void unregisterGlobal(String name) {
            backend.removeAtomic(name);
        }

        @Override
        public Map<String, GlobalProcRef> listGlobal() {
            return backend.listAll();
        }

        @Override
        public boolean registerGlobalIfAbsent(String name, ProcRef<?, ?> ref, String nodeName) {
            GlobalProcRef globalRef =
                    new GlobalProcRef(name, ref, nodeName, 0, java.time.Instant.now());
            return backend.compareAndSwap(name, Optional.empty(), globalRef);
        }

        @Override
        public void transferGlobal(String name, String toNode) {
            Optional<GlobalProcRef> current = backend.verifyAndRecover(name);
            if (current.isEmpty()) {
                throw new IllegalStateException("Name not registered: " + name);
            }
            GlobalProcRef newRef =
                    new GlobalProcRef(
                            name,
                            current.get().localRef(),
                            toNode,
                            current.get().sequenceNumber(),
                            current.get().registeredAt());
            if (!backend.compareAndSwap(name, current, newRef)) {
                throw new IllegalStateException("Transfer failed for: " + name);
            }
        }
    }
}
