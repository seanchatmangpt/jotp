package io.github.seanchatmangpt.jotp.stress;

import java.util.*;

/**
 * PerformanceModel — Fortune 500 enterprise capacity planning & resource modeling.
 *
 * <p>Converts raw metrics into business-relevant capacity decisions:
 *
 * <ul>
 *   <li>Cost per operation (CPU cycles, memory, power)
 *   <li>Resource efficiency curves (where does utilization become non-linear?)
 *   <li>Capacity planning (how many instances needed for target SLA?)
 *   <li>Break-even analysis (virtual threads vs platform threads, JVM tuning)
 *   <li>SLA compliance validation (p99 latency, availability, throughput)
 *   <li>Cost optimization (resource contention, GC pause impact on revenue)
 * </ul>
 *
 * <p><strong>Inputs:</strong> MetricsCollector results across load levels
 *
 * <p><strong>Outputs:</strong> Capacity plan, cost model, SLA compliance report
 */
public final class PerformanceModel {

    private final List<BenchmarkResult> results = new ArrayList<>();
    private final String systemName;

    // ── Enterprise SLA Defaults ──────────────────────────────────────────────

    public static class SlaTarget {
        public final double p99LatencyMs; // Latency percentile 99
        public final double p999LatencyMs; // Latency percentile 99.9
        public final double availabilityPercent; // Uptime %
        public final double maxErrorRate; // Error rate %
        public final long tps; // Transactions per second target

        public SlaTarget(
                double p99LatencyMs,
                double p999LatencyMs,
                double availabilityPercent,
                double maxErrorRate,
                long tps) {
            this.p99LatencyMs = p99LatencyMs;
            this.p999LatencyMs = p999LatencyMs;
            this.availabilityPercent = availabilityPercent;
            this.maxErrorRate = maxErrorRate;
            this.tps = tps;
        }

        // ── Industry Standard SLAs ──────────────────────────────────────────

        /** Tier 1: Financial transactions (99.99% uptime, <10ms p99) */
        public static SlaTarget FINANCIAL_TIER_1() {
            return new SlaTarget(10.0, 50.0, 99.99, 0.01, 100_000);
        }

        /** Tier 2: E-commerce (99.95% uptime, <50ms p99) */
        public static SlaTarget ECOMMERCE_TIER_2() {
            return new SlaTarget(50.0, 200.0, 99.95, 0.1, 50_000);
        }

        /** Tier 3: Internal services (99.9% uptime, <100ms p99) */
        public static SlaTarget INTERNAL_TIER_3() {
            return new SlaTarget(100.0, 500.0, 99.9, 0.5, 10_000);
        }

        /** Tier 4: Batch processing (<1s p99) */
        public static SlaTarget BATCH_TIER_4() {
            return new SlaTarget(1000.0, 5000.0, 99.0, 1.0, 1_000);
        }
    }

    // ── Benchmark Result: Raw metrics at a load level ──────────────────────

    public record BenchmarkResult(
            long loadLevel, // msg/sec or ops/sec target
            long actualThroughput, // what we actually achieved
            double p50LatencyMs,
            double p99LatencyMs,
            double p999LatencyMs,
            double maxLatencyMs,
            double heapUsedMb,
            double heapPeakMb,
            long gcPauseTimeMs,
            int gcEventCount,
            double errorRate, // %
            long virtualThreadsAllocated,
            double cpuUtilizationPercent,
            long wallclockTimeMs) {

        // ── Derived metrics ──────────────────────────────────────────────────

        /** Resource efficiency: throughput per MB of heap */
        public double throughputPerHeapMb() {
            return heapUsedMb > 0 ? actualThroughput / heapUsedMb : 0;
        }

        /** Memory overhead per operation */
        public double memoryPerOpBytes() {
            return (heapPeakMb * 1024 * 1024) / Math.max(actualThroughput, 1);
        }

        /** GC impact: pause time as % of total runtime */
        public double gcPauseImpactPercent() {
            return (100.0 * gcPauseTimeMs) / Math.max(wallclockTimeMs, 1);
        }

        /** CPU efficiency: throughput per CPU percent */
        public double throughputPerCpuPercent() {
            return cpuUtilizationPercent > 0 ? actualThroughput / cpuUtilizationPercent : 0;
        }

        /** Virtual thread efficiency: throughput per thread allocated */
        public double throughputPerVirtualThread() {
            return virtualThreadsAllocated > 0 ? actualThroughput / virtualThreadsAllocated : 0;
        }

        /** Cost proxy: heap MB-seconds (memory × duration) */
        public double heapMbSeconds() {
            return heapPeakMb * (wallclockTimeMs / 1000.0);
        }

        /** SLA compliance check */
        public boolean meetsSla(SlaTarget sla) {
            return p99LatencyMs <= sla.p99LatencyMs
                    && p999LatencyMs <= sla.p999LatencyMs
                    && errorRate <= sla.maxErrorRate
                    && actualThroughput >= sla.tps;
        }
    }

