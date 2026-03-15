# JOTP Documentation

Complete documentation for JOTP - Java 26 implementation of Erlang/OTP primitives bringing battle-tested concurrency patterns, supervision trees, and fault tolerance to the JVM.

## Quick Navigation

### 📚 [User Guide](user-guide/)
**Main documentation for using JOTP in production**
- [Getting Started](user-guide/README.md) - Quick start guide and installation
- [Architecture](user-guide/architecture/) - System design and component overview
- [Patterns](user-guide/patterns/) - Enterprise integration patterns with JOTP
- [Tutorials](user-guide/tutorials/) - Step-by-step tutorials
- [API Reference](user-guide/reference/) - Complete API documentation
- [Examples](user-guide/examples/) - Working code examples

### 🔬 [Research](research/)
**Academic foundation and theoretical background**
- [PhD Thesis](research/phd-thesis/) - Formal OTP ↔ Java 26 equivalence proofs
- [Academic Papers](research/academic/) - Peer-reviewed research
- [Innovation](research/innovations/) - Cutting-edge JOTP applications
- [Analysis Reports](research/reports/) - Technical analysis and findings

### 🗺️ [Roadmap](roadmap/)
**Implementation planning and future work**
- [Implementation](roadmap/implementation/) - Active development plans
- [OTP28 Implementation](roadmap/OTP28-Implementation-Guide.md) - OTP 28 feature parity
- [Refactoring Plans](roadmap/REFACTORING-PLAN.md) - Code improvement initiatives
- [Vernon Patterns](roadmap/VERNON_PATTERNS.md) - Enterprise pattern integration

### ✅ [Validation](validation/)
**Production evidence and testing**
- [Case Studies](validation/) - Real-world production deployments
- [Performance](validation/performance/) - Benchmarks and regression analysis
- [Test Results](validation/) - Comprehensive test coverage reports
- [Atlas API](validation/atlas-api-test-results.md) - McLaren case study

### 🛠️ [Infrastructure](infrastructure/)
**Development tooling and CI/CD**
- [Testing](infrastructure/testing/) - Test architecture and utilities
- [CI/CD](infrastructure/) - GitHub Actions workflows
- [Tooling](infrastructure/tooling/) - Development tools and scripts
- [Diagrams](infrastructure/diagrams/) - Architecture diagrams

### 📦 [Books](books/)
**Comprehensive learning resources**
- [JOTP Patterns](../books/jotp-patterns/) - 50+ patterns in depth
- [JOTP in Production](../books/jotpops/) - Operations and DevOps guide

### 🗄️ [Archive](archive/)
**Historical and deprecated content**
- [Project History](archive/project-history/) - Development milestones
- [Migration Guides](archive/migration/) - Framework migration patterns
- [Release Notes](archive/release/) - Historical release information
- [Legacy](archive/legacy/) - Archived documentation

## Key Concepts

### Core Primitives

JOTP implements 15 Erlang/OTP primitives for the JVM:

1. **Proc<S,M>** - Lightweight virtual-thread processes with mailboxes
2. **Supervisor** - Hierarchical process supervision with restart strategies
3. **StateMachine<S,E,D>** - Complex workflows with sealed transitions
4. **EventManager<E>** - Typed event broadcasting
5. **ProcRef<S,M>** - Stable handles surviving supervisor restarts
6. **ProcMonitor** - Unilateral DOWN notifications
7. **ProcLink** - Bilateral crash propagation
8. **ProcTimer** - Timed message delivery
9. **Parallel** - Structured fan-out with fail-fast semantics
10. **Result<T,E>** - Railway-oriented error handling
11. **CrashRecovery** - Isolated retry with supervised recovery
12. **ProcRegistry** - Global process name table
13. **ProcSys** - Live introspection without stopping
14. **ProcLib** - Startup handshake (init_ack pattern)
15. **ExitSignal** - Exit signal trapping and handling

### Design Principles

1. **Let It Crash** - Processes don't handle exceptions; supervisors restart them
2. **Message Passing** - No shared state; communicate via immutable messages
3. **Supervision Trees** - Hierarchical restart strategies contain failures
4. **Virtual Threads** - Millions of lightweight processes (~1 KB heap each)
5. **Sealed Types** - Type-safe message protocols at compile time

