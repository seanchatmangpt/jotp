# Aggregator Pattern

## Overview

The Aggregator pattern collects correlated messages until a completion condition is met, then emits an aggregated result. It's the opposite of the Splitter pattern, combining multiple related messages into a single composite message.

**Enterprise Integration Pattern**: [Aggregator](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Aggregator.html) (EIP §8.6)

**Erlang Analog**: A `gen_server` accumulating partial results by correlation ID, emitting a combined result when all parts arrive

## When to Use This Pattern

- **Gather Results**: Collect responses from multiple services
- **Batch Composition**: Assemble individual items into batches
- **Correlation**: Combine related messages by correlation ID
- **Timeout Handling**: Emit partial results when timeout occurs
- **Scatter-Gather**: Aggregator is the "gather" part

## Architecture

```
┌───┐ ┌───┐ ┌───┐ ┌───┐
│Msg│ │Msg│ │Msg│ │Msg│
│ 1 │ │ 2 │ │ 3 │ │ 4 │
└─┬─┘ └─┬─┘ └─┬─┘ └─┬─┘
  │     │     │     │
  │ correlationId="ABC-123"
  │     │     │     │
  └─────┴─────┴─────┘
        │
        ▼
┌──────────────────┐
│   Aggregator     │
│  (Accumulate)    │
└────────┬─────────┘
         │
         │ complete?
         │
         ▼
┌──────────────────┐
│ Aggregate Result │
└──────────────────┘
```

## JOTP Implementation

### Basic Aggregator

```java
// Define message types
record PriceQuote(String rfqId, String supplier, double price) {}
record QuotationFulfillment(String rfqId, List<PriceQuote> quotes) {}

// Create aggregator
var aggregator = new Aggregator<PriceQuote, String, QuotationFulfillment>(
    // Extract correlation key
    quote -> quote.rfqId(),
    // Aggregate function
    quotes -> new QuotationFulfillment(quotes.get(0).rfqId(), quotes),
    // Result consumer
    fulfillment -> {
        System.out.println("RFQ " + fulfillment.rfqId() + " complete");
        processQuotes(fulfillment.quotes());
    }
);

// Expect 3 quotes for RFQ-123
aggregator.expect("RFQ-123", 3);

// Add quotes as they arrive
aggregator.addPart(new PriceQuote("RFQ-123", "Supplier A", 100.0));
aggregator.addPart(new PriceQuote("RFQ-123", "Supplier B", 95.0));
aggregator.addPart(new PriceQuote("RFQ-123", "Supplier C", 105.0));
// After 3rd quote, aggregator emits QuotationFulfillment
```

### Order Aggregation

```java
// Aggregate order items
record OrderItem(String orderId, String productId, int quantity) {}
record CompleteOrder(String orderId, List<OrderItem> items) {}

var orderAggregator = new Aggregator<OrderItem, String, CompleteOrder>(
    OrderItem::orderId,
    items -> new CompleteOrder(items.get(0).orderId(), items),
    this::processCompleteOrder
);

// Expect 5 items for order-456
orderAggregator.expect("order-456", 5);

// Add items as they're received
for (OrderItem item : getOrderItems()) {
    orderAggregator.addPart(item);
}
// After 5 items, emits CompleteOrder
```

### Multi-Correlation Aggregation

```java
// Aggregate multiple correlations simultaneously
var aggregator = new Aggregator<Part, String, Assembly>(
    Part::assemblyId,
    parts -> new Assembly(parts.get(0).assemblyId(), parts),
    this::processAssembly
);

// Expect different counts for different assemblies
aggregator.expect("assembly-1", 10);
aggregator.expect("assembly-2", 5);
aggregator.expect("assembly-3", 15);

// Add parts for all assemblies
parts.forEach(aggregator::addPart);
// Each assembly completes when its expected count is reached
```

### Timeout-Aware Aggregator

