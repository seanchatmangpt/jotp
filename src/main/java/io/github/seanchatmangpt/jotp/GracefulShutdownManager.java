package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages graceful shutdown by tracking in-flight messages and draining queues.
 *
 * <p>Mirroring Erlang/OTP's graceful shutdown approach, this manager:
 * <ul>
 *   <li>Tracks messages sent but not yet handled (in-flight)
 *   <li>On shutdown signal: stops accepting new messages
 *   <li>Waits up to a timeout for all in-flight messages to complete
 *   <li>Logs timeout if in-flight messages remain
 * </ul>
 */
public final class GracefulShutdownManager {
  private final ConcurrentHashMap<String, AtomicLong> inFlightPerProcess =
      new ConcurrentHashMap<>();
  private volatile boolean acceptingMessages = true;
  private volatile Instant shutdownStartTime = null;

  private GracefulShutdownManager() {}

  /**
   * Create a new graceful shutdown manager.
   *
   * @return a new manager instance
   */
  public static GracefulShutdownManager create() {
    return new GracefulShutdownManager();
  }

  /**
   * Record that a message was sent to a process.
   *
   * @param processName the process identifier
   */
  public void onMessageSent(String processName) {
    if (!acceptingMessages) {
      throw new IllegalStateException("Shutdown in progress: no new messages accepted");
    }
    inFlightPerProcess.computeIfAbsent(processName, _ -> new AtomicLong(0)).incrementAndGet();
  }

  /**
   * Record that a message was handled by a process.
   *
   * @param processName the process identifier
   */
  public void onMessageHandled(String processName) {
    AtomicLong counter = inFlightPerProcess.get(processName);
    if (counter != null) {
      counter.decrementAndGet();
    }
  }

  /**
   * Get the total number of in-flight messages across all processes.
   *
   * @return number of messages still being processed
   */
  public long getInFlightCount() {
    return inFlightPerProcess.values().stream().mapToLong(AtomicLong::get).sum();
  }

  /**
   * Get the number of in-flight messages for a specific process.
   *
   * @param processName the process identifier
   * @return number of in-flight messages for that process
   */
  public long getInFlightCount(String processName) {
    AtomicLong counter = inFlightPerProcess.get(processName);
    return counter != null ? counter.get() : 0;
  }

  /**
   * Drain all in-flight messages with a timeout.
   *
   * <p>Stops accepting new messages and waits up to {@code timeout} for all in-flight messages to
   * complete. If timeout is exceeded, returns with a count of remaining in-flight messages.
   *
   * @param timeout maximum time to wait for in-flight messages
   * @return true if all messages drained within timeout, false if timeout exceeded
   */
  public boolean drain(Duration timeout) {
    acceptingMessages = false;
    shutdownStartTime = Instant.now();

    long timeoutMillis = timeout.toMillis();
    long startTime = System.currentTimeMillis();

    while (true) {
      long inFlight = getInFlightCount();
      if (inFlight == 0) {
        return true;
      }

      long elapsed = System.currentTimeMillis() - startTime;
      if (elapsed >= timeoutMillis) {
        System.err.println(
            "[GracefulShutdown] Timeout after "
                + elapsed
                + "ms with "
                + inFlight
                + " in-flight messages");
        return false;
      }

      try {
        Thread.sleep(Math.min(100, timeoutMillis - elapsed));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /**
   * Check if shutdown is in progress.
   *
   * @return true if accepting no new messages
   */
  public boolean isShuttingDown() {
    return !acceptingMessages;
  }

  /** Reset the manager to initial state (for testing). */
  public void reset() {
    acceptingMessages = true;
    shutdownStartTime = null;
    inFlightPerProcess.clear();
  }
}
