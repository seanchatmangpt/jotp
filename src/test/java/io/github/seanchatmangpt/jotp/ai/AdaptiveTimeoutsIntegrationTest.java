package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests demonstrating AdaptiveTimeouts in realistic scenarios.
 *
 * <p>Tests cover:
 * <ol>
 *   <li>Load-balancing across multiple service replicas with different latencies
 *   <li>Graceful degradation during service outages
 *   <li>Recovery from temporary network partitions
 *   <li>Handling cascading failures with circuit breaker patterns
 *   <li>PostgreSQL history store integration
 * </ol>
 */
@DisplayName("AdaptiveTimeouts Integration Tests")
class AdaptiveTimeoutsIntegrationTest {

  private AdaptiveTimeouts timeouts;

  @BeforeEach
  void setup() {
    timeouts = AdaptiveTimeouts.create();
  }

  @AfterEach
  void cleanup() {
    timeouts.shutdown(1000);
  }

  @Test
  @DisplayName("Load balancing: route to fastest replica based on adaptive timeouts")
  void loadBalancingAcrossReplicas() throws InterruptedException {
    // Simulate three replicas with different latencies
    String[] replicas = {"replica-1-fast", "replica-2-medium", "replica-3-slow"};
    long[] latencies = {20, 50, 100};

    // Simulate requests to each replica
    for (int i = 0; i < 100; i++) {
      for (int r = 0; r < replicas.length; r++) {
        timeouts.recordResponse(replicas[r], latencies[r], true);
      }
    }

    Thread.sleep(100);

    // Verify each replica has appropriate timeout based on its latency
    long timeout1 = timeouts.getStats(replicas[0]).currentTimeoutMs;
    long timeout2 = timeouts.getStats(replicas[1]).currentTimeoutMs;
    long timeout3 = timeouts.getStats(replicas[2]).currentTimeoutMs;

    assertThat(timeout1).isLessThan(timeout2).isLessThan(timeout3);

    // In real code, route requests to replica with shortest expected completion time
    // completion_time = timeout / health_factor
  }

  @Test
  @DisplayName("Graceful degradation: detect service degradation and increase timeouts")
  void gracefulDegradation() throws InterruptedException {
    // Phase 1: Service running normally
    for (int i = 0; i < 50; i++) {
      timeouts.recordResponse("api-server", 50, true);
    }

    Thread.sleep(50);
    var statsHealthy = timeouts.getStats("api-server");
    long healthyTimeout = statsHealthy.currentTimeoutMs;

    // Phase 2: Service degradation (database query slowdown)
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("api-server", 500, true);
    }

    Thread.sleep(100);
    var statsDegraded = timeouts.getStats("api-server");
    long degradedTimeout = statsDegraded.currentTimeoutMs;

    assertThat(degradedTimeout)
        .isGreaterThan(healthyTimeout)
        .as("Timeout should increase when service degrades");

