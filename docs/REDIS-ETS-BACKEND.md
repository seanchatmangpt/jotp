# Redis-Based ETS Backend for JOTP

## Architecture Overview

The Redis ETS Backend implements Erlang/OTP's Erlang Term Storage (ETS) pattern using Redis as the single source of truth for all distributed table storage. This eliminates local duplication and ensures strong consistency across the entire JOTP cluster.

### Design Principle

**Redis IS the Storage, Not a Replication Target**

```
Node 1 ──┐
Node 2 ──┼──→ [Redis Cluster] ← Single source of truth
Node 3 ──┘    (All tables stored here, all nodes read/write directly)
```

Unlike traditional patterns where local caches are replicated to Redis, this backend treats Redis as the primary storage. All nodes:
- Read directly from Redis (no local cache layer)
- Write directly to Redis (no local accumulation)
- Get consistent views of all tables immediately
- Benefit from Redis Cluster replication and high availability

## Supported Table Types

### 1. SET Type
- **Storage:** Redis HASH (`jotp:ets:tableName:set`)
- **Semantics:** Unique keys, last write wins
- **Use Case:** User profiles, configuration values, unique entity lookups
- **Behavior:** `put("users", "user-42", data)` overwrites previous value

### 2. BAG Type
- **Storage:** Redis LIST (`jotp:ets:tableName:bag`)
- **Semantics:** Duplicate keys allowed, all values stored
- **Use Case:** Event logs, audit trails, append-only sequences
- **Behavior:** `put("events", "event-001", data1)` then `put("events", "event-001", data2)` stores both

### 3. ORDERED_SET Type
- **Storage:** Redis ZSET (`jotp:ets:tableName:ordered`)
- **Semantics:** Unique keys with sorted iteration by version/score
- **Use Case:** Time-series data, versioned snapshots, ordered sequences
- **Behavior:** `writeAtomicWithVersion("ts", "ts-1", v1, 1L)` then `v2` at version 2L maintains order

## Key Features

### 1. Distributed Storage
All JOTP nodes read/write to the same Redis instance(s). No replication lag or eventual consistency issues — just direct consistency.

```java
// Both nodes see the same data immediately
node1.put("users", "user-42", data);
List<byte[]> values = node2.get("users", "user-42"); // data is immediately available
```

### 2. Pattern Matching
ETS-style pattern queries for hierarchical key structures:

```java
backend.match("configs", "app:db:*");           // prefix match
backend.match("configs", "app:*:timeout");      // wildcard in middle
backend.match("configs", "exact-key");          // exact match
```

### 3. Atomic Operations with Idempotence
Version-based idempotence prevents duplicate processing:

```java
// Writing with version ensures idempotence
backend.writeAtomicWithVersion("orders", "order-1", data, version123);
// If version 123 already processed, write is skipped
backend.writeAtomicWithVersion("orders", "order-1", data, version123); // no-op
```

### 4. Local Pub/Sub Notifications
Subscribe to local table changes without pub/sub overhead:

```java
backend.subscribeTable("notifications", event -> {
    System.out.println("Change: " + event.type() + " on " + event.key());
});

backend.put("notifications", "notif-1", data); // triggers callback
```

### 5. PersistenceBackend Compatible
Implements the JOTP persistence contract for process state snapshots:

```java
// Save and restore process state
backend.save("proc-001", stateBytes);
Optional<byte[]> restored = backend.load("proc-001");

// Atomic state + ACK writes for crash recovery
backend.writeAtomic("proc-002", stateBytes, ackBytes);
Optional<Long> ackSeq = backend.getAckSequence("proc-002");
```

### 6. TTL Support
Automatic expiration of entries:

```java
// Backend created with 24-hour TTL
RedisEtsBackend backend = new RedisEtsBackend(host, port, nodeName, 86400L);
// All entries automatically expire after 24 hours
```

### 7. Pipeline Operations
Multi-operation batches reduce round-trips to Redis.

## Redis Data Structure Format

### SET Type Internal Format
```
Hash Key: jotp:ets:tableName:set
  key1 → <binary value>
  key2 → <binary value>
```

### BAG Type Internal Format
```
List Key: jotp:ets:tableName:bag
  [0] → <4-byte key_len><key><value>
  [1] → <4-byte key_len><key><value>
```

Each entry is encoded as:
- 4 bytes: key length (big-endian)
- N bytes: key (UTF-8)
- M bytes: value (binary)

### ORDERED_SET Type Internal Format
```
Zset Key: jotp:ets:tableName:ordered
  Score 1.0 → "key:base64(value)"
  Score 2.0 → "key:base64(value)"
```

