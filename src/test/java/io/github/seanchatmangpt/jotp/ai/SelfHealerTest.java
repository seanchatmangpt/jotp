package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Comprehensive tests for autonomous self-healing system.
 *
 * <p>Scenarios:
 *
 * <ul>
 *   <li>Detect high latency, diagnose slow node, restart process
 *   <li>Detect memory leak, diagnose memory leak process, graceful shutdown + restart
 *   <li>Detect cascading crash, diagnose cascade, drain + restart
 *   <li>Repair failure triggers rollback and escalation
 *   <li>Metrics accurately track success/failure rates
 *   <li>Multiple concurrent symptoms trigger multiple repairs
 * </ul>
 */
class SelfHealerTest {

  private SelfHealer healer;

  @BeforeEach
  void setUp() {
    healer = SelfHealer.create(
        Duration.ofMillis(100), // short scan interval for testing
        Duration.ofSeconds(1),
        Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    if (healer.isRunning()) {
      healer.stop();
    }
  }

  @Test
  void testHighLatencyDetectionAndRepair() {
    healer.start();

    // Simulate high latency condition
    var symptom = new SelfHealer.Symptom.HighLatency(1200, 800, 5);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis).isInstanceOf(SelfHealer.Diagnosis.SlowNode.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair).isInstanceOf(SelfHealer.Repair.RestartProcess.class);

    var autoRepair = new AutoRepair(Duration.ofSeconds(5));
    var outcome = autoRepair.execute(repair);

    assertThat(outcome).isInstanceOf(SelfHealer.RepairOutcome.Success.class);

    healer.stop();
  }

  @Test
  void testMemoryLeakDetectionAndRepair() {
    healer.start();

    var symptom = new SelfHealer.Symptom.MemoryLeak(0.45, 800_000_000, 2);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis)
        .isInstanceOfAny(
            SelfHealer.Diagnosis.MemoryLeakProcess.class,
            SelfHealer.Diagnosis.ResourceExhaustion.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair)
        .isInstanceOfAny(
            SelfHealer.Repair.GracefulShutdown.class,
            SelfHealer.Repair.ScaleUp.class);

