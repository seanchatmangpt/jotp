# JOTP Messaging Patterns Library

This directory contains comprehensive documentation for all 34 Vaughn Vernon messaging patterns implemented in JOTP (Java 26 OTP framework).

## Pattern Categories

### Channel Patterns (5 patterns)

Foundational patterns for message transmission between producers and consumers.

1. [Message Channel](./message-channel.md) - Basic message transmission conduit
2. [Point-to-Point Channel](./point-to-point-channel.md) - Single consumer delivery
3. [Publish-Subscribe Channel](./publish-subscribe-channel.md) - Multi-subscriber broadcast
4. [Datatype Channel](./datatype-channel.md) - Type-safe channels
5. [Dead Letter Channel](./dead-letter-channel.md) - Failed message handling

### Routing Patterns (11 patterns)

Patterns for directing messages to appropriate destinations based on various criteria.

6. [Content-Based Router](./content-based-router.md) - Predicate-based routing
7. [Message Router](./message-router.md) - Deterministic routing (round-robin)
8. [Recipient List](./recipient-list.md) - Dynamic recipient selection
9. [Splitter](./splitter.md) - Message decomposition
10. [Aggregator](./aggregator.md) - Message correlation and combination
11. [Resequencer](./resequencer.md) - Message ordering
12. [Dynamic Router](./dynamic-router.md) - Runtime routing rules
13. [Routing Slip](./routing-slip.md) - Message-carried routing instructions
14. [Process Manager](./process-manager.md) - Long-running process coordination
15. [Scatter-Gather](./scatter-gather.md) - Fan-out with aggregation
16. [Composed Message Processor](./composed-message-processor.md) - Processing pipeline

### Transformation Patterns (4 patterns)

Patterns for converting message formats and content.

17. [Message Translator](./message-translator.md) - Format transformation
18. [Content Enricher](./content-enricher.md) - Message enhancement
19. [Content Filter](./content-filter.md) - Content-based filtering
20. [Normalizer](./normalizer.md) - Format standardization

### Messaging Construction Patterns (8 patterns)

Patterns for structuring messages themselves.

21. [Command Message](./command-message.md) - Imperative message pattern
22. [Document Message](./document-message.md) - Data transfer message
23. [Event Message](./event-message.md) - Notification of facts
24. [Request-Reply](./request-reply.md) - Synchronous request-response
25. [Correlation Identifier](./correlation-identifier.md) - Request-reply matching
26. [Return Address](./return-address.md) - Reply destination
27. [Message Metadata](./message-metadata.md) - Message headers and properties
28. [Message Expiration](./message-expiration.md) - Time-based message validity

### Process Management Patterns (3 patterns)

Patterns for orchestrating complex processing workflows.

29. [Pipes and Filters](./pipes-and-filters.md) - Processing pipeline
30. [Smart Proxy](./smart-proxy.md) - Message interception
31. [Service Activator](./service-activator.md) - Process integration

### Endpoint Patterns (3 patterns)

Patterns for message consumption and production.

32. [Polling Consumer](./polling-consumer.md) - Pull-based message consumption
33. [Event-Driven Consumer](./event-driven-consumer.md) - Push-based message consumption
34. [Competing Consumer](./competing-consumer.md) - Load balancing

### Management Patterns (7 patterns)

Patterns for system management and monitoring.

35. [Control Bus](./control-bus.md) - System management messages
36. [Wire Tap](./wire-tap.md) - Message interception for monitoring
37. [Message Bus](./message-bus.md) - Centralized communication hub
38. [Canonical Message](./canonical-message.md) - Standardized message format
39. [Guaranteed Delivery](./guaranteed-delivery.md) - Reliable message delivery
40. [Invalid Message Channel](./invalid-message-channel.md) - Invalid message handling
41. [Messaging Bridge](./messaging-bridge.md) - System interconnection

## Pattern Implementation Matrix

