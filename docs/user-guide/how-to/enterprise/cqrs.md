# CQRS (Command Query Responsibility Segregation)

## Problem

Your complex domain model struggles with conflicting read and write requirements. Writes need validation, business rules, and security. Reads need performance, caching, and flexibility. Combining both in one model leads to poor performance and unmaintainable code.

**Symptoms:**
- Complex queries slow down writes
- Read model optimization compromises write logic
- No clear separation between commands and queries
- Difficulty optimizing for different read/write patterns

## Solution

Implement CQRS: separate the read and write models, allowing each to be optimized independently. JOTP's CommandDispatcher handles write operations (commands), while QueryDispatcher handles read operations (queries).

**Benefits:**
- **Separation of concerns**: Write logic isolated from read optimization
- **Performance**: Optimize reads (caching, denormalization) without affecting writes
- **Scalability**: Scale read and write sides independently
- **Flexibility**: Different data models for commands and queries

## Prerequisites

- Java 26 with preview features enabled
- JOTP core module: `io.github.seanchatmangpt.jotp`
- Understanding of event-driven architecture

## Implementation

### Step 1: Define Commands

```java
import io.github.seanchatmangpt.jotp.CommandDispatcher;

// Define your commands
public sealed interface OrderCommand implements CommandDispatcher.Command {
    record CreateOrder(
        String orderId,
        String customerId,
        List<OrderItem> items
    ) implements OrderCommand {}

    record AddItem(
        String orderId,
        OrderItem item
    ) implements OrderCommand {}

    record CompletePayment(
        String orderId,
        BigDecimal amount
    ) implements OrderCommand {}

    record ShipOrder(
        String orderId,
        String trackingNumber
    ) implements OrderCommand {}
}
```

### Step 2: Define Queries

```java
import io.github.seanchatmangpt.jotp.QueryDispatcher;

// Define your queries
public sealed interface OrderQuery implements QueryDispatcher.Query<Order> {
    record GetOrderById(String orderId) implements OrderQuery {}

    record GetOrdersByCustomer(String customerId) implements OrderQuery {}

    record SearchOrders(String searchTerm, LocalDate fromDate) implements OrderQuery {}
}
```

### Step 3: Create Command Dispatcher

```java
CommandDispatcher commandDispatcher = CommandDispatcher.create()
    .register(OrderCommand.CreateOrder.class, this::handleCreateOrder)
    .register(OrderCommand.AddItem.class, this::handleAddItem)
    .register(OrderCommand.CompletePayment.class, this::handleCompletePayment)
    .register(OrderCommand.ShipOrder.class, this::handleShipOrder)
    .middleware(new LoggingMiddleware())
    .middleware(new ValidationMiddleware());
```

### Step 4: Create Query Dispatcher

```java
QueryDispatcher queryDispatcher = QueryDispatcher.create()
    .register(OrderQuery.GetOrderById.class, this::handleGetOrderById)
    .register(OrderQuery.GetOrdersByCustomer.class, this::handleGetOrdersByCustomer)
    .register(OrderQuery.SearchOrders.class, this::handleSearchOrders)
    .cache(Duration.ofMinutes(5));  // Enable caching
```

### Step 5: Implement Command Handlers

```java
private CommandDispatcher.CommandResult<Order> handleCreateOrder(OrderCommand.CreateOrder cmd) {
    try {
        // Validate
        if (cmd.items().isEmpty()) {
            return CommandDispatcher.CommandResult.err("Order must have at least one item");
        }

        // Execute business logic
        Order order = orderRepository.create(
            cmd.orderId(),
            cmd.customerId(),
            cmd.items()
        );

        // Emit event
        eventBus.publish(new OrderCreated(order));

        return CommandDispatcher.CommandResult.ok(order);
    } catch (Exception e) {
        return CommandDispatcher.CommandResult.err("Failed to create order", e);
    }
}

private CommandDispatcher.CommandResult<Void> handleAddItem(OrderCommand.AddItem cmd) {
    try {
        Order order = orderRepository.get(cmd.orderId());
        Order updated = order.addItem(cmd.item());
        orderRepository.save(updated);

        eventBus.publish(new ItemAdded(cmd.orderId(), cmd.item()));

        return CommandDispatcher.CommandResult.ok(null);
    } catch (Exception e) {
        return CommandDispatcher.CommandResult.err("Failed to add item", e);
    }
}
```

