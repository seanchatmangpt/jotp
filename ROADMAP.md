# JOTP Roadmap: 2026 & Beyond

## 🎯 Philosophy: Let It Crash, Not Bloat It

JOTP follows a **minimal core, maximum compatibility** approach inspired by Erlang/OTP:
- **Core (2026.1)**: 15 OTP primitives—stable, focused, production-ready
- **Extensions (2026.2+)**: Enterprise patterns, integrations—optional, not core

This prevents bloat and allows users to adopt only what they need.

---

## 📊 Release Timeline

```
2026.1.0 (Current - March 17, 2026)
├── 15 OTP Primitives ✅
├── Supervision Trees ✅
├── Virtual Threads ✅
├── Sealed Types ✅
├── 113 Core Tests ✅
└── Production Docs ✅

2026.2 (Q2-Q3 2026 - June/September)
├── Messaging Subsystem (experimental → stable)
├── Enterprise Patterns (opt-in)
├── Connection Pooling
├── OpenTelemetry Integration
└── gRPC Distributed Actors

2027.1+ (Year 2027 and beyond)
├── Cloud-Native (Kubernetes operators)
├── Advanced Monitoring (metrics, tracing)
├── Cluster Support
└── User-driven features
```

---

## 🔄 2026.1 → 2026.2 Progression

### NOT IN 2026.1 (Deferred)

#### 1️⃣ **Messaging Subsystem** (io.github.seanchatmangpt.jotp.messaging)
**What**: Typed message routing, channels, transformation, event streaming

**Why Deferred**:
- Design still evolving (should be sync-based or async?)
- Interactions with EventManager<E> need refinement
- Low priority for core OTP (EventManager covers pub-sub)
- ~40 source files excluded from 2026.1 build

**Scope for 2026.2**:
```java
// Hypothetical API (not final):
var channel = TypedChannel.create(WorkMessage.class);
channel.publish(new WorkMessage.Start());
channel.subscribe(consumer, (state, msg) -> state.handle(msg));
```

**Effort**: 6-8 weeks (design finalization + implementation)

**Blocker Removal**: Already excluded from build; no impact on 2026.1.0 release

---

#### 2️⃣ **Enterprise Patterns** (io.github.seanchatmangpt.jotp.enterprise)
**What**: Saga coordination, multitenancy, circuit breaker, bulkhead, backpressure, health checks

**Why Deferred**:
- Optional for most users (focus on core OTP first)
- 32+ source files, complex interaction matrix
- Requires real-world validation before stable API
- Low usage priority until after 2026.1 stabilization

**Scope for 2026.2+** (prioritized):

| Pattern | Complexity | Timeline | Status |
|---------|-----------|----------|--------|
| **DistributedSagaCoordinator** | High | 2026.2 | Planned |
| **MultiTenantSupervisor** | Medium | 2026.2 | Planned |
| **CircuitBreaker** | Medium | 2026.2 | Planned |
| **BulkheadIsolation** | Medium | 2026.2 | Planned |
| **BackpressureHandler** | High | 2026.3 | Planned |
| **HealthCheckManager** | Low | 2026.2 | Planned |

**Example Use Case** (when available):
```java
// Saga pattern (deferred to 2026.2)
var saga = DistributedSaga.define()
    .step("checkout", checkoutSaga)
    .step("payment", paymentSaga)
    .compensate("payment", paymentCompensation)
    .compensate("checkout", checkoutCompensation)
    .build();
```

**Blocker Removal**: Already excluded from build; no impact on 2026.1.0 release

---

#### 3️⃣ **Connection Pooling** (io.github.seanchatmangpt.jotp.pool)
**What**: Resource reuse for database connections, HTTP clients, etc.

**Why Deferred**:
- Domain-specific (JDBC, R2DBC, HTTP—each different)
- Cleaner to let users integrate with HikariCP, Pooling, etc.
- Optional; users can pool externally and pass connections
- 8+ source files, moderate complexity

**Scope for 2026.2**:
```java
// Generic pool wrapper (not JDBC-specific)
var pool = ObjectPool.create(
    () -> createConnection(),
    (conn) -> conn.close(),
    poolSize
);
```

**Effort**: 2-3 weeks

**Blocker Removal**: Already excluded from build; no impact on 2026.1.0 release

---

### IN 2026.1 ✅ (Stable API)

These are locked in 2026.1.0 and guaranteed stable through 2026.x:

| Primitive | Status | Guarantee |
|-----------|--------|-----------|
| Proc<S,M> | ✅ | Stable |
| Supervisor | ✅ | Stable |
| StateMachine<S,E,D> | ✅ | Stable |
| ProcRef<S,M> | ✅ | Stable |
| ProcMonitor | ✅ | Stable |
| ProcLink | ✅ | Stable |
| ProcRegistry | ✅ | Stable |
| ProcTimer | ✅ | Stable |
| ProcSys | ✅ | Stable |
| ProcLib | ✅ | Stable |
| CrashRecovery | ✅ | Stable |
| Parallel | ✅ | Stable |
| EventManager<E> | ✅ | Stable |
| Result<T,E> | ✅ | Stable |
| ExitSignal | ✅ | Stable |

