# Distributed Locks Pattern

## Problem

In a distributed system with multiple instances, you need to coordinate access to shared resources (database rows, files, external APIs). Traditional JVM locks don't work across processes/machines, leading to race conditions and data corruption.

**Symptoms:**
- Concurrent modifications to shared resources
- Duplicate processing of the same task
- Race conditions in multi-instance deployments
- Inconsistent state across nodes

## Solution

Implement distributed locks to coordinate access to shared resources across multiple processes. JOTP provides a lock-free approach using ProcRegistry for process coordination, and can integrate with Redis for cross-instance locking.

**Approaches:**
1. **Process-based coordination**: Use JOTP's ProcRegistry for single-JVM coordination
2. **Redis-based locking**: Use Redis for multi-instance coordination
3. **Database locking**: Use database row-level locks for persistence

## Prerequisites

- Java 26 with preview features enabled
- JOTP core module: `io.github.seanchatmangpt.jotp`
- For multi-instance: Redis or similar distributed store
- Understanding of lock safety properties

## Implementation

### Approach 1: Process-Based Coordination (Single JVM)

```java
import io.github.seanchatmangpt.jotp.*;
import java.util.UUID;

public class LockCoordinator {
    private final ProcRegistry registry;
    private final Map<String, ProcRef<?, ?>> lockHolders = new ConcurrentHashMap<>();

    public LockCoordinator() {
        this.registry = ProcRegistry.create();
    }

    public boolean acquireLock(String lockName, Duration timeout) {
        // Try to register a process as the lock holder
        String processName = "lock-" + lockName;

        try {
            ProcRef<LockState, LockMsg> lockProc = Proc.spawn(
                new LockState(LockState.Status.LOCKED, UUID.randomUUID()),
                (state, msg) -> state  // Keep state
            );

            boolean registered = registry.register(processName, lockProc);

            if (registered) {
                lockHolders.put(lockName, lockProc);
                return true;
            } else {
                lockProc.shutdown();
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void releaseLock(String lockName) {
        ProcRef<?, ?> lockProc = lockHolders.remove(lockName);
        if (lockProc != null) {
            registry.unregister("lock-" + lockName);
            lockProc.shutdown();
        }
    }

    public boolean isLocked(String lockName) {
        return registry.whereis("lock-" + lockName).isPresent();
    }

    private record LockState(Status status, UUID holderId) {
        enum Status { LOCKED, RELEASED }
    }

    private sealed interface LockMsg {}
}
```

### Approach 2: Redis-Based Locking (Multi-Instance)

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.time.Duration;

public class RedisDistributedLock {
    private final JedisPool redisPool;
    private final String lockPrefix = "lock:";

    public RedisDistributedLock(JedisPool redisPool) {
        this.redisPool = redisPool;
    }

    /**
     * Acquire a lock with automatic expiration.
     *
     * @param lockName Name of the lock
     * @param holderId Unique identifier for the lock holder
     * @param ttl Time-to-live for the lock (auto-expires)
     * @return true if lock acquired, false otherwise
     */
    public boolean acquire(String lockName, String holderId, Duration ttl) {
        String key = lockPrefix + lockName;

        try (Jedis redis = redisPool.getResource()) {
            // SET NX: Set if not exists
            String result = redis.set(key, holderId, SetParams.setParams().nx().px(ttl.toMillis()));
            return "OK".equals(result);
        }
    }

    /**
     * Release a lock (only if holder matches).
     *
     * @param lockName Name of the lock
     * @param holderId Unique identifier for the lock holder
     * @return true if lock released, false otherwise
     */
    public boolean release(String lockName, String holderId) {
        String key = lockPrefix + lockName;

        try (Jedis redis = redisPool.getResource()) {
            // Lua script ensures atomic check-and-delete
            String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    return redis.call('del', KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end";

            Object result = redis.eval(script, List.of(key), List.of(holderId));
            return (Long) result == 1;
        }
    }

    /**
     * Extend lock TTL (prevent expiration).
     *
     * @param lockName Name of the lock
     * @param holderId Unique identifier for the lock holder
     * @param additionalTtl Additional time to add
     * @return true if lock extended, false otherwise
     */
    public boolean extend(String lockName, String holderId, Duration additionalTtl) {
        String key = lockPrefix + lockName;

        try (Jedis redis = redisPool.getResource()) {
            // Lua script ensures atomic check-and-expire
            String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    return redis.call('pexpire', KEYS[1], ARGV[2]) " +
                "else " +
                "    return 0 " +
                "end";

            Object result = redis.eval(script, List.of(key),
                List.of(holderId, String.valueOf(additionalTtl.toMillis())));
            return (Long) result == 1;
        }
    }

    /**
     * Check if lock is held.
     */
    public boolean isLocked(String lockName) {
        String key = lockPrefix + lockName;

        try (Jedis redis = redisPool.getResource()) {
            return redis.exists(key) > 0;
        }
    }
}
```

### Approach 3: Database Row-Level Locking

```java
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DatabaseDistributedLock {
    private final DataSource dataSource;

    public DatabaseDistributedLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean acquire(String lockName, String holderId, Duration ttl) {
        String sql =
            "INSERT INTO distributed_locks (lock_name, holder_id, expires_at) " +
            "VALUES (?, ?, ?) " +
            "ON CONFLICT (lock_name) DO NOTHING";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, lockName);
            stmt.setString(2, holderId);
            stmt.setTimestamp(3, Timestamp.from(Instant.now().plus(ttl)));

            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean release(String lockName, String holderId) {
        String sql =
            "DELETE FROM distributed_locks " +
            "WHERE lock_name = ? AND holder_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, lockName);
            stmt.setString(2, holderId);

            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }
}
```

## Complete Examples

### Example 1: Preventing Duplicate Task Execution

```java
public class TaskProcessor {
    private final RedisDistributedLock lock;
    private final TaskRepository taskRepository;

