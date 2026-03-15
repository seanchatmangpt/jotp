package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.CrashRecovery;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
class CrashRecoveryTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    void successOnFirstAttempt() {
        var result = CrashRecovery.retry(3, () -> 42);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo(42);
    void recoversAfterInitialFailures() {
        var attempts = new AtomicInteger(0);
        var result =
                CrashRecovery.retry(
                        5,
                        () -> {
                            if (attempts.incrementAndGet() < 3) {
                                throw new RuntimeException("crash #" + attempts.get());
                            }
                            return "recovered";
                        });
        assertThat(result.orElseThrow()).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
    void failureWhenAllAttemptsExhausted() {
                        3,
                            throw new IllegalStateException("always fails");
        assertThat(result.isFailure()).isTrue();
    void singleAttemptAllowed() {
        var result = CrashRecovery.retry(1, () -> "ok");
    @Property
    void retryConvergesOrExhausts(
            @ForAll @IntRange(min = 1, max = 5) int maxAttempts,
            @ForAll @IntRange(min = 0, max = 6) int failUntilAttempt) {
        var counter = new AtomicInteger(0);
                        maxAttempts,
                            if (counter.incrementAndGet() <= failUntilAttempt) {
                                throw new RuntimeException("injected failure");
                            return counter.get();
        if (failUntilAttempt < maxAttempts) {
            assertThat(result.isSuccess()).isTrue();
        } else {
            assertThat(result.isFailure()).isTrue();
        }
}
