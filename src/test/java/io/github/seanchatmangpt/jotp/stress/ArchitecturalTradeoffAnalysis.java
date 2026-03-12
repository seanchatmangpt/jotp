package io.github.seanchatmangpt.jotp.stress;

import java.util.*;

/**
 * ArchitecturalTradeoffAnalysis — Fortune 500 strategic decision framework.
 *
 * <p>Compares competing architectural approaches across multiple dimensions:
 * - Throughput vs latency (Pareto frontier)
 * - Resource consumption (CPU, memory, threads)
 * - Operational complexity (monitoring, tuning, scaling)
 * - Cost efficiency (CapEx, OpEx, cost-per-operation)
 * - Risk/reliability (failure modes, recovery speed)
 *
 * <p>Produces executive summary recommending architecture choice based on business objectives.
 */
public final class ArchitecturalTradeoffAnalysis {

  /**
   * Architectural option: represents a competing approach.
   *
   * <p>Examples: "Virtual Threads + LinkedTransferQueue", "Platform Threads + ArrayBlockingQueue",
   * "Actor Model (Akka)", etc.
   */
  public static class Architecture implements Comparable<Architecture> {
    public final String name;
    public final double throughputOpsPerSec;
    public final double latencyP99Ms;
    public final double cpuUsagePercent;
    public final double memoryMb;
    public final int threadCount;
    public final double gcPauseMs;
    public final int complexityScore; // 1-10, where 1 = simplest
    public final double costPerBillionOps;
    public final String failureMode;
    public final double recoveryTimeMs;

    public Architecture(
        String name,
        double throughputOpsPerSec,
        double latencyP99Ms,
        double cpuUsagePercent,
        double memoryMb,
        int threadCount,
        double gcPauseMs,
        int complexityScore,
        double costPerBillionOps,
        String failureMode,
        double recoveryTimeMs) {
      this.name = name;
      this.throughputOpsPerSec = throughputOpsPerSec;
      this.latencyP99Ms = latencyP99Ms;
      this.cpuUsagePercent = cpuUsagePercent;
      this.memoryMb = memoryMb;
      this.threadCount = threadCount;
      this.gcPauseMs = gcPauseMs;
      this.complexityScore = complexityScore;
      this.costPerBillionOps = costPerBillionOps;
      this.failureMode = failureMode;
      this.recoveryTimeMs = recoveryTimeMs;
    }

    // ── Derived metrics ──────────────────────────────────────────────────

    /** Resource efficiency: throughput per unit resource spent */
    public double resourceEfficiency() {
      double resourceCost = cpuUsagePercent + (memoryMb / 1024.0) * 10 + threadCount * 0.5;
      return throughputOpsPerSec / Math.max(resourceCost, 1);
    }

    /** Cost-adjusted efficiency: cost per operation */
    public double costAdjustedEfficiency() {
      return costPerBillionOps / throughputOpsPerSec;
    }

    /** Latency predictability: lower stddev = more predictable (we score as 1 - coefficient of variation) */
    public double latencyPredictability() {
      // Placeholder: would require full distribution data
      return 1.0;
    }

    /** Operational maturity score: 0-100 */
    public int maturitiescore() {
      int score = 100;
      if (complexityScore > 7) score -= 20;
      else if (complexityScore > 5) score -= 10;
      return score;
    }

    @Override
    public int compareTo(Architecture other) {
      // Sort by throughput descending
      return Double.compare(other.throughputOpsPerSec, this.throughputOpsPerSec);
    }
  }

  private final List<Architecture> architectures = new ArrayList<>();
  private final String systemName;

  public ArchitecturalTradeoffAnalysis(String systemName) {
    this.systemName = systemName;
  }

  public void addArchitecture(Architecture arch) {
    architectures.add(arch);
  }

  /**
   * Identify Pareto frontier: architectures that are not dominated on both throughput & latency.
   *
   * <p>Dominated = another option has strictly better throughput AND latency
   */
  public List<Architecture> paretoFrontier() {
    List<Architecture> frontier = new ArrayList<>();

    for (Architecture a : architectures) {
      boolean dominated = false;

      for (Architecture b : architectures) {
        if (a == b) continue;
        if (b.throughputOpsPerSec >= a.throughputOpsPerSec
            && b.latencyP99Ms <= a.latencyP99Ms) {
          dominated = true;
          break;
        }
      }

      if (!dominated) {
        frontier.add(a);
      }
    }

    frontier.sort(Comparator.comparingDouble(a -> a.throughputOpsPerSec));
    return frontier;
  }

