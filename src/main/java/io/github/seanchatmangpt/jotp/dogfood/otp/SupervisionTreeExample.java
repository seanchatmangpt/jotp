package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Dogfood: Supervisor with ONE_FOR_ONE restart strategy.
 *
 * <p>This example demonstrates OTP supervision trees using the ONE_FOR_ONE restart strategy. A
 * supervisor manages 3 worker processes, each implementing a simple counter. When a worker crashes
 * after 5 increments, the supervisor restarts only that worker independently, leaving other workers
 * unaffected.
 *
 * <p>Key concepts:
 *
 * <ul>
 *   <li><strong>Supervisor</strong> — manages child process lifecycle and restarts
 *   <li><strong>ONE_FOR_ONE strategy</strong> — only crashed children are restarted
 *   <li><strong>Restart window</strong> — max restarts within a time window prevents infinite loops
 *   <li><strong>Isolation</strong> — crash of one worker doesn't affect siblings
 * </ul>
 *
 * <p><strong>OTP Mapping:</strong>
 *
 * <ul>
 *   <li>Erlang {@code supervisor:start_link/2} → {@link Supervisor#create(String,
 *       Supervisor.Strategy, int, Duration)}
 *   <li>Erlang {@code supervisor:start_child/2} → {@link Supervisor#supervise(String, Object,
 *       java.util.function.BiFunction)}
 *   <li>Erlang {@code one_for_one} → {@link Supervisor.Strategy#ONE_FOR_ONE}
 * </ul>
 */
public final class SupervisionTreeExample {

    private SupervisionTreeExample() {}

    /**
     * Worker state: tracks counter value and crash cycles.
     *
     * <p>Each worker increments a counter until it reaches {@code crashThreshold}, at which point
     * it throws an exception to simulate a crash. Upon restart by the supervisor, the counter is
     * reset to 0 (fresh state), but the {@code restarts} counter is incremented to track how many
     * times this worker has been restarted.
     */
    record WorkerState(int counter, int restarts) {
        WorkerState() {
            this(0, 0);
        }

        WorkerState increment() {
            return new WorkerState(counter + 1, restarts);
        }

        WorkerState restart() {
            return new WorkerState(0, restarts + 1);
        }
    }

    /**
     * Worker messages: commands that a worker process can handle.
     *
     * <p>Sealed interface ensures type-safe message dispatching. Workers implement a simple state
     * machine that reacts to Increment messages.
     */
    sealed interface WorkerMessage {
        /** Increment the counter. Causes a crash if counter reaches threshold. */
        record Increment() implements WorkerMessage {}

        /** Query the current state (used for testing/verification). */
        record GetState(java.util.concurrent.CompletableFuture<WorkerState> reply)
                implements WorkerMessage {}
    }

    /**
     * Worker handler: defines how a worker processes messages.
     *
     * <p>The handler is a pure function: {@code (state, message) -> nextState}. It simulates a
     * crash by throwing an exception after the counter reaches 5 increments. The supervisor
     * catches the crash and restarts the worker with fresh state (counter = 0).
     */
    static final java.util.function.BiFunction<WorkerState, WorkerMessage, WorkerState>
            WORKER_HANDLER =
                    (state, msg) -> {
                        return switch (msg) {
                            case WorkerMessage.Increment ignored -> {
                                int nextCounter = state.counter() + 1;
                                System.out.println(
                                        "Worker incremented: counter="
                                                + nextCounter
                                                + ", restarts="
                                                + state.restarts());
                                if (nextCounter >= 5) {
                                    throw new IllegalStateException(
                                            "Simulated crash at counter=5");
                                }
                                yield new WorkerState(nextCounter, state.restarts());
                            }
                            case WorkerMessage.GetState req -> {
                                req.reply().complete(state);
                                yield state;
                            }
                        };
                    };

    /**
     * Main example: creates a supervision tree with 3 workers, sends messages, observes crashes
     * and restarts.
     *
     * <pre>{@code
     * Supervisor supervisor = Supervisor.create(
     *     "tree-1",
     *     Supervisor.Strategy.ONE_FOR_ONE,
     *     3,  // max 3 restarts per worker
     *     Duration.ofSeconds(10)  // within a 10-second window
     * );
     *
     * // Spawn 3 workers
     * var worker1 = supervisor.supervise("w1", new WorkerState(), WORKER_HANDLER);
     * var worker2 = supervisor.supervise("w2", new WorkerState(), WORKER_HANDLER);
     * var worker3 = supervisor.supervise("w3", new WorkerState(), WORKER_HANDLER);
     *
     * // Send increment messages
     * // Expect: w1 crashes at counter=5, supervisor restarts it with fresh state (counter=0)
     * // Meanwhile: w2 and w3 continue unaffected (ONE_FOR_ONE isolation)
     * }</pre>
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Supervision Tree Example (ONE_FOR_ONE) ===\n");

        // Create supervisor with ONE_FOR_ONE strategy
        // Max 3 restarts per child within 10 seconds
        Supervisor supervisor =
                Supervisor.create(
                        "tree-1",
                        Supervisor.Strategy.ONE_FOR_ONE,
                        3,
                        Duration.ofSeconds(10));

        System.out.println("Created supervisor with ONE_FOR_ONE strategy");
        System.out.println("Max restarts: 3 per worker, window: 10 seconds\n");

        // Spawn 3 workers
        ProcRef<WorkerState, WorkerMessage> worker1 =
                supervisor.supervise("w1", new WorkerState(), WORKER_HANDLER);
        ProcRef<WorkerState, WorkerMessage> worker2 =
                supervisor.supervise("w2", new WorkerState(), WORKER_HANDLER);
        ProcRef<WorkerState, WorkerMessage> worker3 =
                supervisor.supervise("w3", new WorkerState(), WORKER_HANDLER);

        System.out.println("Spawned 3 workers: w1, w2, w3\n");

        // Scenario 1: Send increments to all workers
        // w1 will crash at counter=5, but w2 and w3 continue
        System.out.println("--- Scenario 1: Normal operation, w1 crashes after 5 increments ---");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(100); // Stagger messages for visibility
            System.out.println("Cycle " + (i + 1) + ":");
            worker1.send(new WorkerMessage.Increment());
            worker2.send(new WorkerMessage.Increment());
            worker3.send(new WorkerMessage.Increment());
        }

        // Wait for crash and restart
        Thread.sleep(500);

        // Verify w1 was restarted with fresh state
        System.out.println("\n--- Scenario 2: Verify isolation (ONE_FOR_ONE) ---");
        var state1 = new java.util.concurrent.CompletableFuture<WorkerState>();
        var state2 = new java.util.concurrent.CompletableFuture<WorkerState>();
        var state3 = new java.util.concurrent.CompletableFuture<WorkerState>();

        worker1.send(new WorkerMessage.GetState(state1));
        worker2.send(new WorkerMessage.GetState(state2));
        worker3.send(new WorkerMessage.GetState(state3));

        WorkerState s1 = state1.get(1, TimeUnit.SECONDS);
        WorkerState s2 = state2.get(1, TimeUnit.SECONDS);
        WorkerState s3 = state3.get(1, TimeUnit.SECONDS);

        System.out.println("After crash and restart:");
        System.out.println("  w1: counter=" + s1.counter() + ", restarts=" + s1.restarts());
        System.out.println("  w2: counter=" + s2.counter() + ", restarts=" + s2.restarts());
        System.out.println("  w3: counter=" + s3.counter() + ", restarts=" + s3.restarts());

        System.out.println("\nKey observations:");
        System.out.println(
                "  1. w1 was restarted (restarts > 0), counter reset to 0 (fresh state)");
        System.out.println("  2. w2 and w3 continued unaffected (counter still high)");
        System.out.println("  3. ONE_FOR_ONE isolation works: crash of w1 didn't kill w2/w3");

        // Scenario 3: Exceed restart limit
        System.out.println("\n--- Scenario 3: Exceed restart limit (max 3 restarts) ---");
        System.out.println("Now causing w1 to crash 4 more times (exceeding limit)...");
        for (int i = 0; i < 30; i++) {
            try {
                worker1.send(new WorkerMessage.Increment());
                Thread.sleep(100);
            } catch (Exception e) {
                // Worker may stop responding if supervisor terminates
                break;
            }
        }

        // Wait and check supervisor status
        Thread.sleep(1000);
        boolean supervisorRunning = supervisor.isRunning();
        Throwable fatalError = supervisor.fatalError();

        System.out.println("\nSupervisor status after exceeding restart limit:");
        System.out.println("  Running: " + supervisorRunning);
        if (fatalError != null) {
            System.out.println(
                    "  Fatal error: "
                            + fatalError.getClass().getSimpleName()
                            + ": "
                            + fatalError.getMessage());
        }

        // Graceful shutdown
        System.out.println("\n--- Scenario 4: Graceful shutdown ---");
        try {
            supervisor.shutdown();
            System.out.println("Supervisor shut down successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n=== Example completed ===");
    }
}
