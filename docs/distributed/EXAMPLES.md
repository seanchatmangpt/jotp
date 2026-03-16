# Distributed JOTP Examples

## Overview

This directory contains complete, production-ready examples of distributed systems patterns using JOTP. Each example demonstrates how to build fault-tolerant, scalable distributed systems using Java 26 virtual threads and OTP primitives.

## Available Examples

### 1. Distributed Counter with CRDT
**Source:** `src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedCounterExample.java`

Demonstrates a conflict-free replicated data type (Grow-Only Counter) that maintains consistency across multiple nodes.

**Key Concepts:**
- State-based CRDT (G-Counter)
- Eventual consistency
- Peer-to-peer synchronization
- Commutative, associative, idempotent merge operations

**Use Cases:**
- Global counters (metrics, analytics)
- Distributed aggregations
- Fault-tolerant statistics

**Quick Start:**
```bash
# Terminal 1
java DistributedCounterExample node1 8081

# Terminal 2
java DistributedCounterExample node2 8082

# Terminal 3
java DistributedCounterExample node3 8083
```

**Commands:**
- `inc` - Increment counter
- `get` - Get current value
- `state` - Show full CRDT state
- `quit` - Exit

### 2. Distributed Pub/Sub
**Source:** `src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedPubSubExample.java`

Builds a topic-based publish/subscribe system that spans multiple nodes with automatic message forwarding.