Scores are versions/timestamps for ordered iteration.

## State Persistence via PersistenceBackend

The Redis ETS backend also implements `PersistenceBackend` for saving process state:

```
Process State Storage:
  Key: jotp:state:proc-key
  Value: <binary state snapshot>

ACK (Last Processed Message):
  Key: jotp:ack:proc-key
  Value: <8-byte sequence number>
```

This enables JOTP processes to:
1. Checkpoint state to Redis
2. Recover on restart
3. Track processed messages for idempotence

## Usage Examples

### Example 1: Distributed Configuration Store
```java
RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-1");
backend.createTable("appconfig", EtsTable.TableType.SET);

// All nodes write their settings
backend.put("appconfig", "auth:timeout", "30000".getBytes());
backend.put("appconfig", "db:pool:size", "20".getBytes());

// All nodes read consistent config
List<byte[]> timeout = backend.get("appconfig", "auth:timeout");

// Query hierarchical keys
List<String> authKeys = backend.match("appconfig", "auth:*");
```

### Example 2: Event Log (Append-Only)
```java
RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-2");
backend.createTable("events", EtsTable.TableType.BAG);

// Multiple nodes append events
backend.put("events", "payment-123", "initiated".getBytes());
backend.put("events", "payment-123", "completed".getBytes());

// Retrieve all events for a transaction
List<byte[]> allEvents = backend.get("events", "payment-123");
// Returns ["initiated", "completed"]
```

### Example 3: Time-Series Data with Versioning
```java
RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-3");
backend.createTable("metrics", EtsTable.TableType.ORDERED_SET);

// Write metrics with versions
backend.writeAtomicWithVersion("metrics", "cpu-usage", "45%".getBytes(), 1000L);
backend.writeAtomicWithVersion("metrics", "cpu-usage", "62%".getBytes(), 2000L);
backend.writeAtomicWithVersion("metrics", "cpu-usage", "38%".getBytes(), 3000L);

// All writes ordered by version
List<byte[]> history = backend.get("metrics", "cpu-usage");
// Returns values in version order: [45%, 62%, 38%]
```

### Example 4: Process Crash Recovery
```java
RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "proc-node");

// Save state + ACK atomically
byte[] stateSnapshot = serializeState(procState);
byte[] ackMarker = longToBytes(lastProcessedMessageSeq);
backend.writeAtomic("payment-processor-1", stateSnapshot, ackMarker);

// On restart: recover state and continue from last ACK
Optional<byte[]> restored = backend.load("payment-processor-1");
Optional<Long> lastSeq = backend.getAckSequence("payment-processor-1");

PaymentProcessorState state = deserializeState(restored.get());
long resumeFromSeq = lastSeq.get() + 1;
```

## Multi-Node Cluster Scenarios

### Scenario 1: Shared Table Ownership
```java
// Node A (owner)
RedisEtsBackend nodeA = new RedisEtsBackend("redis-cluster", 6379, "node-A");
nodeA.createTable("users", EtsTable.TableType.SET);
nodeA.put("users", "alice", aliceData);

// Node B (replica/consumer)
RedisEtsBackend nodeB = new RedisEtsBackend("redis-cluster", 6379, "node-B");
List<byte[]> alice = nodeB.get("users", "alice"); // Sees data written by A
nodeB.put("users", "bob", bobData); // Also contributes to shared table

// Both nodes see consistent view
List<String> allUsers = nodeA.keys("users"); // ["alice", "bob"]
List<String> allUsers = nodeB.keys("users"); // ["alice", "bob"]
```

### Scenario 2: Fault Tolerance
```java
// If Node A crashes, Node B continues unaffected
nodeB.put("users", "charlie", charlieData); // Works because Redis is the source of truth
// Node A restarts and immediately sees latest data
nodeA.get("users", "charlie"); // ["charlieData"]
```

### Scenario 3: Geographic Distribution
```java
// Nodes in different regions all connect to same Redis Cluster
RedisEtsBackend usEast = new RedisEtsBackend("redis-cluster-1", 6379, "us-east");
RedisEtsBackend euWest = new RedisEtsBackend("redis-cluster-1", 6379, "eu-west");
RedisEtsBackend apSoutheast = new RedisEtsBackend("redis-cluster-1", 6379, "ap-se");

// All see consistent data
usEast.put("global-state", "key-1", data);
Thread.sleep(50); // minimal network delay
byte[] readByEU = euWest.get("global-state", "key-1").get(0); // immediate visibility
```

## Consistency Guarantees

