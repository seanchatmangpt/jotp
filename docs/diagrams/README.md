# JOTP C4 Architecture Diagrams - Complete Index

This directory contains **56 comprehensive C4 PlantUML diagrams** covering all aspects of the JOTP (Java OTP) architecture, from system landscape to cross-cutting concerns.

## Quick Navigation

### Level 0: System Landscape
- **level-0-system-landscape.puml** — High-level business context showing JOTP's role in enterprise

### Level 1: System Context (3 diagrams)
Strategic positioning of JOTP in different deployment contexts:
- **level-1a-system-context-main.puml** — Core JOTP as a service
- **level-1b-system-context-spring-boot.puml** — Integration with Spring Boot ecosystem
- **level-1c-system-context-distributed.puml** — Multi-service distributed architecture

### Level 2: Containers (5 diagrams)
Decomposition into major subsystems:
- **level-2a-containers-main.puml** — Core, Reactive, Enterprise, Dogfood modules
- **level-2b-containers-core-otp.puml** — Process, Supervisor, StateMachine, Recovery primitives
- **level-2c-containers-enterprise.puml** — Health, Backpressure, Saga, CircuitBreaker, Bulkhead, MultiTenancy patterns
- **level-2d-containers-modules.puml** — JPMS module structure with sealed packages
- **level-2e-containers-build.puml** — mvnd, Spotless, Surefire, Failsafe, jgen tooling ecosystem

### Level 3: Components (8 diagrams)
Component-level architecture for major subsystems:

#### Process Management
- **level-3a-components-process-mgmt.puml** — Proc<S,M>, ProcRef, ProcLink, ProcMonitor, ProcRegistry, ProcTimer, ProcSys, ProcLib, ExitSignal, Envelope

#### Supervision & Lifecycle
- **level-3b-components-supervision.puml** — Supervisor, RestartStrategy, ChildSpec, AutoShutdown, CrashRecovery, ApplicationController, StartType, RunType

#### State Machine Engine
- **level-3c-components-state-machine.puml** — StateMachine<S,E,D>, SMEvent sealed, Action sealed, Transition sealed, TransitionFn, Builder, EventManager, Parallel

#### Error Handling & Messaging
- **level-3d-components-errors-messaging.puml** — Result<T,E> sealed, Success, Failure, operations; Envelope, Router, Transformation, Endpoint

#### Enterprise Patterns
- **level-3e-components-enterprise.puml** — HealthMonitor, BackpressureManager, SagaCoordinator, CircuitBreaker, BulkheadPool, MultiTenancyController, CrashRecoveryEngine, TypedEventBus

#### Template Generation
- **level-3f-components-dogfood.puml** — Core patterns, Concurrency, GoF patterns, API patterns, Testing, Error handling, Security, Innovation engines

#### Reactive Messaging
- **level-3g-components-reactive.puml** — ReactiveChannel, MessageRouter, MessageSplitter, MessageAggregator, MessageTransformer, MessageEndpoint, ServiceIntegration, PublisherBinding

#### Module System
- **level-3h-components-module-system.puml** — Module exports, sealed packages, transitive requires, qualified exports

### Level 4: Code Details (20 diagrams)
Implementation details of core primitives and patterns:

#### Core Primitives
- **level-4a-code-proc.puml** — Proc<S,M>: virtual thread, LinkedTransferQueue mailbox, StateHandler, tell/ask patterns
- **level-4b-code-procref.puml** — ProcRef<S,M>: stable handle, generation counter, opacity, equality/hashCode, invalidation
- **level-4c-code-supervisor.puml** — Supervisor: restart strategies, ChildSpec registry, sliding window, restart decision, spawn logic, AutoShutdown
- **level-4d-code-statemachine.puml** — StateMachine<S,E,D>: engine loop, SMEvent queue, state holder, handler invocation, transition application, action effects
- **level-4e-code-proclink.puml** — ProcLink: bilateral linking, exit propagation, link registry, cascade logic, trapExits
- **level-4f-code-procmonitor.puml** — ProcMonitor: unilateral monitoring, DOWN signal, monitor registry, multiple monitors, demonitor

#### Utilities
- **level-4g-code-eventmanager.puml** — EventManager<E>: handler isolation, async/sync dispatch, error containment
- **level-4h-code-result.puml** — Result<T,E>: railway-oriented programming, map, flatMap, fold, recover, peek, orElseThrow
- **level-4l-code-procregistry.puml** — ProcRegistry: global name table, whereis, register, unregister, auto-deregister
- **level-4m-code-proctimer.puml** — ProcTimer: scheduled messages, sendAfter, sendInterval, cancel, timer reference