| Pattern | Implementation | Test | Example |
|---------|---------------|------|---------|
| Message Channel | `PointToPoint.java` | `PointToPointChannelTest.java` | - |
| Point-to-Point | `PointToPoint.java` | `PointToPointChannelTest.java` | - |
| Publish-Subscribe | `PublishSubscribe.java` | `PublishSubscribeChannelTest.java` | - |
| Datatype Channel | `DatatypeChannel.java` | `DataTypeChannelTest.java` | - |
| Dead Letter | `DeadLetter.java` | `DeadLetterChannelTest.java` | `DeadLetterChannelExample.java` |
| Content-Based Router | `ContentBasedRouter.java` | `ContentBasedRouterTest.java` | - |
| Message Router | `MessageRouter.java` | `RoutingPatternsTest.java` | - |
| Recipient List | `RecipientList.java` | `RecipientListRouterTest.java` | - |
| Splitter | `Splitter.java` | `SplitterTest.java` | - |
| Aggregator | `Aggregator.java` | `AggregatorTest.java` | - |
| Resequencer | `Resequencer.java` | `ResequencerTest.java` | - |
| Dynamic Router | `DynamicRouter.java` | `DynamicRouterTest.java` | - |
| Routing Slip | `RoutingSlip.java` | `RoutingSlipTest.java` | - |
| Process Manager | `ProcessManager.java` | `ProcessManagerTest.java` | - |
| Scatter-Gather | `ScatterGather.java` | `ScatterGatherTest.java` | `ScatterGatherPatterns.java` |
| Message Translator | `MessageTranslator.java` | `TransformationPatternsTest.java` | - |
| Content Enricher | `ContentEnricher.java` | `TransformationPatternsTest.java` | - |
| Content Filter | `ContentFilter.java` | `MessageFilterTest.java` | - |
| Command Message | `CommandMessage.java` | `ConstructionPatternsTest.java` | - |
| Document Message | `DocumentMessage.java` | `ConstructionPatternsTest.java` | - |
| Event Message | `EventMessage.java` | `ConstructionPatternsTest.java` | - |
| Request-Reply | `RequestReply.java` | `ConstructionPatternsTest.java` | - |
| Correlation Identifier | `CorrelationIdentifier.java` | `ConstructionPatternsTest.java` | - |
| Pipes and Filters | `PipesAndFilters.java` | `ManagementPatternsTest.java` | - |
| Smart Proxy | `SmartProxy.java` | `ManagementPatternsTest.java` | - |
| Wire Tap | `WireTap.java` | `ManagementPatternsTest.java` | - |
| Polling Consumer | `PollingConsumer.java` | `EndpointPatternsTest.java` | - |
| Competing Consumer | `CompetingConsumer.java` | `EndpointPatternsTest.java` | - |
| Message Bus | `MessageBus.java` | `MessageBusPatternsTest.java` | `MessageBusPatterns.java` |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     JOTP Messaging Patterns                  │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │   Channels   │  │   Routing    │  │Transformation│       │
│  │              │  │              │  │              │       │
│  │ • P2P        │  │ • CBR        │  │ • Translator │       │
│  │ • Pub-Sub    │  │ • Router     │  │ • Enricher   │       │
│  │ • Datatype   │  │ • Splitter   │  │ • Filter     │       │
│  │ • Dead Letter│  │ • Aggregator │  │              │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │Construction  │  │  Management  │  │  Endpoints   │       │
│  │              │  │              │  │              │       │
│  │ • Command    │  │ • Pipes&Fil. │  │ • Polling    │       │
│  │ • Document   │  │ • Wire Tap   │  │ • Event-Drv  │       │
│  │ • Event      │  │ • Process Mgr│  │ • Competing  │       │
│  │ • Request-Rep│  │ • Smart Proxy│  │              │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

## Usage Guide

### Getting Started

1. **Choose Your Pattern**: Start with the pattern category that matches your use case
2. **Read Documentation**: Each pattern has comprehensive documentation with examples
3. **Explore Tests**: Look at test files for real usage patterns
4. **Run Examples**: Many patterns have example code in the dogfood package

### Pattern Selection Guide

- **Need to send commands?** → [Command Message](./command-message.md) + [Point-to-Point Channel](./point-to-point-channel.md)
- **Broadcasting events?** → [Event Message](./event-message.md) + [Publish-Subscribe Channel](./publish-subscribe-channel.md)
- **Routing based on content?** → [Content-Based Router](./content-based-router.md)
- **Complex workflows?** → [Process Manager](./process-manager.md)
- **Message transformation?** → [Message Translator](./message-translator.md)
- **Load balancing?** → [Competing Consumer](./competing-consumer.md)

## Contributing

When adding new patterns:
1. Create implementation in `src/main/java/io/github/seanchatmangpt/jotp/messagepatterns/`
2. Add tests in `src/test/java/io/github/seanchatmangpt/jotp/messagepatterns/`
3. Document the pattern in this directory
4. Update this README with the new pattern

## References

- [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/) - Gregor Hohpe and Bobby Woolf
- [Reactive Messaging Patterns with the Actor Model](https://www.amazon.com/Reactive-Messaging-Patterns-Actor-Model/dp/1943918228) - Vaughn Vernon
- [JOTP Documentation](../) - Main project documentation
- [Erlang/OTP Design Principles](https://www.erlang.org/doc/design_principles/des_princ.html) - Erlang/OTP patterns

## License

This documentation is part of the JOTP project. See main project LICENSE for details.
