# Resequencer Pattern

## Overview

The Resequencer pattern reorders out-of-sequence messages back into their correct order. Messages arrive with sequence numbers, and the resequencer buffers them until it can emit a contiguous sequence from the beginning.

**Enterprise Integration Pattern**: [Resequencer](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html) (EIP §8.7)

**Erlang Analog**: A `gen_server` maintaining a TreeMap of buffered messages, dispatching contiguous sequences starting from the expected next index

## When to Use This Pattern

- **Message Ordering**: Messages must be processed in order but arrive out of order
- **Network Reordering**: Multi-path delivery causes reordering
- **Parallel Processing**: Parallel producers with ordering requirements
- **Sequence Recovery**: Recover correct sequence after errors
- **Temporal Ordering**: Time-based message ordering

## Architecture

```
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│Msg 1│ │Msg 3│ │Msg 2│ │Msg 4│
│seq=0│ │seq=2│ │seq=1│ │seq=3│
└──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
   │      │      │      │
   └──────┴──────┴──────┘
          │
          ▼
┌──────────────────┐
│   Resequencer    │
│  (Buffer & Sort) │
│  Expected: 0     │
└────────┬─────────┘
         │
         │ contiguous: 0,1,2,3
         ▼
┌──────────────────┐
│  Ordered Output  │
│  Msg 0,1,2,3     │
└──────────────────┘
```

## JOTP Implementation

### Basic Resequencer

```java
// Define sequenced message
var resequencer = new Resequencer<String>(
    // Consumer receives messages in correct order
    message -> {
        System.out.println("Processing in order: " + message);
    }
);

// Submit messages out of order
resequencer.submit(new Resequencer.Sequenced<>("batch-1", 2, 4, "Message D"));
resequencer.submit(new Resequencer.Sequenced<>("batch-1", 0, 4, "Message A"));
resequencer.submit(new Resequencer.Sequenced<>("batch-1", 1, 4, "Message B"));
resequencer.submit(new Resequencer.Sequenced<>("batch-1", 3, 4, "Message C"));

// Output: Message A, B, C, D (in order)
```

### Multi-Stream Resequencing

```java
// Resequence multiple streams independently
var resequencer = new Resequencer<Trade>(
    trade -> {
        executeTrade(trade);
    }
);

// Process trades for different orders
resequencer.submit(new Resequencer.Sequenced<>("order-1", 1, 3, trade1));
resequencer.submit(new Resequencer.Sequenced<>("order-2", 0, 2, trade2));
resequencer.submit(new Resequencer.Sequenced<>("order-1", 0, 3, trade3));
resequencer.submit(new Resequencer.Sequenced<>("order-2", 1, 2, trade4));

// Each order processed in correct order
```

### Timeout Handling

```java
// Resequencer with timeout for incomplete sequences
class TimeoutResequencer<T> {
    private final Resequencer<T> resequencer;
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Duration timeout;

    public void submit(Resequencer.Sequenced<T> message) {
        resequencer.submit(message);
        lastSeen.put(message.correlationId(), Instant.now());
    }

    public void checkTimeouts() {
        Instant cutoff = Instant.now().minus(timeout);
        lastSeen.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                handleTimeout(entry.getKey());
                return true;
            }
            return false;
        });
    }
}
```

### Resequencer with Gap Detection

```java
// Detect and handle gaps in sequence
class GapDetectingResequencer<T> {
    public void submit(Resequencer.Sequenced<T> message) {
        // Check for gaps
        int expected = getExpected(message.correlationId());
        if (message.index() > expected) {
            System.out.println("Gap detected: expected " + expected +
                             ", got " + message.index());
            reportGap(message.correlationId(), expected, message.index());
        }
        // Continue with normal resequencing
    }
}
```

## Integration with Other Patterns

- **Aggregator**: Similar state management but for ordering
- **Message Router**: Route before resequencing
- **Splitter**: Split then resequence parts
- **Correlation Identifier**: Uses correlation IDs for grouping

## Performance Considerations

- **Memory Usage**: Buffers out-of-order messages
- **TreeMap Overhead**: O(log n) for insert/lookup
- **Garbage Collection**: Cleanup completed sequences
- **Blocking**: May block if messages never arrive

### Best Practices

1. **Set timeouts** for incomplete sequences
2. **Monitor buffer depth** for memory issues
3. **Handle gaps** gracefully
4. **Log missing messages** for debugging
5. **Use meaningful correlation IDs**

## Comparison with Aggregator

| Aspect | Resequencer | Aggregator |
|--------|-------------|------------|
| Purpose | Order messages | Combine messages |
| Output | Ordered sequence | Single composite |
| Completion | Contiguous | Expected count |
| State | TreeMap buffer | List accumulator |

## Related Patterns

- [Aggregator](./aggregator.md) - Message combination
- [Splitter](./splitter.md) - Message decomposition
- [Correlation Identifier](./correlation-identifier.md) - Message grouping
- [Message Router](./message-router.md) - Pre-resequence routing

## See Also

- [`Resequencer.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/Resequencer.java) - Implementation
- [ResequencerTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/ResequencerTest.java) - Test examples
