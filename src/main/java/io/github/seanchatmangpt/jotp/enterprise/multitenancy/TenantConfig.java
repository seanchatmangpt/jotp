package io.github.seanchatmangpt.jotp.enterprise.multitenancy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a tenant in multi-tenant supervisor.
 *
 * Immutable record specifying resource limits, restart policies, and metadata.
 */
public record TenantConfig(
    String tenantId,
    int maxConcurrentProcesses,
    int maxRestarts,
    Duration window,
    TenantIsolationPolicy strategy,
    Map<String, String> metadata) {

  /** Validate configuration constraints. */
  public TenantConfig {
    if (tenantId == null || tenantId.isEmpty()) {
      throw new IllegalArgumentException("tenantId must not be empty");
    }
    if (maxConcurrentProcesses <= 0) {
      throw new IllegalArgumentException("maxConcurrentProcesses must be > 0");
    }
    if (maxRestarts <= 0) {
      throw new IllegalArgumentException("maxRestarts must be > 0");
    }
    if (window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("window must be positive");
    }
  }

  /** Builder pattern for convenient construction. */
  public static Builder builder(String tenantId) {
    return new Builder(tenantId);
  }

  public static class Builder {
    private final String tenantId;
    private int maxConcurrentProcesses = 1000;
    private int maxRestarts = 5;
    private Duration window = Duration.ofMinutes(1);
    private TenantIsolationPolicy strategy = new TenantIsolationPolicy.Strict();
    private Map<String, String> metadata = new HashMap<>();

    public Builder(String tenantId) {
      this.tenantId = tenantId;
    }

    public Builder maxConcurrentProcesses(int max) {
      this.maxConcurrentProcesses = max;
      return this;
    }

    public Builder maxRestarts(int restarts) {
      this.maxRestarts = restarts;
      return this;
    }

    public Builder window(Duration duration) {
      this.window = duration;
      return this;
    }

    public Builder strategy(TenantIsolationPolicy policy) {
      this.strategy = policy;
      return this;
    }

    public Builder metadata(Map<String, String> map) {
      this.metadata = new HashMap<>(map);
      return this;
    }

    public Builder addMetadata(String key, String value) {
      this.metadata.put(key, value);
      return this;
    }

    public TenantConfig build() {
      return new TenantConfig(
          tenantId,
          maxConcurrentProcesses,
          maxRestarts,
          window,
          strategy,
          new HashMap<>(metadata));
    }
  }
}
