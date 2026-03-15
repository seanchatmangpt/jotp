package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * JidokaAgent — autonomous quality detection with stop-the-line authority.
 *
 * <p><strong>Toyota principle:</strong> Jidoka ("autonomation with a human touch") means machines
 * detect abnormalities and stop themselves rather than passing defects downstream. No defective
 * unit proceeds; the line halts until root cause is identified and resolved.
 *
 * <p><strong>Armstrong:</strong> "Let it crash — but crash loudly, early, and into a supervisor
 * that knows what to do. Silence is the enemy of quality."
 *
 * <p>The agent models a quality gate that inspects work items as they pass through. When defect
 * density exceeds the configured threshold, the agent transitions from {@code Running} → {@code
 * Halted}, records the defect log, moves through {@code Investigating}, and finally returns to
 * {@code Running} once a resolution is submitted.
 *
 * <pre>{@code
 * var jidoka = JidokaAgent.start(3); // halt after 3 defects per window
 * var sm = jidoka;
 *
 * sm.send(new JidokaEvent.Inspect("item-42", List.of("NullPointerException in login()")));
 * sm.send(new JidokaEvent.Inspect("item-43", List.of())); // clean
 * // ... after threshold breached:
 * assertThat(sm.state()).isInstanceOf(JidokaState.Halted.class);
 * sm.send(new JidokaEvent.Investigate("Null check missing in UserService.login"));
 * sm.send(new JidokaEvent.Resolve("Added Objects.requireNonNull guards — re-test passed"));
 * assertThat(sm.state()).isInstanceOf(JidokaState.Running.class);
 * }</pre>
 */
public final class JidokaAgent {

    private JidokaAgent() {}

    // ── States ────────────────────────────────────────────────────────────────

