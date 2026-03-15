package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.JidokaAgent.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;

@Timeout(10)
class JidokaAgentTest {

    @Test
    @DisplayName("Clean inspections stay in Running state")
    void cleanItemsStayRunning() throws InterruptedException {
        var sm = JidokaAgent.start(3);
        sm.send(new JidokaEvent.Inspect("item-1", List.of()));
        sm.send(new JidokaEvent.Inspect("item-2", List.of()));
        Thread.sleep(100);
        assertThat(sm.state()).isInstanceOf(JidokaState.Running.class);
        assertThat(sm.data().itemsInspected()).isEqualTo(2);
        sm.stop();
    }

    @Test
    @DisplayName("Defect threshold breach halts the line")
    void defectsHaltLine() throws InterruptedException {
        var sm = JidokaAgent.start(2);
        sm.send(new JidokaEvent.Inspect("X", List.of("NPE in login()")));
        sm.send(new JidokaEvent.Inspect("Y", List.of("ClassCastException")));
        await().atMost(Duration.ofSeconds(2)).until(() -> sm.state() instanceof JidokaState.Halted);

        var halted = (JidokaState.Halted) sm.state();
        assertThat(halted.triggeringDefects()).hasSize(2);
        assertThat(sm.data().haltCount()).isEqualTo(1);
        sm.stop();
    }

    @Test
    @DisplayName("Full PDCA: Halted → Investigating → Resumed → Running")
    void fullRecoveryCycle() throws InterruptedException {
        var sm = JidokaAgent.start(1);
        sm.send(new JidokaEvent.Inspect("Z", List.of("Critical: DB connection lost")));
        await().atMost(Duration.ofSeconds(2)).until(() -> sm.state() instanceof JidokaState.Halted);

        sm.send(new JidokaEvent.Investigate("Connection pool exhausted — leaked connections"));
        await().atMost(Duration.ofSeconds(2))
                .until(() -> sm.state() instanceof JidokaState.Investigating);

        sm.send(new JidokaEvent.Resolve("Closed leaked connections; pool size increased to 20"));
        await().atMost(Duration.ofSeconds(2))
                .until(() -> sm.state() instanceof JidokaState.Resumed);

        // Next inspect triggers return to Running
        sm.send(new JidokaEvent.Inspect("CLEAN", List.of()));
        await().atMost(Duration.ofSeconds(2))
                .until(() -> sm.state() instanceof JidokaState.Running);

        sm.stop();
    }

    @Test
    @DisplayName("ClearWindow resets defect window without halting")
    void clearWindowResets() throws InterruptedException {
        var sm = JidokaAgent.start(3);
        sm.send(new JidokaEvent.Inspect("A", List.of("defect1")));
        sm.send(new JidokaEvent.Inspect("B", List.of("defect2")));
        Thread.sleep(100);
        assertThat(sm.state()).isInstanceOf(JidokaState.Running.class);
        assertThat(sm.data().windowDefects()).hasSize(2);

        sm.send(new JidokaEvent.ClearWindow());
        Thread.sleep(100);
        assertThat(sm.data().windowDefects()).isEmpty();

        // After clear, threshold resets — two more defects don't halt (< 3)
        sm.send(new JidokaEvent.Inspect("C", List.of("defect3")));
        Thread.sleep(100);
        assertThat(sm.state()).isInstanceOf(JidokaState.Running.class);
        sm.stop();
    }

    @Test
    @DisplayName("defectRate() computes total defects / items inspected")
    void defectRateCalculation() throws InterruptedException {
        var sm = JidokaAgent.start(10);
        sm.send(new JidokaEvent.Inspect("A", List.of("d1")));
        sm.send(new JidokaEvent.Inspect("B", List.of()));
        sm.send(new JidokaEvent.Inspect("C", List.of("d2", "d3")));
        sm.call(new JidokaEvent.Inspect("D", List.of())).join();

        // 3 defects across 4 items
        assertThat(sm.data().defectRate()).isCloseTo(3.0 / 4.0, within(0.001));
        sm.stop();
    }
}
