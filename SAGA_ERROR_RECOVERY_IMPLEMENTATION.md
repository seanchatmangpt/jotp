# Saga Error Recovery Implementation

## Overview

This document describes the comprehensive error recovery mechanism implemented in `SagaOrchestrator.java` for fault-tolerant distributed transaction coordination.

## Features Implemented

### 1. Crash Recovery from Event Store (`recoverFromCrash`)

**Purpose**: Recover saga state after a coordinator process crash by replaying the event log.

**Implementation**:
- Loads all events for a saga from the event store
- Reconstructs exact saga state by replaying events in order
- Handles recovery from any point in the saga lifecycle (during forward phase or compensation)
- Returns reconstructed `SagaContext` for resumption

**Key Benefits**:
- Durability: Sagas survive coordinator crashes
- Transparency: Automatic recovery without user intervention
- Consistency: Event sourcing ensures exact state reconstruction

### 2. Event-Driven State Reconstruction (`reconstructSagaState`)

**Purpose**: Replay saga events to reconstruct the state at time of crash.

**State Machine Transitions**:
- `StepAttempt`: Logged for observability, doesn't change state
- `StepSucceeded`: Adds result to completed steps
- `StepFailed`: Transitions to COMPENSATING phase
- `CompensationStarted`: Enters compensation phase
- `CompensationCompleted`: Completes compensation

**Key Design**:
- Uses sealed pattern matching on `SagaEvent` types
- Maintains immutable maps and lists for saga context
- Preserves step results for resumption

### 3. Advanced Error Recovery Handler (`handleErrorRecovery`)

**Purpose**: Classify errors and apply appropriate recovery strategies.

**Error Classification**:

| Classification | Examples | Strategy |
|---|---|---|
| **TRANSIENT** | TimeoutException, ConnectException, InterruptedException | Retry with exponential backoff |
| **PERMANENT** | IllegalArgumentException, SecurityException | Fail immediately, start compensation |
| **PARTIAL** | IllegalStateException (with "already" message) | Idempotent retry with deduplication |

**Recovery Flow**:
1. Classify error based on exception type
2. Log classification for observability
3. Apply appropriate strategy

### 4. Error Classification (`classifyError`)

**Classification Logic**:
```
TimeoutException → TRANSIENT (retry)
ConnectException → TRANSIENT (retry)
InterruptedException → TRANSIENT (retry)
IllegalArgumentException → PERMANENT (fail)
SecurityException → PERMANENT (fail)
IllegalStateException (with "already") → PARTIAL (idempotent)
Others → TRANSIENT (default retry)
```

### 5. Transient Error Handling (`handleTransientError`)

- Network timeouts and temporary unavailability
- Retried with exponential backoff (100ms * 2^attempt + jitter)
- Maximum attempts configurable per step
- Prevents cascading failures through circuit breaker pattern

### 6. Permanent Error Handling (`handlePermanentError`)

- Invalid input, bad state, authentication failures
- Fail fast without retries
- Immediately start compensation
- Cannot be recovered by retry alone

### 7. Partial Error Handling (`handlePartialError`)

- Step partially completed (payment charged but response lost)
- Idempotent retry with request deduplication
- Checks if step already completed before reexecuting
- Safe for "at least once" semantics

### 8. Compensation Retry and Failure Policies

**Compensation Failure Policy Enum**:
- `CONTINUE`: Log and move to next compensation (best-effort)
- `RETRY`: Retry with exponential backoff
- `DEADLETTER`: Send to dead letter queue after max attempts

**Policy Determination** (`getCompensationFailurePolicy`):
```java
if (attemptCount >= maxAttempts)
    return DEADLETTER;  // Max retries exhausted
if (error instanceof TimeoutException || ConnectException)
    return RETRY;       // Network error, always retry
return CONTINUE;        // Other errors, best-effort
```

**Enhanced `executeCompensation` Method**:
- Retries compensation up to 3 times
- Uses exponential backoff with jitter
- Applies failure policy (continue/retry/deadletter)
- Logs all compensation attempts for audit trail

### 9. Step Retry with Exponential Backoff

**Backoff Formula**:
```
delay = min(300s, 100ms * 2^attempt + jitter)
jitter = random(0-100ms)
```

**Features**:
- Exponential growth prevents overwhelming failing service
- Jitter prevents thundering herd (synchronized retries)
- 5-minute maximum prevents indefinite delays
- Configurable via `calculateBackoff` method

## Saga Error Recovery Workflow

### Forward Phase Failure
```
Step N fails with error E
↓
classifyError(E)
↓
TRANSIENT? → Retry with backoff
PERMANENT? → Start compensation
PARTIAL? → Idempotent retry
```

### Compensation Phase
```
For each completed step (in reverse):
  ↓
Execute compensation
  ↓
On compensation timeout/error:
  ├→ RETRY: Sleep with backoff, retry
  ├→ DEADLETTER: Log, send to DLQ
  └→ CONTINUE: Log, move to next compensation
  ↓
Complete saga with Compensated result
```

### Crash Recovery
```
SagaOrchestrator starts
↓
Load events for saga from EventStore
↓
reconstructSagaState(events)
↓
Determine recovery point
↓
If in forward phase: Resume step execution
If in compensation: Resume compensation
```

## Event Sourcing Integration

### Events Captured

The implementation captures these events for audit trail and recovery:

```java
sealed interface SagaEvent {
    record StepAttempt(String stepName, int attemptCount, Instant timestamp);
    record StepFailed(String stepName, String errorMessage, int attemptCount, Instant timestamp);
    record StepSucceeded(String stepName, Object result, Instant timestamp);
    record CompensationStarted(String failedStep, String reason, Instant timestamp);
    record CompensationCompleted(List<String> compensatedSteps, Instant timestamp);
}
```

