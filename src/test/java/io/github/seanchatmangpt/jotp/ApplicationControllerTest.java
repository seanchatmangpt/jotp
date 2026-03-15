package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
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
 * <p>Tests run sequentially ({@link ExecutionMode#SAME_THREAD}) because {@link
 * ApplicationController} uses static shared state; concurrent execution would cause races between
 * {@code @BeforeEach reset()} and test-body operations.
 * @see ApplicationController
 * @see ApplicationSpec
 * @see ApplicationCallback
 * @see RunType
 * @see StartType
 */
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
    // ── Helpers ────────────────────────────────────────────────────────────────
    /** Build a minimal spec from a fixture. */
    private static ApplicationSpec specFrom(AppFixture f) {
        return ApplicationSpec.builder(f.name()).description(f.description()).vsn(f.vsn()).build();
    /** Convenience: spec with just a name (uses fixture defaults). */
    private static ApplicationSpec minimalSpec(String name) {
        return specFrom(new AppFixture(name));
    /** Build a spec that invokes the given callback on start. */
    private static ApplicationSpec specWithCallback(
            String name, ApplicationCallback<String> callback) {
        return ApplicationSpec.builder(name)
                .description("Test app with callback")
                .vsn("1.0")
                .mod(callback)
                .build();
    /** Map an OTP {@link StartType} to our observable test variant using exhaustive switch. */
    private static ObservedStart observeStartType(StartType st) {
        return switch (st) {
            case StartType.Normal() -> new ObservedStart.Normal();
            case StartType.Takeover(var n) -> new ObservedStart.Takeover(n);
            case StartType.Failover(var n) -> new ObservedStart.Failover(n);
        };
    // ── Lifecycle ──────────────────────────────────────────────────────────────
    /** Reset global registry before each test — isolation between process groups. */
    @BeforeEach
    void reset() {
        ApplicationController.reset();
    // ══════════════════════════════════════════════════════════════════════════
    // Load / Unload — application:load/1 and unload/1
    @Nested
    @DisplayName("Load / Unload — application:load/1 and unload/1")
    class LoadUnload {
        @Test
        @DisplayName("load() registers the spec in loadedApplications() without starting")
        void loadMakesSpecAvailable() {
            var f = new AppFixture("my-app");
            var spec = specFrom(f);
            ApplicationController.load(spec);
            var apps = ApplicationController.loadedApplications();
            assertThat(apps).hasSize(1);
            assertThat(apps.get(0).name()).isEqualTo(f.name());
            assertThat(apps.get(0).description()).isEqualTo(f.description());
            assertThat(apps.get(0).vsn()).isEqualTo(f.vsn());
        @DisplayName("unload() removes the spec — application:unload/1 equivalent")
        void unloadRemovesSpec() {
            var spec = minimalSpec("my-app");
            ApplicationController.unload("my-app");
            assertThat(ApplicationController.loadedApplications()).isEmpty();
        @DisplayName("unload() on a running application throws — must stop before unloading")
        void unloadRunningApplicationThrows() throws Exception {
            ApplicationController.start(minimalSpec("my-app"));
            // Armstrong: an application that is running owns its process tree; unloading without
            // stopping would leave orphaned processes — OTP disallows this
            assertThatThrownBy(() -> ApplicationController.unload("my-app"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("my-app");
        @DisplayName("load() is idempotent — reloading with a newer vsn updates the spec")
        void loadIsIdempotentUpdatingSpec() {
            ApplicationController.load(minimalSpec("my-app"));
            ApplicationController.load(ApplicationSpec.builder("my-app").vsn("2.0").build());
            assertThat(apps.get(0).vsn()).isEqualTo("2.0");
    // Start / Stop — application:start/1,2 and stop/1
    @DisplayName("Start / Stop — application:start/1,2 and stop/1")
    class StartStop {
        @DisplayName("start(spec) invokes the callback and registers in whichApplications()")
        void startInvokesCallbackAndRegisters() throws Exception {
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
        @DisplayName("start(spec) auto-loads if not already loaded — convenience overload")
        void startAutoLoadsSpec() throws Exception {
            assertThat(ApplicationController.loadedApplications())
                    .contains("my-app");
        @DisplayName("start(name) on unloaded application throws — no implicit loading by name")
        void startUnloadedByNameThrows() {
            assertThatThrownBy(() -> ApplicationController.start("nonexistent"))
                    .hasMessageContaining("nonexistent");
        @DisplayName("start() is idempotent — calling start twice invokes the callback once")
        void startIsIdempotent() throws Exception {
            var count = new AtomicInteger(0);
                            "my-app",
                                count.incrementAndGet();
                                return "state";
            ApplicationController.start(spec); // second call should be a no-op
            assertThat(count.get()).isEqualTo(1);
            assertThat(ApplicationController.whichApplications()).hasSize(1);
        @DisplayName("stop() invokes callback.stop() with the state returned from start()")
        void stopInvokesCallbackWithStartState() throws Exception {
            var stoppedWith = new AtomicReference<String>();
                    ApplicationSpec.builder("ch-app")
                            .mod(
                                    new ApplicationCallback<String>() {
                                        @Override
                                        public String start(StartType type, Object args) {
                                            return "my-state";
                                        }
                                        public void stop(String state) {
                                            stoppedWith.set(state);
                                    })
                            .build();
            ApplicationController.stop("ch-app");
            assertThat(stoppedWith.get()).isEqualTo("my-state");
            assertThat(ApplicationController.whichApplications()).isEmpty();
        @DisplayName(
                "stop() retains spec in loadedApplications() — spec survives without its process"
                        + " tree")
        void stopKeepsSpecLoaded() throws Exception {
            ApplicationController.stop("my-app");
        @DisplayName("stop() is idempotent — stopping an already-stopped application is a no-op")
        void stopIsIdempotent() throws Exception {
            // Armstrong: "let it crash" also means graceful re-entry — a second stop must not throw
            assertThatCode(() -> ApplicationController.stop("my-app")).doesNotThrowAnyException();
        @DisplayName("start() auto-starts loaded dependencies before the application — OTP order")
        void dependencyStartedBeforeApplication() throws Exception {
            var depSpec = minimalSpec("stdlib");
            var appSpec =
                            .applications("stdlib") // declares stdlib as dependency
            ApplicationController.load(depSpec);
            ApplicationController.load(appSpec);
            ApplicationController.start("ch-app");
            var running =
                    ApplicationController.whichApplications().stream()
                            .map(ApplicationInfo::name)
                            .toList();
            assertThat(running).contains("stdlib", "ch-app");
        @DisplayName("start() skips unloaded dependencies without throwing")
        void unloadedDependencySkipped() throws Exception {
                            .applications("kernel") // kernel is NOT loaded
            // Armstrong: partial environments are normal in distributed systems; skip gracefully
            assertThatCode(() -> ApplicationController.start("ch-app")).doesNotThrowAnyException();
                    .contains("ch-app");
        @DisplayName("application callback can create and return a supervision tree")
        void callbackReturnsSupervisor() throws Exception {
            var supervisorRef = new AtomicReference<Supervisor>();
                            .description("Channel allocator")
                            .vsn("1")
                                    (type, args) -> {
                                        var sup =
                                                new Supervisor(
                                                        Supervisor.Strategy.ONE_FOR_ONE,
                                                        5,
                                                        Duration.ofSeconds(60));
                                        supervisorRef.set(sup);
                                        return sup;
            // The supervisor — root of the application's process tree — must be alive
            assertThat(supervisorRef.get()).isNotNull();
            assertThat(supervisorRef.get().isRunning()).isTrue();
    // Start Types — Normal / Takeover / Failover
    @DisplayName("Start Types — Normal / Takeover / Failover")
    class StartTypes {
        @DisplayName("StartType.Normal() is passed to the callback on an ordinary start")
        void normalStartTypeDelivered() throws Exception {
            var observed = new AtomicReference<ObservedStart>();
                                observed.set(observeStartType(type));
                                return "ok";
            assertThat(observed.get()).isInstanceOf(ObservedStart.Normal.class);
                "StartType.Takeover(node) delivered for distributed takeover — BEAM HA"
                        + " equivalent")
        void takeoverStartTypeDeliveredWithNodeName() throws Exception {
            var received = new AtomicReference<StartType>();
                                received.set(type);
            ApplicationController.start(spec, new StartType.Takeover("other-node"));
            // Exhaustive switch extracts the node name without an unchecked cast
            var node =
                    switch (received.get()) {
                        case StartType.Takeover(var n) -> n;
                        case StartType.Normal() -> fail("expected Takeover, got Normal");
                        case StartType.Failover(var n) -> fail("expected Takeover, got Failover");
                    };
            assertThat(node).isEqualTo("other-node");
        @DisplayName("StartType.Failover(node) delivered when primary node crashed — let it crash")
        void failoverStartTypeDeliveredWithNodeName() throws Exception {
            ApplicationController.start(spec, new StartType.Failover("backup-node"));
                        case StartType.Failover(var n) -> n;
                        case StartType.Normal() -> fail("expected Failover, got Normal");
                        case StartType.Takeover(var n) -> fail("expected Failover, got Takeover");
            assertThat(node).isEqualTo("backup-node");
    // Cascade Semantics — permanent | transient | temporary
    @DisplayName("Cascade Semantics — permanent | transient | temporary")
    class CascadeSemantics {
        @DisplayName("TEMPORARY: stopping one app does not cascade — fault isolation maintained")
        void temporaryNoCascade() throws Exception {
            ApplicationController.start(minimalSpec("app-a"));
            ApplicationController.start(minimalSpec("app-b"), RunType.TEMPORARY);
            // Armstrong: temporary = expendable; its death must not bring down the neighbourhood
            ApplicationController.stop("app-b");
                    .contains("app-a");
                "PERMANENT app termination cascades — BEAM runtime equivalent of node shutdown")
        void permanentStopCascadesToAll() throws Exception {
            ApplicationController.start(minimalSpec("app-b"));
            ApplicationController.start(minimalSpec("critical"), RunType.PERMANENT);
            // Armstrong: permanent = critical infrastructure; any stop cascades like a supervisor
            // hitting max restarts
            ApplicationController.stop("critical");
                "TRANSIENT abnormal termination cascades — crash propagation equivalent to"
                        + " supervisor exhaustion")
        void transientAbnormalCascadesToAll() throws Exception {
            ApplicationController.start(minimalSpec("gateway"), RunType.TRANSIENT);
            // Armstrong: transient = important but not critical; only cascade on crash, not clean
            // shutdown
            ApplicationController.stop("gateway", true); // abnormal — should cascade
                "TRANSIENT normal termination does not cascade — clean shutdown is just logged")
        void transientNormalTerminationDoesNotCascade() throws Exception {
            ApplicationController.stop("gateway"); // normal stop — must NOT cascade
                "TRANSIENT stop(name, true) cascades all peers — stop(name, false) does not"
                        + " (symmetry)")
        void transientAbnormalCascadesAllPeers() throws Exception {
            // Both peers must have been stopped by the cascade
    // Environment — application:get_env/2,3 set_env/3 unset_env/2
    @DisplayName("Environment — application:get_env/2,3 set_env/3 unset_env/2")
    class Environment {
        @DisplayName("getEnv() returns value from spec's env map — OTP application:get_env/2")
        void getEnvReturnsSpecValue() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("file", "/usr/local/log").build();
            var val = ApplicationController.getEnv("ch-app", "file");
            assertThat(val).isPresent().contains("/usr/local/log");
        @DisplayName("getEnv() returns Optional.empty() for a missing key")
        void getEnvAbsentReturnsEmpty() throws Exception {
            var val = ApplicationController.getEnv("my-app", "nonexistent");
            assertThat(val).isEmpty();
        @DisplayName("getEnv() works for loaded-but-not-started applications")
        void getEnvFromLoadedSpec() {
            var spec = ApplicationSpec.builder("my-app").env("key", "value").build();
            assertThat(ApplicationController.getEnv("my-app", "key")).contains("value");
        @DisplayName("setEnv() overrides the spec value at runtime — application:set_env/3")
        void setEnvOverridesSpecValue() throws Exception {
            ApplicationController.setEnv("ch-app", "file", "testlog");
            assertThat(ApplicationController.getEnv("ch-app", "file")).contains("testlog");
        @DisplayName("setEnv() override takes precedence over the spec env")
        void setEnvTakesPrecedenceOverSpec() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("key", "original").build();
            ApplicationController.setEnv("ch-app", "key", "overridden");
            assertThat(ApplicationController.getEnv("ch-app", "key")).contains("overridden");
        @DisplayName("getEnv(app, key, default) returns value when key exists")
        void getEnvWithDefaultKeyPresent() throws Exception {
            var val = ApplicationController.getEnv("ch-app", "file", "/fallback");
            assertThat(val).isEqualTo("/usr/local/log");
        @DisplayName("getEnv(app, key, default) returns defaultValue when key is absent")
        void getEnvWithDefaultKeyAbsent() throws Exception {
            var val = ApplicationController.getEnv("my-app", "nonexistent", "fallback");
            assertThat(val).isEqualTo("fallback");
        @DisplayName("getEnv(app, key, default) runtime override takes precedence over default")
        void getEnvWithDefaultOverrideTakesPrecedence() throws Exception {
            ApplicationController.setEnv("my-app", "key", "overridden");
            var val = ApplicationController.getEnv("my-app", "key", "fallback");
            assertThat(val).isEqualTo("overridden");
        @DisplayName("unsetEnv() removes runtime override — spec value is visible again")
        void unsetEnvRestoresSpecValue() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").env("file", "/spec-value").build();
            ApplicationController.setEnv("ch-app", "file", "runtime-value");
            ApplicationController.unsetEnv("ch-app", "file");
            assertThat(ApplicationController.getEnv("ch-app", "file")).contains("/spec-value");
        @DisplayName("unsetEnv() removes a key with no spec fallback — Optional.empty()")
        void unsetEnvNoSpecFallback() throws Exception {
            ApplicationController.setEnv("my-app", "temp-key", "some-value");
            ApplicationController.unsetEnv("my-app", "temp-key");
            assertThat(ApplicationController.getEnv("my-app", "temp-key")).isEmpty();
        @DisplayName("unsetEnv() on a key that was never set is a no-op")
        void unsetEnvNeverSetIsNoOp() throws Exception {
            // Armstrong: "let it crash" — but only when it should; graceful no-ops are expected
            assertThatCode(() -> ApplicationController.unsetEnv("my-app", "nonexistent"))
                    .doesNotThrowAnyException();
    // Lifecycle Queries — loaded_applications/0 which_applications/0
    @DisplayName("Lifecycle Queries — loaded_applications/0 which_applications/0")
    class LifecycleQueries {
        @DisplayName("loadedApplications() includes running applications — superset of running")
        void loadedApplicationsIncludesRunning() throws Exception {
            ApplicationController.load(minimalSpec("loaded-only"));
            ApplicationController.start(minimalSpec("running-app"));
            var names =
                    ApplicationController.loadedApplications().stream()
            assertThat(names).contains("loaded-only", "running-app");
        @DisplayName("whichApplications() excludes loaded-only applications")
        void whichApplicationsExcludesLoadedOnly() {
        @DisplayName("whichApplications() returns correct ApplicationInfo (name/description/vsn)")
        void whichApplicationsInfoIsCorrect() throws Exception {
            var apps = ApplicationController.whichApplications();
            assertThat(apps.get(0).name()).isEqualTo("ch-app");
            assertThat(apps.get(0).description()).isEqualTo("Channel allocator");
            assertThat(apps.get(0).vsn()).isEqualTo("1");
        @DisplayName("reset() clears all loaded, running, and env state — clean slate")
        void resetClearsAllState() throws Exception {
            ApplicationController.load(minimalSpec("app-b"));
            ApplicationController.setEnv("app-a", "key", "val");
            ApplicationController.reset();
            assertThat(ApplicationController.getEnv("app-a", "key")).isEmpty();
    // Restart — application:restart/1
    @DisplayName("Restart — application:restart/1")
    class Restart {
        @DisplayName("restart() cycles stop then start — application remains running afterwards")
        void restartCyclesStopThenStart() throws Exception {
            var stopCount = new AtomicInteger(0);
            var startCount = new AtomicInteger(0);
                            new ApplicationCallback<String>() {
                                @Override
                                public String start(StartType type, Object args) {
                                    startCount.incrementAndGet();
                                    return "state";
                                }
                                public void stop(String state) {
                                    stopCount.incrementAndGet();
            ApplicationController.restart("my-app");
            assertThat(startCount.get()).isEqualTo(2);
            assertThat(stopCount.get()).isEqualTo(1);
                    .containsExactly("my-app");
        @DisplayName("restart() preserves RunType — PERMANENT remains PERMANENT after cycle")
        void restartPreservesRunType() throws Exception {
            ApplicationController.restart("critical");
            // After restart, a normal stop of the PERMANENT app should still cascade
            ApplicationController.stop("critical"); // cascade expected: RunType is still PERMANENT
                "restart() does not trigger cascade semantics during the stop phase — fault"
                        + " isolation preserved")
        void restartDoesNotCascade() throws Exception {
            // Armstrong: restart is internal book-keeping; it must not cascade-stop peers
                    .contains("app-a", "critical");
    // Spec Keys — application:get_key/2
    @DisplayName("Spec Keys — application:get_key/2")
    class SpecKeys {
        @DisplayName("getKey(\"description\") returns the description from spec")
        void getKeyDescription() throws Exception {
                            .vsn("2")
            assertThat(ApplicationController.getKey("ch-app", "description"))
                    .contains("Channel allocator");
        @DisplayName("getKey(\"vsn\") returns the version from spec")
        void getKeyVsn() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").vsn("3.1.4").build();
            assertThat(ApplicationController.getKey("ch-app", "vsn")).contains("3.1.4");
        @DisplayName("getKey(\"modules\") returns the modules list from spec")
        void getKeyModules() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").modules("ch_app", "ch_sup", "ch3").build();
            var result = ApplicationController.getKey("ch-app", "modules");
            assertThat(result).isPresent();
            assertThat(result.get()).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            var modules = (List<String>) result.get();
            assertThat(modules).containsExactly("ch_app", "ch_sup", "ch3");
        @DisplayName("getKey(\"applications\") returns the dependency list from spec")
        void getKeyApplications() throws Exception {
            var spec = ApplicationSpec.builder("ch-app").applications("kernel", "stdlib").build();
            var result = ApplicationController.getKey("ch-app", "applications");
            var deps = (List<String>) result.get();
            assertThat(deps).containsExactly("kernel", "stdlib");
        @DisplayName("getKey() returns Optional.empty() for an unknown key")
        void getKeyUnknownReturnsEmpty() throws Exception {
            assertThat(ApplicationController.getKey("my-app", "no_such_key")).isEmpty();
        @DisplayName("getKey() returns Optional.empty() when application is not loaded")
        void getKeyNotLoadedReturnsEmpty() {
            assertThat(ApplicationController.getKey("nonexistent", "description")).isEmpty();
        @DisplayName("getKey() works for a loaded-but-not-started application")
        void getKeyLoadedOnlyApp() {
            var spec = ApplicationSpec.builder("my-app").description("Loaded only").build();
            assertThat(ApplicationController.getKey("my-app", "description"))
                    .contains("Loaded only");
}
