# JOTP 2026.1.0 - Production-Ready OTP Framework for Java 26

## 🚀 Release Highlights

JOTP 2026.1.0 brings **all 15 Erlang/OTP primitives** to the JVM with **virtual threads, sealed types, and exhaustive pattern matching**. Battle-tested concurrency, supervision trees, and fault tolerance—now production-ready on Java 26.

**Definition of Done (Joe Armstrong)**: ✅ Let It Crash philosophy fully realized.

---

## ✨ What's Included: 15 OTP Primitives

| Primitive | Purpose | Status |
|-----------|---------|--------|
| **Proc<S,M>** | Lightweight process with immutable mailbox | ✅ |
| **Supervisor** | Hierarchical fault-tolerant process tree | ✅ |
| **StateMachine<S,E,D>** | Full gen_statem contract with sealed transitions | ✅ |
| **ProcRef<S,M>** | Stable handle surviving supervisor restarts | ✅ |
| **ProcMonitor** | One-way DOWN notification without affecting target | ✅ |
| **ProcLink** | Bidirectional crash propagation (exit signals) | ✅ |
| **ProcRegistry** | Name-based process lookup (whereis/1 equivalent) | ✅ |
| **ProcTimer** | Scheduled message delivery to processes | ✅ |
| **ProcSys** | Live introspection: suspend/resume/statistics | ✅ |
| **ProcLib** | Process utilities: init_ack, handshakes | ✅ |
| **CrashRecovery** | Isolated virtual thread with Result<T,E> | ✅ |
| **Parallel** | Structured concurrency via StructuredTaskScope | ✅ |
| **EventManager<E>** | Typed pub-sub event bus (handler crash isolation) | ✅ |
| **Result<T,E>** | Railway-oriented error handling (sealed Ok/Err) | ✅ |
| **ExitSignal** | Exit reason carrier (trap_exit pattern) | ✅ |

---

## 🎯 Key Features

### Supervision Trees: Hierarchical Fault Tolerance
```java
Supervisor.tree()
    .strategy(RestartStrategy.ONE_FOR_ONE)
    .child("worker", () -> new WorkerProcess())
    .child("monitor", () -> new MonitorProcess())
    .start();
```

### Message-Passing with Type Safety
```java
sealed interface WorkMessage permits WorkMessage.DoWork, WorkMessage.Stop {}
record DoWork(String task) implements WorkMessage {}
record Stop() implements WorkMessage {}

// Pure message handler: (State, Message) → State
var proc = Proc.spawn(initialState, (state, msg) -> {
    return switch(msg) {
        case DoWork(var task) -> state.process(task);
        case Stop() -> throw new ShutdownException();
    };
});
```

### Crash Recovery with "Let It Crash"
```java
// No try-catch—let it crash and supervisor restarts it
var result = CrashRecovery.run(() -> riskyOperation());
switch(result) {
    case CrashRecovery.Ok(var value) -> handle(value);
    case CrashRecovery.Err(var ex) -> log(ex);
}
```

### Virtual Threads: Millions of Lightweight Processes
```java
// Each Proc<S,M> ~1 KB heap, runs on virtual thread
// Spawn 1M processes without memory explosion
for (int i = 0; i < 1_000_000; i++) {
    Proc.spawn(...);
}
```

### Exhaustive Pattern Matching (Compiler-Enforced)
```java
// Sealed types ensure all cases covered at compile time
var state = switch(transition) {
    case Transition.Next(var s) -> s;
    case Transition.Keep() -> currentState;
    case Transition.Stop() -> throw new StopException();
    // Compiler error if you forget a case!
};
```

---

## 📦 Installation

### Maven
```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>2026.1.0</version>
</dependency>
```

### Gradle
```gradle
implementation 'io.github.seanchatmangpt:jotp:2026.1.0'
```

### Requirements
- **Java 26** with `--enable-preview` flag
- **Maven 4** or **mvnd** (Maven Daemon)

---

## 🎓 Getting Started

### 5-Minute Introduction
1. [Read QUICK_START.md](docs/user-guide/quick-start.md)
2. Run [ProcExample.java](src/main/java/io/github/seanchatmangpt/jotp/examples/ProcExample.java)
3. Try [SupervisorExample.java](src/main/java/io/github/seanchatmangpt/jotp/examples/SupervisorExample.java)

