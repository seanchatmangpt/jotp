package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.DimensionResult;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.FailureLearningIndicator;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.MaturityAssessment;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.MaturityLevel;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.MessagePassingIndicator;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.ObservabilityIndicator;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.SelfHealingIndicator;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.SupervisionIndicator;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AgentMaturityScorer.SystemProfile;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AgentMaturityScorer")
class AgentMaturityScorerTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Profile builders ─────────────────────────────────────────────────────

    /** Bare-minimum system: nothing enabled, no processes, low uptime. */
    private static SystemProfile bareMinimum() {
        return new SystemProfile("bare-minimum", false, false, false, false, false, false, false, 0,
                50.0);
    }

    /** Traditional microservice: health checks and metrics but no OTP patterns. */
    private static SystemProfile traditionalMicroservice() {
        return new SystemProfile("traditional-ms", false, false, false, true, true, true, false, 5,
                99.0);
    }

    /** JOTP-based system: supervision, message passing, circuit breakers, good uptime. */
    private static SystemProfile jotpSystem() {
        return new SystemProfile("jotp-system", true, true, true, true, true, true, false, 50,
                99.9);
    }

    /** Ideal autonomous system: everything enabled, high process count, near-perfect uptime. */
    private static SystemProfile idealAutonomous() {
        return new SystemProfile("ideal-autonomous", true, true, true, true, true, true, true, 200,
                99.99);
    }

    // ── Profile assessment tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("bare-minimum system")
    class BareMinimumSystem {

        @Test
        @DisplayName("scores MANUAL maturity level")
        void scoresManualLevel() {
            var result = AgentMaturityScorer.assess(bareMinimum());

            assertThat(result).isInstanceOf(Result.Ok.class);
            var assessment = result.orElseThrow();

            assertThat(assessment.overall()).isEqualTo(MaturityLevel.MANUAL);
        }

        @Test
        @DisplayName("all dimensions score below 60")
        void allDimensionsLow() {
            var assessment = AgentMaturityScorer.assess(bareMinimum()).orElseThrow();

            assertThat(assessment.dimensions())
                    .allSatisfy(dim -> assertThat(dim.score()).isLessThan(60));
        }

        @Test
        @DisplayName("generates recommendations for all dimensions")
        void generatesRecommendationsForAllDimensions() {
            var assessment = AgentMaturityScorer.assess(bareMinimum()).orElseThrow();

            assertThat(assessment.recommendations()).hasSize(5);
        }
    }

    @Nested
    @DisplayName("traditional microservice")
    class TraditionalMicroservice {

        @Test
        @DisplayName("scores REACTIVE or PROACTIVE maturity level")
        void scoresReactiveOrProactive() {
            var assessment = AgentMaturityScorer.assess(traditionalMicroservice()).orElseThrow();

            assertThat(assessment.overall().level())
                    .isBetween(MaturityLevel.REACTIVE.level(), MaturityLevel.PROACTIVE.level());
        }

        @Test
        @DisplayName("observability dimension scores higher than supervision")
        void observabilityHigherThanSupervision() {
            var assessment = AgentMaturityScorer.assess(traditionalMicroservice()).orElseThrow();

            var observability = findDimension(assessment, "Observability");
            var supervision = findDimension(assessment, "Supervision");

            assertThat(observability.score()).isGreaterThan(supervision.score());
        }

        @Test
        @DisplayName("generates recommendations for missing capabilities")
        void recommendsMissingCapabilities() {
            var assessment = AgentMaturityScorer.assess(traditionalMicroservice()).orElseThrow();

            assertThat(assessment.recommendations())
                    .anyMatch(r -> r.contains("self-healing") || r.contains("Self-Healing"))
                    .anyMatch(r -> r.contains("supervision") || r.contains("Supervision"))
                    .anyMatch(r -> r.contains("message passing") || r.contains("Message Passing"));
        }
    }

    @Nested
    @DisplayName("JOTP system")
    class JotpSystem {

        @Test
        @DisplayName("scores SELF_HEALING or LEARNING maturity level")
        void scoresSelfHealingOrLearning() {
            var assessment = AgentMaturityScorer.assess(jotpSystem()).orElseThrow();

            assertThat(assessment.overall().level())
                    .isBetween(MaturityLevel.SELF_HEALING.level(), MaturityLevel.LEARNING.level());
        }

        @Test
        @DisplayName("supervision dimension scores at least PROACTIVE")
        void supervisionAtLeastProactive() {
            var assessment = AgentMaturityScorer.assess(jotpSystem()).orElseThrow();
            var supervision = findDimension(assessment, "Supervision");

            assertThat(supervision.dimensionLevel().level())
                    .isGreaterThanOrEqualTo(MaturityLevel.PROACTIVE.level());
        }

        @Test
        @DisplayName("fewer recommendations than bare-minimum system")
        void fewerRecommendationsThanBareMinimum() {
            var jotpAssessment = AgentMaturityScorer.assess(jotpSystem()).orElseThrow();
            var bareAssessment = AgentMaturityScorer.assess(bareMinimum()).orElseThrow();

            assertThat(jotpAssessment.recommendations().size())
                    .isLessThan(bareAssessment.recommendations().size());
        }
    }

    @Nested
    @DisplayName("ideal autonomous system")
    class IdealAutonomous {

        @Test
        @DisplayName("scores LEARNING or AUTONOMOUS maturity level")
        void scoresLearningOrAutonomous() {
            var assessment = AgentMaturityScorer.assess(idealAutonomous()).orElseThrow();

            assertThat(assessment.overall().level())
                    .isBetween(MaturityLevel.LEARNING.level(), MaturityLevel.AUTONOMOUS.level());
        }

        @Test
        @DisplayName("all dimensions score above 60")
        void allDimensionsAbove60() {
            var assessment = AgentMaturityScorer.assess(idealAutonomous()).orElseThrow();

            assertThat(assessment.dimensions())
                    .allSatisfy(dim -> assertThat(dim.score()).isGreaterThanOrEqualTo(60));
        }

        @Test
        @DisplayName("no recommendations generated")
        void noRecommendations() {
            var assessment = AgentMaturityScorer.assess(idealAutonomous()).orElseThrow();

            assertThat(assessment.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("system name is preserved in assessment")
        void preservesSystemName() {
            var assessment = AgentMaturityScorer.assess(idealAutonomous()).orElseThrow();

            assertThat(assessment.systemName()).isEqualTo("ideal-autonomous");
        }

        @Test
        @DisplayName("has exactly five dimensions")
        void hasFiveDimensions() {
            var assessment = AgentMaturityScorer.assess(idealAutonomous()).orElseThrow();

            assertThat(assessment.dimensions()).hasSize(5);
            assertThat(assessment.dimensions())
                    .extracting(DimensionResult::name)
                    .containsExactly("Self-Healing", "Failure Learning", "Message Passing",
                            "Supervision", "Observability");
        }
    }

    // ── MaturityLevel tests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("MaturityLevel enum")
    class MaturityLevelTests {

        @Test
        @DisplayName("levels are ordered 0 through 5")
        void levelsAreOrdered() {
            var levels = MaturityLevel.values();

            assertThat(levels).hasSize(6);
            for (int i = 0; i < levels.length; i++) {
                assertThat(levels[i].level()).isEqualTo(i);
            }
        }

        @Test
        @DisplayName("all levels have non-empty descriptions")
        void allLevelsHaveDescriptions() {
            for (var level : MaturityLevel.values()) {
                assertThat(level.description()).isNotEmpty();
            }
        }
    }

    // ── Score-to-level mapping tests ─────────────────────────────────────────

    @Nested
    @DisplayName("scoreToLevel mapping")
    class ScoreToLevelMapping {

        @Test
        @DisplayName("0-20 maps to MANUAL")
        void manualRange() {
            assertThat(AgentMaturityScorer.scoreToLevel(0)).isEqualTo(MaturityLevel.MANUAL);
            assertThat(AgentMaturityScorer.scoreToLevel(20)).isEqualTo(MaturityLevel.MANUAL);
        }

        @Test
        @DisplayName("21-40 maps to REACTIVE")
        void reactiveRange() {
            assertThat(AgentMaturityScorer.scoreToLevel(21)).isEqualTo(MaturityLevel.REACTIVE);
            assertThat(AgentMaturityScorer.scoreToLevel(40)).isEqualTo(MaturityLevel.REACTIVE);
        }

        @Test
        @DisplayName("41-60 maps to PROACTIVE")
        void proactiveRange() {
            assertThat(AgentMaturityScorer.scoreToLevel(41)).isEqualTo(MaturityLevel.PROACTIVE);
            assertThat(AgentMaturityScorer.scoreToLevel(60)).isEqualTo(MaturityLevel.PROACTIVE);
        }

        @Test
        @DisplayName("61-80 maps to SELF_HEALING")
        void selfHealingRange() {
            assertThat(AgentMaturityScorer.scoreToLevel(61)).isEqualTo(MaturityLevel.SELF_HEALING);
            assertThat(AgentMaturityScorer.scoreToLevel(80)).isEqualTo(MaturityLevel.SELF_HEALING);
        }

        @Test
        @DisplayName("81-95 maps to LEARNING")
        void learningRange() {
            assertThat(AgentMaturityScorer.scoreToLevel(81)).isEqualTo(MaturityLevel.LEARNING);
            assertThat(AgentMaturityScorer.scoreToLevel(95)).isEqualTo(MaturityLevel.LEARNING);
        }

        @Test
        @DisplayName("96-100 maps to AUTONOMOUS")
        void autonomousRange() {
            assertThat(AgentMaturityScorer.scoreToLevel(96)).isEqualTo(MaturityLevel.AUTONOMOUS);
            assertThat(AgentMaturityScorer.scoreToLevel(100)).isEqualTo(MaturityLevel.AUTONOMOUS);
        }
    }

    // ── Indicator record tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("Indicator records")
    class IndicatorRecords {

        @Test
        @DisplayName("SelfHealingIndicator validates score range")
        void selfHealingValidatesScore() {
            assertThatThrownBy(() -> new SelfHealingIndicator(-1, "test"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SelfHealingIndicator(101, "test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("FailureLearningIndicator rejects null evidence")
        void failureLearningRejectsNull() {
            assertThatThrownBy(() -> new FailureLearningIndicator(50, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("all indicator types are sealed subtypes of Indicator")
        void allIndicatorTypesSealed() {
            assertThat(new SelfHealingIndicator(50, "test"))
                    .isInstanceOf(AgentMaturityScorer.Indicator.class);
            assertThat(new FailureLearningIndicator(50, "test"))
                    .isInstanceOf(AgentMaturityScorer.Indicator.class);
            assertThat(new MessagePassingIndicator(50, "test"))
                    .isInstanceOf(AgentMaturityScorer.Indicator.class);
            assertThat(new SupervisionIndicator(50, "test"))
                    .isInstanceOf(AgentMaturityScorer.Indicator.class);
            assertThat(new ObservabilityIndicator(50, "test"))
                    .isInstanceOf(AgentMaturityScorer.Indicator.class);
        }
    }

    // ── SystemProfile validation ─────────────────────────────────────────────

    @Nested
    @DisplayName("SystemProfile validation")
    class SystemProfileValidation {

        @Test
        @DisplayName("rejects null name")
        void rejectsNullName() {
            assertThatThrownBy(
                    () -> new SystemProfile(null, false, false, false, false, false, false, false,
                            0, 50.0))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects negative process count")
        void rejectsNegativeProcessCount() {
            assertThatThrownBy(
                    () -> new SystemProfile("test", false, false, false, false, false, false, false,
                            -1, 50.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects uptime below 0 or above 100")
        void rejectsInvalidUptime() {
            assertThatThrownBy(
                    () -> new SystemProfile("test", false, false, false, false, false, false, false,
                            0, -1.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                    () -> new SystemProfile("test", false, false, false, false, false, false, false,
                            0, 100.1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Result wrapping tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("Result wrapping")
    class ResultWrapping {

        @Test
        @DisplayName("returns Ok for valid profile")
        void returnsOkForValidProfile() {
            var result = AgentMaturityScorer.assess(bareMinimum());

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("returns Err for null profile")
        void returnsErrForNullProfile() {
            var result = AgentMaturityScorer.assess(null);

            assertThat(result.isError()).isTrue();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DimensionResult findDimension(MaturityAssessment assessment, String name) {
        return assessment.dimensions().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Dimension not found: " + name));
    }
}
