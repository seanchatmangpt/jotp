# Publish-Subscribe Channel Pattern

## Overview

The Publish-Subscribe Channel pattern broadcasts each message to all subscribed consumers. Unlike point-to-point channels where each message goes to one consumer, pub-sub channels deliver every message to every interested subscriber.

**Enterprise Integration Pattern**: [Publish-Subscribe Channel](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PublishSubscribeChannel.html) (EIP §6.2)

**Erlang Analog**: `gen_event` - one event manager process with arbitrarily many event handlers

## When to Use This Pattern

- **Event Broadcasting**: Multiple subscribers need to receive the same events
- **Fan-out**: One publisher needs to notify many consumers
- **Decoupled Notifications**: Publishers don't know or care who subscribes
- **Event-Driven Architecture**: Building event notification systems
- **Audit Logging**: Multiple independent observers of the same events

## Architecture

```
┌──────────┐
│Publisher │
└────┬─────┘
     │
     │ publish()
     │
     ▼
┌─────────────────────────────┐
│   Publish-Subscribe Channel │
│   (EventManager)            │
└─────┬───────────────────────┘
      │
      ├──────────┬──────────┬──────────┐
      │          │          │          │
      ▼          ▼          ▼          ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│Sub A    │ │Sub B    │ │Sub C    │ │Audit    │
│(Process)│ │(Process)│ │(Process)│ │Log      │
└─────────┘ └─────────┘ └─────────┘ └─────────┘
```

## JOTP Implementation

### Basic Publish-Subscribe

```java
// Create a pub-sub channel
var pubSub = new PublishSubscribe<String>();

// Subscribe multiple consumers
pubSub.subscribe(message -> System.out.println("Sub A: " + message));
pubSub.subscribe(message -> System.out.println("Sub B: " + message));
pubSub.subscribe(message -> System.out.println("Sub C: " + message));

// Publish - all subscribers receive the message
pubSub.publish("Hello World");

// Output:
// Sub A: Hello World
// Sub B: Hello World
// Sub C: Hello World
```

### Synchronous Publishing

```java
// Block until all subscribers process the message
pubSub.publishSync("Critical Event");
```

### Unsubscribing

```java
Consumer<String> subscriber = message -> process(message);
pubSub.subscribe(subscriber);

// Later, unsubscribe
pubSub.unsubscribe(subscriber);
```

### Domain Events Example

```java
// Define domain events
sealed interface DomainEvent permits OrderPlaced, PaymentReceived, OrderShipped {}

record OrderPlaced(String orderId, Instant timestamp) implements DomainEvent {}
record PaymentReceived(String paymentId, double amount) implements DomainEvent {}
record OrderShipped(String orderId, String trackingId) implements DomainEvent {}

// Create event channel
var eventChannel = new PublishSubscribe<DomainEvent>();

// Multiple independent subscribers
eventChannel.subscribe(event -> {
    if (event instanceof OrderPlaced placed) {
        emailService.sendConfirmation(placed.orderId());
    }
});

eventChannel.subscribe(event -> {
    if (event instanceof OrderPlaced placed) {
        analytics.trackOrder(placed.orderId());
    }
});

eventChannel.subscribe(event -> {
    if (event instanceof PaymentReceived payment) {
        accounting.recordPayment(payment.paymentId(), payment.amount());
    }
});

// Publish events
eventChannel.publish(new OrderPlaced("order-123", Instant.now()));
eventChannel.publish(new PaymentReceived("pay-456", 99.99));
```

## Integration with Other Patterns

- **Event Message**: Pub-sub channels typically carry event messages
- **Content-Based Router**: Filter events before publishing
- **Wire Tap**: Tap into pub-sub for monitoring/auditing
- **Message Bus**: Use pub-sub for notification interests
- **Event-Driven Consumer**: Subscribers are event-driven consumers

## Performance Considerations

- **Fan-out Cost**: Each message is delivered N times (once per subscriber)
- **Subscriber Isolation**: Crashes in one subscriber don't affect others
- **Memory**: Each subscriber has its own mailbox
- **Ordering**: Each subscriber receives messages in order, but no global ordering across subscribers

### Best Practices

1. **Keep subscribers fast** to avoid blocking the publisher
2. **Use async publish** when possible
3. **Handle subscriber failures** gracefully (they shouldn't crash the channel)
4. **Monitor subscriber backlog** to detect slow consumers

## Comparison with Point-to-Point

| Aspect | Point-to-Point | Publish-Subscribe |
|--------|----------------|-------------------|
| Consumers per message | Exactly one | All subscribers |
| Use case | Work distribution | Event broadcasting |
| Ordering | Per-consumer | Per-consumer |
| Load balancing | Yes (competing consumers) | No |

## Related Patterns

- [Point-to-Point Channel](./point-to-point-channel.md) - Single consumer delivery
- [Event Message](./event-message.md) - Notification messages
- [Message Bus](./message-bus.md) - Centralized communication
- [Wire Tap](./wire-tap.md) - Message interception

## See Also

- [`PublishSubscribe.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/PublishSubscribe.java) - Implementation
- [`EventManager`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/EventManager.java) - Backing event manager
- [PublishSubscribeChannelTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/PublishSubscribeChannelTest.java) - Test examples
