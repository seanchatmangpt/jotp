package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Blue Ocean Strategy Scorer — evaluates platforms against a 5-dimension competitive matrix
 * (fault tolerance, type safety, ecosystem, talent pool, autonomy) and applies 80/20 Pareto
 * analysis to identify the 20% of capabilities delivering 80% of competitive advantage.
 *
 * <p>The scorer calculates a "blue ocean index" (0.0–1.0) measuring how differentiated the
 * target platform is from competitors across all dimensions. Dimensions are ranked by competitive
 * gap and the Pareto principle is applied to highlight the highest-impact differentiators.
 *
 * <pre>{@code
 * var jotp = new BlueOceanScorer.PlatformProfile("JOTP", List.of(
 *     new BlueOceanScorer.FaultTolerance(5, "Supervision trees"),
 *     new BlueOceanScorer.TypeSafety(5, "Sealed types"),
 *     new BlueOceanScorer.Ecosystem(5, "Full OTP on JVM"),
 *     new BlueOceanScorer.TalentPool(5, "Java developers"),
 *     new BlueOceanScorer.Autonomy(5, "Self-healing")));
 *
 * var result = BlueOceanScorer.analyze(jotp, List.of(erlang, akka, golang, rust));
 * }</pre>
 */
public final class BlueOceanScorer {

    private BlueOceanScorer() {}

    // ── Dimension sealed hierarchy ───────────────────────────────────────────

    /**
     * A competitive dimension scored on a 1–5 scale with supporting evidence.
     *
     * <p>Each variant represents one axis of the 5-dimension competitive matrix from JOTP's
     * Vision 2030.
     */
    public sealed interface Dimension
            permits FaultTolerance, TypeSafety, Ecosystem, TalentPool, Autonomy {

        /** The score for this dimension (1–5). */
        int score();

        /** Evidence justifying the score. */
        String evidence();

        /** Human-readable label for this dimension. */
        default String label() {
            return switch (this) {
                case FaultTolerance ignored -> "Fault Tolerance";
                case TypeSafety ignored -> "Type Safety";
                case Ecosystem ignored -> "Ecosystem";
                case TalentPool ignored -> "Talent Pool";
                case Autonomy ignored -> "Autonomy";
            };
        }
    }

