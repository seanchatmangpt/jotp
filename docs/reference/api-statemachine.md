# Reference: StateMachine<S,E,D> API

> "In gen_statem, the state is not just data — it is behavior. Different states respond to the same event in different ways. This is the key insight that makes state machines so powerful for modeling real systems."
> — Joe Armstrong (paraphrased from OTP design documents)

Complete API documentation for `StateMachine<S,E,D>` — JOTP's implementation of Erlang's `gen_statem` behavior.

---

## Overview

`StateMachine<S,E,D>` separates three concerns that `Proc<S,M>` conflates:

| Concern | Type | Purpose |
|---------|------|---------|
| **State** | `S` | Which "mode" the machine is in (e.g., `Locked`, `Unlocked`) |
| **Event** | `E` | External stimulus (e.g., `PushButton`, `Lock`, `Timeout`) |
| **Data** | `D` | Context carried across all states (e.g., entered digits, attempt count) |

The transition function `(S state, E event, D data) → Transition<S,D>` defines how each `(state, event)` pair produces a new state and/or data.

**Erlang/OTP equivalent:** `gen_statem` with `handle_event_function` callback mode

---

## Constructor

```java
public StateMachine(S initialState, D initialData, TransitionFn<S, E, D> fn)
```

Create and immediately start the state machine on a virtual thread.

**Parameters:**
- `initialState` — Starting state. Use a sealed interface record (`Locked`, `Open`, etc.)
- `initialData` — Starting context data. Use an immutable record.
- `fn` — Pure transition function: `(state, event, data) → Transition<S,D>`

**Example — code lock:**

```java
// States
sealed interface LockState permits Locked, Open {}
record Locked() implements LockState {}
record Open()   implements LockState {}

// Events
sealed interface LockEvent permits PushButton, Lock {}
record PushButton(char digit) implements LockEvent {}
record Lock()                  implements LockEvent {}

// Data (context carried across states)
record LockData(String entered, String correctCode) {}

var lock = new StateMachine<>(
    new Locked(),              // initial state
    new LockData("", "1234"),  // initial data
    (state, event, data) -> switch (state) {
        case Locked _ -> switch (event) {
            case PushButton(var d) -> {
                var entered = data.entered() + d;
                yield entered.equals(data.correctCode())
                    ? Transition.nextState(new Open(), new LockData("", data.correctCode()))
                    : Transition.keepState(new LockData(entered, data.correctCode()));
            }
            default -> Transition.keepState(data);
        };
        case Open _ -> switch (event) {
            case Lock _ -> Transition.nextState(new Locked(), new LockData("", data.correctCode()));
            default     -> Transition.keepState(data);
        };
    }
);
```

---

## Transition Sealed Hierarchy

`Transition<S,D>` is a sealed interface with three variants:

```java
public sealed interface Transition<S, D>
    permits Transition.NextState, Transition.KeepState, Transition.Stop
```

### `Transition.nextState(S newState, D newData)` — Change State

```java
static <S, D> Transition<S, D> nextState(S newState, D newData)
```

Move to `newState` with `newData`.

**Erlang equivalent:** `{next_state, NewState, NewData}`

```java
// Unlock: move from Locked to Open
Transition.nextState(new Open(), data.withEntered(""))
```

### `Transition.keepState(D newData)` — Stay in State

```java
static <S, D> Transition<S, D> keepState(D newData)
```

Remain in the current state, update data.

**Erlang equivalent:** `{keep_state, NewData}`

```java
// Wrong digit: stay Locked, update entered digits
Transition.keepState(new LockData(data.entered() + digit, data.correctCode()))
```

### `Transition.stop(String reason)` — Terminate

```java
static <S, D> Transition<S, D> stop(String reason)
```

Stop the state machine with the given reason.

**Erlang equivalent:** `{stop, Reason}`

```java
// Exceed max attempts: stop the state machine
if (data.attempts() >= 3) {
    yield Transition.stop("max_attempts_exceeded");
}
```

---

## Methods

### `send(E event)` — Fire-and-Forget

```java
public void send(E event)
```

Deliver an event without waiting for the transition to complete.

**Erlang equivalent:** `gen_statem:cast(Pid, Event)`

```java
lock.send(new PushButton('1'));
lock.send(new PushButton('2'));
lock.send(new PushButton('3'));
lock.send(new PushButton('4'));
```

---

### `call(E event)` — Synchronous Event + Data Response

```java
public CompletableFuture<D> call(E event)
```

Deliver an event and return a `CompletableFuture<D>` that completes with the machine's **data** after the transition.

**Erlang equivalent:** `gen_statem:call(Pid, Event)`

**Returns:** The data `D` after the transition completes.

```java
// Process an event and get the data after transition
LockData dataAfterLock = lock.call(new Lock()).get();

// With timeout
LockData data = lock.call(new Lock())
    .orTimeout(1, TimeUnit.SECONDS)
    .get();
```

**Note:** `call()` returns `D` (data), not `S` (state). To read the current state, use `currentState()`.

---

### `currentState()` — Read Current State

```java
public S currentState()
```

Return the current state. Thread-safe (volatile read).

```java
var state = lock.currentState();
if (state instanceof Open) {
    System.out.println("Lock is open");
}
```

