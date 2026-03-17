package io.github.seanchatmangpt.jotp.coordination;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Centralized manager for distributed locks with monitoring, deadlock detection, and automatic
 * cleanup.
 *
 * <p>Joe Armstrong: "The supervisor pattern works because it decouples problem detection from
 * problem solving. The lock manager detects problems (deadlock, timeouts, crashes) and triggers
 * recovery actions (timeout release, waiter promotion, crash notification)."
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li><strong>Coordination:</strong> Track all locks across the cluster node
 *   <li><strong>Deadlock Detection:</strong> Detect and break cycles in lock dependencies
 *   <li><strong>Monitoring:</strong> Collect metrics on lock contention, wait times, acquisitions
 *   <li><strong>Cleanup:</strong> Automatically release expired locks, notify crash handlers
 *   <li><strong>Integration:</strong> Link locks to process lifecycle via supervisors
 * </ul>
 *
 * <p><strong>Deadlock detection algorithm:</strong>
 *
 * <pre>{@code
 * 1. Build wait-for graph: node A → node B if A waits for B's lock
 * 2. Run DFS from each waiting process
 * 3. If DFS finds a back edge (cycle), deadlock detected
 * 4. Break cycle: force-release one lock in the cycle
 * }</pre>
 *
 * <p><strong>Integration with Supervisor:</strong>
 *
 * <pre>{@code
 * var lockMgr = new LockManager("node-1", lock);
 * var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 *
 * var procRef = supervisor.supervise("worker", state, (s, m) -> {
 *     lockMgr.withLock("db-write", Duration.ofMinutes(1), () -> {
 *         return handler.process(s, m);
 *     });
 * });
 *
 * // On worker crash: lock automatically released via onProcessCrash
 * lockMgr.onProcessCrash("worker");
 * }</pre>
 */
public final class LockManager {

  private final String nodeId;
  private final DistributedLock lock;
  private final ConcurrentHashMap<String, ProcessLockState> processStates;
  private final ConcurrentHashMap<String, LockMetrics> metrics;
  private final ScheduledExecutorService executor;
  private final Clock clock;
  private volatile boolean shutdown;

  /**
   * Tracks lock state for a process.
   */
  private static final class ProcessLockState {
    final String processId;
    final ConcurrentHashMap<String, LockAcquisition> heldLocks = new ConcurrentHashMap<>();
    volatile long lastHeartbeat;

    ProcessLockState(String processId) {
      this.processId = processId;
      this.lastHeartbeat = System.currentTimeMillis();
    }
  }

  /**
   * Represents an active lock acquisition.
   */
  private static final class LockAcquisition {
    final String lockName;
    final long acquiredAt;
    final long expiresAt;
    final String lockHolderId;

    LockAcquisition(String lockName, long acquiredAt, long expiresAt, String lockHolderId) {
      this.lockName = lockName;
      this.acquiredAt = acquiredAt;
      this.expiresAt = expiresAt;
      this.lockHolderId = lockHolderId;
    }
  }

  /**
   * Metrics for a lock.
   */
  public static final class LockMetrics {
    public final String lockName;
    public volatile long totalAcquisitions;
    public volatile long totalTimeouts;
    public volatile long totalDeadlocks;
    public volatile long contentionCount;
    public volatile long avgWaitTimeMs;
    public volatile long maxWaitTimeMs;
    public volatile long minWaitTimeMs;

    public LockMetrics(String lockName) {
      this.lockName = lockName;
      this.minWaitTimeMs = Long.MAX_VALUE;
    }

    public void recordAcquisition(long waitTimeMs) {
      totalAcquisitions++;
      contentionCount++;
      avgWaitTimeMs = (avgWaitTimeMs * (totalAcquisitions - 1) + waitTimeMs) / totalAcquisitions;
      maxWaitTimeMs = Math.max(maxWaitTimeMs, waitTimeMs);
      minWaitTimeMs = Math.min(minWaitTimeMs, waitTimeMs);
    }

    public void recordTimeout() {
      totalTimeouts++;
    }

    public void recordDeadlock() {
      totalDeadlocks++;
    }
  }

  public LockManager(String nodeId, DistributedLock lock) {
    this.nodeId = nodeId;
    this.lock = lock;
    this.processStates = new ConcurrentHashMap<>();
    this.metrics = new ConcurrentHashMap<>();
    this.executor = Executors.newScheduledThreadPool(2, r -> {
      var t = new Thread(r, "LockMgr-" + nodeId);
      t.setDaemon(true);
      return t;
    });
    this.clock = Clock.systemUTC();
    this.shutdown = false;

    // Start deadlock detection task
    executor.scheduleAtFixedRate(this::detectAndBreakDeadlocks, 10, 10, TimeUnit.SECONDS);

    // Start process heartbeat monitor
    executor.scheduleAtFixedRate(this::checkProcessHeartbeats, 15, 15, TimeUnit.SECONDS);
  }

