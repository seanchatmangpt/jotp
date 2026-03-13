# JOTP Diagrams Manifest

## Overview

10 comprehensive PlantUML diagrams documenting Level 4 (Code Patterns) and Level 5 (Dynamic Sequences) of the JOTP Enterprise Solution Architecture.

**Target audience:** Solution architects, engineering teams, and operators implementing JOTP at scale.

---

## Level 4: Code-Level Patterns (5 diagrams)

### 1. level-4n-code-health-pattern.puml
**Topic:** Unilateral Health Monitoring

**What it shows:**
- ServiceA (monitored process) runs independently
- HealthMonitor (monitoring process) observes without coupling
- ProcMonitor establishes unilateral link: DOWN signal on A crash
- No bilateral crash propagation—monitor sees crash but doesn't propagate

**Key concepts:**
- Unilateral DOWN signals (non-fatal for monitor)
- Metrics collection without affecting monitored process
- Supervisor ONE_FOR_ONE restart policy (restarts only failed child)

**Enterprise value:** Observability without introducing distributed failure coupling.

---

### 2. level-4o-code-saga-pattern.puml
**Topic:** Saga Pattern for Distributed Transactions

**What it shows:**
- SagaOrchestrator (StateMachine<SagaState, Event, Data>)
- Three-step workflow: Step1 → Step2 → Step3
- Each step uses ask() for request-reply with guaranteed response
- Compensation log tracks undo-actions (idempotent)
- On failure → execute all compensations in reverse

**Key concepts:**
- Distributed transaction semantics via state machine
- Idempotent compensation (survives restarts)
- Compensation log persistent (DB-backed)
- Fail-safe: if orchestrator crashes, compensations survive

**Enterprise value:** Coordinated multi-service operations with ACID-like guarantees without distributed locks.

---

### 3. level-4p-code-bulkhead-pattern.puml
**Topic:** Bulkhead Isolation (Resource Containment)

**What it shows:**
- APIGateway routes requests to three independent bulkheads
- Bulkhead 1 (CRITICAL): 50 workers, 5s timeout, 10/60s restart limit
- Bulkhead 2 (SECONDARY): 20 workers, 30s timeout, 5/60s restart limit
- Bulkhead 3 (BATCH): 5 workers, 5m timeout, 2/60s restart limit
- Each bulkhead has own supervisor (ONE_FOR_ONE strategy)

**Key concepts:**
- Resource bounds prevent cascade failures
- Independent supervision trees
- Differing timeout/worker policies per service tier
- Router enforces request isolation

**Enterprise value:** Prevent a single overloaded service from starving other services (connection pools, thread pools, memory).

---

### 4. level-4q-code-backpressure-pattern.puml
**Topic:** Backpressure & Adaptive Flow Control

**What it shows:**
- Producer (fast): 100K msg/s send rate
- Mailbox (bounded): 1000 max messages, 800 threshold (80%)
- Consumer (slow): 1K msg/s process rate
- When queue > 80% → SlowDown signal → Producer throttles to 50%
- Consumer catches up → queue drains → rate restored

**Key concepts:**
- Bounded mailbox prevents unbounded queue growth
- Feedback loop: queue depth → producer rate adjustment
- Three stages: Normal (0-50%), Caution (50-80%), Critical (80%+)
- Adaptive: automatic rate reduction without manual tuning

**Enterprise value:** Prevent cascading overload (queue overflow → OOM → crash) via automatic backpressure.

---

### 5. level-4r-code-application-lifecycle.puml
**Topic:** Application Startup, Running, Shutdown Lifecycle

**What it shows:**
- **STARTUP:** Config load → Supervisor create → Children supervise → ProcLib.initAck() → Ready
- **RUNNING:** Message processing loop, supervision handles crashes, metrics collected
- **SHUTDOWN:** SIGTERM → Graceful stop → Child termination (reverse order) → Resource cleanup → Exit

**Key concepts:**
- Synchronous startup handshake (initAck blocks until all children ready)
- Supervision active throughout lifetime
- Graceful shutdown with per-child timeout
- Failed init propagates exception (prevents phantom startup)

**Enterprise value:** Deterministic startup/shutdown sequences with guaranteed supervision window.

---

## Level 5: Dynamic Sequences (5 diagrams)

### 6. level-5a-sequence-process-spawn.puml
**Topic:** Process Spawning Sequence

**Timeline:**
1. Main calls `supervisor.supervise(name, initState, handler)`
2. Supervisor spawns new virtual thread (JEP 425, ~1KB memory)
3. Virtual thread creates bounded mailbox (1000 msg capacity)
4. Handler.init(state) called, returns ready signal
5. Supervisor returns Proc<S,M> reference to caller

**Key points:**
- Synchronous operation: caller blocks until process ready
- Virtual thread allocated per process
- Deterministic state initialization
- Caller receives stable process reference

**Timing:** <5ms typical (just memory allocation + thread scheduler)

---

### 7. level-5b-sequence-tell.puml
**Topic:** Fire-and-Forget Async Messaging (tell)

**Timeline:**
1. Caller invokes `proc.tell(MyMessage)` (async, returns immediately)
2. Message enqueued in concurrent queue (O(1) lock-free)
3. Caller continues without blocking
4. **Concurrent:** Virtual thread dequeues message → handler processes → state updated
5. Handler.handle(state, msg) → (newState, _) returns
6. Next message dequeued (FIFO guaranteed)