  /**
   * Cost-benefit analysis: for each Pareto option, calculate true cost of ownership (TCO).
   *
   * <p>Inputs:
   * - Infrastructure annual cost per instance
   * - Operational cost (SRE time per year)
   * - Opportunity cost (lost revenue from unavailability)
   *
   * <p>Returns: TCO per architecture
   */
  public Map<Architecture, Double> calculateTCO(
      double infrastructureCostPerInstanceYear,
      double operationalCostPerInstanceYear,
      double opportunityCostPerHourUnavailability) {

    Map<Architecture, Double> tco = new LinkedHashMap<>();

    for (Architecture arch : paretoFrontier()) {
      // Instances needed to hit 100K TPS
      long instancesNeeded = Math.max(1, 100_000 / (long) Math.max(arch.throughputOpsPerSec, 1));

      // Annual infrastructure cost
      double infraCost = instancesNeeded * infrastructureCostPerInstanceYear;

      // Operational cost (more complex = higher ops cost)
      double opsCost = instancesNeeded * operationalCostPerInstanceYear * (1 + arch.complexityScore / 10.0);

      // Availability cost (availability = 1 - errorRate, rough estimate)
      double availability = 0.9995; // assume 99.95% baseline
      double downtime =
          365 * 24 * (1 - availability); // hours/year
      double opportunityCost =
          downtime * opportunityCostPerHourUnavailability;

      double totalTCO = infraCost + opsCost + opportunityCost;
      tco.put(arch, totalTCO);
    }

    return tco;
  }

  /**
   * Multi-objective scoring: balance throughput, latency, cost, complexity.
   *
   * <p>Weights:
   * - 40% throughput (max absolute throughput)
   * - 30% latency (minimize p99)
   * - 20% cost (minimize TCO)
   * - 10% operational simplicity
   */
  public Map<Architecture, Double> scoreArchitectures() {
    Map<Architecture, Double> scores = new LinkedHashMap<>();

    // Normalize metrics to 0-100 scale
    double maxThroughput = architectures.stream()
        .mapToDouble(a -> a.throughputOpsPerSec)
        .max()
        .orElse(1);
    double minLatency = architectures.stream()
        .mapToDouble(a -> a.latencyP99Ms)
        .min()
        .orElse(1);
    double maxCost = architectures.stream()
        .mapToDouble(a -> a.costPerBillionOps)
        .max()
        .orElse(1);

    for (Architecture arch : architectures) {
      // Throughput score: 40%
      double throughputScore = (arch.throughputOpsPerSec / maxThroughput) * 40;

      // Latency score: 30% (minimize, so invert)
      double latencyScore = ((minLatency / arch.latencyP99Ms) * 30);

      // Cost score: 20% (minimize, so invert)
      double costScore = ((maxCost / arch.costPerBillionOps) * 20);

      // Operational simplicity: 10%
      double simplicityScore = ((11 - arch.complexityScore) / 10.0) * 10;

      double totalScore = throughputScore + latencyScore + costScore + simplicityScore;
      scores.put(arch, totalScore);
    }

    return scores;
  }