    public TaskProcessor(JedisPool redisPool) {
        this.lock = new RedisDistributedLock(redisPool);
        this.taskRepository = new TaskRepository();
    }

    public void processTask(String taskId) {
        String holderId = UUID.randomUUID().toString();
        String lockName = "task-" + taskId;

        // Try to acquire lock
        if (!lock.acquire(lockName, holderId, Duration.ofMinutes(5))) {
            System.out.println("Task " + taskId + " is already being processed");
            return;
        }

        try {
            // Process the task
            Task task = taskRepository.get(taskId);
            task.execute();
            taskRepository.save(task);

            System.out.println("Task " + taskId + " completed");
        } catch (Exception e) {
            System.err.println("Task " + taskId + " failed: " + e.getMessage());
        } finally {
            // Always release the lock
            lock.release(lockName, holderId);
        }
    }
}
```

### Example 2: Coordinating Access to Shared Resource

```java
public class SharedResourceManager {
    private final RedisDistributedLock lock;

    public SharedResourceManager(JedisPool redisPool) {
        this.lock = new RedisDistributedLock(redisPool);
    }

    public void updateSharedResource(String resourceId, ResourceUpdate update) {
        String holderId = UUID.randomUUID().toString();
        String lockName = "resource-" + resourceId;

        // Acquire lock with auto-renewal
        if (!lock.acquire(lockName, holderId, Duration.ofSeconds(30))) {
            throw new ResourceLockedException("Resource " + resourceId + " is locked");
        }

        // Background thread to extend lock TTL
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> renewTask = renewer.scheduleAtFixedRate(() -> {
            lock.extend(lockName, holderId, Duration.ofSeconds(30));
        }, 10, 10, TimeUnit.SECONDS);

        try {
            // Perform the update
            Resource resource = resourceRepository.get(resourceId);
            resource.apply(update);
            resourceRepository.save(resource);

        } finally {
            // Stop renewal and release lock
            renewTask.cancel(true);
            renewer.shutdown();
            lock.release(lockName, holderId);
        }
    }
}
```

### Example 3: Leasing with JOTP Processes

```java
public class LeaseManager {
    private final ProcRegistry registry;

    public LeaseManager() {
        this.registry = ProcRegistry.create();
    }

    public Optional<Lease> acquireLease(String resourceName, Duration ttl) {
        String leaseName = "lease-" + resourceName;
        String holderId = UUID.randomUUID().toString();

        // Create a process to hold the lease
        ProcRef<LeaseState, LeaseMsg> leaseProc = Proc.spawn(
            new LeaseState(holderId, Instant.now().plus(ttl)),
            (state, msg) -> handleLeaseMessage(state, msg)
        );

        // Try to register the lease
        if (registry.register(leaseName, leaseProc)) {
            return Optional.of(new Lease(leaseName, holderId, leaseProc, ttl));
        } else {
            leaseProc.shutdown();
            return Optional.empty();
        }
    }

    private LeaseState handleLeaseMessage(LeaseState state, LeaseMsg msg) {
        return switch (msg) {
            case LeaseMsg.Renew(Duration additionalTtl) -> {
                yield new LeaseState(state.holderId(), Instant.now().plus(additionalTtl));
            }
            case LeaseMsg.Release _ -> state;
        };
    }

    public record Lease(
        String leaseName,
        String holderId,
        ProcRef<LeaseState, LeaseMsg> process,
        Duration ttl
    ) {
        public void renew(Duration additionalTtl) {
            process.tell(new LeaseMsg.Renew(additionalTtl));
        }

        public void release() {
            registry.unregister(leaseName);
            process.shutdown();
        }
    }

    private record LeaseState(String holderId, Instant expiresAt) {}

