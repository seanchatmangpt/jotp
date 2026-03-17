# Redis & PostgreSQL Backend Integration
## Scaling JOTP Beyond Single-Node

This guide explains how to integrate Redis and PostgreSQL backends into your JOTP cluster, following **Joe Armstrong's fault-tolerance principles** from Erlang/OTP.

---

## 📚 Quick Start

### 1. Persistence Backend (State Storage)

**Use Redis for distributed caching:**
```java
// Fast, distributed, in-memory state
PersistenceBackend backend = new RedisBackend("localhost", 6379, "jotp-cluster");

// Save process state
backend.save("counter-001", serializedState);

// Load on recovery
Optional<byte[]> recovered = backend.load("counter-001");
```

**Use PostgreSQL for durable audit logs:**
```java
// ACID-compliant, queryable state
PostgresBackend backend = new PostgresBackend("localhost", 5432, "jotp_prod", "jotp", "secret");
backend.initializeTables();

// Store state atomically with ACK
backend.writeAtomic("payment-txn-42", stateBytes, ackBytes);

// Query event history
List<byte[]> events = backend.getEvents("payment-txn-42", sinceSequenceNumber);
```

### 2. Global Registry Backend (Process Discovery)

**Use Redis for fast node-local lookups:**
```java
GlobalRegistryBackend registry = new RedisGlobalRegistryBackend("localhost", 6379);

// Register process across cluster
GlobalProcRef ref = new GlobalProcRef("node-1", processId, sequenceNumber);
registry.store("payment-service", ref);

// Lookup from any node
Optional<GlobalProcRef> found = registry.lookup("payment-service");

// Watch for changes
registry.watch(event -> {
    if (event instanceof RegistryEvent.Registered r) {
        System.out.println("Service joined: " + r.name());
    }
});
```

**Use PostgreSQL for consistent, queryable registry:**
```java
PostgresGlobalRegistryBackend registry = new PostgresGlobalRegistryBackend(
    "localhost", 5432, "jotp_registry");
registry.initializeTables();

// All writes go to primary (consistent)
registry.store("order-service", ref);

// Get all services on a node
List<String> nodeServices = registry.listAll().entrySet()
    .stream()
    .filter(e -> e.getValue().nodeName().equals("node-2"))
    .map(Map.Entry::getKey)
    .toList();
```

---

## 🏗️ Architecture

### Single-Node (Development)
```
[JOTP Process]
    ↓
[InMemoryBackend / RocksDBBackend]
    ↓
[Local Disk or RAM]
```

### Multi-Node Cluster (Production)
```
Node 1          Node 2          Node 3
┌─────────┐    ┌─────────┐    ┌─────────┐
│ Proc⟨S⟩ │    │ Proc⟨S⟩ │    │ Proc⟨S⟩ │
│ Registry│    │ Registry│    │ Registry│
└────┬────┘    └────┬────┘    └────┬────┘
     │              │              │
     └──────────────┼──────────────┘
                    ↓
        ┌───────────────────────┐
        │  Redis Cluster        │
        │  ├── Master (7000)    │
        │  ├── Master (7001)    │
        │  ├── Master (7002)    │
        │  ├── Replica (7003)   │
        │  ├── Replica (7004)   │
        │  └── Replica (7005)   │
        └───────────┬───────────┘
                    ↓
        ┌───────────────────────┐
        │  PostgreSQL           │
        │  ├── Primary (main)   │
        │  ├── Hot Standby      │
        │  └── Cold Backup      │
        └───────────────────────┘
```

---

## 💾 Persistence Backends

### RedisBackend

**Best for:** Distributed caching, session storage, fast recovery

| Operation | Latency | Throughput | Notes |
|-----------|---------|-----------|-------|
| save() | ~5-10ms | >10K ops/sec | Network included |
| load() | ~3-5ms | >10K ops/sec | Cached in Redis |
| writeAtomic() | ~8-15ms | >5K ops/sec | Pipeline batch |

**Features:**
- ✅ Distributed across cluster
- ✅ Automatic replication
- ✅ TTL-based expiration
- ✅ Pub/Sub for notifications
- ❌ No durability (RDB/AOF optional)
- ❌ Limited to available RAM

