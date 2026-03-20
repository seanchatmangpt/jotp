package io.github.seanchatmangpt.jotp.dogfood.vision2030;

import io.github.seanchatmangpt.jotp.Result;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 80/20 Roadmap Prioritization Engine — scores roadmap items by impact vs. effort, ranks by ROI,
 * and identifies the 20% of items delivering 80% of strategic value using Pareto analysis.
 *
 * <p>Supports three ranking strategies:
 *
 * <ul>
 *   <li>{@link Strategy.ImpactFirst} — sort by raw impact score descending
 *   <li>{@link Strategy.RoiFirst} — sort by ROI (impact/effort) descending (default)
 *   <li>{@link Strategy.DependencyAware} — topological sort respecting dependencies, then by ROI
 *       within each layer
 * </ul>
 *
 * <pre>{@code
 * var items = List.of(
 *     new RoadmapPrioritizer.RoadmapItem("java26", "Java 26 Launch", 2026, 10.0, 3.0, List.of()),
 *     new RoadmapPrioritizer.RoadmapItem("spring", "Spring Integration", 2026, 8.0, 4.0, List.of())
 * );
 * var result = RoadmapPrioritizer.prioritize(items, new RoadmapPrioritizer.Strategy.RoiFirst());
 * switch (result) {
 *     case Result.Ok(var roadmap) -> System.out.println(roadmap.summary());
 *     case Result.Err(var error) -> System.err.println(error.getMessage());
 * }
 * }</pre>
 */
public final class RoadmapPrioritizer {

    private RoadmapPrioritizer() {}

    // ── Domain types ─────────────────────────────────────────────────────────

    /**
     * A roadmap entry with impact and effort scores.
     *
     * @param id unique identifier for the roadmap item
     * @param title human-readable title
     * @param year target delivery year
     * @param impactScore strategic impact score (1.0–10.0)
     * @param effortScore implementation effort score (1.0–10.0)
     * @param dependencies list of item IDs this item depends on
     */
    public record RoadmapItem(
            String id,
            String title,
            int year,
            double impactScore,
            double effortScore,
            List<String> dependencies) {

        public RoadmapItem {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(dependencies, "dependencies must not be null");
            dependencies = List.copyOf(dependencies);
        }
    }

    /**
     * A roadmap item enriched with ROI, normalized value, cumulative value, and Pareto membership.
     *
     * @param item the original roadmap item
     * @param roi return on investment (impactScore / effortScore)
     * @param normalizedValue this item's ROI as a fraction of total ROI across all items
     * @param cumulativeValue running sum of normalized values up to and including this item
     * @param inParetoSet true if this item is in the top 80% of cumulative strategic value
     */
    public record RankedItem(
            RoadmapItem item,
            double roi,
            double normalizedValue,
            double cumulativeValue,
            boolean inParetoSet) {

        public RankedItem {
            Objects.requireNonNull(item, "item must not be null");
        }
    }

    /**
     * Full prioritization result containing all ranked items and the Pareto subset.
     *
     * @param allItems all roadmap items ranked by the chosen strategy
     * @param paretoSet the items in the Pareto set (top 80% of cumulative value)
     * @param paretoThreshold the cumulative value threshold used (0.80)
     * @param summary human-readable summary of the Pareto analysis
     */
    public record PrioritizedRoadmap(
            List<RankedItem> allItems,
            List<RankedItem> paretoSet,
            double paretoThreshold,
            String summary) {

        public PrioritizedRoadmap {
            allItems = List.copyOf(allItems);
            paretoSet = List.copyOf(paretoSet);
            Objects.requireNonNull(summary, "summary must not be null");
        }
    }

    // ── Strategy ─────────────────────────────────────────────────────────────

    /** Ranking strategy for roadmap prioritization. */
    public sealed interface Strategy permits Strategy.ImpactFirst, Strategy.RoiFirst, Strategy.DependencyAware {

        /** Sort items by raw impact score descending. */
        record ImpactFirst() implements Strategy {}

        /** Sort items by ROI (impact/effort) descending. This is the default strategy. */
        record RoiFirst() implements Strategy {}

        /**
         * Topological sort respecting dependency ordering, with ROI-based ranking within each
         * dependency layer. Returns an error if circular dependencies are detected.
         */
        record DependencyAware() implements Strategy {}
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Prioritize roadmap items using the default {@link Strategy.RoiFirst} strategy.
     *
     * @param items the roadmap items to prioritize
     * @return a {@link PrioritizedRoadmap} on success, or an error if validation fails
     */
    public static Result<PrioritizedRoadmap, Exception> prioritize(List<RoadmapItem> items) {
        return prioritize(items, new Strategy.RoiFirst());
    }

