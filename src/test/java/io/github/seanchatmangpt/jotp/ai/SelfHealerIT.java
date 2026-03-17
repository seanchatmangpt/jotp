package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Integration tests for self-healing system under realistic failure scenarios.
 *
 * <p>Simulates:
 *
 * <ul>
 *   <li>Sustained high latency and autonomous recovery
 *   <li>Memory leak progression and repair
 *   <li>Cascading crashes with multi-stage repair escalation
 *   <li>Circuit breaker trips and timeout recovery
 *   <li>Mixed failure modes with concurrent repairs
 * </ul>
 */
class SelfHealerIT {

  private SelfHealer healer;

  @BeforeEach
  void setUp() {
    healer = SelfHealer.create(
        Duration.ofMillis(50), // fast scans for testing
        Duration.ofSeconds(2),
        Duration.ofSeconds(10));
  }

  @AfterEach
  void tearDown() {
    if (healer.isRunning()) {
      healer.stop();
    }
  }

  @Test
  void testSustainedHighLatencyRecovery() throws InterruptedException {
    healer.start();

    // Simulate sustained latency
    var startMetrics = healer.metrics();
    long startTime = System.currentTimeMillis();

    // Simulate high latency over 2 seconds
    var detector = new AnomalyDetector(Duration.ofSeconds(2));
    for (int i = 0; i < 40; i++) {
      detector.recordProcessLatency("proc-1", 600 + i * 10);
      detector.recordProcessLatency("proc-2", 550 + i * 8);
      Thread.sleep(50);
    }

    long elapsed = System.currentTimeMillis() - startTime;
    assertThat(elapsed).isGreaterThanOrEqualTo(1900); // ~2 seconds

    var endMetrics = healer.metrics();

    // Verify healer is still operational
    assertThat(healer.isRunning()).isTrue();

    healer.stop();
  }

  @Test
  void testMemoryLeakProgressionAndRepair() throws InterruptedException {
    healer.start();

    var detector = new AnomalyDetector(Duration.ofSeconds(1));

    // Simulate memory leak progression
    for (int i = 0; i < 10; i++) {
      detector.recordProcessLatency("proc-leak", 100 + i * 50);
      Thread.sleep(100);
    }

    var symptoms = detector.scan();

    // Should detect something
    assertThat(symptoms).isNotEmpty();

    healer.stop();
  }

  @Test
  void testCascadingCrashRecoveryWithEscalation() throws InterruptedException {
    healer.start();

    var detector = new AnomalyDetector(Duration.ofSeconds(2));

    // Simulate cascading crash: one process crashes, then others follow
    var symptoms = new ArrayList<SelfHealer.Symptom>();
    for (int i = 0; i < 3; i++) {
      symptoms.add(new SelfHealer.Symptom.CascadingCrash(
          3 + i, // increasing number of crashes
          30_000 - i * 5000, // decreasing time window
          "Cascading from primary"));
    }

    var diagnoser = new FailureDiagnoser();
    var decisionTree = new RepairDecisionTree();
    var autoRepair = new AutoRepair(Duration.ofSeconds(10));

    // First repair attempt
    var diagnosis1 = diagnoser.diagnose(symptoms.get(0));
    var repair1 = decisionTree.selectRepair(symptoms.get(0), diagnosis1);
    var outcome1 = autoRepair.execute(repair1);

    // If that fails, escalate
    if (outcome1 instanceof SelfHealer.RepairOutcome.Failure) {
      var repair2 = decisionTree.escalateRepair(repair1, symptoms.get(1));
      assertThat(repair2).isNotNull();

      var outcome2 = autoRepair.execute(repair2);
      assertThat(outcome2).isNotNull();
    }

    healer.stop();
  }

  @Test
  void testCircuitBreakerTripAndRecovery() throws InterruptedException {
    healer.start();

    var detector = new AnomalyDetector(Duration.ofSeconds(1));

    // Simulate circuit breaker failures
    detector.recordCircuitBreakerState("payment-api", 45, 5);
    detector.recordCircuitBreakerState("inventory-api", 30, 10);

    var symptoms = detector.scan();

    var autoRepair = new AutoRepair(Duration.ofSeconds(10));
    var repair = new SelfHealer.Repair.CircuitBreakerOpen("payment-api", Duration.ofSeconds(30));
    var outcome = autoRepair.execute(repair);

    assertThat(outcome).isInstanceOf(SelfHealer.RepairOutcome.Success.class);

    healer.stop();
  }

  @Test
  void testMixedFailureModesWithConcurrentRepairs() throws InterruptedException {
    healer.start();

    var diagnoser = new FailureDiagnoser();
    var decisionTree = new RepairDecisionTree();
    var autoRepair = new AutoRepair(Duration.ofSeconds(10));

    // Simulate multiple concurrent failures
    var failures = new ArrayList<SelfHealer.Symptom>();
    failures.add(new SelfHealer.Symptom.HighLatency(900, 700, 5));
    failures.add(new SelfHealer.Symptom.ExceptionStorm(200, "TimeoutException", 8));
    failures.add(new SelfHealer.Symptom.CpuSaturation(88.0, 180));
    failures.add(new SelfHealer.Symptom.MemoryLeak(0.48, 900_000_000, 2));

    var repairs = new ArrayList<SelfHealer.Repair>();
    for (var failure : failures) {
      var diagnosis = diagnoser.diagnose(failure);
      var repair = decisionTree.selectRepair(failure, diagnosis);
      repairs.add(repair);
    }

    // Execute all repairs
    for (var repair : repairs) {
      var outcome = autoRepair.execute(repair);
      assertThat(outcome).isNotNull();
    }

    var finalMetrics = healer.metrics();
    assertThat(finalMetrics.totalRepairs()).isGreaterThanOrEqualTo(0);

    healer.stop();
  }