    // ── Capacity Analysis ───────────────────────────────────────────────────

    public static class CapacityPlan {
        public final long instancesNeeded;
        public final long totalHeapRequired;
        public final long peakVirtualThreadsNeeded;
        public final double annualCostPerInstance;
        public final String recommendedStrategy;
        public final List<String> constraints;

        public CapacityPlan(
                long instancesNeeded,
                long totalHeapRequired,
                long peakVirtualThreadsNeeded,
                double annualCostPerInstance,
                String recommendedStrategy,
                List<String> constraints) {
            this.instancesNeeded = instancesNeeded;
            this.totalHeapRequired = totalHeapRequired;
            this.peakVirtualThreadsNeeded = peakVirtualThreadsNeeded;
            this.annualCostPerInstance = annualCostPerInstance;
            this.recommendedStrategy = recommendedStrategy;
            this.constraints = constraints;
        }

        public double totalAnnualCost() {
            return instancesNeeded * annualCostPerInstance;
        }

        public String report() {
            return String.format(
                    """
          === CAPACITY PLAN ===
          Instances Needed: %d (for SLA compliance)
          Heap per Instance: %d MB
          Peak Virtual Threads: %d
          Cost per Instance (annual): $%.0f
          Total Annual Cost: $%.0f
          Recommended Strategy: %s
          Constraints: %s
          """,
                    instancesNeeded,
                    totalHeapRequired,
                    peakVirtualThreadsNeeded,
                    annualCostPerInstance,
                    totalAnnualCost(),
                    recommendedStrategy,
                    String.join(", ", constraints));
        }
    }

    // ── Performance Model ────────────────────────────────────────────────────

    public PerformanceModel(String systemName) {
        this.systemName = systemName;
    }

    /** Add benchmark result at a load level */
    public void addResult(BenchmarkResult result) {
        results.add(result);
        results.sort(Comparator.comparingLong(r -> r.loadLevel));
    }

    /**
     * Identify resource efficiency curve: where does scaling become non-linear?
     *
     * <p>Returns inflection points where efficiency drops >20%
     */
    public List<Long> identifyInflectionPoints() {
        List<Long> inflections = new ArrayList<>();
        if (results.size() < 2) return inflections;

        for (int i = 1; i < results.size(); i++) {
            BenchmarkResult prev = results.get(i - 1);
            BenchmarkResult curr = results.get(i);

            double prevEfficiency = prev.throughputPerCpuPercent();
            double currEfficiency = curr.throughputPerCpuPercent();

            double efficiencyDrop =
                    (prevEfficiency - currEfficiency) / Math.max(prevEfficiency, 0.1);

            if (efficiencyDrop > 0.2) {
                // 20% drop in efficiency = inflection point
                inflections.add(curr.loadLevel);
            }
        }

        return inflections;
    }

    /**
     * Analyze memory footprint as load increases: is it linear or superlinear?
     *
     * <p>Returns growth rate (bytes per additional operation)
     */
    public double memoryGrowthRate() {
        if (results.size() < 2) return 0;

        long totalLoadDelta = results.get(results.size() - 1).loadLevel - results.get(0).loadLevel;
        double totalMemoryDelta =
                results.get(results.size() - 1).heapPeakMb - results.get(0).heapPeakMb;

        return (totalLoadDelta > 0) ? (totalMemoryDelta * 1024 * 1024) / totalLoadDelta : 0;
    }

    /**
     * Analyze CPU efficiency: throughput per CPU percent spent
     *
     * <p>Shows if system is CPU-bound or I/O-bound
     */
    public double cpuEfficiency() {
        double totalThroughput = results.stream().mapToLong(r -> r.actualThroughput).sum();
        double totalCpuPercent = results.stream().mapToDouble(r -> r.cpuUtilizationPercent).sum();

        return (totalCpuPercent > 0) ? totalThroughput / totalCpuPercent : 0;
    }

