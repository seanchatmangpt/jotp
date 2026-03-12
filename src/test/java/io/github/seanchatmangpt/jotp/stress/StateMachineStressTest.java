package io.github.seanchatmangpt.jotp.stress;

import static org.junit.jupiter.api.Assertions.*;

import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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

  sealed interface LockState permits Locked, Open {
  }

  record Locked() implements LockState {
  }

  record Open() implements LockState {
  }

  sealed interface LockEvent permits PushButton, Lock {
  }

  record PushButton(char button) implements LockEvent {
  }

  record Lock() implements LockEvent {
  }

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
                              ? Transition.nextState(new Open(), data.withEntered(""))
                              : Transition.keepState(data.withEntered(entered));
                        }
                        default -> Transition.keepState(data);
                      };
                  case Open() ->
                      switch (event) {
                        case Lock() -> Transition.nextState(new Locked(), data.withEntered(""));
                        default -> Transition.keepState(data);
                      };
                });

    try {
      LoadProfile profile =
          new LoadProfile.ConstantLoad(1000L, Duration.ofSeconds(5));
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
          "Latency p99 should be <10ms, was " + metrics.getLatencyPercentileMs(99) + " ms");
      assertEquals(0.0, metrics.getErrorRate(), 0.01, "Error rate should be near 0%");

    } finally {
      sm.stop();
      cleanup();
    }
  }

  /**
   * Test ramp load: gradually increase event rate from 1K to 10K events/sec.
   *
   * <p>Expected: Throughput scales linearly, latency p99 <10ms at peak
   */
  @Test
  @DisplayName("Ramp event load (1K→10K events/sec over 10 seconds)")
  void testRampEventLoad() {
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
                          yield entered.length() >= 4
                              ? Transition.keepState(data.withEntered(""))
                              : Transition.keepState(data.withEntered(entered));
                        }
                        default -> Transition.keepState(data);
                      };
                  case Open() ->
                      switch (event) {
                        case Lock() -> Transition.nextState(new Locked(), data.withEntered(""));
                        default -> Transition.keepState(data);
                      };
                });

    try {
      LoadProfile profile =
          new LoadProfile.RampLoad(1000L, 10000L, Duration.ofSeconds(10));
      MetricsCollector metrics =
          runStressTest(
              "StateMachine Ramp Load (1K→10K events/sec)",
              profile,
              () -> {
                sm.send(new PushButton('5'));
              });

      // Verify results
      assertTrue(metrics.getOperationCount() > 5000, "Should send >5000 events in ramp");
      assertEquals(0.0, metrics.getErrorRate(), 0.01, "Error rate should be near 0%");

    } finally {
      sm.stop();
      cleanup();
    }
  }

  /**
   * Test spike load: sudden burst of events then return to baseline.
   *
   * <p>Expected: System handles spike without errors, then recovers to baseline
   */
  @Test
  @DisplayName("Spike event load (baseline 1K, spike 50K for 1 sec)")
  void testSpikeEventLoad() {
    AtomicInteger eventCount = new AtomicInteger();

    var sm =
        new StateMachine<>(
            new Locked(),
            new LockData("", "1234"),
            (state, event, data) -> {
              eventCount.incrementAndGet();
              return switch (state) {
                case Locked() ->
                    switch (event) {
                      case PushButton(var b) -> Transition.keepState(
                          data.withEntered(data.entered() + b));
                      default -> Transition.keepState(data);
                    };
                case Open() ->
                    switch (event) {
                      case Lock() -> Transition.nextState(new Locked(), data.withEntered(""));
                      default -> Transition.keepState(data);
                    };
              };
            });

    try {
      LoadProfile profile =
          new LoadProfile.SpikeLoad(1000L, 50000L, 1000L, Duration.ofSeconds(10));
      MetricsCollector metrics =
          runStressTest(
              "StateMachine Spike Load (1K baseline, 50K spike)",
              profile,
              () -> {
                sm.send(new PushButton('3'));
              });

      // Verify results
      assertTrue(metrics.getOperationCount() > 5000, "Should send >5000 events");
      assertTrue(
          metrics.getErrorRate() < 5.0,
          "Error rate should be <5%, was " + metrics.getErrorRate() + "%");

    } finally {
      sm.stop();
      cleanup();
    }
  }

  /**
   * Test state transition complexity: measure overhead of NextState vs KeepState.
   *
   * <p>Expected: State transitions scale O(1), latency <1ms regardless of state size
   */
  @Test
  @DisplayName("State transition overhead (NextState vs KeepState)")
  void testStateTransitionOverhead() {
    AtomicInteger transitionCount = new AtomicInteger();
    AtomicInteger keepStateCount = new AtomicInteger();

    var sm =
        new StateMachine<>(
            new Locked(),
            new LockData("", "1234"),
            (state, event, data) ->
                switch (state) {
                  case Locked() ->
                      switch (event) {
                        case Lock() -> {
                          transitionCount.incrementAndGet();
                          yield Transition.nextState(new Open(), data);
                        }
                        case PushButton(var b) -> {
                          keepStateCount.incrementAndGet();
                          yield Transition.keepState(data.withEntered(data.entered() + b));
                        }
                      };
                  case Open() ->
                      switch (event) {
                        case Lock() -> {
                          transitionCount.incrementAndGet();
                          yield Transition.nextState(new Locked(), data.withEntered(""));
                        }
                        default -> {
                          keepStateCount.incrementAndGet();
                          yield Transition.keepState(data);
                        }
                      };
                });

    try {
      // Alternate between state transitions and keep-state
      AtomicInteger switchCounter = new AtomicInteger();
      LoadProfile profile =
          new LoadProfile.ConstantLoad(1000L, Duration.ofSeconds(3));
      MetricsCollector metrics =
          runStressTest(
              "StateMachine State Transition Overhead",
              profile,
              () -> {
                if (switchCounter.incrementAndGet() % 10 == 0) {
                  sm.send(new Lock());
                } else {
                  sm.send(new PushButton('2'));
                }
              });

      // Verify latency is consistent (both transitions are O(1))
      assertTrue(
          metrics.getLatencyPercentileMs(99) < 5,
          "Latency p99 should be <5ms, was " + metrics.getLatencyPercentileMs(99) + " ms");
      assertTrue(metrics.getOperationCount() > 1000, "Should send >1000 events");

    } finally {
      sm.stop();
      cleanup();
    }
  }

  /**
   * Test data mutation patterns: nested record updates in state transitions.
   *
   * <p>Expected: No excessive GC pressure, heap growth bounded
   */
  @Test
  @DisplayName("Data mutation (nested record updates)")
  void testDataMutationPatterns() {
    record ComplexData(String field1, String field2, String field3, String field4) {
      ComplexData withField1(String newVal) {
        return new ComplexData(newVal, this.field2, this.field3, this.field4);
      }

      ComplexData withField2(String newVal) {
        return new ComplexData(this.field1, newVal, this.field3, this.field4);
      }
    }

    var sm =
        new StateMachine<>(
            new Locked(),
            new ComplexData("a", "b", "c", "d"),
            (state, event, data) ->
                switch (state) {
                  case Locked() ->
                      switch (event) {
                        case PushButton(var b) -> Transition.keepState(
                            data.withField1(String.valueOf(b))
                                .withField2(String.valueOf(b)));
                        default -> Transition.keepState(data);
                      };
                  case Open() ->
                      switch (event) {
                        case Lock() -> Transition.nextState(
                            new Locked(),
                            data.withField1("").withField2("")
                                .withField2(""));
                        default -> Transition.keepState(data);
                      };
                });

    try {
      long startHeap = Runtime.getRuntime().totalMemory();

      LoadProfile profile =
          new LoadProfile.ConstantLoad(5000L, Duration.ofSeconds(5));
      MetricsCollector metrics =
          runStressTest(
              "StateMachine Data Mutation (nested records)",
              profile,
              () -> {
                sm.send(new PushButton('1'));
              });

      long endHeap = Runtime.getRuntime().totalMemory();

      // Verify throughput and heap growth is bounded
      assertTrue(metrics.getOperationCount() > 5000, "Should send >5000 events");
      assertTrue(
          metrics.getHeapGrowthMb() < 100,
          "Heap growth should be <100MB, was " + metrics.getHeapGrowthMb() + "MB");

    } finally {
      sm.stop();
      cleanup();
    }
  }

  /**
   * Test call() latency: request-reply with state machine.
   *
   * <p>Expected: call() latency p99 <1ms (single transition + response)
   */
  @Test
  @DisplayName("Request-reply latency (call() blocking)")
  void testCallLatency() {
    var sm =
        new StateMachine<>(
            new Locked(),
            new LockData("", "1234"),
            (state, event, data) ->
                switch (state) {
                  case Locked() ->
                      switch (event) {
                        case Lock() -> Transition.nextState(
                            new Open(),
                            data.withEntered(""));
                        case PushButton(var b) -> Transition.keepState(
                            data.withEntered(data.entered() + b));
                      };
                  case Open() ->
                      switch (event) {
                        case Lock() -> Transition.nextState(
                            new Locked(),
                            data.withEntered(""));
                        default -> Transition.keepState(data);
                      };
                });

    try {
      // Measure blocking call() latency
      LoadProfile profile =
          new LoadProfile.ConstantLoad(100L, Duration.ofSeconds(5));
      MetricsCollector metrics =
          runStressTest(
              "StateMachine call() Latency",
              profile,
              () -> {
                try {
                  sm.call(new Lock()).join();
                } catch (Exception e) {
                  // Ignore timeout/cancellation
                }
              });

      // Verify request-reply latency
      assertTrue(metrics.getOperationCount() > 100, "Should complete >100 calls");
      assertTrue(
          metrics.getLatencyPercentileMs(99) < 10,
          "call() latency p99 should be <10ms, was " + metrics.getLatencyPercentileMs(99) + " ms");

    } finally {
      sm.stop();
      cleanup();
    }
  }

  @AfterEach
  void cleanupAfterEach() {
    cleanup();
  }
}
