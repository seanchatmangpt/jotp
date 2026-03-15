# Advanced JOTP Tutorial: Event Management

## Learning Objectives

By the end of this tutorial, you will:
- Master `EventManager<E>` for publish/subscribe messaging
- Implement event-driven architectures with multiple subscribers
- Build event handlers with filtering and transformation
- Create event aggregators and analytics systems
- Design decoupled microservices with event communication

## Prerequisites

- Complete [Beginner Tutorial: Processes](../beginner/processes.md)
- Understand `Proc<S,M>` message passing
- Basic familiarity with observer pattern concepts
- Understanding of asynchronous messaging

## Introduction: Why Event Management?

**Direct messaging** (what you've learned so far):
```java
procRef.tell(message);  // One sender, one receiver
```

**Event broadcasting** (what you'll learn here):
```java
eventManager.publish(event);  // One sender, multiple receivers
```

**Benefits of event-driven architecture:**
- **Decoupling**: Producers don't know about consumers
- **Scalability**: Add new consumers without changing producers
- **Flexibility**: Multiple independent reactions to events
- **Observability**: Easy to add logging, monitoring, analytics

## Basic Event Manager Setup

### 1. Define Events

```java
import io.github.seanchatmangpt.jotp.event.*;

// Domain events
sealed interface OrderEvent permits
    OrderEvent.Created,
    OrderEvent.Paid,
    OrderEvent.Shipped,
    OrderEvent.Delivered,
    OrderEvent.Cancelled {

    record Created(String orderId, String customerId, double amount) implements OrderEvent {}
    record Paid(String orderId, String paymentId) implements OrderEvent {}
    record Shipped(String orderId, String trackingNumber) implements OrderEvent {}
    record Delivered(String orderId) implements OrderEvent {}
    record Cancelled(String orderId, String reason) implements OrderEvent {}
}
```

### 2. Create Event Manager

```java
class EventManagerExample {
    public static void main(String[] args) throws Exception {
        // Create event manager for OrderEvent
        var eventManager = EventManager.create(OrderEvent.class);

        // Subscribe handler processes
        var loggingHandler = createLoggingHandler(eventManager);
        var emailHandler = createEmailHandler(eventManager);
        var analyticsHandler = createAnalyticsHandler(eventManager);

        // Publish events
        eventManager.publish(new OrderEvent.Created("ORD-123", "CUST-456", 99.99));
        Thread.sleep(100);

        eventManager.publish(new OrderEvent.Paid("ORD-123", "PAY-789"));
        Thread.sleep(100);

        eventManager.publish(new OrderEvent.Shipped("ORD-123", "TRACK-ABC"));
        Thread.sleep(100);

        // Cleanup
        eventManager.shutdown();
    }

    private static Proc<?, OrderEvent> createLoggingHandler(
        EventManager<OrderEvent> eventManager
    ) {
        return Proc.spawn(
            null,
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    System.out.println("📝 [LOG] " + event);
                    return new Proc.Continue<>(state);
                }
            )
        );
    }

    private static Proc<?, OrderEvent> createEmailHandler(
        EventManager<OrderEvent> eventManager
    ) {
        return Proc.spawn(
            null,
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    // Only send emails for specific events
                    return switch (event) {
                        case OrderEvent.Paid p -> {
                            System.out.println("📧 [EMAIL] Payment confirmed for " + p.orderId());
                            yield new Proc.Continue<>(state);
                        }
                        case OrderEvent.Shipped s -> {
                            System.out.println("📧 [EMAIL] Shipping notification for " + s.orderId());
                            yield new Proc.Continue<>(state);
                        }
                        default -> new Proc.Continue<>(state);  // Ignore other events
                    };
                }
            )
        );
    }

    private static Proc<?, OrderEvent> createAnalyticsHandler(
        EventManager<OrderEvent> eventManager
    ) {
        return Proc.spawn(
            new AnalyticsState(0, 0, 0.0),
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    return switch (event) {
                        case OrderEvent.Created c -> {
                            var newState = new AnalyticsState(
                                state.orderCount() + 1,
                                state.orderCount() + 1,
                                state.totalRevenue() + c.amount()
                            );
                            System.out.println("📊 [ANALYTICS] New order: " + c.orderId());
                            yield new Proc.Continue<>(newState);
                        }
                        default -> new Proc.Continue<>(state);
                    };
                }
            )
        );
    }

    record AnalyticsState(
        int orderCount,
        int customerCount,
        double totalRevenue
    ) {}
}
```

## Advanced Event Patterns

### 1. Event Filtering

Subscribe to specific event types only:

```java
// Only receive shipping events
var shippingOnlyHandler = Proc.spawn(
    null,
    EventManager.subscribe(
        eventManager,
        (state, event) -> {
            // Only handle Shipped events
            if (event instanceof OrderEvent.Shipped s) {
                System.out.println("Shipping: " + s.trackingNumber());
            }
            return new Proc.Continue<>(state);
        }
    )
);

// Or use pattern matching for multiple event types
var importantEventsHandler = Proc.spawn(
    null,
    EventManager.subscribe(
        eventManager,
        (state, event) -> {
            return switch (event) {
                case OrderEvent.Paid p,
                     OrderEvent.Cancelled c -> {
                    System.out.println("Important: " + event);
                    yield new Proc.Continue<>(state);
                }
                default -> new Proc.Continue<>(state);  // Ignore others
            };
        }
    )
);
```

### 2. Event Transformation

Transform events before processing:

```java
record EnrichedOrderEvent(
    OrderEvent original,
    long timestamp,
    String source
) {}

class EventTransformer {
    static Proc<?, OrderEvent> createTransformer(
        EventManager<OrderEvent> inputEvents,
        EventManager<EnrichedOrderEvent> outputEvents
    ) {
        return Proc.spawn(
            null,
            EventManager.subscribe(
                inputEvents,
                (state, event) -> {
                    // Transform and republish
                    var enriched = new EnrichedOrderEvent(
                        event,
                        System.currentTimeMillis(),
                        "order-service"
                    );

                    outputEvents.publish(enriched);
                    return new Proc.Continue<>(state);
                }
            )
        );
    }
}
```

### 3. Event Aggregation

Aggregate events over time windows:

```java
record AggregationState(
    Map<String, List<OrderEvent>> eventsByOrder,
    long windowStart
) {
    AggregationState() {
        this(new ConcurrentHashMap<>(), System.currentTimeMillis());
    }
}

class EventAggregator {
    private static final long WINDOW_MS = 5000;  // 5 second windows

    static Proc<AggregationState, OrderEvent> create(
        EventManager<OrderEvent> eventManager
    ) {
        return Proc.spawn(
            new AggregationState(),
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    // Check if we need to start a new window
                    long now = System.currentTimeMillis();
                    if (now - state.windowStart() > WINDOW_MS) {
                        // Process aggregated events
                        processAggregates(state.eventsByOrder());

                        // Start new window
                        return new Proc.Continue<>(
                            new AggregationState(new ConcurrentHashMap<>(), now)
                        );
                    }

                    // Add event to current window
                    var orderId = extractOrderId(event);
                    var events = state.eventsByOrder().getOrDefault(
                        orderId,
                        new ArrayList<>()
                    );
                    events = append(events, event);
                    state.eventsByOrder().put(orderId, events);

                    return new Proc.Continue<>(state);
                }
            )
        );
    }

    private static String extractOrderId(OrderEvent event) {
        return switch (event) {
            case OrderEvent.Created c -> c.orderId();
            case OrderEvent.Paid p -> p.orderId();
            case OrderEvent.Shipped s -> s.orderId();
            case OrderEvent.Delivered d -> d.orderId();
            case OrderEvent.Cancelled c -> c.orderId();
        };
    }

    private static void processAggregates(Map<String, List<OrderEvent>> eventsByOrder) {
        System.out.println("\n=== Aggregation Summary ===");
        for (var entry : eventsByOrder.entrySet()) {
            System.out.println("Order " + entry.getKey() + ": " +
                entry.getValue().size() + " events");
        }
        System.out.println("=== End Summary ===\n");
    }

    private static List<OrderEvent> append(List<OrderEvent> list, OrderEvent item) {
        var result = new ArrayList<>(list);
        result.add(item);
        return List.copyOf(result);
    }
}
```

### 4. Dead Letter Queue

Capture events that fail processing:

```java
record FailedEvent(
    OrderEvent event,
    Throwable reason,
    long timestamp
) {}

class DeadLetterQueue {
    static Proc<?, FailedEvent> create(
        EventManager<OrderEvent> eventManager,
        EventManager<FailedEvent> dlqEvents
    ) {
        return Proc.spawn(
            null,
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    try {
                        // Process event
                        processEvent(event);
                        return new Proc.Continue<>(state);
                    } catch (Exception e) {
                        // Send to DLQ
                        var failed = new FailedEvent(
                            event,
                            e,
                            System.currentTimeMillis()
                        );
                        dlqEvents.publish(failed);

                        System.out.println("⚠️  Event failed, sent to DLQ: " + event);
                        return new Proc.Continue<>(state);
                    }
                }
            )
        );
    }

    private static void processEvent(OrderEvent event) {
        // Simulate processing that might fail
        if (Math.random() < 0.1) {
            throw new RuntimeException("Random processing failure");
        }
    }
}
```

## Exercise: Event-Driven Analytics System

Build a complete analytics system using event management.

### Requirements

1. **Multiple Event Sources**: Orders, Payments, Shipments
2. **Real-time Metrics**: Track orders, revenue, shipping times
3. **Alerting**: Detect anomalies (high value orders, failed payments)
4. **Persistence**: Save events to database
5. **Dashboard**: Aggregate metrics for display

### Solution

```java
// Domain events
sealed interface BusinessEvent permits
    BusinessEvent.OrderCreated,
    BusinessEvent.PaymentCompleted,
    BusinessEvent.PaymentFailed,
    BusinessEvent.OrderShipped,
    BusinessEvent.OrderDelivered {

    record OrderCreated(
        String orderId,
        String customerId,
        double amount,
        long timestamp
    ) implements BusinessEvent {}

    record PaymentCompleted(
        String orderId,
        String paymentId,
        double amount,
        long timestamp
    ) implements BusinessEvent {}

    record PaymentFailed(
        String orderId,
        String reason,
        long timestamp
    ) implements BusinessEvent {}

    record OrderShipped(
        String orderId,
        String trackingNumber,
        long timestamp
    ) implements BusinessEvent {}

    record OrderDelivered(
        String orderId,
        long timestamp
    ) implements BusinessEvent {}
}

// Metrics state
record MetricsState(
    int totalOrders,
    double totalRevenue,
    int failedPayments,
    Map<String, Long> orderCreationTimes,
    Map<String, Long> orderDeliveryTimes
) {
    MetricsState() {
        this(
            0,
            0.0,
            0,
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>()
        );
    }
}

// 1. Real-time metrics collector
class MetricsCollector {
    static Proc<MetricsState, BusinessEvent> create(
        EventManager<BusinessEvent> eventManager
    ) {
        return Proc.spawn(
            new MetricsState(),
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    return switch (event) {
                        case BusinessEvent.OrderCreated o -> {
                            var newState = new MetricsState(
                                state.totalOrders() + 1,
                                state.totalRevenue(),
                                state.failedPayments(),
                                updated(state.orderCreationTimes(), o.orderId(), o.timestamp()),
                                state.orderDeliveryTimes()
                            );

                            System.out.printf(
                                "📊 Orders: %d, Revenue: $%.2f%n",
                                newState.totalOrders(),
                                newState.totalRevenue()
                            );

                            yield new Proc.Continue<>(newState);
                        }

                        case BusinessEvent.PaymentCompleted p -> {
                            var newState = new MetricsState(
                                state.totalOrders(),
                                state.totalRevenue() + p.amount(),
                                state.failedPayments(),
                                state.orderCreationTimes(),
                                state.orderDeliveryTimes()
                            );

                            System.out.printf(
                                "💰 Revenue updated: $%.2f%n",
                                newState.totalRevenue()
                            );

                            yield new Proc.Continue<>(newState);
                        }

                        case BusinessEvent.PaymentFailed p -> {
                            var newState = new MetricsState(
                                state.totalOrders(),
                                state.totalRevenue(),
                                state.failedPayments() + 1,
                                state.orderCreationTimes(),
                                state.orderDeliveryTimes()
                            );

                            System.out.println("⚠️  Payment failed: " + p.reason());

                            yield new Proc.Continue<>(newState);
                        }

                        case BusinessEvent.OrderDelivered d -> {
                            var newState = new MetricsState(
                                state.totalOrders(),
                                state.totalRevenue(),
                                state.failedPayments(),
                                state.orderCreationTimes(),
                                updated(state.orderDeliveryTimes(), d.orderId(), d.timestamp())
                            );

                            // Calculate delivery time
                            var createdTime = state.orderCreationTimes().get(d.orderId());
                            if (createdTime != null) {
                                var deliveryTime = d.timestamp() - createdTime;
                                System.out.println("📦 Delivery time: " + deliveryTime + "ms");
                            }

                            yield new Proc.Continue<>(newState);
                        }

                        default -> new Proc.Continue<>(state);
                    };
                }
            )
        );
    }

    private static Map<String, Long> updated(
        Map<String, Long> map,
        String key,
        long value
    ) {
        var result = new ConcurrentHashMap<>(map);
        result.put(key, value);
        return result;
    }
}

// 2. Anomaly detector (alerting)
record AlertState(int highValueOrderCount) {}

class AnomalyDetector {
    private static final double HIGH_VALUE_THRESHOLD = 1000.0;

    static Proc<AlertState, BusinessEvent> create(
        EventManager<BusinessEvent> eventManager,
        EventManager<String> alertEvents
    ) {
        return Proc.spawn(
            new AlertState(0),
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    return switch (event) {
                        case BusinessEvent.OrderCreated o -> {
                            if (o.amount() > HIGH_VALUE_THRESHOLD) {
                                var alert = String.format(
                                    "🚨 HIGH VALUE ORDER: %s - $%.2f",
                                    o.orderId(),
                                    o.amount()
                                );

                                System.out.println(alert);
                                alertEvents.publish(alert);

                                yield new Proc.Continue<>(
                                    new AlertState(state.highValueOrderCount() + 1)
                                );
                            }
                            yield new Proc.Continue<>(state);
                        }

                        case BusinessEvent.PaymentFailed p -> {
                            var alert = String.format(
                                "🚨 PAYMENT FAILED: %s - %s",
                                p.orderId(),
                                p.reason()
                            );

                            System.out.println(alert);
                            alertEvents.publish(alert);

                            yield new Proc.Continue<>(state);
                        }

                        default -> new Proc.Continue<>(state);
                    };
                }
            )
        );
    }
}

// 3. Event persistence
class EventPersistence {
    static Proc<?, BusinessEvent> create(
        EventManager<BusinessEvent> eventManager
    ) {
        return Proc.spawn(
            null,
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    // Persist event to database
                    persistEvent(event);
                    return new Proc.Continue<>(state);
                }
            )
        );
    }

    private static void persistEvent(BusinessEvent event) {
        // In production, save to database
        System.out.println("💾 Persisted: " + event.getClass().getSimpleName());
    }
}

// 4. Dashboard aggregator
record DashboardState(
    double averageOrderValue,
    int ordersLastHour,
    double revenueLastHour
) {
    DashboardState() {
        this(0.0, 0, 0);
    }
}

class DashboardAggregator {
    static Proc<DashboardState, BusinessEvent> create(
        EventManager<BusinessEvent> eventManager,
        EventManager<DashboardSnapshot> snapshotEvents
    ) {
        return Proc.spawn(
            new DashboardState(),
            EventManager.subscribe(
                eventManager,
                (state, event) -> {
                    var newState = switch (event) {
                        case BusinessEvent.OrderCreated o -> new DashboardState(
                            (state.averageOrderValue() * state.ordersLastHour() + o.amount()) /
                                (state.ordersLastHour() + 1),
                            state.ordersLastHour() + 1,
                            state.revenueLastHour() + o.amount()
                        );
                        default -> state;
                    };

                    // Periodically publish snapshot
                    if (newState.ordersLastHour() % 10 == 0) {
                        var snapshot = new DashboardSnapshot(
                            newState.averageOrderValue(),
                            newState.ordersLastHour(),
                            newState.revenueLastHour(),
                            System.currentTimeMillis()
                        );

                        snapshotEvents.publish(snapshot);
                        System.out.println("\n📈 DASHBOARD SNAPSHOT:");
                        System.out.println("  Average Order: $" + snapshot.averageOrderValue());
                        System.out.println("  Orders (Hour): " + snapshot.ordersLastHour());
                        System.out.println("  Revenue (Hour): $" + snapshot.revenueLastHour());
                        System.out.println();
                    }

                    return new Proc.Continue<>(newState);
                }
            )
        );
    }

    record DashboardSnapshot(
        double averageOrderValue,
        int ordersLastHour,
        double revenueLastHour,
        long timestamp
    ) {}
}

// Main analytics system
class EventDrivenAnalytics {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Event-Driven Analytics System ===\n");

        // Create event managers
        var businessEvents = EventManager.create(BusinessEvent.class);
        var alertEvents = EventManager.create(String.class);
        var snapshotEvents = EventManager.create(DashboardAggregator.DashboardSnapshot.class);

        // Start handlers
        var metrics = MetricsCollector.create(businessEvents);
        var anomalyDetector = AnomalyDetector.create(businessEvents, alertEvents);
        var persistence = EventPersistence.create(businessEvents);
        var dashboard = DashboardAggregator.create(businessEvents, snapshotEvents);

        // Simulate business events
        var random = new Random();
        for (int i = 1; i <= 50; i++) {
            var orderId = "ORD-" + i;
            var amount = 50.0 + random.nextDouble() * 1500.0;

            // Order created
            businessEvents.publish(new BusinessEvent.OrderCreated(
                orderId,
                "CUST-" + random.nextInt(100),
                amount,
                System.currentTimeMillis()
            ));

            Thread.sleep(50);

            // Payment (10% fail rate)
            if (random.nextDouble() < 0.9) {
                businessEvents.publish(new BusinessEvent.PaymentCompleted(
                    orderId,
                    "PAY-" + i,
                    amount,
                    System.currentTimeMillis()
                ));

                // Ship
                businessEvents.publish(new BusinessEvent.OrderShipped(
                    orderId,
                    "TRACK-" + i,
                    System.currentTimeMillis()
                ));

                // Deliver
                businessEvents.publish(new BusinessEvent.OrderDelivered(
                    orderId,
                    System.currentTimeMillis()
                ));
            } else {
                businessEvents.publish(new BusinessEvent.PaymentFailed(
                    orderId,
                    "Insufficient funds",
                    System.currentTimeMillis()
                ));
            }

            Thread.sleep(100);
        }

        Thread.sleep(2000);

        System.out.println("\n=== Analytics System Complete ===");
        businessEvents.shutdown();
        alertEvents.shutdown();
        snapshotEvents.shutdown();
    }
}
```

## What You Learned

- **Event fundamentals**: Publish/subscribe with `EventManager<E>`
- **Event filtering**: Subscribe to specific event types
- **Event transformation**: Transform and republish events
- **Event aggregation**: Aggregate events over time windows
- **Dead letter queues**: Handle failed events
- **Real-world patterns**: Complete analytics system with multiple handlers

## Next Steps

- [Distributed Systems Tutorial](distributed-systems.md) - Multi-node event broadcasting
- [Fault Tolerance Tutorial](fault-tolerance.md) - Make event handlers crash-resilient
- [State Machines Tutorial](state-machines.md) - Combine state machines with events

## Additional Exercises

1. **Event Replay**: Implement event replay for recovery
2. **Event Sourcing**: Build event-sourced aggregates
3. **CQRS**: Separate read/write models with events
4. **Complex Event Processing**: Pattern matching across event streams
5. **Event Versioning**: Handle evolving event schemas

## Further Reading

- [Event-Driven Architecture](https://martinfowler.com/articles/201701-event-driven.html)
- [Reactive Messaging](https://www.reactivemanifesto.org/)
- [JOTP EventManager API](../../api/io/github/seanchatmangpt/jotp/event/EventManager.html)
