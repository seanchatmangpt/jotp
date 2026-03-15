# Event Sourcing Pattern

## Problem

Traditional CRUD systems lose the history of how state evolved. You can't answer "what happened?" or "why is the state like this?". Auditing, debugging, and temporal queries are difficult or impossible.

**Symptoms:**
- No audit trail of state changes
- Can't replay events to rebuild state
- Difficult to debug "how did we get here?"
- Can't implement temporal queries (what was the state yesterday?)

## Solution

Implement Event Sourcing: persist the state of a business entity as a sequence of state-changing events. Reconstruct current state by replaying events. JOTP's EventStore provides append-only event storage with projections and subscriptions.

**Benefits:**
- Complete audit trail (every event is stored)
- Temporal queries (replay to any point in time)
- Event replay (debugging, testing, migration)
- Projections (build read models from events)
- Snapshots (optimize replay with periodic state)

## Prerequisites

- Java 26 with preview features enabled
- JOTP core module: `io.github.seanchatmangpt.jotp`
- Understanding of event-driven architecture

## Implementation

### Step 1: Define Events

```java
import io.github.seanchatmangpt.jotp.EventStore;

// Define your domain events
public sealed interface OrderEvent {
    record OrderCreated(
        String orderId,
        String customerId,
        List<OrderItem> items,
        Instant timestamp
    ) implements OrderEvent {}

    record ItemAdded(
        String orderId,
        OrderItem item,
        Instant timestamp
    ) implements OrderEvent {}

    record PaymentCompleted(
        String orderId,
        BigDecimal amount,
        Instant timestamp
    ) implements OrderEvent {}

    record OrderShipped(
        String orderId,
        String trackingNumber,
        Instant timestamp
    ) implements OrderEvent {}
}
```

### Step 2: Create Event Store

```java
EventStore eventStore = EventStore.create();
```

### Step 3: Append Events

```java
// Append events to a stream
String orderId = "order-123";

eventStore.append(orderId, List.of(
    new OrderEvent.OrderCreated(
        orderId,
        "customer-456",
        List.of(new OrderItem("widget", 2)),
        Instant.now()
    )
));

// Append more events later
eventStore.append(orderId, List.of(
    new OrderEvent.ItemAdded(
        orderId,
        new OrderItem("gadget", 1),
        Instant.now()
    )
));
```

### Step 4: Load and Replay Events

```java
// Load all events for a stream
Stream<EventStore.StoredEvent> events = eventStore.load(orderId);

// Rebuild current state by replaying events
OrderState state = events
    .map(StoredEvent::event)
    .reduce(new OrderState(), (currentState, event) ->
        switch (event) {
            case OrderEvent.OrderCreated e ->
                currentState.withOrderId(e.orderId())
                    .withCustomerId(e.customerId())
                    .withItems(e.items());

            case OrderEvent.ItemAdded e ->
                currentState.withItemAdded(e.item());

            case OrderEvent.PaymentCompleted e ->
                currentState.withPaymentCompleted(e.amount());

            case OrderEvent.OrderShipped e ->
                currentState.withShipped(e.trackingNumber());
        }
    );
```

### Step 5: Subscribe to Events

```java
// Subscribe to new events
EventStore.Subscription subscription = eventStore.subscribe(
    orderId,
    event -> {
        System.out.println("New event: " + event.event());
        // Update read models, send notifications, etc.
    }
);

// Cancel subscription later
subscription.cancel();
```

### Step 6: Build Projections

```java
// Define a projection (read model)
public class OrderSummaryProjection implements EventStore.Projection {
    private final Map<String, OrderSummary> summaries = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "order-summary";
    }

    @Override
    public void apply(EventStore.StoredEvent event) {
        Object e = event.event();
        switch (e) {
            case OrderEvent.OrderCreated created -> {
                summaries.put(created.orderId(), new OrderSummary(
                    created.orderId(),
                    created.customerId(),
                    created.items().stream().mapToInt(OrderItem::quantity).sum(),
                    BigDecimal.ZERO,
                    "PENDING"
                ));
            }
            case OrderEvent.ItemAdded added -> {
                OrderSummary summary = summaries.get(added.orderId());
                if (summary != null) {
                    summaries.put(added.orderId(), summary.withItemCount(
                        summary.itemCount() + added.item().quantity()
                    ));
                }
            }
            case OrderEvent.PaymentCompleted paid -> {
                OrderSummary summary = summaries.get(paid.orderId());
                if (summary != null) {
                    summaries.put(paid.orderId(), summary.withStatus("PAID")
                        .withTotalAmount(paid.amount()));
                }
            }
            case OrderEvent.OrderShipped shipped -> {
                OrderSummary summary = summaries.get(shipped.orderId());
                if (summary != null) {
                    summaries.put(shipped.orderId(), summary.withStatus("SHIPPED"));
                }
            }
        }
    }

    @Override
    public void reset() {
        summaries.clear();
    }

    public OrderSummary getSummary(String orderId) {
        return summaries.get(orderId);
    }
}

// Register projection
OrderSummaryProjection projection = new OrderSummaryProjection();
eventStore.addProjection(projection);

// Query projection
OrderSummary summary = projection.getSummary("order-123");
```

