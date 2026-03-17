package io.github.seanchatmangpt.jotp.discovery;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Simple static node discovery provider for fallback / testing.
 *
 * <p>Maintains an in-memory list of known nodes. No external dependencies. Useful as a fallback
 * when Consul/etcd/Kubernetes are unavailable, or for single-node deployments.
 *
 * <p>Thread-safe: uses CopyOnWriteArrayList for watches and immutable Set snapshots.
 */
public final class StaticNodeProvider implements ServiceDiscoveryProvider {

  private final List<NodeId> nodes;
  private final Map<String, ServiceInstance> instances = new HashMap<>();
  private final List<Consumer<Set<NodeId>>> watchers = new CopyOnWriteArrayList<>();
  private volatile boolean healthy = true;

  /**
   * Create a StaticNodeProvider with a fixed list of nodes.
   *
   * @param nodes initial list of known nodes
   */
  public StaticNodeProvider(List<NodeId> nodes) {
    this.nodes = new CopyOnWriteArrayList<>(nodes);
  }

  @Override
  public CompletableFuture<Void> register(NodeId nodeId, ServiceInstance instance) {
    synchronized (instances) {
      instances.put(instance.processName(), instance);
    }
    notifyWatchers();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> deregister(NodeId nodeId) {
    synchronized (instances) {
      instances.values().removeIf(i -> i.nodeId().equals(nodeId));
    }
    notifyWatchers();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public Optional<NodeId> lookup(String processName) {
    synchronized (instances) {
      ServiceInstance instance = instances.get(processName);
      return instance != null ? Optional.of(instance.nodeId()) : Optional.empty();
    }
  }

  @Override
  public Set<NodeId> listNodes() {
    return Set.copyOf(nodes);
  }

  @Override
  public void watch(Consumer<Set<NodeId>> onMembership) {
    watchers.add(onMembership);
    // Immediate notification of current state
    onMembership.accept(listNodes());
  }

  @Override
  public boolean isHealthy() {
    return healthy;
  }

  @Override
  public CompletableFuture<Void> shutdown() {
    healthy = false;
    watchers.clear();
    synchronized (instances) {
      instances.clear();
    }
    return CompletableFuture.completedFuture(null);
  }

  /** Notify all registered watchers of membership changes. */
  private void notifyWatchers() {
    Set<NodeId> current = listNodes();
    for (Consumer<Set<NodeId>> watcher : watchers) {
      try {
        watcher.accept(current);
      } catch (Exception e) {
        // Watcher crashed — log but don't propagate (similar to EventManager pattern)
        System.err.println("StaticNodeProvider watcher failed: " + e.getMessage());
      }
    }
  }

  /**
   * Add a node to the static list at runtime (useful for testing).
   *
   * @param nodeId the node to add
   */
  public void addNode(NodeId nodeId) {
    if (!nodes.contains(nodeId)) {
      nodes.add(nodeId);
      notifyWatchers();
    }
  }

  /**
   * Remove a node from the static list at runtime (useful for testing).
   *
   * @param nodeId the node to remove
   */
  public void removeNode(NodeId nodeId) {
    if (nodes.remove(nodeId)) {
      notifyWatchers();
    }
  }
}
