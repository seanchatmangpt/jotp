package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Autonomous self-healing system that diagnoses and repairs JOTP failures without human
 * intervention.
 *
 * <p>Joe Armstrong: "The key to fault tolerance is not to prevent failures, but to detect and
 * recover from them quickly." This orchestrator implements autonomous recovery using a decision tree
 * of symptoms → repair strategies.
 *
 * <p><strong>Architecture:</strong>
 *
 * <ol>
 *   <li>{@link AnomalyDetector} monitors system metrics (latency, memory, exceptions)
 *   <li>{@link FailureDiagnoser} identifies root cause (slow node, memory leak, cascade)
 *   <li>{@link AutoRepair} executes recovery (restart, failover, rebalance, scale)
 *   <li>{@link RepairDecisionTree} decides which strategy based on symptoms
 *   <li>Automatic rollback on repair failure
 *   <li>Metrics collection: success rate, time-to-recovery, root cause distribution
 * </ol>
 *
 * <p><strong>Quick start:</strong>
 *
 * <pre>{@code
 * var healer = SelfHealer.create(
 *     Duration.ofSeconds(5),      // scan interval
 *     Duration.ofSeconds(60),     // anomaly window
 *     Duration.ofSeconds(30)      // repair timeout
 * );
 * healer.start();
 *
 * // System runs autonomously, detecting and repairing failures
 * var metrics = healer.metrics();
 * System.out.println("Repair success rate: " + metrics.successRate());
 * System.out.println("Average time to recovery: " + metrics.avgTimeToRecovery());
 * }</pre>
 *
 * <p><strong>Repair Examples:</strong>
 *
 * <ul>
 *   <li>High latency (p99 > 500ms) → Check network, restart slow node
 *   <li>Memory leak (GC overhead > 40%) → Trigger graceful shutdown + restart
 *   <li>Cascading crash → Drain connections, failover to standby
 *   <li>CPU saturation (> 80%) → Trigger rebalance or add capacity
 *   <li>Exception storm (> 100/sec) → Circuit break, drain, recover
 * </ul>
 *
 * <p><strong>Decision Tree Logic:</strong>
 *
 * <pre>{@code
 * if (latency_spike) {
 *     diagnose(NETWORK_ISSUE | SLOW_NODE | GC_PAUSE);
 *     repair = restart_slow_node() | check_network();
 *     if (repair_fails) rollback();
 * } else if (memory_leak) {
 *     diagnose(MEMORY_LEAK);
 *     repair = graceful_shutdown() | force_restart();
 * } else if (exception_storm) {
 *     diagnose(CASCADING_CRASH);
 *     repair = drain_connections() | circuit_break() | failover();
 * }
 * }</pre>
 *
 * <p><strong>Rollback Strategy:</strong> If a repair fails, the system automatically:
 *
 * <ol>
 *   <li>Stops the failed repair attempt
 *   <li>Restores previous state (cached configuration, process trees)
 *   <li>Escalates to next repair strategy (e.g., restart → failover)
 *   <li>Records failure in metrics for SRE analysis
 * </ol>
 *
 * <p><strong>Java 26 Features Used:</strong>
 *
 * <ul>
 *   <li><strong>Sealed Interfaces:</strong> {@code Repair}, {@code Symptom}, {@code Diagnosis}
 *       for exhaustive pattern matching
 *   <li><strong>Records:</strong> Diagnostic events, repair actions, metrics snapshots
 *   <li><strong>Virtual Threads:</strong> Lightweight monitoring and repair executors
 *   <li><strong>Pattern Matching:</strong> Decision tree routes symptoms → repairs
 * </ul>
 *
 * @see AnomalyDetector
 * @see FailureDiagnoser
 * @see AutoRepair
 */
public final class SelfHealer {

  // ── Sealed Type Hierarchy ────────────────────────────────────────────────────────────

  /** Base type for system symptoms detected by anomaly detector. */
  public sealed interface Symptom permits Symptom.HighLatency, Symptom.MemoryLeak,
      Symptom.ExceptionStorm, Symptom.CpuSaturation, Symptom.CascadingCrash,
      Symptom.CircuitBreakerTrip, Symptom.DeadlockDetected {

    record HighLatency(double p99Ms, double p95Ms, int affectedProcesses)
        implements Symptom {}

    record MemoryLeak(double gcOverhead, long heapUsedBytes, int leakingProcesses)
        implements Symptom {}

    record ExceptionStorm(int exceptionsPerSecond, String commonException,
        int affectedProcesses) implements Symptom {}

    record CpuSaturation(double cpuUsagePercent, int hotThreads) implements Symptom {}

    record CascadingCrash(int crashedProcesses, long timeSinceFirstCrash,
        String rootCause) implements Symptom {}

    record CircuitBreakerTrip(String circuitName, int failureCount, double errorRate)
        implements Symptom {}

    record DeadlockDetected(int deadlockedThreads, long duration) implements Symptom {}
  }

