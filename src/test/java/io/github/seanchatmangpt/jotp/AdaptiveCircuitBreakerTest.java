package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.AdaptiveCircuitBreaker.CircuitOpenException;
import io.github.seanchatmangpt.jotp.AdaptiveCircuitBreaker.Config;
import io.github.seanchatmangpt.jotp.AdaptiveCircuitBreaker.State;
import io.github.seanchatmangpt.jotp.AdaptiveCircuitBreaker.Stats;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link AdaptiveCircuitBreaker}.
 *
 * <p>Covers:
 *
 * <ol>
 *   <li>Breaker opens when EWMA error rate exceeds threshold
 *   <li>Predictive opening triggers before the hard threshold when trend is sharply rising
 *   <li>OPEN → HALF_OPEN transition after openDuration
 *   <li>HALF_OPEN → CLOSED on all probes succeeding
 *   <li>HALF_OPEN → OPEN on any probe failing
 *   <li>Stats accurately reflect EWMA error rate and counters
 *   <li>{@code reset()} returns to CLOSED with zeroed stats
 *   <li>Fast-fail throws immediately in OPEN state (supplier never invoked)
 *   <li>Latency spike triggers opening even with low error rate
 *   <li>Boundary conditions for threshold values
 * </ol>
 */
class AdaptiveCircuitBreakerTest {

    // A fast openDuration so OPEN→HALF_OPEN can be tested without sleeping long
    private static final Duration FAST_OPEN = Duration.ofMillis(50);