    private sealed interface LeaseMsg {
        record Renew(Duration additionalTtl) implements LeaseMsg {}
        record Release() implements LeaseMsg {}
    }
}
```

## Configuration Guidelines

### Lock TTL

```java
// Short TTL (fast operations)
Duration.ofSeconds(10);

// Moderate TTL (standard operations)
Duration.ofMinutes(5);

// Long TTL (long-running operations)
Duration.ofHours(1);
```

### Lock Naming

```java
// GOOD: Hierarchical lock names
"resource:type:id"  // "order:payment:12345"
"feature:action:id" // "inventory:reserve:item-67890"

// BAD: Flat lock names (risk of collision)
"lock12345"
```

### Holder ID Generation

```java
// GOOD: UUID per lock attempt
String holderId = UUID.randomUUID().toString();

// GOOD: Include instance info
String holderId = hostname + "-" + UUID.randomUUID();

// BAD: Reusable holder IDs (risk of confusion)
String holderId = "instance-1";  // Same ID for all locks
```

## Performance Considerations

### Memory
- **Process-based**: O(locks) × proc overhead (~1 KB each)
- **Redis-based**: O(locks) × key size (~100 bytes each)
- **Database-based**: O(locks) × row size (~1 KB each)

### Latency
- **Process-based**: ~1 μs (in-memory)
- **Redis-based**: ~1 ms (network round-trip)
- **Database-based**: ~10 ms (disk I/O)

### Throughput
- **Process-based**: 10M+ acquires/second
- **Redis-based**: 100K+ acquires/second
- **Database-based**: 10K+ acquires/second

### Deadlock Prevention

```java
// GOOD: Always acquire locks in same order
public void transfer(String fromAccount, String toAccount) {
    String first = fromAccount.compareTo(toAccount) < 0 ? fromAccount : toAccount;
    String second = fromAccount.compareTo(toAccount) < 0 ? toAccount : fromAccount;

    lock.acquire(first, holderId, ttl);
    lock.acquire(second, holderId, ttl);

    try {
        // Perform transfer
    } finally {
        lock.release(second, holderId);
        lock.release(first, holderId);
    }
}

// BAD: Inconsistent lock ordering (risk of deadlock)
public void transferBad(String fromAccount, String toAccount) {
    lock.acquire(fromAccount, holderId, ttl);  // May deadlock
    lock.acquire(toAccount, holderId, ttl);
}
```

## Monitoring

### Key Metrics

```java
record DistributedLockMetrics(
    String lockName,
    long totalAcquisitions,      // Total acquire attempts
    long successfulAcquisitions, // Successful acquires
    long failedAcquisitions,     // Failed acquires
    double acquisitionRate,      // Acquires per second
    Duration avgHoldTime,        // Average lock hold time
    Duration avgWaitTime,        // Average wait time to acquire
    long expiredLocks,           // Locks that expired
    Map<String, Long> locksByHolder  // Locks per holder
) {}
```

### Alerting

```java
// Alert on high contention
if (acquisitionFailureRate > 20%) {
    alertService.send(AlertPriority.HIGH,
        "High lock contention: " + lockName);
}

// Alert on long-held locks
if (holdTime > SLA.threshold) {
    alertService.send(AlertPriority.MEDIUM,
        "Long-held lock: " + lockName);
}

// Alert on expired locks
if (expiredLocks > threshold) {
    alertService.send(AlertPriority.LOW,
        "Lock expiring frequently: " + lockName);
}
```

## Common Pitfalls

### 1. Forgetting to Release Locks

```java
// BAD: Lock never released (resource leak)
if (lock.acquire(name, holderId, ttl)) {
    doWork();
    // Forgot to release!
}

// GOOD: Always release in finally block
if (lock.acquire(name, holderId, ttl)) {
    try {
        doWork();
    } finally {
        lock.release(name, holderId);
    }
}
```

### 2. Locks Too Short

```java
// BAD: Lock expires before work completes
lock.acquire(name, holderId, Duration.ofSeconds(1));
doSlowWork();  // Takes 5 seconds, lock expires

// GOOD: Lock TTL covers work duration
lock.acquire(name, holderId, Duration.ofMinutes(5));
doSlowWork();
```

### 3. Deadlocks from Lock Ordering

```java
// BAD: Inconsistent lock ordering (deadlock risk)
lock.acquire("A", holder1, ttl);
lock.acquire("B", holder2, ttl);  // Different order = deadlock

// GOOD: Consistent lock ordering
List<String> locks = Arrays.asList("A", "B").sorted();
for (String lockName : locks) {
    lock.acquire(lockName, holderId, ttl);
}
```

## Related Guides

- **[Saga Transactions](./saga-transactions.md)** - Coordinate with sagas
- **[Rate Limiting](./rate-limiting.md)** - Control resource access
- **[Event Sourcing](./event-sourcing.md)** - Prevent conflicts with events

## References

- **ProcRegistry**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/ProcRegistry.java`
- **Redis**: https://redis.io/docs/manual/patterns/distributed-locks/
- **Examples**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/DistributedPaymentProcessing.java`
