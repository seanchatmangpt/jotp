# StateMachine<S,E,D> API Reference

**Java 26 equivalent of Erlang/OTP's `gen_statem`** — Full feature parity for complex stateful workflows.

## Overview

`StateMachine<S,E,D>` implements OTP's `gen_statem` behavior: separate state, event, and data with type-safe transitions. Unlike `gen_server`, `gen_statem` explicitly models state machines with multiple modes, timeouts, and postponed events.

### Key Characteristics

- **Three Separated Concerns:** State (mode), Event (stimulus), Data (context)
- **Six Event Types:** User events, state timeouts, event timeouts, generic timeouts, internal events, state enter callbacks
- **Five Transition Types:** Next state, keep state, repeat state, stop, stop and reply
- **Nine Transition Actions:** Postpone, next event, three timeout types, cancel operations, explicit replies
- **Type-Safe Pattern Matching:** Sealed event/transition hierarchies with exhaustive switch

### OTP Equivalence

| Erlang gen_statem | Java StateMachine |
|-------------------|-------------------|
| `State` | `S` (sealed interface of records) |
| `Event` | `E` (sealed interface of records) |
| `Data` | `D` (immutable record) |
| `{next_state, S, D}` | `Transition.nextState(s, d)` |
| `{keep_state, D}` | `Transition.keepState(d)` |
| `{repeat_state, D}` | `Transition.repeatState(d)` |
| `{stop, Reason}` | `Transition.stop(reason)` |
| `[postpone]` | `Action.postpone()` |
| `{{next_event,internal,C}}` | `Action.nextEvent(c)` |
| `{state_timeout, Ms, C}` | `Action.stateTimeout(ms, c)` |
| `{timeout, Ms, C}` | `Action.eventTimeout(ms, c)` |
| `{{timeout,N}, Ms, C}` | `Action.genericTimeout(name, ms, c)` |
| `{reply, From, V}` | `Action.reply(from, v)` |
| `gen_statem:cast/2` | `send(event)` |
| `gen_statem:call/2` | `call(event)` |

## Type Parameters

- **`<S>`** — State type (mode). Use a sealed interface of records for exhaustive pattern matching.
- **`<E>`** — Event type (user events). Use a sealed interface of records.
- **`<D>`** — Data type (context carried across transitions). Immutable record recommended.

## Public API

### Factory Methods

#### `of(S initialState, D initialData, TransitionFn<S,E,D> fn)`

Shortcut: Create and immediately start a state machine with default options (no state enter).

**Equivalent to:** `StateMachine.create(initialState, initialData, fn).start()`

```java
sealed interface LockState permits Locked, Open {}
record Locked() implements LockState {}
record Open() implements LockState {}

sealed interface LockEvent permits PushButton {}
record PushButton(char button) implements LockEvent {}

record LockData(String entered, String code) {}

var sm = StateMachine.of(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                yield entered.equals(data.code())
                    ? Transition.nextState(new Open(), data.withEntered(""))
                    : Transition.keepState(data.withEntered(entered));
            }
            default -> Transition.keepState(data);
        };
        case Open() -> Transition.keepState(data);
    }
);
```

**Returns:** Running `StateMachine<S,E,D>` instance

---

#### `create(S initialState, D initialData, TransitionFn<S,E,D> fn)`

Create a builder for a new state machine with configurable options.

```java
var sm = StateMachine.create(initialState, initialData, fn)
    .withStateEnter() // Enable state enter callbacks
    .start();
```

**Returns:** `Builder<S,E,D>` for configuration

---

### Builder

#### `Builder<S,E,D>`

Builder for `StateMachine` — configure options before starting.

**Methods:**

##### `withStateEnter()`

Enable state enter callbacks.

When enabled, the transition function is called with `SMEvent.Enter` each time the machine enters a state (including the initial state on startup).

**OTP equivalent:** `callback_mode() -> [handle_event_function, state_enter]`

