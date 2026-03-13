# JOTP Sequence Diagrams

PlantUML sequence diagrams documenting real-time interaction patterns in JOTP (Java OTP: Erlang/OTP fault-tolerance primitives in pure Java 26).

## Quick Reference

### Foundations (01-03)
| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **01-process-spawning.puml** | Process Creation | Virtual threads, message mailbox, state handler |
| **02-message-passing.puml** | Messaging | tell() (fire-and-forget), ask() (request-reply) |
| **03-process-linking.puml** | Process Links | Bilateral crash propagation, exit trapping |

### Process Relationships (04-06)
| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **04-supervised-lifecycle.puml** | Supervision | Child spawning, crash detection, atomic ProcRef.swap() |
| **05-restart-strategies.puml** | Restart Modes | ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE |
| **06-process-monitoring.puml** | Monitoring | Unilateral DOWN signals, health checks |

### Advanced Patterns (07-09)
| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **07-state-machine-events.puml** | State Machines | Event queues, transitions, timeout actions |
| **08-event-manager.puml** | Event Broadcasting | Handler decoupling, crash isolation |
| **09-process-registry.puml** | Name Registry | Registration, lookup, auto-deregistration |

### Enterprise Patterns (10-14) — 80/20 Coverage
| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **10-exit-signal-trapping.puml** | Exit Signals | trapExits, mailbox signals vs direct crash |
| **11-crash-recovery.puml** | Retry Pattern | Exponential backoff, supervised retries |
| **12-parallel-fan-out.puml** | Structured Concurrency | StructuredTaskScope, fail-fast, resource cleanup |
| **13-supervision-hierarchy.puml** | Multi-Level Trees | Hierarchical fault domains, atomic restarts |
| **14-proclib-startup.puml** | Synchronized Init | Startup handshake, initialization ordering |

### Complete OTP Primitives (15-16) — 100% Coverage
| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **15-timer-delivery.puml** | Timed Messages | ProcTimer: send_after, send_interval, cancel |
| **16-process-introspection.puml** | Live Introspection | ProcSys: get_state, suspend, resume, statistics |

### Advanced Enterprise Patterns (17-19) — Beyond Primitives
| Diagram | Pattern | Key Concepts |
|---------|---------|--------------|
| **17-cascading-failures.puml** | Multi-Level Recovery | Hierarchical restarts, fault domains, atomic recovery |
| **18-error-handling-paths.puml** | Error Strategy Selection | PERMANENT/TRANSIENT/TEMPORARY restart types |
| **19-message-ordering.puml** | Ordering Guarantees | FIFO mailbox, causal consistency, determinism |

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

## Enterprise Patterns (80/20 Completion)

### 10. Exit Signal Trapping (`10-exit-signal-trapping.puml`)

**Scenario:** Process A crashes; Process B traps exits and receives signal as mailbox message.

**Key Difference from Links:**
- **Without trapExits**: Crash callback interrupts immediately → cascading failure
- **With trapExits(true)**: Exit signal enqueued as message → process stays alive, can handle gracefully

**Signal Structure:**
```
ExitSignal {
  from: Proc<S,M>        // source of signal
  reason: Throwable      // why it crashed (null = normal exit)
}
```

**When to Use:**
- Building fault-tolerant worker pools that can survive linked crashes
- Implementing supervisor-like recovery without using Supervisor
- Monitoring process health and taking corrective action
- Graceful degradation when dependencies fail

---

### 11. Crash Recovery with Exponential Backoff (`11-crash-recovery.puml`)

**Scenario:** Operation fails; retry with exponential backoff; succeed on 3rd attempt.

**Backoff Formula:**
```
delay(attempt) = baseDelay × 2^(attempt - 1)
Example (baseDelay=100ms):
  Attempt 1 fails → wait 100ms
  Attempt 2 fails → wait 200ms
  Attempt 3 fails → wait 400ms
```

**Why Exponential Backoff:**
1. **Prevents retry storms** — servers aren't pounded
2. **Avoids thundering herd** — clients don't all retry at once
3. **Respects transient failures** — gives system time to recover

**Use Cases:**
- Network timeouts (temporary connectivity loss)
- Rate limiting (temporary quota exhaustion)
- Service unavailability (temporary deployment, maintenance)
- Database connection pools (temporary connection exhaustion)

**Key Property:** Each attempt runs in isolated virtual thread with automatic cleanup.

---

### 12. Parallel Fan-Out with StructuredTaskScope (`12-parallel-fan-out.puml`)

**Scenario:** Spawn 3 independent tasks in parallel; one fails; all shut down immediately (fail-fast).