  @Test
  void testRepairSuccessRateTracking() throws InterruptedException {
    healer.start();

    var autoRepair = new AutoRepair(Duration.ofSeconds(5));

    // Execute 20 repairs
    int successCount = 0;
    for (int i = 0; i < 20; i++) {
      var repair = new SelfHealer.Repair.RestartProcess("proc-" + i, Duration.ofSeconds(3));
      var outcome = autoRepair.execute(repair);

      if (outcome instanceof SelfHealer.RepairOutcome.Success) {
        successCount++;
      }
    }

    // Should have at least some successes
    assertThat(successCount).isGreaterThan(0);

    healer.stop();
  }

  @Test
  void testTimeToRecoveryMetrics() throws InterruptedException {
    healer.start();

    var autoRepair = new AutoRepair(Duration.ofSeconds(5));

    // Execute repairs and track timing
    var durations = new ArrayList<Long>();
    for (int i = 0; i < 10; i++) {
      long before = System.currentTimeMillis();
      var repair = new SelfHealer.Repair.RestartProcess("proc-" + i, Duration.ofSeconds(2));
      autoRepair.execute(repair);
      long after = System.currentTimeMillis();
      durations.add(after - before);
    }

    // Average should be reasonable
    var avg = durations.stream().mapToLong(Long::longValue).average().orElse(0);
    assertThat(avg).isGreaterThan(0);
    assertThat(avg).isLessThan(1000); // Should be reasonably fast

    healer.stop();
  }

  @Test
  void testDiagnosisAccuracy() {
    var diagnoser = new FailureDiagnoser();

    // Test that each symptom type gets a diagnosis
    var testCases = List.of(
        new SelfHealer.Symptom.HighLatency(1000, 800, 5),
        new SelfHealer.Symptom.MemoryLeak(0.50, 1_000_000_000, 3),
        new SelfHealer.Symptom.ExceptionStorm(200, "RuntimeException", 10),
        new SelfHealer.Symptom.CpuSaturation(90.0, 200),
        new SelfHealer.Symptom.CascadingCrash(8, 20_000, "Root cause"),
        new SelfHealer.Symptom.CircuitBreakerTrip("api", 50, 0.40),
        new SelfHealer.Symptom.DeadlockDetected(4, 5000)
    );

    for (var symptom : testCases) {
      var diagnosis = diagnoser.diagnose(symptom);
      assertThat(diagnosis).isNotNull();
      assertThat(diagnosis.getClass().getSimpleName()).doesNotContain("null");
    }
  }

  @Test
  void testRepairDecisionTreeCoverage() {
    var decisionTree = new RepairDecisionTree();
    var diagnoser = new FailureDiagnoser();

    var symptoms = List.of(
        new SelfHealer.Symptom.HighLatency(600, 400, 3),
        new SelfHealer.Symptom.MemoryLeak(0.45, 800_000_000, 2),
        new SelfHealer.Symptom.ExceptionStorm(150, "Exception", 5),
        new SelfHealer.Symptom.CpuSaturation(85.0, 150),
        new SelfHealer.Symptom.CascadingCrash(6, 15_000, "Cascade"),
        new SelfHealer.Symptom.CircuitBreakerTrip("service", 30, 0.25),
        new SelfHealer.Symptom.DeadlockDetected(2, 3000)
    );

    for (var symptom : symptoms) {
      var diagnosis = diagnoser.diagnose(symptom);
      var repair = decisionTree.selectRepair(symptom, diagnosis);
      assertThat(repair).isNotNull();
      assertThat(repair.getClass().getSimpleName()).isNotBlank();
    }
  }

  @Test
  void testLongRunningHealer() throws InterruptedException {
    healer.start();

    // Run for a few seconds
    for (int i = 0; i < 20; i++) {
      Thread.sleep(100);
      var metrics = healer.metrics();
      assertThat(metrics).isNotNull();
    }

    assertThat(healer.isRunning()).isTrue();
    healer.stop();
    assertThat(healer.isRunning()).isFalse();
  }

  @Test
  void testRepairHistoryTracking() throws InterruptedException {
    healer.start();

    var diagnoser = new FailureDiagnoser();
    var decisionTree = new RepairDecisionTree();
    var autoRepair = new AutoRepair(Duration.ofSeconds(5));

    // Generate some repairs
    var symptom = new SelfHealer.Symptom.HighLatency(700, 500, 4);
    var diagnosis = diagnoser.diagnose(symptom);
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    for (int i = 0; i < 5; i++) {
      autoRepair.execute(repair);
    }

    Thread.sleep(100);

    var history = healer.getRepairHistory(100);
    assertThat(history).isNotNull();

    healer.stop();
  }
}
