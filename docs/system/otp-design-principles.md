# OTP Design Principles

This guide describes the 15 JOTP primitives and how they map to Erlang/OTP design patterns. These primitives are the building blocks of every JOTP application.

For formal equivalence proofs, see [OTP Equivalence](../explanations/otp-equivalence.md) and the [PhD Thesis](../phd-thesis/otp-28-java26.md).

---

## Overview: The 15 JOTP Primitives

| Erlang/OTP | JOTP Primitive | Pattern |
|------------|----------------|---------|
| `spawn/3` | `Proc<S,M>` | Lightweight process |
| `gen_server` | `Proc<S,M>` | Generic server (same primitive) |
| `Pid` | `ProcRef<S,M>` | Stable process handle |
| `supervisor` | `Supervisor` | Supervision tree |
| `gen_statem` | `StateMachine<S,E,D>` | Generic state machine |
| `gen_event` | `EventManager<E>` | Event broadcasting |
| `link/2` + `spawn_link` | `ProcessLink` | Bilateral crash propagation |
| `monitor/2` | `ProcessMonitor` | Unilateral DOWN notifications |
| `global:register_name` | `ProcessRegistry` | Global name table |
| `timer:send_after` | `ProcTimer` | Timed message delivery |
| `process_flag(trap_exit)` | `Proc.trapExits(true)` | Catch exit signals |
| `sys` module | `ProcSys` | Process introspection |
| `proc_lib` | `ProcLib` | Startup handshake |
| `supervisor_bridge` | `CrashRecovery` | Isolated retry |
| `pmap` / `async` | `Parallel` | Structured fan-out |
| *(railway)* | `Result<T,E>` | Typed error handling |

---

## `Proc<S,M>` — The Generic Server

`Proc<S,M>` is JOTP's core primitive. It is simultaneously the equivalent of Erlang's `spawn/3`, `gen_server`, and `loop/2` idiom.

Every `Proc`:
- Runs in a **virtual thread** (lightweight, ~1 KB memory)
- Has a **mailbox** (bounded blocking queue)
- Executes a **pure handler**: `S → M → S`
- Is crash-isolated — it cannot corrupt other processes' state

### Creating a Process

```java
// Full form
Proc<Integer, CounterMsg> counter = Proc.start(
    (Integer state) -> (CounterMsg msg) -> switch (msg) {
        case Inc()           -> state + 1;
        case Reset()         -> 0;
        case Get(var replyTo) -> { replyTo.send(state); yield state; }
    },
    0  // initial state
);

// Lambda form (state inference)
var counter = Proc.start(
    state -> msg -> switch (msg) {
        case Inc()            -> state + 1;
        case Reset()          -> 0;
        case Get(var replyTo) -> { replyTo.send(state); yield state; }
    },
    0
);
```

### Handler Semantics

The handler is a **pure function** — it must not have side effects that depend on ordering:

```java
// Good: pure state transition
state -> msg -> state + 1

// Good: reply + return unchanged state
state -> msg -> { msg.replyTo().send(state); yield state; }

// Avoid: shared mutable state accessed without synchronization
state -> msg -> {
    sharedMap.put("key", state);  // race condition with other processes!
    yield state;
}
```

### Synchronous Call (`ask`)

```java
// Blocks until reply or timeout
int value = proc.ask(
    replyTo -> new GetMsg(replyTo),
    Duration.ofSeconds(5)
);
```

### Trapping Exit Signals

```java
proc.trapExits(true);
// ExitSignal messages appear in the mailbox when linked processes crash
```

---

## `ProcRef<S,M>` — Stable Process Handle

`ProcRef<S,M>` is an opaque handle to a process that survives supervisor restarts. When a supervisor restarts a process, the `ProcRef` automatically points to the new process instance.

```java
ProcRef<Integer, CounterMsg> ref = supervisor.getRef("counter");

// Still works after the process has been restarted
ref.send(new Inc());
int value = ref.ask(replyTo -> new Get(replyTo), Duration.ofSeconds(1));
```

Use `ProcRef` for long-lived references. Use `Proc` directly for short-lived, ad-hoc processes.

---

## `Supervisor` — Supervision Trees

`Supervisor` monitors child processes and restarts them when they crash. It is JOTP's equivalent of Erlang's `supervisor` behaviour.

### Three Restart Strategies

