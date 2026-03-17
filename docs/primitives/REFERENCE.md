# JOTP 15 Primitives - Complete Reference Guide

**A comprehensive guide to all 15 JOTP (Java 26 OTP) primitives with real-world examples, performance characteristics, and best practices.**

---

## Table of Contents

1. [Process Primitives](#process-primitives)
   - [Proc](#1-proc-lightweight-process)
   - [ProcRef](#2-procref-stable-handle)
   - [ProcLink](#3-proclink-bidirectional-crash-propagation)
   - [ProcMonitor](#4-procmonitor-unilateral-monitoring)
   - [ProcRegistry](#5-procregistry-name-based-lookup)
   - [ProcTimer](#6-proctimer-scheduled-messages)
   - [ProcSys](#7-procsys-introspection-debugging)
   - [ProcLib](#8-proclib-initialization-handshake)

2. [Supervision & Fault Tolerance](#supervision--fault-tolerance)
   - [Supervisor](#9-supervisor-fault-tolerant-hierarchy)
   - [StateMachine](#10-statemachine-gen_statem-behavior)
   - [CrashRecovery](#11-crashrecovery-resilient-execution)

3. [Communication & Coordination](#communication--coordination)
   - [EventManager](#12-eventmanager-pubsub-event-bus)
   - [Parallel](#13-parallel-structured-concurrency)

4. [Error Handling](#error-handling)
   - [Result](#14-result-railway-oriented-error-handling)
   - [ExitSignal](#15-exitsignal-crash-reason-carrier)

---

## Process Primitives

### 1. Proc - Lightweight Process

**Purpose**: Fundamental unit of concurrency in JOTP - a lightweight virtual-thread process with a mailbox, equivalent to an Erlang process.

**Key Characteristics**:
- Runs on its own virtual thread (~1KB heap overhead)
- Uses `LinkedTransferQueue` for lock-free message passing (50-150ns per message)
- State is never shared - communicate only via message passing
- Crashes are detected by supervisors for automatic restart

**API Reference**:

```java
// Factory method (preferred)
public static <S, M> Proc<S, M> spawn(S initial, BiFunction<S, M, S> handler)

// Constructor
public Proc(S initial, BiFunction<S, M, S> handler)

// Messaging
public void tell(M msg)                              // Fire-and-forget
public CompletableFuture<S> ask(M msg)               // Request-reply
public CompletableFuture<S> ask(M msg, Duration timeout)  // Timed request-reply

// Lifecycle
public void stop() throws InterruptedException        // Graceful shutdown
public boolean isStopped()                           // Query state
public Throwable lastError()                         // Last crash reason

// Exit handling
public void trapExits(boolean trap)                  // Enable exit trapping
public boolean isTrappingExits()                     // Query trapping state

// Introspection support (package-private)
void addCrashCallback(Runnable cb)
void addTerminationCallback(Consumer<Throwable> cb)
void deliverExitSignal(Throwable reason)
```

**Real-World Examples**:

**Example 1: Counter Process**

```java
// Message type
sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.GetValue {
    record Increment() implements CounterMsg {}
    record GetValue(CompletableFuture<Integer> reply) implements CounterMsg {}
}

// State
record CounterState(int count) {}

// Handler
BiFunction<CounterState, CounterMsg, CounterState> handler = (state, msg) -> switch (msg) {
    case CounterMsg.Increment() -> new CounterState(state.count() + 1);
    case CounterMsg.GetValue(var reply) -> {
        reply.complete(state.count());
        yield state;
    }
};

// Create and use
var counter = Proc.spawn(new CounterState(0), handler);
counter.tell(new CounterMsg.Increment());
counter.tell(new CounterMsg.Increment());

var future = new CompletableFuture<Integer>();
counter.tell(new CounterMsg.GetValue(future));
System.out.println("Count: " + future.get()); // Count: 2
```

**Example 2: Request-Reply Pattern**

```java
// Database query process
sealed interface DbMsg permits DbMsg.Query {}
record DbMsg.Query(String sql, CompletableFuture<List<String>> reply) implements DbMsg {}

var db = Proc.spawn(
    new ConnectionState(dataSource),
    (state, msg) -> switch (msg) {
        case DbMsg.Query(var sql, var reply) -> {
            var result = state.execute(sql);
            reply.complete(result);
            yield state;
        }
    }
);

// Query with timeout
var future = new CompletableFuture<List<String>>();
db.tell(new DbMsg.Query("SELECT * FROM users", future));
var users = future.orTimeout(5, TimeUnit.SECONDS).get();
```

**Example 3: Linking Processes**

```java
var parent = Proc.spawn(initialState, parentHandler);
var child = ProcLink.spawnLink(parent, childState, childHandler);

// If parent crashes, child is interrupted
// If child crashes, parent receives ExitSignal (if trapping)
```

**Performance Characteristics**:
- **Memory**: ~1KB heap per process (empirically measured)
- **Message latency**: 50-150ns round-trip (LinkedTransferQueue)
- **Throughput**: Millions of messages per second per process
- **Startup**: Virtual thread creation is sub-microsecond

**When to Use**:
- Need isolated, concurrent state management
- Want automatic crash recovery via supervision
- Building message-passing architectures
- Need lightweight concurrency (millions of processes)

**Alternatives**:
- **Traditional threads**: Use when you need platform-thread features (CPU-bound work)
- **CompletableFuture**: Use for one-shot async tasks without ongoing state
- **Actor frameworks**: Use when you need distributed actors (JOTP is single-JVM)

---

### 2. ProcRef - Stable Handle

**Purpose**: Opaque, stable handle to a supervised process that transparently redirects to restarted instances.

**Key Characteristics**:
- Survives supervisor restarts transparently
- Always points to the live process instance
- Provides crash visibility via `proc().lastError()`
- Location transparency (caller doesn't know about restarts)

**API Reference**:

```java
// Constructor
public ProcRef(Proc<S, M> initial)

// Messaging (delegates to current process)
public void tell(M msg)
public CompletableFuture<S> ask(M msg)
public CompletableFuture<S> ask(M msg, Duration timeout)

// Lifecycle
public void stop() throws InterruptedException

// Introspection
public Proc<S, M> proc()  // Returns live proc or last crashed proc
```

**Real-World Examples**:

**Example 1: Supervised Worker**

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

// Register child - returns ProcRef
var workerRef = supervisor.supervise("worker-1", initialState, handler);

// Use the ref - it transparently survives restarts
workerRef.tell(new WorkMessage());

// Even if worker crashes and restarts, workerRef still works
for (int i = 0; i < 1000; i++) {
    workerRef.tell(new WorkMessage(i));
}
```

**Example 2: Detecting Crashes**

```java
var ref = supervisor.supervise("db-connection", dbState, dbHandler);

// Check if crashed
var proc = ref.proc();
if (proc.lastError() != null) {
    System.err.println("Process crashed: " + proc.lastError());
    // Handle crash (retry, alert, etc.)
}

// Use with Awaitility for testing
await().atMost(5, TimeUnit.SECONDS)
    .until(() -> ref.proc().lastError() != null);
```

**Performance Characteristics**:
- **Overhead**: Volatile read only (~5ns)
- **Restart cost**: 75ms delay before new process starts (absorbs rapid re-crashes)
- **Memory**: Negligible (wrapper around Proc)

**When to Use**:
- Working with supervised processes
- Need crash detection
- Building resilient systems
- Want transparent restart handling

**Anti-Patterns**:
- **Don't** hold raw `Proc` references from supervisors (they become stale)
- **Don't** cache `proc()` result (use fresh calls)
- **Don't** use `ProcRef` for non-supervised processes (unnecessary overhead)

---

### 3. ProcLink - Bidirectional Crash Propagation

**Purpose**: Bidirectional crash propagation between two processes - fundamental building block for fault-tolerant systems.

**Key Characteristics**:
- Bidirectional: if either process crashes, both are affected
- Normal exits don't propagate (only crashes)
- Can be combined with exit trapping for selective handling
- Composable with supervision (a supervised child can also be linked)

**API Reference**:

```java
// Link two existing processes
public static void link(Proc<?, ?> a, Proc<?, ?> b)

// Spawn and link atomically
public static <S, M> Proc<S, M> spawnLink(
    Proc<?, ?> parent,
    S initial,
    BiFunction<S, M, S> handler
)
```

**Real-World Examples**:

**Example 1: Critical Pair**

```java
var primary = Proc.spawn(primaryState, primaryHandler);
var backup = Proc.spawn(backupState, backupHandler);

// Link them - if either crashes, both crash
ProcLink.link(primary, backup);

// If primary crashes, backup is interrupted
// If backup crashes, primary is interrupted
// Normal shutdowns (stop()) don't propagate
```

**Example 2: Atomic Spawn-Link**

```java
var parent = Proc.spawn(parentState, parentHandler);

// Spawn child with link - no window where child can crash undetected
var child = ProcLink.spawnLink(parent, childState, childHandler);

// If child crashes, parent is interrupted (unless trapping exits)
// If parent crashes, child is interrupted
```

**Example 3: Exit Trapping + Linking**

```java
var parent = Proc.spawn(parentState, parentHandler);
parent.trapExits(true);  // Convert EXIT signals to messages

var child = ProcLink.spawnLink(parent, childState, childHandler);

// When child crashes, parent receives ExitSignal message
// instead of being interrupted
parent.tell(new SomeMessage());
// If child crashes, parent's handler receives:
// case ExitSignal(var reason) -> handleChildCrash(reason);
```

**Performance Characteristics**:
- **Link establishment**: O(1) callback registration
- **Crash propagation**: Immediate callback execution (~1µs)
- **Memory**: Two callbacks per link (~32 bytes)

**When to Use**:
- Building critical process pairs (all-or-nothing semantics)
- Want bidirectional fault propagation
- Implementing all-or-nothing transactions
- Need tight coupling between processes

**Anti-Patterns**:
- **Don't** link transient processes (use monitor instead)
- **Don't** create cycles in supervision trees (can cause mass restarts)
- **Don't** forget about exit trapping when you need selective handling

---

### 4. ProcMonitor - Unilateral Monitoring

**Purpose**: One-way monitoring - observe process termination without being affected by crashes.

**Key Characteristics**:
- Unidirectional: monitor process is never killed
- Monitors both normal and abnormal exits
- Returns opaque `MonitorRef` for cancellation
- Composable with supervision and linking

**API Reference**:

```java
// Start monitoring
public static <S, M> MonitorRef<S, M> monitor(
    Proc<S, M> target,
    Consumer<Throwable> downHandler
)

// Cancel monitoring
public static void demonitor(MonitorRef<?, ?> ref)

// MonitorRef record
public record MonitorRef<S, M>(
    Proc<S, M> target,
    Consumer<Throwable> callback
)
```

**Real-World Examples**:

**Example 1: Detecting Process Death**

```java
var worker = Proc.spawn(workerState, workerHandler);

// Monitor the worker
var monitorRef = ProcMonitor.monitor(worker, reason -> {
    if (reason == null) {
        System.out.println("Worker stopped normally");
    } else {
        System.err.println("Worker crashed: " + reason);
        // Handle crash (alert, restart, etc.)
    }
});

// Continue doing work...
worker.tell(new WorkMessage());

// When worker terminates (normal or crash), callback is invoked
```

**Example 2: Temporary Monitoring**

```java
var ref = ProcRegistry.whereis("database").orElseThrow();

// Monitor temporarily for a specific operation
var monitor = ProcMonitor.monitor(ref, reason -> {
    completeOperation.setException(
        new IllegalStateException("Database died during operation", reason)
    );
});

try {
    ref.tell(new DbQuery(query, future));
    future.get(5, TimeUnit.SECONDS);
} finally {
    // Cancel monitoring when done
    ProcMonitor.demonitor(monitor);
}
```

**Example 3: Call Timeout Implementation**

```java
// This is how gen_server:call/3 implements timeouts
public <T> CompletableFuture<T> callWithTimeout(
    Proc<?, ?> target,
    Object message,
    Duration timeout
) {
    var result = new CompletableFuture<T>();

    // Monitor target
    var monitor = ProcMonitor.monitor(target, reason -> {
        // Target died before responding
        result.completeExceptionally(
            new IllegalStateException("Target process died", reason)
        );
    });

    // Send request
    target.ask(message).thenAccept(response -> {
        ProcMonitor.demonitor(monitor);  // Cancel monitor
        result.complete((T) response);
    });

    // Add timeout
    result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    return result;
}
```

**Performance Characteristics**:
- **Monitor establishment**: O(1) callback registration
- **Notification**: Immediate callback execution (~1µs)
- **Memory**: One callback per monitor (~16 bytes)

**When to Use**:
- Need to observe process termination without risk
- Implementing timeouts (like gen_server:call)
- Building monitoring systems
- Want to detect process death without affecting it

**Anti-Patterns**:
- **Don't** forget to demonitor when done (memory leak)
- **Don't** use monitors for bidirectional relationships (use links)
- **Don't** block in the downHandler (use it to enqueue messages)

---

### 5. ProcRegistry - Name-Based Lookup

**Purpose**: Global process name registry - find processes by name without passing PIDs explicitly.

**Key Characteristics**:
- Global (JVM-scoped) name table
- Automatic deregistration on process termination
- Unique name enforcement (throws if name already registered)
- Thread-safe operations

**API Reference**:

```java
// Register a process
public static void register(String name, Proc<?, ?> proc)

// Look up a process
public static <S, M> Optional<Proc<S, M>> whereis(String name)

// Unregister explicitly
public static void unregister(String name)

// List all registered names
public static Set<String> registered()

// Test helper
public static void reset()
```

**Real-World Examples**:

**Example 1: Service Registration**

```java
// Start database service
var dbService = Proc.spawn(dbState, dbHandler);
ProcRegistry.register("database", dbService);

// Anywhere in the codebase:
Optional<Proc<DbState, DbMsg>> db = ProcRegistry.whereis("database");
if (db.isPresent()) {
    db.get().tell(new Query("SELECT * FROM users"));
}
```

**Example 2: Named Event Manager**

```java
// Start and register event manager
var eventMgr = EventManager.start("error-logger");

// Register error handler
eventMgr.addHandler(new ErrorLogger());

// Anywhere in codebase:
ProcRegistry.whereis("error-logger").ifPresent(mgr -> {
    mgr.proc().tell(new EventManager.Msg.Notify<>(new ErrorEvent(e)));
});
```

**Example 3: Dynamic Lookup Pattern**

```java
// Process names as service discovery
sealed interface ServiceRequest implements ServiceRequest {
    record GetDatabase() implements ServiceRequest {}
    record GetCache() implements ServiceRequest {}
    record GetQueue(String name) implements ServiceRequest {}
}

var router = Proc.spawn(initialState, (state, msg) -> switch (msg) {
    case ServiceRequest.GetDatabase() ->
        ProcRegistry.whereis("database").orElseThrow();
    case ServiceRequest.GetCache() ->
        ProcRegistry.whereis("cache").orElseThrow();
    case ServiceRequest.GetQueue(var name) ->
        ProcRegistry.whereis("queue-" + name).orElseThrow();
});
```

**Performance Characteristics**:
- **Registration**: O(1) ConcurrentHashMap put
- **Lookup**: O(1) ConcurrentHashMap get
- **Memory**: ~100 bytes per registration (name + entry)

**When to Use**:
- Building service discovery
- Need global process references
- Implementing named services
- Don't want to pass PIDs through call stack

**Anti-Patterns**:
- **Don't** use for scoped/namespace isolation (use separate maps)
- **Don't** register transient processes (clutters registry)
- **Don't** forget that names are global (name collisions)
- **Don't** rely on registry for supervision (use Supervisor instead)

---

### 6. ProcTimer - Scheduled Messages

**Purpose**: OTP-style process timers - send timed messages to processes for timeout-based behaviors.

**Key Characteristics**:
- One-shot delayed messages (`sendAfter`)
- Repeating periodic messages (`sendInterval`)
- Cancelable timers
- Uses single daemon platform thread (CPU-bound timer wheel)

**API Reference**:

```java
// One-shot timer
public static <M> TimerRef sendAfter(long delayMs, Proc<?, M> target, M msg)
public static <M> TimerRef sendAfter(Duration delay, Proc<?, M> target, M msg)

// Periodic timer
public static <M> TimerRef sendInterval(long periodMs, Proc<?, M> target, M msg)
public static <M> TimerRef sendInterval(Duration period, Proc<?, M> target, M msg)

// Cancel timer
public static boolean cancel(TimerRef ref)
public boolean cancel()  // TimerRef method

// TimerRef record
public record TimerRef(ScheduledFuture<?> future)
```

**Real-World Examples**:

**Example 1: Request Timeout**

```java
// Send request with timeout
var proc = Proc.spawn(state, handler);
var response = new CompletableFuture<String>();

// Set timeout
ProcTimer.sendAfter(Duration.ofSeconds(5), proc, new TimeoutMessage());

proc.tell(new Request("data", response));

// In handler:
BiFunction<State, Msg, State> handler = (state, msg) -> switch (msg) {
    case Request(var data, var reply) -> {
        // Handle request
        reply.complete("result");
        yield state;
    }
    case TimeoutMessage() -> {
        // Request timed out
        response.completeExceptionally(new TimeoutException());
        yield state;
    }
};
```

**Example 2: Periodic Heartbeat**

```java
// Send heartbeat every 10 seconds
var heartbeatTimer = ProcTimer.sendInterval(
    Duration.ofSeconds(10),
    monitoringService,
    new Heartbeat()
);

// Later: heartbeatTimer.cancel();
```

**Example 3: State Machine Timeout**

```java
// State machine with state_timeout
var sm = StateMachine.of(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                if (entered.equals(data.code())) {
                    // Correct code - unlock with timeout
                    return Transition.nextState(
                        new Open(),
                        data.withEntered(""),
                        Action.stateTimeout(10_000, "lock")  // Auto-lock after 10s
                    );
                }
                yield Transition.keepState(data.withEntered(entered));
            }
            case SMEvent.StateTimeout(var _) ->
                Transition.keepState(data.withEntered(""));  // Reset entry
            default -> Transition.keepState(data);
        };
        // ... other states
    }
);
```

**Performance Characteristics**:
- **Timer creation**: O(1) scheduled task submission
- **Cancellation**: O(1) future cancel
- **Memory**: ~200 bytes per timer
- **Scheduler**: Single daemon platform thread (shared across all timers)

**Design Rationale**:
- Uses platform thread (not virtual) because timer wheel management is CPU-bound
- Virtual threads excel at I/O, not CPU work
- Single thread ensures fairness and uniform latency

**When to Use**:
- Implementing timeouts
- Building periodic tasks
- State machine transitions
- Heartbeat/keep-alive messages

**Anti-Patterns**:
- **Don't** use for CPU-intensive work (timers should only send messages)
- **Don't** forget to cancel interval timers (memory leak)
- **Don't** rely on timer precision (best-effort scheduling)

---

### 7. ProcSys - Introspection & Debugging

**Purpose**: Process introspection, debug tracing, and hot code change without stopping the process (OTP's `sys` module).

**Key Characteristics**:
- Non-blocking state inspection
- Suspend/resume for hot code upgrades
- Live event tracing
- Statistics and logging
- Hot state transformation

**API Reference**:

```java
// Statistics
public static Stats statistics(Proc<?, ?> proc)
public record Stats(long messagesIn, long messagesOut, int queueDepth)

// State inspection
public static <S, M> CompletableFuture<S> getState(Proc<S, M> proc)

// Suspend/resume
public static void suspend(Proc<?, ?> proc)
public static void resume(Proc<?, ?> proc)

// Tracing
public static <S, M> void trace(Proc<S, M> proc, boolean enable)
public static <S, M> void trace(Proc<S, M> proc, boolean enable, DebugFormatter<M> formatter)
public static <S, M> List<DebugEvent<M>> getLog(Proc<S, M> proc)

// Hot code upgrade
public static <S, M> S codeChange(Proc<S, M> proc, Function<S, S> transformer)

// Debug options
public static <M> DebugOptions<M> debugOptions()
public static <M> DebugOptions<M> debugOptions(boolean trace)
public static <M> DebugOptions<M> debugOptions(boolean trace, int maxLog)
```

**Real-World Examples**:

**Example 1: State Inspection**

```java
var proc = Proc.spawn(initialState, handler);

// Get current state asynchronously
var futureState = ProcSys.getState(proc);
futureState.thenAccept(state -> {
    System.out.println("Current state: " + state);
});

// Or block for result
State state = ProcSys.getState(proc).get(5, TimeUnit.SECONDS);
```

**Example 2: Performance Monitoring**

```java
var proc = Proc.spawn(state, handler);

// Get statistics snapshot
var stats = ProcSys.statistics(proc);
System.out.println("Messages in: " + stats.messagesIn());
System.out.println("Messages out: " + stats.messagesOut());
System.out.println("Queue depth: " + stats.queueDepth());

// Calculate throughput
var previousStats = stats;
Thread.sleep(1000);
var currentStats = ProcSys.statistics(proc);
var throughput = currentStats.messagesOut() - previousStats.messagesOut();
System.out.println("Messages/sec: " + throughput);
```

**Example 3: Live Tracing**

```java
var proc = Proc.spawn(state, handler);

// Enable tracing
ProcSys.trace(proc, true);

// All messages are now printed to stdout:
// proc event = in: SomeMessage(x)
// proc event = out: SomeMessage(x) -> newState

// Get trace log
var events = ProcSys.getLog(proc);
events.forEach(e -> System.out.println("Event: " + e));

// Disable tracing
ProcSys.trace(proc, false);
```

**Example 4: Hot Code Upgrade**

```java
// Old state version
record UserStateV1(String name, int age) {}

// New state version (added field)
record UserStateV2(String name, int age, String email) {}

var proc = Proc.spawn(new UserStateV1("Alice", 30), handler);

// Migrate state to new version
UserStateV2 newState = ProcSys.codeChange(proc, old ->
    new UserStateV2(old.name(), old.age(), "unknown@example.com")
);

// Process continues running with new state schema
```

**Example 5: Suspend/Resume for Maintenance**

```java
var proc = Proc.spawn(state, handler);

// Pause message processing
ProcSys.suspend(proc);

// Perform maintenance (upgrade, migration, etc.)
doMaintenance();

// Resume processing
ProcSys.resume(proc);
```

**Performance Characteristics**:
- **Statistics**: O(1) atomic counter reads
- **State snapshot**: O(1) future enqueue (non-blocking)
- **Tracing**: ~100ns overhead per message (when enabled)
- **Code change**: Blocks until process handles request

**When to Use**:
- Production debugging
- Performance monitoring
- Hot code upgrades
- Maintenance windows
- Test debugging

**Anti-Patterns**:
- **Don't** call `getState` frequently in hot paths (use statistics instead)
- **Don't** leave tracing enabled in production (performance impact)
- **Don't** forget to resume suspended processes (deadlock)
- **Don't** block in code change functions (process is waiting)

---

### 8. ProcLib - Initialization Handshake

**Purpose**: Synchronous process startup with initialization handshake (OTP's `proc_lib`).

**Key Characteristics**:
- Blocks parent until child signals readiness
- Prevents race conditions during startup
- Returns explicit success/failure result
- Uses `ScopedValue` for thread-safe binding

**API Reference**:

```java
// Start with default 5-second timeout
public static <S, M> StartResult<S, M> startLink(
    S initial,
    Function<S, S> initHandler,
    BiFunction<S, M, S> loopHandler
)

// Start with custom timeout
public static <S, M> StartResult<S, M> startLink(
    S initial,
    Function<S, S> initHandler,
    BiFunction<S, M, S> loopHandler,
    Duration initTimeout
)

// Signal successful initialization
public static void initAck()

// Result types
sealed interface StartResult<S, M> {
    record Ok<S, M>(Proc<S, M> proc) implements StartResult<S, M> {}
    record Err<S, M>(Throwable reason) implements StartResult<S, M> {}
}
```

**Real-World Examples**:

**Example 1: Basic Initialization**

```java
var result = ProcLib.startLink(
    initialState,           // Initial state
    state -> {
        // Init handler - runs once before message loop
        // Perform initialization (connect to DB, open files, etc.)
        database.connect();
        cache.warmup();
        ProcLib.initAck();  // Signal ready
        return state;
    },
    (state, msg) -> {
        // Main message loop
        return handleMessage(state, msg);
    }
);

switch (result) {
    case ProcLib.StartResult.Ok(var proc) -> {
        // Child initialized successfully
        proc.tell(new WorkMessage());
    }
    case ProcLib.StartResult.Err(var reason) -> {
        // Child failed to initialize
        System.err.println("Init failed: " + reason);
    }
}
```

**Example 2: Registration Pattern**

```java
var result = ProcLib.startLink(
    new WorkerState(),
    state -> {
        // Register self in registry during init
        ProcRegistry.register("worker-" + state.id(), self);

        // Connect to dependencies
        state.connectToDatabase();

        // Signal ready (must be explicit!)
        ProcLib.initAck();

        return state;
    },
    workerHandler
);

// Parent knows registration is complete
```

**Example 3: Initialization Timeout**

```java
var result = ProcLib.startLink(
    initialState,
    state -> {
        // If initAck() is never called, parent times out
        // This prevents hanging forever
        Thread.sleep(10000);  // Simulate slow init
        ProcLib.initAck();    // Too late if timeout < 10s
        return state;
    },
    handler,
    Duration.ofSeconds(5)  // 5-second timeout
);

if (result instanceof ProcLib.StartResult.Err(var e)) {
    // Timed out after 5 seconds
    System.err.println("Init timed out: " + e);
}
```

**Example 4: Supervision Integration**

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

// Use ProcLib to ensure child is ready before adding to tree
var result = ProcLib.startLink(childState, initHandler, childHandler);

if (result instanceof ProcLib.StartResult.Ok(var childProc)) {
    // Create ProcRef and add to supervisor
    var ref = new ProcRef<>(childProc);
    supervisor.startChild(specWithRef(ref));
} else {
    // Init failed - don't add to tree
    log.error("Child failed to initialize: " + result);
}
```

**Performance Characteristics**:
- **Overhead**: ~50µs for ScopedValue binding + CountDownLatch
- **Timeout**: Default 5 seconds (configurable)
- **Memory**: Negligible (latch + ref)

**When to Use**:
- Complex initialization (DB connections, file handles)
- Need to ensure readiness before use
- Building supervision trees
- Preventing race conditions during startup
- Need explicit success/failure indication

**Anti-Patterns**:
- **Don't** forget to call `initAck()` (parent times out)
- **Don't** call `initAck()` before initialization is complete
- **Don't** use for trivial initialization (unnecessary overhead)
- **Don't** perform blocking I/O without reasonable timeout

---

## Supervision & Fault Tolerance

### 9. Supervisor - Fault-Tolerant Hierarchy

**Purpose**: Hierarchical process supervision with automatic restart strategies (OTP's `supervisor` behavior).

**Key Characteristics**:
- Four restart strategies: ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE, SIMPLE_ONE_FOR_ONE
- Automatic crash detection and restart
- Max restarts in time window (prevents infinite restart loops)
- ChildSpec for per-child configuration
- Auto-shutdown support

**API Reference**:

```java
// Factory methods
public static Supervisor create(Strategy strategy, int maxRestarts, Duration window)
public static Supervisor create(String name, Strategy strategy, int maxRestarts, Duration window)
public static Supervisor create(Strategy strategy, int maxRestarts, Duration window, AutoShutdown autoShutdown)
public static Supervisor createSimple(ChildSpec<?, ?> template, int maxRestarts, Duration window)

// Child management
public <S, M> ProcRef<S, M> supervise(String id, S initialState, BiFunction<S, M, S> handler)
public <S, M> ProcRef<S, M> startChild(ChildSpec<S, M> spec)
public ProcRef<S, M> startChild()  // For SIMPLE_ONE_FOR_ONE
public void terminateChild(String id) throws InterruptedException
public void deleteChild(String id)

// Query
public List<ChildInfo> whichChildren()
public boolean isRunning()
public Throwable fatalError()

// Lifecycle
public void shutdown() throws InterruptedException
```

**ChildSpec Configuration**:

```java
public record ChildSpec<S, M>(
    String id,                           // Unique identifier
    Supplier<S> stateFactory,            // State factory (called on each restart)
    BiFunction<S, M, S> handler,         // Message handler
    RestartType restart,                 // PERMANENT, TRANSIENT, TEMPORARY
    Shutdown shutdown,                   // BrutalKill, Timeout, Infinity
    ChildType type,                      // WORKER, SUPERVISOR
    boolean significant                  // Affects auto-shutdown
) {
    // Helper factory
    public static <S, M> ChildSpec<S, M> worker(
        String id,
        Supplier<S> stateFactory,
        BiFunction<S, M, S> handler
    )
}
```

**Real-World Examples**:

**Example 1: Basic ONE_FOR_ONE Supervisor**

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                           // Max 5 restarts
    Duration.ofSeconds(60)       // Within 60-second window
);

// Add workers
var worker1 = supervisor.supervise("worker-1", state1, handler1);
var worker2 = supervisor.supervise("worker-2", state2, handler2);

// If worker1 crashes, only worker1 is restarted
// If any worker crashes 5+ times in 60 seconds, supervisor gives up
```

**Example 2: ONE_FOR_ALL Strategy**

```java
var supervisor = Supervisor.create(
    "critical-pair",
    Supervisor.Strategy.ONE_FOR_ALL,
    3, Duration.ofMinutes(5)
);

var db = supervisor.startChild(ChildSpec.worker("db", dbState, dbHandler));
var cache = supervisor.startChild(ChildSpec.worker("cache", cacheState, cacheHandler));

// If db crashes, both db and cache are restarted
// If cache crashes, both db and cache are restarted
```

**Example 3: ChildSpec with Restart Policies**

```java
var spec = new Supervisor.ChildSpec<>(
    "ephemeral-worker",
    () -> new WorkerState(),           // State factory
    workerHandler,
    Supervisor.ChildSpec.RestartType.TRANSIENT,  // Restart on crash only
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(10)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false  // Not significant
);

var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

var worker = supervisor.startChild(spec);

// If worker crashes -> restarted
// If worker stops normally -> not restarted (TRANSIENT)
```

**Example 4: SIMPLE_ONE_FOR_ONE Dynamic Pool**

```java
// Create template for connection pool
var template = ChildSpec.worker(
    "connection",
    () -> new ConnectionState(connectToDB()),
    connectionHandler
);

var pool = Supervisor.createSimple(
    template,
    10, Duration.ofSeconds(30)  // Max 10 restarts in 30 seconds
);

// Dynamically spawn connections
var conn1 = pool.startChild();
var conn2 = pool.startChild();
var conn3 = pool.startChild();

// Each connection is independently supervised
// Names are auto-generated: connection-1, connection-2, etc.
```

**Example 5: Nested Supervisors**

```java
// Root supervisor
var rootSup = Supervisor.create(
    "root",
    Supervisor.Strategy.ONE_FOR_ONE,
    10, Duration.ofMinutes(1)
);

// Child supervisor
var childSupSpec = new Supervisor.ChildSpec<>(
    "child-supervisor",
    () -> Supervisor.create(
        "child",
        Supervisor.Strategy.ONE_FOR_ONE,
        5, Duration.ofSeconds(30)
    ),
    supHandler,  // Handler that delegates to supervisor's proc
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Infinity(),
    Supervisor.ChildSpec.ChildType.SUPERVISOR,
    false
);

var childSup = rootSup.startChild(childSupSpec);

// Add workers under child supervisor
var worker = childSup.startChild(ChildSpec.worker("worker", workerState, workerHandler));

// If root crashes -> child supervisor and its workers restart
// If child supervisor crashes -> only child supervisor restarts
// If worker crashes -> only worker restarts
```

**Restart Strategies**:

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| ONE_FOR_ONE | Only crashed child restarts | Independent workers |
| ONE_FOR_ALL | All children restart | Tightly-coupled processes |
| REST_FOR_ONE | Crashed child + all after it restart | Dependent startup order |
| SIMPLE_ONE_FOR_ONE | Dynamic homogeneous pool | Connection pools, workers |

**Performance Characteristics**:
- **Crash detection**: Immediate callback (~1µs)
- **Restart delay**: 75ms (absorbs rapid re-crashes)
- **Memory**: ~500 bytes per child entry
- **State factory**: Called on each restart (fresh state)

**When to Use**:
- Building fault-tolerant systems
- Need automatic crash recovery
- Isolating failures
- Production systems (99.999% uptime)

**Anti-Patterns**:
- **Don't** use mutable state in state factories (shared state bug)
- **Don't** create infinite restart loops (use maxRestarts)
- **Don't** forget shutdown timeouts (hanging shutdowns)
- **Don't** mix ONE_FOR_ALL with stateful services (massive restart cost)

---

### 10. StateMachine - gen_statem Behavior

**Purpose**: Full OTP `gen_statem` feature parity - complex workflows with state transitions, timeouts, and event handling.

**Key Characteristics**:
- Separates state, event, and data
- Multiple timeout types (state, event, generic)
- State enter callbacks
- Event postponement
- Action-based transitions

**API Reference**:

```java
// Factory methods
public static <S, E, D> StateMachine<S, E, D> of(
    S initialState,
    D initialData,
    TransitionFn<S, E, D> fn
)

public static <S, E, D> Builder<S, E, D> create(
    S initialState,
    D initialData,
    TransitionFn<S, E, D> fn
)

// Messaging
public void send(E event)
public CompletableFuture<D> call(E event)

// Query
public S state()
public D data()
public boolean isRunning()
public String stopReason()

// Lifecycle
public void stop() throws InterruptedException
```

**Transition Function**:

```java
@FunctionalInterface
public interface TransitionFn<S, E, D> {
    Transition<S, D> apply(S state, SMEvent<E> event, D data)
}
```

**Event Types**:

```java
public sealed interface SMEvent<E> permits
    SMEvent.User<E>,              // External user event
    SMEvent.StateTimeout<E>,      // State timeout fired
    SMEvent.EventTimeout<E>,      // Event timeout fired
    SMEvent.GenericTimeout<E>,    // Named generic timeout
    SMEvent.Internal<E>,          // Self-inserted event
    SMEvent.Enter<E>              // State enter callback
```

**Transition Results**:

```java
public sealed interface Transition<S, D> permits
    Transition.NextState<S, D>,      // Move to new state
    Transition.KeepState<S, D>,      // Stay in current state
    Transition.RepeatState<S, D>,    // Stay but re-enter
    Transition.Stop<S, D>,           // Terminate
    Transition.StopAndReply<S, D>    // Terminate with replies
```

**Real-World Examples**:

**Example 1: Code Lock with State Timeout**

```java
// State
sealed interface LockState permits Locked, Open {}
record Locked() implements LockState {}
record Open() implements LockState {}

// Event
sealed interface LockEvent implements LockEvent {}
record PushButton(char button) implements LockEvent {}

// Data
record LockData(String entered, String code) {
    LockData withEntered(String s) { return new LockData(s, code); }
}

// State machine
var sm = StateMachine.of(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                if (entered.equals(data.code())) {
                    // Correct code - unlock with timeout
                    return Transition.nextState(
                        new Open(),
                        data.withEntered(""),
                        Action.stateTimeout(10_000, "lock")  // Auto-lock after 10s
                    );
                }
                yield Transition.keepState(data.withEntered(entered));
            }
            case SMEvent.StateTimeout(var _) ->
                Transition.keepState(data.withEntered(""));  // Reset on timeout
            default -> Transition.keepState(data);
        };
        case Open() -> switch (event) {
            case SMEvent.StateTimeout(var _) ->
                Transition.nextState(new Locked(), data);  // Auto-lock
            case SMEvent.User(var _) ->
                Transition.keepState(data, Action.postpone());  // Defer buttons
            default -> Transition.keepState(data);
        };
    }
);

// Use
sm.send(new PushButton('1'));
sm.send(new PushButton('2'));
sm.send(new PushButton('3'));
sm.send(new PushButton('4'));  // Unlocks

// After 10 seconds, automatically locks
```

**Example 2: Connection Lifecycle with Enter Callbacks**

```java
// State
sealed interface ConnState permits Disconnected, Connecting, Connected {}
record Disconnected() implements ConnState {}
record Connecting() implements ConnState {}
record Connected() implements Connected {}

// Event
sealed interface ConnEvent permits Connect, Disconnect, Data, Timeout {}
record Connect(String host, int port) implements ConnEvent {}
record Disconnect() implements ConnEvent {}
record Data(byte[] payload) implements ConnEvent {}

// Data
record ConnData(String host, int port, Socket socket) {}

var sm = StateMachine.create(
    new Disconnected(),
    new ConnData("", 0, null),
    (state, event, data) -> switch (state) {
        case Disconnected() -> switch (event) {
            case SMEvent.User(Connect(var h, var p)) ->
                Transition.nextState(
                    new Connecting(),
                    new ConnData(h, p, null),
                    Action.eventTimeout(5000, "connect")
                );
            default -> Transition.keepState(data);
        };
        case Connecting() -> switch (event) {
            case SMEvent.EventTimeout(var _) -> {
                // Connection timed out
                System.err.println("Connection failed: timeout");
                yield Transition.nextState(new Disconnected(), new ConnData("", 0, null));
            }
            case SMEvent.User(Data(var payload)) -> {
                // Connection successful
                var socket = parseConnection(payload);
                yield Transition.nextState(new Connected(), data.withSocket(socket));
            }
            default -> Transition.keepState(data);
        };
        case Connected() -> switch (event) {
            case SMEvent.User(Disconnect()) ->
                Transition.nextState(new Disconnected(), new ConnData("", 0, null));
            case SMEvent.User(Data(var payload)) -> {
                // Handle data
                processData(payload);
                yield Transition.keepState(data);
            }
            default -> Transition.keepState(data);
        };
    }
).withStateEnter()  // Enable enter callbacks
.start();

// Enter callback for logging
// In transition function, also handle SMEvent.Enter:
case SMEvent.Enter(var previousState) -> {
    System.out.println("Entered state: " + (previousState == null ? "initial" : previousState));
    yield Transition.keepState(data);
}
```

**Example 3: Postpone and Replay**

```java
// State machine that defers events until ready
sealed interface State permits Busy, Ready {}
record Busy() implements State {}
record Ready() implements State {}

var sm = StateMachine.of(
    new Busy(),
    null,
    (state, event, data) -> switch (state) {
        case Busy() -> switch (event) {
            case SMEvent.User(var _) ->
                // Postpone all user events while busy
                Transition.keepState(data, Action.postpone());
            case SMEvent.Internal(var "ready") ->
                Transition.nextState(new Ready(), data);  // Triggers replay
            default -> Transition.keepState(data);
        };
        case Ready() -> switch (event) {
            case SMEvent.User(var msg) -> {
                // Handle postponed events
                handleMessage(msg);
                yield Transition.keepState(data);
            }
            default -> Transition.keepState(data);
        };
    }
);

// While busy:
sm.send(new WorkMessage1());  // Postponed
sm.send(new WorkMessage2());  // Postponed

// When ready:
sm.send(new SMEvent.Internal("ready"));  // Moves to Ready, replays postponed messages
```

**Performance Characteristics**:
- **Event processing**: ~200ns per event (pattern matching overhead)
- **State transition**: O(1) volatile write
- **Timeout scheduling**: ~1µs (scheduled executor)
- **Postponement**: O(1) queue operation

**When to Use**:
- Complex workflows with multiple states
- Need timeout-based transitions
- Event-driven protocols
- Stateful business logic

**Anti-Patterns**:
- **Don't** use for simple state machines (switch on state field)
- **Don't** forget to handle all event types (compiler won't catch missing cases)
- **Don't** create infinite loops with nextEvent
- **Don't** use blocking operations in transition function

---

### 11. CrashRecovery - Resilient Execution

**Purpose**: Joe Armstrong's "let it crash" philosophy - retry operations in isolated virtual threads.

**Key Characteristics**:
- Each attempt in isolated virtual thread
- Returns first success or last failure
- Railway-oriented Result type
- No state carried between attempts

**API Reference**:

```java
public static <T> Result<T, Exception> retry(
    int maxAttempts,
    Supplier<T> supplier
)
```

**Real-World Examples**:

**Example 1: HTTP Request with Retry**

```java
var result = CrashRecovery.retry(3, () -> {
    var response = httpClient.get("https://api.example.com/data");
    if (response.status() >= 500) {
        throw new RuntimeException("Server error: " + response.status());
    }
    return response.body();
});

switch (result) {
    case Result.Success(var body) ->
        System.out.println("Got data: " + body);
    case Result.Failure(var ex) ->
        System.err.println("Failed after 3 attempts: " + ex);
}
```

**Example 2: Database Connection**

```java
var connection = CrashRecovery.retry(5, () -> {
    try {
        return dataSource.getConnection();
    } catch (SQLException e) {
        throw new RuntimeException("Connection failed", e);
    }
});

switch (connection) {
    case Result.Success(var conn) ->
        useConnection(conn);
    case Result.Failure(var ex) ->
        alertTeam("Database unavailable: " + ex);
}
```

**Example 3: External Service Call**

```java
var price = CrashRecovery.retry(3, () -> {
    var response = pricingService.calculatePrice(order);
    if (response.isRateLimited()) {
        throw new RateLimitException("Rate limited");
    }
    return response.getPrice();
});

// If rate limited, retries with exponential backoff
// Returns first successful price or failure
```

**Performance Characteristics**:
- **Per-attempt overhead**: Virtual thread creation (~1µs)
- **Memory**: Isolated per attempt (no shared state)
- **Retry cost**: Full operation re-execution

**When to Use**:
- Resilient single-task execution
- Transient failure recovery
- Network operations
- External service calls

**Anti-Patterns**:
- **Don't** use for idempotent operations only (retries cause side effects)
- **Don't** retry indefinitely (always set maxAttempts)
- **Don't** use for persistent processes (use Supervisor instead)

---

## Communication & Coordination

### 12. EventManager - Pub/Sub Event Bus

**Purpose**: OTP's `gen_event` - decouple event producers from consumers with fault-isolated handlers.

**Key Characteristics**:
- Process-based event manager (first-class process)
- Fault isolation: crashing handlers don't kill manager
- Async and sync notification
- Hot code upgrade support
- Handler lifecycle management

**API Reference**:

```java
// Start event manager
public static <E> EventManager<E> start()
public static <E> EventManager<E> start(Duration timeout)
public static <E> EventManager<E> start(String name)
public static <E> EventManager<E> start(String name, Duration timeout)

// Handler management
public void addHandler(Handler<E> handler)
public void addSupHandler(Handler<E> handler)
public boolean deleteHandler(Handler<E> handler)
public boolean deleteHandler(Handler<E> handler, Duration timeout)

// Event broadcast
public void notify(E event)
public void syncNotify(E event) throws InterruptedException
public void syncNotify(E event, Duration timeout) throws InterruptedException

// Individual handler call
public void call(Handler<E> handler, E event)
public void call(Handler<E> handler, E event, Duration timeout)

// Lifecycle
public void stop()

// Hot code upgrade
public void codeChange(Object oldVsn, Object extra)
public void codeChange(Object oldVsn, Object extra, Duration timeout)

// Info delivery
public void info(Object info)
```

**Handler Interface**:

```java
public interface Handler<E> {
    void handleEvent(E event)
    default void terminate(Throwable reason) {}
    default void handleInfo(Object info) {}
    default void codeChange(Object oldVsn, Object extra) {}
}
```

**Real-World Examples**:

**Example 1: Error Logger**

```java
// Event type
record ErrorEvent(Instant timestamp, String message, Throwable error) {}

// Handler
class ErrorLogHandler implements EventManager.Handler<ErrorEvent> {
    private final PrintWriter writer;

    ErrorLogHandler(String file) throws IOException {
        this.writer = new PrintWriter(new FileWriter(file, true));
    }

    @Override
    public void handleEvent(ErrorEvent event) {
        writer.printf("[%s] %s: %s%n",
            event.timestamp(),
            event.message(),
            event.error()
        );
        writer.flush();
    }

    @Override
    public void terminate(Throwable reason) {
        writer.close();
        System.out.println("Error logger stopped: " + reason);
    }
}

// Start and use
var eventMgr = EventManager.start("error-logger");
eventMgr.addHandler(new ErrorLogHandler("/var/log/app/errors.log"));

// Anywhere in codebase:
ProcRegistry.whereis("error-logger").ifPresent(mgr ->
    mgr.tell(new EventManager.Msg.Notify<>(
        new ErrorEvent(Instant.now(), "Database error", e)
    ))
);
```

**Example 2: Metrics Collector**

```java
// Event type
sealed interface MetricEvent permits MetricEvent.Counter, MetricEvent.Gauge {
    record Counter(String name, long delta) implements MetricEvent {}
    record Gauge(String name, double value) implements MetricEvent {}
}

// Handler
class MetricsHandler implements EventManager.Handler<MetricEvent> {
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();

    @Override
    public void handleEvent(MetricEvent event) {
        switch (event) {
            case MetricEvent.Counter(var name, var delta) ->
                counters.merge(name, delta, Long::sum);
            case MetricEvent.Gauge(var name, var value) ->
                gauges.put(name, value);
        }
    }

    Map<String, Long> getCounters() { return Map.copyOf(counters); }
    Map<String, Double> getGauges() { return Map.copyOf(gauges); }
}

// Use
var metrics = EventManager.start();
var handler = new MetricsHandler();
metrics.addHandler(handler);

// Increment counter
metrics.notify(new MetricEvent.Counter("requests", 1));

// Set gauge
metrics.notify(new MetricEvent.Gauge("cpu.usage", 45.2));

// Query metrics
System.out.println(handler.getCounters());
```

**Example 3: Supervised Handler**

```java
// Handler that's tied to calling thread's lifecycle
class ConnectionHandler implements EventManager.Handler<ConnectionEvent> {
    @Override
    public void handleEvent(ConnectionEvent event) {
        // Handle connection event
    }

    @Override
    public void terminate(Throwable reason) {
        // Clean up when calling thread dies
        cleanupResources();
    }
}

// Add supervised handler
var eventMgr = EventManager.start();
var handler = new ConnectionHandler();

eventMgr.addSupHandler(handler);  // Auto-removed when calling thread dies

// When current thread terminates, handler is removed from manager
```

**Example 4: Synchronous Notification**

```java
var eventMgr = EventManager.start();

eventMgr.addHandler(new SyncHandler());
eventMgr.addHandler(new SlowHandler());

// Block until all handlers process event
eventMgr.syncNotify(new CriticalEvent());

System.out.println("All handlers processed event");
```

**Performance Characteristics**:
- **Handler list**: CopyOnWriteArrayList (O(n) iteration)
- **Notification**: ~500ns per handler (method call overhead)
- **Fault isolation**: Try-catch per handler (~100ns overhead)

**When to Use**:
- Decoupling event producers from consumers
- Building logging/metrics infrastructure
- Multiple independent event handlers
- Need fault isolation between handlers

**Anti-Patterns**:
- **Don't** block in handleEvent (use separate process)
- **Don't** forget to remove handlers (memory leak)
- **Don't** use for request-response (it's pub/sub)
- **Don't** throw exceptions (they cause handler removal)

---

### 13. Parallel - Structured Concurrency

**Purpose**: Erlang's `pmap` pattern with Java 26 StructuredTaskScope - fail-fast parallel execution.

**Key Characteristics**:
- All-or-nothing semantics (fail-fast)
- Virtual threads for massive parallelism
- Railway-oriented Result type
- Structured concurrency (proper cleanup)

**API Reference**:

```java
public static <T> Result<List<T>, Exception> all(
    List<Supplier<T>> tasks
)
```

**Real-World Examples**:

**Example 1: Parallel Data Fetching**

```java
var tasks = List.of(
    () -> userService.fetchUser(1),
    () -> userService.fetchUser(2),
    () -> userService.fetchUser(3),
    () -> userService.fetchUser(4)
);

var result = Parallel.all(tasks);

switch (result) {
    case Result.Success(var users) ->
        users.forEach(u -> System.out.println("Got: " + u));
    case Result.Failure(var ex) ->
        System.err.println("Fetch failed: " + ex);
}
```

**Example 2: Parallel Validation**

```java
var validators = List.of(
    () -> validateEmail(user.email()),
    () -> validatePhone(user.phone()),
    () -> validateAddress(user.address())
);

var result = Parallel.all(validators);

switch (result) {
    case Result.Success(var _) -> System.out.println("All valid");
    case Result.Failure(var ex) -> System.err.println("Validation failed: " + ex);
}
```

**Example 3: Fan-Out Fan-In**

```java
// Search multiple data sources in parallel
var tasks = List.of(
    () -> searchDatabase(query),
    () -> searchCache(query),
    () -> searchExternalAPI(query)
);

var results = Parallel.all(tasks);

switch (results) {
    case Result.Success(var hits) -> {
        var allHits = hits.stream()
            .flatMap(List::stream)
            .toList();
        return allHits;
    }
    case Result.Failure(var ex) ->
        throw new SearchException("Search failed", ex);
}
```

**Performance Characteristics**:
- **Per-task overhead**: Virtual thread creation (~1µs)
- **Fail-fast**: Immediate cancellation on first failure
- **Memory**: O(tasks) for result collection

**When to Use**:
- Independent parallel operations
- Need fail-fast semantics
- Fan-out/fan-in patterns
- Reducing latency through parallelism

**Anti-Patterns**:
- **Don't** use for dependent tasks (they run in parallel)
- **Don't** throw exceptions without handling (Result.Failure)
- **Don't** use for CPU-bound work (virtual threads are for I/O)

---

## Error Handling

### 14. Result - Railway-Oriented Error Handling

**Purpose**: Railway-oriented programming with explicit error handling - Java 26 equivalent of Erlang's `{ok, Value}` | `{error, Reason}`.

**Key Characteristics**:
- Sealed interface with exhaustive pattern matching
- Railway operations: map, flatMap, recover
- Side-effect operations: peek
- Eliminator: fold

**API Reference**:

```java
// Factory methods
static <S, F> Result<S, F> ok(S value)
static <S, F> Result<S, F> err(F error)
static <S, F> Result<S, F> success(S value)
static <S, F> Result<S, F> failure(F error)
static <S, F> Result<S, F> of(Supplier<S> supplier)

// Query
boolean isSuccess()
boolean isError()
boolean isFailure()

// Railway operations
<T> Result<T, F> map(Function<? super S, ? extends T> mapper)
<T> Result<T, F> flatMap(Function<? super S, ? extends Result<T, F>> mapper)
S orElse(S defaultValue)
S orElseThrow()
<T> T fold(
    Function<? super S, ? extends T> onSuccess,
    Function<? super F, ? extends T> onError
)

// Side effects
Result<S, F> peek(Consumer<? super S> action)

// Recovery
Result<S, F> recover(Function<? super F, ? extends Result<S, F>> handler)
```

**Real-World Examples**:

**Example 1: Railway Chain**

```java
Result<Order, OrderError> result = Result.of(() -> validate(request))
    .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
    .flatMap(order -> Result.of(() -> reserveInventory(order)))
    .peek(order -> auditLog.add(order))
    .recover(error -> {
        metrics.recordFailure(error);
        return Result.failure(error);
    });

switch (result) {
    case Result.Ok(var order) -> shipOrder(order);
    case Result.Err(var error) -> notifyCustomer(error);
}
```

**Example 2: Error Recovery**

```java
var result = Result.of(() -> fetchData())
    .recover(error -> {
        if (error instanceof NetworkException) {
            // Retry with cache
            return Result.ok(loadFromCache());
        }
        return Result.failure(error);
    });
```

**Example 3: Validation Pipeline**

```java
var validated = Result.of(() -> new User(request.name, request.email))
    .flatMap(user -> Result.of(() -> validateName(user.name())))
    .flatMap(user -> Result.of(() -> validateEmail(user.email())))
    .flatMap(user -> Result.of(() -> validateAge(user.age())));

switch (validated) {
    case Result.Ok(var user) -> createUser(user);
    case Result.Err(var errors) -> showErrors(errors);
}
```

**Example 4: Fold Eliminator**

```java
var result = fetchUserData(userId);

String message = result.fold(
    user -> "User found: " + user.name(),
    error -> "Error: " + error.getMessage()
);

System.out.println(message);
```

**Performance Characteristics**:
- **Overhead**: ~20ns per operation (sealed type dispatch)
- **Memory**: Negligible (record wrapper)

**When to Use**:
- Explicit error handling
- Railway-oriented programming
- Chaining fallible operations
- Type-safe error propagation

**Anti-Patterns**:
- **Don't** use for control flow (use exceptions for truly exceptional cases)
- **Don't** ignore errors (always handle Result.Err)
- **Don't** mix with exceptions (choose one paradigm)

---

### 15. ExitSignal - Crash Reason Carrier

**Purpose**: OTP's exit signal delivered as mailbox message when process traps exits.

**Key Characteristics**:
- Record type for pattern matching
- Carries crash reason (Throwable)
- Null reason = normal exit
- Used with `Proc.trapExits(true)`

**API Reference**:

```java
public record ExitSignal(Throwable reason)
```

**Real-World Examples**:

**Example 1: Exit Trapping**

```java
// Message type includes ExitSignal
sealed interface Msg permits Msg.Work, ExitSignal {}

var proc = Proc.spawn(initialState, handler);
proc.trapExits(true);  // Enable exit trapping

// Handler
BiFunction<State, Msg, State> handler = (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> {
        if (reason == null) {
            System.out.println("Linked process stopped normally");
        } else {
            System.err.println("Linked process crashed: " + reason);
            // Handle crash
        }
        yield state;
    }
    case Msg.Work(var data) ->
        doWork(state, data);
};
```

**Example 2: Supervisor Notification**

```java
var supervisor = Proc.spawn(supState, supervisorHandler);
supervisor.trapExits(true);

var worker = ProcLink.spawnLink(servisor, workerState, workerHandler);

// When worker crashes, supervisor receives ExitSignal
// instead of being interrupted
```

**Example 3: Link Monitoring**

```java
var parent = Proc.spawn(parentState, parentHandler);
parent.trapExits(true);

var child = ProcLink.spawnLink(parent, childState, childHandler);

// Parent can detect and handle child crashes without dying
```

**Performance Characteristics**:
- **Delivery**: ~100ns (mailbox enqueue)
- **Pattern matching**: ~20ns (record destructuring)

**When to Use**:
- Need to detect linked process crashes
- Want selective crash handling
- Building supervisors
- Implementing custom restart logic

**Anti-Patterns**:
- **Don't** forget to enable exit trapping (no effect)
- **Don't** block on ExitSignal handling (mailbox backup)
- **Don't** use for monitoring (use ProcMonitor instead)

---

## Performance Summary

| Primitive | Memory Overhead | Latency | Throughput | Use Case |
|-----------|----------------|---------|------------|----------|
| Proc | ~1KB | 50-150ns/msg | Millions msg/sec | Concurrent state |
| ProcRef | ~32 bytes | ~5ns (volatile read) | N/A | Stable reference |
| ProcLink | ~64 bytes | ~1µs (crash) | N/A | Crash propagation |
| ProcMonitor | ~48 bytes | ~1µs (crash) | N/A | Process monitoring |
| ProcRegistry | ~100 bytes/entry | O(1) lookup | N/A | Service discovery |
| ProcTimer | ~200 bytes/timer | ~1µs scheduling | N/A | Timeouts |
| ProcSys | Negligible | O(1) stats | N/A | Introspection |
| ProcLib | ~100 bytes | ~50µs startup | N/A | Init handshake |
| Supervisor | ~500 bytes/child | ~75ms restart | N/A | Fault tolerance |
| StateMachine | ~1KB | ~200ns/event | Millions events/sec | Complex workflows |
| CrashRecovery | ~1KB/attempt | Full retry | N/A | Resilient execution |
| EventManager | ~500 bytes | ~500ns/handler | Millions events/sec | Event routing |
| Parallel | ~1KB/task | Virtual thread | Parallel tasks | Fan-out/fan-in |
| Result | ~32 bytes | ~20ns/op | N/A | Error handling |
| ExitSignal | ~32 bytes | ~100ns delivery | N/A | Crash signals |

---

## Common Patterns

### Pattern 1: Supervision Tree

```
ApplicationSupervisor (ONE_FOR_ONE)
├── DatabaseSupervisor (ONE_FOR_ALL)
│   ├── ConnectionPool (SIMPLE_ONE_FOR_ONE)
│   └── QueryCache (PERMANENT)
├── MessageRouter (PERMANENT)
└── EventLogger (PERMANENT)
```

### Pattern 2: Let It Crash

```java
// Don't handle errors locally
var proc = Proc.spawn(state, (s, m) -> {
    try {
        return handle(s, m);
    } catch (Exception e) {
        // Don't catch - let supervisor restart
        throw e;
    }
});

// Instead, let supervisor handle restarts
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);
supervisor.supervise("worker", state, handler);
```

### Pattern 3: Registry Lookup

```java
// Register service
var service = Proc.spawn(state, handler);
ProcRegistry.register("my-service", service);

// Anywhere in codebase:
ProcRegistry.whereis("my-service").ifPresent(svc ->
    svc.tell(new Request())
);
```

### Pattern 4: Request-Reply with Timeout

```java
var proc = ProcRegistry.whereis("service").orElseThrow();

var future = proc.ask(new Request())
    .orTimeout(5, TimeUnit.SECONDS);

try {
    var response = future.get();
    handleResponse(response);
} catch (TimeoutException e) {
    handleError("Request timed out");
}
```

---

## Anti-Patterns to Avoid

### Anti-Pattern 1: Shared Mutable State

```java
// BAD: Shared state between processes
var sharedList = new ArrayList<>();

var proc1 = Proc.spawn(state, (s, m) -> {
    sharedList.add(m);  // NOT thread-safe!
    return s;
});

// GOOD: Each process has its own state
var proc1 = Proc.spawn(new ArrayList<>(), (list, m) -> {
    var newList = new ArrayList<>(list);
    newList.add(m);
    return newList;
});
```

### Anti-Pattern 2: Blocking in Handlers

```java
// BAD: Blocking I/O in handler
var proc = Proc.spawn(state, (s, m) -> {
    var result = httpClient.get(url);  // Blocks!
    return s;
});

// GOOD: Offload blocking work to separate process
var worker = Proc.spawn(workerState, workerHandler);
proc.tell(new DelegateWork(url, worker));
```

### Anti-Pattern 3: Forgetting Exit Trapping

```java
// BAD: Link without trapping exits
ProcLink.link(parent, child);
// If child crashes, parent dies too

// GOOD: Trap exits if you want to handle crashes
parent.trapExits(true);
ProcLink.link(parent, child);
// Parent receives ExitSignal instead of crashing
```

### Anti-Pattern 4: Raw Proc References from Supervisors

```java
// BAD: Holding raw Proc from supervisor
var rawProc = supervisor.supervise("worker", state, handler);
// rawProc becomes stale after restart

// GOOD: Use ProcRef
var procRef = supervisor.supervise("worker", state, handler);
// procRef transparently redirects to restarted process
```

---

## Migration Guide: Erlang/OTP → JOTP

| Erlang/OTP | JOTP |
|------------|------|
| `spawn/3` | `Proc.spawn()` |
| `Pid ! Msg` | `proc.tell()` |
| `gen_server:call/2` | `proc.ask()` |
| `link/1` | `ProcLink.link()` |
| `spawn_link/3` | `ProcLink.spawnLink()` |
| `monitor/2` | `ProcMonitor.monitor()` |
| `register/2` | `ProcRegistry.register()` |
| `whereis/1` | `ProcRegistry.whereis()` |
| `timer:send_after/3` | `ProcTimer.sendAfter()` |
| `supervisor:start_link/3` | `Supervisor.create()` |
| `gen_statem` | `StateMachine` |
| `gen_event` | `EventManager` |
| `{ok, Val}` | `Result.ok(value)` |
| `{error, Reason}` | `Result.err(reason)` |
| `proc_lib:start_link/3` | `ProcLib.startLink()` |
| `sys:get_state/1` | `ProcSys.getState()` |
| `trap_exit` | `proc.trapExits(true)` |

---

## Conclusion

JOTP provides complete feature parity with Erlang/OTP's 15 core primitives, adapted for Java 26 with virtual threads, sealed types, and pattern matching. By mastering these primitives and their patterns, you can build fault-tolerant, scalable concurrent systems that embody Joe Armstrong's "let it crash" philosophy on the JVM.

**Key Takeaways**:
1. **Proc** is fundamental - all other primitives build on it
2. **Supervisor** enables fault tolerance through automatic restart
3. **ProcRef** provides stable references across restarts
4. **Result** makes error handling explicit and composable
5. **Virtual threads** enable millions of lightweight processes

**Next Steps**:
- Read `/docs/primitives/COMPARISON-ERLANG.md` for detailed Erlang comparison
- Study `/docs/primitives/PATTERNS.md` for common patterns
- Review `/docs/primitives/ANTI-PATTERNS.md` to avoid mistakes
- Check `/docs/primitives/PERFORMANCE.md` for performance insights