    /** Quality-gate pipeline states — exhaustive by sealed contract. */
    public sealed interface JidokaState
            permits JidokaState.Running,
                    JidokaState.Halted,
                    JidokaState.Investigating,
                    JidokaState.Resumed {

        /** Line is running; defects are within tolerance. */
        record Running() implements JidokaState {}

        /** Line stopped — defect threshold breached; awaiting investigation. */
        record Halted(Instant haltedAt, List<Defect> triggeringDefects) implements JidokaState {}

        /** Root-cause analysis in progress. */
        record Investigating(String hypothesis) implements JidokaState {}

        /** Resolution confirmed; transitioning back to Running on next cycle. */
        record Resumed(String resolution, Instant resumedAt) implements JidokaState {}
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /** Events that drive the quality-gate state machine. */
    public sealed interface JidokaEvent
            permits JidokaEvent.Inspect,
                    JidokaEvent.Investigate,
                    JidokaEvent.Resolve,
                    JidokaEvent.ClearWindow {

        /**
         * Submit a work item for quality inspection.
         *
         * @param itemId identifier of the work item
         * @param defects list of defect descriptions found (empty = clean pass)
         */
        record Inspect(String itemId, List<String> defects) implements JidokaEvent {}

        /** Begin root-cause analysis with a hypothesis. Only valid in {@code Halted} state. */
        record Investigate(String hypothesis) implements JidokaEvent {}

        /**
         * Submit resolution and return the line to {@code Running}. Only valid in {@code
         * Investigating}.
         */
        record Resolve(String resolution) implements JidokaEvent {}

        /** Reset the defect window counter (end of measurement period). */
        record ClearWindow() implements JidokaEvent {}
    }

    // ── Domain types ──────────────────────────────────────────────────────────

    /** A single defect observation attached to a work item. */
    public record Defect(String itemId, String description, Instant detectedAt) {}

    // ── Data ─────────────────────────────────────────────────────────────────

    /**
     * Data carried across all states.
     *
     * @param defectThreshold halt after this many defects in the current window
     * @param windowDefects defects detected in the current measurement window
     * @param allDefects cumulative defect history
     * @param itemsInspected total items inspected
     * @param haltCount number of times the line has been halted
     */
    public record JidokaData(
            int defectThreshold,
            List<Defect> windowDefects,
            List<Defect> allDefects,
            long itemsInspected,
            int haltCount) {

        public static JidokaData initial(int threshold) {
            return new JidokaData(threshold, new ArrayList<>(), new ArrayList<>(), 0L, 0);
        }

        public double defectRate() {
            if (itemsInspected == 0) return 0.0;
            return (double) allDefects.size() / itemsInspected;
        }
    }

    // ── Transition logic ──────────────────────────────────────────────────────

    private static Transition<JidokaState, JidokaData> transition(
            JidokaState state, StateMachine.SMEvent<JidokaEvent> smEvent, JidokaData data) {

        return switch (smEvent) {
            case StateMachine.SMEvent.User(JidokaEvent event) ->
                    switch (state) {
                        case JidokaState.Running() ->
                                switch (event) {
                                    case JidokaEvent.Inspect(var itemId, var defectDescs) -> {
                                        long newCount = data.itemsInspected() + 1;
                                        var newWindowDefects =
                                                new ArrayList<>(data.windowDefects());
                                        var newAllDefects = new ArrayList<>(data.allDefects());
                                        for (String desc : defectDescs) {
                                            var d = new Defect(itemId, desc, Instant.now());
                                            newWindowDefects.add(d);
                                            newAllDefects.add(d);
                                        }
                                        var newData =
                                                new JidokaData(
                                                        data.defectThreshold(),
                                                        newWindowDefects,
                                                        newAllDefects,
                                                        newCount,
                                                        data.haltCount());
                                        if (newWindowDefects.size() >= data.defectThreshold()) {
                                            var haltedData =
                                                    new JidokaData(
                                                            data.defectThreshold(),
                                                            newWindowDefects,
                                                            newAllDefects,
                                                            newCount,
                                                            data.haltCount() + 1);
                                            yield Transition.nextState(
                                                    new JidokaState.Halted(
                                                            Instant.now(),
                                                            List.copyOf(newWindowDefects)),
                                                    haltedData);
                                        }
                                        yield Transition.keepState(newData);
                                    }
                                    case JidokaEvent.ClearWindow() ->
                                            Transition.keepState(
                                                    new JidokaData(
                                                            data.defectThreshold(),
                                                            new ArrayList<>(),
                                                            data.allDefects(),
                                                            data.itemsInspected(),
                                                            data.haltCount()));
                                    default -> Transition.keepState(data);
                                };

                        case JidokaState.Halted ignored ->
                                switch (event) {
                                    case JidokaEvent.Investigate(var hypothesis) ->
                                            Transition.nextState(
                                                    new JidokaState.Investigating(hypothesis),
                                                    data);
                                    default -> Transition.keepState(data);
                                };

                        case JidokaState.Investigating ignored ->
                                switch (event) {
                                    case JidokaEvent.Resolve(var resolution) -> {
                                        var cleanData =
                                                new JidokaData(
                                                        data.defectThreshold(),
                                                        new ArrayList<>(),
                                                        data.allDefects(),
                                                        data.itemsInspected(),
                                                        data.haltCount());
                                        yield Transition.nextState(
                                                new JidokaState.Resumed(resolution, Instant.now()),
                                                cleanData);
                                    }
                                    default -> Transition.keepState(data);
                                };

                        case JidokaState.Resumed ignored ->
                                switch (event) {
                                    case JidokaEvent.Inspect ignored2 ->
                                            Transition.nextState(new JidokaState.Running(), data);
                                    default -> Transition.keepState(data);
                                };
                    };
            default -> Transition.keepState(data);
        };
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Starts a Jidoka quality-gate state machine.
     *
     * @param defectThreshold number of defects in a window that triggers a line halt
     * @return running {@link StateMachine}
     */
    public static StateMachine<JidokaState, JidokaEvent, JidokaData> start(int defectThreshold)
            throws InterruptedException {
        return StateMachine.create(
                        new JidokaState.Running(),
                        JidokaData.initial(defectThreshold),
                        JidokaAgent::transition)
                .start();
    }

    /**
     * Inspects a work item synchronously and returns the resulting machine state.
     *
     * @param sm running Jidoka state machine
     * @param itemId work item identifier
     * @param defects list of defect descriptions (empty for a clean pass)
     * @return future resolving to the post-inspection data
     */
    public static CompletableFuture<JidokaData> inspect(
            StateMachine<JidokaState, JidokaEvent, JidokaData> sm,
            String itemId,
            List<String> defects) {
        return sm.call(new JidokaEvent.Inspect(itemId, defects));
    }
}