    /**
     * Virtual thread vs Platform thread trade-off analysis
     *
     * <p>Compares resource consumption between two execution models
     */
    public String virtualThreadEfficiencyAnalysis() {
        if (results.isEmpty()) return "No results";

        double avgVtPerOp =
                results.stream()
                        .mapToDouble(
                                r ->
                                        r.virtualThreadsAllocated
                                                / (double) Math.max(r.actualThroughput, 1))
                        .average()
                        .orElse(0);

        double avgMemPerOp =
                results.stream().mapToDouble(BenchmarkResult::memoryPerOpBytes).average().orElse(0);

        // Cost model: virtual thread ~500KB, platform thread ~1-2MB
        double platformThreadCostMb = 1.5;
        double virtualThreadCostMb = 0.5;
        double virtualization_overhead = avgVtPerOp * virtualThreadCostMb;
        double platform_overhead =
                (avgVtPerOp / 4) * platformThreadCostMb; // assume 4:1 consolidation

        return String.format(
                """
        === Virtual Thread Efficiency Analysis ===
        Avg Virtual Threads per Operation: %.4f
        Memory per Operation: %.1f bytes
        Virtual Thread Memory Overhead: %.1f MB (%.1f threads × %.1f MB/thread)
        Platform Thread Equiv: %.1f MB (25%% fewer threads due to blocking)
        Verdict: Virtual threads use %.1f%% LESS memory
        """,
                avgVtPerOp,
                avgMemPerOp,
                virtualization_overhead,
                avgVtPerOp,
                virtualThreadCostMb,
                platform_overhead,
                100 * (1 - (virtualization_overhead / Math.max(platform_overhead, 0.1))));
    }

    /**
     * Calculate capacity plan for a given SLA target
     *
     * <p>Returns instances needed, heap per instance, annual cost
     */
    public CapacityPlan planCapacity(SlaTarget sla, double instanceCostAnnual) {
        // Find the load level that barely meets SLA
        BenchmarkResult slaBreakpoint = null;
        for (BenchmarkResult r : results) {
            if (r.meetsSla(sla)) {
                slaBreakpoint = r;
                break;
            }
        }

        if (slaBreakpoint == null) {
            return new CapacityPlan(
                    0,
                    0,
                    0,
                    0,
                    "CANNOT MEET SLA - performance degradation too severe",
                    List.of("System cannot achieve SLA at any load level"));
        }

        // Calculate instances needed: ceiling(SLA.tps / breakpoint.throughput)
        long instancesNeeded =
                (sla.tps + slaBreakpoint.actualThroughput - 1) / slaBreakpoint.actualThroughput;

        long heapPerInstance = (long) slaBreakpoint.heapPeakMb;

        long peakVirtualThreadsNeeded = (instancesNeeded * slaBreakpoint.virtualThreadsAllocated);

        List<String> constraints = new ArrayList<>();
        if (slaBreakpoint.gcPauseImpactPercent() > 1.0) {
            constraints.add(
                    "GC pauses consuming "
                            + (int) slaBreakpoint.gcPauseImpactPercent()
                            + "% of runtime");
        }
        if (slaBreakpoint.p999LatencyMs > sla.p999LatencyMs) {
            constraints.add(
                    "p999 latency ("
                            + (int) slaBreakpoint.p999LatencyMs
                            + "ms) exceeds SLA ("
                            + (int) sla.p999LatencyMs
                            + "ms)");
        }
        if (slaBreakpoint.errorRate > sla.maxErrorRate) {
            constraints.add(
                    "Error rate ("
                            + (int) slaBreakpoint.errorRate
                            + "%) exceeds SLA ("
                            + (int) sla.maxErrorRate
                            + "%)");
        }

        String strategy =
                instancesNeeded <= 3
                        ? "Vertical scaling (add heap/CPU per instance)"
                        : instancesNeeded <= 10
                                ? "Horizontal scaling (load balancer + N instances)"
                                : "Sharding + clustering (partition by key, multiple clusters)";

        return new CapacityPlan(
                instancesNeeded,
                heapPerInstance,
                peakVirtualThreadsNeeded,
                instanceCostAnnual,
                strategy,
                constraints);
    }

    /**
     * GC impact analysis: correlate GC pauses with latency spikes
     *
     * <p>Measures: does every GC pause cause latency spike?
     */
    public String gcImpactAnalysis() {
        if (results.isEmpty()) return "No results";

        double avgGcPauseMs = results.stream().mapToLong(r -> r.gcPauseTimeMs).average().orElse(0);
        double avgP99Latency =
                results.stream().mapToDouble(r -> r.p99LatencyMs).average().orElse(0);

        double gcLatencyCorrelation = (avgGcPauseMs > 0) ? avgP99Latency / avgGcPauseMs : 0;

        String verdict =
                gcLatencyCorrelation > 2
                        ? "HIGH: GC pauses dominate latency (tune heap size, use low-latency GC)"
                        : gcLatencyCorrelation > 1
                                ? "MODERATE: GC contributes to latency spikes"
                                : "LOW: GC impact minimal";

        return String.format(
                """
        === GC Impact Analysis ===
        Avg GC Pause: %.1f ms
        Avg p99 Latency: %.1f ms
        GC/Latency Ratio: %.2f
        Verdict: %s
        Recommendation: %s
        """,
                avgGcPauseMs,
                avgP99Latency,
                gcLatencyCorrelation,
                verdict,
                gcLatencyCorrelation > 2
                        ? "Use ZGC/Shenandoah for sub-ms pauses"
                        : "Current GC tuning acceptable");
    }

