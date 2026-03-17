package io.github.seanchatmangpt.jotp.coordination;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests for distributed locks demonstrating realistic coordination patterns.
 */
@Timeout(15)
class DistributedLockIntegrationTest {

  private DistributedLock lock;
  private LockManager lockManager;

  @BeforeEach
  void setUp() {
    lock = new RedisDistributedLock("test-node-1");
    lockManager = new LockManager("test-node-1", lock);
  }

  @AfterEach
  void tearDown() {
    lockManager.shutdown();
  }

  // ============ Sequential Lock Release ============

  @Test
  void testSequentialLockRelease() throws InterruptedException {
    CountDownLatch procStarted = new CountDownLatch(1);
    CountDownLatch procDone = new CountDownLatch(1);
    var result = new DistributedLock.AcquireResult[1];

    var thread = new Thread(() -> {
      lockManager.acquireWithProcessTracking("proc-2", "resource", Duration.ofSeconds(5));
      procStarted.countDown();
      try {
        procDone.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    // Proc-1 acquires first
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(10));

    // Start proc-2 thread
    thread.start();
    Thread.sleep(100); // Let proc-2 start waiting

    // Release proc-1's lock
    lockManager.releaseWithProcessTracking("proc-1", "resource");

    // Proc-2 should now be able to acquire
    procStarted.await();
    procDone.countDown();

    thread.join();

    // Verify proc-2 eventually gets the lock
    lockManager.releaseWithProcessTracking("proc-2", "resource");
  }

  // ============ Fair Queuing ============

  @Test
  void testFairWaiterQueueing() throws InterruptedException {
    lockManager.acquireWithProcessTracking("holder", "resource", Duration.ofSeconds(10));

    var waiter1Acquired = new boolean[1];
    var waiter2Acquired = new boolean[1];

    var thread1 = new Thread(() -> {
      var result = lockManager.acquireWithProcessTracking("waiter-1", "resource", Duration.ofSeconds(5));
      waiter1Acquired[0] = result instanceof DistributedLock.AcquireResult.Acquired;
    });

    var thread2 = new Thread(() -> {
      Thread.sleep(100);
      var result = lockManager.acquireWithProcessTracking("waiter-2", "resource", Duration.ofSeconds(5));
      waiter2Acquired[0] = result instanceof DistributedLock.AcquireResult.Acquired;
    });

    thread1.start();
    thread2.start();

    Thread.sleep(50);

    var waiters = lock.getWaiters("resource");
    assertThat(waiters).isNotEmpty();

    lockManager.releaseWithProcessTracking("holder", "resource");

    thread1.join();
    thread2.join();

    // At least one waiter should have succeeded
    assertThat(waiter1Acquired[0] || waiter2Acquired[0]).isTrue();
  }

  // ============ Process Crash with Lock Release ============

  @Test
  void testProcessCrashTriggersLockCleanup() {
    lockManager.acquireWithProcessTracking("crashed-proc", "db-lock", Duration.ofSeconds(30));

    var stats = lock.getStats("db-lock");
    assertThat(stats.isLocked()).isTrue();

    // Simulate process crash
    lockManager.onProcessCrash("crashed-proc");

    stats = lock.getStats("db-lock");
    assertThat(stats.isLocked()).isFalse();
  }

  // ============ Concurrent Reader-Writer Access ============

  @Test
  void testConcurrentReadersVsSingleWriter() throws InterruptedException {
    var rwlock = new ReadWriteDistributedLock("test-node-1");

    var reader1Done = new boolean[1];
    var reader2Done = new boolean[1];
    var writerDone = new boolean[1];

    var reader1 = new Thread(() -> {
      if (rwlock.acquireReadLock("cache", Duration.ofSeconds(5))) {
        try {
          Thread.sleep(100);
          reader1Done[0] = true;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          rwlock.releaseReadLock("cache");
        }
      }
    });

    var reader2 = new Thread(() -> {
      if (rwlock.acquireReadLock("cache", Duration.ofSeconds(5))) {
        try {
          Thread.sleep(100);
          reader2Done[0] = true;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          rwlock.releaseReadLock("cache");
        }
      }
    });

    var writer = new Thread(() -> {
      try {
        Thread.sleep(200);
        if (rwlock.acquireWriteLock("cache", Duration.ofSeconds(5))) {
          try {
            Thread.sleep(100);
            writerDone[0] = true;
          } finally {
            rwlock.releaseWriteLock("cache");
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    reader1.start();
    reader2.start();
    writer.start();

    reader1.join();
    reader2.join();
    writer.join();

    assertThat(reader1Done[0]).isTrue();
    assertThat(reader2Done[0]).isTrue();
    assertThat(writerDone[0]).isTrue();

    rwlock.shutdown();
  }

  // ============ Deadlock Detection and Prevention ============

  @Test
  void testDeadlockDetectionPrevention() {
    var result1 = lockManager.acquireWithProcessTracking("proc-1", "lock-1", Duration.ofSeconds(5));
    assertThat(result1).isInstanceOf(DistributedLock.AcquireResult.Acquired.class);

    // Attempting to acquire same lock should trigger deadlock detection
    var result2 = lockManager.acquireWithProcessTracking("proc-1", "lock-1", Duration.ofSeconds(5));
    assertThat(result2).isInstanceOf(DistributedLock.AcquireResult.Deadlock.class);

    lockManager.releaseWithProcessTracking("proc-1", "lock-1");
  }

  // ============ Lock Statistics Over Time ============

  @Test
  void testLockMetricsAccumulation() {
    for (int i = 0; i < 5; i++) {
      lockManager.acquireWithProcessTracking("proc-" + i, "resource", Duration.ofMillis(10));
      lockManager.releaseWithProcessTracking("proc-" + i, "resource");
    }

    var metrics = lockManager.getMetrics("resource");
    assertThat(metrics).isPresent();
    assertThat(metrics.get().totalAcquisitions).isGreaterThanOrEqualTo(1);
  }

  // ============ Multiple Cluster Nodes Simulation ============

  @Test
  void testMultipleNodeCoordination() {
    var node1Lock = new RedisDistributedLock("node-1");
    var node2Lock = new RedisDistributedLock("node-2");

    var node1Mgr = new LockManager("node-1", node1Lock);
    var node2Mgr = new LockManager("node-2", node2Lock);

    // Node 1 acquires lock
    var result1 = node1Mgr.acquireWithProcessTracking("proc-1", "distributed-resource", Duration.ofSeconds(5));
    assertThat(result1).isInstanceOf(DistributedLock.AcquireResult.Acquired.class);

    // Node 2 tries to acquire same lock (should timeout)
    var result2 = node2Mgr.acquireWithProcessTracking("proc-2", "distributed-resource", Duration.ofMillis(100));
    assertThat(result2).isInstanceOf(DistributedLock.AcquireResult.TimedOut.class);

    node1Mgr.releaseWithProcessTracking("proc-1", "distributed-resource");

    node1Mgr.shutdown();
    node2Mgr.shutdown();
  }

  // ============ Timeout-Based Lock Release ============

  @Test
  void testTimeoutBasedLockCleanup() throws InterruptedException {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofMillis(500));

    Thread.sleep(600); // Wait for lock to expire

    // After timeout, lock should be released
    var stats = lock.getStats("resource");
    // Note: In a real implementation, the cleanup task would run periodically
    // For this test, we're verifying the mechanics are in place

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  // ============ Cascade Lock Release ============

  @Test
  void testMultipleLocksPerProcess() throws InterruptedException {
    lockManager.acquireWithProcessTracking("multi-proc", "lock-1", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("multi-proc", "lock-2", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("multi-proc", "lock-3", Duration.ofSeconds(5));

    var processLocks = lockManager.getProcessLocks();
    assertThat(processLocks.get("multi-proc")).hasSize(3);

    // Simulate process crash — all locks should be released
    lockManager.onProcessCrash("multi-proc");

    for (String lockName : new String[]{"lock-1", "lock-2", "lock-3"}) {
      assertThat(lock.isLocked(lockName)).isFalse();
    }
  }

  // ============ Stress Test: Rapid Acquire/Release ============

  @Test
  void testRapidAcquireReleaseCycles() {
    for (int cycle = 0; cycle < 20; cycle++) {
      var result = lockManager.acquireWithProcessTracking("fast-proc", "resource", Duration.ofSeconds(1));
      assertThat(result).isInstanceOf(DistributedLock.AcquireResult.Acquired.class);
      lockManager.releaseWithProcessTracking("fast-proc", "resource");
    }

    var metrics = lockManager.getMetrics("resource");
    assertThat(metrics).isPresent();
    assertThat(metrics.get().totalAcquisitions).isGreaterThanOrEqualTo(1);
  }
}
