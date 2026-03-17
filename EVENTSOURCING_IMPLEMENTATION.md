# Event Sourcing & Message Queue Patterns Implementation

## Overview

Comprehensive implementation of event sourcing, CQRS, sagas, and message queue patterns for JOTP. Production-ready framework with PostgreSQL and Redis backends.

## Deployment Locations

All files deployed to: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/eventsourcing/`

### Core Implementation Files (12)

1. **Event.java** - Base event interface with sealed types and metadata
2. **Snapshot.java** - Snapshot record for optimization
3. **EventStoreInterface.java** - Append-only event store contract
4. **PostgresEventStore.java** - PostgreSQL implementation with ACID transactional guarantees
5. **RedisEventStore.java** - Redis Streams with consumer groups and acknowledgments
6. **InMemoryEventStore.java** - In-memory implementation for testing
7. **EventProjection.java** - Material views with thread-safe read-write locks
8. **SagaStep.java** - Sealed types for distributed transaction steps
9. **SagaCoordinator.java** - Saga orchestration with compensation and recovery
10. **DeadLetterQueue.java** - Failed message handling with metrics and retry
11. **EventDeduplicator.java** - Idempotency with Redis cache and PostgreSQL history
12. **CQRS.java** - Command and Query buses with type-safe routing

### Test Files (6)

1. **EventSourcingTest.java** - Core append-replay-snapshot functionality
2. **SagaCoordinatorTest.java** - Saga execution and compensation
3. **DeadLetterQueueTest.java** - DLQ message handling and statistics
4. **EventDeduplicatorTest.java** - Deduplication and caching
5. **CQRSTest.java** - CQRS pattern implementation
6. **EventSourcingIntegrationTest.java** - Full lifecycle scenarios

### Documentation

- **README.md** - Usage guide with examples and configuration

## Component Breakdown

### 1. Event Store

**Interface**: `EventStoreInterface`

```java
long append(String aggregateId, List<Event> events);
long append(String aggregateId, List<Event> events, long expectedVersion); // Optimistic concurrency
List<Event> getEvents(String aggregateId, long fromVersion);
void saveSnapshot(String aggregateId, Snapshot snapshot);
Optional<Snapshot> getSnapshot(String aggregateId);
```

**Implementations**:
- **PostgresEventStore**: ACID transactional, immutable event table, snapshot optimization
- **RedisEventStore**: Redis Streams, consumer groups, TTL-based rotation
- **InMemoryEventStore**: ConcurrentHashMap, fast testing

### 2. Event Projection

**Class**: `EventProjection` (abstract)

Features:
- Thread-safe with ReentrantReadWriteLock
- Incremental event application
- Material views for fast queries
- Automatic version tracking

Examples:
- `OrderProjection` - Tracks order status and items
- `CustomerProjection` - Tracks customer state
- `CountProjection` - Event type counting

### 3. Sagas

**Classes**: `SagaStep`, `SagaCoordinator`

**SagaStep Types**:
- `Action` - Execute with compensation
- `ConditionalStep` - Branching based on context
- `SequentialSteps` - Linear execution
- `ParallelSteps` - Concurrent execution

**Features**:
- Distributed transaction coordination
- Compensation-based rollback
- PostgreSQL state persistence
- Automatic recovery on timeout
- Context carries state through steps

### 4. Dead Letter Queue

**Class**: `DeadLetterQueue`

Features:
- Failed event capture
- Retry with max attempt limits
- PostgreSQL storage with indexes
- Metrics collection every 5 minutes
- Alert handler on threshold
- Failure reason tracking
- DLQ size monitoring

### 5. Event Deduplicator

**Class**: `EventDeduplicator`

Features:
- Idempotency via event ID
- Redis-like cache with TTL
- PostgreSQL historical store
- Cache hit tracking
- Automatic cleanup (7-day retention)
- Processing result caching

### 6. CQRS

**Classes**: `CQRS.CommandBus`, `CQRS.QueryBus`, `CQRS.Facade`

Features:
- Separate command and query models
- Type-safe handler registration
- Example command types: CreateOrder, CancelOrder, ProcessPayment
- Example query types: GetOrder, GetCustomerOrders, GetOrderStats
- Sealed result types

## Database Schema

### PostgreSQL Events Table
```sql
CREATE TABLE events (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  aggregate_id VARCHAR(255) NOT NULL,
  version BIGINT NOT NULL,
  event_type VARCHAR(255) NOT NULL,
  data BYTEA NOT NULL,
  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  correlation_id VARCHAR(255),
  UNIQUE(aggregate_id, version)
);
CREATE INDEX idx_events_aggregate_id ON events(aggregate_id);
CREATE INDEX idx_events_timestamp ON events(timestamp);
CREATE INDEX idx_events_type ON events(event_type);
```

### PostgreSQL Snapshots Table
```sql
CREATE TABLE snapshots (
  id BIGSERIAL PRIMARY KEY,
  aggregate_id VARCHAR(255) NOT NULL UNIQUE,
  version BIGINT NOT NULL,
  data BYTEA NOT NULL,
  timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  metadata TEXT
);
```

### PostgreSQL Saga State Table
```sql
CREATE TABLE saga_state (
  id BIGSERIAL PRIMARY KEY,
  saga_id VARCHAR(255) NOT NULL UNIQUE,
  definition BYTEA NOT NULL,
  context BYTEA NOT NULL,
  status VARCHAR(50) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_steps TEXT,
  failed_steps TEXT
);
```

### PostgreSQL DLQ Table
```sql
CREATE TABLE dead_letter_queue (
  id BIGSERIAL PRIMARY KEY,
  message_id VARCHAR(255) NOT NULL UNIQUE,
  aggregate_id VARCHAR(255),
  event_data BYTEA NOT NULL,
  error_message TEXT,
  error_stack BYTEA,
  retry_count INT DEFAULT 0,
  max_retries INT DEFAULT 3,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  last_retry_at TIMESTAMP WITH TIME ZONE,
  status VARCHAR(50) DEFAULT 'PENDING'
);
```

### PostgreSQL Deduplication Table
```sql
CREATE TABLE event_deduplication (
  id BIGSERIAL PRIMARY KEY,
  event_id VARCHAR(255) NOT NULL UNIQUE,
  aggregate_id VARCHAR(255) NOT NULL,
  processed BOOLEAN DEFAULT FALSE,
  result BYTEA,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  processed_at TIMESTAMP WITH TIME ZONE
);
```

## Redis Usage

### Event Stream (RedisEventStore)
```
Redis Streams: stream:{aggregateId}
Consumer Groups: event-processor
TTL: Configurable (default 24 hours)
```

### Deduplication Cache (EventDeduplicator)
```
Redis Cache: dedup:{eventId}
TTL: Configurable (default 1 hour)
```

## Test Coverage

### EventSourcingTest (11 tests)
- Append and replay events
- Optimistic concurrency control
- Snapshot optimization
- Projection consistency
- Multiple aggregate streams
- Partial replay
- Snapshot deletion

### SagaCoordinatorTest (10 tests)
- Step execution and compensation
- Context state tracking
- Failure handling
- Conditional and parallel steps
- Compensation order

### DeadLetterQueueTest (11 tests)
- Message creation and tracking
- Retry mechanisms
- Statistics calculation
- Failure reason tracking
- Queue overflow detection

### EventDeduplicatorTest (11 tests)
- Duplicate detection
- Result caching
- Statistics tracking
- TTL expiration
- Cache eviction

### CQRSTest (15 tests)
- Command/query registration
- Multiple handlers
- Command execution with side effects
- Query result types
- Handler routing

### EventSourcingIntegrationTest (10 tests)
- Full order lifecycle
- Projection failure recovery
- Multi-projection consistency
- Versioned snapshots
- Thread-safe projection updates

## Performance Characteristics

| Operation | Time | Implementation |
|-----------|------|-----------------|
| Append event | O(1) | Direct insert |
| Get event | O(1) | Index lookup |
| Snapshot | O(log n) | B-tree |
| Full replay | O(n) | Sequential scan |
| Projection query | O(1) | HashMap |
| Dedup cache hit | O(1) | ConcurrentHashMap |
| Saga step | O(1) | Local execution |
| DLQ send | O(log n) | B-tree insert |

## Failure Scenarios Handled

1. **Event Loss**: WAL + replication
2. **Duplicate Processing**: Idempotency keys + deduplication
3. **Partial Saga Failure**: Compensation with rollback
4. **Projection Failure**: Automatic replay from events
5. **DLQ Overflow**: Alert handler triggers
6. **Cache Expiration**: Fall back to persistent store
7. **Concurrency Issues**: Optimistic lock with version check

## Integration Points

- **ProcRef**: Emit events on state changes
- **Supervisor**: Replay events on recovery
- **ProcRegistry**: Project service catalog
- **EventManager**: Broadcast events to subscribers
- **StateMachine**: Event-driven transitions

## Usage Quick Start

```java
// 1. Create event store
EventStoreInterface store = new PostgresEventStore(url, user, pass);

