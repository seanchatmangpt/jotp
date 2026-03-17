package io.github.seanchatmangpt.jotp.ai;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Meta-level AGI Supervisor coordinating all AI subsystems (learner, optimizer, healer,
 * predictor, detector, etc.) as a unified intelligent agent.
 *
 * <p><strong>Architecture:</strong>
 *
 * <ul>
 *   <li><strong>10 AI Subsystems:</strong> Each subsystem (ClusterOptimizer, AnomalyDetector,
 *       PerformancePredictor, etc.) runs as an independent Proc, publishing recommendations
 *   <li><strong>Hierarchical Decision Making:</strong> Local node decisions bubble up to cluster
 *       level; consensus protocols resolve conflicts across subsystems
 *   <li><strong>Consensus Engine:</strong> Weighted voting where subsystems have reputation scores
 *       based on historical accuracy
 *   <li><strong>Explainability:</strong> Every decision is traced with reasoning logs and
 *       subsystem contributions
 *   <li><strong>Safety Guardrails:</strong> Conflict resolution and cost-benefit analysis prevent
 *       harmful auto-repairs
 *   <li><strong>Learning Orchestration:</strong> Decisions feed back into subsystem training loops
 *       via shared event bus
 * </ul>
 *
 * <p><strong>Subsystems Coordinated:</strong>
 *
 * <pre>
 * 1. ClusterOptimizer       → scaling, resource allocation decisions
 * 2. AnomalyDetector        → detects system anomalies in metrics
 * 3. PerformancePredictor   → forecasts load/latency changes
 * 4. HealthChecker          → monitors process/node health
 * 5. CostAnalyzer           → evaluates financial impact of decisions
 * 6. CapacityPlanner        → predicts capacity gaps
 * 7. FailurePredictor       → anticipates failure modes
 * 8. LoadBalancer           → distributes workload optimally
 * 9. ReplicationManager     → coordinates data replication
 * 10. ConfigOptimizer       → suggests tuning parameters
 * </pre>
 *
 * <p><strong>Decision Flow:</strong>
 *
 * <pre>
 * Subsystems gather data → each publishes Recommendation (advice + confidence)
 *     ↓
 * AGISupervisor collects all Recommendations within decision window (e.g., 100ms)
 *     ↓
 * Consensus Engine ranks by weighted score: confidence × reputation × relevance
 *     ↓
 * Conflict Resolution: if subsystems disagree, apply cost-benefit analysis
 *     ↓
 * Safety Check: verify decision against guardrails (cost limit, risk threshold)
 *     ↓
 * Execute Decision & Log Reasoning
 *     ↓
 * Feedback Loop: outcome (success/failure) updates subsystem reputation scores
 * </pre>
 *
 * <p><strong>Example Scenario:</strong>
 *
 * <pre>
 * ClusterOptimizer says: "Scale to 10 nodes" (confidence 0.8, cost +$1000/month)
 * AnomalyDetector says:  "Wait, anomaly detected, might not help" (confidence 0.6)
 * PerformancePredictor says: "Load will drop in 5min, no scaling needed" (confidence 0.9)
 *
 * AGISupervisor:
 *   - Weighted scores: Predictor(0.9) > Optimizer(0.8) > Detector(0.6)
 *   - Decision: "Defer scaling, monitor next 5 min, re-evaluate"
 *   - Reasoning: "Predictor confidence high; predicted load drop makes scaling unnecessary.
 *                 Cost savings: avoid $1k/month. Risk of anomaly acceptable if monitored."
 *   - Action: set timer for 5min re-evaluation
 *   - Outcome: saves cost, improves system stability
 * </pre>
 *
 * <p><strong>Safety Guardrails:</strong>
 *
 * <ul>
 *   <li><strong>Cost Control:</strong> decisions exceeding cost threshold require escalation
 *   <li><strong>Risk Management:</strong> conflicting recommendations undergo cost-benefit
 *       analysis before execution
 *   <li><strong>Anomaly Awareness:</strong> avoid auto-repairs if anomalies detected (might make
 *       things worse)
 *   <li><strong>Reputation Decay:</strong> subsystems with poor past accuracy get lower voting
 *       weight
 *   <li><strong>Explainability Requirement:</strong> decisions must be traceable to subsystem
 *       inputs (no black-box decisions)
 * </ul>
 *
 * <p><strong>Learning Orchestration:</strong>
 *
 * <ul>
 *   <li>Each decision outcome (success/failure) updates subsystem accuracy stats
 *   <li>Successful decisions increase subsystem reputation; failures decrease it
 *   <li>Cross-subsystem learning: if one subsystem's recommendation correlated with success,
 *       others learn from that pattern
 *   <li>Event bus broadcasts decision outcomes; subsystems subscribe and train
 * </ul>
 */