### Step 6: Implement Query Handlers

```java
private Optional<Order> handleGetOrderById(OrderQuery.GetOrderById query) {
    // Query optimized read model
    return readModelRepository.findById(query.orderId());
}

private Optional<List<Order>> handleGetOrdersByCustomer(OrderQuery.GetOrdersByCustomer query) {
    // Use denormalized read model for fast queries
    return Optional.of(readModelRepository.findByCustomerId(query.customerId()));
}

private Optional<List<Order>> handleSearchOrders(OrderQuery.SearchOrders query) {
    // Use specialized search index
    return Optional.of(searchIndex.search(query.searchTerm(), query.fromDate()));
}
```

### Step 7: Dispatch Commands and Queries

```java
// Dispatch a command (write)
CommandDispatcher.CommandResult<Order> result = commandDispatcher.dispatch(
    new OrderCommand.CreateOrder("order-123", "customer-456", items)
);

if (result.isSuccess()) {
    Order order = result.orElse(null);
    System.out.println("Order created: " + order.id());
} else {
    System.err.println("Failed: " + result);
}

// Dispatch a query (read)
Optional<Order> order = queryDispatcher.query(
    new OrderQuery.GetOrderById("order-123")
);

if (order.isPresent()) {
    System.out.println("Order: " + order.get());
}
```

## Complete Example

```java
public class OrderService {
    private final CommandDispatcher commands;
    private final QueryDispatcher queries;
    private final OrderRepository writeModel;
    private final OrderReadModel readModel;
    private final EventBus eventBus;

    public OrderService() {
        this.writeModel = new OrderRepository();
        this.readModel = new OrderReadModel();
        this.eventBus = new EventBus();

        this.commands = CommandDispatcher.create()
            .register(CreateOrder.class, this::handleCreateOrder)
            .register(AddItem.class, this::handleAddItem)
            .register(CompletePayment.class, this::handleCompletePayment)
            .middleware(new ValidationMiddleware())
            .middleware(new LoggingMiddleware());

        this.queries = QueryDispatcher.create()
            .register(GetOrderById.class, this::handleGetOrderById)
            .register(GetOrdersByCustomer.class, this::handleGetOrdersByCustomer)
            .cache(Duration.ofMinutes(5));

        // Subscribe to events to update read model
        eventBus.subscribe("order-updates", event -> updateReadModel(event));
    }

    // Command handlers (write side)
    public String createOrder(String customerId, List<OrderItem> items) {
        CreateOrder cmd = new CreateOrder(UUID.randomUUID().toString(), customerId, items);
        var result = commands.dispatch(cmd);

        if (result.isSuccess()) {
            return cmd.orderId();
        } else {
            throw new OrderException(result.toString());
        }
    }

    public void addItem(String orderId, OrderItem item) {
        var result = commands.dispatch(new AddItem(orderId, item));
        if (!result.isSuccess()) {
            throw new OrderException(result.toString());
        }
    }

    // Query handlers (read side)
    public Order getOrder(String orderId) {
        return queries.query(new GetOrderById(orderId)).orElse(null);
    }

    public List<Order> getCustomerOrders(String customerId) {
        return queries.query(new GetOrdersByCustomer(customerId)).orElse(List.of());
    }

    // Update read model from events
    private void updateReadModel(Object event) {
        switch (event) {
            case OrderCreated e -> readModel.insert(e.order());
            case ItemAdded e -> readModel.addItem(e.orderId(), e.item());
        }
    }

    public CommandDispatcher.CommandResult<?> dispatch(Command cmd) {
        return commands.dispatch(cmd);
    }

    public <T> Optional<T> query(QueryDispatcher.Query<T> query) {
        return queries.query(query);
    }

    public Map<String, Long> getCommandStats() {
        return commands.stats();
    }

    public Map<String, Long> getQueryStats() {
        return queries.stats();
    }
}
```

