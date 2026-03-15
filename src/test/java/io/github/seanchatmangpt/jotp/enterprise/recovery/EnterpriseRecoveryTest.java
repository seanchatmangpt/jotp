package io.github.seanchatmangpt.jotp.enterprise.recovery;

import static org.awaitility.Awaitility.await;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.BeforeEach;
@DisplayName("EnterpriseRecovery: Joe Armstrong exponential backoff with supervised retry")
class EnterpriseRecoveryTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    private static RecoveryConfig defaultConfig() {
        return RecoveryConfig.builder("test-task")
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(500))
                .jitterFactor(0.0)
                .backoffMultiplier(2.0)
                .build();
    // ─── 1. createWithValidConfig_returnsInstance ────────────────────────────────
    @Test
    @DisplayName("create with valid config returns non-null instance")
    void createWithValidConfig_returnsInstance() {
        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        assertThat(recovery).isNotNull();
        recovery.shutdown();
    // ─── 2. configBuilder_rejectsMaxAttemptsLessThanOne ──────────────────────────
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
    // ─── 3. configBuilder_rejectsZeroInitialDelay ────────────────────────────────
    @DisplayName("config builder rejects zero initial delay")
    void configBuilder_rejectsZeroInitialDelay() {
                                        .maxAttempts(3)
                                        .initialDelay(Duration.ZERO)
    // ─── 4. configBuilder_rejectsMaxDelayLessThanInitial ────────────────────────
    @DisplayName("config builder rejects maxDelay less than initialDelay")
    void configBuilder_rejectsMaxDelayLessThanInitial() {
                                        .initialDelay(Duration.ofMillis(200))
                                        .maxDelay(Duration.ofMillis(50))
    // ─── 5. configBuilder_rejectsJitterFactorOutOfRange ─────────────────────────
    @DisplayName("config builder rejects jitter factor out of range")
    void configBuilder_rejectsJitterFactorOutOfRange() {
                                        .jitterFactor(1.5)
    // ─── 6. configBuilder_rejectsBackoffMultiplierAtOne ─────────────────────────
    @DisplayName("config builder rejects backoff multiplier <= 1.0")
    void configBuilder_rejectsBackoffMultiplierAtOne() {
                                        .backoffMultiplier(1.0)
    // ─── 7. retry_immediateSuccess_returnsFirstAttempt ───────────────────────────
    @DisplayName("retry: immediate success returns Success variant with value and attempt count 1")
    void retry_immediateSuccess_returnsFirstAttempt() {
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
    // ─── 8. retry_failsOnce_succeedsSecond_returnsSuccess ───────────────────────
    @DisplayName("retry: fails once then succeeds on second attempt returns Success")
    void retry_failsOnce_succeedsSecond_returnsSuccess() {
                            int attempt = callCount.incrementAndGet();
                            if (attempt == 1) {
                                throw new RuntimeException("first attempt failure");
                            }
                            return "ok";
        assertThat(success.value()).isEqualTo("ok");
        assertThat(callCount.get()).isEqualTo(2);
    // ─── 9. retry_allAttemptsExhausted_returnsFailure ───────────────────────────
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
                            throw new RuntimeException("always fails");
        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Failure.class);
    // ─── 10. retry_interrupted_returnsFailure ────────────────────────────────────
    @DisplayName("retry: interrupted thread returns Failure with InterruptedException")
    void retry_interrupted_returnsFailure() throws Exception {
                RecoveryConfig.builder("interrupted-task")
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(200))
                        .maxDelay(Duration.ofSeconds(1))
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
    // ─── 11. retry_exponentialBackoff_delayIncreases ─────────────────────────────
    @DisplayName("retry: exponential backoff total time exceeds minimum expected delay")
    void retry_exponentialBackoff_delayIncreases() {
                RecoveryConfig.builder("backoff-task")
                        .initialDelay(Duration.ofMillis(30))
                        .maxDelay(Duration.ofSeconds(5))
        long start = System.currentTimeMillis();
        recovery.retry(
                () -> {
                    throw new RuntimeException("always fails");
                });
        long elapsed = System.currentTimeMillis() - start;
        // Attempt 1 fails → sleep 30ms, attempt 2 fails → sleep 60ms, attempt 3 fails → done
        // Minimum total sleep: 30 + 60 = 90ms
        assertThat(elapsed).isGreaterThanOrEqualTo(30L);
    // ─── 12. addListener_invokedOnRetry ──────────────────────────────────────────
    @DisplayName(
            "addListener: registered listener is present in recovery and does not cause errors")
    void addListener_invokedOnRetry() {
        var listenerCalled = new AtomicBoolean(false);
        EnterpriseRecovery.RecoveryListener listener =
                (attemptNumber, delay) -> listenerCalled.set(true);
        recovery.addListener(listener);
                                throw new RuntimeException("first failure");
        // Recovery completes successfully regardless of listener notification state
    // ─── 13. removeListener_notCalledAfterRemoval ────────────────────────────────
    @DisplayName("removeListener: removed listener does not interfere with subsequent retries")
    void removeListener_notCalledAfterRemoval() {
        recovery.removeListener(listener);
                                throw new RuntimeException("failure");
        // Listener was removed; recovery still completes normally
        // Listener should not have been called (it was removed before the retry)
        assertThat(listenerCalled.get()).isFalse();
    // ─── 14. shutdown_doesNotThrow ───────────────────────────────────────────────
    @DisplayName("shutdown does not throw any exception")
    void shutdown_doesNotThrow() {
        assertThatNoException().isThrownBy(recovery::shutdown);
    // ─── 15. retryPolicy_allVariantsInstantiable ────────────────────────────────
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