    /**
     * Prioritize roadmap items using the specified strategy.
     *
     * <p>The engine validates all items, sorts them according to the strategy, calculates ROI and
     * normalized values, computes cumulative values, and identifies the Pareto set (items delivering
     * 80% of strategic value).
     *
     * @param items the roadmap items to prioritize
     * @param strategy the ranking strategy to apply
     * @return a {@link PrioritizedRoadmap} on success, or an error if validation fails
     */
    public static Result<PrioritizedRoadmap, Exception> prioritize(
            List<RoadmapItem> items, Strategy strategy) {
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");

        // Validate all items
        var validationError = validateItems(items);
        if (validationError != null) {
            return Result.err(validationError);
        }

        // Sort according to strategy
        List<RoadmapItem> sorted;
        switch (strategy) {
            case Strategy.ImpactFirst() -> sorted = sortByImpact(items);
            case Strategy.RoiFirst() -> sorted = sortByRoi(items);
            case Strategy.DependencyAware() -> {
                var topoResult = sortByDependencyThenRoi(items);
                if (topoResult.isError()) {
                    return Result.err(
                            ((Result.Err<List<RoadmapItem>, Exception>) topoResult).error());
                }
                sorted = ((Result.Ok<List<RoadmapItem>, Exception>) topoResult).value();
            }
        }

        // Calculate ROI for each item
        double totalRoi = sorted.stream().mapToDouble(RoadmapPrioritizer::calculateRoi).sum();

        // Build ranked items with normalized and cumulative values
        var rankedItems = new ArrayList<RankedItem>();
        double cumulativeValue = 0.0;

        for (var item : sorted) {
            double roi = calculateRoi(item);
            double normalizedValue = totalRoi > 0 ? roi / totalRoi : 0.0;
            cumulativeValue += normalizedValue;
            rankedItems.add(new RankedItem(item, roi, normalizedValue, cumulativeValue, false));
        }

        // Mark Pareto set: items while cumulative value <= 0.80, plus the one that crosses 0.80
        var finalItems = new ArrayList<RankedItem>();
        var paretoSet = new ArrayList<RankedItem>();
        boolean thresholdCrossed = false;

        for (var ranked : rankedItems) {
            boolean inPareto = !thresholdCrossed;
            var updated = new RankedItem(
                    ranked.item(),
                    ranked.roi(),
                    ranked.normalizedValue(),
                    ranked.cumulativeValue(),
                    inPareto);
            finalItems.add(updated);
            if (inPareto) {
                paretoSet.add(updated);
            }
            if (!thresholdCrossed && ranked.cumulativeValue() >= 0.80) {
                thresholdCrossed = true;
            }
        }

        String summary =
                "%d of %d items (%d%%) deliver 80%% of strategic value"
                        .formatted(
                                paretoSet.size(),
                                finalItems.size(),
                                Math.round((double) paretoSet.size() / finalItems.size() * 100));

        return Result.ok(new PrioritizedRoadmap(finalItems, paretoSet, 0.80, summary));
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private static Exception validateItems(List<RoadmapItem> items) {
        if (items.isEmpty()) {
            return new IllegalArgumentException("items must not be empty");
        }
        for (var item : items) {
            if (item.impactScore() < 1.0 || item.impactScore() > 10.0) {
                return new IllegalArgumentException(
                        "impactScore must be 1.0-10.0 for item '%s', got %.1f"
                                .formatted(item.id(), item.impactScore()));
            }
            if (item.effortScore() < 1.0 || item.effortScore() > 10.0) {
                return new IllegalArgumentException(
                        "effortScore must be 1.0-10.0 for item '%s', got %.1f"
                                .formatted(item.id(), item.effortScore()));
            }
        }
        // Validate that all dependency references exist
        var ids = new HashSet<String>();
        for (var item : items) {
            ids.add(item.id());
        }
        for (var item : items) {
            for (var dep : item.dependencies()) {
                if (!ids.contains(dep)) {
                    return new IllegalArgumentException(
                            "item '%s' depends on unknown item '%s'"
                                    .formatted(item.id(), dep));
                }
            }
        }
        return null;
    }

    // ── Sorting strategies ───────────────────────────────────────────────────

    private static double calculateRoi(RoadmapItem item) {
        return item.impactScore() / item.effortScore();
    }

    private static List<RoadmapItem> sortByImpact(List<RoadmapItem> items) {
        return items.stream()
                .sorted(Comparator.comparingDouble(RoadmapItem::impactScore).reversed())
                .toList();
    }

    private static List<RoadmapItem> sortByRoi(List<RoadmapItem> items) {
        return items.stream()
                .sorted(Comparator.comparingDouble(
                                (RoadmapItem i) -> calculateRoi(i))
                        .reversed())
                .toList();
    }

    /**
     * Topological sort respecting dependencies, with ROI-based ranking within each layer. Detects
     * circular dependencies and returns an error if found.
     */
    private static Result<List<RoadmapItem>, Exception> sortByDependencyThenRoi(
            List<RoadmapItem> items) {
        // Build adjacency and in-degree maps
        Map<String, RoadmapItem> itemById = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (var item : items) {
            itemById.put(item.id(), item);
            dependents.put(item.id(), new HashSet<>());
            inDegree.put(item.id(), 0);
        }

        for (var item : items) {
            for (var dep : item.dependencies()) {
                dependents.get(dep).add(item.id());
                inDegree.merge(item.id(), 1, Integer::sum);
            }
        }

        // Kahn's algorithm for topological sort
        var result = new ArrayList<RoadmapItem>();
        var ready = new ArrayList<String>();

        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        while (!ready.isEmpty()) {
            // Within each layer, sort by ROI descending
            ready.sort(
                    Comparator.comparingDouble(
                                    (String id) -> calculateRoi(itemById.get(id)))
                            .reversed());

            var nextReady = new ArrayList<String>();
            for (var id : ready) {
                result.add(itemById.get(id));
                for (var dependent : dependents.get(id)) {
                    int newDegree = inDegree.get(dependent) - 1;
                    inDegree.put(dependent, newDegree);
                    if (newDegree == 0) {
                        nextReady.add(dependent);
                    }
                }
            }
            ready = nextReady;
        }

        if (result.size() != items.size()) {
            return Result.err(
                    new IllegalArgumentException(
                            "circular dependency detected among roadmap items"));
        }

        return Result.ok(result);
    }
}
