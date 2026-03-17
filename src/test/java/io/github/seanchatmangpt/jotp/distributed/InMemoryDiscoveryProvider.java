package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.discovery.ServiceDiscoveryProvider;
import io.github.seanchatmangpt.jotp.discovery.ServiceInstance;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class InMemoryDiscoveryProvider implements ServiceDiscoveryProvider {

  private final Set<NodeId> healthyNodes = new HashSet<>();
  private final java.util.List<Consumer<Set<NodeId>>> watchers = new CopyOnWriteArrayList<>();

  synchronized void addHealthyNode(NodeId nodeId) {
    if (healthyNodes.add(nodeId)) {
      notifyWatchers();
    }
  }

  synchronized void removeNode(NodeId nodeId) {
    if (healthyNodes.remove(nodeId)) {
      notifyWatchers();
    }
  }

  synchronized void clear() {
    healthyNodes.clear();
  }

  @Override
  public CompletableFuture<Void> register(NodeId nodeId, ServiceInstance instance) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> deregister(NodeId nodeId) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public Optional<NodeId> lookup(String processName) {
    return Optional.empty();
  }

  @Override
  public Set<NodeId> listNodes() {
    synchronized (this) {
      return new HashSet<>(healthyNodes);
    }
  }

  @Override
  public void watch(Consumer<Set<NodeId>> onMembership) {
    watchers.add(onMembership);
  }

  @Override
  public boolean isHealthy() {
    return true;
  }

  @Override
  public CompletableFuture<Void> shutdown() {
    return CompletableFuture.completedFuture(null);
  }

  private void notifyWatchers() {
    var snapshot = new HashSet<>(healthyNodes);
    Thread.ofVirtual().start(() -> {
      for (var watcher : watchers) {
        try {
          watcher.accept(snapshot);
        } catch (Exception ignored) {}
      }
    });
  }
}
