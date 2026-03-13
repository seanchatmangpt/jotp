# JOTP Sequence Diagrams

PlantUML sequence diagrams documenting real-time interaction patterns in JOTP (Java OTP: Erlang/OTP fault-tolerance primitives in pure Java 26).

## Quick Reference

| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **01-process-spawning.puml** | Process Creation | Virtual threads, message mailbox, state handler |
| **02-message-passing.puml** | Messaging | tell() (fire-and-forget), ask() (request-reply) |
| **03-process-linking.puml** | Process Links | Bilateral crash propagation, exit trapping |
| **04-supervised-lifecycle.puml** | Supervision | Child spawning, crash detection, atomic ProcRef.swap() |
| **05-restart-strategies.puml** | Restart Modes | ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE |
| **06-process-monitoring.puml** | Monitoring | Unilateral DOWN signals, health checks |
| **07-state-machine-events.puml** | State Machines | Event queues, transitions, timeout actions |
| **08-event-manager.puml** | Event Broadcasting | Handler decoupling, crash isolation |
| **09-process-registry.puml** | Name Registry | Registration, lookup, auto-deregistration |

---

## Foundation Patterns (Start Here)

### 1. Process Spawning (`01-process-spawning.puml`)

**Scenario:** Create a new `Proc<Integer, String>` with initial state and message handler.

**Key Actors:**
- **Main**: Application thread spawning the process
- **Proc.spawn()**: Factory method returning opaque `Proc<S,M>` handle
- **Virtual Thread**: Lightweight process (1 KB heap, millions per JVM)
- **LinkedTransferQueue<M>**: Lock-free MPMC mailbox
- **Message Loop**: Infinite loop receiving messages and calling handler
- **Handler<S,M>**: Pure state transition function

**Flow:**
1. `Main` calls `Proc.spawn(0, handler)` with initial state (0) and handler
2. `Proc.spawn()` creates a `VirtualThread` and `LinkedTransferQueue<M>` mailbox
3. Message loop starts immediately (non-blocking spawn)
4. Returns to `Main` with `Proc<Integer, String>` reference
5. `Main` sends message via `tell("hello")`
6. Handler processes state: `apply(0, "hello")` → 5
7. Loop continues waiting for next message

**Java 26 Features:**
- **Virtual Threads** (JEP 425): `Thread.ofVirtual().start()` – millions per JVM, 1-2 KB stack
- **Records** (JEP 395): Message types as sealed records
- **Pattern Matching** (JEP 427+): Handler switch expressions

---

### 2. Message Passing (`02-message-passing.puml`)

**Scenario:** Contrast two messaging modes: fire-and-forget vs request-reply.

**tell(msg): Fire-and-Forget**
- Non-blocking, returns `void` immediately
- Sender continues; handler processes asynchronously
- Use case: Events, notifications, one-way commands

**ask(msg, timeout): Request-Reply**
- Returns `CompletableFuture<S>`, blocks on `get()`
- Handler completes future with new state
- Throws `TimeoutException` if takes too long
- Use case: Queries, RPC-style calls, synchronized state changes

**Java 26 Features:**
- **CompletableFuture** (Java 8+): Async result container with timeout
- **Virtual Thread blocking**: Blocking on future doesn't block OS threads, just pauses virtual thread

---

## Process Relationships

### 3. Process Linking (`03-process-linking.puml`)

**Scenario:** Two processes linked; one crashes and propagates failure to the other.

**Key Points:**
- **Bilateral**: Both processes have crash callbacks for each other
- **"Let It Crash"**: Failures propagate unless `trapExits(true)`
- **Exit Signal**: Delivered as message to inbox if trapping enabled
- **No Recovery**: Links just propagate; supervising them requires `Supervisor`

**When Process A Crashes:**
1. Exception in handler → `lastError = exception`
2. Exit message loop
3. Fire crash callback on Process B
4. Process B receives `interrupt()` on virtual thread
5. If `trapExits=true`: deliver `ExitSignal` message to mailbox
6. If `trapExits=false`: crashes immediately (propagates up)

**Use Case:** Bilateral fault propagation (e.g., two redundant services that must stay synchronized).

---

### 4. Supervised Lifecycle (`04-supervised-lifecycle.puml`)

**Scenario:** `Supervisor` starts a child process; child crashes; supervisor restarts it.

**Key Concepts:**
- **ProcRef<S,M>**: Opaque stable handle surviving restarts (location transparency)
- **ProcRef.swap()**: Atomically updates internal delegate to new child Proc
- **ChildSpec<S,M>**: Declares `RestartType` (PERMANENT/TRANSIENT/TEMPORARY), `Shutdown` mode, `ChildType`
- **Restart Window**: Tracks restart count in sliding time window; if exceeded, stop supervisor