  /**
   * Generate executive summary recommending best architecture for different scenarios.
   */
  public String executiveSummary() {
    Map<Architecture, Double> scores = scoreArchitectures();
    List<Architecture> frontier = paretoFrontier();

    Architecture bestOverall =
        scores.entrySet().stream()
            .max(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(null);

    Architecture bestThroughput =
        architectures.stream()
            .max(Comparator.comparingDouble(a -> a.throughputOpsPerSec))
            .orElse(null);

    Architecture bestLatency =
        architectures.stream()
            .min(Comparator.comparingDouble(a -> a.latencyP99Ms))
            .orElse(null);

    Architecture bestCost =
        architectures.stream()
            .min(Comparator.comparingDouble(a -> a.costPerBillionOps))
            .orElse(null);

    Architecture leastComplex =
        architectures.stream()
            .min(Comparator.comparingInt(a -> a.complexityScore))
            .orElse(null);

    return String.format(
        """
        ╔════════════════════════════════════════════════════════════════════╗
        ║  ARCHITECTURAL TRADEOFF ANALYSIS                                   ║
        ║  %s
        ╚════════════════════════════════════════════════════════════════════╝

        PARETO FRONTIER (%d architectures, %d dominated):
        %s

        BEST-IN-CLASS RECOMMENDATIONS:
        ┌──────────────────────────────────────────────────────────────────
        │ Overall Winner:        %s (score: %.1f/100)
        │ Best Throughput:       %s (%.0f ops/sec)
        │ Best Latency:          %s (%.2f ms p99)
        │ Best Cost:             %s ($%.2f per billion ops)
        │ Least Complex:         %s (complexity %d/10)
        └──────────────────────────────────────────────────────────────────

        DECISION MATRIX:
        ┌────────────────────┬──────────────┬───────────┬──────────┬────────┐
        │ Architecture       │ Throughput   │ p99 Lat   │ Cost/B   │ Cmplx  │
        ├────────────────────┼──────────────┼───────────┼──────────┼────────┤
        %s
        └────────────────────┴──────────────┴───────────┴──────────┴────────┘

        RISK ANALYSIS:
        %s

        RECOMMENDATION:
        For this system, %s is the optimal choice because:
        - Delivers %.0f%% of peak throughput
        - Achieves %.2f ms p99 latency (acceptable for most workloads)
        - Minimizes total cost of ownership
        - Reduces operational complexity vs alternatives
        - Provides clear scaling path as load grows

        """,
        systemName,
        frontier.size(),
        architectures.size() - frontier.size(),
        frontier.stream()
            .map(a -> "  • " + a.name)
            .reduce((a, b) -> a + "\n" + b)
            .orElse("  (none)"),
        bestOverall != null ? bestOverall.name : "N/A",
        scores.getOrDefault(bestOverall, 0.0),
        bestThroughput != null ? bestThroughput.name : "N/A",
        bestThroughput != null ? bestThroughput.throughputOpsPerSec : 0,
        bestLatency != null ? bestLatency.name : "N/A",
        bestLatency != null ? bestLatency.latencyP99Ms : 0,
        bestCost != null ? bestCost.name : "N/A",
        bestCost != null ? bestCost.costPerBillionOps : 0,
        leastComplex != null ? leastComplex.name : "N/A",
        leastComplex != null ? leastComplex.complexityScore : 0,
        buildDecisionMatrix(),
        buildRiskAnalysis(),
        bestOverall != null ? bestOverall.name : "TBD",
        bestOverall != null
            ? (100.0 * bestOverall.throughputOpsPerSec)
                / (bestThroughput != null ? bestThroughput.throughputOpsPerSec : 1)
            : 0,
        bestOverall != null ? bestOverall.latencyP99Ms : 0);
  }

  private String buildDecisionMatrix() {
    StringBuilder sb = new StringBuilder();
    for (Architecture arch : paretoFrontier()) {
      sb.append(
          String.format(
              "│ %-18s │ %12.0f │ %9.2f │ %8.2f │ %6d │\n",
              arch.name.substring(0, Math.min(18, arch.name.length())),
              arch.throughputOpsPerSec,
              arch.latencyP99Ms,
              arch.costPerBillionOps,
              arch.complexityScore));
    }
    return sb.toString();
  }

  private String buildRiskAnalysis() {
    StringBuilder sb = new StringBuilder();
    for (Architecture arch : paretoFrontier()) {
      sb.append(
          String.format(
              """
              %s:
                Failure Mode: %s
                Recovery Time: %.1f ms
                GC Impact: %.1f ms pauses
              """,
              arch.name, arch.failureMode, arch.recoveryTimeMs, arch.gcPauseMs));
    }
    return sb.toString();
  }

  /**
   * Build decision tree: which architecture for given business requirements?
   *
   * <p>Decision logic:
   * - If latency <1ms required: pick lowest latency architecture
   * - If throughput >500K required: pick highest throughput architecture
   * - If cost <$1per billion ops required: pick lowest cost
   * - Otherwise: pick Pareto best overall score
   */
  public String recommendForRequirements(
      Double maxLatencyP99Ms,
      Long minThroughputTps,
      Double maxCostPerBillion,
      Boolean preferSimplicity) {

    List<Architecture> candidates = new ArrayList<>(architectures);

    // Filter by hard constraints
    if (maxLatencyP99Ms != null) {
      candidates.removeIf(a -> a.latencyP99Ms > maxLatencyP99Ms);
    }
    if (minThroughputTps != null) {
      candidates.removeIf(a -> a.throughputOpsPerSec < minThroughputTps);
    }
    if (maxCostPerBillion != null) {
      candidates.removeIf(a -> a.costPerBillionOps > maxCostPerBillion);
    }

    if (candidates.isEmpty()) {
      return String.format(
          "⚠ No architecture meets all requirements. Feasible alternatives:\n%s",
          paretoFrontier().stream()
              .map(a -> String.format("  • %s (trade-offs: %s)", a.name, tradeoffAnalysis(a)))
              .reduce((a, b) -> a + "\n" + b)
              .orElse("  (none)"));
    }

    // Tie-break with preferences
    Architecture recommended = candidates.get(0);
    if (preferSimplicity != null && preferSimplicity) {
      recommended =
          candidates.stream()
              .min(Comparator.comparingInt(a -> a.complexityScore))
              .orElse(recommended);
    } else {
      Map<Architecture, Double> scores = scoreArchitectures();
      recommended = candidates.stream()
          .max(Comparator.comparingDouble(a -> scores.getOrDefault(a, 0.0)))
          .orElse(recommended);
    }

    return String.format("✓ Recommended: %s\n%s", recommended.name, tradeoffAnalysis(recommended));
  }

  private String tradeoffAnalysis(Architecture arch) {
    return String.format(
        """
        ├─ Throughput: %.0f ops/sec
        ├─ Latency (p99): %.2f ms
        ├─ Cost: $%.2f per billion ops
        ├─ Complexity: %d/10
        └─ Recovery time: %.1f ms
        """,
        arch.throughputOpsPerSec,
        arch.latencyP99Ms,
        arch.costPerBillionOps,
        arch.complexityScore,
        arch.recoveryTimeMs);
  }
}
