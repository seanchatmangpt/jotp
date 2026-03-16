# Message Router Pattern

## Overview

The Message Router pattern provides deterministic routing logic that directs messages to destinations based on fixed routing rules. Unlike content-based routing which examines message content, message routers use predefined strategies like round-robin, alternating, or weighted distribution.

**Enterprise Integration Pattern**: [Message Router](https://www.enterpriseintegrationpatterns.com/patterns/messaging/MessageRouter.html) (EIP §8.1)

**Erlang Analog**: A `gen_server` maintaining an internal counter or routing state, selecting the next destination deterministically

## When to Use This Pattern

- **Load Distribution**: Distribute messages across multiple consumers
- **Round-Robin Processing**: Alternate between destinations evenly
- **Deterministic Routing**: Fixed routing strategy independent of message content
- **Resource Sharing**: Multiple workers sharing the load
- **Failover**: Alternate destinations if one fails

## Architecture

```
┌──────────┐
│ Producer │
└────┬─────┘
     │
     │ route()
     │
     ▼
┌──────────────────┐
│  Message Router  │
│  (Round-Robin)   │
└──┬──┬──┬──┬──┬───┘
   │  │  │  │  │
   ▼  ▼  ▼  ▼  ▼
┌───┐┌───┐┌───┐┌───┐┌───┐
│ C1││ C2││ C3││ C4││ C5│
└───┘└───┘└───┘└───┘└───┘
```

## JOTP Implementation

### Basic Round-Robin Router

```java
// Create multiple destinations
var consumer1 = PointToPoint.<String>create(msg -> process(msg, 1));
var consumer2 = PointToPoint.<String>create(msg -> process(msg, 2));
var consumer3 = PointToPoint.<String>create(msg -> process(msg, 3));

// Create round-robin router
var router = new MessageRouter<String>(
    consumer1::send,
    consumer2::send,
    consumer3::send
);

// Messages are distributed in round-robin fashion
router.route("Message 1"); // Goes to consumer1
router.route("Message 2"); // Goes to consumer2
router.route("Message 3"); // Goes to consumer3
router.route("Message 4"); // Goes to consumer1 (wraps around)
```

### Alternating Router

```java
// Simple alternating between two destinations
var primary = PointToPoint.<WorkItem>create(this::processPrimary);
var secondary = PointToPoint.<WorkItem>create(this::processSecondary);

var router = new MessageRouter<WorkItem>(
    primary::send,
    secondary::send
);

// Alternates between primary and secondary
for (WorkItem item : workItems) {
    router.route(item);
}
```

### Router with Multiple Workers

```java
// Create pool of workers
List<PointToPoint<Task>> workers = new ArrayList<>();
for (int i = 0; i < 10; i++) {
    final int workerId = i;
    var worker = PointToPoint.<Task>create(task -> {
        processTask(task, workerId);
    });
    workers.add(worker);
}

// Create router with all workers
var router = new MessageRouter<Task>(
    workers.stream()
        .map(w -> w::send)
        .toArray(Consumer[]::new)
);

// Distributes tasks across all 10 workers
tasks.forEach(router::route);
```

### Router State Monitoring

```java
// Monitor router state
var router = new MessageRouter<String>(destinations);

// Track which destination gets next message
int destinationCount = router.destinationCount();
System.out.println("Router has " + destinationCount + " destinations");
```

## Integration with Other Patterns

- **Competing Consumers**: Router distributes to competing consumer pools
- **Channel Adapter**: Routes between different messaging systems
- **Load Balancer**: Simple load balancing across services
- **Message Filter**: Pre-filter before routing
- **Recipient List**: Router as one recipient in a list

## Performance Considerations

- **Thread Safety**: Router uses atomic counter for thread-safe round-robin
- **Memory Overhead**: Minimal - just maintains destinations and counter
- **Distribution Quality**: Even distribution over time
- **Zero Content Inspection**: No message parsing overhead

### Best Practices

1. **Use for even distribution** when message processing times are similar
2. **Monitor consumer backpressure** to prevent mailbox buildup
3. **Combine with supervision** to restart failed consumers
4. **Consider weighted routing** if consumers have different capacities

## Router Variations

### Weighted Round-Robin

```java
// Custom weighted router
class WeightedRouter<T> {
    private final List<Consumer<T>> destinations;
    private final List<Integer> weights;
    private final AtomicInteger counter = new AtomicInteger(0);

    public WeightedRouter(Map<Consumer<T>, Integer> weightedDestinations) {
        this.destinations = new ArrayList<>();
        this.weights = new ArrayList<>();
        weightedDestinations.forEach((dest, weight) -> {
            destinations.add(dest);
            weights.add(weight);
        });
    }

    public void route(T message) {
        int totalWeight = weights.stream().mapToInt(Integer::intValue).sum();
        int index = counter.getAndIncrement() % totalWeight;

        int cumulativeWeight = 0;
        for (int i = 0; i < destinations.size(); i++) {
            cumulativeWeight += weights.get(i);
            if (index < cumulativeWeight) {
                destinations.get(i).accept(message);
                return;
            }
        }
    }
}
```

### Sticky Router

```java
// Routes all messages with same key to same destination
class StickyRouter<K, T> {
    private final List<Consumer<T>> destinations;
    private final Function<T, K> keyExtractor;
    private final Map<K, Integer> keyToDestination = new ConcurrentHashMap<>();

    public StickyRouter(List<Consumer<T>> destinations, Function<T, K> keyExtractor) {
        this.destinations = List.copyOf(destinations);
        this.keyExtractor = keyExtractor;
    }

    public void route(T message) {
        K key = keyExtractor.apply(message);
        int index = keyToDestination.computeIfAbsent(key,
            k -> keyToDestination.size() % destinations.size());
        destinations.get(index).accept(message);
    }
}
```

## Comparison with Content-Based Router

| Aspect | Message Router | Content-Based Router |
|--------|---------------|----------------------|
| Routing Decision | Fixed strategy | Message content |
| Use Case | Load distribution | Conditional routing |
| Complexity | Simple | Can be complex |
| Performance | Higher (no inspection) | Lower (predicate evaluation) |

## Related Patterns

- [Content-Based Router](./content-based-router.md) - Content-based routing
- [Recipient List](./recipient-list.md) - Dynamic recipient selection
- [Competing Consumer](./competing-consumer.md) - Load balancing
- [Splitter](./splitter.md) - Message decomposition

## See Also

- [`MessageRouter.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/MessageRouter.java) - Implementation
- [RoutingPatternsTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/RoutingPatternsTest.java) - Test examples