## Middleware Implementation

```java
// Validation middleware
public class ValidationMiddleware implements CommandDispatcher.Middleware {
    @Override
    public <T> CommandDispatcher.CommandResult<T> before(CommandDispatcher.Command command) {
        return switch (command) {
            case CreateOrder c -> {
                if (c.items().isEmpty()) {
                    yield CommandDispatcher.CommandResult.err("Order must have items");
                }
                yield null;  // Continue
            }
            default -> null;  // Continue
        };
    }
}

// Logging middleware
public class LoggingMiddleware implements CommandDispatcher.Middleware {
    @Override
    public <T> CommandDispatcher.CommandResult<T> before(CommandDispatcher.Command command) {
        logger.info("Executing command: {}", command);
        return null;  // Continue
    }

    @Override
    public <T> CommandDispatcher.CommandResult<T> after(
        CommandDispatcher.Command command,
        CommandDispatcher.CommandResult<T> result
    ) {
        if (result.isSuccess()) {
            logger.info("Command succeeded: {}", command);
        } else {
            logger.error("Command failed: {} - {}", command, result);
        }
        return result;
    }
}

// Authorization middleware
public class AuthorizationMiddleware implements CommandDispatcher.Middleware {
    @Override
    public <T> CommandDispatcher.CommandResult<T> before(CommandDispatcher.Command command) {
        User user = SecurityContext.getCurrentUser();

        if (!user.canExecute(command)) {
            return CommandDispatcher.CommandResult.err("Unauthorized: " + command);
        }

        return null;  // Continue
    }
}
```

## Configuration Guidelines

### Command Design

```java
// GOOD: Intent-revealing commands
record CreateOrder(String orderId, String customerId, List<OrderItem> items) {}
record CompletePayment(String orderId, BigDecimal amount) {}

// BAD: CRUD-style commands
record SaveOrder(Order order) {}  // What does this do? Create? Update?
```

### Query Design

```java
// GOOD: Specific queries
record GetOrderById(String orderId) implements OrderQuery {}
record GetOrdersByCustomer(String customerId) implements OrderQuery {}

// BAD: Generic queries
record FindOrders(Map<String, Object> criteria) implements OrderQuery {}
```

### Cache Configuration

```java
// Aggressive caching (for stable data)
QueryDispatcher.create()
    .cache(Duration.ofHours(1));

// Moderate caching (default)
QueryDispatcher.create()
    .cache(Duration.ofMinutes(5));

// No caching (for real-time data)
QueryDispatcher.create();  // Cache disabled
```

## Performance Considerations

### Memory
- **Command dispatcher**: ~1 KB (handler registry)
- **Query dispatcher**: ~1 KB + cache size
- **Cache memory**: O(queries) × result size
- **Scaling**: Negligible overhead

### Latency
- **Command dispatch**: ~1 μs (handler lookup + execution)
- **Query dispatch**: ~1 μs (handler lookup + cache check)
- **Cache hit**: ~0.1 μs (hash lookup)
- **Cache miss**: Handler execution time

### Throughput
- **Commands**: 10M+ commands/second
- **Queries**: 50M+ queries/second (with cache)
- **Limited by**: Handler execution, not dispatch

### Optimization Strategies

```java
// 1. Enable query caching
queries.cache(Duration.ofMinutes(5));

// 2. Use async dispatch
CompletableFuture<CommandResult<?>> future = commands.dispatchAsync(command);

// 3. Batch queries
Map<Query<T>, Optional<T>> results = queries.queryAll(List.of(query1, query2, query3));

// 4. Evict stale cache entries
queries.evictExpired();
```

## Monitoring

### Key Metrics

