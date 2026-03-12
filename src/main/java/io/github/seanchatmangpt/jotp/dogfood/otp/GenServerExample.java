package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Dogfood: demonstrates the Proc + ask() pattern for a stateful service.
 *
 * <p>This example shows a simple counter service that:
 * <ul>
 *   <li>Maintains state (current count) immutably
 *   <li>Handles request-reply messages via ask()
 *   <li>Returns the new state after each operation
 * </ul>
 *
 * <p>Armstrong: "Everything is a process. A process has three things: identity, state, and
 * behavior."
 */
public class GenServerExample {

  /**
   * Sealed message hierarchy for counter operations.
   *
   * <p>Using sealed records ensures type safety and exhaustive pattern matching.
   */
  public sealed interface CounterMessage {
    /** Increment the counter by a given amount. */
    record IncrementBy(int delta) implements CounterMessage {}

    /** Get the current count. */
    record GetCount() implements CounterMessage {}
  }

  /**
   * Counter service state — immutable value.
   *
   * <p>The Proc handler never returns this by reference; instead, the handler function returns a
   * new CounterState with the updated count.
   */
  public record CounterState(int count) {
    public CounterState {
      if (count < 0) {
        throw new IllegalArgumentException("count must be non-negative, got " + count);
      }
    }
  }

  /**
   * Handler function for the counter service.
   *
   * <p>This is the "pure" function that transforms state based on incoming messages. No
   * side-effects — just state → message → new state.
   *
   * @param state current counter state
   * @param msg incoming message
   * @return new state after processing the message
   */
  private static CounterState handleCounterMessage(CounterState state, CounterMessage msg) {
    return switch (msg) {
      case CounterMessage.IncrementBy inc -> new CounterState(state.count() + inc.delta());
      case CounterMessage.GetCount ignored -> state;
    };
  }

  /**
   * Main method demonstrating the counter service.
   *
   * <p>Shows:
   * <ul>
   *   <li>Creating a Proc with initial state and handler
   *   <li>Sending multiple ask() requests with timeout
   *   <li>Collecting results and displaying them
   *   <li>Graceful shutdown
   * </ul>
   */
  public static void main(String[] args) throws InterruptedException {
    System.out.println("=== Counter Service Example (Proc + ask()) ===\n");

    // Initialize the counter service with count = 0
    var counterState = new CounterState(0);
    var counterService = new Proc<>(counterState, GenServerExample::handleCounterMessage);

    // Define a timeout for all ask() requests
    var timeout = Duration.ofSeconds(5);

    System.out.println("1. Initial state: " + counterState.count());

    // Request 1: Increment by 5
    System.out.println("\n2. Sending: IncrementBy(5)");
    CompletableFuture<CounterState> result1 = counterService.ask(
        new CounterMessage.IncrementBy(5), timeout);
    CounterState state1 = result1.join();
    System.out.println("   Response: count = " + state1.count());

    // Request 2: Increment by 3
    System.out.println("\n3. Sending: IncrementBy(3)");
    CompletableFuture<CounterState> result2 = counterService.ask(
        new CounterMessage.IncrementBy(3), timeout);
    CounterState state2 = result2.join();
    System.out.println("   Response: count = " + state2.count());

    // Request 3: Get current count
    System.out.println("\n4. Sending: GetCount()");
    CompletableFuture<CounterState> result3 = counterService.ask(
        new CounterMessage.GetCount(), timeout);
    CounterState state3 = result3.join();
    System.out.println("   Response: count = " + state3.count());

    // Request 4: Another increment
    System.out.println("\n5. Sending: IncrementBy(2)");
    CompletableFuture<CounterState> result4 = counterService.ask(
        new CounterMessage.IncrementBy(2), timeout);
    CounterState state4 = result4.join();
    System.out.println("   Response: count = " + state4.count());

    // Demonstrate concurrent asks (fire them all at once, then collect results)
    System.out.println("\n6. Concurrent requests (fire 3 asks, then join all):");
    CompletableFuture<CounterState> async1 = counterService.ask(
        new CounterMessage.IncrementBy(1), timeout);
    CompletableFuture<CounterState> async2 = counterService.ask(
        new CounterMessage.IncrementBy(1), timeout);
    CompletableFuture<CounterState> async3 = counterService.ask(
        new CounterMessage.IncrementBy(1), timeout);

    // Wait for all three to complete
    CompletableFuture.allOf(async1, async2, async3).join();
    System.out.println("   async1: count = " + async1.join().count());
    System.out.println("   async2: count = " + async2.join().count());
    System.out.println("   async3: count = " + async3.join().count());

    // Final state
    System.out.println("\n7. Final state verification:");
    CompletableFuture<CounterState> finalCheck = counterService.ask(
        new CounterMessage.GetCount(), timeout);
    CounterState finalState = finalCheck.join();
    System.out.println("   Final count = " + finalState.count());

    // Graceful shutdown
    System.out.println("\n8. Shutting down counter service...");
    counterService.stop();
    System.out.println("   Service stopped.\n");

    System.out.println("=== Test Complete ===");
  }
}
