package io.github.seanchatmangpt.jotp.coordination;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for LockManager coordination and monitoring.
 */
@Timeout(10)
class LockManagerTest {

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

  // ============ Basic Acquire with Process Tracking ============

  @Test
  void testAcquireWithProcessTracking() {
    var result = lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(5));

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.Acquired.class);

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  @Test
  void testProcessTrackingMultipleLocks() {
    lockManager.acquireWithProcessTracking("proc-1", "lock1", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("proc-1", "lock2", Duration.ofSeconds(5));

    var processLocks = lockManager.getProcessLocks();
    assertThat(processLocks).containsKey("proc-1");
    assertThat(processLocks.get("proc-1")).containsExactlyInAnyOrder("lock1", "lock2");

    lockManager.releaseWithProcessTracking("proc-1", "lock1");
    lockManager.releaseWithProcessTracking("proc-1", "lock2");
  }

  // ============ Process Crash Handling ============

  @Test
  void testProcessCrashReleasesLocks() {
    lockManager.acquireWithProcessTracking("proc-1", "lock1", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("proc-1", "lock2", Duration.ofSeconds(5));

    assertThat(lock.isLocked("lock1")).isTrue();
    assertThat(lock.isLocked("lock2")).isTrue();

    lockManager.onProcessCrash("proc-1");

    assertThat(lock.isLocked("lock1")).isFalse();
    assertThat(lock.isLocked("lock2")).isFalse();
  }

  @Test
  void testProcessCrashRemovesFromTracking() {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(5));

    var processLocks = lockManager.getProcessLocks();
    assertThat(processLocks).containsKey("proc-1");

    lockManager.onProcessCrash("proc-1");

    processLocks = lockManager.getProcessLocks();
    assertThat(processLocks).doesNotContainKey("proc-1");
  }

  // ============ Metrics Recording ============

  @Test
  void testMetricsRecordAcquisition() {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(5));

    var metrics = lockManager.getMetrics("resource");
    assertThat(metrics).isPresent();
    assertThat(metrics.get().totalAcquisitions).isGreaterThan(0);

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  @Test
  void testMetricsRecordTimeout() {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(10));

    var result = lockManager.acquireWithProcessTracking("proc-2", "resource", Duration.ofMillis(100));
    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.TimedOut.class);

    var metrics = lockManager.getMetrics("resource");
    assertThat(metrics).isPresent();
    assertThat(metrics.get().totalTimeouts).isGreaterThan(0);

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  @Test
  void testMetricsRecordDeadlock() {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(5));

    var result = lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(5));
    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.Deadlock.class);

    var metrics = lockManager.getMetrics("resource");
    assertThat(metrics).isPresent();
    assertThat(metrics.get().totalDeadlocks).isGreaterThan(0);

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  // ============ Metrics Queries ============

  @Test
  void testGetAllMetrics() {
    lockManager.acquireWithProcessTracking("proc-1", "lock1", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("proc-2", "lock2", Duration.ofSeconds(5));

    var allMetrics = lockManager.getAllMetrics();
    assertThat(allMetrics).containsKeys("lock1", "lock2");

    lockManager.releaseWithProcessTracking("proc-1", "lock1");
    lockManager.releaseWithProcessTracking("proc-2", "lock2");
  }

  @Test
  void testGetLockStats() {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofSeconds(5));

    var stats = lockManager.getLockStats("resource");
    assertThat(stats.isLocked()).isTrue();
    assertThat(stats.holder()).isPresent();

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  // ============ Heartbeat Monitoring ============

  @Test
  void testUpdateHeartbeat() {
    lockManager.updateHeartbeat("proc-1");

    var processLocks = lockManager.getProcessLocks();
    // Heartbeat update alone doesn't add to process locks
    assertThat(processLocks).doesNotContainKey("proc-1");
  }

  // ============ Lock Holder Queries ============

  @Test
  void testGetProcessLocks() {
    lockManager.acquireWithProcessTracking("proc-1", "lock1", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("proc-1", "lock2", Duration.ofSeconds(5));
    lockManager.acquireWithProcessTracking("proc-2", "lock3", Duration.ofSeconds(5));

    var processLocks = lockManager.getProcessLocks();

    assertThat(processLocks).containsKey("proc-1");
    assertThat(processLocks).containsKey("proc-2");
    assertThat(processLocks.get("proc-1")).containsExactlyInAnyOrder("lock1", "lock2");
    assertThat(processLocks.get("proc-2")).containsExactly("lock3");

    lockManager.releaseWithProcessTracking("proc-1", "lock1");
    lockManager.releaseWithProcessTracking("proc-1", "lock2");
    lockManager.releaseWithProcessTracking("proc-2", "lock3");
  }

  // ============ Metrics Content ============

  @Test
  void testLockMetricsContent() {
    lockManager.acquireWithProcessTracking("proc-1", "resource", Duration.ofMillis(50));

    var metrics = lockManager.getMetrics("resource").get();
    assertThat(metrics.lockName).isEqualTo("resource");
    assertThat(metrics.totalAcquisitions).isGreaterThan(0);
    assertThat(metrics.avgWaitTimeMs).isGreaterThanOrEqualTo(0);

    lockManager.releaseWithProcessTracking("proc-1", "resource");
  }

  // ============ Multiple Processes ============

  @Test
  void testMultipleProcessCoordination() throws InterruptedException {
    lockManager.acquireWithProcessTracking("proc-1", "shared", Duration.ofSeconds(10));

    var proc2Acquired = new boolean[1];
    var thread = new Thread(() -> {
      var result = lockManager.acquireWithProcessTracking("proc-2", "shared", Duration.ofMillis(100));
      proc2Acquired[0] = result instanceof DistributedLock.AcquireResult.Acquired;
    });

    thread.start();
    thread.join();

    // proc-2 should not have acquired (proc-1 holds it)
    assertThat(proc2Acquired[0]).isFalse();

    lockManager.releaseWithProcessTracking("proc-1", "shared");
  }

  // ============ Empty Metrics ============

  @Test
  void testMetricsNonexistent() {
    var metrics = lockManager.getMetrics("nonexistent");
    assertThat(metrics).isEmpty();
  }

  // ============ Stress: Many Locks ============

  @Test
  void testManyLocksTracking() {
    for (int i = 0; i < 10; i++) {
      lockManager.acquireWithProcessTracking("proc-1", "lock-" + i, Duration.ofSeconds(5));
    }

    var processLocks = lockManager.getProcessLocks();
    assertThat(processLocks.get("proc-1")).hasSize(10);

    lockManager.onProcessCrash("proc-1");

    processLocks = lockManager.getProcessLocks();
    assertThat(processLocks).doesNotContainKey("proc-1");
  }
}
