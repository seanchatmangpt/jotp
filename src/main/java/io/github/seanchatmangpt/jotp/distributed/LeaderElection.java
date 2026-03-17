package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.discovery.ServiceDiscoveryProvider;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Deterministic leader election using the service discovery provider's node list.
 */
public final class LeaderElection {

  private final ServiceDiscoveryProvider provider;
  private final NodeFailureDetector failureDetector;
  private final Duration electionInterval;
  private final AtomicReference<Optional<NodeId>> currentLeader = new AtomicReference<>(Optional.empty());
  private volatile Consumer<Optional<NodeId>> leaderChangeCallback = leader -> {};

  public LeaderElection(
      ServiceDiscoveryProvider provider,
      NodeFailureDetector failureDetector,
      Duration electionInterval) {
    this.provider = provider;
    this.failureDetector = failureDetector;
    this.electionInterval = electionInterval;
  }

  public static LeaderElection withDefaults(
      ServiceDiscoveryProvider provider, NodeFailureDetector failureDetector) {
    return new LeaderElection(provider, failureDetector, Duration.ofSeconds(30));
  }

  public void onLeaderChange(Consumer<Optional<NodeId>> callback) {
    this.leaderChangeCallback = callback;
  }

  public void elect() {
    var candidates = provider.listNodes();
    var healthy = candidates.stream()
        .filter(failureDetector::isHealthy)
        .sorted(nodeComparator())
        .toList();

    Optional<NodeId> newLeader = healthy.isEmpty() ? Optional.empty() : Optional.of(healthy.get(0));
    Optional<NodeId> oldLeader = currentLeader.getAndSet(newLeader);
    if (!oldLeader.equals(newLeader)) {
      Thread.ofVirtual().start(() -> leaderChangeCallback.accept(newLeader));
    }
  }

  public Optional<NodeId> currentLeader() {
    return currentLeader.get();
  }

  public Duration electionInterval() {
    return electionInterval;
  }

  private static Comparator<NodeId> nodeComparator() {
    return Comparator.comparing(NodeId::name)
        .thenComparing(NodeId::host)
        .thenComparingInt(NodeId::port);
  }
}
