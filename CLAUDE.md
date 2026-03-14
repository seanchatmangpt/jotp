# JOTP — Developer Reference

## Build (mvnd mandatory)

```bash
make compile          # compile sources
make test             # unit tests  (T=ClassName for single class)
make verify           # unit + integration + quality checks
make format           # apply Spotless (Google Java Format AOSP)
make benchmark-quick  # JMH: 1 fork, 1 warmup, 2 iterations
make package          # build JAR (skips tests)
make guard-check      # validate H_TODO/H_MOCK/H_STUB guards
make deploy           # full cloud deploy pipeline (CLOUD=oci default)
make help             # list all targets
```

## Module & Runtime

- **Module:** `io.github.seanchatmangpt.jotp` (Java 26 JPMS)
- **Compiler flag:** `--enable-preview` required for all compilation and test runs
- **Tests:** `*Test.java` → Maven Surefire (unit); `*IT.java` → Maven Failsafe (integration)
- **Formatting:** Spotless runs automatically via PostToolUse hook after every `.java` edit

## 15 OTP Primitives

| Primitive | Purpose |
|-----------|---------|
| `Proc<S,M>` | Lightweight process with `LinkedTransferQueue` mailbox; pure `(S,M)→S` handler |
| `Supervisor` | Fault-tolerant process tree; ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE strategies |
| `StateMachine<S,E,D>` | Full gen_statem contract; sealed S and E required for exhaustive switches |
| `ProcRef<S,M>` | Stable handle that survives supervisor restarts — never hold raw `Proc` |
| `ProcMonitor` | One-way DOWN notification; monitor side unaffected if target crashes |
| `ProcRegistry` | Name-based process lookup (equivalent to Erlang's `whereis/1`) |
| `ProcTimer` | Scheduled message delivery to a Proc |
| `ProcLink` | Bidirectional crash propagation between two processes |
| `ProcSys` | Live introspection: suspend/resume/statistics without stopping the process |
| `ProcLib` | Process utility functions |
| `CrashRecovery` | Wraps supplier in isolated virtual thread; returns `Result<T, Exception>` |
| `Parallel` | Structured concurrency via `StructuredTaskScope` |
| `EventManager<E>` | Typed pub-sub event bus; handler crash doesn't kill bus |
| `ExitSignal` | Exit reason carrier for linked/monitored processes |
| `ApplicationController` | Static registry for load/start/stop/query of applications |

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

## Test Conventions

- **JUnit 5** (`@Test`, `@BeforeEach`, `@ParameterizedTest`)
- **AssertJ** for assertions (`assertThat(...)`, not `assertTrue`)
- **jqwik** for property-based tests (`@Property`, `@ForAll`)
- **Awaitility** for async (`await().atMost(...).until(...)`) — never `Thread.sleep()`
- **Instancio** for test data generation
- `ApplicationController.reset()` in `@BeforeEach` — required for test isolation

## Package Structure

```
io.github.seanchatmangpt.jotp
├── Proc, ProcRef, ProcLink, ProcMonitor, ProcRegistry, ProcTimer, ProcSys, ProcLib
├── Supervisor, CrashRecovery, StateMachine, EventManager, Parallel, ExitSignal
├── ApplicationController, ApplicationSpec, ApplicationCallback, StartType, RunType
└── dogfood/   ← template-generated examples, not production primitives
```

## Automation (hooks run automatically)

- **SessionStart:** installs JDK 26 + mvnd, configures Maven proxy, shows git context
- **PostToolUse (Edit/Write):** Spotless formats `.java` files, then guard validation runs

## Deep Docs (human reference, not session guidance)

- `docs/ARCHITECTURE.md` — enterprise patterns, competitive analysis, performance benchmarks
- `docs/SLA-PATTERNS.md` — SRE runbooks, monitoring, disaster recovery
- `docs/INTEGRATION-PATTERNS.md` — brownfield Spring Boot adoption strategy
