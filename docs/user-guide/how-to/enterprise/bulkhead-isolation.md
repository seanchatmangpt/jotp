# Bulkhead Isolation Pattern

## Problem

Resource-intensive features (image processing, PDF generation, ML inference) consume all available threads/memory, starving other features and causing system-wide degradation. One misbehaving feature can impact the entire application.

**Symptoms:**
- One feature causes high latency across all features
- Thread pool exhaustion from heavy computations
- Memory pressure from large file processing
- Cascading failures when features compete for resources

## Solution

Implement the Bulkhead pattern to isolate resource-intensive features with per-feature resource limits. Each feature gets its own "bulkhead" (resource boundary) preventing it from affecting others.

JOTP's BulkheadIsolationEnterprise enforces:
- **Concurrent request limits** (max parallel executions)
- **Queue timeout** (max wait time for resources)
- **Utilization monitoring** (HEALTHY/DEGRADED/EXHAUSTED states)
- **Process-based strategy** (uses Semaphore for limiting)

## Prerequisites

- Java 26 with preview features enabled
- JOTP enterprise module: `io.github.seanchatmangpt.jotp.enterprise.bulkhead`
- Understanding of resource limits

## Implementation

### Step 1: Configure Bulkhead Limits

```java
import io.github.seanchatmangpt.jotp.enterprise.bulkhead.*;
import java.time.Duration;
import java.util.List;

// Define resource limits
List<ResourceLimit> limits = List.of(
    new ResourceLimit.MaxConcurrentRequests(10),  // Max 10 parallel
    new ResourceLimit.MaxQueueSize(50),            // Max 50 queued
    new ResourceLimit.MaxMemoryMB(500),            // Max 500 MB
    new ResourceLimit.MaxCpuPercent(80)            // Max 80% CPU
);

// Create configuration
BulkheadConfig config = new BulkheadConfig(
    "image-processing",           // Feature name
    limits,                        // Resource limits
    Duration.ofSeconds(30),        // Queue timeout
    0.8                            // Alert threshold (80%)
);
```

### Step 2: Create Bulkhead Isolation

```java
BulkheadIsolationEnterprise bulkhead =
    BulkheadIsolationEnterprise.create(config);
```

### Step 3: Execute Tasks Within Bulkhead

```java
// Define your resource-intensive task
BulkheadIsolationEnterprise.BulkheadTask<ProcessedImage> task = () -> {
    return imageProcessor.resize(image, 1920, 1080);
};

// Execute within bulkhead isolation
Result<ProcessedImage> result = bulkhead.execute(task);

// Handle result
switch (result) {
    case Result.Success<ProcessedImage>(var processed) -> {
        // Task succeeded
        return processed;
    }
    case Result.Failure<ProcessedImage>(var error) -> {
        // Bulkhead rejected or task failed
        if (error.getMessage().contains("QUEUE_TIMEOUT")) {
            return Response.of(503, "Service busy, try again later");
        }
        throw error;
    }
}
```

### Step 4: Monitor Bulkhead Status

```java
// Get current status
BulkheadIsolationEnterprise.BulkheadState.Status status =
    bulkhead.getStatus();

System.out.println("Bulkhead status: " + status);
// Output: HEALTHY, DEGRADED, or EXHAUSTED

// Get utilization percentage
int utilization = bulkhead.getUtilizationPercent();
System.out.println("Utilization: " + utilization + "%");

// Alert on high utilization
if (utilization > 80) {
    metrics.alert("Bulkhead DEGRADED: " + utilization + "% used");
}
```

### Step 5: Handle Status Transitions

```java
// Add listener for status changes
bulkhead.addListener(new BulkheadIsolationEnterprise.BulkheadListener() {
    @Override
    public void onStatusChanged(
        BulkheadIsolationEnterprise.BulkheadState.Status from,
        BulkheadIsolationEnterprise.BulkheadState.Status to
    ) {
        metrics.recordBulkheadStatus("image-processing", to);

        if (to == BulkheadIsolationEnterprise.BulkheadState.Status.EXHAUSTED) {
            // Bulkhead at capacity
            alertTeam("Image processing bulkhead EXHAUSTED");
            enableFallback();
        }

        if (to == BulkheadIsolationEnterprise.BulkheadState.Status.DEGRADED) {
            // High utilization (80-99%)
            metrics.warn("Bulkhead DEGRADED, consider scaling");
        }

        if (to == BulkheadIsolationEnterprise.BulkheadState.Status.HEALTHY) {
            // Normal operation
            metrics.info("Bulkhead recovered");
        }
    }
});
```

## Complete Example

```java
public class ImageProcessingService {
    private final BulkheadIsolationEnterprise bulkhead;
    private final ImageProcessor processor;

    public ImageProcessingService(ImageProcessor processor) {
        this.processor = processor;

        // Configure bulkhead for image processing
        List<ResourceLimit> limits = List.of(
            new ResourceLimit.MaxConcurrentRequests(5),
            new ResourceLimit.MaxQueueSize(20),
            new ResourceLimit.MaxMemoryMB(1024)
        );

        BulkheadConfig config = new BulkheadConfig(
            "image-processing",
            limits,
            Duration.ofSeconds(30),
            0.8
        );

        this.bulkhead = BulkheadIsolationEnterprise.create(config);
    }

    public Response<ProcessedImage> resizeImage(Image image, int width, int height) {
        Result<ProcessedImage> result = bulkhead.execute(() ->
            processor.resize(image, width, height)
        );

        return switch (result) {
            case Result.Success<ProcessedImage>(var processed) ->
                Response.of(processed);
            case Result.Failure<ProcessedImage>(var error) -> {
                if (error.getMessage().contains("QUEUE_TIMEOUT")) {
                    yield Response.of(503, "Service busy, retry later");
                }
                yield Response.of(500, "Processing failed");
            }
        };
    }

    public BulkheadIsolationEnterprise.BulkheadState.Status getStatus() {
        return bulkhead.getStatus();
    }

    public void shutdown() {
        bulkhead.shutdown();
    }
}
```

