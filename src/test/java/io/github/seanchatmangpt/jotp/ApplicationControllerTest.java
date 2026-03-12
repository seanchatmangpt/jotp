package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests for {@link ApplicationController} — OTP {@code application:} module equivalence.
 *
 * <p>Covers the full lifecycle: load → start → getEnv/setEnv → stop → unload, plus dependency
 * resolution and RunType cascade semantics.
 *
 * <p>Tests run sequentially within this class because {@link ApplicationController} uses static
 * shared state; concurrent test execution would cause race conditions between {@code @BeforeEach
 * reset()} and test body operations.
 *
 * @see ApplicationController
 * @see ApplicationSpec
 * @see ApplicationCallback
 * @see RunType
 * @see StartType
 */
@DisplayName("ApplicationController: OTP application: module equivalence")
@Execution(ExecutionMode.SAME_THREAD)
class ApplicationControllerTest {

    /** Reset global registry before each test to ensure isolation. */
    @BeforeEach
    void reset() {
        ApplicationController.reset();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ApplicationSpec minimalSpec(String name) {
        return ApplicationSpec.builder(name).description("Test app").vsn("1.0").build();
    }

    private static ApplicationSpec specWithCallback(
            String name, ApplicationCallback<String> callback) {
        return ApplicationSpec.builder(name)
                .description("Test app with callback")
                .vsn("1.0")
                .mod(callback)
                .build();
    }

    // ── Load / Unload ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("load() makes the spec available in loadedApplications()")
    void testLoad() {
        var spec = minimalSpec("my-app");

        ApplicationController.load(spec);

        List<ApplicationInfo> apps = ApplicationController.loadedApplications();
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).name()).isEqualTo("my-app");
        assertThat(apps.get(0).description()).isEqualTo("Test app");
        assertThat(apps.get(0).vsn()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("unload() removes the spec from loadedApplications()")
    void testUnload() {
        var spec = minimalSpec("my-app");
        ApplicationController.load(spec);

        ApplicationController.unload("my-app");

        assertThat(ApplicationController.loadedApplications()).isEmpty();
    }

    @Test
    @DisplayName("unload() on a running application throws IllegalStateException")
    void testUnloadRunningThrows() throws Exception {
        var spec = minimalSpec("my-app");
        ApplicationController.start(spec);

        assertThatThrownBy(() -> ApplicationController.unload("my-app"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("my-app");
    }

    @Test
    @DisplayName("load() is idempotent — reloading the same app name updates the spec")
    void testLoadIdempotent() {
        ApplicationController.load(minimalSpec("my-app"));
        ApplicationController.load(ApplicationSpec.builder("my-app").vsn("2.0").build());

        List<ApplicationInfo> apps = ApplicationController.loadedApplications();
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).vsn()).isEqualTo("2.0");
    }

    // ── Start / Stop ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("start(spec) invokes the callback and appears in whichApplications()")
    void testStart() throws Exception {
        var started = new AtomicBoolean(false);
        var spec =
                specWithCallback(
                        "ch-app",
                        (type, args) -> {
                            started.set(true);
                            return "running";
                        });

        ApplicationController.start(spec);

        assertThat(started.get()).isTrue();
        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .containsExactly("ch-app");
    }

    @Test
    @DisplayName("start() auto-loads the spec if not already loaded")
    void testStartAutoLoads() throws Exception {
        var spec = minimalSpec("my-app");

        ApplicationController.start(spec); // spec is not pre-loaded

        assertThat(ApplicationController.loadedApplications())
                .extracting(ApplicationInfo::name)
                .contains("my-app");
        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains("my-app");
    }

    @Test
    @DisplayName("start(name) on unloaded application throws IllegalStateException")
    void testStartUnloadedThrows() {
        assertThatThrownBy(() -> ApplicationController.start("nonexistent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    @DisplayName("start() is idempotent — starting an already-running app is a no-op")
    void testStartIdempotent() throws Exception {
        var count = new java.util.concurrent.atomic.AtomicInteger(0);
        var spec =
                specWithCallback(
                        "my-app",
                        (type, args) -> {
                            count.incrementAndGet();
                            return "state";
                        });

        ApplicationController.start(spec);
        ApplicationController.start(spec); // second call should be no-op

        assertThat(count.get()).isEqualTo(1);
        assertThat(ApplicationController.whichApplications()).hasSize(1);
    }

    @Test
    @DisplayName("stop() invokes the callback's stop method with the state from start()")
    void testStop() throws Exception {
        var stoppedWith = new AtomicReference<String>();
        var spec =
                ApplicationSpec.builder("ch-app")
                        .mod(
                                new ApplicationCallback<String>() {
                                    @Override
                                    public String start(StartType type, Object args) {
                                        return "my-state";
                                    }

                                    @Override
                                    public void stop(String state) {
                                        stoppedWith.set(state);
                                    }
                                })
                        .build();

        ApplicationController.start(spec);
        ApplicationController.stop("ch-app");

        assertThat(stoppedWith.get()).isEqualTo("my-state");
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName(
            "stop() keeps the spec in loadedApplications() but removes from whichApplications()")
    void testStopKeepsLoaded() throws Exception {
        var spec = minimalSpec("my-app");
        ApplicationController.start(spec);

        ApplicationController.stop("my-app");

        assertThat(ApplicationController.whichApplications()).isEmpty();
        assertThat(ApplicationController.loadedApplications())
                .extracting(ApplicationInfo::name)
                .contains("my-app");
    }

    @Test
    @DisplayName("stop() is idempotent — stopping an already-stopped app is a no-op")
    void testStopIdempotent() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));
        ApplicationController.stop("my-app");

        // Second call should not throw
        assertThatCode(() -> ApplicationController.stop("my-app")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("StartType.Normal is passed to the callback on a normal start")
    void testStartTypeNormal() throws Exception {
        var receivedType = new AtomicReference<StartType>();
        var spec =
                specWithCallback(
                        "my-app",
                        (type, args) -> {
                            receivedType.set(type);
                            return "ok";
                        });

        ApplicationController.start(spec);

        assertThat(receivedType.get()).isInstanceOf(StartType.Normal.class);
    }

    @Test
    @DisplayName("StartType.Takeover is passed to the callback when started with takeover")
    void testStartWithTakeoverStartType() throws Exception {
        var receivedType = new AtomicReference<StartType>();
        var spec =
                specWithCallback(
                        "my-app",
                        (type, args) -> {
                            receivedType.set(type);
                            return "ok";
                        });

        ApplicationController.start(spec, new StartType.Takeover("other-node"));

        assertThat(receivedType.get()).isInstanceOf(StartType.Takeover.class);
        assertThat(((StartType.Takeover) receivedType.get()).node()).isEqualTo("other-node");
    }

    @Test
    @DisplayName("StartType.Failover is passed to the callback when started with failover")
    void testStartWithFailoverStartType() throws Exception {
        var receivedType = new AtomicReference<StartType>();
        var spec =
                specWithCallback(
                        "my-app",
                        (type, args) -> {
                            receivedType.set(type);
                            return "ok";
                        });

        ApplicationController.start(spec, new StartType.Failover("backup-node"));

        assertThat(receivedType.get()).isInstanceOf(StartType.Failover.class);
        assertThat(((StartType.Failover) receivedType.get()).node()).isEqualTo("backup-node");
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("loadedApplications() includes running applications")
    void testLoadedApplicationsIncludesRunning() throws Exception {
        ApplicationController.load(minimalSpec("loaded-only"));
        ApplicationController.start(minimalSpec("running-app"));

        List<String> names =
                ApplicationController.loadedApplications().stream()
                        .map(ApplicationInfo::name)
                        .toList();
        assertThat(names).contains("loaded-only", "running-app");
    }

    @Test
    @DisplayName("whichApplications() excludes loaded-only applications")
    void testWhichApplicationsExcludesLoadedOnly() {
        ApplicationController.load(minimalSpec("loaded-only"));

        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("whichApplications() returns correct ApplicationInfo (name/description/vsn)")
    void testWhichApplicationsInfo() throws Exception {
        var spec =
                ApplicationSpec.builder("ch-app").description("Channel allocator").vsn("1").build();
        ApplicationController.start(spec);

        List<ApplicationInfo> apps = ApplicationController.whichApplications();
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).name()).isEqualTo("ch-app");
        assertThat(apps.get(0).description()).isEqualTo("Channel allocator");
        assertThat(apps.get(0).vsn()).isEqualTo("1");
    }

    // ── Environment ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getEnv() returns the value from the spec's env map")
    void testGetEnv() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
        ApplicationController.start(spec);

        Optional<Object> val = ApplicationController.getEnv("ch-app", "file");

        assertThat(val).isPresent().contains("/usr/local/log");
    }

    @Test
    @DisplayName("getEnv() returns Optional.empty() for a missing key")
    void testGetEnvAbsent() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));

        Optional<Object> val = ApplicationController.getEnv("my-app", "nonexistent");

        assertThat(val).isEmpty();
    }

