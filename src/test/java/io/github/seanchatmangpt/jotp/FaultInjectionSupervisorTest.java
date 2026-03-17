package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FaultInjectionSupervisor: Chaos testing with real restart semantics")
class FaultInjectionSupervisorTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    sealed interface Msg permits Msg.Inc, Msg.Crash {
        record Inc() implements Msg {}

        record Crash() implements Msg {}
    }

    private static final Duration AWAIT = Duration.ofSeconds(5);

    // ── AfterMessages(0) ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("AfterMessages(0): crash immediately on the first message")
    void afterMessages_zero_crashesOnFirstMessage() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);

        var ref =
                chaos.superviseWithFault(
                        "worker",
                        0,
                        (state, msg) -> state + 1,
                        FaultInjectionSupervisor.FaultSpec.afterMessages(0));

        ref.tell(new Msg.Inc()); // 0 messages processed before, triggers fault immediately

        await().atMost(AWAIT).until(() -> chaos.faultsInjected() >= 1);
        assertThat(chaos.faultsInjected()).isEqualTo(1);

        sup.shutdown();
    }

    // ── AfterMessages(N) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("AfterMessages(3): process 3 messages then crash on 4th")
    void afterMessages_three_crashesOnFourthMessage() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);
        var processedCount = new AtomicInteger(0);

        var ref =
                chaos.superviseWithFault(
                        "counter",
                        0,
                        (state, msg) -> {
                            processedCount.incrementAndGet();
                            return state + 1;
                        },
                        FaultInjectionSupervisor.FaultSpec.afterMessages(3));

        // Send 3 messages — all processed successfully by real handler
        ref.tell(new Msg.Inc());
        ref.tell(new Msg.Inc());
        ref.tell(new Msg.Inc());

        await().atMost(AWAIT).until(() -> processedCount.get() == 3);
        assertThat(chaos.faultsInjected()).isZero();

        // 4th message triggers the fault
        ref.tell(new Msg.Inc());
        await().atMost(AWAIT).until(() -> chaos.faultsInjected() == 1);
        assertThat(chaos.faultsInjected()).isEqualTo(1);

        // After supervisor restarts: no second fault (one-shot semantics)
        ref.tell(new Msg.Inc());
        ref.tell(new Msg.Inc());
        await().atMost(AWAIT).until(() -> processedCount.get() >= 5);
        assertThat(chaos.faultsInjected()).isEqualTo(1); // still exactly 1

        sup.shutdown();
    }

    // ── OnMessage ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OnMessage: crash only when a specific message type is received")
    void onMessage_crashesOnMatchingMessage() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);
        var normalCount = new AtomicInteger(0);

        var ref =
                chaos.superviseWithFault(
                        "selective",
                        0,
                        (state, msg) -> {
                            if (msg instanceof Msg.Inc) normalCount.incrementAndGet();
                            return state;
                        },
                        FaultInjectionSupervisor.FaultSpec.onMessage(m -> m instanceof Msg.Crash));

        // Normal messages pass through without fault
        ref.tell(new Msg.Inc());
        ref.tell(new Msg.Inc());
        await().atMost(AWAIT).until(() -> normalCount.get() == 2);
        assertThat(chaos.faultsInjected()).isZero();

        // Crash-triggering message
        ref.tell(new Msg.Crash());
        await().atMost(AWAIT).until(() -> chaos.faultsInjected() == 1);
        assertThat(chaos.faultsInjected()).isEqualTo(1);

        // Process recovers — normal messages still work
        ref.tell(new Msg.Inc());
        await().atMost(AWAIT).until(() -> normalCount.get() >= 3);

        // Second Crash message should NOT inject a second fault (one-shot)
        ref.tell(new Msg.Crash());
        Thread.sleep(300); // allow time for any spurious fault to register
        assertThat(chaos.faultsInjected()).isEqualTo(1);

        sup.shutdown();
    }

    // ── AfterDuration ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AfterDuration: crash when wall-clock time elapses")
    void afterDuration_crashesAfterTimeout() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);

        var ref =
                chaos.superviseWithFault(
                        "timed",
                        0,
                        (state, msg) -> state,
                        FaultInjectionSupervisor.FaultSpec.afterDuration(Duration.ofMillis(100)));

        // Wait for the duration to elapse
        Thread.sleep(200);

        // Any message now triggers the fault
        ref.tell(new Msg.Inc());
        await().atMost(AWAIT).until(() -> chaos.faultsInjected() == 1);
        assertThat(chaos.faultsInjected()).isEqualTo(1);

        sup.shutdown();
    }

    // ── Multiple processes ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Multiple processes: each accumulates to the shared fault counter")
    void multipleProcesses_sharedFaultCounter() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);

        var ref1 =
                chaos.superviseWithFault(
                        "p1", 0, (s, m) -> s, FaultInjectionSupervisor.FaultSpec.afterMessages(1));
        var ref2 =
                chaos.superviseWithFault(
                        "p2", 0, (s, m) -> s, FaultInjectionSupervisor.FaultSpec.afterMessages(1));

        // p1: process 1 msg successfully, crash on 2nd
        ref1.tell(new Msg.Inc()); // ok
        ref1.tell(new Msg.Inc()); // fault

        // p2: same pattern
        ref2.tell(new Msg.Inc()); // ok
        ref2.tell(new Msg.Inc()); // fault

        await().atMost(AWAIT).until(() -> chaos.faultsInjected() == 2);
        assertThat(chaos.faultsInjected()).isEqualTo(2);

        sup.shutdown();
    }

    // ── resetFaultCount ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("resetFaultCount: resets the counter to zero")
    void resetFaultCount_resetsCounter() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);

        var ref =
                chaos.superviseWithFault(
                        "worker",
                        0,
                        (s, m) -> s,
                        FaultInjectionSupervisor.FaultSpec.afterMessages(0));

        ref.tell(new Msg.Inc()); // triggers immediate fault
        await().atMost(AWAIT).until(() -> chaos.faultsInjected() == 1);

        chaos.resetFaultCount();
        assertThat(chaos.faultsInjected()).isZero();

        sup.shutdown();
    }

    // ── ChildSpec variant ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("superviseWithFault(ChildSpec): uses full child spec configuration")
    void superviseWithFaultChildSpec_usesChildSpec() throws Exception {
        var sup = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        var chaos = FaultInjectionSupervisor.wrap(sup);

        Supervisor.ChildSpec<Integer, Msg> spec =
                Supervisor.ChildSpec.worker("spec-worker", () -> 0, (state, msg) -> state + 1);

        var ref =
                chaos.<Integer, Msg>superviseWithFault(
                        spec, FaultInjectionSupervisor.FaultSpec.afterMessages(2));

        ref.tell(new Msg.Inc()); // ok
        ref.tell(new Msg.Inc()); // ok — 2 processed
        ref.tell(new Msg.Inc()); // fault on 3rd (after 2 processed)

        await().atMost(AWAIT).until(() -> chaos.faultsInjected() == 1);
        assertThat(chaos.faultsInjected()).isEqualTo(1);

        sup.shutdown();
    }
}