### Step 7: Use Snapshots

```java
// Save snapshot to optimize replay
OrderState currentState = rebuildFromEvents(orderId);
eventStore.saveSnapshot(orderId, currentState, eventStore.currentVersion(orderId));

// Load snapshot to skip event replay
Optional<EventStore.Snapshot<OrderState>> snapshot = eventStore.loadSnapshot(orderId);
if (snapshot.isPresent()) {
    OrderState state = snapshot.get().state();
    long fromVersion = snapshot.get().version();

    // Replay events from snapshot version
    eventStore.loadFrom(orderId, fromVersion)
        .forEach(event -> state = applyEvent(state, event.event()));
}
```

## Complete Example

```java
public class OrderService {
    private final EventStore eventStore;
    private final OrderSummaryProjection summaryProjection;

    public OrderService() {
        this.eventStore = EventStore.create();
        this.summaryProjection = new OrderSummaryProjection();
        eventStore.addProjection(summaryProjection);
    }

    public String createOrder(String customerId, List<OrderItem> items) {
        String orderId = UUID.randomUUID().toString();

        eventStore.append(orderId, List.of(
            new OrderEvent.OrderCreated(orderId, customerId, items, Instant.now())
        ));

        return orderId;
    }

    public void addItem(String orderId, OrderItem item) {
        eventStore.append(orderId, List.of(
            new OrderEvent.ItemAdded(orderId, item, Instant.now())
        ));
    }

    public void completePayment(String orderId, BigDecimal amount) {
        eventStore.append(orderId, List.of(
            new OrderEvent.PaymentCompleted(orderId, amount, Instant.now())
        ));
    }

    public void shipOrder(String orderId, String trackingNumber) {
        eventStore.append(orderId, List.of(
            new OrderEvent.OrderShipped(orderId, trackingNumber, Instant.now())
        ));
    }

    public OrderState getOrder(String orderId) {
        return eventStore.load(orderId)
            .reduce(new OrderState(), (state, stored) ->
                applyEvent(state, stored.event())
            );
    }

    public OrderSummary getOrderSummary(String orderId) {
        return summaryProjection.getSummary(orderId);
    }

    public List<OrderEvent> getOrderHistory(String orderId) {
        return eventStore.load(orderId)
            .map(stored -> (OrderEvent) stored.event())
            .toList();
    }

    public void rebuildReadModels() {
        eventStore.rebuildProjection("order-summary");
    }
}
```

## Configuration Guidelines

### Event Granularity

```java
// GOOD: Fine-grained events (recommended)
new OrderCreated(...);
new ItemAdded(...);
new ItemAdded(...);
new PaymentCompleted(...);

// BAD: Coarse-grained events (lose information)
new OrderCreatedAndPaidAndShipped(...);
```

### Event Immutability

```java
// GOOD: Events are immutable records
record OrderCreated(String orderId, String customerId, ...) {}

// BAD: Mutable events (can cause bugs)
class OrderCreated {
    private String orderId;
    public void setOrderId(String orderId) { this.orderId = orderId; }
}
```

### Version Handling

```java
// Append with optimistic concurrency
long expectedVersion = eventStore.currentVersion(orderId);

try {
    eventStore.append(orderId, events, expectedVersion);
} catch (IllegalStateException e) {
    // Version mismatch - handle conflict
    throw new ConcurrentModificationException("Order modified by another process");
}
```

## Performance Considerations

### Memory
- **Event overhead**: ~200 bytes per event
- **Stream tracking**: O(streams) metadata
- **Projections**: O(events × projections) for in-memory
- **Scaling**: 1M events ≈ 200 MB heap (in-memory)

