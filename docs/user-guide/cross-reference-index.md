# JOTP DTR Cross-Reference Index

## Complete mapping of all test classes, sections, and cross-reference relationships

## How to Use This Index

**For Readers**: Navigate to test classes and sections to find related documentation
**For Contributors**: Find where to add new cross-references when writing tests
**For Maintainers**: Track documentation coverage and relationships

## Legend

- **[Core]**: Core OTP primitive tests
- **[Enterprise]**: Enterprise pattern tests
- **[EIP]**: Enterprise Integration Pattern tests
- **[Dogfood]**: JOTP eating its own dogfood
- **→**: References (points to related concept)
- **←**: Referenced by (other tests link here)

---

## Core OTP Primitives

### ProcTest (Lightweight Process)

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/test/ProcTest.java`

**Sections**:
1. `proc-basic-creation` - Process creation and lifecycle
   - Virtual thread process architecture
   - Mailbox initialization
   - Process lifecycle (start/stop)

2. `proc-message-passing` - tell/ask patterns
   - Fire-and-forget messaging (tell)
   - Request-reply pattern (ask)
   - Message ordering guarantees

3. `proc-share-nothing` - Immutable state semantics
   - Private state per process
   - Record-based state types
   - No shared mutable fields

4. `proc-crash-callbacks` - "Let It Crash" philosophy
   - Crash notification callbacks
   - Supervisor integration
   - Exception propagation

5. `proc-introspection` - ProcSys operations
   - getState() for inspection
   - statistics() for metrics
   - suspend/resume operations

6. `proc-timed-messages` - ProcTimer integration
   - Delayed message delivery
   - Timer-based scheduling
   - Periodic message patterns

**Referenced BY** (who links to ProcTest):
- SupervisorTest → proc-basic-creation, proc-message-passing
- ProcRefTest → proc-basic-creation
- ProcLinkTest → proc-crash-callbacks
- ProcMonitorTest → proc-crash-callbacks
- CircuitBreakerTest → proc-message-passing
- BulkheadIsolationTest → proc-basic-creation
- StateMachineTest → proc-basic-creation

**References TO** (what ProcTest links to):
- VirtualThreadPatternsTest → virtual-thread-basics (implementation)
- StructuredTaskScopeTest → structured-concurrency (foundation)

---

### SupervisorTest (Fault Tolerance)

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/test/SupervisorTest.java`

**Sections**:
1. `supervisor-crash-restart` - Crash and restart recovery
   - Automatic restart on crash
   - State reset to initial value
   - ProcRef stability across restarts

2. `supervisor-one-for-one-strategy` - Individual child restart
   - Only crashed child restarts
   - Siblings unaffected
   - Fault isolation

3. `supervisor-one-for-all-strategy` - All children restart
   - Cascaded restart on any crash
   - Use case: interdependent children
   - Consistency over availability

4. `supervisor-rest-for-one-strategy` - Tail restart
   - Crash and later children restart
   - Earlier children preserved
   - Startup order matters

5. `supervisor-restart-intensity` - Escalation thresholds
   - Max restarts per window
   - Supervisor gives up
   - Escalation up supervision tree

6. `supervisor-procref-stability` - Stable references
   - ProcRef survives restarts
   - Never hold raw Proc references
   - Dereferencing behavior

**Referenced BY** (who links to SupervisorTest):
- CircuitBreakerTest → supervisor-crash-restart, supervisor-procref-stability
- BulkheadIsolationTest → supervisor-one-for-one-strategy
- MultiTenantSupervisorTest → supervisor-restart-intensity
- DistributedSagaCoordinatorTest → supervisor-one-for-one-strategy
- BackpressureTest → supervisor-one-for-all-strategy
- HealthCheckManagerTest → supervisor-crash-restart

**References TO** (what SupervisorTest links to):
- ProcTest → proc-basic-creation (what it supervises)
- ProcRefTest → procref-stability (stable handles)
- CrashRecoveryTest → crash-escalation (failure propagation)

---