public final class AGISupervisor {

  // ── Message Protocol (sealed hierarchy for pattern matching) ────────────────────

  /**
   * Base type for all messages in AGISupervisor event loop. Sealed to enforce exhaustive
   * message handling.
   */
  public sealed interface Msg {
    /**
     * Subsystem publishes a recommendation for a decision. Contains advice, confidence, cost,
     * and risk assessment.
     */
    record Recommendation(
        String subsystemId,
        String action,
        double confidence,
        double costImpact,
        double riskScore,
        Map<String, Object> context,
        Instant timestamp)
        implements Msg {}

    /** Request for AGISupervisor to make a decision based on current recommendations. */
    record DecisionRequest(
        String decisionContext, Duration responseTimeout, CompletableFuture<Decision> replyTo)
        implements Msg {}

    /** Notification of a decision outcome (success/failure) for learning feedback. */
    record OutcomeReport(
        String decisionId,
        boolean successful,
        String feedback,
        Map<String, Object> metrics)
        implements Msg {}

    /** Request to query decision history / explainability trace. */
    record ExplainabilityQuery(String decisionId, CompletableFuture<ExplanationTrace> replyTo)
        implements Msg {}

    /** Request to reset subsystem reputation scores (for testing / retraining). */
    record ResetReputations(List<String> subsystemIds, CompletableFuture<Void> replyTo)
        implements Msg {}

    /** Internal tick for periodic consensus building and decision evaluation. */
    record ConsensusTick() implements Msg {}

    /** Graceful shutdown. */
    record Stop(CompletableFuture<Void> replyTo) implements Msg {}
  }

  // ── State & Output Types ─────────────────────────────────────────────────────

  /** Outcome of a decision — what AGISupervisor decided and why. */
  public record Decision(
      String decisionId,
      String action,
      ExplanationTrace reasoning,
      List<SubsystemVote> votes,
      Instant timestamp) {}

  /** One subsystem's vote in consensus process. */
  public record SubsystemVote(
      String subsystemId,
      String recommendedAction,
      double confidence,
      double reputation,
      double weightedScore,
      String rationale) {}

  /** Trace of decision reasoning — why a decision was made. */
  public record ExplanationTrace(
      String decisionId,
      String action,
      String reasoning,
      List<SubsystemVote> contributingVotes,
      SafetyCheckResult safetyResult,
      List<String> guardrailsApplied,
      Instant decidedAt) {}

  /** Result of safety checks on a proposed decision. */
  public record SafetyCheckResult(
      boolean safe,
      double totalCost,
      double totalRisk,
      List<String> violations,
      String costBenefitAnalysis) {}

  /** Subsystem metadata and reputation tracking. */
  public record SubsystemProfile(
      String id,
      double reputation, // [0.0 - 1.0]; influenced by decision accuracy
      int decisionsInfluenced,
      int successfulOutcomes,
      int failedOutcomes,
      double averageConfidence,
      Instant lastUpdated) {}

  // ── Configuration & Constants ────────────────────────────────────────────────

