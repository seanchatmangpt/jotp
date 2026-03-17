# Mnesia-Style Distributed Database Backend for JOTP

## Overview

The `MnesiaBackend` brings Erlang's battle-tested Mnesia distributed database patterns to Java 26 and JOTP. It provides ACID transactions, schema definition, multi-node replication, and automatic recovery using PostgreSQL for durability and Redis for distributed coordination.

## Architecture

```
┌─────────────────────────────────────────────────┐
│          JOTP Application Layer                 │
├─────────────────────────────────────────────────┤
│       MnesiaBackend (Transaction Layer)         │
├────────────────────┬────────────────────────────┤
│   PostgreSQL       │   Redis Lock Manager       │
│   (ACID, MVCC,     │   (Distributed Locks,     │
│    Durability)     │    Cache Layer)            │
└────────────────────┴────────────────────────────┘
```

### Key Components

- **MnesiaBackend**: Main API for database operations
- **MnesiaSchema**: Table definition with replication strategy
- **MnesiaTransaction**: Transaction context with MVCC snapshot isolation
- **PostgreSQL**: Primary storage with full ACID compliance
- **Redis**: Distributed locking and caching

## Features

### 1. Schema Definition

Define table structure following Erlang Mnesia patterns:

```java
var schema = new MnesiaSchema(
    "users",                                    // table name
    List.of("id", "name", "email"),            // attributes
    ReplicationType.DISC_COPIES,               // replication strategy
    List.of("node1", "node2", "node3"),        // replica nodes
    Optional.of(86400L)                        // 24-hour TTL
);

backend.createTable(schema);
```

### 2. ACID Transactions with MVCC

Transactions provide snapshot isolation via PostgreSQL's MVCC:

```java
Result<String, MnesiaError> result = backend.transaction(tx -> {
    // All reads see a consistent snapshot
    Optional<byte[]> existing = tx.read("users", "alice");

    // Multiple writes are buffered
    tx.write("users", "alice", "updated".getBytes());
    tx.write("users", "bob", "data".getBytes());

    // Conditional operations
    if (existing.isPresent()) {
        tx.delete("users", "old_user");
    }

    return Result.ok("success");
});

// Transaction is atomically committed or rolled back
```

### 3. Replication Strategies

Three replication models (mapping to Erlang Mnesia):

#### COPIES (RAM_COPIES equivalent)
In-memory only, no persistence. Fastest but lost on node crash:
```java
ReplicationType.COPIES
```

#### DISC_COPIES
Durable on disk via PostgreSQL. Survives node crashes:
```java
ReplicationType.DISC_COPIES  // Most common for production
```

#### RAM_COPIES
Replicated in-memory via Redis. Fast and distributed:
```java
ReplicationType.RAM_COPIES
```

### 4. Distributed Locks

Automatic distributed locking using Redis with TTL:

```
[Node 1] ─────┐
              ├──→ [Redis Lock Manager] ─── TTL: 30s
[Node 2] ─────┤
              │
[Node 3] ─────┘

// Lock automatically acquired during transaction
// Released on commit or rollback
// TTL prevents deadlocks from crashed processes
```

### 5. Lamport Clocks

Logical timestamp ordering across distributed nodes:

```java
long clock1 = backend.incrementLamportClock();  // 1001
long clock2 = backend.incrementLamportClock();  // 1002
long clock3 = backend.incrementLamportClock();  // 1003

// Guarantees causal ordering of transactions
// Resolves distributed timeline ambiguities
```

### 6. Transaction Hooks

Pre-commit and post-commit hooks for distributed coordination:

```java
backend.transaction(tx -> {
    tx.write("users", "alice", data);

    // Pre-commit: Runs before database write
    // Use for validation, resource reservation, etc.
    tx.beforeCommit(() -> {
        validateUserData(data);
        acquireLock("alice");
    });

    // Post-commit: Runs after successful commit
    // Use for notifications, cache updates, etc.
    tx.afterCommit(() -> {
        publishUserUpdatedEvent("alice");
        invalidateCache("users");
    });

    return Result.ok("success");
});
```

## Mapping to Erlang Mnesia

| Erlang Mnesia | Java 26 JOTP |
|---|---|
| `mnesia:create_table(Name, Options)` | `backend.createTable(schema)` |
| `mnesia:transaction(Fun)` | `backend.transaction(tx -> ...)` |
| `mnesia:read(Table, Key)` | `tx.read(tableName, key)` |
| `mnesia:write(Table, Record)` | `tx.write(tableName, key, value)` |
| `mnesia:delete(Table, Key)` | `tx.delete(tableName, key)` |
| `mnesia:table_info(Table, copies)` | `backend.getTableInfo(tableName)` |
| `{ram_copies, Nodes}` | `ReplicationType.COPIES` |
| `{disc_copies, Nodes}` | `ReplicationType.DISC_COPIES` |
| `{record_name, user}` | `schema.tableName()` |
| Lamport timestamp | `tx.getLamportClock()` |

