# Kafka Messaging Pattern

## Problem

You need to build a scalable, fault-tolerant event-driven architecture with durable messaging, replay capabilities, and support for multiple consumers. Traditional message queues lack the durability and scalability needed for high-throughput systems.

**Symptoms:**
- Message loss during failures
- No replay capability for consumers
- Difficulty scaling consumers
- Lack of message ordering guarantees
- No support for event sourcing patterns

## Solution

Use Apache Kafka as the event bus for distributed messaging. JOTP's EventBus can be integrated with Kafka to provide:
- **Durable messaging**: Messages persisted to disk
- **Partitioning**: Parallel processing with ordering guarantees
- **Consumer groups**: Scalable consumer coordination
- **Replayability**: Re-read messages from any offset
- **Exactly-once semantics**: Idempotent producers and consumers

## Prerequisites

- Java 26 with preview features enabled
- Apache Kafka cluster (or local Kafka for development)
- JOTP enterprise module: `io.github.seanchatmangpt.jotp.enterprise.eventbus`
- Understanding of event-driven architecture

## Implementation

### Step 1: Configure Kafka Producer

```java
import io.github.seanchatmangpt.jotp.enterprise.eventbus.*;
import org.apache.kafka.clients.producer.*;

import java.util.Properties;

public class KafkaEventProducer {
    private final KafkaProducer<String, Object> producer;
    private final String topic;

    public KafkaEventProducer(String bootstrapServers, String topic) {
        this.topic = topic;

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "io.github.seanchatmangpt.jotp.enterprise.eventbus.EventSerializer");

        // Enable idempotence for exactly-once
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        this.producer = new KafkaProducer<>(props);
    }

    public CompletableFuture<RecordMetadata> publish(Object event) {
        String eventId = UUID.randomUUID().toString();
        String eventKey = extractKey(event);

        ProducerRecord<String, Object> record = new ProducerRecord<>(
            topic, eventKey, event
        );

        return CompletableFuture.supplyAsync(() -> {
            try {
                RecordMetadata metadata = producer.send(record).get();
                return metadata;
            } catch (Exception e) {
                throw new EventPublishException("Failed to publish event", e);
            }
        });
    }

    private String extractKey(Object event) {
        // Extract partition key for ordering
        return switch (event) {
            case OrderEvent e -> e.orderId();
            case PaymentEvent e -> e.paymentId();
            default -> UUID.randomUUID().toString();
        };
    }

    public void shutdown() {
        producer.close();
    }
}
```

### Step 2: Configure Kafka Consumer

```java
import org.apache.kafka.clients.consumer.*;
import io.github.seanchatmangpt.jotp.Proc;

import java.time.Duration;
import java.util.Properties;

public class KafkaEventConsumer {
    private final KafkaConsumer<String, Object> consumer;
    private final String topic;
    private final String groupId;
    private volatile boolean running = true;

    public KafkaEventConsumer(
        String bootstrapServers,
        String topic,
        String groupId,
        java.util.function.Consumer<Object> eventHandler
    ) {
        this.topic = topic;
        this.groupId = groupId;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "io.github.seanchatmangpt.jotp.enterprise.eventbus.EventDeserializer");

        // Enable auto-commit for simplicity (disable for manual control)
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));

        // Start consumption in background process
        startConsuming(eventHandler);
    }

    private void startConsuming(java.util.function.Consumer<Object> handler) {
        // Create a JOTP process for consumption
        var consumerProc = new Proc<>(
            new ConsumerState(running, 0),
            (state, msg) -> handleConsumerMessage(state, msg, handler)
        );

        // Start polling loop
        new Thread(() -> {
            while (running) {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, Object> record : records) {
                    try {
                        handler.accept(record.value());
                        consumerProc.tell(new ConsumerMessage(record));
                    } catch (Exception e) {
                        System.err.println("Error processing event: " + e.getMessage());
                    }
                }
            }
        }).start();
    }

    public void shutdown() {
        running = false;
        consumer.close();
    }

    private record ConsumerState(boolean running, long processedCount) {}
    private sealed interface ConsumerMessage {
        record Processed(ConsumerRecord<String, Object> record) implements ConsumerMessage {}
    }
}
```

