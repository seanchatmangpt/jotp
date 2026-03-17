# io.github.seanchatmangpt.jotp.persistence.DistributedFailoverIT


Distributed systems must handle node failures gracefully by detecting
failed nodes and migrating processes to healthy nodes. This test demonstrates
the complete failover flow:

1. Register process on node-1
2. Simulate node-1 failure
3. Detect failure from node-2
4. Migrate process to node-2
5. Verify successful migration

The distributed registry uses a shared RocksDB backend, allowing all nodes
to discover registered processes and detect failures.


Phase 1: Create two-node cluster - node-1 and node-2 with shared registry.

Cluster setup:
- Node 1: node-1 ✓
- Node 2: node-2 ✓
- Shared backend: RocksDB registry ✓


Phase 2: Register process on node-1 - Process registered locally.

```// Register process
var processInfo = distRegistry1.register(
        "process-1",
        NodeId.of("node-1"),
        Map.of("type", "counter", "initial", "0"));

java
```

Cascading failures occur when multiple nodes fail in quick succession.
The system must handle multiple failures and redistribute processes
across remaining healthy nodes.

This test simulates failure of 2 out of 3 nodes:
1. Create 3-node cluster
2. Distribute processes across all nodes
3. Fail node-1 and node-2
4. Migrate all processes to node-3
5. Verify all processes running on node-3


Phase 1: Create 3-node cluster - Distribute processes across nodes.

```// Create 3-node cluster
var node1 = createRegistry("node-1");
var node2 = createRegistry("node-2");
var node3 = createRegistry("node-3");

var distRegistry1 = createDistributedRegistry(node1);
var distRegistry2 = createDistributedRegistry(node2);
var distRegistry3 = createDistributedRegistry(node3);

java
```

Maintaining consistency during cascading failures is critical for
distributed systems. This test simulates a cascading failure in a
5-node cluster where 3 nodes fail simultaneously.

Test scenario:
1. Create 5-node cluster
2. Distribute 20 processes across all nodes
3. Fail nodes 1, 2, 3 (cascading)
4. Redistribute processes to nodes 4, 5
5. Verify load distribution and consistency


Phase 1: Create 5-node cluster - Distribute 20 processes.

State transfer during failover ensures that process state is preserved
when migrating between nodes. This test demonstrates:

1. Create process with persistent state on node-1
2. Persist state to durable storage
3. Simulate node-1 failure
4. Recover state on node-2
5. Re-register process on node-2
6. Verify state transferred correctly

DurableState provides the persistence layer that enables state transfer
between nodes using RocksDB backend.


Phase 1: Node-1 creates process with state - State persisted to disk.

```// Create 5-node cluster
List<GlobalRegistry> nodes = new ArrayList<>();
List<DistributedProcRegistry> distRegistries = new ArrayList<>();

for (int i = 1; i <= 5; i++) {
    var node = createRegistry("node-" + i);
    nodes.add(node);
    distRegistries.add(createDistributedRegistry(node));
}

java
```

```// Create durable state on node-1
var durableState = DurableState.<Integer>builder()
        .entityId("process-state-1")
        .config(config)
        .initialState(100)
        .build();

// Record events and persist
durableState.recordEvent(new StateTransferTestEvent.Increment(50));
durableState.recordEvent(new StateTransferTestEvent.Increment(25));
durableState.saveCurrentState();
// Final state: 100 + 50 + 25 = 175

java
```

State persistence:
- Initial state: 100 ✓
- Event 1: +50 = 150 ✓
- Event 2: +25 = 175 ✓
- Final state persisted: 175 ✓


Phase 2: Node-2 recovers after node-1 failure - State transfer in progress.

```// Recover state on node-2
var recoveredState = DurableState.<Integer>builder()
        .entityId("process-state-1")
        .config(config)
        .initialState(0)
        .build();

int recoveredValue = recoveredState.recover(() -> 0);
// Recovers 175 from durable storage

java
```

Registry recovery after node crash ensures that process registration
information is not lost when a node fails. The shared RocksDB backend
provides durable storage for registry metadata.

Recovery flow:
1. Node-1 registers 10 processes
2. Node-1 crashes
3. Node-2 starts and recovers registry from shared backend
4. Node-2 re-registers all processes
5. Verify all processes accessible


Phase 1: Node-1 registers 10 processes - Bulk registration.

```// Register 10 processes
for (int i = 0; i < 10; i++) {
    distRegistry1.register(
            "proc-" + i,
            NodeId.of("node-1"),
            Map.of("index", String.valueOf(i)));
}

java
```

State synchronization across distributed nodes ensures that all nodes
see the same process state when accessing shared durable state. This test
demonstrates:

1. Node-1 writes state to durable storage
2. Node-2 reads state (should see updates from node-1)
3. Node-3 reads state (should see updates from node-1)
4. Node-3 writes state
5. Verify all nodes see final state

The shared RocksDB backend provides consistent state across all nodes.


Phase 1: Create 3-node cluster - All nodes share state backend.

```// Create shared state
PersistenceConfig config = PersistenceConfig.builder()
        .durabilityLevel(DurabilityLevel.DURABLE)
        .persistenceDirectory(tempDir.resolve("sync-test"))
        .syncWrites(true)
        .build();

var state1 = DurableState.<Integer>builder()
        .entityId("shared-counter")
        .config(config)
        .initialState(0)
        .build();

java
```

Phase 2: Node-1 updates state - Initial state: 0 → 100

Node-1 state update:
- Initial state: 0 ✓
- Event: Increment(100) ✓
- New state: 100 ✓
- Persisted to shared backend: ✓


Phase 3: Node-2 reads state - Should see 100

---
*Generated by [DTR](http://www.dtr.org)*
