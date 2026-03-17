package io.github.seanchatmangpt.jotp.cluster;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * In-memory leader election using bully algorithm.
 *
 * <p>For production use, prefer RedisLeaderElection or PostgresLeaderElection which support
 * distributed coordination.
 */
public final class InMemoryLeaderElection implements LeaderElection {
  private static final Logger logger = Logger.getLogger(InMemoryLeaderElection.class.getName());

  private final AtomicReference<Optional<String>> leader = new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<String>> leaseHolder = new AtomicReference<>(Optional.empty());
  private final AtomicBoolean electionInProgress = new AtomicBoolean(false);
  private final ConcurrentHashMap<String, LeaseInfo> leases = new ConcurrentHashMap<>();

  private record LeaseInfo(String holder, long expiresAt) {}

  public InMemoryLeaderElection() {}

  @Override
  public Optional<String> electLeader(
      String nodeName, Set<String> candidates, long electionTimeoutMs) {
    var latch = new CountDownLatch(1);
    var result = new AtomicReference<Optional<String>>(Optional.empty());

    electionInProgress.set(true);
    try {
      // Bully algorithm: find highest node ID
      var winner = candidates.stream().max(String::compareTo).orElse(null);

      if (winner != null) {
        leader.set(Optional.of(winner));
        result.set(Optional.of(winner));
      }

      return result.get();
    } finally {
      electionInProgress.set(false);
    }
  }

  @Override
  public boolean acquireLeaderLease(
      String nodeName, long leaseDurationMs, long acquireTimeoutMs) {
    var now = System.currentTimeMillis();
    var deadline = now + acquireTimeoutMs;

    while (System.currentTimeMillis() < deadline) {
      var current = leaseHolder.get();

      if (current.isEmpty() || isExpired(current.get())) {
        var lease = new LeaseInfo(nodeName, now + leaseDurationMs);
        if (leaseHolder.compareAndSet(current, Optional.of(nodeName))) {
          leases.put(nodeName, lease);
          leader.set(Optional.of(nodeName));
          return true;
        }
      } else if (current.get().equals(nodeName)) {
        // Already hold the lease
        return true;
      }

      // Retry after short delay
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    return false;
  }

  @Override
  public void releaseLeaderLease(String nodeName) {
    if (leaseHolder.get().filter(h -> h.equals(nodeName)).isPresent()) {
      leaseHolder.set(Optional.empty());
      leases.remove(nodeName);
      leader.set(Optional.empty());
    }
  }

  @Override
  public boolean renewLeaderLease(String nodeName, long newDurationMs) {
    var current = leaseHolder.get();
    if (current.isEmpty() || !current.get().equals(nodeName)) {
      return false;
    }

    var now = System.currentTimeMillis();
    var lease = new LeaseInfo(nodeName, now + newDurationMs);
    leases.put(nodeName, lease);
    return true;
  }

  @Override
  public boolean isLeader(String nodeName) {
    return leaseHolder.get().filter(h -> h.equals(nodeName)).isPresent()
        && !isExpired(nodeName);
  }

  @Override
  public Optional<String> getCurrentLeader() {
    var current = leaseHolder.get();
    if (current.isPresent() && !isExpired(current.get())) {
      return current;
    }
    return Optional.empty();
  }

  @Override
  public boolean isElectionInProgress() {
    return electionInProgress.get();
  }

  @Override
  public void close() {
    leaseHolder.set(Optional.empty());
    leader.set(Optional.empty());
    leases.clear();
  }

  // --- Private helpers ---

  private boolean isExpired(String nodeName) {
    var lease = leases.get(nodeName);
    if (lease == null) return true;
    return System.currentTimeMillis() > lease.expiresAt();
  }
}
