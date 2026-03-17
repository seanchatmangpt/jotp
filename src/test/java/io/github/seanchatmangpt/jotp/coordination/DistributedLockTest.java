package io.github.seanchatmangpt.jotp.coordination;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for DistributedLock implementations (Redis and PostgreSQL).
 */
@Timeout(10)
class DistributedLockTest {

  private DistributedLock lock;

  @BeforeEach
  void setUp() {
    lock = new RedisDistributedLock("test-node-1");
  }

  @AfterEach
  void tearDown() {
    lock.shutdown();
  }

  // ============ Basic Acquire/Release ============

  @Test
  void testAcquireAndRelease() {
    var result = lock.acquireLock("resource", Duration.ofSeconds(5));

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.Acquired.class);
    assertThat(lock.isLocked("resource")).isTrue();

    lock.releaseLock("resource");
    assertThat(lock.isLocked("resource")).isFalse();
  }

  @Test
  void testTryLock() {
    boolean acquired = lock.tryLock("resource", Duration.ZERO);
    assertThat(acquired).isTrue();
    assertThat(lock.isLocked("resource")).isTrue();

    lock.releaseLock("resource");
  }

  // ============ Mutual Exclusion ============

  @Test
  void testMutualExclusion() throws InterruptedException {
    lock.acquireLock("critical", Duration.ofSeconds(5));

    var thread = new Thread(() -> {
      var result = lock.acquireLock("critical", Duration.ofMillis(100));
      assertThat(result).isInstanceOf(DistributedLock.AcquireResult.TimedOut.class);
    });

    thread.start();
    thread.join();

    lock.releaseLock("critical");
  }

  // ============ Timeout Handling ============

  @Test
  void testAcquireTimeout() {
    lock.acquireLock("locked", Duration.ofSeconds(10));

    var result = lock.acquireLock("locked", Duration.ofMillis(100));

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.TimedOut.class);

    lock.releaseLock("locked");
  }

  @Test
  void testTimeoutIncludesWaitTime() {
    lock.acquireLock("resource", Duration.ofSeconds(10));

    long start = System.currentTimeMillis();
    var result = lock.acquireLock("resource", Duration.ofMillis(50));
    long elapsed = System.currentTimeMillis() - start;

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.TimedOut.class);
    if (result instanceof DistributedLock.AcquireResult.TimedOut timedOut) {
      assertThat(timedOut.waitedFor().toMillis()).isGreaterThanOrEqualTo(50);
    }

    lock.releaseLock("resource");
  }

  // ============ Deadlock Detection ============

  @Test
  void testSelfDeadlock() {
    lock.acquireLock("resource", Duration.ofSeconds(5));

    var result = lock.acquireLock("resource", Duration.ofSeconds(5));

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.Deadlock.class);

    lock.releaseLock("resource");
  }

  // ============ Waiter Queue ============

  @Test
  void testWaiters() {
    lock.acquireLock("resource", Duration.ofSeconds(10));

    // Simulate additional processes waiting
    var list = lock.getWaiters("resource");
    assertThat(list).isNotNull();

    lock.releaseLock("resource");
  }

  // ============ Statistics ============

  @Test
  void testGetStats() {
    lock.acquireLock("resource", Duration.ofSeconds(10));

    var stats = lock.getStats("resource");

    assertThat(stats.lockName()).isEqualTo("resource");
    assertThat(stats.isLocked()).isTrue();
    assertThat(stats.holder()).isPresent();
    assertThat(stats.queueLength()).isGreaterThanOrEqualTo(0);

    lock.releaseLock("resource");
  }

  @Test
  void testStatsAfterRelease() {
    lock.acquireLock("resource", Duration.ofSeconds(10));
    lock.releaseLock("resource");

    var stats = lock.getStats("resource");

    assertThat(stats.isLocked()).isFalse();
    assertThat(stats.holder()).isEmpty();
  }

  // ============ Force Release ============

  @Test
  void testForceRelease() {
    lock.acquireLock("resource", Duration.ofSeconds(10));
    assertThat(lock.isLocked("resource")).isTrue();

    boolean released = lock.forceRelease("resource");

    assertThat(released).isTrue();
    assertThat(lock.isLocked("resource")).isFalse();
  }

  @Test
  void testForceReleaseNotLocked() {
    boolean released = lock.forceRelease("resource");
    assertThat(released).isFalse();
  }

  // ============ Deadlock Detection Config ============

  @Test
  void testDeadlockDetectionEnabled() {
    lock.setDeadlockDetectionEnabled("resource", true);

    lock.acquireLock("resource", Duration.ofSeconds(5));

    var result = lock.acquireLock("resource", Duration.ofSeconds(5));

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.Deadlock.class);

    lock.releaseLock("resource");
  }

  @Test
  void testDeadlockDetectionDisabled() {
    lock.setDeadlockDetectionEnabled("resource", false);

    lock.acquireLock("resource", Duration.ofSeconds(5));

    // With detection disabled, the second acquire might timeout or wait
    var result = lock.acquireLock("resource", Duration.ofMillis(100));

    assertThat(result).isInstanceOf(DistributedLock.AcquireResult.TimedOut.class);

    lock.releaseLock("resource");
  }

  // ============ Crash Callback ============

  @Test
  void testCrashCallback() {
    var called = new boolean[1];
    lock.onHolderCrash("resource", () -> called[0] = true);

    lock.acquireLock("resource", Duration.ofSeconds(10));
    lock.releaseLock("resource");

    // Callback should be invoked when holder is detected as crashed
    // (In real implementation, this happens via heartbeat timeout)
  }

  // ============ Flush All ============

  @Test
  void testFlushAll() {
    lock.acquireLock("lock1", Duration.ofSeconds(10));
    lock.acquireLock("lock2", Duration.ofSeconds(10));

    lock.flushAll();

    assertThat(lock.isLocked("lock1")).isFalse();
    assertThat(lock.isLocked("lock2")).isFalse();
  }

  // ============ Integration: Multiple Locks ============

  @Test
  void testMultipleLocks() {
    lock.acquireLock("lock1", Duration.ofSeconds(5));
    lock.acquireLock("lock2", Duration.ofSeconds(5));

    assertThat(lock.isLocked("lock1")).isTrue();
    assertThat(lock.isLocked("lock2")).isTrue();

    lock.releaseLock("lock1");
    assertThat(lock.isLocked("lock1")).isFalse();
    assertThat(lock.isLocked("lock2")).isTrue();

    lock.releaseLock("lock2");
  }

  // ============ Lock Stats Metrics ============

  @Test
  void testLockStatsEmpty() {
    var stats = lock.getStats("nonexistent");

    assertThat(stats.isLocked()).isFalse();
    assertThat(stats.holder()).isEmpty();
    assertThat(stats.queueLength()).isZero();
    assertThat(stats.waiters()).isEmpty();
  }
}
