# Advanced JOTP Tutorial: Distributed Systems

## Learning Objectives

By the end of this tutorial, you will:
- Understand distributed process architectures in JOTP
- Design multi-node clusters with process communication
- Implement failure detection and recovery
- Build distributed registry patterns
- Create production-ready distributed systems

## Prerequisites

- Complete [Supervision Trees Tutorial](supervision-trees.md)
- Complete [Event Management Tutorial](event-management.md)
- Understanding of distributed systems concepts
- Familiarity with network programming basics

## Introduction: Distributed JOTP

JOTP is designed for distributed systems from the ground up:

**Current Capabilities:**
- In-memory `ProcRegistry` for single-node process naming
- Local process monitoring and supervision
- Event broadcasting within a single JVM

**Planned Capabilities (Roadmap):**
- `DistributedProcRegistry` for cross-node process discovery
- Network-transparent messaging between nodes
- Distributed failure detection
- Cluster membership and gossip protocols

This tutorial covers both current patterns and planned features for distributed systems.

## Current Patterns: Single-Node Distribution

### 1. Process Registry for Naming

Use `ProcRegistry` to name processes and locate them by name:

```java
import io.github.seanchatmangpt.jotp.registry.*;

sealed interface ServiceMessage permits
    ServiceMessage.Request,
    ServiceMessage.Shutdown {

    record Request(String data, ProcRef<String, String> replyTo) implements ServiceMessage {}
    record Shutdown() implements ServiceMessage {}
}

class ServiceExample {
    public static void main(String[] args) throws Exception {
        // Create and register a service
        var service = Proc.spawn(
            null,
            (state, msg) -> {
                if (msg instanceof ServiceMessage.Request r) {
                    var response = "Processed: " + r.data();
                    r.replyTo().tell(response);
                }
                return new Proc.Continue<>(state);
            }
        );

        // Register with a name
        ProcRegistry.register("payment-service", service);

        // Later, lookup by name from anywhere in the application
        var located = ProcRegistry.lookup("payment-service");

        if (located.isPresent()) {
            var serviceRef = located.get();

            // Create client for response
            var client = Proc.spawn(
                null,
                (state, msg) -> {
                    if (msg instanceof String response) {
                        System.out.println("Received: " + response);
                    }
                    return new Proc.Continue<>(state);
                }
            );

            // Send request
            serviceRef.tell(new ServiceMessage.Request(
                "Hello from client",
                client
            ));
        }

        Thread.sleep(100);

        // Unregister when done
        ProcRegistry.unregister("payment-service");
        service.shutdown();
    }
}
```

### 2. Supervised Service Registration

Combine supervision with registration for robust services:

```java
class SupervisedService {
    public static void main(String[] args) throws Exception {
        // Create supervisor for service
        var childSpec = new Supervisor.ChildSpec(
            "critical_service",
            () -> createCriticalService(),
            Supervisor.RestartStrategy.PERMANENT
        );

        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            List.of(childSpec)
        );

        // Register the supervised service
        var serviceRef = supervisor.getChild("critical_service")
            .orElseThrow();

        ProcRegistry.register("critical-service", serviceRef);

        // Service is now both supervised and discoverable
        System.out.println("Service registered and supervised");

        Thread.sleep(1000);

        // Cleanup
        ProcRegistry.unregister("critical-service");
        supervisor.shutdown();
    }

    private static Proc<?, ServiceMessage> createCriticalService() {
        return Proc.spawn(
            null,
            (state, msg) -> {
                if (msg instanceof ServiceMessage.Request r) {
                    // Handle request
                    r.replyTo().tell("Response from critical service");
                }
                return new Proc.Continue<>(state);
            }
        );
    }
}
```

## Planned Features: Multi-Node Distribution

### 1. Distributed ProcRegistry (Planned)

**Feature Status:** Planned for JOTP 1.1

Register processes visible across multiple nodes:

