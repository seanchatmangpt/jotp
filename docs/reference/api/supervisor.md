# Supervisor API Reference

**OTP supervision tree node** — Hierarchical process supervision with automatic restart strategies.

## Overview

`Supervisor` implements OTP's fault-tolerance core: monitor child processes, detect crashes, and apply restart strategies. This "let it crash" approach isolates failures and enables self-healing systems.

### Key Characteristics

- **Four Restart Strategies:** ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE, SIMPLE_ONE_FOR_ONE
- **ChildSpec Configuration:** Per-child restart policy, shutdown method, and significance
- **Auto-Shutdown:** Optional automatic shutdown when significant children terminate
- **Intensity Limiting:** Max restarts within time window prevents infinite restart loops
- **Virtual Thread Execution:** Supervisor runs on its own virtual thread

### OTP Equivalence

| Erlang/OTP | Java 26 |
|------------|---------|
| `supervisor:start_link/3` | `Supervisor.create()` |
| `one_for_one` | `Strategy.ONE_FOR_ONE` |
| `child_spec()` | `ChildSpec` record |
| `supervisor:terminate_child/2` | `supervisor.terminateChild(id)` |
| `supervisor:which_children/1` | `supervisor.whichChildren()` |

## Public API

### Factory Methods

#### `create(Strategy strategy, int maxRestarts, Duration window)`

Create a supervisor with the given strategy and restart limits.

```java
var sup = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                    // Max 5 crashes within window
    Duration.ofSeconds(60) // 60-second window
);
```

