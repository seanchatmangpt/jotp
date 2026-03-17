package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SPI for distributed process-level service discovery.
 *
 * <p>Extends service discovery to resolve individual process references by name across a cluster.
 * Whereas {@code ServiceDiscoveryProvider} (in distributed package) tracks node membership,
 * {@code ProcessServiceDiscoveryProvider} tracks individual processes and their locations.
 *
 * <p>Analogous to Erlang's global name server: processes can be registered globally and discovered
 * by name from any node in the cluster.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #register(String, NodeId)} — register a process name to a node
 *   <li>{@link #deregister(String)} — remove a process from the registry
 *   <li>{@link #lookup(String)} — find the NodeId where a process lives
 *   <li>{@link #watch(Consumer)} — subscribe to process registration changes
 *   <li>{@link #isHealthy()} — check provider health
 * </ul>
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Backing storage (Consul, etcd, DNS, Kubernetes, or in-memory for testing)
 *   <li>TTL cache management (avoid repeated queries)
 *   <li>Handling network timeouts gracefully
 *   <li>Notifying watchers of registration changes
 * </ul>
 *
 * <p><strong>Design note:</strong> Processes are identified by name + NodeId. If a process crashes
 * and restarts on a different node, it's treated as a new process (different lookup result). This
 * is consistent with Erlang's global behavior where a crashed process loses its global name.
 *
 * @see ProcRef
 * @see GlobalProcRegistry
 */
public sealed interface ProcessServiceDiscoveryProvider
    permits InMemoryProcessDiscovery {

  /**
   * Register a process by name at a specific node.
   *
   * @param processName the process name (e.g., "user-service")
   * @param nodeId the node where the process is running
   */
  void register(String processName, NodeId nodeId);

  /**
   * Deregister a process by name (typically called when the process crashes).
   *
   * @param processName the process name
   */
  void deregister(String processName);

  /**
   * Look up the node where a process is running by name.
   *
   * @param processName the process name to look up
   * @return the NodeId where the process is running, or empty if not registered
   */
  Optional<NodeId> lookup(String processName);

  /**
   * List all currently registered process names.
   *
   * @return a snapshot of process names registered in the discovery provider
   */
  Set<String> listProcesses();

  /**
   * Watch for process registration changes.
   *
   * <p>The callback is invoked when a process is registered or deregistered. The set parameter
   * contains the current set of all registered process names (for reconciliation).
   *
   * <p>Invoked on a virtual thread; must be thread-safe.
   *
   * @param onProcessesChanged callback receiving updated set of process names
   */
  void watch(Consumer<Set<String>> onProcessesChanged);

  /**
   * Check if the discovery backend is healthy and accessible.
   *
   * @return true if the provider can service requests, false if unreachable
   */
  boolean isHealthy();

  /**
   * Gracefully shut down this provider and release any resources.
   */
  void shutdown();
}
