# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JOTP is a production-ready Java 26 framework implementing all 15 OTP (Erlang/OTP) primitives. It brings battle-tested concurrency patterns, supervision trees, and fault tolerance to the JVM using virtual threads, sealed types, and pattern matching.

**Key Architecture:**
- **Processes**: Lightweight virtual-thread processes with mailboxes (`Proc<S,M>`)
- **Supervisors**: Hierarchical process supervision with restart strategies (`Supervisor`)
- **State Machines**: Complex workflows with sealed transitions (`StateMachine<S,E,D>`)
- **Event Management**: Typed event broadcasting (`EventManager<E>`)
- **Fault Tolerance**: Crash recovery, monitoring, linking, and supervision trees

## Build System

This project uses Maven 4 with the following key commands:

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

## Development Requirements

- **Java 26** with preview features enabled (`--enable-preview`)
- **Maven 4** (or use included Maven Wrapper: `./mvnw`)
- Optional: `mvnd` (Maven Daemon) - 30% faster builds via persistent JVM

## Code Structure

### Core Package: `io.github.seanchatmangpt.jotp`

- **`Proc<S,M>`**: Lightweight process with virtual-thread mailbox - Java 26 equivalent of Erlang process
- **`Supervisor`**: Hierarchical process supervision with restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- **`StateMachine<S,E,D>`**: Complex workflows with sealed transitions
- **`EventManager<E>`**: Typed event broadcasting
- **`ProcRef<S,M>`**: Stable handle that survives supervisor restarts
- **`ProcMonitor`**: Unilateral DOWN notifications
- **`ProcLink`**: Bilateral crash propagation
- **`ProcTimer`**: Timed message delivery
- **`Parallel`**: Structured fan-out with fail-fast semantics
- **`Result<T,E>`**: Railway-oriented error handling
- **`CrashRecovery`**: Isolated retry with supervised recovery
- **`ProcRegistry`**: Global process name table
- **`ProcSys`**: Live introspection without stopping
- **`ProcLib`**: Startup handshake (init_ack pattern)
- **`ExitSignal`**: Exit signal trapping and handling

### Key Design Patterns

1. **Let It Crash**: Processes don't handle exceptions - supervisors restart them
2. **Message Passing**: No shared state, communicate via immutable messages
3. **Supervision Trees**: Hierarchical restart strategies contain failures
4. **Virtual Threads**: Millions of lightweight processes (~1 KB heap each)
5. **Sealed Types**: Type-safe message protocols at compile time

### Testing Architecture

Tests are organized in several categories:
- **Unit Tests**: Core functionality (`*Test.java`)
- **Integration Tests**: Cross-component interactions (`*IT.java`)
- **Dogfood Tests**: Self-validation of JOTP using JOTP (`dogfood.*`)
- **Stress Tests**: Performance and reliability under load (`stress.*`)
- **Pattern Tests**: Enterprise integration patterns (`messagepatterns.*`)

### Excluded Components

Some components are currently excluded from compilation due to ongoing development:
- `**/messaging/**` - Experimental messaging system
- `**/enterprise/**` - Enterprise patterns under evaluation
- `**/pool/**` - Connection pooling patterns
- Various experimental features with integration dependencies

## Development Workflow

1. **Code Formatting**: Uses Google Java Format (AOSP style) via Spotless
2. **Quality Gates**: Javadoc validation, test coverage, static analysis
3. **Preview Features**: All Java 26 preview features are enabled
4. **Module System**: JPMS module `io.github.seanchatmangpt.jotp`

## Key Conventions

- **Message Types**: Use sealed interfaces of records for type-safe pattern matching
- **State**: Keep immutable (records, sealed classes, or value types)
- **Error Handling**: Use `Result<T,E>` for railway-oriented programming
- **Processes**: Prefer `spawn()` factory over constructor for new code
- **Supervisors**: Use structured ChildSpec for complex scenarios
- **Timeouts**: Always use timeouts for `ask()` calls to avoid deadlocks

## Documentation

- **Book**: `book/src/` - Comprehensive guide with examples
- **Docs**: `docs/` - Technical documentation and patterns
- **Examples**: `src/main/java/io/github/seanchatmangpt/jotp/examples/` - Working examples
- **Thesis**: `docs/phd-thesis-otp-java26.md` - Formal OTP ↔ Java 26 equivalence proofs