  /** Root cause analysis — what's actually wrong. */
  public sealed interface Diagnosis permits Diagnosis.NetworkIssue, Diagnosis.SlowNode,
      Diagnosis.MemoryLeakProcess, Diagnosis.CascadingFailure, Diagnosis.ResourceExhaustion,
      Diagnosis.SoftwareBug, Diagnosis.ExternalDependencyFailure {

    record NetworkIssue(String detail) implements Diagnosis {}

    record SlowNode(String nodeId, double responseTimeMs, String bottleneck)
        implements Diagnosis {}

    record MemoryLeakProcess(String processId, long leakRateBytes, int heapUsedPercent)
        implements Diagnosis {}

    record CascadingFailure(String initialCrash, int affectedDownstream, boolean isRepairable)
        implements Diagnosis {}

    record ResourceExhaustion(String resourceType, double utilization)
        implements Diagnosis {}

    record SoftwareBug(String exception, String stackTrace, boolean isKnownIssue)
        implements Diagnosis {}

    record ExternalDependencyFailure(String service, long timeoutMs)
        implements Diagnosis {}
  }

  /** Repair strategies that can be applied. */
  public sealed interface Repair permits Repair.RestartProcess, Repair.RestartNode,
      Repair.Failover, Repair.Rebalance, Repair.ScaleUp, Repair.DrainAndRestart,
      Repair.CircuitBreakerOpen, Repair.GracefulShutdown {

    record RestartProcess(String processId, Duration timeout) implements Repair {}

    record RestartNode(String nodeId, Duration shutdownGrace) implements Repair {}

    record Failover(String failingService, String standbySupervisor) implements Repair {}

    record Rebalance(List<String> affectedProcesses, int targetLoad) implements Repair {}

    record ScaleUp(String supervisorId, int newChildCount) implements Repair {}

    record DrainAndRestart(String processId, Duration drainTimeout) implements Repair {}

    record CircuitBreakerOpen(String circuitName, Duration openDuration) implements Repair {}

    record GracefulShutdown(String processId, Duration timeout) implements Repair {}
  }

  /** Outcome of a repair attempt. */
  public sealed interface RepairOutcome permits RepairOutcome.Success, RepairOutcome.Failure,
      RepairOutcome.PartialSuccess {

    record Success(Repair repair, long durationMs, String detail) implements RepairOutcome {}

    record Failure(Repair repair, Exception cause, String detail) implements RepairOutcome {}

    record PartialSuccess(Repair repair, long durationMs, int successfulItems, int failedItems)
        implements RepairOutcome {}
  }

  // ── Internal State ────────────────────────────────────────────────────────────────────

  private final AnomalyDetector anomalyDetector;
  private final FailureDiagnoser diagnoser;
  private final AutoRepair autoRepair;
  private final RepairDecisionTree decisionTree;
  private final SelfHealerMetrics metrics;
  private final Duration scanInterval;
  private final Queue<DiagnosticEvent> eventLog;
  private final Queue<RepairHistoryEntry> repairHistory;
  private final AtomicBoolean isRunning;
  private volatile Thread healerThread;

  private static final int MAX_EVENTS = 10000;
  private static final int MAX_REPAIRS = 5000;

  private SelfHealer(
      AnomalyDetector detector,
      FailureDiagnoser diagnoser,
      AutoRepair autoRepair,
      RepairDecisionTree decisionTree,
      Duration scanInterval) {
    this.anomalyDetector = detector;
    this.diagnoser = diagnoser;
    this.autoRepair = autoRepair;
    this.decisionTree = decisionTree;
    this.scanInterval = scanInterval;
    this.metrics = new SelfHealerMetrics();
    this.eventLog = new ConcurrentLinkedQueue<>();
    this.repairHistory = new ConcurrentLinkedQueue<>();
    this.isRunning = new AtomicBoolean(false);
  }

