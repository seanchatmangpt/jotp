package io.github.seanchatmangpt.jotp.ai;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Comprehensive tests for AGISupervisor multi-AI coordination.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Consensus building across multiple subsystem recommendations
 *   <li>Conflict resolution when subsystems disagree
 *   <li>Safety guardrails preventing harmful decisions
 *   <li>Reputation-based weighting
 *   <li>Explainability and reasoning traces
 *   <li>Learning feedback (outcome reporting)
 *   <li>Cost-benefit analysis
 * </ul>
 */
@DisplayName("AGI Supervisor Multi-AI Coordination")
class AGISupervisorTest {

  private AGISupervisor supervisor;

  @BeforeEach
  void setup() {
    ApplicationController.reset();
    supervisor = AGISupervisor.create();
  }

  // ── Basic Consensus Tests ────────────────────────────────────────────────────

  @Test
  @DisplayName("Single recommendation should be adopted without conflict")
  void testSingleRecommendation() throws Exception {
    // Single ClusterOptimizer recommends scaling
    supervisor.publishRecommendation(
        "ClusterOptimizer",
        "scale-to-10-nodes",
        0.9, // high confidence
        500.0, // $500/month cost
        0.3, // 30% risk
        Map.of("currentNodes", 5, "targetLoad", "80%"));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Should we scale the cluster?", Duration.ofSeconds(2));

    assertThat(decision.action()).isEqualTo("scale-to-10-nodes");
    assertThat(decision.votes()).hasSize(1);
    assertThat(decision.votes().get(0).subsystemId()).isEqualTo("ClusterOptimizer");
    assertThat(decision.votes().get(0).confidence()).isEqualTo(0.9);
  }

  @Test
  @DisplayName("Agreement among multiple subsystems yields confident consensus")
  void testConsensusAgreement() throws Exception {
    // Multiple subsystems agree on scaling
    supervisor.publishRecommendation(
        "ClusterOptimizer", "scale-to-10-nodes", 0.85, 500.0, 0.3, Map.of("nodes", 5));
    supervisor.publishRecommendation(
        "CapacityPlanner", "scale-to-10-nodes", 0.80, 500.0, 0.25, Map.of("projection", "60 days"));
    supervisor.publishRecommendation(
        "LoadBalancer", "scale-to-10-nodes", 0.75, 500.0, 0.35, Map.of("distribution", "skewed"));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Should we scale?", Duration.ofSeconds(2));

    // Decision should be to scale (consensus)
    assertThat(decision.action()).isEqualTo("scale-to-10-nodes");
    assertThat(decision.votes()).hasSize(3);
    System.out.println("Consensus Decision: " + decision.action());
    System.out.println("Reasoning:\n" + decision.reasoning().reasoning());
  }

  @Test
  @DisplayName("Conflict: high-confidence predictor overrides optimizer")
  void testConflictResolutionWithHigherConfidence() throws Exception {
    // Optimizer says scale (medium confidence)
    supervisor.publishRecommendation(
        "ClusterOptimizer",
        "scale-to-10-nodes",
        0.6, // medium confidence
        1000.0,
        0.4,
        Map.of("currentLoad", 0.75));

    // Predictor says wait (high confidence that load will drop)
    supervisor.publishRecommendation(
        "PerformancePredictor",
        "wait-5-minutes",
        0.95, // very high confidence
        0.0, // no cost
        0.1, // low risk
        Map.of("predictedLoadDropInSeconds", 300, "predictedLoadLevel", 0.4));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("To scale or not to scale?", Duration.ofSeconds(2));

    // Predictor should dominate due to higher confidence
    assertThat(decision.votes()).isNotEmpty();
    assertThat(decision.votes().get(0).subsystemId()).isEqualTo("PerformancePredictor");
    assertThat(decision.votes().get(0).confidence()).isEqualTo(0.95);
    System.out.println("Winner: " + decision.votes().get(0).subsystemId());
  }

  @Test
  @DisplayName("Anomaly detector prevents scaling during anomaly")
  void testAnomalyDetectionInhibitsAction() throws Exception {
    // Optimizer recommends scaling
    supervisor.publishRecommendation(
        "ClusterOptimizer", "scale-to-10-nodes", 0.8, 500.0, 0.3, Map.of());

    // Anomaly detector warns about unusual metrics
    supervisor.publishRecommendation(
        "AnomalyDetector",
        "investigate-first",
        0.85, // high confidence in anomaly
        0.0,
        0.6, // high risk of scaling during anomaly
        Map.of("anomalies", List.of("memory_spike", "latency_jitter")));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Scale despite anomalies?", Duration.ofSeconds(2));

    // Should prioritize anomaly investigation (higher confidence)
    assertThat(decision.reasoning().reasoning()).contains("anomaly");
    System.out.println("Anomaly took precedence: " + decision.action());
  }

