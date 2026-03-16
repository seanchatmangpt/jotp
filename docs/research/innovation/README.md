# JOTP Innovation Hub

This directory contains cutting-edge innovation specifications that extend JOTP's OTP primitives into new domains. Each innovation applies Erlang/OTP's battle-tested patterns to solve modern infrastructure problems with structural correctness guarantees.

## Innovation Matrix

| Innovation | Domain | Status | Complexity | Impact |
|------------|--------|--------|------------|--------|
| **OTP-Native JDBC** | Database Connectivity | Proposal | Medium | High |
| **LLM Inference Supervisor** | AI/ML Infrastructure | Specification | High | Very High |
| **Actor-Per-Request HTTP** | Web Servers | Specification | Medium | High |
| **Distributed OTP Supervisor** | Distributed Systems | Proposal | Very High | Very High |
| **Event Sourcing** | Data Architecture | Specification | Medium | High |
| **Turtle Composition** | Application Generation | Implemented | Low | Medium |

## Directory Structure

```
docs/innovation/
├── infrastructure/          # OTP applied to infrastructure components
│   ├── otp-jdbc/           # Connection pool that cannot leak
│   ├── llm-supervisor/     # Supervised GPU inference workers
│   └── actor-http/         # Actor-per-request HTTP server
├── distributed/            # Distributed systems patterns
│   ├── cluster-supervisor/ # Location-transparent distributed actors
│   └── event-sourcing/     # Event-sourced aggregates with OTP
├── platform/               # Application composition and generation
│   └── turtle-composition/ # Complete apps from Turtle specs
└── status/                 # Implementation tracking and roadmap
```

## Key Themes

### 1. Structural Correctness Over Convention

Every innovation makes failure modes **structurally impossible** rather than merely discouraged:
- **OTP-JDBC**: Connection leaks are prevented by the actor model, not documentation
- **Actor-HTTP**: ThreadLocal leakage is impossible because there are no thread pools
- **LLM Supervisor**: GPU crashes are isolated to one shard, not the entire serving process

### 2. Zero New Concepts for Developers

The distributed layer requires learning exactly three facts:
1. `ActorRegistry registry = new ActorRegistry(clusterConfig)`
2. `registry.lookup("name")` returns a `DistributedActorRef`
3. `registry.lookupReplicated("name")` returns a `DistributedStateActor`

Everything else — `tell()`, `ask()`, `Supervisor`, `CrashRecovery` — is unchanged.

### 3. Virtual Threads as the Missing Primitive

Java 25's virtual threads (Project Loom) enable Erlang's process model on the JVM:
- **Per-actor virtual threads**: ~2 KB heap vs 512 KB for platform threads
- **Blocking-safe I/O**: Virtual threads unmount on blocking, no carrier thread pinning
- **Millions of concurrent processes**: Tested to 10M+ virtual threads

### 4. Railway-Oriented Error Handling

All innovations use `Result<T,E>` for explicit error handling:
```java
Result<List<Row>, SqlException> rows = conn.query(sql, params);
rows.map(list -> list.stream().map(Row::toUserDto).toList())
    .peekError(ex -> metrics.increment("db.query.error"))
    .orElseThrow();
```

## Implementation Status

### Completed ✅
- **Turtle Application Composition**: Full code generation from Turtle specs

### In Progress 🚧
- **Event Sourcing**: Specification complete, awaiting implementation
- **Actor-Per-Request HTTP**: Specification complete, prototype phase

### Proposed 📋
- **OTP-Native JDBC**: Full specification, seeking implementation team
- **LLM Inference Supervisor**: Specification v1.0, hardware validation needed
- **Distributed OTP Supervisor**: Design complete, UDP transport prototype available

## Getting Started

1. **For Decision Makers**: Read the executive summaries in each innovation's README
2. **For Architects**: Review the full specifications for design patterns
3. **For Developers**: Start with Turtle Composition for immediate code generation
4. **For Researchers**: Explore the Blue Ocean analysis in each specification

## Contributing

Each innovation is a standalone specification that can be implemented independently. To contribute:

1. Review the specification in the relevant directory
2. Check `status/` for current implementation roadmap
3. Open an issue or PR referencing the innovation number

## References

- Joe Armstrong, *Programming Erlang* — OTP design philosophy
- Vaughn Vernon, *Reactive Messaging Patterns* — Enterprise integration patterns
- JEP 444 — Virtual Threads (Project Loom)
- JEP 454 — Foreign Function & Memory API

---

**Last Updated**: 2026-03-15
**Maintainer**: JOTP Architecture Working Group