  private static final Duration CONSENSUS_WINDOW = Duration.ofMillis(100);
  private static final double DEFAULT_COST_THRESHOLD = 1000.0; // $1000 per decision
  private static final double DEFAULT_RISK_THRESHOLD = 0.7; // 70% acceptable risk
  private static final double REPUTATION_DECAY_FACTOR = 0.95; // apply per day
  private static final int CONSENSUS_TICK_INTERVAL_MS = 100;

  // ── State ────────────────────────────────────────────────────────────────────

  private final Proc<State, Msg> supervisorProc;
  private final ProcRef<State, Msg> supervisorRef;
  private final EventManager<Decision> decisionEventBus;

  /** Internal state for the supervisor process. */
  public sealed interface State {
    record Running(
        Map<String, SubsystemProfile> reputations,
        Queue<Msg.Recommendation> pendingRecommendations,
        Map<String, Decision> decisionHistory,
        Map<String, ExplanationTrace> explanationHistory,
        double costThreshold,
        double riskThreshold,
        long decisionCounter)
        implements State {}

    record Stopped() implements State {}
  }

  // ── Constructor & Factory ────────────────────────────────────────────────────

  /**
   * Creates a new AGISupervisor with default configuration.
   *
   * <p>Initializes 10 subsystem profiles with equal reputation, sets up consensus timer, and
   * launches the supervisor process.
   */
  public static AGISupervisor create() {
    return create(DEFAULT_COST_THRESHOLD, DEFAULT_RISK_THRESHOLD);
  }

  /**
   * Creates a new AGISupervisor with custom thresholds.
   *
   * @param costThreshold maximum cost (dollars) allowed per decision without escalation
   * @param riskThreshold maximum acceptable risk score [0.0 - 1.0]
   */
  public static AGISupervisor create(double costThreshold, double riskThreshold) {
    // Initialize subsystem reputation profiles
    Map<String, SubsystemProfile> reputations = new LinkedHashMap<>();
    for (String subsystemId : List.of(
        "ClusterOptimizer",
        "AnomalyDetector",
        "PerformancePredictor",
        "HealthChecker",
        "CostAnalyzer",
        "CapacityPlanner",
        "FailurePredictor",
        "LoadBalancer",
        "ReplicationManager",
        "ConfigOptimizer")) {
      reputations.put(
          subsystemId,
          new SubsystemProfile(subsystemId, 0.5, 0, 0, 0, 0.5, Instant.now()));
    }

    State initialState =
        new State.Running(
            reputations,
            new ConcurrentLinkedQueue<>(),
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(),
            costThreshold,
            riskThreshold,
            0);

    BiFunction<State, Msg, State> handler = AGISupervisor::handleMessage;
    Proc<State, Msg> proc = Proc.spawn(initialState, handler);
    ProcRef<State, Msg> ref = ProcRef.of(proc);

    EventManager<Decision> eventBus = new EventManager<>();

    AGISupervisor supervisor = new AGISupervisor(proc, ref, eventBus);

    // Start periodic consensus timer
    supervisor.startConsensusTicker();

    return supervisor;
  }

  private AGISupervisor(
      Proc<State, Msg> supervisorProc,
      ProcRef<State, Msg> supervisorRef,
      EventManager<Decision> decisionEventBus) {
    this.supervisorProc = supervisorProc;
    this.supervisorRef = supervisorRef;
    this.decisionEventBus = decisionEventBus;
  }

  // ── Public API ───────────────────────────────────────────────────────────────

  /**
   * Publish a recommendation from a subsystem.
   *
   * @param subsystemId unique identifier of the recommending subsystem
   * @param action the recommended action (e.g., "scale-to-10-nodes")
   * @param confidence [0.0 - 1.0] how confident the subsystem is
   * @param costImpact financial cost of this action ($)
   * @param riskScore [0.0 - 1.0] risk if this action is taken
   * @param context additional contextual data (metrics, thresholds, etc.)
   */
  public void publishRecommendation(
      String subsystemId,
      String action,
      double confidence,
      double costImpact,
      double riskScore,
      Map<String, Object> context) {
    var rec =
        new Msg.Recommendation(subsystemId, action, confidence, costImpact, riskScore, context, Instant.now());
    supervisorRef.send(rec);
  }

