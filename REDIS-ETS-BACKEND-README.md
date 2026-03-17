# Redis ETS Backend: Distributed Table Storage for JOTP

## Overview

The Redis ETS Backend brings Erlang/OTP's distributed ETS (Erlang Term Storage) to the JVM using Redis as the single source of truth. This eliminates the need for local table replicas and provides strong consistency across all cluster nodes.

### Key Innovation: Redis as Primary Storage

**Traditional Approach (Local EtsBackend):**
```
Node 1: Local Table ──┐
Node 2: Local Table ──┼──→ Redis (Replication Target)
Node 3: Local Table ──┘    (Eventual Consistency)
```

**New Approach (RedisEtsBackend):**
```
Node 1 ──┐
Node 2 ──┼──→ Redis Cluster (Primary Storage)
Node 3 ──┘    (Strong Consistency)
```

All nodes read/write directly to Redis, eliminating replication lag and simplifying architecture.

## What's Included

### 1. Production Implementation
**File:** `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackend.java`

952 lines of production-ready code providing:
- All 3 ETS table types: SET, BAG, ORDERED_SET
- Full CRUD operations (create, put, get, delete, match)
- Pattern matching for hierarchical queries
- Selection with predicates
- Table statistics and metadata
- TTL-based expiration
- Local Pub/Sub notifications for changes
- Atomic operations with version-based idempotence
- PersistenceBackend contract implementation
- Connection pooling and resource management

### 2. Comprehensive Examples
**File:** `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackendExample.java`

273 lines showing 7 real-world scenarios:
1. SET table operations (unique keys)
2. BAG table operations (duplicate keys allowed)
3. ORDERED_SET table operations (versioned/ordered)
4. Pattern matching queries
5. Local subscriptions and change notifications
6. Process state persistence (PersistenceBackend)
7. Multi-node cluster scenarios

Run examples:
```bash
java io.github.seanchatmangpt.jotp.distributed.RedisEtsBackendExample
```

### 3. Test Suite
**File:** `/home/user/jotp/src/test/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackendTest.java`

500 lines with 30+ test cases covering:
- All table type operations
- CRUD functionality
- Pattern matching validation
- Selection predicates
- Table management
- PersistenceBackend contract
- Subscriptions and notifications
- Error handling
- Null argument handling
- Closed state handling
- Auto-creation behavior

Run tests:
```bash
./mvnw test -Dtest=RedisEtsBackendTest
```

### 4. Documentation
**Files:**
- `/home/user/jotp/docs/REDIS-ETS-BACKEND.md` - Architecture, concepts, and design
- `/home/user/jotp/docs/REDIS-ETS-INTEGRATION.md` - Integration patterns and examples

## Quick Start

### Basic Setup
```java
// Create backend
RedisEtsBackend backend = new RedisEtsBackend(
    "localhost",           // Redis host
    6379,                  // Redis port
    "node-1"               // Local node name
);

// Create table
backend.createTable("users", EtsTable.TableType.SET);

// Put value
backend.put("users", "alice", aliceData);

// Get value
List<byte[]> values = backend.get("users", "alice");

// Pattern query
List<String> allUsers = backend.match("users", "*");

// Close
backend.close();
```

### Table Types

#### SET Type
- **Semantics:** Unique keys, last write wins
- **Storage:** Redis HASH
- **Performance:** O(1) for put/get/delete
- **Use:** User profiles, config values, unique lookups

```java
backend.createTable("users", EtsTable.TableType.SET);
backend.put("users", "alice", data);    // Overwrites if exists
backend.put("users", "alice", newData); // Last write wins
List<byte[]> result = backend.get("users", "alice"); // [newData]
```

#### BAG Type
- **Semantics:** Duplicate keys allowed, all values stored
- **Storage:** Redis LIST
- **Performance:** O(N) for put/get/delete
- **Use:** Event logs, audit trails, append-only sequences

```java
backend.createTable("events", EtsTable.TableType.BAG);
backend.put("events", "event-1", "login");
backend.put("events", "event-1", "logout");
List<byte[]> results = backend.get("events", "event-1"); // [login, logout]
```

#### ORDERED_SET Type
- **Semantics:** Unique keys with sorted iteration by version
- **Storage:** Redis ZSET
- **Performance:** O(log N) for ordered operations
- **Use:** Time-series, versioned snapshots, ordered sequences

