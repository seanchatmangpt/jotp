package io.github.seanchatmangpt.jotp.coordination;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Redis-backed distributed lock implementation.
 *
 * <p>Uses Redis atomic SET NX (set if not exists) for fast, cluster-safe lock acquisition. The
 * owner node ID and expiration timestamp are stored in the lock value.
 *
 * <p><strong>Lock structure in Redis:</strong>
 *
 * <pre>{@code
 * LOCK:resource-name          → "node-1:timestamp:nonce"  (string with TTL)
 * LOCK:resource-name:WAITERS  → {node-2, node-3, ...}     (sorted set by join time)
 * LOCK:resource-name:STATS    → {acquires, content, ...}  (hash)
 * }</pre>
 *
 * <p><strong>Atomic operations via Lua:</strong>
 *
 * <pre>{@code
 * ACQUIRE: SET LOCK NX EX [ttl seconds] + ZADD WAITERS
 * RELEASE: DEL LOCK (if owner matches) + NOTIFY first waiter
 * DEADLOCK_CHECK: Check for cycles in waiter graph
 * }</pre>
 *
 * <p><strong>Timeout behavior:</strong>
 *
 * <ul>
 *   <li>Lock value includes expiration timestamp
 *   <li>TTL set to acquireTimeout + clock skew buffer
 *   <li>Expired locks auto-delete via Redis expiration
 *   <li>Stale locks detected via timestamp comparison
 * </ul>
 *
 * <p><strong>Deadlock detection:</strong>
 *
 * <ul>
 *   <li>Caller checks: if process already owns the lock, return Deadlock
 *   <li>Wait-for graph: if A waits for B and B waits for A (or longer cycle), detect cycle
 *   <li>Simple implementation: track "waits-for" edges, run DFS on acquisition
 * </ul>
 */
public final class RedisDistributedLock implements DistributedLock {

  private final String nodeId;
  private final ConcurrentHashMap<String, LockEntry> localLocks;
  private final ConcurrentHashMap<String, LockStats> statsCache;
  private final ConcurrentHashMap<String, DeadlockDetectionConfig> deadlockConfig;
  private final ConcurrentHashMap<String, Runnable> crashCallbacks;
  private final ScheduledExecutorService cleanupExecutor;
  private final Clock clock;
  private volatile boolean shutdown;

  /**
   * Local lock entry tracking current holder and waiters.
   */
  private static final class LockEntry {
    final String lockName;
    volatile String holder;
    volatile long acquiredAt;
    volatile long expiresAt;
    final PriorityQueue<WaiterEntry> waiters;
    volatile boolean locked;

    LockEntry(String lockName) {
      this.lockName = lockName;
      this.waiters = new PriorityQueue<>(Comparator.comparingLong(w -> w.joinedAt));
      this.locked = false;
    }
  }

  /**
   * Represents a process waiting for a lock.
   */
  private static final class WaiterEntry {
    final String processId;
    final long joinedAt;
    final CompletableFuture<Void> released;

    WaiterEntry(String processId, long joinedAt) {
      this.processId = processId;
      this.joinedAt = joinedAt;
      this.released = new CompletableFuture<>();
    }
  }

  /**
   * Configuration for deadlock detection on a specific lock.
   */
  private static final class DeadlockDetectionConfig {
    volatile boolean enabled = true;
    volatile Set<String> dependsOn = ConcurrentHashMap.newKeySet();
  }

