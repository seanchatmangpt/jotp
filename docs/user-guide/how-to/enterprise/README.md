# JOTP Enterprise Patterns - How-To Guides

This directory contains practical how-to guides for implementing enterprise integration patterns with JOTP (Java OTP). Each guide follows a problem-solution format with step-by-step instructions, code examples, and production-ready configurations.

## Available Guides

### 1. [Circuit Breaker](./circuit-breaker.md)
Protect external service calls from cascading failures. Learn to configure failure thresholds, handle OPEN state, and implement reset strategies.

**Use when:**
- Calling external services that may fail
- Preventing cascading failures
- Implementing fail-fast patterns

### 2. [Bulkhead Isolation](./bulkhead-isolation.md)
Isolate resource-intensive features to prevent system-wide degradation. Configure concurrent request limits, handle queue timeouts, and monitor utilization.

**Use when:**
- Processing heavy computations (image processing, ML inference)
- Preventing resource starvation
- Isolating feature-specific resources

### 3. [Saga Transactions](./saga-transactions.md)
Implement distributed saga for multi-step transactions with compensating actions. Define compensation logic, handle saga failures, and monitor execution.

**Use when:**
- Coordinating transactions across microservices
- Implementing compensating transactions
- Managing distributed workflows

### 4. [Rate Limiting](./rate-limiting.md)
Control request rates using token bucket, sliding window, and per-client limiting. Implement distributed rate limiting with Redis and handle rate limit exceeded scenarios.

**Use when:**
- Protecting APIs from overload
- Implementing per-tenant limits
- Preventing abuse and spikes

### 5. [Event Sourcing](./event-sourcing.md)
Implement event-sourced aggregates with EventStore. Store events, build projections, handle event replay, and optimize with snapshots.

**Use when:**
- Need complete audit trail
- Replaying events for debugging
- Building temporal queries

### 6. [CQRS](./cqrs.md)
Separate command and query dispatchers for optimized read/write models. Implement read models, handle eventual consistency, and optimize queries.

**Use when:**
- Read and write requirements conflict
- Need different data models for commands/queries
- Scaling read and write sides independently

### 7. [Distributed Locks](./distributed-locks.md)
Acquire distributed locks across instances, handle lock expiration, and implement Redis-based locking with deadlock prevention.

**Use when:**
- Coordinating access to shared resources
- Preventing duplicate task execution
- Multi-instance deployments

### 8. [Kafka Messaging](./kafka-messaging.md)
Configure Kafka as event bus, consume/produce messages, handle serialization, and implement exactly-once semantics.

**Use when:**
- Building event-driven architecture
- Need durable messaging with replay
- Scaling consumers with partitions

## Pattern Relationships

```
┌─────────────────────────────────────────────────────────────┐
│                    Enterprise Patterns                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Resilience                                                 │
│  ├── Circuit Breaker ──┬──> Bulkhead Isolation             │
│  └── Rate Limiting ────┘                                     │
│                                                             │
│  Coordination                                              │
│  ├── Saga Transactions ───> Distributed Locks               │
│  └── Kafka Messaging ─────> Event Sourcing ──> CQRS         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Common Patterns

### Composition Example: E-Commerce Order Processing

```java
public class OrderService {
    // Circuit breaker for payment gateway
    private final CircuitBreakerPattern paymentBreaker;

    // Bulkhead for order processing
    private final BulkheadIsolationEnterprise orderBulkhead;

    // Saga for distributed transaction
    private final DistributedSagaCoordinator orderSaga;

    // Event sourcing for audit trail
    private final EventStore eventStore;

    // CQRS for read optimization
    private final CommandDispatcher commands;
    private final QueryDispatcher queries;

    // Rate limiting for API protection
    private final RateLimiter apiRateLimiter;

    // Kafka for event distribution
    private final KafkaEventBus eventBus;
}
```

## Quick Reference

### Resilience Patterns

| Pattern | Key Benefit | Typical Config |
|---------|-------------|----------------|
| Circuit Breaker | Fail fast | 3 failures / 60s window |
| Bulkhead Isolation | Resource isolation | 10 concurrent requests |
| Rate Limiting | Prevent overload | 100 req/s token bucket |

### Coordination Patterns

| Pattern | Key Benefit | Typical Config |
|---------|-------------|----------------|
| Saga Transactions | Distributed consistency | Compensating actions |
| Distributed Locks | Mutual exclusion | 30s TTL, auto-renewal |
| Kafka Messaging | Event streaming | 3 partitions, 2 replicas |

### Data Patterns

| Pattern | Key Benefit | Typical Config |
|---------|-------------|----------------|
| Event Sourcing | Audit trail | Snapshot every 1000 events |
| CQRS | Read/write optimization | 5min query cache |

## Getting Started

1. **Choose your pattern** based on your problem
2. **Read the how-to guide** for implementation details
3. **Run the examples** to understand behavior
4. **Adapt configuration** to your requirements
5. **Monitor metrics** in production

## Code Examples

All guides include:
- Problem statements
- Solution overviews
- Step-by-step instructions
- Complete working examples
- Production configurations
- Performance considerations
- Common pitfalls to avoid

## Testing

```bash
# Run enterprise pattern tests
mvnd test -Dtest=*CircuitBreaker*
mvnd test -Dtest=*Bulkhead*
mvnd test -Dtest=*Saga*
mvnd test -Dtest=*Enterprise*
```

## Further Reading

- **Architecture**: `/Users/sac/jotp/docs/architecture/README.md`
- **Design Decisions**: `/Users/sac/jotp/docs/explanations/design-decisions.md`
- **Thesis**: `/Users/sac/jotp/docs/phd-thesis-otp-java26.md`
- **Examples**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/`

## Contributing

When adding new patterns:
1. Follow the problem-solution format
2. Include complete code examples
3. Add performance considerations
4. Document common pitfalls
5. Provide monitoring guidance

## License

Apache License 2.0 - See LICENSE file for details
