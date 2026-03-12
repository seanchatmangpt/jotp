# Reference: Supervisor API

> "Supervisors are the key to Erlang's fault tolerance. A supervisor's job is to start, stop, and monitor its children. When a child crashes, the supervisor decides what to do — restart it, restart all children, or give up."
> — Joe Armstrong

Complete API documentation for `Supervisor` — JOTP's hierarchical process supervision primitive.

---

## Overview

`Supervisor` manages a group of child processes and automatically restarts them when they crash. It implements three restart strategies from Erlang/OTP and enforces restart intensity limits to prevent infinite restart loops.

**Erlang/OTP equivalent:** `supervisor` behavior / `supervisor:start_link/2`

---

## Constructor

```java
public Supervisor(Strategy strategy, int maxRestarts, Duration window)
```

Create and start a supervisor with the given restart strategy and limits.

**Parameters:**
- `strategy` — Restart strategy: `Strategy.ONE_FOR_ONE`, `Strategy.ONE_FOR_ALL`, or `Strategy.REST_FOR_ONE`
- `maxRestarts` — Maximum number of restarts allowed within `window`. If exceeded, the supervisor itself terminates (propagating up the supervision tree).
- `window` — Time window for counting restarts. Restarts older than `window` are forgotten.

**Starts immediately:** The supervisor's virtual thread starts when the constructor returns.

**Example:**

```java
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                          // max 5 restarts...
    Duration.ofSeconds(60)      // ...within 60 seconds
);
```

**Restart intensity semantics:**
```
Window: 60 seconds, maxRestarts: 5

Crash at t=0s  → restarts=1 ✓
Crash at t=10s → restarts=2 ✓
Crash at t=20s → restarts=3 ✓
Crash at t=30s → restarts=4 ✓
Crash at t=40s → restarts=5 ✓
Crash at t=50s → restarts=6 ✗ → Supervisor terminates!

Crash at t=0s  → restarts=1 ✓
Crash at t=70s → restarts=1 ✓ (t=0 crash is outside the 60s window)
```

---

## Strategy Enum

```java
public enum Strategy {
    ONE_FOR_ONE,
    ONE_FOR_ALL,
    REST_FOR_ONE
}
```

### `Strategy.ONE_FOR_ONE`

**When one child crashes, restart only that child. Other children continue running.**

Use when: Children are independent — a crash in one doesn't affect the state of others.

```
Before: [A] [B] [C]
B crashes
After:  [A] [B'] [C]   (B' is new instance with initial state)
```

**Common use cases:**
- HTTP request handlers (each request is independent)
- Per-tenant supervisors in multi-tenant SaaS
- Worker pool members processing independent jobs

### `Strategy.ONE_FOR_ALL`

**When one child crashes, stop and restart ALL children.**

Use when: Children share state or have a dependency cycle — if one crashes, the group must reset together.

```
Before: [A] [B] [C]
B crashes
After:  [A'] [B'] [C']  (all restarted with initial state)
```

**Common use cases:**
- Database connection + cache + connection pool (must restart together)
- Coordinated state machines where all must agree on initial state

### `Strategy.REST_FOR_ONE`

**When one child crashes, restart that child and all children started AFTER it.**

Use when: Children have a startup dependency order — later children depend on earlier ones.

```
Started order: A, B, C, D
C crashes
After: [A] [B] [C'] [D']   (C and D restarted; A and B continue)
```

**Common use cases:**
- Service initialization pipeline (config → connection pool → workers)
- Chain of dependent processes where each depends on the previous

---

## `supervise()` — Add a Child Process

```java
public <S, M> ProcRef<S, M> supervise(String id, S initialState, BiFunction<S, M, S> handler)
```

Start and supervise a child process.

**Parameters:**
- `id` — Unique string identifier for this child within this supervisor.
- `initialState` — Initial state for the child process. Used on every restart.
- `handler` — Pure function `(state, message) → newState`.

**Returns:** A `ProcRef<S, M>` — a stable handle that transparently redirects to the restarted process after each restart.

**Example:**