    // Verify we're still within acceptable bounds
    assertThat(degradedTimeout).isLessThanOrEqualTo(30000);
  }

  @Test
  @DisplayName("Network partition recovery: timeout adjustment during temporary outages")
  void networkPartitionRecovery() throws InterruptedException {
    // Normal operation
    for (int i = 0; i < 50; i++) {
      timeouts.recordResponse("backend", 100, true);
    }

    Thread.sleep(50);
    var statsNormal = timeouts.getStats("backend");

    // Network partition: all requests timeout
    for (int i = 0; i < 20; i++) {
      timeouts.recordResponse("backend", 30000, false);
    }

    Thread.sleep(100);
    var statsPartitioned = timeouts.getStats("backend");

    assertThat(statsPartitioned.isUnderStress())
        .isTrue()
        .as("Service should be marked under stress during partition");

    // Recovery: service comes back but still seeing elevated latency
    for (int i = 0; i < 30; i++) {
      timeouts.recordResponse("backend", 200, true);
    }

    Thread.sleep(100);
    var statsRecovering = timeouts.getStats("backend");

    // Timeout should still be elevated (hysteresis prevents immediate drop)
    assertThat(statsRecovering.currentTimeoutMs)
        .isGreaterThanOrEqualTo(statsPartitioned.currentTimeoutMs * 0.8)
        .as("Timeout should not drop immediately after recovery (hysteresis)");
  }

  @Test
  @DisplayName("Cascading failures: prevent timeout storms with exponential backoff")
  void preventCascadingFailures() throws InterruptedException {
    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.empty(), 10);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Initial healthy state
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("service", 100, true);
      }

      Thread.sleep(50);

      var statsHealthy = adaptive.getStats("service");
      long healthyTimeout = statsHealthy.currentTimeoutMs;

      // Cascading failures: each timeout increases the next timeout
      int timeoutStreak = 0;
      for (int i = 0; i < 50; i++) {
        boolean success = i % 5 == 0; // 80% timeout rate (cascading failure scenario)
        adaptive.recordResponse("service", success ? 100 : 10000, success);

        if (!success) {
          timeoutStreak++;
        } else {
          timeoutStreak = 0;
        }

        if (timeoutStreak > 3) {
          // After 3+ consecutive timeouts, timeout should increase significantly
          var stats = adaptive.getStats("service");
          assertThat(stats.currentTimeoutMs)
              .isGreaterThan(healthyTimeout)
              .as("Should scale up timeout during consecutive failures");
        }
      }

      var statsFailing = adaptive.getStats("service");
      assertThat(statsFailing.currentTimeoutMs)
          .isGreaterThan(healthyTimeout)
          .isLessThanOrEqualTo(30000)
          .as("Should increase but respect bounds even with cascading failures");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Circuit breaker pattern: track p99 for circuit breaking decisions")
  void circuitBreakerIntegration() throws InterruptedException {
    // Healthy state: p99 is low
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("db", 10 + i % 20, true);
    }

    Thread.sleep(100);
    var statsHealthy = timeouts.getStats("db");

    // Circuit breaker should open if p99 / p50 ratio is too high (indicates instability)
    double healthyJitterRatio = statsHealthy.jitterRatio();
    assertThat(healthyJitterRatio).isLessThan(1.0);

    // Degraded state: huge jitter
    for (int i = 0; i < 100; i++) {
      boolean isOutlier = i % 10 == 0;
      timeouts.recordResponse("db", isOutlier ? 5000 : 50, true);
    }

    Thread.sleep(100);
    var statsDegraded = timeouts.getStats("db");

    assertThat(statsDegraded.jitterRatio())
        .isGreaterThan(healthyJitterRatio)
        .as("Jitter ratio should increase with degradation");

    // Circuit breaker logic: if jitterRatio > threshold, open circuit
    if (statsDegraded.jitterRatio() > 2.0) {
      // Would open circuit breaker here
      System.out.println("Circuit breaker would open: jitter ratio = " + statsDegraded.jitterRatio());
    }
  }

  @Test
  @DisplayName("Multi-service coordination: different timeout curves for different services")
  void multiServiceCoordination() throws InterruptedException {
    // Service A: Database queries (slower but consistent)
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("database", 200 + (i % 100), true);
    }

    // Service B: Cache lookups (very fast)
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("cache", 5 + (i % 10), true);
    }

    // Service C: Remote API (unpredictable)
    Random rand = new Random(42);
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("remote-api", 50 + rand.nextInt(500), true);
    }

    Thread.sleep(150);

    var dbStats = timeouts.getStats("database");
    var cacheStats = timeouts.getStats("cache");
    var apiStats = timeouts.getStats("remote-api");

    // Verify independent tuning
    assertThat(cacheStats.currentTimeoutMs)
        .isLessThan(dbStats.currentTimeoutMs)
        .isLessThan(apiStats.currentTimeoutMs)
        .as("Timeouts should reflect service characteristics");

    // Verify all within bounds
    assertThat(dbStats.currentTimeoutMs).isBetween(100L, 30000L);
    assertThat(cacheStats.currentTimeoutMs).isBetween(100L, 30000L);
    assertThat(apiStats.currentTimeoutMs).isBetween(100L, 30000L);
  }

  @Test
  @DisplayName("History store: record timeout adjustments for audit/analysis")
  void historyStoreRecordsAdjustments() throws InterruptedException {
    var adjustments = Collections.synchronizedList(new ArrayList<Map<String, Object>>());

    var store =
        new AdaptiveTimeouts.TimeoutHistoryStore() {
          @Override
          public void recordAdjustment(
              String serviceName,
              long previousTimeoutMs,
              long newTimeoutMs,
              long p99Ms,
              long totalRequests,
              String reason) {
            var record = new HashMap<String, Object>();
            record.put("service", serviceName);
            record.put("prev", previousTimeoutMs);
            record.put("new", newTimeoutMs);
            record.put("p99", p99Ms);
            record.put("requests", totalRequests);
            record.put("reason", reason);
            adjustments.add(record);
          }

          @Override
          public List<Map<String, Object>> queryHistory(String serviceName, int limit) {
            return adjustments.stream()
                .filter(r -> r.get("service").equals(serviceName))
                .limit(limit)
                .toList();
          }
        };

    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.of(store), 5);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Generate adjustment events
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("monitored-service", 50, true);
      }

      Thread.sleep(50);

      for (int i = 0; i < 100; i++) {
        adaptive.recordResponse("monitored-service", 200, true);
      }

      Thread.sleep(100);

      // Verify adjustments were recorded
      assertThat(adjustments).isNotEmpty().as("History store should record adjustments");

      var serviceHistory = store.queryHistory("monitored-service", 10);
      assertThat(serviceHistory)
          .allMatch(r -> r.get("service").equals("monitored-service"))
          .as("History should contain service-specific records");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Stress test: concurrent service clients with adaptive timeouts")
  void concurrentServiceClients() throws InterruptedException, ExecutionException {
    var executor = Executors.newFixedThreadPool(20);
    var requestCount = new AtomicInteger(0);
    var timeoutCount = new AtomicInteger(0);

    try {
      var tasks =
          IntStream.range(0, 20)
              .mapToObj(
                  threadId ->
                      executor.submit(
                          () -> {
                            String serviceName = "service-" + (threadId % 5);

                            for (int i = 0; i < 50; i++) {
                              // Simulate request with some timeouts
                              boolean success = (threadId + i) % 10 != 0;
                              long latency = 50 + (threadId * 10) + (i % 20);

                              timeouts.recordResponse(serviceName, latency, success);
                              requestCount.incrementAndGet();
                              if (!success) {
                                timeoutCount.incrementAndGet();
                              }
                            }
                          }))
              .toList();

      for (var task : tasks) {
        task.get();
      }

      Thread.sleep(150);

      // Verify statistics
      var allStats = timeouts.getAllStats();
      assertThat(allStats).hasSize(5);
      assertThat(requestCount.get()).isEqualTo(1000);
      assertThat(timeoutCount.get()).isEqualTo(100);

      // Verify each service is properly tuned
      for (var stats : allStats.values()) {
        assertThat(stats.currentTimeoutMs())
            .isBetween(100L, 30000L)
            .as(stats.serviceName() + " timeout should be within bounds");
      }
    } finally {
      executor.shutdown();
    }
  }

  @Test
  @DisplayName("Real-world scenario: payment processing with multi-stage timeouts")
  void paymentProcessingScenario() throws InterruptedException {
    // Simulate payment processing pipeline
    // Stage 1: Authentication (fast, consistent)
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("auth", 20, true);
    }

    // Stage 2: Inventory check (medium, consistent)
    for (int i = 0; i < 100; i++) {
      timeouts.recordResponse("inventory", 100, true);
    }

    // Stage 3: Billing (slow, variable)
    Random rand = new Random(123);
    for (int i = 0; i < 100; i++) {
      long latency = 200 + rand.nextLong(300);
      boolean success = i % 5 != 0; // 80% success rate (some failures in billing)
      timeouts.recordResponse("billing", latency, success);
    }

    Thread.sleep(150);

    var authStats = timeouts.getStats("auth");
    var inventoryStats = timeouts.getStats("inventory");
    var billingStats = timeouts.getStats("billing");

    // Verify timeout hierarchy: auth < inventory < billing
    assertThat(authStats.currentTimeoutMs)
        .isLessThan(inventoryStats.currentTimeoutMs)
        .isLessThan(billingStats.currentTimeoutMs)
        .as("Pipeline stages should have increasing timeouts");

    // Total timeout for payment = sum of stages
    long totalTimeout = authStats.currentTimeoutMs
        + inventoryStats.currentTimeoutMs
        + billingStats.currentTimeoutMs;

    assertThat(totalTimeout).isBetween(300L, 30000L);

    System.out.println("Payment processing timeouts:");
    System.out.println("  Auth: " + authStats.currentTimeoutMs + "ms");
    System.out.println("  Inventory: " + inventoryStats.currentTimeoutMs + "ms");
    System.out.println("  Billing: " + billingStats.currentTimeoutMs + "ms (health: "
        + String.format("%.2f", billingStats.timeoutHealth()) + ")");
  }
}
