# Splitter Pattern

## Overview

The Splitter pattern decomposes a composite message into individual parts and routes each part to a separate destination. It's the opposite of the Aggregator pattern, breaking complex messages into simpler components for parallel or sequential processing.

**Enterprise Integration Pattern**: [Splitter](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Sequencer.html) (EIP §8.5)

**Erlang Analog**: A process that decomposes a list-based message and sends each element individually - `[Worker ! Item || Item <- Items]`

## When to Use This Pattern

- **Batch Processing**: Break batches into individual items
- **Parallel Processing**: Process parts concurrently
- **Complex Message Decomposition**: Split complex data structures
- **Item-Level Processing**: Need to handle each item separately
- **Scatter-Gather**: First step in scatter-gather pattern

## Architecture

```
┌──────────┐
│ Producer │
└────┬─────┘
     │
     │ Composite Message
     │ [Item1, Item2, Item3]
     │
     ▼
┌──────────────┐
│   Splitter   │
└──┬───┬───┬───┘
   │   │   │
   │   │   └──────────┐
   │   └──────────┐   │
   └──────────┐   │   │
      ▼       ▼   ▼   ▼
   ┌────┐┌────┐┌────┐┌────┐
   │Item││Item││Item││Item│
   │ 1  ││ 2  ││ 3  ││ 4  │
   └────┘└────┘└────┘└────┘
```

## JOTP Implementation

### Basic Splitter

```java
// Define composite message
record Order(String orderId, List<OrderItem> items) {}
record OrderItem(String productId, int quantity, double price) {}

// Create splitter
var splitter = new Splitter<>(
    // Split function: extract items from order
    (Order order) -> order.items(),
    // Part consumer: process each item
    (OrderItem item) -> {
        System.out.println("Processing item: " + item.productId());
        inventory.checkStock(item);
    }
);

// Split an order into items
Order order = new Order("order-123", List.of(
    new OrderItem("SKU-001", 2, 29.99),
    new OrderItem("SKU-002", 1, 49.99),
    new OrderItem("SKU-003", 3, 19.99)
));

int itemCount = splitter.split(order);
System.out.println("Split into " + itemCount + " items");
```

### Splitter with Content-Based Routing

```java
// Split and route based on item type
var inventoryRouter = ContentBasedRouter.<OrderItem>builder()
    .when(item -> item.category().equals("electronics"), electronicsHandler::tell)
    .when(item -> item.category().equals("clothing"), clothingHandler::tell)
    .otherwise(generalHandler::tell)
    .build();

// Create splitter with router
var splitter = new Splitter<>(
    Order::items,
    router::route
);

// Each item routed to appropriate handler
splitter.split(order);
```

### Parallel Processing Splitter

```java
// Split and process in parallel
var parallelSplitter = new Splitter<>(
    Order::items,
    item -> {
        // Each item processed in parallel by competing consumers
        workerPool.submit(item);
    }
);

// Worker pool for parallel processing
var workerPool = new CompetingConsumer<OrderItem>(
    4,  // 4 workers
    this::processItem
);
```

### Splitter with Transformation

```java
// Split and transform each part
record PurchaseOrder(String orderId, List<LineItem> items) {}
record LineItem(String productId, int quantity) {}
record InventoryReservation(String orderId, String productId, int quantity) {}

var splitter = new Splitter<>(
    PurchaseOrder::items,
    item -> {
        // Transform line item to inventory reservation
        var reservation = new InventoryReservation(
            order.orderId(),
            item.productId(),
            item.quantity()
        );
        inventorySystem.reserve(reservation);
    }
);
```

### Batch Processing

```java
// Split large batches
record Batch<T>(String batchId, List<T> items) {}

var batchSplitter = new Splitter<>(
    Batch::items,
    item -> {
        // Process each item from batch
        processIndividualItem(item);
    }
);

// Split a batch of records
Batch<Record> batch = new Batch<>("batch-001", loadRecords());
batchSplitter.split(batch);
```

## Integration with Other Patterns

- **Aggregator**: Splitter and aggregator are inverse operations
- **Content-Based Router**: Route split parts to different destinations
- **Scatter-Gather**: Splitter is the "scatter" part
- **Pipes and Filters**: Splitter can be first filter in pipeline
- **Competing Consumers**: Split parts to competing consumer pool

## Performance Considerations

- **Memory**: Splitter doesn't store parts, just forwards them
- **Throughput**: Can be very fast with virtual threads
- **Parallelism**: Natural parallelism when parts go to different consumers
- **Ordering**: No guaranteed ordering of parts after splitting

### Best Practices

1. **Keep split function simple** for better performance
2. **Handle empty lists** gracefully
3. **Consider part consumer capacity** to avoid overload
4. **Monitor split statistics** for system health
5. **Use with aggregator** for scatter-gather patterns

## Splitter Variations

### Splitter with Correlation

```java
// Splitter that adds correlation ID
class CorrelatingSplitter<T, P> {
    private final Function<T, List<P>> splitFunction;
    private final Consumer<P> partConsumer;
    private final AtomicLong counter = new AtomicLong(0);

    public int split(T composite) {
        String correlationId = "split-" + counter.getAndIncrement();
        List<P> parts = splitFunction.apply(composite);

        for (P part : parts) {
            // Add correlation to each part
            enrichWithCorrelation(part, correlationId);
            partConsumer.accept(part);
        }

        return parts.size();
    }
}
```

### Filtering Splitter

```java
// Splitter that filters parts
class FilteringSplitter<T, P> {
    private final Function<T, List<P>> splitFunction;
    private final Predicate<P> filter;
    private final Consumer<P> partConsumer;

    public int split(T composite) {
        List<P> parts = splitFunction.apply(composite);

        return parts.stream()
            .filter(filter)
            .peek(partConsumer)
            .count()
            .intValue();
    }
}
```

## Comparison with Aggregator

| Aspect | Splitter | Aggregator |
|--------|----------|------------|
| Direction | One → Many | Many → One |
| Use Case | Decomposition | Composition |
| State | Stateless | Stateful |
| Complexity | Simple | Complex |

## Related Patterns

- [Aggregator](./aggregator.md) - Combine parts back together
- [Content-Based Router](./content-based-router.md) - Route split parts
- [Scatter-Gather](./scatter-gather.md) - Split and aggregate
- [Pipes and Filters](./pipes-and-filters.md) - Processing pipeline

## See Also

- [`Splitter.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/Splitter.java) - Implementation
- [SplitterTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/SplitterTest.java) - Test examples
