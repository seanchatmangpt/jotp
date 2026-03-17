# Distributed Message Log Replication

## Overview

The distributed message log system provides fault-tolerant, replicated logging for JOTP applications running on multiple nodes. It enables message replay for recovery and crash resilience, following Erlang/OTP's disk_log and distributed error logger patterns.

## Architecture

### Core Components

#### 1. DistributedLog Interface
Sealed interface defining the append-only log contract:
- `append(msg)` — append message and get sequence number
- `get(seq)` — retrieve message by sequence
- `getRange(fromSeq, toSeq)` — range queries for replay
- `watch(onMessage)` — subscribe to new messages
- `lastSequence()` — get highest assigned sequence

#### 2. RocksDBLog Implementation
Local persistent storage using file-based key-value store:
- Key format: `log:SEQUENCE_NUMBER` (lexicographically ordered)
- Value: Java serialized LogMessage objects
- Metadata: `log:lastSeq` tracks highest sequence number
- Thread-safe via AtomicLong sequence counter

#### 3. ReplicatedLog Wrapper
Async replication layer on top of RocksDBLog:
- Wraps local log and async replicates to remote nodes
- Quorum-based replication: majority acknowledgments required
- Returns sequence immediately (fire-and-forget)
- Tracks replication via CompletableFuture
- Exponential backoff for failed replications (100ms → 10s)

#### 4. LogReplicationController
Metrics and coordination:
- Tracks total replicated messages
- Measures replication latency
- Calculates per-node lag
- Records last replication timestamp
- Provides observability

### Replication Model

**Quorum-Based:** For N nodes, quorum = N/2 + 1
- Example: 3 nodes → need 2 ACKs for commit

**Fire-and-Forget:** append() returns immediately
- Replication happens asynchronously
- Caller can wait via replicationStatus(seq)

**Exponential Backoff:**
```
Attempt 1: 100ms
Attempt 2: 200ms
Attempt 3: 400ms
Attempt 4: 800ms
Attempt 5: 1600ms (capped at 10s)
```

## Failover & Replay

### Crash Recovery
1. Node crashes → state lost, messages persisted
2. Failover triggered → next node takes over
3. Log replay → replayed from lastReplayedSeq
4. State reconstruction → idempotent handlers
5. Resume → application continues

### Idempotent Handler Requirement
Message handlers **must be idempotent**:

```java
// CORRECT: idempotent
handler = (msg) -> counter.increment();

// WRONG: non-idempotent
handler = (msg) -> list.add(msg);

// CORRECT: with deduplication
handler = (msg) -> {
  if (!seen.contains(msg.id())) {
    seen.add(msg.id());
    process(msg);
  }
};
```

### DistributedAppSpecWithReplay
Tracks replay progress:

```java
var spec = new DistributedAppSpecWithReplay(
    "myapp",
    nodes,
    Duration.ZERO,
    "/data/logs/myapp",
    lastReplayedSeq
);
```

## Performance

### Throughput
- Local append: O(1)
- Replication: Async (limited by network)
- Range queries: O(n)

### Latency
- Append return: <1ms
- Quorum replication: 100-200ms typical
- Replay: Handler-dependent

### Storage
- Per message: Serialized size + 8 bytes
- No log compaction (append-only)

## Thread Safety

- RocksDBLog: AtomicLong sequence counter
- ReplicatedLog: Concurrent replication
- Controller: ConcurrentHashMap metrics
- Watchers: Synchronous on appender thread

## Integration with JOTP

### With ApplicationController
```java
ApplicationController.load(spec);
ApplicationController.start("myapp", RunType.PERMANENT);

var log = new RocksDBLog("myapp", Paths.get("/data"));
log.watch(msg -> replicator.replicate(msg));
```

### With Supervisor Hierarchies
```java
var supervisor = new Supervisor(
    Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

supervisor.spawnChild((state, msg) -> {
  long seq = log.append(new LogMessage(msg.id(), msg.payload(), now()));
  return handler(state, msg);
});
```

## Testing

### Unit Tests
- RocksDBLogTest: append, get, range, watchers
- ReplicatedLogTest: async replication, quorum
- FailoverReplayTest: crash recovery, replay
- ReplicationLagTest: metrics, per-node lag

### Integration Tests
- DistributedLogIntegrationTest: full workflow

## Design Decisions

### NOT using Raft/Paxos
Simple quorum-based replication is sufficient for most use cases:
- Simpler implementation
- Lower latency (no consensus rounds)
- Eventual consistency acceptable

### Deterministic Replay
- Idempotent handlers ensure safe re-execution
- No need for transaction logs or checksums
- Works with virtual threads (stateless)

### Append-Only Design
- Simple: no deletes, updates
- Natural fit for event sourcing
- Supports full audit trails

## Limitations & Future Work

### Current
- File-based storage simulation (not real RocksDB)
- No log compaction
- Single-thread watchers
- No cross-DC replication

### Planned
- Real RocksDB JNI integration
- Snapshot & compaction
- Async watchers
- Micrometer/Prometheus metrics
- Optional Raft mode for strong consistency

## References

- Erlang/OTP disk_log: https://www.erlang.org/doc/man/disk_log.html
- RocksDB: https://rocksdb.org/
- Event Sourcing: https://martinfowler.com/bliki/EventSourcing.html