**Configuration:**
```java
RedisBackend backend = new RedisBackend(
    host,           // "redis-cluster.internal"
    port,           // 6379 or 7000 (cluster)
    keyspace,       // "jotp-prod"
    ttlSeconds      // 86400 (24 hours)
);

// Monitor server
String info = backend.getServerInfo();
System.out.println(info);

// Set expiration
backend.setExpiry("counter-001", 3600);

// Check TTL
long ttl = backend.getTimeToLive("counter-001");
```

### PostgresBackend

**Best for:** Event sourcing, audit logs, complex queries, ACID compliance

| Operation | Latency | Throughput | Notes |
|-----------|---------|-----------|-------|
| save() | ~10-20ms | >1K ops/sec | ACID transaction |
| load() | ~5-10ms | >5K ops/sec | B-tree lookup |
| writeAtomic() | ~15-30ms | >500 ops/sec | Full transaction |
| appendEvent() | ~10-20ms | >1K ops/sec | Append-only |

**Features:**
- ✅ ACID transactions
- ✅ Event sourcing support
- ✅ Complex SQL queries
- ✅ Replication & failover
- ✅ Full audit trail
- ❌ Slower than Redis
- ❌ Network roundtrip per operation

**Configuration:**
```java
PostgresBackend backend = new PostgresBackend(
    host,           // "postgres-primary.internal"
    port,           // 5432
    database,       // "jotp_prod"
    user,           // "jotp"
    password        // from secrets manager
);

// Initialize schema
backend.initializeTables();

// Event sourcing
backend.appendEvent(
    "order-42",           // entity key
    eventData,            // serialized event
    "OrderCreated",       // event type
    1L                    // sequence number
);

// Replay events for recovery
List<byte[]> events = backend.getEvents("order-42", 0);
for (byte[] event : events) {
    state = applyEvent(state, event);
}
```

---

## 🔍 Global Registry Backends

### RedisGlobalRegistryBackend

**Register process globally:**
```java
RedisGlobalRegistryBackend registry = new RedisGlobalRegistryBackend(
    "redis-cluster.internal", 6379);

// Each node registers its services
GlobalProcRef ref = new GlobalProcRef(
    "node-1",      // this node
    12345,         // process ID
    100            // sequence number
);
registry.store("payment-service", ref);

// Other nodes can find it
Optional<GlobalProcRef> found = registry.lookup("payment-service");
if (found.isPresent()) {
    GlobalProcRef ref = found.get();
    // Connect to node-1:port to reach payment-service
}
```

**Watch for changes:**
```java
registry.watch(event -> {
    switch (event) {
        case RegistryEvent.Registered(var name, var ref) -> {
            System.out.println("Process joined: " + name + " on " + ref.nodeName());
            // Update load balancer
        }
        case RegistryEvent.Unregistered(var name) -> {
            System.out.println("Process left: " + name);
            // Remove from routing
        }
    }
});
```

**Cleanup failed nodes:**
```java
// When health checks detect node failure
registry.cleanupNode("dead-node-2");

// All entries for dead-node-2 removed
// All watchers notified of removals
// Automatic TTL cleanup (5 minute default)
```

### PostgresGlobalRegistryBackend

**Durable registry with complex queries:**
```java
PostgresGlobalRegistryBackend registry = new PostgresGlobalRegistryBackend(
    "postgres-primary.internal", 5432, "jotp_registry");
registry.initializeTables();

// Store with audit log
registry.store("order-service-v2", ref);

// Find all services on a node
var all = registry.listAll();
var nodeServices = all.entrySet()
    .stream()
    .filter(e -> e.getValue().nodeName().equals("node-1"))
    .collect(Collectors.toList());

// Query via SQL (directly)
String query = "SELECT * FROM jotp_global_registry WHERE node_name = ?";
// Run custom SQL analysis
```

**Atomic compare-and-swap:**
```java
// Safely transition service ownership
Optional<GlobalProcRef> current = registry.lookup("master-service");

if (current.isPresent() && current.get().nodeName().equals("old-node")) {
    GlobalProcRef newRef = new GlobalProcRef("new-node", newProcId, seqNum);

    boolean success = registry.compareAndSwap("master-service", current, newRef);

    if (success) {
        System.out.println("Ownership transferred");
    } else {
        System.out.println("Another node beat us to it");
    }
}
```

---

## 🎯 Joe Armstrong's Principles Applied

### 1. **Let It Crash**
```java
// If Redis connection dies, let the Supervisor restart the actor
try {
    backend.save(key, state);
} catch (PersistenceException e) {
    // Throw, let supervisor handle it
    throw new RuntimeException("Storage failed, supervisor will restart", e);
}
```

