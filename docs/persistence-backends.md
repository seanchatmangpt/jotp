# Persistence Backends Reference

## Overview

JOTP provides pluggable persistence backends for state storage. This document explains available backends, their configuration, performance characteristics, and use cases.

## Available Backends

| Backend | Persistence | Performance | Use Case |
|---------|-------------|-------------|----------|
| **InMemoryBackend** | No (lost on restart) | Fastest (~1µs ops) | Testing, development, caching |
| **RocksDBBackend** | Yes (survives restart) | Fast (~100-500µs ops) | Production, crash recovery |

## Backend Comparison

```
┌─────────────────────────────────────────────────────────────────┐
│                    InMemoryBackend                              │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  ConcurrentHashMap<String, byte[]>                       │  │
│  │                                                          │  │
│  │  - In-memory storage                                     │  │
│  │  - No persistence                                        │  │
│  │  - Fastest performance                                   │  │
│  │  - Lost on JVM restart                                   │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    RocksDBBackend                               │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Column Families (separate namespaces)                   │  │
│  │  ┌─────────────────────────────────────────────────┐    │  │
│  │  │ events:   [msg1, msg2, msg3, ...]              │    │  │
│  │  │          ↑ Event sourcing log                   │    │  │
│  │  ├─────────────────────────────────────────────────┤    │  │
│  │  │ snapshots: [snap1, snap2, ...]                  │    │  │
│  │  │            ↑ Fast recovery points               │    │  │
│  │  ├─────────────────────────────────────────────────┤    │  │
│  │  │ processes: [state1, state2, ...]                │    │  │
│  │  │            ↑ Process state                      │    │  │
│  │  └─────────────────────────────────────────────────┘    │  │
│  │                                                          │  │
│  │  - Persistent storage (survives restart)                 │  │
│  │  - Write-Ahead Log for durability                        │  │
│  │  - LZ4 compression (2-3x space savings)                  │  │
│  │  - Atomic batch writes                                  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## InMemoryBackend

### Description

In-memory backend using `ConcurrentHashMap`. Provides fast operations but no persistence. Data is lost on JVM restart.

### Usage

```java
PersistenceBackend backend = new InMemoryBackend();

// Save and load
backend.save("key", data);
Optional<byte[]> loaded = backend.load("key");

// List keys
Stream<String> keys = backend.listKeys("prefix:");

// Delete
backend.delete("key");

// Cleanup
backend.close();
```

### Performance Characteristics

| Operation | Latency | Throughput |
|-----------|---------|------------|
| `save()` | ~1 µs | >1M ops/sec |
| `load()` | ~0.5 µs | >2M ops/sec |
| `delete()` | ~1 µs | >1M ops/sec |

### Use Cases

- **Unit tests** - Fast test execution
- **Development** - Quick iteration without file cleanup
- **Caching** - Temporary data storage
- **Prototyping** - Proof of concept before adding persistence

### Limitations

- ❌ No persistence - lost on JVM restart
- ❌ Limited by heap memory
- ❌ No crash recovery

## RocksDBBackend

### Description

Production-grade persistent backend using RocksDB's Log-Structured Merge-Tree (LSM) storage engine. Provides durability, compression, and atomic writes.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  RocksDB Backend Architecture                               │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Write Path                                          │   │
│  │                                                     │   │
│  │  1. Application calls save(key, value)              │   │
│  │  2. Write to MemTable (in-memory skip list)         │   │
│  │  3. Write to Write-Ahead Log (WAL) for durability   │   │
│  │  4. Flush: MemTable → Immutable MemTable            │   │
│  │  5. Compaction: Immutable MemTable → SST file       │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Read Path                                           │   │
│  │                                                     │   │
│  │  1. Check MemTable (most recent writes)             │   │
│  │  2. Check Immutable MemTables                       │   │
│  │  3. Check SST files (sorted by key)                 │   │
│  │  4. Bloom filters reduce disk I/O                   │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Column Families (separate namespaces)               │   │
│  │                                                     │   │
│  │  events    - Event sourcing log                      │   │
│  │  snapshots - Fast recovery points                    │   │
│  │  processes - Process state                           │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Usage

```java
// Initialize backend
Path dbPath = Path.of("/var/lib/jotp/rocksdb");
PersistenceBackend backend = new RocksDBBackend(dbPath);

// Save and load
backend.save("process-001:state", stateBytes);
Optional<byte[]> loaded = backend.load("process-001:state");

// Save and load snapshots
backend.saveSnapshot("process-001", snapshotBytes);
Optional<byte[]> snapshot = backend.loadLatestSnapshot("process-001");

