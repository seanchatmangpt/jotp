package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.dogfood.otp.GenServerExample.CounterMessage;
import io.github.seanchatmangpt.jotp.dogfood.otp.GenServerExample.CounterState;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: tests for GenServerExample demonstrating the Proc + ask() pattern.
 *
 * <p>JOTP implements the OTP GenServer pattern using Proc<S,M> with ask() for request-response" + "
 * messaging. This provides Erlang/OTP-style fault-tolerant servers in Java 26.
 *
 * <p>Key patterns: - Proc<S,M> for stateful processes - ask() for request-response - Immutable" + "
 * state records - Sealed message types - Timeout handling
 */
@DtrTest
@DisplayName("GenServerExample (Counter Service) - OTP GenServer Pattern in Java")
class GenServerExampleTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    private Proc<CounterState, CounterMessage> counterService;
    private Duration timeout;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        var initialState = new CounterState(0);
        counterService =
                new Proc<>(
                        initialState,
                        (state, msg) ->
                                switch (msg) {
                                    case CounterMessage.IncrementBy inc ->
                                            new CounterState(state.count() + inc.delta());
                                    case CounterMessage.GetCount ignored -> state;
                                });
        timeout = Duration.ofSeconds(5);
    }

    @Test
    @DisplayName("should initialize with count = 0")
    void shouldInitializeWithZero() throws InterruptedException {
        ctx.sayNextSection("OTP GenServer Pattern: Stateful Processes");
        ctx.say(
                "JOTP's Proc<S,M> implements the OTP GenServer pattern — a stateful process that handles"
                        + " messages and updates state immutably. Each message creates a new state instead"
                        + " of mutating existing state.");

        ctx.sayTable(
                new String[][] {
                    {"Concept", "State", "Request/Response"},
                    {"Erlang/OTP", "Immutable record", "call/2"},
                    {"JOTP (Java 26)", "Immutable record", "ask()"},
                    {"Process", "Messages", "Cast (fire-forget)"},
                    {"gen_server process", "Pattern matching", "cast/2"},
                    {"Proc<S,M>", "Sealed + pattern matching", "tell()"}
                });

        ctx.sayCode(
                """
            // Create a GenServer-style process
            Proc<CounterState, CounterMessage> counterService = new Proc<>(
                new CounterState(0),  // Initial state
                (state, msg) -> switch (msg) {  // Message handler
                    case CounterMessage.IncrementBy inc ->
                        new CounterState(state.count() + inc.delta());
                    case CounterMessage.GetCount ignored ->
                        state;
                }
            );

            // Query state via ask()
            CounterState result = counterService.ask(new CounterMessage.GetCount(), timeout)
                .join();

            // Result: count = 0
            """,
                "java");

        var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();

        assertThat(result.count()).isEqualTo(0);
        counterService.stop();

        ctx.sayKeyValue(
                Map.of(
                        "Initial State",
                        "CounterState(0)",
                        "Message",
                        "GetCount",
                        "Result",
                        "CounterState(0)",
                        "State Change",
                        "None (query)"));

        ctx.sayNote(
                "Proc<S,M> uses sealed message types and pattern matching, ensuring exhaustive handling"
                        + " at compile time. The state is always immutable — transitions create new state"
                        + " records.");
    }

    @Test
    @DisplayName("should increment correctly via ask()")
    void shouldIncrementCorrectly() throws InterruptedException {
        ctx.sayNextSection("Request-Response Messaging with ask()");
        ctx.say(
                "The ask() method implements OTP's call/2 pattern — send a request and wait for a"
                        + " response. It returns CompletableFuture<State>, enabling async/await patterns.");

        ctx.sayCode(
                """
            // Send command and wait for new state
            counterService.ask(new CounterMessage.IncrementBy(5), timeout)
                .thenAccept(newState -> {
                    // newState.count() == 5
                });

            // Query current state
            CounterState result = counterService.ask(new CounterMessage.GetCount(), timeout)
                .join();

            // Result: count = 5
            """,
                "java");

        counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
        var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();

        assertThat(result.count()).isEqualTo(5);
        counterService.stop();

        ctx.sayKeyValue(
                Map.of(
                        "Command",
                        "IncrementBy(5)",
                        "State Change",
                        "0 → 5",
                        "Query",
                        "GetCount",
                        "Result",
                        "CounterState(5)",
                        "Pattern",
                        "Command Query Responsibility Segregation (CQRS)"));

        ctx.sayNote(
                "ask() returns the new state after message processing. This enables CQRS-style"
                        + " operations where commands change state and queries read it. All state"
                        + " transitions are immutable.");
    }

    @Test
    @DisplayName("should handle multiple sequential asks")
    void shouldHandleMultipleSequentialAsks() throws InterruptedException {
        ctx.sayNextSection("Sequential State Transitions");
        ctx.say(
                "GenServers process messages sequentially from their mailbox. This ensures consistent"
                        + " state — no race conditions from concurrent state updates.");

        ctx.sayCode(
                """
            // Sequential message processing
            counterService.ask(new CounterMessage.IncrementBy(2), timeout).join();  // 0 → 2
            counterService.ask(new CounterMessage.IncrementBy(3), timeout).join();  // 2 → 5
            counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();  // 5 → 10

            CounterState result = counterService.ask(new CounterMessage.GetCount(), timeout)
                .join();

            // Final state: count = 10 (2 + 3 + 5)
            """,
                "java");

        counterService.ask(new CounterMessage.IncrementBy(2), timeout).join();
        counterService.ask(new CounterMessage.IncrementBy(3), timeout).join();
        counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
        var result = counterService.ask(new CounterMessage.GetCount(), timeout).join();

        assertThat(result.count()).isEqualTo(10);
        counterService.stop();

        ctx.sayKeyValue(
                Map.of(
                        "Message 1",
                        "IncrementBy(2) → state = 2",
                        "Message 2",
                        "IncrementBy(3) → state = 5",
                        "Message 3",
                        "IncrementBy(5) → state = 10",
                        "Final State",
                        "count = 10",
                        "Processing",
                        "Sequential, ordered"));

        ctx.sayNote(
                "Sequential processing eliminates race conditions. Each message sees the state left by"
                        + " the previous message. This is the essence of the Actor model — message passing"
                        + " instead of shared mutable state.");
    }

    @Nested
    @DisplayName("concurrent asks")
    class ConcurrentAsks {

        @Test
        @DisplayName("should handle multiple concurrent asks")
        void shouldHandleConcurrentAsks() throws InterruptedException {
            ctx.sayNextSection("Concurrent Requests with Serialized Processing");
            ctx.say(
                    "Multiple callers can send concurrent ask() requests. The GenServer processes them"
                            + " one at a time (serial execution) but responses return as soon as each message"
                            + " is handled.");

            ctx.sayCode(
                    """
            // Fire three concurrent asks
            CompletableFuture<CounterState> async1 =
                counterService.ask(new CounterMessage.IncrementBy(1), timeout);
            CompletableFuture<CounterState> async2 =
                counterService.ask(new CounterMessage.IncrementBy(1), timeout);
            CompletableFuture<CounterState> async3 =
                counterService.ask(new CounterMessage.IncrementBy(1), timeout);

            // Wait for all to complete
            CompletableFuture.allOf(async1, async2, async3).join();

            // Verify final state
            CounterState finalState = counterService.ask(new CounterMessage.GetCount(), timeout)
                .join();

            // Result: count = 3 (1 + 1 + 1)
            """,
                    "java");

            CompletableFuture<CounterState> async1 =
                    counterService.ask(new CounterMessage.IncrementBy(1), timeout);
            CompletableFuture<CounterState> async2 =
                    counterService.ask(new CounterMessage.IncrementBy(1), timeout);
            CompletableFuture<CounterState> async3 =
                    counterService.ask(new CounterMessage.IncrementBy(1), timeout);

            CompletableFuture.allOf(async1, async2, async3).join();

            var finalState = counterService.ask(new CounterMessage.GetCount(), timeout).join();
            assertThat(finalState.count()).isEqualTo(3);

            counterService.stop();

            ctx.sayKeyValue(
                    Map.of(
                            "Concurrent Requests",
                            "3 concurrent IncrementBy(1)",
                            "Processing",
                            "Serialized (one at a time)",
                            "Final State",
                            "count = 3",
                            "Responses",
                            "Complete out-of-order",
                            "Pattern",
                            "Mailbox queue"));

            ctx.sayNote(
                    "The mailbox serializes concurrent requests — no locks needed. This is the Actor"
                            + " model's solution to concurrency: message passing instead of shared mutable"
                            + " state.");
        }

        @Test
        @DisplayName("should maintain order and consistency with concurrent asks")
        void shouldMaintainOrderWithConcurrentAsks() throws InterruptedException {
            ctx.sayNextSection("State Consistency Under Concurrency");
            ctx.say(
                    "Even with concurrent requests, state transitions are consistent. Each message is"
                            + " processed atomically, seeing the state left by the previous message.");

            ctx.sayCode(
                    """
            // Concurrent increments of different amounts
            CompletableFuture<CounterState> f1 =
                counterService.ask(new CounterMessage.IncrementBy(10), timeout);
            CompletableFuture<CounterState> f2 =
                counterService.ask(new CounterMessage.IncrementBy(20), timeout);
            CompletableFuture<CounterState> f3 =
                counterService.ask(new CounterMessage.IncrementBy(30), timeout);

            // Each future completes with the state after its message
            CounterState s1 = f1.join();  // One of: 10, 30, 60 (depends on order)
            CounterState s2 = f2.join();  // One of: 30, 60
            CounterState s3 = f3.join();  // Always: 60

            // Final state is deterministic: sum of all increments
            CounterState finalState = counterService.ask(new CounterMessage.GetCount(), timeout)
                .join();

            // Result: count = 60 (10 + 20 + 30)
            """,
                    "java");

            CompletableFuture<CounterState> f1 =
                    counterService.ask(new CounterMessage.IncrementBy(10), timeout);
            CompletableFuture<CounterState> f2 =
                    counterService.ask(new CounterMessage.IncrementBy(20), timeout);
            CompletableFuture<CounterState> f3 =
                    counterService.ask(new CounterMessage.IncrementBy(30), timeout);

            CounterState s1 = f1.join();
            CounterState s2 = f2.join();
            CounterState s3 = f3.join();

            assertThat(s1.count()).isGreaterThan(0);
            assertThat(s2.count()).isGreaterThan(s1.count());
            assertThat(s3.count()).isGreaterThan(s2.count());

            var finalState = counterService.ask(new CounterMessage.GetCount(), timeout).join();
            assertThat(finalState.count()).isEqualTo(60);

            counterService.stop();

            ctx.sayKeyValue(
                    Map.of(
                            "Increments",
                            "10 + 20 + 30",
                            "Intermediate States",
                            "Monotonically increasing",
                            "Final State",
                            "count = 60 (deterministic)",
                            "Consistency",
                            "Guaranteed by serialization"));

            ctx.sayNote(
                    "The final state is deterministic (sum of all increments) even though intermediate"
                            + " states depend on message ordering. This is because each state transition is"
                            + " atomic — no partially applied updates.");
        }
    }

    @Nested
    @DisplayName("timeout behavior")
    class TimeoutBehavior {

        @Test
        @DisplayName("should respect timeout on ask()")
        void shouldRespectTimeout() throws InterruptedException {
            ctx.sayNextSection("Timeout Handling in ask()");
            ctx.say(
                    "ask() accepts a timeout parameter. If the GenServer doesn't respond within the"
                            + " timeout, the CompletableFuture completes exceptionally with"
                            + " TimeoutException.");

            ctx.sayCode(
                    """
            // Create a slow GenServer (sleeps 2 seconds)
            Proc<CounterState, CounterMessage> slowCounterService = new Proc<>(
                new CounterState(0),
                (state, msg) -> {
                    try {
                        Thread.sleep(2000);  // Simulate slow operation
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return switch (msg) {
                        case CounterMessage.IncrementBy inc ->
                            new CounterState(state.count() + inc.delta());
                        case CounterMessage.GetCount ignored -> state;
                    };
                }
            );

            // Ask with short timeout (100ms)
            Duration shortTimeout = Duration.ofMillis(100);
            CompletableFuture<CounterState> future =
                slowCounterService.ask(new CounterMessage.IncrementBy(1), shortTimeout);

            // Should timeout
            assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(future::join);
            """,
                    "java");

            var slowCounterService =
                    new Proc<CounterState, CounterMessage>(
                            new CounterState(0),
                            (state, msg) -> {
                                try {
                                    Thread.sleep(2000);
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

            assertThatExceptionOfType(TimeoutException.class).isThrownBy(future::join);

            slowCounterService.stop();

            ctx.sayKeyValue(
                    Map.of(
                            "Operation Time",
                            "2000ms (2 seconds)",
                            "Timeout",
                            "100ms",
                            "Result",
                            "TimeoutException",
                            "Process State",
                            "Still running (not crashed)"));

            ctx.sayNote(
                    "Timeouts don't crash the GenServer — the process continues running. The caller"
                            + " gets a TimeoutException, but the GenServer is unaffected. This prevents"
                            + " cascading failures from slow operations.");
        }

        @Test
        @DisplayName("should complete within timeout for fast operations")
        void shouldCompleteWithinTimeout() throws InterruptedException {
            ctx.sayNextSection("Normal Completion with Timeout");
            ctx.say(
                    "For fast operations that complete within the timeout, ask() returns the new state"
                            + " normally. Timeouts only trigger when the deadline expires.");

            ctx.sayCode(
                    """
            // Fast operation with generous timeout
            Duration generousTimeout = Duration.ofSeconds(5);
            CompletableFuture<CounterState> future =
                counterService.ask(new CounterMessage.IncrementBy(5), generousTimeout);

            // Completes successfully within timeout
            CounterState newState = future.join();

            // Result: count = 5
            """,
                    "java");

            Duration generousTimeout = Duration.ofSeconds(5);
            var future = counterService.ask(new CounterMessage.IncrementBy(5), generousTimeout);

            assertThatCode(future::join).doesNotThrowAnyException();
            assertThat(future.join().count()).isEqualTo(5);

            counterService.stop();

            ctx.sayKeyValue(
                    Map.of(
                            "Operation",
                            "IncrementBy(5)",
                            "Time Required",
                            "<1ms",
                            "Timeout",
                            "5000ms (5 seconds)",
                            "Result",
                            "Success (no timeout)"));

            ctx.sayNote(
                    "Always use timeouts in production, even for fast operations. This prevents"
                            + " indefinite hangs if the GenServer crashes or enters an infinite loop.");
        }
    }

    @Nested
    @DisplayName("state immutability")
    class StateImmutability {

        @Test
        @DisplayName("should maintain immutable state records")
        void shouldMaintainImmutableState() throws InterruptedException {
            ctx.sayNextSection("Immutable State Records");
            ctx.say(
                    "GenServer state is always immutable — a record. Each state transition creates a"
                            + " new state record instead of mutating the existing one.");

            ctx.sayCode(
                    """
            // Record-based state (immutable)
            public record CounterState(int count) {
                public CounterState {
                    if (count < 0) {
                        throw new IllegalArgumentException("count must be non-negative");
                    }
                }
            }

            // Each message creates new state
            CounterState s1 = counterService.ask(new IncrementBy(5), timeout).join();
            CounterState s2 = counterService.ask(new IncrementBy(3), timeout).join();

            // s1 is unchanged (record is immutable)
            assertThat(s1.count()).isEqualTo(5);
            assertThat(s2.count()).isEqualTo(8);
            """,
                    "java");

            CounterState s1 = counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();
            CounterState s2 = counterService.ask(new CounterMessage.IncrementBy(3), timeout).join();

            assertThat(s1.count()).isEqualTo(5);
            assertThat(s2.count()).isEqualTo(8);

            counterService.stop();

            ctx.sayKeyValue(
                    Map.of(
                            "State Type",
                            "Record (immutable)",
                            "s1",
                            "CounterState(5)",
                            "s2",
                            "CounterState(8)",
                            "s1 After s2 Created",
                            "Still CounterState(5) (unchanged)"));

            ctx.sayNote(
                    "Immutable state eliminates race conditions — no need for locks or synchronized"
                            + " blocks. The JVM can optimize immutable records more aggressively than"
                            + " mutable objects.");
        }

        @Test
        @DisplayName("CounterState should validate non-negative count")
        void shouldValidateNonNegativeCount() {
            ctx.sayNextSection("State Validation with Compact Constructors");
            ctx.say(
                    "Records support compact constructors for validation. The CounterState record"
                            + " validates that count is non-negative at construction time.");

            ctx.sayCode(
                    """
            // Record with validation
            public record CounterState(int count) {
                public CounterState {
                    if (count < 0) {
                        throw new IllegalArgumentException("count must be non-negative");
                    }
                }
            }

            // Invalid state throws exception
            assertThatThrownBy(() -> new CounterState(-1))
                .isInstanceOf(IllegalArgumentException.class);
            """,
                    "java");

            assertThatIllegalArgumentException().isThrownBy(() -> new CounterState(-1));

            ctx.sayKeyValue(
                    Map.of(
                            "Validation",
                            "Compact constructor",
                            "Check",
                            "count >= 0",
                            "Violation",
                            "IllegalArgumentException",
                            "Timing",
                            "Construction time"));

            ctx.sayNote(
                    "State validation at construction prevents invalid states from ever existing."
                            + " This is fail-fast design — bugs are caught immediately rather than"
                            + " propagating invalid state.");
        }
    }
}