### Step 3: Integrate with JOTP EventBus

```java
public class KafkaEventBus implements EventBusIntegration {
    private final KafkaEventProducer producer;
    private final EventBus localBus;

    public KafkaEventBus(String bootstrapServers, String topic) {
        this.producer = new KafkaEventProducer(bootstrapServers, topic);
        this.localBus = EventBus.create(EventBusConfig.of("kafka-event-bus"));
    }

    @Override
    public EventBus.PublishResult publish(Object event) {
        // Publish to Kafka
        CompletableFuture<RecordMetadata> future = producer.publish(event);

        // Also publish to local bus for in-process subscribers
        localBus.publish(event);

        return new EventBus.PublishResult(
            UUID.randomUUID().toString(),
            EventBus.PublishResult.Status.ACCEPTED,
            ""
        );
    }

    @Override
    public EventBus.Subscription subscribe(String id, java.util.function.Consumer<Object> handler) {
        // Subscribe to local bus (Kafka consumer handles remote events)
        return localBus.subscribe(id, handler);
    }

    public void startConsumer(String groupId, java.util.function.Consumer<Object> handler) {
        new KafkaEventConsumer("localhost:9092", "events", groupId, handler);
    }

    public void shutdown() {
        producer.shutdown();
        localBus.shutdown();
    }
}
```

### Step 4: Implement Exactly-Once Semantics

```java
public class IdempotentEventConsumer {
    private final KafkaConsumer<String, Object> consumer;
    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public IdempotentEventConsumer(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");  // Manual commit

        this.consumer = new KafkaConsumer<>(props);
    }

    public void startConsuming(java.util.function.Consumer<Object> handler) {
        new Thread(() -> {
            while (true) {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, Object> record : records) {
                    String eventId = extractEventId(record);

                    // Skip if already processed
                    if (!processedEventIds.add(eventId)) {
                        System.out.println("Skipping duplicate event: " + eventId);
                        continue;
                    }

                    try {
                        // Process event
                        handler.accept(record.value());

                        // Commit offset after successful processing
                        consumer.commitSync();

                    } catch (Exception e) {
                        System.err.println("Error processing event: " + e.getMessage());
                        // Don't commit on error, will retry
                    }
                }
            }
        }).start();
    }

    private String extractEventId(ConsumerRecord<String, Object> record) {
        // Extract unique event ID
        return record.key() + "-" + record.offset();
    }
}
```

## Complete Examples

### Example 1: Event-Driven Order Processing

```java
public class OrderProcessingService {
    private final KafkaEventBus eventBus;

    public OrderProcessingService() {
        this.eventBus = new KafkaEventBus("localhost:9092", "order-events");

        // Subscribe to order events
        eventBus.subscribe("order-processor", this::handleOrderEvent);

        // Start Kafka consumer
        eventBus.startConsumer("order-processing-group", event -> {
            // Forward Kafka events to local bus
            eventBus.publish(event);
        });
    }

    public void createOrder(CreateOrderCommand cmd) {
        // Create order
        Order order = orderRepository.create(cmd.orderId(), cmd.customerId(), cmd.items());

        // Publish event
        eventBus.publish(new OrderCreated(
            order.id(),
            order.customerId(),
            order.items(),
            Instant.now()
        ));
    }

    private void handleOrderEvent(Object event) {
        switch (event) {
            case OrderCreated created -> {
                System.out.println("Order created: " + created.orderId());

                // Trigger payment processing
                eventBus.publish(new PaymentRequested(
                    created.orderId(),
                    calculateTotal(created.items()),
                    Instant.now()
                ));
            }

            case PaymentCompleted paid -> {
                System.out.println("Payment completed: " + paid.orderId());

                // Trigger shipping
                eventBus.publish(new OrderShipped(
                    paid.orderId(),
                    generateTrackingNumber(),
                    Instant.now()
                ));
            }

            case OrderShipped shipped -> {
                System.out.println("Order shipped: " + shipped.orderId());

                // Send notification
                notificationService.notifyCustomer(shipped.orderId());
            }
        }
    }

    public void shutdown() {
        eventBus.shutdown();
    }
}
```

