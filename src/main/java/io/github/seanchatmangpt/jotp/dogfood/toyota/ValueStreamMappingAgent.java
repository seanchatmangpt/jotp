package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ValueStreamMappingAgent — measures end-to-end flow and identifies waste.
 *
 * <p><strong>Toyota principle:</strong> Value stream mapping (VSM) visualises the full sequence of
 * activities required to deliver a product, distinguishing value-added time (VA) from non-value-
 * added time (NVA, "muda"). The current-state map reveals bottlenecks and waiting waste; the
 * future-state map guides improvement.
 *
 * <p><strong>Armstrong:</strong> "You cannot improve what you cannot measure. Every process should
 * know its own cycle time, queue depth, and the ratio of work to waiting."
 *
 * <p>The agent receives {@link FlowEvent}s as work items move through named stages. It accumulates
 * timing data per stage, computes VA/NVA ratios, identifies the longest waiting segments, and
 * produces a {@link ValueStreamMap} on demand.
 *
 * <pre>{@code
 * var vsm = ValueStreamMappingAgent.start();
 *
 * var id = "ORDER-42";
 * vsm.enter(id, "requirements");
 * Thread.sleep(50);
 * vsm.exit(id, "requirements");
 * vsm.enter(id, "development");
 * Thread.sleep(100);
 * vsm.exit(id, "development");
 *
 * var map = vsm.currentStateMap(Duration.ofSeconds(1));
 * assertThat(map.pcEfficiency()).isGreaterThan(0.0);
 * }</pre>
 */
public final class ValueStreamMappingAgent {

    // ── Domain types ──────────────────────────────────────────────────────────

    /** Classification of a processing stage. */
    public enum StageType {
        /** Work that directly adds value for the customer (e.g. development, testing). */
        VALUE_ADDED,
        /**
         * Work required by the business but not directly valued by the customer (e.g. approval).
         */
        BUSINESS_NON_VALUE_ADDED,
        /** Pure waste: waiting, rework, motion, transport, overproduction. */
        WASTE
    }

    /** Declaration of a named stage in the value stream. */
    public record StageDef(String name, StageType type) {}

    /** Observed timing record for one work item through one stage. */
    public record StagePassage(
            String itemId, String stageName, Instant enteredAt, Instant exitedAt) {

        public Duration cycleTime() {
            return Duration.between(enteredAt, exitedAt);
        }
    }

    /** Per-stage aggregate statistics. */
    public record StageStats(
            String stageName,
            StageType type,
            int passageCount,
            Duration totalCycleTime,
            Duration avgCycleTime,
            Duration maxCycleTime) {}

    /**
     * The current-state value stream map.
     *
     * @param stages ordered stage statistics
     * @param leadTime total observed lead time across all completed passages
     * @param valueAddedTime time in VALUE_ADDED stages
     * @param pcEfficiency process cycle efficiency = valueAddedTime / leadTime
     * @param bottleneck name of the stage with the longest average cycle time
     * @param wasteStages stages classified as WASTE
     * @param generatedAt when this map was produced
     */
    public record ValueStreamMap(
            List<StageStats> stages,
            Duration leadTime,
            Duration valueAddedTime,
            double pcEfficiency,
            String bottleneck,
            List<String> wasteStages,
            Instant generatedAt) {}

    // ── Flow events ───────────────────────────────────────────────────────────