**Parameters:**
- `strategy` — Restart strategy (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- `maxRestarts` — Crash limit within window; supervisor gives up on the `maxRestarts`-th crash (allows up to `maxRestarts - 1` restarts)
- `window` — Time window for counting restart attempts

**Returns:** Running `Supervisor` instance

---

#### `create(String name, Strategy strategy, int maxRestarts, Duration window)`

Create a named supervisor (used for thread naming).

```java
var sup = Supervisor.create(
    "db-supervisor",      // Name for debugging
    Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);
```

---

#### `create(Strategy strategy, int maxRestarts, Duration window, AutoShutdown autoShutdown)`

Create a supervisor with auto-shutdown behavior.

```java
var sup = Supervisor.create(
    Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60),
    Supervisor.AutoShutdown.ANY_SIGNIFICANT // Shut down if any significant child exits
);
```

**AutoShutdown options:**
- `NEVER` — Never auto-shutdown (default)
- `ANY_SIGNIFICANT` — Shut down when any significant child terminates by itself
- `ALL_SIGNIFICANT` — Shut down when all significant children have terminated

---

#### `createSimple(ChildSpec<S,M> template, int maxRestarts, Duration window)`

Create a SIMPLE_ONE_FOR_ONE supervisor for dynamic homogeneous child pools.

```java
var template = Supervisor.ChildSpec.worker(
    "conn",
    ConnectionState::new,
    ConnectionHandler::handle
);

var pool = Supervisor.createSimple(
    template,
    10,                   // Max 10 crashes within window
    Duration.ofSeconds(30)
);

// Dynamically spawn instances
var conn1 = pool.startChild();
var conn2 = pool.startChild();
```

**Use case:** Connection pools, worker pools, dynamic service instances

---

### Child Management

#### `supervise(String id, S initialState, BiFunction<S,M,S> handler)`

Backward-compatible convenience method: supervise a permanent worker child.

**Equivalent to:** `startChild(ChildSpec.permanent(id, initialState, handler))`

```java
var worker = sup.supervise(
    "worker-1",
    new WorkerState(),
    WorkerHandler::handle
);
```

**Returns:** `ProcRef<S,M>` — Stable handle that survives restarts

**Note:** All restarts use the same `initialState` value. For mutable state, all restarts share the same instance (potential corrupted state). Prefer immutable state or state factories.

---

#### `startChild(ChildSpec<S,M> spec)`

Add a child to the supervision tree using an explicit `ChildSpec`.

```java
var spec = new Supervisor.ChildSpec<>(
    "worker-1",
    () -> new WorkerState(),  // State factory (fresh state each restart)
    WorkerHandler::handle,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(10)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false  // Not significant
);

var worker = sup.startChild(spec);
```

**Not valid for SIMPLE_ONE_FOR_ONE supervisors** — use `startChild()` without args instead.

**Returns:** `ProcRef<S,M>` — Stable handle that survives restarts

---

#### `startChild()`

Spawn a new instance from the template (SIMPLE_ONE_FOR_ONE only).

```java
var conn1 = pool.startChild(); // Returns ProcRef<ConnectionState,ConnMsg>
var conn2 = pool.startChild();
```

**Returns:** `ProcRef<S,M>` for the new instance

**Throws:** `IllegalStateException` if not a SIMPLE_ONE_FOR_ONE supervisor

---

#### `terminateChild(String id)`

Stop a child and retain its spec in the tree.

```java
sup.terminateChild("worker-1"); // Child can be restarted later
```

**Behavior:**
- Does NOT trigger auto-shutdown even if child is significant
- Child can be restarted later via `startChild(ChildSpec)` with same id
- Child spec remains in the tree

**Use case:** Temporary shutdown without removing child from supervision

---

#### `terminateChild(ProcRef<?,?> ref)`

Stop a specific SIMPLE_ONE_FOR_ONE instance by its ProcRef.

```java
pool.terminateChild(conn1);
```

---

#### `deleteChild(String id)`

Remove a stopped child's spec from the tree.

```java
sup.terminateChild("worker-1");
sup.deleteChild("worker-1"); // Spec removed from tree
```

**Throws:** `IllegalStateException` if child is still running

---

#### `whichChildren()`

Returns a snapshot of the current child tree state.

```java
List<Supervisor.ChildInfo> children = sup.whichChildren();
for (var child : children) {
    System.out.println(child.id() + ": " + (child.alive() ? "alive" : "dead"));
}
```

**Returns:** Unmodifiable list of `ChildInfo` records

**Fields:**
- `id` — Child identifier
- `alive` — Whether child is currently running
- `type` — `WORKER` or `SUPERVISOR`

---

### Lifecycle

#### `shutdown()`

Gracefully shut down the supervisor and all its children.

```java
sup.shutdown(); // Blocks until all children terminated
```

**Shutdown order:**
- Static strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE): LIFO order (reverse registration)
- SIMPLE_ONE_FOR_ONE: Asynchronous parallel shutdown

**Blocks:** Until all children have terminated

---

#### `isRunning()`

Returns `true` if the supervisor is still running.

```java
if (!sup.isRunning()) {
    System.err.println("Supervisor terminated: " + sup.fatalError());
}
```

---

#### `fatalError()`

Returns the fatal error that caused the supervisor to terminate, or `null` if still running.

```java
Throwable error = sup.fatalError();
if (error != null) {
    logger.error("Supervisor failed", error);
}
```

**Causes:** Exceeding max restarts within window

---

## Nested Types

### `Strategy` (Enum)

Restart strategy for the supervision tree.

| Strategy | Description |
|----------|-------------|
| `ONE_FOR_ONE` | Only the crashed child is restarted. Other children unaffected. |
| `ONE_FOR_ALL` | All children are restarted when any child crashes. |
| `REST_FOR_ONE` | The crashed child and all children started after it are restarted. |
| `SIMPLE_ONE_FOR_ONE` | Dynamic homogeneous pool; all children are instances of the same template spec. |

**Use cases:**
- `ONE_FOR_ONE` — Independent workers (default choice)
- `ONE_FOR_ALL` — Tightly coupled components (all must restart together)
- `REST_FOR_ONE` — Dependencies where later children depend on earlier ones
- `SIMPLE_ONE_FOR_ONE` — Connection pools, worker pools

---

### `ChildSpec<S,M>` (Record)

Per-child configuration — mirrors OTP's `child_spec()` map.