**Key points:**
- Non-blocking for caller
- FIFO ordering maintained despite concurrent senders
- Handler is pure function (deterministic)
- State updated atomically (CAS)

**Throughput:** 100K+ msg/s per process (hardware dependent)

---

### 8. level-5c-sequence-ask.puml
**Topic:** Request-Reply Synchronous Messaging (ask)

**Timeline:**
1. Caller invokes `proc.ask(Request, timeout=5s)` (blocking, returns CompletableFuture)
2. Internal: Create reply future, enqueue request, schedule timeout(5s)
3. **Concurrent:** Virtual thread dequeues → handler processes → reply generated
4. Reply completes future
5. **Race 1 (reply wins):** Reply arrives <5s → timeout cancelled → caller unblocks with Reply
6. **Race 2 (timeout wins):** No reply after 5s → timeout fires → future completes with TimeoutException → caller unblocks

**Key points:**
- Synchronous from caller perspective (blocks until reply or timeout)
- Internally async (uses virtual threads, no OS thread blocking)
- Guaranteed reply or timeout (never hangs)
- Timeout cancellation prevents leaks

**Latency:** 20-100µs (virtual thread context switch + FIFO dequeue)

---

### 9. level-5d-sequence-supervisor-restart.puml
**Topic:** Supervisor Crash Detection & Restart

**Timeline:**
1. Child process running, handler processing messages
2. Exception thrown in handler → process crashes → virtual thread terminates
3. Supervisor detects child exit (DOWN signal)
4. Restart policy checked: record crash timestamp
5. **Decision point:**
   - Too many (5+ crashes in 60s) → FAIL_FAST → supervisor terminates (alerts ops)
   - Within limits (<5/60s) → proceed
6. Spawn new virtual thread with same init state + handler
7. New process ready, old reference invalid
8. Supervisor registers new child reference

**Key points:**
- Crash detection automatic (supervisor monitors all children)
- Restart limit prevents infinite crash loops
- New process gets fresh state (like-for-like replacement)
- Fail-fast on restart limit exceeded (prevents zombie loops)

**Restart time:** ~200µs (allocate virtual thread + init handler)

---

### 10. level-5e-sequence-link-exit.puml
**Topic:** Process Linking & Bilateral Crash Propagation

**Timeline:**
1. ProcessA calls `Proc.link(ProcessB)` → establish BILATERAL link
2. Link manager installs trap_exit handlers on both A and B
3. Both processes running normally
4. ProcessB crashes → exception thrown → virtual thread terminates
5. Link manager detects B crash → sends EXIT(B, reason) to A
6. **Decision point at A:**
   - trap_exit = false → A terminates immediately (bilateral contract)
   - trap_exit = true → A receives EXIT as message, can decide:
     - Crash self (intentional failure cascade)
     - Recover (A survives, B remains dead, link broken)
7. Supervisor detects both/one dead, applies restart policy

**Key points:**
- Bilateral: both dies if one fails (unless trap_exit + recovery)
- EXIT signal carries exit reason
- trap_exit allows controlled failure coupling
- Useful for coordinated services (all-or-nothing semantics)

**Use case:** Coordinator + Worker pairs where both must succeed or both fail.

---

## Diagram Characteristics

### Styling
- **Color scheme:** Semantic (supervised=green, monitor=orange, mailbox=blue, etc.)
- **Font size:** 11pt for readability at standard zoom
- **Layout:** Top-to-bottom (startup) or left-to-right (flows)

### Annotations
- Each diagram includes detailed notes explaining:
  - What is happening
  - Why it matters (enterprise value)
  - Key timing/throughput characteristics
  - Common decision points and failure modes

### References
- Diagrams reference JOTP primitives from CLAUDE.md:
  - `Proc<S,M>` — core lightweight process
  - `Supervisor` — supervision tree with restart policy
  - `ProcMonitor` — unilateral monitoring
  - `StateMachine<S,E,D>` — gen_statem for orchestration
  - `ProcLink` — bilateral crash coupling
  - `ProcLib.initAck()` — startup synchronization

---

## Reading Guide

**For architects/CTOs:**
- Start with Level 4 diagrams (patterns) to understand system design
- Read 4n (health), 4o (saga), 4p (bulkhead) first
- Understand isolation (4p) and backpressure (4q) for SLA guarantees

**For SREs/operators:**
- Focus on 4n (monitoring), 4q (backpressure), 4r (lifecycle)
- Understand restart windows (4n, 4p) for incident response
- Use 4r for deployment runbooks

**For engineers implementing JOTP:**
- Study Level 5 sequences (5a-5e) in order
- 5a: How does a process start?
- 5b: How does async messaging work?
- 5c: How does sync RPC work?
- 5d: How does crash recovery work?
- 5e: What about process linking?

---

## Rendering

All diagrams are pure PlantUML and render in:
- PlantUML online editor (plantuml.com)
- Markdown viewers with PlantUML support
- VS Code (with PlantUML extension)
- CI/CD pipelines (plantuml CLI)

To render to PNG:
```bash
plantuml docs/diagrams/level-4n-code-health-pattern.puml -o png
```

To render all:
```bash
plantuml docs/diagrams/level-*.puml -o png
```

---

**Last updated:** 2026-03-13
**Status:** Complete (10/10 diagrams)