    /** Messages accepted by the VSM process. */
    public sealed interface VsmMsg
            permits VsmMsg.DefineStage, VsmMsg.Enter, VsmMsg.Exit, VsmMsg.GetMap {

        /** Register a stage definition with its type classification. */
        record DefineStage(StageDef stage) implements VsmMsg {}

        /** Record a work item entering a stage. */
        record Enter(String itemId, String stageName) implements VsmMsg {}

        /** Record a work item exiting a stage. */
        record Exit(String itemId, String stageName) implements VsmMsg {}

        /** Produce the current-state map (ask() pattern). */
        record GetMap() implements VsmMsg {}
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** State held by the VSM process. */
    public record VsmState(
            Map<String, StageDef> stageDefs,
            Map<String, Instant> openPassages, // key: itemId:stageName
            List<StagePassage> completedPassages) {

        public static VsmState empty() {
            return new VsmState(new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>());
        }

        public VsmState defineStage(StageDef def) {
            stageDefs.put(def.name(), def);
            return this;
        }

        public VsmState enter(String itemId, String stageName) {
            openPassages.put(itemId + ":" + stageName, Instant.now());
            return this;
        }

        public VsmState exit(String itemId, String stageName) {
            Instant entered = openPassages.remove(itemId + ":" + stageName);
            if (entered != null) {
                completedPassages.add(new StagePassage(itemId, stageName, entered, Instant.now()));
            }
            return this;
        }

        public ValueStreamMap buildMap() {
            // Compute per-stage stats
            var stageStats = new ArrayList<StageStats>();
            for (var def : stageDefs.values()) {
                var passages =
                        completedPassages.stream()
                                .filter(p -> p.stageName().equals(def.name()))
                                .toList();
                if (passages.isEmpty()) continue;
                Duration total =
                        passages.stream()
                                .map(StagePassage::cycleTime)
                                .reduce(Duration.ZERO, Duration::plus);
                Duration max =
                        passages.stream()
                                .map(StagePassage::cycleTime)
                                .max(Duration::compareTo)
                                .orElse(Duration.ZERO);
                Duration avg = total.dividedBy(passages.size());
                stageStats.add(
                        new StageStats(def.name(), def.type(), passages.size(), total, avg, max));
            }

            // Lead time = sum of all stage totals
            Duration leadTime =
                    stageStats.stream()
                            .map(StageStats::totalCycleTime)
                            .reduce(Duration.ZERO, Duration::plus);

            // Value-added time
            Duration vaTime =
                    stageStats.stream()
                            .filter(s -> s.type() == StageType.VALUE_ADDED)
                            .map(StageStats::totalCycleTime)
                            .reduce(Duration.ZERO, Duration::plus);

            double pcEff = leadTime.isZero() ? 0.0 : (double) vaTime.toNanos() / leadTime.toNanos();

            String bottleneck =
                    stageStats.stream()
                            .max((a, b) -> a.avgCycleTime().compareTo(b.avgCycleTime()))
                            .map(StageStats::stageName)
                            .orElse("none");

            List<String> wasteStages =
                    stageStats.stream()
                            .filter(s -> s.type() == StageType.WASTE)
                            .map(StageStats::stageName)
                            .toList();

            return new ValueStreamMap(
                    List.copyOf(stageStats),
                    leadTime,
                    vaTime,
                    pcEff,
                    bottleneck,
                    wasteStages,
                    Instant.now());
        }
    }

    // ── Handler ───────────────────────────────────────────────────────────────

    private static VsmState handle(VsmState state, VsmMsg msg) {
        return switch (msg) {
            case VsmMsg.DefineStage(var stage) -> state.defineStage(stage);
            case VsmMsg.Enter(var itemId, var stage) -> state.enter(itemId, stage);
            case VsmMsg.Exit(var itemId, var stage) -> state.exit(itemId, stage);
            case VsmMsg.GetMap() -> state; // ask() returns state which caller maps
        };
    }

    // ── Agent ─────────────────────────────────────────────────────────────────

    private final Proc<VsmState, VsmMsg> proc;

    private ValueStreamMappingAgent() {
        this.proc = new Proc<>(VsmState.empty(), ValueStreamMappingAgent::handle);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Creates and starts a ValueStreamMappingAgent. */
    public static ValueStreamMappingAgent start() {
        return new ValueStreamMappingAgent();
    }

    /**
     * Declare a stage in the value stream.
     *
     * @param name stage name (e.g. "requirements", "development", "code-review")
     * @param type VA / BNVA / WASTE classification
     */
    public void defineStage(String name, StageType type) {
        proc.tell(new VsmMsg.DefineStage(new StageDef(name, type)));
    }

    /** Record a work item entering a stage. */
    public void enter(String itemId, String stageName) {
        proc.tell(new VsmMsg.Enter(itemId, stageName));
    }

    /** Record a work item exiting a stage. */
    public void exit(String itemId, String stageName) {
        proc.tell(new VsmMsg.Exit(itemId, stageName));
    }

    /** Returns the current-state value stream map, blocking up to {@code timeout}. */
    public ValueStreamMap currentStateMap(Duration timeout) {
        return proc.ask(new VsmMsg.GetMap(), timeout).join().buildMap();
    }

    /** Stops the agent process. */
    public void stop() throws InterruptedException {
        proc.stop();
    }
}
