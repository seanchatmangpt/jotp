# io.github.seanchatmangpt.jotp.dogfood.otp.GenServerExampleTest

## Table of Contents

- [State Consistency Under Concurrency](#stateconsistencyunderconcurrency)
- [Concurrent Requests with Serialized Processing](#concurrentrequestswithserializedprocessing)
- [Normal Completion with Timeout](#normalcompletionwithtimeout)
- [Timeout Handling in ask()](#timeouthandlinginask)
- [Immutable State Records](#immutablestaterecords)
- [State Validation with Compact Constructors](#statevalidationwithcompactconstructors)


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

| Key | Value |
| --- | --- |
| `Intermediate States` | `Monotonically increasing` |
| `Consistency` | `Guaranteed by serialization` |
| `Final State` | `count = 60 (deterministic)` |
| `Increments` | `10 + 20 + 30` |

> [!NOTE]
> The final state is deterministic (sum of all increments) even though intermediate states depend on message ordering. This is because each state transition is atomic — no partially applied updates.

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
| `Processing` | `Serialized (one at a time)` |
| `Concurrent Requests` | `3 concurrent IncrementBy(1)` |
| `Pattern` | `Mailbox queue` |
| `Responses` | `Complete out-of-order` |
| `Final State` | `count = 3` |

> [!NOTE]
> The mailbox serializes concurrent requests — no locks needed. This is the Actor model's solution to concurrency: message passing instead of shared mutable state.

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
| `Timeout` | `5000ms (5 seconds)` |
| `Operation` | `IncrementBy(5)` |
| `Result` | `Success (no timeout)` |
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
| `State Type` | `Record (immutable)` |
| `s2` | `CounterState(8)` |
| `s1` | `CounterState(5)` |
| `s1 After s2 Created` | `Still CounterState(5) (unchanged)` |

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
| `Validation` | `Compact constructor` |
| `Check` | `count >= 0` |
| `Violation` | `IllegalArgumentException` |
| `Timing` | `Construction time` |

> [!NOTE]
> State validation at construction prevents invalid states from ever existing. This is fail-fast design — bugs are caught immediately rather than propagating invalid state.

---
*Generated by [DTR](http://www.dtr.org)*
