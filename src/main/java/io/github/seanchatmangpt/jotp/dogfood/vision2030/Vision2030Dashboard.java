package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.Result;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vision 2030 Executive Dashboard — a unified abstraction layer that ties all four Vision 2030
 * analysis engines together: {@link BlueOceanScorer}, {@link AdoptionPipelineEngine}, {@link
 * AgentMaturityScorer}, and {@link RoadmapPrioritizer}.
 *
 * <p>Runs all four assessments concurrently using Java 26 virtual threads with fail-fast semantics
 * (Armstrong: "If any process fails, fail fast"), then aggregates results into a single {@link
 * DashboardResult} with an executive summary.
 *
 * <p>The dashboard maps a single {@link EnterpriseProfile} to each engine's specific input types,
 * builds sensible defaults for competitor profiles and roadmap items, and produces a one-stop
 * executive view of an enterprise's JOTP adoption posture.
 *
 * <pre>{@code
 * var profile = new Vision2030Dashboard.EnterpriseProfile(
 *         "Acme Corp", 20, true, true, 15, 99.95,
 *         true, true, true, true, true, true, true, 50, 99.5);
 * var result = Vision2030Dashboard.assess(profile);
 * switch (result) {
 *     case Result.Ok(var dashboard) -> System.out.println(dashboard.executiveSummary());
 *     case Result.Err(var error) -> System.err.println(error.getMessage());
 * }
 * }</pre>
 */
public final class Vision2030Dashboard {

    private Vision2030Dashboard() {}

    // ── Input type ──────────────────────────────────────────────────────────

