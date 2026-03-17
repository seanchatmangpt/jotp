package io.github.seanchatmangpt.jotp.ai;

import java.time.Duration;
import java.util.*;

/**
 * Decision tree that maps (symptom, diagnosis) pairs to repair strategies.
 *
 * <p>Implements the logic:
 *
 * <pre>{@code
 * if (HighLatency && SlowNode) {
 *     restart_slow_node()
 * } else if (HighLatency && NetworkIssue) {
 *     check_network() [informational]
 * } else if (MemoryLeak && MemoryLeakProcess) {
 *     graceful_shutdown() + restart()
 * } else if (ExceptionStorm && CascadingFailure) {
 *     drain_and_restart() or failover()
 * } else if (CpuSaturation) {
 *     scale_up() or rebalance()
 * } else if (DeadlockDetected) {
 *     force_shutdown() + restart()
 * }
 * }</pre>
 *
 * <p><strong>Escalation:</strong> If a repair fails, escalate to the next strategy (e.g., restart
 * → failover).
 */
public final class RepairDecisionTree {

  /**
   * Select the primary repair strategy for a (symptom, diagnosis) pair.
   *
   * @param symptom detected symptom
   * @param diagnosis root cause analysis
   * @return recommended repair action
   */
  public SelfHealer.Repair selectRepair(
      SelfHealer.Symptom symptom,
      SelfHealer.Diagnosis diagnosis) {
    return switch (symptom) {
      case SelfHealer.Symptom.HighLatency latency ->
          selectHighLatencyRepair(latency, diagnosis);
      case SelfHealer.Symptom.MemoryLeak leak ->
          selectMemoryLeakRepair(leak, diagnosis);
      case SelfHealer.Symptom.ExceptionStorm storm ->
          selectExceptionStormRepair(storm, diagnosis);
      case SelfHealer.Symptom.CpuSaturation sat ->
          selectCpuSaturationRepair(sat, diagnosis);
      case SelfHealer.Symptom.CascadingCrash crash ->
          selectCascadingCrashRepair(crash, diagnosis);
      case SelfHealer.Symptom.CircuitBreakerTrip trip ->
          selectCircuitBreakerRepair(trip, diagnosis);
      case SelfHealer.Symptom.DeadlockDetected deadlock ->
          selectDeadlockRepair(deadlock, diagnosis);
    };
  }

  /**
   * Escalate to a more aggressive repair if the previous one failed.
   *
   * @param failedRepair the repair that didn't work
   * @param symptom the original symptom
   * @return escalated repair strategy, or null if no escalation available
   */
  public SelfHealer.Repair escalateRepair(
      SelfHealer.Repair failedRepair,
      SelfHealer.Symptom symptom) {
    return switch (failedRepair) {
      case SelfHealer.Repair.RestartProcess restartProc ->
          // Escalate to draining before restart
          new SelfHealer.Repair.DrainAndRestart(
              restartProc.processId(), Duration.ofSeconds(10));
      case SelfHealer.Repair.RestartNode restartNode ->
          // Escalate to failover
          new SelfHealer.Repair.Failover("failed-service", "standby-supervisor");
      case SelfHealer.Repair.DrainAndRestart drain ->
          // Escalate to full node restart
          new SelfHealer.Repair.RestartNode(
              "node-" + symptom.toString().hashCode(), Duration.ofSeconds(5));
      case SelfHealer.Repair.Rebalance rebalance ->
          // Escalate to scaling up
          new SelfHealer.Repair.ScaleUp("supervisor-1", 10);
      default ->
          // No escalation for others
          null;
    };
  }

  // ── Decision Logic ────────────────────────────────────────────────────────────────────

  private SelfHealer.Repair selectHighLatencyRepair(
      SelfHealer.Symptom.HighLatency latency,
      SelfHealer.Diagnosis diagnosis) {
    return switch (diagnosis) {
      case SelfHealer.Diagnosis.SlowNode slowNode ->
          new SelfHealer.Repair.RestartProcess(
              slowNode.nodeId(), Duration.ofSeconds(5));
      case SelfHealer.Diagnosis.NetworkIssue network ->
          // Network issues usually can't be auto-fixed; try rebalance to avoid the link
          new SelfHealer.Repair.Rebalance(
              List.of("proc-1", "proc-2", "proc-3"), 50);
      default ->
          new SelfHealer.Repair.RestartProcess(
              "proc-unknown", Duration.ofSeconds(3));
    };
  }