```java
var sm = StateMachine.create(
    new Idle(),
    new Data(),
    handler
).withStateEnter().start();

// Handler receives SMEvent.Enter events:
(state, event, data) -> switch (event) {
    case SMEvent.Enter(var prevState) -> {
        System.out.println("Entered state: " + state + " from " + prevState);
        yield Transition.keepState(data);
    }
    // ... other event handling
}
```

**Important:** Actions returned from enter callbacks are processed; state/data changes are ignored (must return `keepState` or `repeatState`).

##### `start()`

Create and start the state machine.

```java
var sm = StateMachine.create(s, d, fn)
    .withStateEnter()
    .start();
```

**Returns:** Running `StateMachine<S,E,D>` instance

---

### Event API

#### `send(E event)`

Fire-and-forget: Deliver `event` to the state machine without waiting.

**OTP equivalent:** `gen_statem:cast(Pid, Event)`

```java
sm.send(new PushButton('1'));
sm.send(new PushButton('2'));
```

**Use cases:**
- Event streaming
- Fire-and-forget notifications
- Asynchronous state changes

---

#### `call(E event)`

Request-reply: Deliver `event` and return a future that completes with the machine's data after processing.

**OTP equivalent:** `gen_statem:call(Pid, Event)`

```java
CompletableFuture<LockData> future = sm.call(new GetStatus());
future.thenAccept(data -> System.out.println("Current state: " + data));
```

**Returns:** `CompletableFuture<D>` completing with data after transition

**Error handling:**
- Fails with `IllegalStateException` if machine stopped
- Can be explicitly completed via `Action.reply()`

---

### State/Data Queries

#### `state()`

Returns the current state. Thread-safe (volatile read).

```java
LockState current = sm.state();
if (current instanceof Open) {
    System.out.println("Lock is open");
}
```

---

#### `data()`

Returns the current data. Thread-safe (volatile read).

```java
LockData data = sm.data();
System.out.println("Entered: " + data.entered());
```

---

#### `isRunning()`

Returns `true` if the machine is still running.

```java
if (!sm.isRunning()) {
    System.err.println("Machine stopped: " + sm.stopReason());
}
```

---

#### `stopReason()`

Returns the stop reason if the machine has stopped, or `null` if still running.

**OTP equivalent:** Reason atom passed to `{stop, Reason}`

```java
String reason = sm.stopReason();
if (reason != null) {
    logger.error("State machine stopped: " + reason);
}
```

---

### Lifecycle

#### `stop()`

Graceful shutdown: Stop the machine and wait for the virtual thread to finish.

**OTP equivalent:** `gen_statem:stop(Pid)`

```java
sm.stop(); // Blocks until thread finishes
```

**Behavior:**
- Sets `running = false`
- Interrupts virtual thread
- Cancels all pending timeouts
- Fails all pending `call()` futures
- Blocks until thread terminates

---

## Nested Types

### `SMEvent<E>` (Sealed Interface)

Sealed wrapper for all event types delivered to the transition function.

**Implementations:**

#### `User<E>`

External user event (cast or call).

**OTP equivalent:** `cast` / `{call,From}`

```java
case SMEvent.User(PushButton(var b)) -> {
    // Handle user event
}
```

---

#### `StateTimeout<E>`

State timeout fired. Auto-canceled on any state change.

**OTP equivalent:** `state_timeout` event type

```java
case SMEvent.StateTimeout(var content) -> {
    // Handle state timeout
}
```

**Started by:** `Action.stateTimeout(ms, content)`

**Canceled by:** Any state change OR `Action.cancelStateTimeout()`

---

#### `EventTimeout<E>`

Event timeout fired. Auto-canceled when any event (including internal) arrives.

**OTP equivalent:** `timeout` event type

```java
case SMEvent.EventTimeout(var content) -> {
    // Handle event timeout (e.g., no activity for 5 seconds)
}
```

**Started by:** `Action.eventTimeout(ms, content)`

**Canceled by:** Any incoming event OR `Action.cancelEventTimeout()`

---

#### `GenericTimeout<E>`