**Fields:**

```java
public record ChildSpec<S, M>(
    String id,                          // Unique identifier
    Supplier<S> stateFactory,           // Creates initial state (fresh each restart)
    BiFunction<S, M, S> handler,        // Message handler
    RestartType restart,                // When to restart
    Shutdown shutdown,                  // How to terminate
    ChildType type,                     // WORKER or SUPERVISOR
    boolean significant                 // Triggers auto-shutdown?
)
```

#### `RestartType` (Enum)

Controls when a terminated child is restarted.

| Type | Description |
|------|-------------|
| `PERMANENT` | Always restarted — on crash or normal exit |
| `TRANSIENT` | Restarted only on abnormal exit (crash). Normal exit not restarted. |
| `TEMPORARY` | Never restarted |

**Default:** `PERMANENT` (via `ChildSpec.worker()` factory)

#### `ChildType` (Enum)

Whether the child is a worker process or a supervisor.

| Type | Description |
|------|-------------|
| `WORKER` | Regular worker process |
| `SUPERVISOR` | Child supervisor (forms supervision tree) |

#### `Shutdown` (Sealed Interface)

How a child is terminated during shutdown.

**Implementations:**

```java
// Unconditional immediate termination (OTP: brutal_kill)
record BrutalKill() implements Shutdown {}

// Graceful shutdown with timeout (OTP: integer milliseconds)
record Timeout(Duration duration) implements Shutdown {}

// Wait indefinitely (OTP: infinity)
record Infinity() implements Shutdown {}
```

**Factory Methods:**

```java
// Common case: permanent worker with 5-second shutdown timeout
ChildSpec<S,M> spec = ChildSpec.worker(
    "worker-1",
    () -> initialState,
    handler
);

// Permanent worker with fixed initial state
ChildSpec<S,M> spec = ChildSpec.permanent(
    "worker-1",
    initialState,
    handler
);
```

**Validation:** All fields are non-null by construction (compact constructor)

---

### `AutoShutdown` (Enum)

Controls whether and when a supervisor automatically shuts itself down when significant children terminate.

| Value | Description |
|-------|-------------|
| `NEVER` | Automatic shutdown disabled (default) |
| `ANY_SIGNIFICANT` | Shut down when any significant child terminates by itself |
| `ALL_SIGNIFICANT` | Shut down when all significant children have terminated |

**Only applies** when children terminate by themselves, not when termination is supervisor-initiated (e.g., via `terminateChild()`).

---

### `ChildInfo` (Record)

Snapshot of a child's current state, returned by `whichChildren()`.

```java
public record ChildInfo(
    String id,              // Child identifier
    boolean alive,          // Currently running?
    ChildType type          // WORKER or SUPERVISOR
)
```

---

## Implementation Details

### Restart Flow

1. **Child crashes** → Crash callback fires
2. **Event queued** → `SvEvent_ChildCrashed` posted to supervisor's event queue
3. **Intensity check** → Restart history pruned to window, new crash added
4. **Max restarts?** → If exceeded, supervisor terminates entire tree
5. **Restart type check** → PERMANENT restarts always; TRANSIENT only on crash
6. **Strategy applied** → ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE, or SIMPLE_ONE_FOR_ONE
7. **75ms delay** → Brief delay before restart (absorbs rapid re-crash messages)
8. **New process spawned** → Fresh state from `stateFactory`
9. **ProcRef updated** → Stable handle now points to new process

### Intensity Tracking

Each child maintains a restart history (`List<Instant>`):

```java
// Before restart:
Instant now = Instant.now();
entry.restartHistory.removeIf(t -> t.isBefore(now.minus(window)));
entry.restartHistory.add(now);

if (entry.restartHistory.size() >= maxRestarts) {
    // Supervisor gives up
    fatalError = cause;
    running = false;
    stopAllOrdered();
}
```

**Example:** `maxRestarts=5, window=60s` allows 4 crashes in 60 seconds. The 5th crash triggers supervisor termination.

