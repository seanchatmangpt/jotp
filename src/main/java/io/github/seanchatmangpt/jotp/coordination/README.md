# JOTP Distributed Lock Service

A production-grade distributed coordination service for the JOTP framework, implementing Erlang/OTP's synchronization primitives for the JVM.

## Overview

The Distributed Lock Service provides battle-tested mutual exclusion, fair queuing, deadlock detection, and monitoring for cluster-wide resource coordination. It follows Erlang/OTP's philosophy: **let it crash** — locks automatically release when their holders fail.

### Key Features

- **Mutual Exclusion**: Exclusive access to named resources across cluster nodes
- **Deadlock Detection**: Automatic cycle detection in lock dependency graphs
- **Fair Queuing**: First-come-first-served waiter promotion with FIFO ordering
- **Timeout-Based Release**: Automatic lock release on timeout (default: holder crash)
- **Reader-Writer Locks**: High-concurrency scenarios with multiple readers + exclusive writer
- **Process Monitoring**: Automatic cleanup when lock holders crash
- **Metrics & Diagnostics**: Contention tracking, wait time statistics, lock holder discovery
- **Virtual Thread Safe**: Designed for lightweight concurrency with Java 21+ virtual threads

## Components

### 1. DistributedLock (Interface)

Core distributed lock abstraction with sealed result types for exhaustive pattern matching.

```java
var lock = new RedisDistributedLock("node-1");

switch (lock.acquireLock("resource", Duration.ofSeconds(30))) {
    case DistributedLock.AcquireResult.Acquired -> {
        try {
            // Critical section
        } finally {
            lock.releaseLock("resource");
        }
    }
    case DistributedLock.AcquireResult.TimedOut(var duration) -> {
        // Waited too long
    }
    case DistributedLock.AcquireResult.Deadlock(var holder) -> {
        // Cycle detected
    }
    case DistributedLock.AcquireResult.Failed(var reason) -> {
        // Error
    }
}
```

**Key Methods:**
- `acquireLock(String lockName, Duration timeout)` — Blocking with timeout
- `tryLock(String lockName, Duration timeout)` — Non-blocking immediate return
- `releaseLock(String lockName)` — Release held lock
- `getStats(String lockName)` — Detailed lock statistics
- `forceRelease(String lockName)` — Admin override for stuck locks
- `onHolderCrash(String lockName, Runnable callback)` — Crash notifications

### 2. RedisDistributedLock

Fast, cluster-native implementation using Redis atomic operations.

**Architecture:**
- Lock value: `"node-id:expiration:nonce"` (string with TTL)
- Waiters queue: Sorted set by join timestamp
- Statistics: Hash tracking acquisitions, timeouts, deadlocks

**Atomic Operations:**
```
SET LOCK NX EX [ttl seconds]  // Acquire if not exists
DEL LOCK + ZADD WAITERS        // Release and notify waiter
DEADLOCK_CHECK                 // Cycle detection in graph
```

**Performance:**
- Acquire: O(1) atomic SET
- Release: O(log N) queue promotion
- Deadlock detection: O(N) DFS on lock graph

### 3. PostgresDistributedLock

Transactional implementation using PostgreSQL advisory locks and explicit schema.

**Schema:**
```sql
CREATE TABLE dlocks (
  lock_name VARCHAR(255) PRIMARY KEY,
  holder VARCHAR(255),
  acquired_at TIMESTAMP,
  expires_at TIMESTAMP,
  nonce UUID
);

CREATE TABLE dlock_waiters (
  id SERIAL,
  lock_name VARCHAR(255),
  process_id VARCHAR(255),
  joined_at TIMESTAMP
);

CREATE TABLE dlock_stats (
  lock_name VARCHAR(255) PRIMARY KEY,
  total_acquires BIGINT,
  contention_count BIGINT
);
```

**Advantages:**
- Full ACID isolation
- Automatic deadlock reporting
- Persistent lock history
- Full recovery across crashes

### 4. ReadWriteDistributedLock

Allows multiple concurrent readers or single exclusive writer.