Named generic timeout fired. NOT auto-canceled on state change.

**OTP equivalent:** `{timeout, Name}` event type

```java
case SMEvent.GenericTimeout(var name, var content) -> {
    if (name.equals("session expiry")) {
        // Handle session timeout
    }
}
```

**Started by:** `Action.genericTimeout(name, ms, content)`

**Canceled by:** Explicit `Action.cancelGenericTimeout(name)`

---

#### `Internal<E>`

Self-inserted synthetic event, processed before the external mailbox.

**OTP equivalent:** `internal` event type via `{next_event, internal, Content}`

```java
case SMEvent.Internal(var content) -> {
    // Handle internal event (e.g., retry action)
}
```

**Inserted by:** `Action.nextEvent(content)`

**Processing:** Inserted at head of queue, before next external message

---

#### `Enter<E>`

State enter callback, fired each time a state is entered (requires `Builder.withStateEnter()`).

**OTP equivalent:** `enter` event type when `state_enter` is in callback_mode list

```java
case SMEvent.Enter(var previousState) -> {
    System.out.println("Entered " + state + " from " + previousState);
    yield Transition.keepState(data);
}
```

**Field:** `previousState` — State transitioned from; `null` for initial state

**Important:** Must return `keepState` or `repeatState` (state changes ignored)

---

### `Action` (Sealed Interface)

Transition actions returned alongside a `Transition` to command the engine.

**Implementations:**

#### `Reply`

Reply to a pending call future.

**OTP equivalent:** `{reply, From, Reply}`

```java
yield Transition.keepState(data,
    Action.reply(callFuture, "response value"));
```

**Factory:** `Action.reply(CompletableFuture<?> from, Object value)`

---

#### `Postpone`

Defer the current event until the next state change, then re-deliver.

**OTP equivalent:** `postpone`

```java
yield Transition.keepState(data, Action.postpone());
```

**Use case:** Defer events in current state that should be handled in next state

---

#### `NextEvent`

Insert a synthetic `SMEvent.Internal` event at the head of the event queue.

**OTP equivalent:** `{next_event, internal, Content}`

```java
yield Transition.nextState(newState, data,
    Action.nextEvent("retry")); // Processed before next external message
```

**Use case:** Retry logic, internal state transitions, event chaining

---

#### `SetStateTimeout`

Start (or restart) the state timeout.

**OTP equivalent:** `{state_timeout, Ms, Content}`

```java
yield Transition.nextState(newState, data,
    Action.stateTimeout(10_000, "lock")); // Fire in 10 seconds
```

**Factory:** `Action.stateTimeout(long delayMs, Object content)`

**Behavior:** Auto-canceled on any state change

---

#### `CancelStateTimeout`

Cancel any pending state timeout.

**OTP equivalent:** `{state_timeout, cancel}`

```java
yield Transition.keepState(data,
    Action.cancelStateTimeout());
```

---

#### `SetEventTimeout`

Start (or restart) the event timeout.

**OTP equivalent:** `{timeout, Ms, Content}`

```java
yield Transition.keepState(data,
    Action.eventTimeout(5_000, "no activity")); // Fire if no event for 5s
```

**Factory:** `Action.eventTimeout(long delayMs, Object content)`

**Behavior:** Auto-canceled when any event arrives

---

#### `CancelEventTimeout`

Cancel any pending event timeout.

**OTP equivalent:** `{timeout, cancel}`

```java
yield Transition.keepState(data,
    Action.cancelEventTimeout());
```

---

#### `SetGenericTimeout`

Start (or restart) a named generic timeout.

**OTP equivalent:** `{{timeout,N}, Ms, C}`

```java
yield Transition.keepState(data,
    Action.genericTimeout("session", 30_000, "expired")); // 30-second session timeout
```

**Factory:** `Action.genericTimeout(String name, long delayMs, Object content)`

**Behavior:** NOT auto-canceled on state change (must cancel explicitly)

---

#### `CancelGenericTimeout`

