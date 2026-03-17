package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.ProcRef;
import java.util.Map;
import java.util.Optional;

/**
 * Distributed process registry for cluster-wide process lookup.
 *
 * <p>Equivalent to Erlang's {@code global} module. Allows processes to be registered under unique
 * names visible across all nodes in a distributed cluster. This enables location-transparent
 * messaging — callers can find a process by name without knowing which node hosts it.
 *
 * <p><strong>Key operations:</strong>
 *
 * <ul>
 *   <li>{@link #registerGlobal(String, ProcRef, String)} — register a process globally (throws if
 *       name exists)
 *   <li>{@link #findGlobal(String)} — lookup a process by name from any node
 *   <li>{@link #unregisterGlobal(String)} — remove a global registration
 *   <li>{@link #registerGlobalIfAbsent(String, ProcRef, String)} — atomic register-if-absent
 * </ul>
 *
 * <p><strong>Integration with Supervision:</strong> When a supervised process crashes and restarts,
 * the {@link ProcRef} automatically points to the new instance. Global registrations survive
 * process restarts because they reference the stable {@code ProcRef}, not the underlying {@code
 * Proc}.
 *
 * <p><strong>Backend implementations:</strong> The registry delegates to a {@link
 * GlobalRegistryBackend} for storage. Available backends:
 *
 * <ul>
 *   <li>{@link InMemoryGlobalRegistryBackend} — single-node or testing (default)
 *   <li>{@link RocksDBGlobalRegistryBackend} — persistent local storage
 *   <li>Consul, Etcd, Kubernetes — external distributed coordination (future)
 * </ul>
 *
 * @see GlobalRegistryBackend
 * @see GlobalProcRef
 */
public interface GlobalProcRegistry {

    /**
     * Register a process globally across the cluster.
     *
     * <p>The process will be discoverable via {@link #findGlobal(String)} from any node in the
     * cluster. The registration persists until explicitly removed or the node goes down.
     *
     * @param name unique global name for the process
     * @param ref stable reference to the process (survives supervisor restarts)
     * @param nodeName name of the node hosting this process
     * @throws IllegalStateException if the name is already registered
     * @throws NullPointerException if any argument is null
     */
    void registerGlobal(String name, ProcRef<?, ?> ref, String nodeName);

    /**
     * Find a process anywhere in the cluster by its global name.
     *
     * <p>Returns the {@link GlobalProcRef} containing both the local {@link ProcRef} and metadata
     * about which node hosts the process. The caller can then use {@link GlobalProcRef#localRef()}
     * to send messages.
     *
     * @param name the global name to lookup
     * @return the global reference, or empty if not registered
     */
    Optional<GlobalProcRef> findGlobal(String name);

    /**
     * Remove a global registration.
     *
     * <p>Safe to call even if the name is not currently registered.
     *
     * @param name the global name to unregister
     */
    void unregisterGlobal(String name);

    /**
     * List all globally registered processes.
     *
     * <p>Returns a snapshot of all registrations at the time of the call. The map is not live —
     * changes after this call returns will not be reflected.
     *
     * @return map of name to global reference
     */
    Map<String, GlobalProcRef> listGlobal();

    /**
     * Atomically register a process if the name is not already taken.
     *
     * <p>This is a compare-and-swap operation: the registration succeeds only if no other process
     * has registered the name concurrently. Equivalent to Erlang's {@code global:register_name/2}
     * with atomic semantics.
     *
     * @param name unique global name for the process
     * @param ref stable reference to the process
     * @param nodeName name of the node hosting this process
     * @return {@code true} if registration succeeded, {@code false} if name already exists
     */
    boolean registerGlobalIfAbsent(String name, ProcRef<?, ?> ref, String nodeName);

    /**
     * Transfer a global registration to another node.
     *
     * <p>Used during failover to move ownership of a global name from a dead or departing node to a
     * live one. The new node assumes responsibility for the process.
     *
     * @param name the global name to transfer
     * @param toNode the destination node name
     * @throws IllegalStateException if the name is not currently registered
     */
    void transferGlobal(String name, String toNode);

    /**
     * Get the global registry instance for the current node.
     *
     * <p>Returns the default implementation using an in-memory backend. For production use with
     * persistence, configure via {@link
     * DefaultGlobalProcRegistry#setBackend(GlobalRegistryBackend)}.
     *
     * @return the singleton global registry instance
     */
    static GlobalProcRegistry getInstance() {
        return DefaultGlobalProcRegistry.getInstance();
    }
}