### StateMachineTest (gen_statem)

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/StateMachineTest.java`

**Sections**:
1. `statemachine-gen-statem-contract` - OTP contract implementation
   - (State, Event, Data) → Transition
   - Sealed transition types
   - Exhaustive pattern matching

2. `statemachine-transitions` - Next/Keep/Stop
   - Transition.Next (new state + data)
   - Transition.Keep (same state, new data)
   - Transition.Stop (terminate)

3. `statemachine-event-handling` - Event processing
   - Event-specific handlers
   - Deferred events
   - Timeout events

4. `statemachine-data-carrier` - Immutable data
   - Data parameter through transitions
   - Record-based data types
   - Type safety

**Referenced BY** (who links to StateMachineTest):
- DistributedSagaCoordinatorTest → statemachine-transitions
- OrderProcessingTest → statemachine-event-handling
- WorkflowEngineTest → statemachine-gen-statem-contract

**References TO** (what StateMachineTest links to):
- ProcTest → proc-basic-creation (implementation base)
- PatternMatchingPatternsTest → exhaustive-switches (type safety)

---

### EventManagerTest (Typed Events)

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/test/EventManagerTest.java`

**Sections**:
1. `eventmanager-typed-events` - Sealed event hierarchies
   - Event type safety
   - Sealed interface events
   - Pattern matching on events

2. `eventmanager-subscription` - Handler registration
   - Subscribe to event types
   - Per-handler processes
   - Unsubscription semantics

3. `eventmanager-broadcast` - Event delivery
   - Fan-out to all subscribers
   - Delivery order guarantees
   - Async processing

4. `eventmanager-fault-isolation` - Handler crashes
   - Handler crash doesn't kill bus
   - Per-handler supervision
   - Crash isolation

**Referenced BY** (who links to EventManagerTest):
- EventBusTest → eventmanager-broadcast
- MessageBusPatternsTest → eventmanager-typed-events
- ReactiveMessagingTest → eventmanager-fault-isolation

**References TO** (what EventManagerTest links to):
- ProcTest → proc-message-passing (per-handler processes)
- SupervisorTest → supervisor-one-for-one-strategy (handler supervision)

---

### ProcRefTest (Stable References)

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/test/ProcRefTest.java`

**Sections**:
1. `procref-stability` - Survives restarts
   - ProcRef vs Proc
   - Dereferencing behavior
   - Supervisor integration

2. `procref-lifecycle` - Reference management
   - Creating ProcRef
   - Validating references
   - Cleaning up

**Referenced BY** (who links to ProcRefTest):
- SupervisorTest → procref-stability
- CircuitBreakerTest → procref-stability
- BulkheadIsolationTest → procref-lifecycle

**References TO** (what ProcRefTest links to):
- ProcTest → proc-basic-creation (referencing processes)
- SupervisorTest → supervisor-one-for-one-strategy (restart stability)

---

## Enterprise Patterns

### CircuitBreakerTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/CircuitBreakerTest.java`

**Sections**:
1. `circuit-breaker-closed-state` - Normal operation
   - Requests pass through
   - Failures tracked
   - Success resets counter

2. `circuit-breaker-open-state` - Failure threshold
   - Threshold exceeded
   - Requests suppressed
   - Failure fast behavior

3. `circuit-breaker-half-open-state` - Recovery probe
   - Timeout expires
   - Test request allowed
   - Transition on result

4. `circuit-breaker-supervision` - Supervisor integration
   - Supervised state machine
   - Crash recovery
   - State persistence

**Referenced BY** (who links to CircuitBreakerTest):
- EnterpriseIntegrationTest → circuit-breaker-closed-state
- ResiliencePatternsTest → circuit-breaker-supervision
- SpringBootMigrationTest → circuit-breaker-half-open-state

**References TO** (what CircuitBreakerTest links to):
- SupervisorTest → supervisor-one-for-one-strategy (fault tolerance)
- StateMachineTest → statemachine-transitions (state management)
- ResultTest → result-error-handling (sealed results)

