package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link AckRetry} — Joe Armstrong's idempotent retry / ACK pattern.
 *
 * <p>"The sender monitors the node, detects DOWN, and resends." — Joe Armstrong
 */
@DtrTest
@Timeout(10)
class AckRetryTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Domain messages ────────────────────────────────────────────────────────

    sealed interface Cmd permits Cmd.Echo {
        record Echo(String value) implements Cmd {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Build a simple echo proc whose state is the last received message string. */
    private Proc<String, Cmd> echoProc() {
        return new Proc<>(
                "",
                (state, msg) ->
                        switch (msg) {
                            case Cmd.Echo(var v) -> v;
                        });
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void send_succeedsOnFirstAttempt(DtrContext ctx) throws InterruptedException {
        ctx.sayNextSection("AckRetry: Joe Armstrong's Idempotent Retry Pattern");
        ctx.say(
                """
                AckRetry implements Joe Armstrong's advice: "The sender monitors the node, detects DOWN, and resends."
                It provides automatic retry with configurable timeout and max attempts.
                When the first attempt succeeds, the result is returned immediately without additional retries.
                """);
        var proc = echoProc();
        try {
            var result = AckRetry.send(proc, new Cmd.Echo("hello"), Duration.ofSeconds(2), 3);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElse("")).isEqualTo("hello");
        } finally {
            proc.stop();
        }
    }

    @Test
    void send_retriesOnTimeoutAndEventuallySucceeds(DtrContext ctx) throws InterruptedException {
        ctx.say(
                """
                When an attempt fails (timeout or exception), AckRetry automatically retries.
                Each retry is a fresh attempt with the same message.
                This pattern is essential for distributed systems where transient failures are common.
                """);
        // A proc that ignores the first two messages (simulating timeout by never completing ask),
        // then handles the third normally.  We simulate this by using a proc whose handler
        // throws on the first two calls, then succeeds.
        var callCount = new AtomicInteger(0);

        var proc =
                new Proc<String, Cmd>(
                        "",
                        (state, msg) -> {
                            int n = callCount.incrementAndGet();
                            if (n <= 2) {
                                throw new RuntimeException("simulated crash on attempt " + n);
                            }
                            return switch (msg) {
                                case Cmd.Echo(var v) -> v;
                            };
                        });

        try {
            // Use a short timeout so the crashing attempts fail quickly.
            var result = AckRetry.send(proc, new Cmd.Echo("eventual"), Duration.ofSeconds(2), 5);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElse("")).isEqualTo("eventual");
            assertThat(callCount.get()).isGreaterThanOrEqualTo(3);
        } finally {
            proc.stop();
        }
    }

    @Test
    void send_returnsFailureAfterMaxAttempts() throws InterruptedException {
        // A proc whose handler always throws.
        var proc =
                new Proc<String, Cmd>(
                        "",
                        (state, msg) -> {
                            throw new RuntimeException("always fails");
                        });

        try {
            var result = AckRetry.send(proc, new Cmd.Echo("any"), Duration.ofSeconds(2), 3);

            assertThat(result.isFailure()).isTrue();
            // The failure should contain an exception
            result.fold(
                    v -> {
                        fail("expected failure, got success: " + v);
                        return null;
                    },
                    err -> {
                        assertThat(err).isInstanceOf(Exception.class);
                        return null;
                    });
        } finally {
            proc.stop();
        }
    }

    @Test
    void sendWithBackoff_respectsBackoffDelay(DtrContext ctx) throws InterruptedException {
        ctx.say(
                """
                sendWithBackoff adds exponential backoff between retries to avoid thundering herd problems.
                After each failure, the system waits before retrying, giving the target time to recover.
                This is crucial for avoiding cascading failures in distributed systems.
                """);
        // Two attempts: first fails, second succeeds.
        // We measure elapsed wall time and verify it is >= initialDelay.
        var callCount = new AtomicInteger(0);

        var proc =
                new Proc<String, Cmd>(
                        "",
                        (state, msg) -> {
                            int n = callCount.incrementAndGet();
                            if (n == 1) {
                                throw new RuntimeException("first attempt fails");
                            }
                            return switch (msg) {
                                case Cmd.Echo(var v) -> v;
                            };
                        });

        Duration initialDelay = Duration.ofMillis(200);

        try {
            long startNs = System.nanoTime();
            var result =
                    AckRetry.sendWithBackoff(
                            proc, new Cmd.Echo("backoff"), Duration.ofSeconds(2), 3, initialDelay);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.orElse("")).isEqualTo("backoff");
            // At least one backoff of initialDelay should have occurred between attempt 1 and 2.
            assertThat(elapsedMs).isGreaterThanOrEqualTo(initialDelay.toMillis());
        } finally {
            proc.stop();
        }
    }
}
