# JOTP Distributed Architecture Documentation

This directory contains comprehensive documentation for running JOTP across multiple JVMs in distributed environments.

## Overview

JOTP's distributed architecture extends the OTP model across multiple JVMs while maintaining the same programming model as local actors. The system provides:

- **Location Transparency** - A `ProcRef` is a `ProcRef`, whether local or remote
- **Automatic Failover** - Leader election with 200ms monitoring
- **Zero API Changes** - Existing actor code works across JVMs without modification
- **Fault Tolerance** - Failures are isolated and recovered automatically

## Documentation

### Core Guides

1. **[Multi-JVM Architecture Guide](./MULTI-JVM-ARCHITECTURE.md)**
   - Architecture overview and design philosophy
   - Core components (DistributedNode, DistributedActorBridge, DistributedAppSpec)
   - Communication protocols (TCP health checks, gRPC messaging)
   - Performance characteristics and optimization strategies
   - Security considerations and best practices
   - Monitoring and observability

2. **[Deployment Patterns](./DEPLOYMENT-PATTERNS.md)**
   - Active-Passive pattern (leader election)
   - Active-Active pattern (sharded by key)
   - Geo-Distributed pattern (multi-region)
   - Service Mesh integration (Istio, Linkerd)
   - Hybrid patterns and configuration examples
   - Kubernetes, Docker Compose, and Terraform examples

3. **[Failure Handling](./FAILURE-HANDLING.md)**
   - Failure detection mechanisms
   - Leader failover and takeover procedures
   - Network partition handling and split-brain prevention
   - Graceful degradation strategies
   - State synchronization and recovery
   - Disaster recovery and backup strategies
   - Chaos engineering and testing

## Quick Start

### 1. Create a Distributed Node

```java
import io.github.seanchatmangpt.jotp.distributed.*;

// Create a distributed node
var node = new DistributedNode(
    "my-node",           // Node name
    "localhost",         // Host
    0,                   // Port (0 = OS-assigned)
    NodeConfig.defaults()  // Configuration
);

System.out.println("Node started on port: " + node.nodeId().port());
```

### 2. Define a Distributed Application

```java
// Create distributed application spec
var spec = new DistributedAppSpec(
    "myapp",
    List.of(
        List.of(node1.nodeId()),  // Primary
        List.of(node2.nodeId())   // Backup
    ),
    Duration.ofSeconds(5)  // Failover timeout
);

// Register callbacks
node.register(spec, new ApplicationCallbacks() {
    @Override
    public void onStart(StartMode mode) {
        System.out.println("Application started in mode: " + mode);
        // Initialize your application here
    }

    @Override
    public void onStop() {
        System.out.println("Application stopped");
        // Cleanup resources here
    }
});
```

### 3. Start the Application

```java
// Start on all nodes (only leader will actually run it)
node1.start("myapp");
node2.start("myapp");
node3.start("myapp");
```

### 4. Use Remote Actors

```java
// Create a bridge for remote communication
var bridge = new DistributedActorBridge("localhost", 5432);
bridge.startServer();

// Export a local actor
var proc = new Proc<MyState, MyMsg>(initialState, handler);
ProcRegistry.register("my-service", proc);
bridge.exportActor("my-service", proc);

// From another JVM, create a remote reference
var remoteRef = bridge.remoteRef("localhost", 5432, "my-service");

// Send messages transparently
remoteRef.tell(new MyMsg.Hello("world"));

// Request-reply
var response = remoteRef.ask(new MyMsg.GetState()).get();
```

## Architecture Diagrams

### Cluster Topology

```
┌──────────────────────────────────────────────────────────────┐
│                    Distributed Application                    │
│                    (e.g., payment-processor)                 │
└──────────────────────────────────────────────────────────────┘
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
   ┌─────▼─────┐      ┌─────▼─────┐      ┌─────▼─────┐
   │  Node 1   │      │  Node 2   │      │  Node 3   │
   │  Leader   │      │ Standby   │      │ Standby   │
   │ RUNNING   │      │ Monitoring │      │ Monitoring │
   └─────┬─────┘      └─────┬─────┘      └─────┬─────┘
         │                  │                  │
         └──────────────────┼──────────────────┘
                            │
               TCP PING/PONG (200ms interval)
```