Cancel a named generic timeout.

**OTP equivalent:** `{{timeout,N}, cancel}`

```java
yield Transition.keepState(data,
    Action.cancelGenericTimeout("session"));
```

**Factory:** `Action.cancelGenericTimeout(String name)`

---

### `Transition<S,D>` (Sealed Interface)

The return type of a state machine transition function.

**Implementations:**

#### `NextState<S,D>`

Move to a new state, optionally with new data + actions.

**OTP equivalent:** `{next_state, S, D, [Actions]}`

```java
yield Transition.nextState(new Open(), data,
    Action.stateTimeout(10_000, "auto lock"));
```

**Factory:** `Transition.nextState(S state, D data, Action... actions)`

**Behavior:** Cancels state timeout, replays postponed events, fires enter callback (if enabled)

---

#### `KeepState<S,D>`

Stay in the current state, optionally update data + actions.

**OTP equivalent:** `{keep_state, D, [Actions]}` / `keep_state_and_data`

```java
yield Transition.keepState(data.withEntered(entered));
```

**Factory:** `Transition.keepState(D data, Action... actions)`

**Behavior:** Does NOT cancel state timeout or replay postponed events

---

#### `RepeatState<S,D>`

Like KeepState but re-triggers state enter + postpone replay.

**OTP equivalent:** `{repeat_state, D, [Actions]}`

```java
yield Transition.repeatState(data);
```

**Factory:** `Transition.repeatState(D data, Action... actions)`

**Behavior:** Replays postponed events, fires enter callback (if enabled), does NOT change state

---

#### `Stop<S,D>`

Terminate the state machine.

**OTP equivalent:** `{stop, Reason}`

```java
yield Transition.stop("shutdown requested");
```

**Factory:** `Transition.stop(String reason)`

**Behavior:** Terminates machine, cancels all timeouts, fails pending futures

---

#### `StopAndReply<S,D>`

Stop and complete pending call futures with explicit reply values.

**OTP equivalent:** `{stop_and_reply, Reason, Replies}`

```java
yield Transition.stopAndReply("shutdown complete",
    Action.reply(future1, "final response 1"),
    Action.reply(future2, "final response 2"));
```

**Factory:** `Transition.stopAndReply(String reason, Action... replies)`

**Behavior:** Processes reply actions, then terminates machine

---

### `TransitionFn<S,E,D>` (Functional Interface)

The pure transition function — equivalent to OTP's `handle_event/4`.

**Signature:** `Transition<S,D> apply(S state, SMEvent<E> event, D data)`

**OTP equivalent:** `handle_event(EventType, EventContent, State, Data) -> Transition`

```java
TransitionFn<LockState, LockEvent, LockData> fn = (state, event, data) -> switch (state) {
    case Locked() -> switch (event) {
        case SMEvent.User(PushButton(var b)) -> {
            // Handle button press in locked state
            var entered = data.entered() + b;
            yield entered.equals(data.code())
                ? Transition.nextState(new Open(), data.withEntered(""))
                : Transition.keepState(data.withEntered(entered));
        }
        case SMEvent.StateTimeout(var _) ->
            Transition.keepState(data.withEntered("")); // Reset on timeout
        default -> Transition.keepState(data);
    };
    case Open() -> switch (event) {
        case SMEvent.StateTimeout(var _) ->
            Transition.nextState(new Locked(), data); // Auto-lock
        case SMEvent.User(var _) ->
            Transition.keepState(data, Action.postpone()); // Defer buttons
        default -> Transition.keepState(data);
    };
};
```

**Important:** Should be a pure function (no side effects) for testability and reasoning.

---

## Implementation Details

### Event Loop

