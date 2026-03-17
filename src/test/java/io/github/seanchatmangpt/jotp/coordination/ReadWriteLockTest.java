package io.github.seanchatmangpt.jotp.coordination;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for ReadWriteDistributedLock.
 */
@Timeout(10)
class ReadWriteLockTest {

  private ReadWriteDistributedLock rwlock;

  @BeforeEach
  void setUp() {
    rwlock = new ReadWriteDistributedLock("test-node-1");
  }

  @AfterEach
  void tearDown() {
    rwlock.shutdown();
  }

  // ============ Read Lock Acquisition ============

  @Test
  void testAcquireReadLock() {
    boolean acquired = rwlock.acquireReadLock("resource", Duration.ofSeconds(5));

    assertThat(acquired).isTrue();
    assertThat(rwlock.hasReadLock("resource")).isTrue();

    rwlock.releaseReadLock("resource");
    assertThat(rwlock.hasReadLock("resource")).isFalse();
  }

  @Test
  void testMultipleReaders() {
    boolean reader1 = rwlock.acquireReadLock("resource", Duration.ofSeconds(5));
    boolean reader2 = rwlock.acquireReadLock("resource", Duration.ofSeconds(5));

    assertThat(reader1).isTrue();
    assertThat(reader2).isTrue();
    assertThat(rwlock.getReaderCount("resource")).isEqualTo(2);

    rwlock.releaseReadLock("resource");
    rwlock.releaseReadLock("resource");
  }

  // ============ Write Lock Acquisition ============

  @Test
  void testAcquireWriteLock() {
    boolean acquired = rwlock.acquireWriteLock("resource", Duration.ofSeconds(5));

    assertThat(acquired).isTrue();
    assertThat(rwlock.hasWriteLock("resource")).isTrue();

    rwlock.releaseWriteLock("resource");
    assertThat(rwlock.hasWriteLock("resource")).isFalse();
  }

  // ============ Reader-Writer Mutual Exclusion ============

  @Test
  void testReaderBlocksWriter() throws InterruptedException {
    rwlock.acquireReadLock("resource", Duration.ofSeconds(10));

    var writerBlocked = new boolean[1];
    var thread = new Thread(() -> {
      boolean acquired = rwlock.acquireWriteLock("resource", Duration.ofMillis(100));
      writerBlocked[0] = !acquired;
    });

    thread.start();
    thread.join();

    assertThat(writerBlocked[0]).isTrue();

    rwlock.releaseReadLock("resource");
  }

  @Test
  void testWriterBlocksReaders() throws InterruptedException {
    rwlock.acquireWriteLock("resource", Duration.ofSeconds(10));

    var readerBlocked = new boolean[1];
    var thread = new Thread(() -> {
      boolean acquired = rwlock.acquireReadLock("resource", Duration.ofMillis(100));
      readerBlocked[0] = !acquired;
    });

    thread.start();
    thread.join();

    assertThat(readerBlocked[0]).isTrue();

    rwlock.releaseWriteLock("resource");
  }

  @Test
  void testWriterBlocksWriter() throws InterruptedException {
    rwlock.acquireWriteLock("resource", Duration.ofSeconds(10));

    var secondWriterBlocked = new boolean[1];
    var thread = new Thread(() -> {
      boolean acquired = rwlock.acquireWriteLock("resource", Duration.ofMillis(100));
      secondWriterBlocked[0] = !acquired;
    });

    thread.start();
    thread.join();

    assertThat(secondWriterBlocked[0]).isTrue();

    rwlock.releaseWriteLock("resource");
  }

  // ============ Fair Ordering ============

  @Test
  void testWriterPriority() throws InterruptedException {
    rwlock.acquireReadLock("resource", Duration.ofSeconds(10));

    // Start a writer thread waiting
    var writerAcquired = new boolean[1];
    var writerThread = new Thread(() -> {
      writerAcquired[0] = rwlock.acquireWriteLock("resource", Duration.ofSeconds(5));
    });
    writerThread.start();

    // Small delay to let writer start waiting
    Thread.sleep(100);

    // Now try another reader (should wait for writer)
    var readerAcquired = new boolean[1];
    var readerThread = new Thread(() -> {
      readerAcquired[0] = rwlock.acquireReadLock("resource", Duration.ofMillis(100));
    });
    readerThread.start();
    readerThread.join();

    // Reader should have timed out (writer has priority)
    assertThat(readerAcquired[0]).isFalse();

    // Release reader lock, allow writer to proceed
    rwlock.releaseReadLock("resource");
    writerThread.join();

    assertThat(writerAcquired[0]).isTrue();

    rwlock.releaseWriteLock("resource");
  }

