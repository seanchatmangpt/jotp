# Proc<S,M> API Reference

**Java 26 equivalent of Erlang/OTP processes** — Lightweight virtual-thread processes with lock-free mailboxes.

## Overview

`Proc<S,M>` implements the core OTP process model: isolated state, message passing, and "let it crash" fault tolerance. Each process runs on its own virtual thread (~1 KB heap), enabling millions of concurrent processes.

### Key Characteristics

- **Virtual Thread Execution**: Each process runs on `Thread.ofVirtual()`, allowing millions of concurrent processes
- **Lock-Free Mailbox**: Uses `LinkedTransferQueue` for 50-150 nanosecond message latency
- **Shared-Nothing State**: State `S` is never returned by reference — guarantees isolation
- **Type-Safe Messages**: Message type `M` should be a sealed interface of records for pattern matching
- **Crash Semantics**: Unhandled exceptions trigger supervisor restarts (via crash callbacks)

### OTP Equivalence

| Erlang/OTP | Java 26 |
|------------|---------|
| `spawn/3` | `Proc.spawn()` or `new Proc()` |
| `Pid ! Msg` | `proc.tell(msg)` |
| `gen_server:call/2` | `proc.ask(msg)` |
| `process_flag(trap_exit, true)` | `proc.trapExits(true)` |
| `sys:get_state/1` | `ProcSys.getState(proc)` |
| `sys:suspend/1` | `ProcSys.suspend(proc)` |
| `sys:resume/1` | `ProcSys.resume(proc)` |

## Type Parameters

- **`<S>`** — Process state type. Should be immutable (record, sealed class, or value type) to enable safe concurrent access via monitors and asks.
- **`<M>`** — Message type. Use a sealed interface of records for type-safe pattern matching in handlers.

## Public API

### Factory Methods

#### `spawn(S initial, BiFunction<S, M, S> handler)`

Create and start a process — mirrors Erlang's `spawn/3`.

```java
// Simple counter process
record CounterState(int count) {}
sealed interface CounterMsg permits Increment {}
record Increment() implements CounterMsg {}

var proc = Proc.spawn(
    new CounterState(0),
    (state, msg) -> switch (msg) {
        case Increment() -> new CounterState(state.count() + 1);
    }
);
```

**Parameters:**
- `initial` — Initial state value
- `handler` — Pure function `(state, message) -> nextState`. Should not have side effects.

**Returns:** Running `Proc<S,M>` instance

**Prefer this factory over the constructor in new code.**

---

### Constructor

#### `Proc(S initial, BiFunction<S, M, S> handler)`

Create and start a process. Equivalent to `spawn()` but kept for backward compatibility.

---

### Message Passing

#### `tell(M msg)`

Fire-and-forget message delivery. Enqueues `msg` without waiting for processing.

**OTP equivalent:** `Pid ! Msg`

```java
proc.tell(new Increment());
```

**Performance:** Lock-free enqueue, ~50-100ns

**Use cases:**
- Event streaming
- Fire-and-forget notifications
- High-throughput message pipelines

---

#### `ask(M msg)`

Request-reply pattern. Sends `msg` and returns a `CompletableFuture<S>` that completes with the process's state after the message is processed.

**OTP equivalent:** `gen_server:call(Pid, Msg)`

```java
CompletableFuture<CounterState> future = proc.ask(new Increment());
future.thenAccept(state -> System.out.println("Count: " + state.count()));
```

**Returns:** Future completing with state `S` after message processing

**Error handling:**
- If handler throws, future completes exceptionally
- Process continues running (OTP gen_server semantics)

---

#### `ask(M msg, Duration timeout)`

Timed request-reply with automatic timeout.

**OTP equivalent:** `gen_server:call(Pid, Msg, Timeout)`

```java
try {
    CounterState state = proc.ask(new Increment(), Duration.ofSeconds(5))
        .orTimeout(5, TimeUnit.SECONDS)
        .get();
} catch (TimeoutException e) {
    System.err.println("Process did not respond in time");
}
```

**Throws:** `TimeoutException` if process doesn't respond within `timeout`

