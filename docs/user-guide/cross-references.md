# JOTP DTR Cross-Reference System Design

## Overview

This document defines the cross-reference system for JOTP's Documentation Test Runner (DTR) output. Cross-references enable semantic linking between related documentation sections, similar to JavaDoc's `@link` but for executable documentation tests.

## Goals

1. **Semantic Navigation**: Enable readers to navigate from primitives to their dependencies
2. **Learning Paths**: Create guided paths from basic to advanced concepts
3. **Pattern Relationships**: Show how enterprise patterns build on core OTP primitives
4. **EIP Composition**: Document how Enterprise Integration Patterns compose

## Cross-Reference Types

### 1. Prerequisite References (requires)

Links to concepts that should be understood first:
```java
ctx.sayRef(ProcTest.class, "basic-process-creation",
    "See Proc: Basic Process Creation for foundational concepts");
```

**Usage**: When explaining advanced features that depend on basic concepts.

### 2. Related Concept References (see-also)

Links to related but not prerequisite concepts:
```java
ctx.sayRef(SupervisorTest.class, "one-for-one-strategy",
    "Compare with Supervisor: ONE_FOR_ONE Strategy for alternative approaches");
```

**Usage**: When contrasting approaches or showing alternatives.

### 3. Implementation References (built-with)

Links from implementation details to the primitive they implement:
```java
ctx.sayRef(CircuitBreakerTest.class, "circuit-breaker-supervision",
    "CircuitBreaker is built using Supervisor for fault tolerance");
```

**Usage**: When showing how enterprise patterns use core OTP primitives.

### 4. Composition References (composed-of)

Links to patterns that compose to form this pattern:
```java
ctx.sayRef(SplitterTest.class, "splitter-aggregator-pattern",
    "Splitter → Aggregator forms a complete scatter-gather pattern");
```

**Usage**: For EIP patterns that commonly work together.

## Anchor Naming Conventions

Anchors should be **kebab-case** and follow this pattern:
```
<primitive-or-pattern>-<specific-concept>
```

### Examples

- `proc-basic-creation`
- `proc-message-passing`
- `supervisor-one-for-one-strategy`
- `supervisor-restart-intensity`
- `statemachine-gen-statem-contract`
- `circuit-breaker-closed-state`
- `saga-compensating-transactions`
- `splitter-aggregator-composition`

## Core OTP Primitives Cross-Reference Map

### Proc (Lightweight Process)

**Sections:**
- `proc-basic-creation` - Process creation and lifecycle
- `proc-message-passing` - tell/ask patterns
- `proc-share-nothing` - Immutable state semantics
- `proc-crash-callbacks` - Crash notification
- `proc-introspection` - ProcSys operations
- `proc-timed-messages` - ProcTimer integration

**References FROM:**
- All tests that create processes
- Supervisor (manages Procs)
- ProcRef (stable references)
- ProcLink (bidirectional linking)
- ProcMonitor (one-way monitoring)

**References TO:**
- Virtual Thread patterns (implementation)
- StructuredTaskScope (concurrency foundation)

### Supervisor (Fault Tolerance)

**Sections:**
- `supervisor-one-for-one-strategy` - Individual child restart
- `supervisor-one-for-all-strategy` - All children restart
- `supervisor-rest-for-one-strategy` - Tail restart
- `supervisor-restart-intensity` - Escalation thresholds
- `supervisor-procref-stability` - Stable references

**References FROM:**
- CircuitBreaker (uses Supervisor for fault tolerance)
- BulkheadIsolation (supervised resource pools)
- MultiTenantSupervisor (tenant isolation)
- Enterprise patterns (all)

**References TO:**
- Proc (what it supervises)
- ProcRef (stable handles)
- Crash Recovery (escalation)

### StateMachine (gen_statem)

**Sections:**
- `statemachine-gen-statem-contract` - OTP contract
- `statemachine-transitions` - Next/Keep/Stop
- `statemachine-event-handling` - Event processing
- `statemachine-data-carrier` - Immutable data

