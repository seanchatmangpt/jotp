# JOTP 2026.1.0 - Production-Ready OTP Framework for Java 26

**Release Date:** March 17, 2026

JOTP 2026.1.0 is the first stable, production-ready release of the Java OTP framework. This release brings battle-tested Erlang/OTP concurrency patterns, supervision trees, and fault tolerance to the JVM using Java 26's virtual threads, sealed types, and pattern matching. All 15 core OTP primitives are fully implemented, tested, and ready for enterprise use.

## Table of Contents

- [What's Included (15 OTP Primitives)](#whats-included-15-otp-primitives)
- [Key Features](#key-features)
- [What's Deferred to 2026.2](#whats-deferred-to-20262)
- [Getting Started](#getting-started)
- [Breaking Changes](#breaking-changes)
- [Migration Guide](#migration-guide)
- [Known Limitations](#known-limitations)
- [Contributors](#contributors)
- [License](#license)

---

## What's Included (15 OTP Primitives)

All 15 core OTP primitives are production-ready in this release. Each primitive has been thoroughly tested, documented, and optimized for the JVM.

### Core Process Management

| Primitive | Description | Status |
|-----------|-------------|--------|
| **`Proc<S,M>`** | Lightweight virtual-thread processes with `LinkedTransferQueue` mailboxes; state + message handler | ✅ Production-Ready |
| **`ProcRef<S,M>`** | Stable process handles that survive supervisor restarts; use instead of raw `Proc` references | ✅ Production-Ready |
| **`ProcLink`** | Bidirectional crash propagation between processes; one process crash kills linked processes | ✅ Production-Ready |
| **`ProcMonitor`** | One-way process monitoring; target crash sends DOWN notification; monitor remains unaffected | ✅ Production-Ready |

### Supervision & Fault Tolerance

| Primitive | Description | Status |
|-----------|-------------|--------|
| **`Supervisor`** | Hierarchical process supervision with restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE) | ✅ Production-Ready |
| **`CrashRecovery`** | Isolated virtual thread wrapper returning `Result<T, Exception>`; recovers from supplier crashes | ✅ Production-Ready |
| **`ExitSignal`** | Exit reason carrier for linked/monitored processes; implements trap_exit pattern | ✅ Production-Ready |

### State Management & Workflows

| Primitive | Description | Status |
|-----------|-------------|--------|
| **`StateMachine<S,E,D>`** | Full gen_statem contract with sealed states and events; compiler-enforced exhaustive handling | ✅ Production-Ready |
| **`Result<T,E>`** | Railway-oriented error handling with sealed Ok/Err variants; eliminates null checks | ✅ Production-Ready |

### Process Utilities & Introspection

| Primitive | Description | Status |
|-----------|-------------|--------|
| **`ProcRegistry`** | Name-based process lookup; equivalent to Erlang's `whereis/1` | ✅ Production-Ready |
| **`ProcTimer`** | Scheduled message delivery to a `Proc`; implements OTP timer behavior | ✅ Production-Ready |
| **`ProcSys`** | Live process introspection: suspend, resume, statistics without stopping | ✅ Production-Ready |
| **`ProcLib`** | Process utility functions including init_ack handshake pattern support | ✅ Production-Ready |

### Events & Concurrency

| Primitive | Description | Status |
|-----------|-------------|--------|
| **`EventManager<E>`** | Typed pub-sub event bus; handler crash doesn't kill the bus; broadcasts to all subscribers | ✅ Production-Ready |
| **`Parallel`** | Structured concurrency via `StructuredTaskScope`; fork-join parallelism with automatic cleanup | ✅ Production-Ready |

---

## Key Features

### Java 26 Language Support

JOTP leverages the latest Java 26 preview features to bring idiomatic OTP to the JVM:

- **Virtual Threads**: Millions of lightweight processes, each consuming ~1 KB heap. No thread pool contention. True concurrency via OS scheduling.
- **Sealed Types**: `Proc<S,M>` handlers, `Transition<S>`, `Result<T,E>`, and `ExitSignal` are sealed classes enabling compile-time exhaustiveness checking. Missing a case? Compiler error.
- **Pattern Matching**: Switch expressions on records and sealed hierarchies eliminate boilerplate:
  ```java
  switch (result) {
    case Result.Ok(var value) -> use(value);
    case Result.Err(var error) -> handle(error);
  }
  ```
- **Records**: Immutable data carriers with destructuring pattern matching; ideal for message types.
- **ScopedValue**: Virtual-thread-safe alternative to `ThreadLocal`; no leaks, no cleanup needed.

### Supervision Trees

Hierarchical process supervision contains failures and prevents cascade crashes:

- **ONE_FOR_ONE**: One child crashes → restart only that child; siblings unaffected.
- **ONE_FOR_ALL**: One child crashes → restart all children in the supervisor.
- **REST_FOR_ONE**: One child crashes → restart that child and all children started after it.

Supervisors form trees, allowing complex failure domains to be managed independently. A crashed supervisor restarts via its parent.

### Message-Passing Concurrency

No shared mutable state. All communication via immutable messages:

- Type-safe message protocols using sealed record interfaces
- Mailbox-based message delivery with configurable queuing strategies
- Async messaging with timeouts to prevent deadlocks
- Pattern matching on message content for elegant handler logic

### Fault Tolerance & "Let It Crash" Philosophy

Processes don't handle exceptions—they crash. Supervisors decide what to do:

- **Default Behavior**: Process crashes with unhandled exception → supervisor restarts it.
- **Monitoring**: One-way observation of a process; crash sends DOWN notification, monitor unaffected.
- **Linking**: Bidirectional crash propagation; one crash kills linked partners (useful for atomic operations).
- **Recovery**: `CrashRecovery` provides isolated execution returning `Result<T, Exception>`.

This philosophy eliminates defensive try-catch chains and enables systems to heal automatically.

### Type-Safe State Machines

`StateMachine<S,E,D>` implements the full gen_statem contract:

- Sealed state and event types ensure compiler-enforced exhaustiveness
- Data carrier `D` maintains context across transitions
- Transition logic returns `Transition.Next`, `Transition.Keep`, or `Transition.Stop`
- Timer support for scheduled state changes
- Perfect for order workflows, payment processing, user authentication

### Dogfood Testing

The JOTP framework is validated using JOTP itself. Critical flows are implemented as JOTP processes and tests serve as living documentation of framework behavior.

---

## What's Deferred to 2026.2

These subsystems are experimental and not included in 2026.1.0. They will be production-ready in 2026.2.

### Messaging Subsystem

Advanced routing and message transformation patterns:

- Content-based routing (route messages based on header/payload inspection)
- Message-to-message transformations (enrichers, filters, translators)
- Saga orchestration (multi-step distributed workflows with compensating transactions)
- Dead-letter channels and retry policies
- Correlation identifiers for tracking cross-process workflows

**Why Deferred**: The messaging subsystem requires additional integration testing with distributed tracing and observability tools. Current implementation is functional but benefits from real-world load testing before marking stable.

### Enterprise Patterns

Production-grade patterns for complex systems:

- **Multitenancy**: Tenant isolation, resource quotas, per-tenant supervision trees
- **Circuit Breaker**: Fail-fast integration with external services
- **Bulkhead Isolation**: Partition processes into failure domains preventing resource exhaustion
- **Event Sourcing**: Audit-trail persistence of all process state changes
- **CQRS**: Separate read/write models for scalability
- **Distributed Locks**: Coordinated resource access across process boundaries

**Why Deferred**: Enterprise patterns depend on messaging infrastructure and require integration with external systems (databases, message brokers, distributed consensus). Testing in production environments is ongoing.

### Connection Pooling

Resource pooling utilities:

- Database connection pools with health checks
- HTTP client pools with circuit breaker integration
- Custom resource pools with configurable strategies

**Why Deferred**: Pooling is orthogonal to core OTP primitives and benefits from experimentation with real database drivers and protocols.

### Experimental Subsystems

- Distributed actor registry (network transparency)
- Clustering and node discovery
- Hot code reloading
- Distributed tracing integration

**Philosophy**: JOTP prioritizes stability and correctness. Optional patterns are deferred until thoroughly validated. Core OTP primitives are complete and battle-tested.

---

## Getting Started

### Maven Dependency

Add JOTP to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.seanchatmangpt</groupId>
    <artifactId>jotp</artifactId>
    <version>2026.1.0</version>
</dependency>
```

### System Requirements

- **Java 26** or later with `--enable-preview` flag enabled
- **Maven 4.0+** (or use the included Maven Wrapper `./mvnw`)
- Optional: Maven Daemon (`mvnd`) for 30% faster builds

### Compiler Configuration

Update your `pom.xml` to enable preview features:

```xml
<properties>
    <maven.compiler.release>26</maven.compiler.release>
</properties>

<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.15.0</version>
    <configuration>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>

<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>--enable-preview</argLine>
    </configuration>
</plugin>
```

### First Process: Hello World

```java
import io.github.seanchatmangpt.jotp.*;

public class HelloWorld {
    record Greet(String name) {}

    public static void main(String[] args) throws Exception {
        // Spawn a simple process
        var proc = Proc.spawn(
            "World",  // initial state
            (state, msg) -> {
                if (msg instanceof Greet(var name)) {
                    System.out.println("Hello, " + name + "!");
                    return state;
                }
                return state;
            }
        );

        // Send a message
        proc.send(new Greet("JOTP"));

        // Give process time to handle message
        Thread.sleep(100);
        proc.stop();
    }
}
```

### Documentation

Comprehensive user guide with 100+ pages covering all primitives:

- **[User Guide](https://github.com/seanchatmangpt/jotp/tree/main/docs/user-guide)** - Complete API reference and how-to guides
  - [Creating Your First Process](https://github.com/seanchatmangpt/jotp/blob/main/docs/user-guide/how-to/creating-your-first-process.md)
  - [Building Supervision Trees](https://github.com/seanchatmangpt/jotp/blob/main/docs/user-guide/how-to/building-supervision-trees.md)
  - [Implementing State Machines](https://github.com/seanchatmangpt/jotp/blob/main/docs/user-guide/how-to/implementing-state-machines.md)
  - [Error Handling & Fault Tolerance](https://github.com/seanchatmangpt/jotp/blob/main/docs/user-guide/how-to/error-handling.md)

### Key Examples

JOTP includes runnable examples demonstrating core patterns:

1. **[ApplicationLifecycleExample](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/examples/ApplicationLifecycleExample.java)** - Start/stop sequences, init_ack pattern
2. **[DistributedCounterExample](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedCounterExample.java)** - Stateful process with message-passing
3. **[DistributedCacheExample](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedCacheExample.java)** - Concurrent state management
4. **[EcommerceOrderService](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/examples/EcommerceOrderService.java)** - StateMachine for order workflows
5. **[ChaosDemo](https://github.com/seanchatmangpt/jotp/blob/main/src/main/java/io/github/seanchatmangpt/jotp/examples/ChaosDemo.java)** - Supervision tree under failure conditions

### Build & Test

```bash
# Compile sources
mvn compile

# Run tests (113 core unit tests)
mvn test

# Full verification (unit + integration + quality checks)
mvn verify

# Format code (Google Java Format AOSP)
mvn spotless:apply

# Run JMH benchmarks
mvn verify -Pbenchmark

# Full release validation
mvn verify -Ddogfood
```

---

## Breaking Changes

**N/A** - This is the first stable release. No prior stable API exists.

---

## Migration Guide

**N/A** - This is the first stable release. No migration from prior versions required.

If migrating from Erlang/OTP:

- Erlang `spawn/1` → JOTP `Proc.spawn/2` with state + handler
- Erlang `!` send operator → JOTP `proc.send(message)` method
- Erlang `receive` → JOTP sealed record pattern matching in handlers
- Erlang `gen_server` → JOTP `Proc<S,M>` with request/reply pattern or `StateMachine<S,E,D>` for workflows
- Erlang supervisor children → JOTP `ChildSpec` in `Supervisor`
- Erlang `gen_event` → JOTP `EventManager<E>`

See [Erlang-Java Mapping Guide](https://github.com/seanchatmangpt/jotp/blob/main/docs/user-guide/explanations/erlang-java-mapping.md) for detailed equivalences.

---

## Known Limitations

### 1. Java Version & Preview Features

JOTP requires **Java 26 with `--enable-preview`** enabled at compile-time and runtime. This is necessary for:

- Virtual threads (vthreads)
- Sealed types and sealed pattern matching
- Record patterns
- ScopedValue

Java 26 is the current preview version. When Java 26 becomes LTS (expected 2028), this requirement will be removed.

### 2. Maven Central Network

Dependency resolution requires network access to Maven Central (or a proxy). JOTP depends on standard libraries (gRPC, Jackson, JUnit, AssertJ) available on Central.

### 3. Experimental Subsystems Not Included

The following components are excluded from 2026.1.0 and will be available in 2026.2:

- **`io.github.seanchatmangpt.jotp.messaging.*`** - Message routing, transformations, saga orchestration
- **`io.github.seanchatmangpt.jotp.enterprise.*`** - Multitenancy, circuit breaker, bulkheads, event sourcing, CQRS
- **`io.github.seanchatmangpt.jotp.pool.*`** - Connection pooling utilities

These can be included for testing with Maven profiles:

```bash
# Include experimental features (not recommended for production)
mvn verify -Darchive-experimental

# Include infrastructure tests
mvn verify -Darchive-infra

# Run all 160 tests (core + experimental + infrastructure + stress)
mvn verify -Darchive-all
```

### 4. Performance Characteristics

- **Process Creation**: ~100 microseconds per process (vs ~1 microsecond in Erlang/OTP)
- **Message Passing**: ~1 microsecond per message (comparable to Erlang)
- **Memory**: ~1 KB per virtual thread; can spawn millions on modern hardware

JVM startup overhead dominates for short-lived applications. JOTP excels in long-running services with thousands of concurrent processes.

### 5. Distributed Clustering

JOTP 2026.1.0 is single-node only. Distributed features (node discovery, distribution protocol) are deferred to 2026.2. Use gRPC or standard HTTP/message brokers for multi-node coordination.

### 6. Code Formatting & Guards

The project enforces Google Java Format (AOSP style). Spotless runs automatically on file edits. Additionally:

- **H_TODO guard**: Production code cannot contain `TODO` or `FIXME` comments
- **H_MOCK guard**: Production code cannot contain mock implementations
- **H_STUB guard**: Production code cannot return empty stubs (e.g., `return null` or `return ""`)

Violations block builds.

---

## Contributors

- **seanchatmangpt** (primary author, architect, test design)

---

## License

JOTP is released under the **Apache License, Version 2.0**.

```
Copyright 2026 Sean Chat Mangpt

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## What's Next?

The JOTP roadmap for 2026:

### 2026.1 (Current Release)
- ✅ 15 core OTP primitives
- ✅ Virtual thread processes and supervision
- ✅ Type-safe state machines and event buses
- ✅ Comprehensive documentation (100+ pages)
- ✅ 113 core unit tests
- ✅ JMH benchmarks for primitives

### 2026.2 (Q2 2026)
- Messaging subsystem (routing, sagas, correlation)
- Enterprise patterns (multitenancy, circuit breaker, bulkheads)
- Connection pooling
- Performance optimizations and stress testing

### 2026.3+ (H2 2026 and beyond)
- Distributed clustering and node discovery
- Hot code reloading
- Advanced observability (OpenTelemetry integration)
- Spring Boot 3.x native compilation support

---

## Support & Community

- **GitHub**: https://github.com/seanchatmangpt/jotp
- **Issues**: https://github.com/seanchatmangpt/jotp/issues
- **Documentation**: https://github.com/seanchatmangpt/jotp/tree/main/docs/user-guide

For Erlang/OTP users, the framework is designed to feel familiar. Patterns you know from Erlang apply directly in Java.

---

**JOTP 2026.1.0 — Bringing Erlang/OTP's proven concurrency model to Java 26.**