    /**
     * Unified enterprise profile capturing all inputs needed by the four Vision 2030 engines.
     *
     * @param name enterprise name
     * @param teamSize number of developers
     * @param hasOtpExperience whether the team has OTP/actor-model experience
     * @param hasSpringBoot whether the codebase uses Spring Boot
     * @param microserviceCount number of microservices in the architecture
     * @param currentSlaPercent current SLA achievement percentage (0-100)
     * @param hasSupervisor whether supervision trees are in place
     * @param hasCircuitBreaker whether circuit breakers are configured
     * @param hasMessagePassing whether communication is message-based
     * @param hasHealthChecks whether health checks are enabled
     * @param hasMetrics whether metrics collection is active
     * @param hasFailureLogging whether failure logging is active
     * @param hasAdaptiveRestart whether adaptive restart is configured
     * @param processCount number of active processes
     * @param uptimePercent system uptime percentage (0-100)
     */
    public record EnterpriseProfile(
            String name,
            int teamSize,
            boolean hasOtpExperience,
            boolean hasSpringBoot,
            int microserviceCount,
            double currentSlaPercent,
            boolean hasSupervisor,
            boolean hasCircuitBreaker,
            boolean hasMessagePassing,
            boolean hasHealthChecks,
            boolean hasMetrics,
            boolean hasFailureLogging,
            boolean hasAdaptiveRestart,
            int processCount,
            double uptimePercent) {

        public EnterpriseProfile {
            Objects.requireNonNull(name, "name must not be null");
            if (teamSize < 0) {
                throw new IllegalArgumentException(
                        "teamSize must be non-negative, got " + teamSize);
            }
            if (microserviceCount < 0) {
                throw new IllegalArgumentException(
                        "microserviceCount must be non-negative, got " + microserviceCount);
            }
            if (currentSlaPercent < 0.0 || currentSlaPercent > 100.0) {
                throw new IllegalArgumentException(
                        "currentSlaPercent must be 0-100, got " + currentSlaPercent);
            }
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

    // ── Output type ─────────────────────────────────────────────────────────

    /**
     * Aggregated dashboard result from all four Vision 2030 engines.
     *
     * @param strategy blue ocean competitive analysis result
     * @param adoption adoption pipeline assessment result
     * @param maturity agent maturity assessment result
     * @param roadmap prioritized roadmap result
     * @param executiveSummary one-paragraph executive summary with key metrics
     */
    public record DashboardResult(
            BlueOceanScorer.BlueOceanAnalysis strategy,
            AdoptionPipelineEngine.AdoptionAssessment adoption,
            AgentMaturityScorer.MaturityAssessment maturity,
            RoadmapPrioritizer.PrioritizedRoadmap roadmap,
            String executiveSummary) {

        public DashboardResult {
            Objects.requireNonNull(strategy, "strategy must not be null");
            Objects.requireNonNull(adoption, "adoption must not be null");
            Objects.requireNonNull(maturity, "maturity must not be null");
            Objects.requireNonNull(roadmap, "roadmap must not be null");
            Objects.requireNonNull(executiveSummary, "executiveSummary must not be null");
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Assess an enterprise using all four Vision 2030 engines concurrently with default roadmap
     * items and competitor profiles.
     *
     * @param profile the unified enterprise profile
     * @return {@code Result.ok(DashboardResult)} on success, {@code Result.err(Exception)} on
     *     failure
     */
    public static Result<DashboardResult, Exception> assess(EnterpriseProfile profile) {
        return assess(profile, defaultRoadmapItems());
    }

    /**
     * Assess an enterprise using all four Vision 2030 engines concurrently with custom roadmap
     * items.
     *
     * <p>Uses Java 26 virtual threads via {@link Executors#newVirtualThreadPerTaskExecutor()} to run
     * all four engines concurrently. Heterogeneous return types prevent use of {@code
     * StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow()}, so the pattern from {@code
     * ArmstrongAgiEngine} is used instead: {@link CompletableFuture#allOf} with a virtual-thread
     * executor ensures fail-fast semantics while supporting different result types.
     *
     * @param profile the unified enterprise profile
     * @param roadmapItems the roadmap items to prioritize
     * @return {@code Result.ok(DashboardResult)} on success, {@code Result.err(Exception)} on
     *     failure
     */
    public static Result<DashboardResult, Exception> assess(
            EnterpriseProfile profile, List<RoadmapPrioritizer.RoadmapItem> roadmapItems) {
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(roadmapItems, "roadmapItems must not be null");

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            // Fork all four engines concurrently on virtual threads
            var blueOceanFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    BlueOceanScorer.analyze(
                                                    buildJotpProfile(profile),
                                                    defaultCompetitors())
                                            .orElseThrow(),
                            exec);

            var adoptionFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    AdoptionPipelineEngine.assess(
                                                    profile.name(),
                                                    profile.teamSize(),
                                                    profile.hasOtpExperience(),
                                                    profile.hasSpringBoot(),
                                                    profile.microserviceCount(),
                                                    profile.currentSlaPercent())
                                            .orElseThrow(),
                            exec);

            var maturityFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    AgentMaturityScorer.assess(buildSystemProfile(profile))
                                            .orElseThrow(),
                            exec);

            var roadmapFuture =
                    CompletableFuture.supplyAsync(
                            () -> RoadmapPrioritizer.prioritize(roadmapItems).orElseThrow(), exec);

            // Wait for all four to complete — fail-fast if any throws
            CompletableFuture.allOf(
                            blueOceanFuture, adoptionFuture, maturityFuture, roadmapFuture)
                    .join();

            var strategyResult = blueOceanFuture.join();
            var adoptionResult = adoptionFuture.join();
            var maturityResult = maturityFuture.join();
            var roadmapResult = roadmapFuture.join();

            var summary =
                    buildExecutiveSummary(
                            profile, strategyResult, adoptionResult, maturityResult, roadmapResult);

            return Result.ok(
                    new DashboardResult(
                            strategyResult,
                            adoptionResult,
                            maturityResult,
                            roadmapResult,
                            summary));
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    // ── Profile mapping ─────────────────────────────────────────────────────

    /**
     * Builds a JOTP platform profile from the enterprise profile for blue ocean analysis. Scores are
     * derived from the enterprise's capability flags: each enabled capability contributes to the
     * relevant dimension score.
     */
    private static BlueOceanScorer.PlatformProfile buildJotpProfile(EnterpriseProfile profile) {
        int faultScore =
                Math.clamp(
                        1
                                + (profile.hasSupervisor() ? 1 : 0)
                                + (profile.hasCircuitBreaker() ? 1 : 0)
                                + (profile.hasAdaptiveRestart() ? 1 : 0)
                                + (profile.uptimePercent() >= 99.9 ? 1 : 0),
                        1,
                        5);

        int typeScore =
                Math.clamp(
                        2
                                + (profile.hasMessagePassing() ? 1 : 0)
                                + (profile.hasHealthChecks() ? 1 : 0)
                                + (profile.hasMetrics() ? 1 : 0),
                        1,
                        5);

        int ecoScore =
                Math.clamp(
                        2
                                + (profile.hasSpringBoot() ? 1 : 0)
                                + (profile.microserviceCount() > 10 ? 1 : 0)
                                + (profile.processCount() > 100 ? 1 : 0),
                        1,
                        5);

        int talentScore =
                Math.clamp(
                        2
                                + (profile.teamSize() > 10 ? 1 : 0)
                                + (profile.hasOtpExperience() ? 1 : 0)
                                + (profile.hasSpringBoot() ? 1 : 0),
                        1,
                        5);

        int autonomyScore =
                Math.clamp(
                        1
                                + (profile.hasSupervisor() ? 1 : 0)
                                + (profile.hasAdaptiveRestart() ? 1 : 0)
                                + (profile.hasFailureLogging() ? 1 : 0)
                                + (profile.hasMetrics() ? 1 : 0),
                        1,
                        5);

        return new BlueOceanScorer.PlatformProfile(
                "JOTP@" + profile.name(),
                List.of(
                        new BlueOceanScorer.FaultTolerance(faultScore, "Enterprise fault tolerance"),
                        new BlueOceanScorer.TypeSafety(typeScore, "Sealed types + pattern matching"),
                        new BlueOceanScorer.Ecosystem(ecoScore, "JVM ecosystem integration"),
                        new BlueOceanScorer.TalentPool(talentScore, "Java developer pool"),
                        new BlueOceanScorer.Autonomy(
                                autonomyScore, "Self-healing agent capabilities")));
    }

    /**
     * Builds an {@link AgentMaturityScorer.SystemProfile} from the enterprise profile by mapping
     * capability flags directly.
     */
    private static AgentMaturityScorer.SystemProfile buildSystemProfile(
            EnterpriseProfile profile) {
        return new AgentMaturityScorer.SystemProfile(
                profile.name(),
                profile.hasSupervisor(),
                profile.hasCircuitBreaker(),
                profile.hasMessagePassing(),
                profile.hasHealthChecks(),
                profile.hasMetrics(),
                profile.hasFailureLogging(),
                profile.hasAdaptiveRestart(),
                profile.processCount(),
                profile.uptimePercent());
    }

    // ── Default competitors ─────────────────────────────────────────────────

    /**
     * Builds default competitor profiles for blue ocean analysis: Erlang/OTP, Akka, Go, and Rust.
     * Scores reflect general industry consensus on each platform's strengths.
     */
    private static List<BlueOceanScorer.PlatformProfile> defaultCompetitors() {
        var erlang =
                new BlueOceanScorer.PlatformProfile(
                        "Erlang/OTP",
                        List.of(
                                new BlueOceanScorer.FaultTolerance(5, "Battle-tested supervision"),
                                new BlueOceanScorer.TypeSafety(2, "Dynamic typing"),
                                new BlueOceanScorer.Ecosystem(3, "Niche ecosystem"),
                                new BlueOceanScorer.TalentPool(1, "Small developer pool"),
                                new BlueOceanScorer.Autonomy(5, "Self-healing pioneer")));

        var akka =
                new BlueOceanScorer.PlatformProfile(
                        "Akka",
                        List.of(
                                new BlueOceanScorer.FaultTolerance(4, "Actor supervision"),
                                new BlueOceanScorer.TypeSafety(3, "Scala/Java typed actors"),
                                new BlueOceanScorer.Ecosystem(4, "JVM ecosystem"),
                                new BlueOceanScorer.TalentPool(3, "Scala/Java developers"),
                                new BlueOceanScorer.Autonomy(3, "Cluster management")));

        var golang =
                new BlueOceanScorer.PlatformProfile(
                        "Go",
                        List.of(
                                new BlueOceanScorer.FaultTolerance(2, "Manual error handling"),
                                new BlueOceanScorer.TypeSafety(3, "Static typing"),
                                new BlueOceanScorer.Ecosystem(4, "Growing ecosystem"),
                                new BlueOceanScorer.TalentPool(4, "Popular language"),
                                new BlueOceanScorer.Autonomy(2, "No built-in supervision")));

        var rust =
                new BlueOceanScorer.PlatformProfile(
                        "Rust",
                        List.of(
                                new BlueOceanScorer.FaultTolerance(3, "Ownership + Result types"),
                                new BlueOceanScorer.TypeSafety(5, "Strongest type system"),
                                new BlueOceanScorer.Ecosystem(3, "Growing ecosystem"),
                                new BlueOceanScorer.TalentPool(2, "Smaller talent pool"),
                                new BlueOceanScorer.Autonomy(2, "No built-in supervision")));

        return List.of(erlang, akka, golang, rust);
    }

    // ── Default roadmap items ───────────────────────────────────────────────

    /**
     * Builds default Vision 2030 roadmap items covering the key milestones from Java 26 launch
     * through autonomous agent maturity.
     */
    private static List<RoadmapPrioritizer.RoadmapItem> defaultRoadmapItems() {
        return List.of(
                new RoadmapPrioritizer.RoadmapItem(
                        "java26-launch",
                        "Java 26 Launch with Virtual Threads",
                        2026,
                        10.0,
                        3.0,
                        List.of()),
                new RoadmapPrioritizer.RoadmapItem(
                        "spring-integration",
                        "Spring Boot Integration Layer",
                        2026,
                        8.0,
                        4.0,
                        List.of("java26-launch")),
                new RoadmapPrioritizer.RoadmapItem(
                        "supervision-trees",
                        "Production Supervision Trees",
                        2027,
                        9.0,
                        5.0,
                        List.of("java26-launch")),
                new RoadmapPrioritizer.RoadmapItem(
                        "agent-autonomy",
                        "Agent Autonomy Level 3",
                        2028,
                        9.0,
                        7.0,
                        List.of("supervision-trees")),
                new RoadmapPrioritizer.RoadmapItem(
                        "enterprise-adoption",
                        "Enterprise Adoption Toolkit",
                        2027,
                        7.0,
                        4.0,
                        List.of("spring-integration")),
                new RoadmapPrioritizer.RoadmapItem(
                        "self-healing",
                        "Self-Healing Infrastructure",
                        2029,
                        8.0,
                        8.0,
                        List.of("agent-autonomy")),
                new RoadmapPrioritizer.RoadmapItem(
                        "full-autonomy",
                        "Full Autonomous Operation (Level 5)",
                        2030,
                        10.0,
                        9.0,
                        List.of("self-healing", "enterprise-adoption")));
    }

    // ── Executive summary ───────────────────────────────────────────────────

    /**
     * Builds a concise executive summary string from all four engine results, including the blue
     * ocean index, adoption phase and readiness score, maturity level, Pareto set summary, and a
     * one-line recommendation.
     */
    private static String buildExecutiveSummary(
            EnterpriseProfile profile,
            BlueOceanScorer.BlueOceanAnalysis strategy,
            AdoptionPipelineEngine.AdoptionAssessment adoption,
            AgentMaturityScorer.MaturityAssessment maturity,
            RoadmapPrioritizer.PrioritizedRoadmap roadmap) {

        var phaseName = adoptionPhaseName(adoption.currentPhase());
        var recommendation = deriveRecommendation(adoption, maturity);

        return ("Vision 2030 Dashboard for %s: "
                        + "Blue Ocean Index: %.2f. "
                        + "Adoption Phase: %s (readiness %d/100). "
                        + "Maturity Level: %s (level %d/5). "
                        + "Roadmap: %s. "
                        + "Recommendation: %s")
                .formatted(
                        profile.name(),
                        strategy.blueOceanIndex(),
                        phaseName,
                        adoption.readiness().overall(),
                        maturity.overall().name(),
                        maturity.overall().level(),
                        roadmap.summary(),
                        recommendation);
    }

    /**
     * Extracts a human-readable name from the sealed {@link
     * AdoptionPipelineEngine.AdoptionPhase} hierarchy using exhaustive pattern matching.
     */
    private static String adoptionPhaseName(AdoptionPipelineEngine.AdoptionPhase phase) {
        return switch (phase) {
            case AdoptionPipelineEngine.AdoptionPhase.Assessment ignored -> "Assessment";
            case AdoptionPipelineEngine.AdoptionPhase.Pilot ignored -> "Pilot";
            case AdoptionPipelineEngine.AdoptionPhase.Production ignored -> "Production";
            case AdoptionPipelineEngine.AdoptionPhase.Scale ignored -> "Scale";
            case AdoptionPipelineEngine.AdoptionPhase.Blocked(var reason) ->
                    "Blocked (" + reason + ")";
        };
    }

    /**
     * Derives a one-line recommendation based on the adoption phase and maturity level. Combines
     * both dimensions to give actionable guidance.
     */
    private static String deriveRecommendation(
            AdoptionPipelineEngine.AdoptionAssessment adoption,
            AgentMaturityScorer.MaturityAssessment maturity) {

        int readiness = adoption.readiness().overall();
        int maturityLevel = maturity.overall().level();

        if (readiness >= 90 && maturityLevel >= 4) {
            return "Enterprise is ready for full-scale autonomous JOTP deployment.";
        } else if (readiness >= 75 && maturityLevel >= 3) {
            return "Move to production deployment and invest in agent autonomy capabilities.";
        } else if (readiness >= 60) {
            return "Begin pilot deployment; focus on improving maturity from %s to the next level."
                    .formatted(maturity.overall().name());
        } else if (readiness >= 40) {
            return "Continue assessment phase; address team training and infrastructure gaps.";
        } else {
            return "Foundational investment needed before JOTP adoption is viable.";
        }
    }
}
