package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.AndonCordAgent.*;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

@Timeout(10)
class AndonCordAgentTest {

    private AndonCordAgent agent;
    private CopyOnWriteArrayList<AndonEvent> received;

    @BeforeEach
    void setUp() {
        received = new CopyOnWriteArrayList<>();
        agent = AndonCordAgent.start(Duration.ofSeconds(5));
        agent.addListener(received::add);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        agent.stop();
    }

    @Test
    @DisplayName("Pull emits AndonEvent.Pull and creates open alert")
    void pullCreatesAlert() {
        agent.pull("station-1", "Pressure drop detected");

        await().atMost(Duration.ofSeconds(2))
                .until(() -> received.stream().anyMatch(e -> e instanceof AndonEvent.Pull));

        assertThat(agent.openAlerts()).hasSize(1);
        assertThat(agent.openAlerts().get(0).station()).isEqualTo("station-1");
    }

    @Test
    @DisplayName("Acknowledge removes alert from open list and emits Acknowledge event")
    void acknowledgeClosesAlert() {
        agent.pull("station-2", "Overheating sensor");
        await().atMost(Duration.ofSeconds(2))
                .until(() -> received.stream().anyMatch(e -> e instanceof AndonEvent.Pull));

        agent.acknowledge("station-2");
        await().atMost(Duration.ofSeconds(2))
                .until(() -> received.stream().anyMatch(e -> e instanceof AndonEvent.Acknowledge));

        assertThat(agent.openAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Resolve emits Resolve event and removes from open list")
    void resolveClosesAlert() {
        agent.pull("station-3", "Conveyor jammed");
        agent.acknowledge("station-3");
        agent.resolve("station-3", "Belt realigned and tension adjusted");

        await().atMost(Duration.ofSeconds(2))
                .until(() -> received.stream().anyMatch(e -> e instanceof AndonEvent.Resolve));

        assertThat(agent.openAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Metrics track total pulls")
    void metricsTrackPulls() {
        agent.pull("s-A", "fault-1");
        agent.pull("s-B", "fault-2");
        agent.acknowledge("s-A");
        agent.resolve("s-A", "fixed");

        await().atMost(Duration.ofSeconds(2)).until(() -> agent.metrics().totalPulls() >= 2);

        assertThat(agent.metrics().totalPulls()).isEqualTo(2);
    }
}
