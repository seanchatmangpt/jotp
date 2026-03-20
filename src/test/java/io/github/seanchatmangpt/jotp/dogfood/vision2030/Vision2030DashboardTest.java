package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;

/**
 * Tests for {@link Vision2030Dashboard} — verifies concurrent orchestration of all four Vision 2030
 * engines and aggregation into an executive dashboard.
 */
@DisplayName("Vision2030Dashboard")
class Vision2030DashboardTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Nested
    @DisplayName("startup profile (minimal capabilities)")
    class StartupProfile {

        private final Vision2030Dashboard.EnterpriseProfile profile =
                new Vision2030Dashboard.EnterpriseProfile(
                        "TinyStartup",
                        3, // small team
                        false, // no OTP experience
                        false, // no Spring Boot
                        1, // single service
                        95.0, // low SLA
                        false, // no supervisor
                        false, // no circuit breaker
                        false, // no message passing
                        false, // no health checks
                        false, // no metrics
                        false, // no failure logging
                        false, // no adaptive restart
                        2, // few processes
                        90.0); // low uptime

        @Test
        @DisplayName("assess returns successful result with all four sub-results populated")
        void assessReturnsAllFourSubResults() {
            var result = Vision2030Dashboard.assess(profile);

            assertThat(result).isInstanceOf(Result.Ok.class);
            var dashboard = result.orElseThrow();

            assertThat(dashboard.strategy()).isNotNull();
            assertThat(dashboard.adoption()).isNotNull();
            assertThat(dashboard.maturity()).isNotNull();
            assertThat(dashboard.roadmap()).isNotNull();
            assertThat(dashboard.executiveSummary()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("executive summary contains key metrics")
        void executiveSummaryContainsKeyMetrics() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.executiveSummary())
                    .contains("TinyStartup")
                    .contains("Blue Ocean Index")
                    .contains("Adoption Phase")
                    .contains("Maturity Level")
                    .contains("Roadmap")
                    .contains("Recommendation");
        }

        @Test
        @DisplayName("startup has low readiness and early adoption phase")
        void startupHasLowReadiness() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();
            var adoption = dashboard.adoption();

            assertThat(adoption.readiness().overall()).isLessThan(60);
            assertThat(adoption.currentPhase())
                    .isInstanceOfAny(
                            AdoptionPipelineEngine.AdoptionPhase.Assessment.class,
                            AdoptionPipelineEngine.AdoptionPhase.Blocked.class);
        }

        @Test
        @DisplayName("startup has low maturity level")
        void startupHasLowMaturity() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.maturity().overall().level()).isLessThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("enterprise profile (established capabilities)")
    class EnterpriseProfileTests {

        private final Vision2030Dashboard.EnterpriseProfile profile =
                new Vision2030Dashboard.EnterpriseProfile(
                        "AcmeCorp",
                        25, // large team
                        true, // OTP experience
                        true, // Spring Boot
                        15, // many microservices
                        99.95, // high SLA
                        true, // supervisor
                        true, // circuit breaker
                        true, // message passing
                        true, // health checks
                        true, // metrics
                        true, // failure logging
                        false, // no adaptive restart yet
                        50, // moderate processes
                        99.9); // high uptime

        @Test
        @DisplayName("assess returns successful result with all sub-results")
        void assessReturnsAllSubResults() {
            var result = Vision2030Dashboard.assess(profile);

            assertThat(result).isInstanceOf(Result.Ok.class);
            var dashboard = result.orElseThrow();

            assertThat(dashboard.strategy()).isNotNull();
            assertThat(dashboard.adoption()).isNotNull();
            assertThat(dashboard.maturity()).isNotNull();
            assertThat(dashboard.roadmap()).isNotNull();
        }

        @Test
        @DisplayName("enterprise has higher readiness than startup")
        void enterpriseHasHigherReadiness() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.adoption().readiness().overall()).isGreaterThanOrEqualTo(60);
        }

        @Test
        @DisplayName("enterprise has meaningful maturity level")
        void enterpriseHasMeaningfulMaturity() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.maturity().overall().level()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("roadmap has pareto set populated")
        void roadmapHasParetoSet() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.roadmap().paretoSet()).isNotEmpty();
            assertThat(dashboard.roadmap().allItems()).hasSizeGreaterThanOrEqualTo(
                    dashboard.roadmap().paretoSet().size());
        }

        @Test
        @DisplayName("blue ocean analysis has competitor data")
        void blueOceanHasCompetitors() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.strategy().competitors()).hasSize(4);
            assertThat(dashboard.strategy().blueOceanIndex()).isBetween(0.0, 1.0);
        }
    }

    @Nested
    @DisplayName("JOTP-native profile (all features, high scale)")
    class JotpNativeProfile {

        private final Vision2030Dashboard.EnterpriseProfile profile =
                new Vision2030Dashboard.EnterpriseProfile(
                        "JOTPNative",
                        50, // large team
                        true, // deep OTP experience
                        true, // Spring Boot
                        30, // many microservices
                        99.99, // highest SLA
                        true, // supervisor
                        true, // circuit breaker
                        true, // message passing
                        true, // health checks
                        true, // metrics
                        true, // failure logging
                        true, // adaptive restart
                        1500, // many processes
                        99.99); // highest uptime

        @Test
        @DisplayName("assess returns successful result")
        void assessSucceeds() {
            var result = Vision2030Dashboard.assess(profile);

            assertThat(result).isInstanceOf(Result.Ok.class);
        }

        @Test
        @DisplayName("all four sub-results are populated")
        void allSubResultsPopulated() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.strategy()).isNotNull();
            assertThat(dashboard.strategy().paretoRanking()).isNotEmpty();
            assertThat(dashboard.adoption()).isNotNull();
            assertThat(dashboard.adoption().readiness()).isNotNull();
            assertThat(dashboard.maturity()).isNotNull();
            assertThat(dashboard.maturity().dimensions()).isNotEmpty();
            assertThat(dashboard.roadmap()).isNotNull();
            assertThat(dashboard.roadmap().paretoSet()).isNotEmpty();
        }

        @Test
        @DisplayName("JOTP-native has highest readiness and maturity")
        void jotpNativeHasHighestLevels() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.adoption().readiness().overall()).isGreaterThanOrEqualTo(75);
            assertThat(dashboard.maturity().overall().level()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("executive summary recommends production or scale deployment")
        void summaryRecommendsAdvancedDeployment() {
            var dashboard = Vision2030Dashboard.assess(profile).orElseThrow();

            assertThat(dashboard.executiveSummary())
                    .containsAnyOf(
                            "production", "Production",
                            "scale", "Scale",
                            "autonomous", "Autonomous",
                            "full-scale");
        }
    }

    @Nested
    @DisplayName("custom roadmap items")
    class CustomRoadmapItems {

        private final Vision2030Dashboard.EnterpriseProfile profile =
                new Vision2030Dashboard.EnterpriseProfile(
                        "CustomRoadmapCorp",
                        10, true, true, 5, 99.5,
                        true, false, true, true, true, false, false, 20, 99.0);

        @Test
        @DisplayName("assess with custom roadmap items succeeds")
        void assessWithCustomRoadmapItems() {
            var customItems =
                    List.of(
                            new RoadmapPrioritizer.RoadmapItem(
                                    "phase-1",
                                    "Phase 1 Migration",
                                    2026,
                                    9.0,
                                    3.0,
                                    List.of()),
                            new RoadmapPrioritizer.RoadmapItem(
                                    "phase-2",
                                    "Phase 2 Expansion",
                                    2027,
                                    7.0,
                                    5.0,
                                    List.of("phase-1")),
                            new RoadmapPrioritizer.RoadmapItem(
                                    "phase-3",
                                    "Phase 3 Optimization",
                                    2028,
                                    6.0,
                                    4.0,
                                    List.of("phase-2")));

            var result = Vision2030Dashboard.assess(profile, customItems);

            assertThat(result).isInstanceOf(Result.Ok.class);
            var dashboard = result.orElseThrow();
            assertThat(dashboard.roadmap().allItems()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        @DisplayName("null profile throws NullPointerException")
        void nullProfileThrows() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Vision2030Dashboard.assess(null));
        }

        @Test
        @DisplayName("null roadmap items throws NullPointerException")
        void nullRoadmapItemsThrows() {
            var profile =
                    new Vision2030Dashboard.EnterpriseProfile(
                            "Test", 5, false, false, 1, 99.0,
                            false, false, false, false, false, false, false, 1, 95.0);

            assertThatNullPointerException()
                    .isThrownBy(() -> Vision2030Dashboard.assess(profile, null));
        }

        @Test
        @DisplayName("null name in EnterpriseProfile throws NullPointerException")
        void nullNameThrows() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new Vision2030Dashboard.EnterpriseProfile(
                                            null, 5, false, false, 1, 99.0,
                                            false, false, false, false, false, false, false,
                                            1, 95.0));
        }

        @Test
        @DisplayName("negative team size throws IllegalArgumentException")
        void negativeTeamSizeThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new Vision2030Dashboard.EnterpriseProfile(
                                            "Test", -1, false, false, 1, 99.0,
                                            false, false, false, false, false, false, false,
                                            1, 95.0));
        }
    }

    @Nested
    @DisplayName("concurrent execution")
    class ConcurrentExecution {

        @Test
        @DisplayName("assessment completes within reasonable time")
        void assessmentCompletesInTime() {
            var profile =
                    new Vision2030Dashboard.EnterpriseProfile(
                            "TimingTest",
                            10, true, true, 5, 99.5,
                            true, true, true, true, true, true, true, 100, 99.9);

            long start = System.nanoTime();
            var result = Vision2030Dashboard.assess(profile);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            assertThat(result).isInstanceOf(Result.Ok.class);
            // Concurrent execution should complete well under 5 seconds
            assertThat(elapsed).isLessThan(5_000);
        }
    }
}
