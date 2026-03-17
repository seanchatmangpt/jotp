# JVM Crash Survival in JOTP

## Overview

JOTP provides production-ready JVM crash survival through atomic state persistence, idempotent message processing, and automatic recovery. When a JVM crashes, processes can resume from their last consistent state without data loss or corruption.

## The Problem: Dual-Write Corruption

When a JVM crashes mid-write, we face the **dual-write problem**:

```
┌─────────────────────────────────────────────────────────────┐
│ Scenario: Processing a message and updating state           │
├─────────────────────────────────────────────────────────────┤
│ 1. Message arrives                                          │
│ 2. Process message (side effects: payment charged)          │
│ 3. Write new state to disk ← JVM CRASHES HERE               │
│ 4. Write ACK (processed marker) to disk                     │
└─────────────────────────────────────────────────────────────┘

Result on restart:
- Event log contains: message was processed
- ACK file contains: message was NOT processed
- System replays message → DOUBLE CHARGING! 💳💳
```

**This is not a theoretical problem** - it happens in production when:
- JVM runs out of memory and is killed by OOM killer
- Hardware failure or power loss
- Network partition during distributed commit
- Human error (kill -9, server restart)

## The Solution: Atomic Writes + Idempotence

JOTP solves this through a two-layer approach:

### Layer 1: Atomic Batch Writes

State and ACK are written **atomically** in a single batch operation:

```java
// AtomicStateWriter.java
public void writeAtomic(String key, S state, long seqNum) {
    // Create atomic write batch
    WriteBatch batch = new WriteBatch();

    // Add state to batch
    batch.put(stateKey, serialize(state));

    // Add ACK to batch
    batch.put(ackKey, String.valueOf(seqNum));

    // Write atomically - either both succeed or both fail
    db.write(WriteOptions(), batch);
}
```

**RocksDB guarantees:** If the JVM crashes during `write()`, the Write-Ahead Log (WAL) ensures either:
- Both state AND ACK are persisted, OR
- Neither is persisted

Never: one without the other.

### Layer 2: Sequence Number Verification

Each state change includes a monotonically increasing sequence number:

```java
public record GlobalProcRef(
    String name,
    ProcRef<?, ?> localRef,
    String nodeName,
    long sequenceNumber,  // ← Increments on each write
    Instant registeredAt
) implements SequencedState {

    @Override
    public long lastProcessedSeq() {
        return sequenceNumber;
    }
}
```

**On recovery:**

```java
public Optional<S> readWithAck(String key) {
    // Read both state and ACK
    S state = backend.load(key);
    long ackSeq = backend.getAckSequence(key);

    // Verify consistency
    if (state.lastProcessedSeq() != ackSeq) {
        // Mismatch indicates crash during write
        // State is from a partially-completed transaction
        return Optional.empty();  // Caller will rebuild from event log
    }

    return Optional.of(state);
}
```

### Layer 3: Idempotent Message Processing

Skip messages that have already been processed:

```java
public Result<State, Exception> handleMessage(State state, Message msg) {
    long msgSeq = msg.sequenceNumber();

    // Check if already processed
    if (writer.isDuplicate(state.id(), msgSeq)) {
        return Result.ok(state);  // Skip - already processed
    }

    // Process message
    State newState = applyMessage(state, msg);

    // Write atomically with new sequence number
    writer.writeAtomic(state.id(), newState, msgSeq);

    return Result.ok(newState);
}
```

**Idempotence guarantee:** A message with sequence N is processed **exactly once**, even if:
- The JVM crashes after processing but before writing
- The message is delivered multiple times (at-least-once delivery)
- The process is restarted and messages are replayed

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                         JOTP Process                                 │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Mailbox (LinkedTransferQueue)                              │   │
│  │  [msg1(seq=5), msg2(seq=6), msg3(seq=7)]                   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                 │                                    │
│                                 ▼                                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Message Handler                                             │   │
│  │                                                              │   │
│  │  if (isDuplicate(msg.seq)) return state;  // Idempotence    │   │
│  │  newState = process(state, msg);                             │   │
│  │  writeAtomic(id, newState, msg.seq);  // Atomic write       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                 │                                    │
│                                 ▼                                    │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  AtomicStateWriter                                           │   │
│  │                                                              │   │
│  │  ┌─────────────────────────────────────────────────────┐    │   │
│  │  │ WriteBatch (atomic operation)                       │    │   │
│  │  │                                                     │    │   │
│  │  │  stateColumnFamily.put(key, serialize(state))       │    │   │
│  │  │  ackColumnFamily.put(key, "7")  // sequence number  │    │   │
│  │  │                                                     │    │   │
│  │  │  db.write(batch)  ← Both succeed or both fail       │    │   │
│  │  └─────────────────────────────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         RocksDB Storage                              │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Column Family: state                                        │   │
│  │  "counter-001" → {id:"counter-001", value:42, lastSeq:7}   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Column Family: ack                                          │   │
│  │  "counter-001" → "7"                                         │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

## Recovery Procedures

### Normal Recovery Flow

