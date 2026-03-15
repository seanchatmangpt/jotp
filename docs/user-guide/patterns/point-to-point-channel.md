# Point-to-Point Channel Pattern

## Overview

The Point-to-Point Channel pattern ensures each message is consumed by exactly one receiver. Messages are delivered in FIFO order to a single consumer process, providing guaranteed ordering and exclusive consumption.

**Enterprise Integration Pattern**: [Point-to-Point Channel](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PointToPointChannel.html) (EIP §6.1)

**Erlang Analog**: Direct process-to-process send (`Pid ! Message`) - the message lands in exactly one process's mailbox

## When to Use This Pattern

- **Exclusive Processing**: Each message should be processed by exactly one consumer
- **Ordered Processing**: Messages must be processed in the order they arrive
- **Work Distribution**: You need to distribute work across multiple consumers (via competing consumers)
- **Load Balancing**: Multiple consumers competing for messages from the same channel
- **Command Semantics**: Sending commands that should be executed exactly once

## Architecture

```
┌──────────┐
│Producer 1│
└────┬─────┘
     │
┌────┴─────┐
│Producer 2│
└────┬─────┘
     │
     ├─────────────────────┐
     │                     │
     ▼                     ▼
┌─────────────────┐   ┌─────────────────┐
│ Point-to-Point  │   │ Point-to-Point  │
│    Channel 1    │   │    Channel 2    │
│   (Consumer A)  │   │   (Consumer B)  │
└─────────────────┘   └─────────────────┘
```

## JOTP Implementation

### Basic Point-to-Point Channel

```java
// Create a simple point-to-point channel
var channel = PointToPoint.<String>create(message -> {
    System.out.println("Processing: " + message);
});

// Send messages
channel.send("Message 1");
channel.send("Message 2");
channel.send("Message 3");
```

### Stateful Point-to-Point Channel

```java
// Create a channel with stateful processing
var counterChannel = PointToPoint.createStateful(
    0,  // initial state
    (count, message) -> {
        int newCount = count + 1;
        System.out.println("Processed " + newCount + " messages");
        return newCount;
    }
);
```

### Competing Consumers (Load Balancing)

```java
// Multiple consumers competing for messages from the same channel
var competingConsumers = new CompetingConsumer<String>(
    4,  // 4 worker threads
    message -> {
        // Each message processed by exactly one worker
        processMessage(message);
    }
);

// Submit work to the shared queue
for (int i = 0; i < 100; i++) {
    competingConsumers.submit("Work-" + i);
}
```

## Integration with Other Patterns

- **Competing Consumers**: Multiple consumers sharing a point-to-point channel for load balancing
- **Message Router**: Routes messages to different point-to-point channels based on content
- **Datatype Channel**: Type-safe point-to-point delivery
- **Dead Letter Channel**: Handles messages that can't be delivered
- **Guaranteed Delivery**: Ensures messages aren't lost if the consumer fails

## Performance Considerations

- **Throughput**: Single consumer limited to one message at a time
- **Parallelism**: Use competing consumers for parallel processing
- **Memory**: Each channel has a mailbox buffer (configurable size)
- **Backpressure**: Producer can outpace consumer, causing mailbox buildup

### Best Practices

1. **Monitor mailbox depth** to detect slow consumers
2. **Use competing consumers** for higher throughput
3. **Set appropriate mailbox sizes** to prevent memory issues
4. **Handle consumer failures** with supervision strategies

## Related Patterns

- [Publish-Subscribe Channel](./publish-subscribe-channel.md) - Multi-subscriber delivery
- [Competing Consumers](./competing-consumers.md) - Load balancing
- [Message Router](./message-router.md) - Content-based routing
- [Datatype Channel](./datatype-channel.md) - Type-safe channels

## See Also

- [`PointToPoint.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/PointToPoint.java) - Implementation
- [`CompetingConsumer.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/endpoint/CompetingConsumer.java) - Load balancing
- [PointToPointChannelTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/PointToPointChannelTest.java) - Test examples
