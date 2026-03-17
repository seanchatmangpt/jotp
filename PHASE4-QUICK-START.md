# Phase 4 Quick Start Guide

## Overview

Phase 4 provides NodeDiscovery and Failover capabilities for JOTP clusters. This guide shows you how to set up a distributed cluster with automatic failover.

## 5-Minute Setup

### 1. Create Your Cluster Nodes

```java
import io.github.seanchatmangpt.jotp.distributed.*;
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.*;

// Define your cluster
List<String> nodes = List.of("node1", "node2", "node3");
Map<String, String> addresses = Map.of(
    "node1", "localhost:8080",
    "node2", "localhost:8081",
    "node3", "localhost:8082"
);
```

### 2. Set Up Node Discovery

```java
// For development/testing (in-memory)
NodeDiscoveryBackend backend = new InMemoryNodeDiscoveryBackend();

// For production (persistent)
// NodeDiscoveryBackend backend = new RocksDBNodeDiscoveryBackend(
//     Path.of("/var/lib/jotp/nodes")
// );

// Create discovery instance
NodeDiscovery discovery = new StaticNodeDiscovery(
    "node1",  // This node's name
    nodes,    // All cluster nodes
    addresses,// Node addresses
    backend,  // Storage backend
    Duration.ofSeconds(5),  // Health check interval
    Duration.ofSeconds(10), // Heartbeat timeout
    Duration.ofSeconds(30)  // Degraded timeout
);

// Start health monitoring
discovery.startHealthChecks();
```

### 3. Configure Failover

```java
// Get the global registry
GlobalProcRegistry registry = GlobalProcRegistry.getInstance();

// Create failover controller
FailoverController failover = new FailoverController(registry, discovery);

// Register node failure listener
discovery.addNodeDownListener(failedNode -> {
    System.out.println("Node failed: " + failedNode);
    int migrated = failover.handleNodeDown(failedNode);
    System.out.println("Migrated " + migrated + " processes");
});

// Register node recovery listener
discovery.addNodeUpListener(recoveredNode -> {
    System.out.println("Node recovered: " + recoveredNode);
});
```

### 4. Register Distributed Application

```java
// Create distributed node
DistributedNode node = new DistributedNode(
    "node1",
    "localhost",
    8080,
    NodeConfig.defaults()
);

// Define application spec
DistributedAppSpec spec = new DistributedAppSpec(
    "myapp",
    List.of(
        List.of(
            new NodeId("node1", "localhost", 8080),
            new NodeId("node2", "localhost", 8081),
            new NodeId("node3", "localhost", 8082)
        )
    ),
    Duration.ZERO  // Failover timeout
);

// Register with callbacks
node.register(spec, new ApplicationCallbacks() {
    @Override
    public void onStart(StartMode mode) {
        System.out.println("Starting as: " + mode);
        // Initialize your application
    }

    @Override
    public void onStop() {
        System.out.println("Stopping");
        // Cleanup your application
    }
});
```

### 5. Register Global Processes

```java
// Create a process
Proc<String, String> myProcess = Proc.spawn(
    "my-process",
    "initial-state",
    (state, msg) -> {
        // Process messages
        return state; // Return new state
    }
);

// Get stable reference
ProcRef<String, String> ref = myProcess.ref();

// Register globally
registry.registerGlobal("my-process", ref, "node1");

// Find from any node
Optional<GlobalProcRef> found = registry.findGlobal("my-process");
if (found.isPresent()) {
    ProcRef<?, ?> procRef = found.get().localRef();
    procRef.tell("hello");
}
```

### 6. Start the Application

```java
// Start on all nodes (only highest-priority runs)
node.start("myapp");

// Send periodic heartbeats (optional - system handles this)
ScheduledExecutorService heartbeatScheduler =
    Executors.newSingleThreadScheduledExecutor();
heartbeatScheduler.scheduleAtFixedRate(
    ((StaticNodeDiscovery) discovery)::sendHeartbeat,
    0, 2, TimeUnit.SECONDS
);
```

## Complete Example

```java
public class DistributedApp {
    public static void main(String[] args) throws Exception {
        // Parse node name from args
        String nodeName = args.length > 0 ? args[0] : "node1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        // Setup discovery
        List<String> nodes = List.of("node1", "node2", "node3");
        Map<String, String> addresses = Map.of(
            "node1", "localhost:8080",
            "node2", "localhost:8081",
            "node3", "localhost:8082"
        );

        NodeDiscoveryBackend backend = new InMemoryNodeDiscoveryBackend();
        NodeDiscovery discovery = new StaticNodeDiscovery(
            nodeName, nodes, addresses, backend
        );

        // Setup failover
        GlobalProcRegistry registry = GlobalProcRegistry.getInstance();
        FailoverController failover = new FailoverController(registry, discovery);
        discovery.addNodeDownListener(failover::handleNodeDown);

        // Start health checks
        discovery.startHealthChecks();

        // Create distributed node
        DistributedNode node = new DistributedNode(
            nodeName, "localhost", port, NodeConfig.defaults()
        );

        // Register application
        DistributedAppSpec spec = new DistributedAppSpec(
            "myapp",
            List.of(List.of(
                new NodeId("node1", "localhost", 8080),
                new NodeId("node2", "localhost", 8081),
                new NodeId("node3", "localhost", 8082)
            )),
            Duration.ofSeconds(5)
        );

        node.register(spec, new ApplicationCallbacks() {
            @Override
            public void onStart(StartMode mode) {
                System.out.println(nodeName + " starting as " + mode);
                // Start your application logic
            }

            @Override
            public void onStop() {
                System.out.println(nodeName + " stopping");
            }
        });

        // Start application
        node.start("myapp");

        // Keep running
        Thread.currentThread().join();
    }
}
```