```java
public void recover() {
    // 1. Load persisted state for all processes
    for (String processId : backend.listKeys("process:")) {
        // 2. Load state with ACK verification
        Optional<State> state = atomicWriter.readWithAck(processId);

        if (state.isPresent()) {
            // 3. State is consistent, resume processing
            resumeProcess(processId, state.get());
        } else {
            // 4. State is corrupt/incomplete, rebuild from event log
            State recovered = rebuildFromEventLog(processId);
            atomicWriter.writeAtomic(processId, recovered, recovered.lastProcessedSeq());
            resumeProcess(processId, recovered);
        }
    }
}
```

### Event Log Rebuild

When state is corrupt, rebuild from the event log:

```java
private State rebuildFromEventLog(String processId) {
    // 1. Load latest snapshot (if exists)
    Optional<State> snapshot = backend.loadLatestSnapshot(processId);
    State state = snapshot.orElse(initialState());

    // 2. Get last processed sequence from ACK
    long lastSeq = atomicWriter.getLastProcessed(processId);

    // 3. Replay events after lastSeq
    for (Event event : backend.loadEvents(processId, lastSeq + 1)) {
        if (event.sequenceNumber() > lastSeq) {
            state = applyEvent(state, event);
        }
    }

    return state;
}
```

## Usage Examples

### Example 1: Crash-Safe Counter

```java
record CounterState(String id, int value, long lastProcessedSeq)
        implements SequencedState {

    CounterState withValue(int newValue) {
        return new CounterState(id, newValue, lastProcessedSeq);
    }

    CounterState withSeq(long seq) {
        return new CounterState(id, value, seq);
    }

    @Override
    public long lastProcessedSeq() {
        return lastProcessedSeq;
    }
}

// Create process with persistence
PersistenceBackend backend = new RocksDBBackend(Path.of("/var/lib/jotp"));
AtomicStateWriter<CounterState> writer = new AtomicStateWriter<>(
    backend,
    new JsonSnapshotCodec<>(CounterState.class)
);

ProcRef<CounterState, Increment> counter = supervisor.supervise(
    "counter",
    new CounterState("counter-001", 0, 0),
    (state, msg) -> {
        // Idempotent check
        if (writer.isDuplicate(state.id(), msg.sequenceNumber())) {
            return state;
        }

        // Process increment
        CounterState newState = state.withValue(state.value() + msg.amount());

        // Atomic write
        writer.writeAtomic(state.id(), newState.withSeq(msg.sequenceNumber()), msg.sequenceNumber());

        return newState;
    }
);
```

### Example 2: Distributed Counter with CRDTs

```java
record GCounterState(String id, Map<String, Integer> counts, long lastProcessedSeq)
        implements SequencedState {

    GCounterState withCount(String nodeId, int delta) {
        Map<String, Integer> newCounts = new HashMap<>(counts);
        newCounts.merge(nodeId, delta, Integer::sum);
        return new GCounterState(id, newCounts, lastProcessedSeq);
    }

    @Override
    public long lastProcessedSeq() {
        return lastProcessedSeq;
    }

    public int value() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}

// Each node increments its own counter
ProcRef<GCounterState, Increment> counter = supervisor.supervise(
    "distributed-counter",
    new GCounterState("global-counter", new HashMap<>(), 0),
    (state, msg) -> {
        if (writer.isDuplicate(state.id(), msg.sequenceNumber())) {
            return state;
        }

        GCounterState newState = state.withCount(msg.nodeId(), msg.amount());
        writer.writeAtomic(state.id(), newState.withSeq(msg.sequenceNumber()), msg.sequenceNumber());

        return newState;
    }
);
```

## Performance Considerations

### Write Amplification

Each message processing results in **2 writes**:
1. State column family (serialized state)
2. ACK column family (sequence number as string)

**Optimization:** Use snapshots to reduce write frequency:
- Write ACK for every message (small, fast)
- Write snapshot every N messages (larger, amortized)

### Read Performance

Recovery requires **1 read** for state + **1 read** for ACK:
- Both are single-key lookups (O(log N) in RocksDB)
- Typically sub-millisecond for moderate datasets

### Compression

RocksDBBackend uses LZ4 compression by default:
- Reduces storage footprint by 2-3x
- Minimal CPU overhead
- Transparent to application code

## Comparison to Alternatives

| Approach | Crash Safety | Complexity | Performance |
|----------|--------------|------------|-------------|
| **In-memory only** | ❌ Loses all state on crash | Low | Fastest |
| **Write-ahead logging** | ✅ Safe but complex | High (replay log) | Slower (replay) |
| **JOTP Atomic + Idempotence** | ✅ Safe | Medium | Fast (no replay) |

## Best Practices

1. **Always use AtomicStateWriter** for state that changes
2. **Implement SequencedState** for all persisted state types
3. **Use snapshots** for expensive state reconstruction
4. **Monitor sequence numbers** - gaps indicate skipped/reordered messages
5. **Test crash recovery** - kill -9 during writes, verify recovery

## References

- [RocksDB Write-Ahead Log](https://github.com/facebook/rocksdb/wiki/Write-Ahead-Log)
- [Idempotence in Distributed Systems](https://martin.kleppmann.com/papers/idempotence.pdf)
- [Distributed Patterns Documentation](distributed-patterns.md)
- [Persistence Backends Reference](persistence-backends.md)
