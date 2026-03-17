package io.github.seanchatmangpt.jotp.dogfood.core;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Java 26 Gatherer patterns in {@link GathererPatterns}.
 *
 * <p>Gatherer API (JEP 461) enables custom stream intermediate operations. Unlike collectors which
 * operate at the end, gatherers transform streams mid-pipeline, enabling operations like batching,
 * windowing, scanning, and folding that were previously difficult or impossible.
 *
 * <p>Key patterns: - Fixed-size batching - Sliding window operations - Running scan (prefix sums) -
 * Fold as intermediate operation - Concurrent mapping - Custom deduplication - Gatherer chaining
 */
@DisplayName("GathererPatterns - Java 26 Gatherer API")
class GathererPatternsTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    @DisplayName("batch creates fixed-sized batches")
    void batch_createsFixedSizedBatches() {
                "Java 26 introduces the Gatherer API, enabling custom stream intermediate operations."
                        + " Unlike collectors (terminal operations), gatherers transform streams mid-pipeline,"
                        + " enabling batching, windowing, and other complex transformations.");

                new String[][] {
                    {"Aspect", "Intermediate (middle)", "Final result"},
                    {"Collector", "Operations", "Per-element state"},
                    {"Gatherer", "Reduction only", "Use Cases"},
                    {"Pipeline Position", "Transformation + reduction", "sum, toList, grouping"},
                    {"Terminal (end)", "State", "batch, window, scan"}
                });

                """
            // Batch elements into fixed-size groups
            var items = List.of(1, 2, 3, 4, 5, 6, 7);
            var batches = GathererPatterns.batch(items, 3);

            // Result: [[1, 2, 3], [4, 5, 6], [7]]
            // Last batch may be smaller
            """,
                "java");

        var items = List.of(1, 2, 3, 4, 5, 6, 7);
        var batches = GathererPatterns.batch(items, 3);

        assertThat(batches).containsExactly(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7));

                Map.of(
                        "Input Size",
                        "7 elements",
                        "Batch Size",
                        "3",
                        "Output",
                        "3 batches",
                        "Last Batch",
                        "Partial (size 1)"));

                "Batching is essential for bulk operations (database inserts, API calls) where you"
                        + " want to process multiple items together. The Gatherer API makes this a"
                        + " one-liner instead of manual iteration.");
    }

    @Test
    @DisplayName("slidingWindow creates overlapping windows")
    void slidingWindow_createsOverlappingWindows() {
                "Sliding windows create overlapping sequences, useful for moving averages, time-series"
                        + " analysis, and pattern detection. Each window slides by one element.");

                """
            // Create sliding windows of size 3
            var items = List.of(1, 2, 3, 4, 5);
            var windows = GathererPatterns.slidingWindow(items, 3);

            // Result: [[1, 2, 3], [2, 3, 4], [3, 4, 5]]
            // Each window slides by 1 element
            """,
                "java");

        var items = List.of(1, 2, 3, 4, 5);
        var windows = GathererPatterns.slidingWindow(items, 3);

        assertThat(windows).containsExactly(List.of(1, 2, 3), List.of(2, 3, 4), List.of(3, 4, 5));

        var values = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
        var averages = GathererPatterns.movingAverage(values, 3);

        assertThat(averages).containsExactly(20.0, 30.0, 40.0);

                Map.of(
                        "Input",
                        "[1, 2, 3, 4, 5]",
                        "Window Size",
                        "3",
                        "Output",
                        "3 windows",
                        "Overlap",
                        "2 elements between windows",
                        "Moving Average",
                        "[20.0, 30.0, 40.0]"));

                "Sliding windows are computationally expensive (O(n*k) for n elements and window size"
                        + " k). Use them judiciously on large datasets or consider windowed aggregations.");
    }

    @Test
    @DisplayName("runningSum calculates prefix sums")
    void runningSum_calculatesCorrectly() {
                "Scan operations (also called prefix sums) maintain running state across elements."
                        + " Unlike reduce which returns a single value, scan returns an intermediate value"
                        + " for each input element.");

                """
            // Calculate running sum
            var values = List.of(1, 2, 3, 4, 5);
            var sums = GathererPatterns.runningSum(values);

            // Result: [1, 3, 6, 10, 15]
            // Each element is the sum of all previous elements
            """,
                "java");

        var values = List.of(1, 2, 3, 4, 5);
        var sums = GathererPatterns.runningSum(values);

        assertThat(sums).containsExactly(1, 3, 6, 10, 15);

        var items = List.of("a", "b", "c");
        var result = GathererPatterns.runningAccumulate(items, "", (acc, s) -> acc + s);

        assertThat(result).containsExactly("a", "ab", "abc");

                Map.of(
                        "Input",
                        "[1, 2, 3, 4, 5]",
                        "Operation",
                        "Running sum",
                        "Output",
                        "[1, 3, 6, 10, 15]",
                        "State",
                        "Accumulated across elements",
                        "String Concat",
                        "[a, ab, abc]"));

                "Scan operations are perfect for cumulative calculations, running totals, and"
                        + " maintaining state across stream elements without manual loops.");
    }

    @Test
    @DisplayName("foldToSingle sums all elements")
    void foldToSingle_sumsAllElements() {
                "Fold is traditionally a terminal operation (Stream.reduce), but Gatherer enables fold"
                        + " as an intermediate operation. This allows folding to be part of a larger"
                        + " pipeline.");

                """
            // Fold to single value mid-stream
            var items = List.of(1, 2, 3, 4, 5);
            var sum = GathererPatterns.foldToSingle(items, 0, Integer::sum);

            // Result: 15
            // Can be chained with other operations
            """,
                "java");

        var items = List.of(1, 2, 3, 4, 5);
        var sum = GathererPatterns.foldToSingle(items, 0, Integer::sum);

        assertThat(sum).isEqualTo(15);

        var strings = List.of("a", "b", "c");
        var concat = GathererPatterns.foldToSingle(strings, "", (a, b) -> a + b);

        assertThat(concat).isEqualTo("abc");

                Map.of(
                        "Integer Fold",
                        "1+2+3+4+5 = 15",
                        "String Fold",
                        "a+b+c = abc",
                        "Identity",
                        "0 for sum, \"\" for concat",
                        "Operation",
                        "Intermediate (can chain)"));

                "Fold as an intermediate operation enables complex pipelines: filter → fold → map."
                        + " This was impossible with traditional Stream.reduce, which is terminal-only.");
    }

    @Test
    @DisplayName("mapConcurrent maps with parallelism")
    void mapConcurrent_mapsAllElements() {
                "Gatherers can introduce parallelism mid-stream. The mapConcurrent gatherer processes"
                        + " elements with a fixed thread pool, combining the simplicity of streams with"
                        + " the performance of parallel execution.");

                """
            // Map with controlled parallelism
            var items = List.of(1, 2, 3, 4, 5);
            var result = GathererPatterns.mapConcurrent(items, 2, i -> i * 2);

            // Result: [2, 4, 6, 8, 10]
            // Uses 2 threads for mapping
            // Order is preserved
            """,
                "java");

        var items = List.of(1, 2, 3, 4, 5);
        var result = GathererPatterns.mapConcurrent(items, 2, i -> i * 2);

        assertThat(result).containsExactly(2, 4, 6, 8, 10);

        var strings = List.of("a", "b", "c", "d");
        var upper = GathererPatterns.mapConcurrent(strings, 3, String::toUpperCase);

        assertThat(upper).containsExactly("A", "B", "C", "D");

                Map.of(
                        "Input",
                        "[1, 2, 3, 4, 5]",
                        "Parallelism",
                        "2 threads",
                        "Operation",
                        "i → i * 2",
                        "Output",
                        "[2, 4, 6, 8, 10]",
                        "Order",
                        "Preserved"));

                "Unlike parallel streams which use common ForkJoinPool, gatherers can use custom"
                        + " thread pools. This prevents resource contention and enables fine-tuned"
                        + " parallelism.");
    }

    @Test
    @DisplayName("deduplicateConsecutive removes duplicates")
    void deduplicateConsecutive_removesConsecutiveDuplicates() {
                "Gatherers can implement arbitrary stateful transformations. Here's a custom gatherer"
                        + " that removes consecutive duplicates while preserving non-consecutive ones.");

                """
            // Custom gatherer: deduplicate consecutive elements
            var items = List.of(1, 1, 2, 2, 2, 3, 2, 2, 4);
            var deduped = items.stream()
                .gather(GathererPatterns.deduplicateConsecutive())
                .toList();

            // Result: [1, 2, 3, 2, 4]
            // Consecutive duplicates removed, non-consecutive preserved
            """,
                "java");

        var items = List.of(1, 1, 2, 2, 2, 3, 2, 2, 4);
        var deduped = items.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();

        assertThat(deduped).containsExactly(1, 2, 3, 2, 4);

        var allSame = List.of(5, 5, 5, 5);
        var dedupedSame =
                allSame.stream().gather(GathererPatterns.deduplicateConsecutive()).toList();

        assertThat(dedupedSame).containsExactly(5);

                Map.of(
                        "Input",
                        "[1, 1, 2, 2, 2, 3, 2, 2, 4]",
                        "Consecutive Duplicates",
                        "Removed",
                        "Non-Consecutive",
                        "Preserved (2 appears twice)",
                        "Output",
                        "[1, 2, 3, 2, 4]",
                        "All Same",
                        "[5, 5, 5, 5] → [5]"));

                "Custom gatherers maintain internal state (the previous element) to make decisions."
                        + " This enables sophisticated transformations that would require manual loops or"
                        + " external libraries.");
    }

    @Test
    @DisplayName("batchAndDeduplicate chains gatherers")
    void batchAndDeduplicate_chainsGatherers() {
                "Gatherers can be chained like any stream operation. Each gatherer transforms the"
                        + " stream, passing results to the next. This enables complex pipelines.");

                """
            // Chain gatherers: dedupe → batch
            var items = List.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
            var result = GathererPatterns.batchAndDeduplicate(items, 2);

            // Step 1: Dedupe → [1, 2, 3, 4, 5]
            // Step 2: Batch → [[1, 2], [3, 4], [5]]
            // Result: [[1, 2], [3, 4], [5]]
            """,
                "java");

        var items = List.of(1, 1, 2, 2, 3, 3, 4, 4, 5, 5);
        var result = GathererPatterns.batchAndDeduplicate(items, 2);

        assertThat(result).containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));

                Map.of(
                        "Input",
                        "[1, 1, 2, 2, 3, 3, 4, 4, 5, 5]",
                        "After Dedupe",
                        "[1, 2, 3, 4, 5]",
                        "After Batch",
                        "[[1, 2], [3, 4], [5]]",
                        "Pipeline",
                        "stream → dedupe → batch → toList"));

                "Gatherer chaining is composable and type-safe. Each gatherer is independent and can"
                        + " be reused in different pipelines. This is the essence of functional"
                        + " programming principles applied to streams.");
    }
}