  private SelfHealer.Repair selectMemoryLeakRepair(
      SelfHealer.Symptom.MemoryLeak leak,
      SelfHealer.Diagnosis diagnosis) {
    return switch (diagnosis) {
      case SelfHealer.Diagnosis.MemoryLeakProcess leakProc ->
          // Graceful shutdown + restart
          new SelfHealer.Repair.GracefulShutdown(
              leakProc.processId(), Duration.ofSeconds(10));
      case SelfHealer.Diagnosis.ResourceExhaustion exhaust ->
          // Try to scale up or rebalance
          new SelfHealer.Repair.ScaleUp("supervisor-1", 5);
      default ->
          new SelfHealer.Repair.RestartProcess(
              "proc-leak", Duration.ofSeconds(5));
    };
  }

  private SelfHealer.Repair selectExceptionStormRepair(
      SelfHealer.Symptom.ExceptionStorm storm,
      SelfHealer.Diagnosis diagnosis) {
    return switch (diagnosis) {
      case SelfHealer.Diagnosis.CascadingFailure cascading ->
          // Drain connections and restart
          new SelfHealer.Repair.DrainAndRestart(
              "proc-" + Math.abs(storm.commonException().hashCode() % 100),
              Duration.ofSeconds(5));
      case SelfHealer.Diagnosis.SoftwareBug bug ->
          // Graceful shutdown if known issue, restart
          new SelfHealer.Repair.GracefulShutdown(
              "proc-bug", Duration.ofSeconds(5));
      default ->
          new SelfHealer.Repair.RestartProcess(
              "proc-exception", Duration.ofSeconds(3));
    };
  }

  private SelfHealer.Repair selectCpuSaturationRepair(
      SelfHealer.Symptom.CpuSaturation saturation,
      SelfHealer.Diagnosis diagnosis) {
    return switch (diagnosis) {
      case SelfHealer.Diagnosis.ResourceExhaustion exhaust ->
          // Scale up first, then rebalance if that doesn't help
          new SelfHealer.Repair.ScaleUp("supervisor-hot", 5);
      default ->
          new SelfHealer.Repair.Rebalance(
              List.of("proc-a", "proc-b", "proc-c"), 40);
    };
  }

  private SelfHealer.Repair selectCascadingCrashRepair(
      SelfHealer.Symptom.CascadingCrash crash,
      SelfHealer.Diagnosis diagnosis) {
    return switch (diagnosis) {
      case SelfHealer.Diagnosis.CascadingFailure cascading ->
          if (cascading.isRepairable()) {
            // Drain and restart the root cause
            new SelfHealer.Repair.DrainAndRestart(
                "proc-root", Duration.ofSeconds(5));
          } else {
            // Non-repairable; failover to standby
            new SelfHealer.Repair.Failover("crashed-service", "standby-supervisor");
          }
      default ->
          new SelfHealer.Repair.RestartNode(
              "node-cascade", Duration.ofSeconds(10));
    };
  }

  private SelfHealer.Repair selectCircuitBreakerRepair(
      SelfHealer.Symptom.CircuitBreakerTrip trip,
      SelfHealer.Diagnosis diagnosis) {
    return switch (diagnosis) {
      case SelfHealer.Diagnosis.ExternalDependencyFailure external ->
          // Open the circuit to fail-fast
          new SelfHealer.Repair.CircuitBreakerOpen(
              trip.circuitName(), Duration.ofSeconds(30));
      default ->
          new SelfHealer.Repair.CircuitBreakerOpen(
              trip.circuitName(), Duration.ofSeconds(60));
    };
  }

  private SelfHealer.Repair selectDeadlockRepair(
      SelfHealer.Symptom.DeadlockDetected deadlock,
      SelfHealer.Diagnosis diagnosis) {
    // Deadlocks require forceful restart
    return new SelfHealer.Repair.RestartNode(
        "node-deadlock", Duration.ofSeconds(1));
  }
}
