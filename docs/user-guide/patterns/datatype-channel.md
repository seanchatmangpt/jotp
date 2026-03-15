# Datatype Channel Pattern

## Overview

The Datatype Channel pattern ensures type safety by using separate channels for each message type. Each channel carries only one specific message type, allowing the receiver to know exactly what type to expect without runtime type checks.

**Enterprise Integration Pattern**: [Datatype Channel](https://www.enterpriseintegrationpatterns.com/patterns/messaging/DatatypeChannel.html) (EIP §6.1)

**Erlang Analog**: Separate registered processes for each message type - e.g., `product_queries`, `price_quotes`, `purchase_orders` - each with a typed receive clause

## When to Use This Pattern

- **Type Safety**: You want compile-time type safety for message channels
- **Message Segregation**: Different message types should flow through separate channels
- **Clear Contracts**: Channel purpose is explicit from its type
- **Avoid Type Casting**: You want to avoid runtime type checks and casts
- **Domain Modeling**: Channel types reflect domain concepts

## Architecture

```
┌─────────────┐
│  Producer   │
└──────┬──────┘
       │
       ├─────────┬─────────┬─────────┐
       │         │         │         │
       ▼         ▼         ▼         ▼
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│Channel<T1>│ │Channel<T2>│ │Channel<T3>│ │Channel<T4>│
│Product Qry│ │Price Quote│ │Order     │ │Payment   │
└─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
      │             │             │             │
      ▼             ▼             ▼             ▼
┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│Consumer 1│ │Consumer 2│ │Consumer 3│ │Consumer 4│
│(Proc<T1>)│ │(Proc<T2>)│ │(Proc<T3>)│ │(Proc<T4>)│
└──────────┘ └──────────┘ └──────────┘ └──────────┘
```

## JOTP Implementation

### Basic Datatype Channel

```java
// Define message types
record ProductQuery(String productId) {}
record PriceQuote(String productId, double price) {}
record PurchaseOrder(String orderId, List<Item> items) {}

// Create typed channels for each message type
var queryChannel = DatatypeChannel.create(ProductQuery.class, query -> {
    System.out.println("Query: " + query.productId());
});

var quoteChannel = DatatypeChannel.create(PriceQuote.class, quote -> {
    System.out.println("Quote: " + quote.productId() + " = $" + quote.price());
});

var orderChannel = DatatypeChannel.create(PurchaseOrder.class, order -> {
    System.out.println("Order: " + order.orderId());
});

// Send messages to appropriate channels
queryChannel.send(new ProductQuery("SKU-001"));
quoteChannel.send(new PriceQuote("SKU-001", 29.99));
orderChannel.send(new PurchaseOrder("ORD-123", items));
```

### Domain-Specific Channels

```java
// Trading system channels
sealed interface TradingMessage {}

record BuyOrder(String symbol, int quantity, double price) implements TradingMessage {}
record SellOrder(String symbol, int quantity, double price) implements TradingMessage {}
record MarketData(String symbol, double bid, double ask) implements TradingMessage {}

// Create separate channels for each type
var buyChannel = DatatypeChannel.create(BuyOrder.class, this::executeBuy);
var sellChannel = DatatypeChannel.create(SellOrder.class, this::executeSell);
var marketDataChannel = DatatypeChannel.create(MarketData.class, this::updateMarket);

// Type-safe routing
void routeMessage(TradingMessage msg) {
    switch (msg) {
        case BuyOrder buy -> buyChannel.send(buy);
        case SellOrder sell -> sellChannel.send(sell);
        case MarketData data -> marketDataChannel.send(data);
    }
}
```

### Channel Registry

```java
// Registry for managing multiple datatype channels
class ChannelRegistry {
    private final Map<Class<?>, DatatypeChannel<?>> channels = new ConcurrentHashMap<>();

    <T> void register(Class<T> messageType, Consumer<T> handler) {
        var channel = DatatypeChannel.create(messageType, handler);
        channels.put(messageType, channel);
    }

    @SuppressWarnings("unchecked")
    <T> void send(T message) {
        DatatypeChannel<T> channel = (DatatypeChannel<T>) channels.get(message.getClass());
        if (channel != null) {
            channel.send(message);
        }
    }
}

// Usage
var registry = new ChannelRegistry();
registry.register(OrderMessage.class, this::handleOrder);
registry.register(PaymentMessage.class, this::handlePayment);

registry.send(new OrderMessage("order-1"));
registry.send(new PaymentMessage("pay-1", 100.0));
```

## Integration with Other Patterns

- **Message Bus**: Datatype channels can be registered with a message bus
- **Content-Based Router**: Routes messages to appropriate datatype channels
- **Point-to-Point Channel**: Each datatype channel is a point-to-point channel
- **Message Translator**: Transforms between datatype channels

## Performance Considerations

- **Memory**: Each channel has its own process (~1 KB heap)
- **Type Safety**: Zero runtime overhead for type checking
- **Channel Lookup**: O(1) channel lookup by message type
- **Scalability**: Millions of channels supported via virtual threads

### Best Practices

1. **Use sealed interfaces** for message type hierarchies
2. **Name channels** after the message type they carry
3. **Register channels** in a central registry for discovery
4. **Document message contracts** clearly for each channel

## Comparison with Untyped Channels

| Aspect | Datatype Channel | Untyped Channel |
|--------|------------------|-----------------|
| Type Safety | Compile-time | Runtime checks |
| Performance | No casting overhead | Casts required |
| Documentation | Self-documenting | Requires documentation |
| Refactoring | Compiler-assisted | Manual updates |

## Related Patterns

- [Message Channel](./message-channel.md) - Foundation pattern
- [Point-to-Point Channel](./point-to-point-channel.md) - Single consumer
- [Message Bus](./message-bus.md) - Centralized communication
- [Content-Based Router](./content-based-router.md) - Type-based routing

## See Also

- [`DatatypeChannel.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/DatatypeChannel.java) - Implementation
- [DataTypeChannelTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/channels/DataTypeChannelTest.java) - Test examples