### 2. **Supervision Trees**
```java
// Supervisor monitors processes AND their storage
var spec = new Supervisor.ChildSpec(
    "storage-watcher",
    () -> new StorageHealthChecker(redisBackend),
    Supervisor.RestartStrategy.ONE_FOR_ONE
);

supervisor.startChild(spec);
```

### 3. **Message Passing**
```java
// Store state in Redis, retrieve on demand
sealed interface StorageMsg {
    record Save(String key, byte[] data) implements StorageMsg {}
    record Load(String key, ProcRef<?, ?> replyCh) implements StorageMsg {}
}

// Handler
StorageMsg msg = mailbox.receive();
switch (msg) {
    case StorageMsg.Save(var key, var data) ->
        redisBackend.save(key, data);
    case StorageMsg.Load(var key, var replyCh) -> {
        Optional<byte[]> data = redisBackend.load(key);
        replyCh.send(data);
    }
}
```

### 4. **Distributed Clustering**
```java
// Registry is shared across all nodes
registry.watch(event -> {
    // Any node can react to service discovery changes
    if (event instanceof RegistryEvent.Registered r) {
        connectToRemoteService(r.ref());
    }
});
```

### 5. **Fault Tolerance**
```java
// Atomic writes prevent partial failures
backend.writeAtomic(
    "transaction-123",
    stateBytes,      // Full transaction state
    ackBytes         // Mark as processed
);

// On crash, system knows exactly what to replay
Optional<Long> ack = backend.getAckSequence("transaction-123");
if (ack.isPresent() && ack.get() >= 5) {
    // Messages 1-5 already processed
    startFrom(6);  // Skip duplicates
}
```

---

## 🚀 Production Deployment

### Redis Deployment

**1. Use Redis Cluster (not Sentinel):**
```bash
# docker-compose-redis-cluster.yml
version: '3.8'
services:
  redis-cluster:
    image: redis:7-alpine
    ports:
      - "7000-7005:7000-7005"
    volumes:
      - ./redis-cluster.conf:/redis-cluster.conf:ro
    command: redis-server /redis-cluster.conf
```

**2. Enable persistence:**
```conf
# redis.conf
appendonly yes              # AOF persistence
appendfsync everysec       # Fsync every second
maxmemory 2gb              # Limit memory
maxmemory-policy allkeys-lru  # Evict LRU
```

**3. Monitor:**
```java
// Health check
String info = backend.getServerInfo();
if (info.contains("role:master")) {
    System.out.println("Connected to primary");
} else if (info.contains("role:slave")) {
    System.out.println("Connected to replica");
}
```

### PostgreSQL Deployment

**1. Setup streaming replication:**
```bash
# On primary
pg_basebackup -h primary.internal -U replication -D /data -Pv -W

# On standby (recovery.conf or postgresql.auto.conf)
standby_mode = 'on'
primary_conninfo = 'host=primary.internal port=5432 user=replication password=secret'
```

**2. Enable archiving for PITR:**
```conf
# postgresql.conf
wal_level = replica
max_wal_senders = 3
wal_keep_size = 1GB
archive_mode = on
archive_command = 'cp %p /archive/%f'
```

**3. Monitor replication lag:**
```sql
-- Check replication status
SELECT slot_name, active, restart_lsn, confirmed_flush_lsn FROM pg_replication_slots;

-- Check transaction age
SELECT now() - pg_postmaster_start_time() AS uptime;
```

---

## 📊 Choosing a Backend

| Requirement | Redis | PostgreSQL | RocksDB |
|-------------|-------|-----------|---------|
| **Distributed** | ✅ Yes | ✅ Yes | ❌ Local only |
| **Durable** | ⚠️ Optional | ✅ ACID | ✅ Yes |
| **Fast** | ✅ Sub-10ms | ⚠️ 10-30ms | ✅ <1ms |
| **Queryable** | ⚠️ Limited | ✅ Full SQL | ❌ Key-value |
| **Event Sourcing** | ❌ No | ✅ Yes | ⚠️ Possible |
| **Cost** | 💰 Medium | 💰 Low (OSS) | 💰 Free |

**Recommendation Matrix:**

```
Single Node Dev?           → Use RocksDBBackend
Distributed Caching?       → Use RedisBackend
Audit Trail / Compliance?  → Use PostgresBackend
High Traffic Events?       → Use Redis + Postgres
```

