# io.github.seanchatmangpt.jotp.dogfood.otp.GenServerExampleTest

## Table of Contents

- [State Consistency Under Concurrency](#stateconsistencyunderconcurrency)
- [Request-Response Messaging with ask()](#requestresponsemessagingwithask)
- [Sequential State Transitions](#sequentialstatetransitions)
- [OTP GenServer Pattern: Stateful Processes](#otpgenserverpatternstatefulprocesses)
- [Immutable State Records](#immutablestaterecords)
- [State Validation with Compact Constructors](#statevalidationwithcompactconstructors)
- [Normal Completion with Timeout](#normalcompletionwithtimeout)
- [Timeout Handling in ask()](#timeouthandlinginask)
- [Concurrent Requests with Serialized Processing](#concurrentrequestswithserializedprocessing)


## State Consistency Under Concurrency

Even with concurrent requests, state transitions are consistent. Each message is processed atomically, seeing the state left by the previous message.

```java
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
```

## Request-Response Messaging with ask()

The ask() method implements OTP's call/2 pattern — send a request and wait for a response. It returns CompletableFuture<State>, enabling async/await patterns.

```java
// Send command and wait for new state
counterService.ask(new CounterMessage.IncrementBy(5), timeout)
    .thenAccept(newState -> {
        // newState.count() == 5
    });

// Query current state
CounterState result = counterService.ask(new CounterMessage.GetCount(), timeout)
    .join();

// Result: count = 5
```

| Key | Value |
| --- | --- |
| `Result` | `CounterState(5)` |
| `Pattern` | `Command Query Responsibility Segregation (CQRS)` |
| `Command` | `IncrementBy(5)` |
| `Query` | `GetCount` |
| `State Change` | `0 → 5` |

> [!NOTE]
> ask() returns the new state after message processing. This enables CQRS-style operations where commands change state and queries read it. All state transitions are immutable.

## Sequential State Transitions

GenServers process messages sequentially from their mailbox. This ensures consistent state — no race conditions from concurrent state updates.

```java
// Sequential message processing
counterService.ask(new CounterMessage.IncrementBy(2), timeout).join();  // 0 → 2
counterService.ask(new CounterMessage.IncrementBy(3), timeout).join();  // 2 → 5
counterService.ask(new CounterMessage.IncrementBy(5), timeout).join();  // 5 → 10

CounterState result = counterService.ask(new CounterMessage.GetCount(), timeout)
    .join();

// Final state: count = 10 (2 + 3 + 5)
```

| Key | Value |
| --- | --- |
| `Message 1` | `IncrementBy(2) → state = 2` |
| `Message 2` | `IncrementBy(3) → state = 5` |
| `Message 3` | `IncrementBy(5) → state = 10` |
| `Final State` | `count = 10` |
| `Processing` | `Sequential, ordered` |

> [!NOTE]
> Sequential processing eliminates race conditions. Each message sees the state left by the previous message. This is the essence of the Actor model — message passing instead of shared mutable state.

## OTP GenServer Pattern: Stateful Processes

JOTP's Proc<S,M> implements the OTP GenServer pattern — a stateful process that handles messages and updates state immutably. Each message creates a new state instead of mutating existing state.

| Concept | State | Request/Response |
| --- | --- | --- |
| Erlang/OTP | Immutable record | call/2 |
| JOTP (Java 26) | Immutable record | ask() |
| Process | Messages | Cast (fire-forget) |
| gen_server process | Pattern matching | cast/2 |
| Proc<S,M> | Sealed + pattern matching | tell() |

```java
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
```

| Key | Value |
| --- | --- |
| `Initial State` | `CounterState(0)` |
| `Result` | `CounterState(0)` |
| `Message` | `GetCount` |
| `State Change` | `None (query)` |

> [!NOTE]
> Proc<S,M> uses sealed message types and pattern matching, ensuring exhaustive handling at compile time. The state is always immutable — transitions create new state records.

## Immutable State Records

GenServer state is always immutable — a record. Each state transition creates a new state record instead of mutating the existing one.

```java
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
```

| Key | Value |
| --- | --- |
| `s1 After s2 Created` | `Still CounterState(5) (unchanged)` |
| `s1` | `CounterState(5)` |
| `s2` | `CounterState(8)` |
| `State Type` | `Record (immutable)` |

> [!NOTE]
> Immutable state eliminates race conditions — no need for locks or synchronized blocks. The JVM can optimize immutable records more aggressively than mutable objects.

## State Validation with Compact Constructors

Records support compact constructors for validation. The CounterState record validates that count is non-negative at construction time.

```java
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
```

| Key | Value |
| --- | --- |
| `Violation` | `IllegalArgumentException` |
| `Check` | `count >= 0` |
| `Validation` | `Compact constructor` |
| `Timing` | `Construction time` |

> [!NOTE]
> State validation at construction prevents invalid states from ever existing. This is fail-fast design — bugs are caught immediately rather than propagating invalid state.

## Normal Completion with Timeout

For fast operations that complete within the timeout, ask() returns the new state normally. Timeouts only trigger when the deadline expires.

```java
// Fast operation with generous timeout
Duration generousTimeout = Duration.ofSeconds(5);
CompletableFuture<CounterState> future =
    counterService.ask(new CounterMessage.IncrementBy(5), generousTimeout);

// Completes successfully within timeout
CounterState newState = future.join();

// Result: count = 5
```

| Key | Value |
| --- | --- |
| `Result` | `Success (no timeout)` |
| `Operation` | `IncrementBy(5)` |
| `Timeout` | `5000ms (5 seconds)` |
| `Time Required` | `<1ms` |

> [!NOTE]
> Always use timeouts in production, even for fast operations. This prevents indefinite hangs if the GenServer crashes or enters an infinite loop.

## Timeout Handling in ask()

ask() accepts a timeout parameter. If the GenServer doesn't respond within the timeout, the CompletableFuture completes exceptionally with TimeoutException.

```java
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
```

## Concurrent Requests with Serialized Processing

Multiple callers can send concurrent ask() requests. The GenServer processes them one at a time (serial execution) but responses return as soon as each message is handled.

```java
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
```

| Key | Value |
| --- | --- |
| `Concurrent Requests` | `3 concurrent IncrementBy(1)` |
| `Processing` | `Serialized (one at a time)` |
| `Final State` | `count = 3` |
| `Responses` | `Complete out-of-order` |
| `Pattern` | `Mailbox queue` |

> [!NOTE]
> The mailbox serializes concurrent requests — no locks needed. This is the Actor model's solution to concurrency: message passing instead of shared mutable state.

| Key | Value |
| --- | --- |
| `Final State` | `count = 60 (deterministic)` |
| `Consistency` | `Guaranteed by serialization` |
| `Intermediate States` | `Monotonically increasing` |
| `Increments` | `10 + 20 + 30` |

> [!NOTE]
> The final state is deterministic (sum of all increments) even though intermediate states depend on message ordering. This is because each state transition is atomic — no partially applied updates.

---
*Generated by [DTR](http://www.dtr.org)*
