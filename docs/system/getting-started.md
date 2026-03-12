# Getting Started with JOTP

This guide walks you from zero to a running concurrent application. After completing it, you will understand JOTP's core model: processes, message passing, supervision, and the "let it crash" philosophy.

For the hands-on tutorials with more detail, see [tutorials/](../tutorials/).

---

## Sequential Programming: Your First Process

JOTP's fundamental unit is `Proc<S,M>` — a lightweight virtual-thread process that:

- Maintains **isolated state** of type `S`
- Receives **typed messages** of type `M`
- Executes a pure **state handler**: `state → message → new_state`
- Costs ~1 KB of memory (vs. ~1 MB for a platform thread)

Here is the canonical first program — a counter:

```java
import io.github.seanchatmangpt.jotp.*;

public class HelloJOTP {
    // Sealed message hierarchy — the Java equivalent of Erlang atoms
    sealed interface CounterMsg permits Inc, Get {}
    record Inc() implements CounterMsg {}
    record Get(ProcRef<Integer, ?> replyTo) implements CounterMsg {}

    public static void main(String[] args) throws Exception {
        // Spawn a process: state=Integer, messages=CounterMsg
        var counter = Proc.start(
            state -> msg -> switch (msg) {
                case Inc()      -> state + 1;
                case Get(var r) -> { r.send(state); yield state; }
            },
            0  // initial state
        );

        // Fire-and-forget messages
        counter.send(new Inc());
        counter.send(new Inc());
        counter.send(new Inc());

        // Synchronous request-reply
        int value = counter.ask(replyTo -> new Get(replyTo),
            java.time.Duration.ofSeconds(1));

        System.out.println("Counter: " + value);  // Counter: 3
    }
}
```

This is JOTP's equivalent of spawning a `gen_server` in Erlang:

```erlang
% Erlang equivalent
{ok, Pid} = gen_server:start_link(counter_module, 0, []).
gen_server:cast(Pid, increment).
Count = gen_server:call(Pid, get).
```

---

## Processes and Message Passing

Processes communicate exclusively through **messages** — there is no shared memory. This is the single most important design principle in JOTP (inherited from OTP).

### Sending Messages (Async)

```java
proc.send(new MyMessage("data"));  // Non-blocking, always returns immediately
```

### Request-Reply (Sync)

```java
var result = proc.ask(
    replyTo -> new Request<>(replyTo),  // Build request with reply address
    Duration.ofSeconds(5)               // Timeout
);
```

### Broadcasting to Many Processes

```java
var workers = List.of(worker1, worker2, worker3);
workers.forEach(w -> w.send(new JobMsg("task")));
```

### Typed Messages with Sealed Interfaces

Java 26 sealed types enforce exhaustive handling — the compiler ensures every message type is handled:

```java
sealed interface OrderMsg permits PlaceOrder, CancelOrder, QueryStatus {}
record PlaceOrder(String item, int qty) implements OrderMsg {}
record CancelOrder(String orderId) implements OrderMsg {}
record QueryStatus(String orderId, ProcRef<String, ?> replyTo) implements OrderMsg {}

var handler = Proc.start(
    (Map<String, String> orders) -> msg -> switch (msg) {
        case PlaceOrder(var item, var qty) -> {
            orders.put(UUID.randomUUID().toString(), item + "x" + qty);
            yield orders;
        }
        case CancelOrder(var id)           -> { orders.remove(id); yield orders; }
        case QueryStatus(var id, var r)    -> { r.send(orders.getOrDefault(id, "not found")); yield orders; }
    },
    new HashMap<>()
);
```

---

## Process Links and Supervision

### The "Let It Crash" Philosophy

In JOTP (as in OTP), you do **not** write defensive code everywhere. Instead:

1. Let processes crash when they encounter unexpected conditions
2. A `Supervisor` automatically restarts them with fresh state
3. System remains available through individual failures

This is fundamentally different from try-catch-everywhere Java.

### Supervisors

A `Supervisor` monitors child processes and restarts them according to a strategy:

```java
var supervisor = Supervisor.oneForOne()
    .restartWindow(Duration.ofMinutes(1), 10)  // max 10 restarts per minute
    .add("database",   () -> DatabaseProc.start())
    .add("cache",      () -> CacheProc.start())
    .add("requestHandler", () -> RequestHandlerProc.start())
    .build();
```

**Restart strategies:**

| Strategy | Erlang | Behavior |
|----------|--------|----------|
| `ONE_FOR_ONE` | `one_for_one` | Restart only the crashed child |
| `ONE_FOR_ALL` | `one_for_all` | Restart all children if any crashes |
| `REST_FOR_ONE` | `rest_for_one` | Restart the crashed child and those started after it |

### Process Links

`ProcessLink` creates a bilateral death pact between two processes — if either crashes, the other receives an `ExitSignal`:

```java
ProcessLink.link(producer, consumer);
// If producer crashes, consumer receives ExitSignal and also terminates
// (unless consumer calls Proc.trapExits(true))
```

### Process Monitors

`ProcessMonitor` provides unilateral observation — the watcher is notified of a crash but does **not** crash itself:

```java
var monitor = ProcessMonitor.monitor(targetProc);
// If targetProc crashes, monitor receives DownNotification
// The monitoring process continues running
```

---

## Concurrent Programming Patterns

### Parallel Fan-Out

Execute work across many processes and collect results with `Parallel`:

```java
var results = Parallel.map(
    List.of("task1", "task2", "task3"),
    task -> processTask(task)  // Runs in parallel virtual threads
);
```

`Parallel` uses `StructuredTaskScope` — if any task fails, all others are cancelled automatically.

### State Machines

Use `StateMachine<S,E,D>` for workflows with explicit states:

```java
enum TrafficLight { RED, YELLOW, GREEN }
enum Signal { TIMER, EMERGENCY }

var light = StateMachine.create(
    (TrafficLight state, Signal event, Void data) ->
        switch (state) {
            case RED    -> new Transition<>(GREEN,  data);
            case GREEN  -> new Transition<>(YELLOW, data);
            case YELLOW -> new Transition<>(RED,    data);
        }
    ,
    TrafficLight.RED,
    null
);

light.handle(Signal.TIMER);  // RED → GREEN
light.handle(Signal.TIMER);  // GREEN → YELLOW
```

### Event Broadcasting

`EventManager<E>` decouples producers from consumers — fire events without knowing who handles them:

```java
var events = new EventManager<OrderEvent>();
events.addHandler(new AuditLogHandler());
events.addHandler(new EmailNotificationHandler());
events.addHandler(new InventoryUpdateHandler());

// All handlers receive this
events.notify(new OrderPlaced("ORD-123", "Widget", 5));
```

---

## Where to Go Next

| If you want to... | Go to... |
|-------------------|----------|
| Deep-dive on `Proc<S,M>` | [Tutorial 02: Your First Process](../tutorials/02-first-process.md) |
| Understand virtual threads | [Tutorial 03: Virtual Threads](../tutorials/03-virtual-threads.md) |
| Build supervision trees | [Tutorial 04: Supervision Basics](../tutorials/04-supervision-basics.md) |
| Understand the system architecture | [System Principles](system-principles.md) |
| Learn all 15 primitives | [OTP Design Principles](otp-design-principles.md) |
| See full API signatures | [Reference Manual](reference-manual.md) |
| Optimize for production | [Efficiency Guide](efficiency-guide.md) |
| Integrate with Spring Boot | [Interoperability Guide](interoperability.md) |
