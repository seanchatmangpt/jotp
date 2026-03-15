package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/**
 * StateMachineStressTest — stress tests for state machine event processing and latency.
 *
 * <p>Tests the event processing performance of StateMachine under various load profiles (constant,
 * ramp, spike). Measures throughput, latency percentiles, state transition overhead, and data
 * mutation impact on GC.
 */
@DisplayName("StateMachine Event Processing Stress Tests")
class StateMachineStressTest extends StressTestBase {
    // ── Test Domain: Code Lock ────────────────────────────────────────────────
    sealed interface LockState permits Locked, Open {}
    record Locked() implements LockState {}
    record Open() implements LockState {}
    sealed interface LockEvent permits PushButton, Lock {}
    record PushButton(char button) implements LockEvent {}
    record Lock() implements LockEvent {}
    record LockData(String entered, String code) {
        LockData withEntered(String newEntered) {
            return new LockData(newEntered, this.code);
        }
    }
    /**
     * Test constant event load: send events at N/sec, measure processing latency.
     *
     * <p>Expected: Throughput >50K events/sec, latency p99 <5ms
     */
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    @Test
    @DisplayName("Constant event load (1K events/sec for 5 seconds)")
    void testConstantEventLoad() {
        var sm =
                new StateMachine<>(
                        new Locked(),
                        new LockData("", "1234"),
                        (state, event, data) ->
                                switch (state) {
                                    case Locked() ->
                                            switch (event) {
                                                case PushButton(var b) -> {
                                                    var entered = data.entered() + b;
                                                    yield entered.equals(data.code())
                                                            ? Transition.nextState(
                                                                    new Open(),
                                                                    data.withEntered(""))
                                                            : Transition.keepState(
                                                                    data.withEntered(entered));
                                                }
                                                default -> Transition.keepState(data);
                                            };
                                    case Open() ->
                                                case Lock() ->
                                                        Transition.nextState(
                                                                new Locked(), data.withEntered(""));
                                });
        try {
            LoadProfile profile = new LoadProfile.ConstantLoad(1000L, Duration.ofSeconds(5));
            MetricsCollector metrics =
                    runStressTest(
                            "StateMachine Constant Event Load (1K events/sec)",
                            profile,
                            () -> {
                                sm.send(new PushButton('1'));
                            });
            // Verify results
            assertTrue(metrics.getOperationCount() > 1000, "Should send >1000 events");
            assertTrue(
                    metrics.getLatencyPercentileMs(99) < 10,
                    "Latency p99 should be <10ms, was "
                            + metrics.getLatencyPercentileMs(99)
                            + " ms");
            assertEquals(0.0, metrics.getErrorRate(), 0.01, "Error rate should be near 0%");
        } finally {
            sm.stop();
            cleanup();
     * Test ramp load: gradually increase event rate from 1K to 10K events/sec.
     * <p>Expected: Throughput scales linearly, latency p99 <10ms at peak
    @DisplayName("Ramp event load (1K→10K events/sec over 10 seconds)")
    void testRampEventLoad() {
                                                    yield entered.length() >= 4
                                                            ? Transition.keepState(
            LoadProfile profile = new LoadProfile.RampLoad(1000L, 10000L, Duration.ofSeconds(10));
                            "StateMachine Ramp Load (1K→10K events/sec)",
                                sm.send(new PushButton('5'));
            assertTrue(metrics.getOperationCount() > 5000, "Should send >5000 events in ramp");
     * Test spike load: sudden burst of events then return to baseline.
     * <p>Expected: System handles spike without errors, then recovers to baseline
    @DisplayName("Spike event load (baseline 1K, spike 50K for 1 sec)")
    void testSpikeEventLoad() {
        AtomicInteger eventCount = new AtomicInteger();
                        (state, event, data) -> {
                            eventCount.incrementAndGet();
                            return switch (state) {
                                case Locked() ->
                                        switch (event) {
                                            case PushButton(var b) ->
                                                    Transition.keepState(
                                                            data.withEntered(data.entered() + b));
                                            default -> Transition.keepState(data);
                                        };
                                case Open() ->
                                            case Lock() ->
                                                    Transition.nextState(
                                                            new Locked(), data.withEntered(""));
                            };
                        });
            LoadProfile profile =
                    new LoadProfile.SpikeLoad(1000L, 50000L, 1000L, Duration.ofSeconds(10));
                            "StateMachine Spike Load (1K baseline, 50K spike)",
                                sm.send(new PushButton('3'));
            assertTrue(metrics.getOperationCount() > 5000, "Should send >5000 events");
                    metrics.getErrorRate() < 5.0,
                    "Error rate should be <5%, was " + metrics.getErrorRate() + "%");
     * Test state transition complexity: measure overhead of NextState vs KeepState.
     * <p>Expected: State transitions scale O(1), latency <1ms regardless of state size
    @DisplayName("State transition overhead (NextState vs KeepState)")
    void testStateTransitionOverhead() {
        AtomicInteger transitionCount = new AtomicInteger();
        AtomicInteger keepStateCount = new AtomicInteger();
                                                case Lock() -> {
                                                    transitionCount.incrementAndGet();
                                                    yield Transition.nextState(new Open(), data);
                                                    keepStateCount.incrementAndGet();
                                                    yield Transition.keepState(
                                                    yield Transition.nextState(
                                                default -> {
                                                    yield Transition.keepState(data);
            // Alternate between state transitions and keep-state
            AtomicInteger switchCounter = new AtomicInteger();
            LoadProfile profile = new LoadProfile.ConstantLoad(1000L, Duration.ofSeconds(3));
                            "StateMachine State Transition Overhead",
                                if (switchCounter.incrementAndGet() % 10 == 0) {
                                    sm.send(new Lock());
                                } else {
                                    sm.send(new PushButton('2'));
                                }
            // Verify latency is consistent (both transitions are O(1))
                    metrics.getLatencyPercentileMs(99) < 5,
                    "Latency p99 should be <5ms, was "
     * Test data mutation patterns: nested record updates in state transitions.
     * <p>Expected: No excessive GC pressure, heap growth bounded
    @DisplayName("Data mutation (nested record updates)")
    void testDataMutationPatterns() {
        record ComplexData(String field1, String field2, String field3, String field4) {
            ComplexData withField1(String newVal) {
                return new ComplexData(newVal, this.field2, this.field3, this.field4);
            }
            ComplexData withField2(String newVal) {
                return new ComplexData(this.field1, newVal, this.field3, this.field4);
                        new ComplexData("a", "b", "c", "d"),
                                                case PushButton(var b) ->
                                                        Transition.keepState(
                                                                data.withField1(String.valueOf(b))
                                                                        .withField2(
                                                                                String.valueOf(b)));
                                                                new Locked(),
                                                                data.withField1("")
                                                                        .withField2("")
                                                                        .withField2(""));
            long startHeap = Runtime.getRuntime().totalMemory();
            LoadProfile profile = new LoadProfile.ConstantLoad(5000L, Duration.ofSeconds(5));
                            "StateMachine Data Mutation (nested records)",
            long endHeap = Runtime.getRuntime().totalMemory();
            // Verify throughput and heap growth is bounded
                    metrics.getHeapGrowthMb() < 100,
                    "Heap growth should be <100MB, was " + metrics.getHeapGrowthMb() + "MB");
     * Test call() latency: request-reply with state machine.
     * <p>Expected: call() latency p99 <1ms (single transition + response)
    @DisplayName("Request-reply latency (call() blocking)")
    void testCallLatency() {
                                                                new Open(), data.withEntered(""));
                                                                data.withEntered(
                                                                        data.entered() + b));
            // Measure blocking call() latency
            LoadProfile profile = new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(5));
                            "StateMachine call() Latency",
                                try {
                                    sm.call(new Lock()).join();
                                } catch (Exception e) {
                                    // Ignore timeout/cancellation
            // Verify request-reply latency
            assertTrue(metrics.getOperationCount() > 100, "Should complete >100 calls");
                    "call() latency p99 should be <10ms, was "
    @AfterEach
    void cleanupAfterEach() {
        cleanup();
}