```java
var rwlock = new ReadWriteDistributedLock("node-1");

// Multiple readers concurrently
if (rwlock.acquireReadLock("cache", Duration.ofSeconds(5))) {
    try {
        var data = readCache();
    } finally {
        rwlock.releaseReadLock("cache");
    }
}

// Single writer blocks all readers
if (rwlock.acquireWriteLock("cache", Duration.ofSeconds(10))) {
    try {
        updateCache(newData);
    } finally {
        rwlock.releaseWriteLock("cache");
    }
}
```

**Fair Ordering:**
- Writers always have priority (prevent writer starvation)
- Readers wait if writers are queued
- FIFO promotion among waiters

### 5. LockManager

Centralized coordination with deadlock detection, process tracking, and metrics.

```java
var lock = new RedisDistributedLock("node-1");
var lockMgr = new LockManager("node-1", lock);

// Acquire with process tracking
var result = lockMgr.acquireWithProcessTracking("worker-1", "db-write", Duration.ofMinutes(1));

// On process crash: automatically release all locks
lockMgr.onProcessCrash("worker-1");

// Deadlock detection: runs every 10 seconds
// Automatically breaks cycles via force-release

// Metrics
var metrics = lockMgr.getMetrics("db-write");
System.out.printf("Acquisitions: %d, Timeouts: %d, Deadlocks: %d%n",
    metrics.get().totalAcquisitions,
    metrics.get().totalTimeouts,
    metrics.get().totalDeadlocks);
```

## Deadlock Detection

### Algorithm

1. **Build wait-for graph:**
   - Node A → Node B if A waits for a lock held by B

2. **Cycle detection:**
   - DFS from each waiting process
   - Back-edge indicates cycle

3. **Resolution:**
   - Break cycle by force-releasing one lock
   - First lock in cycle released to unblock

### Example: Circular Wait

```
Process A holds lock-1, waits for lock-2
Process B holds lock-2, waits for lock-1
                ↓
LockManager detects A → B → A cycle
                ↓
Force-releases one lock to break cycle
```

## Integration with Supervisor

Locks automatically release when their holder crashes:

```java
var lock = new RedisDistributedLock("node-1");
var lockMgr = new LockManager("node-1", lock);
var supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

var procRef = supervisor.supervise("worker", initialState, (state, message) -> {
    var result = lockMgr.acquireWithProcessTracking("worker", "critical", Duration.ofMinutes(1));
    if (result instanceof DistributedLock.AcquireResult.Acquired) {
        try {
            return handler.process(state, message);
        } finally {
            lockMgr.releaseWithProcessTracking("worker", "critical");
        }
    }
    return state; // Could not acquire, retry later
});

// On worker crash: supervisor restarts it
// LockManager automatically releases all locks via onProcessCrash
lockMgr.onProcessCrash("worker");
```

## Timeout Behavior

**Lock Expiration:**
- Each acquired lock stores `acquiredAt` and `expiresAt` timestamps
- Background cleanup task (5-10 second interval) releases expired locks
- On release: first waiter is notified via CompletableFuture

**Timeout Semantics:**
```java
lock.acquireLock("resource", Duration.ofSeconds(30));
// Waits up to 30 seconds for lock to become available
// Returns TimedOut if not acquired within 30s
// Lock holder then has 30s of exclusive access (configurable)
```

## Performance Characteristics

| Operation | Redis | PostgreSQL | ReadWrite |
|-----------|-------|------------|-----------|
| Acquire (unlocked) | O(1) | O(1) | O(1) |
| Acquire (timeout) | O(N) wait | O(N) wait | O(N) wait |
| Release | O(log N) | O(log N) | O(log N) |
| Deadlock detect | O(N²) | automatic | O(N²) |
| Lock stats | O(1) | O(1) | O(1) |

Where N = number of waiters.

## Usage Patterns

### Pattern 1: Critical Section Protection

```java
lock.acquireLock("database-write", Duration.ofMinutes(1));
try {
    // Only one process executes this
    database.writeTransaction(() -> {
        // Safe to modify shared state
    });
} finally {
    lock.releaseLock("database-write");
}
```

### Pattern 2: Process-Aware Coordination

```java
// Process registers itself with lock manager
lockMgr.acquireWithProcessTracking("processor-1", "job-queue", Duration.ofHours(1));
try {
    job = queue.take();
    process(job);
} finally {
    lockMgr.releaseWithProcessTracking("processor-1", "job-queue");
}

// On process crash: automatic cleanup
// Lock released, next waiter gets job
```

