# Redis ETS Backend Integration Guide

## Quick Start

### 1. Basic Setup
```java
// Create backend instance
RedisEtsBackend backend = new RedisEtsBackend(
    "localhost",           // Redis host
    6379,                  // Redis port
    "node-1"               // Local node name
);

// Create tables
backend.createTable("users", EtsTable.TableType.SET);
backend.createTable("events", EtsTable.TableType.BAG);
backend.createTable("metrics", EtsTable.TableType.ORDERED_SET);
```

### 2. CRUD Operations
```java
// Create/Update
backend.put("users", "alice", aliceData);

// Read
List<byte[]> values = backend.get("users", "alice");

// Query with pattern
List<String> allUsers = backend.match("users", "*");

// Delete
backend.delete("users", "alice");

// Clear entire table
backend.clearTable("users");
```

### 3. PersistenceBackend Usage
```java
// Use as persistence layer
PersistenceBackend persistence = backend;

// Save process state
persistence.save("proc-001", stateSnapshot);

// Load on restart
Optional<byte[]> restored = persistence.load("proc-001");

// Atomic state + ACK
persistence.writeAtomic("proc-001", state, ack);
```

## Architecture Integration Points

### 1. DurableState Integration
```java
public class PaymentProcessor {
    private final RedisEtsBackend backend;
    private final DurableState<PaymentState> state;

    public PaymentProcessor(String nodeName) {
        this.backend = new RedisEtsBackend("redis-host", 6379, nodeName);
        this.state = new DurableState<>(
            backend,                    // PersistenceBackend
            "payment-processor-state",  // Key
            PaymentState.initial()      // Initial state
        );
    }

    public void handlePayment(Payment payment) {
        PaymentState current = state.get();
        PaymentState updated = current.process(payment);
        state.persist(updated);  // Saved to Redis
    }
}
```

### 2. EventSourcingAuditLog Integration
```java
public class OrderAggregate {
    private final RedisEtsBackend backend;
    private final EventSourcingAuditLog<OrderEvent> log;

    public OrderAggregate(String nodeId) {
        this.backend = new RedisEtsBackend("redis-host", 6379, nodeId);
        this.log = new EventSourcingAuditLog<>(
            backend,           // PersistenceBackend for snapshots
            "order-123",       // Aggregate ID
            OrderEvent.class   // Event type
        );
    }

    public void addLineItem(LineItem item) {
        log.append(new LineItemAdded(item));
    }
}
```

### 3. Supervisor + ETS Tables
```java
public class DistributedSessionManager {
    private final RedisEtsBackend etsBackend;
    private final Supervisor supervisor;

    public DistributedSessionManager(String nodeName) {
        this.etsBackend = new RedisEtsBackend("redis-host", 6379, nodeName);
        this.etsBackend.createTable("sessions", EtsTable.TableType.SET);
        this.etsBackend.createTable("session-events", EtsTable.TableType.BAG);

        this.supervisor = Supervisor.builder()
            .childSpec(() -> new SessionCleanerProc(etsBackend))
            .strategy(OneForAll.INSTANCE)
            .build();
    }

    public void createSession(String sessionId, byte[] sessionData) {
        etsBackend.put("sessions", sessionId, sessionData);
        etsBackend.put("session-events", sessionId, "created".getBytes());
    }

    public List<byte[]> getSession(String sessionId) {
        return etsBackend.get("sessions", sessionId);
    }

    public List<byte[]> getSessionEvents(String sessionId) {
        return etsBackend.get("session-events", sessionId);
    }
}
```

## Multi-Node Cluster Setup

### Configuration
```properties
# node1.properties
jotp.node.name=node-1
jotp.redis.host=redis-cluster.internal
jotp.redis.port=6379
jotp.redis.ttl=86400

# node2.properties
jotp.node.name=node-2
jotp.redis.host=redis-cluster.internal
jotp.redis.port=6379
jotp.redis.ttl=86400
```