// 2. Append events
Event event = new Event.DomainEvent(metadata, payload);
long version = store.append(aggregateId, List.of(event));

// 3. Create projection
EventProjection.OrderProjection proj = new EventProjection.OrderProjection();
store.getEvents(aggregateId).forEach(proj::apply);

// 4. Set up saga
SagaCoordinator coordinator = new SagaCoordinator(url, user, pass);
boolean success = coordinator.executeSaga(sagaId, definition);

// 5. Handle failures
DeadLetterQueue dlq = new DeadLetterQueue(url, user, pass);
dlq.send(message);

// 6. Prevent duplicates
EventDeduplicator dedup = new EventDeduplicator(url, user, pass);
if (!dedup.isDuplicate(eventId)) {
    Object result = processEvent(event);
    dedup.markProcessed(eventId, result);
}

// 7. Use CQRS
CQRS.Facade cqrs = new CQRS.Facade();
cqrs.commands().register(...);
cqrs.queries().register(...);
```

## Compilation

```bash
cd /home/user/jotp
./mvnw compile --enable-preview -q
./mvnw test --enable-preview -q -Dtest=EventSourcingTest
./mvnw verify --enable-preview -q
```

## Future Enhancements

1. Event versioning with migration
2. Event encryption at rest
3. Cross-region replication
4. GraphQL query API
5. Temporal query API (as-of, valid-from)
6. Event compaction/archival
7. Distributed saga coordinator
8. Event streaming to Kafka
9. Analytics via event log
10. Event schema registry

## References

- File paths: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/eventsourcing/`
- Tests: `/home/user/jotp/src/test/java/io/github/seanchatmangpt/jotp/eventsourcing/`
- Docs: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/eventsourcing/README.md`