### Event Store Usage

- **Append**: Record events during saga execution
- **Load**: Retrieve events for crash recovery
- **Replay**: Reconstruct saga state by replaying all events
- **Audit**: Complete event trail for compliance

## Thread Safety & Concurrency

### Safety Guarantees

1. **Virtual Thread Safe**: Works with Java 21+ virtual threads
2. **Message-Driven**: All state changes via `SagaMsg` (actor model)
3. **Immutable State**: `SagaContext` is immutable record
4. **ConcurrentHashMap**: Thread-safe saga storage
5. **CompletableFuture**: Proper async/await patterns

### Concurrent Sagas

- Each saga has independent `SagaRuntime` instance
- No shared mutable state between sagas
- Error in one saga doesn't affect others
- Isolation via sealed message protocol

## Idempotency Patterns

### Step Idempotency
- Steps designed to be idempotent (safe to retry)
- `maxRetries` limits retry attempts
- `retryOn` predicate controls error handling

### Compensation Idempotency
- Compensation can be safely retried multiple times
- Step results stored for compensation use
- Completed steps tracked in immutable list

### Partial Failure Idempotency
- Checks if step already completed before retry
- Skips reexecution if result already stored
- Prevents duplicate charges, inventory deductions, etc.

## Dead Letter Queue Support

Failed compensations that exhaust retries are logged with:
- Saga ID
- Failed step name
- Error message and stack trace
- Attempt count

Operations team can:
1. Investigate failure root cause
2. Manually complete compensation if needed
3. Trigger saga retry after fix
4. Update system based on partial state

## Performance Characteristics

### Memory
- O(active_sagas * avg_steps) for saga state
- Event log size depends on saga complexity

### Latency
- Forward phase: O(steps * avg_step_latency)
- Compensation phase: O(steps * avg_compensation_latency)
- Retry backoff adds delay (configurable)

### Throughput
- Limited by slowest step in saga
- Concurrent sagas execute independently
- Virtual threads enable millions of concurrent sagas

## Example Usage

### Basic Saga with Error Recovery

```java
SagaOrchestrator<String, OrderData> saga = SagaOrchestrator.builder("order-processing")
    .step(SagaOrchestrator.Step.named("reserve-inventory")
        .action((data, ctx) -> inventoryService.reserve(data.items))
        .compensation((data, result) -> inventoryService.release(result))
        .maxRetries(3)
        .retryOn(t -> t instanceof TimeoutException)
        .build())
    .step(SagaOrchestrator.Step.named("charge-payment")
        .action((data, ctx) -> paymentService.charge(data.amount))
        .compensation((data, result) -> paymentService.refund(result))
        .build())
    .eventStore(EventStore.create())  // Enable crash recovery
    .globalTimeout(Duration.ofMinutes(10))
    .build();

// Execute saga
CompletableFuture<SagaOrchestrator.SagaResult> future = saga.execute(orderData);

// Handle result
future.thenAccept(result -> {
    switch (result) {
        case SagaOrchestrator.SagaResult.Success success ->
            System.out.println("Order processed: " + success.results());
        case SagaOrchestrator.SagaResult.Compensated comp ->
            System.err.println("Order failed, compensation completed: " + comp.failedStep());
        case SagaOrchestrator.SagaResult.Failure failure ->
            System.err.println("Saga failed: " + failure.error());
    }
});
```

### Crash Recovery

```java
// After coordinator restart
Optional<SagaContext> recovered = saga.recoverFromCrash(sagaId);

if (recovered.isPresent()) {
    SagaContext ctx = recovered.get();
    if (ctx.status() == SagaStatus.COMPENSATING) {
        // Resume compensation from last completed step
        saga.resumeCompensation(sagaId);
    } else if (ctx.status() == SagaStatus.IN_PROGRESS) {
        // Resume step execution from current step
        saga.resumeExecution(sagaId);
    }
}
```

## Testing

Comprehensive test suite in `SagaErrorRecoveryTest.java` covers:

1. **Transient error retry** with exponential backoff
2. **Permanent error** immediate compensation
3. **Crash recovery** from event store
4. **Compensation retry** and failure policies
5. **Multi-step compensation** in reverse order
6. **Idempotent compensations** prevent duplicates
7. **Concurrent saga** independence
8. **Global timeout** handling

Run tests with:
```bash
mvnd test -Dtest=SagaErrorRecoveryTest
```

## JOTP Integration

### Sealed Types
- Error classification uses sealed enum
- Saga events use sealed interface for exhaustive patterns
- Saga results use sealed interface (Success/Failure/Compensated)

### Virtual Threads
- Step execution uses virtual threads for lightweight concurrency
- Backoff sleep doesn't block platform threads
- Millions of concurrent sagas possible

### Event Manager Integration
- Can emit `SagaEvent` via `EventManager<SagaEvent>`
- Real-time saga monitoring and dashboards
- Alerting on permanent failures

### Supervision Tree Integration
- Saga orchestrator can be child of supervisor
- Crash recovery restores saga on parent restart
- One-for-one strategy for isolated saga failure

## Limitations & Future Enhancements

### Current Limitations
- No built-in distributed lock (requires user implementation)
- Compensation assumes undo is possible
- No saga pause/resume by user request

### Future Enhancements
1. Dead letter queue implementation
2. Saga pause/resume API
3. Distributed locking support
4. Saga replay from specific event
5. Compensation-only mode for manual fixes
6. Metrics integration (Micrometer)
7. Tracing integration (OpenTelemetry)

## References

- Vaughn Vernon: "Designing Event-Driven Systems"
- Joe Armstrong: "Erlang and OTP in Action"
- Chris Richardson: "Microservices Patterns"
- JOTP Architecture: `/home/user/jotp/docs/ARCHITECTURE.md`