### Example 2: Event Sourcing with Kafka

```java
public class EventSourcedAggregate {
    private final KafkaEventProducer producer;
    private final EventStore eventStore;

    public EventSourcedAggregate(String bootstrapServers) {
        this.producer = new KafkaEventProducer(bootstrapServers, "aggregate-events");
        this.eventStore = EventStore.create();

        // Replay events from Kafka on startup
        replayFromKafka();
    }

    public void handle(Command command) {
        // Execute command
        List<Object> events = executeCommand(command);

        // Persist to event store
        eventStore.append(command.aggregateId(), events);

        // Publish to Kafka
        events.forEach(producer::publish);
    }

    private void replayFromKafka() {
        // Create consumer to replay all events
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("aggregate-events"));

        // Consume all events to rebuild state
        ConsumerRecords<String, Object> records = consumer.poll(Duration.ofSeconds(10));
        for (ConsumerRecord<String, Object> record : records) {
            eventStore.append(record.key(), List.of(record.value()));
        }

        consumer.close();
    }
}
```

### Example 3: CQRS with Kafka

```java
public class CQRSWithKafka {
    private final CommandDispatcher commands;
    private final QueryDispatcher queries;
    private final KafkaEventBus eventBus;
    private final ReadModelUpdater readModelUpdater;

    public CQRSWithKafka() {
        this.commands = CommandDispatcher.create()
            .register(CreateOrder.class, this::handleCreateOrder);
        this.queries = QueryDispatcher.create()
            .cache(Duration.ofMinutes(5));

        this.eventBus = new KafkaEventBus("localhost:9092", "cqrs-events");
        this.readModelUpdater = new ReadModelUpdater();

        // Subscribe to events to update read models
        eventBus.startConsumer("read-model-group", event -> {
            readModelUpdater.apply(event);
        });
    }

    public String createOrder(CreateOrder cmd) {
        var result = commands.dispatch(cmd);

        if (result.isSuccess()) {
            return cmd.orderId();
        } else {
            throw new CommandException(result.toString());
        }
    }

    public Order getOrder(String orderId) {
        return queries.query(new GetOrderById(orderId)).orElse(null);
    }

    private CommandDispatcher.CommandResult<Order> handleCreateOrder(CreateOrder cmd) {
        // Validate and create order
        Order order = orderRepository.create(cmd.orderId(), cmd.customerId(), cmd.items());

        // Publish event
        eventBus.publish(new OrderCreated(
            order.id(),
            order.customerId(),
            order.items(),
            Instant.now()
        ));

        return CommandDispatcher.CommandResult.ok(order);
    }

    private static class ReadModelUpdater {
        private final Map<String, Order> readModel = new ConcurrentHashMap<>();

        public void apply(Object event) {
            switch (event) {
                case OrderCreated created -> {
                    Order order = new Order(created.orderId(), created.customerId(), created.items());
                    readModel.put(created.orderId(), order);
                }
            }
        }

        public Order get(String orderId) {
            return readModel.get(orderId);
        }
    }
}
```

## Configuration Guidelines

### Producer Configuration

```java
// High throughput (lower durability)
props.put(ProducerConfig.ACKS_CONFIG, "1");
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

// High durability (lower throughput)
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");

// Balanced (default)
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
```

### Consumer Configuration

```java
// Low latency (risk of data loss)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "100");

// High reliability (manual commit)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

// Replay from beginning
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

// Start from new messages only
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
```

### Partitioning Strategy