1. **Initial enter:** Fire `SMEvent.Enter(null)` if state enter enabled
2. **Priority queue:** Process inserted events (from `nextEvent` actions) before mailbox
3. **Mailbox poll:** Block on mailbox for next external event
4. **Event timeout cancel:** Auto-cancel event timeout if non-EventTimeout event arrives
5. **Stale timeout discard:** Silently discard stale StateTimeout/EventTimeout events
6. **Transition function:** Call `fn.apply(currentState, event, currentData)`
7. **Transition switch:** Handle NextState, KeepState, RepeatState, Stop, StopAndReply
8. **Action processing:** Execute all actions (postpone, timeouts, replies, etc.)
9. **Reply completion:** Complete pending call future if not postponed
10. **State change detection:** Check if state changed (or RepeatState forced enter)
11. **State change actions:**
    - Cancel state timeout
    - Replay postponed events (in original order)
    - Fire enter callback (if enabled)

### Timeout Management

- **State timeout:** One active timeout per machine, auto-canceled on state change
- **Event timeout:** One active timeout per machine, auto-canceled on any event
- **Generic timeouts:** Multiple named timeouts, NOT auto-canceled
- **Scheduler:** Shared daemon `ScheduledExecutorService` across all machines

### Postponed Events

- **Storage:** `ArrayDeque<Envelope<E>>` maintains insertion order
- **Replay:** Moved to head of inserted-events queue on state change
- **Order:** Preserved original order during replay

### Inserted Events

- **Storage:** `ArrayDeque<Envelope<E>>` (double-ended queue)
- **Priority:** Processed before mailbox events (poll from head)
- **Source:** `Action.nextEvent()` inserts at head

### Thread Safety

- **State/Data:** `volatile` fields (safe concurrent reads via `state()`/`data()`)
- **Mailbox:** `LinkedTransferQueue` (lock-free MPMC)
- **Timer scheduler:** Single daemon thread for all machines
- **Event loop:** Single-threaded (virtual thread only)

## Common Usage Patterns

### Code Lock with State Timeout

```java
sealed interface LockState permits Locked, Open {}
record Locked() implements LockState {}
record Open() implements LockState {}

sealed interface LockEvent permits PushButton {}
record PushButton(char button) implements LockEvent {}

record LockData(String entered, String code) {
    LockData withEntered(String e) { return new LockData(e, code); }
}

var sm = StateMachine.of(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                yield entered.equals(data.code())
                    ? Transition.nextState(new Open(), data.withEntered(""),
                          Action.stateTimeout(10_000, "lock")) // Auto-lock after 10s
                    : Transition.keepState(data.withEntered(entered));
            }
            case SMEvent.StateTimeout(var _) ->
                Transition.keepState(data.withEntered("")); // Reset on timeout
            default -> Transition.keepState(data);
        };
        case Open() -> switch (event) {
            case SMEvent.StateTimeout(var _) ->
                Transition.nextState(new Locked(), data); // Auto-lock fires
            case SMEvent.User(var _) ->
                Transition.keepState(data, Action.postpone()); // Defer buttons to locked
            default -> Transition.keepState(data);
        };
    }
);

// Usage
sm.send(new PushButton('1'));
sm.send(new PushButton('2'));
sm.send(new PushButton('3'));
sm.send(new PushButton('4'));
// State transitions to Open, then auto-locks after 10 seconds
```

### Connection Lifecycle