### Restart Delay

`restartOne()` spawns a virtual thread with 75ms delay:

```java
Thread.ofVirtual()
    .name("supervisor-restart-" + entry.spec.id())
    .start(() -> {
        try {
            Thread.sleep(75); // Delay
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        Object freshState = entry.spec.stateFactory().get();
        Proc newProc = spawnProc(entry, freshState);
        entry.ref.swap(newProc); // Update ProcRef
    });
```

**Purpose of delay:**
1. Gives observers time to inspect `lastError` on crashed proc via `ref.proc()`
2. Absorbs rapid re-crash messages during restart (they land on dead proc)

### Shutdown Ordering

**Static strategies:** LIFO (reverse registration order)

```java
// Children: [c1, c2, c3]
// Shutdown order: c3, c2, c1
for (int i = snapshot.size() - 1; i >= 0; i--) {
    stopChild(snapshot.get(i));
}
```

**SIMPLE_ONE_FOR_ONE:** Asynchronous parallel shutdown

```java
// All children stopped in parallel virtual threads
List<Thread> stoppers = new ArrayList<>(snapshot.size());
for (ChildEntry e : snapshot) {
    stoppers.add(Thread.ofVirtual().start(() -> stopChild(e)));
}
for (Thread t : stoppers) {
    t.join(); // Wait for all to finish
}
```

## Common Usage Patterns

### Simple ONE_FOR_ONE Supervisor

```java
var sup = Supervisor.create(
    Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

var worker1 = sup.supervise("worker-1", state1, handler1);
var worker2 = sup.supervise("worker-2", state2, handler2);

// If worker1 crashes, only worker1 is restarted
// If any worker crashes >= 5 times in 60 seconds, supervisor terminates
```

### Tightly Coupled Components (ONE_FOR_ALL)

```java
var sup = Supervisor.create(
    Strategy.ONE_FOR_ALL,
    3,
    Duration.ofMinutes(5)
);

sup.supervise("db", dbState, dbHandler);
sup.supervise("cache", cacheState, cacheHandler);

// If DB crashes, both DB and cache restart together
// Ensures cache consistency with DB
```

### Dependency Chain (REST_FOR_ONE)

```java
var sup = Supervisor.create(
    Strategy.REST_FOR_ONE,
    5,
    Duration.ofSeconds(30)
);

sup.supervise("database", dbState, dbHandler);
sup.supervise("service", serviceState, serviceHandler);
sup.supervise("api", apiState, apiHandler);

// If service crashes, service and api restart (database unaffected)
// If api crashes, only api restarts
```

### Dynamic Connection Pool (SIMPLE_ONE_FOR_ONE)

```java
var template = ChildSpec.worker(
    "conn",
    ConnectionState::new,
    ConnHandler::handle
);

var pool = Supervisor.createSimple(template, 10, Duration.ofSeconds(30));

for (int i = 0; i < 10; i++) {
    pool.startChild(); // Spawn 10 connections
}
```

### Nested Supervision Trees

```java
// Root supervisor
var root = Supervisor.create("root", Strategy.ONE_FOR_ONE, 5, Duration.ofMinutes(5));

// Child supervisor
var childSup = root.supervise(
    "child-sup",
    () -> Supervisor.create(Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(60)),
    supHandler
);

// Workers under child supervisor
childSup.supervise("worker-1", state1, handler1);
childSup.supervise("worker-2", state2, handler2);

// If worker-1 crashes, childSup restarts both workers
// If childSup crashes >= 3 times, root restarts childSup
```

### Significant Children with Auto-Shutdown

```java
var spec = new ChildSpec<>(
    "critical-worker",
    () -> new WorkerState(),
    handler,
    RestartType.PERMANENT,
    new Shutdown.Timeout(Duration.ofSeconds(10)),
    ChildType.WORKER,
    true  // significant = true
);

var sup = Supervisor.create(
    Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60),
    AutoShutdown.ANY_SIGNIFICANT // Shut down if any significant child exits
);

sup.startChild(spec);

// If critical-worker exits normally (not crash), supervisor shuts down
```

