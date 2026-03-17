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

## Implementation Guidelines for Guard Violations

When the `make guard-check` validation detects violations of H_TODO, H_MOCK, or H_STUB guards, follow these patterns to fix them:

### H_STUB Violations (Empty or Null Returns)

**Problem:** Methods that return empty strings, null, or Collections.emptyList() without a real implementation.

**Solutions:**

1. **Implement properly** (preferred):
   ```java
   // Before (STUB violation)
   public String getName() { return ""; }

   // After (implemented)
   public String getName() { return this.name; }
   ```

2. **Throw UnsupportedOperationException** (if implementation is genuinely blocked):
   ```java
   // Before (STUB violation)
   public List<Item> getItems() { return null; }

   // After (explicit blocking)
   public List<Item> getItems() {
       throw new UnsupportedOperationException(
           "not implemented: requires persistence layer (see #1234)"
       );
   }
   ```

**Why it matters:** Empty returns hide logic gaps and can cause `NullPointerException` cascades downstream. Explicit failures make issues visible.

### H_TODO Violations (Deferred Work Markers)

**Problem:** TODO/FIXME comments in production code (`src/main/java`) indicating incomplete work.

**Solutions:**

1. **Complete the work** (preferred):
   ```java
   // Before (TODO violation)
   public void process() {
       // TODO: add logging
       doWork();
   }

   // After (completed)
   public void process() {
       logger.info("Processing started");
       doWork();
       logger.info("Processing completed");
   }
   ```

2. **Throw UnsupportedOperationException** (if work is deferred):
   ```java
   // Before (TODO violation)
   public Result<User, Exception> authenticateUser(String token) {
       // TODO: implement OAuth2 flow
       return null;
   }

   // After (explicit blocking)
   public Result<User, Exception> authenticateUser(String token) {
       throw new UnsupportedOperationException(
           "not implemented: OAuth2 integration scheduled for Sprint 5"
       );
   }
   ```

3. **Move to test code** (if experimental):
   - Move to `*Test.java` or `*IT.java` where TODOs are allowed
   - Add test case to track progress

**Why it matters:** TODOs in production code create technical debt and hide incomplete features. Either finish it or make it explicit.

### H_MOCK Violations (Mock Implementations in Production)

**Problem:** Mock/stub classes or mock object creation in production source code.

**Solutions:**

1. **Remove and use real implementation** (preferred):
   ```java
   // Before (MOCK violation)
   class MockPaymentService implements PaymentService {
       public Result<Payment, Exception> charge(BigDecimal amount) {
           return Ok(new Payment(amount, "mock-id"));
       }
   }

   // After (use real implementation)
   PaymentService service = new StripePaymentService(apiKey);
   ```

2. **Inject via interface** (for testing):
   ```java
   // Production code (no mocks)
   public class CheckoutProcessor {
       private final PaymentService payments;
       public CheckoutProcessor(PaymentService payments) {
           this.payments = payments;
       }
   }

   // Test code only
   @Test void testCheckout() {
       var mockPayments = mock(PaymentService.class);
       var processor = new CheckoutProcessor(mockPayments);
       // ...
   }
   ```

3. **Use factory/strategy pattern** (for environment-specific behavior):
   ```java
   public class PaymentServiceFactory {
       public static PaymentService create(Environment env) {
           return switch(env) {
               case PRODUCTION -> new StripePaymentService(liveKey);
               case STAGING -> new StripePaymentService(testKey);
               // Never default to mock — throw instead
           };
       }
   }
   ```

**Why it matters:** Mocks in production code hide real dependencies and create illusions of functionality. Tests must use real implementations or explicit dependency injection.

### Recent Examples (v2026.1.0)

The following fixes demonstrate the patterns above:

| Guard Type | File | Fix Type | Reason |
|-----------|------|----------|--------|
| H_STUB | `Result.java` | Implemented proper ok()/err() | Return values must be explicit |
| H_TODO | `ProcRef.java` | Completed link/monitor implementation | Feature required for supervision |
| H_MOCK | `ProcRegistry.java` | Removed mock registry; used real map | Production code must use real state |
| H_STUB | `EventManager.java` | Throw UnsupportedOperationException | Deferred feature (tracking #42) |

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
