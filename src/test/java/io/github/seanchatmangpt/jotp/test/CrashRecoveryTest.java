package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
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
@DtrTest
class CrashRecoveryTest implements WithAssertions {

    @Test
    void successOnFirstAttempt(DtrContext ctx) {
        ctx.say(
                "CrashRecovery.retry() returns Ok immediately when the supplier succeeds on the first attempt.");
        ctx.say("No retries are performed when the operation succeeds.");

        var result = CrashRecovery.retry(3, () -> 42);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isEqualTo(42);
    }

    @Test
    void recoversAfterInitialFailures(DtrContext ctx) {
        ctx.say(
                "CrashRecovery.retry() automatically retries on failure until success or max attempts exhausted.");
        ctx.say("The supplier is invoked in an isolated virtual thread on each attempt.");

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

        ctx.say("After 2 failures, the 3rd attempt succeeded with value 'recovered'.");
    }

    @Test
    void failureWhenAllAttemptsExhausted(DtrContext ctx) {
        ctx.say(
                "When all retry attempts are exhausted, CrashRecovery returns Err containing the final exception.");
        ctx.say(
                "This enables railway-oriented error handling without catching exceptions at the call site.");

        var result =
                CrashRecovery.retry(
                        3,
                        () -> {
                            throw new IllegalStateException("always fails");
                        });
        assertThat(result.isFailure()).isTrue();

        ctx.say(
                "Result is Err after 3 failed attempts — the caller decides how to handle the failure.");
    }

    @Test
    void singleAttemptAllowed(DtrContext ctx) {
        ctx.say("CrashRecovery.retry(1, ...) performs exactly one attempt with no retries.");
        ctx.say("This is useful for isolating a single operation in a virtual thread.");

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
