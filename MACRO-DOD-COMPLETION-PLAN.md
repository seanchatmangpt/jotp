# JOTP Macro Definition of Done - Completion Plan

**Generated:** 2026-03-16
**Status:** Comprehensive 5-agent analysis complete
**Objective:** Finish JOTP to meet production Macro DoD requirements

---

## Executive Summary

JOTP has a **strong foundation** (~65-75% complete) but requires focused work in 5 critical areas to achieve full Macro DoD compliance:

| Area | Status | Key Gaps | Priority |
|------|--------|----------|----------|
| **Core Contract & Persistence** | 70% | Message replay, recovery orchestration | HIGH |
| **Supervision & Restart** | 75% | Sub-1ms crash detection, state persistence integration | HIGH |
| **Distributed Systems** | 75% | Real network transport, API transparency | HIGH |
| **Java 26 & Observability** | 80% | Virtual thread scalability, ZGC config, event persistence | MEDIUM |
| **Testing & Production Readiness** | 60% | Fault injection, JVM crash simulation, production validation | CRITICAL |

**Overall Assessment:** JOTP has excellent architecture and solid implementation of core OTP primitives. The remaining work is primarily in **production readiness validation**, **distributed system completion**, and **testing coverage** for failure modes.

---

## Phase 1: Critical Production Blockers (Weeks 1-4)

### 1.1 Fault Injection & Testing Infrastructure

**Why Critical:** Cannot claim production readiness without systematic failure validation.

