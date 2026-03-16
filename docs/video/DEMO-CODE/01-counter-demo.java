package io.github.seanchatmangpt.jotp.video.demos;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * JOTP Video Tutorial - Demo 1: Simple Counter Process
 *
 * This example demonstrates:
 * - Defining process state using records
 * - Creating a sealed message hierarchy
 * - Spawning a supervised process
 * - Sending messages with tell() and ask()
 *
 * Video: 02 - Your First Process (Proc Basics)
 */
public class CounterDemo {

    // ========== STATE ==========
    /**
     * Process state: Immutable record holding the counter value.
     * Records are perfect for process state because they're immutable
     * and provide compile-time safety.
     */
    record Counter(int value) {
        // Validation: counter can't be negative
        public Counter {
            if (value < 0) {
                throw new IllegalArgumentException("Counter cannot be negative");
            }
        }
    }

    // ========== MESSAGES ==========
    /**
     * Sealed message hierarchy enables compile-time exhaustive
     * pattern matching. All message types must be listed here.
     */
    sealed interface CounterMsg permits Increment, Reset, Snapshot {}

    /**
     * Increment message: Add a value to the counter.
     * Use pattern matching to extract the 'by' field.
     */
    record Increment(int by) implements CounterMsg {
        public Increment {
            if (by <= 0) {
                throw new IllegalArgumentException("Increment must be positive");
            }
        }
    }

    /**
     * Reset message: Set counter back to zero.
     * No data needed, so it's a simple record.
     */
    record Reset() implements CounterMsg {}

    /**
     * Snapshot message: Request current counter value.
     * Used with ask() to get the current state.
     */
    record Snapshot() implements CounterMsg {}

    // ========== MAIN DEMO ==========
    public static void main(String[] args) throws Exception {
        System.out.println("=== JOTP Counter Demo ===\n");

        // Step 1: Create a supervisor
        // Supervisors provide automatic crash recovery.
        // We'll learn more about this in Video 4.
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,  // Restart only failed child
            5,                                 // Max restarts
            Duration.ofMinutes(1)              // Per minute window
        );

