# JOTP Architecture Diagrams (C4 Model)

This directory contains C4 architecture diagrams for JOTP at all four abstraction levels: System Context, Containers, Components, and Code.

## Overview

The C4 model provides a visual way to communicate software architecture at progressively deeper levels of detail:

- **Level 1 (System Context):** JOTP in the broader Java ecosystem
- **Level 2 (Containers):** High-level subsystems within JOTP
- **Level 3 (Components):** The 15 OTP primitives organized into 5 subsystems
- **Level 4 (Code):** Implementation details of key primitives

All diagrams are written in **PlantUML** and can be rendered to PNG using the PlantUML CLI, VS Code extensions, or online renderers.

---

## Level 1: System Context

**File:** [`level-1-system-context.puml`](./level-1-system-context.puml)

**Audience:** CTOs, architects, decision-makers

**Purpose:** Show JOTP's position in the Java ecosystem and how it integrates with external systems.

**Key Elements:**
- **JOTP Framework** (central system): 15 OTP primitives + Java 26 semantics
- **Java Applications**: Spring Boot microservices, standalone services, batch systems
- **External Systems**: Kafka (interim distributed actor bridge), databases, gRPC, legacy Erlang/OTP systems
- **Relationships**: Message passing, fault signals, event broadcasts

**When to use this:** Present to stakeholders evaluating JOTP vs. Erlang/OTP, Akka, or Go for a new system design.

**Key Insights:**
- JOTP brings OTP fault-tolerance semantics into the Java ecosystem
- Integration points with Kafka allow cross-JVM actor communication (interim solution)
- Distributed actors planned for Q2 2026 (native gRPC bridge)

---

## Level 2: Containers

**File:** [`level-2-containers.puml`](./level-2-containers.puml)

**Audience:** Technical leads, senior engineers

**Purpose:** Show the 4 major containers within JOTP and their dependencies.

**Containers:**

1. **Core OTP Primitives** (15 production-ready classes)
   - Process: `Proc`, `ProcRef`
   - Supervision: `Supervisor`, `CrashRecovery`
   - Workflows: `StateMachine`, `EventManager`, `Parallel`
   - Naming: `ProcRegistry`, `ProcTimer`
   - Introspection: `ProcSys`, `ProcLib`
   - Linking: `ProcLink`, `ProcMonitor`
   - Error Handling: `Result`

2. **Enterprise Patterns**
   - Health checks (unilateral monitoring without killing)
   - Backpressure & flow control
   - Saga orchestration for multi-service workflows
   - Multi-tenant isolation (per-supervisor)
   - Bulkhead isolation (fault containment)
   - Crash recovery strategies
   - Event broadcasting (decoupled subscribers)

3. **Dogfood Examples** (template-generated reference code)
   - Core patterns (records, sealed types, pattern matching)
   - Concurrency (virtual threads, structured concurrency)
   - GoF patterns reimagined for modern Java
   - API patterns (HttpClient, java.time, NIO.2)
   - Testing strategies (JUnit 5, jqwik, AssertJ, ArchUnit)
   - Error handling (Result<T,E> railway patterns)
   - Security (validation, encryption)
   - OTP-specific examples

