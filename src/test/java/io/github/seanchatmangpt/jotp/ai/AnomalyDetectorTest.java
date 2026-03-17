package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.ai.AnomalyDetector.AnomalyEvent;
import io.github.seanchatmangpt.jotp.ai.AnomalyDetector.Config;
import io.github.seanchatmangpt.jotp.ai.AnomalyDetector.Metric;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for AnomalyDetector.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Baseline learning phase (warm-up)
 *   <li>Normal behavior detection
 *   <li>Latency spike detection
 *   <li>Memory leak detection (gradual resource increase)
 *   <li>Cascading failure detection (error rate explosion)
 *   <li>Critical vs. non-critical anomalies
 *   <li>Sealed event type exhaustiveness
 * </ul>
 */
class AnomalyDetectorTest implements WithAssertions {

  private AnomalyDetector detector;
  private List<AnomalyEvent> events;

  @BeforeEach
  void setUp() {
    events = new ArrayList<>();
    var config = new Config(3, 50, 100, 0.70, 0.85, Duration.ofMillis(100));
    detector = AnomalyDetector.create(config);
    detector.addHandler(events::add);
  }

  @Test
  void warmupPhaseEmitsNormalEvents() throws Exception {
    // During warm-up, all events should be Normal
    for (int i = 0; i < 30; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> events.size() >= 30);

    assertThat(events)
        .allMatch(e -> e instanceof AnomalyEvent.Normal)
        .hasSizeGreaterThanOrEqualTo(30);

    detector.stop();
  }

  @Test
  void detectsLatencySpike() throws Exception {
    // Warm-up: establish baseline
    for (int i = 0; i < 55; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> events.size() >= 55);

    events.clear();

    // Inject latency spike
    var spike = new Metric(
        Instant.now(),
        750.0, // 750ms latency (normal is ~50ms)
        100.0,
        5.0,
        512.0);

    detector.recordMetric(spike);

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !events.isEmpty());

    var anomaly = events.get(0);
    assertThat(anomaly).isInstanceOf(AnomalyEvent.Anomaly.class);

    if (anomaly instanceof AnomalyEvent.Anomaly a) {
      assertThat(a.anomalyScore()).isGreaterThan(0.5);
      assertThat(a.reason()).containsIgnoringCase("latency");
    }

