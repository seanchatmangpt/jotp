package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Backend storage interface for the global process registry.
 *
 * <p>Implementations provide different persistence and distribution strategies:
 *
 * <ul>
 *   <li>{@link InMemoryGlobalRegistryBackend} — ConcurrentHashMap, single-node, no persistence
 *   <li>{@link RocksDBGlobalRegistryBackend} — persistent local storage, survives restarts
 *   <li>Consul — distributed coordination across datacenters
 *   <li>Etcd — Kubernetes-native distributed key-value store
 *   <li>Kubernetes — native service discovery via API server
 * </ul>
 *
 * <p><strong>Consistency model:</strong> All implementations must support atomic compare-and-swap
 * via {@link #compareAndSwap(String, Optional, GlobalProcRef)}. This enables race-free registration
 * even in distributed environments.
 *
 * <p><strong>Node failure handling:</strong> When a node goes down, {@link #cleanupNode(String)} is
 * called to remove all registrations for that node. Implementations may also use {@link
 * #watch(Consumer)} to receive notifications about registration changes.
 *
 * @see GlobalProcRegistry
 * @see InMemoryGlobalRegistryBackend
 */
public interface GlobalRegistryBackend {

    /**
     * Store a registration.
     *
     * <p>If a registration already exists for the given name, it is overwritten.
     *
     * @param name the global name
     * @param ref the reference to store
     * @return {@code Ok} on success, or an error
     */
    Result<Void, RegistryError> store(String name, GlobalProcRef ref);

    /**
     * Lookup a registration by name.
     *
     * @param name the global name to lookup
     * @return the reference if found, empty otherwise
     */
    Optional<GlobalProcRef> lookup(String name);

    /**
     * Remove a registration.
     *
     * <p>Safe to call even if the name is not registered.
     *
     * @param name the global name to remove
     * @return {@code Ok} on success, or an error
     */
    Result<Void, RegistryError> remove(String name);

    /**
     * List all registrations.
     *
     * <p>Returns a snapshot of all current registrations. The map is not live — changes after this
     * call returns will not be reflected.
     *
     * @return map of name to global reference
     */
    Map<String, GlobalProcRef> listAll();

    /**
     * Atomic compare-and-swap registration.
     *
     * <p>Atomically sets the registration to {@code newValue} if and only if the current value
     * equals {@code expected}. This enables race-free conditional registration.
     *
     * <p><strong>Usage:</strong>
     *
     * <pre>{@code
     * // Register only if absent
     * boolean success = backend.compareAndSwap(name, Optional.empty(), newRef);
     *
     * // Replace only if currently registered to specific node
     * Optional<GlobalProcRef> current = backend.lookup(name);
     * if (current.isPresent() && current.get().nodeName().equals(oldNode)) {
     *     backend.compareAndSwap(name, current, newRef);
     * }
     * }</pre>
     *
     * @param name the global name
     * @param expected the expected current value (empty means "not registered")
     * @param newValue the new value to set
     * @return {@code true} if the swap succeeded, {@code false} if the expected value did not match
     */
    boolean compareAndSwap(String name, Optional<GlobalProcRef> expected, GlobalProcRef newValue);

    /**
     * Watch for registration changes.
     *
     * <p>Registers a listener that will be notified when registrations are added, removed, or
     * transferred. This enables distributed synchronization — nodes can react to registration
     * changes in real-time.
     *
     * <p>Listeners are called on a background thread and should not block.
     *
     * @param listener callback for registry events
     */
    void watch(Consumer<RegistryEvent> listener);

    /**
     * Clean up all registrations for a dead node.
     *
     * <p>Called when a node is confirmed to be down (failed health checks, explicit shutdown,
     * etc.). All registrations owned by that node are removed.
     *
     * @param nodeName the name of the dead node
     */
    void cleanupNode(String nodeName);

    /**
     * Atomic store with ACK for crash-safe idempotent writes.
     *
     * <p>Stores both the registry entry and an ACK (sequence number) in a single atomic batch. On
     * recovery, the system verifies consistency between the entry and ACK to detect partial writes
     * caused by JVM crashes.
     *
     * <p><strong>Idempotent Recovery:</strong> If the stored sequence number doesn't match the ACK,
     * the system uses the lower value to recover idempotently.
     *
     * @param name the global name
     * @param ref the reference to store (must have valid sequenceNumber)
     * @return {@code Ok} on success, or an error
     */
    default Result<Void, RegistryError> storeAtomic(String name, GlobalProcRef ref) {
        // Default implementation delegates to store for backward compatibility
        return store(name, ref);
    }

    /**
     * Atomic remove with ACK for crash-safe idempotent writes.
     *
     * <p>Removes both the registry entry and the ACK in a single atomic batch. This ensures that on
     * recovery, the system doesn't see orphaned ACK entries.
     *
     * @param name the global name to remove
     * @return {@code Ok} on success, or an error
     */
    default Result<Void, RegistryError> removeAtomic(String name) {
        // Default implementation delegates to remove for backward compatibility
        return remove(name);
    }

    /**
     * Verify consistency of stored state and ACK for idempotent recovery.
     *
     * <p>Called during startup to detect partial writes from crashes. If the sequence number in the
     * registry entry doesn't match the ACK, the system can recover by:
     *
     * <ul>
     *   <li>Using the lower sequence number (safer, may reprocess)
     *   <li>Using the higher sequence number (faster, may skip)
     * </ul>
     *
     * @param name the global name to verify
     * @return the verified reference, or empty if not found or inconsistent
     */
    default Optional<GlobalProcRef> verifyAndRecover(String name) {
        // Default implementation delegates to lookup for backward compatibility
        return lookup(name);
    }
}