4. **Reactive Messaging** (Vaughn Vernon's patterns for Java)
   - Message channels & pipes
   - Message routing strategies
   - Message transformations
   - Message endpoints
   - Channel management
   - Publisher bindings for reactive streams

**Dependencies:**
- Enterprise Patterns depend on Core OTP Primitives
- Dogfood Examples use both Core and Enterprise patterns
- Reactive Messaging wraps Core OTP Primitives with stream semantics

**When to use this:** Plan phased adoption or feature rollout across your organization.

---

## Level 3: Components

**File:** [`level-3-components.puml`](./level-3-components.puml)

**Audience:** Java developers learning JOTP

**Purpose:** Understand the 15 OTP primitives and how they relate to each other.

**5 Subsystems:**

### 1. Process Management (8 classes)
- **Proc<S,M>**: Virtual thread + mailbox + state handler (`tell`, `ask`)
- **ProcRef<S,M>**: Stable process handle (survives supervisor restarts)
- **ProcLink**: Bilateral crash propagation (`link`, `trapExits`)
- **ProcMonitor**: Unilateral monitoring (`monitor` → DOWN signal)
- **ProcRegistry**: Global name table (`register`, `whereis`, `unregister`)
- **ProcTimer**: Timed message delivery (`sendAfter`, `sendInterval`, `cancel`)
- **ProcSys**: Live introspection (`getState`, `suspend`, `resume`, `statistics`)
- **ProcLib**: Startup handshake (`startLink`, `initAck`)

### 2. Supervision & Recovery (7 classes)
- **Supervisor**: Hierarchical fault-tolerance tree with restart strategies
- **CrashRecovery**: Isolated retry with supervised recovery
- **ApplicationController**: Application lifecycle orchestrator
- **ApplicationSpec**: Application metadata (record)
- **ApplicationCallback**: Application behavior (interface)
- **StartType** (sealed): NORMAL, TAKEOVER, FAILOVER
- **RunType** (sealed): PERMANENT, TRANSIENT, TEMPORARY

### 3. State Machines & Events (4 classes)
- **StateMachine<S,E,D>**: Full `gen_statem` parity with sealed event/action/transition types
- **EventManager<E>**: Typed event bus (handlers crash independently)
- **Parallel**: Structured fan-out with fail-fast semantics
- **Transition**: Sealed type with variants (NextState, KeepState, RepeatState, Stop, StopAndReply)

### 4. Error Handling (1 class)
- **Result<T,E>**: Railway-oriented sealed type
  - `Ok(value)` | `Err(reason)`
  - Methods: `map`, `flatMap`, `fold`, `recover`, `peek`, `orElseThrow`

### 5. Exit Signals & Messaging (2 classes)
- **ExitSignal**: Message sent when process crashes (only if `trapExits(true)`)
- **Envelope**: Wrapper around user messages with sender, replyTo, type info

**Relationships:**
- `Proc` can be linked → `ProcLink`
- `Proc` can be monitored → `ProcMonitor`
- `Proc` can register a name → `ProcRegistry`
- `Proc` receives timed messages → `ProcTimer`
- `Supervisor` manages multiple `Proc` instances
- `StateMachine` typically runs inside a `Proc`
- `EventManager` handlers run as `Proc` instances
- `Result` is returned from most operations

**When to use this:** Learn which primitive to use for your use case (process spawning, supervision, state workflows, monitoring, etc.).

**Source Code Reference:**
- All primitives: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/*.java`
- Enterprise patterns: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/`
- Dogfood examples: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/dogfood/`
- Reactive bindings: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/reactive/`

---

## Level 4: Code Details

Three detailed code-level diagrams show the internals of the three most important primitives.

### 4a. Proc<S,M>: Virtual Thread Process

**File:** [`level-4-code-proc.puml`](./level-4-code-proc.puml)

**Focus:** How a lightweight process works internally.

**Key Components:**
- **Virtual Thread** (~1 KB heap, millions can run concurrently)
- **LinkedTransferQueue<Envelope<M>>** (lock-free MPMC mailbox, 50-150 ns/message latency)
- **Envelope<M>** (message wrapper: type, sender, replyTo channel, payload)
- **StateHandler: BiFunction<S, M, S>** (pure function: state × message → new state)
- **tell(msg)**: Async fire-and-forget (non-blocking to sender)
- **ask(msg, timeout)**: Sync request-reply with timeout (blocking caller)
- **trapExits(bool)**: Process flag controlling whether exit signals become mailbox messages
- **Engine Loop**: Dequeue → call handler → update state → process replies

**Non-Blocking Architecture:**
- Multiple senders can `tell()` concurrently (lock-free queue)
- Single receiver dequeues sequentially (no lock contention)
- `ask()` creates temporary reply channel (TransferQueue)
- Failures thrown to supervisor (no catching in handler)

**When to use this:** Understand how to design state handlers and message types for your processes.

**Source Code:** [`/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Proc.java`](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/Proc.java)

---

### 4b. Supervisor: Fault-Tolerance Tree & Restart Logic

**File:** [`level-4-code-supervisor.puml`](./level-4-code-supervisor.puml)

**Focus:** How supervision trees manage child process lifecycles and restarts.

**Key Components:**

1. **RestartStrategy** (enum)
   - `ONE_FOR_ONE`: Restart only the failed child (preferred for independent workers)
   - `ONE_FOR_ALL`: Restart all children when any fails (for tightly coupled subsystems)
   - `REST_FOR_ONE`: Restart failed child + all started after (for dependency chains)
   - `SIMPLE_ONE_FOR_ONE`: Dynamic homogeneous pools with auto-scaling

2. **ChildSpec<S,M>** (per-child metadata)
   - `id`: Unique identifier
   - `factory`: Factory function to create new instances
   - `restartType`: PERMANENT (always restart) | TRANSIENT (restart on abnormal exit) | TEMPORARY (never restart)
   - `shutdown`: How to terminate (BrutalKill | Timeout | Infinity)
   - `childType`: WORKER (stateless) or SUPERVISOR (has children)

3. **Sliding Restart Window** (rate limiting)
   - `maxRestarts`: Integer limit
   - `withinSeconds`: Duration
   - If exceeded → supervisor itself terminates (propagates up to parent)

4. **AutoShutdown Policy** (supervisor lifecycle)
   - `NEVER`: Supervisor lives regardless of child exits
   - `ANY_SIGNIFICANT`: Supervisor dies if any significant child dies
   - `ALL_SIGNIFICANT`: Supervisor dies if all significant children exit

5. **State Machine Loop**
   ```
   Running
     ↓ child exits
   Crash Detection
     ↓ check RestartType + restart window
   Restart Decision (PERMANENT/TRANSIENT) or Restarted/Failed
     ↓
   Running (new child with new ProcRef) or Failure Propagation
   ```

6. **Dynamic Operations** (at runtime)
   - `startChild(ChildSpec)` → ProcRef
   - `terminateChild(id | ref)`
   - `deleteChild(id)` (must terminate first)
   - `whichChildren()` → List<ChildSpec>

**Isolation Benefits:**
- **Process isolation**: Each child runs in its own virtual thread
- **Fault containment**: Child crash doesn't affect siblings (ONE_FOR_ONE)
- **Transparent restart**: ProcRef stable handle hides restarts from callers
- **Rate limiting**: Sliding window prevents restart storms

**When to use this:** Design supervision trees for your application (how to structure child processes, which restart strategy fits your domain).

**Source Code:** [`/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java`](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java)

---

### 4c. StateMachine<S,E,D>: Full gen_statem Parity

**File:** [`level-4-code-statemachine.puml`](./level-4-code-statemachine.puml)

**Focus:** How state machine workflows are implemented with full Erlang `gen_statem` semantics.

**Key Components:**

1. **SMEvent<E>** (sealed hierarchy of event types)
   - `User(event: E)`: User-generated event (`tell(event)`)
   - `StateTimeout(data)`: State timeout fired
   - `EventTimeout(id, data)`: Event-specific timeout
   - `GenericTimeout(id, data)`: Generic timeout
   - `Internal(event: E)`: Internal event queued by action
   - `Enter(prevState)`: State entry signal (fired on state change)

2. **Action** (sealed hierarchy of side effects)
   - `Postpone`: Queue current event for next state (no event loss)
   - `NextEvent(event: E)`: Insert event at head of queue (priority)
   - `SetStateTimeout(duration, data)`: Arm timeout (auto-cancel on any event)
   - `CancelStateTimeout`: Disarm state timeout
   - `SetEventTimeout(id, duration, data)`: Arm event-specific timeout (manual lifecycle)
   - `CancelEventTimeout(id)`: Disarm event timeout
   - `SetGenericTimeout(id, duration, data)`: Arm generic timeout
   - `CancelGenericTimeout(id)`: Disarm generic timeout
   - `Reply(to: ProcRef, reply)`: Send async reply (for `ask()` initiators)

3. **Transition<S,D>** (sealed hierarchy of outcomes)
   - `NextState(newState, newData, actions)`: Change state (fires `Enter` event)
   - `KeepState(newData, actions)`: Keep state (no `Enter` event)
   - `RepeatState(actions)`: Keep state and data (internal transition)
   - `Stop(reason)`: Halt state machine
   - `StopAndReply(reply, reason)`: Reply then halt

4. **TransitionFn<S,E,D>** (pure handler function)
   - Signature: `apply(state: S, event: SMEvent<E>, data: D) → Transition<S,D>`
   - Deterministic, no side effects (effects via actions)

5. **State Machine Engine Loop** (runs in `Proc`)
   ```
   1. Check StateTimeout (auto-cancel on any event)
   2. Dequeue from queue (nextEvent priority, then pending)
   3. Call handler(state, event, data)
   4. Execute transition (NextState/KeepState/etc)
   5. Execute actions (set timeouts, queue events, reply, etc)
   6. Loop
   ```

6. **Advanced Semantics**
   - **Postpone mechanism**: Queue event for next state → no event loss on state change
   - **StateTimeout semantics**: Auto-cancel on any event (even unrelated) → prevents stale timeouts
   - **EventTimeout semantics**: Must cancel explicitly → for async workflows
   - **Enter signal**: State entry hook → initialize state-specific data
   - **insertedEvents queue**: `NextEvent` has priority over pending events

7. **Builder Pattern** (DSL for configuration)
   ```java
   stateMachine<State, Event, Data>(initialState, initialData)
     .withStateEnter(state, handler)
     .build()
   ```

**No Event Loss Guarantee:**
- Postpone ensures events don't get discarded on state changes
- Engine replays postponed events when state changes
- Full parity with Erlang `gen_statem` semantics

**When to use this:** Model complex workflows with timeouts, multi-state transitions, and coordinated actions.

**Source Code:** [`/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/StateMachine.java`](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/StateMachine.java)

---

## Rendering the Diagrams

All diagrams are written in **PlantUML** format and can be rendered to PNG using:

### Option 1: PlantUML CLI (Recommended)

```bash
# Install PlantUML (requires Java)
# https://plantuml.com/download

# Render a single diagram
plantuml level-1-system-context.puml

# Render all diagrams in directory
plantuml *.puml

# Render to specific format
plantuml -tpng level-1-system-context.puml
plantuml -tsvg level-1-system-context.puml
```

### Option 2: VS Code Extension

Install **PlantUML** extension by jebbs in VS Code:
1. Search for "PlantUML" in Extensions
2. Click rendered preview with `Alt+D` while editing `.puml` files

### Option 3: Online Renderer

Use [PlantUML Online Editor](https://www.plantuml.com/plantuml/uml/):
1. Copy entire `.puml` file content
2. Paste into editor
3. PNG export button in top right

### Option 4: Generate All Diagrams Programmatically

```bash
#!/bin/bash
# Requires plantuml jar in PATH

for file in *.puml; do
  echo "Rendering $file..."
  plantuml -tpng "$file" -o rendered/
done
```

---

## Architecture Documentation

For deeper context on JOTP architecture, design patterns, and enterprise integration:

- **[ARCHITECTURE.md](../.claude/ARCHITECTURE.md)** — Executive summary, competitive analysis, 7 fault-tolerance patterns
- **[SLA-PATTERNS.md](../.claude/SLA-PATTERNS.md)** — Meeting 99.95%+ SLA, operational patterns, incident runbooks
- **[INTEGRATION-PATTERNS.md](../.claude/INTEGRATION-PATTERNS.md)** — Phased brownfield adoption (Spring Boot integration, dual-write migration)
- **[phd-thesis-otp-java26.md](../../docs/phd-thesis-otp-java26.md)** — Formal equivalence proofs, benchmarks, migration frameworks

---

## Quick Navigation

**For newcomers to JOTP:**
1. Start with [Level 1 System Context](#level-1-system-context) to understand the big picture
2. Read [Level 2 Containers](#level-2-containers) to see the major subsystems
3. Explore [Level 3 Components](#level-3-components) to learn the 15 primitives
4. Dive into [Level 4 Code](#level-4-code-details) for implementation details on Proc, Supervisor, StateMachine

**For architects evaluating JOTP:**
1. Study [Level 1 System Context](#level-1-system-context)
2. Read [ARCHITECTURE.md](../.claude/ARCHITECTURE.md) for competitive comparison
3. Review [SLA-PATTERNS.md](../.claude/SLA-PATTERNS.md) for operational excellence patterns

**For experienced OTP developers migrating from Erlang:**
1. Review [Level 2 Containers](#level-2-containers) to map OTP concepts to Java
2. Study [Level 3 Components](#level-3-components) for primitive parity
3. Check [Level 4 Code](#level-4-code-details) for implementation differences (mailbox, state machine engine)
4. Read [INTEGRATION-PATTERNS.md](../.claude/INTEGRATION-PATTERNS.md) for adoption roadmap

---

## Contributing

To update these diagrams:

1. Edit the `.puml` file with your changes
2. Render to PNG using PlantUML CLI (see [Rendering](#rendering-the-diagrams))
3. Commit both `.puml` and `.png` files
4. Update this README with any new content

---

## License

These diagrams are part of JOTP and are distributed under the same license as the main project.

For source code references and implementation details, see:
- Main module: `src/main/java/io/github/seanchatmangpt/jotp/`
- Tests: `src/test/java/io/github/seanchatmangpt/jotp/`
- Documentation: `docs/`
