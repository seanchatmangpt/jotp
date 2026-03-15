# Dead Letter Channel Pattern

## Overview

The Dead Letter Channel pattern captures messages that could not be delivered to their intended consumer. When a message fails to be delivered (consumer doesn't exist, consumer is overloaded, or message is invalid), it's moved to a dead letter channel for later analysis and reprocessing.

**Enterprise Integration Pattern**: [Dead Letter Channel](https://www.enterpriseintegrationpatterns.com/patterns/messaging/DeadLetterChannel.html) (EIP §9.4)

**Erlang Analog**: Erlang's system-level `error_logger` and OTP's process exit signals - messages sent to dead processes are captured rather than silently lost

## When to Use This Pattern

- **Error Handling**: You need to capture and analyze failed message deliveries
- **Message Recovery**: You want to reprocess failed messages later
- **Debugging**: You need visibility into message delivery failures
- **Compliance**: Auditing requires tracking all messages, even failed ones
- **Circuit Breaking**: Temporarily store messages when downstream systems are down

## Architecture

```
┌──────────┐
│ Producer │
└────┬─────┘
     │
     │ send()
     │
     ▼
┌─────────────────┐
│   Message       │
│   Channel       │
└───┬─────────┬───┘
    │         │
    │ success │ failure
    │         │
    ▼         ▼
┌─────────┐ ┌──────────────────┐
│Consumer │ │ Dead Letter      │
│         │ │ Channel          │
└─────────┘ │ (Failed Messages)│
            └────────┬─────────┘
                     │
                     │ analyze/retry
                     ▼
            ┌──────────────────┐
            │ Admin Console    │
            └──────────────────┘
```

## JOTP Implementation

### Basic Dead Letter Channel

```java
// Create a dead letter channel
var deadLetter = new DeadLetter<String>();

// Try to send to a consumer
var consumer = PointToPoint.<String>create(msg -> {
    if (msg.equals("FAIL")) {
        throw new RuntimeException("Processing failed");
    }
    System.out.println("Processed: " + msg);
});

// Send messages with dead letter handling
List<String> messages = List.of("Hello", "FAIL", "World");
for (String msg : messages) {
    try {
        consumer.send(msg);
    } catch (Exception e) {
        deadLetter.dead(msg, e.getMessage());
    }
}

// Drain and analyze dead letters
List<DeadLetter.Entry<String>> failed = deadLetter.drain();
for (DeadLetter.Entry<String> entry : failed) {
    System.out.println("Failed: " + entry.message() + " - " + entry.reason());
}
```

### Dead Letter with Retry

```java
// Dead letter channel with automatic retry
class DeadLetterWithRetry<T> {
    private final DeadLetter<T> deadLetter;
    private final Consumer<T> consumer;
    private final int maxRetries;

    public DeadLetterWithRetry(Consumer<T> consumer, int maxRetries) {
        this.deadLetter = new DeadLetter<>();
        this.consumer = consumer;
        this.maxRetries = maxRetries;
    }

    public void sendWithRetry(T message) {
        int attempts = 0;
        while (attempts < maxRetries) {
            try {
                consumer.accept(message);
                return; // Success
            } catch (Exception e) {
                attempts++;
                if (attempts >= maxRetries) {
                    deadLetter.dead(message, "Failed after " + maxRetries + " attempts: " + e.getMessage());
                }
            }
        }
    }

    public List<DeadLetter.Entry<T>> getFailedMessages() {
        return deadLetter.drain();
    }
}
```

## Integration with Other Patterns

- **Message Router**: Sends unmatched messages to dead letter
- **Guaranteed Delivery**: Moves expired messages to dead letter
- **Content Filter**: Dead letter for filtered messages
- **Wire Tap**: Can tap dead letter for monitoring

## Performance Considerations

- **Memory Usage**: Dead letters accumulate in memory
- **Monitoring**: Regularly drain dead letters to prevent memory leaks
- **Persistence**: Consider persistent storage for long-term retention

### Best Practices

1. **Regularly drain** dead letters to prevent memory buildup
2. **Log failures** with detailed error messages
3. **Set retention policies** for dead letter messages
4. **Monitor dead letter depth** for system health

## Related Patterns

- [Content-Based Router](./content-based-router.md) - Can route to dead letter
- [Guaranteed Delivery](./guaranteed-delivery.md) - Moves to dead letter on timeout
- [Message Bus](./message-bus.md) - Centralized error handling

## See Also

- [`DeadLetter.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/channel/DeadLetter.java) - Implementation
- [DeadLetterChannelExample](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/dogfood/messaging/DeadLetterChannelExample.java) - Example usage
