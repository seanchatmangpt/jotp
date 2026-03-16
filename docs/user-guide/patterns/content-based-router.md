# Content-Based Router Pattern

## Overview

The Content-Based Router pattern examines message content and routes each message to the appropriate destination based on predicate conditions. Unlike a fixed router, the routing decision depends on the message's data.

**Enterprise Integration Pattern**: [Content-Based Router](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ContentBasedRouter.html) (EIP §8.1)

**Erlang Analog**: Pattern matching in `receive` clauses - the first matching clause handles the message

## When to Use This Pattern

- **Conditional Routing**: Messages need different handling based on their content
- **Dynamic Dispatch**: Routing logic depends on message data
- **Business Rules**: Different processing for different customer tiers, regions, etc.
- **Message Type Separation**: Different message types go to different handlers
- **Complex Routing Logic**: Multiple conditions determine the destination

## Architecture

```
┌──────────┐
│ Producer │
└────┬─────┘
     │
     │ route(message)
     │
     ▼
┌─────────────────────────┐
│  Content-Based Router   │
│  (Predicate Matching)   │
└───┬────────┬────────┬───┘
    │        │        │
    │ type=A │ type=B │ otherwise
    │        │        │
    ▼        ▼        ▼
┌────────┐┌────────┐┌────────┐
│Dest A  ││Dest B  ││Default │
└────────┘└────────┘└────────┘
```

## JOTP Implementation

### Basic Content-Based Router

```java
// Define message types
record Order(String orderId, String type, List<Item> items) {}

// Create destinations
var typeAProcessor = PointToPoint.<Order>create(o -> processTypeA(o));
var typeBProcessor = PointToPoint.<Order>create(o -> processTypeB(o));
var defaultProcessor = PointToPoint.<Order>create(o -> processDefault(o));

// Build router
var router = ContentBasedRouter.<Order>builder()
    .when(order -> order.type().equals("TypeABC"), typeAProcessor::send)
    .when(order -> order.type().equals("TypeXYZ"), typeBProcessor::send)
    .otherwise(defaultProcessor::send)
    .build();

// Route messages
router.route(new Order("order-1", "TypeABC", items));
router.route(new Order("order-2", "TypeXYZ", items));
router.route(new Order("order-3", "Unknown", items));
```

### Complex Routing Conditions

```java
// Router with complex predicates
var router = ContentBasedRouter.<Transaction>builder()
    .when(tx -> tx.amount() > 10000 && tx.currency().equals("USD"), highValueProcessor::tell)
    .when(tx -> tx.region().equals("EU") && tx.isVatApplicable(), euVatProcessor::tell)
    .when(tx -> tx.customer().tier() == Tier.PLATINUM), platinumProcessor::tell)
    .otherwise(standardProcessor::tell)
    .build();
```

### Router with Dead Letter

```java
// Router with dead letter for unmatched messages
var deadLetter = new DeadLetter<Order>();

var router = ContentBasedRouter.<Order>builder()
    .when(order -> isValidType(order.type()), validOrderProcessor::send)
    .otherwise(order -> deadLetterChannel.dead(order, "Invalid order type"))
    .build();
```

### Domain-Driven Routing

```java
// Domain-specific routing
sealed interface OrderCommand {}
record BuyOrder(String symbol, int quantity) implements OrderCommand {}
record SellOrder(String symbol, int quantity) implements OrderCommand {}
record CancelOrder(String orderId) implements OrderCommand {}

var tradingRouter = ContentBasedRouter.<OrderCommand>builder()
    .when(cmd -> cmd instanceof BuyOrder, buyOrderProcessor::tell)
    .when(cmd -> cmd instanceof SellOrder, sellOrderProcessor::tell)
    .when(cmd -> cmd instanceof CancelOrder, cancelOrderProcessor::tell)
    .build();
```

## Integration with Other Patterns

- **Recipient List**: Router can be a recipient in a list
- **Splitter**: Routes split parts to different destinations
- **Message Filter**: Pre-filters before routing
- **Dynamic Router**: Changes routing rules at runtime
- **Process Manager**: Routes messages to process instances

## Performance Considerations

- **Predicate Evaluation**: Predicates are evaluated in order
- **Early Exit**: First matching predicate wins
- **Complexity**: Keep predicates simple for better performance
- **Otherwise Handler**: Always provide a default for unmatched messages

### Best Practices

1. **Order predicates** from most specific to most general
2. **Keep predicates** simple and fast
3. **Use pattern matching** with sealed interfaces for type safety
4. **Provide an otherwise** handler for unmatched messages
5. **Log routing decisions** for debugging
6. **Monitor routing statistics** for each destination

## Router Composition

```java
// Compose multiple routers
var primaryRouter = ContentBasedRouter.<Message>builder()
    .when(msg -> msg.priority() == Priority.HIGH, highPriorityRouter::route)
    .otherwise(normalPriorityRouter::route)
    .build();

var highPriorityRouter = ContentBasedRouter.<Message>builder()
    .when(msg -> msg.region().equals("US"), usHighPriorityProcessor::tell)
    .otherwise(euHighPriorityProcessor::tell)
    .build();
```

## Related Patterns

- [Message Router](./message-router.md) - Deterministic routing
- [Recipient List](./recipient-list.md) - Dynamic recipient selection
- [Dynamic Router](./dynamic-router.md) - Runtime routing rules
- [Splitter](./splitter.md) - Message decomposition

## See Also

- [`ContentBasedRouter.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/ContentBasedRouter.java) - Implementation
- [ContentBasedRouterTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/ContentBasedRouterTest.java) - Test examples