**Key Concepts:**
- EventManager for local pub/sub
- Topic-based routing
- Node-to-node message forwarding
- Fault isolation (crashing subscribers don't kill topics)

**Use Cases:**
- Event-driven architectures
- Real-time notifications
- Multi-region event broadcasting

**Quick Start:**
```bash
# Terminal 1
java DistributedPubSubExample node1 9091

# Terminal 2
java DistributedPubSubExample node2 9092

# Terminal 3
java DistributedPubSubExample node3 9093
```

**Commands:**
- `sub <topic>` - Subscribe to topic
- `pub <topic> <type>` - Publish event (order/payment)
- `quit` - Exit

### 3. Distributed Saga Coordinator
**Source:** `src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedSagaExample.java`

Implements the saga pattern for distributed transactions with automatic compensation on failure.

**Key Concepts:**
- Saga orchestration
- Compensation transactions
- Timeout handling
- Multi-node workflow coordination

**Use Cases:**
- Order fulfillment (inventory → payment → shipping)
- Booking workflows (flight → hotel → car)
- Multi-service transactions

**Quick Start:**
```bash
# Terminal 1: Saga Coordinator
java DistributedSagaExample coordinator 7071

# Terminal 2: Service Node
java DistributedSagaExample service 7072

# Terminal 3: Another Service Node
java DistributedSagaExample service 7073
```

**Commands (coordinator):**
- `start <orderId>` - Start new saga
- `quit` - Exit

### 4. Distributed Cache with Consistent Hashing
**Source:** `src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedCacheExample.java`

Creates a distributed cache cluster using consistent hashing for key distribution and replication.

**Key Concepts:**
- Consistent hashing ring
- Configurable replication factor
- Minimal key migration on topology changes
- Replica fallback on failure

**Use Cases:**
- Distributed session storage
- Caching layers
- Hot data distribution

**Quick Start:**
```bash
# Terminal 1
java DistributedCacheExample node1 6081

# Terminal 2
java DistributedCacheExample node2 6082

# Terminal 3
java DistributedCacheExample node3 6083
```

**Commands:**
- `put <key> <value>` - Store key-value pair
- `get <key>` - Retrieve value
- `delete <key>` - Remove key
- `stats` - Show cache statistics
- `quit` - Exit

## Common Patterns

### Pattern 1: Location Transparency
All examples demonstrate location-transparent communication where callers don't know if a service is local or remote.

```java
// Local call
counterProc.tell(new IncrementMsg());

// Remote call (in production, would use RPC/gRPC)
remoteCounterProc.tell(new IncrementMsg());
```

### Pattern 2: Fault Tolerance
Every example uses JOTP supervision trees for automatic recovery:

```java
Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
).supervise("cache-node", initialState, handler);
```

### Pattern 3: Type Safety
All messages use sealed interfaces for exhaustive pattern matching:

```java
public sealed interface Msg permits Msg.A, Msg.B, Msg.C {
    record A() implements Msg {}
    record B() implements Msg {}
    record C() implements Msg {}
}

// Compiler enforces exhaustive handling
switch (msg) {
    case Msg.A a -> handleA(a);
    case Msg.B b -> handleB(b);
    case Msg.C c -> handleC(c);
    // Compiler error if case missing!
}
```

## Deployment

### Local Multi-Node Testing
Run multiple JVM instances in different terminals (as shown above).

### Docker Compose
Use the provided Docker Compose file for multi-container deployment:

```bash
cd docs/distributed
docker-compose up -d

# View logs
docker-compose logs -f

# Stop
docker-compose down
```

### Kubernetes
Deploy to Kubernetes using the provided manifests:

```bash
kubectl apply -f docs/distributed/k8s/

# Check status
kubectl get pods -l app=jotp-distributed

# Port forward for testing
kubectl port-forward svc/jotp-coordinator 7071:7071
```

## Architecture Diagrams

### Distributed Counter CRDT
```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│   Node-1    │         │   Node-2    │         │   Node-3    │
│  Counter    │         │  Counter    │         │  Counter    │
│             │         │             │         │             │
│ count: {    │         │ count: {    │         │ count: {    │
│   n1: 5     │◄────────┤   n1: 5     │◄────────┤   n1: 5     │
│   n2: 3     │  sync   │   n2: 3     │  sync   │   n2: 3     │
│   n3: 7     │────────►│   n3: 7     │────────►│   n3: 7     │
│ }           │         │ }           │         │ }           │
└─────────────┘         └─────────────┘         └─────────────┘
total: 15               total: 15               total: 15
```

### Distributed Pub/Sub
```
┌──────────┐  publish   ┌─────────────┐  forward  ┌─────────────┐
│Publisher │───────────►│ TopicBroker │──────────►│TopicBroker  │
└──────────┘  orders    │  Node-1     │  orders   │  Node-2     │
                         └─────────────┘           └─────────────┘
                               │                         │
                               │ notify                 │ notify
                               ▼                         ▼
                         ┌─────────┐               ┌─────────┐
                         │Sub A    │               │Sub C    │
                         │Handler  │               │Handler  │
                         └─────────┘               └─────────┘
```

### Distributed Saga
```
┌─────────────────────────────────────────────────────────────┐
│                    Saga Coordinator                         │
│                                                              │
│  Step 1: Reserve Inventory ──► [Inventory Service]          │
│         │                                                    │
│         ├─ Success ──► Step 2: Process Payment ──► [Payment │
│         │                                              Service]
│         │                                                    │
│         ├─ Success ──► Step 3: Schedule Shipping ──► [Ship │
│         │                                              Service]
│         │                                                    │
│         └─ Failure ──► Compensate: Release Inventory        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Distributed Cache
```
           Consistent Hash Ring (0 - 2³²-1)
                         │
    ┌────────────────────┼────────────────────┐
    │                    │                    │
    ▼                    ▼                    ▼
┌─────────┐         ┌─────────┐         ┌─────────┐
│ Node-1  │         │ Node-2  │         │ Node-3  │
│ Keys:   │         │ Keys:   │         │ Keys:   │
│ user:1  │         │ user:2  │         │ user:3  │
│ user:5  │         │ user:6  │         │ user:4  │
│         │         │         │         │         │
│ Repl:   │         │ Repl:   │         │ Repl:   │
│ user:2  │         │ user:3  │         │ user:1  │
└─────────┘         └─────────┘         └─────────┘
```

## Production Considerations

### 1. Network Communication
The examples simulate distributed communication. In production, replace with:
- **gRPC:** High-performance RPC (recommended)
- **Apache Pulsar:** Distributed messaging
- **Kafka:** Event streaming
- **NATS:** Lightweight messaging

### 2. Service Discovery
Add service discovery for node registration:
- **Consul:** Health checking + KV store
- **etcd:** Distributed key-value store
- **Kubernetes:** Service discovery built-in
- **ZooKeeper:** Coordination service

### 3. Persistence
Add state persistence for durability:
- **RocksDB:** Embedded key-value store
- **PostgreSQL:** Relational database
- **MongoDB:** Document store
- **Cassandra:** Distributed database

### 4. Monitoring
Add observability:
- **OpenTelemetry:** Distributed tracing
- **Prometheus:** Metrics collection
- **Grafana:** Visualization dashboards
- **ELK Stack:** Log aggregation

### 5. Security
Add security measures:
- **TLS:** Encrypted communication
- **mTLS:** Mutual authentication
- **JWT:** Authorization tokens
- **Network policies:** Kubernetes network security

## Troubleshooting

### Issue: Nodes can't communicate
**Symptoms:** Sync not working, messages not delivered
**Solutions:**
1. Check network connectivity: `ping <host>`
2. Verify ports are open: `netstat -an | grep <port>`
3. Check firewall rules: `ufw status`
4. Validate DNS resolution: `nslookup <hostname>`

### Issue: High memory usage
**Symptoms:** OOM errors, slow GC
**Solutions:**
1. Reduce replication factor
2. Add TTL for cache entries
3. Implement eviction policies
4. Increase heap size: `-Xmx4g`

### Issue: Slow message processing
**Symptoms:** High latency, backlog
**Solutions:**
1. Increase parallelism (more virtual threads)
2. Add batching for bulk operations
3. Implement load shedding
4. Use async fire-and-forget where appropriate

### Issue: Inconsistent state
**Symptoms:** Nodes show different values
**Solutions:**
1. Increase sync frequency
2. Implement anti-entropy protocol
3. Add Merkle tree verification
4. Use vector clocks for causality

## Performance Tuning

### Virtual Thread Pool Size
```java
// Default is fine for most cases
// Adjust if needed:
System.setProperty("jdk.virtualThreadScheduler.parallelism", "16");
```

### Mailbox Capacity
```java
// LinkedTransferQueue is unbounded by default
// For backpressure, use bounded queue:
var boundedQueue = new LinkedBlockingQueue<>(10000);
```

### Sync Interval
```java
// Balance consistency vs overhead
scheduler.scheduleAtFixedRate(
    this::sync,
    5, 5, TimeUnit.SECONDS  // Every 5 seconds
);
```

## Next Steps

1. **Read the source code** - Each example is heavily documented
2. **Run locally** - Start with single-node, then multi-node
3. **Modify and experiment** - Change replication, add features
4. **Deploy to Docker/K8s** - Test in realistic environment
5. **Monitor and tune** - Use metrics to optimize

## Additional Resources

- [JOTP Architecture](../ARCHITECTURE.md) - System design overview
- [OTP Patterns](../otp-patterns.md) - Fault tolerance patterns
- [SLA Patterns](../SLA-PATTERNS.md) - SRE runbooks
- [Integration Patterns](../INTEGRATION-PATTERNS.md) - Brownfield adoption

## Contributing

To add new distributed examples:

1. Create example class in `src/main/java/io/github/seanchatmangpt/jotp/examples/`
2. Add documentation to this README
3. Add Docker Compose service if needed
4. Add Kubernetes manifests if applicable
5. Test locally and in containerized environment

## License

Same as parent JOTP project.
