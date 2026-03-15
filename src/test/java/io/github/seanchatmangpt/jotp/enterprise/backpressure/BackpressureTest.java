package io.github.seanchatmangpt.jotp.enterprise.backpressure;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
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
    @Test
    @DisplayName("create with valid config returns non-null instance")
    void createWithValidConfig_returnsInstance() {
        var bp = Backpressure.create(defaultConfig());
        assertThat(bp).isNotNull();
        bp.shutdown();
    @DisplayName("config builder rejects zero initial timeout")
    void configBuilder_rejectsZeroInitialTimeout() {
        assertThatThrownBy(
                        () ->
                                BackpressureConfig.builder("svc")
                                        .initialTimeout(Duration.ZERO)
                                        .maxTimeout(Duration.ofSeconds(30))
                                        .windowSize(10)
                                        .successRateThreshold(0.95)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    @DisplayName("config builder rejects maxTimeout less than initialTimeout")
    void configBuilder_rejectsMaxTimeoutLessThanInitial() {
                                        .initialTimeout(Duration.ofSeconds(10))
                                        .maxTimeout(Duration.ofSeconds(5))
    @DisplayName("config builder rejects window size of zero")
    void configBuilder_rejectsInvalidWindowSize() {
                                        .initialTimeout(Duration.ofMillis(500))
                                        .windowSize(0)
    @DisplayName("config builder rejects success rate threshold above 1.0")
    void configBuilder_rejectsInvalidSuccessRate() {
                                        .successRateThreshold(1.5)
    @DisplayName("execute with successful task returns Success result")
    void execute_successfulTask_returnsSuccess() {
        Backpressure.Result<String> result = bp.execute(timeout -> "hello", Duration.ofSeconds(5));
        assertThat(result).isInstanceOf(Backpressure.Result.Success.class);
        assertThat(((Backpressure.Result.Success<String>) result).value()).isEqualTo("hello");
    @DisplayName("execute with task that throws BackpressureException returns Failure")
    void execute_failingTask_returnsFailure() {
        Backpressure.Result<String> result =
                bp.execute(
                        timeout -> {
                            throw new Backpressure.BackpressureException("circuit open");
                        },
                        Duration.ofSeconds(5));
        assertThat(result).isInstanceOf(Backpressure.Result.Failure.class);
    @DisplayName("execute with task that throws RuntimeException wraps in Failure")
    void execute_generalException_wrappedInFailure() {
                            throw new RuntimeException("unexpected error");
    @DisplayName("add listener registers successfully without throwing")
    void addListener_registeredSuccessfully() {
        Backpressure.BackpressureListener listener = (from, to) -> {};
        assertThatNoException().isThrownBy(() -> bp.addListener(listener));
    @DisplayName("remove listener removes without throwing")
    void removeListener_removedSuccessfully() {
        bp.addListener(listener);
        assertThatNoException().isThrownBy(() -> bp.removeListener(listener));
    @DisplayName("shutdown does not throw")
    void shutdown_doesNotThrow() {
        assertThatNoException().isThrownBy(bp::shutdown);
}