**Best practice:** Always use timeouts to avoid deadlocks (Armstrong's rule)

---

### Lifecycle Management

#### `stop()`

Gracefully stop the process. Interrupts the virtual thread and waits for termination.

**Behavior:**
- Sets stopped flag
- Interrupts virtual thread immediately
- **Drops pending messages** in mailbox (does not drain them)
- Does **not** fire crash callbacks (normal exit)
- Blocks until thread finishes

```java
proc.stop(); // Interrupts and waits for completion
```

**Note:** If you need to process all queued messages before shutdown, send a sentinel "stop" message and wait for the process to handle it explicitly.

---

#### `lastError()`

Returns the last unhandled exception, or `null` if the process has not crashed.

```java
if (proc.lastError() != null) {
    System.err.println("Process crashed: " + proc.lastError());
}
```

**Use cases:**
- Diagnostic logging
- Crash analysis
- Supervisor restart decisions

---

#### `thread()`

Exposes the underlying virtual thread for advanced use cases.

```java
proc.thread().join(); // Wait for termination
```

---

### Exit Trapping

#### `trapExits(boolean trap)`

Enable or disable exit signal trapping.

**OTP equivalent:** `process_flag(trap_exit, true)`

**When `true`:**
- EXIT signals from linked processes become `ExitSignal` messages in mailbox
- Process stays alive and can handle each exit reason
- **Critical:** Message type `M` must include `ExitSignal` (or common supertype)

**When `false` (default):**
- EXIT signals kill this process immediately
- Supervisor is notified for restart

```java
// Process that handles exit signals
sealed interface Msg implements ExitSignal, UserMsg {}
record UserMsg(String text) implements Msg {}
record ExitSignal(Throwable reason) implements Msg {}

proc.trapExits(true);
```

**Warning:** When `trapExits(true)` is set, `ExitSignal` is cast to `M` unchecked. If `M` doesn't include `ExitSignal`, `ClassCastException` occurs at dispatch time.

---

#### `isTrappingExits()`

Returns `true` if this process is currently trapping exit signals.

---

### Callbacks (Package-Private)

#### `addCrashCallback(Runnable cb)`

Register a callback invoked when this process terminates abnormally (unhandled exception).

**Used by:** `Supervisor`, `ProcLink`

**Not fired:** On graceful `stop()` (normal exit)

---

#### `addTerminationCallback(Consumer<Throwable> cb)`

Register a callback fired on *any* termination.

- `null` reason → normal exit
- Non-null `Throwable` → abnormal exit cause

**Used by:** `ProcMonitor`, `ProcRegistry`

---

## Implementation Details

### Message Loop

The process loop:
1. Drains high-priority `sysGetState` requests (for `ProcSys.getState()`)
2. Checks suspend flag (blocks if suspended)
3. Polls mailbox with 50ms timeout (allows periodic re-evaluation)
4. Applies handler to state and message
5. Completes reply future if present
6. Handles exceptions:
   - With reply handle → complete exceptionally, keep running
   - Without reply (fire-and-forget) → mark as crashed, break loop

### Thread Safety

- **Mailbox:** `LinkedTransferQueue` (lock-free MPMC)
- **State access:** Single-threaded (virtual thread only)
- **External queries:** Via `ProcSys` (synchronous futures to state)
- **Crash callbacks:** `CopyOnWriteArrayList` (thread-safe iteration)

### Performance Characteristics

| Operation | Latency | Notes |
|-----------|---------|-------|
| `tell()` | 50-100ns | Lock-free enqueue |
| `ask()` | 50-150ns round-trip | Queue + future completion |
| State transition | <1μs | Handler application only |
| Virtual thread spawn | ~1μs | Thread.ofVirtual().start() |

### Memory Footprint

- **Per process:** ~1 KB heap (virtual thread + mailbox overhead)
- **Scalability:** Millions of processes on modest hardware
- **Message overhead:** 24-32 bytes per enqueued message (record + queue node)

## Common Usage Patterns

### Simple Worker

```java
record WorkState(int processed) {}
record WorkItem(String data) {}
record WorkResult(String output) {}

var worker = Proc.spawn(
    new WorkState(0),
    (state, item) -> {
        // Process item
        System.out.println("Processing: " + item.data());
        return new WorkState(state.processed() + 1);
    }
);

worker.tell(new WorkItem("data1"));
worker.tell(new WorkItem("data2"));
```

### Server with Request-Reply

```java
record Database(Map<String, String> data) {}
sealed interface DbMsg permits Get, Put {}
record Get(String key, CompletableFuture<String> reply) implements DbMsg {}
record Put(String key, String value) implements DbMsg {}

var db = Proc.spawn(
    new Database(new HashMap<>()),
    (state, msg) -> switch (msg) {
        case Get(var k, var r) -> {
            r.complete(state.data().get(k));
            yield state;
        }
        case Put(var k, var v) -> {
            var updated = new HashMap<>(state.data());
            updated.put(k, v);
            yield new Database(updated);
        }
    }
);

// Usage
var future = new CompletableFuture<String>();
db.tell(new Get("user:123", future));
String value = future.get();
```

### State Accumulation

```java
record Accumulator(List<String> items, int count) {}
sealed interface AccMsg permits Add, Flush {}
record Add(String item) implements AccMsg {}
record Flush(CompletableFuture<List<String>> reply) implements AccMsg {}

var acc = Proc.spawn(
    new Accumulator(new ArrayList<>(), 0),
    (state, msg) -> switch (msg) {
        case Add(var item) -> {
            var updated = new ArrayList<>(state.items());
            updated.add(item);
            yield new Accumulator(updated, state.count() + 1);
        }
        case Flush(var reply) -> {
            reply.complete(state.items());
            yield new Accumulator(new ArrayList<>(), 0);
        }
    }
);
```

## Best Practices

### 1. Use Immutable State

```java
// Good: Immutable record
record CounterState(int count) {}

// Bad: Mutable state (breaks isolation)
class BadState {
    int count; // Mutable field!
}
```

### 2. Sealed Message Types

```java
// Good: Sealed interface for exhaustive pattern matching
sealed interface Msg permits A, B, C {}
record A() implements Msg {}
record B() implements Msg {}
record C() implements Msg {}

// Bad: Unsealed interface (not exhaustive)
interface Msg {} // Missing 'sealed'
```

### 3. Always Use Timeouts on ask()

```java
// Good: Explicit timeout
proc.ask(msg, Duration.ofSeconds(5))
    .orTimeout(5, TimeUnit.SECONDS)
    .get();

// Bad: No timeout (potential deadlock)
proc.ask(msg).get(); // May block forever
```

### 4. Pure Handlers

```java
// Good: Pure function (state in, state out)
(state, msg) -> new State(state.value() + 1)

// Bad: Side effects in handler
(state, msg) -> {
    System.out.println("Side effect!"); // Violates purity
    externalService.call();             // Violates isolation
    return new State(state.value() + 1);
}
```

## Gotchas

### 1. Stop Drops Messages

```java
proc.tell(msg1);
proc.tell(msg2);
proc.stop(); // msg1 and msg2 are NOT processed
```

**Solution:** Send a shutdown sentinel and wait for acknowledgment:

```java
sealed interface Msg permits Work, Shutdown {}
record Shutdown(CompletableFuture<Void> done) implements Msg {}

proc.tell(new Shutdown(done));
done.get(); // Wait for all messages to drain
proc.stop();
```

### 2. Exit Trapping Type Safety

```java
// If M doesn't include ExitSignal:
proc.trapExits(true);
// Linked process crashes
// ClassCastException at dispatch time!
```

**Solution:** Ensure `M` includes `ExitSignal`:

```java
sealed interface Msg extends ExitSignal, UserMsg {}
```

### 3. Mutable State Sharing

```java
var sharedList = new ArrayList<String>();
Proc.spawn(sharedList, handler); // ALL restarts share same list!
```

**Solution:** Use immutable state or state factories:

```java
// Good: Each restart gets fresh state
Supplier<List<String>> factory = ArrayList::new;
Proc.spawn(factory.get(), handler);
```

## Related Classes

- **[`Supervisor`](supervisor.md)** — Hierarchical process supervision with restart strategies
- **[`ProcRef`](../proc_ref.md)** — Stable handle that survives supervisor restarts
- **[`ProcLink`](../proc_link.md)** — Bilateral crash propagation between processes
- **[`ProcMonitor`](../proc_monitor.md)** — Unilateral DOWN notifications
- **[`ExitSignal`](../exit_signal.md)** — Exit signal wrapper for trapped exits
- **[`ProcSys`](../proc_sys.md)** — Live introspection (get state, suspend/resume, statistics)
- **[`StateMachine`](statemachine.md)** — Complex workflows with sealed transitions
- **[`EventManager`](eventmanager.md)** — Typed event broadcasting

## Performance Considerations

### When to Use Proc

- **Concurrent tasks** that need isolation
- **Stateful services** with message-based APIs
- **Supervision trees** requiring fault tolerance
- **Actor-style concurrency** patterns

### When NOT to Use Proc

- **Pure computations** (use virtual threads directly)
- **Shared-state parallelism** (use structured concurrency)
- **Simple callbacks** (use `CompletableFuture`)
- **CPU-bound work** (use platform threads)

### Scaling Limits

- **Processes:** Millions (limited by heap, ~1 KB each)
- **Message throughput:** ~10M messages/sec per core (lock-free queues)
- **Latency:** 50-150ns for `tell()`, <1μs for state transitions

## See Also

- [JOTP Architecture](../architecture.md)
- [Supervision Trees](supervisor.md)
- [StateMachine API](statemachine.md)
- [EventManager API](eventmanager.md)
- [OTP Design Principles](https://erlang.org/doc/design_principles/des_princ.html)
