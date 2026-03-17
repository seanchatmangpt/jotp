package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory process discovery provider for testing and single-node scenarios.
 *
 * <p>Suitable for development and testing; in production use Consul, etcd, or Kubernetes.
 *
 * <p>Thread-safe via ConcurrentHashMap. All operations are O(1).
 */
public final class InMemoryProcessDiscovery implements ProcessServiceDiscoveryProvider {

  private final ConcurrentHashMap<String, NodeId> processRegistry = new ConcurrentHashMap<>();
  private final List<Consumer<Set<String>>> watchers = new CopyOnWriteArrayList<>();

  @Override
  public void register(String processName, NodeId nodeId) {
    processRegistry.put(processName, nodeId);
    notifyWatchers();
  }

  @Override
  public void deregister(String processName) {
    processRegistry.remove(processName);
    notifyWatchers();
  }

  @Override
  public Optional<NodeId> lookup(String processName) {
    return Optional.ofNullable(processRegistry.get(processName));
  }

  @Override
  public Set<String> listProcesses() {
    return Set.copyOf(processRegistry.keySet());
  }

  @Override
  public void watch(Consumer<Set<String>> onProcessesChanged) {
    watchers.add(onProcessesChanged);
  }

  @Override
  public boolean isHealthy() {
    return true; // In-memory provider is always healthy
  }

  @Override
  public void shutdown() {
    processRegistry.clear();
    watchers.clear();
  }

  private void notifyWatchers() {
    Set<String> current = listProcesses();
    for (Consumer<Set<String>> watcher : watchers) {
      try {
        watcher.accept(current);
      } catch (Exception e) {
        // Log but don't fail other watchers
        System.err.println("Watcher failed: " + e.getMessage());
      }
    }
  }

  /** Clear all registrations — for testing only. */
  public void reset() {
    processRegistry.clear();
    watchers.clear();
  }
}
