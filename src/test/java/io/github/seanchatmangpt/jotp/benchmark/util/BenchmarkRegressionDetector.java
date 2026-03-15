package io.github.seanchatmangpt.jotp.benchmark.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects performance regressions between benchmark runs.
 *
 * <p>This utility loads baseline benchmark results from JSON files, compares them against current
 * results, and identifies statistically significant performance changes.
 *
 * <p>Regression thresholds:
 *
 * <ul>
 *   <li>CRITICAL: >10% degradation
 *   <li>WARNING: >5% degradation
 *   <li>IMPROVEMENT: >5% improvement
 *   <li>STABLE: Within ±5%
 * </ul>
 */
public final class BenchmarkRegressionDetector {

    private static final double CRITICAL_THRESHOLD = 0.10; // 10%
    private static final double WARNING_THRESHOLD = 0.05; // 5%
    private static final double IMPROVEMENT_THRESHOLD = 0.05; // 5%

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BenchmarkRegressionDetector() {
        // Utility class
    }

    /**
     * Loads baseline benchmark results from a JSON file.
     *
     * <p>Expected JSON format:
     *
     * <pre>
     * {
     *   "benchmarks": [
     *     {
     *       "name": "benchmarkName",
     *       "score": 1234.56,
     *       "error": 12.34,
     *       "unit": "ns/op"
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param jsonFile the JSON file containing baseline results
     * @return parsed benchmark results
     * @throws IOException if the file cannot be read or parsed
     */
    public static BenchmarkResults loadBaseline(Path jsonFile) throws IOException {
        String json = Files.readString(jsonFile);
        BaselineData data = GSON.fromJson(json, BaselineData.class);
        return new BenchmarkResults(data.benchmarks());
    }

    /**
     * Compares current benchmark results against a baseline.
     *
     * <p>Performs statistical significance testing on each benchmark to identify regressions,
     * improvements, and stable results.
     *
     * @param baseline the baseline benchmark results
     * @param current the current benchmark results
     * @return regression report with detailed analysis
     */
    public static RegressionReport compare(BenchmarkResults baseline, BenchmarkResults current) {
        List<RegressionAlert> alerts = new ArrayList<>();

        for (BenchmarkResult currentResult : current.results()) {
            Optional<BenchmarkResult> baselineResult = baseline.find(currentResult.name());

            if (baselineResult.isPresent()) {
                BenchmarkResult baselineValue = baselineResult.get();
                double delta = calculateDelta(baselineValue.score(), currentResult.score());
                RegressionStatus status = determineStatus(delta);

                alerts.add(
                        new RegressionAlert(
                                currentResult.name(),
                                baselineValue.score(),
                                currentResult.score(),
                                delta,
                                status));
            } else {
                // New benchmark without baseline - mark as stable
                alerts.add(
                        new RegressionAlert(
                                currentResult.name(),
                                currentResult.score(),
                                currentResult.score(),
                                0.0,
                                RegressionStatus.STABLE));
            }
        }

        // Check for removed benchmarks
        for (BenchmarkResult baselineResult : baseline.results()) {
            if (current.find(baselineResult.name()).isEmpty()) {
                alerts.add(
                        new RegressionAlert(
                                baselineResult.name(),
                                baselineResult.score(),
                                -1.0, // Indicate missing result
                                -1.0,
                                RegressionStatus.MISSING));
            }
        }

        long regressions =
                alerts.stream().filter(a -> a.status() == RegressionStatus.REGRESSION).count();

        long improvements =
                alerts.stream().filter(a -> a.status() == RegressionStatus.IMPROVEMENT).count();

        RegressionReportStatus reportStatus = determineReportStatus(alerts);

        return new RegressionReport(alerts, regressions, improvements, reportStatus);
    }

    /**
     * Tests if a performance change is statistically significant.
     *
     * <p>A regression is considered significant if it exceeds the configured threshold and
     * represents a degradation in performance.
     *
     * @param baseline the baseline score (lower is better for time benchmarks)
     * @param current the current score
     * @param threshold the significance threshold (e.g., 0.05 for 5%)
     * @return true if the change represents a significant regression
     */
    public static boolean isSignificantRegression(
            double baseline, double current, double threshold) {
        double delta = calculateDelta(baseline, current);
        return delta > threshold && delta > 0;
    }

    /**
     * Calculates the percentage delta between baseline and current scores.
     *
     * <p>Positive delta indicates degradation (slower/less efficient). Negative delta indicates
     * improvement (faster/more efficient).
     *
     * @param baseline the baseline score
     * @param current the current score
     * @return percentage delta (e.g., 0.15 for 15% degradation)
     */
    private static double calculateDelta(double baseline, double current) {
        if (baseline == 0.0) {
            return current == 0.0 ? 0.0 : 1.0; // 100% degradation if baseline was perfect
        }
        return (current - baseline) / baseline;
    }

    /**
     * Determines the regression status based on delta percentage.
     *
     * @param delta the percentage delta
     * @return the regression status
     */
    private static RegressionStatus determineStatus(double delta) {
        if (delta > CRITICAL_THRESHOLD) {
            return RegressionStatus.CRITICAL_REGRESSION;
        } else if (delta > WARNING_THRESHOLD) {
            return RegressionStatus.REGRESSION;
        } else if (delta < -IMPROVEMENT_THRESHOLD) {
            return RegressionStatus.IMPROVEMENT;
        } else {
            return RegressionStatus.STABLE;
        }
    }