```java
record CQRSMetrics(
    long commandsDispatched,      // Total commands
    long commandsSucceeded,       // Successful commands
    long commandsFailed,          // Failed commands
    long queriesExecuted,         // Total queries
    long cacheHits,               // Cache hits
    long cacheMisses,             // Cache misses
    double cacheHitRate,          // Cache effectiveness
    Map<String, Long> commandsByType,  // Commands by type
    Map<String, Long> queriesByType     // Queries by type
) {}
```

### Alerting

```java
// Alert on high command failure rate
if (commandFailureRate > 5%) {
    alertService.send(AlertPriority.HIGH,
        "High command failure rate");
}

// Alert on low cache hit rate
if (cacheHitRate < 50%) {
    alertService.send(AlertPriority.LOW,
        "Low cache hit rate, consider adjusting TTL");
}

// Alert on slow commands
if (commandLatency > SLA.threshold) {
    alertService.send(AlertPriority.MEDIUM,
        "Slow command: " + commandType);
}
```

## Common Pitfalls

### 1. Commands Returning Data

```java
// BAD: Commands should not return data (except confirmation)
record GetOrder(String orderId) implements Command {}

// GOOD: Use queries for reading
record GetOrder(String orderId) implements Query {}

// Commands should return confirmation, not data
CommandResult<Void> result = commands.dispatch(new CreateOrder(...));
```

### 2. Queries Modifying State

```java
// BAD: Queries should not modify state
private Order handleGetOrder(GetOrder query) {
    Order order = repository.get(query.orderId());
    order.setLastViewed(Instant.now());  // Side effect!
    return order;
}

// GOOD: Queries are pure reads
private Order handleGetOrder(GetOrder query) {
    return readModelRepository.get(query.orderId());
}
```

### 3. Ignoring Cache Invalidation

```java
// BAD: Stale cache after command
commands.dispatch(new UpdateOrder(orderId, ...));
// Cache still has old data!

// GOOD: Invalidate cache after command
commands.dispatch(new UpdateOrder(orderId, ...));
queries.clearCache();  // Or evict specific key
```

## Advanced Patterns

### Event Sourcing Integration

```java
// Command side: append events
public CommandResult<Order> handleCreateOrder(CreateOrder cmd) {
    eventStore.append(cmd.orderId(), List.of(
        new OrderCreated(cmd.orderId(), cmd.customerId(), cmd.items())
    ));
    return CommandResult.ok(new Order(cmd.orderId()));
}

// Query side: read from projection
public Optional<Order> handleGetOrderById(GetOrderById query) {
    return Optional.ofNullable(projection.get(query.orderId()));
}
```

### Async Command Processing

```java
public CompletableFuture<String> createOrderAsync(CreateOrder cmd) {
    return commands.dispatchAsync(cmd)
        .thenApply(result -> {
            if (result.isSuccess()) {
                return cmd.orderId();
            } else {
                throw new CommandException(result.toString());
            }
        });
}
```

### Cross-Cutting Concerns

```java
// Transaction middleware
public class TransactionMiddleware implements CommandDispatcher.Middleware {
    @Override
    public <T> CommandDispatcher.CommandResult<T> before(Command command) {
        transactionManager.begin();
        return null;
    }

    @Override
    public <T> CommandDispatcher.CommandResult<T> after(
        Command command,
        CommandDispatcher.CommandResult<T> result
    ) {
        if (result.isSuccess()) {
            transactionManager.commit();
        } else {
            transactionManager.rollback();
        }
        return result;
    }
}
```

## Related Guides

- **[Event Sourcing](./event-sourcing.md)** - Persist events instead of state
- **[Saga Transactions](./saga-transactions.md)** - Coordinate with sagas
- **[Event Bus](./kafka-messaging.md)** - Distribute events

## References

- **CommandDispatcher**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/CommandDispatcher.java`
- **QueryDispatcher**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/QueryDispatcher.java`
- **EventSourcingAuditLog**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/EventSourcingAuditLog.java`
