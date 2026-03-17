# Failure Handling in Distributed JOTP

**Version:** 1.0.0
**Last Updated:** 2026-03-16

## Table of Contents

1. [Failure Detection](#failure-detection)
2. [Leader Failover](#leader-failover)
3. [Network Partitions](#network-partitions)
4. [Graceful Degradation](#graceful-degradation)
5. [State Synchronization](#state-synchronization)
6. [Disaster Recovery](#disaster-recovery)
7. [Testing Failures](#testing-failures)

---

## Failure Detection

### Health Check Mechanism

JOTP uses a TCP-based PING/PONG protocol for health monitoring:

```java
// Monitoring loop (runs on standby nodes)
private void monitorLoop(AppEntry entry, String appName) {
    NodeId leaderToWatch = entry.currentLeader;

    while (running && entry.status.get() == AppStatus.STANDBY) {
        try {
            Thread.sleep(MONITOR_INTERVAL.toMillis());  // 200ms
        } catch (InterruptedException e) {
            return;  // Shutdown requested
        }

        if (!ping(leaderToWatch)) {
            // Leader unreachable - wait failover timeout
            try {
                Thread.sleep(entry.spec.failoverTimeout().toMillis());
            } catch (InterruptedException e) {
                return;
            }

            // Confirm failure (not transient)
            if (ping(leaderToWatch)) continue;

            // Leader confirmed dead - trigger failover
            tryBecomeLeader(entry, appName, leaderToWatch);
            return;
        }
    }
}
```

### Detection Timeline

```
T+0ms:        Leader running normally
              [Standby] PING → [Leader]
              [Standby] ← PONG [Leader]

T+200ms:      Next health check
              [Standby] PING → [Leader]
              [Leader crashes]

T+500ms:      PING timeout (300ms connect + 200ms read)
              [Standby] detects timeout

T+5200ms:     After 5-second failover timeout
              [Standby] PING → [Leader X]
              [Standby] ← (no response)

T+5400ms:     Failure confirmed
              [Standby] triggers failover
              [New Leader] starts application
```

### Configuration

```java
// Adjust monitoring sensitivity
var spec = new DistributedAppSpec(
    "myapp",
    List.of(
        List.of(node1.nodeId()),
        List.of(node2.nodeId())
    ),
    Duration.ofSeconds(5)  // Failover timeout
);

// Recommendations:
// - LAN (low latency): 1-3 seconds
// - WAN (high latency): 10-30 seconds
// - Unstable networks: 30-60 seconds
```

### False Positive Prevention

To avoid failover during transient network issues:

```java
// Strategy 1: Multiple consecutive failures
private int failureCount = 0;
private final int FAILURE_THRESHOLD = 3;

if (!ping(leader)) {
    failureCount++;
    if (failureCount >= FAILURE_THRESHOLD) {
        triggerFailover();
    }
} else {
    failureCount = 0;  // Reset on success
}

// Strategy 2: Exponential backoff
private long backoffMs = 100;

if (!ping(leader)) {
    Thread.sleep(backoffMs);
    backoffMs = Math.min(backoffMs * 2, 5000);  // Max 5s

    if (!ping(leader)) {
        triggerFailover();
    }
}

// Strategy 3: Quorum-based detection
// Require majority of nodes to confirm failure
```

---

## Leader Failover

### Deterministic Election Algorithm

JOTP uses priority-based election without distributed consensus:

```java
private void electLeader(AppEntry entry, String appName) {
    List<NodeId> priority = entry.spec.priorityList();
    int selfIdx = priority.indexOf(self);

    // Pass 1: Find higher-priority live node
    for (NodeId candidate : priority) {
        if (candidate.equals(self)) break;  // No higher-priority node found

        if (ping(candidate)) {
            // Higher-priority node alive - become STANDBY
            entry.currentLeader = candidate;
            entry.status.set(AppStatus.STANDBY);
            startMonitor(entry, appName);
            return;
        }
    }

    // Pass 2: I am highest-priority live node
    if (entry.status.get() == AppStatus.RUNNING) {
        return;  // Already running
    }

    // Check for lower-priority RUNNING nodes (takeover)
    for (int i = selfIdx + 1; i < priority.size(); i++) {
        NodeId lower = priority.get(i);
        if (ping(lower) && "RUNNING".equals(queryStatus(lower, appName))) {
            // Take over from lower-priority node
            send(lower, "START " + appName + " takeover " + self.wire());
            entry.currentLeader = self;
            entry.status.set(AppStatus.RUNNING);
            entry.callbacks.onStart(new StartMode.Takeover(lower));
            return;
        }
    }

    // No lower-priority node running - start normally
    entry.currentLeader = self;
    entry.status.set(AppStatus.RUNNING);
    entry.callbacks.onStart(new StartMode.Normal());
}
```

### Failover Sequence

```java
private void tryBecomeLeader(AppEntry entry, String appName, NodeId deadLeader) {
    List<NodeId> priority = entry.spec.priorityList();

    for (NodeId candidate : priority) {
        if (candidate.equals(deadLeader)) continue;  // Skip dead node

        if (candidate.equals(self)) {
            // I am first live node - become leader
            if (entry.status.compareAndSet(AppStatus.STANDBY, AppStatus.RUNNING)) {
                entry.currentLeader = self;
                entry.callbacks.onStart(new StartMode.Failover(deadLeader));
            }
            return;
        }

        if (ping(candidate)) {
            // Higher-priority node alive - defer to it
            entry.currentLeader = candidate;
            entry.status.set(AppStatus.STANDBY);
            startMonitor(entry, appName);
            return;
        }
    }
}
```

### Startup Modes

```java
public sealed interface StartMode permits StartMode.Normal, StartMode.Failover, StartMode.Takeover {
    /** Initial start on highest-priority node */
    record Normal() implements StartMode {}

    /** Start after detecting leader crash */
    record Failover(NodeId from) implements StartMode {}

    /** Reclaim from lower-priority node */
    record Takeover(NodeId from) implements StartMode {}
}

// Usage in callbacks
public class MyAppCallbacks implements ApplicationCallbacks {
    @Override
    public void onStart(StartMode mode) {
        switch (mode) {
            case StartMode.Normal normal -> {
                // Fresh start - initialize from scratch
                state = loadInitialState();
            }
            case StartMode.Failover(var fromNode) -> {
                // Recover from crashed node
                state = recoverState(fromNode);
            }
            case StartMode.Takeover(var fromNode) -> {
                // Takeover from lower-priority node
                state = synchronizeState(fromNode);
            }
        }
    }
}
```

### State Recovery

```java
public class StateRecovery {
    private final DistributedStateActor<AppState, AppMsg> replicatedState;

    public AppState recoverState(NodeId failedNode) {
        // Option 1: Read from replicated state store
        return replicatedState.read();

        // Option 2: Rebuild from event log
        // return rebuildFromEventLog();

        // Option 3: Load from snapshot + replay events
        // return loadSnapshotAndReplay();
    }

    private AppState rebuildFromEventLog() {
        List<Event> events = eventLog.readAll();
        AppState state = new AppState();

        for (Event event : events) {
            state = applyEvent(state, event);
        }

        return state;
    }
}
```

---

## Network Partitions

### Split-Brain Prevention

JOTP prevents split-brain through deterministic election:

```java
// Scenario: Network partitions cluster into two groups
//
// Before partition:
//   [node1 (LEADER)] <--> [node2 (STANDBY)] <--> [node3 (STANDBY)]
//
// After partition:
//   Group A: [node1 (LEADER)] [node2 (STANDBY)]
//   Group B: [node3 (STANDBY)]
//
// Election in Group A:
//   - node1 is highest priority and alive
//   - node1 remains LEADER
//   - node2 remains STANDBY
//
// Election in Group B:
//   - node3 checks for higher-priority nodes
//   - node1 and node2 are unreachable
//   - node3 becomes LEADER in its partition
//
// When network heals:
//   - node1 (priority 1) and node3 (priority 3) reconnect
//   - node3 sees node1 is alive and higher priority
//   - node3 yields leadership back to node1
```

### Majority Quorum (Optional Enhancement)

For stricter split-brain prevention:

```java
public class QuorumBasedElection {
    private final int quorumSize;

    public boolean shouldSurvivePartition(Set<NodeId> liveNodes) {
        // Survive only if partition contains majority
        return liveNodes.size() >= quorumSize;
    }

    public void handlePartition(Set<NodeId> liveNodes) {
        if (!shouldSurvivePartition(liveNodes)) {
            // Self-terminate to prevent split-brain
            logger.warn("Partition below quorum, shutting down");
            Runtime.getRuntime().halt(1);
        }
    }
}

// Configuration for 5-node cluster (quorum = 3)
var quorumStrategy = new QuorumBasedElection(3);
```

### Network Partition Detection

```java
public class PartitionDetector {
    private final Map<NodeId, Long> lastSeenTimestamp = new ConcurrentHashMap<>();
    private final long partitionThresholdMs = 5000;

    public void recordHeartbeat(NodeId from) {
        lastSeenTimestamp.put(from, System.currentTimeMillis());
    }

    public Set<NodeId> detectPartition() {
        long now = System.currentTimeMillis();
        return lastSeenTimestamp.entrySet().stream()
            .filter(e -> now - e.getValue() > partitionThresholdMs)
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    public boolean isPartitioned() {
        return !detectPartition().isEmpty();
    }
}
```

### Healing After Partition

```java
public class PartitionHealing {
    public void onNetworkHeal(Set<NodeId> rejoinedNodes) {
        // Re-run leader election with all nodes
        for (String appName : apps.keySet()) {
            AppEntry entry = apps.get(appName);
            electLeader(entry, appName);
        }
    }

    private void synchronizeState(Set<NodeId> rejoinedNodes) {
        for (NodeId node : rejoinedNodes) {
            if (node.priority() < self.priority()) {
                // Higher-priority node rejoined - sync state to it
                AppState localState = getState();
                sendStateSync(node, localState);
            }
        }
    }
}
```

---

## Graceful Degradation

### Circuit Breaker Pattern

Protect against cascading failures:

```java
public class RemoteServiceCircuitBreaker {
    private final DistributedActorBridge bridge;
    private final int failureThreshold;
    private final Duration timeout;
    private int failureCount = 0;
    private CircuitState state = CircuitState.CLOSED;
    private long lastFailureTime = 0;

    public enum CircuitState {
        CLOSED,    // Normal operation
        OPEN,      // Failing, reject requests
        HALF_OPEN  // Testing if recovery succeeded
    }

    public <S, M> CompletableFuture<S> callWithCircuitBreaker(
        String actorName,
        M message,
        Duration timeout
    ) {
        if (state == CircuitState.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime > timeout.toMillis()) {
                state = CircuitState.HALF_OPEN;
            } else {
                return CompletableFuture.failedFuture(
                    new CircuitBreakerOpenException("Circuit breaker is OPEN")
                );
            }
        }

        return bridge.remoteRef(
            bridge.getHost(), bridge.getPort(), actorName
        ).ask(message).whenComplete((result, error) -> {
            if (error != null) {
                handleFailure();
            } else {
                handleSuccess();
            }
        });
    }

    private void handleFailure() {
        failureCount++;
        lastFailureTime = System.currentTimeMillis();

        if (failureCount >= failureThreshold) {
            state = CircuitState.OPEN;
            logger.warn("Circuit breaker opened after {} failures", failureCount);
        }
    }

    private void handleSuccess() {
        failureCount = 0;
        if (state == CircuitState.HALF_OPEN) {
            state = CircuitState.CLOSED;
            logger.info("Circuit breaker closed after successful test");
        }
    }
}
```

### Fallback Strategies

```java
public class FallbackStrategy {
    private final RemoteServiceCircuitBreaker circuitBreaker;
    private final Cache<String, Object> cache;

    public <S> CompletableFuture<S> withFallback(
        String actorName,
        Object message,
        Supplier<CompletableFuture<S>> remoteCall,
        Supplier<CompletableFuture<S>> fallback
    ) {
        return circuitBreaker.callWithCircuitBreaker(actorName, message, Duration.ofSeconds(5))
            .exceptionallyCompose(error -> {
                logger.warn("Remote call failed, using fallback: {}", error.getMessage());

                // Fallback 1: Return cached value
                S cached = (S) cache.getIfPresent(actorName);
                if (cached != null) {
                    return CompletableFuture.completedFuture(cached);
                }

                // Fallback 2: Call local service
                return fallback.get();
            });
    }
}
```

### Rate Limiting

```java
public class RateLimitedRemoteCalls {
    private final Semaphore rateLimiter;
    private final int maxConcurrentCalls;

    public RateLimitedRemoteCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.rateLimiter = new Semaphore(maxConcurrentCalls);
    }

    public <S> CompletableFuture<S> call(Supplier<CompletableFuture<S>> remoteCall) {
        if (!rateLimiter.tryAcquire()) {
            return CompletableFuture.failedFuture(
                new RateLimitExceededException("Too many concurrent calls")
            );
        }

        return remoteCall.get().whenComplete((result, error) -> {
            rateLimiter.release();
        });
    }
}
```

---

## State Synchronization

### Event Sourcing

```java
public class EventSourcedState {
    private final EventStore eventStore;
    private List<Event> eventLog = new ArrayList<>();

    public void persistEvent(Event event) {
        eventStore.append(event);
        eventLog.add(event);
    }

    public AppState rebuildState() {
        AppState state = new AppState();

        for (Event event : eventLog) {
            state = applyEvent(state, event);
        }

        return state;
    }

    public void replayEvents(AppState targetState, long fromVersion) {
        List<Event> newEvents = eventStore.getEvents(fromVersion);

        for (Event event : newEvents) {
            targetState = applyEvent(targetState, event);
        }
    }
}
```

### Snapshotting

```java
public class StateSnapshot {
    private final SnapshotStore snapshotStore;
    private final long snapshotIntervalMs;

    public void maybeSnapshot(AppState state, long currentVersion) {
        long lastSnapshotVersion = snapshotStore.getLastSnapshotVersion();

        if (currentVersion - lastSnapshotVersion >= 1000) {  // Every 1000 events
            takeSnapshot(state, currentVersion);
        }
    }

    private void takeSnapshot(AppState state, long version) {
        Snapshot snapshot = new Snapshot(version, state);
        snapshotStore.save(snapshot);
        logger.info("Snapshot taken at version {}", version);
    }

    public AppState loadSnapshot() {
        Snapshot snapshot = snapshotStore.getLatestSnapshot();
        if (snapshot != null) {
            return snapshot.state();
        }
        return new AppState();  // Initial state
    }
}
```

### Replicated State (Raft)

```java
public class ReplicatedActorState {
    private final DistributedStateActor<AppState, AppMsg> replicatedState;

    public void write(AppMsg message) {
        // Write to Raft leader, replicated to quorum
        Result<AppState, Exception> result = replicatedState.write(message);

        if (result.isFailure()) {
            Exception error = result.fold(v -> null, e -> e);
            logger.error("Failed to replicate state: {}", error.getMessage());
            throw new RuntimeException("State replication failed", error);
        }
    }

    public AppState read() {
        // Read from local replica (eventually consistent)
        return replicatedState.read();
    }

    public AppState readLinearizable() {
        // Read from leader via ReadIndex protocol
        return replicatedState.readLinearizable();
    }
}
```

---

## Disaster Recovery

### Backup Strategies

```java
public class DisasterRecovery {
    private final BackupStore backupStore;
    private final S3BackupService s3Backup;

    public void createBackup(AppState state) {
        // Local backup
        backupStore.save(state);

        // Offsite backup (S3)
        s3Backup.upload(state);
    }

    public AppState restoreFromBackup() {
        // Try local backup first
        AppState state = backupStore.load();
        if (state != null) {
            return state;
        }

        // Fallback to S3 backup
        return s3Backup.download();
    }
}
```

### Multi-Region Replication

```java
public class GeoReplicatedState {
    private final List<DistributedStateActor<AppState, AppMsg>> replicas;

    public void replicateAcrossRegions(AppMsg message) {
        // Write to all regional replicas
        List<CompletableFuture<Result<AppState, Exception>>> futures = replicas.stream()
            .map(replica -> CompletableFuture.supplyAsync(
                () -> replica.write(message)
            ))
            .toList();

        // Wait for majority acknowledgement
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .orTimeout(5, TimeUnit.SECONDS)
            .join();
    }
}
```

### Failover Testing

```java
@Test
public void testDisasterRecovery() {
    // Create cluster
    DistributedNode node1 = new DistributedNode("node1", "localhost", 0, config);
    DistributedNode node2 = new DistributedNode("node2", "localhost", 0, config);

    // Start application on node1
    node1.register(spec, callbacks);
    node1.start("myapp");

    // Create backup
    disasterRecovery.createBackup(currentState);

    // Kill node1
    node1.shutdown();

    // Verify node2 takes over
    await().atMost(10, SECONDS).until(() -> node2.isRunning());

    // Verify state restored from backup
    AppState restoredState = disasterRecovery.restoreFromBackup();
    assertThat(restoredState).isEqualTo(currentState);
}
```

---

## Testing Failures

### Chaos Engineering

```java
public class ChaosTest {
    @Test
    public void testRandomNodeFailure() {
        List<DistributedNode> nodes = createCluster(5);

        // Run for 10 minutes with random failures
        ExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> {
            // Kill random node
            DistributedNode victim = nodes.get(
                ThreadLocalRandom.current().nextInt(nodes.size())
            );
            victim.shutdown();

            // Restart it after 30 seconds
            executor.schedule(() -> {
                try {
                    victim.restart();
                } catch (IOException e) {
                    logger.error("Failed to restart node", e);
                }
            }, 30, TimeUnit.SECONDS);
        }, 0, 2, TimeUnit.MINUTES);
    }

    @Test
    public void testNetworkPartition() {
        // Use Toxiproxy to simulate network partition
        toxiproxy.createPartition("node1", "node2");

        // Verify cluster behavior
        await().atMost(5, SECONDS).until(() -> {
            return node1.detectPartition().contains(node2.nodeId());
        });

        // Heal partition
        toxiproxy.healPartition("node1", "node2");

        // Verify recovery
        await().atMost(10, SECONDS).until(() -> {
            return node1.isConnectedTo(node2.nodeId());
        });
    }
}
```

### Failure Injection

```java
public class FailureInjector {
    public void injectLatency(DistributedNode node, Duration latency) {
        // Add delay to all PING responses
        node.setLatencyInjector((req) -> {
            Thread.sleep(latency.toMillis());
            return req;
        });
    }

    public void injectPacketLoss(DistributedNode node, double lossRate) {
        // Drop percentage of messages
        node.setPacketFilter((req) -> {
            return Math.random() > lossRate;
        });
    }

    public void injectCorruption(DistributedNode node) {
        // Corrupt message payloads
        node.setMessageMutator((msg) -> {
            return msg + "[CORRUPTED]";
        });
    }
}
```

---

## Best Practices

### Failure Detection

1. **Tune timeouts based on network conditions**
2. **Use multiple consecutive failures** before triggering failover
3. **Monitor detection latency** to catch performance issues
4. **Log all failures** for post-mortem analysis

### Failover Strategy

1. **Test failover regularly** in staging environments
2. **Monitor failover duration** to ensure SLA compliance
3. **Verify state recovery** after each failover
4. **Alert on frequent failovers** (indicates underlying issues)

### State Management

1. **Use event sourcing** for auditability and replay
2. **Snapshot periodically** to reduce recovery time
3. **Replicate across regions** for disaster recovery
4. **Test recovery procedures** monthly

### Testing

1. **Practice chaos engineering** - break things on purpose
2. **Test network partitions** with tools like Toxiproxy
3. **Simulate region failures** for geo-distributed systems
4. **Measure MTTR** (Mean Time To Recovery)

---

## Further Reading

- [Multi-JVM Architecture Guide](./MULTI-JVM-ARCHITECTURE.md)
- [Deployment Patterns](./DEPLOYMENT-PATTERNS.md)
- [Security Best Practices](./SECURITY.md)
- [Monitoring and Observability](./MONITORING.md)