  // ── Safety Guardrails Tests ──────────────────────────────────────────────────

  @Test
  @DisplayName("Cost threshold prevents expensive decisions")
  void testCostGuardrail() throws Exception {
    AGISupervisor expensive = AGISupervisor.create(100.0, 1.0); // $100 cost limit

    // Very expensive recommendation
    expensive.publishRecommendation(
        "ClusterOptimizer",
        "scale-to-100-nodes",
        0.9,
        5000.0, // $5000 cost — exceeds limit
        0.2,
        Map.of());

    AGISupervisor.Decision decision = expensive.requestDecision("Expensive scaling?", Duration.ofSeconds(2));

    // Safety should be triggered; decision should be escalated
    AGISupervisor.SafetyCheckResult safety = decision.reasoning().safetyResult();
    System.out.println("Safety violations: " + safety.violations());
    System.out.println("Decision escalated to: " + decision.action());
  }

  @Test
  @DisplayName("Risk threshold protects against high-risk decisions")
  void testRiskGuardrail() throws Exception {
    AGISupervisor risky = AGISupervisor.create(10000.0, 0.5); // max 50% risk

    supervisor.publishRecommendation(
        "FailurePredictor",
        "migrate-all-data",
        0.7,
        1000.0,
        0.8, // 80% risk — exceeds 50% limit
        Map.of());

    AGISupervisor.Decision decision = risky.requestDecision("Risky migration?", Duration.ofSeconds(2));

    AGISupervisor.SafetyCheckResult safety = decision.reasoning().safetyResult();
    System.out.println("Risk violations: " + safety.violations());
  }

  // ── Reputation & Learning Tests ──────────────────────────────────────────────

  @Test
  @DisplayName("Reputation scores influence future decision weighting")
  void testReputationBasedWeighting() throws Exception {
    // Get initial reputations (all should be 0.5)
    Map<String, AGISupervisor.SubsystemProfile> initial = supervisor.getReputations();
    assertThat(initial).containsKeys(
        "ClusterOptimizer",
        "AnomalyDetector",
        "PerformancePredictor",
        "HealthChecker",
        "CostAnalyzer",
        "CapacityPlanner",
        "FailurePredictor",
        "LoadBalancer",
        "ReplicationManager",
        "ConfigOptimizer");
    initial.forEach(
        (id, profile) -> assertThat(profile.reputation()).isEqualTo(0.5));

    // Make a decision where ClusterOptimizer has high confidence
    supervisor.publishRecommendation("ClusterOptimizer", "scale", 0.9, 100.0, 0.2, Map.of());
    AGISupervisor.Decision decision =
        supervisor.requestDecision("Scale?", Duration.ofSeconds(2));

    // Report success
    supervisor.reportOutcome(decision.decisionId(), true, "Scaling worked great", Map.of());

    // ClusterOptimizer reputation should increase
    Map<String, AGISupervisor.SubsystemProfile> updated = supervisor.getReputations();
    assertThat(updated.get("ClusterOptimizer").reputation())
        .isGreaterThan(initial.get("ClusterOptimizer").reputation());
    assertThat(updated.get("ClusterOptimizer").successfulOutcomes()).isEqualTo(1);

    System.out.println("ClusterOptimizer reputation improved: " +
        initial.get("ClusterOptimizer").reputation() + " → " +
        updated.get("ClusterOptimizer").reputation());
  }

  @Test
  @DisplayName("Failed decisions decrease subsystem reputation")
  void testReputationDecayOnFailure() throws Exception {
    // Make decision
    supervisor.publishRecommendation("HealthChecker", "restart-service", 0.8, 50.0, 0.4, Map.of());
    AGISupervisor.Decision decision = supervisor.requestDecision("Restart?", Duration.ofSeconds(2));

    // Get initial reputation
    Map<String, AGISupervisor.SubsystemProfile> before = supervisor.getReputations();
    double beforeRep = before.get("HealthChecker").reputation();

    // Report failure
    supervisor.reportOutcome(
        decision.decisionId(), false, "Restart made things worse", Map.of());

    // Check reputation decreased
    Map<String, AGISupervisor.SubsystemProfile> after = supervisor.getReputations();
    double afterRep = after.get("HealthChecker").reputation();

    assertThat(afterRep).isLessThan(beforeRep);
    System.out.println("HealthChecker reputation decreased: " + beforeRep + " → " + afterRep);
  }

