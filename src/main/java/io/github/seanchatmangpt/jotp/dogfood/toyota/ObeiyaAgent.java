package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ObeiyaAgent — war-room coordination of all nine Toyota production agents.
 *
 * <p><strong>Toyota principle:</strong> Obeiya ("big room") brings all key stakeholders and live
 * project data into one physical or virtual space. Every agent's status is visible; decisions are
 * made at the gemba, not in separate siloed meetings. The room itself embodies visual management.
 *
 * <p><strong>Armstrong:</strong> "A supervision tree without a window is a black box. The obeiya
 * gives every observer a view into the running system without coupling them to its internals."
 *
 * <p>This agent:
 *
 * <ol>
 *   <li>Starts and owns all nine Toyota sub-agents ({@link KanbanFlowAgent}, {@link JidokaAgent},
 *       {@link AndonCordAgent}, {@link KaizenAgent}, {@link HeijunkaAgent}, {@link PokayokeAgent},
 *       {@link GenchiGenbutsuAgent}, {@link TaktTimeAgent}, {@link ValueStreamMappingAgent})
 *   <li>Wires an {@link EventManager} bus to which each agent publishes its key events
 *   <li>Produces a {@link SystemHealthReport} on demand — a single source of truth for the
 *       end-to-end production system
 * </ol>
 *
 * <pre>{@code
 * var obeiya = ObeiyaAgent.start();
 *
 * // Each agent accessible by name
 * obeiya.kanban().tell(new KanbanFlowAgent.KanbanMsg.AddWork("build", item));
 * obeiya.jidoka().send(new JidokaAgent.JidokaEvent.Inspect("X", List.of()));
 * obeiya.andon().pull("station-7", "Disk I/O spike");
 *
 * var report = obeiya.healthReport(Duration.ofSeconds(2));
 * assertThat(report.overallHealthy()).isTrue();
 * }</pre>
 */
public final class ObeiyaAgent {

    // ── System health types ───────────────────────────────────────────────────

    /** Status of a single sub-agent within the obeiya. */
    public record AgentStatus(String name, boolean running, String summary, Instant checkedAt) {}

    /**
     * Unified system health report produced by the obeiya.
     *
     * @param agentStatuses status of each managed sub-agent
     * @param openAndonAlerts Andon alerts currently open
     * @param kaizenCycles number of completed Kaizen improvement cycles
     * @param kanbanUtilisation WIP utilisation snapshot per lane
     * @param vsmBottleneck current bottleneck stage per the value stream map
     * @param producedAt when this report was generated
     */
    public record SystemHealthReport(
            List<AgentStatus> agentStatuses,
            int openAndonAlerts,
            int kaizenCycles,
            Map<String, String> kanbanUtilisation,
            String vsmBottleneck,
            boolean overallHealthy,
            Instant producedAt) {}

    // ── Messages ──────────────────────────────────────────────────────────────

    /** Messages accepted by the ObeiyaAgent's coordinating process. */
    public sealed interface ObeiyaMsg permits ObeiyaMsg.HealthReport, ObeiyaMsg.BroadcastStatus {

        /** Request a full system health report (ask() pattern). */
        record HealthReport() implements ObeiyaMsg {}

        /** Broadcast the current status to all registered dashboards. */
        record BroadcastStatus(AgentStatus status) implements ObeiyaMsg {}
    }

    /** Events published on the obeiya event bus. */
    public sealed interface ObeiyaEvent
            permits ObeiyaEvent.AgentStatusChanged, ObeiyaEvent.SystemAlert {

        record AgentStatusChanged(AgentStatus status) implements ObeiyaEvent {}

        record SystemAlert(String source, String message, Instant timestamp)
                implements ObeiyaEvent {}
    }

    // ── Internal state ────────────────────────────────────────────────────────

    private record ObeiyaState(Map<String, AgentStatus> statuses, List<ObeiyaEvent> eventLog) {

        static ObeiyaState empty() {
            return new ObeiyaState(new ConcurrentHashMap<>(), new ArrayList<>());
        }
    }

    private static ObeiyaState handleMsg(ObeiyaState state, ObeiyaMsg msg) {
        return switch (msg) {
            case ObeiyaMsg.HealthReport() -> state; // ask() delivers state
            case ObeiyaMsg.BroadcastStatus(var status) -> {
                state.statuses().put(status.name(), status);
                var log = new ArrayList<>(state.eventLog());
                log.add(new ObeiyaEvent.AgentStatusChanged(status));
                yield new ObeiyaState(state.statuses(), log);
            }
        };
    }