```java
backend.createTable("metrics", EtsTable.TableType.ORDERED_SET);
backend.writeAtomicWithVersion("metrics", "cpu", v1, 1L);
backend.writeAtomicWithVersion("metrics", "cpu", v2, 2L);
// Values returned in version order
```

## Core Features

### 1. Pattern Matching
Query tables using hierarchical key patterns:

```java
backend.put("config", "app:db:host", "localhost".getBytes());
backend.put("config", "app:db:port", "5432".getBytes());
backend.put("config", "app:cache:ttl", "3600".getBytes());

// Prefix match
List<String> dbConfigs = backend.match("config", "app:db:*");
// Returns: ["app:db:host", "app:db:port"]

// Exact match
List<String> exact = backend.match("config", "app:db:host");
// Returns: ["app:db:host"]

// All keys
List<String> all = backend.match("config", "*");
```

### 2. Selection with Predicates
Filter keys using custom logic:

```java
backend.createTable("users", EtsTable.TableType.SET);
backend.put("users", "admin-1", adminData);
backend.put("users", "user-2", userData);
backend.put("users", "guest-3", guestData);

// Select by predicate
List<String> adminUsers = backend.select("users", k -> k.startsWith("admin"));
// Returns: ["admin-1"]
```

### 3. Atomic Operations with Idempotence
Version-based writes prevent duplicate processing:

```java
// Write with version for idempotence
backend.writeAtomicWithVersion("orders", "order-123", data, version5);

// If version 5 already processed, this is skipped
backend.writeAtomicWithVersion("orders", "order-123", data, version5); // no-op

// Safe for distributed retries
```

### 4. Local Subscriptions
Subscribe to table changes:

```java
backend.subscribeTable("notifications", event -> {
    System.out.println("Change: " + event.type() + " on " + event.key());
    System.out.println("Origin: " + event.originNode());
    System.out.println("Time: " + new Date(event.timestamp()));
});

backend.put("notifications", "alert-1", data); // Triggers callback
```

### 5. PersistenceBackend Contract
Use as process state persistence layer:

```java
// Save state
backend.save("proc-001", stateSnapshot);

// Load state
Optional<byte[]> restored = backend.load("proc-001");

// Atomic state + ACK
backend.writeAtomic("proc-001", state, ack);
Optional<Long> lastProcessedSeq = backend.getAckSequence("proc-001");

// Check existence
boolean exists = backend.exists("proc-001");

// List all persisted keys
Iterable<String> keys = backend.listKeys();

// Delete
backend.delete("proc-001");
```

## Integration Examples

### With DurableState
```java
public class PaymentProcessor {
    private final RedisEtsBackend backend;
    private final DurableState<PaymentState> state;

    public PaymentProcessor(String nodeName) {
        this.backend = new RedisEtsBackend("redis", 6379, nodeName);
        this.state = new DurableState<>(backend, "payment-proc", PaymentState.initial());
    }

    public void process(Payment payment) {
        PaymentState current = state.get();
        PaymentState updated = current.process(payment);
        state.persist(updated);  // Saved to Redis
    }
}
```

### With EventSourcingAuditLog
```java
public class OrderAggregate {
    private final RedisEtsBackend backend;
    private final EventSourcingAuditLog<OrderEvent> log;

    public OrderAggregate(String nodeId) {
        this.backend = new RedisEtsBackend("redis", 6379, nodeId);
        this.log = new EventSourcingAuditLog<>(backend, "order-123", OrderEvent.class);
    }

    public void addLineItem(LineItem item) {
        log.append(new LineItemAdded(item));
    }
}
```

### With Supervisor
```java
public class DistributedSessionManager {
    private final RedisEtsBackend backend;

    public DistributedSessionManager(String nodeName) {
        this.backend = new RedisEtsBackend("redis", 6379, nodeName);
        this.backend.createTable("sessions", EtsTable.TableType.SET);
    }

    public void createSession(String id, byte[] data) {
        backend.put("sessions", id, data);
    }

    public List<byte[]> getSession(String id) {
        return backend.get("sessions", id);
    }
}
```

## Multi-Node Cluster Usage

All nodes connect to the same Redis and see consistent data:

```java
// Node 1
RedisEtsBackend node1 = new RedisEtsBackend("redis-cluster", 6379, "node-1");
node1.createTable("shared", EtsTable.TableType.SET);
node1.put("shared", "key-1", "data-1".getBytes());

// Node 2 (immediately sees Node 1's write)
RedisEtsBackend node2 = new RedisEtsBackend("redis-cluster", 6379, "node-2");
List<byte[]> values = node2.get("shared", "key-1"); // ["data-1"]

// Node 1 reads what Node 2 writes
node2.put("shared", "key-2", "data-2".getBytes());
List<byte[]> node1Reads = node1.get("shared", "key-2"); // ["data-2"]
```

