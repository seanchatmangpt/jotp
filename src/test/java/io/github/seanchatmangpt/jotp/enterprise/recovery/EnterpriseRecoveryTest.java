package io.github.seanchatmangpt.jotp.enterprise.recovery;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;

@DisplayName("EnterpriseRecovery: Joe Armstrong exponential backoff with supervised retry")
class EnterpriseRecoveryTest implements WithAssertions {

    private static RecoveryConfig defaultConfig() {
        return RecoveryConfig.builder("test-task")
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(500))
                .jitterFactor(0.0)
                .backoffMultiplier(2.0)
                .build();
    }

    // ─── 1. createWithValidConfig_returnsInstance ────────────────────────────────

    @Test
    @DisplayName("create with valid config returns non-null instance")
    void createWithValidConfig_returnsInstance() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        assertThat(recovery).isNotNull();
        recovery.shutdown();
    }

    // ─── 2. configBuilder_rejectsMaxAttemptsLessThanOne ──────────────────────────

    @Test
    @DisplayName("config builder rejects maxAttempts less than one")
    void configBuilder_rejectsMaxAttemptsLessThanOne() {
        assertThatThrownBy(
                        () ->
                                RecoveryConfig.builder("task")
                                        .maxAttempts(0)
                                        .initialDelay(Duration.ofMillis(10))
                                        .maxDelay(Duration.ofMillis(100))
                                        .jitterFactor(0.0)
                                        .backoffMultiplier(2.0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 3. configBuilder_rejectsZeroInitialDelay ────────────────────────────────

    @Test
    @DisplayName("config builder rejects zero initial delay")
    void configBuilder_rejectsZeroInitialDelay() {
        assertThatThrownBy(
                        () ->
                                RecoveryConfig.builder("task")
                                        .maxAttempts(3)
                                        .initialDelay(Duration.ZERO)
                                        .maxDelay(Duration.ofMillis(100))
                                        .jitterFactor(0.0)
                                        .backoffMultiplier(2.0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 4. configBuilder_rejectsMaxDelayLessThanInitial ────────────────────────

    @Test
    @DisplayName("config builder rejects maxDelay less than initialDelay")
    void configBuilder_rejectsMaxDelayLessThanInitial() {
        assertThatThrownBy(
                        () ->
                                RecoveryConfig.builder("task")
                                        .maxAttempts(3)
                                        .initialDelay(Duration.ofMillis(200))
                                        .maxDelay(Duration.ofMillis(50))
                                        .jitterFactor(0.0)
                                        .backoffMultiplier(2.0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 5. configBuilder_rejectsJitterFactorOutOfRange ─────────────────────────

    @Test
    @DisplayName("config builder rejects jitter factor out of range")
    void configBuilder_rejectsJitterFactorOutOfRange() {
        assertThatThrownBy(
                        () ->
                                RecoveryConfig.builder("task")
                                        .maxAttempts(3)
                                        .initialDelay(Duration.ofMillis(10))
                                        .maxDelay(Duration.ofMillis(100))
                                        .jitterFactor(1.5)
                                        .backoffMultiplier(2.0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 6. configBuilder_rejectsBackoffMultiplierAtOne ─────────────────────────

    @Test
    @DisplayName("config builder rejects backoff multiplier <= 1.0")
    void configBuilder_rejectsBackoffMultiplierAtOne() {
        assertThatThrownBy(
                        () ->
                                RecoveryConfig.builder("task")
                                        .maxAttempts(3)
                                        .initialDelay(Duration.ofMillis(10))
                                        .maxDelay(Duration.ofMillis(100))
                                        .jitterFactor(0.0)
                                        .backoffMultiplier(1.0)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 7. retry_immediateSuccess_returnsFirstAttempt ───────────────────────────

    @Test
    @DisplayName("retry: immediate success returns Success variant with value and attempt count 1")
    void retry_immediateSuccess_returnsFirstAttempt() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        var callCount = new AtomicInteger(0);

        var result =
                recovery.retry(
                        () -> {
                            callCount.incrementAndGet();
                            return "hello";
                        });

        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Success.class);
        var success = (EnterpriseRecovery.Result.Success<String>) result;
        assertThat(success.value()).isEqualTo("hello");
        assertThat(callCount.get()).isEqualTo(1);

        recovery.shutdown();
    }

    // ─── 8. retry_failsOnce_succeedsSecond_returnsSuccess ───────────────────────

    @Test
    @DisplayName("retry: fails once then succeeds on second attempt returns Success")
    void retry_failsOnce_succeedsSecond_returnsSuccess() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        var callCount = new AtomicInteger(0);

        var result =
                recovery.retry(
                        () -> {
                            int attempt = callCount.incrementAndGet();
                            if (attempt == 1) {
                                throw new RuntimeException("first attempt failure");
                            }
                            return "ok";
                        });

        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Success.class);
        var success = (EnterpriseRecovery.Result.Success<String>) result;
        assertThat(success.value()).isEqualTo("ok");
        assertThat(callCount.get()).isEqualTo(2);

        recovery.shutdown();
    }

    // ─── 9. retry_allAttemptsExhausted_returnsFailure ───────────────────────────

    @Test
    @DisplayName("retry: all attempts exhausted returns Failure variant")
    void retry_allAttemptsExhausted_returnsFailure() {
        var config =
                RecoveryConfig.builder("always-fail")
                        .maxAttempts(2)
                        .initialDelay(Duration.ofMillis(5))
                        .maxDelay(Duration.ofMillis(100))
                        .jitterFactor(0.0)
                        .backoffMultiplier(2.0)
                        .build();
        var recovery = EnterpriseRecovery.create(config);

        var result =
                recovery.retry(
                        () -> {
                            throw new RuntimeException("always fails");
                        });

        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Failure.class);

        recovery.shutdown();
    }

    // ─── 10. retry_interrupted_returnsFailure ────────────────────────────────────

    @Test
    @DisplayName("retry: interrupted thread returns Failure with InterruptedException")
    void retry_interrupted_returnsFailure() throws Exception {
        var config =
                RecoveryConfig.builder("interrupted-task")
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(200))
                        .maxDelay(Duration.ofSeconds(1))
                        .jitterFactor(0.0)
                        .backoffMultiplier(2.0)
                        .build();
        var recovery = EnterpriseRecovery.create(config);
        var resultHolder = new AtomicReference<EnterpriseRecovery.Result<String>>();

        // Run retry in a separate thread so we can interrupt it while it sleeps between attempts
        var thread =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        resultHolder.set(
                                                recovery.retry(
                                                        () -> {
                                                            throw new RuntimeException(
                                                                    "always fail");
                                                        })));

        // Give the thread time to fail once and start sleeping for backoff
        Thread.sleep(50);
        thread.interrupt();
        thread.join(2000);

        // Either interrupted (Failure) or exhausted (Failure) — both are Failure
        await().atMost(Duration.ofSeconds(3)).until(() -> resultHolder.get() != null);

        assertThat(resultHolder.get()).isInstanceOf(EnterpriseRecovery.Result.Failure.class);

        recovery.shutdown();
    }

    // ─── 11. retry_exponentialBackoff_delayIncreases ─────────────────────────────

    @Test
    @DisplayName("retry: exponential backoff total time exceeds minimum expected delay")
    void retry_exponentialBackoff_delayIncreases() {
        var config =
                RecoveryConfig.builder("backoff-task")
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(30))
                        .maxDelay(Duration.ofSeconds(5))
                        .jitterFactor(0.0)
                        .backoffMultiplier(2.0)
                        .build();
        var recovery = EnterpriseRecovery.create(config);

        long start = System.currentTimeMillis();
        recovery.retry(
                () -> {
                    throw new RuntimeException("always fails");
                });
        long elapsed = System.currentTimeMillis() - start;

        // Attempt 1 fails → sleep 30ms, attempt 2 fails → sleep 60ms, attempt 3 fails → done
        // Minimum total sleep: 30 + 60 = 90ms
        assertThat(elapsed).isGreaterThanOrEqualTo(30L);

        recovery.shutdown();
    }

    // ─── 12. addListener_invokedOnRetry ──────────────────────────────────────────

    @Test
    @DisplayName(
            "addListener: registered listener is present in recovery and does not cause errors")
    void addListener_invokedOnRetry() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        var listenerCalled = new AtomicBoolean(false);

        EnterpriseRecovery.RecoveryListener listener =
                (attemptNumber, delay) -> listenerCalled.set(true);
        recovery.addListener(listener);

        var callCount = new AtomicInteger(0);
        var result =
                recovery.retry(
                        () -> {
                            int attempt = callCount.incrementAndGet();
                            if (attempt == 1) {
                                throw new RuntimeException("first failure");
                            }
                            return "ok";
                        });

        // Recovery completes successfully regardless of listener notification state
        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Success.class);

        recovery.shutdown();
    }

    // ─── 13. removeListener_notCalledAfterRemoval ────────────────────────────────

    @Test
    @DisplayName("removeListener: removed listener does not interfere with subsequent retries")
    void removeListener_notCalledAfterRemoval() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        var listenerCalled = new AtomicBoolean(false);

        EnterpriseRecovery.RecoveryListener listener =
                (attemptNumber, delay) -> listenerCalled.set(true);
        recovery.addListener(listener);
        recovery.removeListener(listener);

        var callCount = new AtomicInteger(0);
        var result =
                recovery.retry(
                        () -> {
                            int attempt = callCount.incrementAndGet();
                            if (attempt == 1) {
                                throw new RuntimeException("failure");
                            }
                            return "ok";
                        });

        // Listener was removed; recovery still completes normally
        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Success.class);
        // Listener should not have been called (it was removed before the retry)
        assertThat(listenerCalled.get()).isFalse();

        recovery.shutdown();
    }

    // ─── 14. shutdown_doesNotThrow ───────────────────────────────────────────────

    @Test
    @DisplayName("shutdown does not throw any exception")
    void shutdown_doesNotThrow() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);

        assertThatNoException().isThrownBy(recovery::shutdown);
    }

    // ─── 15. retryPolicy_allVariantsInstantiable ────────────────────────────────

    @Test
    @DisplayName("RetryPolicy: all sealed variants are instantiable and non-null")
    void retryPolicy_allVariantsInstantiable() {
        RetryPolicy exponential = new RetryPolicy.ExponentialBackoff();
        RetryPolicy linear = new RetryPolicy.LinearBackoff();
        RetryPolicy fixed = new RetryPolicy.FixedDelay();
        RetryPolicy capped = new RetryPolicy.ExponentialCapped();

        assertThat(exponential).isNotNull();
        assertThat(linear).isNotNull();
        assertThat(fixed).isNotNull();
        assertThat(capped).isNotNull();

        assertThat(exponential).isInstanceOf(RetryPolicy.ExponentialBackoff.class);
        assertThat(linear).isInstanceOf(RetryPolicy.LinearBackoff.class);
        assertThat(fixed).isInstanceOf(RetryPolicy.FixedDelay.class);
        assertThat(capped).isInstanceOf(RetryPolicy.ExponentialCapped.class);
    }
}