    // ── Sub-agents ────────────────────────────────────────────────────────────

    private final Proc<ObeiyaState, ObeiyaMsg> coordinator;
    private final EventManager<ObeiyaEvent> bus;
    private final Supervisor supervisor;

    private final Proc<KanbanFlowAgent.KanbanState, KanbanFlowAgent.KanbanMsg> kanban;
    private final io.github.seanchatmangpt.jotp.StateMachine<
                    JidokaAgent.JidokaState, JidokaAgent.JidokaEvent, JidokaAgent.JidokaData>
            jidoka;
    private final AndonCordAgent andon;
    private final io.github.seanchatmangpt.jotp.StateMachine<
                    KaizenAgent.KaizenState, KaizenAgent.KaizenEvent, KaizenAgent.KaizenData>
            kaizen;
    private final HeijunkaAgent heijunka;
    private final PokayokeAgent<String> pokayoke;
    private final GenchiGenbutsuAgent genchi;
    private final TaktTimeAgent<String> takt;
    private final ValueStreamMappingAgent vsm;

    @SuppressWarnings("unchecked")
    private ObeiyaAgent(
            Map<String, Integer> kanbanLanes,
            int jidokaThreshold,
            Duration andonWindow,
            String kaizenMetric,
            double kaizenTarget,
            List<String> heijunkaTypes,
            Duration taktTime)
            throws InterruptedException {

        this.bus = EventManager.start("obeiya-bus");
        this.supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(30));

        // Initialise sub-agents
        this.kanban = KanbanFlowAgent.start(kanbanLanes);
        this.jidoka = JidokaAgent.start(jidokaThreshold);
        this.andon = AndonCordAgent.start(andonWindow);
        this.kaizen = KaizenAgent.start(kaizenMetric, kaizenTarget);
        this.heijunka = HeijunkaAgent.start(heijunkaTypes, Duration.ofMillis(200));
        this.pokayoke =
                PokayokeAgent.<String>builder()
                        .device("non-empty", s -> s != null && !s.isBlank())
                        .device("max-length-255", s -> s != null && s.length() <= 255)
                        .build();
        this.genchi = GenchiGenbutsuAgent.start(Duration.ofMillis(500));
        this.takt =
                TaktTimeAgent.start(
                        taktTime,
                        item ->
                                bus.notify(
                                        new ObeiyaEvent.SystemAlert(
                                                "takt", "Dispatched: " + item, Instant.now())));
        this.vsm = ValueStreamMappingAgent.start();

        // Register VSM stages mirroring the kanban lanes
        kanbanLanes
                .keySet()
                .forEach(
                        lane ->
                                vsm.defineStage(
                                        lane, ValueStreamMappingAgent.StageType.VALUE_ADDED));

        // Coordinator process
        this.coordinator = new Proc<>(ObeiyaState.empty(), ObeiyaAgent::handleMsg);

        // Wire andon alerts to obeiya bus
        andon.addListener(
                event -> {
                    if (event instanceof AndonCordAgent.AndonEvent.Pull(var s, var p, var ts)) {
                        bus.notify(
                                new ObeiyaEvent.SystemAlert("andon", "PULL: " + s + " — " + p, ts));
                        coordinator.tell(
                                new ObeiyaMsg.BroadcastStatus(
                                        new AgentStatus(
                                                "andon", true, "ALERT: " + p, Instant.now())));
                    }
                });

        // Watch sub-agent processes in GenchiGenbutsu
        genchi.watch("kanban", kanban, new GenchiGenbutsuAgent.HealthThreshold(0, 1000, 0));
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates and starts the ObeiyaAgent with sensible defaults.
     *
     * @return running obeiya coordinator
     */
    public static ObeiyaAgent start() throws InterruptedException {
        return new ObeiyaAgent(
                Map.of("design", 2, "build", 3, "test", 2, "deploy", 1),
                3, // jidoka halt threshold
                Duration.ofSeconds(30), // andon response window
                "quality-rate",
                0.95,
                List.of("feature", "bugfix", "chore"),
                Duration.ofMillis(500));
    }

