package io.github.seanchatmangpt.jotp.ai;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Metrics collection for self-healing operations.
 *
 * <p>Tracks:
 *
 * <ul>
 *   <li>Repair success/failure rates
 *   <li>Time-to-recovery (TTR) statistics
 *   <li>Root cause distribution
 *   <li>Most frequent symptoms
 *   <li>Repair strategy effectiveness
 * </ul>
 */
public final class SelfHealerMetrics {

  private final AtomicLong totalRepairs = new AtomicLong(0);
  private final AtomicLong successfulRepairs = new AtomicLong(0);
  private final AtomicLong failedRepairs = new AtomicLong(0);
  private final AtomicLong partialSuccessRepairs = new AtomicLong(0);
  private final AtomicLong escalatedRepairs = new AtomicLong(0);
  private final AtomicLong rollbackFailures = new AtomicLong(0);
  private final AtomicLong healerErrors = new AtomicLong(0);

  private final Queue<Long> repairDurations = new ConcurrentLinkedQueue<>();
  private final Queue<Long> ttRecoveries = new ConcurrentLinkedQueue<>();

  private final Map<String, AtomicInteger> symptomCounts = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> diagnosisCounts = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> repairCounts = new ConcurrentHashMap<>();

  private static final int MAX_DURATIONS = 1000;
  private volatile Instant startTime = Instant.now();

  void recordRepairAttempt(
      SelfHealer.Symptom symptom,
      SelfHealer.Diagnosis diagnosis,
      SelfHealer.RepairOutcome outcome) {
    totalRepairs.incrementAndGet();

    // Track by type
    symptomCounts.computeIfAbsent(
            symptom.getClass().getSimpleName(),
            _ -> new AtomicInteger(0))
        .incrementAndGet();
    diagnosisCounts.computeIfAbsent(
            diagnosis.getClass().getSimpleName(),
            _ -> new AtomicInteger(0))
        .incrementAndGet();

    // Track outcome
    switch (outcome) {
      case SelfHealer.RepairOutcome.Success success -> {
        successfulRepairs.incrementAndGet();
        repairCounts.computeIfAbsent(
                getRepairType(success.repair()),
                _ -> new AtomicInteger(0))
            .incrementAndGet();
        recordDuration(success.durationMs());
        ttRecoveries.offer(success.durationMs());
      }
      case SelfHealer.RepairOutcome.Failure failure -> {
        failedRepairs.incrementAndGet();
      }
      case SelfHealer.RepairOutcome.PartialSuccess partial -> {
        partialSuccessRepairs.incrementAndGet();
        recordDuration(partial.durationMs());
      }
    }
  }

  void recordEscalatedRepair(
      SelfHealer.Repair repair,
      SelfHealer.RepairOutcome outcome) {
    escalatedRepairs.incrementAndGet();
    if (outcome instanceof SelfHealer.RepairOutcome.Success success) {
      recordDuration(success.durationMs());
    }
  }

  void recordRepairFailure(SelfHealer.RepairOutcome.Failure failure) {
    // Already counted in recordRepairAttempt, but can add additional tracking here
  }

  void recordRollbackFailure(Exception e) {
    rollbackFailures.incrementAndGet();
  }

  void recordHealerError(Exception e) {
    healerErrors.incrementAndGet();
  }

  void reset() {
    totalRepairs.set(0);
    successfulRepairs.set(0);
    failedRepairs.set(0);
    partialSuccessRepairs.set(0);
    escalatedRepairs.set(0);
    rollbackFailures.set(0);
    healerErrors.set(0);
    repairDurations.clear();
    ttRecoveries.clear();
    symptomCounts.clear();
    diagnosisCounts.clear();
    repairCounts.clear();
    startTime = Instant.now();
  }

