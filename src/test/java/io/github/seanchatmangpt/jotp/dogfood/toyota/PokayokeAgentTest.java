package io.github.seanchatmangpt.jotp.dogfood.toyota;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.dogfood.toyota.PokayokeAgent.*;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.*;

@Timeout(10)
class PokayokeAgentTest {

    record Order(String id, double amount, String currency) {}

    private PokayokeAgent<Order> pipeline;

    @BeforeEach
    void setUp() {
        pipeline =
                PokayokeAgent.<Order>builder()
                        .device("non-null-id", o -> o.id() != null && !o.id().isBlank())
                        .device("positive-amount", o -> o.amount() > 0)
                        .device(
                                "valid-currency",
                                o -> Set.of("USD", "EUR", "GBP").contains(o.currency()))
                        .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pipeline.stop();
    }

    @Test
    @DisplayName("Valid order passes all three devices")
    void validOrderPasses() {
        var result = pipeline.validate(new Order("ORD-1", 42.0, "USD"), Duration.ofSeconds(1));
        assertThat(result).isInstanceOf(ValidationResult.Passed.class);
        var passed = (ValidationResult.Passed<Order>) result;
        assertThat(passed.passes()).hasSize(3);
    }

    @Test
    @DisplayName("Zero amount fails positive-amount device")
    void zeroAmountFails() {
        var result = pipeline.validate(new Order("ORD-2", 0.0, "EUR"), Duration.ofSeconds(1));
        assertThat(result).isInstanceOf(ValidationResult.Rejected.class);
        var rejected = (ValidationResult.Rejected<Order>) result;
        assertThat(rejected.failures()).hasSize(1);
        assertThat(rejected.failures().get(0).deviceName()).isEqualTo("positive-amount");
    }

    @Test
    @DisplayName("Null ID and invalid currency produces two failures")
    void multipleFailures() {
        var result = pipeline.validate(new Order(null, 10.0, "JPY"), Duration.ofSeconds(1));
        assertThat(result).isInstanceOf(ValidationResult.Rejected.class);
        var rejected = (ValidationResult.Rejected<Order>) result;
        assertThat(rejected.failures()).hasSize(2);
        var failNames = rejected.failures().stream().map(DeviceOutcome.Fail::deviceName).toList();
        assertThat(failNames).containsExactlyInAnyOrder("non-null-id", "valid-currency");
    }

    @Test
    @DisplayName("Statistics accumulate correctly across multiple validations")
    void statsAccumulate() {
        pipeline.validate(new Order("A", 5.0, "USD"), Duration.ofSeconds(1));
        pipeline.validate(new Order(null, 5.0, "USD"), Duration.ofSeconds(1));
        pipeline.validate(new Order("C", 5.0, "EUR"), Duration.ofSeconds(1));

        var stats = pipeline.stats(Duration.ofSeconds(1));
        assertThat(stats.totalValidated()).isEqualTo(3);
        assertThat(stats.totalPassed()).isEqualTo(2);
        assertThat(stats.totalRejected()).isEqualTo(1);
        assertThat(stats.passRate()).isCloseTo(2.0 / 3.0, within(0.001));
    }

    @Test
    @DisplayName("Builder with no devices throws IllegalStateException")
    void emptyPipelineThrows() {
        assertThatThrownBy(() -> PokayokeAgent.builder().build())
                .isInstanceOf(IllegalStateException.class);
    }
}