### Bootstrap Code
```java
public class JotpNode {
    public static void main(String[] args) {
        String nodeName = System.getProperty("jotp.node.name");
        String redisHost = System.getProperty("jotp.redis.host");
        int redisPort = Integer.parseInt(System.getProperty("jotp.redis.port"));
        long ttl = Long.parseLong(System.getProperty("jotp.redis.ttl"));

        // Create shared backend
        RedisEtsBackend backend = new RedisEtsBackend(redisHost, redisPort, nodeName, ttl);

        // Create shared tables
        backend.createTable("config", EtsTable.TableType.SET);
        backend.createTable("events", EtsTable.TableType.BAG);

        // Start application with shared backend
        Application app = new JotpApplication(backend);
        app.start();
    }
}
```

## Pattern Usage Examples

### Hierarchical Configuration
```java
// Store config with hierarchical keys
backend.createTable("config", EtsTable.TableType.SET);

backend.put("config", "database:host", "db.example.com".getBytes());
backend.put("config", "database:port", "5432".getBytes());
backend.put("config", "cache:host", "redis.example.com".getBytes());
backend.put("config", "cache:ttl", "3600".getBytes());
backend.put("config", "auth:jwt:secret", "secret123".getBytes());

// Query by prefix
List<String> dbConfigs = backend.match("config", "database:*");
// Returns: ["database:host", "database:port"]

List<String> allConfigs = backend.match("config", "*");
// Returns all config keys
```

### Event Log with Timestamps
```java
// Store events with hierarchical keys including timestamps
backend.createTable("audit-log", EtsTable.TableType.BAG);

long timestamp = System.currentTimeMillis();
String eventKey = String.format("user:%s:%d", userId, timestamp);

backend.put("audit-log", eventKey, "login".getBytes());
backend.put("audit-log", eventKey, "accessed-profile".getBytes());

// Query events for a user
List<byte[]> userEvents = backend.get("audit-log", eventKey);
```

### Session Management
```java
// Store active sessions with TTL
backend.createTable("sessions", EtsTable.TableType.SET);

String sessionKey = "session:" + sessionId;
SessionData session = new SessionData(userId, ip, timestamp);
backend.put("sessions", sessionKey, serialize(session));

// Query active sessions for a user
List<String> activeSessions = backend.match("sessions", "session:user-123:*");

// Sessions automatically expire after TTL
```

### Distributed Cache
```java
// Use as distributed cache layer
backend.createTable("cache", EtsTable.TableType.SET);

// Store with composite key
String cacheKey = "cache:" + queryHash + ":" + version;
backend.put("cache", cacheKey, resultBytes);

// Retrieve
List<byte[]> cachedResult = backend.get("cache", cacheKey);

// Invalidate by pattern
backend.match("cache", "cache:" + queryHash + ":*").forEach(key ->
    backend.delete("cache", key)
);
```

## Monitoring and Observability

### Table Statistics
```java
// Monitor table growth
EtsTable.TableStats stats = backend.stats("users");
System.out.println("User count: " + stats.objectCount());
System.out.println("Age: " + stats.ageMillis() + "ms");

// Monitor all tables
for (String tableName : backend.listTables()) {
    EtsTable.TableStats ts = backend.stats(tableName);
    System.out.println(tableName + ": " + ts.objectCount() + " objects");
}
```

### Subscribe to Changes
```java
// Monitor table changes
backend.subscribeTable("critical-data", event -> {
    System.out.println("Change: " + event.type() + " on " + event.key()
        + " from " + event.originNode()
        + " at " + new Date(event.timestamp()));

    // Can trigger side effects: logging, metrics, webhooks
    monitoringService.recordChange(event);
});
```

## Troubleshooting

### Common Issues

**Issue: All reads return empty**
```
Cause: Table not created before use
Solution: Ensure createTable() called before put()/get()

backend.createTable("users", EtsTable.TableType.SET);
backend.put("users", "key", data);  // Now works
```

