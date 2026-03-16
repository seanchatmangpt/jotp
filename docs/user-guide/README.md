# JOTP User Guide

Welcome to the JOTP User Guide! This section provides comprehensive documentation for using the JOTP framework.

## Getting Started

JOTP is a production-ready Java 26 framework implementing all 15 OTP (Erlang/OTP) primitives, bringing battle-tested concurrency patterns to the JVM using virtual threads, sealed types, and pattern matching.

### Quick Start
```java
// Create a simple process
Proc<String, String> proc = Proc.spawn(
    "hello-process",
    "initial-state",
    (msg, state) -> {
        System.out.println("Received: " + msg);
        return state; // Return new state
    }
);

// Send a message
proc.cast("Hello, JOTP!");
```

## Documentation Structure

### [How-To Guides](./how-to/)
Practical, step-by-step guides for common tasks:
- **[Creating Your First Process](./how-to/creating-your-first-process.md)** - Get started with JOTP
- **[Process Communication](./how-to/process-communication.md)** - Send and receive messages
- **[Building Supervision Trees](./how-to/build-supervision-trees.md)** - Create fault-tolerant hierarchies
- **[State Machine Workflows](./how-to/state-machine-workflow.md)** - Implement complex workflows
- **[Testing JOTP Processes](./how-to/testing-jotp-processes.md)** - Test concurrent code effectively
- **[Spring Boot Integration](./how-to/spring-boot-integration.md)** - Use JOTP with Spring Boot
- **[Migrating from Erlang](./how-to/migrate-from-erlang.md)** - Transition from Erlang/OTP
- **[Performance Tuning](./how-to/performance-tuning.md)** - Optimize your JOTP applications

### [Explanations](./explanations/)
In-depth technical explanations:
- **[Architecture Overview](./explanations/architecture-overview.md)** - System design and components
- **[Concurrency Model](./explanations/concurrency-model.md)** - Virtual threads and beyond
- **[Let It Crash Philosophy](./explanations/let-it-crash-philosophy.md)** - Embracing failure
- **[OTP Equivalence](./explanations/otp-equivalence.md)** - Java 26 ≡ Erlang/OTP
- **[Erlang-Java Mapping](./explanations/erlang-java-mapping.md)** - Concept translation guide
- **[Design Decisions](./explanations/design-decisions.md)** - Architecture trade-offs

### [Examples](./examples/)
Working code examples:
- **[Basic Process Example](./examples/basic-process-example.md)** - Simple process creation
- **[Message Passing Example](./examples/message-passing-example.md)** - Communication patterns
- **[Supervision Tree Example](./examples/supervision-tree-example.md)** - Fault tolerance in action

### [Troubleshooting](./troubleshooting/)
Common issues and solutions:
- **[Diagnostic Checklist](./troubleshooting/diagnostic-checklist.md)** - Systematic problem-solving
- Debugging techniques
- Performance issues
- Common pitfalls

## Core Concepts

### Processes
Lightweight virtual-thread processes with mailboxes (~3.9 KB heap each, validated at 1M+ processes):
```java
Proc<S, M> proc = Proc.spawn(name, initialState, handler);
```

### Supervision
Hierarchical process supervision with restart strategies:
```java
Supervisor.supervisor(children, Supervisor.Strategy.ONE_FOR_ONE);
```

### State Machines
Complex workflows with sealed transitions:
```java
StateMachine<S, E, D> fsm = StateMachine.create(initialState, transitions);
```

### Message Passing
Type-safe, immutable message communication:
```java
proc.cast(message);           // Fire-and-forget
proc.reply(request, timeout); // Request-reply
```

## Learning Path

### Beginner
1. Read [Creating Your First Process](./how-to/creating-your-first-process.md)
2. Try the [Basic Process Example](./examples/basic-process-example.md)
3. Learn about [Process Communication](./how-to/process-communication.md)

### Intermediate
4. Understand [Supervision Trees](./how-to/build-supervision-trees.md)
5. Explore [State Machines](./how-to/state-machine-workflow.md)
6. Study [Architecture](./explanations/architecture-overview.md)

### Advanced
7. Master [Testing Concurrent Code](./how-to/testing-jotp-processes.md)
8. Learn [Performance Tuning](./how-to/performance-tuning.md)
9. Integrate with [Spring Boot](./how-to/spring-boot-integration.md)

## Reference Documentation

- **[Javadoc](https://javadoc.io/doc/io.github.seanchatmangpt/jotp)** - API reference
- **[Research](../research/)** - Academic foundations
- **[Roadmap](../roadmap/)** - Implementation plans
- **[Validation](../validation/)** - Test results

## Community

- **GitHub Issues**: Bug reports and feature requests
- **Discussions**: Questions and conversations
- **Contributing**: Pull requests welcome

## Requirements

- **Java 26** with preview features (`--enable-preview`)
- **Maven 4** (or included wrapper)
- Optional: Maven Daemon (`mvnd`) for faster builds

## Support

Need help?
1. Check the [troubleshooting guide](./troubleshooting/)
2. Search [existing issues](https://github.com/seanchatmangpt/jotp/issues)
3. Ask in [GitHub Discussions](https://github.com/seanchatmangpt/jotp/discussions)

---

**Next**: Start with [Creating Your First Process](./how-to/creating-your-first-process.md)