    /** Fault tolerance dimension — supervision trees, crash recovery, self-healing. */
    public record FaultTolerance(int score, String evidence) implements Dimension {
        public FaultTolerance {
            validateScore(score, "FaultTolerance");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Type safety dimension — sealed types, exhaustive matching, compile-time checks. */
    public record TypeSafety(int score, String evidence) implements Dimension {
        public TypeSafety {
            validateScore(score, "TypeSafety");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Ecosystem dimension — libraries, tooling, framework maturity. */
    public record Ecosystem(int score, String evidence) implements Dimension {
        public Ecosystem {
            validateScore(score, "Ecosystem");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Talent pool dimension — developer availability, community size, hiring ease. */
    public record TalentPool(int score, String evidence) implements Dimension {
        public TalentPool {
            validateScore(score, "TalentPool");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Autonomy dimension — self-managing, minimal ops overhead, autonomous operation. */
    public record Autonomy(int score, String evidence) implements Dimension {
        public Autonomy {
            validateScore(score, "Autonomy");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    // ── Platform profile ─────────────────────────────────────────────────────

    /**
     * A platform to evaluate, identified by name and scored across all five dimensions.
     *
     * @param name the platform name (e.g. "JOTP", "Erlang", "Akka")
     * @param dimensions the five scored dimensions
     */
    public record PlatformProfile(String name, List<Dimension> dimensions) {
        public PlatformProfile {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(dimensions, "dimensions must not be null");
            dimensions = List.copyOf(dimensions);
            if (dimensions.size() != 5) {
                throw new IllegalArgumentException(
                        "Exactly 5 dimensions required, got " + dimensions.size());
            }
        }
    }

    // ── Pareto item ──────────────────────────────────────────────────────────

    /**
     * A single dimension ranked by competitive impact for Pareto analysis.
     *
     * @param dimension the scored dimension
     * @param impactWeight the competitive gap (target score minus average competitor score)
     * @param cumulativeImpact cumulative fraction of total impact (0.0–1.0)
     * @param inTopTwentyPercent whether this item falls within the top 20% delivering 80% impact
     */
    public record ParetoItem(
            Dimension dimension,
            double impactWeight,
            double cumulativeImpact,
            boolean inTopTwentyPercent) {
        public ParetoItem {
            Objects.requireNonNull(dimension, "dimension must not be null");
        }
    }

    // ── Blue ocean analysis result ───────────────────────────────────────────

    /**
     * Complete blue ocean analysis result containing the target profile, competitors, Pareto
     * ranking, and the overall blue ocean index.
     *
     * @param target the platform being evaluated
     * @param competitors the competing platforms
     * @param paretoRanking dimensions ranked by competitive impact (descending)
     * @param blueOceanIndex differentiation score (0.0–1.0)
     */
    public record BlueOceanAnalysis(
            PlatformProfile target,
            List<PlatformProfile> competitors,
            List<ParetoItem> paretoRanking,
            double blueOceanIndex) {
        public BlueOceanAnalysis {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(competitors, "competitors must not be null");
            Objects.requireNonNull(paretoRanking, "paretoRanking must not be null");
            competitors = List.copyOf(competitors);
            paretoRanking = List.copyOf(paretoRanking);
            if (blueOceanIndex < 0.0 || blueOceanIndex > 1.0) {
                throw new IllegalArgumentException(
                        "blueOceanIndex must be 0.0-1.0, got " + blueOceanIndex);
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Analyzes how differentiated the {@code target} platform is compared to {@code competitors}
     * across all five dimensions, producing a Pareto-ranked blue ocean analysis.
     *
     * <p>The scoring logic:
     * <ol>
     *   <li>For each dimension, calculates the competitive gap (target score minus average
     *       competitor score)
     *   <li>Normalizes gaps as impact weights (using absolute values for total impact calculation)
     *   <li>Sorts dimensions by impact weight descending
     *   <li>Applies Pareto: marks items as "in top 20%" when cumulative impact reaches 80%
     *   <li>Calculates the blue ocean index as the mean of positive gaps divided by max possible
     *       gap
     * </ol>
     *
     * @param target the platform to evaluate
     * @param competitors the competing platforms to compare against
     * @return {@code Result.ok(BlueOceanAnalysis)} on success, {@code Result.err(Exception)} on
     *     failure
     */
    public static Result<BlueOceanAnalysis, Exception> analyze(
            PlatformProfile target, List<PlatformProfile> competitors) {
        return Result.of(() -> doAnalyze(target, competitors));
    }

    // ── Private implementation ───────────────────────────────────────────────

    private static BlueOceanAnalysis doAnalyze(
            PlatformProfile target, List<PlatformProfile> competitors) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(competitors, "competitors must not be null");
        if (competitors.isEmpty()) {
            throw new IllegalArgumentException("At least one competitor is required");
        }

        // Calculate average competitor score per dimension index
        double[] competitorAvg = new double[5];
        for (int i = 0; i < 5; i++) {
            double sum = 0.0;
            for (var comp : competitors) {
                sum += comp.dimensions().get(i).score();
            }
            competitorAvg[i] = sum / competitors.size();
        }

        // Calculate competitive gap per dimension
        record DimGap(Dimension dimension, double gap) {}
        var gaps = new ArrayList<DimGap>();
        for (int i = 0; i < 5; i++) {
            var dim = target.dimensions().get(i);
            double gap = dim.score() - competitorAvg[i];
            gaps.add(new DimGap(dim, gap));
        }

        // Sort by absolute gap descending (highest differentiation first)
        gaps.sort((a, b) -> Double.compare(Math.abs(b.gap()), Math.abs(a.gap())));

        // Compute total absolute impact for Pareto normalization
        double totalAbsImpact = gaps.stream().mapToDouble(g -> Math.abs(g.gap())).sum();

        // Build Pareto ranking
        var paretoItems = new ArrayList<ParetoItem>();
        double cumulative = 0.0;
        for (var g : gaps) {
            double weight = totalAbsImpact > 0.0 ? Math.abs(g.gap()) / totalAbsImpact : 0.2;
            cumulative += weight;
            // Mark as top 20% if cumulative has not yet reached 80% threshold,
            // or if this is the item that crosses it
            boolean inTop = cumulative <= 0.80 + 1e-9
                    || paretoItems.isEmpty();
            paretoItems.add(new ParetoItem(g.dimension(), g.gap(), cumulative, inTop));
        }

        // Calculate blue ocean index: mean of positive gaps normalized to max possible gap (4.0)
        // A gap of +4.0 means target scored 5 while competitors averaged 1.0
        double maxGap = 4.0; // max score 5 minus min average 1
        double positiveGapSum = gaps.stream()
                .mapToDouble(DimGap::gap)
                .filter(g -> g > 0.0)
                .sum();
        double positiveCount = gaps.stream()
                .mapToDouble(DimGap::gap)
                .filter(g -> g > 0.0)
                .count();
        double blueOceanIndex;
        if (positiveCount == 0) {
            blueOceanIndex = 0.0;
        } else {
            double meanPositiveGap = positiveGapSum / 5.0; // normalize across all 5 dims
            blueOceanIndex = Math.clamp(meanPositiveGap / maxGap, 0.0, 1.0);
        }

        return new BlueOceanAnalysis(target, competitors, paretoItems, blueOceanIndex);
    }

    // ── Validation helper ────────────────────────────────────────────────────

    private static void validateScore(int score, String dimensionName) {
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException(
                    dimensionName + " score must be 1-5, got " + score);
        }
    }
}
