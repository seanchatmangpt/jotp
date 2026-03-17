package io.github.seanchatmangpt.jotp.ai;

import java.util.*;
import java.util.concurrent.*;

/**
 * Root cause analysis engine that interprets symptoms and diagnoses the underlying failure.
 *
 * <p>Maps symptoms to likely root causes using heuristics and pattern recognition.
 *
 * <p><strong>Diagnostic Rules:</strong>
 *
 * <ul>
 *   <li>{@code HighLatency} → likely {@code SlowNode}, {@code NetworkIssue}, or {@code GC pause}
 *   <li>{@code MemoryLeak} → likely {@code MemoryLeakProcess} or {@code ResourceExhaustion}
 *   <li>{@code ExceptionStorm} → likely {@code SoftwareBug} or {@code CascadingFailure}
 *   <li>{@code CpuSaturation} → likely {@code ResourceExhaustion} or {@code SoftwareBug}
 *   <li>{@code CascadingCrash} → likely {@code CascadingFailure} or {@code ExternalDependencyFailure}
 *   <li>{@code CircuitBreakerTrip} → likely {@code ExternalDependencyFailure}
 *   <li>{@code DeadlockDetected} → likely {@code SoftwareBug}
 * </ul>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * var diagnoser = new FailureDiagnoser();
 * var symptom = new SelfHealer.Symptom.HighLatency(1200, 800, 3);
 * var diagnosis = diagnoser.diagnose(symptom);
 * System.out.println(diagnosis);
 * }</pre>
 */
public final class FailureDiagnoser {

  private final Map<String, DiagnosticRule> rules = new ConcurrentHashMap<>();
  private final Random random = new Random();

  public FailureDiagnoser() {
    initializeRules();
  }

  /**
   * Diagnose the root cause of a symptom.
   *
   * @param symptom the detected symptom
   * @return likely root cause diagnosis
   */
  public SelfHealer.Diagnosis diagnose(SelfHealer.Symptom symptom) {
    return switch (symptom) {
      case SelfHealer.Symptom.HighLatency latency ->
          diagnoseHighLatency(latency);
      case SelfHealer.Symptom.MemoryLeak leak ->
          diagnoseMemoryLeak(leak);
      case SelfHealer.Symptom.ExceptionStorm storm ->
          diagnoseExceptionStorm(storm);
      case SelfHealer.Symptom.CpuSaturation saturation ->
          diagnoseCpuSaturation(saturation);
      case SelfHealer.Symptom.CascadingCrash crash ->
          diagnoseCascadingCrash(crash);
      case SelfHealer.Symptom.CircuitBreakerTrip trip ->
          diagnoseCircuitBreakerTrip(trip);
      case SelfHealer.Symptom.DeadlockDetected deadlock ->
          diagnoseDeadlock(deadlock);
    };
  }

  private SelfHealer.Diagnosis diagnoseHighLatency(
      SelfHealer.Symptom.HighLatency latency) {
    // High p99 with significant affect: likely slow node or network issue
    if (latency.affectedProcesses() > 5) {
      return new SelfHealer.Diagnosis.SlowNode(
          "node-" + random.nextInt(10),
          latency.p99Ms(),
          "CPU bottleneck or GC pause");
    } else if (latency.p99Ms() > 2000) {
      return new SelfHealer.Diagnosis.NetworkIssue("High latency spike detected");
    } else {
      return new SelfHealer.Diagnosis.SlowNode(
          "proc-" + random.nextInt(100),
          latency.p99Ms(),
          "Processing delay");
    }
  }

  private SelfHealer.Diagnosis diagnoseMemoryLeak(
      SelfHealer.Symptom.MemoryLeak leak) {
    if (leak.leakingProcesses() > 0) {
      return new SelfHealer.Diagnosis.MemoryLeakProcess(
          "proc-" + leak.leakingProcesses(),
          1024 * 1024, // 1MB per second guess
          (int) (leak.gcOverhead() * 100));
    } else {
      return new SelfHealer.Diagnosis.ResourceExhaustion(
          "Heap Memory",
          leak.gcOverhead());
    }
  }

  private SelfHealer.Diagnosis diagnoseExceptionStorm(
      SelfHealer.Symptom.ExceptionStorm storm) {
    if (storm.affectedProcesses() > 10) {
      return new SelfHealer.Diagnosis.CascadingFailure(
          "Exception propagation",
          storm.affectedProcesses(),
          true);
    } else {
      return new SelfHealer.Diagnosis.SoftwareBug(
          storm.commonException(),
          "Detected from exception storm",
          false);
    }
  }

  private SelfHealer.Diagnosis diagnoseCpuSaturation(
      SelfHealer.Symptom.CpuSaturation saturation) {
    return new SelfHealer.Diagnosis.ResourceExhaustion(
        "CPU",
        saturation.cpuUsagePercent() / 100);
  }

  private SelfHealer.Diagnosis diagnoseCascadingCrash(
      SelfHealer.Symptom.CascadingCrash crash) {
    boolean isRepairable = crash.timeSinceFirstCrash() < 60_000; // Repairable if within 1 min
    return new SelfHealer.Diagnosis.CascadingFailure(
        crash.rootCause(),
        crash.crashedProcesses(),
        isRepairable);
  }

  private SelfHealer.Diagnosis diagnoseCircuitBreakerTrip(
      SelfHealer.Symptom.CircuitBreakerTrip trip) {
    return new SelfHealer.Diagnosis.ExternalDependencyFailure(
        "cb-" + trip.circuitName(),
        (long) (1000 / Math.max(0.01, trip.errorRate())));
  }

  private SelfHealer.Diagnosis diagnoseDeadlock(
      SelfHealer.Symptom.DeadlockDetected deadlock) {
    return new SelfHealer.Diagnosis.SoftwareBug(
        "Deadlock",
        "Thread deadlock detected with " + deadlock.deadlockedThreads() + " threads",
        false);
  }

  private void initializeRules() {
    // Placeholder for more sophisticated rule engine if needed
  }

  /**
   * Diagnostic rule — maps symptoms to diagnoses.
   */
  private record DiagnosticRule(
      String name,
      Class<?> symptomType,
      DiagnosticFunction function) {}

  @FunctionalInterface
  private interface DiagnosticFunction {
    SelfHealer.Diagnosis apply(SelfHealer.Symptom symptom);
  }
}