## Consistency Guarantees

### Strong Consistency for Reads
Once a write completes in Redis, all subsequent reads immediately see that value (no replication lag).

### Idempotent Writes
Using `writeAtomicWithVersion()`:
- Same version written multiple times has no duplicate effect
- Safe for distributed retries
- Enables exactly-once semantics

### Atomic State + ACK
The `writeAtomic()` operation:
- Writes state and ACK in single Redis pipeline
- Both succeed or both fail
- No partial updates
- Safe for crash recovery

## Performance

| Operation | Complexity | Table Type |
|-----------|-----------|-----------|
| put | O(1) | SET |
| get | O(1) | SET |
| delete | O(1) | SET |
| put | O(N) | BAG |
| get | O(N) | BAG |
| delete | O(N) | BAG |
| match | O(N) | All |
| select | O(N) | All |

**Optimization Tips:**
1. Use SET for unique keys (faster than BAG)
2. Prefer pattern matching over fetching all keys
3. Use hierarchical key structure (e.g., `app:db:host`)
4. Configure Redis Cluster for load distribution
5. Enable Redis persistence (RDB/AOF) for durability

## Configuration

### TTL (Time-To-Live)
```java
// Default 24 hours
RedisEtsBackend backend = new RedisEtsBackend(host, port, nodeName);

// Custom TTL (1 hour)
RedisEtsBackend backend = new RedisEtsBackend(host, port, nodeName, 3600L);

// Entries automatically expire after TTL
```

### Connection Pooling
```java
// Backend creates internal JedisPool
// Default: max 32 connections, min 4, idle 16
// Automatically configured for typical JOTP workloads
```

## Error Handling

```java
try (RedisEtsBackend backend = new RedisEtsBackend(host, port, nodeName)) {
    // Null arguments throw NullPointerException
    backend.put(null, "key", data);          // NullPointerException
    backend.put("table", null, data);        // NullPointerException

    // Closed backend throws PersistenceException
    backend.close();
    backend.put("table", "key", data);       // PersistenceException

    // Redis connection failures throw PersistenceException
    backend.put("table", "key", data);       // Wraps Redis exceptions
} catch (PersistenceException e) {
    System.err.println("Failed: " + e.getMessage());
}
```

## Testing

### Unit Test Example
```java
@Test
void testSetTableOperations() {
    try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "test")) {
        backend.createTable("test", EtsTable.TableType.SET);
        backend.put("test", "key-1", "value".getBytes());

        List<byte[]> result = backend.get("test", "key-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("value".getBytes());
    }
}
```

### Run Test Suite
```bash
./mvnw test -Dtest=RedisEtsBackendTest
```

## Requirements

- Java 26+ (for sealed types and pattern matching)
- Redis 6.0+ (for SCAN, ZSET, LIST operations)
- Maven 4 or `./mvnw` wrapper
- Jedis 5.0+ (included in JOTP dependencies)

## Files

- **Implementation:** `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackend.java`
- **Examples:** `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackendExample.java`
- **Tests:** `/home/user/jotp/src/test/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackendTest.java`
- **Architecture:** `/home/user/jotp/docs/REDIS-ETS-BACKEND.md`
- **Integration:** `/home/user/jotp/docs/REDIS-ETS-INTEGRATION.md`

## References

- **Erlang ETS:** https://www.erlang.org/doc/man/ets.html
- **Redis Documentation:** https://redis.io/docs/
- **JOTP PersistenceBackend:** `io.github.seanchatmangpt.jotp.persistence.PersistenceBackend`
- **EtsTable Interface:** `io.github.seanchatmangpt.jotp.distributed.EtsTable`
- **JOTP Module:** `io.github.seanchatmangpt.jotp`

## Summary

The Redis ETS Backend provides a production-ready, distributed table storage solution for JOTP that:

✓ Eliminates local table duplication
✓ Ensures strong consistency across nodes
✓ Supports all Erlang ETS table types
✓ Provides pattern matching and querying
✓ Implements PersistenceBackend for process state
✓ Scales with Redis Cluster
✓ Handles multi-node clusters transparently
✓ Includes comprehensive tests and examples

Use it to build fault-tolerant, distributed systems with reliable shared storage.
