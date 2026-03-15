# Recipient List Pattern

## Overview

The Recipient List pattern routes a message to a dynamically computed list of recipients. Unlike a static router, the recipient list is computed per-message based on message content and registered recipient interests.

**Enterprise Integration Pattern**: [Recipient List](https://www.enterpriseintegrationpatterns.com/patterns/messaging/RecipientList.html) (EIP §8.4)

**Erlang Analog**: Dynamic fan-out - compute a list of Pids from message content, then send to each: `[Pid ! Msg || Pid <- compute_recipients(Msg)]`

## When to Use This Pattern

- **Dynamic Recipients**: Recipients are determined at runtime based on message content
- **Interest-Based Routing**: Only interested recipients receive messages
- **Multi-Cast**: Send to multiple matching recipients
- **Conditional Broadcasting**: Not all subscribers get all messages
- **Decoupled Publishers**: Publishers don't know specific recipients

## Architecture

```
┌──────────┐
│ Producer │
└────┬─────┘
     │
     │ route(message)
     │
     ▼
┌─────────────────────┐
│   Recipient List    │
│  (Predicate Match)  │
└──┬───────┬───────┬──┘
   │       │       │
   │matchA │matchB │matchC
   │       │       │
   ▼       ▼       ▼
┌─────┐ ┌─────┐ ┌─────┐
│Rec A│ │Rec B│ │Rec C│
└─────┘ └─────┘ └─────┘
```

## JOTP Implementation

### Basic Recipient List

```java
// Create recipient list
var recipientList = new RecipientList<QuoteRequest>();

// Register recipients with interest predicates
recipientList.register(
    "electronics-supplier",
    request -> request.category().equals("electronics"),
    this::handleElectronics
);

recipientList.register(
    "clothing-supplier",
    request -> request.category().equals("clothing"),
    this::handleClothing
);

recipientList.register(
    "books-supplier",
    request -> request.category().equals("books"),
    this::handleBooks
);

// Route to all matching recipients
QuoteRequest request = new QuoteRequest("RFQ-001", "electronics", items);
int recipientCount = recipientList.route(request);
System.out.println("Sent to " + recipientCount + " suppliers");
```

### Dynamic Recipient Selection

```java
// Recipients based on order value
var recipientList = new RecipientList<Order>();

recipientList.register(
    "premium-processor",
    order -> order.total() > 10000,
    this::processPremium
);

recipientList.register(
    "standard-processor",
    order -> order.total() > 100 && order.total() <= 10000,
    this::processStandard
);

recipientList.register(
    "basic-processor",
    order -> order.total() <= 100,
    this::processBasic
);

// Order routes to appropriate processor(s)
recipientList.route(new Order("order-1", 15000)); // premium only
recipientList.route(new Order("order-2", 5000));  // standard only
recipientList.route(new Order("order-3", 50));    // basic only
```

### Geographic Routing

```java
// Geographic-based recipients
var geoRouter = new RecipientList<Shipment>();

geoRouter.register(
    "north-america-facility",
    shipment -> shipment.region().equals("NA"),
    this::handleNorthAmerica
);

geoRouter.register(
    "europe-facility",
    shipment -> shipment.region().equals("EU"),
    this::handleEurope
);

geoRouter.register(
    "asia-pacific-facility",
    shipment -> shipment.region().equals("APAC"),
    this::handleAsiaPacific
);

// Ship to matching facilities
geoRouter.route(new Shipment("ship-1", "NA", items)); // NA facility
```

### Multi-Recipient Scenarios

```java
// Message can go to multiple recipients
var notificationList = new RecipientList<Notification>();

notificationList.register(
    "email-notifier",
    notif -> notif.preferences().emailEnabled(),
    this::sendEmail
);

notificationList.register(
    "sms-notifier",
    notif -> notif.preferences().smsEnabled(),
    this::sendSMS
);

notificationList.register(
    "push-notifier",
    notif -> notif.preferences().pushEnabled(),
    this::sendPush
);

// Send to all enabled channels
Notification notification = new Notification(user, message, preferences);
int channels = notificationList.route(notification);
System.out.println("Sent via " + channels + " channels");
```

## Integration with Other Patterns

- **Content-Based Router**: Recipient list can be used as a destination
- **Publish-Subscribe**: Similar but with filtering
- **Splitter**: Route split parts to different recipients
- **Message Filter**: Pre-filter before recipient list

## Performance Considerations

- **Predicate Evaluation**: All predicates evaluated for each message
- **Recipient Count**: Can route to 0 to N recipients
- **Ordering**: No guaranteed delivery order across recipients
- **Thread Safety**: Concurrent routing supported

### Best Practices

1. **Keep predicates simple** for better performance
2. **Handle zero matches** gracefully
3. **Monitor recipient statistics** for each recipient
4. **Consider recipient capacity** to avoid overload
5. **Log routing decisions** for debugging

## Recipient Management

```java
// Dynamic recipient management
class ManagedRecipientList<T> {
    private final RecipientList<T> recipientList;
    private final Map<String, Recipient<T>> recipients = new ConcurrentHashMap<>();

    public void addRecipient(String id, Predicate<T> predicate, Consumer<T> handler) {
        recipientList.register(id, handler, predicate);
        recipients.put(id, new Recipient<>(id, handler, predicate));
    }

    public void removeRecipient(String id) {
        Recipient<T> recipient = recipients.remove(id);
        if (recipient != null) {
            // Recipient list needs rebuild or mark as inactive
        }
    }

    public int recipientCount() {
        return recipientList.recipientCount();
    }
}
```

## Comparison with Publish-Subscribe

| Aspect | Recipient List | Publish-Subscribe |
|--------|---------------|-------------------|
| Recipient Selection | Predicate-based per message | Static subscription |
| Filtering | Yes | No (all get all) |
| Use Case | Conditional routing | Event broadcasting |
| Flexibility | High | Lower |

## Related Patterns

- [Content-Based Router](./content-based-router.md) - Single recipient routing
- [Publish-Subscribe Channel](./publish-subscribe-channel.md) - Broadcast to all
- [Splitter](./splitter.md) - Message decomposition
- [Message Router](./message-router.md) - Deterministic routing

## See Also

- [`RecipientList.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/routing/RecipientList.java) - Implementation
- [RecipientListRouterTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messaging/routing/RecipientListRouterTest.java) - Test examples