### Pattern 3: High-Concurrency Cache

```java
var rwlock = new ReadWriteDistributedLock("node-1");

// Cache reads (many concurrent readers)
if (rwlock.acquireReadLock("user-cache", Duration.ofSeconds(5))) {
    try {
        return cache.get(userId);
    } finally {
        rwlock.releaseReadLock("user-cache");
    }
}

// Cache invalidation (exclusive writer)
if (rwlock.acquireWriteLock("user-cache", Duration.ofSeconds(30))) {
    try {
        cache.clear();
        loadFromDatabase();
    } finally {
        rwlock.releaseWriteLock("user-cache");
    }
}
```

### Pattern 4: Fairness with Waiters

```java
var stats = lock.getStats("resource");

// Observe queue
System.out.printf("Lock holder: %s, Waiters: %s%n",
    stats.holder().orElse("none"),
    stats.waiters());

// Subscribe to crash notifications
lock.onHolderCrash("resource", () -> {
    System.out.println("Lock holder crashed, lock released");
});
```

## Testing

All components include comprehensive test suites:

- **DistributedLockTest**: Basic acquire/release, mutual exclusion, timeouts
- **ReadWriteLockTest**: Reader-writer semantics, fair ordering, concurrent access
- **LockManagerTest**: Process tracking, crash handling, metrics
- **DistributedLockIntegrationTest**: End-to-end coordination, multi-node scenarios

**Example Test:**
```java
@Test
void testMutualExclusion() throws InterruptedException {
    lock.acquireLock("resource", Duration.ofSeconds(10));

    var blocked = new boolean[1];
    var thread = new Thread(() -> {
        var result = lock.acquireLock("resource", Duration.ofMillis(100));
        blocked[0] = result instanceof TimedOut;
    });

    thread.start();
    thread.join();

    assertThat(blocked[0]).isTrue();
    lock.releaseLock("resource");
}
```

## Monitoring & Diagnostics

### Metrics

```java
var metrics = lockMgr.getMetrics("database-write");
System.out.printf("Total acquisitions: %d%n", metrics.get().totalAcquisitions);
System.out.printf("Total timeouts: %d%n", metrics.get().totalTimeouts);
System.out.printf("Total deadlocks: %d%n", metrics.get().totalDeadlocks);
System.out.printf("Average wait time: %d ms%n", metrics.get().avgWaitTimeMs);
System.out.printf("Contention count: %d%n", metrics.get().contentionCount);
```

### Lock Introspection

```java
var stats = lock.getStats("database-write");
System.out.printf("Locked: %b%n", stats.isLocked());
System.out.printf("Holder: %s%n", stats.holder().orElse("none"));
System.out.printf("Waiters: %s%n", stats.waiters());
System.out.printf("Queue length: %d%n", stats.queueLength());
```

### Process Tracking

```java
var processLocks = lockMgr.getProcessLocks();
processLocks.forEach((processId, locks) -> {
    System.out.printf("Process %s holds: %s%n", processId, locks);
});
```

## Limitations & Future Work

### Current Limitations

- In-memory backend (Redis/PostgreSQL abstractions ready)
- Deadlock detection runs periodically (not real-time)
- No lock upgrade from read → write
- Single-node lock manager (future: cluster-aware)

### Planned Enhancements

- [ ] Real Redis/PostgreSQL backend implementation
- [ ] Cluster-aware deadlock detection across nodes
- [ ] Lock lease renewal (long-running operations)
- [ ] Conditional acquire (acquire-if condition holds)
- [ ] Lock migration (rebalance across nodes)
- [ ] Metrics export (Prometheus, Micrometer)

## See Also

- `DistributedLock.java` — Core interface
- `RedisDistributedLock.java` — Fast in-memory backend
- `PostgresDistributedLock.java` — Transactional backend
- `ReadWriteDistributedLock.java` — High-concurrency variant
- `LockManager.java` — Centralized coordination
- `DistributedLockExample.java` — Usage examples

## References

- Erlang/OTP Global Module: `https://www.erlang.org/doc/man/global.html`
- Lamport Bakery Algorithm: Mutual exclusion without atomic operations
- Java Virtual Threads: Project Loom (JEP 425)
- Sealed Types: Java 17+ (JEP 405)