## Configuration Guidelines

### Concurrent Requests

```java
// CPU-bound: Use # of CPU cores
new ResourceLimit.MaxConcurrentRequests(
    Runtime.getRuntime().availableProcessors()
);

// I/O-bound: Higher concurrency
new ResourceLimit.MaxConcurrentRequests(50);

// Heavy computation: Low concurrency
new ResourceLimit.MaxConcurrentRequests(2);
```

### Queue Size

```java
// Small queue: Fail fast
new ResourceLimit.MaxQueueSize(10);

// Balanced queue
new ResourceLimit.MaxQueueSize(50);

// Large queue: High tolerance
new ResourceLimit.MaxQueueSize(200);
```

### Queue Timeout

```java
// Quick fail: 5 seconds
Duration.ofSeconds(5);

// Standard: 30 seconds (typical)
Duration.ofSeconds(30);

// Patient: 2 minutes
Duration.ofMinutes(2);
```

### Alert Threshold

```java
// Early warning: 60%
new BulkheadConfig(name, limits, timeout, 0.6);

// Standard: 80% (typical)
new BulkheadConfig(name, limits, timeout, 0.8);

// Late warning: 90%
new BulkheadConfig(name, limits, timeout, 0.9);
```

## Performance Considerations

### Memory
- **Per-bulkhead overhead**: ~2 KB (state tracking + semaphore)
- **Queue overhead**: O(queueSize) × request object size
- **Scaling**: 100 bulkheads ≈ 200 KB heap

### Latency
- **HEALTHY state**: ~1 μs overhead (semaphore acquire)
- **DEGRADED state**: ~1 μs overhead (still just semaphore)
- **EXHAUSTED state**: QueueTimeout duration (fail-fast)

### Throughput
- **Semaphore acquisition**: Lock-free, volatile read
- **High concurrency**: Millions of requests/second per bulkhead
- **Contention**: Minimal (semaphore fair scheduling)

## Monitoring

### Key Metrics

```java
record BulkheadMetrics(
    String featureName,
    String status,              // HEALTHY/DEGRADED/EXHAUSTED
    int utilizationPercent,     // 0-100
    long activeRequests,        // Currently executing
    long queuedRequests,        // Waiting in queue
    long totalRequests,         // Total received
    long rejectedRequests,      // Rejected due to limits
    Duration avgQueueTime,      // Average wait time
    Duration avgProcessTime     // Average execution time
) {}
```

### Alerting

```java
// Alert on EXHAUSTED state
if (status == Status.EXHAUSTED) {
    alertService.send(AlertPriority.HIGH,
        "Bulkhead EXHAUSTED for " + featureName);
}

// Alert on high rejection rate
if (rejectionRate > 10%) {
    alertService.send(AlertPriority.MEDIUM,
        "High rejection rate: " + featureName);
}

// Alert on sustained DEGRADED state
if (status == Status.DEGRADED for > 5 minutes) {
    alertService.send(AlertPriority.MEDIUM,
        "Sustained DEGRADED state: " + featureName);
}
```

## Common Pitfalls

### 1. Limits Too High
```java
// BAD: No isolation
new ResourceLimit.MaxConcurrentRequests(10000);

// GOOD: Reasonable limits
new ResourceLimit.MaxConcurrentRequests(
    Runtime.getRuntime().availableProcessors()
);
```

### 2. Queue Too Large
```java
// BAD: Memory explosion
new ResourceLimit.MaxQueueSize(100000);

// GOOD: Balanced queue
new ResourceLimit.MaxQueueSize(50);
```

### 3. Ignoring Rejections
```java
// BAD: Throw exception, crash request
case Result.Failure(var error) -> {
    throw new RuntimeException(error);
}

// GOOD: Graceful degradation
case Result.Failure(var error) -> {
    return Response.of(503, "Service busy, retry later");
}
```

## Advanced Patterns

### Per-Tenant Bulkheads

```java
// Create separate bulkhead per tenant
Map<String, BulkheadIsolationEnterprise> tenantBulkheads = new ConcurrentHashMap<>();

public BulkheadIsolationEnterprise getTenantBulkhead(String tenantId) {
    return tenantBulkheads.computeIfAbsent(tenantId, id -> {
        List<ResourceLimit> limits = getLimitsForTenant(id);
        return BulkheadIsolationEnterprise.create(
            new BulkheadConfig(id + "-bulkhead", limits, timeout, 0.8)
        );
    });
}
```

### Dynamic Scaling

```java
// Scale bulkhead limits based on load
public void adjustLimits(BulkheadConfig config, int utilization) {
    if (utilization > 90) {
        // Increase capacity
        int newMax = config.maxConcurrentRequests() * 2;
        updateBulkheadLimits(config, newMax);
    } else if (utilization < 30) {
        // Decrease capacity
        int newMax = Math.max(1, config.maxConcurrentRequests() / 2);
        updateBulkheadLimits(config, newMax);
    }
}
```

## Related Guides

- **[Circuit Breaker](./circuit-breaker.md)** - Fail fast for failing services
- **[Rate Limiting](./rate-limiting.md)** - Control request rates
- **[Backpressure](../resilience/backpressure.md)** - Flow control for producers

## References

- **BulkheadIsolationEnterprise**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/bulkhead/BulkheadIsolationEnterprise.java`
- **BulkheadConfig**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/bulkhead/BulkheadConfig.java`
- **Test**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/bulkhead/BulkheadIsolationEnterpriseTest.java`
