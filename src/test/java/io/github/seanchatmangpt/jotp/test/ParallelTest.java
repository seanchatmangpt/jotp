package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Parallel;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
class ParallelTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    void allSuccessReturnsAllResults() {
        List<Supplier<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);
        var result = Parallel.all(tasks);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).containsExactlyInAnyOrder(1, 2, 3);
    void emptyListReturnsEmptySuccess() {
        var result = Parallel.<Integer>all(List.of());
        assertThat(result.orElseThrow()).isEmpty();
    void anyFailureReturnsFailure() {
        List<Supplier<Integer>> tasks =
                List.of(
                        () -> 1,
                        () -> {
                            throw new IllegalStateException("injected crash");
                        },
                        () -> 3);
        assertThat(result.isFailure()).isTrue();
    @Property
    void allSucceedMeansSuccessResult(@ForAll @IntRange(min = 0, max = 20) int taskCount) {
        var tasks =
                IntStream.range(0, taskCount).<Supplier<Integer>>mapToObj(i -> () -> i).toList();
        assertThat(result.orElseThrow()).hasSize(taskCount);
    void anyFailureMeansFailureResult(@ForAll @IntRange(min = 1, max = 10) int failIndex) {
                IntStream.range(0, 10)
                        .<Supplier<Integer>>mapToObj(
                                i ->
                                        () -> {
                                            if (i == failIndex % 10) {
                                                throw new RuntimeException("crash at " + i);
                                            }
                                            return i;
                                        })
                        .toList();
}