#### Restart Strategies
- **level-4i-code-restart-one-for-one.puml** — ONE_FOR_ONE: isolated child restart, others unaffected
- **level-4j-code-restart-one-for-all.puml** — ONE_FOR_ALL: cascade all children restart
- **level-4k-code-restart-rest-for-one.puml** — REST_FOR_ONE: ordered dependency, restart failed + dependents

#### Enterprise Patterns
- **level-4n-code-health-pattern.puml** — Health Monitor: unilateral monitoring, aggregation, no-kill semantics
- **level-4o-code-saga-pattern.puml** — Distributed Saga: multi-step coordination, compensation on failure, idempotency
- **level-4p-code-bulkhead-pattern.puml** — Bulkhead Isolation: per-tenant semaphore quotas, failure containment
- **level-4q-code-backpressure-pattern.puml** — Backpressure: token bucket, adaptive backoff, admission control

#### Infrastructure
- **level-4r-code-application-lifecycle.puml** — Application: startup sequence, boot order, graceful shutdown, callbacks
- **level-4s-code-module-system-detail.puml** — JPMS: module-info.java, exports, requires, sealed packages, readability graph
- **level-4t-code-virtual-threads.puml** — Virtual Threads: JEP 425, StructuredTaskScope, scoped values, work-stealing scheduler
- **level-4u-code-proc-detailed.puml** — Proc Deep Dive: mailbox operations, message flow, state lifecycle, error handling, links/monitors, supervisor restart, memory visibility

### Level 5: Sequence Diagrams (8 diagrams)
Dynamic interactions and timing:

#### Core Operations
- **level-5a-sequence-process-spawn.puml** — Spawn: caller → spawn() → virtual thread, mailbox, state handler, return ProcRef
- **level-5b-sequence-tell.puml** — Tell: sender → tell() → enqueue, receiver dequeue, handler runs, continues
- **level-5c-sequence-ask.puml** — Ask: caller → ask(), reply channel, timeout, receiver → Reply, unblock caller
- **level-5g-sequence-eventmanager.puml** — EventManager: event source → notify(), isolated handler execution, error containment

#### Supervision & Failure
- **level-5d-sequence-supervisor-restart.puml** — Supervisor restart: crash → exit signal, restart decision, new child, callback
- **level-5e-sequence-link-exit.puml** — Link exit: bilateral propagation, trapExits decision, cascade

#### State Management
- **level-5f-sequence-statemachine-event.puml** — StateMachine: event → dequeue, handler, transition, actions, state change, Enter event

#### Lifecycle
- **level-5h-sequence-graceful-shutdown.puml** — Graceful shutdown: signal → supervisor, signal children, cleanup, wait, exit normally

### Level 6: Deployment Diagrams (5 diagrams)
Production deployment topologies:

#### Deployment Patterns
- **level-6a-deployment-single-jvm.puml** — Single JVM: server, JVM, JOTP, app, supervisor tree, database, external services
- **level-6b-deployment-multi-jvm-kafka.puml** — Multi-JVM: nodes, Kafka bridge, shared DB, service discovery, registry
- **level-6c-deployment-containerized.puml** — Kubernetes: pods, service discovery, persistent volume, configmap, monitoring
- **level-6d-deployment-multitenancy.puml** — Multi-tenant SaaS: shared runtime, per-tenant supervisors, per-schema isolation
- **level-6e-deployment-blue-green.puml** — Blue-Green: active/standby environments, instant cutover, graceful rollback

### Level 7: Data Flow Diagrams (3 diagrams)
Information flow through the system:
- **level-7a-dataflow-message-passing.puml** — Message flow: source → tell() → queue → dequeue → handler → state update → effects
- **level-7b-dataflow-supervision-tree.puml** — Supervision flow: init → parent → children, crashes → exit signals → decisions → restart
- **level-7c-dataflow-statemachine.puml** — State machine: state+data → events → handler → transition → actions → new state