---

### DistributedSagaCoordinatorTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/saga/DistributedSagaCoordinatorTest.java`

**Sections**:
1. `saga-compensating-transactions` - LIFO rollback
   - Forward actions
   - Compensating transactions
   - LIFO compensation order

2. `saga-orchestration` - Coordinator pattern
   - Saga coordinator
   - Step sequencing
   - Timeout handling

3. `saga-timeout-handling` - Step timeouts
   - Per-step timeouts
   - Timeout triggers compensation
   - Partial rollback

4. `saga-failure-recovery` - Compensation execution
   - Failure detection
   - Compensation chain
   - Recovery completion

**Referenced BY** (who links to DistributedSagaCoordinatorTest):
- OrderProcessingTest → saga-compensating-transactions
- PaymentSystemTest → saga-orchestration
- MicroserviceIntegrationTest → saga-timeout-handling

**References TO** (what DistributedSagaCoordinatorTest links to):
- StateMachineTest → statemachine-transitions (saga state)
- SupervisorTest → supervisor-one-for-one-strategy (step supervision)
- EventManagerTest → eventmanager-broadcast (compensation events)

---

### BulkheadIsolationTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/bulkhead/BulkheadIsolationEnterpriseTest.java`

**Sections**:
1. `bulkhead-resource-pools` - Partitioned resources
   - Thread pool isolation
   - Semaphore isolation
   - Resource limits

2. `bulkhead-strategy` - Thread pool vs semaphore
   - ThreadPoolBulkhead
   - SemaphoreBulkhead
   - Use cases

3. `bulkhead-supervision` - Fault containment
   - Supervised pools
   - Crash isolation
   - Pool recovery

**Referenced BY** (who links to BulkheadIsolationTest):
- MultiTenantSaaSTest → bulkhead-resource-pools
- ResourceConstrainedSystemTest → bulkhead-strategy
- CloudNativeDeploymentTest → bulkhead-supervision

**References TO** (what BulkheadIsolationTest links to):
- SupervisorTest → supervisor-one-for-one-strategy (pool supervision)
- ProcRefTest → procref-stability (stable pool references)
- VirtualThreadPatternsTest → virtual-thread-pools (lightweight pools)

---

### BackpressureTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/backpressure/BackpressureTest.java`

**Sections**:
1. `backpressure-strategies` - Rate limiting approaches
   - Fixed rate limiting
   - Adaptive throttling
   - Load shedding

2. `backpressure-monitoring` - Metrics and thresholds
   - Queue depth monitoring
   - Processing lag metrics
   - Threshold alerts

3. `backpressure-rejection` - Load shedding
   - Reject oldest
   - Reject newest
   - Graceful degradation

**Referenced BY** (who links to BackpressureTest):
- HighThroughputSystemTest → backpressure-strategies
- StreamProcessingTest → backpressure-monitoring
- RateLimitingTest → backpressure-rejection

**References TO** (what BackpressureTest links to):
- EventManagerTest → eventmanager-broadcast (event rate control)
- SupervisorTest → supervisor-one-for-all-strategy (cascaded throttling)
- ProcTest → proc-message-passing (mailbox backpressure)

---

### HealthCheckManagerTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/health/HealthCheckManagerTest.java`

**Sections**:
1. `health-check-liveness` - Process health
   - Process liveness checks
   - Supervisor tree health
   - Crash detection

2. `health-check-readiness` - Service readiness
   - Dependency readiness
   - Startup probe
   - Traffic readiness

3. `health-check-aggregation` - Composite health
   - Multiple health checks
   - Composite status
   - Partial degradation

**Referenced BY** (who links to HealthCheckManagerTest):
- ProductionDeploymentTest → health-check-liveness
- KubernetesIntegrationTest → health-check-readiness
- ServiceMeshTest → health-check-aggregation

**References TO** (what HealthCheckManagerTest links to):
- ProcTest → proc-introspection (process health)
- SupervisorTest → supervisor-crash-restart (tree health)
- EventManagerTest → eventmanager-fault-isolation (bus health)