    /** Full-control factory for custom configurations. */
    public static ObeiyaAgent start(
            Map<String, Integer> kanbanLanes,
            int jidokaThreshold,
            Duration andonWindow,
            String kaizenMetric,
            double kaizenTarget,
            List<String> heijunkaTypes,
            Duration taktTime)
            throws InterruptedException {
        return new ObeiyaAgent(
                kanbanLanes,
                jidokaThreshold,
                andonWindow,
                kaizenMetric,
                kaizenTarget,
                heijunkaTypes,
                taktTime);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the KanbanFlowAgent process. */
    public Proc<KanbanFlowAgent.KanbanState, KanbanFlowAgent.KanbanMsg> kanban() {
        return kanban;
    }

    /** Returns the JidokaAgent state machine. */
    public io.github.seanchatmangpt.jotp.StateMachine<
                    JidokaAgent.JidokaState, JidokaAgent.JidokaEvent, JidokaAgent.JidokaData>
            jidoka() {
        return jidoka;
    }

    /** Returns the AndonCordAgent. */
    public AndonCordAgent andon() {
        return andon;
    }

    /** Returns the KaizenAgent state machine. */
    public io.github.seanchatmangpt.jotp.StateMachine<
                    KaizenAgent.KaizenState, KaizenAgent.KaizenEvent, KaizenAgent.KaizenData>
            kaizen() {
        return kaizen;
    }

    /** Returns the HeijunkaAgent. */
    public HeijunkaAgent heijunka() {
        return heijunka;
    }

    /** Returns the PokayokeAgent. */
    public PokayokeAgent<String> pokayoke() {
        return pokayoke;
    }

    /** Returns the GenchiGenbutsuAgent. */
    public GenchiGenbutsuAgent genchi() {
        return genchi;
    }

    /** Returns the TaktTimeAgent. */
    public TaktTimeAgent<String> takt() {
        return takt;
    }

    /** Returns the ValueStreamMappingAgent. */
    public ValueStreamMappingAgent vsm() {
        return vsm;
    }

    /** Register a listener for all system-level events. */
    public void addDashboardListener(EventManager.Handler<ObeiyaEvent> listener) {
        bus.addHandler(listener);
    }

    /**
     * Produces a unified system health report.
     *
     * @param timeout maximum wait time
     */
    public SystemHealthReport healthReport(Duration timeout) {
        ObeiyaState state = coordinator.ask(new ObeiyaMsg.HealthReport(), timeout).join();

        // Kanban utilisation
        Map<String, String> kanbanUtil =
                kanban.ask(new KanbanFlowAgent.KanbanMsg.Utilisation(), timeout)
                        .join()
                        .utilisation();

        // Kaizen cycles
        int kaizenCycles = kaizen.data().cycleCount();

        // Open andon alerts
        int openAlerts = andon.openAlerts().size();

        // VSM bottleneck
        String bottleneck;
        try {
            var map = vsm.currentStateMap(timeout);
            bottleneck = map.stages().isEmpty() ? "none" : map.bottleneck();
        } catch (Exception ex) {
            bottleneck = "unavailable";
        }

        var statuses = new ArrayList<AgentStatus>();
        statuses.add(
                new AgentStatus(
                        "kanban", kanban.thread().isAlive(), "WIP: " + kanbanUtil, Instant.now()));
        statuses.add(
                new AgentStatus(
                        "jidoka", jidoka.isRunning(), "State: " + jidoka.state(), Instant.now()));
        statuses.add(new AgentStatus("andon", true, "Open alerts: " + openAlerts, Instant.now()));
        statuses.add(
                new AgentStatus(
                        "kaizen", kaizen.isRunning(), "Cycles: " + kaizenCycles, Instant.now()));
        statuses.add(
                new AgentStatus(
                        "heijunka", heijunka.proc().thread().isAlive(), "Running", Instant.now()));
        statuses.add(new AgentStatus("vsm", true, "Bottleneck: " + bottleneck, Instant.now()));
        statuses.addAll(state.statuses().values());

        boolean healthy =
                statuses.stream().allMatch(AgentStatus::running)
                        && openAlerts == 0
                        && !(jidoka.state() instanceof JidokaAgent.JidokaState.Halted);

        return new SystemHealthReport(
                List.copyOf(statuses),
                openAlerts,
                kaizenCycles,
                kanbanUtil,
                bottleneck,
                healthy,
                Instant.now());
    }

    /** Stops all sub-agents and the coordinator. */
    public void stop() throws InterruptedException {
        genchi.stop();
        takt.stop();
        heijunka.stop();
        andon.stop();
        vsm.stop();
        pokayoke.stop();
        jidoka.stop();
        kaizen.stop();
        kanban.stop();
        bus.stop();
        coordinator.stop();
    }
}
