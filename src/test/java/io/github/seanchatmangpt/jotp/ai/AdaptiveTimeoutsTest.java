package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@DisplayName("AdaptiveTimeouts: Dynamic timeout adjustment based on response distributions")
class AdaptiveTimeoutsTest {

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
  @DisplayName("Initial timeout should be minimum timeout")
  void initialTimeout() {
    Duration timeout = timeouts.getTimeout("unknown-service");
    assertThat(timeout).isEqualTo(Duration.ofMillis(100));
  }

  @Test
  @DisplayName("Should record response times and compute percentiles")
  void recordResponseTimes() {
    // Simulate responses: 10ms, 20ms, 30ms, ..., 100ms (even distribution)
    for (int i = 1; i <= 100; i++) {
      timeouts.recordResponse("api", i * 10L, true);
    }

    // Give stats update a chance to run
    Thread.yield();

    var stats = timeouts.getStats("api");
    assertThat(stats.p50Ms).isGreaterThan(0);
    assertThat(stats.p99Ms).isGreaterThan(stats.p50Ms);
    assertThat(stats.p999Ms).isGreaterThanOrEqualTo(stats.p99Ms);
    assertThat(stats.totalRequests).isEqualTo(100);
  }

  @Test
  @DisplayName("Timeout should increase after observing high latency")
  void timeoutIncreasesWithLatency() {
    // Start with fast responses
    for (int i = 0; i < 50; i++) {
      timeouts.recordResponse("slow-service", 20, true);
    }

    Duration fastTimeout = timeouts.getTimeout("slow-service");

    // Now introduce latency spike
    for (int i = 0; i < 50; i++) {
      timeouts.recordResponse("slow-service", 500, true);
    }

    // Wait for async update
    sleep(100);

    Duration adjustedTimeout = timeouts.getTimeout("slow-service");
    assertThat(adjustedTimeout.toMillis())
        .isGreaterThanOrEqualTo(fastTimeout.toMillis())
        .isLessThanOrEqualTo(30000); // max timeout
  }

  @Test
  @DisplayName("Timeout should follow algorithm: p999 + (p999 - p50) * buffer_factor")
  void timeoutFollowsAlgorithm() {
    // Create a specific distribution: 0-400ms uniform
    for (int ms = 0; ms <= 400; ms += 10) {
      for (int j = 0; j < 10; j++) {
        timeouts.recordResponse("math-check", ms, true);
      }
    }

    sleep(100); // Wait for update

    var stats = timeouts.getStats("math-check");
    assertThat(stats.totalRequests).isEqualTo(410);

    // Recommended timeout should be approximately p999 + (p999 - p50) * 0.5
    long expected = stats.p999Ms + (long) ((stats.p999Ms - stats.p50Ms) * 0.5);
    long buffer = 50; // Account for rounding and smoothing
    assertThat(stats.recommendedTimeoutMs).isCloseTo(expected, byLessThan(buffer));
  }