---

## EIP Patterns

### SplitterTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/SplitterTest.java`

**Sections**:
1. `splitter-message-decomposition` - Breaking messages apart
   - Message splitting logic
   - Sequence generation
   - Metadata preservation

2. `splitter-sequential-vs-parallel` - Processing strategies
   - Sequential processing
   - Parallel processing
   - Ordering guarantees

**Referenced BY** (who links to SplitterTest):
- ScatterGatherTest → splitter-message-decomposition
- RoutingPatternsTest → splitter-sequential-vs-parallel
- ContentBasedRouterTest → splitter-message-decomposition

**References TO** (what SplitterTest links to):
- AggregatorTest → aggregator-message-correlation (reassembly)
- ChannelPatternsTest → point-to-point-channel (output routing)

---

### AggregatorTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/AggregatorTest.java`

**Sections**:
1. `aggregator-message-correlation` - Matching responses
   - Correlation identifiers
   - Message grouping
   - Timeout handling

2. `aggregator-completion-strategies` - Timeout/size-based
   - Size-based completion
   - Timeout-based completion
   - Custom predicates

3. `aggregator-strategies` - Collection strategies
   - Aggregation logic
   - Result construction
   - Error handling

**Referenced BY** (who links to AggregatorTest):
- ScatterGatherTest → aggregator-message-correlation
- RoutingPatternsTest → aggregator-completion-strategies
- SplitterTest → aggregator-message-correlation

**References TO** (what AggregatorTest links to):
- SplitterTest → splitter-message-decomposition (source of messages)
- CorrelationIdTest → correlation-id-generation (message matching)

---

### ContentBasedRouterTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/ContentBasedRouterTest.java`

**Sections**:
1. `router-content-evaluation` - Message inspection
   - Content predicates
   - Type-based routing
   - Custom evaluators

2. `router-destination-selection` - Channel selection
   - Channel mapping
   - Default routing
   - Unmatched messages

**Referenced BY** (who links to ContentBasedRouterTest):
- DynamicRouterTest → router-content-evaluation
- RoutingPatternsTest → router-destination-selection
- ScatterGatherTest → router-destination-selection

**References TO** (what ContentBasedRouterTest links to):
- ChannelPatternsTest → point-to-point-channel (destinations)
- MessagePatternsTest → command-message (evaluation)

---

### ScatterGatherTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/ScatterGatherTest.java`

**Sections**:
1. `scatter-gather-composition` - Splitter + Aggregator
   - Combined pattern
   - Scatter phase
   - Gather phase

2. `scatter-gather-timeouts` - Response aggregation
   - Overall timeout
   - Partial responses
   - Timeout handling

3. `scatter-gather-failure-handling` - Partial failures
   - Individual failures
   - Successful subset
   - Error aggregation

**Referenced BY** (who links to ScatterGatherTest):
- EnterpriseIntegrationTest → scatter-gather-composition
- ParallelProcessingTest → scatter-gather-timeouts
- ResiliencePatternsTest → scatter-gather-failure-handling

**References TO** (what ScatterGatherTest links to):
- SplitterTest → splitter-message-decomposition (scatter)
- AggregatorTest → aggregator-message-correlation (gather)
- ChannelPatternsTest → publish-subscribe-channel (parallel routing)

---

## Dogfood Tests (JOTP using JOTP)

### GenServerTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/GenServerTest.java`

**Sections**:
1. `genserver-call-cast-pattern` - Request-response vs fire-and-forget
   - call/2 (synchronous)
   - cast/2 (async)
   - OTP gen_server contract

2. `genserver-lifecycle` - init/terminate/handle_info
   - Initialization
   - Termination
   - Info messages

**Referenced BY**: N/A (foundational pattern)

**References TO**:
- ProcTest → proc-message-passing (implementation)
- SupervisorTest → supervisor-crash-restart (fault tolerance)

---

### ApplicationExampleTest