```java
// Future API example (not yet implemented)
class DistributedRegistryExample {
    public static void main(String[] args) throws Exception {
        // Node 1
        var node1 = ClusterNode.create("node1@localhost", 2551);
        var service = Proc.spawn(node1, null, (state, msg) -> {
            // Service logic
            return new Proc.Continue<>(state);
        });

        // Register process - visible across cluster
        DistributedProcRegistry.register(
            "payment-service",
            service,
            node1
        );

        // Node 2 (different machine)
        var node2 = ClusterNode.create("node2@localhost", 2552);

        // Lookup process from different node
        var located = DistributedProcRegistry.lookup(
            "payment-service",
            node2
        );

        if (located.isPresent()) {
            var remoteRef = located.get();

            // Send message across nodes
            remoteRef.tell(new ServiceMessage.Request(
                "Cross-node message",
                null
            ));
        }

        node1.shutdown();
        node2.shutdown();
    }
}
```

**Implementation Considerations:**
- Gossip protocol for registry propagation
- Conflict resolution for duplicate names
- Automatic cleanup on node failure
- Partition tolerance strategies

### 2. Cross-Node Messaging (Planned)

**Feature Status:** Planned for JOTP 1.2

Send messages to processes on remote nodes transparently:

```java
// Future API example (not yet implemented)
sealed interface ClusterMessage permits
    ClusterMessage.NodePing,
    ClusterMessage.WorkRequest {

    record NodePing(String fromNode) implements ClusterMessage {}
    record WorkRequest(String data, ProcRef<?, ?> replyTo) implements ClusterMessage {}
}

class CrossNodeMessaging {
    public static void main(String[] args) throws Exception {
        // Create cluster nodes
        var node1 = ClusterNode.create("node1@localhost", 2551);
        var node2 = ClusterNode.create("node2@localhost", 2552);

        // Connect nodes
        node1.connect(node2);

        // Create process on node2
        var worker = Proc.spawn(
            node2,
            null,
            (state, msg) -> {
                if (msg instanceof ClusterMessage.WorkRequest w) {
                    System.out.println("Node2 received work: " + w.data());
                    // Process work...
                }
                return new Proc.Continue<>(state);
            }
        );

        // Send message from node1 to node2
        var workerRef = ProcRef.create(worker, node2);
        workerRef.tell(new ClusterMessage.WorkRequest(
            "Work from node1",
            null
        ));

        // Message is automatically serialized and sent over network
        // No special code needed - works like local messaging

        Thread.sleep(1000);
        node1.shutdown();
        node2.shutdown();
    }
}
```

**Implementation Considerations:**
- Message serialization (JSON, protobuf, etc.)
- Network transport (TCP, UDP, QUIC)
- Backpressure and flow control
- Message ordering guarantees

### 3. Distributed Failure Detection (Planned)

**Feature Status:** Planned for JOTP 1.3

Detect and respond to node failures:

```java
// Future API example (not yet implemented)
class FailureDetectionExample {
    public static void main(String[] args) throws Exception {
        var node1 = ClusterNode.create("node1@localhost", 2551);
        var node2 = ClusterNode.create("node2@localhost", 2552);
        var node3 = ClusterNode.create("node3@localhost", 2553);

        // Form cluster
        Cluster.join(node1, node2);
        Cluster.join(node1, node3);

        // Monitor node failures
        var monitor = Proc.spawn(
            node1,
            null,
            ClusterFailureDetector.monitor(
                (state, event) -> {
                    if (event instanceof NodeDownEvent down) {
                        System.out.println(
                            "⚠️  Node down: " + down.node().name()
                        );

                        // Redirect work from failed node
                        redistributeWork(down.node());
                    }
                    return new Proc.Continue<>(state);
                }
            )
        );

        // Simulate node failure
        node2.simulateFailure();

        // Other nodes detect failure automatically
        // via heartbeat and gossip protocols

        Thread.sleep(5000);
        node1.shutdown();
        node3.shutdown();
    }

    private static void redistributeWork(ClusterNode failedNode) {
        // Move processes from failed node to healthy nodes
        System.out.println("Redistributing work from failed node...");
    }
}
```

**Implementation Considerations:**
- Phi Accrual Failure Detector
- Gossip-based membership
- Suspicion timeouts and thresholds
- Network partition handling

## Exercise: Multi-Node Cluster Setup

Design a distributed system (using current patterns + planned features).

### Requirements

