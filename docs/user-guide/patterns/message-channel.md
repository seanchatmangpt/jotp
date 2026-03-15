# Message Channel Pattern

## Overview

The Message Channel pattern is the foundational messaging pattern that establishes a logical conduit for transmitting messages between producers and consumers. In JOTP, channels are backed by processes (`Proc`) that provide message passing semantics with guaranteed ordering and isolation.

**Enterprise Integration Pattern**: [Message Channel](https://www.enterpriseintegrationpatterns.com/patterns/messaging/MessageChannel.html) (EIP §4.1)

**Erlang Analog**: Direct process-to-process messaging (`Pid ! Message`) or named process registration (`whereis(Name) ! Message`)

## When to Use This Pattern

- **Decoupling**: You need to decouple message producers from consumers
- **Temporal Decoupling**: Producers and consumers don't need to be active simultaneously
- **Message Ordering**: You need guaranteed FIFO order for messages
- **Isolation**: You want isolated failure domains between components
- **Virtual Thread Scalability**: You need millions of lightweight channels

## Architecture

```
┌─────────────┐                  ┌─────────────┐
│  Producer   │                  │  Consumer   │
│             │                  │  (Proc)     │
└──────┬──────┘                  └──────▲──────┘
       │                                │
       │   Message Channel              │
       │   (Proc Mailbox)               │
       └────────────────────────────────┘
```

## JOTP Implementation

### Command Channel

Command channels carry imperative messages that tell the receiver to perform an action:

```java
// Define command messages
sealed interface TradingCommand permits BuyOrder, SellOrder {}

record BuyOrder(String symbol, int quantity, double price) implements TradingCommand {}
record SellOrder(String symbol, int quantity, double price) implements TradingCommand {}

// Create a command channel
var commandChannel = PointToPoint.<TradingCommand>create(cmd -> {
    switch (cmd) {
        case BuyOrder buy -> executeBuy(buy);
        case SellOrder sell -> executeSell(sell);
    }
});

// Send commands
commandChannel.send(new BuyOrder("AAPL", 100, 150.0));
commandChannel.send(new SellOrder("GOOGL", 50, 2800.0));
```

### Message Channel (Datatype Channel)

Type-safe channels ensure only specific message types flow through:

```java
// Create typed channels for different message types
var orderChannel = DatatypeChannel.create(OrderMessage.class, this::processOrder);
var paymentChannel = DatatypeChannel.create(PaymentMessage.class, this::processPayment);

// Type-safe sending
orderChannel.send(new OrderMessage("order-123", items));
paymentChannel.send(new PaymentMessage("payment-456", 299.99));
```

## Integration with Other Patterns

- **Point-to-Point Channel**: Single consumer receives each message
- **Publish-Subscribe Channel**: Multiple subscribers receive each message
- **Datatype Channel**: Type-safe channel for specific message types
- **Dead Letter Channel**: Handles failed message delivery
- **Content-Based Router**: Routes messages to different channels based on content

## Performance Considerations

- **Memory**: Each channel has a mailbox backed by a virtual thread (~1 KB heap)
- **Throughput**: Virtual threads enable millions of concurrent channels
- **Latency**: Message passing is extremely fast (microsecond-level)
- **Scalability**: Linear scalability with CPU cores for parallel processing

## Related Patterns

- [Point-to-Point Channel](./point-to-point-channel.md) - Single consumer delivery
- [Publish-Subscribe Channel](./publish-subscribe-channel.md) - Multi-subscriber broadcast
- [Datatype Channel](./datatype-channel.md) - Type-safe channels
- [Dead Letter Channel](./dead-letter-channel.md) - Failed message handling
- [Message Bus](./message-bus.md) - Centralized communication hub

## See Also

- [`PointToPoint.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/PointToPoint.java) - Point-to-point implementation
- [`PublishSubscribe.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/PublishSubscribe.java) - Pub-sub implementation
- [`DatatypeChannel.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/DatatypeChannel.java) - Typed channel implementation
