package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;

/**
 * Result of a single health check execution.
 */
public sealed interface HealthCheckResult permits
    HealthCheckResult.Pass,
    HealthCheckResult.Fail {

  record Pass(String checkName, Duration duration, long timestamp) implements HealthCheckResult {}

  record Fail(String checkName, String error, Duration duration, long timestamp)
      implements HealthCheckResult {
    public Fail(String checkName, String error, Duration duration) {
      this(checkName, error, duration, System.currentTimeMillis());
    }
  }

  default String checkName() {
    return switch (this) {
      case Pass(var name, _, _) -> name;
      case Fail(var name, _, _, _) -> name;
    };
  }

  default Duration duration() {
    return switch (this) {
      case Pass(_, var d, _) -> d;
      case Fail(_, _, var d, _) -> d;
    };
  }

  default boolean isPassed() {
    return this instanceof Pass;
  }
}
