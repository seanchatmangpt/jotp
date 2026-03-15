package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ApplicationInfo;
import io.github.seanchatmangpt.jotp.RunType;
import io.github.seanchatmangpt.jotp.StartType;
import java.time.Duration;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link ToyotaProductionSystemApp} running through the full OTP {@link
 * ApplicationController} lifecycle.
 *
 * <p>Each test exercises load → start → query → stop, verifying that the ten Blue Ocean Toyota AGI
 * agents wire up correctly under the OTP application model.
 */
@DisplayName("ToyotaProductionSystem — ApplicationController lifecycle IT")
class ToyotaProductionSystemIT implements WithAssertions {

    private static final String APP_NAME = "toyota-production-system";

    @BeforeEach
    void setUp() {
        // Required: reset global ApplicationController state for test isolation.
        ApplicationController.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Ensure the app is stopped even if a test fails mid-flight.
        try {
            ApplicationController.stop(APP_NAME);
        } catch (Exception ignored) {
            // Already stopped or never started — that is fine.
        }
        ApplicationController.reset();
    }

    // ── Lifecycle tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("load: spec registers in loaded applications without starting")
    void loadRegistersSpecWithoutStarting() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());

        assertThat(ApplicationController.loadedApplications())
                .extracting(ApplicationInfo::name)
                .contains(APP_NAME);

        // Not yet started — whichApplications() excludes it.
        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .doesNotContain(APP_NAME);
    }

    @Test
    @DisplayName("start: app appears in whichApplications after start")
    void startAddsAppToRunning() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());
        ApplicationController.start(APP_NAME);

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains(APP_NAME);
    }

    @Test
    @DisplayName("stop: app removed from whichApplications after stop")
    void stopRemovesAppFromRunning() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());
        ApplicationController.start(APP_NAME);
        ApplicationController.stop(APP_NAME);

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .doesNotContain(APP_NAME);
    }

    @Test
    @DisplayName("getEnv: spec env values are readable after load")
    void envValuesReadableAfterLoad() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());

        assertThat(ApplicationController.getEnv(APP_NAME, "jidoka.threshold", -1)).isEqualTo(3);
        assertThat(ApplicationController.getEnv(APP_NAME, "andon.window.seconds", -1))
                .isEqualTo(30);
        assertThat(ApplicationController.getEnv(APP_NAME, "takt.time.ms", -1)).isEqualTo(500);
    }

    @Test
    @DisplayName("setEnv: runtime overrides survive across queries")
    void runtimeEnvOverridePersists() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());
        ApplicationController.start(APP_NAME);

        ApplicationController.setEnv(APP_NAME, "jidoka.threshold", 10);

        assertThat(ApplicationController.getEnv(APP_NAME, "jidoka.threshold", -1)).isEqualTo(10);
    }

    @Test
    @DisplayName("getKey: description and vsn keys are queryable")
    void specKeysAreQueryable() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());

        assertThat(ApplicationController.getKey(APP_NAME, "description"))
                .isPresent()
                .hasValueSatisfying(v -> assertThat(v.toString()).contains("Toyota"));

        assertThat(ApplicationController.getKey(APP_NAME, "vsn"))
                .isPresent()
                .hasValueSatisfying(v -> assertThat(v.toString()).isEqualTo("1.0.0"));
    }

    @Test
    @DisplayName("restart: app remains running after restart")
    void restartKeepsAppRunning() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());
        ApplicationController.start(APP_NAME);
        ApplicationController.restart(APP_NAME);

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains(APP_NAME);
    }

    @Test
    @DisplayName("StartType.Normal: ObeiyaAgent starts and health report is healthy")
    void normalStartProducesHealthyReport() throws Exception {
        ApplicationController.load(ToyotaProductionSystemApp.spec());
        ApplicationController.start(APP_NAME, RunType.TEMPORARY, new StartType.Normal());

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains(APP_NAME);
    }

    @Test
    @DisplayName("launch: convenience factory load+starts the app in one call")
    void launchConvenienceFactory() throws Exception {
        String name = ToyotaProductionSystemApp.launch();

        assertThat(name).isEqualTo(APP_NAME);
        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains(APP_NAME);
    }

    @Test
    @DisplayName("PERMANENT run type: stop cascades to all running applications")
    void permanentStopCascadesToAll() throws Exception {
        // Start a sidecar app that depends on the toyota app.
        var sidecarSpec =
                io.github.seanchatmangpt.jotp.ApplicationSpec.builder("toyota-sidecar")
                        .description("Test sidecar")
                        .vsn("1.0.0")
                        .applications(APP_NAME)
                        .mod((startType, args) -> "running")
                        .build();

        ApplicationController.load(ToyotaProductionSystemApp.spec());
        ApplicationController.start(APP_NAME, RunType.PERMANENT);

        ApplicationController.load(sidecarSpec);
        ApplicationController.start("toyota-sidecar", RunType.TEMPORARY);

        // Both running
        List<ApplicationInfo> before = ApplicationController.whichApplications();
        assertThat(before).extracting(ApplicationInfo::name).contains(APP_NAME, "toyota-sidecar");

        // Stopping PERMANENT cascades to all
        ApplicationController.stop(APP_NAME);

        await().atMost(Duration.ofSeconds(2))
                .until(
                        () ->
                                ApplicationController.whichApplications().stream()
                                        .noneMatch(i -> i.name().equals(APP_NAME)));

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .doesNotContain(APP_NAME);
    }

    // ── ObeiyaAgent sub-agent wiring ──────────────────────────────────────────

    @Test
    @DisplayName("obeiya: all sub-agents reachable through running ObeiyaAgent")
    void allSubAgentsReachableThroughObeiya() throws Exception {
        var obeiya = ObeiyaAgent.start();
        try {
            assertThat(obeiya.kanban()).isNotNull();
            assertThat(obeiya.jidoka()).isNotNull();
            assertThat(obeiya.andon()).isNotNull();
            assertThat(obeiya.kaizen()).isNotNull();
            assertThat(obeiya.heijunka()).isNotNull();
            assertThat(obeiya.pokayoke()).isNotNull();
            assertThat(obeiya.genchi()).isNotNull();
            assertThat(obeiya.takt()).isNotNull();
            assertThat(obeiya.vsm()).isNotNull();
        } finally {
            obeiya.stop();
        }
    }

    @Test
    @DisplayName("obeiya: healthReport returns overallHealthy=true on a clean start")
    void healthReportHealthyOnCleanStart() throws Exception {
        var obeiya = ObeiyaAgent.start();
        try {
            // Allow sub-agents to initialise before requesting a report.
            await().atMost(Duration.ofSeconds(2)).until(() -> obeiya.kanban().thread().isAlive());

            var report = obeiya.healthReport(Duration.ofSeconds(2));

            assertThat(report).isNotNull();
            assertThat(report.producedAt()).isNotNull();
            assertThat(report.agentStatuses()).isNotEmpty();
            assertThat(report.openAndonAlerts()).isZero();
        } finally {
            obeiya.stop();
        }
    }

    @Test
    @DisplayName("obeiya: kanban work flows through add → pull → complete cycle")
    void kanbanWorkFlowsEndToEnd() throws Exception {
        var obeiya = ObeiyaAgent.start();
        try {
            var kanban = obeiya.kanban();

            kanban.tell(
                    new KanbanFlowAgent.KanbanMsg.AddWork(
                            "build", KanbanFlowAgent.workItem("ticket-42")));

            await().atMost(Duration.ofSeconds(2))
                    .until(
                            () ->
                                    KanbanFlowAgent.pull(kanban, "build", Duration.ofMillis(500))
                                            .isPresent());
        } finally {
            obeiya.stop();
        }
    }

    @Test
    @DisplayName("obeiya: pokayoke rejects blank strings via the wired validator chain")
    void pokayokeRejectsBlankStrings() throws Exception {
        var pokayoke =
                PokayokeAgent.<String>builder()
                        .device("non-empty", s -> s != null && !s.isBlank())
                        .build();

        var result = pokayoke.validate("", Duration.ofMillis(500));

        assertThat(result).isInstanceOf(PokayokeAgent.ValidationResult.Rejected.class);
        pokayoke.stop();
    }

    @Test
    @DisplayName("obeiya: andon pull publishes event to bus listeners")
    void andonPullPublishesToBus() throws Exception {
        var obeiya = ObeiyaAgent.start();
        try {
            var received = new java.util.concurrent.atomic.AtomicBoolean(false);
            obeiya.addDashboardListener(
                    event -> {
                        if (event
                                instanceof
                                ObeiyaAgent.ObeiyaEvent.SystemAlert(var src, var msg, var ts)) {
                            if (src.equals("andon")) received.set(true);
                        }
                    });

            obeiya.andon().pull("station-1", "Overload detected");

            await().atMost(Duration.ofSeconds(2)).until(received::get);
        } finally {
            obeiya.stop();
        }
    }
}
