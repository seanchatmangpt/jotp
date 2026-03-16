# ProcRef<S,M> - Stable Process References

## Overview

`ProcRef<S,M>` is a stable opaque handle to a supervised process that survives supervisor restarts. It implements Joe Armstrong's principle: "A process identifier should be opaque. Callers must not know or care whether the process has restarted."

When a `Supervisor` restarts a crashed child, it atomically swaps the underlying `Proc`. All existing `ProcRef` handles transparently redirect to the new process without any caller changes. This is Erlang location-transparency in Java.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `Pid` | `ProcRef<S,M>` |
| Process identifier (opaque) | Stable handle to supervised process |
| Automatic pid reuse | Atomic swap on supervisor restart |

## Type Parameters

- **`S`** - Process state type
- **`M`** - Message type (use a `Record` or sealed-Record hierarchy)

## Public API

### Constructor

```java
public ProcRef(Proc<S, M> initial)
```

Creates a new `ProcRef` wrapping the initial process instance.

**Parameters:**
- `initial` - The initial process to wrap

### Core Operations

#### `tell()` - Fire-and-Forget Messaging

```java
public void tell(M msg)
```

Enqueues `msg` to the underlying process without blocking.

**Behavior:**
- If the process is mid-restart, the message goes to the stale process and is lost
- For guaranteed delivery during restart, use `ask()` with retry logic
- Non-blocking operation

**Example:**
```java
ProcRef<State, Message> ref = supervisor.startChild(spec);
ref.tell(new Message.Work("data"));
```

#### `ask()` - Request-Reply Messaging

```java
public CompletableFuture<S> ask(M msg)
```

Sends a message and returns a `CompletableFuture` that completes with the process's state after the message is processed.

**Behavior:**
- Times out naturally if the process is restarting
- Future completes exceptionally if process crashes before responding
- Use `Awaitility` or similar for retry logic during restarts

**Example:**
```java
CompletableFuture<State> future = ref.ask(new Message.GetStatus());
future.thenAccept(state -> System.out.println("Status: " + state));
```

#### `stop()` - Graceful Shutdown

```java
public void stop() throws InterruptedException
```

Gracefully stops the process. Does **not** notify the supervisor.

**Important:** Use `Supervisor.shutdown()` to stop the entire supervision tree.

**Example:**
```java
ref.stop();  // Stops this process only
```

#### `proc()` - Access Underlying Process

```java
public Proc<S, M> proc()
```

Returns the underlying `Proc` delegate with crash detection.

**Behavior:**
- After supervisor restart, returns the previously-crashed proc (with `lastError` set) until observed
- Subsequent calls return the current live delegate
- Primarily intended for monitoring infrastructure

**Example:**
```java
Proc<State, Message> proc = ref.proc();
if (proc.lastError() != null) {
    System.err.println("Process crashed: " + proc.lastError());
}
```

## Common Usage Patterns

### 1. Supervised Child Process

```java
Supervisor supervisor = new Supervisor();
ChildSpec<State, Message> spec = ChildSpec.of(
    "worker",
    () -> new State(),
    (state, msg) -> handleMessage(state, msg)
);

ProcRef<State, Message> ref = supervisor.startChild(spec);
ref.tell(new Message.Work("task"));
```

### 2. Crash Detection

```java
ProcRef<State, Message> ref = supervisor.startChild(spec);

// Later, check if process crashed
Proc<State, Message> proc = ref.proc();
if (proc.lastError() != null) {
    System.err.println("Last crash: " + proc.lastError());
    // After first observation, subsequent calls return live process
}
```

### 3. Request with Retry

```java
ProcRef<State, Message> ref = supervisor.startChild(spec);

// Retry until process responds (handles restart gracefully)
await().atMost(10, TimeUnit.SECONDS)
    .until(() -> {
        try {
            State s = ref.ask(new Message.GetStatus()).get(1, TimeUnit.SECONDS);
            return s != null;
        } catch (Exception e) {
            return false;
        }
    });
```

### 4. Message Loss During Restart

```java
// WARNING: Messages sent during restart are lost
ProcRef<State, Message> ref = supervisor.startChild(spec);

// If process crashes here...
ref.tell(new Message.Important());  // Lost if mid-restart

// Use ask() with retry for guaranteed delivery
CompletableFuture<State> future = ref.ask(new Message.Important());
// Retry logic handles restart automatically
```

## Thread-Safety Guarantees

- **Volatile delegate:** The underlying `Proc` reference is `volatile`, ensuring visibility across threads
- **Atomic swap:** Supervisor performs atomic swaps during restart
- **Immutable messages:** Message types should be immutable (records, sealed classes)

## Performance Characteristics

- **No synchronization overhead:** Direct delegation to underlying process
- **Memory footprint:** ~16 bytes per ProcRef (single volatile reference)
- **Swap cost:** O(1) atomic reference update

## Related Classes

- **`Proc<S,M>`** - The underlying process implementation
- **`Supervisor`** - Creates and manages ProcRef instances during restart
- **`ProcLink`** - Bilateral crash propagation (links survive restart via ProcRef)
- **`ProcMonitor`** - Unilateral DOWN notifications (monitoring survives restart)

## Best Practices

1. **Prefer `tell()`** for fire-and-forget messaging when message loss is acceptable
2. **Use `ask()` with retry** for guaranteed delivery during restarts
3. **Store ProcRef** not `Proc` when passing handles to other components
4. **Check `lastError()`** for crash detection in monitoring code
5. **Never assume** the underlying process is the same instance between calls

## Design Rationale

**Why ProcRef instead of direct Proc references?**

In Erlang, process identifiers are opaque. When a process crashes and restarts, other processes shouldn't need to update their references. `ProcRef` implements this principle by:

1. **Opaque handle:** Callers interact with `ProcRef`, not `Proc` directly
2. **Atomic swap:** Supervisor atomically updates the delegate on restart
3. **Transparent redirection:** All message operations redirect to current delegate
4. **Crash visibility:** `proc()` allows observing crashes while maintaining opacity

This enables true fault tolerance: processes can restart without breaking references elsewhere in the system.