## Usage Examples

### Basic Setup

```java
MnesiaBackend backend = new MnesiaBackend(
    "localhost", 5432, "jotp_db",        // PostgreSQL
    "localhost", 6379, "jotp-cluster",   // Redis
    3,                                    // replication factor
    Duration.ofSeconds(30)                // lock timeout
);

backend.initialize();
```

### Create and Query

```java
// Define schema
var schema = new MnesiaSchema(
    "products",
    List.of("product_id", "name", "price"),
    ReplicationType.DISC_COPIES,
    List.of("node1", "node2", "node3"),
    Optional.empty()
);

backend.createTable(schema);

// Insert data
backend.transaction(tx -> {
    tx.write("products", "p1", "widget:9.99".getBytes());
    return Result.ok("inserted");
});

// Read data
backend.transaction(tx -> {
    Optional<byte[]> product = tx.read("products", "p1");
    return Result.ok(product);
});

// Scan all records
List<byte[]> allProducts = backend.scanTable("products");
```

### Conditional Updates

```java
backend.transaction(tx -> {
    var user = tx.read("users", "alice");

    if (user.isPresent()) {
        String userData = new String(user.get());
        if (userData.contains("active")) {
            tx.write("users", "alice", "inactive".getBytes());
            return Result.ok("deactivated");
        }
    }
    return Result.err("user not found");
});
```

### Batch Operations

```java
backend.transaction(tx -> {
    for (var item : items) {
        tx.write("items", item.id, item.serialize());
    }

    // All writes are atomic
    return Result.ok("batch_inserted");
});
```

## Internal Design

### MVCC Implementation

1. Transaction starts → captures Lamport clock
2. Read operations load snapshot of table at that clock value
3. All reads return consistent snapshot (snapshot isolation)
4. Writes buffered in transaction's write set
5. On commit: PostgreSQL transaction ensures atomic application

### Distributed Locking

```
[Transaction Starts]
       ↓
[Acquire Redis Lock: SET NX with 30s TTL]
       ↓
[If acquired, execute transaction]
       ↓
[On commit/rollback: DEL lock]
       ↓
[If TTL expires: lock auto-released]
```

### Transaction Log

Every transaction is logged to PostgreSQL for recovery:

```sql
CREATE TABLE mnesia_transaction_log (
    tx_id VARCHAR(255),
    table_name VARCHAR(255),
    key_value VARCHAR(255),
    operation VARCHAR(64),      -- WRITE or DELETE
    data BYTEA,
    lamport_clock BIGINT,
    created_at TIMESTAMP
);
```

### Deadlock Handling

Automatic retry with exponential backoff:

```
Transaction attempt 1 → DEADLOCK
    ↓
Wait 10ms (2^0 * 10)
    ↓
Transaction attempt 2 → DEADLOCK
    ↓
Wait 20ms (2^1 * 10)
    ↓
Transaction attempt 3 → DEADLOCK
    ↓
Return error
```

## Configuration

### Backend Initialization

```java
new MnesiaBackend(
    postgresHost,           // default: "localhost"
    postgresPort,           // default: 5432
    postgresDatabase,       // e.g., "jotp_prod"
    postgresUser,           // default: "jotp"
    postgresPassword,       // default: ""
    redisHost,              // default: "localhost"
    redisPort,              // default: 6379
    redisKeyspace,          // namespace, e.g., "jotp-prod"
    replicationFactor,      // number of replicas
    lockTimeout             // Duration for lock acquisition
);
```

### Replication Configuration

- **Replication Factor**: Number of nodes holding copies (3 is typical)
- **Replica Nodes**: List of node identifiers in cluster
- **TTL**: Optional automatic expiration for records

## Performance Characteristics

### Latency (approximate)

| Operation | Latency |
|-----------|---------|
| Single read | 5-10ms (PostgreSQL) |
| Single write | 50-100ms (PostgreSQL + Redis) |
| Batch write (10 items) | 100-150ms |
| Large transaction (100+ items) | 500-1000ms |

### Throughput

- **Read-heavy**: Up to 10K reads/sec per node
- **Write-heavy**: Up to 1K writes/sec per cluster
- **Mixed workload**: Depends on read/write ratio

### Scaling

- **Horizontal**: Add replica nodes
- **Vertical**: Increase PostgreSQL connection pool
- **Vertical**: Increase Redis memory (for cache layer)

## Error Handling

### Transaction Failures