    /**
     * Determines the overall report status based on alerts.
     *
     * @param alerts the regression alerts
     * @return the overall report status
     */
    private static RegressionReportStatus determineReportStatus(List<RegressionAlert> alerts) {
        boolean hasCritical =
                alerts.stream().anyMatch(a -> a.status() == RegressionStatus.CRITICAL_REGRESSION);

        boolean hasRegression =
                alerts.stream().anyMatch(a -> a.status() == RegressionStatus.REGRESSION);

        boolean hasImprovement =
                alerts.stream().anyMatch(a -> a.status() == RegressionStatus.IMPROVEMENT);

        if (hasCritical) {
            return RegressionReportStatus.CRITICAL;
        } else if (hasRegression) {
            return RegressionReportStatus.WARNING;
        } else if (hasImprovement) {
            return RegressionReportStatus.IMPROVED;
        } else {
            return RegressionReportStatus.STABLE;
        }
    }

    /**
     * Benchmark results container.
     *
     * @param results the list of benchmark results
     */
    public record BenchmarkResults(List<BenchmarkResult> results) {
        public BenchmarkResults {
            results = List.copyOf(results);
        }

        public Optional<BenchmarkResult> find(String name) {
            return results.stream().filter(r -> r.name().equals(name)).findFirst();
        }
    }

    /**
     * Individual benchmark result.
     *
     * @param name the benchmark name
     * @param score the primary score (e.g., time per operation)
     * @param error the error margin
     * @param unit the unit of measurement
     */
    public record BenchmarkResult(String name, double score, double error, String unit) {
        public BenchmarkResult {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Benchmark name cannot be blank");
            }
            if (score < 0) {
                throw new IllegalArgumentException("Score cannot be negative");
            }
            if (error < 0) {
                throw new IllegalArgumentException("Error cannot be negative");
            }
        }
    }

    /**
     * Regression analysis report.
     *
     * @param alerts the regression alerts for each benchmark
     * @param regressions the number of regressions detected
     * @param improvements the number of improvements detected
     * @param status the overall report status
     */
    public record RegressionReport(
            List<RegressionAlert> alerts,
            long regressions,
            long improvements,
            RegressionReportStatus status) {
        public RegressionReport {
            alerts = List.copyOf(alerts);
        }

        public boolean hasRegressions() {
            return regressions > 0;
        }

        public boolean hasImprovements() {
            return improvements > 0;
        }

        public boolean isStable() {
            return status == RegressionReportStatus.STABLE;
        }

        public String summary() {
            return String.format(
                    "Regression Report: %d regressions, %d improvements, status=%s",
                    regressions, improvements, status);
        }
    }

    /**
     * Individual regression alert.
     *
     * @param benchmarkName the benchmark name
     * @param baseline the baseline score
     * @param current the current score
     * @param delta the percentage change
     * @param status the regression status
     */
    public record RegressionAlert(
            String benchmarkName,
            double baseline,
            double current,
            double delta,
            RegressionStatus status) {
        public String formattedDelta() {
            return String.format("%.2f%%", delta * 100);
        }

        public String description() {
            return switch (status) {
                case CRITICAL_REGRESSION ->
                        String.format(
                                "CRITICAL: %s degraded by %s (baseline: %.2f, current: %.2f)",
                                benchmarkName, formattedDelta(), baseline, current);
                case REGRESSION ->
                        String.format(
                                "WARNING: %s degraded by %s (baseline: %.2f, current: %.2f)",
                                benchmarkName, formattedDelta(), baseline, current);
                case IMPROVEMENT ->
                        String.format(
                                "IMPROVEMENT: %s improved by %s (baseline: %.2f, current: %.2f)",
                                benchmarkName, formattedDelta(), baseline, current);
                case STABLE ->
                        String.format(
                                "STABLE: %s unchanged (baseline: %.2f, current: %.2f)",
                                benchmarkName, baseline, current);
                case MISSING ->
                        String.format(
                                "MISSING: %s was present in baseline but not in current run (%.2f)",
                                benchmarkName, baseline);
            };
        }
    }

    /** Regression status for individual benchmarks. */
    public enum RegressionStatus {
        /** More than 10% degradation */
        CRITICAL_REGRESSION,
        /** Between 5% and 10% degradation */
        REGRESSION,
        /** More than 5% improvement */
        IMPROVEMENT,
        /** Within ±5% change */
        STABLE,
        /** Benchmark present in baseline but missing from current run */
        MISSING
    }

    /** Overall report status. */
    public enum RegressionReportStatus {
        /** Critical regressions detected */
        CRITICAL,
        /** Non-critical regressions detected */
        WARNING,
        /** Performance improvements detected, no regressions */
        IMPROVED,
        /** No significant changes */
        STABLE
    }

    /** Internal data structure for JSON deserialization. */
    private record BaselineData(List<BenchmarkResult> benchmarks) {}
}