---

## 🔗 Integration with Supervisor

```java
// Supervisor that manages storage and recovery
public class ResilientStorageSupervisor extends Supervisor {

    static final String REDIS_BACKEND = "redis-backend";
    static final String POSTGRES_BACKEND = "postgres-backend";
    static final String RECOVERY_WORKER = "recovery-worker";

    public void init() {
        // Redis for speed (replicated)
        startChild(new ChildSpec(
            REDIS_BACKEND,
            () -> new RedisStorageActor(new RedisBackend(...)),
            RestartStrategy.ONE_FOR_ONE
        ));

        // Postgres for durability (primary + replicas)
        startChild(new ChildSpec(
            POSTGRES_BACKEND,
            () -> new PostgresStorageActor(new PostgresBackend(...)),
            RestartStrategy.ONE_FOR_ONE
        ));

        // Recovery worker scans for incomplete transactions
        startChild(new ChildSpec(
            RECOVERY_WORKER,
            this::createRecoveryWorker,
            RestartStrategy.TRANSIENT
        ));
    }
}
```

---

## 📚 Testing

### Unit Tests
```java
@Test
void testRedisAtomicWrite() throws Exception {
    RedisBackend backend = new RedisBackend("localhost", 6379, "test");

    backend.writeAtomic("tx-1", stateBytes, ackBytes);
    Optional<Long> ack = backend.getAckSequence("tx-1");

    assertEquals(Optional.of(100L), ack);
    backend.close();
}

@Test
void testPostgresTransactionIsolation() throws Exception {
    PostgresBackend backend = new PostgresBackend("localhost", 5432, "test");
    backend.initializeTables();

    // Compare-and-swap should not race
    GlobalProcRef old = new GlobalProcRef("node-1", 1, 1);
    GlobalProcRef neu = new GlobalProcRef("node-2", 2, 2);

    assertTrue(registry.compareAndSwap("service", Optional.of(old), neu));
    assertFalse(registry.compareAndSwap("service", Optional.of(old), neu));
}
```

### Integration Tests
```java
@Test
void testClusterFailoverConsistency() throws Exception {
    // Start 3 Redis nodes
    // Kill master
    // Verify all processes still reachable via slaves
    // Verify no state lost (AOF persistence)
}

@Test
void testPostgresReplicationLag() throws Exception {
    // Write to primary
    // Verify appears on standby within SLA
    // Failover to standby
    // Verify old primary catches up
}
```

---

## 🐛 Troubleshooting

### Redis Connection Issues
```java
// Test connectivity
try (Jedis jedis = new Jedis("localhost", 6379)) {
    String pong = jedis.ping();
    System.out.println(pong);  // Should print "PONG"
} catch (Exception e) {
    System.err.println("Redis unreachable: " + e.getMessage());
}

// Check pool status
System.out.println("Active connections: " + pool.getNumActive());
System.out.println("Idle connections: " + pool.getNumIdle());
```

### PostgreSQL Connection Issues
```java
// Test connectivity
try (Connection conn = dataSource.getConnection()) {
    try (Statement stmt = conn.createStatement()) {
        ResultSet rs = stmt.executeQuery("SELECT version()");
        if (rs.next()) {
            System.out.println("Connected: " + rs.getString(1));
        }
    }
} catch (SQLException e) {
    System.err.println("PostgreSQL unreachable: " + e.getMessage());
}
```

### State Corruption Recovery
```java
// PostgreSQL: Verify consistency
Optional<Long> ack = backend.getAckSequence("entity-123");
Optional<byte[]> state = backend.load("entity-123");

if (ack.isPresent() && !state.isPresent()) {
    System.err.println("ACK without state: database corruption!");
    // Fix: delete orphaned ACK
    backend.deleteAck("entity-123");
}
```

---

## 📖 References

- **Joe Armstrong - Erlang/OTP principles:** [Make reliable systems](http://joearms.github.io/)
- **Redis Documentation:** https://redis.io/docs
- **PostgreSQL Documentation:** https://www.postgresql.org/docs
- **JOTP GitHub:** https://github.com/seanchatmangpt/jotp
- **Erlang Distribution Protocol:** https://www.erlang.org/doc

---

**Last Updated:** 2026-03-17
**JOTP Version:** 2026.1.0
**Joe Armstrong Philosophy:** "Let it crash and be proven correct"
