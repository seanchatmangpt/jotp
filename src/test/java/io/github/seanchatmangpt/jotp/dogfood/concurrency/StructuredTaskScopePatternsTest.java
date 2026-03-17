package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: tests for StructuredTaskScopePatterns demonstrating Java 26 structured concurrency.
 *
 * <p>Project Loom (JEP 453) introduces StructuredTaskScope — a way to manage concurrent tasks as a
 * unit, ensuring proper error propagation, timeout handling, and resource cleanup.
 *
 * <p>Key benefits: 1) Tasks complete as a unit — no orphaned threads 2) Automatic error propagation
 * — any failure cancels remaining tasks 3) Timeout support — cancel all tasks if deadline exceeded
 * 4) First-success racing — return when any task succeeds
 */
@DisplayName("StructuredTaskScopePatterns - Java 26 Structured Concurrency")
class StructuredTaskScopePatternsTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    @DisplayName("runBoth returns pair when both tasks succeed")
    void runBoth_returnsPair() throws Exception {
                "StructuredTaskScope ensures that concurrent tasks complete as a unit. The runBoth"
                        + " pattern waits for two tasks to complete successfully and returns their results as a"
                        + " pair. If either task fails, the entire scope fails.");

                new String[][] {
                    {"Pattern", "Purpose", "Error Handling", "Use Case"},
                    {
                        "runBoth",
                        "Run two tasks concurrently, wait for both",
                        "Fails if either task throws",
                        "Parallel independent operations"
                    }
                });

                """
            // Old approach: manual join with potential orphaned threads
            // Future<String> f1 = executor.submit(() -> task1());
            // Future<Integer> f2 = executor.submit(() -> task2());
            // String r1 = f1.get();  // may hang if f2 fails
            // Integer r2 = f2.get();

            // New approach: structured concurrency with automatic cleanup
            var result = StructuredTaskScopePatterns.runBoth(() -> "hello", () -> 42);

            // Both tasks completed successfully
            assertThat(result.first()).isEqualTo("hello");
            assertThat(result.second()).isEqualTo(42);
            """,
                "java");

        var result = StructuredTaskScopePatterns.runBoth(() -> "hello", () -> 42);

        assertThat(result.first()).isEqualTo("hello");
        assertThat(result.second()).isEqualTo(42);

                Map.of(
                        "First Task Result",
                        result.first(),
                        "Second Task Result",
                        String.valueOf(result.second()),
                        "Completion",
                        "Both tasks succeeded",
                        "Resource Cleanup",
                        "Automatic"));

                "StructuredTaskScope ensures that if the first task fails, the second is automatically"
                        + " cancelled. No more orphaned threads wasting resources.");
    }

    @Test
    @DisplayName("runBoth throws when first task fails")
    void runBoth_throwsOnFirstFailure() {
                "When any task in a StructuredTaskScope fails, the entire scope is cancelled and"
                        + " remaining tasks receive interruption. This prevents wasted work on partially"
                        + " failed operations.");

                """
            try {
                var result = StructuredTaskScopePatterns.runBoth(
                    () -> {
                        throw new RuntimeException("task 1 failed");
                    },
                    () -> 42
                );
            } catch (Exception e) {
                // StructuredTaskScope aggregates failures
                // Task 2 was automatically cancelled
            }
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                StructuredTaskScopePatterns.runBoth(
                                        () -> {
                                            throw new RuntimeException("fail");
                                        },
                                        () -> 42))
                .isInstanceOf(Exception.class);

                Map.of(
                        "Error Propagation",
                        "Immediate",
                        "Task 2 Status",
                        "Cancelled",
                        "Exception Type",
                        "StructuredTaskScope$FailedException",
                        "Resource Cleanup",
                        "Automatic"));

                "StructuredTaskScope.join() throws if any task failed. The exception contains all"
                        + " aggregated failures, making debugging easier than manual Future.get() error"
                        + " handling.");
    }

    @Test
    @DisplayName("runBoth throws when second task fails")
    void runBoth_throwsOnSecondFailure() {
                "Error handling is symmetric — it doesn't matter which task fails. StructuredTaskScope"
                        + " ensures all tasks are cancelled when any failure occurs.");

                """
            // Second task fails, first task result is discarded
            assertThatThrownBy(() ->
                StructuredTaskScopePatterns.runBoth(
                    () -> "hello",  // This result is lost
                    () -> {
                        throw new RuntimeException("task 2 failed");
                    }
                )
            ).isInstanceOf(Exception.class);
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                StructuredTaskScopePatterns.runBoth(
                                        () -> "hello",
                                        () -> {
                                            throw new RuntimeException("fail");
                                        }))
                .isInstanceOf(Exception.class);

                Map.of(
                        "First Task",
                        "Completed (result discarded)",
                        "Second Task",
                        "Failed",
                        "Overall Result",
                        "Exception propagated",
                        "Cleanup",
                        "All tasks cancelled"));

                "This 'fail-fast' behavior is crucial for resource management. When a database query"
                        + " fails, there's no point continuing to fetch related data — cancel everything and"
                        + " report the error.");
    }

    @Test
    @DisplayName("runAll returns triple when all tasks succeed")
    void runAll_returnsTriple() throws Exception {
                "The runAll pattern extends runBoth to handle multiple concurrent tasks. It demonstrates"
                        + " how structured concurrency scales to any number of parallel operations while"
                        + " maintaining the same guarantees: all succeed or all fail together.");

                """
            // Run three tasks concurrently, wait for all
            var result = StructuredTaskScopePatterns.runAll(
                () -> "a",
                () -> "b",
                () -> "c"
            );

            // All tasks completed successfully
            assertThat(result.first()).isEqualTo("a");
            assertThat(result.second()).isEqualTo("b");
            assertThat(result.third()).isEqualTo("c");
            """,
                "java");

        var result = StructuredTaskScopePatterns.runAll(() -> "a", () -> "b", () -> "c");

        assertThat(result.first()).isEqualTo("a");
        assertThat(result.second()).isEqualTo("b");
        assertThat(result.third()).isEqualTo("c");

                Map.of(
                        "Task 1 Result",
                        result.first(),
                        "Task 2 Result",
                        result.second(),
                        "Task 3 Result",
                        result.third(),
                        "Completion",
                        "All 3 tasks succeeded",
                        "Pattern",
                        "Extensible to N tasks"));

                "In production, you'd use generic StructuredTaskScope to run any number of tasks. The"
                        + " runAll pattern here shows a typed triple for convenience. Consider using"
                        + " StructuredTaskScope.join() with a list of Subtasks for N-ary concurrency.");
    }

    @Test
    @DisplayName("raceForFirst returns a successful result")
    void raceForFirst_returnsFirstSuccessful() throws Exception {
                "Sometimes you want the first successful result from multiple competing strategies."
                        + " The raceForFirst pattern uses ShutdownOnSuccess to return as soon as any task"
                        + " completes, cancelling the rest.");

                new String[][] {
                    {"Scope Policy", "Completion", "Error Handling", "Use Case", "Cancellation"},
                    {
                        "ShutdownOnSuccess",
                        "Returns first success, cancels rest",
                        "Only fails if ALL tasks fail",
                        "Redundant services, race conditions",
                        "Automatic after first success"
                    }
                });

                """
            // Race between slow and fast tasks
            List<Callable<String>> tasks = List.of(
                () -> {
                    Thread.sleep(100);
                    return "slow";
                },
                () -> "fast"  // Wins the race
            );

            var result = StructuredTaskScopePatterns.raceForFirst(tasks);

            // First task to complete wins
            assertThat(result).isIn("fast", "slow");
            """,
                "java");

        List<Callable<String>> tasks =
                List.of(
                        () -> {
                            Thread.sleep(100);
                            return "slow";
                        },
                        () -> "fast");
        var result = StructuredTaskScopePatterns.raceForFirst(tasks);

        assertThat(result).isIn("fast", "slow");

                Map.of(
                        "Winning Task",
                        result,
                        "Losing Task",
                        "Cancelled",
                        "Race Condition",
                        "Non-deterministic",
                        "Pattern",
                        "First-success wins"));

                "This pattern is ideal for redundant services (multiple APIs, database replicas) where"
                        + " you want the fastest response. StructuredTaskScope ensures the slower tasks are"
                        + " cancelled to free resources.");
    }
}
