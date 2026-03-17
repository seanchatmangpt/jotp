package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps a Supervisor to inject deterministic faults for chaos testing.
 *
 * <p>Allows crashes to be scheduled at specific message boundaries or after elapsed time,
 * without introducing flakiness. Useful for verifying recovery behavior.
 */
public final class FaultInjectionSupervisor {
  /** Crash trigger: at a specific message sequence. */
  public record CrashAtSeq(long messageSeq) {}

  /** Crash trigger: after a duration. */
  public record CrashAfterDuration(Duration duration) {}

  private final Supervisor delegate;
  private final ConcurrentHashMap<String, FaultScenario> faultScenarios =
      new ConcurrentHashMap<>();

  private static final class FaultScenario {
    final AtomicLong messageCount = new AtomicLong(0);
    final Instant createdAt = Instant.now();
    volatile CrashAtSeq crashAtSeq = null;
    volatile CrashAfterDuration crashAfterDuration = null;
    volatile boolean crashed = false;

    boolean shouldCrash() {
      if (crashed) return false;

      if (crashAtSeq != null) {
        if (messageCount.incrementAndGet() >= crashAtSeq.messageSeq()) {
          crashed = true;
          return true;
        }
      }

      if (crashAfterDuration != null) {
        Duration elapsed = Duration.between(createdAt, Instant.now());
        if (elapsed.compareTo(crashAfterDuration.duration()) >= 0) {
          crashed = true;
          return true;
        }
      }

      return false;
    }
  }

  private FaultInjectionSupervisor(Supervisor delegate) {
    this.delegate = delegate;
  }

  /**
   * Wrap a supervisor with fault injection capability.
   *
   * @param supervisor the supervisor to wrap
   * @return a fault-injecting wrapper
   */
  public static FaultInjectionSupervisor wrap(Supervisor supervisor) {
    return new FaultInjectionSupervisor(supervisor);
  }

  /**
   * Schedule a crash at a specific message sequence number.
   *
   * @param processName the child process name
   * @param messageSeq the message number (0-indexed) that triggers the crash
   */
  public void crashAt(String processName, long messageSeq) {
    FaultScenario scenario =
        faultScenarios.computeIfAbsent(processName, _ -> new FaultScenario());
    scenario.crashAtSeq = new CrashAtSeq(messageSeq);
  }

  /**
   * Schedule a crash after a duration.
   *
   * @param processName the child process name
   * @param duration the time after child creation to inject the crash
   */
  public void crashAfter(String processName, Duration duration) {
    FaultScenario scenario =
        faultScenarios.computeIfAbsent(processName, _ -> new FaultScenario());
    scenario.crashAfterDuration = new CrashAfterDuration(duration);
  }

  /**
   * Get the delegate supervisor.
   *
   * @return the wrapped supervisor
   */
  public Supervisor getDelegate() {
    return delegate;
  }

  /**
   * Check if a process should crash right now (for internal use by crash simulation).
   *
   * @param processName the child process name
   * @return true if a fault should be injected
   */
  public boolean shouldInjectFault(String processName) {
    FaultScenario scenario = faultScenarios.get(processName);
    return scenario != null && scenario.shouldCrash();
  }

  /**
   * Reset all fault scenarios (for test teardown).
   */
  public void reset() {
    faultScenarios.clear();
  }

  /**
   * Check if a process was crashed.
   *
   * @param processName the process name
   * @return true if a crash was triggered
   */
  public boolean wasCrashed(String processName) {
    FaultScenario scenario = faultScenarios.get(processName);
    return scenario != null && scenario.crashed;
  }
}