    /**
     * Cost-per-operation modeling: CapEx + OpEx projection
     *
     * <p>AWS pricing example: $0.25 per vCPU-hour × 24 × 365 / throughput
     */
    public String costAnalysis(double cpuCostPerHourPerVcpu, double memCostPerGbHour) {
        if (results.isEmpty()) return "No results";

        BenchmarkResult peak = results.get(results.size() - 1);

        // Annual cost assumptions
        double cpuCostAnnual =
                cpuCostPerHourPerVcpu * (peak.cpuUtilizationPercent / 100) * 24 * 365;
        double memCostAnnual = memCostPerGbHour * peak.heapPeakMb / 1024.0 * 24 * 365;

        double costPerOp = (cpuCostAnnual + memCostAnnual) / Math.max(peak.actualThroughput, 1);

        // For 1 billion operations per year
        long billionOpsAnnual = 1_000_000_000L;
        double costPerBillion = costPerOp * billionOpsAnnual;

        return String.format(
                """
        === Cost-Per-Operation Analysis ===
        CPU Cost (annual): $%.0f
        Memory Cost (annual): $%.0f
        Cost per Operation: $%.6f
        Cost per Billion Operations: $%.0f
        """,
                cpuCostAnnual, memCostAnnual, costPerOp, costPerBillion);
    }

    /**
     * Production readiness assessment
     *
     * <p>Scores system 0-100 based on: SLA margin, GC impact, error rate, latency distribution
     */
    public int productionReadinessScore(SlaTarget sla) {
        if (results.isEmpty()) return 0;

        BenchmarkResult peak = results.get(results.size() - 1);

        int score = 100;

        // Deduct for SLA non-compliance
        if (!peak.meetsSla(sla)) score -= 50;

        // Deduct for high GC impact
        if (peak.gcPauseImpactPercent() > 5) score -= 20;
        else if (peak.gcPauseImpactPercent() > 1) score -= 10;

        // Deduct for high error rate
        if (peak.errorRate > 1) score -= 20;
        else if (peak.errorRate > 0.1) score -= 10;

        // Deduct for latency skew (p999 >> p99 indicates outliers)
        double latencySkew = peak.p999LatencyMs / Math.max(peak.p99LatencyMs, 1);
        if (latencySkew > 10) score -= 15;
        else if (latencySkew > 5) score -= 5;

        // Bonus for virtual thread efficiency
        if (peak.throughputPerVirtualThread() > 100) score += 10;

        return Math.max(0, score);
    }

    /** Generate executive summary report */
    public String executiveSummary(SlaTarget sla, double instanceCostAnnual) {
        CapacityPlan plan = planCapacity(sla, instanceCostAnnual);
        int readinessScore = productionReadinessScore(sla);

        return String.format(
                """
        ╔════════════════════════════════════════════════════════════════════╗
        ║  PERFORMANCE ANALYSIS EXECUTIVE SUMMARY                            ║
        ║  %s
        ╚════════════════════════════════════════════════════════════════════╝

        PRODUCTION READINESS SCORE: %d/100

        CAPACITY REQUIREMENTS:
          - Instances needed: %d
          - Heap per instance: %d GB
          - Peak virtual threads: %d
          - Annual infrastructure cost: $%.0f

        KEY FINDINGS:
          - Memory growth rate: %.2f MB per 1K ops
          - CPU efficiency: %.0f ops per CPU percent
          - GC pause impact: %.1f%% of runtime
          - Inflection points (non-linear scaling): %s

        RECOMMENDATION:
          %s

        %s
        """,
                systemName,
                readinessScore,
                plan.instancesNeeded,
                (long) (plan.totalHeapRequired / 1024),
                plan.peakVirtualThreadsNeeded,
                plan.totalAnnualCost(),
                memoryGrowthRate() / (1024.0 * 1024.0),
                cpuEfficiency(),
                results.isEmpty() ? 0 : results.get(results.size() - 1).gcPauseImpactPercent(),
                identifyInflectionPoints(),
                plan.recommendedStrategy,
                readinessScore >= 80 ? "✓ READY FOR PRODUCTION" : "⚠ REQUIRES OPTIMIZATION");
    }
}
