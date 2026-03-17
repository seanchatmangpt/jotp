# JOTP Event Sourcing & Message Queue Patterns

Production-ready event sourcing framework with CQRS, sagas, and message queue patterns for JOTP.

## Architecture

### Core Components

1. **Event Store Interface** (`EventStoreInterface`)
   - Append-only immutable event log
   - Optimistic concurrency control
   - Snapshot optimization for fast replay

2. **Implementations**
   - `InMemoryEventStore` - Fast testing and development
   - `PostgresEventStore` - Durable ACID persistence
   - `RedisEventStore` - High-throughput with Streams

3. **Projections** (`EventProjection`)
   - Material views built from events
   - Thread-safe with read-write locks
   - Automatic event application
   - Built-in examples: OrderProjection, CustomerProjection, CountProjection

4. **Sagas** (`SagaCoordinator`, `SagaStep`)
   - Distributed transaction coordination
   - Compensation-based rollback
   - Sealed types for exhaustive switching
   - Step types: Action, Conditional, Sequential, Parallel

5. **Dead Letter Queue** (`DeadLetterQueue`)
   - Failed event handling
   - Retry mechanisms with max attempts
   - Metrics and monitoring
   - Alert handling on overflow

6. **Deduplication** (`EventDeduplicator`)
   - Idempotency via event IDs
   - Hot cache for recent events
   - Historical store in PostgreSQL
   - 7-day cleanup policy

7. **CQRS** (`CQRS`)
   - Command and Query buses
   - Separate read/write models
   - Type-safe handler registration
   - Example command/query types

## Usage Examples

### Basic Event Sourcing

```java
EventStoreInterface store = new InMemoryEventStore();

// Create events
String orderId = "order-123";
Event.EventMetadata meta = Event.EventMetadata.create(
    orderId, 0, "OrderCreated", "correlation-id"
);
Event event = new Event.DomainEvent(meta, "order payload");

// Append events
long version = store.append(orderId, List.of(event));

// Replay events
List<Event> events = store.getEvents(orderId);

// Save snapshot for optimization
Snapshot snap = Snapshot.of(orderId, 1, "persisted state");
store.saveSnapshot(orderId, snap);

// Retrieve snapshot
Optional<Snapshot> snapshot = store.getSnapshot(orderId);
```

### Projections

```java
EventProjection.OrderProjection projection = new EventProjection.OrderProjection();

// Apply events
List<Event> events = store.getEvents(orderId);
events.forEach(projection::apply);

// Query projection
Optional<Object> status = projection.query(orderId + ":status");

// Get full state
Map<String, Object> state = projection.getState();

// Rebuild from events
projection.reset();
events.forEach(projection::apply);
```

### Sagas

```java
SagaCoordinator coordinator = new SagaCoordinator(dbUrl, dbUser, dbPass);

// Define saga steps
SagaStep reserveStep = new SagaStep.Action(
    "reserve-inventory",
    ctx -> {
        ctx.put("reserved", true);
        return true;
    },
    ctx -> {
        ctx.put("reserved", false);
        return true;
    }
);

SagaStep paymentStep = new SagaStep.Action(
    "process-payment",
    ctx -> {
        ctx.put("paid", true);
        return true;
    },
    ctx -> {
        ctx.put("paid", false);
        return true;
    }
);

// Execute saga
SagaCoordinator.SagaDefinition definition =
    SagaCoordinator.SagaDefinition.sequential(
        "order-saga", reserveStep, paymentStep
    );

boolean success = coordinator.executeSaga("saga-1", definition);
```

### Dead Letter Queue

```java
DeadLetterQueue dlq = new DeadLetterQueue(dbUrl, dbUser, dbPass, 10000, msg -> {
    System.err.println("Alert: DLQ message " + msg.messageId());
});

// Send failed message
Exception cause = new RuntimeException("Processing failed");
DeadLetterQueue.DLQMessage msg =
    DeadLetterQueue.DLQMessage.of("msg-1", "order-123", event, cause);
dlq.send(msg);

// Retrieve messages
List<DeadLetterQueue.DLQMessage> messages = dlq.receive(10);

// Retry processing
dlq.retry("msg-1");

// Get statistics
DeadLetterQueue.DLQStats stats = dlq.getStats();
System.out.println("Queue size: " + stats.queueSize());
System.out.println("Failure reasons: " + stats.failureReasons());
```

### Event Deduplication

