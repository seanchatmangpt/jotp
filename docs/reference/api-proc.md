# Reference: Proc<S,M> API

> "A process is the unit of concurrency. It has state, it receives messages, and it responds to them. That's it."
> — Joe Armstrong

Complete API documentation for `Proc<S,M>` — JOTP's core lightweight process primitive.

---

## Overview

`Proc<S,M>` is a virtual-thread-based process that:
- Maintains isolated state of type `S`
- Receives messages of type `M` in a FIFO mailbox
- Processes one message at a time via a pure handler function
- Runs on a virtual thread (one per process, ~1 KB heap minimum)

**Erlang/OTP equivalent:** `spawn(Module, Function, Args)` + `gen_server`

---

## Constructor

```java
public Proc(S initial, BiFunction<S, M, S> handler)
```

Creates and immediately starts a process on a new virtual thread.

**Parameters:**
- `initial` — Starting state. Should be an immutable Java record for thread safety.
- `handler` — Pure function `(state, message) → newState`. Called once per message, sequentially. Must not perform I/O that blocks for more than a few seconds (use `ask()` timeout for external calls).

**Returns:** A `Proc<S,M>` instance. The virtual thread is already running when the constructor returns.

**Example:**

```java
// Define state and messages
record CounterState(int value) {}
sealed interface CounterMsg permits Increment, Reset, Snapshot {}
record Increment(int by) implements CounterMsg {}
record Reset() implements CounterMsg {}
record Snapshot() implements CounterMsg {}

// Create process
var counter = new Proc<>(
    new CounterState(0),
    (state, msg) -> switch (msg) {
        case Increment(var by) -> new CounterState(state.value() + by);
        case Reset _           -> new CounterState(0);
        case Snapshot _        -> state;  // Returns state after processing
    }
);
```

**Handler contract:**
- The handler is called once per message, in the order messages were received
- Return the new state (or `state` unchanged if no state change needed)
- Throw `RuntimeException` to crash the process (supervisor restarts it)
- Never share `state` by reference with other threads (pass immutable records)

---

## Methods

### `tell(M msg)` — Fire and Forget

```java
public void tell(M msg)
```

Enqueue `msg` without waiting for processing. Returns immediately.

**Performance:** ~80 ns per call (LinkedTransferQueue enqueue, lock-free).

**When to use:** Notifications, one-way commands, events where you don't need a response.

```java
counter.tell(new Increment(5));
counter.tell(new Increment(3));
// Both messages are queued; process handles them sequentially
```

**Erlang equivalent:** `Pid ! Message`

---

### `ask(M msg)` — Async Request-Reply

```java
public CompletableFuture<S> ask(M msg)
```

Enqueue `msg` and return a `CompletableFuture<S>` that completes with the process's state *after* the message is processed.

**Returns:** The process state after the handler returns. Note: returns **state**, not a separate reply. Design your handler to put the reply into the state.

**Performance:** ~500 ns median round-trip (virtual thread park/unpark).

```java
// Send snapshot message, get state after it's processed
CompletableFuture<CounterState> future = counter.ask(new Snapshot());
CounterState state = future.get();
System.out.println("Current count: " + state.value());
```

**Warning:** Without a timeout, `ask()` can block indefinitely if the process is dead or overwhelmed. Always prefer the timed variant.

---

### `ask(M msg, Duration timeout)` — Timed Request-Reply

```java
public CompletableFuture<S> ask(M msg, Duration timeout)
```

Enqueue `msg` with a timeout. The future completes exceptionally with `TimeoutException` if the process does not respond within `timeout`.

> "An unbounded call is a latent deadlock. Every call must have a timeout."
> — Joe Armstrong

**This is the primary `ask()` variant for production code.**

```java
try {
    var state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
    System.out.println("Count: " + state.value());
} catch (ExecutionException e) {
    if (e.getCause() instanceof TimeoutException) {
        // Process was busy or dead — handle gracefully
        log.warn("Counter process timed out");
    }
}
```

**Backpressure:** When the process is overwhelmed, `ask()` naturally creates backpressure. Callers receive `TimeoutException` instead of queuing unlimited messages, preventing memory exhaustion.

**Erlang equivalent:** `gen_server:call(Pid, Msg, Timeout)`

---

### `trapExits(boolean trap)` — Exit Signal Handling

```java
public void trapExits(boolean trap)
```

When `true`, EXIT signals from linked processes are delivered as `ExitSignal` messages to this process's mailbox instead of killing it.

**Default:** `false` — EXIT signals from linked processes kill this process.

```java
var supervisor = new Proc<>(
    new SupervisorState(),
    (state, msg) -> {
        if (msg instanceof ExitSignal exit) {
            // Handle child exit gracefully
            log.info("Child died: reason={}", exit.reason());
            return state.withChildDead(exit.pid());
        }
        return state;
    }
);
supervisor.trapExits(true);  // Convert EXIT signals to messages
```

**Erlang equivalent:** `process_flag(trap_exit, true)`

---

