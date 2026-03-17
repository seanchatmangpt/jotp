# Event Sourcing Architecture

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      JOTP Application                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │   ProcRef    │  │ Supervisor   │  │ EventManager │           │
│  └──────────────┘  └──────────────┘  └──────────────┘           │
│         │                 │                   │                   │
└─────────┼─────────────────┼───────────────────┼───────────────────┘
          │                 │                   │
          └─────────────────┴───────────────────┘
                      │
        ┌─────────────┴─────────────┐
        │                           │
   ┌────▼────────────────┐  ┌──────▼────────────────┐
   │   Event Emitter     │  │  Command Dispatcher   │
   └────┬────────────────┘  └──────┬────────────────┘
        │                          │
        └──────────────┬───────────┘
                       │
        ┌──────────────▼──────────────────┐
        │     Event Store Interface       │
        │  (Append-Only, Immutable)       │
        ├─────────────────────────────────┤
        │ - append(aggregateId, events)   │
        │ - getEvents(aggregateId)        │
        │ - getCurrentVersion()           │
        │ - saveSnapshot()                │
        │ - getSnapshot()                 │
        └──────────────┬──────────────────┘
        ┌─────────────┴─────────────────────────────┐
        │                                           │
   ┌────▼─────────────┐  ┌────▼──────────────┐  ┌─▼─────────────┐
   │ PostgresEventStore│  │ RedisEventStore   │  │InMemoryEventStore
   │                  │  │                   │  │
   │ - ACID Guarantee │  │ - Redis Streams   │  │ - Fast Testing
   │ - WAL            │  │ - Consumer Groups │  │ - Development
   │ - Durability     │  │ - TTL Rotation    │  │
   └────┬─────────────┘  └────┬──────────────┘  └─┬──────────────┘
        │                     │                    │
        │        ┌────────────┴────────────┐      │
        │        │                         │      │
   ┌────▼────────▼────────────┐   ┌────────▼──┐
   │    PostgreSQL Database    │   │  Redis    │
   │                           │   │  Cluster  │
   │ - events table            │   │           │
   │ - snapshots table         │   │ - Streams │
   │ - saga_state table        │   │ - Cache   │
   │ - dead_letter_queue table │   │           │
   │ - dedup table             │   │           │
   └───────────────────────────┘   └───────────┘
```

## Event Flow

```
Application Layer
    │
    ├─► Emit Event
    │   └─► Event{id, aggregateId, type, payload}
    │
    ├─► Event Store
    │   ├─► Append (transactional)
    │   ├─► Index (aggregate_id, timestamp)
    │   └─► Update Version Cache
    │
    ├─► Projection Subscriber
    │   ├─► Receive Event
    │   ├─► Apply to Material View
    │   └─► Update Read Model
    │
    ├─► Deduplicator
    │   ├─► Check Event ID Cache
    │   ├─► Mark as Processed
    │   └─► Cache Result
    │
    ├─► CQRS Bus
    │   ├─► Route to Handler
    │   ├─► Command: Update aggregate
    │   └─► Query: Read projection
    │
    └─► Saga Orchestrator
        ├─► Execute Step
        ├─► Apply Compensation
        └─► Persist State
```

## Event Sourcing Pattern

```
Aggregate State Evolution
┌──────────────────────────────────────────────────────┐
│  Time ──────────────────────────────────────────►    │
├──────────────────────────────────────────────────────┤
│                                                       │
│  Event 1: OrderCreated                                │
│  ├─ aggregateId: order-123                           │
│  ├─ version: 1                                        │
│  ├─ timestamp: 2026-03-17T10:00:00Z                  │
│  └─ payload: {customer: "C001", total: 0}            │
│         │                                             │
│         ├─► State 1: {status: CREATED, items: []}    │
│         │                                             │
│  Event 2: ItemAdded                                   │
│  ├─ aggregateId: order-123                           │
│  ├─ version: 2                                        │
│  └─ payload: {item: "Laptop", price: 999}            │
│         │                                             │
│         ├─► State 2: {status: CREATED, items: [Laptop]}
│         │                                             │
│  Event 3: OrderSubmitted                              │
│  ├─ aggregateId: order-123                           │
│  ├─ version: 3                                        │
│  └─ payload: {paymentMethod: "CARD"}                 │
│         │                                             │
│         └─► State 3: {status: SUBMITTED, items: [...]}
│                                                       │
│                      ▼ (Optional)                     │
│              Snapshot at v3                           │
│         {state: State3, version: 3}                   │
│                                                       │
│  Event 4: OrderConfirmed                              │
│  ├─ aggregateId: order-123                           │
│  ├─ version: 4                                        │
│  └─ payload: {confirmationId: "CONF-123"}            │
│         │                                             │
│         └─► State 4: {status: CONFIRMED, items: [...]}
│                                                       │
└──────────────────────────────────────────────────────┘