---

### `currentData()` — Read Current Data

```java
public D currentData()
```

Return the current data. Thread-safe (volatile read).

```java
var data = lock.currentData();
System.out.println("Attempts so far: " + data.attempts());
```

---

### `isRunning()` — Health Check

```java
public boolean isRunning()
```

Returns `true` if the state machine is still processing events.

```java
if (!lock.isRunning()) {
    System.out.println("State machine stopped: " + lock.stopReason());
}
```

---

### `stop(String reason)` — Graceful Shutdown

```java
public void stop(String reason)
```

Stop the state machine gracefully (no more events processed).

```java
lock.stop("shutdown");
```

---

## Complete Example: Payment Processor

```java
// States
sealed interface PaymentState permits Idle, Processing, Completed, Failed {}
record Idle()       implements PaymentState {}
record Processing() implements PaymentState {}
record Completed()  implements PaymentState {}
record Failed()     implements PaymentState {}

// Events
sealed interface PaymentEvent permits StartPayment, PaymentSucceeded, PaymentFailed, Reset {}
record StartPayment(long amount, String currency) implements PaymentEvent {}
record PaymentSucceeded(String transactionId)     implements PaymentEvent {}
record PaymentFailed(String errorCode)            implements PaymentEvent {}
record Reset()                                    implements PaymentEvent {}

// Data
record PaymentData(
    long amount,
    String currency,
    String transactionId,
    String errorCode,
    int retries
) {}

var payment = new StateMachine<>(
    new Idle(),
    new PaymentData(0, "USD", null, null, 0),
    (state, event, data) -> switch (state) {
        case Idle _ -> switch (event) {
            case StartPayment(var amount, var currency) ->
                Transition.nextState(new Processing(),
                    new PaymentData(amount, currency, null, null, 0));
            default -> Transition.keepState(data);
        };

        case Processing _ -> switch (event) {
            case PaymentSucceeded(var txId) ->
                Transition.nextState(new Completed(),
                    new PaymentData(data.amount(), data.currency(), txId, null, data.retries()));
            case PaymentFailed(var code) when data.retries() < 3 ->
                Transition.keepState(  // Stay in Processing, increment retries
                    new PaymentData(data.amount(), data.currency(), null, code, data.retries() + 1));
            case PaymentFailed(var code) ->
                Transition.nextState(new Failed(),  // Too many retries, fail
                    new PaymentData(data.amount(), data.currency(), null, code, data.retries()));
            default -> Transition.keepState(data);
        };

        case Completed _ -> switch (event) {
            case Reset _ -> Transition.nextState(new Idle(),
                new PaymentData(0, "USD", null, null, 0));
            default -> Transition.keepState(data);
        };

        case Failed _ -> switch (event) {
            case Reset _ -> Transition.nextState(new Idle(),
                new PaymentData(0, "USD", null, null, 0));
            default -> Transition.keepState(data);
        };
    }
);

// Usage
payment.send(new StartPayment(10000, "USD"));
payment.send(new PaymentSucceeded("txn-abc-123"));

var data = payment.currentData();
System.out.println("Transaction ID: " + data.transactionId());
// Transaction ID: txn-abc-123
```

---

## Why State Machines Over Process Handlers?

Use `StateMachine<S,E,D>` instead of `Proc<S,M>` when:

| Situation | Use |
|-----------|-----|
| Complex workflow with distinct modes (Locked/Open, Idle/Processing/Done) | `StateMachine` |
| Same event means different things in different states | `StateMachine` |
| State transitions have explicit business rules | `StateMachine` |
| Simple counter, accumulator, or service | `Proc` |

The type system benefits: with sealed states and sealed events, the Java compiler verifies that every `(state, event)` combination is handled. Exhaustiveness checking at compile time = no missing state transitions in production.

---

## Erlang/OTP Mapping

| Erlang | Java/JOTP |
|--------|-----------|
| `handle_event(cast, Event, State, Data)` | `fn.apply(state, event, data)` (via `send()`) |
| `handle_event({call, From}, Event, State, Data)` | `fn.apply(state, event, data)` (via `call()`) |
| `{next_state, S2, D2}` | `Transition.nextState(s2, d2)` |
| `{keep_state, D2}` | `Transition.keepState(d2)` |
| `keep_state_and_data` | `Transition.keepState(data)` |
| `{stop, Reason}` | `Transition.stop(reason)` |
| `gen_statem:cast(Pid, Event)` | `sm.send(event)` |
| `gen_statem:call(Pid, Event)` | `sm.call(event)` |

---

## Performance

`StateMachine` has the same performance profile as `Proc`:
- Event processing: ~500 ns round-trip (call) or ~80 ns (send)
- State machine creation: ~50 µs

---

**See Also:**
- [API Overview](api.md) — All 15 primitives
- [Proc API](api-proc.md) — Simpler process for non-state-machine use cases
- [How-To: State Machine Workflow](../how-to/state-machine-workflow.md) — Patterns and best practices
- [OTP Equivalence](../explanations/otp-equivalence.md) — Formal gen_statem comparison

---

**Previous:** [Supervisor API](api-supervisor.md) | **Next:** [Configuration](configuration.md)
