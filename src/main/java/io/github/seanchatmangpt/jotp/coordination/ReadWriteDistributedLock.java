package io.github.seanchatmangpt.jotp.coordination;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Distributed read-write lock allowing multiple concurrent readers or a single writer.
 *
 * <p>Implements the classic reader-writer pattern:
 *
 * <ul>
 *   <li><strong>Multiple readers:</strong> Any number of processes can hold read locks
 *       simultaneously
 *   <li><strong>Exclusive writer:</strong> Only one process can hold a write lock
 *   <li><strong>Read-write mutual exclusion:</strong> Readers and writers block each other
 *   <li><strong>Fair ordering:</strong> Writers have priority to prevent writer starvation
 * </ul>
 *
 * <p><strong>Lock state structure:</strong>
 *
 * <pre>{@code
 * RWLock:resource:MODE       → "READ" | "WRITE" | "UNLOCKED"
 * RWLock:resource:HOLDERS    → Set of reader/writer IDs
 * RWLock:resource:WAITERS    → Queue of (processId, mode, joinedAt)
 * }</pre>
 *
 * <p><strong>Fair ordering algorithm:</strong>
 *
 * <pre>{@code
 * 1. Writers always take priority — once a writer arrives, readers must wait
 * 2. Readers acquire if no writers are waiting or holding
 * 3. Writers acquire only if no readers or writers are holding
 * 4. This prevents writer starvation on high reader load
 * }</pre>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var rwlock = new ReadWriteDistributedLock(nodeId);
 *
 * // Multiple readers can read simultaneously
 * rwlock.acquireReadLock("cache", Duration.ofSeconds(10));
 * try {
 *     var data = cache.get();
 * } finally {
 *     rwlock.releaseReadLock("cache");
 * }
 *
 * // Writer blocks all readers
 * rwlock.acquireWriteLock("cache", Duration.ofSeconds(30));
 * try {
 *     cache.update(newData);
 * } finally {
 *     rwlock.releaseWriteLock("cache");
 * }
 * }</pre>
 */
public final class ReadWriteDistributedLock {

  /**
   * Lock mode: READ for readers, WRITE for exclusive writer, NONE for no lock.
   */
  private enum LockMode {
    READ,
    WRITE,
    NONE
  }

  private final String nodeId;
  private final ConcurrentHashMap<String, RWLockEntry> locks;
  private final ScheduledExecutorService cleanupExecutor;
  private final Clock clock;
  private volatile boolean shutdown;

  /**
   * Represents the state of a read-write lock.
   */
  private static final class RWLockEntry {
    final String lockName;
    volatile LockMode mode = LockMode.NONE;
    final Set<String> readers = ConcurrentHashMap.newKeySet();
    volatile String writer;
    final PriorityQueue<WaiterEntry> waiters;
    volatile long expiresAt;

    RWLockEntry(String lockName) {
      this.lockName = lockName;
      this.waiters = new PriorityQueue<>(Comparator.comparingLong(w -> w.joinedAt));
    }
  }

  /**
   * Represents a process waiting for a read or write lock.
   */
  private static final class WaiterEntry {
    final String processId;
    final LockMode requestedMode;
    final long joinedAt;
    final CompletableFuture<Void> granted;

    WaiterEntry(String processId, LockMode mode, long joinedAt) {
      this.processId = processId;
      this.requestedMode = mode;
      this.joinedAt = joinedAt;
      this.granted = new CompletableFuture<>();
    }
  }

