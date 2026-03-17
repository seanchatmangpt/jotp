package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.CrashRecovery;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/**
 * Crash recovery patterns using the {@link CrashRecovery} utility.
 *
 * <h2>Pattern: Automatic Retry with Isolation</h2>
 *
 * <p>CrashRecovery wraps potentially failing operations in isolated virtual threads and returns a
 * {@link io.github.seanchatmangpt.jotp.Result} type, enabling railway-oriented error handling
 * without exceptions propagating up the call stack.
 *
 * <h3>Key Behaviors:</h3>
 *
 * <ul>
 *   <li>Success on first attempt — no retries needed
 *   <li>Recovery after transient failures — retry until success or exhaustion
 *   <li>Failure after all attempts exhausted — returns Err with the final exception
 *   <li>Single attempt mode — no retries, just isolation
 * </ul>
 *
 * @see CrashRecovery
 * @see io.github.seanchatmangpt.jotp.Result
 */
class CrashRecoveryTest implements WithAssertions {

    @Test
    void successOnFirstAttempt() {
                "CrashRecovery.retry() returns Ok immediately when the supplier succeeds on the first attempt.");

        var result = CrashRecovery.retry(3, () -> 42);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo(42);
    }

    @Test
    void recoversAfterInitialFailures() {
                "CrashRecovery.retry() automatically retries on failure until success or max attempts exhausted.");

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
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);

    }

    @Test
    void failureWhenAllAttemptsExhausted() {
                "When all retry attempts are exhausted, CrashRecovery returns Err containing the final exception.");
                "This enables railway-oriented error handling without catching exceptions at the call site.");

        var result =
                CrashRecovery.retry(
                        3,
                        () -> {
                            throw new IllegalStateException("always fails");
                        });
        assertThat(result.isFailure()).isTrue();

                "Result is Err after 3 failed attempts — the caller decides how to handle the failure.");
    }

    @Test
    void singleAttemptAllowed() {

        var result = CrashRecovery.retry(1, () -> "ok");
        assertThat(result.isSuccess()).isTrue();
    }

    @Property
    void retryConvergesOrExhausts(
            @ForAll @IntRange(min = 1, max = 5) int maxAttempts,
            @ForAll @IntRange(min = 0, max = 6) int failUntilAttempt) {
        var counter = new AtomicInteger(0);
        var result =
                CrashRecovery.retry(
                        maxAttempts,
                        () -> {
                            if (counter.incrementAndGet() <= failUntilAttempt) {
                                throw new RuntimeException("injected failure");
                            }
                            return counter.get();
                        });
        if (failUntilAttempt < maxAttempts) {
            assertThat(result.isSuccess()).isTrue();
        } else {
            assertThat(result.isFailure()).isTrue();
        }
    }
}