**Compatibility**: No breaking changes planned for these in 2026.2, 2026.3, 2027, etc.

---

## 🔮 Vision: 2027 and Beyond

### Cloud-Native Support
- **Kubernetes Operators**: Supervise containers as processes
- **Distributed Tracing**: OpenTelemetry integration (started 2026.2)
- **Service Mesh**: Istio/Linkerd integration examples

### Advanced Observability
- **Metrics**: Prometheus export (process counts, mailbox depth, etc.)
- **Structured Logging**: Correlation IDs, span tracking
- **Profiling**: Virtual thread profiler integration

### Cluster Support
- **Multi-Node Supervisors**: Fault-tolerant across machines
- **Distributed Registry**: Name-based lookup across cluster
- **Raft Consensus**: For distributed state (optional)

### Community-Driven
- Integration patterns for popular frameworks (Spring Boot, Quarkus, Micronaut)
- Domain-specific libraries (HTTP, gRPC, database)
- Third-party monitoring tools

---

## 🚧 How We Prioritize

1. **Core Stability** (2026.1): Lock 15 OTP primitives
2. **User Feedback** (2026.1+): Listen to adoption patterns
3. **Demand-Driven** (2026.2+): Implement most-requested features first
4. **Quality-First**: No feature ships without 80%+ test coverage + docs

### Feature Request Process
1. Open a GitHub Discussion: "Feature Request: <title>"
2. Core team evaluates against philosophy
3. Community votes (issues/discussions)
4. If approved: added to roadmap and prioritized
5. Implementation starts next sprint (2-week cycles)

---

## 📝 Known Constraints & Decisions

### Why Not Include Everything in 2026.1?
- **Joe Armstrong's Definition**: Focus on 15 OTP primitives first
- **Ship Small, Iterate Fast**: Release 2026.1 as production-ready foundation
- **Avoid Bloat**: Users adopt only what they need
- **Quality > Quantity**: Better to ship 15 primitives excellently than 50 mediocrely

### Why Messaging Subsystem is Deferred
- **Design Uncertainty**: Sync vs. async? Backpressure strategy?
- **Interaction with EventManager**: Need real-world usage patterns first
- **Low Critical Path**: EventManager<E> covers pub-sub for now

### Why Enterprise Patterns are Deferred
- **Optional for Core Use Cases**: Most users need just Proc + Supervisor
- **Integration Complexity**: Each pattern (saga, multitenancy, bulkhead) is substantial
- **Needs Validation**: Patterns should prove themselves in production first

### Why Not Backport to Java 21/23?
- **Java 26 Features Required**: Virtual threads, sealed types are core to design
- **Stable Preview**: Java 26's --enable-preview features are final in Java 27
- **Focus on Future**: Better to target Java 26+ than maintain legacy support

---

## 🤝 Contributing to the Roadmap

### How to Request a Feature
```
Title: Feature Request: <short description>

Problem:
What problem does this solve?

Use Case:
How would you use this?

Proposed Solution:
What would the API look like?

Effort:
Estimate: Low / Medium / High
```

### How to Contribute
1. Check [CONTRIBUTING.md](CONTRIBUTING.md)
2. Open a discussion first (not PR)
3. Roadmap impacts need consensus
4. PRs welcome for roadmap items marked "open for contribution"

---

## 📊 Success Metrics

For each roadmap item, we track:
- **Adoption**: How many users request it?
- **Effort**: Weeks to implement + maintain
- **Impact**: What problems does it solve?
- **Stability**: Test coverage, real-world validation

Example decision for 2026.2:
```
Feature: DistributedSagaCoordinator
Adoption: 3 feature requests ✅
Effort: 6-8 weeks
Impact: Enables distributed transaction patterns
Stability: Need enterprise validation before stable API
Decision: APPROVED for 2026.2; 2026.1 too early
```

---

## 🎯 North Star: 2030

By 2030, JOTP should be:
- ✨ **Industry Standard**: The OTP framework for Java
- 🚀 **Battle-Tested**: Millions of processes in production
- 📚 **Well-Documented**: Examples for every use case
- 🌍 **Cloud-Native**: Seamless Kubernetes, cloud integration
- 💡 **Community-Driven**: Steered by real-world needs

---

**Questions?** Open an issue: https://github.com/seanchatmangpt/jotp/issues

**Last Updated**: 2026-03-17
**Next Review**: 2026-06-17 (post-2026.2 planning)
