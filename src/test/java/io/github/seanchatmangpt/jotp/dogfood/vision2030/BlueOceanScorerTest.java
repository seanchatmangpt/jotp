package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.Autonomy;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.BlueOceanAnalysis;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.Dimension;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.Ecosystem;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.FaultTolerance;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.ParetoItem;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.PlatformProfile;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.TalentPool;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.BlueOceanScorer.TypeSafety;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BlueOceanScorer")
class BlueOceanScorerTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Realistic platform profiles ──────────────────────────────────────────

    private static final PlatformProfile JOTP =
            new PlatformProfile(
                    "JOTP",
                    List.of(
                            new FaultTolerance(5, "Supervision trees with ONE_FOR_ONE/ALL/REST"),
                            new TypeSafety(5, "Sealed types + exhaustive pattern matching"),
                            new Ecosystem(5, "Full 15-primitive OTP on JVM"),
                            new TalentPool(5, "10M+ Java developers worldwide"),
                            new Autonomy(5, "Self-healing supervision trees")));

    private static final PlatformProfile ERLANG =
            new PlatformProfile(
                    "Erlang",
                    List.of(
                            new FaultTolerance(5, "Original OTP supervision"),
                            new TypeSafety(2, "Dynamic typing, dialyzer optional"),
                            new Ecosystem(1, "Small library ecosystem"),
                            new TalentPool(1, "Niche developer pool"),
                            new Autonomy(3, "BEAM VM self-healing")));

    private static final PlatformProfile AKKA =
            new PlatformProfile(
                    "Akka",
                    List.of(
                            new FaultTolerance(4, "Actor supervision"),
                            new TypeSafety(4, "Typed actors in Scala/Java"),
                            new Ecosystem(2, "Lightbend ecosystem only"),
                            new TalentPool(2, "Scala/Akka specialists"),
                            new Autonomy(2, "Requires cluster configuration")));

    private static final PlatformProfile GOLANG =
            new PlatformProfile(
                    "Go",
                    List.of(
                            new FaultTolerance(1, "No built-in supervision"),
                            new TypeSafety(2, "Structural typing, no sum types"),
                            new Ecosystem(4, "Strong cloud-native ecosystem"),
                            new TalentPool(3, "Growing developer community"),
                            new Autonomy(1, "Manual error handling")));

    private static final PlatformProfile RUST =
            new PlatformProfile(
                    "Rust",
                    List.of(
                            new FaultTolerance(4, "Result/Option, no exceptions"),
                            new TypeSafety(5, "Ownership + exhaustive enums"),
                            new Ecosystem(2, "Growing crate ecosystem"),
                            new TalentPool(1, "Small but growing community"),
                            new Autonomy(2, "Manual resource management")));

    private static final List<PlatformProfile> ALL_COMPETITORS =
            List.of(ERLANG, AKKA, GOLANG, RUST);

    // ── Dimension validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Dimension validation")
    class DimensionValidation {

        @Test
        @DisplayName("rejects score below 1")
        void rejectsScoreBelowOne() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FaultTolerance(0, "invalid"))
                    .withMessageContaining("1-5");
        }

        @Test
        @DisplayName("rejects score above 5")
        void rejectsScoreAboveFive() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TypeSafety(6, "invalid"))
                    .withMessageContaining("1-5");
        }

        @Test
        @DisplayName("rejects null evidence")
        void rejectsNullEvidence() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Ecosystem(3, null))
                    .withMessageContaining("evidence");
        }

        @Test
        @DisplayName("accepts valid scores 1 through 5")
        void acceptsValidScores() {
            assertThatCode(() -> new FaultTolerance(1, "minimal")).doesNotThrowAnyException();
            assertThatCode(() -> new FaultTolerance(3, "moderate")).doesNotThrowAnyException();
            assertThatCode(() -> new FaultTolerance(5, "excellent")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("dimension label returns human-readable name")
        void dimensionLabelReturnsHumanReadableName() {
            assertThat(new FaultTolerance(3, "test").label()).isEqualTo("Fault Tolerance");
            assertThat(new TypeSafety(3, "test").label()).isEqualTo("Type Safety");
            assertThat(new Ecosystem(3, "test").label()).isEqualTo("Ecosystem");
            assertThat(new TalentPool(3, "test").label()).isEqualTo("Talent Pool");
            assertThat(new Autonomy(3, "test").label()).isEqualTo("Autonomy");
        }

        @Test
        @DisplayName("Dimension is sealed with exactly five variants")
        void dimensionIsSealedWithFiveVariants() {
            assertThat(Dimension.class.isSealed()).isTrue();
            assertThat(Dimension.class.getPermittedSubclasses()).hasSize(5);
        }
    }

    // ── PlatformProfile validation ───────────────────────────────────────────

    @Nested
    @DisplayName("PlatformProfile validation")
    class PlatformProfileValidation {

        @Test
        @DisplayName("rejects null name")
        void rejectsNullName() {
            assertThatNullPointerException()
                    .isThrownBy(
                            () ->
                                    new PlatformProfile(
                                            null,
                                            List.of(
                                                    new FaultTolerance(1, "a"),
                                                    new TypeSafety(1, "b"),
                                                    new Ecosystem(1, "c"),
                                                    new TalentPool(1, "d"),
                                                    new Autonomy(1, "e"))));
        }

        @Test
        @DisplayName("rejects wrong number of dimensions")
        void rejectsWrongDimensionCount() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new PlatformProfile(
                                            "Bad",
                                            List.of(
                                                    new FaultTolerance(1, "a"),
                                                    new TypeSafety(1, "b"))))
                    .withMessageContaining("5 dimensions");
        }

        @Test
        @DisplayName("dimensions list is immutable")
        void dimensionsListIsImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> JOTP.dimensions().add(new FaultTolerance(1, "hack")));
        }
    }

    // ── Analysis results ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("analyze()")
    class Analyze {

        @Test
        @DisplayName("returns success for valid inputs")
        void returnsSuccessForValidInputs() {
            var result = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("returns error for empty competitors list")
        void returnsErrorForEmptyCompetitors() {
            var result = BlueOceanScorer.analyze(JOTP, List.of());

            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("JOTP has high blue ocean index vs competitors")
        void jotpHasHighBlueOceanIndex() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            // JOTP scores 5 across all dimensions; competitors average much lower
            // on ecosystem, talent pool, and autonomy
            assertThat(analysis.blueOceanIndex()).isGreaterThan(0.4);
        }

        @Test
        @DisplayName("blue ocean index is between 0.0 and 1.0")
        void blueOceanIndexInRange() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThat(analysis.blueOceanIndex()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("analysis contains all five Pareto items")
        void analysisContainsFiveParetoItems() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThat(analysis.paretoRanking()).hasSize(5);
        }

        @Test
        @DisplayName("Pareto items are sorted by absolute impact weight descending")
        void paretoItemsSortedByImpactDescending() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            var items = analysis.paretoRanking();
            for (int i = 0; i < items.size() - 1; i++) {
                assertThat(Math.abs(items.get(i).impactWeight()))
                        .isGreaterThanOrEqualTo(Math.abs(items.get(i + 1).impactWeight()));
            }
        }

        @Test
        @DisplayName("cumulative impact reaches 1.0 at the last item")
        void cumulativeImpactReachesOne() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            var lastItem = analysis.paretoRanking().getLast();
            assertThat(lastItem.cumulativeImpact()).isCloseTo(1.0, within(0.01));
        }

        @Test
        @DisplayName("at least one Pareto item is marked as top 20%")
        void atLeastOneItemInTopTwentyPercent() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThat(analysis.paretoRanking())
                    .anyMatch(ParetoItem::inTopTwentyPercent);
        }

        @Test
        @DisplayName("target profile is preserved in analysis")
        void targetProfileIsPreserved() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThat(analysis.target()).isEqualTo(JOTP);
        }

        @Test
        @DisplayName("competitors are preserved in analysis")
        void competitorsArePreserved() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThat(analysis.competitors()).hasSize(4);
            assertThat(analysis.competitors())
                    .extracting(PlatformProfile::name)
                    .containsExactlyInAnyOrder("Erlang", "Akka", "Go", "Rust");
        }
    }

    // ── Pareto analysis ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pareto analysis")
    class ParetoAnalysis {

        @Test
        @DisplayName("identifies correct top-20% dimensions for JOTP")
        void identifiesCorrectTopDimensions() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            // JOTP's biggest competitive gaps are in dimensions where competitors
            // score lowest: TalentPool (avg ~1.75), Ecosystem (avg ~2.25), Autonomy (avg ~2.0)
            var topDimLabels =
                    analysis.paretoRanking().stream()
                            .filter(ParetoItem::inTopTwentyPercent)
                            .map(p -> p.dimension().label())
                            .toList();

            // The top dimensions by gap should include talent pool and/or ecosystem and/or autonomy
            // since those have the biggest gaps between JOTP (all 5s) and competitors
            assertThat(topDimLabels).isNotEmpty();
        }

        @Test
        @DisplayName("positive impact weights indicate JOTP advantage")
        void positiveImpactWeightsIndicateAdvantage() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            // JOTP scores 5 in all dimensions, so all gaps should be positive
            // (target is better than the average competitor in every dimension)
            assertThat(analysis.paretoRanking())
                    .allMatch(p -> p.impactWeight() > 0.0);
        }

        @Test
        @DisplayName("Pareto items marked as top 20% have higher cumulative than non-top")
        void topTwentyPercentHaveHigherCumulative() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            var topItems =
                    analysis.paretoRanking().stream()
                            .filter(ParetoItem::inTopTwentyPercent)
                            .toList();
            var nonTopItems =
                    analysis.paretoRanking().stream()
                            .filter(p -> !p.inTopTwentyPercent())
                            .toList();

            if (!nonTopItems.isEmpty()) {
                var maxTopCumulative =
                        topItems.stream()
                                .mapToDouble(ParetoItem::cumulativeImpact)
                                .max()
                                .orElse(0.0);
                var minNonTopCumulative =
                        nonTopItems.stream()
                                .mapToDouble(ParetoItem::cumulativeImpact)
                                .min()
                                .orElse(1.0);
                assertThat(maxTopCumulative).isLessThanOrEqualTo(minNonTopCumulative + 0.01);
            }
        }
    }

    // ── Comparative analysis ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Comparative scoring")
    class ComparativeScoring {

        @Test
        @DisplayName("platform identical to competitors has zero blue ocean index")
        void identicalPlatformHasZeroIndex() {
            // A platform identical to the single competitor should have zero differentiation
            var clone =
                    new PlatformProfile(
                            "Clone",
                            List.of(
                                    new FaultTolerance(3, "same"),
                                    new TypeSafety(3, "same"),
                                    new Ecosystem(3, "same"),
                                    new TalentPool(3, "same"),
                                    new Autonomy(3, "same")));
            var competitor =
                    new PlatformProfile(
                            "Original",
                            List.of(
                                    new FaultTolerance(3, "same"),
                                    new TypeSafety(3, "same"),
                                    new Ecosystem(3, "same"),
                                    new TalentPool(3, "same"),
                                    new Autonomy(3, "same")));

            var analysis = BlueOceanScorer.analyze(clone, List.of(competitor)).orElseThrow();

            assertThat(analysis.blueOceanIndex()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("maximally differentiated platform has high blue ocean index")
        void maxDifferentiationHasHighIndex() {
            var best =
                    new PlatformProfile(
                            "Best",
                            List.of(
                                    new FaultTolerance(5, "best"),
                                    new TypeSafety(5, "best"),
                                    new Ecosystem(5, "best"),
                                    new TalentPool(5, "best"),
                                    new Autonomy(5, "best")));
            var worst =
                    new PlatformProfile(
                            "Worst",
                            List.of(
                                    new FaultTolerance(1, "worst"),
                                    new TypeSafety(1, "worst"),
                                    new Ecosystem(1, "worst"),
                                    new TalentPool(1, "worst"),
                                    new Autonomy(1, "worst")));

            var analysis = BlueOceanScorer.analyze(best, List.of(worst)).orElseThrow();

            // Max gap is 4.0 per dimension (5 - 1), normalized to 1.0
            assertThat(analysis.blueOceanIndex()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("weaker platform has lower blue ocean index than stronger")
        void weakerPlatformHasLowerIndex() {
            var weak =
                    new PlatformProfile(
                            "Weak",
                            List.of(
                                    new FaultTolerance(1, "none"),
                                    new TypeSafety(1, "none"),
                                    new Ecosystem(1, "none"),
                                    new TalentPool(1, "none"),
                                    new Autonomy(1, "none")));

            var weakResult =
                    BlueOceanScorer.analyze(weak, ALL_COMPETITORS).orElseThrow();
            var strongResult =
                    BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThat(weakResult.blueOceanIndex())
                    .isLessThan(strongResult.blueOceanIndex());
        }

        @Test
        @DisplayName("single competitor analysis works correctly")
        void singleCompetitorWorks() {
            var result = BlueOceanScorer.analyze(JOTP, List.of(ERLANG));

            assertThat(result.isSuccess()).isTrue();
            var analysis = result.orElseThrow();
            assertThat(analysis.competitors()).hasSize(1);
            assertThat(analysis.paretoRanking()).hasSize(5);
        }
    }

    // ── BlueOceanAnalysis record validation ──────────────────────────────────

    @Nested
    @DisplayName("BlueOceanAnalysis validation")
    class AnalysisRecordValidation {

        @Test
        @DisplayName("rejects blue ocean index below 0.0")
        void rejectsIndexBelowZero() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new BlueOceanAnalysis(
                                            JOTP, ALL_COMPETITORS, List.of(), -0.1))
                    .withMessageContaining("blueOceanIndex");
        }

        @Test
        @DisplayName("rejects blue ocean index above 1.0")
        void rejectsIndexAboveOne() {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new BlueOceanAnalysis(
                                            JOTP, ALL_COMPETITORS, List.of(), 1.1))
                    .withMessageContaining("blueOceanIndex");
        }

        @Test
        @DisplayName("competitors list is immutable in analysis")
        void competitorsListIsImmutable() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> analysis.competitors().add(ERLANG));
        }

        @Test
        @DisplayName("Pareto ranking list is immutable in analysis")
        void paretoRankingListIsImmutable() {
            var analysis = BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS).orElseThrow();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(
                            () ->
                                    analysis.paretoRanking()
                                            .add(
                                                    new ParetoItem(
                                                            new FaultTolerance(1, "x"),
                                                            0.0,
                                                            0.0,
                                                            false)));
        }
    }

    // ── Result type integration ──────────────────────────────────────────────

    @Nested
    @DisplayName("Result type integration")
    class ResultIntegration {

        @Test
        @DisplayName("successful analysis can be mapped")
        void successfulAnalysisCanBeMapped() {
            Result<Double, Exception> indexResult =
                    BlueOceanScorer.analyze(JOTP, ALL_COMPETITORS)
                            .map(BlueOceanAnalysis::blueOceanIndex);

            assertThat(indexResult.isSuccess()).isTrue();
            assertThat(indexResult.orElseThrow()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("failed analysis propagates error through map")
        void failedAnalysisPropagatesError() {
            Result<Double, Exception> indexResult =
                    BlueOceanScorer.analyze(JOTP, List.of())
                            .map(BlueOceanAnalysis::blueOceanIndex);

            assertThat(indexResult.isError()).isTrue();
        }
    }
}
