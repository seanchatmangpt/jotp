package io.github.seanchatmangpt.jotp.coordination;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Distributed mutual exclusion lock interface following Erlang/OTP coordination patterns.
 *
 * <p>Joe Armstrong: "Distributed locks are the classic coordination primitive. We need to prevent
 * multiple nodes from accessing the same resource simultaneously while handling node failures,
 * deadlocks, and ensuring fairness."
 *
 * <p>This interface abstracts over multiple backends (Redis, PostgreSQL) to provide:
 *
 * <ul>
 *   <li><strong>Mutual Exclusion:</strong> Only one process holds the lock at a time
 *   <li><strong>Deadlock Prevention:</strong> Timeout-based automatic lock release
 *   <li><strong>Fair Queuing:</strong> First-come-first-served waiter queue
 *   <li><strong>Monitoring:</strong> Process crash detection, automatic cleanup
 *   <li><strong>Diagnostics:</strong> Lock statistics, wait times, contention metrics
 * </ul>
 *
 * <p><strong>Basic Usage (blocking acquire):</strong>
 *
 * <pre>{@code
 * var lock = new RedisDistributedLock(redisClient, nodeId);
 * try {
 *     lock.acquireLock("critical-section", Duration.ofSeconds(30));
 *     // Critical section
 * } finally {
 *     lock.releaseLock("critical-section");
 * }
 * }</pre>
 *
 * <p><strong>Non-blocking acquire:</strong>
 *
 * <pre>{@code
 * if (lock.tryLock("resource", Duration.ofSeconds(5))) {
 *     try {
 *         // Critical section
 *     } finally {
 *         lock.releaseLock("resource");
 *     }
 * } else {
 *     // Lock already held
 * }
 * }</pre>
 *
 * <p><strong>With process monitoring:</strong>
 *
 * <pre>{@code
 * var lock = new RedisDistributedLock(redisClient, nodeId);
 * var procRef = supervisor.supervise("locked-worker", state, (s, m) -> {
 *     lock.acquireLock("db-write", Duration.ofMinutes(1));
 *     try {
 *         return handler.process(s, m);
 *     } finally {
 *         lock.releaseLock("db-write");
 *     }
 * });
 * // On crash: lock auto-released via process lifecycle monitoring
 * }</pre>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Virtual Threads:</strong> Lock acquisition can park the calling virtual thread
 *       without consuming platform threads
 *   <li><strong>Records:</strong> {@link LockStats} is a record carrying immutable statistics
 *   <li><strong>Sealed Types:</strong> {@link AcquireResult} variants for type-safe result handling
 *   <li><strong>Pattern Matching:</strong> Callers can pattern-match on {@link AcquireResult}
 * </ul>
 */
public interface DistributedLock {

  /**
   * Represents the result of a lock acquisition attempt.
   *
   * <p>Use sealed pattern matching to exhaustively handle outcomes:
   *
   * <pre>{@code
   * var result = lock.acquire("resource", Duration.ofSeconds(5));
   * switch (result) {
   *     case AcquireResult.Acquired -> System.out.println("Lock acquired");
   *     case AcquireResult.TimedOut(var waited) -> System.out.println("Waited " + waited);
   *     case AcquireResult.Deadlock(var holder) -> System.out.println("Holder: " + holder);
   * }
   * }</pre>
   */
  sealed interface AcquireResult {
    /**
     * Lock successfully acquired and held by this process.
     */
    record Acquired() implements AcquireResult {}

    /**
     * Lock acquisition timed out — the lock was held by another process.
     *
     * @param waitedFor duration spent waiting for the lock
     */
    record TimedOut(Duration waitedFor) implements AcquireResult {}

    /**
     * Deadlock detected — this process already owns the lock (or a cycle was detected).
     *
     * @param currentHolder the node/process ID holding the lock
     */
    record Deadlock(String currentHolder) implements AcquireResult {}

    /**
     * Lock acquisition failed for an unexpected reason.
     *
     * @param reason the error description
     */
    record Failed(String reason) implements AcquireResult {}
  }

  /**
   * Statistics about a lock's state and contention.
   *
   * <p>Used for monitoring and diagnostics:
   *
   * <pre>{@code
   * var stats = lock.getStats("db-write");
   * System.out.printf("Lock held by: %s (acquired at %s)%n", stats.holder(), stats.acquiredAt());
   * System.out.printf("Queue length: %d, avg wait: %s%n", stats.queueLength(), stats.avgWaitTime());
   * }</pre>
   *
   * @param lockName the name of the lock
   * @param isLocked whether the lock is currently held
   * @param holder the node ID holding the lock (empty if unlocked)
   * @param acquisitionTime when the lock was acquired (empty if unlocked)
   * @param queueLength number of processes waiting for this lock
   * @param waiters list of waiting process IDs (in queue order)
   * @param totalAcquires cumulative acquire attempts since creation
   * @param avgWaitTime average wait time across all acquisitions
   * @param contentionCount number of processes that had to wait
   */
  record LockStats(
      String lockName,
      boolean isLocked,
      Optional<String> holder,
      Optional<Long> acquisitionTime,
      int queueLength,
      List<String> waiters,
      long totalAcquires,
      Optional<Duration> avgWaitTime,
      long contentionCount) {}