## Running the Cluster

### Terminal 1
```bash
java DistributedApp node1 8080
```

### Terminal 2
```bash
java DistributedApp node2 8081
```

### Terminal 3
```bash
java DistributedApp node3 8082
```

## Testing Failover

### Kill a Node
```bash
# Find the PID
ps aux | grep DistributedApp

# Kill node2
kill -9 <PID>
```

### Expected Output
```
[node1] Node failed: node2
[node1] Migrated 3 processes
[node3] Node failed: node2
[node3] Migrated 2 processes
```

### Restart the Node
```bash
java DistributedApp node2 8081
```

### Expected Output
```
[node1] Node recovered: node2
[node3] Node recovered: node2
```

## Common Patterns

### Pattern 1: Leader Election
```java
// Only the highest-priority node runs the app
node.start("myapp");
// node2 and node3 become standbys
// If node1 fails, node2 takes over
```

### Pattern 2: Process Migration
```java
// Processes automatically migrate on failure
registry.registerGlobal("cache", cacheRef, "node1");
// If node1 fails, cache transfers to node2
```

### Pattern 3: Health Monitoring
```java
// Check cluster health
List<String> healthy = discovery.getHealthyNodes();
System.out.println("Healthy nodes: " + healthy);

// Check specific node
if (discovery.canAcceptMigrations("node1")) {
    // Node1 is healthy and can accept work
}
```

### Pattern 4: Graceful Shutdown
```java
// JVM shutdown is handled automatically
// But you can trigger manually:
node.shutdown("myapp");
discovery.shutdown();
failover.shutdown();
```

## Configuration Options

### Health Check Intervals
```java
new StaticNodeDiscovery(
    nodeName,
    nodes,
    addresses,
    backend,
    Duration.ofSeconds(5),  // Check every 5 seconds
    Duration.ofSeconds(10), // Mark degraded after 10s without heartbeat
    Duration.ofSeconds(30)  // Mark down after 30s
);
```

### Failover Timeout
```java
DistributedAppSpec spec = new DistributedAppSpec(
    "myapp",
    priorityLists,
    Duration.ofSeconds(5) // Wait 5s before failover
);
```

### Backend Selection
```java
// In-memory (testing)
NodeDiscoveryBackend backend = new InMemoryNodeDiscoveryBackend();

// RocksDB (production)
NodeDiscoveryBackend backend = new RocksDBNodeDiscoveryBackend(
    Path.of("/var/lib/jotp/nodes")
);
```

## Monitoring

### Health Status
```java
// Check if node is healthy
List<String> healthyNodes = discovery.getHealthyNodes();
boolean isHealthy = healthyNodes.contains("node1");
```

### Process Location
```java
// Find where a process is hosted
Optional<GlobalProcRef> ref = registry.findGlobal("my-process");
if (ref.isPresent()) {
    String location = ref.get().nodeName();
    System.out.println("Process hosted on: " + location);
}
```

### Cluster Size
```java
// Total processes
Map<String, GlobalProcRef> allProcesses = registry.listGlobal();
System.out.println("Total processes: " + allProcesses.size());

// Processes per node
Map<String, Long> perNode = allProcesses.values().stream()
    .collect(Collectors.groupingBy(
        GlobalProcRef::nodeName,
        Collectors.counting()
    ));
```

## Troubleshooting

### Nodes Not Discovering Each Other
- Check network connectivity
- Verify firewall rules
- Ensure addresses are correct (host:port format)

### Failover Not Triggering
- Check health check intervals
- Verify heartbeats are being sent
- Review logs for exceptions

### Processes Not Migrating
- Ensure FailoverController is registered as listener
- Check GlobalProcRegistry is configured
- Verify sequence numbers are incrementing

### High Memory Usage
- Reduce health check interval
- Use RocksDB backend instead of in-memory
- Limit process registry size

## Best Practices

1. **Use Persistent Backends in Production**
   ```java
   NodeDiscoveryBackend backend = new RocksDBNodeDiscoveryBackend(...);
   ```

2. **Set Appropriate Timeouts**
   - Health check: 5-10 seconds
   - Heartbeat timeout: 2-3x health check
   - Degraded timeout: 3-5x heartbeat timeout

3. **Monitor Health State Transitions**
   ```java
   discovery.addNodeDownListener(node -> {
       logger.warn("Node down: " + node);
       metrics.increment("node.down");
   });
   ```

4. **Test Failover Regularly**
   - Kill nodes during testing
   - Verify process migration
   - Check recovery procedures

5. **Use Stable Process References**
   ```java
   // Always use ProcRef, never Proc directly
   ProcRef<?, ?> ref = process.ref();
   registry.registerGlobal("name", ref, nodeName);
   ```

## Next Steps

- Read the full implementation report: `PHASE4-IMPLEMENTATION-REPORT.md`
- Check out distributed examples: `src/main/java/io/github/seanchatmangpt/jotp/examples/`
- Review package documentation: `src/main/java/io/github/seanchatmangpt/jotp/distributed/package-info.java`

## Support

- Issues: https://github.com/seanchatmangpt/jotp/issues
- Documentation: https://jotp.io
- Examples: https://jotp.io/examples