**ONE_FOR_ONE** — restart only the failed child:
```java
var sup = Supervisor.oneForOne()
    .add("a", ProcA::start)
    .add("b", ProcB::start)
    .add("c", ProcC::start)
    .build();
// If "b" crashes → only "b" restarts
```

**ONE_FOR_ALL** — restart all children if any fails:
```java
var sup = Supervisor.oneForAll()
    .add("config",   ConfigProc::start)
    .add("database", DatabaseProc::start)
    .build();
// If "config" crashes → both "config" and "database" restart together
```

**REST_FOR_ONE** — restart the failed child and those started after it:
```java
var sup = Supervisor.restForOne()
    .add("listener", ListenerProc::start)
    .add("router",   RouterProc::start)   // depends on listener
    .add("handler",  HandlerProc::start)  // depends on router
    .build();
// If "router" crashes → "router" and "handler" restart; "listener" continues
```

### Restart Intensity

```java
Supervisor.oneForOne()
    .restartWindow(Duration.ofMinutes(1), 10)
    // Max 10 restarts within 1 minute before supervisor gives up
```

### Hierarchical Trees

Supervisors can supervise other supervisors:

```java
var root = Supervisor.oneForOne()
    .add("infra",    () -> infraSupervisor)
    .add("services", () -> serviceSupervisor)
    .add("api",      () -> apiSupervisor)
    .build();
```

---

## `StateMachine<S,E,D>` — Generic State Machines

`StateMachine<S,E,D>` implements the `gen_statem` pattern: explicit states (`S`), events (`E`), and data (`D`) with sealed `Transition` return types.

```java
sealed interface OrderState permits Pending, Processing, Shipped, Delivered {}
record Pending()    implements OrderState {}
record Processing() implements OrderState {}
record Shipped()    implements OrderState {}
record Delivered()  implements OrderState {}

sealed interface OrderEvent permits Confirm, Ship, Deliver {}
record Confirm() implements OrderEvent {}
record Ship()    implements OrderEvent {}
record Deliver() implements OrderEvent {}

var machine = StateMachine.create(
    (OrderState state, OrderEvent event, OrderData data) ->
        switch (state) {
            case Pending()    -> switch (event) {
                case Confirm() -> Transition.next(new Processing(), data);
                default        -> Transition.keep();
            };
            case Processing() -> switch (event) {
                case Ship()    -> Transition.next(new Shipped(), data.withTracking("TRK-123"));
                default        -> Transition.keep();
            };
            case Shipped()    -> switch (event) {
                case Deliver() -> Transition.next(new Delivered(), data);
                default        -> Transition.keep();
            };
            case Delivered()  -> Transition.stop();
        }
    ,
    new Pending(),
    initialData
);

machine.handle(new Confirm());  // → Processing
machine.handle(new Ship());     // → Shipped
machine.handle(new Deliver());  // → Delivered
```

### Sealed `Transition` Hierarchy

```java
sealed interface Transition<S, D> permits Transition.Keep, Transition.Next, Transition.Stop {}

// Keep current state and data
Transition.keep()

// Move to new state with updated data
Transition.next(newState, newData)

// Terminate the state machine
Transition.stop()
```

---

## `EventManager<E>` — Event Handling

`EventManager<E>` implements the `gen_event` pattern: a typed event bus where handlers can be added and removed at runtime, and a crashing handler does not kill the manager.

```java
var bus = new EventManager<SystemEvent>();

// Add handlers — each runs in isolation
bus.addHandler(new AuditLogHandler());
bus.addHandler(new AlertingHandler());
bus.addHandler(new MetricsHandler());

// Synchronous notification — waits for all handlers
bus.syncNotify(new OrderPlaced("ORD-001", BigDecimal.valueOf(99.99)));

// Async notification — fire and forget
bus.notify(new UserLoggedIn("user-42"));

// Remove a handler
bus.deleteHandler(AuditLogHandler.class);
```

If `AlertingHandler` throws an exception, only that handler is removed — the bus continues delivering events to `AuditLogHandler` and `MetricsHandler`.

---

## Process Links and Monitors

### `ProcessLink` — Bilateral Crash Propagation

Links create a death-pact: if either process crashes, the other receives an `ExitSignal` and also terminates (unless it traps exits).

```java
// After linking: if A crashes, B terminates (and vice versa)
ProcessLink.link(procA, procB);
```

**Use case:** Tightly coupled producer-consumer pairs where one without the other is meaningless.