**File**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/dogfood/otp/ApplicationExampleTest.java`

**Sections**:
1. `application-lifecycle` - Start/stop supervision tree
   - Application specification
   - Supervision tree setup
   - Graceful shutdown

2. `application-topology` - Tree structure
   - Root supervisor
   - Child specs
   - Dependency ordering

**Referenced BY**: N/A (usage examples)

**References TO**:
- SupervisorTest → supervisor-restart-intensity (restart strategies)
- ProcTest → proc-basic-creation (process creation)

---

## Missing Documentation

### Tests Without DTR Integration

The following test files should be migrated to use DTR:

**Core OTP**:
- [ ] ProcLinkTest - Bidirectional crash propagation
- [ ] ProcMonitorTest - One-way crash monitoring
- [ ] ProcRegistryTest - Name-based process lookup
- [ ] ProcTimerTest - Scheduled message delivery
- [ ] ProcLibTest - Process utilities
- [ ] CrashRecoveryTest - Isolated execution
- [ ] ParallelTest - Structured concurrency
- [ ] ResultTest - Railway-oriented error handling

**Enterprise**:
- [ ] EventBusTest - Enterprise event bus
- [ ] MultiTenantSupervisorTest - Tenant isolation
- [ ] EnterpriseRecoveryTest - Recovery patterns

**EIP**:
- [ ] RecipientListRouterTest - Broadcast routing
- [ ] ResequencerTest - Message ordering
- [ ] RoutingSlipTest - Itinerary-based routing
- [ ] DynamicRouterTest - Runtime routing
- [ ] MessageFilterTest - Conditional filtering
- [ ] ComposedMessageProcessorTest - Processing pipeline

**Messaging**:
- [ ] PointToPointChannelTest - Direct messaging
- [ ] PublishSubscribeChannelTest - Broadcast messaging
- [ ] DataTypeChannelTest - Typed channels
- [ ] CommandMessageTest - Command objects
- [ ] DocumentMessageTest - Document objects
- [ ] EnvelopeWrapperTest - Message metadata
- [ ] ClaimCheckTest - Reference-based messaging
- [ ] DeadLetterChannelTest - Failed message handling
- [ ] IdempotentReceiverTest - Duplicate suppression
- [ ] MessageExpirationTest - TTL handling
- [ ] ProcessManagerTest - Stateful orchestration

---

## Cross-Reference Statistics

### Coverage Metrics
- **Total Test Classes**: 100+
- **Classes with DTR**: 40+
- **Classes with Cross-Refs**: 0 (target: 100%)
- **Total Sections**: 200+
- **Sections with Anchors**: 0 (target: 100%)

### Relationship Density
- **Core → Core**: 15 expected references
- **Core → Enterprise**: 25 expected references
- **Core → EIP**: 20 expected references
- **Enterprise → Enterprise**: 10 expected references
- **EIP → EIP**: 15 expected references

### Priority References

**High Priority** (must have):
1. Supervisor → Proc (fault tolerance foundation)
2. CircuitBreaker → Supervisor (implementation)
3. Saga → StateMachine (state management)
4. Splitter → Aggregator (EIP composition)
5. All Enterprise → Supervisor (fault tolerance)

**Medium Priority** (should have):
1. Proc → Virtual Threads (implementation)
2. EventManager → Proc (per-handler processes)
3. Bulkhead → ProcRef (stable references)
4. All EIP → Channel patterns (routing)

**Low Priority** (nice to have):
1. Dogfood → Core patterns (usage examples)
2. Performance tests → Core patterns (optimization)
3. Stress tests → Fault tolerance (reliability)

---

## Next Steps

1. **Implement Phase 1**: Add anchors and references to Core OTP tests
2. **Implement Phase 2**: Add anchors and references to Enterprise tests
3. **Implement Phase 3**: Add anchors and references to EIP tests
4. **Validation**: Implement link checker and orphan detector
5. **Documentation**: Generate reference graph visualization

---

**Index Version**: 1.0
**Last Updated**: 2025-03-15
**Maintainer**: Documentation Team
