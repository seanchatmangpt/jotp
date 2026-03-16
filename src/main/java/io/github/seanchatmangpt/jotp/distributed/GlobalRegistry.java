package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.Map;
import java.util.Optional;

/**
 * Simple facade for the global process registry.
 *
 * <p>Provides a simplified API for common registry operations. Internally delegates to {@link
 * GlobalProcRegistry}. This is the preferred interface for most use cases.
 *
 * <p><strong>Key operations:</strong>
 *
 * <ul>
 *   <li>{@link #register(String, NodeId, ProcRef)} — register a process with metadata
 *   <li>{@link #lookup(String)} — find a process by name
 *   <li>{@link #unregister(String)} — remove a process
 *   <li>{@link #list()} — list all registered processes
 * </ul>
 *
 * @see GlobalProcRegistry
 * @see DistributedProcRegistry
 */
public interface GlobalRegistry extends AutoCloseable {

    /**
     * Register a process with the global registry.
     *
     * @param name unique name for the process
     * @param nodeId node hosting the process
     * @param ref reference to the process
     * @return process info with metadata
     */
    ProcessInfo register(String name, NodeId nodeId, ProcRef<?, ?> ref);

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
     * Create a new GlobalRegistry instance.
     *
     * @param backend the backend storage
     * @param nodeId the node identifier
     * @return new registry instance
     */
    static GlobalRegistry create(GlobalRegistryBackend backend, NodeId nodeId) {
        return new DefaultGlobalRegistry(backend, nodeId);
    }

    /** Close the registry and release resources. */
    @Override
    void close();
}