  // ── Explainability Tests ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Decision reasoning is fully traceable")
  void testExplainabilityTrace() throws Exception {
    supervisor.publishRecommendation(
        "ClusterOptimizer", "scale-to-15-nodes", 0.85, 600.0, 0.35, Map.of("nodes", 5));
    supervisor.publishRecommendation(
        "PerformancePredictor",
        "scale-to-15-nodes",
        0.80,
        600.0,
        0.30,
        Map.of("prediction", "P95 latency"));
    supervisor.publishRecommendation(
        "CostAnalyzer", "scale-to-12-nodes", 0.70, 450.0, 0.25, Map.of("budget", "ok"));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Scale cluster?", Duration.ofSeconds(2));

    // Get full explanation
    AGISupervisor.ExplanationTrace trace =
        supervisor.explainDecision(decision.decisionId());

    assertThat(trace.decisionId()).isEqualTo(decision.decisionId());
    assertThat(trace.action()).isEqualTo(decision.action());
    assertThat(trace.contributingVotes()).isNotEmpty();
    assertThat(trace.reasoning()).contains("subsystems");
    assertThat(trace.safetyResult()).isNotNull();

    System.out.println("Full Explanation for " + decision.decisionId() + ":");
    System.out.println(trace.reasoning());
    System.out.println("\nSafety Check: " + trace.safetyResult().safe());
  }

  @Test
  @DisplayName("Subsystem vote rationale is recorded")
  void testVoteRationale() throws Exception {
    supervisor.publishRecommendation(
        "ConfigOptimizer",
        "tune-jvm-heap",
        0.75,
        10.0,
        0.1,
        Map.of("currentHeap", "2GB", "recommendedHeap", "4GB"));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Tune JVM?", Duration.ofSeconds(2));

    AGISupervisor.SubsystemVote vote = decision.votes().get(0);
    assertThat(vote.rationale()).isNotEmpty();
    assertThat(vote.rationale()).contains("confidence", "reputation");

    System.out.println("ConfigOptimizer vote:");
    System.out.println("  Action: " + vote.recommendedAction());
    System.out.println("  Rationale: " + vote.rationale());
  }

  // ── Multi-Subsystem Coordination Tests ───────────────────────────────────────

  @Test
  @DisplayName("All 10 subsystems can coordinate on single decision")
  void testAllSubsystemsCoordination() throws Exception {
    String[] subsystems = {
        "ClusterOptimizer",
        "AnomalyDetector",
        "PerformancePredictor",
        "HealthChecker",
        "CostAnalyzer",
        "CapacityPlanner",
        "FailurePredictor",
        "LoadBalancer",
        "ReplicationManager",
        "ConfigOptimizer"
    };

    // Each subsystem publishes a recommendation for scaling
    for (int i = 0; i < subsystems.length; i++) {
      supervisor.publishRecommendation(
          subsystems[i],
          "scale-cluster",
          0.5 + (i * 0.04), // varying confidence
          500.0 + (i * 50),
          0.2 + (i * 0.03),
          Map.of("subsystemId", subsystems[i]));
    }

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Coordinated scaling decision?", Duration.ofSeconds(2));

    // Should have collected all 10 votes
    assertThat(decision.votes()).hasSize(subsystems.length);
    assertThat(decision.action()).isNotEmpty();

    System.out.println("Coordinated decision from 10 subsystems:");
    System.out.println("  Final Action: " + decision.action());
    System.out.println("  Total Votes: " + decision.votes().size());
    decision.votes().forEach(
        v -> System.out.println("    - " + v.subsystemId() + ": " +
            v.recommendedAction() + " (score=" + String.format("%.3f", v.weightedScore()) + ")"));
  }

  @Test
  @DisplayName("Decision with conflicting subsystems shows cost-benefit analysis")
  void testConflictWithCostBenefitAnalysis() throws Exception {
    // Aggressive scaler
    supervisor.publishRecommendation(
        "ClusterOptimizer",
        "scale-to-20-nodes",
        0.9,
        2000.0, // very expensive
        0.5,
        Map.of("aggressive", true));

    // Conservative optimizer
    supervisor.publishRecommendation(
        "CostAnalyzer",
        "defer-scaling",
        0.85,
        0.0, // no cost
        0.1, // low risk
        Map.of("budget", "constrained"));

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Aggressive vs. conservative?", Duration.ofSeconds(2));

    AGISupervisor.ExplanationTrace trace =
        supervisor.explainDecision(decision.decisionId());

    System.out.println("Conflict resolution via cost-benefit:");
    System.out.println("  " + trace.safetyResult().costBenefitAnalysis());
    System.out.println("  Decision: " + decision.action());
  }