    private AdaptiveCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        // alpha=0.5 for fast EWMA convergence in tests
        Config cfg =
                Config.builder()
                        .ewmaAlpha(0.5)
                        .errorRateThreshold(0.5)
                        .predictiveThreshold(0.3)
                        .openDuration(FAST_OPEN)
                        .halfOpenProbes(3)
                        .latencyThreshold(Duration.ofSeconds(2))
                        .build();
        breaker = AdaptiveCircuitBreaker.create(cfg);
    }

    // ── Helper suppliers ──────────────────────────────────────────────────────

    private static <T> T succeed(T value) {
        return value;
    }

    private static RuntimeException alwaysFail() {
        throw new RuntimeException("injected failure");
    }

    // ── 1. Opens when EWMA error rate exceeds threshold ───────────────────────

    @Test
    void opensWhenEwmaErrorRateExceedsThreshold() {
        // With alpha=0.5 and errorRateThreshold=0.5:
        // After 1 failure: ewma = 0.5*1 + 0.5*0 = 0.5  — not yet > threshold (strictly greater)
        // After 2 failures: ewma = 0.5*1 + 0.5*0.5 = 0.75 — exceeds threshold
        // Inject a success first so the EWMA starts low, then inject failures.
        assertThatCode(() -> breaker.execute(() -> "ok")).doesNotThrowAnyException();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);

        // First failure: ewma rises to 0.25 (from 0.0 base after one success update)
        // Actually with alpha=0.5: after success ewma=0, after first fail ewma=0.5, still ==
        // threshold
        // After second fail: ewma=0.75 > 0.5 → OPEN
        assertThatCode(
                        () ->
                                breaker.execute(
                                        () -> {
                                            throw new RuntimeException("fail");
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThatCode(
                        () ->
                                breaker.execute(
                                        () -> {
                                            throw new RuntimeException("fail");
                                        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    // ── 2. Predictive opening triggers before hard threshold ──────────────────

    @Test
    void predictiveOpeningTriggersBeforeHardThreshold() {
        // Use a separate breaker with a high errorRateThreshold (0.9) so the hard threshold is
        // never reached, but predictiveThreshold=0.3 with a sharply rising trend will trigger.
        Config predictiveCfg =
                Config.builder()
                        .ewmaAlpha(0.5)
                        .errorRateThreshold(0.9) // hard threshold very high
                        .predictiveThreshold(0.3) // predictive threshold is low
                        .openDuration(FAST_OPEN)
                        .halfOpenProbes(1)
                        .latencyThreshold(Duration.ofSeconds(10)) // disable latency trigger
                        .build();
        AdaptiveCircuitBreaker predictiveBreaker = AdaptiveCircuitBreaker.create(predictiveCfg);

        // Seed with a success so EWMA starts at 0, then inject failures to drive it past 0.3
        // With alpha=0.5:
        //   after 1 fail: 0.5*1 + 0.5*0   = 0.50 > predictiveThreshold(0.3) and trend > 0 → OPEN
        assertThatCode(() -> predictiveBreaker.execute(() -> "ok")).doesNotThrowAnyException();

        // This failure should trigger predictive opening (ewma crosses predictiveThreshold +
        // trend>0)
        assertThatCode(
                        () ->
                                predictiveBreaker.execute(
                                        () -> {
                                            throw new RuntimeException("fail");
                                        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(predictiveBreaker.state()).isEqualTo(State.OPEN);
        // Verify it opened predictively — hard threshold (0.9) was never crossed
        Stats stats = predictiveBreaker.stats();
        assertThat(stats.ewmaErrorRate()).isLessThan(0.9);
    }

    // ── 3. OPEN → HALF_OPEN after openDuration ───────────────────────────────

    @Test
    void transitionsToHalfOpenAfterOpenDuration() throws Exception {
        forceOpen();
        assertThat(breaker.state()).isEqualTo(State.OPEN);

        Thread.sleep(FAST_OPEN.toMillis() + 20);

        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    // ── 4. HALF_OPEN → CLOSED when all probes succeed ────────────────────────

    @Test
    void closesAfterAllHalfOpenProbesSucceed() throws Exception {
        forceOpen();
        Thread.sleep(FAST_OPEN.toMillis() + 20);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);

        // Send 3 successful probes (config.halfOpenProbes = 3)
        for (int i = 0; i < 3; i++) {
            breaker.execute(() -> "probe-ok");
        }

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    // ── 5. HALF_OPEN → OPEN on any probe failure ────────────────────────────

    @Test
    void reopensWhenHalfOpenProbeFails() throws Exception {
        forceOpen();
        Thread.sleep(FAST_OPEN.toMillis() + 20);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);

        assertThatCode(
                        () ->
                                breaker.execute(
                                        () -> {
                                            throw new RuntimeException("probe failed");
                                        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    // ── 6. Stats accurately reflect EWMA error rate ──────────────────────────

    @Test
    void statsReflectAccurateEwmaErrorRate() throws Exception {
        // After only successes, error rate should be 0
        breaker.execute(() -> "ok");
        breaker.execute(() -> "ok");
        Stats statsAllSuccess = breaker.stats();
        assertThat(statsAllSuccess.ewmaErrorRate()).isEqualTo(0.0);
        assertThat(statsAllSuccess.totalCalls()).isEqualTo(2);
        assertThat(statsAllSuccess.totalSuccesses()).isEqualTo(2);
        assertThat(statsAllSuccess.totalFailures()).isEqualTo(0);

        // Use a fresh breaker to observe a known EWMA value
        Config cfg2 =
                Config.builder()
                        .ewmaAlpha(0.5)
                        .errorRateThreshold(0.99) // won't open
                        .predictiveThreshold(0.98)
                        .openDuration(FAST_OPEN)
                        .halfOpenProbes(1)
                        .latencyThreshold(Duration.ofSeconds(10))
                        .build();
        AdaptiveCircuitBreaker metered = AdaptiveCircuitBreaker.create(cfg2);

        // 1 failure → ewma = 0.5*1 + 0.5*0 = 0.5
        try {
            metered.execute(
                    () -> {
                        throw new RuntimeException("x");
                    });
        } catch (Exception ignored) {
        }
        Stats s1 = metered.stats();
        assertThat(s1.ewmaErrorRate()).isCloseTo(0.5, within(1e-9));
        assertThat(s1.errorTrend()).isGreaterThan(0.0); // rising

        // 1 success → ewma = 0.5*0 + 0.5*0.5 = 0.25
        metered.execute(() -> "ok");
        Stats s2 = metered.stats();
        assertThat(s2.ewmaErrorRate()).isCloseTo(0.25, within(1e-9));
        assertThat(s2.errorTrend()).isLessThan(0.0); // declining
    }

    // ── 7. reset() returns to CLOSED with zeroed stats ───────────────────────

    @Test
    void resetReturnsToClosed() throws Exception {
        forceOpen();
        assertThat(breaker.state()).isEqualTo(State.OPEN);

        breaker.reset();

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        Stats stats = breaker.stats();
        assertThat(stats.ewmaErrorRate()).isEqualTo(0.0);
        assertThat(stats.errorTrend()).isEqualTo(0.0);
        assertThat(stats.totalCalls()).isEqualTo(0);
        assertThat(stats.totalFailures()).isEqualTo(0);
        assertThat(stats.totalSuccesses()).isEqualTo(0);
        assertThat(stats.p99Latency()).isEqualTo(Duration.ZERO);
    }

    // ── 8. Fast-fail throws immediately when OPEN (supplier not invoked) ──────

    @Test
    void fastFailDoesNotInvokeSupplierWhenOpen() {
        forceOpen();
        AtomicInteger invocationCount = new AtomicInteger(0);

        assertThatThrownBy(
                        () ->
                                breaker.execute(
                                        () -> {
                                            invocationCount.incrementAndGet();
                                            return "should not run";
                                        }))
                .isInstanceOf(CircuitOpenException.class);

        assertThat(invocationCount.get()).isZero();
    }

    // ── 9. Latency spike triggers opening even with low error rate ────────────

    @Test
    void latencySpikeTriggersOpeningWithLowErrorRate() throws Exception {
        // Use a breaker with a very low latency threshold (10 ms) and high error threshold so only
        // latency can trigger opening.
        Config latencyCfg =
                Config.builder()
                        .ewmaAlpha(0.1)
                        .errorRateThreshold(0.99) // will not be reached
                        .predictiveThreshold(0.98)
                        .openDuration(FAST_OPEN)
                        .halfOpenProbes(1)
                        .latencyThreshold(Duration.ofMillis(10))
                        .build();
        AdaptiveCircuitBreaker latencyBreaker = AdaptiveCircuitBreaker.create(latencyCfg);

        // Execute a slow call that exceeds the latency threshold
        latencyBreaker.execute(
                () -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "slow";
                });

        assertThat(latencyBreaker.state()).isEqualTo(State.OPEN);
        // Error rate should be nearly 0 — it was a successful call, just slow
        assertThat(latencyBreaker.stats().totalFailures()).isEqualTo(0);
    }

    // ── 10. Parameterized threshold boundary tests ────────────────────────────

    static Stream<Arguments> thresholdBoundaries() {
        // (alpha, failures, successes, expectOpen)
        // With alpha=0.5, errorRateThreshold=0.5:
        //   ewma after N failures from 0: F1=0.5, F2=0.75, ...
        //   ewma after 1S then 1F: S=0 → F=0.5 (exactly threshold, not > threshold)
        //   After 2nd failure in a row from 0: 0.75 > 0.5 → open
        return Stream.of(
                // 2 consecutive failures from start → open (ewma=0.75 > 0.5)
                Arguments.of(0.5, 2, 0, true),
                // 1 failure only → ewma=0.5, NOT > threshold → closed
                Arguments.of(0.5, 1, 0, false),
                // 5 successes → ewma=0 → closed
                Arguments.of(0.5, 0, 5, false));
    }

    @ParameterizedTest
    @MethodSource("thresholdBoundaries")
    void thresholdBoundaryBehavior(double alpha, int failures, int successes, boolean expectOpen)
            throws Exception {
        Config cfg =
                Config.builder()
                        .ewmaAlpha(alpha)
                        .errorRateThreshold(0.5)
                        .predictiveThreshold(0.4) // set below errorRateThreshold
                        .openDuration(FAST_OPEN)
                        .halfOpenProbes(1)
                        .latencyThreshold(Duration.ofSeconds(10)) // disable latency trigger
                        .build();
        AdaptiveCircuitBreaker b = AdaptiveCircuitBreaker.create(cfg);

        for (int i = 0; i < successes; i++) {
            b.execute(() -> "ok");
        }
        for (int i = 0; i < failures; i++) {
            if (b.state() == State.OPEN) break; // already open, no point continuing
            try {
                b.execute(
                        () -> {
                            throw new RuntimeException("fail");
                        });
            } catch (Exception ignored) {
            }
        }

        if (expectOpen) {
            assertThat(b.state()).isEqualTo(State.OPEN);
        } else {
            assertThat(b.state()).isEqualTo(State.CLOSED);
        }
    }

    // ── 11. tryExecute returns Result.err(CircuitOpenException) when OPEN ─────

    @Test
    void tryExecuteReturnsErrWhenOpen() {
        forceOpen();
        Result<String, Exception> result = breaker.tryExecute(() -> "should not run");
        assertThat(result.isError()).isTrue();
        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Err.class,
                        err -> assertThat(err.error()).isInstanceOf(CircuitOpenException.class));
    }

    // ── 12. p99Latency is computed from reservoir ─────────────────────────────

    @Test
    void p99LatencyIsTrackedInReservoir() throws Exception {
        // All fast calls — p99 should be very small (well under 100ms)
        for (int i = 0; i < 10; i++) {
            int idx = i;
            breaker.execute(() -> idx);
        }
        Stats stats = breaker.stats();
        assertThat(stats.p99Latency()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(stats.p99Latency()).isLessThan(Duration.ofMillis(500));
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    /**
     * Drive the breaker into OPEN state by injecting enough failures to push EWMA past the
     * errorRateThreshold (0.5 with alpha=0.5 → 2 consecutive failures suffice).
     */
    private void forceOpen() {
        for (int i = 0; i < 5 && breaker.state() != State.OPEN; i++) {
            try {
                breaker.execute(
                        () -> {
                            throw new RuntimeException("force-open");
                        });
            } catch (Exception ignored) {
            }
        }
        if (breaker.state() != State.OPEN) {
            // Fallback: if EWMA hasn't crossed yet, the state machine still needs a push.
            // This shouldn't happen with alpha=0.5 and 5 failures, but guard anyway.
            throw new IllegalStateException(
                    "Could not force breaker to OPEN after 5 failures; current stats: "
                            + breaker.stats());
        }
    }
}
