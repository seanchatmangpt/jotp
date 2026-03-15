# ProcLink - Bilateral Crash Propagation

## Overview

`ProcLink` implements OTP process links - bilateral crash propagation between two processes. When either linked process terminates with a non-normal exit reason, the other receives an exit signal and is terminated too (unless it is trapping exits via `Proc.trapExits(true)`).

Joe Armstrong: "A link is a connection between two processes. If one process dies, the other is notified. This is the fundamental building block of fault-tolerant systems."

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `link(Pid)` | `ProcLink.link(Proc, Proc)` |
| `spawn_link(Module, Func, Args)` | `ProcLink.spawnLink(...)` |
| `unlink(Pid)` | Remove crash callbacks (manual) |
| `process_flag(trap_exit, true)` | `Proc.trapExits(true)` |
| `{'EXIT', Pid, Reason}` | `ExitSignal` in mailbox |

## Architecture

```
Process A                    Process B
    │                            │
    │<─────link installed────────>│
    │                            │
    │                    (crashes with Exception)
    │                            │
    │<───exit signal delivered────│
    │  (A interrupted unless     │
    │   trapping exits)          │
```

## Public API

### `link()` - Establish Bidirectional Link

```java
public static void link(Proc<?, ?> a, Proc<?, ?> b)
```

Establish a bidirectional link between `a` and `b`.

**Behavior:**
- If `a` terminates abnormally, `b` is interrupted with `a`'s exit reason
- If `b` terminates abnormally, `a` is interrupted with `b`'s exit reason
- Normal `Proc.stop()` by either side does **not** affect the other
- Mirrors Erlang's `link(Pid)` BIF

**Example:**
```java
Proc<StateA, MsgA> procA = new Proc<>(initA, handlerA);
Proc<StateB, MsgB> procB = new Proc<>(initB, handlerB);

ProcLink.link(procA, procB);

// If procA crashes, procB is interrupted (and vice versa)
```

### `spawnLink()` - Atomic Spawn + Link

```java
public static <S, M> Proc<S, M> spawnLink(
    Proc<?, ?> parent,
    S initial,
    BiFunction<S, M, S> handler
)
```

Atomically spawn a new process and link it to `parent`.

**Parameters:**
- `parent` - The existing process to link to
- `initial` - Child's initial state
- `handler` - Child's state handler

**Returns:**
- The newly spawned, linked child process

**Behavior:**
- The link is installed before the child processes any messages
- No window where the child could crash undetected
- Identical semantic guarantee to OTP's `spawn_link/3`

**Example:**
```java
Proc<State, Message> child = ProcLink.spawnLink(
    parent,
    new State(),
    (state, msg) -> handleMessage(state, msg)
);

// Child is now linked to parent - crashes propagate both ways
```

## Common Usage Patterns

### 1. Linked Worker Process

```java
Proc<CoordinatorState, Msg> coordinator =
    new Proc<>(new CoordinatorState(), coordHandler);

Proc<WorkerState, Msg> worker =
    ProcLink.spawnLink(coordinator, new WorkerState(), workerHandler);

// If worker crashes, coordinator is interrupted
// If coordinator crashes, worker is interrupted
```

### 2. Bidirectional Failure Detection

```java
Proc<AState, Msg> procA = new Proc<>(initA, handlerA);
Proc<BState, Msg> procB = new Proc<>(initB, handlerB);

ProcLink.link(procA, procB);

// Both processes can detect each other's crashes
// (unless trapping exits)
```

### 3. Critical Section Guard

```java
Proc<GuardState, Msg> guard = new Proc<>(init, guardHandler);
Proc<ResourceState, Msg> resource =
    ProcLink.spawnLink(guard, init, resourceHandler);

// If resource crashes, guard is notified and can clean up
// If guard crashes, resource is terminated (clean shutdown)
```

### 4. Process Pair with Trapped Exits

```java
Proc<State, Msg> procA = new Proc<>(initA, handlerA);
Proc<State, Msg> procB = new Proc<>(initB, handlerB);

ProcLink.link(procA, procB);
procB.trapExits(true);  // B will receive ExitSignal, not die

// When procA crashes, procB receives ExitSignal in mailbox
// procB can decide what to do (cleanup, restart, etc.)
```

### 5. Error Propagation Chain