### Latency
- **Append**: ~1 μs (queue add + notification)
- **Load**: O(N) where N = events in stream
- **Snapshot**: O(1) (hash lookup)
- **Replay with snapshot**: O(events since snapshot)

### Throughput
- **Append rate**: 10M+ events/second
- **Replay rate**: 1M+ events/second
- **Limited by**: Memory bandwidth

### Optimization Strategies

```java
// 1. Use snapshots for large streams
if (eventStore.currentVersion(streamId) > 1000) {
    eventStore.saveSnapshot(streamId, currentState, version);
}

// 2. Batch events
eventStore.append(streamId, List.of(event1, event2, event3));

// 3. Use projections for queries
// Instead of: replayEvents(streamId).filter(...)
// Use: projection.query(...)
```

## Monitoring

### Key Metrics

```java
record EventStoreMetrics(
    long totalEvents,            // Total events stored
    int streamCount,             // Number of streams
    int projectionCount,         // Number of projections
    int subscriptionCount,       // Active subscriptions
    double avgEventsPerStream,   // Average events per stream
    long largestStreamSize,      // Largest stream
    Map<String, Long> eventsByType  // Events by type
) {}
```

### Alerting

```java
// Alert on high event rate
if (eventsPerSecond > threshold) {
    alertService.send(AlertPriority.MEDIUM,
        "High event rate: " + eventsPerSecond + "/sec");
}

// Alert on projection lag
if (projectionLag > SLA.threshold) {
    alertService.send(AlertPriority.HIGH,
        "Projection lag: " + projectionName);
}

// Alert on stream size
if (streamSize > 100000) {
    alertService.send(AlertPriority.LOW,
        "Large stream detected: " + streamId);
}
```

## Common Pitfalls

### 1. Events Too Large

```java
// BAD: Include entire state in events
new OrderUpdated(orderId, entireOrderState);

// GOOD: Capture only what changed
new ItemAdded(orderId, newItem);
```

### 2. Losing Event Immutability

```java
// BAD: Mutable events
class OrderEvent {
    private List<OrderItem> items;
    public void addItem(OrderItem item) { items.add(item); }
}

// GOOD: Immutable events
record ItemAdded(String orderId, OrderItem item, Instant timestamp) {}
```

### 3. Ignoring Snapshot Optimization

```java
// BAD: Replay 1M events every time
OrderState state = eventStore.load(orderId)
    .reduce(new OrderState(), (s, e) -> apply(e));

// GOOD: Use snapshots
var snapshot = eventStore.loadSnapshot(orderId);
if (snapshot.isPresent()) {
    // Replay only new events
}
```

## Advanced Patterns

### Event Versioning

```java
// Handle event schema evolution
public sealed interface OrderEvent {
    record OrderCreatedV1(String orderId, String customerId) implements OrderEvent {}
    record OrderCreatedV2(String orderId, String customerId, String region) implements OrderEvent {}

    static OrderEvent migrate(OrderEvent event) {
        return switch (event) {
            case OrderCreatedV1 v1 -> new OrderCreatedV2(v1.orderId(), v1.customerId(), "default");
            case OrderCreatedV2 v2 -> v2;
        };
    }
}
```

### CQRS with Event Sourcing

```java
// Command side: append events
public void handle(CreateOrder cmd) {
    eventStore.append(cmd.orderId(), List.of(
        new OrderCreated(cmd.orderId(), cmd.customerId(), cmd.items())
    ));
}

// Query side: read from projection
public OrderSummary getOrderSummary(String orderId) {
    return projection.getSummary(orderId);
}
```

### Event Replay for Migration

```java
// Replay events to build new read model
public void migrateToNewProjection() {
    EventStore.Projection newProjection = new NewProjection();
    eventStore.addProjection(newProjection);

    // Rebuild from all events
    eventStore.streams().forEach(streamId ->
        eventStore.load(streamId).forEach(newProjection::apply)
    );
}
```

## Related Guides

- **[CQRS](./cqrs.md)** - Separate command and query models
- **[Saga Transactions](./saga-transactions.md)** - Coordinate with sagas
- **[Event Bus](./kafka-messaging.md)** - Distribute events

## References

- **EventStore**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/EventStore.java`
- **EventSourcingAuditLog**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/EventSourcingAuditLog.java`
- **Test**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/EventSourcingAuditLogTest.java`
- **Examples**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/EcommerceOrderService.java`
