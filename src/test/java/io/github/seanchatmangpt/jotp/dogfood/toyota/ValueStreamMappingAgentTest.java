package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.ValueStreamMappingAgent.*;
import java.time.Duration;
import org.junit.jupiter.api.*;

@Timeout(10)
class ValueStreamMappingAgentTest {

    private ValueStreamMappingAgent vsm;

    @BeforeEach
    void setUp() {
        vsm = ValueStreamMappingAgent.start();
        vsm.defineStage("requirements", StageType.BUSINESS_NON_VALUE_ADDED);
        vsm.defineStage("development", StageType.VALUE_ADDED);
        vsm.defineStage("code-review", StageType.BUSINESS_NON_VALUE_ADDED);
        vsm.defineStage("waiting", StageType.WASTE);
        vsm.defineStage("testing", StageType.VALUE_ADDED);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        vsm.stop();
    }

    @Test
    @DisplayName("Empty stream map has zero lead time and empty stages")
    void emptyMap() {
        var map = vsm.currentStateMap(Duration.ofSeconds(1));
        assertThat(map.stages()).isEmpty();
        assertThat(map.leadTime()).isEqualTo(Duration.ZERO);
        assertThat(map.pcEfficiency()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Completed passages produce per-stage statistics")
    void completedPassagesProduceStats() throws InterruptedException {
        vsm.enter("ORDER-1", "development");
        Thread.sleep(20);
        vsm.exit("ORDER-1", "development");

        vsm.enter("ORDER-1", "testing");
        Thread.sleep(10);
        vsm.exit("ORDER-1", "testing");

        var map = vsm.currentStateMap(Duration.ofSeconds(1));
        assertThat(map.stages()).hasSize(2);
        var stageNames = map.stages().stream().map(StageStats::stageName).toList();
        assertThat(stageNames).containsExactlyInAnyOrder("development", "testing");
    }

    @Test
    @DisplayName("PCE efficiency = VA time / total lead time")
    void pcEfficiencyCalculation() throws InterruptedException {
        vsm.enter("ORD-A", "development");
        Thread.sleep(40);
        vsm.exit("ORD-A", "development");

        vsm.enter("ORD-A", "waiting");
        Thread.sleep(40);
        vsm.exit("ORD-A", "waiting");

        var map = vsm.currentStateMap(Duration.ofSeconds(1));
        assertThat(map.pcEfficiency()).isGreaterThan(0.0).isLessThan(1.0);
        // development is VA, waiting is WASTE
        assertThat(map.pcEfficiency()).isCloseTo(0.5, within(0.3));
    }

    @Test
    @DisplayName("Bottleneck is the stage with highest average cycle time")
    void bottleneckIdentified() throws InterruptedException {
        vsm.enter("X", "requirements");
        Thread.sleep(10);
        vsm.exit("X", "requirements");

        vsm.enter("X", "development");
        Thread.sleep(50); // development is slowest
        vsm.exit("X", "development");

        var map = vsm.currentStateMap(Duration.ofSeconds(1));
        assertThat(map.bottleneck()).isEqualTo("development");
    }

    @Test
    @DisplayName("Waste stages are correctly identified")
    void wasteStagesIdentified() throws InterruptedException {
        vsm.enter("W", "waiting");
        Thread.sleep(15);
        vsm.exit("W", "waiting");

        var map = vsm.currentStateMap(Duration.ofSeconds(1));
        assertThat(map.wasteStages()).containsExactly("waiting");
    }
}
