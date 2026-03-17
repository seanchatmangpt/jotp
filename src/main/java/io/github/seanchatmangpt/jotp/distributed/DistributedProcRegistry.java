package io.github.seanchatmangpt.jotp.distributed;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Distributed process registry with location-transparent messaging.
 *
 * <p>Provides cluster-wide process registration and lookup, similar to Erlang's {@code global}
 * module. Processes can be registered by name and discovered from any node in the cluster.
 *
 * <p><strong>Key features:</strong>
 *
 * <ul>
 *   <li>Location-transparent lookup - find processes without knowing which node hosts them
 *   <li>Automatic failover - processes can migrate to other nodes
 *   <li>Metadata support - store additional information with each process
 *   <li>Cluster coordination - multiple nodes can share the same registry backend
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Create a distributed registry on each node
 * var backend = new InMemoryGlobalRegistryBackend();
 * var registry = DistributedProcRegistry.create(backend);
 *
 * // Register a process
 * var proc = Proc.spawn("initial", (s, m) -> s);
 * var procRef = new ProcRef<>(proc);
 * var info = registry.register("my-service", NodeId.of("node-1"), Map.of("type", "service"));
 *
 * // Lookup from any node
 * Optional<ProcessInfo> found = registry.lookup("my-service");
 * }</pre>
 *
 * @see GlobalRegistry
 * @see GlobalProcRegistry
 */
public interface DistributedProcRegistry extends AutoCloseable {

    /**
     * Register a process with metadata.
     *
     * @param name unique name for the process
     * @param nodeId node hosting the process
     * @param metadata additional metadata about the process
     * @return process info with metadata
     */
    ProcessInfo register(String name, NodeId nodeId, Map<String, String> metadata);

    /**
     * Look up a process by name.
     *
     * @param name the process name
     * @return the process info if found
     */
    Optional<ProcessInfo> lookup(String name);

    /**
     * Unregister a process.
     *
     * @param name the process name
     */
    void unregister(String name);

    /**
     * List all registered processes.
     *
     * @return map of process name to process info
     */
    Map<String, ProcessInfo> list();

    /**
     * Create a new DistributedProcRegistry.
     *
     * @param backend the shared backend storage
     * @return new distributed registry instance
     */
    static DistributedProcRegistry create(GlobalRegistryBackend backend) {
        return new DefaultDistributedProcRegistry(backend);
    }

    /**
     * Create a new DistributedProcRegistry with a GlobalRegistry delegate.
     *
     * @param globalRegistry the global registry to delegate to
     * @return new distributed registry instance
     */
    static DistributedProcRegistry create(GlobalRegistry globalRegistry) {
        return new DelegatingDistributedProcRegistry(globalRegistry);
    }

    @Override
    void close();
}

/** Default implementation of DistributedProcRegistry. */
final class DefaultDistributedProcRegistry implements DistributedProcRegistry {

    private final GlobalRegistryBackend backend;
    private final Map<String, Map<String, String>> metadataStore;
    private volatile boolean closed = false;

    DefaultDistributedProcRegistry(GlobalRegistryBackend backend) {
        this.backend = backend;
        this.metadataStore = new ConcurrentHashMap<>();
    }

    @Override
    public ProcessInfo register(String name, NodeId nodeId, Map<String, String> metadata) {
        ensureOpen();
        // Create a GlobalProcRef without a ProcRef (for distributed coordination)
        var globalRef = new GlobalProcRef(name, null, nodeId.name(), 0, java.time.Instant.now());
        backend.storeAtomic(name, globalRef);
        metadataStore.put(name, Map.copyOf(metadata));
        return ProcessInfo.withoutRef(name, nodeId, metadata);
    }

    @Override
    public Optional<ProcessInfo> lookup(String name) {
        ensureOpen();
        Optional<GlobalProcRef> globalRef = backend.verifyAndRecover(name);
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
        backend.removeAtomic(name);
        metadataStore.remove(name);
    }

    @Override
    public Map<String, ProcessInfo> list() {
        ensureOpen();
        return backend.listAll().entrySet().stream()
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
}

/** Delegating implementation that wraps a GlobalRegistry. */
final class DelegatingDistributedProcRegistry implements DistributedProcRegistry {

    private final GlobalRegistry delegate;
    private volatile boolean closed = false;

    DelegatingDistributedProcRegistry(GlobalRegistry delegate) {
        this.delegate = delegate;
    }

    @Override
    public ProcessInfo register(String name, NodeId nodeId, Map<String, String> metadata) {
        ensureOpen();
        return delegate.register(name, nodeId, metadata);
    }

    @Override
    public Optional<ProcessInfo> lookup(String name) {
        ensureOpen();
        return delegate.lookup(name);
    }

    @Override
    public void unregister(String name) {
        ensureOpen();
        delegate.unregister(name);
    }

    @Override
    public Map<String, ProcessInfo> list() {
        ensureOpen();
        return delegate.list();
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Registry is closed");
        }
    }
}