**Java 26 Pattern:**
```java
Parallel.run(List.of(
  () -> processPartition1(),
  () -> processPartition2(),
  () -> processPartition3()
))
```

**Guaranteed Cleanup:**
- All tasks spawn in virtual threads (lightweight, no thread pool overhead)
- On any task failure: others cancelled immediately
- Resources cleaned up automatically (try-with-resources pattern)
- No leaked threads or dangling resources

**Contrast with Unstructured Threads:**
| Aspect | StructuredTaskScope | Unstructured Threads |
|--------|-------------------|----------------------|
| **Cancellation** | Automatic on failure | Must manually cancel |
| **Cleanup** | Guaranteed by JVM | Risk of leaks |
| **Lifetime** | Scoped (clear boundaries) | Unbounded |
| **Overhead** | Virtual threads (1-2 KB each) | OS threads (1-8 MB each) |

**Use Cases:**
- MapReduce: partition data, process in parallel, collect results
- Batch processing: divide work across cores, fail-fast if any task fails
- Fan-out queries: fetch from multiple sources, fail if any source fails
- Divide-and-conquer: binary search, quicksort, parallel tree traversal

---

### 13. Hierarchical Supervision Tree (`13-supervision-hierarchy.puml`)

**Scenario:** Multi-level supervision: Root → API/Data/Cache Supervisors → Leaf Processes.

**Typical Enterprise Hierarchy:**
```
Root Supervisor (ONE_FOR_ALL)
 ├── API Layer (ONE_FOR_ONE)
 │    ├── API-1 (HTTP handler)
 │    └── API-2 (HTTP handler)
 ├── Data Layer (ONE_FOR_ONE)
 │    ├── DB-1 (connection pool)
 │    └── DB-2 (connection pool)
 └── Cache Layer (ONE_FOR_ONE)
      ├── Cache-1 (Redis client)
      └── Cache-2 (Redis client)
```