  // ============ Reader Count ============

  @Test
  void testReaderCountIncreases() {
    assertThat(rwlock.getReaderCount("resource")).isZero();

    rwlock.acquireReadLock("resource", Duration.ofSeconds(5));
    assertThat(rwlock.getReaderCount("resource")).isEqualTo(1);

    rwlock.acquireReadLock("resource", Duration.ofSeconds(5));
    assertThat(rwlock.getReaderCount("resource")).isEqualTo(2);

    rwlock.releaseReadLock("resource");
    rwlock.releaseReadLock("resource");
  }

  // ============ Writer Query ============

  @Test
  void testGetWriter() {
    assertThat(rwlock.getWriter("resource")).isEmpty();

    rwlock.acquireWriteLock("resource", Duration.ofSeconds(5));
    var writer = rwlock.getWriter("resource");

    assertThat(writer).isPresent();

    rwlock.releaseWriteLock("resource");
    assertThat(rwlock.getWriter("resource")).isEmpty();
  }

  // ============ Force Release ============

  @Test
  void testForceRelease() {
    rwlock.acquireWriteLock("resource", Duration.ofSeconds(5));
    assertThat(rwlock.hasWriteLock("resource")).isTrue();

    rwlock.forceRelease("resource");
    assertThat(rwlock.hasWriteLock("resource")).isFalse();
  }

  @Test
  void testForceReleaseEmptyLock() {
    rwlock.forceRelease("resource");
    // Should not throw
    assertThat(rwlock.hasWriteLock("resource")).isFalse();
  }

  // ============ Flush All ============

  @Test
  void testFlushAll() {
    rwlock.acquireReadLock("lock1", Duration.ofSeconds(5));
    rwlock.acquireWriteLock("lock2", Duration.ofSeconds(5));

    rwlock.flushAll();

    assertThat(rwlock.hasReadLock("lock1")).isFalse();
    assertThat(rwlock.hasWriteLock("lock2")).isFalse();
  }

  // ============ Multiple Resources ============

  @Test
  void testMultipleResources() {
    rwlock.acquireReadLock("resource1", Duration.ofSeconds(5));
    rwlock.acquireWriteLock("resource2", Duration.ofSeconds(5));

    assertThat(rwlock.hasReadLock("resource1")).isTrue();
    assertThat(rwlock.hasWriteLock("resource2")).isTrue();
    assertThat(rwlock.hasWriteLock("resource1")).isFalse();
    assertThat(rwlock.hasReadLock("resource2")).isFalse();

    rwlock.releaseReadLock("resource1");
    rwlock.releaseWriteLock("resource2");
  }

  // ============ Concurrent Reader Acquisition ============

  @Test
  void testConcurrentReadersAcquire() throws InterruptedException {
    var acquired1 = new boolean[1];
    var acquired2 = new boolean[1];

    var thread1 = new Thread(() -> {
      acquired1[0] = rwlock.acquireReadLock("resource", Duration.ofSeconds(5));
    });

    var thread2 = new Thread(() -> {
      acquired2[0] = rwlock.acquireReadLock("resource", Duration.ofSeconds(5));
    });

    thread1.start();
    thread2.start();

    thread1.join();
    thread2.join();

    assertThat(acquired1[0]).isTrue();
    assertThat(acquired2[0]).isTrue();
    assertThat(rwlock.getReaderCount("resource")).isEqualTo(2);

    rwlock.releaseReadLock("resource");
    rwlock.releaseReadLock("resource");
  }

  // ============ Timeout Behavior ============

  @Test
  void testReadLockTimeout() {
    rwlock.acquireWriteLock("resource", Duration.ofSeconds(10));

    boolean acquired = rwlock.acquireReadLock("resource", Duration.ofMillis(100));

    assertThat(acquired).isFalse();

    rwlock.releaseWriteLock("resource");
  }

  @Test
  void testWriteLockTimeout() {
    rwlock.acquireReadLock("resource", Duration.ofSeconds(10));

    boolean acquired = rwlock.acquireWriteLock("resource", Duration.ofMillis(100));

    assertThat(acquired).isFalse();

    rwlock.releaseReadLock("resource");
  }
}
