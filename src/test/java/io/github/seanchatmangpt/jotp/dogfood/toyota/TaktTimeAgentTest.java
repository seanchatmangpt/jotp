package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.TaktTimeAgent.*;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

@Timeout(10)
class TaktTimeAgentTest {

    private TaktTimeAgent<String> agent;
    private CopyOnWriteArrayList<String> produced;

    @BeforeEach
    void setUp() {
        produced = new CopyOnWriteArrayList<>();
        agent = TaktTimeAgent.start(Duration.ofMillis(150), produced::add);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        agent.stop();
    }

    @Test
    @DisplayName("Items enqueued are dispatched to consumer at takt rhythm")
    void itemsDispatchedAtTakt() {
        agent.enqueue("widget-1");
        agent.enqueue("widget-2");
        agent.enqueue("widget-3");

        await().atMost(Duration.ofSeconds(3)).until(() -> produced.size() >= 3);

        assertThat(produced).containsExactly("widget-1", "widget-2", "widget-3");
    }

    @Test
    @DisplayName("Empty ticks do not dispatch items")
    void emptyTicksNothingDispatched() {
        await().atMost(Duration.ofSeconds(2))
                .until(() -> agent.report(Duration.ofSeconds(1)).totalTicks() >= 3);

        assertThat(produced).isEmpty();
        assertThat(agent.report(Duration.ofSeconds(1)).dispatchedCount()).isZero();
        assertThat(agent.report(Duration.ofSeconds(1)).emptyTicks()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Report tracks dispatched count and efficiency")
    void reportTracksEfficiency() {
        agent.enqueue("A");
        agent.enqueue("B");

        await().atMost(Duration.ofSeconds(3)).until(() -> produced.size() >= 2);

        var report = agent.report(Duration.ofSeconds(1));
        assertThat(report.dispatchedCount()).isEqualTo(2);
        assertThat(report.taktTime()).isEqualTo(Duration.ofMillis(150));
        assertThat(report.efficiency()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("Tick count increases monotonically")
    void tickCountIncreases() {
        await().atMost(Duration.ofSeconds(2))
                .until(() -> agent.report(Duration.ofSeconds(1)).totalTicks() >= 4);

        var r1 = agent.report(Duration.ofSeconds(1)).totalTicks();
        var r2 = agent.report(Duration.ofSeconds(1)).totalTicks();
        assertThat(r2).isGreaterThanOrEqualTo(r1);
    }
}