  /**
   * Blocking lock acquisition with timeout.
   *
   * <p>Blocks the current virtual thread until:
   *
   * <ul>
   *   <li>The lock is acquired (success)
   *   <li>The timeout expires (timeout)
   *   <li>A deadlock is detected (deadlock)
   *   <li>An error occurs (failed)
   * </ul>
   *
   * <p><strong>This method MUST be called in a try-finally block:</strong>
   *
   * <pre>{@code
   * lock.acquireLock("resource", Duration.ofSeconds(30));
   * try {
   *     // Critical section
   * } finally {
   *     lock.releaseLock("resource");
   * }
   * }</pre>
   *
   * <p>Deadlock detection: if the same process tries to acquire the same lock twice, this returns
   * {@link AcquireResult.Deadlock}. In a distributed setting, deadlocks are detected via:
   *
   * <ul>
   *   <li>Timeout-based detection: if a waiter's holder crashes, the lock is released
   *   <li>Graph-based detection: if backends track lock dependency graphs
   * </ul>
   *
   * @param lockName the name of the lock (e.g., "db-write", "session-cache")
   * @param timeout how long to wait before giving up
   * @return an {@link AcquireResult} indicating success, timeout, deadlock, or failure
   */
  AcquireResult acquireLock(String lockName, Duration timeout);

  /**
   * Non-blocking lock acquisition with immediate return.
   *
   * <p>Attempts to acquire the lock and returns immediately:
   *
   * <ul>
   *   <li>{@code true} — lock acquired, call {@link #releaseLock} in finally block
   *   <li>{@code false} — lock held by another process, not added to queue
   * </ul>
   *
   * <p>Unlike {@link #acquireLock}, this does not wait or add the process to the waiter queue.
   *
   * @param lockName the name of the lock
   * @param timeout unused (for API compatibility), actual behavior is non-blocking
   * @return true if lock acquired, false if held by another process
   */
  boolean tryLock(String lockName, Duration timeout);

  /**
   * Release a lock held by this process.
   *
   * <p><strong>Must be called for every successful {@link #acquireLock} or {@link #tryLock}:</strong>
   *
   * <pre>{@code
   * if (lock.tryLock("resource", Duration.ZERO)) {
   *     try {
   *         // Critical section
   *     } finally {
   *         lock.releaseLock("resource");  // Always call in finally
   *     }
   * }
   * }</pre>
   *
   * <p>When this process releases the lock:
   *
   * <ol>
   *   <li>The lock is marked as unoccupied
   *   <li>The first waiter (if any) is notified via its watch/callback
   *   <li>If no watchers exist, the lock transitions to unlocked state
   * </ol>
   *
   * <p>If called by a process that doesn't own the lock, this is a no-op (some backends may log
   * a warning).
   *
   * @param lockName the name of the lock to release
   */
  void releaseLock(String lockName);

  /**
   * Get the list of process IDs waiting for a lock, in queue order.
   *
   * <p>Returns a snapshot of the waiter queue at the moment of call — the queue may change
   * immediately after.
   *
   * @param lockName the name of the lock
   * @return list of waiting process IDs (empty if no waiters or lock doesn't exist)
   */
  List<String> getWaiters(String lockName);

  /**
   * Get statistics about a lock's current state.
   *
   * <p>Returns a snapshot of the lock state, useful for monitoring dashboards and diagnostics.
   *
   * @param lockName the name of the lock
   * @return a {@link LockStats} record with detailed information
   */
  LockStats getStats(String lockName);

  /**
   * Check if a lock is currently held.
   *
   * <p>Returns true if:
   *
   * <ul>
   *   <li>The lock exists AND
   *   <li>A process currently holds it
   * </ul>
   *
   * <p>Returns false if the lock is unlocked or doesn't exist.
   *
   * @param lockName the name of the lock
   * @return true if the lock is currently held by some process
   */
  boolean isLocked(String lockName);

  /**
   * Force-release a lock, regardless of who owns it.
   *
   * <p><strong>Caution:</strong> This is an administrative operation for disaster recovery. Use
   * only when:
   *
   * <ul>
   *   <li>A lock holder has crashed and the lock is stuck
   *   <li>Manual intervention is necessary to restore cluster health
   * </ul>
   *
   * <p>Normal operation uses timeouts to eventually release stuck locks.
   *
   * @param lockName the name of the lock to force-release
   * @return true if the lock was held and released, false if it wasn't locked
   */
  boolean forceRelease(String lockName);

  /**
   * Enable/disable deadlock detection for a specific lock.
   *
   * <p>Deadlock detection uses cycle detection in the waiter graph. For high-contention locks,
   * this may add overhead — disable if cycles are not possible in your application.
   *
   * @param lockName the name of the lock
   * @param enabled true to enable deadlock detection, false to disable
   */
  void setDeadlockDetectionEnabled(String lockName, boolean enabled);

  /**
   * Register a callback to be invoked when the lock holder crashes or is suspected dead.
   *
   * <p>Useful for fast failover: instead of waiting for the timeout, the callback is invoked
   * immediately when the process is detected as dead.
   *
   * @param lockName the name of the lock
   * @param onHolderCrash callback to invoke when holder is suspected dead
   */
  void onHolderCrash(String lockName, Runnable onHolderCrash);

  /**
   * Flush all locks (for testing only).
   *
   * <p><strong>Warning:</strong> This clears all locks and waiters. Use only in test teardown.
   */
  void flushAll();

  /**
   * Gracefully shut down the lock manager, releasing all locks.
   *
   * <p>Blocks until all locks are released and background tasks are terminated.
   */
  void shutdown();
}