Replay from Version 3:
1. Load snapshot at v3
2. Start from state: {status: SUBMITTED, items: [...]}
3. Apply Event 4 (only 1 event instead of 4)
4. Final state: {status: CONFIRMED, items: [...]}
```

## Projection Pattern

```
Event Stream
    │
    ├─► OrderCreated
    │   └─► Projection: {order-123:status = "CREATED"}
    │
    ├─► ItemAdded (x2)
    │   ├─► Projection: {order-123:items_count = 1}
    │   └─► Projection: {order-123:items_count = 2}
    │
    ├─► OrderSubmitted
    │   └─► Projection: {order-123:status = "SUBMITTED"}
    │
    └─► OrderConfirmed
        └─► Projection: {order-123:status = "CONFIRMED"}

Final Projection State:
{
  "order-123:status": "CONFIRMED",
  "order-123:items_count": 2,
  "order-123:created": "2026-03-17T10:00:00Z"
}

Query:
SELECT * FROM projection WHERE order-123:status = "CONFIRMED"
Result: (instant, no event replay needed)
```

## Saga Coordination Pattern

```
┌───────────────────────────────────────────────────────────┐
│              Distributed Transaction (Saga)               │
└───────────────────────────────────────────────────────────┘

Step 1: ReserveInventory
┌─────────────────────────┐
│ Action                  │
├─────────────────────────┤
│ ctx.put("reserved", ✓) │
└─────────────────────────┘
         │
         ├─ Success ──────────────────┐
         │                             │
    Step 2: ProcessPayment         Compensate 1
    ┌──────────────────┐          ┌──────────────┐
    │ Action           │          │ Release      │
    ├──────────────────┤          │ Inventory    │
    │ ctx.put("paid",✓)│          └──────────────┘
    └──────────────────┘                 ▲
             │                            │
             ├─ Success ─┐               │
             │           │               │
        Step 3:      Failure ────────────┘
        ConfirmOrder    │
        ┌────────────┐   │
        │ Action     │   │
        │ Complete   │   │
        └────────────┘   │
             │           │
             ├─ Success  │
             │      Compensate 2
             │      ┌──────────────┐
             │      │ Refund       │
             │      │ Payment      │
             │      └──────────────┘
             │           ▲
             │           │
         COMMITTED   ROLLBACK
         Status:     Status:
         COMPLETED   FAILED
```

## Deduplication Pattern

```
Request 1: ProcessOrder (eventId: EVT-123)
    │
    ├─► Deduplicator.isDuplicate(EVT-123)
    │   └─ Cache: Miss (ConcurrentHashMap)
    │   └─ Database: Miss (PostgreSQL)
    │   └─ Result: Not a duplicate
    │
    ├─► Process Event
    │   └─ Reserved Inventory
    │   └─ Processed Payment
    │   └─ Created Order
    │
    └─► Deduplicator.markProcessed(EVT-123, result)
        ├─ Cache: Hit (add to ConcurrentHashMap, TTL=1h)
        └─ Database: Insert with processed=true

Request 2: ProcessOrder (eventId: EVT-123) [RETRY]
    │
    ├─► Deduplicator.isDuplicate(EVT-123)
    │   └─ Cache: Hit! (within 1 hour)
    │   └─ Result: Is a duplicate
    │
    ├─► Deduplicator.getResult(EVT-123)
    │   └─ Returns cached: {reserved: ✓, paid: ✓, ...}
    │
    └─ NO SIDE EFFECTS (exact idempotence)

Request 3: ProcessOrder (eventId: EVT-123) [MUCH LATER]
    │
    ├─► Deduplicator.isDuplicate(EVT-123)
    │   └─ Cache: Miss (expired after 1 hour)
    │   └─ Database: Hit (PostgreSQL)
    │   └─ Result: Is a duplicate
    │
    ├─► Deduplicator.getResult(EVT-123)
    │   └─ Cache + return: {reserved: ✓, paid: ✓, ...}
    │
    └─ NO SIDE EFFECTS (idempotence preserved)
```

## CQRS Separation

```
Command Side (Write Model)          Query Side (Read Model)
                │
    CreateOrder │                    OrderProjection
    │           │                    │
    ├─► Event   ├──────────────────►├─ aggregate_id:status
    │   Store   │    Events          │- aggregate_id:items
    │   │       │                    │- aggregate_id:total
    │   └───────┤                    │
    │           │
    Snapshot    │                    CustomerProjection
    │           │                    │
    └───────────┤                    ├─ customer_id:orders
                │                    │- customer_id:lifetime_value
                │
            Command Bus            Query Bus
            │                       │
    Handle CreateOrder      GetOrder
    Handle CancelOrder       GetCustomerOrders
    Handle ProcessPayment    GetOrderStats