  /**
   * Acquire a lock for a process with automatic release on timeout or process crash.
   *
   * <p>This is the preferred way to acquire locks when integrating with supervisors.
   *
   * @param processId the ID of the process acquiring the lock
   * @param lockName the name of the lock
   * @param timeout how long to wait
   * @return the result of lock acquisition
   */
  public DistributedLock.AcquireResult acquireWithProcessTracking(
      String processId, String lockName, Duration timeout) {
    if (shutdown) {
      return new DistributedLock.AcquireResult.Failed("LockManager is shut down");
    }

    var processState = processStates.computeIfAbsent(processId, ProcessLockState::new);
    long startTime = System.currentTimeMillis();

    var result = lock.acquireLock(lockName, timeout);

    if (result instanceof DistributedLock.AcquireResult.Acquired) {
      long now = clock.instant().toEpochMilli();
      long expiresAt = now + timeout.toMillis();

      var acquisition = new LockAcquisition(lockName, now, expiresAt, processId);
      processState.heldLocks.put(lockName, acquisition);

      var metrics = this.metrics.computeIfAbsent(lockName, LockMetrics::new);
      metrics.recordAcquisition(System.currentTimeMillis() - startTime);
    } else if (result instanceof DistributedLock.AcquireResult.TimedOut timedOut) {
      var metrics = this.metrics.computeIfAbsent(lockName, LockMetrics::new);
      metrics.recordTimeout();
    } else if (result instanceof DistributedLock.AcquireResult.Deadlock) {
      var metrics = this.metrics.computeIfAbsent(lockName, LockMetrics::new);
      metrics.recordDeadlock();
    }

    processState.lastHeartbeat = System.currentTimeMillis();
    return result;
  }

  /**
   * Release a lock held by a process.
   *
   * @param processId the ID of the process
   * @param lockName the name of the lock
   */
  public void releaseWithProcessTracking(String processId, String lockName) {
    lock.releaseLock(lockName);

    var processState = processStates.get(processId);
    if (processState != null) {
      processState.heldLocks.remove(lockName);
    }
  }

  /**
   * Notify the manager that a process has crashed.
   *
   * <p>Automatically releases all locks held by this process.
   *
   * @param processId the ID of the crashed process
   */
  public void onProcessCrash(String processId) {
    var processState = processStates.remove(processId);
    if (processState == null) {
      return;
    }

    // Release all locks held by this process
    for (var acquisition : processState.heldLocks.values()) {
      lock.forceRelease(acquisition.lockName);
    }

    processState.heldLocks.clear();
  }

  /**
   * Update heartbeat for a process.
   *
   * <p>Called periodically to indicate that a process is still alive.
   *
   * @param processId the ID of the process
   */
  public void updateHeartbeat(String processId) {
    var processState = processStates.computeIfAbsent(processId, ProcessLockState::new);
    processState.lastHeartbeat = System.currentTimeMillis();
  }

  /**
   * Get metrics for a lock.
   *
   * @param lockName the name of the lock
   * @return the metrics, or empty if lock hasn't been used
   */
  public Optional<LockMetrics> getMetrics(String lockName) {
    return Optional.ofNullable(metrics.get(lockName));
  }

  /**
   * Get all metrics across all locks.
   *
   * @return immutable snapshot of all lock metrics
   */
  public Map<String, LockMetrics> getAllMetrics() {
    return Map.copyOf(metrics);
  }

  /**
   * Get statistics for a lock via the underlying lock implementation.
   *
   * @param lockName the name of the lock
   * @return lock statistics
   */
  public DistributedLock.LockStats getLockStats(String lockName) {
    return lock.getStats(lockName);
  }

  /**
   * Get all processes currently holding locks.
   *
   * @return map of process ID to set of lock names
   */
  public Map<String, Set<String>> getProcessLocks() {
    var result = new ConcurrentHashMap<String, Set<String>>();
    processStates.forEach((processId, state) -> {
      result.put(processId, new HashSet<>(state.heldLocks.keySet()));
    });
    return result;
  }

  /**
   * Shut down the lock manager.
   */
  public void shutdown() {
    shutdown = true;
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    lock.shutdown();
  }

  // ============ Private helpers ============

  private void detectAndBreakDeadlocks() {
    // Build wait-for graph from all lock waiters
    var waitForGraph = new ConcurrentHashMap<String, Set<String>>();

    for (String lockName : metrics.keySet()) {
      var stats = lock.getStats(lockName);
      if (stats.isLocked && stats.holder.isPresent()) {
        for (String waiter : stats.waiters) {
          waitForGraph.computeIfAbsent(waiter, k -> ConcurrentHashMap.newKeySet())
              .add(stats.holder.get());
        }
      }
    }

    // Detect cycles using DFS
    Set<String> visited = ConcurrentHashMap.newKeySet();
    Set<String> recStack = ConcurrentHashMap.newKeySet();

    for (String node : waitForGraph.keySet()) {
      if (hasCycle(node, waitForGraph, visited, recStack)) {
        // Deadlock detected, break the cycle
        breakDeadlock(node, waitForGraph);
      }
    }
  }

  private boolean hasCycle(
      String node,
      Map<String, Set<String>> graph,
      Set<String> visited,
      Set<String> recStack) {
    visited.add(node);
    recStack.add(node);

    var neighbors = graph.getOrDefault(node, Set.of());
    for (String neighbor : neighbors) {
      if (!visited.contains(neighbor)) {
        if (hasCycle(neighbor, graph, visited, recStack)) {
          return true;
        }
      } else if (recStack.contains(neighbor)) {
        return true;
      }
    }

    recStack.remove(node);
    return false;
  }

  private void breakDeadlock(String startNode, Map<String, Set<String>> graph) {
    // Force-release one lock to break the cycle
    // Simple strategy: release the lock held by startNode
    var processState = processStates.get(startNode);
    if (processState != null && !processState.heldLocks.isEmpty()) {
      var firstLock = processState.heldLocks.values().iterator().next();
      lock.forceRelease(firstLock.lockName);
    }
  }

  private void checkProcessHeartbeats() {
    long now = System.currentTimeMillis();
    long heartbeatTimeout = 60_000; // 60 second timeout

    processStates.forEach((processId, state) -> {
      if (now - state.lastHeartbeat > heartbeatTimeout) {
        onProcessCrash(processId);
      }
    });
  }
}
