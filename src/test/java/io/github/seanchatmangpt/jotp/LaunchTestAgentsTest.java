package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Launches 10 supervised agents with diverse {@link Supervisor.ChildSpec} configurations, verifying
 * that each agent starts, processes messages, and responds correctly under supervision.
 *
 * <p>Each agent represents a distinct ChildSpec pattern: permanent workers, transient workers,
 * temporary workers, custom shutdown policies, significant children, and supervisor-type children.
 */
@DisplayName("Launch 10 Test Agents Against Example ChildSpecs")
class LaunchTestAgentsTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    /** Sealed message protocol for all test agents. */
    sealed interface AgentMsg
            permits AgentMsg.Ping,
                    AgentMsg.Increment,
                    AgentMsg.GetState,
                    AgentMsg.Crash,
                    AgentMsg.Work {
        record Ping() implements AgentMsg {}

        record Increment(int amount) implements AgentMsg {}

        record GetState() implements AgentMsg {}

        record Crash(String reason) implements AgentMsg {}

        record Work(String payload) implements AgentMsg {}
    }

    /** Agent state record — immutable, per OTP conventions. */
    record AgentState(String name, int counter, List<String> log) {
        AgentState withCounter(int c) {
            return new AgentState(name, c, log);
        }

        AgentState withLog(String entry) {
            var newLog = new java.util.ArrayList<>(log);
            newLog.add(entry);
            return new AgentState(name, counter, List.copyOf(newLog));
        }
    }

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(3);

    // ── Helper: standard handler shared by most agents ──────────────────────────

    private static BiFunction<AgentState, AgentMsg, AgentState> agentHandler() {
        return (state, msg) ->
                switch (msg) {
                    case AgentMsg.Ping() -> state;
                    case AgentMsg.Increment(var amt) -> state.withCounter(state.counter() + amt);
                    case AgentMsg.GetState() -> state;
                    case AgentMsg.Crash(var reason) ->
                            throw new RuntimeException("Agent " + state.name() + ": " + reason);
                    case AgentMsg.Work(var payload) -> state.withLog(payload);
                };
    }

    private static Supplier<AgentState> stateFactory(String name) {
        return () -> new AgentState(name, 0, List.of());
    }

    // ── The 10 example ChildSpecs ───────────────────────────────────────────────

    /**
     * Spec 1: Permanent worker with default 5s shutdown — the most common OTP pattern. Always
     * restarted on any exit.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> permanentWorkerSpec() {
        return Supervisor.ChildSpec.worker("agent-permanent", stateFactory("permanent"), agentHandler());
    }

    /**
     * Spec 2: Transient worker — restarted only on abnormal exit. Normal exit is final.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> transientWorkerSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-transient",
                stateFactory("transient"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.TRANSIENT,
                new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
                Supervisor.ChildSpec.ChildType.WORKER,
                false);
    }

    /**
     * Spec 3: Temporary worker — never restarted. Fire-and-forget tasks.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> temporaryWorkerSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-temporary",
                stateFactory("temporary"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.TEMPORARY,
                new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
                Supervisor.ChildSpec.ChildType.WORKER,
                false);
    }

    /**
     * Spec 4: Brutal-kill shutdown — immediately interrupted, no graceful period.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> brutalKillSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-brutal",
                stateFactory("brutal"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.PERMANENT,
                new Supervisor.ChildSpec.Shutdown.BrutalKill(),
                Supervisor.ChildSpec.ChildType.WORKER,
                false);
    }

    /**
     * Spec 5: Infinity shutdown — waits indefinitely for graceful stop. Used for trusted workers.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> infinityShutdownSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-infinity",
                stateFactory("infinity"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.PERMANENT,
                new Supervisor.ChildSpec.Shutdown.Infinity(),
                Supervisor.ChildSpec.ChildType.WORKER,
                false);
    }

    /**
     * Spec 6: Significant permanent worker — triggers auto-shutdown when it exits.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> significantWorkerSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-significant",
                stateFactory("significant"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.PERMANENT,
                new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
                Supervisor.ChildSpec.ChildType.WORKER,
                true);
    }

    /**
     * Spec 7: Supervisor-type child — marked as SUPERVISOR, with infinity shutdown.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> supervisorChildSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-supervisor-type",
                stateFactory("supervisor-type"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.PERMANENT,
                new Supervisor.ChildSpec.Shutdown.Infinity(),
                Supervisor.ChildSpec.ChildType.SUPERVISOR,
                false);
    }

    /**
     * Spec 8: Short timeout worker — 500ms shutdown timeout for fast teardown.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> shortTimeoutSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-short-timeout",
                stateFactory("short-timeout"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.PERMANENT,
                new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofMillis(500)),
                Supervisor.ChildSpec.ChildType.WORKER,
                false);
    }

    /**
     * Spec 9: Counter agent — permanent worker using ChildSpec.permanent convenience method.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> counterAgentSpec() {
        return Supervisor.ChildSpec.permanent(
                "agent-counter", new AgentState("counter", 100, List.of()), agentHandler());
    }

    /**
     * Spec 10: Logger agent — transient significant worker with long timeout.
     */
    private Supervisor.ChildSpec<AgentState, AgentMsg> loggerAgentSpec() {
        return new Supervisor.ChildSpec<>(
                "agent-logger",
                stateFactory("logger"),
                agentHandler(),
                Supervisor.ChildSpec.RestartType.TRANSIENT,
                new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(30)),
                Supervisor.ChildSpec.ChildType.WORKER,
                true);
    }

    // ── Tests ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All 10 agents start and respond to ping")
    @SuppressWarnings("unchecked")
    void allTenAgentsStartAndRespondToPing() throws Exception {
        var supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        var specs =
                List.of(
                        permanentWorkerSpec(),
                        transientWorkerSpec(),
                        temporaryWorkerSpec(),
                        brutalKillSpec(),
                        infinityShutdownSpec(),
                        significantWorkerSpec(),
                        supervisorChildSpec(),
                        shortTimeoutSpec(),
                        counterAgentSpec(),
                        loggerAgentSpec());

        assertThat(specs).hasSize(10);

        ProcRef<AgentState, AgentMsg>[] refs = new ProcRef[10];
        for (int i = 0; i < specs.size(); i++) {
            refs[i] = supervisor.startChild(specs.get(i));
        }

        // Verify all 10 agents respond to ping
        for (int i = 0; i < 10; i++) {
            var result = refs[i].ask(new AgentMsg.Ping()).get(2, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(AgentState.class);
        }

        assertThat(supervisor.whichChildren()).hasSize(10);
        assertThat(supervisor.isRunning()).isTrue();

        supervisor.shutdown();
    }

    @Test
    @DisplayName("All 10 agents process increment messages correctly")
    @SuppressWarnings("unchecked")
    void allTenAgentsProcessIncrements() throws Exception {
        var supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        var specs =
                List.of(
                        permanentWorkerSpec(),
                        transientWorkerSpec(),
                        temporaryWorkerSpec(),
                        brutalKillSpec(),
                        infinityShutdownSpec(),
                        significantWorkerSpec(),
                        supervisorChildSpec(),
                        shortTimeoutSpec(),
                        counterAgentSpec(),
                        loggerAgentSpec());

        ProcRef<AgentState, AgentMsg>[] refs = new ProcRef[10];
        for (int i = 0; i < specs.size(); i++) {
            refs[i] = supervisor.startChild(specs.get(i));
        }

        // Send increment to all agents
        for (int i = 0; i < 10; i++) {
            refs[i].tell(new AgentMsg.Increment(i + 1));
        }

        // Wait for all agents' increment messages to be processed
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            for (int i = 0; i < 10; i++) {
                                var state =
                                        (AgentState)
                                                refs[i]
                                                        .ask(new AgentMsg.GetState())
                                                        .get(2, TimeUnit.SECONDS);
                                int expectedBase = (i == 8) ? 100 : 0; // counterAgentSpec
                                // starts at 100
                                if (state.counter() != expectedBase + (i + 1)) return false;
                            }
                            return true;
                        });

        // Verify each agent's counter updated
        for (int i = 0; i < 10; i++) {
            var state = (AgentState) refs[i].ask(new AgentMsg.GetState()).get(2, TimeUnit.SECONDS);
            int expectedBase = (i == 8) ? 100 : 0; // counterAgentSpec starts at 100
            assertThat(state.counter()).isEqualTo(expectedBase + (i + 1));
        }

        supervisor.shutdown();
    }

    @Test
    @DisplayName("All 10 agents process work messages and accumulate log entries")
    @SuppressWarnings("unchecked")
    void allTenAgentsProcessWork() throws Exception {
        var supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        var specs =
                List.of(
                        permanentWorkerSpec(),
                        transientWorkerSpec(),
                        temporaryWorkerSpec(),
                        brutalKillSpec(),
                        infinityShutdownSpec(),
                        significantWorkerSpec(),
                        supervisorChildSpec(),
                        shortTimeoutSpec(),
                        counterAgentSpec(),
                        loggerAgentSpec());

        ProcRef<AgentState, AgentMsg>[] refs = new ProcRef[10];
        for (int i = 0; i < specs.size(); i++) {
            refs[i] = supervisor.startChild(specs.get(i));
        }

        // Send work to all agents
        for (int i = 0; i < 10; i++) {
            refs[i].tell(new AgentMsg.Work("task-" + i));
        }

        // Wait for all agents' work messages to be processed and logged
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            for (int i = 0; i < 10; i++) {
                                var state =
                                        (AgentState)
                                                refs[i]
                                                        .ask(new AgentMsg.GetState())
                                                        .get(2, TimeUnit.SECONDS);
                                if (!state.log().contains("task-" + i)) {
                                    return false;
                                }
                            }
                            return true;
                        });

        // Verify each agent logged the work
        for (int i = 0; i < 10; i++) {
            var state = (AgentState) refs[i].ask(new AgentMsg.GetState()).get(2, TimeUnit.SECONDS);
            assertThat(state.log()).containsExactly("task-" + i);
        }

        supervisor.shutdown();
    }

    @Test
    @DisplayName("Permanent agents restart after crash; transient/temporary do not")
    @SuppressWarnings("unchecked")
    void permanentAgentsRestartOnCrash() throws Exception {
        var supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        var refPermanent = supervisor.startChild(permanentWorkerSpec());
        var refTransient = supervisor.startChild(transientWorkerSpec());
        var refTemporary = supervisor.startChild(temporaryWorkerSpec());

        // Increment all to establish baseline
        refPermanent.tell(new AgentMsg.Increment(42));
        refTransient.tell(new AgentMsg.Increment(42));
        refTemporary.tell(new AgentMsg.Increment(42));

        // Wait for all three agents to process their increment messages
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            var permState =
                                    (AgentState)
                                            refPermanent
                                                    .ask(new AgentMsg.GetState())
                                                    .get(2, TimeUnit.SECONDS);
                            var transState =
                                    (AgentState)
                                            refTransient
                                                    .ask(new AgentMsg.GetState())
                                                    .get(2, TimeUnit.SECONDS);
                            var tempState =
                                    (AgentState)
                                            refTemporary
                                                    .ask(new AgentMsg.GetState())
                                                    .get(2, TimeUnit.SECONDS);
                            return permState.counter() == 42
                                    && transState.counter() == 42
                                    && tempState.counter() == 42;
                        });

        // Crash all three
        refPermanent.tell(new AgentMsg.Crash("test"));
        refTransient.tell(new AgentMsg.Crash("test"));
        refTemporary.tell(new AgentMsg.Crash("test"));

        // Wait for supervisor to detect crashes
        await().atMost(AWAIT_TIMEOUT).until(() -> refPermanent.proc().lastError() != null);

        // Explicit restart-completion await: send ping and wait for response from restarted process.
        // This ensures the supervisor has completed the restart and the new process is ready.
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                var response =
                                        refPermanent.ask(new AgentMsg.Ping()).get(1, TimeUnit.SECONDS);
                                return response instanceof AgentState;
                            } catch (Exception e) {
                                return false;
                            }
                        });

        // Transient restart-completion verification
        await().atMost(AWAIT_TIMEOUT)
                .until(
                        () -> {
                            try {
                                var response =
                                        refTransient.ask(new AgentMsg.Ping()).get(1, TimeUnit.SECONDS);
                                return response instanceof AgentState;
                            } catch (Exception e) {
                                return false;
                            }
                        });

        // Permanent should restart with fresh state (counter = 0)
        var permState =
                (AgentState) refPermanent.ask(new AgentMsg.GetState()).get(2, TimeUnit.SECONDS);
        assertThat(permState.counter()).isEqualTo(0);
        assertThat(permState.name()).isEqualTo("permanent");

        // Transient also restarts on crash (it only stops on normal exit)
        var transState =
                (AgentState) refTransient.ask(new AgentMsg.GetState()).get(2, TimeUnit.SECONDS);
        assertThat(transState.counter()).isEqualTo(0);

        // Temporary agent must NOT restart after crash — verify it's gone from supervisor's children
        var childrenAfterCrash = supervisor.whichChildren();
        var temporaryChild =
                childrenAfterCrash.stream()
                        .filter(c -> c.id().equals("agent-temporary"))
                        .findFirst();
        assertThat(temporaryChild)
                .as("Temporary agent should not be restarted by supervisor")
                .isEmpty();

        supervisor.shutdown();
    }

    @Test
    @DisplayName("whichChildren reports correct types for all 10 agents")
    void whichChildrenReportsAllAgents() throws Exception {
        var supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        supervisor.startChild(permanentWorkerSpec());
        supervisor.startChild(transientWorkerSpec());
        supervisor.startChild(temporaryWorkerSpec());
        supervisor.startChild(brutalKillSpec());
        supervisor.startChild(infinityShutdownSpec());
        supervisor.startChild(significantWorkerSpec());
        supervisor.startChild(supervisorChildSpec());
        supervisor.startChild(shortTimeoutSpec());
        supervisor.startChild(counterAgentSpec());
        supervisor.startChild(loggerAgentSpec());

        var children = supervisor.whichChildren();
        assertThat(children).hasSize(10);

        // Verify all are alive
        assertThat(children).allMatch(Supervisor.ChildInfo::alive);

        // Verify the supervisor-type child is marked correctly
        var supChild =
                children.stream()
                        .filter(c -> c.id().equals("agent-supervisor-type"))
                        .findFirst()
                        .orElseThrow();
        assertThat(supChild.type()).isEqualTo(Supervisor.ChildSpec.ChildType.SUPERVISOR);

        // All others should be WORKER
        var workers =
                children.stream()
                        .filter(c -> !c.id().equals("agent-supervisor-type"))
                        .toList();
        assertThat(workers).allMatch(c -> c.type() == Supervisor.ChildSpec.ChildType.WORKER);

        supervisor.shutdown();
    }

    @Test
    @DisplayName("Concurrent message flood to all 10 agents")
    @SuppressWarnings("unchecked")
    void concurrentMessageFloodToAllAgents() throws Exception {
        var supervisor =
                Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

        var specs =
                List.of(
                        permanentWorkerSpec(),
                        transientWorkerSpec(),
                        temporaryWorkerSpec(),
                        brutalKillSpec(),
                        infinityShutdownSpec(),
                        significantWorkerSpec(),
                        supervisorChildSpec(),
                        shortTimeoutSpec(),
                        counterAgentSpec(),
                        loggerAgentSpec());

        ProcRef<AgentState, AgentMsg>[] refs = new ProcRef[10];
        for (int i = 0; i < specs.size(); i++) {
            refs[i] = supervisor.startChild(specs.get(i));
        }

        // Send 100 increments to each agent concurrently
        var messagesPerAgent = 100;
        var threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int agentIdx = i;
            threads[i] =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        for (int m = 0; m < messagesPerAgent; m++) {
                                            refs[agentIdx].tell(new AgentMsg.Increment(1));
                                        }
                                    });
        }
        for (Thread t : threads) t.join();

        // Wait for all messages to be processed
        await()
                .atMost(AWAIT_TIMEOUT)
                .pollDelay(Duration.ofMillis(50))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> {
                            for (int i = 0; i < 10; i++) {
                                var state =
                                        (AgentState)
                                                refs[i]
                                                        .ask(new AgentMsg.GetState())
                                                        .get(1, TimeUnit.SECONDS);
                                int expectedBase = (i == 8) ? 100 : 0;
                                if (state.counter() != expectedBase + messagesPerAgent) return false;
                            }
                            return true;
                        });

        supervisor.shutdown();
    }
}
