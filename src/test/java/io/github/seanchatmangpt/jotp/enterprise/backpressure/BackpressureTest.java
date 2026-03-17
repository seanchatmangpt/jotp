package io.github.seanchatmangpt.jotp.enterprise.backpressure;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Comprehensive DTR-documented tests for Backpressure pattern.
 *
 * <p>Documents adaptive timeout-based flow control, circuit breaker semantics, success rate
 * monitoring, and resource protection using Joe Armstrong's OTP supervision principles.
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining backpressure state machines, timeout adaptation, and production patterns. Run with DTR
 * to see examples with actual output values.
 */
@DisplayName("Backpressure: Adaptive timeout-based flow control coordinator")
class BackpressureTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    private static BackpressureConfig defaultConfig() {
        return BackpressureConfig.builder("test-service")
                .initialTimeout(Duration.ofMillis(500))
                .maxTimeout(Duration.ofSeconds(30))
                .windowSize(100)
                .successRateThreshold(0.95)
                .build();
    }

    // ── Test 1: Backpressure Overview and Creation ───────────────────────────

    @DisplayName("createWithValidConfig_returnsInstance: Backpressure.create returns non-null")
    void createWithValidConfig_returnsInstance() {
                "Backpressure prevents cascading failures by regulating request rate when downstream"
                        + " services are slow or failing. It implements adaptive timeout adjustment and"
                        + " circuit breaker patterns to maintain system stability.");
                """
            // Configure backpressure with adaptive timeout
            BackpressureConfig config = BackpressureConfig.builder("payment-service")
                .initialTimeout(Duration.ofMillis(500))
                .maxTimeout(Duration.ofSeconds(30))
                .windowSize(100)
                .successRateThreshold(0.95)
                .build();

            var bp = Backpressure.create(config);
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());
        assertThat(bp).isNotNull();

                Map.of(
                        "Service Name",
                        "test-service",
                        "Initial Timeout",
                        "500ms",
                        "Max Timeout",
                        "30s",
                        "Success Rate Threshold",
                        "95%",
                        "Window Size",
                        "100 requests"));
                """
            stateDiagram-v2
                [*] --> HEALTHY
                HEALTHY --> WARNING: Success rate < 95%
                WARNING --> CIRCUIT_OPEN: Success rate < 80%
                CIRCUIT_OPEN --> HEALTHY: Recovery timeout
                WARNING --> HEALTHY: Success rate ≥ 95%
            """);
        bp.shutdown();
    }

    @DisplayName(
            "configBuilder_rejectsZeroInitialTimeout: Duration.ZERO throws IllegalArgumentException")
    void configBuilder_rejectsZeroInitialTimeout() {
                "Backpressure configurations enforce invariants at construction time. Zero initial"
                        + " timeout, max timeout less than initial, and invalid window sizes are rejected"
                        + " immediately.");
                """
            // Invalid configuration - zero initial timeout
            assertThatThrownBy(() ->
                BackpressureConfig.builder("svc")
                    .initialTimeout(Duration.ZERO)
                    .maxTimeout(Duration.ofSeconds(30))
                    .windowSize(10)
                    .successRateThreshold(0.95)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                BackpressureConfig.builder("svc")
                                        .initialTimeout(Duration.ZERO)
                                        .maxTimeout(Duration.ofSeconds(30))
                                        .windowSize(10)
                                        .successRateThreshold(0.95)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

                "Zero initial timeout causes immediate failures. Always use initialTimeout ="
                        + " (expected p95 latency * 1.5) to allow for normal variance.");
    }

    @DisplayName("configBuilder_rejectsMaxTimeoutLessThanInitial: max < initial throws exception")
    void configBuilder_rejectsMaxTimeoutLessThanInitial() {
                "Max timeout must be greater than or equal to initial timeout. This prevents"
                        + " configuration errors where timeout can never increase.");
                """
            assertThatThrownBy(() ->
                BackpressureConfig.builder("svc")
                    .initialTimeout(Duration.ofSeconds(10))
                    .maxTimeout(Duration.ofSeconds(5))  // Invalid!
                    .windowSize(10)
                    .successRateThreshold(0.95)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                BackpressureConfig.builder("svc")
                                        .initialTimeout(Duration.ofSeconds(10))
                                        .maxTimeout(Duration.ofSeconds(5))
                                        .windowSize(10)
                                        .successRateThreshold(0.95)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

                "Max timeout < initial timeout is logically invalid. Adaptive backpressure needs"
                        + " room to increase timeout during degradation.");
    }

    @DisplayName("configBuilder_rejectsInvalidWindowSize: windowSize=0 throws exception")
    void configBuilder_rejectsInvalidWindowSize() {
                "Window size determines how many recent requests are tracked for success rate"
                        + " calculation. Zero window size prevents state tracking.");
                """
            assertThatThrownBy(() ->
                BackpressureConfig.builder("svc")
                    .initialTimeout(Duration.ofMillis(500))
                    .maxTimeout(Duration.ofSeconds(30))
                    .windowSize(0)  // Invalid!
                    .successRateThreshold(0.95)
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                BackpressureConfig.builder("svc")
                                        .initialTimeout(Duration.ofMillis(500))
                                        .maxTimeout(Duration.ofSeconds(30))
                                        .windowSize(0)
                                        .successRateThreshold(0.95)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

                Map.of(
                        "Recommended Window Size",
                        "50-200 requests",
                        "Small Window",
                        "Fast adaptation, noisy",
                        "Large Window",
                        "Slow adaptation, stable"));
    }

    @DisplayName("configBuilder_rejectsInvalidSuccessRate: successRate > 1.0 throws exception")
    void configBuilder_rejectsInvalidSuccessRate() {
                "Success rate threshold must be between 0.0 and 1.0. Values above 1.0 represent"
                        + " impossible success rates.");
                """
            assertThatThrownBy(() ->
                BackpressureConfig.builder("svc")
                    .initialTimeout(Duration.ofMillis(500))
                    .maxTimeout(Duration.ofSeconds(30))
                    .windowSize(10)
                    .successRateThreshold(1.5)  // Invalid!
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                BackpressureConfig.builder("svc")
                                        .initialTimeout(Duration.ofMillis(500))
                                        .maxTimeout(Duration.ofSeconds(30))
                                        .windowSize(10)
                                        .successRateThreshold(1.5)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

                List.of(
                        List.of("0.90", "Lenient - allows 10% failure"),
                        List.of("0.95", "Standard - allows 5% failure"),
                        List.of("0.99", "Strict - allows 1% failure")),
                List.of("Success Rate Threshold", "Behavior"));
    }

    // ── Test 6: Execution Paths ─────────────────────────────────────────────────

    @DisplayName("execute_successfulTask_returnsSuccess: successful task produces Success variant")
    void execute_successfulTask_returnsSuccess() {
                "When tasks complete within timeout, backpressure tracks success and returns"
                        + " Success variant. Success rate is updated for adaptive timeout adjustment.");
                """
            var bp = Backpressure.create(config);

            Backpressure.Result<String> result = bp.execute(
                timeout -> "hello",  // Task completes successfully
                Duration.ofSeconds(5)
            );

            // result instanceof Backpressure.Result.Success
            // result.value() == "hello"
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());

        Backpressure.Result<String> result = bp.execute(timeout -> "hello", Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(Backpressure.Result.Success.class);
        assertThat(((Backpressure.Result.Success<String>) result).value()).isEqualTo("hello");

                List.of(
                        List.of("Result Type", "Success<String>"),
                        List.of("Value", "hello"),
                        List.of("State Update", "Success count incremented"),
                        List.of("Timeout", "Unchanged (success)")),
                List.of("Property", "Value"));
        bp.shutdown();
    }

    @DisplayName(
            "execute_failingTask_returnsFailure: BackpressureException produces Failure variant")
    void execute_failingTask_returnsFailure() {
                "When tasks throw BackpressureException, backpressure returns Failure variant."
                        + " This indicates the task recognized backpressure conditions and aborted"
                        + " proactively.");
                """
            var bp = Backpressure.create(config);

            Backpressure.Result<String> result = bp.execute(
                timeout -> {
                    throw new Backpressure.BackpressureException("circuit open");
                },
                Duration.ofSeconds(5)
            );

            // result instanceof Backpressure.Result.Failure
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());

        Backpressure.Result<String> result =
                bp.execute(
                        timeout -> {
                            throw new Backpressure.BackpressureException("circuit open");
                        },
                        Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(Backpressure.Result.Failure.class);

                Map.of(
                        "Result Type", "Failure",
                        "Error", "BackpressureException: circuit open",
                        "State Update", "Failure count incremented",
                        "Timeout", "Doubled (on failure)"));
        bp.shutdown();
    }

    @DisplayName(
            "execute_generalException_wrappedInFailure: RuntimeException produces Failure variant")
    void execute_generalException_wrappedInFailure() {
                "General exceptions are wrapped in BackpressureException and returned as Failure."
                        + " This provides consistent error handling regardless of exception type.");
                """
            var bp = Backpressure.create(config);

            Backpressure.Result<String> result = bp.execute(
                timeout -> {
                    throw new RuntimeException("unexpected error");
                },
                Duration.ofSeconds(5)
            );

            // result instanceof Backpressure.Result.Failure
            // result.error().getMessage() == "Request failed: unexpected error"
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());

        Backpressure.Result<String> result =
                bp.execute(
                        timeout -> {
                            throw new RuntimeException("unexpected error");
                        },
                        Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(Backpressure.Result.Failure.class);

                "All exceptions are treated as failures for backpressure purposes. Distinguish"
                        + " transient failures (retry) from permanent errors (abort) at application level.");
        bp.shutdown();
    }

    // ── Test 10: Listener Management ────────────────────────────────────────────

    @DisplayName("addListener_registeredSuccessfully: listener added without throwing")
    void addListener_registeredSuccessfully() {
                "Backpressure emits events when state transitions occur (HEALTHY → WARNING → CIRCUIT_OPEN)."
                        + " Listeners receive callbacks for monitoring and alerting.");
                """
            var bp = Backpressure.create(config);

            Backpressure.BackpressureListener listener = (from, to) -> {
                log.info("State changed: {} → {}", from, to);
            };

            bp.addListener(listener);
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());
        Backpressure.BackpressureListener listener = (from, to) -> {};

        assertThatNoException().isThrownBy(() -> bp.addListener(listener));

                List.of(
                        List.of("HEALTHY", "Normal operation"),
                        List.of("WARNING", "Degraded, adapting timeout"),
                        List.of("CIRCUIT_OPEN", "Fail-fast, rejecting requests")),
                List.of("State", "Description"));
        bp.shutdown();
    }

    @DisplayName("removeListener_removedSuccessfully: listener removed without throwing")
    void removeListener_removedSuccessfully() {
                "Listeners can be removed to stop receiving callbacks. Useful for cleanup or"
                        + " temporary monitoring.");
                """
            var bp = Backpressure.create(config);
            Backpressure.BackpressureListener listener = (from, to) -> {};

            bp.addListener(listener);
            bp.removeListener(listener);
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());
        Backpressure.BackpressureListener listener = (from, to) -> {};

        bp.addListener(listener);
        assertThatNoException().isThrownBy(() -> bp.removeListener(listener));

                "Listener removal is idempotent - removing a non-existent listener or removing twice"
                        + " are both safe operations.");
        bp.shutdown();
    }

    // ── Test 13: Lifecycle Management ───────────────────────────────────────────

    @DisplayName("shutdown_doesNotThrow: calling shutdown does not throw")
    void shutdown_doesNotThrow() {
                "Backpressure coordinators must be shutdown to release resources. Shutdown is"
                        + " idempotent and safe to call multiple times.");
                """
            var bp = Backpressure.create(config);
            bp.shutdown();
            """,
                "java");

        var bp = Backpressure.create(defaultConfig());
        assertThatNoException().isThrownBy(bp::shutdown);

                Map.of(
                        "Resource Release",
                        "Process coordinator shutdown",
                        "State Cleanup",
                        "Window and counters cleared",
                        "Listener Removal",
                        "All listeners cleared"));
    }
}