1. **Cluster Nodes**: 3 nodes forming a cluster
2. **Service Registration**: Services registered across cluster
3. **Load Balancing**: Distribute requests across nodes
4. **Failure Detection**: Detect and respond to node failures
5. **Service Discovery**: Locate services by name

### Solution Design

```java
// Architecture for distributed payment processing system
class DistributedPaymentSystem {

    // ===== NODE 1: API Gateway =====
    static class ApiGatewayNode {
        public static void main(String[] args) throws Exception {
            var node = ClusterNode.create("gateway@localhost", 2551);

            // Connect to cluster
            node.connect("node1@localhost:2552");
            node.connect("node2@localhost:2553");

            // Create API gateway service
            var gateway = createApiGateway(node);

            // Register gateway
            DistributedProcRegistry.register("api-gateway", gateway, node);

            System.out.println("🌐 API Gateway node started");
        }

        private static Proc<?, ?> createApiGateway(ClusterNode node) {
            return Proc.spawn(
                node,
                null,
                (state, msg) -> {
                    if (msg instanceof ApiRequest request) {
                        // Discover payment service
                        var paymentService = DistributedProcRegistry.lookup(
                            "payment-service",
                            node
                        );

                        if (paymentService.isPresent()) {
                            // Forward request to payment service
                            paymentService.get().tell(request);
                        } else {
                            // No payment service available
                            request.replyTo().tell(new ServiceUnavailable());
                        }
                    }
                    return new Proc.Continue<>(state);
                }
            );
        }
    }

    // ===== NODE 2: Payment Service =====
    static class PaymentServiceNode {
        public static void main(String[] args) throws Exception {
            var node = ClusterNode.create("node1@localhost", 2552);

            // Connect to cluster
            node.connect("gateway@localhost:2551");

            // Create payment service with supervision
            var supervisor = createPaymentSupervisor(node);

            // Register payment service
            var serviceRef = supervisor.getChild("payment_worker").orElseThrow();
            DistributedProcRegistry.register("payment-service", serviceRef, node);

            // Monitor cluster for failures
            createFailureMonitor(node, supervisor);

            System.out.println("💳 Payment service node started");
        }

        private static Supervisor createPaymentSupervisor(ClusterNode node) {
            var childSpec = new Supervisor.ChildSpec(
                "payment_worker",
                () -> createPaymentWorker(node),
                Supervisor.RestartStrategy.PERMANENT
            );

            return Supervisor.create(
                node,
                Supervisor.Strategy.ONE_FOR_ONE,
                List.of(childSpec)
            );
        }

        private static Proc<?, ?> createPaymentWorker(ClusterNode node) {
            return Proc.spawn(
                node,
                null,
                (state, msg) -> {
                    if (msg instanceof ApiRequest request) {
                        // Process payment
                        var result = processPayment(request);

                        // Send response back through gateway
                        request.replyTo().tell(result);
                    }
                    return new Proc.Continue<>(state);
                }
            );
        }

        private static Object processPayment(ApiRequest request) {
            // Payment processing logic
            return new PaymentSuccess();
        }

        private static Proc<?, ?> createFailureMonitor(
            ClusterNode node,
            Supervisor supervisor
        ) {
            return Proc.spawn(
                node,
                null,
                ClusterFailureDetector.monitor(
                    (state, event) -> {
                        if (event instanceof NodeDownEvent down) {
                            System.out.println(
                                "⚠️  Node " + down.node().name() + " failed"
                            );

                            // Check if payment service was on failed node
                            if (wasOnFailedNode(down.node())) {
                                // Restart payment service locally
                                System.out.println("🔄 Restarting payment service locally");
                            }
                        }
                        return new Proc.Continue<>(state);
                    }
                )
            );
        }

        private static boolean wasOnFailedNode(ClusterNode node) {
            // Check if service was on failed node
            return true;
        }
    }

    // ===== NODE 3: Analytics Service =====
    static class AnalyticsServiceNode {
        public static void main(String[] args) throws Exception {
            var node = ClusterNode.create("node2@localhost", 2553");

            // Connect to cluster
            node.connect("gateway@localhost:2551");

            // Create analytics service
            var analytics = createAnalyticsService(node);

            // Register analytics service
            DistributedProcRegistry.register("analytics-service", analytics, node);

            // Subscribe to cluster-wide events
            DistributedEventManager.subscribe(
                PaymentCompletedEvent.class,
                analytics,
                node
            );

            System.out.println("📊 Analytics service node started");
        }

        private static Proc<?, ?> createAnalyticsService(ClusterNode node) {
            return Proc.spawn(
                node,
                new AnalyticsState(0, 0.0),
                (state, msg) -> {
                    if (msg instanceof PaymentCompletedEvent event) {
                        var newState = new AnalyticsState(
                            state.count() + 1,
                            state.total() + event.amount()
                        );

                        System.out.printf(
                            "📊 Analytics: %d payments, $%.2f total%n",
                            newState.count(),
                            newState.total()
                        );

                        return new Proc.Continue<>(newState);
                    }
                    return new Proc.Continue<>(state);
                }
            );
        }
    }

    // ===== Supporting Types =====
    record ApiRequest(String data, ProcRef<?, ?> replyTo) {}
    record PaymentSuccess() {}
    record ServiceUnavailable() {}
    record PaymentCompletedEvent(String paymentId, double amount) {}
    record AnalyticsState(int count, double total) {}

    // ===== Cluster Management =====
    static class ClusterNode {
        static ClusterNode create(String name, int port) {
            // Implementation: Create cluster node
            return null;
        }

        void connect(String address) {
            // Implementation: Connect to remote node
        }

        void shutdown() {
            // Implementation: Shutdown node
        }
    }

    static class DistributedProcRegistry {
        static void register(String name, ProcRef<?, ?> ref, ClusterNode node) {
            // Implementation: Register across cluster
        }

        static Optional<ProcRef<?, ?>> lookup(String name, ClusterNode node) {
            // Implementation: Lookup across cluster
            return Optional.empty();
        }
    }

    static class ClusterFailureDetector {
        static <S, M> Proc<S, M> monitor(Proc.Handler<S, M> handler) {
            // Implementation: Create failure monitor
            return null;
        }
    }

    static class DistributedEventManager {
        static <E> void subscribe(
            Class<E> eventType,
            ProcRef<?, ?> subscriber,
            ClusterNode node
        ) {
            // Implementation: Subscribe to cluster-wide events
        }
    }
}
```