```java
// Aggregator with timeout handling
class TimeoutAggregator<T, K, R> {
    private final Aggregator<T, K, R> aggregator;
    private final ScheduledExecutorService scheduler;
    private final Map<K, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();

    public void expect(K correlationKey, int expectedCount, Duration timeout) {
        aggregator.expect(correlationKey, expectedCount);

        var timeoutFuture = scheduler.schedule(() -> {
            // Timeout - emit partial results
            handleTimeout(correlationKey);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        timeouts.put(correlationKey, timeoutFuture);
    }

    public void addPart(T part) {
        K key = extractKey(part);
        aggregator.addPart(part);

        // If aggregation complete, cancel timeout
        if (isComplete(key)) {
            ScheduledFuture<?> future = timeouts.remove(key);
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
```

### Aggregator with Custom Logic

```java
// Aggregator with complex aggregation logic
var bestPriceAggregator = new Aggregator<PriceQuote, String, BestPrice>(
    PriceQuote::rfqId,
    quotes -> {
        // Select best quote (lowest price)
        return quotes.stream()
            .min(Comparator.comparing(PriceQuote::price))
            .orElseThrow();
    },
    bestPrice -> {
        System.out.println("Best price for " + bestPrice.rfqId() + ": " + bestPrice.price());
        notifyWinner(bestPrice);
    }
);

bestPriceAggregator.expect("RFQ-789", 5);
```

## Integration with Other Patterns

- **Splitter**: Inverse operation - split then aggregate
- **Scatter-Gather**: Aggregator provides the "gather" part
- **Correlation Identifier**: Uses correlation IDs for grouping
- **Resequencer**: Similar but maintains ordering
- **Content-Based Router**: Routes to aggregator

## Performance Considerations

- **Memory Usage**: Stores partial results until completion
- **State Management**: Maintains state per correlation key
- **Completion Detection**: Expected count required
- **Garbage Collection**: Completed aggregations cleaned up

### Best Practices

1. **Always set expectations** before adding parts
2. **Handle timeouts** for partial results
3. **Monitor in-flight aggregations** to detect issues
4. **Clean up stale state** periodically
5. **Use meaningful correlation keys** for debugging

## Aggregator Variations

### Conditional Aggregator

```java
// Aggregate based on condition rather than count
class ConditionalAggregator<T, K, R> {
    public void aggregate(K key, Predicate<List<T>> condition, Function<List<T>, R> aggregateFunction) {
        List<T> parts = new ArrayList<>();

        // Add parts until condition is met
        while (!condition.test(parts)) {
            // Wait for more parts
        }

        R result = aggregateFunction.apply(parts);
        emitResult(key, result);
    }
}
```

### Time-Based Aggregator

```java
// Aggregate all messages within time window
class TimeWindowAggregator<T> {
    private final Map<String, WindowedAggregation<T>> windows = new ConcurrentHashMap<>();

    public void addPart(T part, String windowKey) {
        WindowedAggregation<T> window = windows.computeIfAbsent(windowKey,
            k -> new WindowedAggregation<>(Duration.ofSeconds(10)));

        window.add(part);

        if (window.isExpired()) {
            emit(window.collect());
            windows.remove(windowKey);
        }
    }
}
```

## Comparison with Resequencer

| Aspect | Aggregator | Resequencer |
|--------|-----------|-------------|
| Purpose | Combine messages | Order messages |
| Output | Single composite | Ordered sequence |
| State | Accumulates | Buffers |
| Completion | Count-based | Contiguous sequence |

## Related Patterns

- [Splitter](./splitter.md) - Inverse operation
- [Scatter-Gather](./scatter-gather.md) - Split and aggregate
- [Correlation Identifier](./correlation-identifier.md) - Message grouping
- [Resequencer](./resequencer.md) - Message ordering

## See Also

- [`Aggregator.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/Aggregator.java) - Implementation
- [AggregatorTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/AggregatorTest.java) - Test examples