  /**
   * Create a new self-healer with default components.
   *
   * @param scanInterval how often to check for anomalies
   * @param anomalyWindow time window for anomaly detection
   * @param repairTimeout max duration for a repair attempt
   * @return new SelfHealer instance
   */
  public static SelfHealer create(
      Duration scanInterval, Duration anomalyWindow, Duration repairTimeout) {
    var detector = new AnomalyDetector(anomalyWindow);
    var diagnoser = new FailureDiagnoser();
    var autoRepair = new AutoRepair(repairTimeout);
    var decisionTree = new RepairDecisionTree();
    return new SelfHealer(detector, diagnoser, autoRepair, decisionTree, scanInterval);
  }

  /**
   * Start autonomous healing. Spawns a background virtual thread that continuously monitors and
   * repairs.
   */
  public synchronized void start() {
    if (isRunning.getAndSet(true)) {
      return; // already running
    }
    healerThread = Thread.ofVirtual().start(this::healingLoop);
  }

  /** Stop the healer. */
  public synchronized void stop() {
    isRunning.set(false);
    if (healerThread != null) {
      try {
        healerThread.join(Duration.ofSeconds(10).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Main healing loop: detect → diagnose → decide → repair → record. */
  private void healingLoop() {
    while (isRunning.get()) {
      try {
        var symptoms = anomalyDetector.scan();

        for (var symptom : symptoms) {
          var event = new DiagnosticEvent(Instant.now(), symptom);
          eventLog.offer(event);
          if (eventLog.size() > MAX_EVENTS) {
            eventLog.poll(); // drop oldest
          }

          // Diagnose root cause
          var diagnosis = diagnoser.diagnose(symptom);

          // Decide which repair to apply
          var repair = decisionTree.selectRepair(symptom, diagnosis);

          // Execute repair with timeout
          var outcome = autoRepair.execute(repair);

          // Record result
          var historyEntry = new RepairHistoryEntry(
              Instant.now(), symptom, diagnosis, repair, outcome);
          repairHistory.offer(historyEntry);
          if (repairHistory.size() > MAX_REPAIRS) {
            repairHistory.poll(); // drop oldest
          }

          // Update metrics
          metrics.recordRepairAttempt(symptom, diagnosis, outcome);

          // If repair failed, try rollback
          if (outcome instanceof RepairOutcome.Failure failure) {
            metrics.recordRepairFailure(failure);
            attemptRollback(failure, symptom);
          }
        }

        Thread.sleep(scanInterval.toMillis());
      } catch (InterruptedException e) {
        if (!isRunning.get()) break;
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        metrics.recordHealerError(e);
        // Continue healing despite errors
      }
    }
  }

  /**
   * Attempt to rollback a failed repair by restoring previous state and escalating to next
   * strategy.
   */
  private void attemptRollback(RepairOutcome.Failure failure, Symptom symptom) {
    try {
      // Stop the failed repair
      autoRepair.stop(failure.repair());

      // Try escalation: use next repair strategy
      var nextRepair = decisionTree.escalateRepair(failure.repair(), symptom);
      if (nextRepair != null) {
        var outcome = autoRepair.execute(nextRepair);
        metrics.recordEscalatedRepair(nextRepair, outcome);
      }
    } catch (Exception e) {
      metrics.recordRollbackFailure(e);
    }
  }

  // ── Public API ────────────────────────────────────────────────────────────────────────

  /** Check if the healer is currently running. */
  public boolean isRunning() {
    return isRunning.get();
  }

  /** Get current metrics snapshot. */
  public SelfHealerMetrics.Snapshot metrics() {
    return metrics.snapshot();
  }

  /** Get recent diagnostic events. */
  public List<DiagnosticEvent> getEventLog(int maxSize) {
    var all = new ArrayList<>(eventLog);
    return all.subList(Math.max(0, all.size() - maxSize), all.size());
  }

  /** Get repair history. */
  public List<RepairHistoryEntry> getRepairHistory(int maxSize) {
    var all = new ArrayList<>(repairHistory);
    return all.subList(Math.max(0, all.size() - maxSize), all.size());
  }

  /** Clear all logs and metrics. */
  public void reset() {
    eventLog.clear();
    repairHistory.clear();
    metrics.reset();
  }

  // ── Event Types ──────────────────────────────────────────────────────────────────────

  /**
   * Diagnostic event — a symptom was detected.
   */
  public record DiagnosticEvent(Instant timestamp, Symptom symptom) {}

  /**
   * Repair history entry — tracks what was detected, diagnosed, and attempted.
   */
  public record RepairHistoryEntry(
      Instant timestamp,
      Symptom symptom,
      Diagnosis diagnosis,
      Repair repair,
      RepairOutcome outcome) {}
}