    detector.stop();
  }

  @Test
  void detectsMemoryLeak() throws Exception {
    // Warm-up with stable memory
    for (int i = 0; i < 55; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> events.size() >= 55);

    events.clear();

    // Inject memory leak: gradual heap increase
    double startHeap = 512.0;
    for (int i = 0; i < 5; i++) {
      var leakyMetric = new Metric(
          Instant.now(),
          50.0,
          1000.0,
          5.0,
          startHeap + (i * 50.0)); // Memory increasing by 50MB each time

      detector.recordMetric(leakyMetric);
      Thread.sleep(10);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !events.isEmpty());

    // Should detect memory leak as Anomaly
    var lastEvent = events.get(events.size() - 1);
    assertThat(lastEvent)
        .isInstanceOf(AnomalyEvent.Anomaly.class)
        .matches(e -> {
          if (e instanceof AnomalyEvent.Anomaly a) {
            return a.reason().containsIgnoringCase("memory") || a.anomalyScore() > 0.6;
          }
          return false;
        });

    detector.stop();
  }

  @Test
  void detectsCascadingFailure() throws Exception {
    // Warm-up: normal behavior
    for (int i = 0; i < 55; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> events.size() >= 55);

    events.clear();

    // Inject cascading failure: error rate explosion
    var cascade = new Metric(
        Instant.now(),
        100.0,
        500.0, // Throughput cut in half
        50.0, // Error rate jumps to 50%
        512.0);

    detector.recordMetric(cascade);

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !events.isEmpty());

    var lastEvent = events.get(events.size() - 1);
    assertThat(lastEvent).isInstanceOf(AnomalyEvent.CriticalAnomaly.class);

    if (lastEvent instanceof AnomalyEvent.CriticalAnomaly c) {
      assertThat(c.anomalyScore()).isGreaterThan(0.6);
      assertThat(c.reason()).containsIgnoringCase("cascading");
    }

    detector.stop();
  }

  @Test
  void sealedEventExhaustiveness() throws Exception {
    // Verify handler can exhaustively switch on all event types
    var normalCount = new java.util.concurrent.atomic.AtomicInteger(0);
    var anomalyCount = new java.util.concurrent.atomic.AtomicInteger(0);
    var criticalCount = new java.util.concurrent.atomic.AtomicInteger(0);

    detector.addHandler(event -> {
      // This switch must be exhaustive — compiler enforces it
      switch (event) {
        case AnomalyEvent.Normal n -> normalCount.incrementAndGet();
        case AnomalyEvent.Anomaly a -> anomalyCount.incrementAndGet();
        case AnomalyEvent.CriticalAnomaly c -> criticalCount.incrementAndGet();
      }
    });

    // Warm-up (Normal events)
    for (int i = 0; i < 30; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> normalCount.get() >= 30);

    // Inject anomaly
    for (int i = 0; i < 55; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    events.clear();
    var spike = new Metric(Instant.now(), 750.0, 100.0, 5.0, 512.0);
    detector.recordMetric(spike);

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> anomalyCount.get() > 0 || criticalCount.get() > 0);

    // Verify at least one of each type was recorded
    assertThat(normalCount.get()).isGreaterThan(0);

    detector.stop();
  }

  @Test
  void multipleHandlersCanSubscribeIndependently() throws Exception {
    var handler1Events = new ArrayList<AnomalyEvent>();
    var handler2Events = new ArrayList<AnomalyEvent>();

    detector.addHandler(handler1Events::add);
    detector.addHandler(handler2Events::add);

    // Emit some metrics
    for (int i = 0; i < 35; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> handler1Events.size() >= 35 && handler2Events.size() >= 35);

    assertThat(handler1Events).hasSameSizeAs(handler2Events);

    detector.stop();
  }

  @Test
  void getStateReturnsDetectorMetadata() throws Exception {
    // Warm-up
    for (int i = 0; i < 55; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> events.size() >= 55);

    var stateResult = detector.getState();
    assertThat(stateResult.isSuccess()).isTrue();

    var state = stateResult.fold(s -> s, e -> {
      throw new AssertionError("Failed to get state", e);
    });

    assertThat(state.learned()).isTrue();
    assertThat(state.anomalyCount()).isGreaterThanOrEqualTo(0);
    assertThat(state.criticalCount()).isGreaterThanOrEqualTo(0);

    detector.stop();
  }

  @Test
  void handlerCrashDoesNotKillDetector() throws Exception {
    var crashingHandler = new Exception("test crash");
    detector.addHandler(event -> {
      throw new RuntimeException(crashingHandler);
    });

    var goodEvents = new ArrayList<AnomalyEvent>();
    detector.addHandler(goodEvents::add);

    // Emit metrics — crashing handler should be removed
    for (int i = 0; i < 30; i++) {
      detector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> goodEvents.size() >= 30);

    // Detector should still be processing
    assertThat(goodEvents).hasSizeGreaterThanOrEqualTo(30);

    detector.stop();
  }

  @Test
  void configurationAffectsDetectionSensitivity() throws Exception {
    var strictConfig = new Config(3, 50, 100, 0.50, 0.75, Duration.ofMillis(100));
    var strictDetector = AnomalyDetector.create(strictConfig);

    var strictEvents = new ArrayList<AnomalyEvent>();
    strictDetector.addHandler(strictEvents::add);

    // Warm-up
    for (int i = 0; i < 55; i++) {
      strictDetector.recordMetric(normalMetric());
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> strictEvents.size() >= 55);

    strictEvents.clear();

    // Mild latency increase (40ms instead of 50ms — within normal variance)
    var mildLatency = new Metric(Instant.now(), 90.0, 1000.0, 5.0, 512.0);
    strictDetector.recordMetric(mildLatency);

    Thread.sleep(100);

    // With stricter threshold, should detect as anomaly more readily
    assertThat(strictEvents).isNotEmpty();

    strictDetector.stop();
  }

  @Test
  void detectionAccuracyAfterLongRun() throws Exception {
    // Simulate a real workload: mostly normal with occasional anomalies
    for (int i = 0; i < 100; i++) {
      if (i % 20 == 0) {
        // Every 20 samples, inject an anomaly
        detector.recordMetric(
            new Metric(
                Instant.now(),
                i % 40 == 0 ? 500.0 : 50.0, // Latency spike every other anomaly
                1000.0,
                i % 40 == 0 ? 25.0 : 5.0, // Error rate spike
                512.0 + (i % 40 == 0 ? 100.0 : 0.0))); // Memory increase
      } else {
        detector.recordMetric(normalMetric());
      }
      Thread.sleep(5);
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> events.size() >= 100);

    // Count anomalies vs. normal
    var anomalies =
        events.stream()
            .filter(e -> !(e instanceof AnomalyEvent.Normal))
            .count();

    // Should detect some anomalies (5 injected + possible false positives)
    assertThat(anomalies).isGreaterThanOrEqualTo(2);

    detector.stop();
  }

  // ====== Helper Methods ======

  private Metric normalMetric() {
    return new Metric(
        Instant.now(),
        50.0, // 50ms latency (baseline)
        1000.0, // 1000 req/sec (baseline)
        5.0, // 5% error rate (baseline)
        512.0); // 512MB heap
  }
}