  @Test
  @DisplayName("Should prevent timeout storms with exponential backoff")
  void preventTimeoutStorms() {
    var config =
        new AdaptiveTimeouts.Config(
            100, // minTimeoutMs
            30000, // maxTimeoutMs
            0.5, // bufferFactor
            60000, // smoothingWindowMs
            0.05, // hysteresisThreshold
            1.5, // cpuScaleFactor
            5, // estimatedNetworkLatencyMs
            Optional.empty(), // historyStore
            10); // statisticsUpdateIntervalMs (10ms for testing)

    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Record initial good responses
      for (int i = 0; i < 20; i++) {
        adaptive.recordResponse("flaky-service", 50, true);
      }

      sleep(50);

      var statsGood = adaptive.getStats("flaky-service");
      long goodTimeout = statsGood.currentTimeoutMs;

      // Now record many consecutive timeouts
      for (int i = 0; i < 10; i++) {
        adaptive.recordResponse("flaky-service", 50000, false);
      }

      sleep(100);

      var statsUnderStress = adaptive.getStats("flaky-service");
      assertThat(statsUnderStress.currentTimeoutMs)
          .isGreaterThan(goodTimeout)
          .as("Timeout should increase under consecutive timeout stress");
      assertThat(statsUnderStress.isUnderStress())
          .isTrue()
          .as("Service should be marked as under stress");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should handle network latency awareness")
  void networkLatencyAwareness() {
    var config =
        new AdaptiveTimeouts.Config(
            100, // minTimeoutMs
            30000, // maxTimeoutMs
            0.5, // bufferFactor
            60000, // smoothingWindowMs
            0.05, // hysteresisThreshold
            1.5, // cpuScaleFactor
            100, // estimatedNetworkLatencyMs (100ms RTT)
            Optional.empty(), // historyStore
            10); // statisticsUpdateIntervalMs

    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Fast local responses
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("remote-api", 20, true);
      }

      sleep(50);

      var stats = adaptive.getStats("remote-api");
      assertThat(stats.currentTimeoutMs)
          .isGreaterThan(100)
          .as("Should include 100ms base network latency estimate");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should use hysteresis to prevent timeout oscillation")
  void hysteresisPreventOscillation() {
    var config =
        new AdaptiveTimeouts.Config(
            100, // minTimeoutMs
            30000, // maxTimeoutMs
            0.5, // bufferFactor
            60000, // smoothingWindowMs
            0.20, // hysteresisThreshold (20% change required)
            1.5, // cpuScaleFactor
            5, // estimatedNetworkLatencyMs
            Optional.empty(), // historyStore
            5); // statisticsUpdateIntervalMs

    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Record consistent latencies
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("stable-service", 100, true);
      }

      sleep(50);

      var stats1 = adaptive.getStats("stable-service");
      long timeout1 = stats1.currentTimeoutMs;

      // Small variation in latency (5% change)
      for (int i = 0; i < 10; i++) {
        adaptive.recordResponse("stable-service", 105, true);
      }

      sleep(50);

      var stats2 = adaptive.getStats("stable-service");
      long timeout2 = stats2.currentTimeoutMs;

      // Should not change due to hysteresis
      assertThat(timeout2).isEqualTo(timeout1).as("5% variation should not trigger adjustment");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should compute jitter ratio (high jitter indicates instability)")
  void jitterRatioIndicatesInstability() {
    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.empty(), 5);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Low jitter: all responses ~100ms
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("stable", 100, true);
      }

      sleep(50);
      var stableStats = adaptive.getStats("stable");