## What You Learned

- **Current patterns**: Process registry and supervised services
- **Planned features**: Distributed registry, cross-node messaging, failure detection
- **Architecture patterns**: Multi-node clusters, service discovery, load balancing
- **Real-world design**: Distributed payment processing system

## Current Capabilities vs. Roadmap

| Feature | Status | Version |
|---------|--------|---------|
| ProcRegistry (local) | ✅ Implemented | 1.0 |
| DistributedProcRegistry | 🔄 Planned | 1.1 |
| Cross-node messaging | 🔄 Planned | 1.2 |
| Failure detection | 🔄 Planned | 1.3 |
| Gossip membership | 🔄 Planned | 1.3 |

## Next Steps

- [Contributing Guide](../../CONTRIBUTING.md) - Help build distributed features
- [Architecture Docs](../../ARCHITECTURE.md) - Deep dive into JOTP architecture
- [Roadmap](../../ROADMAP.md) - Future feature timeline

## Additional Exercises

1. **CAP Theorem Analysis**: Design systems for AP vs CP trade-offs
2. **Network Partition Simulation**: Test system behavior during partitions
3. **Eventual Consistency**: Implement CRDTs for distributed state
4. **Service Mesh**: Build inter-service communication patterns
5. **Multi-Datacenter**: Design geo-distributed deployment

## Further Reading

- [Distributed Systems for Fun and Profit](http://book.mixu.net/distsys/)
- [Phoenix Framework - Distribution](https://hexdocs.pm/phoenix/distribution.html)
- [Akka Cluster Documentation](https://doc.akka.io/docs/akka/current/typed/index-cluster.html)
- [CAP Theorem](https://www.ibm.com/topics/cap-theorem)

---

**Note:** Distributed features are currently under active development. Check the [JOTP Roadmap](../../ROADMAP.md) for updates and consider contributing to accelerate development!