#### Task 1.1.1: Implement FaultInjectionSupervisor
**Effort:** M (3-5 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/supervision/FaultInjectionSupervisor.java`

```java
/**
 * Specialized supervisor for controlled crash injection in testing.
 * Supports configurable failure rates, patterns, and systematic failure scenarios.
 */
public final class FaultInjectionSupervisor extends Supervisor {
    private final FaultPattern faultPattern;
    private final double failureRate;

    public record FaultPattern(
        Set<Class<? extends Throwable>> exceptionTypes,
        IntRange messageCountRange,
        Duration injectAfter
    ) {}

    // Inject faults systematically for testing
}
```

**Requirements:**
- Configurable failure rates (0.1% to 100%)
- Message count-based fault injection
- Exception type specification
- Integration with existing test framework

#### Task 1.1.2: Message Sequence Recording Framework
**Effort:** M (4-6 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/testing/MessageSequenceRecorder.java`

```java
/**
 * Records message sequences for deterministic replay and validation.
 * Enables "same result on repeated runs" guarantee.
 */
public final class MessageSequenceRecorder {
    public void record(ProcRef<?, ?> from, ProcRef<?, ?> to, Object message, long sequence);
    public List<RecordedMessage> getSequence(ProcRef<?, ?> proc);
    public ValidationResult validateReplay(List<RecordedMessage> original, List<RecordedMessage> replay);
}
```

**Requirements:**
- Deterministic message capture
- Sequence validation and replay
- Integration with DTR narrative testing
- Zero overhead when disabled

#### Task 1.1.3: True JVM Crash Simulation
**Effort:** M (3-4 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/fault/JVMCrashSimulation.java`

```java
/**
 * Simulates true JVM crashes for recovery validation.
 * Uses SIGKILL or equivalent to terminate JVM without shutdown hooks.
 */
public final class JVMCrashSimulation {
    public static void crashSimulate(CrashType type);
    public static CrashRecoveryResult validateRecovery(Path crashDump);
}
```

**Requirements:**
- Multi-process JVM testing
- Crash dump validation
- Recovery verification
- Integration with existing CrashDumpCollector

---

### 1.2 Distributed System Completion

**Why Critical:** Current implementation uses simulated replication - not production-ready.

#### Task 1.2.1: Real Network Transport for DistributedMessageLog
**Effort:** M (5-7 days)
**Files:**
- `src/main/java/io/github/seanchatmangpt/jotp/distributed/RocksDBDistributedMessageLog.java`
- `src/main/java/io/github/seanchatmangpt/jotp/distributed/rpc/ReplicationRPCService.java`

```java
/**
 * Replace simulated replication with actual RPC communication.
 * Currently uses simulateReplicationToNode() - needs real gRPC/netty.
 */
public final class ReplicationRPCService {
    public CompletableFuture<AckResult> replicateToNode(
        InetSocketAddress node,
        byte[] message,
        long sequence
    );

    // Handle network errors, retries, deduplication
}
```

**Requirements:**
- Real inter-node RPC (gRPC or custom netty)
- Message deduplication across nodes
- Proper error handling and retry logic
- TLS encryption for production

#### Task 1.2.2: Remote Message Transparency
**Effort:** S (2-3 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/distributed/TransparentProcRef.java`

```java
/**
 * Provides identical APIs for local and remote processes.
 * Routes messages transparently based on process location.
 */
public final class TransparentProcRef<S, M> implements ProcRef<S, M> {
    private final ProcRef<S, M> delegate;
    private final GlobalProcRegistry registry;

    @Override
    public void send(M message) {
        if (registry.isRemote(delegate)) {
            sendRemotely(delegate, message);
        } else {
            delegate.send(message);
        }
    }
}
```

**Requirements:**
- Location-transparent message sending
- Automatic routing optimization
- Network failure detection
- Fallback to local when available

---

### 1.3 Supervision Performance

**Why Critical:** Macro DoD requires sub-1ms crash detection - currently unvalidated.

#### Task 1.3.1: Sub-1ms Crash Detection Implementation
**Effort:** M (4-6 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java`

```java
/**
 * Optimize crash detection for sub-1ms latency.
 * Current implementation uses virtual thread event loop - may add latency.
 */
private final class OptimizedCrashDetector {
    private static final long TARGET_LATENCY_NS = 1_000_000; // 1ms in nanoseconds

    void processCrashNotification(ProcRef<?, ?> proc, Throwable reason) {
        long start = System.nanoTime();
        // Direct processing without virtual thread scheduling
        handleCrash(proc, reason);
        long elapsed = System.nanoTime() - start;

        if (elapsed > TARGET_LATENCY_NS) {
            metrics.recordSlowCrashDetection(elapsed);
        }
    }
}
```

**Requirements:**
- Nano-precision timing measurements
- Direct callback processing (no virtual thread scheduling)
- Performance regression tests
- Continuous monitoring

#### Task 1.3.2: Process State Persistence Integration
**Effort:** L (8-10 days)
**Files:**
- `src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java`
- `src/main/java/io/github/seanchatmangpt/jotp/persistence/ProcessStateManager.java`

```java
/**
 * Integrate state persistence with supervisor lifecycle.
 * Each state change is atomically persisted with ACK markers.
 */
public final class ProcessStateManager<S> {
    public void persistState(ProcRef<S, ?> proc, S state);
    public S restoreState(ProcRef<S, ?> proc);
    public void recordAck(ProcRef<?, ?> proc, long sequence);
}
```

**Requirements:**
- Automatic state persistence on each handler invocation
- Integration with AtomicStateWriter
- State restoration on supervisor restart
- Consistency validation

---

## Phase 2: Enhanced Recovery & Orchestration (Weeks 5-8)

### 2.1 Recovery Coordination

**Why Important:** Current recovery is fragmented - needs orchestration layer.

#### Task 2.1.1: Recovery State Machine
**Effort:** L (5-7 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/persistence/RecoveryStateMachine.java`

```java
/**
 * State machine guiding crash recovery process.
 * States: IDLE → ANALYZING → REPLAYING → VERIFYING → RESUMING → COMPLETED
 */
public sealed interface RecoveryState permits RecoveryState.Idle, RecoveryState.Analyzing, /*...*/ {
    record Idle() implements RecoveryState {}
    record Analyzing(CrashDump dump) implements RecoveryState {}
    record Replaying(List<PendingMessage> messages) implements RecoveryState {}
    record Verifying(ValidationResult result) implements RecoveryState {}
    record Resuming(ProcRef<?, ?> proc) implements RecoveryState {}
    record Completed(Duration totalTime) implements RecoveryState {}
}
```

**Requirements:**
- Explicit recovery phases with validation
- Rollback capability on failure
- Progress tracking and metrics
- Integration with CrashDumpCollector

#### Task 2.1.2: Message Replay Coordinator
**Effort:** M (4-5 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/persistence/MessageReplayCoordinator.java`

```java
/**
 * Coordinates message replay across all processes during recovery.
 * Handles throttling, batching, and ordering guarantees.
 */
public final class MessageReplayCoordinator {
    public void replayMessages(ProcRef<?, ?> proc, RecoveryPlan plan);
    public void throttleReplay(RateLimiter limiter);
    public List<ReplayResult> getPendingReplays();
}
```

**Requirements:**
- Sequence-based replay ordering
- Throttling to prevent overload
- Batch replay for efficiency
- Progress tracking and cancellation

#### Task 2.1.3: Crash Dump Analyzer
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/crash/CrashDumpAnalyzer.java`

```java
/**
 * Analyzes crash dumps to guide recovery strategy selection.
 * Pattern recognition, consistency verification, recommendations.
 */
public final class CrashDumpAnalyzer {
    public RecoveryStrategy analyze(CrashDump dump);
    public ConsistencyReport verifyConsistency(CrashDump dump);
    public List<Recommendation> getRecommendations(CrashDump dump);
}
```

**Requirements:**
- Pattern recognition in crash dumps
- Recovery strategy recommendations
- Consistency verification
- Forensic analysis capabilities

---

### 2.2 Enhanced Persistence Features

**Why Important:** Basic persistence exists - needs production-grade features.

#### Task 2.2.1: Column Family Manager
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/persistence/ColumnFamilyManager.java`

```java
/**
 * Advanced column family lifecycle management.
 * Automatic creation, compaction strategies, performance monitoring.
 */
public final class ColumnFamilyManager {
    public void createColumnFamily(String name, ColumnFamilyOptions options);
    public void configureCompaction(String name, CompactionStyle style);
    public ColumnFamilyMetrics getMetrics(String name);
}
```

**Requirements:**
- Automatic column family lifecycle
- Configurable compaction strategies
- Performance monitoring per family
- Cross-family atomic operations

#### Task 2.2.2: WAL Archival System
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/persistence/WALArchiver.java`

```java
/**
 * WAL rotation, archiving, and point-in-time recovery.
 * Compressed archival with corruption detection.
 */
public final class WALArchiver {
    public void rotateWAL(SizeTrigger sizeTrigger, TimeTrigger timeTrigger);
    public void archiveWAL(Path walPath, CompressionType compression);
    public RecoveryResult recoverFromArchive(Path archivePath, Instant timestamp);
}
```

**Requirements:**
- Size-based and time-based rotation
- Compressed archival
- Point-in-time recovery
- Corruption detection and recovery

---

## Phase 3: Java 26 & Observability Completion (Weeks 9-12)

### 3.1 Virtual Thread Scalability

**Why Important:** Macro DoD claims 1M+ processes - currently unvalidated.

#### Task 3.1.1: Virtual Thread Scalability Validation
**Effort:** L (4-5 weeks, includes testing)

**Files:**
- `src/test/java/io/github/seanchatmangpt/jotp/scale/VirtualThreadStressTest.java`
- `src/main/java/io/github/seanchatmangpt/jotp/observability/VirtualThreadMonitor.java`

```java
/**
 * Stress test for 1M+ virtual thread scalability.
 * Validates memory usage, GC impact, scheduling latency.
 */
@DtrTest
class VirtualThreadStressTest {
    @Test
    void validateOneMillionProcesses() {
        // Spawn 1M processes
        // Measure memory usage
        // Validate GC pauses < 1ms
        // Verify no thread starvation
    }
}
```

**Requirements:**
- 1M+ process creation and management
- Memory usage validation (~1KB per process)
- GC pause measurement (target < 1ms)
- Thread starvation detection
- Performance regression tests

#### Task 3.1.2: Virtual Thread Resource Management
**Effort:** M (2-3 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/observability/VirtualThreadResourceManager.java`

```java
/**
 * Monitor and manage virtual thread resources at scale.
 * Prevents resource exhaustion, provides visibility.
 */
public final class VirtualThreadResourceManager {
    public ThreadPoolMetrics getMetrics();
    public void setResourceLimits(ResourceLimits limits);
    public HealthStatus getHealthStatus();
}
```

**Requirements:**
- Resource usage monitoring
- Configurable limits
- Health status reporting
- Alerting on threshold breaches

---

### 3.2 JVM Configuration & GC

**Why Important:** Macro DoD requires <1ms GC pauses - needs configuration and validation.

#### Task 3.2.1: JVM Configuration Templates
**Effort:** M (2-3 days)
**Files:**
- `docs/deployment/jvm-configuration.md`
- `src/main/resources/jvm-templates/production-gc.sh`

```bash
# Production JVM configuration for JOTP with 1M+ processes
JAVA_OPTS="
  -XX:+UseZGC
  -XX:+ZGenerational
  -XX:ZCollectionInterval=0.01
  -XX:ZFragmentationLimit=25
  -XX:MaxInlineSize=32
  -XX:FreqInlineSize=325
  -Djdk.virtualThreadScheduler.parallelism=runtime
  -Djdk.virtualThreadScheduler.maxPoolSize=256
"
```

**Requirements:**
- ZGC configuration templates
- Virtual thread tuning parameters
- Memory sizing guidelines
- Production deployment scripts

#### Task 3.2.2: GC Pause Validation Framework
**Effort:** M (2-3 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/gc/GCPauseValidationTest.java`

```java
/**
 * Validate GC pause requirements under production load.
 * Ensures <1ms pauses even with 1M+ objects.
 */
@DtrTest
class GCPauseValidationTest {
    @Test
    void validateGCPausesUnderLoad() {
        // Run under ZGC with 1M objects
        // Measure pause times
        // Verify < 1ms requirement
    }
}
```

**Requirements:**
- GC pause measurement
- Load testing with realistic data
- Regression detection
- Continuous validation in CI/CD

---

### 3.3 Observability Enhancement

**Why Important:** Event persistence missing - critical for production debugging.

#### Task 3.3.1: RocksDB Event Persistence
**Effort:** L (4-5 weeks)

**Files:**
- `src/main/java/io/github/seanchatmangpt/jotp/observability/persistent/PersistentEventBus.java`
- `src/main/java/io/github/seanchatmangpt/jotp/observability/persistent/EventStreamQuery.java`

```java
/**
 * Persist all framework events to RocksDB for long-term analysis.
 * Enables event stream queries for debugging and auditing.
 */
public final class PersistentEventBus implements FrameworkEventBus {
    private final FrameworkEventBus delegate;
    private final EventStore eventStore;

    @Override
    public void publish(FrameworkEvent event) {
        delegate.publish(event); // Zero-overhead fast path
        eventStore.storeAsync(event); // Async persistence
    }
}

public interface EventStreamQuery {
    List<FrameworkEvent> query(EventFilter filter, TimeRange range);
    List<FrameworkEvent> getEventsForProc(ProcRef<?, ?> proc);
    List<FrameworkEvent> getCrashSequence(CrashId crashId);
}
```

**Requirements:**
- Async event persistence (non-blocking)
- Event stream query API
- Time-based filtering
- Process-centric event queries
- Crash sequence reconstruction

---

## Phase 4: Production Readiness Validation (Weeks 13-16)

### 4.1 Comprehensive Testing

**Why Critical:** Cannot ship to production without production-level validation.

#### Task 4.1.1: Node Crash Survival Tests
**Effort:** M (3-4 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/production/NodeCrashSurvivalIT.java`

```java
/**
 * Production integration test for node crash survival.
 * Validates complete system recovery after JVM termination.
 */
@DtrTest
@Tag("production-validation")
class NodeCrashSurvivalIT {
    @Test
    void surviveNodeCrashWithStateRecovery() {
        // Start multi-node cluster
        // Create processes with state
        // Kill JVM (SIGKILL)
        // Restart JVM
        // Validate all processes recovered with correct state
    }
}
```

**Requirements:**
- Multi-process JVM testing
- True crash simulation
- Complete state validation
- Recovery time measurement

#### Task 4.1.2: Distributed Log Recovery Validation
**Effort:** M (3-5 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/production/DistributedLogRecoveryIT.java`

```java
/**
 * Validate recovery from distributed message log.
 * Tests RocksDB corruption, network partitions, concurrent recovery.
 */
@DtrTest
@Tag("production-validation")
class DistributedLogRecoveryIT {
    @Test
    void recoverFromCorruptedLog() { /* ... */ }
    @Test
    void recoverDuringNetworkPartition() { /* ... */ }
    @Test
    void concurrentRecoveryMultipleNodes() { /* ... */ }
}
```

**Requirements:**
- Corruption recovery scenarios
- Network partition handling
- Concurrent recovery validation
- Cross-node state consistency

#### Task 4.1.3: Handler Timeout Testing Framework
**Effort:** M (2-4 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/production/HandlerTimeoutTest.java`

```java
/**
 * Validate handler timeout and recovery mechanisms.
 * Tests timeout under high load and concurrent processing.
 */
@DtrTest
@Tag("production-validation")
class HandlerTimeoutTest {
    @Test
    void timeoutRecoveryUnderLoad() { /* ... */ }
    @Test
    void timeoutDuringConcurrentProcessing() { /* ... */ }
}
```

**Requirements:**
- Timeout injection and validation
- High-load timeout testing
- Concurrent timeout scenarios
- Recovery verification

---

### 4.2 Production Metrics Validation

**Why Important:** Macro DoD claims specific performance characteristics - need validation.

#### Task 4.2.1: Observability Performance Validation
**Effort:** M (2-3 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/observability/ObservabilityPerformanceTest.java`

```java
/**
 * Validate 300ns observability overhead claim.
 * Benchmark hot path with and without observability.
 */
@DtrTest
class ObservabilityPerformanceTest {
    @Test
    void validate300nsOverhead() {
        // Measure hot path with observability disabled
        long baseline = measureHotPathWithoutObservability();

        // Measure hot path with observability enabled
        long withObservability = measureHotPathWithObservability();

        // Verify overhead < 300ns
        assertThat(withObservability - baseline).isLessThan(300);
    }
}
```

**Requirements:**
- 300ns overhead validation
- Scale testing (1M+ events/sec)
- Regression detection
- Continuous benchmarking

#### Task 4.2.2: Production Metrics Integration
**Effort:** M (3-4 days)
**Files:**
- `src/main/java/io/github/seanchatmangpt/jotp/observability/ProductionMetrics.java`
- `src/main/java/io/github/seanchatmangpt/jotp/observability/MetricsExporter.java`

```java
/**
 * Production-level metrics collection and export.
 * Integration with Prometheus, OpenTelemetry, or custom systems.
 */
public final class ProductionMetrics {
    private final MeterRegistry registry;

    public void recordCrashDetection(Duration latency);
    public void recordRecoveryTime(Duration duration);
    public void recordMessageProcessing(Duration latency);
}

public interface MetricsExporter {
    void export(ProductionMetrics metrics);
    void configureExport(ExportConfig config);
}
```

**Requirements:**
- Production metrics collection
- Export to standard formats
- Alerting integration
- Dashboard templates

---

### 4.3 Failure Mode Coverage

**Why Critical:** Macro DoD requires explicit handling of all failure modes.

#### Task 4.3.1: Comprehensive Failure Mode Tests
**Effort:** L (5-7 days)
**File:** `src/test/java/io/github/seanchatmangpt/jotp/production/FailureModeExhaustiveTest.java`

```java
/**
 * Comprehensive test coverage for all explicit failure modes.
 * JVM crashes, handler timeouts, duplicates, node death.
 */
@DtrTest
@Tag("production-validation")
class FailureModeExhaustiveTest {
    @Test void jvmCrashRecovery() { /* ... */ }
    @Test void handlerTimeoutHandling() { /* ... */ }
    @Test void duplicateMessageHandling() { /* ... */ }
    @Test void nodeDeathFailover() { /* ... */ }
    @Test void networkPartitionRecovery() { /* ... */ }
    @Test void diskFailureRecovery() { /* ... */ }
}
```

**Requirements:**
- All failure modes explicitly tested
- Production-level simulation
- Recovery time validation
- Metrics collection during failures

---

## Phase 5: Advanced Distributed Features (Weeks 17-20)

### 5.1 Enhanced Causality

**Why Important:** Basic causality exists - advanced features needed for production.

#### Task 5.1.1: Vector Clock Implementation
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/distributed/causality/VectorClock.java`

```java
/**
 * Vector clocks for distributed causality tracking.
 * Enables causal ordering and conflict detection.
 */
public final class VectorClock implements Comparable<VectorClock> {
    private final Map<NodeId, Long> timestamps;

    public boolean happensAfter(VectorClock other);
    public boolean isConcurrentWith(VectorClock other);
    public VectorClock merge(VectorClock other);
}
```

**Requirements:**
- Causal ordering implementation
- Conflict detection
- Clock merge operations
- Integration with message metadata

#### Task 5.1.2: Causal Message Delivery
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/distributed/causality/CausalMessageDelivery.java`

```java
/**
 * Causal ordering guarantees for message delivery.
 * Ensures messages delivered in causal order.
 */
public final class CausalMessageDelivery {
    public void deliverCausal(ProcRef<?, ?> recipient, Message message, VectorClock clock);
    public boolean canDeliver(Message message, VectorClock deliveryClock);
}
```

**Requirements:**
- Causal delivery guarantees
- Buffering for out-of-order messages
- Delivery optimization
- Integration with existing message passing

---

### 5.2 Network Partition Handling

**Why Important:** Current implementation has basic health checks - needs sophisticated handling.

#### Task 5.2.1: Network Partition Detector
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/distributed/partition/PartitionDetector.java`

```java
/**
 * Detect network partitions using phi accrual failure detector.
 * Provides probabilistic partition detection with tunable sensitivity.
 */
public final class PartitionDetector {
    private final PhiAccrualFailureDetector detector;

    public PartitionStatus detectPartition(NodeId node);
    public void setSensitivity(double phiThreshold);
}
```

**Requirements:**
- Phi accrual failure detector
- Configurable sensitivity
- Fast detection (< 1s)
- Low false positive rate

#### Task 5.2.2: Split-Brain Prevention
**Effort:** M (4-5 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/distributed/partition/SplitBrainPrevention.java`

```java
/**
 * Prevent split-brain scenarios during network partitions.
 * Uses quorum-based decision making and majority voting.
 */
public final class SplitBrainPrevention {
    public boolean canFormQuorum(Set<NodeId> availableNodes);
    public PartitionAction decideAction(PartitionEvent event);
}
```

**Requirements:**
- Quorum-based decision making
- Majority voting
- Automatic healing
- Graceful degradation

---

### 5.3 Consistency Configuration

**Why Important:** Production systems need tunable consistency levels.

#### Task 5.3.1: Configurable Consistency Levels
**Effort:** M (3-4 days)
**File:** `src/main/java/io/github/seanchatmangpt/jotp/distributed/consistency/ConsistencyLevel.java`

```java
/**
 * Configurable CAP theorem balance for distributed operations.
 * Strong, eventual, and causal consistency options.
 */
public enum ConsistencyLevel {
    STRONG,           // Linearizability, high latency
    CAUSAL,           // Causal consistency, medium latency
    EVENTUAL,         // Eventual consistency, low latency
    TUNABLE           // User-defined N/R/W configuration
}
```

**Requirements:**
- Multiple consistency levels
- Per-operation configuration
- Performance trade-offs documented
- Runtime switching capability

---

## Summary & Timeline

### Overall Timeline: 20 Weeks (~5 Months)

| Phase | Duration | Focus | Deliverables |
|-------|----------|-------|--------------|
| **Phase 1** | Weeks 1-4 | Critical Blockers | Fault injection, real network, sub-1ms crash detection |
| **Phase 2** | Weeks 5-8 | Recovery Orchestration | Recovery state machine, message replay, crash analysis |
| **Phase 3** | Weeks 9-12 | Java 26 Completion | Virtual thread scalability, ZGC config, event persistence |
| **Phase 4** | Weeks 13-16 | Production Validation | Node crash tests, recovery validation, metrics |
| **Phase 5** | Weeks 17-20 | Advanced Distributed | Causality, partition handling, tunable consistency |

### Task Count by Effort

- **Small (S):** 5 tasks (1-2 days each)
- **Medium (M):** 35 tasks (3-7 days each)
- **Large (L):** 8 tasks (8+ days each)

**Total Effort:** ~48 major tasks across 5 phases

### Critical Path to Production

**Minimum Viable Production (MVP):** Phases 1-2 only (~8 weeks)
- Enables crash-safe operation
- Real distributed messaging
- Basic recovery orchestration

**Full Macro DoD Compliance:** All 5 phases (~20 weeks)
- Includes scalability validation
- Production-level testing
- Advanced distributed features

---

## Risk Assessment

### High Risk Items

1. **Virtual Thread Scalability (Phase 3.1)**
   - **Risk:** 1M+ processes may not be achievable with current JVM
   - **Mitigation:** Early validation, performance tuning, fallback strategies

2. **Sub-1ms Crash Detection (Phase 1.3)**
   - **Risk:** Virtual thread scheduling may add latency
   - **Mitigation:** Direct callback implementation, continuous monitoring

3. **Real Network Transport (Phase 1.2)**
   - **Risk:** Complex edge cases in distributed communication
   - **Mitigation:** Incremental rollout, comprehensive testing

### Medium Risk Items

1. **Event Persistence Performance (Phase 3.3)**
   - **Risk:** Async persistence may impact hot path
   - **Mitigation:** Zero-overhead fast path, extensive benchmarking

2. **Recovery Coordination (Phase 2.1)**
   - **Risk:** Complex state machine may have edge cases
   - **Mitigation:** Formal verification, extensive testing

### Low Risk Items

1. **API Modernization (Phase 3)**
   - **Risk:** Minimal - incremental improvements
   - **Mitigation:** Backward compatibility maintenance

---

## Success Criteria

JOTP will meet the Macro Definition of Done when:

1. ✅ **Zero Data Loss:** All processes survive host node crashes with state recovery
2. ✅ **Zero Message Loss:** Distributed message log with quorum replication
3. ✅ **Deterministic Recovery:** Message replay in sequence order with idempotent handlers
4. ✅ **Sub-1ms Crash Detection:** Supervisor detects and initiates recovery in < 1ms
5. ✅ **1M+ Processes:** Virtual thread scalability validated with < 1ms GC pauses
6. ✅ **Transparent Distribution:** Local and remote APIs identical
7. ✅ **Observable:** All lifecycle events persisted to RocksDB with query API
8. ✅ **Explicit Failure Modes:** All failure modes tested and documented
9. ✅ **Production Validated:** Comprehensive testing under realistic conditions
10. ✅ **Zero Configuration:** No XML/YAML/properties files - pure Java code

---

## Next Steps

1. **Review this plan** with stakeholders for prioritization
2. **Allocate resources** based on critical path (Phases 1-2 first)
3. **Set up CI/CD** for continuous validation
4. **Create task tracking** in project management system
5. **Begin Phase 1** with FaultInjectionSupervisor implementation

---

**Document Status:** Ready for execution
**Last Updated:** 2026-03-16
**Version:** 1.0