**Lifecycle:**
1. `Supervisor.create(ONE_FOR_ONE, maxRestarts=5, window=60s)`
2. `supervise("worker", init, handler)` → spawns child Proc v1
3. Returns `ProcRef<S,M>` (opaque handle)
4. Child v1 crashes → `onCrash` callback fires
5. Supervisor checks restart count; if within limits, restart
6. Spawns child v2 (fresh process with fresh state)
7. `ProcRef.swap(v2)` atomically updates delegate
8. Old v1 eligible for GC
9. Caller unaware: `ProcRef` now points to v2

**Why ProcRef?** Without it, all callers would need to be notified of restart. With it, location transparency is automatic (like Erlang PIDs).

---

### 5. Restart Strategies (`05-restart-strategies.puml`)

**Scenario:** Three supervisor strategies compared when a child crashes.

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| **ONE_FOR_ONE** | Restart only crashed child; siblings unaffected | Independent workers, microservices |
| **ONE_FOR_ALL** | Stop + restart all children atomically | Shared resource pool, coordinated state |
| **REST_FOR_ONE** | Restart crashed child + all descendants | Dependency chains, parent→child deps |

**Example ONE_FOR_ONE:**
- A, B, C running
- B crashes
- A: still running, C: still running
- B: restarted with fresh state
- A, C unaffected (don't see the restart)

**Example REST_FOR_ONE (dependency chain):**
- Parent, Child1, Child2, Child3 (each depends on previous)
- Child1 crashes
- Child1, Child2, Child3: all restarted in order
- Parent: unaffected
- Result: dependency chain re-initialized

---

### 6. Process Monitoring (`06-process-monitoring.puml`)

**Scenario:** Monitor a process; it terminates and sends DOWN signal.

**Key Differences from Links:**
| Aspect | ProcLink | ProcMonitor |
|--------|----------|-------------|
| **Direction** | Bilateral | Unilateral |
| **Effect** | Both crash | Only target can crash; watcher unaffected |
| **Exit Signal** | Delivered to inbox (if trapping) | Delivered as callback (not mailbox message) |
| **Use Case** | Fault propagation | Health checks, observability |

**Flow:**
1. `ProcMonitor.monitor(target, downHandler)` → returns `MonitorRef`
2. Handler callback registered on target
3. Target terminates (normally or abnormally)
4. `downHandler.accept(reason)` called:
   - `reason = null`: normal exit
   - `reason = exception`: abnormal exit
5. Watcher remains unaffected
6. `ProcMonitor.demonitor(ref)` removes monitor

**Use Case:** Health monitoring, circuit breakers, cascading failover without bidirectional crash propagation.

---

## Advanced Patterns

### 7. State Machine Events (`07-state-machine-events.puml`)

**Scenario:** Send user event; transition state; execute actions (timeout, reply, inject event).

**Sealed Hierarchies (Java 26 Records):**

```java
sealed interface SMEvent<E> {
  record User<E>(E event) implements SMEvent<E> {}
  record StateTimeout<E>() implements SMEvent<E> {}
  record EventTimeout<E>(int id) implements SMEvent<E> {}
  record Enter<E>() implements SMEvent<E> {}
  // ... more event types
}

sealed interface Transition<S> {
  record NextState<S>(S state, List<Action> actions) implements Transition<S> {}
  record KeepState<S>(List<Action> actions) implements Transition<S> {}
  record RepeatState<S>(List<Action> actions) implements Transition<S> {}
  record Stop<S>() implements Transition<S> {}
  record StopAndReply<S>(Object reply) implements Transition<S> {}
}
```

**Flow:**
1. `send(userEvent)` enqueues `SMEvent.User(userEvent)`
2. Message loop dequeues and calls `transitionFn(state, event, data)`
3. `transitionFn` pattern matches on state + event
4. Returns `Transition<S>` with optional actions
5. Action executor processes actions:
   - **SetStateTimeout(ms)**: Inject timeout event at T+ms
   - **Reply(result)**: Send result back to caller
   - **NextEvent(event)**: Inject into queue for immediate processing
   - **Postpone**: Save event for later when state changes
6. Loop continues waiting for next event

**Timeout Semantics:**
- **StateTimeout**: Fires if state unchanged after N ms; auto-cancelled on state change
- **EventTimeout**: Fires if no event after N ms; auto-cancelled on any event
- **Auto-cancel**: Prevents accumulation of stale timeouts

---

### 8. Event Manager (`08-event-manager.puml`)

**Scenario:** Broadcast event to multiple handlers; handler crashes; others unaffected.

**Key Benefit:** Decoupling producers from consumers.

**Handler Isolation:**
- Each handler runs independently
- Exception in handler A doesn't affect handler B
- Failed handler is removed; manager stays alive

**Modes:**

| Method | Behavior | Use Case |
|--------|----------|----------|
| **notify(event)** | Fire-and-forget broadcast | Non-critical events |
| **syncNotify(event)** | Block until all handlers complete | Atomicity required |

**Handler Management:**
- `addHandler(handler)`: Register handler
- `deleteHandler(handler)`: Unregister handler
- Handlers in list; exception during processing → auto-remove

**Use Case:** Pub/sub for domain events (e.g., user registered → send email + log + update cache; email fails → log and cache still happen).

---

### 9. Process Registry (`09-process-registry.puml`)

**Scenario:** Register process by name; lookup; auto-deregister on crash.

**Global Name Table:**
- `register(name, proc)`: Store name → ProcRef mapping
- `whereis(name)`: Lookup process by name (returns null if not found)
- `unregister(name)`: Manual removal
- `registered()`: List all registered names

**Auto-Deregistration:**
- Process linked to registry
- On crash: name automatically removed
- No stale entries
- Safe to re-register with same name

**Use Case:**
1. Supervisor starts "auth-service" → `register("auth-service", authProc)`
2. Client calls `whereis("auth-service")` → gets proc reference
3. Sends requests without holding original reference
4. Auth service crashes → `unregister("auth-service")` fires automatically
5. Client's next `whereis("auth-service")` returns null
6. Can detect failure and failover

**Like Erlang's global:register_name/2 but local scope.**

---

## How to View These Diagrams

### Option 1: PlantUML Online
1. Visit [PlantUML Online Editor](http://www.plantuml.com/plantuml/uml)
2. Copy-paste the `.puml` file contents
3. Diagram renders in browser

### Option 2: PlantUML CLI
```bash
# Install PlantUML (requires Java)
brew install plantuml  # macOS
apt-get install plantuml  # Linux

# Render to PNG/SVG
plantuml docs/sequence-diagrams/01-process-spawning.puml
# Generates: 01-process-spawning.png
```

### Option 3: IDE Plugins
- **VS Code**: Install "PlantUML" extension
- **IntelliJ IDEA**: Built-in PlantUML support
- **Asciidoctor**: Embed PlantUML in `.adoc` documentation

---

## Learning Path

**New to JOTP?** Follow this order:

1. **01-process-spawning.puml** — Understand what a Proc is
2. **02-message-passing.puml** — Learn tell() vs ask()
3. **04-supervised-lifecycle.puml** — See how Supervisor restarts children
4. **05-restart-strategies.puml** — Compare recovery modes
5. **03-process-linking.puml** — Understand bilateral fault propagation
6. **06-process-monitoring.puml** — Learn unilateral monitoring
7. **07-state-machine-events.puml** — Advanced: state machines
8. **08-event-manager.puml** — Advanced: event broadcasting
9. **09-process-registry.puml** — Advanced: process discovery

**Enterprise Focus?** Jump to:
- **04-supervised-lifecycle.puml** + **05-restart-strategies.puml** (reliability)
- **07-state-machine-events.puml** (workflow engines)
- **08-event-manager.puml** (event sourcing / CQRS)
- **09-process-registry.puml** (service discovery)

---

## Related Documentation

- **[ARCHITECTURE.md](../ARCHITECTURE.md)** — Enterprise positioning vs Erlang/Go/Rust/Akka
- **[QUICK_REFERENCE.md](../QUICK_REFERENCE.md)** — API reference by Erlang function
- **[phd-thesis-otp-java26.md](../phd-thesis-otp-java26.md)** — Formal equivalence proof
- **[VERNON_PATTERNS.md](../VERNON_PATTERNS.md)** — Integration patterns (34 EIP patterns)
- **[how-to/](../how-to/)** — Step-by-step guides (creating processes, supervision trees, etc.)
- **[C4 Architecture](ARCHITECTURE-C4.md)** — System/container/component views

---

## Contributing

To add or modify sequence diagrams:

1. Create `.puml` file in this directory
2. Follow PlantUML `@startuml` / `@enduml` syntax
3. Use consistent actor names across related diagrams
4. Add `note` blocks explaining key concepts
5. Update this README with new diagram entry
6. Verify syntax with [PlantUML Online Editor](http://www.plantuml.com/plantuml/uml)

---

## References

- **Erlang/OTP Documentation**: [erlang.org](https://www.erlang.org/docs)
- **Joe Armstrong's PhD Thesis**: "Making reliable distributed systems in the presence of software errors" (2003)
- **PlantUML Sequence Diagrams**: [PlantUML Docs](https://plantuml.com/sequence-diagram)
- **JOTP Source Code**: `/src/main/java/io/github/seanchatmangpt/jotp/`
- **JOTP Tests**: `/src/test/java/io/github/seanchatmangpt/jotp/`
