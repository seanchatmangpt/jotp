package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.List;
import java.util.concurrent.Callable;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Dogfood: tests for StructuredTaskScopePatterns generated from
 * concurrency/structured-task-scope.tera.
 */
@DisplayName("StructuredTaskScopePatterns")
class StructuredTaskScopePatternsTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("runBoth returns pair when both tasks succeed")
    void runBoth_returnsPair() throws Exception {
        var result = StructuredTaskScopePatterns.runBoth(() -> "hello", () -> 42);
        assertThat(result.first()).isEqualTo("hello");
        assertThat(result.second()).isEqualTo(42);
    @DisplayName("runBoth throws when first task fails")
    void runBoth_throwsOnFirstFailure() {
        assertThatThrownBy(
                        () ->
                                StructuredTaskScopePatterns.runBoth(
                                        () -> {
                                            throw new RuntimeException("fail");
                                        },
                                        () -> 42))
                .isInstanceOf(Exception.class); // StructuredTaskScope$FailedException
    @DisplayName("runBoth throws when second task fails")
    void runBoth_throwsOnSecondFailure() {
                                        () -> "hello",
                                        }))
    @DisplayName("runAll returns triple when all tasks succeed")
    void runAll_returnsTriple() throws Exception {
        var result = StructuredTaskScopePatterns.runAll(() -> "a", () -> "b", () -> "c");
        assertThat(result.first()).isEqualTo("a");
        assertThat(result.second()).isEqualTo("b");
        assertThat(result.third()).isEqualTo("c");
    @DisplayName("raceForFirst returns a successful result")
    void raceForFirst_returnsFirstSuccessful() throws Exception {
        List<Callable<String>> tasks =
                List.of(
                        () -> {
                            Thread.sleep(100);
                            return "slow";
                        },
                        () -> "fast");
        var result = StructuredTaskScopePatterns.raceForFirst(tasks);
        // Race condition: either task can complete first depending on scheduling
        assertThat(result).isIn("fast", "slow");
}