**References FROM:**
- Complex workflow tests
- Saga coordinators (stateful orchestration)
- Order processing examples

**References TO:**
- Proc (implementation base)
- Sealed types (type safety)
- Pattern matching (exhaustive switches)

### EventManager (Pub/Sub)

**Sections:**
- `eventmanager-typed-events` - Sealed event types
- `eventmanager-subscription` - Handler registration
- `eventmanager-broadcast` - Event delivery
- `eventmanager-fault-isolation` - Handler crashes don't kill bus

**References FROM:**
- EventBus (enterprise wrapper)
- Messaging patterns (channel-based)
- Reactive streams integration

**References TO:**
- Proc (per-handler processes)
- Sealed types (event hierarchies)

## Enterprise Patterns Cross-Reference Map

### CircuitBreaker

**Sections:**
- `circuit-breaker-closed-state` - Normal operation
- `circuit-breaker-open-state` - Failure threshold
- `circuit-breaker-half-open-state` - Recovery probe
- `circuit-breaker-supervision` - Supervisor integration

**References FROM:**
- Enterprise integration examples
- Resilience pattern compositions

**References TO:**
- Supervisor (fault tolerance foundation)
- Result (sealed error handling)
- ProcRef (stable circuit handle)

### Saga (Distributed Transactions)

**Sections:**
- `saga-compensating-transactions` - LIFO rollback
- `saga-orchestration` - Coordinator pattern
- `saga-timeout-handling` - Step timeouts
- `saga-failure-recovery` - Compensation execution

**References FROM:**
- Order processing examples
- Payment system demos
- Microservice integration

**References TO:**
- StateMachine (saga state transitions)
- Supervisor (step fault tolerance)
- EventManager (compensation events)

### BulkheadIsolation

**Sections:**
- `bulkhead-resource-pools` - Partitioned resources
- `bulkhead-strategy` - Thread pool vs semaphore
- `bulkhead-supervision` - Fault containment

**References FROM:**
- Multi-tenant SaaS examples
- Resource-constrained systems

**References TO:**
- Supervisor (pool supervision)
- ProcRef (stable pool references)
- Virtual Threads (lightweight isolation)

### Backpressure

**Sections:**
- `backpressure-strategies` - Rate limiting approaches
- `backpressure-monitoring` - Metrics and thresholds
- `backpressure-rejection` - Load shedding

**References FROM:**
- High-throughput systems
- Stream processing examples

**References TO:**
- EventManager (event rate control)
- Proc (mailbox backpressure)

### HealthCheck

**Sections:**
- `health-check-liveness` - Process health
- `health-check-readiness` - Service readiness
- `health-check-aggregation` - Composite health

**References FROM:**
- Production deployment examples
- K8s integration demos

**References TO:**
- Proc (process liveness)
- Supervisor (tree health)
- EventManager (bus health)

## EIP Patterns Cross-Reference Map

### Splitter

**Sections:**
- `splitter-message-decomposition` - Breaking messages apart
- `splitter-sequential-vs-parallel` - Processing strategies

**References TO:**
- Aggregator (reassembly)
- Router (post-split routing)

### Aggregator

**Sections:**
- `aggregator-message-correlation` - Matching responses
- `aggregator-completion-strategies` - Timeout/size-based
- `aggregator-strategies` - Collection strategies

**References TO:**
- Splitter (source of messages)
- Correlation identifiers (message matching)

### ContentBasedRouter

**Sections:**
- `router-content-evaluation` - Message inspection
- `router-destination-selection` - Channel selection

**References TO:**
- Channel patterns (destinations)
- Message patterns (evaluation)

### ScatterGather

**Sections:**
- `scatter-gather-composition` - Splitter + Aggregator
- `scatter-gather-timeouts` - Response aggregation
- `scatter-gather-failure-handling` - Partial failures

