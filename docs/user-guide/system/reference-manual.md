# Reference Manual

This is the technical reference for the `io.github.seanchatmangpt.jotp` module — language features, module system, and a complete API quick reference for all 15 primitives.

For detailed API docs with full method signatures, see [docs/reference/](../reference/).

---

## The JOTP Module System

JOTP is a **JPMS module** (Java Platform Module System). To use it, declare a dependency in your `module-info.java`:

```java
module com.example.myapp {
    requires io.github.seanchatmangpt.jotp;
    // Optional: expose your own packages to JOTP (rarely needed)
    // opens com.example.myapp.processes to io.github.seanchatmangpt.jotp;
}
```

All JOTP public types are in the `io.github.seanchatmangpt.jotp` package:

```java
import io.github.seanchatmangpt.jotp.*;
```

No sub-packages need to be imported separately.

---

## Java 26 Language Features Used by JOTP

Understanding these features is essential for reading and writing JOTP code.

### Sealed Interfaces and Records

Sealed interfaces restrict which classes can implement them, enabling **exhaustive pattern matching**:

```java
// Sealed hierarchy — compiler knows all cases
sealed interface Msg permits Ping, Pong, Stop {}
record Ping(ProcRef<?, ?> replyTo) implements Msg {}
record Pong()                       implements Msg {}
record Stop()                       implements Msg {}

// Compiler verifies all cases are handled
String handle(Msg m) {
    return switch (m) {
        case Ping(var r) -> "ping";
        case Pong()      -> "pong";
        case Stop()      -> "stop";
        // No default needed — compiler knows the hierarchy is exhaustive
    };
}
```

### Pattern Matching in Switch Expressions

```java
// Guarded patterns
String classify(Object obj) {
    return switch (obj) {
        case Integer i when i < 0  -> "negative";
        case Integer i             -> "non-negative integer";
        case String s when s.isEmpty() -> "empty string";
        case String s              -> "non-empty string: " + s;
        case null                  -> "null";
        default                    -> "other";
    };
}
```

### Record Patterns (Destructuring)

```java
record Point(int x, int y) {}
record Line(Point from, Point to) {}

// Nested destructuring
if (shape instanceof Line(Point(var x1, var y1), Point(var x2, var y2))) {
    double length = Math.sqrt(Math.pow(x2-x1, 2) + Math.pow(y2-y1, 2));
}
```

### Virtual Threads (JEP 444, final in Java 21+)

```java
// Every Proc uses this internally
Thread vt = Thread.ofVirtual()
    .name("proc-handler")
    .start(() -> runMailboxLoop());
```

### Structured Concurrency (JEP 453)

```java
// Used by Parallel internally
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task1 = scope.fork(() -> computeA());
    var task2 = scope.fork(() -> computeB());
    scope.join().throwIfFailed();
    return List.of(task1.get(), task2.get());
}
```

### `var` (Local Type Inference)

```java
var counter = Proc.start(state -> msg -> state + 1, 0);
// Equivalent to: Proc<Integer, Integer> counter = ...
```

---

## API Quick Reference

### `Proc<S,M>` — Core Process

```java
// Create
Proc<S,M> proc = Proc.start(Function<S, Function<M, S>> handler, S initialState);
Proc<S,M> proc = Proc.spawnLink(Proc<?,?> parent, handler, initialState);

// Send
void proc.send(M message);

// Request-Reply
<R> R proc.ask(Function<ProcRef<R,?>, M> requestBuilder, Duration timeout);

// Lifecycle
void proc.trapExits(boolean trap);
void proc.shutdown();
boolean proc.isAlive();
```

### `ProcRef<S,M>` — Stable Handle

```java
// Obtained from Supervisor
ProcRef<S,M> ref = supervisor.getRef(String name);

// Same API as Proc
void ref.send(M message);
<R> R ref.ask(Function<ProcRef<R,?>, M> requestBuilder, Duration timeout);
```

### `Supervisor` — Supervision Tree

```java
// Builders
Supervisor.Builder Supervisor.oneForOne();
Supervisor.Builder Supervisor.oneForAll();
Supervisor.Builder Supervisor.restForOne();

// Builder methods
Builder .add(String name, Supplier<Proc<?,?>> factory);
Builder .restartWindow(Duration window, int maxRestarts);
Supervisor .build();

// Runtime
<S,M> ProcRef<S,M> supervisor.getRef(String name);
void supervisor.shutdown(Duration gracePeriod);
void supervisor.awaitShutdown();
```

### `StateMachine<S,E,D>` — State Machine

