# Distributed Patterns in JOTP

## Overview

JOTP provides distributed computing primitives for building multi-node clusters with automatic failover, process migration, and JVM crash survival. This document explains the distributed patterns, architecture, and usage.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Node Discovery](#node-discovery)
3. [Leader Election](#leader-election)
4. [Global Process Registry](#global-process-registry)
5. [Failover and Process Migration](#failover-and-process-migration)
6. [Distributed Applications](#distributed-applications)
7. [Usage Examples](#usage-examples)

## Architecture Overview

JOTP distributed system consists of three layers:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Application Layer                            │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  DistributedAppSpec (app configuration)                  │  │
│  │  ApplicationCallbacks (lifecycle hooks)                  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Coordination Layer                           │
│  ┌──────────────────┐  ┌──────────────────────────────────┐   │
│  │  NodeDiscovery   │  │  GlobalProcRegistry              │   │
│  │  - Health checks │  │  - Cluster-wide naming           │   │
│  │  - Membership    │  │  - Process location tracking     │   │
│  └──────────────────┘  └──────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  FailoverController                                      │  │
│  │  - Process migration                                      │  │
│  │  - Load balancing                                         │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Persistence Layer                            │
│  ┌──────────────────┐  ┌──────────────────────────────────┐   │
│  │ RocksDBBackend   │  │  InMemoryBackend                 │   │
│  │ (persistent)     │  │  (testing)                       │   │
│  └──────────────────┘  └──────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Node Discovery

### Purpose

Node discovery manages cluster membership and health monitoring. It answers two questions:
1. **Which nodes are alive?** - Get healthy nodes for process placement
2. **When does a node fail?** - Trigger failover when a node goes down

### Components

- **NodeDiscovery** - Interface for cluster membership
- **StaticNodeDiscovery** - Static configuration implementation
- **NodeDiscoveryBackend** - Pluggable storage for node registry
- **NodeInfo** - Node metadata (name, address, health status)

### Usage Example

```java
// Create node discovery backend
NodeDiscoveryBackend backend = new InMemoryNodeDiscoveryBackend();

// Configure static node discovery
NodeDiscovery discovery = new StaticNodeDiscovery(
    "node1",  // This node's name
    List.of("node1", "node2", "node3"),  // All nodes in cluster
    Map.of(
        "node1", "localhost:8080",
        "node2", "localhost:8081",
        "node3", "localhost:8082"
    ),
    backend
);

// Start health checks (every 1 second, timeout after 3 missed heartbeats)
discovery.startHealthChecks();

// Add node down listener
discovery.addNodeDownListener(failedNode -> {
    System.out.println("Node " + failedNode + " failed!");
    // Trigger failover
});

// Get healthy nodes
List<String> healthyNodes = discovery.getHealthyNodes();
```

### Health Check Algorithm

```
┌─────────────────────────────────────────────────────────────┐
│  Health Check Loop (runs every 1 second)                    │
│                                                             │
│  for each node in cluster:                                  │
│    if now - node.lastHeartbeat > timeout (3 seconds):      │
│      mark node as DOWN                                      │
│      trigger onNodeDown(node)                               │
│    else if node.status == DOWN && node.heartbeatReceived:  │
│      mark node as UP                                        │
│      trigger onNodeUp(node)                                 │
└─────────────────────────────────────────────────────────────┘
```

## Leader Election

### Purpose

Distributed applications run on **only one node at a time** - the highest-priority live node. This prevents split-brain and ensures consistency.

### Deterministic Election Algorithm

```
┌─────────────────────────────────────────────────────────────┐
│  Leader Election (same algorithm on all nodes)              │
│                                                             │
│  1. Scan priority list from highest to lowest              │
│  2. First node found alive becomes the leader               │
│  3. Lower-priority nodes monitor the leader                 │
│  4. On leader failure, next live node takes over            │
└─────────────────────────────────────────────────────────────┘
```

### Example

```java
// Priority list: node1 > node2 > node3
List<NodeId> priority = List.of(
    new NodeId("node1", "localhost", 8080),
    new NodeId("node2", "localhost", 8081),
    new NodeId("node3", "localhost", 8082)
);

// All nodes run this election algorithm:
for (NodeId candidate : priority) {
    if (ping(candidate)) {  // Node is alive
        if (candidate.equals(self)) {
            // I am the highest-priority live node → become leader
            startApplication();
        } else {
            // Higher-priority node is alive → become standby
            monitor(candidate);
        }
        break;  // Election complete
    }
}
```

### Failover Timeout

When the leader fails, standby nodes wait before taking over:

```java
DistributedAppSpec spec = new DistributedAppSpec(
    "myapp",
    List.of(List.of(node1, node2, node3)),
    Duration.ofSeconds(5)  // Wait 5s before failover
);
```

**Why wait?** To prevent spurious failover during temporary network blips.

## Global Process Registry

### Purpose

The global registry enables **location-transparent messaging** - send messages to named processes without knowing which node hosts them.

### Key Operations

```java
GlobalProcRegistry registry = GlobalProcRegistry.getInstance();

// Register a process globally
registry.registerGlobal("payment-processor", procRef, "node1");

// Find a process from any node
Optional<GlobalProcRef> ref = registry.findGlobal("payment-processor");
if (ref.isPresent()) {
    ref.get().localRef().tell(new PaymentRequest(100));
}

// Unregister when done
registry.unregisterGlobal("payment-processor");
```

### Idempotent Registration

Each registration includes a sequence number for crash safety:

```java
public record GlobalProcRef(
    String name,
    ProcRef<?, ?> localRef,
    String nodeName,
    long sequenceNumber,  // ← Increments on each update
    Instant registeredAt
) implements SequencedState {
    // ...
}
```

**On recovery:** If state sequence ≠ ACK sequence, rebuild from event log.

## Failover and Process Migration

### Purpose

When a node fails, its processes must be **migrated to healthy nodes** to maintain availability.

### Failover Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. NodeDiscovery detects node failure                      │
│     onNodeDown("node2")                                      │
│                                                             │
│  2. FailoverController finds all processes on failed node   │
│     processesToMigrate = [                                   │
│       "payment-service" → node2,                            │
│       "user-session-123" → node2,                           │
│       "order-processor" → node2                             │
│     ]                                                       │
│                                                             │
│  3. Distribute processes across healthy nodes (round-robin) │
│     "payment-service" → node1                               │
│     "user-session-123" → node3                              │
│     "order-processor" → node1                               │
│                                                             │
│  4. Update GlobalProcRegistry with new locations            │
│     registry.transferGlobal("payment-service", "node1");    │
└─────────────────────────────────────────────────────────────┘
```

### Implementation

```java
// Configure failover controller
FailoverController failover = new FailoverController(
    GlobalProcRegistry.getInstance(),
    nodeDiscovery
);

// Register node down listener
nodeDiscovery.addNodeDownListener(failedNode -> {
    failover.handleNodeDown(failedNode);
});

// When node2 fails:
// - Find all processes on node2
// - Distribute to node1 and node3 (round-robin)
// - Update registry with new locations
```

### Process Migration Strategy

Processes are distributed using **round-robin** assignment:

```java
List<String> healthyNodes = discovery.getHealthyNodes();  // [node1, node3]
List<Process> processes = getProcessesOn(failedNode);     // [p1, p2, p3]

for (int i = 0; i < processes.size(); i++) {
    String targetNode = healthyNodes.get(i % healthyNodes.size());
    migrateProcess(processes.get(i), targetNode);
}

// Result:
// p1 → node1
// p2 → node3
// p3 → node1
```

## Distributed Applications

### Purpose

Distributed applications coordinate startup/shutdown across nodes with:
- **Leader election** - Only one node runs the app
- **Failover** - Next node takes over after timeout
- **Takeover** - Higher-priority node reclaims when it rejoins

### Application Lifecycle

```
┌─────────────────────────────────────────────────────────────┐
│  Normal Start                                                │
│  - Highest-priority node calls onStart(Normal)              │
│  - Lower-priority nodes become standby                      │
│                                                             │
│  Failover                                                    │
│  - Leader node fails                                        │
│  - Next live node calls onStart(Failover) after timeout     │
│                                                             │
│  Takeover                                                    │
│  - Higher-priority node rejoins                             │
│  - Lower-priority node calls onStop()                       │
│  - Higher-priority node calls onStart(Takeover)             │
└─────────────────────────────────────────────────────────────┘
```

### Usage Example

```java
// Create distributed nodes
var node1 = new DistributedNode("cp1", "localhost", 0, NodeConfig.defaults());
var node2 = new DistributedNode("cp2", "localhost", 0, NodeConfig.defaults());
var node3 = new DistributedNode("cp3", "localhost", 0, NodeConfig.defaults());

// Configure application spec
var spec = new DistributedAppSpec("payment-service",
    List.of(
        List.of(node1.nodeId()),  // Priority 1: node1
        List.of(node2.nodeId(), node3.nodeId())  // Priority 2: node2 or node3
    ),
    Duration.ofSeconds(5)
);

// Register callbacks on all nodes
ApplicationCallbacks callbacks = new ApplicationCallbacks() {
    @Override
    public void onStart(StartMode mode) {
        System.out.println("Starting as " + mode);
        // Start payment service processes
    }

    @Override
    public void onStop() {
        System.out.println("Stopping");
        // Shutdown payment service processes
    }
};

node1.register(spec, callbacks);
node2.register(spec, callbacks);
node3.register(spec, callbacks);

// Start application on all nodes (only node1 runs)
node1.start("payment-service");  // onStart(Normal)
node2.start("payment-service");  // Becomes standby
node3.start("payment-service");  // Becomes standby

// If node1 fails:
// - node2 detects failure after 5s
// - node2 calls onStart(Failover(node1))

// If node1 rejoins:
// - node1 calls onStop() on node2
// - node1 calls onStart(Takeover(node2))
```

## Usage Examples

### Example 1: Distributed Cache

```java
// Create 3-node cluster
var nodes = List.of(
    new DistributedNode("node1", "localhost", 0, NodeConfig.defaults()),
    new DistributedNode("node2", "localhost", 0, NodeConfig.defaults()),
    new DistributedNode("node3", "localhost", 0, NodeConfig.defaults())
);

// Configure node discovery
NodeDiscoveryBackend discoveryBackend = new InMemoryNodeDiscoveryBackend();
NodeDiscovery discovery = new StaticNodeDiscovery(
    "node1",
    List.of("node1", "node2", "node3"),
    Map.of(
        "node1", "localhost:8080",
        "node2", "localhost:8081",
        "node3", "localhost:8082"
    ),
    discoveryBackend
);
discovery.startHealthChecks();

// Configure global registry with persistence
PersistenceBackend persistence = new RocksDBBackend(Path.of("/var/lib/jotp"));
GlobalRegistryBackend registryBackend = new RocksDBGlobalRegistryBackend(persistence);
DefaultGlobalProcRegistry.setBackend(registryBackend);

// Configure failover
FailoverController failover = new FailoverController(
    GlobalProcRegistry.getInstance(),
    discovery
);
discovery.addNodeDownListener(failover::handleNodeDown);

// Register distributed cache application
DistributedAppSpec cacheSpec = new DistributedAppSpec(
    "distributed-cache",
    List.of(List.of(nodes.get(0).nodeId(), nodes.get(1).nodeId(), nodes.get(2).nodeId())),
    Duration.ZERO
);

for (var node : nodes) {
    node.register(cacheSpec, new ApplicationCallbacks() {
        @Override
        public void onStart(StartMode mode) {
            // Start cache processes on this node
            System.out.println(node.nodeId() + " starting cache as " + mode);
        }

        @Override
        public void onStop() {
            // Stop cache processes
            System.out.println(node.nodeId() + " stopping cache");
        }
    });
}

// Start cache cluster (only highest-priority node runs)
for (var node : nodes) {
    node.start("distributed-cache");
}
```

### Example 2: Location-Transparent Messaging

```java
// Register process globally from any node
ProcRef<CacheState, CacheMsg> cacheProc = supervisor.supervise(
    "cache-local-001",
    initialState,
    handler
);

GlobalProcRegistry registry = GlobalProcRegistry.getInstance();
registry.registerGlobal("cache-primary", cacheProc, "node1");

// From ANY node in the cluster, send message without knowing location
Optional<GlobalProcRef> ref = registry.findGlobal("cache-primary");
if (ref.isPresent()) {
    // Send message to process on node1 from node3
    ref.get().localRef().tell(new CachePut("key", "value"));
}

// Process migration: node1 fails, process moves to node2
// Registry updates automatically, clients continue using same name
ref = registry.findGlobal("cache-primary");  // Now points to node2
ref.get().localRef().tell(new CacheGet("key"));  // Works transparently
```

## Thread Safety

All public APIs in the distributed package are thread-safe:
- **NodeDiscovery** - Concurrent health checks from multiple threads
- **GlobalProcRegistry** - Concurrent registration and lookup
- **FailoverController** - Parallel migrations via virtual threads

Internal state is protected by:
- `ConcurrentHashMap` for process/registries
- `CopyOnWriteArrayList` for listener lists
- Atomic references for singleton instances

## Comparison to Erlang/OTP

| Erlang/OTP | JOTP |
|------------|------|
| `global:register_name/2` | `GlobalProcRegistry.registerGlobal()` |
| `global:whereis_name/1` | `GlobalProcRegistry.findGlobal()` |
| `application:start/1` | `DistributedNode.start()` |
| `dist_ac` | `DistributedNode` |
| `net_adm:ping/1` | `NodeDiscovery.getHealthyNodes()` |

## References

- [JVM Crash Survival](jvm-crash-survival.md)
- [Persistence Backends](persistence-backends.md)
- [ARCHITECTURE.md](ARCHITECTURE.md)
