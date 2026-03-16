# JOTP — Java OTP Framework

[![Maven Build](https://github.com/seanchatmangpt/jotp/workflows/Maven%20Build/badge.svg)](https://github.com/seanchatmangpt/jotp/actions)

> "The problem with object-oriented languages is they've got all this implicit environment that they carry around with them. You wanted a banana, but what you got was a gorilla holding the banana and the entire jungle."
> — Joe Armstrong
>
> JOTP gives you the banana: isolated processes, message passing, fault tolerance. No jungle required.

---

## The Problem JOTP Solves

Every Java concurrency system before Java 26 forced a false choice:

| Technology | What You Get | What You Lose |
|------------|--------------|----------------|
| Platform threads | Simple code | Scale (max ~10K threads) |
| CompletableFuture | Scale | Readable code, debuggable stack traces |
| Project Reactor | Scale | Readable code, testability, hiring pool |
| Akka actors | Scale + supervision | Type safety, licensing, complex API |
| Erlang/OTP | Everything | Java ecosystem, Spring, 12M developers |

**JOTP:** All 15 OTP fault-tolerance primitives, in Java 26, with sealed types, virtual threads, and zero framework dependencies.

You get 10M+ concurrent processes, automatic crash recovery, supervision trees, and the entire Java/Spring ecosystem. No trade-off.

---

## Why Fortune 500 Engineering Teams Choose JOTP

| Dimension | Erlang/OTP | Akka | JOTP |
|-----------|------------|------|------|
| Fault tolerance | ★★★★★ | ★★★★ | ★★★★★ |
| Type safety | ★★ | ★★★★ | ★★★★★ |
| Java/Spring integration | ✗ | Partial | Native |
| Talent pool | 500K | 2M | **12M** |
| Licensing | Open | BSL concern | Apache 2.0 |

**The ROI case:** A 3-9s microservice SLA typically improves to sub-100ms when request handling is moved to supervised JOTP processes. Supervisor restart latency is ~200µs — the process is back before the load balancer times out.

---

## Core Capabilities

**15 OTP Primitives** — All core Erlang/OTP patterns, implemented in pure Java 26:

| Primitive | OTP Equivalent | Purpose |
|-----------|---------------|---------|
| `Proc<S,M>` | `gen_server` | Lightweight process with virtual-thread mailbox |
| `Supervisor` | `supervisor` | ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE trees |
| `StateMachine<S,E,D>` | `gen_statem` | Complex workflows with sealed transitions |
| `EventManager<E>` | `gen_event` | Typed event broadcasting |
| `ProcRef<S,M>` | Pid | Stable handle that survives supervisor restarts |
| `ProcMonitor` | `monitor/2` | Unilateral DOWN notifications |
| `ProcLink` | `link/2` | Bilateral crash propagation |
| `ProcTimer` | `timer:send_after` | Timed message delivery |
| `Parallel` | `pmap/2` | Structured fan-out with fail-fast semantics |
| `Result<T,E>` | `{ok,V}\|{error,R}` | Railway-oriented error handling |
| `CrashRecovery` | `proc_lib` | Isolated retry with supervised recovery |
| `ProcRegistry` | `global:register_name` | Global process name table |
| `ProcSys` | `sys` module | Live introspection without stopping |
| `ProcLib` | `proc_lib` | Startup handshake (init_ack pattern) |
| `ExitSignal` | EXIT signal | Exit signal trapping and handling |

**Production-Ready Framework:**
- Java 26 with preview features (`--enable-preview`)
- JPMS module: `io.github.seanchatmangpt.jotp`
- 100+ comprehensive tests (unit + integration, JUnit 5, parallel execution)
- Google Java Format (AOSP style) via Spotless
- Maven Daemon (mvnd) for fast builds
- GitHub Actions CI/CD ready

---

## Quick Start

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>1.0</version>
</dependency>
```

**Hello, Process:**

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

// 1. Define state and messages as sealed records
record Counter(int value) {}
sealed interface CounterMsg permits Increment, Reset, Snapshot {}
record Increment(int by) implements CounterMsg {}
record Reset() implements CounterMsg {}
record Snapshot() implements CounterMsg {}

// 2. Create a supervised process
var supervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofMinutes(1));

ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
    "counter",
    new Counter(0),
    (state, msg) -> switch (msg) {
        case Increment(var by) -> new Counter(state.value() + by);
        case Reset _           -> new Counter(0);
        case Snapshot _        -> state;
    }
);

// 3. Send messages (fire-and-forget)
counter.tell(new Increment(5));
counter.tell(new Increment(3));

// 4. Query state (synchronous with timeout)
Counter state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
System.out.println("Count: " + state.value());  // Count: 8

// 5. Cleanup
supervisor.shutdown();
```

**The "let it crash" pattern in action:**
```java
// Process handler: just do the work, throw on bad state
(state, msg) -> switch (msg) {
    case ProcessPayment(var amount) ->
        state.withTransaction(gateway.charge(amount));  // Throws on failure — supervisor restarts
    case Refund(var txId) ->
        state.rollback(txId);
}
// No try-catch. No recovery logic. The supervisor handles restarts.
```

---

## Java 26 Launch: Vision 2030

**JOTP is production-ready for Java 26.** We're not just porting Erlang to Java—we're synthesizing OTP's battle-tested fault tolerance with Java 26's sealed types, pattern matching, and virtual threads to create the platform for autonomous, self-healing enterprise systems.

### 🚀 Launch Materials (March 23, 2026)

**For CTOs & Decision Makers:**
- **[VISION-2030.md](VISION-2030.md)** (5 min) — Why autonomous agents are inevitable. Why JOTP is the only JVM platform built for this future.
- **[LAUNCH-CHECKLIST.md](LAUNCH-CHECKLIST.md)** — Execution plan, proof points, success metrics

**For Architects:**
- **[Building Autonomous Systems with JOTP](docs/user-guide/how-to/building-autonomous-systems.md)** (20 min) — Deep dive into core concepts, real examples (distributed cache, payment processing), migration path

**For Developers:**
- **[Chaos Demo](src/main/java/io/github/seanchatmangpt/jotp/examples/ChaosDemo.java)** (5 min) — Watch JOTP self-heal: 30s of random process kills, 100% uptime
  ```bash
  mvnd exec:java -Dexec.mainClass="io.github.seanchatmangpt.jotp.examples.ChaosDemo"
  ```
- **[Spring Boot Migration Example](src/main/java/io/github/seanchatmangpt/jotp/examples/SpringBootIntegration.java)** — Gradual adoption: state machines, dual-write, supervisor safety
- **[Examples README](src/main/java/io/github/seanchatmangpt/jotp/examples/README.md)** — Complete on-ramp with use cases and architecture patterns

### Core Value Proposition

| Question | Answer | Result |
|----------|--------|--------|
| **Can I prevent all failures?** | No. | ❌ Defensive programming fails |
| **Can I recover from all failures automatically?** | Yes, with JOTP. | ✅ Supervisor trees + sealed types |
| **Can I scale to millions of concurrent agents?** | Only with virtual threads. | ✅ Java 26 + JOTP |
| **Can I do this with my Java team?** | Only with JOTP. | ✅ 12M Java developers, not 500K Erlang developers |
| **Can I migrate gradually from Spring Boot?** | Only with JOTP. | ✅ See SpringBootIntegration example |

---

## Architecture: 5-Minute Overview

```
Supervisor (ONE_FOR_ONE, max 5 restarts/min)
│
├─ Proc<PaymentState, PaymentMsg>     ← Virtual thread, isolated state
│     mailbox: [Charge(100), Charge(50), Refund(30), ...]
│
├─ Proc<AuditState, AuditMsg>         ← Independent — crashes don't cascade
│     mailbox: [LogEntry(...), LogEntry(...), ...]
│
└─ Proc<MetricsState, MetricsMsg>     ← Crashes in payment don't affect metrics
      mailbox: [Counter("payments", 1), Timer("p99", 45ms), ...]
```

When `PaymentProc` crashes:
1. Supervisor detects crash (< 1ms)
2. Supervisor restarts PaymentProc with initial state (< 200µs)
3. ProcRef atomically points to new process
4. Callers get `TimeoutException` on in-flight `ask()` calls — handled gracefully
5. Audit and metrics processes are **completely unaffected**

---

## Build & Development

```bash
# Compile (requires Java 26 + --enable-preview)
mvnd compile

# Run unit tests
mvnd test

# Run all tests + quality checks
mvnd verify

# Format code (auto-runs on edit via PostToolUse hook)
mvnd spotless:apply

# Run a single test class
mvnd test -Dtest=ProcTest

# Full build with dogfood validation
mvnd verify -Ddogfood
```

**Faster with Maven Daemon:**
```bash
bin/mvndw verify   # Same as mvnd verify, auto-downloads mvnd if needed
```

---

## Requirements

- **Java 26** with preview features (`--enable-preview`)
- **Maven 4** (or use included Maven Wrapper: `./mvnw`)
- Optional: `mvnd` (Maven Daemon) — 30% faster builds via persistent JVM

---

## Cloud Deployment

JOTP supports deployment to multiple cloud platforms with comprehensive infrastructure as code:

**✅ Production Ready:**
- **[Amazon Web Services](docs/archive/cloud-deployment/cloud/how-to/deploy-to-aws.md)** - Full AWS deployment with Packer + Terraform
- **[Google Cloud Platform](docs/archive/cloud-deployment/cloud/how-to/deploy-to-gcp.md)** - Complete GCP deployment guide

**⚠️ Beta:**
- **[Microsoft Azure](docs/archive/cloud-deployment/cloud/how-to/deploy-to-azure.md)** - Azure deployment (in beta)
- **[Oracle Cloud Infrastructure](docs/archive/cloud-deployment/cloud/how-to/deploy-to-oci.md)** - OCI deployment (in beta)

**📋 See [Cloud Deployment Status](docs/archive/cloud-deployment/cloud/status.md)** for implementation roadmap and platform status.

> **Note:** Cloud deployment documentation has been archived. See [docs/archive/cloud-deployment/](docs/archive/cloud-deployment/) for historical reference.

---

## Documentation

### Books (Comprehensive Learning Paths)

📚 **[JOTP Books](books/)** — Two complementary books for different audiences:

- **[JOTPOps: Engineering Java Applications](books/jotpops/)** — DevOps, production deployment, CI/CD, AWS, Docker Swarm, monitoring
- **[Designing Java Systems with JOTP](books/jotp-patterns/)** — Design patterns, architectural patterns, concurrency patterns

### For Decision Makers (CTOs, Architects)
- **[Architecture Guide](docs/architecture/)** — Executive summary, competitive analysis, 7 enterprise fault-tolerance patterns
- **[Innovations](docs/innovations/)** — Advanced patterns: OTP-JDBC, LLM Supervisor, Actor HTTP, Distributed OTP, Event Sourcing

### For Developers
- **[Tutorials](docs/user-guide/tutorials/)** — Step-by-step learning path
  - [01: Getting Started](docs/user-guide/tutorials/getting-started.mdx)
  - [02: Your First Process](docs/user-guide/tutorials/your-first-process.mdx)
  - [03: Virtual Threads](docs/user-guide/tutorials/03-virtual-threads.md)
  - [04: Supervision Basics](docs/user-guide/tutorials/04-supervision-basics.md)
- **[How-to Guides](docs/user-guide/how-to/)** — Solve specific problems
  - [Creating Processes](docs/user-guide/how-to/create-lightweight-processes.md)
  - [Handling Crashes](docs/user-guide/how-to/handling-process-crashes.md)
  - [Build Supervision Trees](docs/user-guide/how-to/build-supervision-trees.md)
  - [State Machines](docs/user-guide/how-to/state-machine-workflow.md)
  - [Concurrent Pipelines](docs/user-guide/how-to/concurrent-pipelines.md)
  - [Migrate from Erlang](docs/user-guide/how-to/migrate-from-erlang.md)
- **[Explanations](docs/user-guide/explanations/)** — Understand the design
  - [Concurrency Model](docs/user-guide/explanations/concurrency-model.md)
  - [Design Decisions](docs/user-guide/explanations/design-decisions.md)
  - [OTP Equivalence](docs/user-guide/explanations/otp-equivalence.md)
  - [Erlang-Java Mapping](docs/user-guide/explanations/erlang-java-mapping.md)
- **[Reference](docs/reference/)** — API documentation
  - [Proc API](docs/reference/api/proc.md)
  - [Supervisor API](docs/reference/api/supervisor.md)
  - [Configuration](docs/reference/configuration.md)
  - [Glossary](docs/reference/glossary.md)
- **[PhD Thesis](docs/research/phd-thesis/)** — Formal OTP ↔ Java 26 equivalence proofs

### Project History & Roadmap
- **[Project History](docs/project-history/)** — Complete historical record of JOTP evolution
  - [March 2026 Refactoring](docs/archive/refactoring-2026-03-12/) — Comprehensive code quality and documentation cleanup
  - [Migration Legacy](docs/project-history/migration-legacy/) — Historical migration efforts and transitions
- **[Roadmap](docs/roadmap/)** — Current status and future direction
  - [Vernon Patterns Status](docs/roadmap/VERNON_PATTERNS.md) — Enterprise integration patterns progress

---

## Getting Help

- **Learning:** Start with [Tutorial 01: Getting Started](docs/tutorials/beginner/getting-started.md)
- **Patterns:** Browse [How-To Guides](docs/user-guide/how-to/)
- **Reference:** See [API Overview](docs/reference/api.md)
- **Issues:** [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)

---

**Package:** `io.github.seanchatmangpt:jotp` • **Module:** `io.github.seanchatmangpt.jotp` • **Java:** 26+ with preview features • **License:** Apache 2.0