### Strong Consistency for Reads
Once a write completes in Redis, all subsequent reads see that value immediately (no replication lag).

### Idempotent Writes
Using `writeAtomicWithVersion()` ensures:
- Same version written multiple times has no additional effect
- Recovery from transient failures doesn't duplicate operations
- Safe for distributed retries

### Atomic State + ACK
The `writeAtomic()` operation batches state and ACK in a single Redis pipeline:
- Both succeed or both fail
- No partial updates
- Safe for crash recovery

## Performance Characteristics

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| PUT (SET) | O(1) | Redis HSET |
| GET (SET) | O(1) | Redis HGET |
| PUT (BAG) | O(N) | Redis RPUSH + encoding |
| GET (BAG) | O(N) | List scan + decoding |
| MATCH | O(N) | Redis HSCAN with pattern |
| SELECT | O(N) | Full key scan + predicate |
| DELETE | O(N) | Hash delete (SET) or list filter (BAG) |

### Optimization Tips
1. **Prefer SET over BAG** for unique keys (O(1) vs O(N))
2. **Use pattern matching** for hierarchical queries instead of fetching all keys
3. **Batch writes** in application logic before flushing to Redis
4. **Enable Redis Cluster** for load distribution
5. **Configure Redis persistence** (RDB/AOF) for durability

## Error Handling

### Null Arguments
```java
backend.put("table", null, data);      // NullPointerException
backend.put("table", "key", null);     // NullPointerException
backend.createTable(null, TableType.SET); // NullPointerException
```

### Closed Backend
```java
backend.close();
backend.put("table", "key", data);     // PersistenceException("Backend is closed")
```

### Redis Connection Failures
All operations throw `PersistenceException` wrapping underlying Redis exceptions.

## Migration from Local EtsBackend

### Before (Local Tables)
```java
EtsBackend backend = new EtsBackend("node-1", 3600, "redis-host", 6379);
// Tables stored locally, replicated via Pub/Sub
// Eventual consistency, replication lag possible
```

### After (Redis Backend)
```java
RedisEtsBackend backend = new RedisEtsBackend("redis-host", 6379, "node-1");
// Tables stored directly in Redis
// Strong consistency, no replication lag
// Simpler architecture, same API
```

## PersistenceBackend Integration

When used as a `PersistenceBackend`:

```java
// Can be injected into DurableState or EventSourcingAuditLog
PersistenceBackend backend = new RedisEtsBackend(host, port, nodeName);

// DurableState uses it for snapshots
new DurableState(backend, "proc-key", initialState);

// EventSourcingAuditLog uses it for state persistence
new EventSourcingAuditLog(backend, "aggregate-id");
```

## Configuration

### TTL Management
```java
// Default 24-hour TTL
RedisEtsBackend backend = new RedisEtsBackend(host, port, nodeName);

// Custom TTL (1 hour)
RedisEtsBackend backend = new RedisEtsBackend(host, port, nodeName, 3600L);

// Per-operation expiry setting
backend.createTable("temporary", TableType.SET); // auto-expires in 1 hour
```

### Connection Pooling
```java
// Backend creates internal JedisPool
// Default: 32 max connections, 4 minimum, 16 idle
// Suitable for typical JOTP workloads
```

## Testing

The `RedisEtsBackendTest` class provides comprehensive test coverage:

```bash
mvnd test -Dtest=RedisEtsBackendTest
```

Tests cover:
- All table types (SET, BAG, ORDERED_SET)
- Pattern matching
- PersistenceBackend contract
- Subscriptions and notifications
- Error conditions
- Null/closed state handling

## Troubleshooting

### Connection Failures
```
PersistenceException: Failed to create table: tableName
  → Check Redis is running on specified host:port
  → Check network connectivity
  → Check Redis is not out of memory
```

### Pattern Matching No Results
```
List<String> results = backend.match("table", "prefix:*");
if (results.isEmpty()) {
    // Check table name and prefix are correct
    // Patterns are case-sensitive
    // Use SCAN with pattern: "prefix:\*"
}
```

### Slow Bag Operations
```
// Bags can be slow for large datasets (O(N) operations)
// Consider:
// - Moving to SET if duplicates not needed
// - Partitioning by key prefix
// - Using time-windowed tables
```

## References

- **Erlang ETS:** https://www.erlang.org/doc/man/ets.html
- **Redis Data Structures:** https://redis.io/docs/data-types/
- **JOTP PersistenceBackend:** See `io.github.seanchatmangpt.jotp.persistence.PersistenceBackend`
- **EtsTable Interface:** See `io.github.seanchatmangpt.jotp.distributed.EtsTable`
