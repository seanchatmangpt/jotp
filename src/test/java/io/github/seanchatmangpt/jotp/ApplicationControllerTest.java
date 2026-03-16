package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Verifies JOTP {@link ApplicationController} equivalence with the Erlang/OTP {@code application:}
 * module.
 *
 * <p>Joe Armstrong: "The key idea in Erlang is that everything is a process, and processes
 * communicate by message passing." Applications are the top-level process groupings — each
 * application is a supervision tree rooted at a single callback, just as every Erlang node is a set
 * of supervised applications. This suite verifies that JOTP faithfully reproduces the OTP {@code
 * application_controller} contract: load → start → stop → unload, cascade semantics, environment
 * queries, and spec key access.
 *
 * <p>Tests run sequentially ({@link ExecutionMode#SAME_THREAD}) because {@link
 * ApplicationController} uses static shared state; concurrent execution would cause races between
 * {@code @BeforeEach reset()} and test-body operations.
 *
 * @see ApplicationController
 * @see ApplicationSpec
 * @see ApplicationCallback
 * @see RunType
 * @see StartType
 */
@DtrTest
@DisplayName("ApplicationController — OTP application: module equivalence")
@Execution(ExecutionMode.SAME_THREAD)
class ApplicationControllerTest implements WithAssertions {

    // ── Test fixtures ──────────────────────────────────────────────────────────

    /**
     * Lightweight descriptor for a test application. Using a record instead of bare strings keeps
     * test intent clear — each call site names the fields explicitly.
     */
    record AppFixture(String name, String description, String vsn) {
        AppFixture(String name) {
            this(name, "Test app", "1.0");
        }
    }

    /** Sealed hierarchy modelling the observed start-type received by a callback during a test. */
    sealed interface ObservedStart
            permits ObservedStart.Normal, ObservedStart.Takeover, ObservedStart.Failover {
        record Normal() implements ObservedStart {}

        record Takeover(String node) implements ObservedStart {}

        record Failover(String node) implements ObservedStart {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Build a minimal spec from a fixture. */
    private static ApplicationSpec specFrom(AppFixture f) {
        return ApplicationSpec.builder(f.name()).description(f.description()).vsn(f.vsn()).build();
    }

    /** Convenience: spec with just a name (uses fixture defaults). */
    private static ApplicationSpec minimalSpec(String name) {
        return specFrom(new AppFixture(name));
    }

    /** Build a spec that invokes the given callback on start. */
    private static ApplicationSpec specWithCallback(
            String name, ApplicationCallback<String> callback) {
        return ApplicationSpec.builder(name)
                .description("Test app with callback")
                .vsn("1.0")
                .mod(callback)
                .build();
    }

    /** Map an OTP {@link StartType} to our observable test variant using exhaustive switch. */
    private static ObservedStart observeStartType(StartType st) {
        return switch (st) {
            case StartType.Normal() -> new ObservedStart.Normal();
            case StartType.Takeover(var n) -> new ObservedStart.Takeover(n);
            case StartType.Failover(var n) -> new ObservedStart.Failover(n);
        };
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Reset global registry before each test — isolation between process groups. */
    @BeforeEach
    void reset() {
        ApplicationController.reset();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Load / Unload — application:load/1 and unload/1
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Load / Unload — application:load/1 and unload/1")
    class LoadUnload {

        @Test
        @DisplayName("load() registers the spec in loadedApplications() without starting")
        void loadMakesSpecAvailable(DtrContext ctx) {
            ctx.sayNextSection("ApplicationController: OTP application: Module");
            ctx.say(
                    """
                    The ApplicationController is JOTP's equivalent to Erlang/OTP's application module.
                    It manages application lifecycle: load (register spec) -> start (spawn processes) ->
                    stop (terminate processes) -> unload (remove spec). Loading an application does NOT
                    start it - it merely registers the spec for later use.
                    """);

            var f = new AppFixture("my-app");
            var spec = specFrom(f);

            ApplicationController.load(spec);

            var apps = ApplicationController.loadedApplications();
            assertThat(apps).hasSize(1);
            assertThat(apps.get(0).name()).isEqualTo(f.name());
            assertThat(apps.get(0).description()).isEqualTo(f.description());
            assertThat(apps.get(0).vsn()).isEqualTo(f.vsn());
        }

        @Test
        @DisplayName("unload() removes the spec — application:unload/1 equivalent")
        void unloadRemovesSpec() {
            var spec = minimalSpec("my-app");
            ApplicationController.load(spec);

            ApplicationController.unload("my-app");

            assertThat(ApplicationController.loadedApplications()).isEmpty();
        }

        @Test
        @DisplayName("unload() on a running application throws — must stop before unloading")
        void unloadRunningApplicationThrows() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));

            // Armstrong: an application that is running owns its process tree; unloading without
            // stopping would leave orphaned processes — OTP disallows this
            assertThatThrownBy(() -> ApplicationController.unload("my-app"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("my-app");
        }

        @Test
        @DisplayName("load() is idempotent — reloading with a newer vsn updates the spec")
        void loadIsIdempotentUpdatingSpec() {
            ApplicationController.load(minimalSpec("my-app"));
            ApplicationController.load(ApplicationSpec.builder("my-app").vsn("2.0").build());

            var apps = ApplicationController.loadedApplications();
            assertThat(apps).hasSize(1);
            assertThat(apps.get(0).vsn()).isEqualTo("2.0");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Start / Stop — application:start/1,2 and stop/1
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Start / Stop — application:start/1,2 and stop/1")
    class StartStop {

        @Test
        @DisplayName("start(spec) invokes the callback and registers in whichApplications()")
        void startInvokesCallbackAndRegisters(DtrContext ctx) throws Exception {
            ctx.say(
                    """
                    Starting an application invokes its callback's start() method and registers it
                    in whichApplications() - the list of currently running applications. This is
                    equivalent to Erlang's application:start/1.
                    """);

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
        @DisplayName("start(spec) auto-loads if not already loaded — convenience overload")
        void startAutoLoadsSpec() throws Exception {
            var spec = minimalSpec("my-app");

            ApplicationController.start(spec);

            assertThat(ApplicationController.loadedApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("my-app");
            assertThat(ApplicationController.whichApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("my-app");
        }

        @Test
        @DisplayName("start(name) on unloaded application throws — no implicit loading by name")
        void startUnloadedByNameThrows() {
            assertThatThrownBy(() -> ApplicationController.start("nonexistent"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nonexistent");
        }

        @Test
        @DisplayName("start() is idempotent — calling start twice invokes the callback once")
        void startIsIdempotent() throws Exception {
            var count = new AtomicInteger(0);
            var spec =
                    specWithCallback(
                            "my-app",
                            (type, args) -> {
                                count.incrementAndGet();
                                return "state";
                            });

            ApplicationController.start(spec);
            ApplicationController.start(spec); // second call should be a no-op

            assertThat(count.get()).isEqualTo(1);
            assertThat(ApplicationController.whichApplications()).hasSize(1);
        }

        @Test
        @DisplayName("stop() invokes callback.stop() with the state returned from start()")
        void stopInvokesCallbackWithStartState() throws Exception {
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
                "stop() retains spec in loadedApplications() — spec survives without its process"
                        + " tree")
        void stopKeepsSpecLoaded() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));

            ApplicationController.stop("my-app");

            assertThat(ApplicationController.whichApplications()).isEmpty();
            assertThat(ApplicationController.loadedApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("my-app");
        }

        @Test
        @DisplayName("stop() is idempotent — stopping an already-stopped application is a no-op")
        void stopIsIdempotent() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));
            ApplicationController.stop("my-app");

            // Armstrong: "let it crash" also means graceful re-entry — a second stop must not throw
            assertThatCode(() -> ApplicationController.stop("my-app")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("start() auto-starts loaded dependencies before the application — OTP order")
        void dependencyStartedBeforeApplication() throws Exception {
            var depSpec = minimalSpec("stdlib");
            var appSpec =
                    ApplicationSpec.builder("ch-app")
                            .applications("stdlib") // declares stdlib as dependency
                            .build();

            ApplicationController.load(depSpec);
            ApplicationController.load(appSpec);
            ApplicationController.start("ch-app");

            var running =
                    ApplicationController.whichApplications().stream()
                            .map(ApplicationInfo::name)
                            .toList();
            assertThat(running).contains("stdlib", "ch-app");
        }

        @Test
        @DisplayName("start() skips unloaded dependencies without throwing")
        void unloadedDependencySkipped() throws Exception {
            var appSpec =
                    ApplicationSpec.builder("ch-app")
                            .applications("kernel") // kernel is NOT loaded
                            .build();
            ApplicationController.load(appSpec);

            // Armstrong: partial environments are normal in distributed systems; skip gracefully
            assertThatCode(() -> ApplicationController.start("ch-app")).doesNotThrowAnyException();
            assertThat(ApplicationController.whichApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("ch-app");
        }

        @Test
        @DisplayName("application callback can create and return a supervision tree")
        void callbackReturnsSupervisor() throws Exception {
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

            // The supervisor — root of the application's process tree — must be alive
            assertThat(supervisorRef.get()).isNotNull();
            assertThat(supervisorRef.get().isRunning()).isTrue();

            ApplicationController.stop("ch-app");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Start Types — Normal / Takeover / Failover
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Start Types — Normal / Takeover / Failover")
    class StartTypes {

        @Test
        @DisplayName("StartType.Normal() is passed to the callback on an ordinary start")
        void normalStartTypeDelivered(DtrContext ctx) throws Exception {
            ctx.say(
                    """
                    StartTypes mirror Erlang/OTP's distributed application semantics:
                    - Normal: regular startup
                    - Takeover: this node is taking over from another node
                    - Failover: the primary node crashed and this is the backup
                    """);

            var observed = new AtomicReference<ObservedStart>();
            var spec =
                    specWithCallback(
                            "my-app",
                            (type, args) -> {
                                observed.set(observeStartType(type));
                                return "ok";
                            });

            ApplicationController.start(spec);

            assertThat(observed.get()).isInstanceOf(ObservedStart.Normal.class);
        }

        @Test
        @DisplayName(
                "StartType.Takeover(node) delivered for distributed takeover — BEAM HA"
                        + " equivalent")
        void takeoverStartTypeDeliveredWithNodeName() throws Exception {
            var received = new AtomicReference<StartType>();
            var spec =
                    specWithCallback(
                            "my-app",
                            (type, args) -> {
                                received.set(type);
                                return "ok";
                            });

            ApplicationController.start(spec, new StartType.Takeover("other-node"));

            // Exhaustive switch extracts the node name without an unchecked cast
            var node =
                    switch (received.get()) {
                        case StartType.Takeover(var n) -> n;
                        case StartType.Normal() -> fail("expected Takeover, got Normal");
                        case StartType.Failover(var n) -> fail("expected Takeover, got Failover");
                    };
            assertThat(node).isEqualTo("other-node");
        }

        @Test
        @DisplayName("StartType.Failover(node) delivered when primary node crashed — let it crash")
        void failoverStartTypeDeliveredWithNodeName() throws Exception {
            var received = new AtomicReference<StartType>();
            var spec =
                    specWithCallback(
                            "my-app",
                            (type, args) -> {
                                received.set(type);
                                return "ok";
                            });

            ApplicationController.start(spec, new StartType.Failover("backup-node"));

            var node =
                    switch (received.get()) {
                        case StartType.Failover(var n) -> n;
                        case StartType.Normal() -> fail("expected Failover, got Normal");
                        case StartType.Takeover(var n) -> fail("expected Failover, got Takeover");
                    };
            assertThat(node).isEqualTo("backup-node");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cascade Semantics — permanent | transient | temporary
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cascade Semantics — permanent | transient | temporary")
    class CascadeSemantics {

        @Test
        @DisplayName("TEMPORARY: stopping one app does not cascade — fault isolation maintained")
        void temporaryNoCascade() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("app-b"), RunType.TEMPORARY);

            // Armstrong: temporary = expendable; its death must not bring down the neighbourhood
            ApplicationController.stop("app-b");

            assertThat(ApplicationController.whichApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("app-a");
        }

        @Test
        @DisplayName(
                "PERMANENT app termination cascades — BEAM runtime equivalent of node shutdown")
        void permanentStopCascadesToAll(DtrContext ctx) throws Exception {
            ctx.say(
                    """
                    RunType.PERMANENT apps are critical infrastructure. When a permanent app terminates,
                    ALL running apps are stopped - this is equivalent to BEAM's node shutdown behavior.
                    This ensures system consistency when core services fail.
                    """);

            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("app-b"));
            ApplicationController.start(minimalSpec("critical"), RunType.PERMANENT);

            // Armstrong: permanent = critical infrastructure; any stop cascades like a supervisor
            // hitting max restarts
            ApplicationController.stop("critical");

            assertThat(ApplicationController.whichApplications()).isEmpty();
        }

        @Test
        @DisplayName(
                "TRANSIENT abnormal termination cascades — crash propagation equivalent to"
                        + " supervisor exhaustion")
        void transientAbnormalCascadesToAll() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("app-b"));
            ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

            // Armstrong: transient = important but not critical; only cascade on crash, not clean
            // shutdown
            ApplicationController.stop("gateway", true); // abnormal — should cascade

            assertThat(ApplicationController.whichApplications()).isEmpty();
        }

        @Test
        @DisplayName(
                "TRANSIENT normal termination does not cascade — clean shutdown is just logged")
        void transientNormalTerminationDoesNotCascade() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

            // Armstrong: transient = important but not critical; only cascade on crash, not clean
            // shutdown
            ApplicationController.stop("gateway"); // normal stop — must NOT cascade

            assertThat(ApplicationController.whichApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("app-a");
        }

        @Test
        @DisplayName(
                "TRANSIENT stop(name, true) cascades all peers — stop(name, false) does not"
                        + " (symmetry)")
        void transientAbnormalCascadesAllPeers() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("app-b"));
            ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);

            ApplicationController.stop("gateway", true); // abnormal — should cascade

            // Both peers must have been stopped by the cascade
            assertThat(ApplicationController.whichApplications()).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Environment — application:get_env/2,3 set_env/3 unset_env/2
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Environment — application:get_env/2,3 set_env/3 unset_env/2")
    class Environment {

        @Test
        @DisplayName("getEnv() returns value from spec's env map — OTP application:get_env/2")
        void getEnvReturnsSpecValue(DtrContext ctx) throws Exception {
            ctx.say(
                    """
                    Application environment provides per-app configuration via getEnv/setEnv/unsetEnv,
                    mirroring Erlang's application:get_env/2,3 and application:set_env/3. Environment
                    can be set in the spec or overridden at runtime.
                    """);

            var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
            ApplicationController.start(spec);

            var val = ApplicationController.getEnv("ch-app", "file");

            assertThat(val).isPresent().contains("/usr/local/log");
        }

        @Test
        @DisplayName("getEnv() returns Optional.empty() for a missing key")
        void getEnvAbsentReturnsEmpty() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));

            var val = ApplicationController.getEnv("my-app", "nonexistent");

            assertThat(val).isEmpty();
        }

        @Test
        @DisplayName("getEnv() works for loaded-but-not-started applications")
        void getEnvFromLoadedSpec() {
            var spec = ApplicationSpec.builder("my-app").env("key", "value").build();
            ApplicationController.load(spec);

            assertThat(ApplicationController.getEnv("my-app", "key")).contains("value");
        }

        @Test
        @DisplayName("setEnv() overrides the spec value at runtime — application:set_env/3")
        void setEnvOverridesSpecValue() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
            ApplicationController.start(spec);

            ApplicationController.setEnv("ch-app", "file", "testlog");

            assertThat(ApplicationController.getEnv("ch-app", "file")).contains("testlog");
        }

        @Test
        @DisplayName("setEnv() override takes precedence over the spec env")
        void setEnvTakesPrecedenceOverSpec() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("key", "original").build();
            ApplicationController.start(spec);

            ApplicationController.setEnv("ch-app", "key", "overridden");

            assertThat(ApplicationController.getEnv("ch-app", "key")).contains("overridden");
        }

        @Test
        @DisplayName("getEnv(app, key, default) returns value when key exists")
        void getEnvWithDefaultKeyPresent() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
            ApplicationController.start(spec);

            var val = ApplicationController.getEnv("ch-app", "file", "/fallback");

            assertThat(val).isEqualTo("/usr/local/log");
        }

        @Test
        @DisplayName("getEnv(app, key, default) returns defaultValue when key is absent")
        void getEnvWithDefaultKeyAbsent() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));

            var val = ApplicationController.getEnv("my-app", "nonexistent", "fallback");

            assertThat(val).isEqualTo("fallback");
        }

        @Test
        @DisplayName("getEnv(app, key, default) runtime override takes precedence over default")
        void getEnvWithDefaultOverrideTakesPrecedence() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));
            ApplicationController.setEnv("my-app", "key", "overridden");

            var val = ApplicationController.getEnv("my-app", "key", "fallback");

            assertThat(val).isEqualTo("overridden");
        }

        @Test
        @DisplayName("unsetEnv() removes runtime override — spec value is visible again")
        void unsetEnvRestoresSpecValue() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("file", "/spec-value").build();
            ApplicationController.start(spec);
            ApplicationController.setEnv("ch-app", "file", "runtime-value");

            ApplicationController.unsetEnv("ch-app", "file");

            assertThat(ApplicationController.getEnv("ch-app", "file")).contains("/spec-value");
        }

        @Test
        @DisplayName("unsetEnv() removes a key with no spec fallback — Optional.empty()")
        void unsetEnvNoSpecFallback() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));
            ApplicationController.setEnv("my-app", "temp-key", "some-value");

            ApplicationController.unsetEnv("my-app", "temp-key");

            assertThat(ApplicationController.getEnv("my-app", "temp-key")).isEmpty();
        }

        @Test
        @DisplayName("unsetEnv() on a key that was never set is a no-op")
        void unsetEnvNeverSetIsNoOp() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));

            // Armstrong: "let it crash" — but only when it should; graceful no-ops are expected
            assertThatCode(() -> ApplicationController.unsetEnv("my-app", "nonexistent"))
                    .doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle Queries — loaded_applications/0 which_applications/0
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Lifecycle Queries — loaded_applications/0 which_applications/0")
    class LifecycleQueries {

        @Test
        @DisplayName("loadedApplications() includes running applications — superset of running")
        void loadedApplicationsIncludesRunning() throws Exception {
            ApplicationController.load(minimalSpec("loaded-only"));
            ApplicationController.start(minimalSpec("running-app"));

            var names =
                    ApplicationController.loadedApplications().stream()
                            .map(ApplicationInfo::name)
                            .toList();
            assertThat(names).contains("loaded-only", "running-app");
        }

        @Test
        @DisplayName("whichApplications() excludes loaded-only applications")
        void whichApplicationsExcludesLoadedOnly() {
            ApplicationController.load(minimalSpec("loaded-only"));

            assertThat(ApplicationController.whichApplications()).isEmpty();
        }

        @Test
        @DisplayName("whichApplications() returns correct ApplicationInfo (name/description/vsn)")
        void whichApplicationsInfoIsCorrect() throws Exception {
            var spec =
                    ApplicationSpec.builder("ch-app")
                            .description("Channel allocator")
                            .vsn("1")
                            .build();
            ApplicationController.start(spec);

            var apps = ApplicationController.whichApplications();
            assertThat(apps).hasSize(1);
            assertThat(apps.get(0).name()).isEqualTo("ch-app");
            assertThat(apps.get(0).description()).isEqualTo("Channel allocator");
            assertThat(apps.get(0).vsn()).isEqualTo("1");
        }

        @Test
        @DisplayName("reset() clears all loaded, running, and env state — clean slate")
        void resetClearsAllState() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.load(minimalSpec("app-b"));
            ApplicationController.setEnv("app-a", "key", "val");

            ApplicationController.reset();

            assertThat(ApplicationController.loadedApplications()).isEmpty();
            assertThat(ApplicationController.whichApplications()).isEmpty();
            assertThat(ApplicationController.getEnv("app-a", "key")).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Restart — application:restart/1
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Restart — application:restart/1")
    class Restart {

        @Test
        @DisplayName("restart() cycles stop then start — application remains running afterwards")
        void restartCyclesStopThenStart() throws Exception {
            var stopCount = new AtomicInteger(0);
            var startCount = new AtomicInteger(0);
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
        @DisplayName("restart() preserves RunType — PERMANENT remains PERMANENT after cycle")
        void restartPreservesRunType() throws Exception {
            ApplicationController.start(minimalSpec("critical"), RunType.PERMANENT);

            ApplicationController.restart("critical");

            // After restart, a normal stop of the PERMANENT app should still cascade
            ApplicationController.start(minimalSpec("app-b"));
            ApplicationController.stop("critical"); // cascade expected: RunType is still PERMANENT

            assertThat(ApplicationController.whichApplications()).isEmpty();
        }

        @Test
        @DisplayName(
                "restart() does not trigger cascade semantics during the stop phase — fault"
                        + " isolation preserved")
        void restartDoesNotCascade() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("critical"), RunType.PERMANENT);

            // Armstrong: restart is internal book-keeping; it must not cascade-stop peers
            ApplicationController.restart("critical");

            assertThat(ApplicationController.whichApplications())
                    .extracting(ApplicationInfo::name)
                    .contains("app-a", "critical");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Spec Keys — application:get_key/2
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Spec Keys — application:get_key/2")
    class SpecKeys {

        @Test
        @DisplayName("getKey(\"description\") returns the description from spec")
        void getKeyDescription() throws Exception {
            var spec =
                    ApplicationSpec.builder("ch-app")
                            .description("Channel allocator")
                            .vsn("2")
                            .build();
            ApplicationController.start(spec);

            assertThat(ApplicationController.getKey("ch-app", "description"))
                    .contains("Channel allocator");
        }

        @Test
        @DisplayName("getKey(\"vsn\") returns the version from spec")
        void getKeyVsn() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").vsn("3.1.4").build();
            ApplicationController.start(spec);

            assertThat(ApplicationController.getKey("ch-app", "vsn")).contains("3.1.4");
        }

        @Test
        @DisplayName("getKey(\"modules\") returns the modules list from spec")
        void getKeyModules() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").modules("ch_app", "ch_sup", "ch3").build();
            ApplicationController.start(spec);

            var result = ApplicationController.getKey("ch-app", "modules");
            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var modules = (List<String>) result.get();
            assertThat(modules).containsExactly("ch_app", "ch_sup", "ch3");
        }

        @Test
        @DisplayName("getKey(\"applications\") returns the dependency list from spec")
        void getKeyApplications() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").applications("kernel", "stdlib").build();
            ApplicationController.load(spec);

            var result = ApplicationController.getKey("ch-app", "applications");
            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var deps = (List<String>) result.get();
            assertThat(deps).containsExactly("kernel", "stdlib");
        }

        @Test
        @DisplayName("getKey() returns Optional.empty() for an unknown key")
        void getKeyUnknownReturnsEmpty() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));

            assertThat(ApplicationController.getKey("my-app", "no_such_key")).isEmpty();
        }

        @Test
        @DisplayName("getKey() returns Optional.empty() when application is not loaded")
        void getKeyNotLoadedReturnsEmpty() {
            assertThat(ApplicationController.getKey("nonexistent", "description")).isEmpty();
        }

        @Test
        @DisplayName("getKey() works for a loaded-but-not-started application")
        void getKeyLoadedOnlyApp() {
            var spec = ApplicationSpec.builder("my-app").description("Loaded only").build();
            ApplicationController.load(spec);

            assertThat(ApplicationController.getKey("my-app", "description"))
                    .contains("Loaded only");
        }
    }
}