**Issue: Pattern matching returns no results**
```
Cause: Keys don't match pattern format
Solution: Verify key format matches pattern

// Keys: "app:db:host", "app:db:port"
List<String> matches = backend.match("config", "app:db:*");  // Works

// Keys: "app_db_host", "app_db_port"
List<String> matches = backend.match("config", "app:db:*");  // Empty, need "app_db_*"
```

**Issue: PersistenceException on every operation**
```
Cause: Backend closed or Redis unavailable
Solution: Check backend lifecycle and Redis connectivity

// Check Redis connection
try (Jedis jedis = new Jedis("host", 6379)) {
    String pong = jedis.ping();
    System.out.println(pong);  // Should print "PONG"
}

// Ensure backend not closed
if (backend != null) {
    backend.put(...);  // Works
}
```

### Redis Debugging
```bash
# Check Redis is running
redis-cli ping
# Should return: PONG

# Inspect tables
redis-cli KEYS "jotp:ets:*"

# Monitor operations
redis-cli MONITOR

# Check memory usage
redis-cli INFO memory

# Clear test data
redis-cli FLUSHDB
```

## Performance Tips

### 1. Use SET for Unique Keys
```java
// Efficient: SET type for O(1) lookup
backend.createTable("users", EtsTable.TableType.SET);
backend.put("users", "alice", data);  // O(1)

// Less efficient: BAG type for unique keys
backend.createTable("users", EtsTable.TableType.BAG);
backend.put("users", "alice", data);  // O(N)
```

### 2. Batch Updates
```java
// Less efficient: Individual writes
for (User user : users) {
    backend.put("users", user.id(), serialize(user));  // Each hits Redis
}

// More efficient: Batch in app, then flush
Map<String, byte[]> batch = users.stream()
    .collect(Collectors.toMap(User::id, u -> serialize(u)));
batch.forEach((id, data) -> backend.put("users", id, data));
```

### 3. Use Hierarchical Keys
```java
// Efficient querying with patterns
backend.put("config", "db:host", "localhost".getBytes());
backend.put("config", "db:port", "5432".getBytes());
backend.put("config", "cache:host", "redis".getBytes());

List<String> dbConfig = backend.match("config", "db:*");  // Fast pattern query
```

### 4. Configure Redis Cluster
```java
// For high availability and load distribution
RedisEtsBackend backend = new RedisEtsBackend(
    "redis-cluster-node-1.internal",  // Primary node
    6379,
    "node-1"
);
// Backend connects through cluster node, automatically routes to shards
```

## Testing

### Unit Testing with Mock Redis
```java
@Test
void testSessionCreation() {
    try (RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "test")) {
        backend.createTable("sessions", EtsTable.TableType.SET);

        backend.put("sessions", "sess-1", "data".getBytes());
        List<byte[]> result = backend.get("sessions", "sess-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo("data".getBytes());
    }
}
```

### Integration Testing
```java
@Test
@DisplayName("Multi-node consistency")
void testMultiNodeConsistency() throws Exception {
    RedisEtsBackend node1 = new RedisEtsBackend("redis", 6379, "node-1");
    RedisEtsBackend node2 = new RedisEtsBackend("redis", 6379, "node-2");

    node1.createTable("shared", EtsTable.TableType.SET);
    node2.createTable("shared", EtsTable.TableType.SET);

    node1.put("shared", "key-1", "value-1".getBytes());

    List<byte[]> fromNode2 = node2.get("shared", "key-1");
    assertThat(fromNode2).isNotEmpty();
}
```

## References

- Source: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackend.java`
- Example: `/home/user/jotp/src/main/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackendExample.java`
- Tests: `/home/user/jotp/src/test/java/io/github/seanchatmangpt/jotp/distributed/RedisEtsBackendTest.java`
- Docs: `/home/user/jotp/docs/REDIS-ETS-BACKEND.md`
