package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent autonomy maturity scorer — evaluates a system's maturity level (0-5) based on the Vision
 * 2030 autonomous agent model.
 *
 * <p>Measures five dimensions of operational maturity:
 *
 * <ul>
 *   <li><strong>Self-healing:</strong> Can the system recover from failures without human
 *       intervention?
 *   <li><strong>Failure learning:</strong> Does the system learn from past failures and adapt?
 *   <li><strong>Message passing:</strong> Is communication purely message-based (no shared state)?
 *   <li><strong>Supervision:</strong> Are processes organized in supervision trees?
 *   <li><strong>Observability:</strong> Can operators see what the system is doing?
 * </ul>
 *
 * <p>Each dimension produces a score (0-100). The overall {@link MaturityLevel} is derived from the
 * average of all dimension scores.
 *
 * <pre>{@code
 * var profile = new AgentMaturityScorer.SystemProfile(
 *         "my-service", true, true, true, true, true, true, true, 50, 99.5);
 * var result = AgentMaturityScorer.assess(profile);
 * switch (result) {
 *     case Result.Ok(var assessment) -> System.out.println(assessment.overall());
 *     case Result.Err(var error) -> System.err.println(error.getMessage());
 * }
 * }</pre>
 */
public final class AgentMaturityScorer {

    private AgentMaturityScorer() {}

    // ── MaturityLevel ────────────────────────────────────────────────────────

    /** Maturity levels from fully manual (0) to fully autonomous (5). */
    public enum MaturityLevel {
        /** Manual intervention required for all failures. */
        MANUAL(0, "Manual intervention required for all failures"),
        /** Basic error handling, manual restart. */
        REACTIVE(1, "Basic error handling, manual restart"),
        /** Health checks and auto-restart on failure. */
        PROACTIVE(2, "Health checks and auto-restart on failure"),
        /** Supervision trees with automatic recovery. */
        SELF_HEALING(3, "Supervision trees with automatic recovery"),
        /** Failure pattern analysis and adaptive recovery. */
        LEARNING(4, "Failure pattern analysis and adaptive recovery"),
        /** Self-optimizing, zero-touch operations. */
        AUTONOMOUS(5, "Self-optimizing, zero-touch operations");

        private final int level;
        private final String description;

        MaturityLevel(int level, String description) {
            this.level = level;
            this.description = description;
        }

        /** Returns the numeric maturity level (0-5). */
        public int level() {
            return level;
        }

        /** Returns a human-readable description of this maturity level. */
        public String description() {
            return description;
        }
    }

    // ── Indicator hierarchy ──────────────────────────────────────────────────

    /**
     * Sealed indicator hierarchy — each variant captures a scored dimension with supporting
     * evidence.
     */
    public sealed interface Indicator
            permits SelfHealingIndicator,
                    FailureLearningIndicator,
                    MessagePassingIndicator,
                    SupervisionIndicator,
                    ObservabilityIndicator {

        /** Score for this indicator (0-100). */
        int score();

        /** Evidence supporting the score. */
        String evidence();
    }