**References TO:**
- Splitter (scatter phase)
- Aggregator (gather phase)
- Channel patterns (parallel routing)

## Implementation Guidelines

### When to Add Cross-References

**DO add references when:**
1. Explaining a concept that builds on another (prerequisite)
2. Showing how a pattern uses a primitive (implementation)
3. Contrasting two approaches (comparison)
4. Documenting pattern composition (EIP)
5. Providing alternative solutions (see-also)

**DON'T add references when:**
1. The relationship is tenuous or indirect
2. It would create circular dependencies
3. The target section doesn't exist yet
4. It's more of a footnote than a semantic link

### Reference Placement

Place references **after** the explanation, not before:
```java
// GOOD
ctx.say("Supervisor manages multiple Procs for fault tolerance");
ctx.sayRef(ProcTest.class, "proc-basic-creation",
    "See Proc: Basic Process Creation for process lifecycle details");

// BAD
ctx.sayRef(ProcTest.class, "proc-basic-creation");
ctx.say("Supervisor manages multiple Procs");
```

### Multiple References

Group related references:
```java
ctx.say("Enterprise patterns build on core OTP primitives");
ctx.sayRef(SupervisorTest.class, "supervisor-one-for-one-strategy",
    "Fault tolerance via supervision");
ctx.sayRef(ProcTest.class, "proc-message-passing",
    "Message passing semantics");
ctx.sayRef(ResultTest.class, "result-error-handling",
    "Error handling with Result types");
```

## Forward References

When referencing a section that doesn't exist yet, use TODO comments:
```java
// TODO: Add ctx.sayRef() when target section is implemented
// ctx.sayRef(FutureTest.class, "future-section", "Description");
```

## Validation

The cross-reference system will be validated by:
1. **Link Checker**: Verifies all referenced anchors exist
2. **Circular Dependency Detector**: Prevents reference cycles
3. **Orphaned Section Detector**: Finds sections with no inbound references
4. **Reference Quality Analyzer**: Flags overly generic or tenuous links

## Examples

### Example 1: Core to Enterprise

```java
// In CircuitBreakerTest.java
@Test
void circuitBreakerUsesSupervisor(DtrContext ctx) {
    ctx.sayNextSection("CircuitBreaker: Supervisor Integration");
    ctx.say(
        "CircuitBreaker uses Supervisor to manage fault detection state. "
        + "Each circuit state (CLOSED, OPEN, HALF_OPEN) is managed by "
        + "a supervised process that survives crashes.");

    ctx.sayRef(SupervisorTest.class, "supervisor-one-for-one-strategy",
        "Supervisor: ONE_FOR_ONE Strategy - how individual processes are restarted");

    ctx.sayRef(ProcRef.class, "procref-stability",
        "ProcRef: Stable References - why circuit handles survive restarts");

    // ... test code ...
}
```

### Example 2: EIP Composition

```java
// In ScatterGatherTest.java
@Test
void scatterGatherCombinesSplitterAndAggregator(DtrContext ctx) {
    ctx.sayNextSection("Scatter-Gather: Splitter + Aggregator Composition");
    ctx.say(
        "The Scatter-Gather pattern combines Splitter (decomposition) "
        + "with Aggregator (reassembly) for parallel processing.");

    ctx.sayRef(SplitterTest.class, "splitter-message-decomposition",
        "Splitter: Message Decomposition - breaking messages into parts");

    ctx.sayRef(AggregatorTest.class, "aggregator-message-correlation",
        "Aggregator: Message Correlation - reassembling responses");

    // ... test code ...
}
```

### Example 3: Prerequisite Chain

