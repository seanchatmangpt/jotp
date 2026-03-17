# io.github.seanchatmangpt.jotp.BulkheadIsolationTest

## Table of Contents

- [Rejection Handling: FAILED State](#rejectionhandlingfailedstate)
- [Resource Limits: Pool Size and Queue Depth](#resourcelimitspoolsizeandqueuedepth)
- [Lifecycle: Recreation After Shutdown](#lifecyclerecreationaftershutdown)
- [Bulkhead Status: FAILED State](#bulkheadstatusfailedstate)
- [Fault Tolerance: Supervision-Based Recovery](#faulttolerancesupervisionbasedrecovery)
- [BulkheadIsolation: Process-Pool-Based Feature Isolation](#bulkheadisolationprocesspoolbasedfeatureisolation)
- [Concurrency: Multiple Senders](#concurrencymultiplesenders)
- [Performance Trade-offs: Isolation vs Efficiency](#performancetradeoffsisolationvsefficiency)
- [Worker Pool: On-Demand Process Spawning](#workerpoolondemandprocessspawning)
- [Lifecycle: Graceful Shutdown](#lifecyclegracefulshutdown)
- [Bulkhead Status: DEGRADED State](#bulkheadstatusdegradedstate)
- [Bulkhead Status: ACTIVE State](#bulkheadstatusactivestate)


## Rejection Handling: FAILED State

When bulkhead is in FAILED state, all messages are rejected:
- SendResult is Rejected with Reason.FAILED
- No queuing or processing attempts
- Caller must handle rejection explicitly
- Enables fallback strategies (retry, failover, circuit breaker)

Rejection is fast-fail:
- Immediate return (no blocking)
- Structured error information
- Enables graceful degradation
- Prevents resource waste on doomed requests


```java
var bulkhead = BulkheadIsolation.create(
    "feature-reject-failed",
    2, 10,
    (state, msg) -> {
        if (msg instanceof TestMsg.Crash) {
            throw new RuntimeException("Worker crash");
        }
        return state;
    });

// Trigger supervisor failure
for (int i = 0; i < 10; i++) {
    bulkhead.send(new TestMsg.Crash("crash-" + i));
}

// Wait for FAILED status
await().until(() ->
    bulkhead.status() instanceof BulkheadIsolation.BulkheadStatus.Failed);

// Now send returns Rejected
var result = bulkhead.send(new TestMsg.Noop());
// result == Rejected(Reason.FAILED)
```

## Resource Limits: Pool Size and Queue Depth

BulkheadIsolation enforces two key resource limits:

1. Pool Size (max workers):
   - Limits concurrent processing capacity
   - Prevents CPU/thread exhaustion
   - Typical values: 2-20 workers per feature

2. Queue Depth (max messages per worker):
   - Limits buffered work per worker
   - Prevents memory exhaustion
   - Typical values: 10-1000 messages per worker

These limits provide:
- Bounded memory usage
- Predictable latency
- Isolation between features
- Graceful degradation under load


```java
var bulkhead = BulkheadIsolation.create(
    "feature-limits",
    5,              // Pool size: 5 workers max
    100,            // Queue depth: 100 messages per worker
    (state, msg) -> state);

// Max capacity = 5 workers * 100 queue = 500 messages
// Beyond that, messages rejected with Rejected.Reason.DEGRADED
```

| Key | Value |
| --- | --- |
| `Pool Size` | `5 workers` |
| `Rejection Policy` | `Fail-fast when exceeded` |
| `Memory Estimate` | `~500KB (500 msgs * ~1KB)` |
| `Queue Depth` | `100 messages/worker` |
| `Max Capacity` | `500 messages` |

| Resource Limit | Purpose | Typical Range | Exceeded Behavior |
| --- | --- | --- | --- |
| Pool Size | Limit concurrent workers | 2-20 | New messages wait for available worker |
| Queue Depth | Limit buffered messages | 10-1000 | Transition to DEGRADED, reject new messages |
| Max Restarts | Limit supervisor restarts | 3-10 | Transition to FAILED, reject all messages |

| Resource | Per-Worker Cost | Max Pool (20 workers) |
| --- | --- | --- |
| Memory | ~1KB | ~20KB |
| Threads | 1 virtual thread | 20 virtual threads |
| Queue | Configurable | Configurable |
| Context Switch | Virtual scheduler | Virtual scheduler |

## Lifecycle: Recreation After Shutdown

After shutdown, bulkheads can be recreated for the same feature:
- Clean state: No residual messages or workers
- Fresh supervisor: Reset restart counters
- New process pool: No prior crash state
- Same feature ID: Maintains identity

Recreation pattern:
1. Shutdown old bulkhead
2. Create new bulkhead with same feature ID
3. Resume message processing
4. Fresh isolation boundaries


```java
var bulkhead1 = BulkheadIsolation.create(
    "feature-recreate",
    2, 10,
    (state, msg) -> state);

bulkhead1.shutdown();

// Recreate bulkhead for same feature
var bulkhead2 = BulkheadIsolation.create(
    "feature-recreate",
    2, 10,
    (state, msg) -> state);

var result = bulkhead2.send(new TestMsg.Noop());
// result == Success (fresh bulkhead)
```

| Key | Value |
| --- | --- |
| `First Bulkhead` | `Shut down` |
| `Message Processing` | `Resumed normally` |
| `Second Bulkhead` | `Created successfully` |
| `State` | `Fresh (no residual)` |
| `Feature ID` | `Same (feature-recreate)` |

## Bulkhead Status: FAILED State

When supervisor exceeds max restarts, bulkhead transitions to FAILED:
- Supervisor has terminated (cannot recover)
- All messages rejected with Rejected.Reason.FAILED
- Manual intervention required to restart
- Indicates systemic failure in feature

Failure cascade protection:
- One feature's failure doesn't affect others
- Failed bulkhead stops accepting messages
- Caller must handle Rejected result
- System remains partially available


```java
var crashCounter = new AtomicInteger(0);
var bulkhead = BulkheadIsolation.create(
    "feature-failed",
    2,              // Pool size
    10,             // Queue depth
    (state, msg) -> {
        if (msg instanceof TestMsg.Crash) {
            crashCounter.incrementAndGet();
            throw new RuntimeException("Worker crash");
        }
        return state;
    });

// Trigger repeated crashes to exceed max restarts
for (int i = 0; i < 10; i++) {
    bulkhead.send(new TestMsg.Crash("test-crash-" + i));
    Thread.sleep(50);
}

// Status transitions to FAILED
```

## Fault Tolerance: Supervision-Based Recovery

BulkheadIsolation uses JOTP's supervision for fault tolerance:
- ONE_FOR_ONE restart strategy: only crashed worker restarts
- Other workers continue processing during restart
- No message loss (unprocessed messages remain queued)
- Automatic recovery without manual intervention

Supervision provides:
- Self-healing: Workers restart automatically
- Isolation: One worker crash doesn't stop others
- Resilience: Transient errors handled transparently
- Observability: Crashes tracked via status metrics


```java
var crashCount = new AtomicInteger(0);
var bulkhead = BulkheadIsolation.create(
    "feature-recovery",
    3,              // Pool size
    10,             // Queue depth
    (state, msg) -> {
        if (msg instanceof TestMsg.Crash) {
            crashCount.incrementAndGet();
            throw new RuntimeException("Worker crash");
        }
        return state;
    });

// Send normal messages
for (int i = 0; i < 3; i++) {
    bulkhead.send(new TestMsg.Noop());
}

// Trigger a crash
bulkhead.send(new TestMsg.Crash("test"));

// Bulkhead still responsive (supervisor restarted worker)
await().until(() -> {
    var result = bulkhead.send(new TestMsg.Noop());
    return result instanceof BulkheadIsolation.Send.Success;
});
```

| Key | Value |
| --- | --- |
| `Send Result` | `Rejected` |
| `Behavior` | `Fast-fail, no queuing` |
| `Rejection Reason` | `FAILED` |
| `Caller Action` | `Apply fallback strategy` |

| Rejection Reason | When | Caller Action |
| --- | --- | --- |
| DEGRADED | Queue depth exceeded | Retry with backoff, shed load |
| FAILED | Supervisor terminated | Failover to alternate, alert ops |
| NOT_FOUND | Bulkhead doesn't exist | Create bulkhead, check configuration |

## BulkheadIsolation: Process-Pool-Based Feature Isolation

BulkheadIsolation implements Joe Armstrong's bulkhead pattern using JOTP's supervision trees.
Each feature gets its own isolated process pool with bounded queue depth, preventing
cascading failures across features.

Architecture:
- Per-feature Supervisor with ONE_FOR_ONE restart strategy
- Bounded process pool (poolSize workers)
- Queue depth monitoring for overload detection
- Graceful rejection when degraded or failed


```java
var bulkhead = BulkheadIsolation.create(
    "feature-1",     // Feature identifier
    3,               // Pool size: 3 worker processes
    10,              // Max queue depth per worker
    (state, msg) -> {
        if (msg instanceof TestMsg.Noop) return state;
        return state;
    });

var result = bulkhead.send(new TestMsg.Noop());
// result == Success
```

| Key | Value |
| --- | --- |
| `Feature ID` | `feature-1` |
| `Pool Size` | `3 workers` |
| `Process Count` | `1` |
| `Send Result` | `Success` |

| Component | Purpose | Key Benefit |
| --- | --- | --- |
| Supervisor | ONE_FOR_ONE restart strategy | Isolated crash recovery per feature |
| Process Pool | Bounded worker processes | Prevents resource exhaustion |
| Queue Monitor | Tracks mailbox depth | Detects overload before failure |
| Graceful Rejection | Returns Rejected on overload | Caller can apply backpressure |

## Concurrency: Multiple Senders

BulkheadIsolation handles concurrent message sends safely:
- Thread-safe send() operation
- Lock-free message delivery to worker mailboxes
- Bounded queue prevents unbounded memory growth
- Rejection under overload prevents system collapse

Concurrency guarantees:
- No lost messages (unless rejected)
- No corruption of worker state
- Sequential processing per worker
- Fair message distribution


```java
var bulkhead = BulkheadIsolation.create(
    "feature-concurrent",
    5,              // Pool size
    100,            // Queue depth
    (state, msg) -> {
        if (msg instanceof TestMsg.Process) {
            // Simulate work
        }
        return state;
    });

var executor = Executors.newFixedThreadPool(10);
var latch = new CountDownLatch(100);
var successes = new AtomicInteger(0);

// 10 threads, 10 messages each = 100 concurrent sends
for (int t = 0; t < 10; t++) {
    executor.execute(() -> {
        for (int i = 0; i < 10; i++) {
            var result = bulkhead.send(
                new TestMsg.Process("msg-" + i, 1));
            if (result instanceof BulkheadIsolation.Send.Success) {
                successes.incrementAndGet();
            }
            latch.countDown();
        }
    });
}

latch.await();
```

| Key | Value |
| --- | --- |
| `Recovery` | `Manual restart required` |
| `Total Rejections` | `5` |
| `Status` | `FAILED` |
| `Crashes Triggered` | `3` |

## Performance Trade-offs: Isolation vs Efficiency

BulkheadIsolation represents a trade-off between isolation and efficiency:

ISOLATION BENEFITS:
- Feature failures don't cascade
- Bounded resource usage per feature
- Predictable performance under load
- Graceful degradation possible

EFFICIENCY COSTS:
- Context switching between workers
- Queue memory overhead
- Rejection of excess messages
- Monitoring and status tracking overhead

JOTP OPTIMIZATIONS:
- Virtual threads: ~1KB per process (vs ~1MB platform thread)
- Lock-free mailboxes: LinkedTransferQueue
- On-demand spawning: No fixed pool allocation
- Supervision trees: Efficient restart handling


```java
// High isolation, lower efficiency
var isolated = BulkheadIsolation.create(
    "feature-isolated",
    2,              // Small pool
    10,             // Small queue
    handler);

// Lower isolation, higher efficiency
var efficient = BulkheadIsolation.create(
    "feature-efficient",
    20,             // Large pool
    1000,           // Large queue
    handler);
```

| Configuration | Isolation | Efficiency | Use Case |
| --- | --- | --- | --- |
| Pool=2, Queue=10 | High | Low | Critical features, strict limits |
| Pool=10, Queue=100 | Medium | Medium | Standard features, balanced |
| Pool=20, Queue=1000 | Low | High | Non-critical, high throughput |

| Key | Value |
| --- | --- |
| `Context Switch` | `Scheduler-managed, minimal overhead` |
| `Scalability` | `Millions of processes possible` |
| `Recommendation` | `Use larger pools with virtual threads` |
| `Virtual Thread Advantage` | `1000x less memory than platform threads` |

| Configuration | Max Throughput | Memory Usage |
| --- | --- | --- |
| Small (2/10) | ~20 msg/sec | ~20KB |
| Medium (10/100) | ~100 msg/sec | ~100KB |
| Large (20/1000) | ~1000 msg/sec | ~200KB |

## Worker Pool: On-Demand Process Spawning

BulkheadIsolation creates worker processes on-demand up to the pool size limit.
This provides efficient resource utilization while maintaining isolation boundaries.

Process Pool Strategy:
- Workers created when messages arrive
- Pool size limits maximum concurrent workers
- Virtual threads enable lightweight scaling (~1KB per process)
- Workers handle messages sequentially per process


```java
var bulkhead = BulkheadIsolation.create(
    "feature-pool",
    5,              // Pool size: 5 workers max
    100,            // Max queue depth
    (state, msg) -> {
        if (msg instanceof TestMsg.Process p) {
            Thread.sleep(p.delay);  // Simulate work
        }
        return state;
    });

// Send 5 messages to spawn 5 workers
for (int i = 0; i < 5; i++) {
    bulkhead.send(new TestMsg.Process("msg-" + i, 10));
}

// Process count grows to 5
```

| Key | Value |
| --- | --- |
| `Manual Intervention` | `Not required` |
| `Recovery Strategy` | `ONE_FOR_ONE supervision` |
| `Crashes Detected` | `1` |
| `Other Workers` | `Continued during restart` |
| `Message Loss` | `None (queue preserved)` |

| Supervision Feature | Benefit | Enterprise Value |
| --- | --- | --- |
| Automatic Restart | Self-healing without ops | Reduced MTTR, higher availability |
| Crash Isolation | One worker crash doesn't stop others | Partial service during failures |
| State Preservation | Queue preserved during restart | No message loss, no data corruption |
| Restart Throttling | Max restarts prevents crash loops | System stability, controlled failure |

## Lifecycle: Graceful Shutdown

BulkheadIsolation supports graceful shutdown:
- Stops accepting new messages immediately
- In-flight messages complete processing
- Workers terminate after queue drains
- Clean resource release

Shutdown behavior:
- send() returns Rejected after shutdown()
- No new messages queued
- Existing workers finish current messages
- Supervisor terminates all workers


```java
var bulkhead = BulkheadIsolation.create(
    "feature-shutdown",
    2, 10,
    (state, msg) -> state);

// Send message before shutdown
bulkhead.send(new TestMsg.Noop());

// Shutdown bulkhead
bulkhead.shutdown();

// Send after shutdown returns Rejected
var result = bulkhead.send(new TestMsg.Noop());
// result == Rejected
```

| Key | Value |
| --- | --- |
| `Post-Shutdown Send` | `Rejected` |
| `Resource Release` | `Clean termination` |
| `Pre-Shutdown Send` | `Success` |
| `In-Flight Messages` | `Complete normally` |

## Bulkhead Status: DEGRADED State

When queue depth exceeds maxQueueDepth threshold, bulkhead transitions to DEGRADED:
- At least one worker's mailbox is overloaded
- Messages still accepted but system is under stress
- Alerts triggered for monitoring
- May lead to rejections if overload continues

Degraded status provides early warning before failure:
- maxQueueDepth: Depth of most congested worker queue
- totalRejections: Running count of rejected messages


```java
var bulkhead = BulkheadIsolation.create(
    "feature-degraded",
    1,              // Single worker
    3,              // Low queue threshold
    (state, msg) -> {
        if (msg instanceof TestMsg.Process p) {
            Thread.sleep(100);  // Slow processing
        }
        return state;
    });

// Send many messages to overwhelm queue
for (int i = 0; i < 6; i++) {
    bulkhead.send(new TestMsg.Process("msg-" + i, 50));
}

// Status transitions to DEGRADED
```

| Key | Value |
| --- | --- |
| `Strategy` | `On-demand spawning` |
| `Pool Size Limit` | `5 workers` |
| `Active Processes` | `1` |
| `Messages Sent` | `5` |

| Metric | Value |
| --- | --- |
| Memory per Worker | ~1KB (virtual thread) |
| Spawn Time | <1ms |
| Max Pool Size | Configurable |
| Context Switch | Virtual thread scheduler |

## Bulkhead Status: ACTIVE State

Freshly created bulkheads start in ACTIVE status, indicating:
- All workers healthy and responsive
- Queue depths below threshold
- No supervisor restarts exceeded
- System accepting messages

Active status includes metrics for observability:
- processCount: Number of active worker processes
- totalRejections: Cumulative rejected messages


```java
var bulkhead = BulkheadIsolation.create(
    "feature-status",
    3,              // Pool size
    5,              // Max queue depth
    (state, msg) -> state);

BulkheadIsolation.BulkheadStatus status = bulkhead.status();
// status == Active(processCount=3, totalRejections=0)
```

| Key | Value |
| --- | --- |
| `Process Count` | `0` |
| `Total Rejections` | `0` |
| `Status` | `ACTIVE` |
| `Health` | `All workers healthy` |

| Key | Value |
| --- | --- |
| `Messages per Sender` | `10` |
| `Thread Safety` | `No corruption, no lost messages` |
| `Concurrent Senders` | `10 threads` |
| `Total Messages` | `100` |
| `Successful Sends` | `100` |

---
*Generated by [DTR](http://www.dtr.org)*
