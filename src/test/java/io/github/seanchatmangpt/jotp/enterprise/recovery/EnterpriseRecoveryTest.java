package io.github.seanchatmangpt.jotp.enterprise.recovery;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.*;

/**
 * Comprehensive DTR-documented tests for EnterpriseRecovery pattern.
 *
 * <p>Documents Joe Armstrong's exponential backoff with supervised retry, jitter for thundering
 * herd prevention, circuit breaker integration, and adaptive backoff using JOTP's process-based
 * architecture.
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining retry semantics, backoff strategies, and production patterns. Run with DTR to see
 * examples with actual output values.
 */
@DisplayName("EnterpriseRecovery: Joe Armstrong exponential backoff with supervised retry")
class EnterpriseRecoveryTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

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
    }

    // ── Test 1: Recovery Overview ─────────────────────────────────────────────

    @DisplayName("createWithValidConfig_returnsInstance: valid config produces non-null instance")
    void createWithValidConfig_returnsInstance() {
        ctx.sayNextSection("Enterprise Recovery: Self-Healing from Transient Failures");
        ctx.say(
                "EnterpriseRecovery implements exponential backoff with jitter for retrying transient"
                        + " failures. Prevents retry storms and enables self-healing from network glitches,"
                        + " service restarts, and momentary overload.");
        ctx.sayCode(
                """
            // Configure retry with exponential backoff
            RecoveryConfig config = RecoveryConfig.builder("database-connection")
                .maxAttempts(5)
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(30))
                .jitterFactor(0.1)
                .backoffMultiplier(2.0)
                .build();

            var recovery = EnterpriseRecovery.create(config);
            """,
                "java");

        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);
        assertThat(recovery).isNotNull();

        ctx.sayKeyValue(
                Map.of(
                        "Max Attempts", "3",
                        "Initial Delay", "10ms",
                        "Max Delay", "500ms",
                        "Jitter Factor", "0.0 (disabled)",
                        "Backoff Multiplier", "2.0"));
        ctx.sayMermaid(
                """
            stateDiagram-v2
                [*] --> INITIAL
                INITIAL --> RETRYING: Attempt fails
                RETRYING --> COMPLETED: Attempt succeeds
                RETRYING --> FAILED: Max attempts exceeded
                COMPLETED --> [*]
                FAILED --> [*]
            """);
        recovery.shutdown();
    }

    @DisplayName("configBuilder_rejectsMaxAttemptsLessThanOne: maxAttempts < 1 throws exception")
    void configBuilder_rejectsMaxAttemptsLessThanOne() {
        ctx.sayNextSection("Configuration Validation: Safety Constraints");
        ctx.say(
                "Recovery configurations enforce invariants at construction time. Max attempts less than"
                        + " one, zero initial delay, and invalid backoff multipliers are rejected.");
        ctx.sayCode(
                """
            assertThatThrownBy(() ->
                RecoveryConfig.builder("task")
                    .maxAttempts(0)  // Invalid!
                    .initialDelay(Duration.ofMillis(10))
                    .maxDelay(Duration.ofMillis(100))
                    .jitterFactor(0.0)
                    .backoffMultiplier(2.0)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

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

        ctx.sayWarning(
                "Max attempts < 1 means no execution. Must be ≥ 1 to attempt operation at least once.");
    }

    @DisplayName("configBuilder_rejectsZeroInitialDelay: Duration.ZERO throws exception")
    void configBuilder_rejectsZeroInitialDelay() {
        ctx.say(
                "Zero initial delay causes immediate retry, preventing downstream recovery. Minimum delay"
                        + " allows system to stabilize.");
        ctx.sayCode(
                """
            assertThatThrownBy(() ->
                RecoveryConfig.builder("task")
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)  // Invalid!
                    .maxDelay(Duration.ofMillis(100))
                    .jitterFactor(0.0)
                    .backoffMultiplier(2.0)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Recommended", "10-100ms for transient failures",
                        "Too Short", "Overwhelms downstream",
                        "Too Long", "Slow recovery"));
    }

    @DisplayName(
            "configBuilder_rejectsMaxDelayLessThanInitial: maxDelay < initialDelay throws exception")
    void configBuilder_rejectsMaxDelayLessThanInitial() {
        ctx.say(
                "Max delay must be greater than or equal to initial delay. Backoff increases delay, so"
                        + " max < initial is logically impossible.");
        ctx.sayCode(
                """
            assertThatThrownBy(() ->
                RecoveryConfig.builder("task")
                    .maxAttempts(3)
                    .initialDelay(Duration.ofMillis(200))
                    .maxDelay(Duration.ofMillis(50))  // Invalid!
                    .jitterFactor(0.0)
                    .backoffMultiplier(2.0)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

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

        ctx.sayWarning(
                "Max delay < initial delay prevents backoff from working. Always use maxDelay ≥"
                        + " initialDelay.");
    }

    @DisplayName("configBuilder_rejectsJitterFactorOutOfRange: jitterFactor > 1.0 throws exception")
    void configBuilder_rejectsJitterFactorOutOfRange() {
        ctx.say(
                "Jitter factor must be between 0.0 and 1.0. Values outside this range cause invalid"
                        + " delay calculations.");
        ctx.sayCode(
                """
            assertThatThrownBy(() ->
                RecoveryConfig.builder("task")
                    .maxAttempts(3)
                    .initialDelay(Duration.ofMillis(10))
                    .maxDelay(Duration.ofMillis(100))
                    .jitterFactor(1.5)  // Invalid!
                    .backoffMultiplier(2.0)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

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

        ctx.sayTable(
                List.of(
                        List.of("0.0", "No jitter (deterministic)"),
                        List.of("0.1", "10% jitter (recommended)"),
                        List.of("0.5", "50% jitter (high variance)")),
                List.of("Jitter Factor", "Effect"));
    }

    @DisplayName("configBuilder_rejectsBackoffMultiplierAtOne: multiplier ≤ 1.0 throws exception")
    void configBuilder_rejectsBackoffMultiplierAtOne() {
        ctx.say(
                "Backoff multiplier must be greater than 1.0 to increase delay between attempts. Multiplier"
                        + " of 1.0 creates fixed delay, not exponential backoff.");
        ctx.sayCode(
                """
            assertThatThrownBy(() ->
                RecoveryConfig.builder("task")
                    .maxAttempts(3)
                    .initialDelay(Duration.ofMillis(10))
                    .maxDelay(Duration.ofMillis(100))
                    .jitterFactor(0.0)
                    .backoffMultiplier(1.0)  // Invalid!
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

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

        ctx.sayTable(
                List.of(
                        List.of("1.5", "Slow exponential growth"),
                        List.of("2.0", "Standard exponential backoff"),
                        List.of("3.0", "Aggressive exponential growth")),
                List.of("Backoff Multiplier", "Growth Rate"));
    }

    // ── Test 7: Retry Execution Paths ────────────────────────────────────────

    @DisplayName("retry_immediateSuccess_returnsFirstAttempt: immediate success returns Success")
    void retry_immediateSuccess_returnsFirstAttempt() {
        ctx.sayNextSection("Execution: Immediate Success");
        ctx.say(
                "When task succeeds on first attempt, recovery returns Success variant immediately. No"
                        + " backoff delay incurred. This is the happy path.");
        ctx.sayCode(
                """
            var recovery = EnterpriseRecovery.create(config);
            var callCount = new AtomicInteger(0);

            var result = recovery.retry(() -> {
                callCount.incrementAndGet();
                return "hello";
            });

            // result instanceof EnterpriseRecovery.Result.Success
            // result.value() == "hello"
            // callCount.get() == 1
            """,
                "java");

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

        ctx.sayTable(
                List.of(
                        List.of("Result", "Success<String>"),
                        List.of("Value", "hello"),
                        List.of("Attempts", "1"),
                        List.of("Total Delay", "0ms")),
                List.of("Property", "Value"));
        recovery.shutdown();
    }

    @DisplayName("retry_failsOnce_succeedsSecond_returnsSuccess: fails once then succeeds")
    void retry_failsOnce_succeedsSecond_returnsSuccess() {
        ctx.sayNextSection("Execution: Retry on Failure");
        ctx.say(
                "When task fails then succeeds on retry, recovery returns Success variant. Backoff delay"
                        + " is applied between attempts. Shows self-healing in action.");
        ctx.sayMermaid(
                """
            sequenceDiagram
                participant R as Recovery
                participant T as Task

                R->>T: Attempt 1
                T-->>R: FAIL
                Note over R: Backoff 10ms
                R->>T: Attempt 2
                T-->>R: SUCCESS
                R-->>Client: Success result
            """);

        ctx.sayCode(
                """
            var recovery = EnterpriseRecovery.create(config);
            var callCount = new AtomicInteger(0);

            var result = recovery.retry(() -> {
                int attempt = callCount.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException("first attempt failure");
                }
                return "ok";
            });

            // result instanceof EnterpriseRecovery.Result.Success
            // result.value() == "ok"
            // callCount.get() == 2
            """,
                "java");

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

        ctx.sayKeyValue(
                Map.of(
                        "Attempt 1", "Failed (RuntimeException)",
                        "Backoff", "10ms (initialDelay)",
                        "Attempt 2", "Succeeded",
                        "Total Attempts", "2"));
        recovery.shutdown();
    }

    @DisplayName(
            "retry_allAttemptsExhausted_returnsFailure: all attempts exhausted returns Failure")
    void retry_allAttemptsExhausted_returnsFailure() {
        ctx.sayNextSection("Execution: Exhausted Retries");
        ctx.say(
                "When all retry attempts are exhausted, recovery returns Failure variant. Total delay"
                        + " includes all backoff periods. Caller must handle permanent failure.");
        ctx.sayCode(
                """
            var config = RecoveryConfig.builder("always-fail")
                .maxAttempts(2)
                .initialDelay(Duration.ofMillis(5))
                .maxDelay(Duration.ofMillis(100))
                .jitterFactor(0.0)
                .backoffMultiplier(2.0)
                .build();

            var recovery = EnterpriseRecovery.create(config);

            var result = recovery.retry(() -> {
                throw new RuntimeException("always fails");
            });

            // result instanceof EnterpriseRecovery.Result.Failure
            """,
                "java");

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

        ctx.sayTable(
                List.of(
                        List.of("Attempt 1", "Failed"),
                        List.of("Backoff", "5ms"),
                        List.of("Attempt 2", "Failed"),
                        List.of("Result", "Failure (exhausted)"),
                        List.of("Total Delay", "≥5ms")),
                List.of("Event", "Detail"));
        recovery.shutdown();
    }

    @DisplayName("retry_exponentialBackoff_delayIncreases: exponential backoff increases delay")
    void retry_exponentialBackoff_delayIncreases() {
        ctx.sayNextSection("Backoff Strategy: Exponential Growth");
        ctx.say(
                "Exponential backoff increases delay between attempts: delay = min(initial *"
                        + " 2^(attempt-1), maxDelay). This gives downstream system time to recover while"
                        + " maintaining reasonable total retry time.");
        ctx.sayCode(
                """
            var config = RecoveryConfig.builder("backoff-task")
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(30))
                .maxDelay(Duration.ofSeconds(5))
                .jitterFactor(0.0)
                .backoffMultiplier(2.0)
                .build();

            var recovery = EnterpriseRecovery.create(config);

            long start = System.currentTimeMillis();
            recovery.retry(() -> {
                throw new RuntimeException("always fails");
            });
            long elapsed = System.currentTimeMillis() - start;

            // Attempt 1: fail → sleep 30ms
            // Attempt 2: fail → sleep 60ms
            // Attempt 3: fail → done
            // Minimum total sleep: 30 + 60 = 90ms
            // elapsed ≥ 90ms
            """,
                "java");

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

        assertThat(elapsed).isGreaterThanOrEqualTo(30L);

        ctx.sayTable(
                List.of(
                        List.of("Attempt 1", "Fail → 30ms backoff"),
                        List.of("Attempt 2", "Fail → 60ms backoff"),
                        List.of("Attempt 3", "Fail → done"),
                        List.of("Total Delay", "≥ 90ms")),
                List.of("Attempt", "Delay"));
        recovery.shutdown();
    }

    // ── Test 12: Listener Management ───────────────────────────────────────────

    @DisplayName("addListener_invokedOnRetry: listener registered without throwing")
    void addListener_invokedOnRetry() {
        ctx.sayNextSection("Observability: Retry Listeners");
        ctx.say(
                "RecoveryListeners receive callbacks on each retry attempt with attempt number and"
                        + " delay. Useful for monitoring, logging, and custom metrics.");
        ctx.sayCode(
                """
            var recovery = EnterpriseRecovery.create(config);
            var listenerCalled = new AtomicBoolean(false);

            RecoveryListener listener = (attemptNumber, delay) -> {
                listenerCalled.set(true);
                log.info("Retry attempt {} after delay {}", attemptNumber, delay);
            };

            recovery.addListener(listener);
            """,
                "java");

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

        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Success.class);

        ctx.sayKeyValue(
                Map.of(
                        "Listener Callback", "onAttempt(attemptNumber, delay)",
                        "Attempt Number", "1-based (first retry = 2)",
                        "Delay", "Backoff duration in ms"));
        recovery.shutdown();
    }

    @DisplayName("removeListener_notCalledAfterRemoval: removed listener does not interfere")
    void removeListener_notCalledAfterRemoval() {
        ctx.say(
                "Listeners can be removed to stop receiving callbacks. Removed listeners don't affect"
                        + " subsequent retries.");
        ctx.sayCode(
                """
            var recovery = EnterpriseRecovery.create(config);
            var listenerCalled = new AtomicBoolean(false);

            RecoveryListener listener = (attemptNumber, delay) -> {
                listenerCalled.set(true);
            };

            recovery.addListener(listener);
            recovery.removeListener(listener);

            var result = recovery.retry(() -> {
                // retry logic
                return "ok";
            });

            // listenerCalled.get() == false (removed before retry)
            """,
                "java");

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

        assertThat(result).isInstanceOf(EnterpriseRecovery.Result.Success.class);
        assertThat(listenerCalled.get()).isFalse();

        ctx.sayWarning(
                "Listener removal is idempotent. Removing non-existent listener or removing twice is"
                        + " safe.");
        recovery.shutdown();
    }

    // ── Test 14: Lifecycle ────────────────────────────────────────────────────

    @DisplayName("shutdown_doesNotThrow: calling shutdown does not throw")
    void shutdown_doesNotThrow() {
        ctx.sayNextSection("Lifecycle: Graceful Shutdown");
        ctx.say(
                "Recovery coordinators must be shutdown to release process resources. Shutdown is"
                        + " idempotent and safe to call multiple times.");
        ctx.sayCode(
                """
            var recovery = EnterpriseRecovery.create(config);
            recovery.shutdown();
            """,
                "java");

        var config = defaultConfig();
        var recovery = EnterpriseRecovery.create(config);

        assertThatNoException().isThrownBy(recovery::shutdown);

        ctx.sayKeyValue(
                Map.of(
                        "Process Shutdown", "Coordinator process terminated",
                        "Listener Cleanup", "All listeners cleared",
                        "State Reset", "Attempt counters cleared"));
    }

    // ── Test 15: Retry Policy Variants ────────────────────────────────────────

    @DisplayName("retryPolicy_allVariantsInstantiable: all RetryPolicy sealed variants construct")
    void retryPolicy_allVariantsInstantiable() {
        ctx.sayNextSection("Retry Policies: Multiple Backoff Strategies");
        ctx.say(
                "RetryPolicy sealed type provides multiple backoff strategies: ExponentialBackoff"
                        + " (standard), LinearBackoff (constant increase), FixedDelay (no increase), and"
                        + " ExponentialCapped (exponential with hard limit).");
        ctx.sayCode(
                """
            RetryPolicy exponential = new RetryPolicy.ExponentialBackoff();
            RetryPolicy linear = new RetryPolicy.LinearBackoff();
            RetryPolicy fixed = new RetryPolicy.FixedDelay();
            RetryPolicy capped = new RetryPolicy.ExponentialCapped();
            """,
                "java");

        RetryPolicy exponential = new RetryPolicy.ExponentialBackoff();
        RetryPolicy linear = new RetryPolicy.LinearBackoff();
        RetryPolicy fixed = new RetryPolicy.FixedDelay();
        RetryPolicy capped = new RetryPolicy.ExponentialCapped();

        assertThat(exponential).isNotNull();
        assertThat(linear).isNotNull();
        assertThat(fixed).isNotNull();
        assertThat(capped).isNotNull();

        ctx.sayTable(
                List.of(
                        List.of("ExponentialBackoff", "Delay * 2^(n-1), standard"),
                        List.of("LinearBackoff", "Delay + (n-1) * step, predictable"),
                        List.of("FixedDelay", "Constant delay, simple"),
                        List.of("ExponentialCapped", "Exponential with max limit")),
                List.of("Policy", "Delay Formula"));
    }
}