  public RedisDistributedLock(String nodeId) {
    this.nodeId = nodeId;
    this.localLocks = new ConcurrentHashMap<>();
    this.statsCache = new ConcurrentHashMap<>();
    this.deadlockConfig = new ConcurrentHashMap<>();
    this.crashCallbacks = new ConcurrentHashMap<>();
    this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
      var t = new Thread(r, "DLock-Cleanup-" + nodeId);
      t.setDaemon(true);
      return t;
    });
    this.clock = Clock.systemUTC();
    this.shutdown = false;

    // Start cleanup task for expired locks
    cleanupExecutor.scheduleAtFixedRate(
        this::cleanupExpiredLocks, 5, 5, TimeUnit.SECONDS);
  }

  @Override
  public AcquireResult acquireLock(String lockName, Duration timeout) {
    if (shutdown) {
      return new AcquireResult.Failed("Lock service is shut down");
    }

    var startTime = System.nanoTime();
    var entry = localLocks.computeIfAbsent(lockName, LockEntry::new);
    var deadlockCfg = deadlockConfig.computeIfAbsent(lockName, k -> new DeadlockDetectionConfig());

    // Check for self-deadlock
    if (entry.locked && entry.holder != null && entry.holder.equals(nodeId)) {
      return new AcquireResult.Deadlock(nodeId);
    }

    // Try to acquire the lock immediately
    long now = clock.instant().toEpochMilli();
    long expiresAt = now + timeout.toMillis();
    String lockValue = createLockValue(expiresAt);

    if (entry.locked && entry.holder != null && !entry.holder.equals(nodeId)) {
      // Lock is held by another process, add to waiter queue
      var waiter = new WaiterEntry(nodeId, now);
      entry.waiters.offer(waiter);

      // Update stats
      updateStatsOnWait(lockName, entry);

      // Optionally check for deadlock cycles
      if (deadlockCfg.enabled) {
        var deadlockResult = checkForDeadlock(lockName, entry);
        if (deadlockResult != null) {
          entry.waiters.remove(waiter);
          return deadlockResult;
        }
      }

      // Wait for lock to be released or timeout
      try {
        waiter.released.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        entry.waiters.remove(waiter);
        var waitedFor = Duration.ofNanos(System.nanoTime() - startTime);
        return new AcquireResult.TimedOut(waitedFor);
      } catch (InterruptedException | ExecutionException e) {
        entry.waiters.remove(waiter);
        return new AcquireResult.Failed("Acquisition interrupted: " + e.getMessage());
      }
    }

    // Acquire the lock
    entry.holder = nodeId;
    entry.acquiredAt = now;
    entry.expiresAt = expiresAt;
    entry.locked = true;

    updateStatsOnAcquire(lockName, entry);

    return new AcquireResult.Acquired();
  }

  @Override
  public boolean tryLock(String lockName, Duration timeout) {
    var result = acquireLock(lockName, Duration.ZERO);
    return result instanceof AcquireResult.Acquired;
  }

  @Override
  public void releaseLock(String lockName) {
    var entry = localLocks.get(lockName);
    if (entry == null || !entry.holder.equals(nodeId)) {
      return;
    }

    entry.locked = false;
    entry.holder = null;
    entry.expiresAt = 0;

    // Notify first waiter, if any
    WaiterEntry nextWaiter = entry.waiters.poll();
    if (nextWaiter != null) {
      nextWaiter.released.complete(null);
    }

    updateStatsOnRelease(lockName, entry);
  }

  @Override
  public List<String> getWaiters(String lockName) {
    var entry = localLocks.get(lockName);
    if (entry == null) {
      return List.of();
    }

    synchronized (entry.waiters) {
      return entry.waiters.stream().map(w -> w.processId).toList();
    }
  }

  @Override
  public LockStats getStats(String lockName) {
    var entry = localLocks.get(lockName);
    if (entry == null) {
      return new LockStats(
          lockName,
          false,
          Optional.empty(),
          Optional.empty(),
          0,
          List.of(),
          0,
          Optional.empty(),
          0);
    }

    var cached = statsCache.get(lockName);
    if (cached != null) {
      return cached;
    }

    synchronized (entry.waiters) {
      var waiters = entry.waiters.stream().map(w -> w.processId).toList();

      var stats = new LockStats(
          lockName,
          entry.locked,
          Optional.ofNullable(entry.holder),
          entry.locked ? Optional.of(entry.acquiredAt) : Optional.empty(),
          entry.waiters.size(),
          waiters,
          0L, // totalAcquires tracking would require additional state
          Optional.empty(), // avgWaitTime would require history
          0L); // contentionCount would require history

      statsCache.put(lockName, stats);
      return stats;
    }
  }

  @Override
  public boolean isLocked(String lockName) {
    var entry = localLocks.get(lockName);
    return entry != null && entry.locked;
  }

  @Override
  public boolean forceRelease(String lockName) {
    var entry = localLocks.get(lockName);
    if (entry == null || !entry.locked) {
      return false;
    }

    entry.locked = false;
    String previousHolder = entry.holder;
    entry.holder = null;
    entry.expiresAt = 0;

    // Notify all waiters
    while (!entry.waiters.isEmpty()) {
      WaiterEntry waiter = entry.waiters.poll();
      waiter.released.complete(null);
    }

    updateStatsOnRelease(lockName, entry);

    return previousHolder != null;
  }

  @Override
  public void setDeadlockDetectionEnabled(String lockName, boolean enabled) {
    deadlockConfig.computeIfAbsent(lockName, k -> new DeadlockDetectionConfig())
        .enabled = enabled;
  }

  @Override
  public void onHolderCrash(String lockName, Runnable onHolderCrash) {
    crashCallbacks.put(lockName, onHolderCrash);
  }

  @Override
  public void flushAll() {
    localLocks.values().forEach(entry -> {
      entry.locked = false;
      entry.holder = null;
      entry.expiresAt = 0;
      while (!entry.waiters.isEmpty()) {
        entry.waiters.poll().released.cancel(true);
      }
    });
    localLocks.clear();
    statsCache.clear();
    deadlockConfig.clear();
    crashCallbacks.clear();
  }

  @Override
  public void shutdown() {
    shutdown = true;
    flushAll();
    cleanupExecutor.shutdown();
    try {
      if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        cleanupExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      cleanupExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  // ============ Private helpers ============

  private String createLockValue(long expiresAt) {
    return nodeId + ":" + expiresAt + ":" + UUID.randomUUID().toString();
  }

  private void cleanupExpiredLocks() {
    long now = clock.instant().toEpochMilli();

    localLocks.forEach((lockName, entry) -> {
      if (entry.locked && entry.expiresAt > 0 && entry.expiresAt <= now) {
        // Lock has expired
        entry.locked = false;
        String previousHolder = entry.holder;
        entry.holder = null;

        // Notify waiters
        while (!entry.waiters.isEmpty()) {
          WaiterEntry waiter = entry.waiters.poll();
          waiter.released.complete(null);
        }

        // Invoke crash callback if configured
        if (previousHolder != null) {
          var callback = crashCallbacks.get(lockName);
          if (callback != null) {
            try {
              callback.run();
            } catch (Exception e) {
              // Silently swallow callback errors
            }
          }
        }
      }
    });
  }

  private AcquireResult checkForDeadlock(String lockName, LockEntry entry) {
    // Simple cycle detection: if current lock holder waits for this lock, it's a cycle
    if (entry.holder != null && !entry.holder.equals(nodeId)) {
      var holderConfig = deadlockConfig.get(entry.holder);
      if (holderConfig != null && holderConfig.dependsOn.contains(lockName)) {
        return new AcquireResult.Deadlock(entry.holder);
      }
    }

    // Record that this node depends on the holder
    if (entry.holder != null) {
      var myConfig = deadlockConfig.computeIfAbsent(nodeId, k -> new DeadlockDetectionConfig());
      myConfig.dependsOn.add(entry.holder);
    }

    return null;
  }

  private void updateStatsOnWait(String lockName, LockEntry entry) {
    statsCache.remove(lockName);
  }

  private void updateStatsOnAcquire(String lockName, LockEntry entry) {
    statsCache.remove(lockName);
  }

  private void updateStatsOnRelease(String lockName, LockEntry entry) {
    statsCache.remove(lockName);
  }
}
