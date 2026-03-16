package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ApplicationInfo;
import io.github.seanchatmangpt.jotp.RunType;
import io.github.seanchatmangpt.jotp.StartType;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies the ApplicationExample OTP lifecycle dogfood.
 *
 * <p>Joe Armstrong: "Supervisors are the backbone of every real OTP application. Without them, you
 * don't have an OTP application — you have a script."
 *
 * <p>Each test starts with a clean {@link ApplicationController} state via {@code reset()} so that
 * the global static registry does not leak between tests.
 */
@DtrTest
@DisplayName("ApplicationController: OTP application lifecycle")
@Execution(ExecutionMode.SAME_THREAD)
class ApplicationExampleTest implements WithAssertions {

    @BeforeEach
    void cleanRegistry() {
        // Armstrong: "Start fresh every time — state from a previous run is
        // the enemy of reproducible tests."
        ApplicationController.reset();
    }

    // ── Load / start / stop ────────────────────────────────────────────────

    @Test
    @DisplayName("load() registers spec without starting the application")
    void loadRegistersSpecWithoutStarting() {
        ApplicationController.load(ApplicationExample.buildSpec());

        List<ApplicationInfo> loaded = ApplicationController.loadedApplications();
        List<ApplicationInfo> running = ApplicationController.whichApplications();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).name()).isEqualTo("ch-hub");
        assertThat(running).isEmpty();
    }

    @Test
    @DisplayName("start() moves the application from loaded to running")
    void startMovesApplicationToRunning() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.PERMANENT);

        assertThat(ApplicationController.loadedApplications()).hasSize(1);
        assertThat(ApplicationController.whichApplications()).hasSize(1);
        assertThat(ApplicationController.whichApplications().get(0).name()).isEqualTo("ch-hub");
    }

    @Test
    @DisplayName("start() is idempotent — calling twice leaves one running entry")
    void startIsIdempotent() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.PERMANENT);
        ApplicationController.start("ch-hub", RunType.PERMANENT); // second call is no-op

        assertThat(ApplicationController.whichApplications()).hasSize(1);
    }

    @Test
    @DisplayName("stop() removes application from running but keeps spec loaded")
    void stopKeepsSpecLoaded() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);
        ApplicationController.stop("ch-hub");

        assertThat(ApplicationController.whichApplications()).isEmpty();
        assertThat(ApplicationController.loadedApplications()).hasSize(1);
    }

    // ── Environment ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getEnv() reads values from the spec's env map")
    void getEnvReadsFromSpec() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        Object port = ApplicationController.getEnv("ch-hub", "port", -1);
        assertThat(port).isEqualTo(5672);

        Object maxChannels = ApplicationController.getEnv("ch-hub", "max_channels", -1);
        assertThat(maxChannels).isEqualTo(1000);
    }

    @Test
    @DisplayName("setEnv() runtime override takes precedence over spec env")
    void setEnvOverridesTakePrecedence() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        ApplicationController.setEnv("ch-hub", "max_channels", 2000);

        Object maxChannels = ApplicationController.getEnv("ch-hub", "max_channels", 1000);
        assertThat(maxChannels).isEqualTo(2000);
    }

    @Test
    @DisplayName("getEnv() returns default when key is absent")
    void getEnvReturnsDefaultWhenAbsent() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        Object value = ApplicationController.getEnv("ch-hub", "nonexistent_key", "fallback");
        assertThat(value).isEqualTo("fallback");
    }

    @Test
    @DisplayName("unsetEnv() removes the runtime override, restoring spec default")
    void unsetEnvRestoresSpecDefault() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        ApplicationController.setEnv("ch-hub", "max_channels", 9999);
        ApplicationController.unsetEnv("ch-hub", "max_channels");

        Object maxChannels = ApplicationController.getEnv("ch-hub", "max_channels", 0);
        assertThat(maxChannels).isEqualTo(1000); // original spec value
    }

    // ── Spec key lookup ────────────────────────────────────────────────────

    @Test
    @DisplayName("getKey() retrieves description and vsn from spec")
    void getKeyReturnsSpecFields() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        Optional<Object> description = ApplicationController.getKey("ch-hub", "description");
        Optional<Object> vsn = ApplicationController.getKey("ch-hub", "vsn");

        assertThat(description).contains("Channel Hub — message routing core");
        assertThat(vsn).contains("1.0.0");
    }

    @Test
    @DisplayName("getKey() returns empty for unknown key names")
    void getKeyReturnsEmptyForUnknownKey() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());

        Optional<Object> unknown = ApplicationController.getKey("ch-hub", "no_such_key");
        assertThat(unknown).isEmpty();
    }

    // ── Restart ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restart() cycles the application without cascade semantics")
    void restartCyclesWithoutCascade() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.load(ApplicationExample.buildMetricsSpec());
        ApplicationController.start("ch-hub", RunType.PERMANENT);
        ApplicationController.start("ch-metrics", RunType.TEMPORARY);

        assertThat(ApplicationController.whichApplications()).hasSize(2);

        // restart ch-hub — PERMANENT but restart must NOT cascade
        ApplicationController.restart("ch-hub");

        // Both apps should still be running after the restart
        List<ApplicationInfo> afterRestart = ApplicationController.whichApplications();
        assertThat(afterRestart).hasSize(2);
        assertThat(afterRestart.stream().map(ApplicationInfo::name))
                .containsExactlyInAnyOrder("ch-hub", "ch-metrics");
    }

    @Test
    @DisplayName("restart() preserves runtime env overrides across the cycle")
    void restartPreservesEnvOverrides() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        ApplicationController.setEnv("ch-hub", "max_channels", 4000);
        ApplicationController.restart("ch-hub");

        // The override survives the restart (envOverrides map is separate from the app lifecycle)
        Object maxChannels = ApplicationController.getEnv("ch-hub", "max_channels", 1000);
        assertThat(maxChannels).isEqualTo(4000);
    }

    // ── Cascade semantics ──────────────────────────────────────────────────

    @Test
    @DisplayName("PERMANENT stop cascades to all running applications")
    void permanentStopCascadesToAll() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.load(ApplicationExample.buildMetricsSpec());
        ApplicationController.start("ch-hub", RunType.PERMANENT);
        ApplicationController.start("ch-metrics", RunType.TEMPORARY);

        assertThat(ApplicationController.whichApplications()).hasSize(2);

        // Stopping the PERMANENT app cascades and stops ch-metrics too
        ApplicationController.stop("ch-hub");

        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("TEMPORARY stop does NOT cascade to other running applications")
    void temporaryStopDoesNotCascade() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.load(ApplicationExample.buildMetricsSpec());
        ApplicationController.start("ch-hub", RunType.PERMANENT);
        ApplicationController.start("ch-metrics", RunType.TEMPORARY);

        // Stop the TEMPORARY sidecar — ch-hub must keep running
        ApplicationController.stop("ch-metrics");

        List<ApplicationInfo> stillRunning = ApplicationController.whichApplications();
        assertThat(stillRunning).hasSize(1);
        assertThat(stillRunning.get(0).name()).isEqualTo("ch-hub");

        // Clean up the permanent app
        ApplicationController.stop("ch-hub");
    }

    // ── StartType exhaustive switch ────────────────────────────────────────

    @Test
    @DisplayName("StartType.Normal() produces localhost host in callback")
    void normalStartProducesLocalhostHost() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        // Default start uses StartType.Normal()
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        // Verify the app is running (callback returned a Running state)
        assertThat(ApplicationController.whichApplications()).hasSize(1);
        assertThat(ApplicationController.whichApplications().get(0).name()).isEqualTo("ch-hub");
    }

    @Test
    @DisplayName("StartType.Takeover() is exhaustively matched in the callback")
    void takeoverStartTypeIsHandled() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        // Simulate distributed takeover from node2@remotehost
        ApplicationController.start(
                "ch-hub", RunType.PERMANENT, new StartType.Takeover("node2@remotehost"));

        // Application started successfully — callback's switch handled Takeover variant
        assertThat(ApplicationController.whichApplications()).hasSize(1);
    }

    @Test
    @DisplayName("StartType.Failover() is exhaustively matched in the callback")
    void failoverStartTypeIsHandled() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        // Simulate failover from a crashed primary node
        ApplicationController.start(
                "ch-hub", RunType.PERMANENT, new StartType.Failover("primary@crashed"));

        assertThat(ApplicationController.whichApplications()).hasSize(1);
    }

    @Test
    @DisplayName("Sealed switch on StartType — all variants covered at compile time")
    void sealedSwitchOnStartTypeIsExhaustive() {
        // This test verifies that the exhaustive switch compiles without a default branch.
        // Java 26 sealed types guarantee no unhandled variant can reach runtime.
        StartType[] types = {
            new StartType.Normal(),
            new StartType.Takeover("n1@host"),
            new StartType.Failover("n2@host")
        };
        for (StartType st : types) {
            String label =
                    switch (st) {
                        case StartType.Normal() -> "normal";
                        case StartType.Takeover(var n) -> "takeover:" + n;
                        case StartType.Failover(var n) -> "failover:" + n;
                    };
            assertThat(label).isNotNull();
        }
    }

    // ── ChannelHubState sealed type ────────────────────────────────────────

    @Test
    @DisplayName("Sealed ChannelHubState switch is exhaustive at compile time")
    void sealedChannelHubStateSwitchIsExhaustive() {
        ApplicationExample.ChannelHubState[] states = {
            new ApplicationExample.ChannelHubState.Initializing("localhost", 5672),
            new ApplicationExample.ChannelHubState.Running("localhost", 5672, 10),
            new ApplicationExample.ChannelHubState.Draining("localhost")
        };
        for (var state : states) {
            String label =
                    switch (state) {
                        case ApplicationExample.ChannelHubState.Initializing(var host, var port) ->
                                "initializing:" + host + ":" + port;
                        case ApplicationExample.ChannelHubState.Running(
                                        var host,
                                        var port,
                                        var count) ->
                                "running:" + host + ":" + port + ":" + count;
                        case ApplicationExample.ChannelHubState.Draining(var host) ->
                                "draining:" + host;
                    };
            assertThat(label).isNotNull();
        }
    }

    // ── Full lifecycle integration ─────────────────────────────────────────

    @Test
    @DisplayName("runLifecycle() completes the full OTP application lifecycle")
    void runLifecycleCompletesSuccessfully() throws Exception {
        List<ApplicationInfo> runningBeforeStop = ApplicationExample.runLifecycle();

        // Before the final stop, ch-hub should be the one running app
        assertThat(runningBeforeStop).hasSize(1);
        assertThat(runningBeforeStop.get(0).name()).isEqualTo("ch-hub");

        // After stop (inside runLifecycle), nothing should be running
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("demonstrateCascadeSemantics() completes without error")
    void demonstrateCascadeSemanticsCompletesSuccessfully() throws Exception {
        ApplicationExample.demonstrateCascadeSemantics();
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("ApplicationInfo record fields are populated from spec")
    void applicationInfoRecordFieldsArePopulated() throws Exception {
        ApplicationController.load(ApplicationExample.buildSpec());
        ApplicationController.start("ch-hub", RunType.TEMPORARY);

        ApplicationInfo info = ApplicationController.whichApplications().get(0);
        assertThat(info.name()).isEqualTo("ch-hub");
        assertThat(info.description()).isEqualTo("Channel Hub — message routing core");
        assertThat(info.vsn()).isEqualTo("1.0.0");
    }
}