```java
sealed interface ConnState permits Disconnected, Connecting, Connected {}
record Disconnected() implements ConnState {}
record Connecting() implements ConnState {}
record Connected() implements ConnState {}

sealed interface ConnEvent permits Connect, Disconnect, Data, Timeout {}
record Connect(String host, int port) implements ConnEvent {}
record Disconnect() implements ConnEvent {}
record Data(byte[] bytes) implements ConnEvent {}
record Timeout() implements ConnEvent {}

record ConnData(String host, int port, Socket socket) {
    ConnData withSocket(Socket s) { return new ConnData(host, port, s); }
}

var sm = StateMachine.of(
    new Disconnected(),
    new ConnData("", null, null),
    (state, event, data) -> switch (state) {
        case Disconnected() -> switch (event) {
            case SMEvent.User(Connect(var h, var p)) ->
                Transition.nextState(new Connecting(),
                    new ConnData(h, p, null),
                    Action.eventTimeout(5_000, "connect timeout"));
            default -> Transition.keepState(data);
        };
        case Connecting() -> switch (event) {
            case SMEvent.EventTimeout(var _) -> {
                // Connection failed
                yield Transition.nextState(new Disconnected(), data);
            }
            case SMEvent.User(Data(var bytes)) -> {
                // Assume connected (simplified)
                Socket socket = new Socket(data.host(), data.port());
                yield Transition.nextState(new Connected(),
                    data.withSocket(socket),
                    Action.stateTimeout(30_000, "idle"));
            }
            default -> Transition.keepState(data);
        };
        case Connected() -> switch (event) {
            case SMEvent.StateTimeout(var _) -> {
                // Idle timeout - disconnect
                data.socket().close();
                yield Transition.nextState(new Disconnected(), data);
            }
            case SMEvent.User(Disconnect()) -> {
                data.socket().close();
                yield Transition.nextState(new Disconnected(), data);
            }
            case SMEvent.User(Data(var bytes)) -> {
                data.socket().getOutputStream().write(bytes);
                yield Transition.keepState(data,
                    Action.stateTimeout(30_000, "idle")); // Reset idle timeout
            }
            default -> Transition.keepState(data);
        };
    }
);
```

### Session with Multiple Timeouts

```java
sealed interface SessionState permits Active, Expiring {}
record Active() implements SessionState {}
record Expiring() implements SessionState {}

sealed interface SessionEvent permits Activity, Extend, Expired {}
record Activity() implements SessionEvent {}
record Extend(Duration additional) implements SessionEvent {}
record Expired() implements SessionEvent {}

record SessionData(Instant expiry, Map<String, String> store) {}

var sm = StateMachine.of(
    new Active(),
    new SessionData(Instant.now().plusSeconds(30), new HashMap<>()),
    (state, event, data) -> switch (state) {
        case Active() -> switch (event) {
            case SMEvent.User(Activity()) ->
                Transition.keepState(data,
                    Action.genericTimeout("idle", 30_000, "expired")); // Reset idle timer
            case SMEvent.GenericTimeout("idle", var _) ->
                Transition.nextState(new Expiring(), data,
                    Action.stateTimeout(5_000, "final warning")); // Grace period
            case SMEvent.User(Extend(var additional)) ->
                Transition.nextState(new Active(),
                    new SessionData(data.expiry().plusSeconds(additional.getSeconds()), data.store()));
            default -> Transition.keepState(data);
        };
        case Expiring() -> switch (event) {
            case SMEvent.StateTimeout(var _) ->
                Transition.stop("session expired"); // Terminate
            case SMEvent.User(Activity()) ->
                Transition.nextState(new Active(), data,
                    Action.genericTimeout("idle", 30_000, "expired")); // Resume
            default -> Transition.keepState(data);
        };
    }
);
```

### State Machine with Enter Callbacks

```java
var sm = StateMachine.create(
    new Idle(),
    new Data(),
    (state, event, data) -> switch (event) {
        case SMEvent.Enter(var previousState) -> {
            logger.info("Entered " + state + " from " + previousState);
            yield Transition.keepState(data); // Must return keepState
        }
        case SMEvent.User(var userEvent) -> {
            // Handle user events
            yield Transition.keepState(data);
        }
        // ... other event handling
    }
).withStateEnter().start();
```

## Best Practices

### 1. Use Sealed State/Event Types

```java
// Good: Exhaustive pattern matching
sealed interface State permits A, B, C {}
record A() implements State {}
record B() implements State {}
record C() implements State {}

// Bad: Non-sealed (not exhaustive)
interface State {}
```

### 2. Immutable Data Records

```java
// Good: Immutable record
record Data(String value, int count) {
    Data withValue(String v) { return new Data(v, count); }
}

// Bad: Mutable class
class Data {
    String value; // Mutable!
    int count;
}
```

### 3. Use Factory Methods for Transitions/Actions