## Getting Started

### Prerequisites

- **Java 26** with preview features enabled (`--enable-preview`)
- **Maven 4** (or use included Maven Wrapper: `./mvnw`)
- Optional: `mvnd` (Maven Daemon) for 30% faster builds

### Quick Start

```bash
# Clone repository
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp

# Build project
./mvnw compile

# Run tests
./mvnw test

# Run example
./mvnw exec:java -Dexec.mainClass="io.github.seanchatmangpt.jotp.examples.HelloWorld"
```

### First Process

```java
import io.github.seanchatmangpt.jotp.Proc;
import static io.github.seanchatmangpt.jotp.Proc.tell;

// Define message protocol
sealed interface Message permits SayHello {}
record SayHello(String name) implements Message {}

// Start a process
Proc<Void, Message> greeter = Proc.spawn(
    initial -> (state, msg) -> {
        if (msg instanceof SayHello(String name)) {
            System.out.println("Hello, " + name + "!");
        }
        return state;
    }
);

// Send message
tell(greeter, new SayHello("World"));
```

## Documentation Structure

This documentation is organized by purpose and audience:

### By Audience

**Developers using JOTP** → Start with [User Guide](user-guide/)
- Learn core concepts
- Explore patterns
- Build applications

**Architects evaluating JOTP** → Review [Research](research/) and [Validation](validation/)
- Understand theoretical foundation
- Review production evidence
- Assess performance characteristics

**Contributors to JOTP** → Study [Roadmap](roadmap/) and [Infrastructure](infrastructure/)
- Current development priorities
- Test architecture
- Contribution guidelines

**Researchers** → Explore [Research](research/)
- Academic papers
- Formal proofs
- Innovation opportunities

### By Lifecycle

1. **Learning** → [User Guide](user-guide/) → [Tutorials](user-guide/tutorials/) → [Books](../books/)
2. **Implementing** → [Patterns](user-guide/patterns/) → [Examples](user-guide/examples/) → [API Reference](user-guide/reference/)
3. **Deploying** → [Infrastructure](infrastructure/) → [Validation](validation/) → [Archive/Migration](archive/migration/)
4. **Contributing** → [Roadmap](roadmap/) → [Research](research/) → [Infrastructure/Testing](infrastructure/testing/)

## Conventions

- **Message Types**: Use sealed interfaces of records for type-safe pattern matching
- **State**: Keep immutable (records, sealed classes, or value types)
- **Error Handling**: Use `Result<T,E>` for railway-oriented programming
- **Processes**: Prefer `spawn()` factory over constructor for new code
- **Supervisors**: Use structured ChildSpec for complex scenarios
- **Timeouts**: Always use timeouts for `ask()` calls to avoid deadlocks

## Build System

```bash
# Compile (requires Java 26 with --enable-preview)
mvnd compile

# Run unit tests
mvnd test

# Run all tests + quality checks
mvnd verify

# Format code (runs automatically on edit via hook)
mvnd spotless:apply

# Run a single test class
mvnd test -Dtest=ProcTest

# Full build with dogfood validation
mvnd verify -Ddogfood

# Faster with Maven Daemon (included)
./bin/mvndw verify  # Auto-downloads mvnd if needed
```

## Support

- **Issues**: [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Discussions**: [GitHub Discussions](https://github.com/seanchatmangpt/jotp/discussions)
- **Documentation**: This repository
- **Book**: [../books/jotp-patterns/](../books/jotp-patterns/)

## License

Apache License 2.0 - See [LICENSE](../LICENSE) for details

## Citation

If you use JOTP in academic research, please cite:

```bibtex
@software{jotp2026,
  title = {JOTP: Erlang/OTP Primitives for Java 26},
  author = {Sean Chatman},
  year = {2026},
  url = {https://github.com/seanchatmangpt/jotp}
}
```

---

**Last Updated**: 2026-03-15
**Version**: 1.0.0-SNAPSHOT
**Java Version**: 26 (with preview features)