### `isTrappingExits()` — Query Exit Trapping State

```java
public boolean isTrappingExits()
```

Returns `true` if this process is currently trapping exit signals.

---

### `stop()` — Graceful Shutdown

```java
public void stop() throws InterruptedException
```

Signal the process to stop after draining remaining messages, then wait for the virtual thread to finish.

**Behavior:**
- Sets the stop flag
- Interrupts the virtual thread
- Waits (blocks caller) until the virtual thread completes
- Does **not** fire crash callbacks (normal exit, not a crash)

```java
counter.stop();  // Blocks until counter has fully stopped
```

**Erlang equivalent:** `exit(Pid, normal)` when trap_exit is false

---

### `addCrashCallback(Runnable cb)` — Crash Notification

```java
public void addCrashCallback(Runnable cb)
```

Register a callback invoked when the process terminates **abnormally** (unhandled exception).

**Note:** This is used internally by `Supervisor` and `ProcLink`. You should rarely need to call this directly — prefer using a `Supervisor` to handle crashes.

```java
proc.addCrashCallback(() -> {
    log.error("Process crashed: {}", proc.lastError.getMessage());
    alerting.notify("process_crash", proc.lastError);
});
```

**Not fired on:** Normal `stop()` calls.
**Fired on:** Any `RuntimeException` escaping the handler function.

---

### `lastError` — Last Crash Reason

```java
public volatile Throwable lastError
```

The last unhandled exception that caused the process to crash. `null` if the process is healthy or stopped normally.

```java
if (proc.lastError != null) {
    log.error("Last crash cause", proc.lastError);
}
```

---

## Process State After Crash

When a process crashes (unhandled `RuntimeException`):
1. Handler stops executing
2. `lastError` is set to the exception
3. Crash callbacks fire
4. Virtual thread exits
5. Supervisor detects the crash (via crash callback) and restarts the process with `initialState`

The **mailbox is NOT preserved** across restarts — in-flight messages are lost. This is the OTP behavior: processes start fresh.

**Implication for ask():** If a process crashes while processing your `ask()`, the future completes exceptionally with `ExecutionException`. Always handle this case.

---

## Thread Safety

| Operation | Thread-Safe? | Notes |
|-----------|--------------|-------|
| `tell(msg)` | Yes | Lock-free LinkedTransferQueue |
| `ask(msg)` | Yes | Lock-free, returns Future |
| `stop()` | Yes | Volatile flag + interrupt |
| `trapExits(bool)` | Yes | Volatile write |
| Read `lastError` | Yes | Volatile field |
| Modify `S state` | N/A | Only modified within process thread |

**The state `S` is never shared.** Only the process's own virtual thread accesses it. This is the core isolation guarantee.

---

## Performance Characteristics

| Operation | Latency (p50) | Latency (p99) |
|-----------|---------------|---------------|
| `tell()` | 80 ns | 300 ns |
| `ask()` round-trip | 500 ns | 2 µs |
| Process creation | 50 µs | 200 µs |
| `stop()` | 200 µs | 1 ms |

**Throughput:** Up to ~10M messages/second on a single process (limited by handler processing time).

**Scale:** 1M concurrent `Proc` instances ≈ 2–10 GB heap (depends on state size).

---

## Common Patterns

### Request-Reply with Typed Response

Since `ask()` returns the state after processing, design your state to include the response:

```java
record ServiceState(Map<String, User> users, User lastLooked) {}
sealed interface ServiceMsg permits LookupUser, AddUser {}
record LookupUser(String id) implements ServiceMsg {}

var service = new Proc<>(
    new ServiceState(new HashMap<>(), null),
    (state, msg) -> switch (msg) {
        case LookupUser(var id) -> new ServiceState(state.users(), state.users().get(id));
        // ...
    }
);

// Get the looked-up user via state after processing
var state = service.ask(new LookupUser("user-123"), Duration.ofSeconds(1)).get();
User user = state.lastLooked();
```

### Supervision (Preferred Pattern)

In production, always supervise processes:

```java
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofMinutes(1));
ProcRef<CounterState, CounterMsg> counter = supervisor.supervise(
    "counter",
    new CounterState(0),
    (state, msg) -> switch (msg) {
        case Increment(var by) -> new CounterState(state.value() + by);
        case Reset _           -> new CounterState(0);
        case Snapshot _        -> state;
    }
);
```

See [Supervisor API](api-supervisor.md) for full supervision patterns.

---

**See Also:**
- [Supervisor API](api-supervisor.md) — Supervision tree for crash recovery
- [ProcRef](api.md#procref) — Stable handle to a supervised process
- [Tutorial 02: Your First Process](../tutorials/02-first-process.md) — Hands-on introduction
- [How-To: Create Lightweight Processes](../how-to/create-lightweight-processes.md) — Patterns and best practices
- [Concurrency Model](../explanations/concurrency-model.md) — How virtual threads work internally

---

**Previous:** [API Overview](api.md) | **Next:** [Supervisor API](api-supervisor.md)
