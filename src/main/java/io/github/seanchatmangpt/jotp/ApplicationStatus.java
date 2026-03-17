package io.github.seanchatmangpt.jotp;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Detailed status of a single application in an ApplicationController.
 *
 * <p>Captures the health, timing, and error information for an application at a point in time.
 * This is the detailed counterpart to {@link ApplicationHealth}, providing fine-grained
 * diagnostics for monitoring and troubleshooting.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var controller = ApplicationController.defaultInstance();
 * for (ApplicationStatus status : controller.allStatuses()) {
 *     System.out.println(status.name() + " -> " + status.health());
 *     if (status.lastError().isPresent()) {
 *         System.out.println("  Error: " + status.lastError().get());
 *     }
 * }
 * }</pre>
 *
 * @param name the application name
 * @param health the current health status (UP, DEGRADED, DOWN)
 * @param startedAt the time the application started, or {@code null} if not running
 * @param stoppedAt an optional time the application stopped
 * @param messageCount the number of messages processed (lifecycle-dependent)
 * @param crashCount the number of times this application has crashed and restarted
 * @param lastError a human-readable error message, or empty if healthy
 * @see ApplicationHealth
 * @see ApplicationController#statusOf(String)
 * @see ApplicationController#allStatuses()
 * @since 1.0
 * @author JOTP Contributors
 */
public record ApplicationStatus(
    String name,
    ApplicationHealth health,
    Instant startedAt,
    Optional<Instant> stoppedAt,
    long messageCount,
    long crashCount,
    String lastError) {

  /**
   * Canonical constructor for ApplicationStatus.
   *
   * @param name the application name
   * @param health the health status
   * @param startedAt the startup time
   * @param stoppedAt the optional stop time
   * @param messageCount the message count
   * @param crashCount the crash count
   * @param lastError the last error message
   */
  public ApplicationStatus {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(health, "health must not be null");
    if (stoppedAt == null) {
      stoppedAt = Optional.empty();
    }
    Objects.requireNonNull(lastError, "lastError must not be null");
  }

  /**
   * Convenience: create an UP status for a running application.
   *
   * @param name the application name
   * @param startedAt the startup time
   * @return an ApplicationStatus with health=UP
   */
  public static ApplicationStatus up(String name, Instant startedAt) {
    return new ApplicationStatus(
        name, ApplicationHealth.UP, startedAt, Optional.empty(), 0L, 0L, "");
  }

  /**
   * Convenience: create a DOWN status for a stopped application.
   *
   * @param name the application name
   * @return an ApplicationStatus with health=DOWN
   */
  public static ApplicationStatus down(String name) {
    return new ApplicationStatus(
        name, ApplicationHealth.DOWN, null, Optional.empty(), 0L, 0L, "");
  }

  /**
   * Convenience: create a DEGRADED status with an error message.
   *
   * @param name the application name
   * @param error the error message
   * @return an ApplicationStatus with health=DEGRADED
   */
  public static ApplicationStatus degraded(String name, String error) {
    return new ApplicationStatus(
        name, ApplicationHealth.DEGRADED, null, Optional.empty(), 0L, 0L, error);
  }
}
