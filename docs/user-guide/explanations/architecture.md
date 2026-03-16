# Architecture: JOTP Design Principles

## Overview

JOTP brings Erlang/OTP's battle-tested concurrency model to Java 26, implementing 15 core primitives that enable building fault-tolerant distributed systems.

## The Concurrency Model

### Lightweight Processes

At JOTP's core is the **`Proc<S, M>`** — a lightweight process equivalent to Erlang's `spawn/3`.

Each process:
- Runs in a **virtual thread** (not a platform thread) — minimal overhead
- Maintains **isolated, immutable state** of type `S`
- Receives **typed messages** of type `M` (use sealed interfaces and records)
- Executes a **pure handler** function: `(state, message) → nextState`
- Never blocks the JVM's limited platform thread pool

```java
var counter = new Proc<>(
    0,  // initial state
    (state, msg) -> state + 1  // pure handler
);
```

### Message Passing

Processes communicate exclusively through message passing — no shared memory:

- **Fire-and-forget:** `proc.tell(msg)` — async, non-blocking
- **Request-reply:** `proc.ask(msg)` — returns `CompletableFuture<State>`
- **Timed calls:** `proc.ask(msg, timeout)` — prevents deadlocks

### Process Lifecycle

```
Created → Running → Crashed/Stopped
                ↓
            Supervised → Restarted
```

When a process crashes (unhandled exception):
1. Crash callbacks fire
2. Linked processes receive EXIT signals
3. Supervisor decides whether to restart
4. Termination callbacks notify monitors

## The 15 OTP Primitives

### Core Concurrency (5 primitives)

1. **`Proc<S,M>`** — Lightweight virtual-thread process
2. **`ProcRef<S,M>`** — Opaque, stable process handle (survives restarts)
3. **`Parallel`** — Structured concurrency: fan-out with fail-fast
4. **`Result<T,E>`** — Sealed type for railway-oriented programming
5. **`CrashRecovery`** — Isolated retry with exponential backoff

### Supervision & Reliability (4 primitives)

6. **`Supervisor`** — Hierarchical process management with 3 restart strategies
7. **`ProcLink`** — Bilateral crash propagation
8. **`ProcMonitor`** — Unilateral DOWN notifications
9. **`ExitSignal`** — Exception signaling between processes

### Process Behavior (3 primitives)

10. **`StateMachine<S,E,D>`** — gen_statem: explicit state/event/data
11. **`EventManager<E>`** — gen_event: decoupled event handlers
12. **`ProcLib`** — Startup handshake (sync init guarantee)

### Process Utilities (3 primitives)

13. **`ProcessRegistry`** — Global name table (process discovery)
14. **`ProcTimer`** — Timed message delivery (send_after, send_interval)
15. **`ProcSys`** — Introspection (get state, suspend, resume, stats)

## Design Patterns

### "Let It Crash" Philosophy

Rather than defensive programming, JOTP embraces failure:

1. **Don't catch exceptions** — let processes crash
2. **Supervisors restart automatically** — with appropriate strategy
3. **Results cascade** — monitors and links propagate failures
4. **Recovery is declarative** — declare restart limits and strategies, not logic

### Isolation Boundaries

Processes form **isolation domains** — failure in one doesn't poison others:

```
┌─ Supervisor ──────────┐
│  ┌─ Worker1 (CRASHED) │
│  │  [will restart]    │
│  ├─ Worker2 (running) │
│  └─ Worker3 (running) │
└────────────────────────┘
```

When Worker1 crashes:
- Worker2 and Worker3 continue unaffected (ONE_FOR_ONE strategy)
- Supervisor restarts Worker1
- System recovers without human intervention

### Supervision Hierarchies

Build reliable systems by composing supervisors:

```
┌─ RootSupervisor ─────────┐
├─ Database Manager        │
│  └─ Connection Pool      │
├─ Request Router          │
│  ├─ Worker Pool          │
│  └─ Cache Manager        │
└─ Status Monitor          │
```

Each supervisor can use different strategies (ONE_FOR_ONE, ALL_FOR_ONE, REST_FOR_ONE).

## Module Structure

```
io.github.seanchatmangpt.jotp/
├── Core Processes
│   ├── Proc.java              ← Proc<S,M>
│   ├── ProcRef.java           ← stable handles
│   └── ProcLink.java          ← bilateral linking
├── Supervision
│   ├── Supervisor.java        ← supervision trees
│   ├── ProcMonitor.java       ← unilateral monitoring
│   └── CrashRecovery.java     ← retry logic
├── Behaviors
│   ├── StateMachine.java      ← gen_statem equivalent
│   ├── EventManager.java      ← gen_event equivalent
│   └── ProcLib.java           ← startup handshake
├── Utilities
│   ├── ProcessRegistry.java   ← name table
│   ├── ProcTimer.java         ← timers
│   └── ProcSys.java           ← introspection
└── Error Handling
    ├── Result.java            ← sealed Result<T,E>
    └── ExitSignal.java        ← exception record
```

## Comparison to Erlang/OTP

| Erlang/OTP | JOTP | Notes |
|-----------|------|-------|
| Lightweight process | `Proc<S,M>` | Virtual thread, ~1 KB overhead |
| Mailbox | `LinkedTransferQueue` | Lock-free MPMC queue |
| `spawn/3` | `new Proc(...)` | Constructor starts automatically |
| `send/2` | `tell(msg)` | Async message send |
| `gen_server:call/2` | `ask(msg)` | Sync request-reply |
| `gen_statem` | `StateMachine<S,E,D>` | Explicit state/event/data |
| `gen_event` | `EventManager<E>` | Event handler dispatch |
| `supervisor` | `Supervisor` | Restart strategies |
| Links | `ProcLink` | Bilateral crash propagation |
| Monitors | `ProcMonitor` | Unilateral notifications |
| `global` | `ProcessRegistry` | Process discovery |
| `timer` | `ProcTimer` | Timed messages |
| `sys` | `ProcSys` | Introspection |

## Virtual Threads vs Platform Threads

JOTP uses **virtual threads** (Project Loom, Java 21+):

- **1 million processes** on a modest server
- **~1 KB memory per process** (vs ~1 MB for platform threads)
- **No context switching costs** — scheduler unmaps blocked threads
- **No thread pool exhaustion** — scale to millions

Code looks like blocking I/O, but yields to the scheduler automatically.

## Next: Learn JOTP

Start with the **[tutorials](../tutorials/)** for hands-on examples, or dive into **[how-to guides](../how-to/)** for specific tasks.