  public ReadWriteDistributedLock(String nodeId) {
    this.nodeId = nodeId;
    this.locks = new ConcurrentHashMap<>();
    this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
      var t = new Thread(r, "RWDLock-Cleanup-" + nodeId);
      t.setDaemon(true);
      return t;
    });
    this.clock = Clock.systemUTC();
    this.shutdown = false;

    cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredLocks, 5, 5, TimeUnit.SECONDS);
  }

  /**
   * Acquire a read lock with timeout.
   *
   * <p>Multiple readers can hold a read lock simultaneously. Blocks if a writer holds the lock or
   * is waiting.
   *
   * @param lockName the name of the lock
   * @param timeout how long to wait before giving up
   * @return true if read lock acquired, false if timeout
   */
  public boolean acquireReadLock(String lockName, Duration timeout) {
    if (shutdown) {
      return false;
    }

    var entry = locks.computeIfAbsent(lockName, RWLockEntry::new);
    long now = clock.instant().toEpochMilli();
    entry.expiresAt = now + timeout.toMillis();

    synchronized (entry) {
      // Check if we can acquire immediately: no writer, no writers waiting
      if (entry.mode == LockMode.NONE || entry.mode == LockMode.READ) {
        boolean hasWaitingWriters = entry.waiters.stream()
            .anyMatch(w -> w.requestedMode == LockMode.WRITE);

        if (!hasWaitingWriters) {
          entry.readers.add(nodeId);
          entry.mode = LockMode.READ;
          return true;
        }
      }

      // Must wait
      var waiter = new WaiterEntry(nodeId, LockMode.READ, now);
      entry.waiters.offer(waiter);

      try {
        waiter.granted.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        entry.readers.add(nodeId);
        entry.mode = LockMode.READ;
        return true;
      } catch (TimeoutException | InterruptedException | ExecutionException e) {
        entry.waiters.remove(waiter);
        return false;
      }
    }
  }

  /**
   * Acquire a write lock with timeout.
   *
   * <p>Only one process can hold a write lock. Blocks if any readers or other writers hold the
   * lock.
   *
   * @param lockName the name of the lock
   * @param timeout how long to wait before giving up
   * @return true if write lock acquired, false if timeout
   */
  public boolean acquireWriteLock(String lockName, Duration timeout) {
    if (shutdown) {
      return false;
    }

    var entry = locks.computeIfAbsent(lockName, RWLockEntry::new);
    long now = clock.instant().toEpochMilli();
    entry.expiresAt = now + timeout.toMillis();

    synchronized (entry) {
      // Check if we can acquire immediately: no readers, no writer
      if (entry.mode == LockMode.NONE) {
        entry.writer = nodeId;
        entry.mode = LockMode.WRITE;
        return true;
      }

      // Must wait
      var waiter = new WaiterEntry(nodeId, LockMode.WRITE, now);
      entry.waiters.offer(waiter);

      try {
        waiter.granted.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        entry.writer = nodeId;
        entry.mode = LockMode.WRITE;
        return true;
      } catch (TimeoutException | InterruptedException | ExecutionException e) {
        entry.waiters.remove(waiter);
        return false;
      }
    }
  }

  /**
   * Release a read lock held by this process.
   *
   * @param lockName the name of the lock
   */
  public void releaseReadLock(String lockName) {
    var entry = locks.get(lockName);
    if (entry == null) {
      return;
    }

    synchronized (entry) {
      if (!entry.readers.remove(nodeId)) {
        return; // This process doesn't hold the lock
      }

      if (entry.readers.isEmpty()) {
        entry.mode = LockMode.NONE;
      }

      notifyWaiters(entry);
    }
  }

  /**
   * Release a write lock held by this process.
   *
   * @param lockName the name of the lock
   */
  public void releaseWriteLock(String lockName) {
    var entry = locks.get(lockName);
    if (entry == null) {
      return;
    }

    synchronized (entry) {
      if (entry.writer == null || !entry.writer.equals(nodeId)) {
        return; // This process doesn't hold the write lock
      }

      entry.writer = null;
      entry.mode = LockMode.NONE;

      notifyWaiters(entry);
    }
  }

  /**
   * Check if a read lock is held on this resource.
   *
   * @param lockName the name of the lock
   * @return true if at least one reader holds the lock
   */
  public boolean hasReadLock(String lockName) {
    var entry = locks.get(lockName);
    return entry != null && !entry.readers.isEmpty();
  }

  /**
   * Check if a write lock is held on this resource.
   *
   * @param lockName the name of the lock
   * @return true if a writer holds the lock
   */
  public boolean hasWriteLock(String lockName) {
    var entry = locks.get(lockName);
    return entry != null && entry.writer != null;
  }

  /**
   * Get the number of readers holding the lock.
   *
   * @param lockName the name of the lock
   * @return count of active readers
   */
  public int getReaderCount(String lockName) {
    var entry = locks.get(lockName);
    return entry == null ? 0 : entry.readers.size();
  }

  /**
   * Get the ID of the process holding the write lock (if any).
   *
   * @param lockName the name of the lock
   * @return the writer's process ID, or empty if no writer
   */
  public Optional<String> getWriter(String lockName) {
    var entry = locks.get(lockName);
    return Optional.ofNullable(entry == null ? null : entry.writer);
  }

  /**
   * Force-release all locks on a resource.
   *
   * <p>For disaster recovery only.
   *
   * @param lockName the name of the lock
   */
  public void forceRelease(String lockName) {
    var entry = locks.get(lockName);
    if (entry == null) {
      return;
    }

    synchronized (entry) {
      entry.readers.clear();
      entry.writer = null;
      entry.mode = LockMode.NONE;

      // Notify all waiters
      while (!entry.waiters.isEmpty()) {
        entry.waiters.poll().granted.complete(null);
      }
    }
  }

  /**
   * Flush all locks for testing.
   */
  public void flushAll() {
    locks.values().forEach(entry -> {
      synchronized (entry) {
        entry.readers.clear();
        entry.writer = null;
        entry.mode = LockMode.NONE;
        entry.waiters.forEach(w -> w.granted.cancel(true));
        entry.waiters.clear();
      }
    });
    locks.clear();
  }

  /**
   * Shut down the lock manager.
   */
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

  private void notifyWaiters(RWLockEntry entry) {
    // Grant locks to waiting processes according to fair scheduling
    // Writers have priority: if a writer is waiting and lock is free, grant it
    // Otherwise, grant all waiting readers if no writers are waiting

    while (!entry.waiters.isEmpty()) {
      WaiterEntry next = entry.waiters.peek();

      if (next.requestedMode == LockMode.WRITE) {
        if (entry.mode == LockMode.NONE) {
          entry.waiters.poll();
          next.granted.complete(null);
          entry.writer = next.processId;
          entry.mode = LockMode.WRITE;
          break; // Only one writer at a time
        } else {
          break; // Can't grant writer while lock is held
        }
      } else {
        // Reader: grant if no writers and no waiting writers
        boolean hasWaitingWriters = false;
        for (var w : entry.waiters) {
          if (w != next && w.requestedMode == LockMode.WRITE) {
            hasWaitingWriters = true;
            break;
          }
        }

        if (entry.mode == LockMode.NONE || entry.mode == LockMode.READ) {
          if (!hasWaitingWriters) {
            entry.waiters.poll();
            next.granted.complete(null);
          } else {
            break; // Writers waiting, readers must wait
          }
        } else {
          break; // Writer holds lock
        }
      }
    }
  }

  private void cleanupExpiredLocks() {
    long now = clock.instant().toEpochMilli();

    locks.forEach((lockName, entry) -> {
      synchronized (entry) {
        if (entry.expiresAt > 0 && entry.expiresAt <= now) {
          entry.readers.clear();
          entry.writer = null;
          entry.mode = LockMode.NONE;
          entry.waiters.forEach(w -> w.granted.complete(null));
          entry.waiters.clear();
        }
      }
    });
  }
}