      // High jitter: 10ms to 500ms
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("jittery", i % 2 == 0 ? 10 : 500, true);
      }

      sleep(50);
      var jitteryStats = adaptive.getStats("jittery");

      assertThat(jitteryStats.jitterRatio)
          .isGreaterThan(stableStats.jitterRatio)
          .as("Jittery service should have higher jitter ratio");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should respect min and max timeout bounds")
  void respectBounds() {
    var config =
        new AdaptiveTimeouts.Config(
            500, // minTimeoutMs
            2000, // maxTimeoutMs (very low for testing)
            0.5, // bufferFactor
            60000, // smoothingWindowMs
            0.05, // hysteresisThreshold
            1.5, // cpuScaleFactor
            5, // estimatedNetworkLatencyMs
            Optional.empty(), // historyStore
            5); // statisticsUpdateIntervalMs

    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Very fast responses
      for (int i = 0; i < 30; i++) {
        adaptive.recordResponse("fast-service", 1, true);
      }

      sleep(50);
      var stats = adaptive.getStats("fast-service");
      assertThat(stats.currentTimeoutMs).isGreaterThanOrEqualTo(500).as("Should respect min");

      // Very slow responses
      for (int i = 0; i < 30; i++) {
        adaptive.recordResponse("slow-service", 10000, true);
      }

      sleep(50);
      stats = adaptive.getStats("slow-service");
      assertThat(stats.currentTimeoutMs).isLessThanOrEqualTo(2000).as("Should respect max");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should track per-service statistics independently")
  void perServiceIndependence() {
    // Service A: fast
    for (int i = 0; i < 50; i++) {
      timeouts.recordResponse("service-a", 20, true);
    }

    // Service B: slow
    for (int i = 0; i < 50; i++) {
      timeouts.recordResponse("service-b", 500, true);
    }

    sleep(100);

    var statsA = timeouts.getStats("service-a");
    var statsB = timeouts.getStats("service-b");

    assertThat(statsA.p99Ms).isLessThan(statsB.p99Ms);
    assertThat(statsA.currentTimeoutMs).isLessThan(statsB.currentTimeoutMs);
  }

  @Test
  @DisplayName("Should handle timeout failures (recording false success)")
  void recordTimeoutFailures() {
    // Mix of successes and timeouts
    for (int i = 0; i < 95; i++) {
      timeouts.recordResponse("api", 50, true);
    }
    for (int i = 0; i < 5; i++) {
      timeouts.recordResponse("api", 5000, false);
    }

    sleep(100);

    var stats = timeouts.getStats("api");
    assertThat(stats.totalRequests).isEqualTo(100);
    assertThat(stats.totalTimeouts).isEqualTo(5);
    assertThat(stats.currentTimeoutMs).isGreaterThan(100);
  }

  @Test
  @DisplayName("Should smooth timeout transitions to prevent sudden jumps")
  void smoothTransitions() {
    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.empty(), 10);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Start with 50ms responses
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("smooth-test", 50, true);
      }

      sleep(50);
      long timeout1 = adaptive.getStats("smooth-test").currentTimeoutMs;

      // Jump to 1000ms responses
      for (int i = 0; i < 100; i++) {
        adaptive.recordResponse("smooth-test", 1000, true);
      }

      sleep(50);
      long timeout2 = adaptive.getStats("smooth-test").currentTimeoutMs;

      // Transition should be smooth, not immediate jump
      long difference = Math.abs(timeout2 - timeout1);
      assertThat(difference).isGreaterThan(0).isLessThan(1000).as("Transition should be gradual");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should provide getAllStats() for comprehensive monitoring")
  void getAllStats() {
    timeouts.recordResponse("api-1", 100, true);
    timeouts.recordResponse("api-2", 200, true);
    timeouts.recordResponse("api-3", 300, true);

    sleep(100);

    var allStats = timeouts.getAllStats();
    assertThat(allStats).hasSize(3).containsKeys("api-1", "api-2", "api-3");

    assertThat(allStats.get("api-1").p50Ms)
        .isLessThan(allStats.get("api-2").p50Ms)
        .isLessThan(allStats.get("api-3").p50Ms);
  }

  @Test
  @DisplayName("Should reset service statistics")
  void resetService() {
    timeouts.recordResponse("test-service", 100, true);
    timeouts.recordResponse("test-service", 200, true);

    var statsBefore = timeouts.getStats("test-service");
    assertThat(statsBefore.totalRequests).isEqualTo(2);

    timeouts.resetService("test-service");

    var statsAfter = timeouts.getStats("test-service");
    assertThat(statsAfter.totalRequests).isEqualTo(0);
  }

  @Test
  @DisplayName("Should reset all statistics with resetAll()")
  void resetAll() {
    timeouts.recordResponse("service-1", 100, true);
    timeouts.recordResponse("service-2", 200, true);

    timeouts.resetAll();

    assertThat(timeouts.getAllStats()).isEmpty();
  }

  @Test
  @DisplayName("Should support custom history store for PostgreSQL integration")
  void customHistoryStore() {
    var records = new ArrayList<Map<String, Object>>();

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
            record.put("reason", reason);
            records.add(record);
          }

          @Override
          public List<Map<String, Object>> queryHistory(String serviceName, int limit) {
            return records.stream().limit(limit).toList();
          }
        };

    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.of(store), 5);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Trigger an adjustment
      for (int i = 0; i < 50; i++) {
        adaptive.recordResponse("logged-service", 50, true);
      }

      sleep(100);

      for (int i = 0; i < 100; i++) {
        adaptive.recordResponse("logged-service", 500, true);
      }

      sleep(100);

      // History should have recorded the adjustment
      assertThat(records).isNotEmpty().as("History store should record adjustments");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should simulate realistic network conditions")
  void simulateNetworkConditions() {
    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 20, Optional.empty(), 10);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Phase 1: Good network, fast responses (20ms base + 20ms network = 40ms p50)
      simulateResponses(adaptive, "search-api", 40, 50, 100);

      sleep(50);
      var statsGood = adaptive.getStats("search-api");
      assertThat(statsGood.p50Ms).isCloseTo(40, byLessThan(20));

      // Phase 2: Network degradation (latency spike)
      simulateResponses(adaptive, "search-api", 200, 300, 50);

      sleep(50);
      var statsLaggy = adaptive.getStats("search-api");
      assertThat(statsLaggy.currentTimeoutMs).isGreaterThan(statsGood.currentTimeoutMs);

      // Phase 3: Recovery
      simulateResponses(adaptive, "search-api", 40, 50, 100);

      sleep(50);
      var statsRecovered = adaptive.getStats("search-api");
      assertThat(statsRecovered.currentTimeoutMs)
          .isGreaterThanOrEqualTo(statsGood.currentTimeoutMs)
          .as("Timeout should not drop below previous good state immediately (hysteresis)");
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Should handle burst traffic with varying latencies")
  void burstTraffic() {
    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.empty(), 10);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      // Simulate burst: many requests with distribution from 10ms to 500ms
      Random rand = new Random(42);
      for (int i = 0; i < 1000; i++) {
        long latency = 10 + (long) (Math.pow(rand.nextDouble(), 2) * 490); // skewed toward 10ms
        adaptive.recordResponse("burst-service", latency, true);
      }

      sleep(100);

      var stats = adaptive.getStats("burst-service");
      assertThat(stats.totalRequests).isEqualTo(1000);
      assertThat(stats.p50Ms).isLessThan(stats.p99Ms);
      assertThat(stats.p99Ms).isLessThan(stats.p999Ms);
      assertThat(stats.currentTimeoutMs).isGreaterThan(100).isLessThan(30000);
    } finally {
      adaptive.shutdown(1000);
    }
  }

  @Test
  @DisplayName("Stress test: many services with concurrent updates")
  @Timeout(10)
  void stressTestManyServices() throws InterruptedException {
    var config =
        new AdaptiveTimeouts.Config(
            100, 30000, 0.5, 60000, 0.05, 1.5, 5, Optional.empty(), 5);
    var adaptive = AdaptiveTimeouts.create(config);

    try {
      var executor = Executors.newFixedThreadPool(10);

      // 50 services, each with 100 requests
      var futures =
          IntStream.range(0, 50)
              .mapToObj(
                  serviceId ->
                      executor.submit(
                          () -> {
                            String serviceName = "service-" + serviceId;
                            for (int i = 0; i < 100; i++) {
                              long latency = 10 + (serviceId * 5);
                              adaptive.recordResponse(serviceName, latency, i % 10 != 0);
                            }
                          }))
              .toList();

      for (var future : futures) {
        future.get();
      }

      executor.shutdown();
      Thread.yield();
      sleep(100);

      var allStats = adaptive.getAllStats();
      assertThat(allStats).hasSize(50);

      // Verify services are properly tuned
      for (int i = 0; i < 50; i++) {
        var stats = allStats.get("service-" + i);
        assertThat(stats.currentTimeoutMs).isGreaterThanOrEqualTo(100).isLessThanOrEqualTo(30000);
      }
    } finally {
      adaptive.shutdown(1000);
    }
  }

  private void simulateResponses(
      AdaptiveTimeouts adaptive, String serviceName, long minMs, long maxMs, int count) {
    Random rand = new Random();
    for (int i = 0; i < count; i++) {
      long latency = minMs + rand.nextLong(maxMs - minMs);
      adaptive.recordResponse(serviceName, latency, true);
    }
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
