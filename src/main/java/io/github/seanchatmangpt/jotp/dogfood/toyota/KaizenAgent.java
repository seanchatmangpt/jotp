package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;

/**
 * KaizenAgent — continuous improvement driven by the PDCA (Plan-Do-Check-Act) cycle.
 *
 * <p><strong>Toyota principle:</strong> Kaizen ("change for the better") is the relentless pursuit
 * of small, incremental improvements by every worker every day. Each PDCA cycle measures a
 * baseline, experiments with a hypothesis, checks results against the target, and either
 * standardises the gain or discards the change and loops back.
 *
 * <p><strong>Armstrong:</strong> "Software that cannot improve itself is already decaying. A
 * process should carry its own quality metrics and know when to ask for help."
 *
 * <p>The agent uses a {@link StateMachine} to enforce the strict PDCA sequence: {@code Idle →
 * Planning → Doing → Checking → Acting → Idle (next cycle)}. Improvement history accumulates in
 * {@link KaizenData} so that each cycle can compare against prior baselines.
 *
 * <pre>{@code
 * var kaizen = KaizenAgent.start("build-pipeline", 0.95); // target: 95% quality
 *
 * kaizen.send(new KaizenEvent.Plan("Parallelize test execution", 0.78));
 * kaizen.send(new KaizenEvent.Do("Split unit/IT suites into separate stages"));
 * kaizen.send(new KaizenEvent.Check(0.91, "P99 build time: 4m12s → 2m38s"));
 * kaizen.send(new KaizenEvent.Act(true, "Standardised: parallel stage added to pipeline"));
 * // State returns to Idle; cycle recorded in history.
 * }</pre>
 */
public final class KaizenAgent {

    private KaizenAgent() {}

    // ── States ────────────────────────────────────────────────────────────────