```java
Proc<MainState, Msg> main = new Proc<>(initMain, mainHandler);
Proc<WorkerState, Msg> worker1 =
    ProcLink.spawnLink(main, initWorker, workerHandler);
Proc<WorkerState, Msg> worker2 =
    ProcLink.spawnLink(worker1, initWorker, workerHandler);

// Chain: main <- worker1 <- worker2
// Any crash propagates up the chain
```

## Exit Trapping

Processes can choose to trap exit signals instead of being killed:

```java
Proc<State, Msg> proc = new Proc<>(init, handler);
proc.trapExits(true);  // Trap exits instead of dying

// Define message type to include ExitSignal
sealed interface Msg permits Msg.Work, Msg.Stop {}

// Handle ExitSignal in message loop
BiFunction<State, Msg, State> handler = (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> {
        System.err.println("Linked process crashed: " + reason);
        // Decide what to do - cleanup, restart, etc.
        yield state;
    }
    case Msg.Work w -> doWork(state, w);
    default -> state;
};
```

**When to trap exits:**
- Need to clean up after a linked process dies
- Want to make a deliberate decision about crash propagation
- Implementing custom supervision strategies
- Building robust error recovery mechanisms

## Thread-Safety Guarantees

- **Crash callback installation:** Atomic during `link()` or `spawnLink()`
- **Exit signal delivery:** Delivered via interrupt to linked process
- **No race conditions:** `spawnLink()` has no window between spawn and link
- **Concurrent links:** Multiple links can be established concurrently

## Composability

Links are composable with `Supervisor`:

```java
Proc<State, Msg> supervisedProc = new Proc<>(init, handler);
Supervisor supervisor = new Supervisor();
supervisor.startChild(spec);  // Supervises the process

// Can also link to another process
Proc<OtherState, OtherMsg> peer = new Proc<>(initOther, otherHandler);
ProcLink.link(supervisedProc, peer);

// Both the supervisor's crash callback and the link's crash callback fire independently
```

## Performance Characteristics

- **Link installation:** O(1) - adds two callbacks (one per process)
- **Exit signal delivery:** O(1) - interrupt delivery
- **Memory overhead:** ~16 bytes per link (callback reference)
- **No blocking:** Immediate interrupt delivery on crash

## Related Classes

- **`ProcMonitor`** - Unilateral DOWN notifications (doesn't kill monitor)
- **`ExitSignal`** - Trapped exit signals delivered as mailbox messages
- **`Supervisor`** - Hierarchical supervision with restart strategies
- **`ProcRef`** - Stable references surviving supervisor restart

## Best Practices

1. **Use `spawnLink()`** for atomic spawn + link (no race window)
2. **Trap exits for cleanup:** When you need to handle crashes gracefully
3. **Normal exit doesn't propagate:** `Proc.stop()` doesn't kill linked processes
4. **Consider monitoring:** If you don't want to be killed by crashes
5. **Combine with supervision:** Supervised processes can also be linked

## Design Rationale

**Why Links instead of Just Monitoring?**

In Erlang/OTP, links provide bilateral failure containment:
- **Crash propagation:** Failures are contained by killing related processes
- **All-or-nothing semantics:** Linked process groups live or die together
- **No orphaned state:** No processes left running with stale references

JOTP preserves this model:
- **Links:** Bilateral - crash propagates both ways (fault containment)
- **Monitoring:** Unilateral - observe without being killed (observation)

**When to use links vs monitoring:**

| Use Case | Primitive |
|----------|-----------|
| Critical sections (all-or-nothing) | `ProcLink` |
| Error propagation chains | `ProcLink` |
| Detecting crashes without dying | `ProcMonitor` |
| Implementing timeouts | `ProcMonitor` |
| Cleanup without suicide | `ProcMonitor` + `trapExits()` |

Links are the foundation of OTP's "let it crash" philosophy - they ensure that failures are contained rather than silently corrupting system state.

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `link(Pid)` | `ProcLink.link(a, b)` |
| `spawn_link(M, F, A)` | `ProcLink.spawnLink(parent, init, handler)` |
| `unlink(Pid)` | Remove crash callbacks (manual cleanup) |
| `process_flag(trap_exit, true)` | `proc.trapExits(true)` |
| `receive {'EXIT', Pid, Reason} -> ... end` | Pattern match on `ExitSignal` in handler |