```java
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

ProcRef<Integer, CounterMsg> counter = supervisor.supervise(
    "counter",
    0,
    (state, msg) -> switch (msg) {
        case Increment _ -> state + 1;
        case Reset _     -> 0;
        case Snapshot _  -> state;
    }
);

// Use ProcRef — survives restarts transparently
counter.tell(new Increment());
Integer value = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
```

### How `ProcRef` Survives Restarts

`ProcRef<S,M>` is a stable handle. When the supervised process crashes and restarts:

1. Supervisor stops the crashed `Proc` instance
2. Supervisor creates a new `Proc` with `initialState`
3. `ProcRef` atomically updates its internal reference
4. All subsequent `ref.tell()` / `ref.ask()` route to the new instance

**This is transparent to callers.** The same `ref` works before and after restarts.

---

## `shutdown()` — Stop All Children

```java
public void shutdown() throws InterruptedException
```

Gracefully shut down the supervisor and all its children.

**Behavior:**
- Sends a shutdown signal to the supervisor's event loop
- Stops all children in reverse order of their creation
- Blocks until all children have terminated

```java
supervisor.shutdown();
// All supervised processes are now stopped
```

---

## `isRunning()` — Health Check

```java
public boolean isRunning()
```

Returns `true` if the supervisor is still running (hasn't exceeded restart limits or been shut down).

```java
if (!supervisor.isRunning()) {
    log.error("Supervisor exceeded restart limit");
    // Handle at parent supervisor level
}
```

---

## `fatalError()` — Restart Limit Exceeded Cause

```java
public Throwable fatalError()
```

Returns the `Throwable` that caused the supervisor to exceed its restart limit, or `null` if healthy.

---

## Hierarchical Supervision Trees

Supervisors can supervise other supervisors, forming a tree:

```java
// Root supervisor: owns the entire application lifecycle
var root = new Supervisor(Strategy.ONE_FOR_ONE, 1, Duration.ofMinutes(1));

// HTTP subsystem supervisor
var httpSupervisor = new Supervisor(Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));
ProcRef<HttpState, HttpMsg> handler1 = httpSupervisor.supervise("handler-1", new HttpState(), httpHandler);
ProcRef<HttpState, HttpMsg> handler2 = httpSupervisor.supervise("handler-2", new HttpState(), httpHandler);

// Database subsystem supervisor
var dbSupervisor = new Supervisor(Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(30));
ProcRef<DbState, DbMsg> primary = dbSupervisor.supervise("primary", new DbState(), dbHandler);
```

**Propagation:** A child supervisor exceeding its restart limit terminates itself. The parent supervisor detects this and applies its own restart strategy. This creates layered fault isolation.

---

## Restart Strategy Selection Guide

| Scenario | Strategy | Reasoning |
|----------|----------|-----------|
| HTTP request handlers | ONE_FOR_ONE | Each request independent |
| Worker pool (independent tasks) | ONE_FOR_ONE | Workers don't share state |
| Cache + DB + connection pool | ONE_FOR_ALL | Must reset together on failure |
| Config loader → DB pool → Workers | REST_FOR_ONE | Later stages depend on earlier |
| Multi-tenant isolation | ONE_FOR_ONE | Tenant A failure must not affect Tenant B |

---

## Restart Window Tuning

| Scenario | Recommendation | Reasoning |
|----------|----------------|-----------|
| High-availability service | `10 / 5min` | Tolerates transient network issues |
| Development/testing | `1 / 1s` | Fail fast, expose bugs immediately |
| Fast-fail on permanent failure | `1 / 10s` | Don't retry a fundamentally broken service |

---

## Performance

| Operation | Latency |
|-----------|---------|
| `supervise()` (add child) | ~50 µs |
| Crash detection | < 1 ms |
| Child restart | ~200 µs |
| `shutdown()` (per child) | ~200 µs |

---

**See Also:**
- [Proc API](api-proc.md) — The process primitive
- [Tutorial 04: Supervision Basics](../tutorials/04-supervision-basics.md) — Hands-on introduction
- [How-To: Build Supervision Trees](../how-to/build-supervision-trees.md) — Advanced patterns

---

**Previous:** [Proc API](api-proc.md) | **Next:** [StateMachine API](api-statemachine.md)