    @Test
    @DisplayName("getEnv() works for loaded-but-not-started applications")
    void testGetEnvFromLoadedSpec() {
        var spec = ApplicationSpec.builder("my-app").env("key", "value").build();
        ApplicationController.load(spec);

        assertThat(ApplicationController.getEnv("my-app", "key")).contains("value");
    }

    @Test
    @DisplayName("setEnv() overrides the spec value at runtime")
    void testSetEnv() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
        ApplicationController.start(spec);

        ApplicationController.setEnv("ch-app", "file", "testlog");

        assertThat(ApplicationController.getEnv("ch-app", "file")).contains("testlog");
    }

    @Test
    @DisplayName("setEnv() override takes precedence over the spec env")
    void testSetEnvPrecedence() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").env("key", "original").build();
        ApplicationController.start(spec);

        ApplicationController.setEnv("ch-app", "key", "overridden");

        assertThat(ApplicationController.getEnv("ch-app", "key")).contains("overridden");
    }

    // ── Dependency resolution ──────────────────────────────────────────────────

    @Test
    @DisplayName("start() auto-starts loaded dependencies before the application")
    void testDependencyStartedFirst() throws Exception {
        var depSpec = minimalSpec("stdlib");
        var appSpec =
                ApplicationSpec.builder("ch-app")
                        .applications("stdlib") // declares stdlib as a dependency
                        .build();

        ApplicationController.load(depSpec);
        ApplicationController.load(appSpec);

        ApplicationController.start("ch-app");

        // Both the dependency and the application should now be running
        List<String> running =
                ApplicationController.whichApplications().stream()
                        .map(ApplicationInfo::name)
                        .toList();
        assertThat(running).contains("stdlib", "ch-app");
    }

    @Test
    @DisplayName("start() skips unloaded dependencies without throwing")
    void testUnloadedDependencySkipped() throws Exception {
        var appSpec =
                ApplicationSpec.builder("ch-app")
                        .applications("kernel") // kernel is NOT loaded
                        .build();
        ApplicationController.load(appSpec);

        // Should not throw — unloaded deps are skipped
        assertThatCode(() -> ApplicationController.start("ch-app")).doesNotThrowAnyException();
        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains("ch-app");
    }

    // ── RunType cascade ────────────────────────────────────────────────────────

    @Test
    @DisplayName("TEMPORARY: stopping one app does not cascade to others")
    void testTemporaryNoCascade() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("app-b"), RunType.TEMPORARY);

        ApplicationController.stop("app-b"); // TEMPORARY — should not cascade

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains("app-a");
    }

    @Test
    @DisplayName("PERMANENT: stopping one app cascades to all other running apps")
    void testPermanentCascade() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("app-b"));
        ApplicationController.start(minimalSpec("critical"), RunType.PERMANENT);

        ApplicationController.stop("critical"); // PERMANENT — should cascade

        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("TRANSIENT: abnormal termination cascades to all other running apps")
    void testTransientCascade() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

        ApplicationController.stop("gateway", true); // TRANSIENT abnormal — should cascade

        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("TRANSIENT: normal termination does not cascade to other apps")
    void testTransientNormalTerminationDoesNotCascade() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

        ApplicationController.stop("gateway"); // normal stop — should NOT cascade

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains("app-a");
    }

    @Test
    @DisplayName(
            "TRANSIENT: abnormal termination (stop(name, true)) cascades to all other running apps")
    void testTransientAbnormalTerminationCascades() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("app-b"));
        ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

        ApplicationController.stop("gateway", true); // abnormal — should cascade

        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    // ── Supervisor integration ─────────────────────────────────────────────────

    @Test
    @DisplayName("application callback can create and return a supervision tree")
    void testCallbackReturnsSupervisor() throws Exception {
        var supervisorRef = new AtomicReference<Supervisor>();

        var spec =
                ApplicationSpec.builder("ch-app")
                        .description("Channel allocator")
                        .vsn("1")
                        .mod(
                                (type, args) -> {
                                    var sup =
                                            new Supervisor(
                                                    Supervisor.Strategy.ONE_FOR_ONE,
                                                    5,
                                                    Duration.ofSeconds(60));
                                    supervisorRef.set(sup);
                                    return sup;
                                })
                        .build();

        ApplicationController.start(spec);

        assertThat(supervisorRef.get()).isNotNull();
        assertThat(supervisorRef.get().isRunning()).isTrue();

        ApplicationController.stop("ch-app");
    }

    // ── restart() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("restart() invokes stop then start; app remains running afterwards")
    void testRestartCyclesTheApp() throws Exception {
        var stopCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var startCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var spec =
                specWithCallback(
                        "my-app",
                        new ApplicationCallback<String>() {
                            @Override
                            public String start(StartType type, Object args) {
                                startCount.incrementAndGet();
                                return "state";
                            }

                            @Override
                            public void stop(String state) {
                                stopCount.incrementAndGet();
                            }
                        });

        ApplicationController.start(spec);
        ApplicationController.restart("my-app");

        assertThat(startCount.get()).isEqualTo(2);
        assertThat(stopCount.get()).isEqualTo(1);
        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .containsExactly("my-app");
    }

    @Test
    @DisplayName("restart() preserves the RunType from the original start")
    void testRestartPreservesRunType() throws Exception {
        var spec = minimalSpec("critical");
        ApplicationController.start(spec, RunType.PERMANENT);

        ApplicationController.restart("critical");

        // After restart, a normal stop of the PERMANENT app should still cascade
        ApplicationController.start(minimalSpec("app-b"));
        ApplicationController.stop("critical"); // should cascade because RunType is still PERMANENT

        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("restart() does not trigger cascade semantics during the stop phase")
    void testRestartDoesNotCascade() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("critical"), RunType.PERMANENT);

        // restart should NOT cascade-stop app-a even though critical is PERMANENT
        ApplicationController.restart("critical");

        assertThat(ApplicationController.whichApplications())
                .extracting(ApplicationInfo::name)
                .contains("app-a", "critical");
    }

    // ── getEnv with default ────────────────────────────────────────────────────

    @Test
    @DisplayName("getEnv(app, key, default) returns value when key exists")
    void testGetEnvWithDefaultKeyPresent() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
        ApplicationController.start(spec);

        Object val = ApplicationController.getEnv("ch-app", "file", "/fallback");

        assertThat(val).isEqualTo("/usr/local/log");
    }

    @Test
    @DisplayName("getEnv(app, key, default) returns defaultValue when key is absent")
    void testGetEnvWithDefaultKeyAbsent() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));

        Object val = ApplicationController.getEnv("my-app", "nonexistent", "fallback");

        assertThat(val).isEqualTo("fallback");
    }

    @Test
    @DisplayName("getEnv(app, key, default) runtime override takes precedence over default")
    void testGetEnvWithDefaultOverrideTakesPrecedence() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));
        ApplicationController.setEnv("my-app", "key", "overridden");

        Object val = ApplicationController.getEnv("my-app", "key", "fallback");

        assertThat(val).isEqualTo("overridden");
    }

    // ── unsetEnv() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unsetEnv() removes a runtime override so spec value is visible again")
    void testUnsetEnvRestoresSpecValue() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").env("file", "/spec-value").build();
        ApplicationController.start(spec);
        ApplicationController.setEnv("ch-app", "file", "runtime-value");

        ApplicationController.unsetEnv("ch-app", "file");

        assertThat(ApplicationController.getEnv("ch-app", "file")).contains("/spec-value");
    }

    @Test
    @DisplayName("unsetEnv() removes a key that has no spec fallback → Optional.empty()")
    void testUnsetEnvNoSpecFallback() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));
        ApplicationController.setEnv("my-app", "temp-key", "some-value");

        ApplicationController.unsetEnv("my-app", "temp-key");

        assertThat(ApplicationController.getEnv("my-app", "temp-key")).isEmpty();
    }

    @Test
    @DisplayName("unsetEnv() on a key that was never set is a no-op")
    void testUnsetEnvNeverSetIsNoOp() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));

        assertThatCode(() -> ApplicationController.unsetEnv("my-app", "nonexistent"))
                .doesNotThrowAnyException();
    }

    // ── getKey() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getKey() returns description from spec")
    void testGetKeyDescription() throws Exception {
        var spec =
                ApplicationSpec.builder("ch-app").description("Channel allocator").vsn("2").build();
        ApplicationController.start(spec);

        assertThat(ApplicationController.getKey("ch-app", "description"))
                .contains("Channel allocator");
    }

    @Test
    @DisplayName("getKey() returns vsn from spec")
    void testGetKeyVsn() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").vsn("3.1.4").build();
        ApplicationController.start(spec);

        assertThat(ApplicationController.getKey("ch-app", "vsn")).contains("3.1.4");
    }

    @Test
    @DisplayName("getKey() returns modules list from spec")
    void testGetKeyModules() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").modules("ch_app", "ch_sup", "ch3").build();
        ApplicationController.start(spec);

        Optional<Object> result = ApplicationController.getKey("ch-app", "modules");
        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(List.class);
        assertThat((List<String>) result.get()).containsExactly("ch_app", "ch_sup", "ch3");
    }

    @Test
    @DisplayName("getKey() returns applications (dependency) list from spec")
    void testGetKeyApplications() throws Exception {
        var spec = ApplicationSpec.builder("ch-app").applications("kernel", "stdlib").build();
        ApplicationController.load(spec);

        Optional<Object> result = ApplicationController.getKey("ch-app", "applications");
        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(List.class);
        assertThat((List<String>) result.get()).containsExactly("kernel", "stdlib");
    }

    @Test
    @DisplayName("getKey() returns Optional.empty() for an unknown key")
    void testGetKeyUnknown() throws Exception {
        ApplicationController.start(minimalSpec("my-app"));

        assertThat(ApplicationController.getKey("my-app", "no_such_key")).isEmpty();
    }

    @Test
    @DisplayName("getKey() returns Optional.empty() when the application is not loaded")
    void testGetKeyAppNotLoaded() {
        assertThat(ApplicationController.getKey("nonexistent", "description")).isEmpty();
    }

    @Test
    @DisplayName("getKey() works for a loaded-but-not-started application")
    void testGetKeyLoadedOnly() {
        var spec = ApplicationSpec.builder("my-app").description("Loaded only").build();
        ApplicationController.load(spec);

        assertThat(ApplicationController.getKey("my-app", "description")).contains("Loaded only");
    }

    // ── reset() ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reset() clears all loaded, running, and env state")
    void testReset() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.load(minimalSpec("app-b"));
        ApplicationController.setEnv("app-a", "key", "val");

        ApplicationController.reset();

        assertThat(ApplicationController.loadedApplications()).isEmpty();
        assertThat(ApplicationController.whichApplications()).isEmpty();
        assertThat(ApplicationController.getEnv("app-a", "key")).isEmpty();
    }
}
