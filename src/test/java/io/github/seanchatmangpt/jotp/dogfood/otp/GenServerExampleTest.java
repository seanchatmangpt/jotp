package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.dogfood.otp.GenServerExample.CounterMessage;
import io.github.seanchatmangpt.jotp.dogfood.otp.GenServerExample.CounterState;
import io.github.seanchatmangpt.jotp.Proc;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * Dogfood: tests for GenServerExample demonstrating the Proc + ask() pattern.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Counter increments correctly via ask()
 *   <li>ask() returns correct new state
 *   <li>Concurrent asks work (multiple senders)
 *   <li>Timeout behavior is correct
 *   <li>State immutability is preserved
 * </ul>
 */
@DisplayName("GenServerExample (Counter Service)")
class GenServerExampleTest implements WithAssertions {

  private Proc<CounterState, CounterMessage> counterService;
  private Duration timeout;

  @BeforeEach
  void setUp() {
    var initialState = new CounterState(0);
    counterService = new Proc<>(
        initialState,
        (state, msg) -> switch (msg) {
          case CounterMessage.IncrementBy inc ->
              new CounterState(state.count() + inc.delta());
          case CounterMessage.GetCount ignored -> state;
        });
    timeout = Duration.ofSeconds(5);
  }

  @Test
  @DisplayName("should initialize with count = 0")
  void shouldInitializeWithZero() throws InterruptedException {
    var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();
    assertThat(result.count()).isEqualTo(0);
    counterService.stop();
  }

  @Test
  @DisplayName("should increment correctly via ask()")
  void shouldIncrementCorrectly() throws InterruptedException {
    counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
    var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();
    assertThat(result.count()).isEqualTo(5);
    counterService.stop();
  }

  @Test
  @DisplayName("should return correct new state after increment")
  void shouldReturnNewStateAfterIncrement() throws InterruptedException {
    var future = counterService.ask(new CounterMessage.IncrementBy(3), timeout);
    CounterState newState = future.join();
    assertThat(newState.count()).isEqualTo(3);
    counterService.stop();
  }

  @Test
  @DisplayName("should handle multiple sequential asks")
  void shouldHandleMultipleSequentialAsks() throws InterruptedException {
    counterService.ask(new CounterMessage.IncrementBy(2), timeout).join();
    counterService.ask(new CounterMessage.IncrementBy(3), timeout).join();
    counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
    var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();
    assertThat(result.count()).isEqualTo(10);
    counterService.stop();
  }

  @Nested
  @DisplayName("concurrent asks")
  class ConcurrentAsks {

    @Test
    @DisplayName("should handle multiple concurrent asks")
    void shouldHandleConcurrentAsks() throws InterruptedException {
      // Fire three asks concurrently
      CompletableFuture<CounterState> async1 = counterService.ask(
          new CounterMessage.IncrementBy(1), timeout);
      CompletableFuture<CounterState> async2 = counterService.ask(
          new CounterMessage.IncrementBy(1), timeout);
      CompletableFuture<CounterState> async3 = counterService.ask(
          new CounterMessage.IncrementBy(1), timeout);

      // Wait for all to complete
      CompletableFuture.allOf(async1, async2, async3).join();

      // Verify final state
      var finalState = counterService.ask(new CounterMessage.GetCount(), timeout).join();
      assertThat(finalState.count()).isEqualTo(3);

      counterService.stop();
    }

    @Test
    @DisplayName("should maintain order and consistency with concurrent asks")
    void shouldMaintainOrderWithConcurrentAsks() throws InterruptedException {
      // Fire multiple concurrent increments
      CompletableFuture<CounterState> f1 = counterService.ask(
          new CounterMessage.IncrementBy(10), timeout);
      CompletableFuture<CounterState> f2 = counterService.ask(
          new CounterMessage.IncrementBy(20), timeout);
      CompletableFuture<CounterState> f3 = counterService.ask(
          new CounterMessage.IncrementBy(30), timeout);

      CounterState s1 = f1.join();
      CounterState s2 = f2.join();
      CounterState s3 = f3.join();

      // All operations should complete successfully
      assertThat(s1.count()).isGreaterThan(0);
      assertThat(s2.count()).isGreaterThan(s1.count());
      assertThat(s3.count()).isGreaterThan(s2.count());

      // Final state should be sum of all increments
      var finalState = counterService.ask(new CounterMessage.GetCount(), timeout).join();
      assertThat(finalState.count()).isEqualTo(60);

      counterService.stop();
    }
  }

  @Nested
  @DisplayName("timeout behavior")
  class TimeoutBehavior {

    @Test
    @DisplayName("should respect timeout on ask()")
    void shouldRespectTimeout() throws InterruptedException {
      // Create a service with a handler that delays
      var slowCounterService = new Proc<>(
          new CounterState(0),
          (state, msg) -> {
            try {
              Thread.sleep(2000); // Sleep for 2 seconds
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return switch (msg) {
              case CounterMessage.IncrementBy inc ->
                  new CounterState(state.count() + inc.delta());
              case CounterMessage.GetCount ignored -> state;
            };
          });

      Duration shortTimeout = Duration.ofMillis(100);

      var future = slowCounterService.ask(new CounterMessage.IncrementBy(1), shortTimeout);

      // Should timeout and throw
      assertThatExceptionOfType(TimeoutException.class)
          .isThrownBy(future::join);

      slowCounterService.stop();
    }

    @Test
    @DisplayName("should complete within timeout for fast operations")
    void shouldCompleteWithinTimeout() throws InterruptedException {
      Duration generousTimeout = Duration.ofSeconds(5);
      var future = counterService.ask(new CounterMessage.IncrementBy(5), generousTimeout);

      // Should complete without timeout
      assertThatCode(future::join).doesNotThrowAnyException();
      assertThat(future.join().count()).isEqualTo(5);

      counterService.stop();
    }
  }

  @Nested
  @DisplayName("state immutability")
  class StateImmutability {

    @Test
    @DisplayName("should maintain immutable state records")
    void shouldMaintainImmutableState() throws InterruptedException {
      CounterState s1 = counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
      CounterState s2 = counterService.ask(new CounterMessage.IncrementBy(3), timeout).join();

      // Original state object should remain unchanged (record is immutable)
      assertThat(s1.count()).isEqualTo(5);
      assertThat(s2.count()).isEqualTo(8);

      counterService.stop();
    }

    @Test
    @DisplayName("CounterState should validate non-negative count")
    void shouldValidateNonNegativeCount() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new CounterState(-1));
    }
  }

  @Nested
  @DisplayName("message types")
  class MessageTypes {

    @Test
    @DisplayName("IncrementBy message should work with positive delta")
    void incrementByWithPositiveDelta() throws InterruptedException {
      counterService.ask(new CounterMessage.IncrementBy(42), timeout).join();
      var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();
      assertThat(result.count()).isEqualTo(42);
      counterService.stop();
    }

    @Test
    @DisplayName("IncrementBy message should work with zero delta")
    void incrementByWithZeroDelta() throws InterruptedException {
      counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
      counterService.ask(new CounterMessage.IncrementBy(0), timeout).join();
      var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();
      assertThat(result.count()).isEqualTo(5);
      counterService.stop();
    }

    @Test
    @DisplayName("GetCount message should return current state")
    void getCountReturnsCurrentState() throws InterruptedException {
      counterService.ask(new CounterMessage.IncrementBy(7), timeout).join();
      var state = counterService.ask(new CounterMessage.GetCount(), timeout).join();
      assertThat(state.count()).isEqualTo(7);
      counterService.stop();
    }
  }
}