    /** PDCA cycle states — exhaustive by sealed contract. */
    public sealed interface KaizenState
            permits KaizenState.Idle,
                    KaizenState.Planning,
                    KaizenState.Doing,
                    KaizenState.Checking,
                    KaizenState.Acting {

        /** Awaiting the next improvement cycle. */
        record Idle() implements KaizenState {}

        /** Hypothesis formed; target and baseline captured. */
        record Planning(String hypothesis, double baseline) implements KaizenState {}

        /** Experiment running; change description recorded. */
        record Doing(String hypothesis, double baseline, String changeDescription)
                implements KaizenState {}

        /** Experiment complete; result measured against baseline and target. */
        record Checking(String hypothesis, double baseline, double result, String observation)
                implements KaizenState {}

        /** Decision: standardise the gain or revert and try again. */
        record Acting(boolean standardise, String rationale) implements KaizenState {}
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /** Events that drive the PDCA cycle. */
    public sealed interface KaizenEvent
            permits KaizenEvent.Plan, KaizenEvent.Do, KaizenEvent.Check, KaizenEvent.Act {

        /**
         * Begin a new improvement cycle with a hypothesis and current baseline metric.
         *
         * @param hypothesis what improvement is expected and why
         * @param baseline current measured value of the target metric (0.0–1.0)
         */
        record Plan(String hypothesis, double baseline) implements KaizenEvent {}

        /**
         * Execute the planned change.
         *
         * @param changeDescription what was actually done
         */
        record Do(String changeDescription) implements KaizenEvent {}

        /**
         * Measure the outcome and compare to baseline.
         *
         * @param result new measured value after the change
         * @param observation textual observation about the result
         */
        record Check(double result, String observation) implements KaizenEvent {}

        /**
         * Decide whether to standardise the gain or revert.
         *
         * @param standardise {@code true} to lock in the improvement, {@code false} to discard
         * @param rationale reason for the decision
         */
        record Act(boolean standardise, String rationale) implements KaizenEvent {}
    }

    // ── Domain types ─────────────────────────────────────────────────────────

    /** Completed PDCA cycle record. */
    public record CycleRecord(
            int cycleNumber,
            String hypothesis,
            double baseline,
            double result,
            boolean standardised,
            String rationale,
            Duration elapsed,
            Instant completedAt) {

        public double improvement() {
            return result - baseline;
        }
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    /**
     * Data carried across all PDCA states.
     *
     * @param metricName name of the quality metric being optimised
     * @param target desired metric value (improvement goal)
     * @param cycleHistory all completed PDCA cycles
     * @param cycleStartedAt timestamp when the current cycle began
     */
    public record KaizenData(
            String metricName,
            double target,
            List<CycleRecord> cycleHistory,
            Instant cycleStartedAt) {

        public static KaizenData initial(String metricName, double target) {
            return new KaizenData(metricName, target, new ArrayList<>(), null);
        }

        public int cycleCount() {
            return cycleHistory.size();
        }

        public OptionalDouble latestResult() {
            if (cycleHistory.isEmpty()) return OptionalDouble.empty();
            return OptionalDouble.of(cycleHistory.getLast().result());
        }

        public boolean targetAchieved() {
            return latestResult().isPresent() && latestResult().getAsDouble() >= target;
        }
    }

    // ── Transition logic ──────────────────────────────────────────────────────

    private static Transition<KaizenState, KaizenData> transition(
            KaizenState state, StateMachine.SMEvent<KaizenEvent> smEvent, KaizenData data) {

        return switch (smEvent) {
            case StateMachine.SMEvent.User(KaizenEvent event) ->
                    switch (state) {
                        case KaizenState.Idle() ->
                                switch (event) {
                                    case KaizenEvent.Plan(var hypothesis, var baseline) ->
                                            Transition.nextState(
                                                    new KaizenState.Planning(hypothesis, baseline),
                                                    new KaizenData(
                                                            data.metricName(),
                                                            data.target(),
                                                            data.cycleHistory(),
                                                            Instant.now()));
                                    default -> Transition.keepState(data);
                                };

                        case KaizenState.Planning(var hypothesis, var baseline) ->
                                switch (event) {
                                    case KaizenEvent.Do(var changeDescription) ->
                                            Transition.nextState(
                                                    new KaizenState.Doing(
                                                            hypothesis,
                                                            baseline,
                                                            changeDescription),
                                                    data);
                                    default -> Transition.keepState(data);
                                };

                        case KaizenState.Doing(var hypothesis, var baseline, var change) ->
                                switch (event) {
                                    case KaizenEvent.Check(var result, var observation) ->
                                            Transition.nextState(
                                                    new KaizenState.Checking(
                                                            hypothesis,
                                                            baseline,
                                                            result,
                                                            observation),
                                                    data);
                                    default -> Transition.keepState(data);
                                };

                        case KaizenState.Checking(
                                        var hypothesis,
                                        var baseline,
                                        var result,
                                        var obs) ->
                                switch (event) {
                                    case KaizenEvent.Act(var standardise, var rationale) -> {
                                        var elapsed =
                                                data.cycleStartedAt() != null
                                                        ? Duration.between(
                                                                data.cycleStartedAt(),
                                                                Instant.now())
                                                        : Duration.ZERO;
                                        var record =
                                                new CycleRecord(
                                                        data.cycleCount() + 1,
                                                        hypothesis,
                                                        baseline,
                                                        result,
                                                        standardise,
                                                        rationale,
                                                        elapsed,
                                                        Instant.now());
                                        var newHistory = new ArrayList<>(data.cycleHistory());
                                        newHistory.add(record);
                                        var newData =
                                                new KaizenData(
                                                        data.metricName(),
                                                        data.target(),
                                                        newHistory,
                                                        null);
                                        yield Transition.nextState(
                                                new KaizenState.Acting(standardise, rationale),
                                                newData);
                                    }
                                    default -> Transition.keepState(data);
                                };

                        case KaizenState.Acting ignored ->
                                switch (event) {
                                    case KaizenEvent.Plan(var hypothesis, var baseline) ->
                                            Transition.nextState(
                                                    new KaizenState.Planning(hypothesis, baseline),
                                                    new KaizenData(
                                                            data.metricName(),
                                                            data.target(),
                                                            data.cycleHistory(),
                                                            Instant.now()));
                                    default -> Transition.nextState(new KaizenState.Idle(), data);
                                };
                    };
            default -> Transition.keepState(data);
        };
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Starts a KaizenAgent for the named quality metric.
     *
     * @param metricName human-readable name of the metric (e.g. "test-pass-rate")
     * @param target desired metric value considered "target achieved" (0.0–1.0)
     * @return running {@link StateMachine}
     */
    public static StateMachine<KaizenState, KaizenEvent, KaizenData> start(
            String metricName, double target) throws InterruptedException {
        return StateMachine.create(
                        new KaizenState.Idle(),
                        KaizenData.initial(metricName, target),
                        KaizenAgent::transition)
                .start();
    }

    /**
     * Runs a complete PDCA cycle synchronously, blocking until the {@code Act} step completes.
     *
     * @return future resolving to the resulting {@link KaizenData}
     */
    public static CompletableFuture<KaizenData> runCycle(
            StateMachine<KaizenState, KaizenEvent, KaizenData> sm,
            String hypothesis,
            double baseline,
            String changeDesc,
            double result,
            String observation,
            boolean standardise,
            String rationale) {
        sm.send(new KaizenEvent.Plan(hypothesis, baseline));
        sm.send(new KaizenEvent.Do(changeDesc));
        sm.send(new KaizenEvent.Check(result, observation));
        return sm.call(new KaizenEvent.Act(standardise, rationale)).thenApply(ignored -> sm.data());
    }
}