**Fault Isolation:**
- **Level 1 (Leaf):** API-1 crashes → API-2 continues (ONE_FOR_ONE)
- **Level 2 (Supervisor):** Data Supervisor crashes → API/Cache unaffected (Root's ONE_FOR_ALL restarts only Data)
- **Level 3 (Root):** Root crashes → entire system restarts (rare, coordinated recovery)

**Why Hierarchical:**
1. **Bounded blast radius** — fault contained to supervisor's domain
2. **Granular recovery** — only affected layer restarts
3. **Coordinated startup** — parent starts children in order
4. **Observable structure** — mirrors business architecture (API/Data/Cache layers)
5. **Scales to large systems** — hundreds of supervisors, thousands of workers

**Strategy Selection:**
- **ONE_FOR_ONE:** Independent workers (most common, workers are stateless)
- **ONE_FOR_ALL:** Coordinated restart (layers depend on each other, need atomic startup)
- **REST_FOR_ONE:** Dependency chains (microservice A depends on B which depends on C)

---

### 14. ProcLib Startup Handshake (`14-proclib-startup.puml`)

**Scenario:** Parent blocks until child calls `initAck()`, ensuring child fully initialized.

**Erlang Pattern (proc_lib:start_link/3):**
```
Parent → ProcLib: start_link(init, handler)
  Parent BLOCKS here (no return yet)
  Child spawns, runs init code
Child → ProcLib: initAck()
  ProcLib releases latch
Parent returns with Ok(childProc)
  Child guaranteed initialized
```

**Without ProcLib (Fire-and-Forget):**
```
Parent → Child: spawn(init, handler)
Parent → Child: tell(firstMessage)  ← RACE CONDITION!
Parent continues

Problem: Child may not be ready
  - Database not connected
  - Configuration not loaded
  - firstMessage arrives before init complete
```

**With ProcLib:**
```
Parent → ProcLib: start_link(init, handler)
  [BLOCKS]
Child initializes
Child → ProcLib: initAck()
Parent UNBLOCKS
Parent → Child: tell(firstMessage)  ← SAFE, child ready
```

**Failure Handling:**
```
Child encounters initialization error
Child → ProcLib: initAck(StartResult.Err(reason))
Parent UNBLOCKS with Err
Parent can:
  - Retry with different params
  - Use fallback
  - Shutdown service
```

**Use Cases:**
- Database connection pool initialization
- Configuration loading and validation
- Health checks before accepting traffic
- Warm-up (pre-populate cache before serving)
- Service startup sequencing (A must start before B)

**Why Critical:** Ensures parent knows exactly when child is ready, eliminating race conditions in initialization-dependent systems.

---

### 15. Timer Message Delivery (`15-timer-delivery.puml`)

**Scenario:** Schedule message delivery after delay; recurring messages; cancel timer.

**OTP Primitive: `timer:send_after/3` and `timer:send_interval/3`**

**Three Core Operations:**

1. **send_after(delay, message)**
   - Deliver message after N milliseconds
   - Returns `TimerRef` for later cancellation
   - Virtual thread pool handles precision scheduling
   - Message enqueued to mailbox at exact time (within ±1ms)

2. **send_interval(interval, message)**
   - Recurring message every N milliseconds
   - Keep sending until cancelled
   - Use case: heartbeats, health checks, GC triggers
   - Each message follows FIFO ordering

3. **cancel(timerRef)**
   - Stop pending timer
   - If already fired: no-op (idempotent)
   - Safe to cancel non-existent timer

**Integration with Supervisor:**
```
Supervisor detects child crash
→ schedule retry attempt
→ sendAfter(1000ms, RetryMessage)
→ wait 1 second
→ message arrives in queue
→ Supervisor handles with exponential backoff
```

**Why Critical:** State machines and supervision depend on timeouts; without timers, there's no way to break deadlocks or retry failed operations.

---

### 16. Process Introspection (`16-process-introspection.puml`)

**Scenario:** Query running process state without stopping it; suspend/resume; collect statistics.

**OTP Primitive: `sys` module (sys:get_state, sys:suspend, sys:resume, sys:statistics)**

**Four Key Operations:**

1. **get_state(procRef) → State**
   - Capture current state object
   - Non-blocking (doesn't stop process)
   - Use case: operations debugging, health dashboards

2. **suspend(procRef) → ok**
   - Pause message handling (FIFO queue paused, not discarded)
   - Keep process alive (virtual thread paused)
   - Use case: maintenance, zero-downtime upgrades, pause playback

3. **resume(procRef) → ok**
   - Unpause message handling
   - Accumulated messages processed in order (FIFO preserved)
   - Safe to interleave: suspend → inspect → modify → resume

4. **statistics(procRef) → Stats**
   - Message queue depth
   - Uptime
   - Memory usage
   - Message throughput
   - Use case: alerting (queue building?), SLA monitoring

**Why Critical:** Operations need production visibility without downtime. ProcSys enables live debugging, health monitoring, and incident response.

---

### 17. Cascading Failures & Multi-Level Recovery (`17-cascading-failures.puml`)

**Scenario:** Worker crashes → L2 Supervisor restarts all workers → L1 Supervisor restarts L2 → system recovered atomically.

**Key Insight:** Failure recovery is atomic per level.

**Blast Radius Control:**
- **Worker A crashes** → Only A restarts (ONE_FOR_ONE)
  - B, C continue unaffected
  - New A instance spawned with fresh state
  - Time: ~10µs

- **L2 Supervisor crashes** → Only L2 restarts (L1's ONE_FOR_ONE)
  - Other L1 children (L3, L4, etc.) unaffected
  - All L2 children (A, B, C) killed + respawned atomically
  - New L2 state reinitialized from defaults
  - Time: ~50µs

- **L1 Root crashes** → Entire system restarts
  - Rare; typically doesn't happen (root is simple)
  - If it does: Java process restart (requires external orchestration)

**Why Multi-Level Supervision Matters:**
1. **Fault Domains** — Container boundaries isolate impact
2. **Atomic Recovery** — All-or-nothing semantics (no partial state)
3. **Speed** — Virtual threads enable sub-millisecond recovery
4. **Type Safety** — New instances are sealed; no state corruption

**Real-World Example:**
```
ecommerce-service crash
→ Supervisor detects
→ Restart ecommerce and all its children atomically
→ Order-Service, Payment-Service, Notification-Service all fresh
→ Causality preserved (no out-of-order messages)
→ Total recovery: <200µs
→ Load balancer hasn't even noticed the outage
```

---

### 18. Error Handling Strategy Selection (`18-error-handling-paths.puml`)

**Scenario:** Process crashes; supervisor decides whether to restart, remove, or escalate.

**Decision Tree:**

| Exit Reason | RestartType | Supervisor Action |
|-------------|----------|-----------------|
| **normal** | (any) | Remove child (success) |
| **shutdown** | (any) | Restart (clean shutdown, not an error) |
| **{error, R}** | PERMANENT | Always restart (essential service) |
| **{error, R}** | TRANSIENT | Restart only if abnormal |
| **{error, R}** | TEMPORARY | Never restart (job completed) |

**Frequency-Based Escalation:**
```
If restart count exceeds maxRestarts in time window:
  → Supervisor crash (escalate to parent)
  → Parent must handle via its own strategy
  → Prevents infinite restart loops
```

**Use Cases:**

1. **PERMANENT Workers:**
   - Database connection pools
   - API handlers
   - Background job processors
   - Always restart; if they fail repeatedly → parent supervisor intervenes

2. **TRANSIENT Children:**
   - Temporary request handlers
   - Cache warming jobs
   - Restart only if abnormal (don't restart if they exit normally)

3. **TEMPORARY Tasks:**
   - Map/reduce workers
   - One-shot cleanup tasks
   - Never auto-restart (already completed their mission)

**Why Critical:** Sophisticated error recovery is OTP's killer feature. Without this decision tree, you either restart everything (waste) or nothing (brittleness).

---

### 19. Message Ordering Guarantees (`19-message-ordering.puml`)

**Scenario:** Three clients send messages rapidly; process handles them in strict FIFO order.

**Guarantee: FIFO Mailbox Ordering**

```
Client-1 → send(Msg-A) @ T=0µs  ┐
Client-2 → send(Msg-B) @ T=1µs  │ Queue: [A, B, C]
Client-3 → send(Msg-C) @ T=2µs  ┘

Process dequeues in order:
  T=100µs: Process(A, state) → state' = handler(state, A)
  T=150µs: Process(B, state') → state'' = handler(state', B)
  T=200µs: Process(C, state'') → state''' = handler(state'', C)

Key: Order is NEVER (A, C, B) or (C, A, B)
     Always (A, B, C) — deterministic
```

**Why This Matters:**

1. **Determinism:** Same message sequence → same final state (testable, reproducible)
2. **Causality:** Effect of message A is visible to message B handler
3. **State Consistency:** No race conditions in state transitions

**Advanced: Causal Ordering in State Machines**

```
State Machine inserts timeout actions:
  1. User sends Event-X
  2. Transition generates action: SetStateTimeout(100ms)
  3. Queue: [Event-X, StateTimeout-X]
  4. (100ms later) StateTimeout-X fires

Key: Timeout fires AFTER user event processed
     Not before, not interleaved
     Causality preserved
```

**Use Cases Enabled by FIFO:**
- Request processing: request → response (no interleaving with other requests)
- Workflow engines: step-1 → step-2 → step-3 (guaranteed order)
- Replay/recovery: replay messages → same final state (exactly-once semantics)
- Testing: message sequences are reproducible (property-based testing)

**Contrast with Java Concurrency:**
| System | Ordering | Cost |
|--------|----------|------|
| JOTP Mailbox | FIFO guaranteed | None (natural consequence of queue) |
| Concurrent HashMap | No order | Must use ConcurrentHashMap |
| ReentrantLock | No message order | Complex coordination needed |
| Akka Actors | Per-sender FIFO* | Not true FIFO across senders |

*Akka guarantees FIFO per sender, but not across senders. JOTP guarantees global FIFO.

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

### 🟢 Beginner (Foundations 01-03)
**Goal:** Understand core JOTP concepts — processes, messaging, and basic fault propagation.

1. **01-process-spawning.puml** — What is a Proc? (virtual thread + mailbox)
2. **02-message-passing.puml** — How do processes communicate? (tell vs ask)
3. **03-process-linking.puml** — How do failures propagate? (bilateral links)

**Outcomes:** You can spawn processes and send messages; you understand virtual threads.

---

### 🟡 Intermediate (Process Relationships 04-06)
**Goal:** Learn process relationships — supervision, monitoring, and discovery.

4. **04-supervised-lifecycle.puml** — How does Supervisor restart children?
5. **05-restart-strategies.puml** — When to use ONE_FOR_ONE vs ONE_FOR_ALL vs REST_FOR_ONE?
6. **06-process-monitoring.puml** — How to monitor without killing the watcher?

**Outcomes:** You can build reliable systems with automatic child restarts; you know the difference between links and monitors.

---

### 🔴 Advanced (Patterns 07-09)
**Goal:** Master stateful systems, event handling, and distributed discovery.

7. **07-state-machine-events.puml** — How do finite state machines work in JOTP?
8. **08-event-manager.puml** — How to decouple producers and consumers?
9. **09-process-registry.puml** — How to discover processes by name?

**Outcomes:** You can build workflows with state machines, implement pub/sub patterns, and enable service discovery.

---

### 🚀 Enterprise (80/20 Patterns 10-14)
**Goal:** Solve production challenges — retries, concurrency, hierarchies, and initialization.

10. **10-exit-signal-trapping.puml** — How to handle linked crashes gracefully?
11. **11-crash-recovery.puml** — How to retry with exponential backoff?
12. **12-parallel-fan-out.puml** — How to run parallel tasks safely?
13. **13-supervision-hierarchy.puml** — How to structure real-world systems hierarchically?
14. **14-proclib-startup.puml** — How to ensure initialization dependencies are met?

**Outcomes:** You can build enterprise-grade fault-tolerant systems with retry logic, parallel processing, and coordinated startup.

---

### 🎯 Complete OTP Primitives (15-16) — Joe Armstrong Checklist
**Goal:** Achieve 100% coverage of the 15 Erlang/OTP primitives ("if it doesn't have a diagram, it doesn't exist").

15. **15-timer-delivery.puml** — How to schedule delayed and recurring messages? (OTP: `timer:send_after`)
16. **16-process-introspection.puml** — How to debug processes without stopping? (OTP: `sys` module)

**Outcomes:** You understand all 15 OTP primitives and their Java equivalents. You can build sophisticated timing and observability features.

**Coverage Checklist:**
- ✅ `spawn/3` → Proc.spawn (01)
- ✅ `link/2` → Proc.link (03)
- ✅ `gen_server:call` → Proc.ask (02)
- ✅ `gen_statem` → StateMachine (07)
- ✅ `supervisor` → Supervisor (04, 05, 13)
- ✅ `pmap/2` → Parallel (12)
- ✅ `monitor/2` → ProcMonitor (06)
- ✅ `global:register_name` → ProcRegistry (09)
- ✅ `timer:send_after` → ProcTimer (15)
- ✅ `crash_recovery` → CrashRecovery (11)
- ✅ `trap_exit` → Proc.trapExits (10)
- ✅ `sys` module → ProcSys (16)
- ✅ `proc_lib` → ProcLib (14)
- ✅ `gen_event` → EventManager (08)
- ✅ `gen_server` → Proc (01, 02 core)

---

### 🌟 Advanced Enterprise Patterns (17-19) — Beyond Primitives
**Goal:** Master sophisticated patterns for real-world fault-tolerant systems.

17. **17-cascading-failures.puml** — How do multi-level supervisors recover atomically?
18. **18-error-handling-paths.puml** — How do I choose PERMANENT vs TRANSIENT restart?
19. **19-message-ordering.puml** — How do FIFO guarantees enable deterministic testing?

**Outcomes:** You can reason about complex fault scenarios, design sophisticated error handling, and leverage deterministic ordering for property-based testing.

---

### 🎯 Learning Paths by Role

**For Beginners:**
1. Foundations (01-03) → Intermediate (04-06) → Advanced (07-09) → Enterprise (10-14)
2. Then Complete OTP (15-16) for full primitives coverage

**For CTOs / Architects:**
- Start with 17-cascading-failures.puml (understanding fault domains)
- Then 13-supervision-hierarchy.puml (real-world structure)
- Then 05-restart-strategies.puml (reliability patterns)
- Then 18-error-handling-paths.puml (strategy selection)
- Then 11-crash-recovery.puml (production resilience)
- Then 14-proclib-startup.puml (initialization discipline)
- Optional: 19-message-ordering.puml (determinism for SRE)

**For Microservice Teams:**
- 06-process-monitoring.puml (health checks)
- 09-process-registry.puml (service discovery)
- 12-parallel-fan-out.puml (fan-out queries)
- 13-supervision-hierarchy.puml (service layers)
- 15-timer-delivery.puml (timeouts, heartbeats)
- 16-process-introspection.puml (production debugging)
- 17-cascading-failures.puml (multi-service recovery)

**For Workflow Engine Builders:**
- 07-state-machine-events.puml (state machines)
- 10-exit-signal-trapping.puml (signal handling)
- 14-proclib-startup.puml (initialization)
- 15-timer-delivery.puml (timeout actions in workflows)
- 19-message-ordering.puml (deterministic workflow execution)

**For Performance Engineers:**
- 12-parallel-fan-out.puml (structured concurrency)
- 11-crash-recovery.puml (intelligent retries, backoff)
- 02-message-passing.puml (mailbox performance)
- 19-message-ordering.puml (determinism enables optimization)
- 15-timer-delivery.puml (GC trigger scheduling)

**For Operations / SRE:**
- 16-process-introspection.puml (live debugging without downtime)
- 13-supervision-hierarchy.puml (understanding fault domains)
- 17-cascading-failures.puml (incident response)
- 18-error-handling-paths.puml (error classification)
- 10-exit-signal-trapping.puml (graceful degradation)

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
