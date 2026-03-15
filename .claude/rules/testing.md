---
paths:
  - "src/test/java/**/*.java"
---

# JOTP Test Conventions

## Framework Stack

- **JUnit 5** — `@Test`, `@BeforeEach`, `@AfterEach`, `@ParameterizedTest`, `@Nested`
- **AssertJ** — `assertThat(...)` for fluent assertions; never `assertTrue`/`assertEquals`
- **jqwik** — `@Property`, `@ForAll` for property-based testing
- **Awaitility** — `await().atMost(Duration).until(...)` for async; never `Thread.sleep()`
- **Instancio** — `Instancio.create(MyRecord.class)` for random test data
- **ArchUnit** — architectural rule enforcement (module boundaries, naming)

## OTP-Specific Patterns

```java
// REQUIRED: reset ApplicationController state before each test
@BeforeEach void setUp() { ApplicationController.reset(); }

// Async Proc assertions — use Awaitility, not sleep
await().atMost(Duration.ofSeconds(2)).until(() -> proc.state().equals(expected));

// Supervisor restart verification — send a crash, assert restart
proc.tell(new CrashMsg());
await().atMost(Duration.ofSeconds(1)).until(() -> registry.isAlive("my-proc"));

// StateMachine — test each transition independently
var result = fsm.handle(INITIAL, new OrderCreated(id), data);
assertThat(result).isInstanceOf(Transition.Next.class);
assertThat(((Transition.Next<?,?>) result).state()).isEqualTo(READY);
```

## Naming

- `*Test.java` → Maven Surefire (unit tests, fast, no external deps)
- `*IT.java` → Maven Failsafe (integration tests, may use real threads/timing)
- `@Property` methods live inside `*Test.java` classes

## Forbidden in Tests

- `Thread.sleep()` → use Awaitility
- `System.out.println()` → use assertions or SLF4J logger
- `@Mock` / `Mockito` → guards are disabled for test scope, but prefer real impls
- Hardcoded timeouts under 100ms → flaky on slow CI; use at least `Duration.ofMillis(500)`