### Message Flow

```
Local Message Send:
  Process A ──inline──> Process B
  Latency: ~1µs

Remote Message Send:
  Process A ──serialize──> Network ──deserialize──> Process B
  Latency: ~120-600µs (LAN), ~10-100ms (WAN)
```

## Key Features

### Leader Election

JOTP uses deterministic priority-based election without distributed consensus:

1. Nodes are ordered by priority (configured in `DistributedAppSpec`)
2. Highest-priority live node becomes leader
3. Standby nodes monitor leader via PING/PONG (200ms interval)
4. On leader failure, next-highest node takes over after timeout

### Failure Detection

- **Health Check Interval**: 200ms between PING requests
- **Connection Timeout**: 300ms per connection attempt
- **Failover Timeout**: Configurable (default: 5 seconds)
- **Detection Strategy**: Multiple consecutive failures before failover

### Message Serialization

JOTP supports multiple serialization strategies:

- **Java Serialization** (default) - Base64-encoded for gRPC transport
- **JSON** (Jackson) - Human-readable, debuggable
- **Protobuf** - Smallest wire size, schema-enforced
- **Custom** - Pluggable `MessageCodec<M>` interface

### State Management

Options for maintaining state across JVMs:

- **Event Sourcing** - Append-only event log with replay
- **Snapshotting** - Periodic state snapshots for faster recovery
- **Replicated State** - Raft consensus for strong consistency
- **Cache** - Eventually consistent distributed cache

## Deployment Patterns

### Active-Passive

- **Use Case**: High availability for stateful services
- **Pros**: Simple, no split-brain risk
- **Cons**: Standby nodes idle
- **Example**: Payment processing system

### Active-Active

- **Use Case**: Horizontal scaling for stateless services
- **Pros**: Linear scalability, no single point of failure
- **Cons**: Client-side routing complexity
- **Example**: Order processing sharded by order ID

### Geo-Distributed

- **Use Case**: Low-latency access across regions
- **Pros**: Disaster recovery, compliance
- **Cons**: Higher operational complexity
- **Example**: Global user database

## Performance Characteristics

| Operation | Local | Remote (LAN) | Remote (WAN) |
|-----------|-------|--------------|--------------|
| `tell()`  | ~1µs  | ~120-600µs   | ~10-100ms    |
| `ask()`   | ~2µs  | ~240-1200µs  | ~20-200ms    |
| Leader Election | N/A | ~200-400ms | ~2-5s |

### Throughput

| Transport | Messages/sec | Latency p50 | Bandwidth |
|-----------|--------------|-------------|-----------|
| Local     | 10M+/s       | <1µs        | N/A       |
| UDP (LAN) | 1.2M/s       | ~80µs       | ~1 Gbps   |
| TCP (LAN) | 800K/s       | ~150µs      | ~800 Mbps |
| gRPC (LAN)| 500K/s       | ~200µs      | ~600 Mbps |

## Security Best Practices

### Transport Security

- Use TLS 1.3 or higher for gRPC
- Enforce mutual authentication (mTLS)
- Rotate certificates automatically (90-day expiry)

### Network Security

- Implement Kubernetes NetworkPolicies
- Use service mesh for mTLS and observability
- Encrypt secrets at rest

### Access Control

- Implement JWT token validation
- Use role-based access control (RBAC)
- Audit all distributed operations

## Monitoring and Observability

### Key Metrics

- **Node Health**: Role (LEADER/STANDBY), uptime
- **Failover Events**: Count, duration, affected nodes
- **Message Metrics**: Sent/received counts, latency percentiles
- **Serialization**: Encode/decode duration, failure rate

### Distributed Tracing

- **OpenTelemetry Integration**: Trace context propagation
- **Span Creation**: Serialize, network transfer, deserialize
- **Correlation**: Link local and remote operations

