package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.dogfood.toyota.GenchiGenbutsuAgent.*;
import java.time.Duration;
import org.junit.jupiter.api.*;

@Timeout(10)
class GenchiGenbutsuAgentTest {

    private GenchiGenbutsuAgent inspector;

    @BeforeEach
    void setUp() {
        inspector = GenchiGenbutsuAgent.start(Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        inspector.stop();
    }

    @Test
    @DisplayName("Report is empty when no processes are watched")
    void emptyReportWhenNoWatched() {
        inspector.forceSweep();
        var report = inspector.latestReport(Duration.ofSeconds(1));
        assertThat(report.observations()).isEmpty();
        assertThat(report.allHealthy()).isTrue();
    }

    @Test
    @DisplayName("Watched process appears in sweep report")
    void watchedProcessAppearsInReport() throws InterruptedException {
        var target = new Proc<Integer, Integer>(0, (s, m) -> s + m);
        try {
            inspector.watch("counter", target, new HealthThreshold(0, 100, 0));
            inspector.forceSweep();

            await().atMost(Duration.ofSeconds(2))
                    .until(
                            () ->
                                    !inspector
                                            .latestReport(Duration.ofSeconds(1))
                                            .observations()
                                            .isEmpty());

            var report = inspector.latestReport(Duration.ofSeconds(1));
            assertThat(report.observations()).hasSize(1);
            assertThat(report.observations().get(0).processName()).isEqualTo("counter");
        } finally {
            target.stop();
        }
    }

    @Test
    @DisplayName("Queue depth violation flagged as anomaly")
    void queueDepthViolationFlagged() throws InterruptedException {
        var slow =
                new Proc<Integer, Integer>(
                        0,
                        (s, m) -> {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return s + m;
                        });
        try {
            // Send many messages to build up queue
            for (int i = 0; i < 20; i++) slow.tell(i);

            inspector.watch("slow-proc", slow, new HealthThreshold(0, 2, 0)); // max 2 queued
            inspector.forceSweep();

            await().atMost(Duration.ofSeconds(3))
                    .until(
                            () ->
                                    !inspector
                                            .latestReport(Duration.ofSeconds(1))
                                            .anomalies()
                                            .isEmpty());

            var anomalies = inspector.latestReport(Duration.ofSeconds(1)).anomalies();
            assertThat(anomalies).isNotEmpty();
            assertThat(anomalies.get(0).violations()).anyMatch(v -> v.contains("queueDepth"));
        } finally {
            slow.stop();
        }
    }

    @Test
    @DisplayName("Unwatch removes process from subsequent sweeps")
    void unwatchRemovesProcess() throws InterruptedException {
        var target = new Proc<Integer, Integer>(0, (s, m) -> s + m);
        try {
            inspector.watch("removable", target, new HealthThreshold(0, 100, 0));
            inspector.forceSweep();
            await().atMost(Duration.ofSeconds(2))
                    .until(
                            () ->
                                    !inspector
                                            .latestReport(Duration.ofSeconds(1))
                                            .observations()
                                            .isEmpty());

            inspector.unwatch("removable");
            inspector.forceSweep();
            await().atMost(Duration.ofSeconds(2))
                    .until(
                            () ->
                                    inspector
                                            .latestReport(Duration.ofSeconds(1))
                                            .observations()
                                            .isEmpty());
        } finally {
            target.stop();
        }
    }
}