    /** Self-healing capability indicator. */
    public record SelfHealingIndicator(int score, String evidence) implements Indicator {
        public SelfHealingIndicator {
            validateScore(score);
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Failure learning capability indicator. */
    public record FailureLearningIndicator(int score, String evidence) implements Indicator {
        public FailureLearningIndicator {
            validateScore(score);
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Message-passing purity indicator. */
    public record MessagePassingIndicator(int score, String evidence) implements Indicator {
        public MessagePassingIndicator {
            validateScore(score);
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Supervision coverage indicator. */
    public record SupervisionIndicator(int score, String evidence) implements Indicator {
        public SupervisionIndicator {
            validateScore(score);
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Observability depth indicator. */
    public record ObservabilityIndicator(int score, String evidence) implements Indicator {
        public ObservabilityIndicator {
            validateScore(score);
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    // ── Result types ─────────────────────────────────────────────────────────

    /** Per-dimension breakdown of a maturity assessment. */
    public record DimensionResult(String name, int score, MaturityLevel dimensionLevel,
            String evidence) {
        public DimensionResult {
            Objects.requireNonNull(name, "name must not be null");
            validateScore(score);
            Objects.requireNonNull(dimensionLevel, "dimensionLevel must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    /** Top-level maturity assessment result. */
    public record MaturityAssessment(String systemName, MaturityLevel overall,
            List<DimensionResult> dimensions, List<String> recommendations) {
        public MaturityAssessment {
            Objects.requireNonNull(systemName, "systemName must not be null");
            Objects.requireNonNull(overall, "overall must not be null");
            dimensions = List.copyOf(dimensions);
            recommendations = List.copyOf(recommendations);
        }
    }

    /** Input profile describing a system's capabilities. */
    public record SystemProfile(String name, boolean hasSupervisor, boolean hasCircuitBreaker,
            boolean hasMessagePassing, boolean hasHealthChecks, boolean hasMetrics,
            boolean hasFailureLogging, boolean hasAdaptiveRestart, int processCount,
            double uptimePercent) {
        public SystemProfile {
            Objects.requireNonNull(name, "name must not be null");
            if (processCount < 0) {
                throw new IllegalArgumentException(
                        "processCount must be non-negative, got " + processCount);
            }
            if (uptimePercent < 0.0 || uptimePercent > 100.0) {
                throw new IllegalArgumentException(
                        "uptimePercent must be 0-100, got " + uptimePercent);
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Assesses the maturity of a system described by the given profile.
     *
     * <p>Evaluates five dimensions (self-healing, failure learning, message passing, supervision,
     * observability) and computes an overall maturity level from their average score.
     *
     * @param profile the system profile to assess
     * @return {@code Result.ok(assessment)} on success, {@code Result.err(exception)} on failure
     */
    public static Result<MaturityAssessment, Exception> assess(SystemProfile profile) {
        return Result.of(() -> {
            Objects.requireNonNull(profile, "profile must not be null");
            return doAssess(profile);
        });
    }

    // ── Scoring logic ────────────────────────────────────────────────────────

    private static MaturityAssessment doAssess(SystemProfile profile) {
        var selfHealing = scoreSelfHealing(profile);
        var failureLearning = scoreFailureLearning(profile);
        var messagePassing = scoreMessagePassing(profile);
        var supervision = scoreSupervision(profile);
        var observability = scoreObservability(profile);

        var dimensions = List.of(
                toDimensionResult("Self-Healing", selfHealing),
                toDimensionResult("Failure Learning", failureLearning),
                toDimensionResult("Message Passing", messagePassing),
                toDimensionResult("Supervision", supervision),
                toDimensionResult("Observability", observability));

        int averageScore = dimensions.stream()
                .mapToInt(DimensionResult::score)
                .sum() / dimensions.size();

        var overall = scoreToLevel(averageScore);
        var recommendations = generateRecommendations(dimensions);

        return new MaturityAssessment(profile.name(), overall, dimensions, recommendations);
    }

    private static Indicator scoreSelfHealing(SystemProfile profile) {
        int score = 0;
        var evidence = new ArrayList<String>();

        if (profile.hasSupervisor()) {
            score += 30;
            evidence.add("supervisor present");
        }
        if (profile.hasCircuitBreaker()) {
            score += 20;
            evidence.add("circuit breaker enabled");
        }
        if (profile.hasAdaptiveRestart()) {
            score += 30;
            evidence.add("adaptive restart configured");
        }

        // Uptime mapping: up to 20 points
        score += uptimePoints(profile.uptimePercent());
        evidence.add("uptime %.2f%%".formatted(profile.uptimePercent()));

        return new SelfHealingIndicator(
                Math.min(score, 100), String.join("; ", evidence));
    }

    private static Indicator scoreFailureLearning(SystemProfile profile) {
        int score = 0;
        var evidence = new ArrayList<String>();

        if (profile.hasFailureLogging()) {
            score += 40;
            evidence.add("failure logging active");
        }
        if (profile.hasAdaptiveRestart()) {
            score += 40;
            evidence.add("adaptive restart enabled");
        }
        if (profile.hasMetrics()) {
            score += 20;
            evidence.add("metrics collection active");
        }

        return new FailureLearningIndicator(
                Math.min(score, 100), String.join("; ", evidence));
    }

    private static Indicator scoreMessagePassing(SystemProfile profile) {
        int score = 0;
        var evidence = new ArrayList<String>();

        if (profile.hasMessagePassing()) {
            score += 60;
            evidence.add("message-passing architecture");
        }
        if (profile.processCount() > 10) {
            score += 20;
            evidence.add("process count %d (>10)".formatted(profile.processCount()));
        } else {
            score += 10;
            evidence.add("process count %d (<=10)".formatted(profile.processCount()));
        }
        if (profile.hasHealthChecks()) {
            score += 20;
            evidence.add("health checks enabled");
        }

        return new MessagePassingIndicator(
                Math.min(score, 100), String.join("; ", evidence));
    }

    private static Indicator scoreSupervision(SystemProfile profile) {
        int score = 0;
        var evidence = new ArrayList<String>();

        if (profile.hasSupervisor()) {
            score += 50;
            evidence.add("supervisor tree present");
        }
        if (profile.processCount() > 100) {
            score += 30;
            evidence.add("process count %d (>100)".formatted(profile.processCount()));
        } else if (profile.processCount() > 10) {
            score += 20;
            evidence.add("process count %d (>10)".formatted(profile.processCount()));
        } else {
            score += 10;
            evidence.add("process count %d (<=10)".formatted(profile.processCount()));
        }
        if (profile.hasCircuitBreaker()) {
            score += 20;
            evidence.add("circuit breaker enabled");
        }

        return new SupervisionIndicator(
                Math.min(score, 100), String.join("; ", evidence));
    }

    private static Indicator scoreObservability(SystemProfile profile) {
        int score = 0;
        var evidence = new ArrayList<String>();

        if (profile.hasMetrics()) {
            score += 40;
            evidence.add("metrics collection active");
        }
        if (profile.hasHealthChecks()) {
            score += 30;
            evidence.add("health checks enabled");
        }
        if (profile.hasFailureLogging()) {
            score += 30;
            evidence.add("failure logging active");
        }

        return new ObservabilityIndicator(
                Math.min(score, 100), String.join("; ", evidence));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int uptimePoints(double uptimePercent) {
        if (uptimePercent >= 99.99) return 20;
        if (uptimePercent >= 99.9) return 15;
        if (uptimePercent >= 99.0) return 10;
        if (uptimePercent >= 95.0) return 5;
        return 0;
    }

    private static DimensionResult toDimensionResult(String name, Indicator indicator) {
        return new DimensionResult(
                name, indicator.score(), scoreToLevel(indicator.score()), indicator.evidence());
    }

    /**
     * Maps a numeric score (0-100) to a maturity level.
     *
     * <ul>
     *   <li>0-20 → MANUAL
     *   <li>21-40 → REACTIVE
     *   <li>41-60 → PROACTIVE
     *   <li>61-80 → SELF_HEALING
     *   <li>81-95 → LEARNING
     *   <li>96-100 → AUTONOMOUS
     * </ul>
     */
    static MaturityLevel scoreToLevel(int score) {
        if (score <= 20) return MaturityLevel.MANUAL;
        if (score <= 40) return MaturityLevel.REACTIVE;
        if (score <= 60) return MaturityLevel.PROACTIVE;
        if (score <= 80) return MaturityLevel.SELF_HEALING;
        if (score <= 95) return MaturityLevel.LEARNING;
        return MaturityLevel.AUTONOMOUS;
    }

    private static List<String> generateRecommendations(List<DimensionResult> dimensions) {
        var recommendations = new ArrayList<String>();

        for (var dimension : dimensions) {
            if (dimension.score() < 60) {
                recommendations.add(switch (dimension.name()) {
                    case "Self-Healing" ->
                            "Improve self-healing: add supervision trees and circuit breakers"
                                    + " to enable automatic failure recovery";
                    case "Failure Learning" ->
                            "Improve failure learning: enable failure logging, metrics collection,"
                                    + " and adaptive restart strategies";
                    case "Message Passing" ->
                            "Improve message passing: adopt message-based communication and"
                                    + " increase process isolation";
                    case "Supervision" ->
                            "Improve supervision: implement supervisor hierarchies with"
                                    + " appropriate restart strategies";
                    case "Observability" ->
                            "Improve observability: add metrics, health checks, and structured"
                                    + " failure logging";
                    default ->
                            "Improve " + dimension.name() + ": score is below threshold ("
                                    + dimension.score() + "/100)";
                });
            }
        }

        return List.copyOf(recommendations);
    }

    private static void validateScore(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be 0-100, got " + score);
        }
    }
}