### Health Checks

- **Kubernetes Probes**: Liveness and readiness endpoints
- **Custom Health Indicators**: Application-specific health
- **Alert Rules**: Prometheus alerts for failures

## Best Practices

### Development

1. **Prefer local actors** when possible (lower latency)
2. **Use sealed interfaces** for message types (type safety)
3. **Implement idempotent handlers** (handle retries)
4. **Avoid large messages** (>1MB) - use shared storage
5. **Test with network injection** (chaos engineering)

### Operations

1. **Graceful shutdown** - call `node.stop()` before terminating
2. **Rolling upgrades** - upgrade standbys first, then leader
3. **Monitor leader election** - detect performance issues
4. **Test failover regularly** - verify recovery procedures
5. **Set JVM heap appropriately** - 2-4x per-actor memory

### Deployment

1. **Use odd-numbered clusters** (3, 5, 7 nodes) for clear majority
2. **Spread nodes across AZs** for fault tolerance
3. **Configure appropriate timeouts** based on network conditions
4. **Enable GC logging** for memory troubleshooting
5. **Set up alerts** for failover events

## Comparison with Alternatives

| Feature | JOTP | Akka Cluster | Hazelcast | Infinispan |
|---------|------|--------------|-----------|------------|
| Same API local/distributed | ✓ | ✗ | ✗ | ✗ |
| Zero new concepts | ✓ | ✗ | ✗ | ✗ |
| Lines of code | <1,000 | 50,000+ | 100,000+ | 80,000+ |
| Virtual thread per actor | ✓ | ✗ | ✗ | ✗ |
| Dependency footprint | Minimal | Large | Large | Large |
| Learning curve | Low | High | High | High |

## Service Mesh Integration

### [Istio Integration Guide](./ISTIO-INTEGRATION.md)
Complete guide for deploying JOTP on Istio service mesh with:
- mTLS configuration
- Traffic management (VirtualService, DestinationRule)
- Canary and blue-green deployments
- Fault injection for chaos testing
- Observability integration (Jaeger, Prometheus, Grafana)

### [Linkerd Integration Guide](./LINKERD-INTEGRATION.md)
Lightweight service mesh guide featuring:
- Simple setup and configuration
- Service profiles for JOTP gRPC
- Traffic splitting with SMI
- Built-in observability with Linkerd Viz
- Lower resource footprint compared to Istio

### [Observability with Service Mesh](./OBSERVABILITY-MESH.md)
Comprehensive observability strategy covering:
- Distributed tracing with OpenTelemetry
- Trace context propagation across JOTP processes
- Golden metrics (latency, traffic, errors, saturation)
- Structured logging with trace correlation
- Prometheus integration and alerting
- Grafana dashboards

### Example Configurations

The `examples/` directory contains working configurations for both service meshes:

- **Istio Examples** (`examples/istio/`)
  - Complete deployment with sidecar injection
  - Canary deployment with traffic splitting
  - Fault injection for chaos testing

- **Linkerd Examples** (`examples/linkerd/`)
  - Lightweight deployment with proxy injection
  - Service profiles for gRPC observability
  - Traffic splitting with SMI spec

- **Helm Values** (`examples/helm/`)
  - Istio mesh override values
  - Linkerd mesh override values
  - Production-ready resource allocation

## Further Reading

- [Erlang/OTP Distribution](http://erlang.org/doc/reference_manual/distributed.html)
- [Distributed Systems: Principles and Paradigms](https://www Distributed-Systems.net/)
- [Designing Data-Intensive Applications](https://dataintensive.net/)
- [Istio Documentation](https://istio.io/latest/docs/)
- [Linkerd Documentation](https://linkerd.io/latest/docs/)
- [OpenTelemetry Java](https://opentelemetry.io/docs/instrumentation/java/)

## Contributing

Contributions to improve the distributed architecture are welcome! Please see the main [CONTRIBUTING.md](../../CONTRIBUTING.md) file for guidelines.

## License

This documentation is part of JOTP and is licensed under the same terms as the main project.