```java
Result<T, MnesiaError> result = backend.transaction(...);

if (result instanceof Result.Err<?, ?> err) {
    MnesiaError error = (MnesiaError) err.error();

    String code = error.getErrorCode();
    String message = error.getMessage();

    // Handle specific error types
    switch(code) {
        case "DEADLOCK_RETRY_EXCEEDED" -> // Retry externally
        case "PRECOMMIT_FAILED" -> // Check hooks
        case "CREATE_TABLE_FAILED" -> // Schema error
        // ...
    }
}
```

### Recovery

On node crash:

1. PostgreSQL durability ensures data is safe
2. Redis cache is invalidated (will be rebuilt)
3. Pending transactions are lost (will be retried by application)
4. Node restarts and rejoins cluster automatically

## Best Practices

### 1. Keep Transactions Small

```java
// GOOD: Small, focused transaction
backend.transaction(tx -> {
    tx.write("users", userId, userData);
    return Result.ok("ok");
});

// AVOID: Large transaction with many operations
backend.transaction(tx -> {
    for (int i = 0; i < 10000; i++) {
        // Too many operations in one transaction
        tx.write("items", "item_" + i, data);
    }
});
```

### 2. Use Appropriate Replication Strategy

```java
// For hot data (frequently accessed): RAM_COPIES
var hotSchema = new MnesiaSchema(
    "sessions",
    List.of("session_id"),
    ReplicationType.RAM_COPIES,  // Fast, replicated
    List.of("node1", "node2"),
    Optional.of(3600L)  // 1 hour TTL
);

// For important data (must survive crashes): DISC_COPIES
var importantSchema = new MnesiaSchema(
    "users",
    List.of("user_id"),
    ReplicationType.DISC_COPIES,  // Durable
    List.of("node1", "node2", "node3"),
    Optional.empty()
);
```

### 3. Leverage Hooks for Coordination

```java
backend.transaction(tx -> {
    // Write data
    tx.write("users", userId, userData);

    // Ensure validation before commit
    tx.beforeCommit(() -> validateUser(userId));

    // Update derived state after commit
    tx.afterCommit(() -> invalidateUserCache(userId));

    return Result.ok("ok");
});
```

### 4. Handle Transaction Failures

```java
var maxRetries = 3;
for (int attempt = 0; attempt < maxRetries; attempt++) {
    Result<String, ?> result = backend.transaction(...);

    if (result instanceof Result.Ok) {
        return result;  // Success
    }

    if (attempt < maxRetries - 1) {
        Thread.sleep((long) Math.pow(2, attempt) * 100);
    }
}
```

## Troubleshooting

### Issue: Deadlock Errors

**Cause**: Multiple transactions accessing same rows in different order

**Solution**:
- Keep transactions small
- Access rows in consistent order
- Use exponential backoff retry

### Issue: Lock Timeouts

**Cause**: Distributed lock not acquired within timeout

**Solution**:
- Increase `lockTimeout` duration
- Check Redis availability
- Reduce transaction scope

### Issue: PostgreSQL Connection Pool Exhausted

**Cause**: Too many concurrent transactions

**Solution**:
- Reduce concurrent transaction count
- Increase PostgreSQL `max_connections`
- Batch operations where possible

### Issue: Redis Cache Not Updating

**Cause**: Redis connection lost

**Solution**:
- Check Redis availability
- Cache failures are non-fatal (data still in PostgreSQL)
- Restart backend to rebuild cache

## Testing

### Unit Tests

```bash
mvn test -Dtest=MnesiaBackendTest
```

Tests cover:
- Basic CRUD operations
- Transaction isolation
- Schema validation
- Lamport clock ordering

### Integration Tests

```bash
mvn verify -Dit.test=MnesiaBackendIT
```

Tests cover:
- Concurrent access
- Failover scenarios
- Large datasets
- TTL expiration

### Requirements

- PostgreSQL 12+ running on localhost:5432
- Redis 6+ running on localhost:6379
- Java 26 with preview features

## See Also

- [JOTP Reference](./JOTP.md)
- [Erlang Mnesia Documentation](http://erlang.org/doc/man/mnesia.html)
- [PostgreSQL MVCC](https://www.postgresql.org/docs/current/mvcc-intro.html)
- [Redis Patterns](https://redis.io/commands/)

## Summary

The MnesiaBackend brings Erlang's proven distributed database patterns to Java 26 JOTP, enabling:

- **Reliable** storage with ACID transactions
- **Distributed** coordination across nodes
- **Fault-tolerant** recovery from crashes
- **Scalable** replication strategies
- **Battle-tested** patterns from 30+ years of Erlang production experience

Use it to build production-grade, distributed applications in Java with confidence.
