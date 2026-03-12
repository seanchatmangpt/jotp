package io.github.seanchatmangpt.jotp.enterprise.health;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for a health check manager.
 *
 * <p>Immutable record specifying how health checks should be executed and how status transitions
 * should occur.
 */
public record HealthCheckConfig(
        String serviceName,
        List<HealthCheck> checks,
        Duration checkInterval,
        Duration timeout,
        int passThreshold,
        int failThreshold,
        boolean metricsEnabled) {

    /** Validate configuration constraints. */
    public HealthCheckConfig {
        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (timeout.compareTo(checkInterval) > 0) {
            throw new IllegalArgumentException("timeout must be <= checkInterval");
        }
        if (passThreshold <= 0) {
            throw new IllegalArgumentException("passThreshold must be > 0");
        }
        if (failThreshold <= 0) {
            throw new IllegalArgumentException("failThreshold must be > 0");
        }
        if (checks.isEmpty()) {
            throw new IllegalArgumentException("must register at least one check");
        }
    }

    /** Builder pattern for convenient construction. */
    public static Builder builder(String serviceName) {
        return new Builder(serviceName);
    }

    public static class Builder {
        private final String serviceName;
        private List<HealthCheck> checks = List.of();
        private Duration checkInterval = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(5);
        private int passThreshold = 1;
        private int failThreshold = 2;
        private boolean metricsEnabled = true;

        public Builder(String serviceName) {
            this.serviceName = serviceName;
        }

        public Builder checks(List<HealthCheck> checks) {
            this.checks = checks;
            return this;
        }

        public Builder checkInterval(Duration interval) {
            this.checkInterval = interval;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder passThreshold(int threshold) {
            this.passThreshold = threshold;
            return this;
        }

        public Builder failThreshold(int threshold) {
            this.failThreshold = threshold;
            return this;
        }

        public Builder metricsEnabled(boolean enabled) {
            this.metricsEnabled = enabled;
            return this;
        }

        public HealthCheckConfig build() {
            return new HealthCheckConfig(
                    serviceName,
                    checks,
                    checkInterval,
                    timeout,
                    passThreshold,
                    failThreshold,
                    metricsEnabled);
        }
    }
}