```java
// Create
StateMachine<S,E,D> StateMachine.create(
    TriFunction<S,E,D, Transition<S,D>> handler,
    S initialState,
    D initialData
);

// Runtime
void machine.handle(E event);
S machine.getState();
D machine.getData();
boolean machine.isTerminated();

// Transitions
Transition.keep()               // no change
Transition.next(S state, D data) // transition
Transition.stop()               // terminate
```

### `EventManager<E>` — Event Bus

```java
EventManager<E> bus = new EventManager<>();

void bus.addHandler(EventHandler<E> handler);
void bus.notify(E event);                    // async
void bus.syncNotify(E event);               // sync — waits for all handlers
<R> R bus.call(EventHandler<E> h, Object req, Duration timeout);
void bus.deleteHandler(Class<? extends EventHandler<E>> type);
```

### `ProcessLink` — Bilateral Links

```java
void ProcessLink.link(Proc<?,?> a, Proc<?,?> b);
void ProcessLink.unlink(Proc<?,?> a, Proc<?,?> b);
```

### `ProcessMonitor` — Unilateral Monitoring

```java
ProcessMonitor monitor = ProcessMonitor.monitor(Proc<?,?> target);
void monitor.onDown(Consumer<DownNotification> handler);
void ProcessMonitor.demonitor(ProcessMonitor monitor);
```

### `ProcessRegistry` — Global Name Table

```java
void ProcessRegistry.register(String name, ProcRef<?,?> ref);
Optional<ProcRef<?,?>> ProcessRegistry.whereis(String name);
void ProcessRegistry.unregister(String name);
Set<String> ProcessRegistry.registered();
```

### `ProcTimer` — Timed Messages

```java
TimerRef ProcTimer.sendAfter(Duration delay, Proc<?,?> target, Object msg);
TimerRef ProcTimer.sendInterval(Duration interval, Proc<?,?> target, Object msg);
void ProcTimer.cancel(TimerRef ref);
```

### `ProcSys` — Introspection

```java
ProcStats ProcSys.statistics(Proc<?,?> proc);
// ProcStats: messageCount(), uptime(), stateClass(), queueLength()

void ProcSys.suspend(Proc<?,?> proc);
void ProcSys.resume(Proc<?,?> proc);
Object ProcSys.getState(Proc<?,?> proc);
```

### `ProcLib` — Startup Handshake

```java
StartResult ProcLib.startLink(Proc<?,?> parent, Supplier<Proc<?,?>> init);
void ProcLib.initAck();  // Called inside init to unblock parent
// StartResult: Ok | Err
```

### `CrashRecovery` — Isolated Retry

```java
<T> T CrashRecovery.withRetry(Supplier<T> computation, RetryPolicy policy);
RetryPolicy RetryPolicy.exponentialBackoff(int maxAttempts, Duration initialDelay);
RetryPolicy RetryPolicy.fixedDelay(int maxAttempts, Duration delay);
```

### `Parallel` — Structured Fan-Out

```java
<T,R> List<R> Parallel.map(List<T> items, Function<T,R> fn);
<T,R> List<R> Parallel.map(List<T> items, Function<T,R> fn, Duration timeout);
```

### `Result<T,E>` — Railway Error Handling

```java
sealed interface Result<T,E> permits Result.Success, Result.Failure {}
record Success<T,E>(T value) implements Result<T,E> {}
record Failure<T,E>(E error) implements Result<T,E> {}

Result<T,E> Result.of(Supplier<T> supplier);   // wraps throwing operations
Result<T,E> Result.success(T value);
Result<T,E> Result.failure(E error);

<U> Result<U,E> result.map(Function<T,U> fn);
<U> Result<U,E> result.flatMap(Function<T, Result<U,E>> fn);
Result<T,E>     result.recover(Function<E,T> fn);
T               result.orElseThrow();
<R> R           result.fold(Function<T,R> onSuccess, Function<E,R> onFailure);
```

---

## Configuration Reference

JOTP reads configuration from system properties at startup:

| Property | Default | Description |
|----------|---------|-------------|
| `jotp.mailbox.capacity` | `1024` | Default mailbox capacity per process |
| `jotp.supervisor.defaultWindow` | `60s` | Default restart window |
| `jotp.supervisor.defaultMaxRestarts` | `10` | Default max restarts per window |
| `jotp.parallel.defaultTimeout` | `30s` | Default timeout for `Parallel.map` |
| `jotp.registry.maxNames` | `10000` | Max entries in `ProcessRegistry` |

Set via JVM flag: `-Djotp.mailbox.capacity=2048`

---

*Next: [Efficiency Guide](efficiency-guide.md)*