// List keys by prefix
Stream<String> eventKeys = backend.listKeys("event:");

// Flush to disk (called automatically on close)
backend.flush();

// Cleanup
backend.close();
```

### Configuration

RocksDBBackend is configured with sensible defaults:

| Setting | Value | Description |
|---------|-------|-------------|
| **Compression** | LZ4 | 2-3x space savings, minimal CPU overhead |
| **Write-Ahead Log** | Enabled | Durability guarantee |
| **Block size** | 4 KB | Optimized for SSD |
| **Bloom filters** | Enabled | Faster reads |
| **Auto compaction** | Enabled | Background maintenance |

### Atomic Writes

RocksDBBackend supports atomic batch writes via `WriteBatch`:

```java
// AtomicStateWriter uses this for crash safety
WriteBatch batch = new WriteBatch();
batch.put(stateKey, stateBytes);      // Add state
batch.put(ackKey, ackBytes);          // Add ACK
db.write(WriteOptions(), batch);      // Atomic write
```

**Guarantee:** Either both state and ACK are persisted, or neither. Never one without the other.

### Performance Characteristics

| Operation | Latency | Throughput |
|-----------|---------|------------|
| `save()` | 100-500 µs | ~100K ops/sec |
| `load()` | 50-200 µs | ~200K ops/sec |
| `delete()` | 100-300 µs | ~100K ops/sec |
| `saveSnapshot()` | 1-5 ms | ~1K ops/sec |

**Factors affecting performance:**
- Disk type (SSD vs HDD)
- Data size (larger values = slower)
- Compression ratio (compressible data = faster)
- Background compaction (may slow writes temporarily)

### Space Usage

With LZ4 compression:
- Text/JSON data: ~2-3x compression
- Binary data: ~1.5-2x compression
- Overhead: ~32 bytes per key

**Example:** 1 GB of in-memory state → ~400-500 MB on disk

### Use Cases

- **Production systems** - Crash recovery required
- **Event sourcing** - Persistent event logs
- **State snapshots** - Fast recovery points
- **Distributed systems** - Shared state across nodes

### Best Practices

1. **Use SSDs** - 10x faster than HDD for LSM trees
2. **Monitor disk space** - RocksDB needs space for compaction
3. **Regular snapshots** - Reduce event log replay time
4. **Flush on shutdown** - Ensure all data is durable
5. **Batch writes** - Use `WriteBatch` for atomic multi-key writes

### Troubleshooting

#### High Write Latency

**Symptom:** `save()` operations take >10ms

**Causes:**
- MemTable full and flushing to disk
- Background compaction lagging
- Disk I/O bottleneck

**Solutions:**
- Increase MemTable size (more memory, fewer flushes)
- Use faster disk (SSD)
- Reduce write rate

#### High Read Latency

**Symptom:** `load()` operations take >1ms

**Causes:**
- Cache misses (data not in block cache)
- Too many SST files (needs compaction)
- Bloom filter ineffective

**Solutions:**
- Increase block cache size
- Trigger manual compaction
- Use more selective key prefixes

#### Disk Space Growing

**Symptom:** Database uses more space than expected

**Causes:**
- Compaction not keeping up
- Old snapshots not deleted
- Too many event log entries

**Solutions:**
- Delete old snapshots
- Compact event log (remove processed events)
- Increase compaction trigger thresholds

## Choosing a Backend

### Decision Tree

```
                          Need crash recovery?
                               │
              ┌───────────────┴───────────────┐
              │ YES                           │ NO
              ▼                               ▼
         Production?                  Testing/Dev?
              │                               │
              │                         Use InMemoryBackend
              ▼
         Use RocksDBBackend
```

### Recommendations

| Scenario | Backend | Reason |
|----------|---------|--------|
| **Unit tests** | InMemory | Fast, no cleanup |
| **Integration tests** | RocksDB | Test persistence |
| **Local development** | InMemory | Quick iteration |
| **Production** | RocksDB | Crash recovery |
| **Distributed cluster** | RocksDB | Shared state |
| **Caching layer** | InMemory | Speed > persistence |

## Future Backends

Planned backends for future releases:

- **RedisBackend** - Distributed cache, pub/sub
- **PostgresBackend** - SQL persistence, transactions
- **S3Backend** - Cloud object storage, infinite scale
- **ConsulBackend** - Distributed configuration, service discovery

## References

- [RocksDB Documentation](https://github.com/facebook/rocksdb/wiki)
- [LSM Trees](https://en.wikipedia.org/wiki/Log-structured_merge-tree)
- [JVM Crash Survival](jvm-crash-survival.md)
- [Distributed Patterns](distributed-patterns.md)