```java
// Good: Clear intent
yield Transition.nextState(newState, data,
    Action.stateTimeout(5000, "timeout"));

// Verbose: Direct construction
yield new Transition.NextState<>(newState, data,
    List.of(new Action.SetStateTimeout(5000, "timeout")));
```

### 4. Handle All Event Types

```java
(state, event, data) -> switch (state) {
    case MyState() -> switch (event) {
        case SMEvent.User(var userEvent) -> /* handle user events */
        case SMEvent.StateTimeout(var content) -> /* handle state timeout */
        case SMEvent.EventTimeout(var content) -> /* handle event timeout */
        case SMEvent.GenericTimeout(var name, var content) -> /* handle generic timeout */
        case SMEvent.Internal(var content) -> /* handle internal events */
        case SMEvent.Enter(var previousState) -> /* handle enter callback */
    };
};
```

### 5. Pure Transition Functions

```java
// Good: Pure function
(state, event, data) -> {
    var newData = computeNewData(data);
    return Transition.nextState(newState, newData);
}

// Bad: Side effects
(state, event, data) -> {
    externalService.call(); // Side effect!
    System.out.println("Side effect!"); // Side effect!
    return Transition.nextState(newState, data);
}
```

## Gotchas

### 1. Enter Callbacks Must Return keepState

```java
case SMEvent.Enter(var prevState) -> {
    // ERROR: returning nextState from enter callback is ignored
    yield Transition.nextState(otherState, data);

    // Correct: return keepState or repeatState
    yield Transition.keepState(data);
}
```

### 2. State Timeout Auto-Canceled on State Change

```java
// Start state timeout in state A
yield Transition.nextState(stateA, data,
    Action.stateTimeout(10_000, "timeout"));

// Transition to state B
yield Transition.nextState(stateB, data);
// State timeout from state A is automatically canceled!

// To keep timeout active, re-set it in state B
yield Transition.nextState(stateB, data,
    Action.stateTimeout(10_000, "timeout"));
```

### 3. Event Timeout Canceled by Any Event

```java
// Start event timeout
yield Transition.keepState(data,
    Action.eventTimeout(5_000, "no activity"));

// Any event arrives (even internal events)
sm.send(new ActivityEvent());
// Event timeout is automatically canceled!
```

### 4. Postponed Events Replay on State Change

```java
case MyState() -> switch (event) {
    case SMEvent.User(var unwantedEvent) ->
        Transition.keepState(data, Action.postpone()); // Deferred
};

// Later: state changes
yield Transition.nextState(newState, data);
// Postponed events are immediately re-delivered in newState!
```

### 5. Generic Timeouts Not Auto-Canceled

```java
// Start generic timeout
yield Transition.nextState(newState, data,
    Action.genericTimeout("session", 30_000, "expired"));

// Change state
yield Transition.nextState(otherState, data);
// Generic timeout is STILL ACTIVE (not auto-canceled)

// Must cancel explicitly
yield Transition.nextState(otherState, data,
    Action.cancelGenericTimeout("session"));
```

## Related Classes

- **[`Proc`](proc.md)** — Lightweight process with mailbox
- **[`Supervisor`](supervisor.md)** — Hierarchical process supervision
- **[`EventManager`](eventmanager.md)** — Typed event broadcasting

## Performance Considerations

### Overhead

- **Per machine:** ~2 KB heap (virtual thread + queues + timers)
- **Event processing:** <1μs per transition (pure function + pattern matching)
- **Timeout precision:** ~10ms (daemon scheduler resolution)

### Scaling

- **Machines:** Millions (limited by heap, ~2 KB each)
- **Event throughput:** ~1M events/sec per core (lock-free queues)
- **Timer overhead:** Shared scheduler across all machines

## See Also

- [JOTP Architecture](../architecture.md)
- [Proc API](proc.md)
- [Supervisor API](supervisor.md)
- [State Machine Patterns](../patterns/state_machines.md)
- [OTP gen_statem Behaviour](http://erlang.org/doc/man/gen_statem.html)
