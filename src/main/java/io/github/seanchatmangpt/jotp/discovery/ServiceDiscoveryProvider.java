package io.github.seanchatmangpt.jotp.discovery;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * SPI for distributed service discovery.
 *
 * <p>Analogous to Erlang's epmd (Erlang Port Mapper Daemon), this sealed interface defines the
 * contract for registering processes across a cluster and discovering them by name.
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Registering this node's processes with a backing store (Consul, etcd, Kubernetes, or
 *       static config)
 *   <li>Looking up processes by name
 *   <li>Monitoring cluster membership changes and notifying watchers
 *   <li>Handling network failures gracefully with CompletableFuture.exceptionally
 * </ul>
 *
 * <p>All operations are async (return CompletableFuture) to support non-blocking discovery in
 * virtual thread environments.
 */
public sealed interface ServiceDiscoveryProvider
    permits StaticNodeProvider, ConsulServiceDiscovery, EtcdServiceDiscovery,
        KubernetesServiceDiscovery {

  /**
   * Register a process instance with the discovery backend.
   *
   * @param nodeId the node hosting this process
   * @param instance metadata about the service instance (name, gRPC port, etc.)
   * @return CompletableFuture that completes when registration is acknowledged
   */
  CompletableFuture<Void> register(NodeId nodeId, ServiceInstance instance);

  /**
   * Deregister a process instance from the discovery backend.
   *
   * @param nodeId the node to deregister
   * @return CompletableFuture that completes when deregistration is acknowledged
   */
  CompletableFuture<Void> deregister(NodeId nodeId);

  /**
   * Look up a single process by name.
   *
   * @param processName the process name (e.g., "user_service")
   * @return Optional containing the NodeId if found, empty if not found
   */
  Optional<NodeId> lookup(String processName);

  /**
   * List all currently registered nodes in the cluster.
   *
   * @return Set of all known NodeIds
   */
  Set<NodeId> listNodes();

  /**
   * Watch for cluster membership changes.
   *
   * <p>The provided callback is invoked whenever the set of registered nodes changes. Invocation
   * happens on a background thread; ensure the callback is thread-safe.
   *
   * @param onMembership callback that receives the updated set of NodeIds
   */
  void watch(Consumer<Set<NodeId>> onMembership);

  /**
   * Check if the discovery backend is healthy and accessible.
   *
   * @return true if the backend is responding, false if unreachable
   */
  boolean isHealthy();

  /**
   * Gracefully shut down this provider and release any resources.
   *
   * @return CompletableFuture that completes when shutdown is finished
   */
  CompletableFuture<Void> shutdown();
}