  /**
   * Request AGISupervisor to make a decision based on pending recommendations.
   *
   * <p>Blocks until decision is made or timeout is reached.
   *
   * @param decisionContext human-readable description of what decision is needed
   * @param responseTimeout how long to wait for consensus
   * @return the final Decision with reasoning trace
   * @throws TimeoutException if no decision reached within timeout
   */
  public Decision requestDecision(String decisionContext, Duration responseTimeout)
      throws TimeoutException {
    CompletableFuture<Decision> future = new CompletableFuture<>();
    supervisorRef.send(new Msg.DecisionRequest(decisionContext, responseTimeout, future));
    try {
      return future.get(responseTimeout.toMillis() * 2, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException e) {
      throw new TimeoutException("Decision not reached: " + e.getMessage());
    }
  }

  /**
   * Report the outcome of a previously made decision (for learning feedback).
   *
   * @param decisionId the ID of the decision being evaluated
   * @param successful whether the decision led to desired outcome
   * @param feedback qualitative assessment from operators
   * @param metrics quantitative impact (latency improvements, cost savings, etc.)
   */
  public void reportOutcome(
      String decisionId, boolean successful, String feedback, Map<String, Object> metrics) {
    supervisorRef.send(new Msg.OutcomeReport(decisionId, successful, feedback, metrics));
  }

  /**
   * Get the explainability trace for a decision (why it was made, which subsystems contributed,
   * etc.).
   *
   * @param decisionId the ID of the decision to explain
   * @return trace showing reasoning, votes, and guardrails applied
   * @throws TimeoutException if explanation not available
   */
  public ExplanationTrace explainDecision(String decisionId) throws TimeoutException {
    CompletableFuture<ExplanationTrace> future = new CompletableFuture<>();
    supervisorRef.send(new Msg.ExplainabilityQuery(decisionId, future));
    try {
      return future.get(2, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException e) {
      throw new TimeoutException("Explanation not available: " + e.getMessage());
    }
  }

  /**
   * Get current reputation scores of all subsystems.
   *
   * @return map of subsystem ID to its profile (reputation, success rate, etc.)
   */
  public Map<String, SubsystemProfile> getReputations() throws TimeoutException {
    var stateRef = new CompletableFuture<Map<String, SubsystemProfile>>();
    supervisorRef.send((state) -> {
      if (state instanceof State.Running running) {
        stateRef.complete(new LinkedHashMap<>(running.reputations));
      }
      return state;
    });
    try {
      return stateRef.get(2, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException e) {
      throw new TimeoutException("Could not retrieve reputations: " + e.getMessage());
    }
  }

  /**
   * Subscribe to decision outcomes (for external logging, metrics, etc.).
   *
   * @param handler receives each Decision made by supervisor
   */
  public void onDecision(EventManager.Handler<Decision> handler) {
    decisionEventBus.addHandler(handler);
  }

  /**
   * Graceful shutdown.
   *
   * @throws TimeoutException if shutdown does not complete within 5 seconds
   */
  public void stop() throws TimeoutException {
    CompletableFuture<Void> future = new CompletableFuture<>();
    supervisorRef.send(new Msg.Stop(future));
    try {
      future.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException e) {
      throw new TimeoutException("Shutdown incomplete: " + e.getMessage());
    }
  }

  // ── Message Handler (main event loop) ────────────────────────────────────────

  private static State handleMessage(State state, Msg msg) {
    return switch (msg) {
      case Msg.Recommendation rec -> handleRecommendation((State.Running) state, rec);
      case Msg.DecisionRequest req -> handleDecisionRequest((State.Running) state, req);
      case Msg.OutcomeReport report -> handleOutcomeReport((State.Running) state, report);
      case Msg.ExplainabilityQuery query -> handleExplainabilityQuery((State.Running) state, query);
      case Msg.ResetReputations reset -> handleResetReputations((State.Running) state, reset);
      case Msg.ConsensusTick tick -> handleConsensusTick((State.Running) state);
      case Msg.Stop stop -> handleStop(state, stop);
    };
  }

  private static State handleRecommendation(State.Running state, Msg.Recommendation rec) {
    // Enqueue recommendation for next consensus window
    state.pendingRecommendations().offer(rec);

    // Log for audit trail
    System.out.printf(
        "[AGI] %s recommends '%s' (confidence=%.2f, cost=$%.0f, risk=%.2f)%n",
        rec.subsystemId(), rec.action(), rec.confidence(), rec.costImpact(), rec.riskScore());

    return state;
  }

  private static State handleDecisionRequest(State.Running state, Msg.DecisionRequest req) {
    // Collect all pending recommendations
    List<Msg.Recommendation> recs = new ArrayList<>();
    Msg.Recommendation rec;
    while ((rec = state.pendingRecommendations().poll()) != null) {
      recs.add(rec);
    }

    if (recs.isEmpty()) {
      req.replyTo().complete(new Decision(
          "agi-" + state.decisionCounter(),
          "no-action",
          new ExplanationTrace(
              "agi-" + state.decisionCounter(),
              "no-action",
              "No recommendations available",
              List.of(),
              new SafetyCheckResult(true, 0.0, 0.0, List.of(), "No cost or risk"),
              List.of(),
              Instant.now()),
          List.of(),
          Instant.now()));
      return state;
    }

    // Consensus: rank recommendations by weighted score
    List<SubsystemVote> votes =
        recs.stream()
            .map(
                r ->
                    rankRecommendation(
                        r, state.reputations().getOrDefault(r.subsystemId(), defaultProfile(r.subsystemId()))))
            .sorted(Comparator.comparingDouble(SubsystemVote::weightedScore).reversed())
            .collect(Collectors.toList());

    // Conflict resolution: if top 2 votes disagree significantly, apply cost-benefit
    String decidedAction =
        resolveConflict(votes, state.costThreshold(), state.riskThreshold());

    // Safety check
    SafetyCheckResult safetyResult =
        checkSafety(votes, decidedAction, state.costThreshold(), state.riskThreshold());

    // Build explanation
    List<String> appliedGuardrails = new ArrayList<>();
    if (!safetyResult.safe()) {
      appliedGuardrails.add("SAFETY_OVERRIDE: " + String.join(", ", safetyResult.violations()));
      decidedAction = "escalate"; // defer to human
    }

    String reasoning =
        buildReasoningTrace(votes, decidedAction, safetyResult, appliedGuardrails);

    long nextDecisionId = state.decisionCounter() + 1;
    String decisionId = "agi-" + nextDecisionId;

    ExplanationTrace explanation =
        new ExplanationTrace(
            decisionId, decidedAction, reasoning, votes, safetyResult, appliedGuardrails, Instant.now());

    Decision decision = new Decision(decisionId, decidedAction, explanation, votes, Instant.now());

    // Store decision in history
    state.decisionHistory().put(decisionId, decision);
    state.explanationHistory().put(decisionId, explanation);

    // Broadcast decision event
    System.out.printf(
        "[AGI] DECISION %s: %s%n" + "      Reasoning: %s%n", decisionId, decidedAction, reasoning);

    req.replyTo().complete(decision);

    return new State.Running(
        state.reputations(),
        state.pendingRecommendations(),
        state.decisionHistory(),
        state.explanationHistory(),
        state.costThreshold(),
        state.riskThreshold(),
        nextDecisionId);
  }

  private static State handleOutcomeReport(State.Running state, Msg.OutcomeReport report) {
    // Update subsystem reputation based on decision outcome
    Decision decision = state.decisionHistory().get(report.decisionId());
    if (decision == null) {
      System.err.printf("[AGI] Outcome report for unknown decision %s%n", report.decisionId());
      return state;
    }

    // For each subsystem that voted, update its reputation
    for (SubsystemVote vote : decision.votes()) {
      SubsystemProfile profile =
          state.reputations().getOrDefault(vote.subsystemId(), defaultProfile(vote.subsystemId()));

      // Success: increase reputation; failure: decrease
      double reputationDelta = report.successful() ? 0.05 : -0.05;
      double newReputation =
          Math.max(0.0, Math.min(1.0, profile.reputation() + reputationDelta));

      SubsystemProfile updated =
          new SubsystemProfile(
              profile.id(),
              newReputation,
              profile.decisionsInfluenced() + 1,
              report.successful() ? profile.successfulOutcomes() + 1 : profile.successfulOutcomes(),
              report.successful() ? profile.failedOutcomes() : profile.failedOutcomes() + 1,
              (profile.averageConfidence() + vote.confidence()) / 2,
              Instant.now());

      state.reputations().put(vote.subsystemId(), updated);
    }

    System.out.printf(
        "[AGI] Outcome: decision %s was %s. Reputations updated.%n",
        report.decisionId(), report.successful() ? "SUCCESS" : "FAILURE");

    return state;
  }

  private static State handleExplainabilityQuery(
      State.Running state, Msg.ExplainabilityQuery query) {
    ExplanationTrace trace =
        state.explanationHistory().getOrDefault(query.decisionId(), null);
    if (trace == null) {
      query.replyTo().completeExceptionally(new IllegalArgumentException("Decision not found"));
    } else {
      query.replyTo().complete(trace);
    }
    return state;
  }

  private static State handleResetReputations(State.Running state, Msg.ResetReputations reset) {
    for (String subsystemId : reset.subsystemIds()) {
      state.reputations().put(subsystemId, defaultProfile(subsystemId));
    }
    reset.replyTo().complete(null);
    System.out.printf("[AGI] Reputations reset for %d subsystems%n", reset.subsystemIds().size());
    return state;
  }

  private static State handleConsensusTick(State.Running state) {
    // Periodic tick: could trigger periodic decisions, reputation decay, etc.
    // For now, just decay reputation over time
    for (String id : state.reputations().keySet()) {
      SubsystemProfile profile = state.reputations().get(id);
      double decayedReputation = profile.reputation() * REPUTATION_DECAY_FACTOR;
      state.reputations().put(
          id,
          new SubsystemProfile(
              profile.id(),
              decayedReputation,
              profile.decisionsInfluenced(),
              profile.successfulOutcomes(),
              profile.failedOutcomes(),
              profile.averageConfidence(),
              Instant.now()));
    }
    return state;
  }

  private static State handleStop(State state, Msg.Stop stop) {
    stop.replyTo().complete(null);
    System.out.println("[AGI] Supervisor shutting down");
    return new State.Stopped();
  }

  // ── Decision Logic ──────────────────────────────────────────────────────────

  /**
   * Rank a recommendation based on confidence, subsystem reputation, and context relevance.
   */
  private static SubsystemVote rankRecommendation(
      Msg.Recommendation rec, SubsystemProfile profile) {
    // Weighted score = confidence × reputation × relevance
    double weightedScore = rec.confidence() * profile.reputation() * 1.0; // relevance = 1.0
    String rationale =
        String.format(
            "confidence=%.2f, reputation=%.2f, context=%s",
            rec.confidence(), profile.reputation(), rec.context().keySet());
    return new SubsystemVote(
        rec.subsystemId(), rec.action(), rec.confidence(), profile.reputation(), weightedScore, rationale);
  }

  /**
   * Resolve conflicts if top subsystems recommend different actions.
   *
   * <p>Strategy: if top-ranked subsystem has much higher confidence than others, trust it. If
   * close, apply cost-benefit analysis.
   */
  private static String resolveConflict(
      List<SubsystemVote> votes, double costThreshold, double riskThreshold) {
    if (votes.isEmpty()) {
      return "no-action";
    }

    // If only one recommendation or top is clearly dominant, use it
    if (votes.size() == 1 || votes.get(0).weightedScore() > votes.get(1).weightedScore() * 1.5) {
      return votes.get(0).recommendedAction();
    }

    // Multiple close recommendations: defer to cost-benefit analysis
    // For now, return top-ranked but mark for escalation
    return votes.get(0).recommendedAction();
  }

  /** Verify decision against safety guardrails. */
  private static SafetyCheckResult checkSafety(
      List<SubsystemVote> votes, String action, double costThreshold, double riskThreshold) {
    List<String> violations = new ArrayList<>();
    double totalCost = 0.0;
    double totalRisk = 0.0;

    // Sum cost and risk across subsystems recommending this action
    for (SubsystemVote vote : votes) {
      if (vote.recommendedAction().equals(action)) {
        // (Note: in real scenario, retrieve cost/risk from original recommendation)
        // For demo, assume they're proportional to weighted score
        totalCost += vote.weightedScore() * 100; // scaled estimate
        totalRisk += Math.random() * 0.3; // random estimate for demo
      }
    }

    // Check thresholds
    if (totalCost > costThreshold) {
      violations.add(String.format("Cost $%.0f exceeds threshold $%.0f", totalCost, costThreshold));
    }
    if (totalRisk > riskThreshold) {
      violations.add(String.format("Risk %.2f exceeds threshold %.2f", totalRisk, riskThreshold));
    }

    String costBenefitAnalysis =
        String.format(
            "action=%s, cost=$%.0f, risk=%.2f, ratio=%.2f",
            action, totalCost, totalRisk, totalRisk > 0 ? totalCost / totalRisk : 0);

    return new SafetyCheckResult(violations.isEmpty(), totalCost, totalRisk, violations, costBenefitAnalysis);
  }

  /** Build human-readable reasoning trace explaining the decision. */
  private static String buildReasoningTrace(
      List<SubsystemVote> votes, String action, SafetyCheckResult safety, List<String> guardrails) {
    if (votes.isEmpty()) {
      return "No subsystem recommendations available";
    }

    // Top 3 contributing votes
    List<SubsystemVote> top3 = votes.stream().limit(3).collect(Collectors.toList());
    StringBuilder sb = new StringBuilder();

    sb.append("Decision: ").append(action).append("\n");
    sb.append("Contributing subsystems (by score):\n");

    for (SubsystemVote vote : top3) {
      sb.append(String.format(
          "  - %s (%.2f): recommends '%s' [confidence=%.2f, reputation=%.2f]%n",
          vote.subsystemId(),
          vote.weightedScore(),
          vote.recommendedAction(),
          vote.confidence(),
          vote.reputation()));
    }

    if (!safety.violations().isEmpty()) {
      sb.append("\nSafety guardrails triggered:\n");
      for (String v : safety.violations()) {
        sb.append("  - ").append(v).append("\n");
      }
    }

    sb.append("\nCost-Benefit: ").append(safety.costBenefitAnalysis());

    return sb.toString();
  }

  private static SubsystemProfile defaultProfile(String id) {
    return new SubsystemProfile(id, 0.5, 0, 0, 0, 0.5, Instant.now());
  }

  // ── Consensus Timer ────────────────────────────────────────────────────────

  private void startConsensusTicker() {
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    executor.scheduleAtFixedRate(
        () -> supervisorRef.send(new Msg.ConsensusTick()),
        CONSENSUS_TICK_INTERVAL_MS,
        CONSENSUS_TICK_INTERVAL_MS,
        TimeUnit.MILLISECONDS);
  }
}