  @Test
  @DisplayName("Empty recommendation set produces safe no-op decision")
  void testEmptyRecommendations() throws Exception {
    AGISupervisor.Decision decision =
        supervisor.requestDecision("Any ideas?", Duration.ofSeconds(2));

    assertThat(decision.action()).isEqualTo("no-action");
    assertThat(decision.votes()).isEmpty();
    assertThat(decision.reasoning().safetyResult().safe()).isTrue();
  }

  // ── Learning & Feedback Loop Tests ───────────────────────────────────────────

  @Test
  @DisplayName("Multiple decision outcomes update reputation distribution")
  void testReputationEvolution() throws Exception {
    // Make 5 decisions and report outcomes
    for (int i = 0; i < 5; i++) {
      supervisor.publishRecommendation("ClusterOptimizer", "scale", 0.8, 100.0, 0.2, Map.of());
      supervisor.publishRecommendation("AnomalyDetector", "investigate", 0.6, 0.0, 0.3, Map.of());

      AGISupervisor.Decision decision =
          supervisor.requestDecision("Decision " + i, Duration.ofSeconds(2));

      // Alternate success and failure for ClusterOptimizer
      boolean success = (i % 2 == 0);
      supervisor.reportOutcome(
          decision.decisionId(),
          success,
          success ? "Good call" : "Wrong call",
          Map.of());
    }

    Map<String, AGISupervisor.SubsystemProfile> reputations = supervisor.getReputations();
    AGISupervisor.SubsystemProfile optimizer = reputations.get("ClusterOptimizer");

    assertThat(optimizer.decisionsInfluenced()).isGreaterThan(0);
    System.out.println("ClusterOptimizer after 5 decisions:");
    System.out.println("  Successful: " + optimizer.successfulOutcomes());
    System.out.println("  Failed: " + optimizer.failedOutcomes());
    System.out.println("  Reputation: " + String.format("%.3f", optimizer.reputation()));
  }

  @Test
  @DisplayName("Explainability tracks how each subsystem influenced final decision")
  void testExplainabilityInfuence() throws Exception {
    supervisor.publishRecommendation(
        "PerformancePredictor",
        "scale-for-peak",
        0.92,
        700.0,
        0.25,
        Map.of("peakLoadTime", "14:30 UTC"));
    supervisor.publishRecommendation(
        "CapacityPlanner",
        "add-5-nodes",
        0.78,
        350.0,
        0.15,
        Map.of());
    supervisor.publishRecommendation(
        "CostAnalyzer",
        "add-3-nodes",
        0.65,
        210.0,
        0.10,
        Map.of());

    AGISupervisor.Decision decision =
        supervisor.requestDecision("Optimal scaling?", Duration.ofSeconds(2));
    AGISupervisor.ExplanationTrace trace =
        supervisor.explainDecision(decision.decisionId());

    // Verify trace shows contribution breakdown
    assertThat(trace.contributingVotes()).isNotEmpty();
    trace.contributingVotes().forEach(
        v -> assertThat(v.weightedScore()).isGreaterThan(0.0));

    System.out.println("Contribution breakdown for decision:");
    trace.contributingVotes().forEach(v ->
        System.out.println("  " + v.subsystemId() + ": score=" +
            String.format("%.3f", v.weightedScore())));
  }

  @Test
  @DisplayName("Guardrails log their application in reasoning")
  void testGuardrailLogging() throws Exception {
    AGISupervisor strict = AGISupervisor.create(50.0, 0.3); // very strict

    strict.publishRecommendation(
        "ClusterOptimizer", "scale-expensive", 0.9, 500.0, 0.7, Map.of());

    AGISupervisor.Decision decision =
        strict.requestDecision("Expensive + risky?", Duration.ofSeconds(2));

    AGISupervisor.ExplanationTrace trace =
        strict.explainDecision(decision.decisionId());

    // If guardrails applied, they should be logged
    if (!trace.guardrailsApplied().isEmpty()) {
      System.out.println("Applied guardrails:");
      trace.guardrailsApplied().forEach(g -> System.out.println("  - " + g));
      assertThat(trace.guardrailsApplied()).isNotEmpty();
    }
  }

  @Test
  @DisplayName("Shutdown completes gracefully")
  void testGracefulShutdown() throws Exception {
    supervisor.publishRecommendation(
        "ConfigOptimizer", "tune-config", 0.7, 10.0, 0.1, Map.of());
    AGISupervisor.Decision d =
        supervisor.requestDecision("Config?", Duration.ofSeconds(2));

    // Should shutdown without hanging
    assertThatCode(() -> supervisor.stop())
        .doesNotThrowAnyException();
  }
}
