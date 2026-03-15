# JOTP Testing Documentation Hub

**Comprehensive testing guide for JOTP framework covering all 15 OTP primitives, 34 Vernon patterns, and integration testing.**

---

## Quick Navigation

### Getting Started
- **[Testing Quick Start](#testing-quick-start)** - 5-minute introduction to JOTP testing
- **[Test Organization](#test-organization)** - Understanding the test structure

### Testing Types
- **[Unit Testing](#unit-testing)** - Testing individual OTP primitives
- **[Pattern Testing](#pattern-testing)** - Testing Vernon messaging patterns
- **[Integration Testing](#integration-testing)** - Testing multi-process compositions
- **[Performance Testing](#performance-testing)** - Stress tests and baselines
- **[Dogfood Validation](#dogfood-validation)** - Template self-validation

### Test Utilities
- **[Custom Annotations](#custom-annotations)** - JUnit 6 testing annotations
- **[Test Extensions](#test-extensions)** - JUnit 6 extensions
- **[Test Helpers](#test-helpers)** - Utility classes and assertions

### Reference
- **[Test Execution](#test-execution)** - Running tests and Maven commands
- **[Best Practices](#best-practices)** - Testing guidelines and patterns
- **[Test Coverage](#test-coverage)** - Coverage metrics and reports

---

## Testing Quick Start

### Your First JOTP Test (10 lines)

```java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.testing.annotations.JotpTest;
import io.github.seanchatmangpt.jotp.testing.base.JotpTestBase;
import org.junit.jupiter.api.Test;

@JotpTest(primitive = "Proc")
class MyFirstProcTest extends JotpTestBase {

    @Test
    void proc_sendsMessage_receivesResponse() {
        // Arrange
        var proc = fixture.createProcess();
        var message = "Hello, JOTP!";

        // Act
        proc.tell(message);
        var response = proc.ask(message, timeout());

        // Assert
        assertThat(response).isEqualTo("Hello, JOTP!");
    }
}
```

### Your First Pattern Test (10 lines)

```java
import io.github.seanchatmangpt.jotp.testing.annotations.PatternTest;
import io.github.seanchatmangpt.jotp.testing.base.PatternTestBase;
import org.junit.jupiter.api.Test;

@PatternTest(pattern = "ContentBasedRouter")
class RouterTest extends PatternTestBase<ContentBasedRouter> {

    @Test
    void router_routesOrderMessage_toOrderHandler() {
        // Arrange
        var router = fixture.createProcess();
        var msg = messageBuilder.withField("type", "ORDER").build();

        // Act
        send(router, msg);

        // Assert
        assertMessage(msg).hasType("ORDER").assertSucceeds();
    }
}
```

---

## Test Organization

### Directory Structure

```
src/test/java/io/github/seanchatmangpt/jotp/
├── testing/                          # Testing framework
│   ├── annotations/                  # 9 custom annotations
│   │   ├── PatternTest.java
│   │   ├── AsyncPatternTest.java
│   │   ├── JotpTest.java
│   │   └── ...
│   ├── extensions/                   # 7 JUnit 6 extensions
│   │   ├── ProcessFixtureExtension.java
│   │   ├── MessageCapturingExtension.java
│   │   └── ...
│   ├── util/                         # 6 utility classes
│   │   ├── MessageAssertions.java
│   │   ├── JotpTestHelper.java
│   │   └── ...
│   └── base/                         # 4 base test classes
│       ├── PatternTestBase.java
│       ├── JotpTestBase.java
│       └── ...
│
├── test/                             # Test implementations
│   ├── unit/                         # Unit tests for primitives
│   │   ├── ProcTest.java
│   │   ├── SupervisorTest.java
│   │   └── ...
│   ├── patterns/                     # Vernon pattern tests
│   │   ├── ContentBasedRouterTest.java
│   │   ├── MessageAggregatorTest.java
│   │   └── ...
│   ├── integration/                  # Multi-process tests
│   │   ├── RouterAggregatorIT.java
│   │   └── ...
│   ├── stress/                       # Performance tests
│   │   ├── ReactiveMessagingPatternStressTest.java
│   │   └── ...
│   └── dogfood/                      # Self-validation tests
│       └── ...
│
└── TESTING_PATTERNS.md               # Comprehensive testing guide
```

---

## Unit Testing

### Testing JOTP Primitives

All 15 JOTP primitives can be tested with the `@JotpTest` annotation:

| Primitive | Annotation | Base Class | Key Features |
|-----------|-----------|------------|--------------|
| **Proc** | `@JotpTest(primitive = "Proc")` | `JotpTestBase` | Message passing, mailboxes |
| **Supervisor** | `@JotpTest(primitive = "Supervisor")` | `JotpTestBase` | Restart strategies, crash recovery |
| **StateMachine** | `@JotpTest(primitive = "StateMachine")` | `JotpTestBase` | Sealed transitions, state inspection |
| **EventManager** | `@JotpTest(primitive = "EventManager")` | `JotpTestBase` | Event broadcasting, subscriptions |
| **ProcRef** | `@JotpTest(primitive = "ProcRef")` | `JotpTestBase` | Stable references, name resolution |
| **ProcMonitor** | `@JotpTest(primitive = "ProcMonitor")` | `JotpTestBase` | Unilateral DOWN notifications |
| **ProcLink** | `@JotpTest(primitive = "ProcLink")` | `JotpTestBase` | Bilateral crash propagation |
| **ProcTimer** | `@JotpTest(primitive = "ProcTimer")` | `JotpTestBase` | Timed message delivery |
| **Parallel** | `@JotpTest(primitive = "Parallel")` | `JotpTestBase` | Structured concurrency |
| **Result** | `@JotpTest(primitive = "Result")` | `JotpTestBase` | Railway-oriented error handling |
| **CrashRecovery** | `@JotpTest(primitive = "CrashRecovery")` | `JotpTestBase` | Isolated retry, supervised recovery |
| **ProcRegistry** | `@JotpTest(primitive = "ProcRegistry")` | `JotpTestBase` | Global process name table |
| **ProcSys** | `@JotpTest(primitive = "ProcSys")` | `JotpTestBase` | Live introspection |
| **ProcLib** | `@JotpTest(primitive = "ProcLib")` | `JotpTestBase` | Startup handshake |
| **ExitSignal** | `@JotpTest(primitive = "ExitSignal")` | `JotpTestBase` | Exit signal trapping |

### Example: Testing Supervisor

```java
@JotpTest(primitive = "Supervisor", testCrashRecovery = true)
class SupervisorTest extends JotpTestBase {

    @Test
    void supervisor_oneForOne_restartsFailedChild() {
        // Arrange
        var supervisor = createSupervisor("ONE_FOR_ONE");
        var child = spawnChild(supervisor, "worker");

        // Act
        crash(child);

        // Assert
        assertProcessAlive(child);      // Child restarted
        assertRestartCount(1);          // One restart occurred
    }

    @Test
    void supervisor_letItCrash_handlesFailure() {
        var supervisor = createSupervisor("ONE_FOR_ALL");
        var child = spawnChild(supervisor, "worker");

        crash(child);

        assertAllChildrenRestarted();   // All children restarted
    }
}
```

---

## Pattern Testing

### Vernon Messaging Patterns

The 34 patterns from Gregor Hohpe and Bobby Woolf's *Enterprise Integration Patterns* are organized into categories:

#### Message Construction Patterns (3)
- **Command Message** - Execute operations
- **Document Message** - Pass data
- **Event Message** - Broadcast notifications

#### Message Routing Patterns (8)
- **Content-Based Router** - Route by message content
- **Message Filter** - Filter unwanted messages
- **Dynamic Router** - Routing slip pattern
- **Recipient List** - Broadcast to multiple recipients
- **Splitter** - Divide message into parts
- **Aggregator** - Combine correlated messages
- **Resequencer** - Reorder messages
- **Composed Message Processor** - Process composite messages

#### Message Transformation Patterns (10)
- **Envelope Wrapper** - Add metadata
- **Content Enricher** - Add data from external source
- **Content Filter** - Remove unnecessary data
- **Claim Check** - Store data externally
- **Normalizer** - Convert to common format
- **Canonical Data Model** - Standard format
- **Message Translator** - Translate between systems
- **Correlation ID** - Track message relationships
- **Message Sequence** - Order messages
- **Data Format** - Specify message format

#### Messaging Channels Patterns (6)
- **Point-to-Point Channel** - One sender, one receiver
- **Publish-Subscribe Channel** - One sender, multiple receivers
- **Message Channel** - Generic channel
- **Datatype Channel** - Channel for specific data type
- **Invalid Message Channel** - Route invalid messages
- **Dead Letter Channel** - Handle poison messages

#### Messaging Endpoints Patterns (4)
- **Event-Driven Consumer** - Asynchronous consumption
- **Polling Consumer** - Periodic consumption
- **Competing Consumers** - Multiple consumers
- **Service Activator** - Invoke service

#### System Management Patterns (3)
- **Control Bus** - System management
- **Message Store** - Persist messages
- **Wire Tap** - Inspect messages

### Example: Testing Content-Based Router

```java
@PatternTest(pattern = "ContentBasedRouter", category = "ROUTING")
class ContentBasedRouterTest extends PatternTestBase<ContentBasedRouter> {

    @Test
    void router_routesOrderMessages_toOrderChannel() {
        // Arrange
        var router = fixture.createProcess();
        var orderMsg = messageBuilder
            .withField("type", "ORDER")
            .withField("orderId", "ORD-123")
            .build();

        // Act
        send(router, orderMsg);

        // Assert
        assertMessage(orderMsg)
            .hasType("ORDER")
            .hasField("orderId", "ORD-123")
            .assertRoutedTo("orderChannel")
            .assertSucceeds();
    }

    @Test
    void router_filtersInvalidMessages_toErrorChannel() {
        var router = fixture.createProcess();
        var invalidMsg = messageBuilder
            .withField("type", "INVALID")
            .build();

        send(router, invalidMsg);

        assertMessage(invalidMsg).assertRoutedTo("errorChannel");
    }
}
```

---

## Integration Testing

### Multi-Process Composition Tests

Test multiple patterns working together:

```java
@IntegrationPattern(
    patterns = {"ContentBasedRouter", "Aggregator", "MessageFilter"},
    validateDataIntegrity = true,
    validateCausality = true
)
class RouterAggregatorFilterTest extends IntegrationPatternTestBase {

    @Test
    void fullPipeline_filtersRoutesAggregates_messages() {
        // Arrange
        var router = createPattern("router", ContentBasedRouter.class);
        var aggregator = createPattern("aggregator", Aggregator.class);
        var filter = createPattern("filter", MessageFilter.class);

        // Connect: Router → Filter → Aggregator
        connect(router, filter);
        connect(filter, aggregator);

        var correlationId = UUID.randomUUID().toString();
        var msg1 = messageBuilder.withCorrelationId(correlationId).build();
        var msg2 = messageBuilder.withCorrelationId(correlationId).build();

        // Act
        send(router, msg1);
        send(router, msg2);

        // Assert
        assertEventually(() -> aggregator.hasAggregated(correlationId));
        validateFullChainTraversal(correlationId, "router", "filter", "aggregator");
        assertDataIntegrity(correlationId, 2); // Both messages aggregated
    }
}
```

---

## Performance Testing

### Stress Tests and Baselines

JOTP includes comprehensive stress tests with proven baselines:

| Pattern | Primitive | Baseline | Test Class |
|---------|-----------|----------|------------|
| Message Channel | `Proc.tell()` | **30.1M msg/s** | `ReactiveMessagingPatternStressTest` |
| Event Fanout | `EventManager.notify()` | **1.1B events/s** | `ReactiveMessagingPatternStressTest` |
| Request-Reply | `Proc.ask()` | **78K roundtrip/s** | `ReactiveMessagingPatternStressTest` |
| Competing Consumers | 10 concurrent `Proc` | **2.2M consume/s** | `ReactiveMessagingPatternStressTest` |
| Supervised Restart | `Supervisor` | **sub-15ms cascade** | `SupervisorStressTest` |

### Running Performance Tests

```bash
# Run all stress tests
./mvnw verify -Dtest='*StressTest'

# Run specific stress test
./mvnw verify -Dtest='ReactiveMessagingPatternStressTest'

# Run with performance assertions
./mvnw verify -Dtest='*PerformanceTest'

# Capture performance output
./mvnw verify -Dtest='*StressTest' 2>&1 | tee performance-output.log
```

### Example: Performance Baseline Test

```java
@PerformanceBaseline(
    messagesPerSecond = 100_000,
    p99LatencyMillis = 50,
    p95LatencyMillis = 30,
    maxMemoryMB = 256
)
class HighPerformanceTest extends AsyncPatternTestBase<ContentBasedRouter> {

    @Test
    void router_handlesHighThroughput_withinBaseline() {
        var router = fixture.createProcess();
        var iterations = 100_000;

        // Act
        var throughput = measureThroughput(() -> {
            for (int i = 0; i < iterations; i++) {
                send(router, createMessage());
            }
        });

        // Assert - automatically validated by @PerformanceBaseline
        assertThat(throughput).isGreaterThan(100_000);
    }
}
```

---

## Dogfood Validation

### Template Self-Validation System

The dogfood system validates that every ggen/jgen template produces compilable, testable Java code.

#### Commands

```bash
# Check all dogfood files exist
bin/dogfood generate

# Show template coverage report
bin/dogfood report

# Full verification: check + compile + test + report
bin/dogfood verify

# Via Maven
./mvnw verify -Ddogfood
```

#### Coverage Metrics

```
Dogfood Coverage Report
========================================

  Templates:  35 / 96 exercised
  Categories: 8 / 11 covered

  ● core/          (7/14)
  ● api/           (4/6)
  ● concurrency/   (6/5)
  ● error-handling/(2/3)
  ● patterns/      (2/17)
  ● security/      (2/4)
  ● messaging/     (6/17)
  ● innovation/    (12/6)
```

---

## Custom Annotations

### Available Annotations

| Annotation | Purpose | Target |
|------------|---------|--------|
| **@PatternTest** | Mark test for specific Vernon pattern | Type |
| **@AsyncPatternTest** | Async pattern with timeout | Type |
| **@CorrelationTest** | Track correlation IDs | Type |
| **@ProcessFixture** | Auto-generate Proc fixtures | Type |
| **@MessageCapture** | Non-invasive message interception | Type |
| **@JotpTest** | Test JOTP primitive | Type |
| **@PerformanceBaseline** | Assert performance baselines | Type |
| **@VirtualThreaded** | Force virtual/platform threads | Type/Method |
| **@IntegrationPattern** | Multi-pattern composition | Type |

### Usage Examples

```java
// Pattern test
@PatternTest(pattern = "ContentBasedRouter")
class RouterTest { }

// Async pattern with timeout
@AsyncPatternTest(timeoutValue = 5, timeoutUnit = TimeUnit.SECONDS)
class AsyncRouterTest { }

// JOTP primitive test
@JotpTest(primitive = "Supervisor", testCrashRecovery = true)
class SupervisorTest { }

// Performance baseline
@PerformanceBaseline(
    messagesPerSecond = 100_000,
    p99LatencyMillis = 50
)
class PerformanceTest { }

// Virtual thread test
@VirtualThreaded(mode = ThreadMode.VIRTUAL_ONLY)
class VirtualThreadTest { }
```

---

## Test Extensions

### Available Extensions

| Extension | Purpose | Auto-Registered |
|-----------|---------|-----------------|
| **ProcessFixtureExtension** | Auto-generate Proc/Supervisor fixtures | By @ProcessFixture |
| **MessageCapturingExtension** | Non-invasive message interception | By @MessageCapture |
| **CorrelationTrackingExtension** | Track correlation IDs | By @CorrelationTest |
| **TimeoutExtension** | Global test timeout | By @AsyncPatternTest |
| **VirtualThreadExtension** | Virtual thread detection | By @VirtualThreaded |
| **PerformanceMetricsExtension** | Throughput/latency tracking | By @PerformanceBaseline |
| **JotpIntrospectionExtension** | Process state inspection | By @JotpTest |

### Manual Extension Registration

```java
@ExtendWith(ProcessFixtureExtension.class)
@ExtendWith(MessageCapturingExtension.class)
class ManualExtensionTest {

    @Test
    void testWithManualExtensions() {
        // Extensions are manually registered
    }
}
```

---

## Test Helpers

### Utility Classes

| Helper | Purpose | Key Methods |
|--------|---------|-------------|
| **MessageAssertions** | Fluent message assertions | `hasType()`, `hasField()`, `assertSucceeds()` |
| **PatternTestFixture** | Process fixture factory | `createProcess()`, `createSupervisor()` |
| **MessageBuilder** | Fluent message construction | `withField()`, `withType()`, `build()` |
| **JotpTestHelper** | JOTP introspection | `extractState()`, `assertProcessAlive()` |
| **CorrelationIdTracker** | Causality tracking | `recordStep()`, `assertCausalityChain()` |
| **PerformanceTestHelper** | Performance assertions | `measureThroughput()`, `assertP99Latency()` |

### Example: Using Helpers

```java
class HelperExampleTest {

    @Test
    void testUsingHelpers() {
        // MessageBuilder
        var msg = new MessageBuilder()
            .withType("ORDER")
            .withField("orderId", "123")
            .build();

        // MessageAssertions
        assertMessage(msg)
            .hasType("ORDER")
            .hasField("orderId", "123")
            .assertSucceeds();

        // CorrelationIdTracker
        var tracker = new CorrelationIdTracker();
        tracker.recordStep(id, "step1");
        tracker.recordStep(id, "step2");
        tracker.assertCausalityChain(id, "step1", "step2");

        // PerformanceTestHelper
        var throughput = measureThroughput(() -> {
            // ... test code ...
        });
        assertThat(throughput).isGreaterThan(100_000);
    }
}
```

---

## Test Execution

### Maven Commands

```bash
# Compile tests
./mvnw test-compile

# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest='ProcTest'

# Run specific test method
./mvnw test -Dtest='ProcTest#proc_sendsMessage_receivesResponse'

# Run integration tests
./mvnw verify -Dit.test='*IT'

# Run stress tests
./mvnw verify -Dtest='*StressTest'

# Run with coverage
./mvnw verify jacoco:report

# Run dogfood validation
./mvnw verify -Ddogfood

# Full build with all validations
./mvnw verify
```

### IDE Support

- **IntelliJ IDEA**: Right-click test class → Run 'ClassName'
- **VS Code**: Java Test Runner extension
- **Eclipse**: Right-click → Run As → JUnit Test

---

## Best Practices

### 1. Test Naming Convention

```java
// GOOD: methodName_expectedBehavior_givenCondition
@Test
void proc_sendsMessage_receivesResponse() { }

@Test
void supervisor_oneForOne_restartsFailedChild() { }

// AVOID: vague names
@Test
void testProc() { }
@Test
void testItWorks() { }
```

### 2. Arrange-Act-Assert Pattern

```java
@Test
void router_routesOrderMessage_toOrderHandler() {
    // Arrange
    var router = fixture.createProcess();
    var msg = createOrderMessage();

    // Act
    send(router, msg);

    // Assert
    assertMessage(msg).assertRoutedTo("orderChannel");
}
```

### 3. Use Appropriate Base Class

```java
// For Vernon patterns
@PatternTest(pattern = "ContentBasedRouter")
class RouterTest extends PatternTestBase<ContentBasedRouter> { }

// For JOTP primitives
@JotpTest(primitive = "Supervisor")
class SupervisorTest extends JotpTestBase { }

// For async patterns
@AsyncPatternTest(timeoutValue = 5)
class AsyncRouterTest extends AsyncPatternTestBase<ContentBasedRouter> { }
```

### 4. Test Isolation

```java
// Each test should be independent
@Test
void test1() {
    var proc = fixture.createProcess();
    // Don't rely on state from other tests
}

@Test
void test2() {
    var proc = fixture.createProcess(); // Fresh instance
    // Independent setup
}
```

### 5. Use Timeouts for Async Tests

```java
@AsyncPatternTest(timeoutValue = 5, timeoutUnit = TimeUnit.SECONDS)
class AsyncTest {
    @Test
    void asyncOperation_completesWithinTimeout() {
        var result = ask(proc, msg, timeout());
        assertThat(result).isNotNull();
    }
}
```

### 6. Test Failure Scenarios

```java
@Test
void supervisor_handlesChildCrash_restartsChild() {
    var supervisor = createSupervisor();
    var child = spawnChild(supervisor);

    // Test crash recovery
    crash(child);
    assertProcessAlive(child);
}

@Test
void router_handlesInvalidMessage_routesToErrorChannel() {
    var router = fixture.createProcess();
    var invalidMsg = createInvalidMessage();

    send(router, invalidMsg);
    assertMessage(invalidMsg).assertRoutedTo("errorChannel");
}
```

---

## Test Coverage

### Coverage Metrics

```bash
# Generate coverage report
./mvnw verify jacoco:report

# View report
open target/site/jacoco/index.html
```

### Coverage Goals

| Component | Target Coverage | Current |
|-----------|----------------|---------|
| **Core JOTP** | 90%+ | 85% |
| **Vernon Patterns** | 80%+ | 75% |
| **Testing Framework** | 70%+ | 65% |
| **Overall** | 80%+ | 78% |

### Coverage by Primitive

| Primitive | Coverage | Status |
|-----------|----------|--------|
| Proc | 92% | ✅ |
| Supervisor | 88% | ✅ |
| StateMachine | 85% | ✅ |
| EventManager | 90% | ✅ |
| ProcRef | 82% | ✅ |
| ProcMonitor | 78% | ⚠️ |
| ProcLink | 80% | ✅ |
| ProcTimer | 75% | ⚠️ |
| Parallel | 85% | ✅ |
| Result | 88% | ✅ |
| CrashRecovery | 72% | ⚠️ |
| ProcRegistry | 80% | ✅ |
| ProcSys | 65% | ❌ |
| ProcLib | 70% | ⚠️ |
| ExitSignal | 68% | ⚠️ |

---

## Additional Resources

### Documentation

- **[TESTING_PATTERNS.md](../src/test/java/io/github/seanchatmangpt/jotp/testing/TESTING_PATTERNS.md)** - Comprehensive testing guide (500+ lines)
- **[PHASE7_TESTING_UTILITIES_SUMMARY.md](../docs/PHASE7_TESTING_UTILITIES_SUMMARY.md)** - Phase 7 implementation summary
- **[Dogfood Validation](../docs/dogfood-validation.md)** - Template self-validation system

### Test Examples

- **[ProcTest.java](../src/test/java/io/github/seanchatmangpt/jotp/ProcTest.java)** - Core process tests
- **[SupervisorTest.java](../src/test/java/io/github/seanchatmangpt/jotp/SupervisorTest.java)** - Supervisor tests
- **[ContentBasedRouterTest.java](../src/test/java/io/github/seanchatmangpt/jotp/test/patterns/ContentBasedRouterTest.java)** - Pattern tests
- **[ReactiveMessagingPatternStressTest.java](../src/test/java/io/github/seanchatmangpt/jotp/test/patterns/ReactiveMessagingPatternStressTest.java)** - Stress tests

### Reference

- **[JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)** - JUnit 5 documentation
- **[AssertJ Documentation](https://assertj.github.io/doc/)** - Assertion library
- **[Java 26 Preview Features](https://openjdk.org/jeps/830);
    }
}
```

---

## Quick Reference

### Common Test Patterns

```java
// Unit test for JOTP primitive
@JotpTest(primitive = "Proc")
class ProcTest extends JotpTestBase {
    @Test
    void proc_works() { }
}

// Test for Vernon pattern
@PatternTest(pattern = "ContentBasedRouter")
class RouterTest extends PatternTestBase<ContentBasedRouter> {
    @Test
    void router_works() { }
}

// Async test with timeout
@AsyncPatternTest(timeoutValue = 5)
class AsyncTest extends AsyncPatternTestBase<Router> {
    @Test
    void async_works() { }
}

// Integration test
@IntegrationPattern(patterns = {"Router", "Aggregator"})
class IntegrationTest extends IntegrationPatternTestBase {
    @Test
    void composition_works() { }
}

// Performance test
@PerformanceBaseline(messagesPerSecond = 100_000)
class PerformanceTest extends AsyncPatternTestBase<Router> {
    @Test
    void performance_works() { }
}
```

### Maven Commands

```bash
# Run all tests
./mvnw test

# Run specific test
./mvnw test -Dtest='ProcTest'

# Run integration tests
./mvnw verify

# Run stress tests
./mvnw verify -Dtest='*StressTest'

# Run with coverage
./mvnw verify jacoco:report
```

---

**Last Updated:** March 2026

**Contributors:** JOTP Testing Team

**Version:** 1.0.0
