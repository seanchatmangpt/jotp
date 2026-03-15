package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.KaizenAgent.*;
import java.time.Duration;
import org.junit.jupiter.api.*;

@Timeout(10)
class KaizenAgentTest {

    @Test
    @DisplayName("Initial state is Idle")
    void initialStateIdle() throws InterruptedException {
        var sm = KaizenAgent.start("test-pass-rate", 0.95);
        assertThat(sm.state()).isInstanceOf(KaizenState.Idle.class);
        assertThat(sm.data().cycleCount()).isZero();
        sm.stop();
    }

    @Test
    @DisplayName("Plan event transitions Idle → Planning")
    void planTransition() throws InterruptedException {
        var sm = KaizenAgent.start("build-time", 0.80);
        sm.send(new KaizenEvent.Plan("Parallelize compilation", 0.65));
        await().atMost(Duration.ofSeconds(2))
                .until(() -> sm.state() instanceof KaizenState.Planning);
        var planning = (KaizenState.Planning) sm.state();
        assertThat(planning.baseline()).isEqualTo(0.65);
        sm.stop();
    }

    @Test
    @DisplayName("Full PDCA cycle completes and records history")
    void fullCycleRecorded() throws InterruptedException {
        var sm = KaizenAgent.start("quality-rate", 0.95);

        var data =
                KaizenAgent.runCycle(
                                sm,
                                "Introduce mutation testing",
                                0.78,
                                "Added PITest to build",
                                0.87,
                                "Mutation score improved: 68% → 81%",
                                true,
                                "Standardised: PITest added to CI pipeline")
                        .join();

        assertThat(data.cycleCount()).isEqualTo(1);
        assertThat(data.cycleHistory().get(0).standardised()).isTrue();
        assertThat(data.cycleHistory().get(0).improvement()).isCloseTo(0.09, within(0.001));
        await().atMost(Duration.ofSeconds(2)).until(() -> sm.state() instanceof KaizenState.Acting);
        sm.stop();
    }

    @Test
    @DisplayName("Two consecutive cycles accumulate in history")
    void twoCycles() throws InterruptedException {
        var sm = KaizenAgent.start("defect-rate", 0.90);

        KaizenAgent.runCycle(
                        sm,
                        "Hypothesis A",
                        0.70,
                        "Change A",
                        0.75,
                        "Small gain",
                        true,
                        "Standardised A")
                .join();
        // After Act, machine is in Acting state — next Plan from Acting triggers Planning
        KaizenAgent.runCycle(
                        sm,
                        "Hypothesis B",
                        0.75,
                        "Change B",
                        0.85,
                        "Bigger gain",
                        true,
                        "Standardised B")
                .join();

        assertThat(sm.data().cycleCount()).isEqualTo(2);
        sm.stop();
    }

    @Test
    @DisplayName("Discarded cycle is recorded with standardised=false")
    void discardedCycleRecorded() throws InterruptedException {
        var sm = KaizenAgent.start("throughput", 0.95);

        var data =
                KaizenAgent.runCycle(
                                sm,
                                "Risky experiment",
                                0.80,
                                "Applied change",
                                0.79,
                                "Regression detected",
                                false,
                                "Reverted — result worse than baseline")
                        .join();

        assertThat(data.cycleHistory().get(0).standardised()).isFalse();
        assertThat(data.latestResult().getAsDouble()).isEqualTo(0.79);
        assertThat(data.targetAchieved()).isFalse();
        sm.stop();
    }
}
