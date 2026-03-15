package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.dogfood.toyota.JidokaAgent.*;
import io.github.seanchatmangpt.jotp.dogfood.toyota.KanbanFlowAgent.*;
import io.github.seanchatmangpt.jotp.dogfood.toyota.ObeiyaAgent.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

@Timeout(15)
class ObeiyaAgentTest {

    private ObeiyaAgent obeiya;

    @BeforeEach
    void setUp() throws InterruptedException {
        ApplicationController.reset();
        obeiya =
                ObeiyaAgent.start(
                        Map.of("build", 2, "test", 1),
                        2, // jidoka: halt after 2 defects
                        Duration.ofSeconds(30),
                        "quality-rate",
                        0.90,
                        List.of("feature", "bugfix"),
                        Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        obeiya.stop();
    }

    @Test
    @DisplayName("ObeiyaAgent starts and all sub-agents are accessible")
    void allSubAgentsReachable() {
        assertThat(obeiya.kanban()).isNotNull();
        assertThat(obeiya.jidoka()).isNotNull();
        assertThat(obeiya.andon()).isNotNull();
        assertThat(obeiya.kaizen()).isNotNull();
        assertThat(obeiya.heijunka()).isNotNull();
        assertThat(obeiya.pokayoke()).isNotNull();
        assertThat(obeiya.genchi()).isNotNull();
        assertThat(obeiya.takt()).isNotNull();
        assertThat(obeiya.vsm()).isNotNull();
    }

    @Test
    @DisplayName("Health report shows all sub-agents running initially")
    void initialHealthReportAllRunning() {
        var report = obeiya.healthReport(Duration.ofSeconds(2));
        assertThat(report).isNotNull();
        assertThat(report.agentStatuses()).isNotEmpty();
        assertThat(report.openAndonAlerts()).isZero();
        assertThat(report.kaizenCycles()).isZero();
    }

    @Test
    @DisplayName("Kanban work flows through the obeiya kanban lanes")
    void kanbanWorkFlows() throws InterruptedException {
        var item = new WorkItem("TASK-1", "Implement SSO");
        obeiya.kanban().tell(new KanbanMsg.AddWork("build", item));
        Thread.sleep(100);

        var state = obeiya.kanban().ask(new KanbanMsg.Utilisation(), Duration.ofSeconds(1)).join();
        assertThat(state.backlog().get("build")).hasSize(1);
    }

    @Test
    @DisplayName("Jidoka halt propagates through obeiya health report")
    void jidokaHaltReflectedInReport() {
        obeiya.jidoka().send(new JidokaEvent.Inspect("A", List.of("defect-1")));
        obeiya.jidoka().send(new JidokaEvent.Inspect("B", List.of("defect-2")));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> obeiya.jidoka().state() instanceof JidokaState.Halted);

        var report = obeiya.healthReport(Duration.ofSeconds(2));
        // Jidoka is halted → overall healthy is false
        assertThat(report.overallHealthy()).isFalse();
    }

    @Test
    @DisplayName("Andon pull event reaches obeiya event bus")
    void andonPullReachesBus() {
        var events = new CopyOnWriteArrayList<ObeiyaEvent>();
        obeiya.addDashboardListener(events::add);

        obeiya.andon().pull("station-X", "Vibration anomaly");

        await().atMost(Duration.ofSeconds(3))
                .until(
                        () ->
                                events.stream()
                                        .anyMatch(
                                                e ->
                                                        e instanceof ObeiyaEvent.SystemAlert a
                                                                && a.source().equals("andon")));
    }

    @Test
    @DisplayName("Pokayoke validates strings through obeiya")
    void pokayokeValidatesStrings() {
        var pass = obeiya.pokayoke().validate("valid-item", Duration.ofSeconds(1));
        assertThat(pass).isInstanceOf(PokayokeAgent.ValidationResult.Passed.class);

        var fail = obeiya.pokayoke().validate("", Duration.ofSeconds(1));
        assertThat(fail).isInstanceOf(PokayokeAgent.ValidationResult.Rejected.class);
    }
}