```java
// GOOD: Partition by aggregate ID (ordering per aggregate)
String partitionKey = event.aggregateId();

// GOOD: Partition by customer ID (all customer events in same partition)
String partitionKey = event.customerId();

// BAD: Random partitioning (no ordering guarantees)
String partitionKey = UUID.randomUUID().toString();
```

## Performance Considerations

### Memory
- **Producer buffer**: ~32 MB (default batch size)
- **Consumer buffer**: ~64 MB (fetch size)
- **EventBus overhead**: ~1 KB per subscriber
- **Scaling**: Linear with throughput

### Latency
- **Producer**: ~1 ms (network + broker)
- **Consumer**: ~1 ms (network + poll)
- **End-to-end**: ~2-5 ms (typical)

### Throughput
- **Producer**: 100K+ messages/second per partition
- **Consumer**: 100K+ messages/second per consumer
- **Cluster**: Scales with partitions

### Optimization Strategies

```java
// 1. Batch messages
producer.sendBatch(List.of(event1, event2, event3));

// 2. Increase batch size
props.put(ProducerConfig.BATCH_SIZE_CONFIG, "32768");

// 3. Increase linger time
props.put(ProducerConfig.LINGER_MS_CONFIG, "10");

// 4. Use multiple partitions
props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, CustomPartitioner.class);
```

## Monitoring

### Key Metrics

```java
record KafkaMessagingMetrics(
    long messagesProduced,         // Total messages produced
    long messagesConsumed,         // Total messages consumed
    double produceRate,            // Messages/second (produce)
    double consumeRate,            // Messages/second (consume)
    Duration avgProduceLatency,    // Average produce latency
    Duration avgConsumeLatency,    // Average consume latency
    long consumerLag,              // Consumer lag (messages behind)
    int partitionCount,            // Number of partitions
    int consumerCount,             // Number of consumers
    Map<Integer, Long> messagesByPartition  // Messages per partition
) {}
```

### Alerting

```java
// Alert on high consumer lag
if (consumerLag > threshold) {
    alertService.send(AlertPriority.HIGH,
        "High consumer lag: " + groupId);
}

// Alert on low produce rate
if (produceRate < expectedRate) {
    alertService.send(AlertPriority.MEDIUM,
        "Low produce rate: " + topic);
}

// Alert on consumer failure
if (consumerNotResponding > timeout) {
    alertService.send(AlertPriority.CRITICAL,
        "Consumer not responding: " + groupId);
}
```

## Common Pitfalls

### 1. Not Handling Consumer Errors

```java
// BAD: Errors crash consumer
while (true) {
    ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
    for (record : records) {
        handler.accept(record.value());  // Can throw!
    }
}

// GOOD: Catch and handle errors
while (true) {
    ConsumerRecords records = consumer.poll(Duration.ofMillis(100));
    for (record : records) {
        try {
            handler.accept(record.value());
        } catch (Exception e) {
            logger.error("Error processing record", e);
            // Send to DLQ or skip
        }
    }
}
```

### 2. Forgetting to Commit Offsets

```java
// BAD: Auto-commit enabled (may process twice)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

// GOOD: Manual commit (exactly-once)
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
// Process, then commit:
consumer.commitSync();
```

### 3. Ignoring Message Ordering

```java
// BAD: Random partitioning (no ordering)
ProducerRecord record = new ProducerRecord<>(topic, null, event);

// GOOD: Consistent partitioning (ordering per key)
ProducerRecord record = new ProducerRecord<>(topic, event.orderId(), event);
```

## Related Guides

- **[Event Sourcing](./event-sourcing.md)** - Persist events in Kafka
- **[CQRS](./cqrs.md)** - Separate command and query with Kafka
- **[Saga Transactions](./saga-transactions.md)** - Coordinate with Kafka events

## References

- **EventBus**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/eventbus/EventBus.java`
- **EventBusConfig**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/eventbus/EventBusConfig.java`
- **Test**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/eventbus/EventBusTest.java`
- **Kafka Docs**: https://kafka.apache.org/documentation/