### Full Documentation
- 📚 [User Guide](docs/user-guide/) — 100+ pages
- 🏗️ [Architecture Guide](docs/ARCHITECTURE.md) — design decisions, OTP equivalence
- 🎯 [Pattern Library](docs/INTEGRATION-PATTERNS.md) — brownfield Spring Boot adoption, migrations
- 📊 [SLA & Operations](docs/SLA-PATTERNS.md) — monitoring, disaster recovery, runbooks
- 🔬 [PhD Thesis](docs/phd-thesis-otp-java26.md) — formal OTP ↔ Java 26 proofs

---

## 🗺️ What's Deferred to 2026.2

These components are **not included** in 2026.1.0, but planned for 2026.2:

- **Messaging Subsystem** (experimental) — typed message routing, channels, transformation
  - Rationale: Still refining design; not part of core 15 OTP primitives
  - Timeline: 2026.2 (3-6 months)

- **Enterprise Patterns** — saga coordination, multitenancy, circuit breaker, bulkhead, backpressure, health checks
  - Rationale: Optional patterns for large-scale systems; core OTP complete without them
  - Timeline: 2026.2+ (ongoing)

- **Connection Pooling** — resource reuse for database/HTTP connections
  - Rationale: Integrations require per-domain tuning; defer to user libraries
  - Timeline: 2026.2

These will ship in updates **without breaking core API**. 2026.1.0 stability guaranteed.

---

## 🔄 Migration Guide

**This is the first stable release.** No migrations needed.

If you used pre-release versions:
- API is stable as of 2026.1.0
- All 15 primitives semantics locked
- Future updates additive only (no breaking changes planned)

---

## 🐛 Known Limitations

1. **Java 26 Only** — Requires `--enable-preview`. When preview features stabilize (Java 27?), this flag can be removed.
2. **Network Dependency** — Maven Central is required for initial setup; once downloaded, fully offline-capable.
3. **No Legacy Support** — Designed for Java 26+. Backports to earlier Java versions out of scope.

---

## 📋 Testing & Quality

- **113 Core Tests** — Exhaustive coverage of all 15 primitives
- **Integration Tests** — Cross-component supervision, linking, monitoring
- **Property-Based Tests** — jqwik; message ordering, fault recovery invariants
- **Test Code Coverage** — 80%+ for production code (excluding dogfood)
- **Format Validation** — Spotless (Google Java Format AOSP)
- **Architecture Checks** — ArchUnit; no forbidden patterns (synchronized, ThreadLocal, mocks)

Run locally:
```bash
make test              # unit tests
make verify            # all tests + quality checks
make benchmark-quick   # JMH benchmarks (1 fork, 1 warmup, 2 iterations)
```

---

## 🔐 Security & Licensing

- **License**: Apache 2.0 (permissive, production-safe)
- **Dependencies**: All MIT, Apache 2.0, or BSD-compatible
- **Security Audit**: No embedded secrets, credentials, or private data
- **Public Source**: Full transparency on GitHub

---

## 🎉 Contributors

**Primary**: [seanchatmangpt](https://github.com/seanchatmangpt)

---

## 📞 Support & Community

- 📖 [Full Documentation](docs/)
- 🐛 [Issue Tracker](https://github.com/seanchatmangpt/jotp/issues)
- 💬 [Discussions](https://github.com/seanchatmangpt/jotp/discussions)
- 📧 Email: jotp@seanchatmangpt.dev (example)

---

## 🚦 Roadmap: 2026.2 & Beyond

### 2026.2 (Q2-Q3 2026)
- [ ] Messaging subsystem (pub-sub channels, routing)
- [ ] Enterprise patterns (saga, multitenancy, bulkhead)
- [ ] Connection pooling
- [ ] OpenTelemetry integration

### 2027.1+ (Year 2027)
- [ ] gRPC distributed actor communication
- [ ] Cloud-native patterns (Kubernetes operators)
- [ ] Advanced monitoring (metrics, tracing)

---

## 🙏 Acknowledgments

JOTP brings proven Erlang/OTP patterns to Java 26, leveraging:
- **Erlang/OTP** — 35+ years of battle-tested concurrency
- **Java 26** — Virtual threads, sealed types, pattern matching
- **Community** — Inspired by Akka, Vert.x, and reactive frameworks

---

**Version**: 2026.1.0
**Release Date**: March 17, 2026
**License**: Apache 2.0
**Repository**: https://github.com/seanchatmangpt/jotp