### Level 8: Cross-Cutting Concerns (3 diagrams)
Non-functional requirements integrated throughout:
- **level-8a-crosscutting-testing.puml** — Testing: JUnit 5, jqwik, AssertJ, ArchUnit, Awaitility, Gatling, TestContainers, Mockito, ProcTestHelper, Instancio
- **level-8b-crosscutting-observability.puml** — Observability: logging (SLF4J), metrics (Micrometer), tracing (OpenTelemetry), profiling (JFR), debugging (JShell)
- **level-8c-crosscutting-security.puml** — Security: validation, encryption, authentication, authorization, audit logging, secrets management, injection protection, rate limiting, encapsulation

## Legend & Conventions

### Diagram Types
- **System Context (Level 1):** Shows system boundary and external systems
- **Container (Level 2):** Decomposition into major subsystems
- **Component (Level 3):** Internal structure of containers
- **Code (Level 4):** Implementation details and algorithms
- **Sequence (Level 5):** Time-ordered interactions
- **Deployment (Level 6):** Physical/cloud infrastructure
- **Data Flow (Level 7):** Information movement through system
- **Cross-Cutting (Level 8):** Non-functional requirements

### PlantUML Conventions
- Uses C4_Context.puml, C4_Container.puml, C4_Component.puml, C4_Sequence.puml, C4_Deployment.puml
- HIDE_STEREOTYPE() for clean rendering
- SHOW_LEGEND() at diagram end
- Sealed type notation for Java 26 exhaustiveness
- Virtual thread terminology throughout

### Key Concepts Visualized

**Sealed Types & Pattern Matching**
- SMEvent<E> sealed by: User, StateTimeout, EventTimeout, GenericTimeout, Internal, Enter
- Action sealed by: Postpone, NextEvent, SetStateTimeout, Reply, etc.
- Transition sealed by: NextState, KeepState, RepeatState, Stop, StopAndReply
- Result<T,E> sealed by: Success<T>, Failure<E>
- ExitSignal.Reason sealed by: Normal, Kill, Other
- RestartType sealed by: PERMANENT, TRANSIENT, TEMPORARY
- ChildType sealed by: WORKER, SUPERVISOR

