package io.github.seanchatmangpt.jotp;

/**
 * Health status of an application or application controller.
 *
 * <p>In Erlang/OTP systems, health monitoring is crucial for detecting failed applications. This
 * enum models the health state of applications in a JOTP controller:
 *
 * <ul>
 *   <li>{@link #UP} — all expected applications are running normally
 *   <li>{@link #DEGRADED} — some applications are running, but not all
 *   <li>{@link #DOWN} — no applications are running
 * </ul>
 *
 * <p><strong>Health check patterns:</strong>
 *
 * <pre>{@code
 * var controller = ApplicationController.defaultInstance();
 * switch (controller.health()) {
 *     case UP -> System.out.println("All systems operational");
 *     case DEGRADED -> System.out.println("Some services unavailable");
 *     case DOWN -> System.out.println("Critical failure - restart required");
 * }
 * }</pre>
 *
 * @see ApplicationStatus
 * @see ApplicationController#health()
 * @since 1.0
 * @author JOTP Contributors
 */
public enum ApplicationHealth {
  /** All expected applications are running. */
  UP,

  /** Some applications are running, but not all expected ones. */
  DEGRADED,

  /** No applications are running. */
  DOWN
}
