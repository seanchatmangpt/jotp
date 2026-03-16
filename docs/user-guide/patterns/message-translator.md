# Message Translator Pattern

## Overview

The Message Translator pattern transforms a message from one format to another, enabling systems with different data formats to communicate. It acts as an adapter between different message representations.

**Enterprise Integration Pattern**: [Message Translator](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Translator.html) (EIP §7.1)

**Erlang Analog**: A process that receives a message in one format, transforms it, and sends the translated version to the next process

## When to Use This Pattern

- **Format Conversion**: Converting between XML, JSON, binary formats
- **Protocol Bridging**: Different messaging protocols
- **Legacy Integration**: Integrating with legacy systems
- **Canonical Data Model**: Transform to/from canonical format
- **Version Migration**: Supporting multiple message versions

## Architecture

```
┌─────────────┐                  ┌─────────────┐
│   Producer  │                  │  Consumer   │
│  (Format A) │                  │  (Format B) │
└──────┬──────┘                  └──────▲──────┘
       │                                │
       │ Message A                      │ Message B
       │                                │
       ▼                                │
┌──────────────────┐                   │
│  Message         │                   │
│  Translator      │───────────────────┘
│  A → B           │
└──────────────────┘
```

## JOTP Implementation

### Basic Translator

```java
// Define different formats
record XmlOrder(String xml) {}
record JsonOrder(String json) {}

// Create translator
var translator = new MessageTranslator<XmlOrder, JsonOrder>(
    // Translation function
    xmlOrder -> {
        // Parse XML and convert to JSON
        OrderData data = parseXml(xmlOrder.xml());
        return new JsonOrder(toJson(data));
    },
    // Destination for translated messages
    jsonOrder -> {
        System.out.println("Converted to JSON: " + jsonOrder.json());
        processJson(jsonOrder);
    }
);

// Translate XML order to JSON
XmlOrder xmlOrder = new XmlOrder("<order><id>123</id></order>");
translator.translate(xmlOrder);
```

### Protocol Translation

```java
// Translate between different protocols
record RestRequest(String method, String path, Map<String, String> headers) {}
record GrpcRequest(String service, String method, byte[] payload) {}

var protocolTranslator = new MessageTranslator<RestRequest, GrpcRequest>(
    restRequest -> {
        // Convert REST to gRPC
        String[] parts = restRequest.path().split("/");
        String service = parts[1];
        String method = parts[2];
        byte[] payload = convertRestToGrpc(restRequest);
        return new GrpcRequest(service, method, payload);
    },
    grpcRequest -> {
        grpcServer.handle(grpcRequest);
    }
);
```

### Canonical Data Model

```java
// Translate to/from canonical format
record ExternalOrderA(Map<String, Object> data) {}
record ExternalOrderB(List<String> fields) {}
record CanonicalOrder(String id, String customer, List<Item> items) {}

// Translator A → Canonical
var translatorA = new MessageTranslator<ExternalOrderA, CanonicalOrder>(
    external -> {
        return new CanonicalOrder(
            (String) external.data().get("orderId"),
            (String) external.data().get("customerName"),
            extractItems(external.data())
        );
    },
    canonical -> orderSystem.process(canonical)
);

// Translator B → Canonical
var translatorB = new MessageTranslator<ExternalOrderB, CanonicalOrder>(
    external -> {
        return new CanonicalOrder(
            external.fields().get(0),
            external.fields().get(1),
            parseItems(external.fields().subList(2, external.fields().size()))
        );
    },
    canonical -> orderSystem.process(canonical)
);
```

### Version Migration

```java
// Support multiple message versions
sealed interface OrderMessage {}
record OrderV1(String orderId, String customerId) implements OrderMessage {}
record OrderV2(String orderId, CustomerId customerId) implements OrderMessage {}

var v1ToV2Translator = new MessageTranslator<OrderV1, OrderV2>(
    v1 -> new OrderV2(v1.orderId(), new CustomerId(v1.customerId())),
    v2 -> orderSystem.processV2(v2)
);
```

### Bidirectional Translation

```java
// Translate both directions
class BidirectionalTranslator<A, B> {
    private final Function<A, B> aToB;
    private final Function<B, A> bToA;

    public B translateToB(A message) {
        return aToB.apply(message);
    }

    public A translateToA(B message) {
        return bToA.apply(message);
    }
}
```

## Integration with Other Patterns

- **Content-Based Router**: Route based on format, then translate
- **Message Bus**: Multiple translators on a bus
- **Content Enricher**: Translate then enrich
- **Normalizer**: Multiple translators to canonical format

## Performance Considerations

- **CPU Intensive**: Parsing and serialization are expensive
- **Memory overhead**: May create intermediate objects
- **Latency**: Adds processing latency
- **Throughput**: May bottleneck if translation is slow

### Best Practices

1. **Keep transformations simple** for better performance
2. **Use efficient parsers** (e.g., Jackson for JSON)
3. **Cache parsed schemas** for reusability
4. **Validate output** before forwarding
5. **Handle translation errors** gracefully

## Translator Variations

### Enriching Translator

```java
// Translate and enrich in one step
class EnrichingTranslator<A, B> {
    private final Function<A, B> translate;
    private final Function<B, B> enrich;

    public B translateAndEnrich(A input) {
        B translated = translate.apply(input);
        return enrich.apply(translated);
    }
}
```

### Filtering Translator

```java
// Translate only certain messages
class FilteringTranslator<A, B> {
    private final Predicate<A> shouldTranslate;
    private final Function<A, B> translate;
    private final Consumer<B> destination;

    public void translate(A input) {
        if (shouldTranslate.test(input)) {
            B translated = translate.apply(input);
            destination.accept(translated);
        }
    }
}
```

## Related Patterns

- [Content Enricher](./content-enricher.md) - Add data during translation
- [Content Filter](./content-filter.md) - Remove data during translation
- [Normalizer](./normalizer.md) - Multiple translators to canonical format
- [Message Bus](./message-bus.md) - Multiple translators on bus

## See Also

- [`MessageTranslator.java`](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/transformation/MessageTranslator.java) - Implementation
- [TransformationPatternsTest](/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/messagepatterns/transformation/TransformationPatternsTest.java) - Test examples
