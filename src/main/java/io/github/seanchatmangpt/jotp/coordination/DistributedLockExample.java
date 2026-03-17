package io.github.seanchatmangpt.jotp.coordination;

import java.time.Duration;

/**
 * Example usage of the JOTP Distributed Lock service.
 *
 * <p>Demonstrates the key patterns for distributed coordination in a cluster:
 *
 * <ul>
 *   <li>Mutual exclusion via DistributedLock
 *   <li>Process-aware lock management with LockManager
 *   <li>Reader-writer coordination with ReadWriteDistributedLock
 *   <li>Deadlock detection and prevention
 * </ul>
 */
public final class DistributedLockExample {

  /**
   * Example 1: Basic lock acquire and release.
   */
  public static void example1BasicLock() {
    var lock = new RedisDistributedLock("node-1");

    // Acquire lock with timeout
    var result = lock.acquireLock("critical-section", Duration.ofSeconds(30));

    switch (result) {
      case DistributedLock.AcquireResult.Acquired acquired -> {
        try {
          // Critical section — only one process executes this
          System.out.println("Lock acquired, executing critical section");
          doWork();
        } finally {
          // Always release
          lock.releaseLock("critical-section");
        }
      }
      case DistributedLock.AcquireResult.TimedOut timedOut -> {
        System.out.println("Timeout after: " + timedOut.waitedFor());
      }
      case DistributedLock.AcquireResult.Deadlock deadlock -> {
        System.out.println("Deadlock detected, holder: " + deadlock.currentHolder());
      }
      case DistributedLock.AcquireResult.Failed failed -> {
        System.out.println("Lock acquisition failed: " + failed.reason());
      }
    }

    lock.shutdown();
  }

  /**
   * Example 2: Lock manager with process tracking and metrics.
   */
  public static void example2LockManager() {
    var lock = new RedisDistributedLock("node-1");
    var lockManager = new LockManager("node-1", lock);

    // Acquire with process tracking
    var result = lockManager.acquireWithProcessTracking("worker-1", "database-write", Duration.ofMinutes(1));

    if (result instanceof DistributedLock.AcquireResult.Acquired) {
      try {
        // Protected work
        updateDatabase();
      } finally {
        lockManager.releaseWithProcessTracking("worker-1", "database-write");
      }
    }

    // Check metrics
    var metrics = lockManager.getMetrics("database-write");
    if (metrics.isPresent()) {
      System.out.printf(
          "Lock stats: acquisitions=%d, timeouts=%d, deadlocks=%d, avg_wait=%dms%n",
          metrics.get().totalAcquisitions,
          metrics.get().totalTimeouts,
          metrics.get().totalDeadlocks,
          metrics.get().avgWaitTimeMs);
    }

    // On process crash: automatically release all locks
    lockManager.onProcessCrash("worker-1");

    lockManager.shutdown();
  }

  /**
   * Example 3: Reader-writer locks for high-concurrency scenarios.
   */
  public static void example3ReaderWriter() {
    var rwlock = new ReadWriteDistributedLock("node-1");

    // Multiple readers can access simultaneously
    if (rwlock.acquireReadLock("cache", Duration.ofSeconds(5))) {
      try {
        var data = readCache();
        System.out.println("Data: " + data);
      } finally {
        rwlock.releaseReadLock("cache");
      }
    }

    // Writer blocks all readers and other writers
    if (rwlock.acquireWriteLock("cache", Duration.ofSeconds(10))) {
      try {
        updateCache("new data");
      } finally {
        rwlock.releaseWriteLock("cache");
      }
    }

    System.out.printf("Readers: %d, Writer: %s%n", rwlock.getReaderCount("cache"), rwlock.getWriter("cache"));

    rwlock.shutdown();
  }

  /**
   * Example 4: Deadlock detection and prevention.
   */
  public static void example4DeadlockPrevention() {
    var lock = new RedisDistributedLock("node-1");

    // First acquisition
    lock.acquireLock("lock-a", Duration.ofSeconds(5));

    // Self-deadlock: same process tries to acquire same lock
    var result = lock.acquireLock("lock-a", Duration.ofSeconds(5));

    if (result instanceof DistributedLock.AcquireResult.Deadlock) {
      System.out.println("Deadlock prevented");
    }

    lock.releaseLock("lock-a");
    lock.shutdown();
  }

  /**
   * Example 5: Lock statistics and diagnostics.
   */
  public static void example5Diagnostics() {
    var lock = new RedisDistributedLock("node-1");

    lock.acquireLock("resource", Duration.ofSeconds(10));

    var stats = lock.getStats("resource");
    System.out.printf(
        "Lock: %s, Locked: %b, Holder: %s, Queue: %s%n",
        stats.lockName(), stats.isLocked(), stats.holder().orElse("none"), stats.waiters());

    lock.releaseLock("resource");
    lock.shutdown();
  }

  /**
   * Example 6: Non-blocking lock acquisition.
   */
  public static void example6NonBlocking() {
    var lock = new RedisDistributedLock("node-1");

    if (lock.tryLock("resource", Duration.ZERO)) {
      try {
        System.out.println("Lock acquired immediately");
      } finally {
        lock.releaseLock("resource");
      }
    } else {
      System.out.println("Lock held by another process, retrying later");
    }

    lock.shutdown();
  }

  // ============ Helper methods ============

  private static void doWork() {
    // Simulate work
  }

  private static void updateDatabase() {
    // Simulate database update
  }

  private static String readCache() {
    return "cached value";
  }

  private static void updateCache(String data) {
    // Simulate cache update
  }

  public static void main(String[] args) {
    System.out.println("=== JOTP Distributed Lock Examples ===\n");

    System.out.println("Example 1: Basic Lock");
    example1BasicLock();

    System.out.println("\nExample 2: Lock Manager");
    example2LockManager();

    System.out.println("\nExample 3: Reader-Writer Lock");
    example3ReaderWriter();

    System.out.println("\nExample 4: Deadlock Prevention");
    example4DeadlockPrevention();

    System.out.println("\nExample 5: Diagnostics");
    example5Diagnostics();

    System.out.println("\nExample 6: Non-Blocking Lock");
    example6NonBlocking();
  }
}