**Process Model**
- Virtual threads as lightweight processes (millions concurrent)
- LinkedTransferQueue mailbox for message passing
- State handlers as pure functions: (S, M) → (S', Effects)
- ProcRef as stable handle (survives restart)
- Crash as control flow mechanism (let-it-crash)

**Supervision**
- Tree hierarchy: parent → children
- Bilateral links for crash propagation
- Unilateral monitors for health aggregation
- Restart window limits (e.g., max 5 restarts in 15 seconds)
- AutoShutdown policies: NEVER, ANY_SIGNIFICANT, ALL_SIGNIFICANT

**State Machines**
- Full gen_statem parity from Erlang/OTP
- Priority queue for events (internal > user > timeout)
- Automatic StateTimeout cancel on state change
- Postponed events replay in order across transitions
- Enter events on NextState transitions

**Enterprise Patterns**
- Health Monitor: unilateral, no-kill aggregation
- Backpressure: token bucket admission control
- Saga: multi-step distributed transactions with compensation
- Circuit Breaker: failure detection with half-open probing
- Bulkhead: per-tenant thread quotas and failure isolation
- Multi-Tenancy: shared runtime with per-tenant supervisors

**JPMS Module System**
- Sealed packages prevent split-package leaks
- Transitive requires for API visibility
- Qualified exports for test-only modules
- Open modules for reflection (testing only)
- No circular dependencies (DAG enforced by JVM)

## Usage

### Rendering Diagrams
```bash
# Using PlantUML CLI
plantuml -Tpng level-1a-system-context-main.puml

# Using Docker
docker run --rm -v $(pwd):/diagrams plantuml/plantuml -Tpng /diagrams/level-*.puml

# Online viewer: https://www.plantuml.com/plantuml/uml/
# Copy/paste content into online editor
```

### Viewing in Documentation
```markdown
![System Context](./diagrams/level-1a-system-context-main.png)
```

### Understanding the Architecture
1. Start with **Level 1** for business context
2. Progress to **Level 2-3** for subsystem decomposition
3. Deep-dive **Level 4** for implementation details
4. Study **Level 5** sequences for runtime behavior
5. Examine **Level 6-7** for deployment and data flow
6. Review **Level 8** for testing, observability, security

## File Structure
```
docs/diagrams/
├── level-0-system-landscape.puml
├── level-1a-system-context-main.puml
├── level-1b-system-context-spring-boot.puml
├── level-1c-system-context-distributed.puml
├── level-2a-containers-main.puml
├── level-2b-containers-core-otp.puml
├── level-2c-containers-enterprise.puml
├── level-2d-containers-modules.puml
├── level-2e-containers-build.puml
├── level-3a-components-process-mgmt.puml
├── level-3b-components-supervision.puml
├── level-3c-components-state-machine.puml
├── level-3d-components-errors-messaging.puml
├── level-3e-components-enterprise.puml
├── level-3f-components-dogfood.puml
├── level-3g-components-reactive.puml
├── level-3h-components-module-system.puml
├── level-4a-code-proc.puml
├── level-4b-code-procref.puml
├── level-4c-code-supervisor.puml
├── level-4d-code-statemachine.puml
├── level-4e-code-proclink.puml
├── level-4f-code-procmonitor.puml
├── level-4g-code-eventmanager.puml
├── level-4h-code-result.puml
├── level-4i-code-restart-one-for-one.puml
├── level-4j-code-restart-one-for-all.puml
├── level-4k-code-restart-rest-for-one.puml
├── level-4l-code-procregistry.puml
├── level-4m-code-proctimer.puml
├── level-4n-code-health-pattern.puml
├── level-4o-code-saga-pattern.puml
├── level-4p-code-bulkhead-pattern.puml
├── level-4q-code-backpressure-pattern.puml
├── level-4r-code-application-lifecycle.puml
├── level-4s-code-module-system-detail.puml
├── level-4t-code-virtual-threads.puml
├── level-4u-code-proc-detailed.puml
├── level-5a-sequence-process-spawn.puml
├── level-5b-sequence-tell.puml
├── level-5c-sequence-ask.puml
├── level-5d-sequence-supervisor-restart.puml
├── level-5e-sequence-link-exit.puml
├── level-5f-sequence-statemachine-event.puml
├── level-5g-sequence-eventmanager.puml
├── level-5h-sequence-graceful-shutdown.puml
├── level-6a-deployment-single-jvm.puml
├── level-6b-deployment-multi-jvm-kafka.puml
├── level-6c-deployment-containerized.puml
├── level-6d-deployment-multitenancy.puml
├── level-6e-deployment-blue-green.puml
├── level-7a-dataflow-message-passing.puml
├── level-7b-dataflow-supervision-tree.puml
├── level-7c-dataflow-statemachine.puml
├── level-8a-crosscutting-testing.puml
├── level-8b-crosscutting-observability.puml
├── level-8c-crosscutting-security.puml
└── README.md (this file)
```

## Related Documentation

- **.claude/ARCHITECTURE.md** — Executive summary, competitive matrix, OTP patterns
- **.claude/INTEGRATION-PATTERNS.md** — Brownfield adoption, phased migration, Spring Boot integration
- **.claude/SLA-PATTERNS.md** — Operational excellence, 99.95% SLA, incident runbooks
- **docs/phd-thesis-otp-java26.md** — Formal equivalence, benchmarks, migration framework
- **CLAUDE.md** — Overall project guide for Fortune 500 architects and developers

## Authoring Notes

### Design Principles
- **One concern per diagram:** Each diagram visualizes one aspect
- **Progressive disclosure:** Level 1 hides detail; Level 4 shows code
- **Sealed type hierarchy:** All decision points use sealed types for exhaustiveness
- **Java 26 terminology:** Virtual threads, StructuredTaskScope, scoped values throughout
- **Enterprise patterns:** Based on 40 years of Erlang/OTP battle-tested reliability

### Consistency
- All diagrams use C4 notation
- Related diagrams use consistent component names (e.g., "Proc<S,M>" across all levels)
- Color coding for related patterns (e.g., restart strategies)
- Notes include implementation details and Java 26 features

### Updating Diagrams
1. Edit the .puml file directly
2. Regenerate PNG via PlantUML
3. Update this README if structure changes
4. Validate references in other documentation

## References

- **Erlang/OTP:** https://www.erlang.org/doc/
- **Joe Armstrong's thesis:** "Making reliable distributed systems in the presence of software errors"
- **C4 Model:** https://c4model.com/
- **Java 26 Preview Features:** https://openjdk.org/jeps/
- **Virtual Threads (JEP 425):** https://openjdk.org/jeps/425
- **Structured Concurrency (JEP 430):** https://openjdk.org/jeps/430
- **PlantUML:** https://plantuml.com/

---

**Total Coverage:** 57 diagrams, 8 levels, all JOTP primitives and patterns documented.