        // Step 2: Spawn a counter process
        ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
            "counter",                          // Process name
            new Counter(0),                     // Initial state
            counterHandler()                    // Message handler
        );

        // Step 3: Send messages using tell() (fire-and-forget)
        System.out.println("Sending: Increment(5)");
        counter.tell(new Increment(5));

        System.out.println("Sending: Increment(3)");
        counter.tell(new Increment(3));

        // Step 4: Query state using ask() (request-reply)
        System.out.println("\nQuerying state...");
        CompletableFuture<Counter> future = counter.ask(
            new Snapshot(),
            Duration.ofSeconds(1)
        );
        Counter state = future.get();
        System.out.println("Current count: " + state.value());  // Expected: 8

        // Step 5: Reset the counter
        System.out.println("\nSending: Reset()");
        counter.tell(new Reset());

        state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
        System.out.println("After reset: " + state.value());  // Expected: 0

        // Step 6: Demonstrate process isolation
        System.out.println("\n=== Process Isolation Demo ===");
        ProcRef<Counter, CounterMsg> counter2 = supervisor.supervise(
            "counter2",
            new Counter(100),
            counterHandler()
        );

        counter.tell(new Increment(10));
        counter2.tell(new Increment(10));

        Counter state1 = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
        Counter state2 = counter2.ask(new Snapshot(), Duration.ofSeconds(1)).get();

        System.out.println("Counter 1: " + state1.value());  // Expected: 10
        System.out.println("Counter 2: " + state2.value());  // Expected: 110
        System.out.println("→ Processes have isolated state!\n");

        // Cleanup
        System.out.println("Shutting down supervisor...");
        supervisor.shutdown();
        System.out.println("Done!");
    }

    /**
     * Message handler: Pure function that processes messages.
     *
     * Signature: (State, Message) → State
     *
     * This function is called for every message in the mailbox.
     * It returns the new state, which becomes the current state
     * for the next message.
     */
    private static java.util.function.BiFunction<Counter, CounterMsg, Counter> counterHandler() {
        return (state, msg) -> {
            // Pattern matching with sealed types:
            // The compiler ensures we handle ALL message types.
            return switch (msg) {
                // Extract 'by' field using pattern matching
                case Increment(var by) -> {
                    System.out.println("  [Handler] Incrementing by " + by);
                    yield new Counter(state.value() + by);
                }
                // Ignore the Reset instance (no data needed)
                case Reset _ -> {
                    System.out.println("  [Handler] Resetting to 0");
                    yield new Counter(0);
                }
                // Snapshot returns current state unchanged
                case Snapshot _ -> {
                    System.out.println("  [Handler] Returning snapshot");
                    yield state;
                }
            };
        };
    }

    // ========== EXTENDED EXAMPLES ==========

    /**
     * Demonstrates what happens when an exception is thrown.
     * The supervisor will restart the process with initial state.
     */
    public static void demonstrateCrash() throws Exception {
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // Handler that throws an exception on certain messages
        ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
            "crash-demo",
            new Counter(0),
            (state, msg) -> {
                if (msg instanceof Increment(10)) {
                    System.out.println("  [Handler] Crashing on purpose!");
                    throw new RuntimeException("Intentional crash!");
                }
                return switch (msg) {
                    case Increment(var by) -> new Counter(state.value() + by);
                    case Reset _ -> new Counter(0);
                    case Snapshot _ -> state;
                };
            }
        );

        // Normal operation
        counter.tell(new Increment(5));
        System.out.println("Count: " + counter.ask(new Snapshot(), Duration.ofSeconds(1)).get().value());

        // Crash the process
        System.out.println("\nCrashing process...");
        counter.tell(new Increment(10));  // This will crash

        // Give supervisor time to restart
        Thread.sleep(100);

        // Process is back with initial state (0)
        Counter state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
        System.out.println("After crash: " + state.value());  // Expected: 0 (restarted)
        System.out.println("→ Supervisor automatically restarted the process!\n");

        supervisor.shutdown();
    }

    /**
     * Demonstrates timeout handling in ask().
     * If the process doesn't respond within the timeout,
     * a TimeoutException is thrown.
     */
    public static void demonstrateTimeout() throws Exception {
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // Handler that simulates slow processing
        ProcRef<Counter, CounterMsg> slowCounter = supervisor.supervise(
            "slow-counter",
            new Counter(0),
            (state, msg) -> {
                if (msg instanceof Snapshot) {
                    try {
                        System.out.println("  [Handler] Simulating slow processing...");
                        Thread.sleep(2000);  // 2 second delay
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return state;
            }
        );

        // This ask() will timeout because handler is too slow
        try {
            System.out.println("Sending Snapshot with 1s timeout (handler takes 2s)...");
            slowCounter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
            System.out.println("Got response (unexpected)");
        } catch (java.util.concurrent.TimeoutException e) {
            System.out.println("→ TimeoutException thrown as expected!");
            System.out.println("→ This prevents blocking forever on slow processes.\n");
        }

        supervisor.shutdown();
    }
}

/**
 * EXPECTED OUTPUT:
 *
 * === JOTP Counter Demo ===
 *
 * Sending: Increment(5)
 * Sending: Increment(3)
 *
 * Querying state...
 *   [Handler] Returning snapshot
 * Current count: 8
 *
 * Sending: Reset()
 *   [Handler] Resetting to 0
 * After reset: 0
 *
 * === Process Isolation Demo ===
 *   [Handler] Incrementing by 10
 *   [Handler] Incrementing by 10
 *   [Handler] Returning snapshot
 *   [Handler] Returning snapshot
 * Counter 1: 10
 * Counter 2: 110
 * → Processes have isolated state!
 *
 * Shutting down...
 * Done!
 */
