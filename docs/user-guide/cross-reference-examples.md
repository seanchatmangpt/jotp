# Cross-Reference Implementation Examples

This document provides concrete examples of how to implement cross-references using `ctx.sayRef()` in JOTP DTR tests.

## Example 1: Core OTP Primitives (Proc → Supervisor)

### File: SupervisorTest.java

```java
@Test
void crashAndRestartResumesService(DtrContext ctx) throws Exception {
    ctx.sayNextSection("Supervisor: Crash and Restart Recovery");

    ctx.say(
        "When a supervised process crashes, the supervisor automatically restarts it. " +
        "The process resets to its initial state, maintaining fault isolation.");

    // CROSS-REFERENCE: Link to basic process creation
    ctx.sayRef(
        ProcTest.class,
        "proc-basic-creation",
        "See Proc: Basic Process Creation for process lifecycle fundamentals"
    );

    ctx.sayCode(
        """
        var sup = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));
        var ref = sup.supervise("counter", 0, SupervisorTest::counterHandler);

        ref.tell(new CounterMsg.Inc(10));
        assertThat(ref.ask(new CounterMsg.Get()).get(1, SECONDS)).isEqualTo(10);

        ref.tell(new CounterMsg.Boom("injected fault")); // Crash it!

        // Supervisor restarts; process resets to initial state (0)
        await().atMost(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(tryGet(ref)).isEqualTo(0));
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 2: Enterprise → Core (CircuitBreaker → Supervisor)

### File: CircuitBreakerTest.java

```java
@Test
void testClosedStateSuccessfulRequest(DtrContext ctx) {
    ctx.sayNextSection("CircuitBreaker: CLOSED State - Normal Operation");

    ctx.say(
        "In CLOSED state, the circuit breaker allows all requests to pass through. " +
        "This is the normal operating state where the service is healthy. " +
        "Failures are tracked but don't block requests until the threshold is reached.");

    // CROSS-REFERENCE: Link to Supervisor (fault tolerance foundation)
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.test.SupervisorTest.class,
        "supervisor-crash-restart",
        "CircuitBreaker uses Supervisor for fault tolerance - see Supervisor: Crash and Restart Recovery"
    );

    ctx.sayCode(
        """
        var breaker = CircuitBreaker.create("test-service", 3,
            Duration.ofSeconds(10), Duration.ofMillis(500));
        var result = breaker.execute("request-1", request -> {
            callCount.incrementAndGet();
            return "response-1";
        });

        // In CLOSED state: requests execute normally
        // Success returns CircuitBreakerResult.Success<T>
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 3: EIP Composition (Splitter + Aggregator)

### File: ScatterGatherTest.java

```java
@Test
void scatterGatherCombinesSplitterAndAggregator(DtrContext ctx) {
    ctx.sayNextSection("Scatter-Gather: Splitter + Aggregator Composition");

    ctx.say(
        "The Scatter-Gather pattern combines Splitter (decomposition) " +
        "with Aggregator (reassembly) for parallel processing of message parts.");

    // CROSS-REFERENCE: Link to Splitter
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.messaging.routing.SplitterTest.class,
        "splitter-message-decomposition",
        "See Splitter: Message Decomposition for breaking messages into parts"
    );

    // CROSS-REFERENCE: Link to Aggregator
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.messaging.routing.AggregatorTest.class,
        "aggregator-message-correlation",
        "See Aggregator: Message Correlation for reassembling responses"
    );

    ctx.sayCode(
        """
        // Scatter phase: split message
        var parts = splitter.split(originalMessage);

        // Process in parallel
        var results = channels.parallel()
            .map(channel -> channel.send(parts))
            .toList();

        // Gather phase: aggregate responses
        var aggregated = aggregator.aggregate(results);
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 4: Prerequisite Chain (Multi-Tenant → Supervisor)

### File: MultiTenantSupervisorTest.java

```java
@Test
void multiTenantIsolationRequiresBasicSupervision(DtrContext ctx) {
    ctx.sayNextSection("Multi-Tenant Supervision: Advanced Supervisor Patterns");

    ctx.say(
        "Multi-tenant supervision extends basic supervision with tenant " +
        "isolation and per-tenant resource limits. Each tenant gets an " +
        "isolated supervision tree.");

    // CROSS-REFERENCE: Prerequisite - basic supervision
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.test.SupervisorTest.class,
        "supervisor-one-for-one-strategy",
        "Prerequisite: Understand ONE_FOR_ONE supervision strategy first"
    );

    // CROSS-REFERENCE: Related - bulkhead isolation
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.enterprise.bulkhead.BulkheadIsolationEnterpriseTest.class,
        "bulkhead-resource-pools",
        "Related: Bulkhead isolation for resource partitioning between tenants"
    );

    ctx.sayCode(
        """
        // Create per-tenant supervisors
        var tenant1Supervisor = new Supervisor(
            Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));

        var tenant2Supervisor = new Supervisor(
            Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));

        // Each tenant has isolated resource pools
        var tenant1Pool = tenant1Supervisor.supervise("pool", ...);
        var tenant2Pool = tenant2Supervisor.supervise("pool", ...);
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 5: Multiple Related References (State Machine)

### File: DistributedSagaCoordinatorTest.java

```java
@Test
void createWithValidConfig_returnsInstance(DtrContext ctx) {
    ctx.sayNextSection("Saga Coordinator: Orchestration-Based Distributed Transactions");

    ctx.say(
        "The Saga coordinator orchestrates distributed transactions without " +
        "two-phase commit. Each step executes forward; on failure, " +
        "compensating actions undo previous work in reverse (LIFO) order.");

    // CROSS-REFERENCE 1: State machine foundation
    ctx.sayRef(
        StateMachineTest.class,
        "statemachine-transitions",
        "Saga uses StateMachine transitions (Next/Keep/Stop) for orchestration state"
    );

    // CROSS-REFERENCE 2: Supervisor for fault tolerance
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.test.SupervisorTest.class,
        "supervisor-one-for-one-strategy",
        "Each saga step is supervised for fault tolerance"
    );

    // CROSS-REFERENCE 3: EventManager for compensation events
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.test.EventManagerTest.class,
        "eventmanager-broadcast",
        "Compensation events are broadcast via EventManager"
    );

    ctx.sayCode(
        """
        // Configure saga with forward actions and compensating transactions
        var config = SagaConfig.builder("order-saga")
            .steps(List.of(
                new SagaStep.Action<>("reserveInventory",
                    req -> inventory.reserve()),
                new SagaStep.Action<>("chargePayment",
                    req -> payment.charge()),
                new SagaStep.Action<>("confirmOrder",
                    req -> order.confirm())
            ))
            .timeout(Duration.ofSeconds(30))
            .compensationTimeout(Duration.ofSeconds(10))
            .build();

        var coordinator = DistributedSagaCoordinator.create(config);
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 6: Comparison Reference (Two Approaches)

### File: RoutingPatternsTest.java

```java
@Test
void contentBasedRouterVsDynamicRouter(DtrContext ctx) {
    ctx.sayNextSection("Content-Based Routing: Static vs Dynamic");

    ctx.say(
        "Content-Based Router evaluates message content to determine routing. " +
        "Unlike Dynamic Router which can change routes at runtime, " +
        "Content-Based Router uses fixed routing rules.");

    // CROSS-REFERENCE: Compare with Dynamic Router
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.messaging.routing.DynamicRouterTest.class,
        "router-content-evaluation",
        "Compare with Dynamic Router for runtime-changing routes"
    );

    ctx.sayCode(
        """
        // Content-based routing with fixed rules
        router.route(message)
            .when(type == Order.class)
            .to("order-channel")
            .when(type == Payment.class)
            .to("payment-channel");
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 7: Implementation Reference (How It's Built)

### File: VirtualThreadPatternsTest.java

```java
@Test
void procUsesVirtualThreads(DtrContext ctx) {
    ctx.sayNextSection("Proc: Virtual Thread Implementation");

    ctx.say(
        "Proc processes run on Java 21+ virtual threads, enabling millions " +
        "of lightweight concurrent processes. Each process has its own " +
        "virtual thread with minimal overhead (~1KB stack vs 1MB for platform threads).");

    // CROSS-REFERENCE: What implements this
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.test.ProcTest.class,
        "proc-basic-creation",
        "Proc wraps virtual threads in OTP process semantics"
    );

    // CROSS-REFERENCE: Foundation technology
    ctx.sayRef(
        StructuredTaskScopePatternsTest.class,
        "structured-concurrency",
        "Virtual threads work with StructuredTaskScope for coordinated concurrency"
    );

    ctx.sayCode(
        """
        // Proc creates a virtual thread for each process
        var proc = new Proc<>(0, (state, msg) -> state + 1);
        // Internally: Thread.startVirtualThread(() -> processMailbox());

        // Millions of processes possible
        var procs = IntStream.range(0, 1_000_000)
            .mapToObj(i -> new Proc<>(i, (s, m) -> s))
            .toList();
        """,
        "java"
    );

    // Test implementation...
}
```

## Example 8: Forward Reference (TODO Pattern)

### File: NewFeatureTest.java

```java
@Test
void upcomingFeaturePlaceholder(DtrContext ctx) {
    ctx.sayNextSection("New Feature: Coming Soon");

    ctx.say(
        "This feature will be implemented in the next release. " +
        "It will provide enhanced capabilities for...");

    // FORWARD REFERENCE: TODO - add when target exists
    // TODO: Add ctx.sayRef() when NewFeatureAdvancedTest.java is implemented
    // ctx.sayRef(
    //     NewFeatureAdvancedTest.class,
    //     "advanced-usage",
    //     "See New Feature: Advanced Usage for complex scenarios"
    // );

    ctx.sayNote(
        "Advanced usage patterns will be documented in a future test class. " +
        "This forward reference is a TODO placeholder."
    );

    // Test implementation...
}
```

## Example 9: Bidirectional References (A ↔ B)

### File: SplitterTest.java

```java
@Test
void splitterOutputsToAggregator(DtrContext ctx) {
    ctx.sayNextSection("Splitter: Output to Aggregator");

    ctx.say(
        "The Splitter breaks messages into parts that are typically " +
        "reassembled by an Aggregator. This forms a complete scatter-gather pattern.");

    // CROSS-REFERENCE: Forward to Aggregator
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.messaging.routing.AggregatorTest.class,
        "aggregator-message-correlation",
        "See Aggregator: Message Correlation for reassembly"
    );

    ctx.sayCode(
        """
        // Splitter generates sequence
        var parts = splitter.split(order);
        // Output: [OrderPart(item1), OrderPart(item2), OrderPart(item3)]
        """,
        "java"
    );

    // Test implementation...
}
```

### File: AggregatorTest.java (corresponding back-reference)

```java
@Test
void aggregatorInputsFromSplitter(DtrContext ctx) {
    ctx.sayNextSection("Aggregator: Input from Splitter");

    ctx.say(
        "The Aggregator collects message parts, typically generated by " +
        "a Splitter, and reassembles them using correlation identifiers.");

    // CROSS-REFERENCE: Back to Splitter
    ctx.sayRef(
        io.github.seanchatmangpt.jotp.messaging.routing.SplitterTest.class,
        "splitter-message-decomposition",
        "See Splitter: Message Decomposition for generating parts"
    );

    ctx.sayCode(
        """
        // Aggregator correlates and assembles
        var aggregated = aggregator.aggregate(
            List.of(orderPart1, orderPart2, orderPart3)
        );
        // Output: CompleteOrder
        """,
        "java"
    );

    // Test implementation...
}
```

## Best Practices Summary

### DO ✓
1. **Place references after explanations** - Readers need context first
2. **Use descriptive anchor names** - kebab-case, specific and clear
3. **Group related references** - Multiple refs about same topic together
4. **Provide relationship context** - Explain WHY the reference matters
5. **Update this documentation** - When adding new refs, document them

### DON'T ✗
1. **Don't reference before explaining** - Context first, then links
2. **Don't create circular refs** - A→B→A creates confusion
3. **Don't reference non-existent sections** - Creates broken links
4. **Don't over-reference** - Too many links dilutes value
5. **Don't use generic anchors** - "basic-stuff" is not helpful

### Reference Types

```java
// Prerequisite (must understand first)
ctx.sayRef(PrerequisiteTest.class, "prerequisite-section",
    "Prerequisite: Understand X before Y");

// Implementation (how it's built)
ctx.sayRef(ImplementationTest.class, "implementation-section",
    "Built using: X for Y");

// Composition (works with)
ctx.sayRef(ComponentTest.class, "component-section",
    "Works with: X for Y");

// Comparison (alternative approach)
ctx.sayRef(AlternativeTest.class, "alternative-section",
    "Compare with: X as an alternative to Y");

// Related (see also)
ctx.sayRef(RelatedTest.class, "related-section",
    "See also: X for related concept");
```

---

**Example Version**: 1.0
**Last Updated**: 2025-03-15
**Maintainer**: Documentation Team