```java
// In MultiTenantSupervisorTest.java
@Test
void multiTenantSupervisionRequiresBasicSupervision(DtrContext ctx) {
    ctx.sayNextSection("Multi-Tenant Supervision: Advanced Supervisor Patterns");
    ctx.say(
        "Multi-tenant supervision extends basic supervision with tenant "
        + "isolation and per-tenant resource limits.");

    ctx.sayRef(SupervisorTest.class, "supervisor-one-for-one-strategy",
        "Prerequisite: Understand basic ONE_FOR_ONE supervision first");

    ctx.sayRef(BulkheadIsolationTest.class, "bulkhead-resource-pools",
        "Related: Bulkhead isolation for resource partitioning");

    // ... test code ...
}
```

## Migration Strategy

### Phase 1: Core OTP Primitives
1. Add anchors to ProcTest sections
2. Add anchors to SupervisorTest sections
3. Add anchors to StateMachineTest sections
4. Link Supervisor → Proc
5. Link StateMachine → Proc

### Phase 2: Enterprise Patterns
1. Add anchors to CircuitBreakerTest
2. Add anchors to DistributedSagaCoordinatorTest
3. Add anchors to BulkheadIsolationTest
4. Link all enterprise → Supervisor
5. Link Saga → StateMachine

### Phase 3: EIP Patterns
1. Add anchors to SplitterTest
2. Add anchors to AggregatorTest
3. Add anchors to ContentBasedRouterTest
4. Document composition relationships
5. Link EIP → Core messaging

### Phase 4: Validation
1. Implement link checker
2. Fix broken references
3. Add reference quality metrics
4. Generate reference graph

## Maintenance

### Adding New Sections
When adding a new documentation section:
1. Create a unique, descriptive anchor
2. Document it in this file's appropriate section map
3. Add references FROM related sections
4. Verify no circular dependencies created

### Removing Sections
When removing a documentation section:
1. Find all inbound references (via grep)
2. Update or remove those references
3. Update this file's section maps
4. Verify no orphaned references remain

## Reference Graph Visualization

The cross-reference system can be visualized as a directed graph:
- **Nodes**: Documentation sections (anchors)
- **Edges**: Cross-references (sayRef calls)
- **Edge Types**: prerequisite, implementation, composition, comparison

Tools for visualization:
- Graphviz (DOT format)
- Mermaid.js (flowcharts)
- Cytoscape.js (interactive)

## Appendix: Complete Anchor Registry

### Core OTP
- `proc-basic-creation`
- `proc-message-passing`
- `proc-share-nothing`
- `proc-crash-callbacks`
- `proc-introspection`
- `proc-timed-messages`
- `supervisor-one-for-one-strategy`
- `supervisor-one-for-all-strategy`
- `supervisor-rest-for-one-strategy`
- `supervisor-restart-intensity`
- `supervisor-procref-stability`
- `statemachine-gen-statem-contract`
- `statemachine-transitions`
- `statemachine-event-handling`
- `statemachine-data-carrier`
- `eventmanager-typed-events`
- `eventmanager-subscription`
- `eventmanager-broadcast`
- `eventmanager-fault-isolation`

### Enterprise Patterns
- `circuit-breaker-closed-state`
- `circuit-breaker-open-state`
- `circuit-breaker-half-open-state`
- `circuit-breaker-supervision`
- `saga-compensating-transactions`
- `saga-orchestration`
- `saga-timeout-handling`
- `saga-failure-recovery`
- `bulkhead-resource-pools`
- `bulkhead-strategy`
- `bulkhead-supervision`
- `backpressure-strategies`
- `backpressure-monitoring`
- `backpressure-rejection`
- `health-check-liveness`
- `health-check-readiness`
- `health-check-aggregation`

### EIP Patterns
- `splitter-message-decomposition`
- `splitter-sequential-vs-parallel`
- `aggregator-message-correlation`
- `aggregator-completion-strategies`
- `aggregator-strategies`
- `router-content-evaluation`
- `router-destination-selection`
- `scatter-gather-composition`
- `scatter-gather-timeouts`
- `scatter-gather-failure-handling`

---

**Status**: Design Complete
**Next Steps**: Implement Phase 1 (Core OTP Primitives)
**Owner**: Documentation Team
**Last Updated**: 2025-03-15
