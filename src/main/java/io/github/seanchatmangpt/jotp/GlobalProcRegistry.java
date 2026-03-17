package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Global process registry for cluster-wide process discovery.
 *
 * <p>Extends {@link ProcRegistry} with distributed awareness: processes can be registered
 * globally and discovered across the cluster using a backing service discovery provider.
 *
 * <p>Joe Armstrong: "Let the processes be named, and let the network find them."
 *
 * <p>Contract:
 * <ul>
 *   <li>Local first: always check local {@link ProcRegistry} before querying provider
 *   <li>Graceful degradation: if provider fails, fall back to local-only (degraded mode)
 *   <li>TTL cache: avoid repeated queries with configurable cache duration
 *   <li>Watchers: subscribe to registration changes and reconcile cache
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Register a process globally
 * registry.register("payment-service", procRef, currentNodeId);
 *
 * // Look up a process locally first, then globally
 * Optional<ProcRef<?,?>> ref = registry.lookup("payment-service");
 *
 * // Watch for remote process registration changes
 * registry.watch(processNames -> System.out.println("Registered processes: " + processNames));
 *
 * // Deregister when process stops
 * registry.deregister("payment-service");
 * }</pre>
 *
 * @see ProcRegistry
 * @see ProcessServiceDiscoveryProvider
 */
public sealed interface GlobalProcRegistry permits DefaultGlobalProcRegistry {

  /**
   * Register a process globally.
   *
   * <p>First registers locally in {@link ProcRegistry}, then syncs to the discovery provider.
   * The process is automatically deregistered when it terminates (via ProcRegistry callback).
   *
   * @param processName the process name (must be globally unique)
   * @param procRef the process reference (sealed, pattern-matchable)
   * @param currentNodeId the node where this process is running
   * @throws IllegalStateException if the name is already registered locally
   */
  void register(String processName, ProcRef<?, ?> procRef, NodeId currentNodeId);

  /**
   * Deregister a process from the global registry.
   *
   * <p>Removes from both local registry and discovery provider.
   *
   * @param processName the process name to deregister
   */
  void deregister(String processName);

  /**
   * Look up a process globally.
   *
   * <p>Attempts resolution in this order:
   * <ol>
   *   <li>Local {@link ProcRegistry} (fast path, ~625ns)
   *   <li>Cache hit (if TTL not expired)
   *   <li>Query discovery provider (50-100μs)
   *   <li>Fall back to DistributedActorBridge if remote (delegated to caller)
   * </ol>
   *
   * @param processName the process name to find
   * @return Optional containing the ProcRef if found locally, empty if not found or remote
   */
  Optional<ProcRef<?, ?>> lookupLocal(String processName);

  /**
   * Look up a process globally, including remote locations.
   *
   * <p>Returns the NodeId where a process is running, whether local or remote. The caller
   * can use this to decide whether to call directly (local) or via DistributedActorBridge (remote).
   *
   * @param processName the process name to find
   * @return Optional containing the NodeId where the process is running
   */
  Optional<NodeId> lookupNodeId(String processName);

  /**
   * List all currently registered process names (local + remote).
   *
   * @return a snapshot of known process names
   */
  Set<String> listProcesses();

  /**
   * Watch for global process registration changes.
   *
   * <p>The callback is invoked whenever a process is registered or deregistered globally.
   * Can be used to maintain client-side route tables or update load balancers.
   *
   * <p>Invoked on a virtual thread; must be thread-safe.
   *
   * @param onProcessesChanged callback receiving updated set of process names
   */
  void watch(Consumer<Set<String>> onProcessesChanged);

  /**
   * Clear all global registrations — for testing only.
   *
   * <p>Clears both local and remote state via the discovery provider.
   */
  void reset();
}
