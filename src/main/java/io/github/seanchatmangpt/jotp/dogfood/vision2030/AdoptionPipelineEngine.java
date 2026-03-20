package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * AdoptionPipelineEngine — models the enterprise JOTP adoption journey through four phases
 * (Assessment, Pilot, Production, Scale) with readiness scoring and migration risk analysis.
 *
 * <p>Evaluates an enterprise's readiness across three dimensions (team, infrastructure, codebase)
 * and produces a weighted overall score that determines the current adoption phase. Migration risks
 * are generated based on per-dimension scores, and phase transitions are recommended via exhaustive
 * pattern matching on the sealed {@link AdoptionPhase} hierarchy.
 *
 * <pre>{@code
 * var assessment = AdoptionPipelineEngine.assess(
 *     "Acme Corp", 20, true, true, 15, 99.95);
 * assessment.map(a -> AdoptionPipelineEngine.recommendTransition(a));
 * }</pre>
 */
public final class AdoptionPipelineEngine {

    private AdoptionPipelineEngine() {}

    // ── Adoption Phase ───────────────────────────────────────────────────────

    /**
     * Sealed hierarchy representing the stages of JOTP enterprise adoption. Each phase is a record
     * for structural transparency and pattern-matching support.
     */
    public sealed interface AdoptionPhase
            permits AdoptionPhase.Assessment,
                    AdoptionPhase.Pilot,
                    AdoptionPhase.Production,
                    AdoptionPhase.Scale,
                    AdoptionPhase.Blocked {

        /** Initial evaluation phase — gathering metrics and assessing readiness. */
        record Assessment() implements AdoptionPhase {}

        /** Controlled pilot deployment — limited scope, measured outcomes. */
        record Pilot() implements AdoptionPhase {}

        /** Full production deployment — all critical services migrated. */
        record Production() implements AdoptionPhase {}

        /** Scaling across the organization — standardization and governance. */
        record Scale() implements AdoptionPhase {}

        /** Adoption is blocked due to an unresolved issue. */
        record Blocked(String reason) implements AdoptionPhase {
            public Blocked {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }

    // ── Readiness Score ──────────────────────────────────────────────────────

    /**
     * Three-dimensional readiness score for JOTP adoption. Each dimension is scored 0-100.
     *
     * @param teamReadiness team skill and experience level (0-100)
     * @param infraReadiness infrastructure maturity for JOTP deployment (0-100)
     * @param codebaseReadiness codebase compatibility and quality (0-100)
     */
    public record ReadinessScore(int teamReadiness, int infraReadiness, int codebaseReadiness) {

        public ReadinessScore {
            if (teamReadiness < 0 || teamReadiness > 100) {
                throw new IllegalArgumentException(
                        "teamReadiness must be 0-100, got " + teamReadiness);
            }
            if (infraReadiness < 0 || infraReadiness > 100) {
                throw new IllegalArgumentException(
                        "infraReadiness must be 0-100, got " + infraReadiness);
            }
            if (codebaseReadiness < 0 || codebaseReadiness > 100) {
                throw new IllegalArgumentException(
                        "codebaseReadiness must be 0-100, got " + codebaseReadiness);
            }
        }

        /**
         * Weighted overall readiness: team 40%, infrastructure 30%, codebase 30%.
         *
         * @return weighted average score (0-100)
         */
        public int overall() {
            return (int) (teamReadiness * 0.4 + infraReadiness * 0.3 + codebaseReadiness * 0.3);
        }

        /**
         * Whether the enterprise meets the minimum threshold for a pilot deployment.
         *
         * @return {@code true} if overall readiness is at least 60
         */
        public boolean isPilotReady() {
            return overall() >= 60;
        }
    }

    // ── Migration Risk ───────────────────────────────────────────────────────

    /** Severity level for a migration risk. */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * A specific risk identified during the adoption assessment.
     *
     * @param name short description of the risk
     * @param level severity classification
     * @param mitigation recommended mitigation strategy
     */
    public record MigrationRisk(String name, RiskLevel level, String mitigation) {

        public MigrationRisk {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(level, "level must not be null");
            Objects.requireNonNull(mitigation, "mitigation must not be null");
        }
    }

    // ── Adoption Assessment ──────────────────────────────────────────────────

    /**
     * Complete assessment result for an enterprise's JOTP adoption readiness.
     *
     * @param enterpriseName the name of the assessed enterprise
     * @param currentPhase the determined current adoption phase
     * @param readiness three-dimensional readiness scores
     * @param risks identified migration risks
     * @param recommendations actionable next steps
     */
    public record AdoptionAssessment(
            String enterpriseName,
            AdoptionPhase currentPhase,
            ReadinessScore readiness,
            List<MigrationRisk> risks,
            List<String> recommendations) {

        public AdoptionAssessment {
            Objects.requireNonNull(enterpriseName, "enterpriseName must not be null");
            Objects.requireNonNull(currentPhase, "currentPhase must not be null");
            Objects.requireNonNull(readiness, "readiness must not be null");
            risks = List.copyOf(risks);
            recommendations = List.copyOf(recommendations);
        }
    }

    // ── Phase Transition ─────────────────────────────────────────────────────

    /**
     * Records a transition between adoption phases with reason and timestamp.
     *
     * @param from the phase being transitioned from
     * @param to the phase being transitioned to
     * @param reason explanation for the transition
     * @param timestamp when the transition was recommended
     */
    public record PhaseTransition(
            AdoptionPhase from, AdoptionPhase to, String reason, Instant timestamp) {

        public PhaseTransition {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Assess an enterprise's readiness for JOTP adoption.
     *
     * <p>Evaluates team readiness, infrastructure readiness, and codebase readiness based on the
     * provided metrics. Generates migration risks for any dimension scoring below thresholds and
     * determines the current adoption phase based on the weighted overall score.
     *
     * @param enterpriseName name of the enterprise being assessed
     * @param teamSize number of developers on the team
     * @param hasOtpExperience whether the team has prior OTP/Erlang/actor-model experience
     * @param hasSpringBoot whether the codebase uses Spring Boot
     * @param microserviceCount number of microservices in the architecture
     * @param currentSlaPercent current SLA achievement percentage (e.g. 99.95)
     * @return {@code Result.ok(AdoptionAssessment)} on success, {@code Result.err(Exception)} on
     *     invalid input
     */
    public static Result<AdoptionAssessment, Exception> assess(
            String enterpriseName,
            int teamSize,
            boolean hasOtpExperience,
            boolean hasSpringBoot,
            int microserviceCount,
            double currentSlaPercent) {

        try {
            Objects.requireNonNull(enterpriseName, "enterpriseName must not be null");
            if (teamSize < 0) {
                throw new IllegalArgumentException("teamSize must be non-negative, got " + teamSize);
            }
            if (microserviceCount < 0) {
                throw new IllegalArgumentException(
                        "microserviceCount must be non-negative, got " + microserviceCount);
            }
            if (currentSlaPercent < 0 || currentSlaPercent > 100) {
                throw new IllegalArgumentException(
                        "currentSlaPercent must be 0-100, got " + currentSlaPercent);
            }

            int teamScore = Math.min(30 + (hasOtpExperience ? 30 : 0) + teamSize * 2, 100);
            int infraScore = Math.min(20 + (hasSpringBoot ? 40 : 0) + microserviceCount, 100);
            int codebaseScore =
                    Math.min(
                            10
                                    + (currentSlaPercent >= 99.9
                                            ? 50
                                            : (int) (currentSlaPercent / 2))
                                    + (hasSpringBoot ? 30 : 0),
                            100);

            var readiness = new ReadinessScore(teamScore, infraScore, codebaseScore);

            var risks = new ArrayList<MigrationRisk>();
            risks.add(riskForDimension("Team readiness", teamScore));
            risks.add(riskForDimension("Infrastructure readiness", infraScore));
            risks.add(riskForDimension("Codebase readiness", codebaseScore));

            var phase = determinePhase(readiness.overall());

            var recommendations = new ArrayList<String>();
            if (teamScore < 60) {
                recommendations.add("Invest in OTP/actor-model training for the development team");
            }
            if (infraScore < 60) {
                recommendations.add(
                        "Modernize infrastructure to support microservice deployment patterns");
            }
            if (codebaseScore < 60) {
                recommendations.add(
                        "Improve codebase quality and SLA compliance before migration");
            }
            if (readiness.isPilotReady()) {
                recommendations.add("Enterprise is ready to begin a controlled pilot deployment");
            }

            var assessment =
                    new AdoptionAssessment(
                            enterpriseName,
                            phase,
                            readiness,
                            List.copyOf(risks),
                            List.copyOf(recommendations));

            return Result.ok(assessment);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * Determine the next recommended phase transition for the given assessment.
     *
     * <p>Uses exhaustive pattern matching on the sealed {@link AdoptionPhase} hierarchy to
     * recommend the appropriate next step based on current phase and readiness scores.
     *
     * @param current the current adoption assessment
     * @return {@code Result.ok(PhaseTransition)} with the recommended transition, or {@code
     *     Result.err(Exception)} if input is invalid
     */
    public static Result<PhaseTransition, Exception> recommendTransition(
            AdoptionAssessment current) {
        try {
            Objects.requireNonNull(current, "current must not be null");

            var now = Instant.now();
            int overall = current.readiness().overall();

            PhaseTransition transition =
                    switch (current.currentPhase()) {
                        case AdoptionPhase.Assessment a -> {
                            if (current.readiness().isPilotReady()) {
                                yield new PhaseTransition(
                                        a,
                                        new AdoptionPhase.Pilot(),
                                        "Overall readiness score of "
                                                + overall
                                                + " meets pilot threshold (>= 60)",
                                        now);
                            }
                            yield new PhaseTransition(
                                    a,
                                    new AdoptionPhase.Blocked(
                                            "Readiness score " + overall + " below pilot threshold"),
                                    "Overall readiness score of "
                                            + overall
                                            + " is below the pilot threshold of 60",
                                    now);
                        }
                        case AdoptionPhase.Pilot p -> {
                            if (overall >= 75) {
                                yield new PhaseTransition(
                                        p,
                                        new AdoptionPhase.Production(),
                                        "Pilot successful with readiness score of "
                                                + overall
                                                + " (>= 75)",
                                        now);
                            }
                            yield new PhaseTransition(
                                    p,
                                    p,
                                    "Continue pilot phase — readiness score of "
                                            + overall
                                            + " has not reached production threshold (75)",
                                    now);
                        }
                        case AdoptionPhase.Production pr -> {
                            if (overall >= 90) {
                                yield new PhaseTransition(
                                        pr,
                                        new AdoptionPhase.Scale(),
                                        "Production deployment stable with readiness score of "
                                                + overall
                                                + " (>= 90)",
                                        now);
                            }
                            yield new PhaseTransition(
                                    pr,
                                    pr,
                                    "Continue production phase — readiness score of "
                                            + overall
                                            + " has not reached scale threshold (90)",
                                    now);
                        }
                        case AdoptionPhase.Scale s ->
                                new PhaseTransition(
                                        s,
                                        s,
                                        "Already at scale — maintain and optimize",
                                        now);
                        case AdoptionPhase.Blocked b ->
                                new PhaseTransition(
                                        b,
                                        new AdoptionPhase.Assessment(),
                                        "Blocked: "
                                                + b.reason()
                                                + " — return to assessment to re-evaluate",
                                        now);
                    };

            return Result.ok(transition);
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Classify a dimension score into a migration risk with appropriate level and mitigation.
     *
     * @param dimensionName the name of the dimension being scored
     * @param score the score for the dimension (0-100)
     * @return a {@link MigrationRisk} with level and mitigation based on the score
     */
    private static MigrationRisk riskForDimension(String dimensionName, int score) {
        if (score >= 80) {
            return new MigrationRisk(
                    dimensionName, RiskLevel.LOW, "No significant risk — maintain current level");
        } else if (score >= 60) {
            return new MigrationRisk(
                    dimensionName,
                    RiskLevel.MEDIUM,
                    "Moderate gaps identified — address before production deployment");
        } else if (score >= 40) {
            return new MigrationRisk(
                    dimensionName,
                    RiskLevel.HIGH,
                    "Significant gaps — focused improvement plan required before pilot");
        } else {
            return new MigrationRisk(
                    dimensionName,
                    RiskLevel.CRITICAL,
                    "Critical deficiency — foundational investment needed before adoption");
        }
    }

    /**
     * Determine the adoption phase based on the overall readiness score.
     *
     * @param overallScore the weighted overall readiness score (0-100)
     * @return the appropriate {@link AdoptionPhase}
     */
    private static AdoptionPhase determinePhase(int overallScore) {
        if (overallScore >= 90) {
            return new AdoptionPhase.Scale();
        } else if (overallScore >= 75) {
            return new AdoptionPhase.Production();
        } else if (overallScore >= 60) {
            return new AdoptionPhase.Pilot();
        } else if (overallScore >= 40) {
            return new AdoptionPhase.Assessment();
        } else {
            return new AdoptionPhase.Blocked(
                    "Overall readiness score of " + overallScore + " is critically low");
        }
    }
}