```java
// Spawn-link pattern (link at creation)
var worker = Proc.spawnLink(
    parentProc,
    state -> msg -> doWork(msg),
    initialState
);
```

### `ProcessMonitor` — Unilateral DOWN Notifications

Monitors observe without coupling. The watcher receives a `DownNotification` but does **not** crash.

```java
var monitor = ProcessMonitor.monitor(targetProc);
// When targetProc terminates (normally or abnormally):
// → monitor receives DownNotification{pid, reason}

// De-monitor (stop watching)
ProcessMonitor.demonitor(monitor);
```

**Use case:** Health checkers, circuit breakers, dependency tracking.

---

## Supporting Primitives

### `ProcessRegistry` — Global Name Table

```java
// Register
ProcessRegistry.register("payment-service", paymentProc);

// Look up
Optional<ProcRef<?, ?>> found = ProcessRegistry.whereis("payment-service");

// All registered names
Set<String> names = ProcessRegistry.registered();

// Auto-deregisters when the process terminates
```

### `ProcTimer` — Timed Message Delivery

```java
// Send once after delay
var ref = ProcTimer.sendAfter(Duration.ofSeconds(30), targetProc, new TimeoutMsg());

// Send repeatedly
var intervalRef = ProcTimer.sendInterval(Duration.ofSeconds(5), targetProc, new HeartbeatMsg());

// Cancel
ProcTimer.cancel(ref);
```

### `ProcLib` — Startup Handshake

`ProcLib` implements `proc_lib`'s `start_link` pattern: the parent blocks until the child signals it is ready.

```java
var startResult = ProcLib.startLink(parentProc, () -> {
    // Initialize resources
    var connection = Database.connect();

    ProcLib.initAck();  // Signal: ready to receive messages

    return Proc.start(state -> msg -> handleMsg(state, msg, connection), initialState);
});

// startResult is Ok | Err — parent only continues when child is initialized
```

### `CrashRecovery` — Isolated Retry

`CrashRecovery` wraps a computation in an isolated virtual thread. If it throws, it retries according to the configured strategy.

```java
var result = CrashRecovery.withRetry(
    () -> externalService.call(),
    RetryPolicy.exponentialBackoff(3, Duration.ofMillis(100))
);
```

### `Parallel` — Structured Fan-Out

```java
// Parallel map — fail-fast if any subtask throws
var results = Parallel.map(
    List.of(item1, item2, item3),
    item -> processItem(item)
);

// Parallel with timeout
var results = Parallel.map(
    items,
    item -> processItem(item),
    Duration.ofSeconds(10)
);
```

Uses `StructuredTaskScope.ShutdownOnFailure` — any failure cancels all peers.

---

## `Result<T,E>` — Railway Error Handling

`Result<T,E>` is a sealed type for representing success or failure without exceptions. Use it at system boundaries (parsing, I/O, validation).

```java
sealed interface Result<T, E> permits Result.Success, Result.Failure {}
record Success<T, E>(T value) implements Result<T, E> {}
record Failure<T, E>(E error) implements Result<T, E> {}

// Wrapping a throwing operation
Result<User, String> result = Result.of(() -> userRepository.findById(id));

// Pattern-matched handling
var response = switch (result) {
    case Success(var user)  -> renderUser(user);
    case Failure(var error) -> renderError(error);
};

// Railway chaining
Result<OrderConfirmation, String> confirmation = result
    .map(user -> user.getCart())
    .flatMap(cart -> placeOrder(cart))
    .map(order -> confirm(order));
```

---

## The Design Philosophy: Why These 15?

Joe Armstrong's insight was that 80% of production reliability comes from 20% of OTP — the primitives above. The rest (distributed Erlang, DETS, Mnesia, hot code reloading) can be deferred or replaced with Java ecosystem tools.

**What JOTP deliberately excludes (and why):**

| OTP Feature | Java Alternative |
|-------------|-----------------|
| Distributed Erlang (net_kernel) | gRPC, Hazelcast, Kafka |
| Mnesia | PostgreSQL, Redis |
| Hot code reloading | Blue-green deployment |
| Ports / NIFs | JNI, Panama (JEP 454) |
| OTP releases | Docker images, fat JARs |

This means JOTP applications are **simpler** — one language, one ecosystem, one deployment model — while capturing the fault-tolerance patterns that matter most in production.

---

*Next: [Programming Examples](programming-examples.md)*