## Best Practices

### 1. Use State Factories for Fresh State

```java
// Good: Fresh state each restart
var spec = ChildSpec.worker(
    "worker",
    () -> new WorkerState(new HashMap<>()), // Factory
    handler
);

// Bad: Shared state across restarts
var sharedState = new WorkerState(new HashMap<>());
var spec = ChildSpec.worker(
    "worker",
    () -> sharedState, // Same instance every restart
    handler
);
```

### 2. Choose Appropriate Restart Type

```java
// Permanent: Always restart (database connections, critical services)
RestartType.PERMANENT

// Transient: Only restart on crash (batch jobs, one-shot tasks)
RestartType.TRANSIENT

// Temporary: Never restart (ephemeral workers, optional services)
RestartType.TEMPORARY
```

### 3. Set Appropriate Intensity Limits

```java
// Too lenient: Slow failures not detected
Supervisor.create(strategy, 1000, Duration.ofDays(1));

// Too strict: Normal restarts kill supervisor
Supervisor.create(strategy, 2, Duration.ofSeconds(1));

// Balanced: Detect rapid crashes but allow normal restarts
Supervisor.create(strategy, 5, Duration.ofMinutes(1));
```

### 4. Use Appropriate Shutdown Strategies

```java
// Workers: Graceful shutdown with timeout
new Shutdown.Timeout(Duration.ofSeconds(5))

// Supervisors: Wait indefinitely (for children to shut down)
new Shutdown.Infinity()

// Unresponsive processes: Immediate kill
new Shutdown.BrutalKill()
```

## Gotchas

### 1. Shared Mutable State Across Restarts

```java
var list = new ArrayList<String>();
sup.supervise("worker", list, handler);
// All restarts share the same list (corrupted state persists!)
```

**Solution:** Use immutable state or state factories:

```java
Supplier<List<String>> factory = ArrayList::new;
sup.supervise("worker", factory.get(), handler);
```

### 2. Max Restarts Semantics

```java
Supervisor.create(strategy, 5, window);
// Allows 4 crashes, gives up on 5th (not 5 crashes!)
```

**Remember:** `maxRestarts=5` means "give up on the 5th crash" = "allow 4 restarts"

### 3. SIMPLE_ONE_FOR_ONE Requires startChild() Without Args

```java
var pool = Supervisor.createSimple(template, 5, Duration.ofSeconds(30));
pool.startChild(spec); // ERROR: startChild(ChildSpec) not valid for SIMPLE_ONE_FOR_ONE

// Correct:
pool.startChild(); // No arguments
```

### 4. Auto-Shutdown Only Triggers on Spontaneous Exit

```java
sup.terminateChild("worker-1"); // Does NOT trigger auto-shutdown
// Child must exit by itself (not supervisor-initiated)
```

## Related Classes

- **[`Proc`](proc.md)** — Lightweight process with mailbox
- **[`ProcRef`](../proc_ref.md)** — Stable handle that survives restarts
- **[`ProcLink`](../proc_link.md)** — Bilateral crash propagation
- **[`ProcMonitor`](../proc_monitor.md)** — Unilateral DOWN notifications

## Performance Considerations

### Overhead

- **Per child:** ~200 bytes for ChildEntry + restart history
- **Restart delay:** 75ms (configurable internally)
- **Event processing:** Lock-free queue (negligible overhead)

### Scaling

- **Children per supervisor:** Thousands (limited by restart history memory)
- **Supervision depth:** 10+ levels (nested supervision trees)
- **Restart latency:** ~75ms + process spawn time (~1μs)

## See Also

- [JOTP Architecture](../architecture.md)
- [Proc API](proc.md)
- [StateMachine API](statemachine.md)
- [Supervision Tree Patterns](../patterns/supervision.md)
- [OTP Supervisor Behaviour](http://erlang.org/doc/man/supervisor.html)