Write optimized              Read optimized
(Single aggregate)           (Denormalized)
(Transactional)              (Eventually consistent)
```

## Dead Letter Queue Pattern

```
Event Processing Pipeline
    │
    ├─► Process Successfully ──► Acknowledge ──► Done
    │
    └─► Process Fails
        │
        └─► Send to DLQ
            ├─► Increment retry_count
            ├─► Record error_message
            ├─► Persist to PostgreSQL
            │
            └─► Retry Scheduler (every minute)
                │
                ├─ retry_count < max_retries (3)
                │  └─ Re-queue for processing
                │
                └─ retry_count >= max_retries
                   └─ Mark as EXHAUSTED
                   └─ Alert: Max retries exceeded

DLQ Metrics (every 5 minutes)
├─ queueSize: 42
├─ totalFailed: 150
├─ avgRetryCount: 1.8
├─ oldestMessageAge: 45 min
└─ failureReasons: ["Timeout", "Network error"]

Alert Handler
└─ Triggered when: queueSize > 10,000
   └─ Action: Page on-call engineer
```

## Thread Safety

```
Event Projection (OrderProjection)
┌──────────────────────────────────┐
│  ReadWriteLock (ReentrantRWLock) │
├──────────────────────────────────┤
│                                  │
│  Read Side (Multiple)    Write Side (Single)
│  ├─ query(key)           ├─ apply(event)
│  ├─ getState()           ├─ reset()
│  └─ version()            │
│                          └─ (lock held during apply)
│
│  1000+ concurrent reads can occur
│  while single event is being applied
│
│  Prevents:
│  ├─ Dirty reads
│  ├─ Lost updates
│  ├─ Race conditions
│  └─ Partial state views
│
└──────────────────────────────────┘
```

## Recovery Scenarios

```
Scenario 1: Process Crashes During Event Processing
┌──────────────────────┐
│ PostgreSQL Event Log │
│  - Event 1: OK ✓     │
│  - Event 2: OK ✓     │
│  - Event 3: ?        │ (partial write before crash)
└──────────────────────┘
         │
         └─► Restart
             ├─ Load snapshot at v2
             ├─ Replay from v2
             └─ Continue from v3
                 Result: No loss, exactly-once semantics

Scenario 2: Duplicate Event Received
┌──────────────────────────┐
│  Event: EVT-123 (Retry)  │
└──────────────────────────┘
         │
         ├─► Deduplicator.isDuplicate("EVT-123")
         │   └─ TRUE (already processed)
         │
         └─ Return cached result
            (No side effects)

Scenario 3: Saga Partial Failure
┌──────────────────────────────────┐
│ Step 1: Reserve (✓)              │
│ Step 2: Pay (✗ Network timeout)  │
└──────────────────────────────────┘
         │
         └─► Recovery Scheduler detects stale saga
             ├─ Compensate Step 1 (Release inventory)
             └─ Mark saga as FAILED
```

## Performance Timeline

```
Append Event:
  Network           1ms
  Disk I/O          5ms
  Index Update      1ms
  ─────────────────────
  Total            ~7ms (P99)

Snapshot Optimization:
  Without snapshot:
    Replay 1000 events:        ~50ms
  With snapshot at v500:
    Load snapshot              ~1ms
    Replay 500 events          ~25ms
    ─────────────────────────────
    Total                     ~26ms (48% faster)

Projection Query (material view):
  HashMap lookup              ~1µs (microsecond!)
  Compared to event replay    ~50ms

Deduplication:
  Cache hit (ConcurrentHashMap): ~10ns (nanosecond!)
  Database hit (PostgreSQL):      ~5ms
```

## File Structure

```
eventsourcing/
├── Core Interfaces
│   ├── Event.java (sealed interface)
│   ├── EventStoreInterface.java
│   └── Snapshot.java
│
├── Implementations
│   ├── PostgresEventStore.java
│   ├── RedisEventStore.java
│   └── InMemoryEventStore.java
│
├── Projections
│   └── EventProjection.java
│       ├── OrderProjection
│       ├── CustomerProjection
│       └── CountProjection
│
├── Sagas
│   ├── SagaStep.java (sealed)
│   │   ├── Action
│   │   ├── ConditionalStep
│   │   ├── SequentialSteps
│   │   ├── ParallelSteps
│   │   └── SagaContext
│   └── SagaCoordinator.java
│
├── Message Queues
│   ├── DeadLetterQueue.java
│   └── EventDeduplicator.java
│
├── CQRS
│   └── CQRS.java
│       ├── CommandBus
│       ├── QueryBus
│       └── Facade
│
└── Documentation
    ├── README.md
    └── ARCHITECTURE.md
```
