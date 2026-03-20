package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.RoadmapPrioritizer.PrioritizedRoadmap;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.RoadmapPrioritizer.RankedItem;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.RoadmapPrioritizer.RoadmapItem;
import io.github.seanchatmangpt.jotp.dogfood.vision2030.RoadmapPrioritizer.Strategy;
import java.util.List;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RoadmapPrioritizer")
class RoadmapPrioritizerTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Test data (JOTP Vision 2030 roadmap) ─────────────────────────────────

    private static final RoadmapItem JAVA26_LAUNCH =
            new RoadmapItem("java26-launch", "Java 26 Launch", 2026, 10.0, 3.0, List.of());

    private static final RoadmapItem SPRING_BOOT_INTEGRATION =
            new RoadmapItem(
                    "spring-boot-integration", "Spring Boot Integration", 2026, 8.0, 4.0,
                    List.of());

    private static final RoadmapItem FORTUNE500_PILOTS =
            new RoadmapItem("fortune500-pilots", "Fortune 500 Pilots", 2026, 9.0, 7.0, List.of());

    private static final RoadmapItem AGENT_DISCOVERY =
            new RoadmapItem(
                    "agent-discovery", "Agent Discovery", 2027, 7.0, 6.0,
                    List.of("java26-launch"));

    private static final RoadmapItem ML_RECOVERY =
            new RoadmapItem(
                    "ml-recovery", "ML Recovery", 2027, 8.0, 8.0, List.of("agent-discovery"));

    private static final RoadmapItem GRPC_BRIDGE =
            new RoadmapItem("grpc-bridge", "gRPC Bridge", 2028, 6.0, 9.0, List.of());

    private static final RoadmapItem CONSENSUS_PROTOCOLS =
            new RoadmapItem(
                    "consensus-protocols", "Consensus Protocols", 2029, 5.0, 8.0, List.of());

    private static final RoadmapItem MARKET_LEADERSHIP =
            new RoadmapItem(
                    "market-leadership", "Market Leadership", 2030, 10.0, 5.0,
                    List.of("ml-recovery"));

    private static List<RoadmapItem> allItems() {
        return List.of(
                JAVA26_LAUNCH,
                SPRING_BOOT_INTEGRATION,
                FORTUNE500_PILOTS,
                AGENT_DISCOVERY,
                ML_RECOVERY,
                GRPC_BRIDGE,
                CONSENSUS_PROTOCOLS,
                MARKET_LEADERSHIP);
    }

    // ── Default strategy (RoiFirst) ──────────────────────────────────────────

    @Nested
    @DisplayName("default strategy (RoiFirst)")
    class DefaultStrategy {

        @Test
        @DisplayName("prioritizes items by ROI descending")
        void prioritizesByRoiDescending() {
            var result = RoadmapPrioritizer.prioritize(allItems());

            assertThat(result).isInstanceOf(Result.Ok.class);
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            // Verify items are sorted by ROI descending
            var rois = roadmap.allItems().stream().map(RankedItem::roi).toList();
            for (int i = 0; i < rois.size() - 1; i++) {
                assertThat(rois.get(i))
                        .as("ROI at index %d should be >= ROI at index %d", i, i + 1)
                        .isGreaterThanOrEqualTo(rois.get(i + 1));
            }
        }

        @Test
        @DisplayName("java26-launch has highest ROI (10/3 = 3.33)")
        void java26LaunchHasHighestRoi() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            assertThat(roadmap.allItems().get(0).item().id()).isEqualTo("java26-launch");
            assertThat(roadmap.allItems().get(0).roi()).isCloseTo(10.0 / 3.0, within(0.001));
        }

        @Test
        @DisplayName("uses default RoiFirst when no strategy specified")
        void usesDefaultRoiFirst() {
            var defaultResult = RoadmapPrioritizer.prioritize(allItems());
            var explicitResult =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.RoiFirst());

            var defaultRoadmap =
                    ((Result.Ok<PrioritizedRoadmap, Exception>) defaultResult).value();
            var explicitRoadmap =
                    ((Result.Ok<PrioritizedRoadmap, Exception>) explicitResult).value();

            assertThat(defaultRoadmap.allItems().stream().map(r -> r.item().id()).toList())
                    .isEqualTo(
                            explicitRoadmap.allItems().stream()
                                    .map(r -> r.item().id())
                                    .toList());
        }
    }

    // ── Pareto analysis ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pareto analysis")
    class ParetoAnalysis {

        @Test
        @DisplayName("pareto set contains highest-ROI items")
        void paretoSetContainsHighestRoiItems() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            // Pareto set should be a prefix of the ranked list
            var paretoIds =
                    roadmap.paretoSet().stream().map(r -> r.item().id()).toList();
            var allIds =
                    roadmap.allItems().stream().map(r -> r.item().id()).toList();

            assertThat(allIds.subList(0, paretoIds.size())).isEqualTo(paretoIds);

            // All pareto items should have inParetoSet = true
            assertThat(roadmap.paretoSet())
                    .allSatisfy(item -> assertThat(item.inParetoSet()).isTrue());
        }

        @Test
        @DisplayName("cumulative value of pareto set reaches at least 0.80")
        void cumulativeValueReachesThreshold() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var lastPareto = roadmap.paretoSet().get(roadmap.paretoSet().size() - 1);
            assertThat(lastPareto.cumulativeValue()).isGreaterThanOrEqualTo(0.80);
        }

        @Test
        @DisplayName("pareto threshold is 0.80")
        void paretoThresholdIs80Percent() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            assertThat(roadmap.paretoThreshold()).isEqualTo(0.80);
        }

        @Test
        @DisplayName("normalized values sum to approximately 1.0")
        void normalizedValuesSumToOne() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            double sum =
                    roadmap.allItems().stream().mapToDouble(RankedItem::normalizedValue).sum();
            assertThat(sum).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("items outside pareto set have inParetoSet = false")
        void nonParetoItemsMarkedFalse() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var nonPareto =
                    roadmap.allItems().stream()
                            .filter(r -> !r.inParetoSet())
                            .toList();
            assertThat(nonPareto).isNotEmpty();
            assertThat(nonPareto).allSatisfy(item -> assertThat(item.inParetoSet()).isFalse());
        }

        @Test
        @DisplayName("summary describes pareto set size")
        void summaryDescribesParetoSet() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            assertThat(roadmap.summary())
                    .contains("of 8 items")
                    .contains("deliver 80% of strategic value");
        }

        @Test
        @DisplayName("cumulative values are monotonically increasing")
        void cumulativeValuesIncreasing() {
            var result = RoadmapPrioritizer.prioritize(allItems());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var cumulatives =
                    roadmap.allItems().stream().map(RankedItem::cumulativeValue).toList();
            for (int i = 0; i < cumulatives.size() - 1; i++) {
                assertThat(cumulatives.get(i))
                        .as("cumulative at %d should be <= cumulative at %d", i, i + 1)
                        .isLessThanOrEqualTo(cumulatives.get(i + 1));
            }
        }
    }

    // ── ImpactFirst strategy ─────────────────────────────────────────────────

    @Nested
    @DisplayName("ImpactFirst strategy")
    class ImpactFirstStrategy {

        @Test
        @DisplayName("sorts by impact score descending")
        void sortsByImpactDescending() {
            var result =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.ImpactFirst());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var impacts =
                    roadmap.allItems().stream()
                            .map(r -> r.item().impactScore())
                            .toList();
            for (int i = 0; i < impacts.size() - 1; i++) {
                assertThat(impacts.get(i))
                        .as("impact at index %d should be >= impact at index %d", i, i + 1)
                        .isGreaterThanOrEqualTo(impacts.get(i + 1));
            }
        }

        @Test
        @DisplayName("java26-launch and market-leadership tie for top impact")
        void highestImpactItems() {
            var result =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.ImpactFirst());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var topTwo =
                    roadmap.allItems().stream()
                            .limit(2)
                            .map(r -> r.item().id())
                            .toList();
            assertThat(topTwo).containsExactlyInAnyOrder("java26-launch", "market-leadership");
        }
    }

    // ── DependencyAware strategy ─────────────────────────────────────────────

    @Nested
    @DisplayName("DependencyAware strategy")
    class DependencyAwareStrategy {

        @Test
        @DisplayName("respects dependency ordering")
        void respectsDependencyOrdering() {
            var result =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.DependencyAware());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var ids = roadmap.allItems().stream().map(r -> r.item().id()).toList();

            // java26-launch must come before agent-discovery
            assertThat(ids.indexOf("java26-launch"))
                    .isLessThan(ids.indexOf("agent-discovery"));

            // agent-discovery must come before ml-recovery
            assertThat(ids.indexOf("agent-discovery"))
                    .isLessThan(ids.indexOf("ml-recovery"));

            // ml-recovery must come before market-leadership
            assertThat(ids.indexOf("ml-recovery"))
                    .isLessThan(ids.indexOf("market-leadership"));
        }

        @Test
        @DisplayName("sorts by ROI within dependency layers")
        void sortsByRoiWithinLayers() {
            var result =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.DependencyAware());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            var ids = roadmap.allItems().stream().map(r -> r.item().id()).toList();

            // First layer has no dependencies: java26-launch, spring-boot-integration,
            // fortune500-pilots, grpc-bridge, consensus-protocols
            // java26-launch ROI = 10/3 = 3.33 (highest in first layer)
            assertThat(ids.get(0)).isEqualTo("java26-launch");
        }

        @Test
        @DisplayName("detects circular dependencies and returns error")
        void detectsCircularDependencies() {
            var cycleItems =
                    List.of(
                            new RoadmapItem("a", "Item A", 2026, 5.0, 3.0, List.of("b")),
                            new RoadmapItem("b", "Item B", 2026, 5.0, 3.0, List.of("c")),
                            new RoadmapItem("c", "Item C", 2026, 5.0, 3.0, List.of("a")));

            var result =
                    RoadmapPrioritizer.prioritize(cycleItems, new Strategy.DependencyAware());

            assertThat(result).isInstanceOf(Result.Err.class);
            var error = ((Result.Err<PrioritizedRoadmap, Exception>) result).error();
            assertThat(error).isInstanceOf(IllegalArgumentException.class);
            assertThat(error.getMessage()).containsIgnoringCase("circular dependency");
        }

        @Test
        @DisplayName("produces valid pareto set with dependency ordering")
        void paretoSetWithDependencies() {
            var result =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.DependencyAware());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            assertThat(roadmap.paretoSet()).isNotEmpty();
            assertThat(roadmap.paretoSet())
                    .allSatisfy(item -> assertThat(item.inParetoSet()).isTrue());

            var lastPareto = roadmap.paretoSet().get(roadmap.paretoSet().size() - 1);
            assertThat(lastPareto.cumulativeValue()).isGreaterThanOrEqualTo(0.80);
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects empty item list")
        void rejectsEmptyList() {
            var result = RoadmapPrioritizer.prioritize(List.of());

            assertThat(result).isInstanceOf(Result.Err.class);
            var error = ((Result.Err<PrioritizedRoadmap, Exception>) result).error();
            assertThat(error.getMessage()).contains("must not be empty");
        }

        @Test
        @DisplayName("rejects impact score below 1.0")
        void rejectsLowImpactScore() {
            var items =
                    List.of(new RoadmapItem("bad", "Bad Item", 2026, 0.5, 5.0, List.of()));
            var result = RoadmapPrioritizer.prioritize(items);

            assertThat(result).isInstanceOf(Result.Err.class);
            var error = ((Result.Err<PrioritizedRoadmap, Exception>) result).error();
            assertThat(error.getMessage()).contains("impactScore must be 1.0-10.0");
        }

        @Test
        @DisplayName("rejects impact score above 10.0")
        void rejectsHighImpactScore() {
            var items =
                    List.of(new RoadmapItem("bad", "Bad Item", 2026, 11.0, 5.0, List.of()));
            var result = RoadmapPrioritizer.prioritize(items);

            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("rejects effort score below 1.0")
        void rejectsLowEffortScore() {
            var items =
                    List.of(new RoadmapItem("bad", "Bad Item", 2026, 5.0, 0.0, List.of()));
            var result = RoadmapPrioritizer.prioritize(items);

            assertThat(result).isInstanceOf(Result.Err.class);
            var error = ((Result.Err<PrioritizedRoadmap, Exception>) result).error();
            assertThat(error.getMessage()).contains("effortScore must be 1.0-10.0");
        }

        @Test
        @DisplayName("rejects effort score above 10.0")
        void rejectsHighEffortScore() {
            var items =
                    List.of(new RoadmapItem("bad", "Bad Item", 2026, 5.0, 11.0, List.of()));
            var result = RoadmapPrioritizer.prioritize(items);

            assertThat(result).isInstanceOf(Result.Err.class);
        }

        @Test
        @DisplayName("rejects unknown dependency reference")
        void rejectsUnknownDependency() {
            var items =
                    List.of(
                            new RoadmapItem(
                                    "item1", "Item 1", 2026, 5.0, 3.0,
                                    List.of("nonexistent")));
            var result = RoadmapPrioritizer.prioritize(items);

            assertThat(result).isInstanceOf(Result.Err.class);
            var error = ((Result.Err<PrioritizedRoadmap, Exception>) result).error();
            assertThat(error.getMessage()).contains("unknown item");
        }

        @Test
        @DisplayName("rejects null items list")
        void rejectsNullItems() {
            assertThatThrownBy(() -> RoadmapPrioritizer.prioritize(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects null strategy")
        void rejectsNullStrategy() {
            assertThatThrownBy(() -> RoadmapPrioritizer.prioritize(allItems(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ── Record invariants ────────────────────────────────────────────────────

    @Nested
    @DisplayName("record invariants")
    class RecordInvariants {

        @Test
        @DisplayName("RoadmapItem rejects null id")
        void roadmapItemRejectsNullId() {
            assertThatThrownBy(
                            () -> new RoadmapItem(null, "Title", 2026, 5.0, 3.0, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("RoadmapItem rejects null title")
        void roadmapItemRejectsNullTitle() {
            assertThatThrownBy(
                            () -> new RoadmapItem("id", null, 2026, 5.0, 3.0, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("RoadmapItem rejects null dependencies")
        void roadmapItemRejectsNullDependencies() {
            assertThatThrownBy(
                            () -> new RoadmapItem("id", "Title", 2026, 5.0, 3.0, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("RoadmapItem defensively copies dependencies")
        void roadmapItemCopiesDependencies() {
            var deps = new java.util.ArrayList<>(List.of("dep1"));
            var item = new RoadmapItem("id", "Title", 2026, 5.0, 3.0, deps);
            deps.add("dep2");
            assertThat(item.dependencies()).containsExactly("dep1");
        }

        @Test
        @DisplayName("RankedItem rejects null item")
        void rankedItemRejectsNullItem() {
            assertThatThrownBy(() -> new RankedItem(null, 1.0, 0.5, 0.5, true))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("PrioritizedRoadmap defensively copies lists")
        void prioritizedRoadmapCopiesLists() {
            var ranked = new RankedItem(JAVA26_LAUNCH, 3.33, 1.0, 1.0, true);
            var items = new java.util.ArrayList<>(List.of(ranked));
            var pareto = new java.util.ArrayList<>(List.of(ranked));
            var roadmap = new PrioritizedRoadmap(items, pareto, 0.80, "test summary");
            items.add(ranked);
            pareto.add(ranked);
            assertThat(roadmap.allItems()).hasSize(1);
            assertThat(roadmap.paretoSet()).hasSize(1);
        }
    }

    // ── Single item edge case ────────────────────────────────────────────────

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("single item is always in pareto set")
        void singleItemAlwaysInParetoSet() {
            var result = RoadmapPrioritizer.prioritize(List.of(JAVA26_LAUNCH));
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            assertThat(roadmap.allItems()).hasSize(1);
            assertThat(roadmap.paretoSet()).hasSize(1);
            assertThat(roadmap.paretoSet().get(0).inParetoSet()).isTrue();
            assertThat(roadmap.paretoSet().get(0).normalizedValue()).isCloseTo(1.0, within(0.001));
            assertThat(roadmap.paretoSet().get(0).cumulativeValue()).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("all strategies produce same number of items")
        void allStrategiesProduceSameItemCount() {
            var roiResult =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.RoiFirst());
            var impactResult =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.ImpactFirst());
            var depResult =
                    RoadmapPrioritizer.prioritize(allItems(), new Strategy.DependencyAware());

            var roiRoadmap =
                    ((Result.Ok<PrioritizedRoadmap, Exception>) roiResult).value();
            var impactRoadmap =
                    ((Result.Ok<PrioritizedRoadmap, Exception>) impactResult).value();
            var depRoadmap =
                    ((Result.Ok<PrioritizedRoadmap, Exception>) depResult).value();

            assertThat(roiRoadmap.allItems()).hasSameSizeAs(impactRoadmap.allItems());
            assertThat(roiRoadmap.allItems()).hasSameSizeAs(depRoadmap.allItems());
        }

        @Test
        @DisplayName("two items with no dependencies produce valid ordering")
        void twoItemsNoDependencies() {
            var items =
                    List.of(
                            new RoadmapItem("low", "Low ROI", 2026, 2.0, 8.0, List.of()),
                            new RoadmapItem("high", "High ROI", 2026, 9.0, 1.0, List.of()));

            var result = RoadmapPrioritizer.prioritize(items, new Strategy.RoiFirst());
            var roadmap = ((Result.Ok<PrioritizedRoadmap, Exception>) result).value();

            assertThat(roadmap.allItems().get(0).item().id()).isEqualTo("high");
            assertThat(roadmap.allItems().get(1).item().id()).isEqualTo("low");
        }
    }
}