```java
EventDeduplicator dedup = new EventDeduplicator(dbUrl, dbUser, dbPass);

String eventId = UUID.randomUUID().toString();

// Check if duplicate
if (dedup.isDuplicate(eventId)) {
    Optional<Object> result = dedup.getResult(eventId);
    return result; // Return cached result
}

// Process event
Object result = processEvent(event);

// Mark as processed
dedup.markProcessed(eventId, result);

// Get statistics
EventDeduplicator.DeduplicationStats stats = dedup.getStats();
```

### CQRS Pattern

```java
CQRS.Facade cqrs = new CQRS.Facade();

// Register command handler
cqrs.commands().register(
    CQRS.Command.CreateOrder.class,
    cmd -> {
        // Execute command, emit events
        return "Order created";
    }
);

// Register query handler
cqrs.queries().register(
    CQRS.Query.GetOrder.class,
    query -> {
        // Query read model
        return new CQRS.QueryResult.OrderResult(
            query.orderId(), "pending", List.of(), 0
        );
    }
);

// Execute
String cmdResult = cqrs.commands().execute(
    new CQRS.Command.CreateOrder("o1", "c1", List.of())
);

CQRS.QueryResult.OrderResult queryResult = cqrs.queries().execute(
    new CQRS.Query.GetOrder("o1")
);
```

## Key Features

### Append-Only Events
- Events are never modified, only appended
- Immutable audit trail
- Full event history for compliance

### Optimistic Concurrency Control
```java
store.append(aggregateId, events, expectedVersion);
```

### Snapshots for Performance
- Avoid replaying 1000+ events
- Save at regular intervals
- Fast path: snapshot + remaining events

### Thread-Safe Projections
- Lock-free reads with read-write locks
- Concurrent event application
- Safe for multi-threaded queries

### Saga Compensation
- Forward and compensating actions
- Automatic rollback on failure
- Idempotent step execution

### Event Deduplication
- Prevent duplicate processing
- Redis cache for hot events
- PostgreSQL for historical records
- TTL-based cleanup

## Configuration

### PostgreSQL Backend

```java
EventStoreInterface store = new PostgresEventStore(
    "jdbc:postgresql://localhost:5432/jotp",
    "user",
    "password"
);
```

### Redis Streams

```java
RedisEventStore store = new RedisEventStore(
    "localhost",
    6379,
    86400 // TTL in seconds
);
```

### In-Memory for Testing

```java
EventStoreInterface store = new InMemoryEventStore();
```

## Performance Characteristics

| Operation | Time | Notes |
|-----------|------|-------|
| Append event | O(1) | Constant time append |
| Get event | O(1) | Direct lookup |
| Snapshot | O(log n) | B-tree lookup |
| Projection replay | O(n) | Linear scan |
| Deduplication cache | O(1) | Hot cache hit |
| DLQ send | O(log n) | B-tree insert |

## Failure Scenarios

### Event Loss Prevention
- PostgreSQL WAL provides durability
- Redis Streams replication for HA
- DLQ captures failed events

### Duplicate Prevention
- Event deduplication with idempotency keys
- Exactly-once semantics for sagas
- Idempotent command handlers

### Partial Failure Recovery
- Saga compensation for distributed transactions
- DLQ for replay analysis
- Event snapshots for fast recovery

## Integration with JOTP Primitives

```java
// ProcRef can emit events
ProcRef<State, Message> proc = ...;
eventStore.append(proc.id(), List.of(event));

// Supervisor can replay events on recovery
Supervisor supervisor = ...;
List<Event> recovered = eventStore.getEvents(supervisorId);

// Registry can project service state
ProcRegistry registry = ...;
orderProjection.getState().forEach((key, value) -> ...);

// EventManager can distribute events
EventManager<DomainEvent> bus = ...;
events.forEach(bus::publish);
```

## Testing

Run tests with:
```bash
mvn test -Dtest=EventSourcingTest
mvn test -Dtest=SagaCoordinatorTest
mvn test -Dtest=DeadLetterQueueTest
mvn test -Dtest=EventDeduplicatorTest
mvn test -Dtest=CQRSTest
mvn test -Dtest=EventSourcingIntegrationTest
```

## References

- Greg Young - "Event Sourcing" patterns
- Fowler & Pramod - "Microservices Patterns"
- CQRS in Practice - Domain-Driven Design
- Erlang/OTP Mnesia - Distributed transaction model
- Apache Kafka - Event stream architecture