  /** Get a snapshot of current metrics. */
  public Snapshot snapshot() {
    return new Snapshot(
        totalRepairs.get(),
        successfulRepairs.get(),
        failedRepairs.get(),
        partialSuccessRepairs.get(),
        escalatedRepairs.get(),
        rollbackFailures.get(),
        healerErrors.get(),
        repairDurations.stream().mapToLong(Long::longValue).toArray(),
        ttRecoveries.stream().mapToLong(Long::longValue).toArray(),
        Map.copyOf(symptomCounts.entrySet().stream()
            .collect(ConcurrentHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue().get()),
                Map::putAll)),
        Map.copyOf(diagnosisCounts.entrySet().stream()
            .collect(ConcurrentHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue().get()),
                Map::putAll)),
        Map.copyOf(repairCounts.entrySet().stream()
            .collect(ConcurrentHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue().get()),
                Map::putAll)));
  }

  // ── Helper Methods ────────────────────────────────────────────────────────────────────

  private void recordDuration(long millis) {
    repairDurations.offer(millis);
    if (repairDurations.size() > MAX_DURATIONS) {
      repairDurations.poll();
    }
  }

  private String getRepairType(SelfHealer.Repair repair) {
    return switch (repair) {
      case SelfHealer.Repair.RestartProcess _ -> "RestartProcess";
      case SelfHealer.Repair.RestartNode _ -> "RestartNode";
      case SelfHealer.Repair.Failover _ -> "Failover";
      case SelfHealer.Repair.Rebalance _ -> "Rebalance";
      case SelfHealer.Repair.ScaleUp _ -> "ScaleUp";
      case SelfHealer.Repair.DrainAndRestart _ -> "DrainAndRestart";
      case SelfHealer.Repair.CircuitBreakerOpen _ -> "CircuitBreakerOpen";
      case SelfHealer.Repair.GracefulShutdown _ -> "GracefulShutdown";
    };
  }

  /**
   * Snapshot of metrics at a point in time.
   */
  public record Snapshot(
      long totalRepairs,
      long successfulRepairs,
      long failedRepairs,
      long partialSuccessRepairs,
      long escalatedRepairs,
      long rollbackFailures,
      long healerErrors,
      long[] repairDurations,
      long[] timeToRecoveries,
      Map<String, Integer> symptomDistribution,
      Map<String, Integer> diagnosisDistribution,
      Map<String, Integer> repairStrategyDistribution) {

    public double successRate() {
      if (totalRepairs == 0) return 0;
      return (double) successfulRepairs / totalRepairs;
    }

    public double avgTimeToRecovery() {
      if (timeToRecoveries.length == 0) return 0;
      var sum = Arrays.stream(timeToRecoveries).sum();
      return (double) sum / timeToRecoveries.length;
    }

    public long minTimeToRecovery() {
      if (timeToRecoveries.length == 0) return 0;
      return Arrays.stream(timeToRecoveries).min().orElse(0);
    }

    public long maxTimeToRecovery() {
      if (timeToRecoveries.length == 0) return 0;
      return Arrays.stream(timeToRecoveries).max().orElse(0);
    }

    public double avgRepairDuration() {
      if (repairDurations.length == 0) return 0;
      var sum = Arrays.stream(repairDurations).sum();
      return (double) sum / repairDurations.length;
    }

    public String getMostFrequentSymptom() {
      return symptomDistribution.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .map(Map.Entry::getKey)
          .orElse("None");
    }

    public String getMostFrequentDiagnosis() {
      return diagnosisDistribution.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .map(Map.Entry::getKey)
          .orElse("None");
    }

    public String getMostEffectiveRepairStrategy() {
      return repairStrategyDistribution.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .map(Map.Entry::getKey)
          .orElse("None");
    }

    @Override
    public String toString() {
      return String.format(
          """
          SelfHealer Metrics:
            Total Repairs: %d
            Success Rate: %.1f%%
            Failed: %d (%.1f%%)
            Partial Success: %d (%.1f%%)
            Escalated: %d
            Rollback Failures: %d
            Healer Errors: %d
            Avg Time-to-Recovery: %.0fms (min: %dms, max: %dms)
            Avg Repair Duration: %.0fms
            Most Frequent Symptom: %s
            Most Frequent Diagnosis: %s
            Most Effective Strategy: %s
          """,
          totalRepairs,
          successRate() * 100,
          failedRepairs,
          totalRepairs > 0 ? (double) failedRepairs / totalRepairs * 100 : 0,
          partialSuccessRepairs,
          totalRepairs > 0 ? (double) partialSuccessRepairs / totalRepairs * 100 : 0,
          escalatedRepairs,
          rollbackFailures,
          healerErrors,
          avgTimeToRecovery(),
          minTimeToRecovery(),
          maxTimeToRecovery(),
          avgRepairDuration(),
          getMostFrequentSymptom(),
          getMostFrequentDiagnosis(),
          getMostEffectiveRepairStrategy());
    }
  }
}