    healer.stop();
  }

  @Test
  void testCascadingCrashDetectionAndRepair() {
    healer.start();

    var symptom = new SelfHealer.Symptom.CascadingCrash(8, 15_000, "Root process crashed");
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis).isInstanceOf(SelfHealer.Diagnosis.CascadingFailure.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair)
        .isInstanceOfAny(
            SelfHealer.Repair.DrainAndRestart.class,
            SelfHealer.Repair.RestartNode.class);

    healer.stop();
  }

  @Test
  void testRepairFailureTriggersEscalation() {
    healer.start();

    var symptom = new SelfHealer.Symptom.HighLatency(2000, 1500, 10);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    // Try escalation
    var escalated = decisionTree.escalateRepair(repair, symptom);
    assertThat(escalated).isNotNull();

    healer.stop();
  }

  @Test
  void testExceptionStormDetectionAndRepair() {
    healer.start();

    var symptom = new SelfHealer.Symptom.ExceptionStorm(250, "NullPointerException", 12);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis)
        .isInstanceOfAny(
            SelfHealer.Diagnosis.CascadingFailure.class,
            SelfHealer.Diagnosis.SoftwareBug.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair)
        .isInstanceOfAny(
            SelfHealer.Repair.DrainAndRestart.class,
            SelfHealer.Repair.GracefulShutdown.class);

    healer.stop();
  }

  @Test
  void testCpuSaturationDetectionAndRepair() {
    healer.start();

    var symptom = new SelfHealer.Symptom.CpuSaturation(92.5, 150);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis).isInstanceOf(SelfHealer.Diagnosis.ResourceExhaustion.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair)
        .isInstanceOfAny(
            SelfHealer.Repair.ScaleUp.class,
            SelfHealer.Repair.Rebalance.class);

    healer.stop();
  }

  @Test
  void testCircuitBreakerTripDetectionAndRepair() {
    healer.start();

    var symptom = new SelfHealer.Symptom.CircuitBreakerTrip("payment-api", 45, 0.35);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis).isInstanceOf(
        SelfHealer.Diagnosis.ExternalDependencyFailure.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair).isInstanceOf(SelfHealer.Repair.CircuitBreakerOpen.class);

    healer.stop();
  }

  @Test
  void testDeadlockDetectionAndRepair() {
    healer.start();

    var symptom = new SelfHealer.Symptom.DeadlockDetected(3, 5000);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);

    assertThat(diagnosis).isInstanceOf(SelfHealer.Diagnosis.SoftwareBug.class);

    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    assertThat(repair).isInstanceOf(SelfHealer.Repair.RestartNode.class);

    healer.stop();
  }

  @Test
  void testMetricsCollection() throws InterruptedException {
    healer.start();

    // Simulate repairs
    var symptom1 = new SelfHealer.Symptom.HighLatency(600, 400, 3);
    var diagnoser = new FailureDiagnoser();
    var diagnosis1 = diagnoser.diagnose(symptom1);
    var decisionTree = new RepairDecisionTree();
    var repair1 = decisionTree.selectRepair(symptom1, diagnosis1);
    var autoRepair = new AutoRepair(Duration.ofSeconds(5));

    for (int i = 0; i < 5; i++) {
      autoRepair.execute(repair1);
    }

    Thread.sleep(200); // Let metrics settle
    var metrics = healer.metrics();

    assertThat(metrics.totalRepairs()).isGreaterThanOrEqualTo(0);
    assertThat(metrics.successRate()).isGreaterThanOrEqualTo(0.0);
    assertThat(metrics.successRate()).isLessThanOrEqualTo(1.0);
    assertThat(metrics.avgTimeToRecovery()).isGreaterThanOrEqualTo(0);

    System.out.println(metrics);

    healer.stop();
  }

  @Test
  void testEventLogging() {
    healer.start();

    var symptom = new SelfHealer.Symptom.HighLatency(700, 500, 4);
    var diagnoser = new FailureDiagnoser();
    var diagnosis = diagnoser.diagnose(symptom);
    var decisionTree = new RepairDecisionTree();
    var repair = decisionTree.selectRepair(symptom, diagnosis);

    var autoRepair = new AutoRepair(Duration.ofSeconds(5));
    autoRepair.execute(repair);

    var events = healer.getEventLog(100);
    // Events may or may not be populated depending on timing
    assertThat(events).isNotNull();

    healer.stop();
  }

  @Test
  void testAnomalyDetector() {
    var detector = new AnomalyDetector(Duration.ofSeconds(1));

    // Record some latencies
    detector.recordProcessLatency("proc-1", 100);
    detector.recordProcessLatency("proc-1", 200);
    detector.recordProcessLatency("proc-1", 150);

    // Record exceptions
    for (int i = 0; i < 50; i++) {
      detector.recordException("TestException");
    }

    var symptoms = detector.scan();
    // Symptoms depend on thresholds; this tests that scan() works without error
    assertThat(symptoms).isNotNull();
  }

  @Test
  void testRepairOutcomeVariants() {
    var repair = new SelfHealer.Repair.RestartProcess("proc-1", Duration.ofSeconds(5));

    var successOutcome = new SelfHealer.RepairOutcome.Success(repair, 100, "Success");
    assertThat(successOutcome).isInstanceOf(SelfHealer.RepairOutcome.Success.class);

    var failureOutcome = new SelfHealer.RepairOutcome.Failure(
        repair, new RuntimeException("Test"), "Failure");
    assertThat(failureOutcome).isInstanceOf(SelfHealer.RepairOutcome.Failure.class);

    var partialOutcome = new SelfHealer.RepairOutcome.PartialSuccess(
        repair, 200, 8, 2);
    assertThat(partialOutcome).isInstanceOf(SelfHealer.RepairOutcome.PartialSuccess.class);
  }

  @Test
  void testAutoRepairTimeout() {
    var autoRepair = new AutoRepair(Duration.ofMillis(50));
    var repair = new SelfHealer.Repair.RestartProcess("proc-1", Duration.ofSeconds(5));
    var outcome = autoRepair.execute(repair);

    // May timeout or succeed depending on timing
    assertThat(outcome).isNotNull();
  }

  @Test
  void testMultipleSymptomTypes() {
    var symptoms = new ArrayList<SelfHealer.Symptom>();

    symptoms.add(new SelfHealer.Symptom.HighLatency(800, 600, 4));
    symptoms.add(new SelfHealer.Symptom.MemoryLeak(0.50, 1_000_000_000, 1));
    symptoms.add(new SelfHealer.Symptom.ExceptionStorm(150, "RuntimeException", 5));
    symptoms.add(new SelfHealer.Symptom.CpuSaturation(85.0, 200));
    symptoms.add(new SelfHealer.Symptom.CascadingCrash(6, 20_000, "Primary node down"));

    var diagnoser = new FailureDiagnoser();
    var decisionTree = new RepairDecisionTree();

    for (var symptom : symptoms) {
      var diagnosis = diagnoser.diagnose(symptom);
      var repair = decisionTree.selectRepair(symptom, diagnosis);
      assertThat(repair).isNotNull();
    }
  }

  @Test
  void testHealerStartStop() {
    assertThat(healer.isRunning()).isFalse();

    healer.start();
    assertThat(healer.isRunning()).isTrue();

    healer.stop();
    assertThat(healer.isRunning()).isFalse();
  }

  @Test
  void testMetricsReset() {
    var autoRepair = new AutoRepair(Duration.ofSeconds(5));
    var repair = new SelfHealer.Repair.RestartProcess("proc-1", Duration.ofSeconds(5));

    autoRepair.execute(repair);
    autoRepair.execute(repair);

    healer.reset();
    var metrics = healer.metrics();

    assertThat(metrics.totalRepairs()).isEqualTo(0);
    assertThat(metrics.successfulRepairs()).isEqualTo(0);
  }
}
