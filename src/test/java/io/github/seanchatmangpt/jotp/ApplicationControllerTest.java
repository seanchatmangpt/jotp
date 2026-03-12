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
    @DisplayName("TRANSIENT: stopping one app cascades to all other running apps")
    void testTransientCascade() throws Exception {
        ApplicationController.start(minimalSpec("app-a"));
        ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

        ApplicationController.stop("gateway"); // TRANSIENT — should cascade

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
