package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AdoptionPipelineEngine.AdoptionAssessment;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AdoptionPipelineEngine.AdoptionPhase;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AdoptionPipelineEngine.MigrationRisk;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AdoptionPipelineEngine.PhaseTransition;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AdoptionPipelineEngine.ReadinessScore;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.AdoptionPipelineEngine.RiskLevel;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AdoptionPipelineEngine")
class AdoptionPipelineEngineTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Small startup scenario ────────────────────────────────────────────────

    @Nested
    @DisplayName("small startup (5 devs, no OTP experience, no Spring Boot)")
    class SmallStartup {

        /** 5 devs, no OTP, no Spring Boot, 3 microservices, 95.0% SLA. */
        private AdoptionAssessment assess() {
            return AdoptionPipelineEngine.assess("TinyStartup", 5, false, false, 3, 95.0)
                    .orElseThrow();
        }

        @Test
        @DisplayName("team readiness scores base 30 + teamSize*2 = 40")
        void teamReadiness() {
            var assessment = assess();

            assertThat(assessment.readiness().teamReadiness()).isEqualTo(40);
        }

        @Test
        @DisplayName("infra readiness scores base 20 + microserviceCount = 23")
        void infraReadiness() {
            var assessment = assess();

            assertThat(assessment.readiness().infraReadiness()).isEqualTo(23);
        }

        @Test
        @DisplayName("codebase readiness scores base 10 + (95/2=47) = 57")
        void codebaseReadiness() {
            var assessment = assess();

            assertThat(assessment.readiness().codebaseReadiness()).isEqualTo(57);
        }

        @Test
        @DisplayName("overall readiness is weighted average of 40")
        void overallReadiness() {
            var assessment = assess();

            // (40*0.4 + 23*0.3 + 57*0.3) = 16 + 6.9 + 17.1 = 40
            assertThat(assessment.readiness().overall()).isEqualTo(40);
        }

        @Test
        @DisplayName("current phase is Assessment for overall score of 40")
        void phaseIsAssessment() {
            var assessment = assess();

            assertThat(assessment.currentPhase()).isInstanceOf(AdoptionPhase.Assessment.class);
        }

        @Test
        @DisplayName("is not pilot ready with overall score of 40")
        void notPilotReady() {
            var assessment = assess();

            assertThat(assessment.readiness().isPilotReady()).isFalse();
        }

        @Test
        @DisplayName("generates HIGH risk for team and CRITICAL risk for infra")
        void risksMatchScores() {
            var assessment = assess();

            var teamRisk = findRisk(assessment, "Team readiness");
            var infraRisk = findRisk(assessment, "Infrastructure readiness");
            var codebaseRisk = findRisk(assessment, "Codebase readiness");

            assertThat(teamRisk.level()).isEqualTo(RiskLevel.HIGH);
            assertThat(infraRisk.level()).isEqualTo(RiskLevel.CRITICAL);
            assertThat(codebaseRisk.level()).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("recommends training and infrastructure improvements")
        void recommendsImprovements() {
            var assessment = assess();

            assertThat(assessment.recommendations())
                    .anyMatch(r -> r.contains("training"))
                    .anyMatch(r -> r.contains("infrastructure") || r.contains("Infrastructure"));
        }
    }

    // ── Mature enterprise scenario ────────────────────────────────────────────

    @Nested
    @DisplayName("mature enterprise (50 devs, Spring Boot, high SLA)")
    class MatureEnterprise {

        /** 50 devs, no OTP, Spring Boot, 30 microservices, 99.5% SLA. */
        private AdoptionAssessment assess() {
            return AdoptionPipelineEngine.assess("MegaCorp", 50, false, true, 30, 99.5)
                    .orElseThrow();
        }

        @Test
        @DisplayName("team readiness is capped at 100")
        void teamReadinessCapped() {
            var assessment = assess();

            // 30 + 0 + 100 = 130, capped at 100
            assertThat(assessment.readiness().teamReadiness()).isEqualTo(100);
        }

        @Test
        @DisplayName("infra readiness scores 90 with Spring Boot and 30 microservices")
        void infraReadiness() {
            var assessment = assess();

            // 20 + 40 + 30 = 90
            assertThat(assessment.readiness().infraReadiness()).isEqualTo(90);
        }

        @Test
        @DisplayName("codebase readiness scores 89 with Spring Boot and 99.5 SLA")
        void codebaseReadiness() {
            var assessment = assess();

            // 10 + (99.5/2=49) + 30 = 89
            assertThat(assessment.readiness().codebaseReadiness()).isEqualTo(89);
        }

        @Test
        @DisplayName("overall score is 93 — Scale phase")
        void overallIsScale() {
            var assessment = assess();

            // (100*0.4 + 90*0.3 + 89*0.3) = 40 + 27 + 26.7 = 93
            assertThat(assessment.readiness().overall()).isEqualTo(93);
            assertThat(assessment.currentPhase()).isInstanceOf(AdoptionPhase.Scale.class);
        }

        @Test
        @DisplayName("is pilot ready")
        void isPilotReady() {
            var assessment = assess();

            assertThat(assessment.readiness().isPilotReady()).isTrue();
        }

        @Test
        @DisplayName("all risk levels are LOW for high-scoring dimensions")
        void allRisksLow() {
            var assessment = assess();

            assertThat(assessment.risks())
                    .allSatisfy(risk -> assertThat(risk.level()).isEqualTo(RiskLevel.LOW));
        }

        @Test
        @DisplayName("recommends pilot deployment")
        void recommendsPilot() {
            var assessment = assess();

            assertThat(assessment.recommendations())
                    .anyMatch(r -> r.contains("pilot"));
        }
    }

    // ── Ideal candidate scenario ──────────────────────────────────────────────

    @Nested
    @DisplayName("ideal candidate (20 devs, OTP experience, Spring Boot, high SLA)")
    class IdealCandidate {

        /** 20 devs, OTP experience, Spring Boot, 15 microservices, 99.95% SLA. */
        private AdoptionAssessment assess() {
            return AdoptionPipelineEngine.assess("IdealCorp", 20, true, true, 15, 99.95)
                    .orElseThrow();
        }

        @Test
        @DisplayName("team readiness is 100 with OTP experience")
        void teamReadiness() {
            var assessment = assess();

            // 30 + 30 + 40 = 100
            assertThat(assessment.readiness().teamReadiness()).isEqualTo(100);
        }

        @Test
        @DisplayName("infra readiness is 75 with Spring Boot and 15 microservices")
        void infraReadiness() {
            var assessment = assess();

            // 20 + 40 + 15 = 75
            assertThat(assessment.readiness().infraReadiness()).isEqualTo(75);
        }

        @Test
        @DisplayName("codebase readiness is 90 with high SLA and Spring Boot")
        void codebaseReadiness() {
            var assessment = assess();

            // 10 + 50 (>= 99.9) + 30 = 90
            assertThat(assessment.readiness().codebaseReadiness()).isEqualTo(90);
        }

        @Test
        @DisplayName("overall score is 89 — Production phase")
        void overallIsProduction() {
            var assessment = assess();

            // (100*0.4 + 75*0.3 + 90*0.3) = 40 + 22.5 + 27 = 89
            assertThat(assessment.readiness().overall()).isEqualTo(89);
            assertThat(assessment.currentPhase()).isInstanceOf(AdoptionPhase.Production.class);
        }

        @Test
        @DisplayName("enterprise name is preserved")
        void preservesEnterpriseName() {
            var assessment = assess();

            assertThat(assessment.enterpriseName()).isEqualTo("IdealCorp");
        }

        @Test
        @DisplayName("has exactly three migration risks")
        void hasThreeRisks() {
            var assessment = assess();

            assertThat(assessment.risks()).hasSize(3);
            assertThat(assessment.risks())
                    .extracting(MigrationRisk::name)
                    .containsExactly(
                            "Team readiness",
                            "Infrastructure readiness",
                            "Codebase readiness");
        }
    }

    // ── Phase transition tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("phase transitions")
    class PhaseTransitions {

        @Test
        @DisplayName("Assessment transitions to Pilot when pilot-ready")
        void assessmentToPilot() {
            var assessment =
                    AdoptionPipelineEngine.assess("ReadyCorp", 20, true, true, 15, 99.95)
                            .orElseThrow();

            // Force an Assessment-phase assessment by building one manually
            var assessmentPhase =
                    new AdoptionAssessment(
                            "ReadyCorp",
                            new AdoptionPhase.Assessment(),
                            assessment.readiness(),
                            assessment.risks(),
                            assessment.recommendations());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessmentPhase).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Assessment.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Pilot.class);
            assertThat(transition.reason()).contains("60");
            assertThat(transition.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("Assessment transitions to Blocked when not pilot-ready")
        void assessmentToBlocked() {
            var lowReadiness = new ReadinessScore(30, 20, 20);
            var assessment =
                    new AdoptionAssessment(
                            "WeakCorp",
                            new AdoptionPhase.Assessment(),
                            lowReadiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Assessment.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Blocked.class);
        }

        @Test
        @DisplayName("Pilot transitions to Production when overall >= 75")
        void pilotToProduction() {
            var readiness = new ReadinessScore(90, 80, 80);
            var assessment =
                    new AdoptionAssessment(
                            "GrowingCorp",
                            new AdoptionPhase.Pilot(),
                            readiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Pilot.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Production.class);
            assertThat(transition.reason()).contains("75");
        }

        @Test
        @DisplayName("Pilot stays in Pilot when overall < 75")
        void pilotStaysInPilot() {
            var readiness = new ReadinessScore(70, 60, 60);
            var assessment =
                    new AdoptionAssessment(
                            "MiddleCorp",
                            new AdoptionPhase.Pilot(),
                            readiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Pilot.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Pilot.class);
        }

        @Test
        @DisplayName("Production transitions to Scale when overall >= 90")
        void productionToScale() {
            var readiness = new ReadinessScore(100, 90, 90);
            var assessment =
                    new AdoptionAssessment(
                            "ScaleCorp",
                            new AdoptionPhase.Production(),
                            readiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Production.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Scale.class);
        }

        @Test
        @DisplayName("Production stays in Production when overall < 90")
        void productionStaysInProduction() {
            var readiness = new ReadinessScore(90, 80, 80);
            var assessment =
                    new AdoptionAssessment(
                            "StableCorp",
                            new AdoptionPhase.Production(),
                            readiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Production.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Production.class);
        }

        @Test
        @DisplayName("Scale stays at Scale")
        void scaleStaysAtScale() {
            var readiness = new ReadinessScore(100, 100, 100);
            var assessment =
                    new AdoptionAssessment(
                            "MaxCorp",
                            new AdoptionPhase.Scale(),
                            readiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Scale.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Scale.class);
            assertThat(transition.reason()).contains("optimize");
        }

        @Test
        @DisplayName("Blocked transitions back to Assessment")
        void blockedToAssessment() {
            var readiness = new ReadinessScore(50, 50, 50);
            var assessment =
                    new AdoptionAssessment(
                            "BlockedCorp",
                            new AdoptionPhase.Blocked("insufficient budget"),
                            readiness,
                            java.util.List.of(),
                            java.util.List.of());

            var transition =
                    AdoptionPipelineEngine.recommendTransition(assessment).orElseThrow();

            assertThat(transition.from()).isInstanceOf(AdoptionPhase.Blocked.class);
            assertThat(transition.to()).isInstanceOf(AdoptionPhase.Assessment.class);
            assertThat(transition.reason()).contains("insufficient budget");
        }
    }

    // ── Risk generation thresholds ────────────────────────────────────────────

    @Nested
    @DisplayName("risk level thresholds")
    class RiskLevelThresholds {

        @Test
        @DisplayName("score >= 80 produces LOW risk")
        void lowRisk() {
            // Team score will be 30 + 30 + 40 = 100
            var assessment =
                    AdoptionPipelineEngine.assess("HighCorp", 20, true, true, 80, 99.95)
                            .orElseThrow();
            var teamRisk = findRisk(assessment, "Team readiness");

            assertThat(teamRisk.level()).isEqualTo(RiskLevel.LOW);
        }

        @Test
        @DisplayName("score 60-79 produces MEDIUM risk")
        void mediumRisk() {
            // Team score will be 30 + 0 + 40 = 70
            var assessment =
                    AdoptionPipelineEngine.assess("MediumCorp", 20, false, true, 10, 99.0)
                            .orElseThrow();
            var teamRisk = findRisk(assessment, "Team readiness");

            assertThat(teamRisk.level()).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("score 40-59 produces HIGH risk")
        void highRisk() {
            // Team score will be 30 + 0 + 10 = 40
            var assessment =
                    AdoptionPipelineEngine.assess("LowCorp", 5, false, false, 3, 95.0)
                            .orElseThrow();
            var teamRisk = findRisk(assessment, "Team readiness");

            assertThat(teamRisk.level()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        @DisplayName("score < 40 produces CRITICAL risk")
        void criticalRisk() {
            // Infra score will be 20 + 0 + 3 = 23
            var assessment =
                    AdoptionPipelineEngine.assess("CriticalCorp", 5, false, false, 3, 95.0)
                            .orElseThrow();
            var infraRisk = findRisk(assessment, "Infrastructure readiness");

            assertThat(infraRisk.level()).isEqualTo(RiskLevel.CRITICAL);
        }
    }

    // ── ReadinessScore record tests ───────────────────────────────────────────

    @Nested
    @DisplayName("ReadinessScore")
    class ReadinessScoreTests {

        @Test
        @DisplayName("weighted average calculation is correct")
        void weightedAverage() {
            var score = new ReadinessScore(100, 50, 50);

            // (100*0.4 + 50*0.3 + 50*0.3) = 40 + 15 + 15 = 70
            assertThat(score.overall()).isEqualTo(70);
        }

        @Test
        @DisplayName("isPilotReady returns true when overall >= 60")
        void pilotReadyThreshold() {
            var ready = new ReadinessScore(80, 60, 60);
            var notReady = new ReadinessScore(40, 40, 40);

            assertThat(ready.isPilotReady()).isTrue();
            assertThat(notReady.isPilotReady()).isFalse();
        }

        @Test
        @DisplayName("rejects scores outside 0-100 range")
        void rejectsInvalidScores() {
            assertThatThrownBy(() -> new ReadinessScore(-1, 50, 50))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ReadinessScore(50, 101, 50))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ReadinessScore(50, 50, -5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Input validation tests ────────────────────────────────────────────────

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        @DisplayName("returns Err for null enterprise name")
        void rejectsNullName() {
            var result = AdoptionPipelineEngine.assess(null, 10, false, false, 5, 99.0);

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("returns Err for negative team size")
        void rejectsNegativeTeamSize() {
            var result = AdoptionPipelineEngine.assess("Corp", -1, false, false, 5, 99.0);

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("returns Err for negative microservice count")
        void rejectsNegativeMicroserviceCount() {
            var result = AdoptionPipelineEngine.assess("Corp", 10, false, false, -1, 99.0);

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("returns Err for SLA outside 0-100")
        void rejectsInvalidSla() {
            var result = AdoptionPipelineEngine.assess("Corp", 10, false, false, 5, 101.0);

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("returns Err for null assessment in recommendTransition")
        void rejectsNullAssessment() {
            var result = AdoptionPipelineEngine.recommendTransition(null);

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("returns Ok for valid inputs")
        void acceptsValidInputs() {
            var result = AdoptionPipelineEngine.assess("ValidCorp", 10, true, true, 10, 99.9);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    // ── Blocked phase scenario ────────────────────────────────────────────────

    @Nested
    @DisplayName("very low readiness (Blocked phase)")
    class BlockedPhase {

        @Test
        @DisplayName("overall below 40 results in Blocked phase")
        void blockedPhase() {
            // 1 dev, no OTP, no Spring Boot, 0 microservices, 10.0% SLA
            // team: 30 + 0 + 2 = 32, infra: 20 + 0 + 0 = 20, codebase: 10 + 5 + 0 = 15
            // overall: (32*0.4 + 20*0.3 + 15*0.3) = 12.8 + 6 + 4.5 = 23
            var assessment =
                    AdoptionPipelineEngine.assess("FailCorp", 1, false, false, 0, 10.0)
                            .orElseThrow();

            assertThat(assessment.currentPhase()).isInstanceOf(AdoptionPhase.Blocked.class);
            assertThat(assessment.readiness().overall()).isLessThan(40);
        }
    }

    // ── AdoptionPhase sealed type tests ───────────────────────────────────────

    @Nested
    @DisplayName("AdoptionPhase sealed types")
    class AdoptionPhaseTypes {

        @Test
        @DisplayName("Blocked record requires non-null reason")
        void blockedRejectsNull() {
            assertThatThrownBy(() -> new AdoptionPhase.Blocked(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Blocked record preserves reason")
        void blockedPreservesReason() {
            var blocked = new AdoptionPhase.Blocked("budget constraints");

            assertThat(blocked.reason()).isEqualTo("budget constraints");
        }

        @Test
        @DisplayName("all phases implement AdoptionPhase")
        void allPhasesImplementAdoptionPhase() {
            assertThat(new AdoptionPhase.Assessment()).isInstanceOf(AdoptionPhase.class);
            assertThat(new AdoptionPhase.Pilot()).isInstanceOf(AdoptionPhase.class);
            assertThat(new AdoptionPhase.Production()).isInstanceOf(AdoptionPhase.class);
            assertThat(new AdoptionPhase.Scale()).isInstanceOf(AdoptionPhase.class);
            assertThat(new AdoptionPhase.Blocked("reason")).isInstanceOf(AdoptionPhase.class);
        }
    }

    // ── MigrationRisk validation ──────────────────────────────────────────────

    @Nested
    @DisplayName("MigrationRisk validation")
    class MigrationRiskValidation {

        @Test
        @DisplayName("rejects null name")
        void rejectsNullName() {
            assertThatThrownBy(() -> new MigrationRisk(null, RiskLevel.LOW, "mitigation"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null level")
        void rejectsNullLevel() {
            assertThatThrownBy(() -> new MigrationRisk("risk", null, "mitigation"))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null mitigation")
        void rejectsNullMitigation() {
            assertThatThrownBy(() -> new MigrationRisk("risk", RiskLevel.LOW, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── PhaseTransition validation ────────────────────────────────────────────

    @Nested
    @DisplayName("PhaseTransition validation")
    class PhaseTransitionValidation {

        @Test
        @DisplayName("rejects null fields")
        void rejectsNullFields() {
            var phase = new AdoptionPhase.Assessment();
            var now = java.time.Instant.now();

            assertThatThrownBy(() -> new PhaseTransition(null, phase, "reason", now))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PhaseTransition(phase, null, "reason", now))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PhaseTransition(phase, phase, null, now))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new PhaseTransition(phase, phase, "reason", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static MigrationRisk findRisk(AdoptionAssessment assessment, String name) {
        return assessment.risks().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Risk not found: " + name));
    }
}
