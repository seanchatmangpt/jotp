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
# Via Make (recommended)
make compile          # compile sources
make test             # unit tests (T=ClassName for single class)
make verify           # unit + integration + quality checks
make format           # apply Spotless (Google Java Format AOSP)
make benchmark-quick  # JMH: 1 fork, 1 warmup, 2 iterations
make package          # build JAR (skips tests)
make guard-check      # validate H_TODO/H_MOCK/H_STUB guards
make deploy           # full cloud deploy pipeline (CLOUD=oci default)
make help             # list all targets

# Via mvnd (Maven Daemon)
mvnd compile          # compile (requires Java 26 with --enable-preview)
mvnd test             # run unit tests
mvnd verify           # run all tests + quality checks
mvnd spotless:apply   # format code (runs automatically on edit via hook)
mvnd test -Dtest=ProcTest  # run a single test class
mvnd verify -Ddogfood  # full build with dogfood validation
./bin/mvndw verify     # auto-downloads mvnd if needed
```

## Module & Runtime

- **Module:** `io.github.seanchatmangpt.jotp` (Java 26 JPMS)
- **Compiler flag:** `--enable-preview` required for all compilation and test runs
- **Tests:** `*Test.java` → Maven Surefire (unit); `*IT.java` → Maven Failsafe (integration)
- **Formatting:** Spotless runs automatically via PostToolUse hook after every `.java` edit

## Development Requirements

- **Java 26** with preview features enabled (`--enable-preview`)
- **Maven 4** (or use included Maven Wrapper: `./mvnw`)
- Optional: `mvnd` (Maven Daemon) - 30% faster builds via persistent JVM

## 15 OTP Primitives

| Primitive | Purpose |
|-----------|---------|
| `Proc<S,M>` | Lightweight process with `LinkedTransferQueue` mailbox; pure `(S,M)→S` handler |
| `Supervisor` | Fault-tolerant process tree; ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE strategies |
| `StateMachine<S,E,D>` | Full gen_statem contract; sealed S and E required for exhaustive switches |
| `ProcRef<S,M>` | Stable handle that survives supervisor restarts — never hold raw `Proc` |
| `ProcMonitor` | One-way DOWN notification; monitor side unaffected if target crashes |
| `ProcLink` | Bidirectional crash propagation between two processes |
| `ProcRegistry` | Name-based process lookup (equivalent to Erlang's `whereis/1`) |
| `ProcTimer` | Scheduled message delivery to a Proc |
| `ProcSys` | Live introspection: suspend/resume/statistics without stopping the process |
| `ProcLib` | Process utility functions (init_ack handshake pattern) |
| `CrashRecovery` | Wraps supplier in isolated virtual thread; returns `Result<T, Exception>` |
| `Parallel` | Structured concurrency via `StructuredTaskScope` |
| `EventManager<E>` | Typed pub-sub event bus; handler crash doesn't kill bus |
| `Result<T,E>` | Railway-oriented error handling with sealed Ok/Err variants |
| `ExitSignal` | Exit reason carrier for linked/monitored processes (trap_exit pattern) |

## Java 26 Patterns to Use

```java
// Sealed types — exhaustive switch is compiler-enforced
public sealed interface Transition<S,D> permits Transition.Next, Transition.Keep, Transition.Stop {}

// Pattern matching with record patterns
if (shape instanceof Circle(var radius)) { ... }
switch (result) { case Success(var v) -> use(v); case Failure(var e) -> handle(e); }

// Virtual threads + StructuredTaskScope (--enable-preview required)
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var task = scope.fork(() -> compute());
    scope.join().throwIfFailed();
    return task.get();
}

// ScopedValue over ThreadLocal (virtual-thread safe)
static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();
ScopedValue.where(CURRENT_USER, user).run(() -> handleRequest());
```

## Forbidden Patterns (guards block on violation)

```java
// H_TODO — never leave in src/main/java
// TODO: implement later    FIXME: this is broken

// H_MOCK — never in production code
class MockPaymentService implements PaymentService { ... }
var mock = mockRepository();

// H_STUB — never return empty stubs
public String getName() { return ""; }
public List<Item> getItems() { return null; }
```
If genuinely blocked: `throw new UnsupportedOperationException("not implemented: <reason>")`.

## Code Structure

### Core Package: `io.github.seanchatmangpt.jotp`

```
io.github.seanchatmangpt.jotp
├── Proc, ProcRef, ProcLink, ProcMonitor, ProcRegistry, ProcTimer, ProcSys, ProcLib
├── Supervisor, CrashRecovery, StateMachine, EventManager, Parallel, ExitSignal, Result
├── ApplicationController, ApplicationSpec, ApplicationCallback, StartType, RunType
└── dogfood/   ← template-generated examples, not production primitives
```

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

### Test Conventions

- **JUnit 5** (`@Test`, `@BeforeEach`, `@ParameterizedTest`)
- **AssertJ** for assertions (`assertThat(...)`, not `assertTrue`)
- **jqwik** for property-based tests (`@Property`, `@ForAll`)
- **Awaitility** for async (`await().atMost(...).until(...)`) — never `Thread.sleep()`
- **Instancio** for test data generation
- `ApplicationController.reset()` in `@BeforeEach` — required for test isolation

### Excluded Components

Some components are currently excluded from compilation due to ongoing development:
- `**/messaging/**` - Experimental messaging system
- `**/enterprise/**` - Enterprise patterns under evaluation
- `**/pool/**` - Connection pooling patterns
- Various experimental features with integration dependencies

## Key Conventions

- **Message Types**: Use sealed interfaces of records for type-safe pattern matching
- **State**: Keep immutable (records, sealed classes, or value types)
- **Error Handling**: Use `Result<T,E>` for railway-oriented programming
- **Processes**: Prefer `spawn()` factory over constructor for new code
- **Supervisors**: Use structured ChildSpec for complex scenarios
- **Timeouts**: Always use timeouts for `ask()` calls to avoid deadlocks

## Automation (hooks run automatically)

- **SessionStart:** installs JDK 26 + mvnd, configures Maven proxy, shows git context
- **PostToolUse (Edit/Write):** Spotless formats `.java` files, then guard validation runs

## Documentation

- **Book**: `book/src/` - Comprehensive guide with examples
- **Docs**: `docs/` - Technical documentation and patterns
  - `docs/ARCHITECTURE.md` — enterprise patterns, competitive analysis, performance benchmarks
  - `docs/SLA-PATTERNS.md` — SRE runbooks, monitoring, disaster recovery
  - `docs/INTEGRATION-PATTERNS.md` — brownfield Spring Boot adoption strategy
- **Examples**: `src/main/java/io/github/seanchatmangpt/jotp/examples/` - Working examples
- **Thesis**: `docs/phd-thesis-otp-java26.md` - Formal OTP ↔ Java 26 equivalence proofs
- **User Guide**: `docs/user-guide/` - Next.js/MDX documentation (100+ files, 150K+ words)
