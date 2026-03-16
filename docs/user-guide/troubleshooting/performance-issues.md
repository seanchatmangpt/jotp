# Performance Issues Troubleshooting Guide

## High Message Latency

### Symptoms
- Messages take seconds to be processed
- `ask()` calls timeout frequently
- Increasing mailbox queue depth
- User-visible delays

### Diagnosis Steps

1. **Measure message throughput:**
   ```java
   // Add metrics to process
   AtomicLong messagesProcessed = new AtomicLong(0);
   Instant start = Instant.now();

   @Override
   protected void handle(Message msg) {
       long count = messagesProcessed.incrementAndGet();
       if (count % 10000 == 0) {
           Duration elapsed = Duration.between(start, Instant.now());
           double rate = count / (double) elapsed.toSeconds();
           log.info("Rate: {} msg/s", rate);
       }
   }
   ```

2. **Check handler execution time:**
   ```java
   @Override
   protected void handle(Message msg) {
       Instant start = Instant.now();
       try {
           processMessage(msg);
       } finally {
           Duration elapsed = Duration.between(start, Instant.now());
           if (elapsed.toMillis() > 100) {
               log.warn("Slow message: {}ms", elapsed.toMillis());
           }
       }
   }
   ```

3. **Profile with JFR:**
   ```bash
   # Record with Java Flight Recorder
   java -XX:StartFlightRecording=filename=recording.jfr,duration=60s \
        -XX:FlightRecorderOptions=settings=profile ...

   # Analyze
   jfr print --events jdk.ExecutionSample recording.jfr
   ```

4. **Check for thread parking:**
   ```bash
   # Monitor virtual thread state
   jcmd <pid> Thread.vthread_summary
   ```

### Solutions

#### Optimize Message Handler
**Problem:** Handler does too much work per message
```java
// Bad: Heavy computation in handler
protected void handle(Work work) {
    String result = expensiveComputation(work);  // Takes 1s
    reply(result);
}

// Good: Offload to worker pool
protected void handle(Work work) {
    CompletableFuture.supplyAsync(() -> {
        return expensiveComputation(work);
    }, workerPool).thenAcceptAsync(result -> {
        reply(result);
    });
}
```

#### Use Batch Processing
```java
// Instead of processing one message at a time
protected void handle(Message msg) {
    process(msg);  // Context switch overhead
}

// Batch multiple messages
protected void handle(Batch batch) {
    batch.messages().stream()
        .forEach(this::process);  // Fewer context switches
}

// In producer:
List<Message> batch = new ArrayList<>(1000);
for (int i = 0; i < 1000; i++) {
    batch.add(createMessage(i));
}
ref.send(new Batch(batch));
```

#### Implement Message Prioritization
```java
class PriorityProc extends Proc<String, PriorityMessage> {
    private final PriorityBlockingQueue<PriorityMessage> queue =
        new PriorityBlockingQueue<>();

    @Override
    protected void handle(PriorityMessage msg) {
        queue.put(msg);
        if (queue.size() >= 100) {
            flushQueue();
        }
    }

    private void flushQueue() {
        List<PriorityMessage> batch = new ArrayList<>();
        queue.drainTo(batch, 100);
        batch.stream()
            .sorted()
            .forEach(this::process);
    }
}
```

### Prevention
- Profile handler execution time
- Use batch messages for bulk operations
- Monitor queue depth metrics
- Set alert thresholds for latency

---

## Memory Leaks

### Symptoms
- Heap usage grows continuously
- OutOfMemoryError after hours/days
- GC pauses increasing over time
- Process mailbox size growing

### Diagnosis Steps

1. **Capture heap dumps:**
   ```bash
   # On OOM
   -XX:+HeapDumpOnOutOfMemoryError
   -XX:HeapDumpPath=/tmp/heap.hprof

   # Manual dump
   jmap -dump:format=b,file=heap.hprof <pid>
   ```

2. **Analyze with MAT:**
   ```
   Open heap.hprof in Eclipse MAT
   → Leak Suspects Report
   → Dominator Tree
   → Look for: Proc instances, Mailbox queues, Message objects
   ```

3. **Check mailbox retention:**
   ```java
   // Are messages holding references?
   record MessageWithPayload(Object payload) {}

   // If payload is large, it leaks
   ref.send(new MessageWithPayload(hugeObject));
   ```

4. **Monitor GC behavior:**
   ```bash
   # Enable GC logging
   -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10m

   # Analyze
   grep "Full GC" gc.log
   ```

### Solutions

#### Fix Message Retention
**Problem:** Messages holding large objects after processing
```java
// Bad: Large payload in message
record LargeMessage(byte[] data) {}  // 10MB per message

protected void handle(LargeMessage msg) {
    process(msg.data());
    // msg.data() stays in memory until GC
}

// Good: Clear references
protected void handle(LargeMessage msg) {
    try {
        process(msg.data());
    } finally {
        msg.clear();  // Explicitly clear large field
    }
}

// Better: Use shared storage
record MessageRef(Path dataFile) {}  // Just a path
```

#### Implement Message Expiration
```java
class ExpiringMailbox {
    private final Map<UUID, Message> messages = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = ...;

    void add(Message msg) {
        UUID id = UUID.randomUUID();
        messages.put(id, msg);

        // Auto-remove after 5 minutes
        cleaner.schedule(() -> {
            messages.remove(id);
        }, 5, TimeUnit.MINUTES);
    }
}
```

#### Use Weak References for Caches
```java
// Bad: Strong references keep objects alive
private final Map<String, Object> cache = new HashMap<>();

// Good: Allow GC to reclaim
private final Map<String, WeakReference<Object>> cache = new ConcurrentHashMap<>();

void cache(String key, Object value) {
    cache.put(key, new WeakReference<>(value));
}
```

### Prevention
- Regular heap dump analysis in staging
- Monitor GC pause times
- Use weak references for caches
- Clear large message payloads

---

## GC Pressure

### Symptoms
- Long GC pauses (>100ms)
- High CPU usage from GC threads
- Frequent Full GC cycles
- Throughput degradation

### Diagnosis Steps

1. **Check GC stats:**
   ```bash
   # Enable GC logging
   -Xlog:gc*:gc.log:time,uptime

   # View GC frequency
   grep "GC" gc.log | wc -l

   # Check pause times
   grep "pause" gc.log | awk '{print $NF}'
   ```

2. **Analyze object allocation:**
   ```bash
   # Use JFR allocation profiling
   java -XX:StartFlightRecording=filename=alloc.jfr \
        -XX:StartFlightRecording=settings=profile ...

   jfr print --events jdk.ObjectAllocationInNewTLAB alloc.jfr
   ```

3. **Check generation sizes:**
   ```bash
   jstat -gc <pid> 1000
   # Look for:
   # - EU (Eden Utilization)
   # - OU (Old Utilization)
   # - YGC (Young GC count)
   ```

### Solutions

#### Reduce Allocation Rate
**Problem:** Creating millions of short-lived objects
```java
// Bad: New object per message
protected void handle(Message msg) {
    StringBuilder sb = new StringBuilder();  // Allocates
    sb.append("Header: ");
    sb.append(msg.header());
    sb.append(", Body: ");
    sb.append(msg.body());
    log.info(sb.toString());
}

// Good: Reuse buffers
private final ThreadLocal<StringBuilder> buffer =
    ThreadLocal.withInitial(() -> new StringBuilder(256));

protected void handle(Message msg) {
    StringBuilder sb = buffer.get();
    sb.setLength(0);  // Reset
    sb.append("Header: ").append(msg.header());
    log.info(sb.toString());
}
```

#### Use Object Pooling
```java
// For frequently allocated objects
class MessagePool {
    private final Queue<Message> pool = new ConcurrentLinkedQueue<>();

    Message acquire() {
        Message msg = pool.poll();
        return msg != null ? msg : new Message();
    }

    void release(Message msg) {
        msg.clear();
        pool.offer(msg);
    }
}

// Usage
Message msg = pool.acquire();
try {
    process(msg);
} finally {
    pool.release(msg);
}
```

#### Tune Heap Sizing
```bash
# G1GC configuration
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m
-XX:InitiatingHeapOccupancyPercent=45

# Adjust based on observed behavior
-Xms4g -Xmx4g  # Fixed size avoids resize pauses
```

### Prevention
- Profile allocation rates
- Use object pools for hot paths
- Tune GC settings for workload
- Monitor pause time metrics

---

## Thread Exhaustion

### Symptoms
- "RejectedExecutionException"
- New processes fail to start
- Carrier threads exhausted
- System hangs under load

### Diagnosis Steps

1. **Check virtual thread count:**
   ```bash
   jcmd <pid> Thread.vthread_summary

   # Look for:
   # - "Virtual thread count"
   # - "Carrier thread count"
   ```

2. **Monitor pinned threads:**
   ```bash
   # Enable pinning detection
   -Djdk.tracePinnedThreads=full

   # Look for warnings in logs
   grep "Pinned virtual thread" application.log
   ```

3. **Check thread factory limits:**
   ```java
   // If using custom thread pool
   ExecutorService pool = Executors.newFixedThreadPool(10);
   // ^ Limits carriers to 10
   ```

### Solutions

#### Fix Pinning (See runtime-issues.md)
```java
// Replace synchronized with ReentrantLock
private final ReentrantLock lock = new ReentrantLock();

void criticalSection() {
    lock.lock();
    try {
        // Won't pin virtual thread
    } finally {
        lock.unlock();
    }
}
```

#### Increase Carrier Thread Pool
```java
// Custom virtual thread executor
import java.lang.Thread.Builder;

Thread.Builder.OfVirtual builder = Thread.ofVirtual();
// Configure scheduler if needed
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor(builder);
```

#### Use Bounded Concurrency
```java
// Limit concurrent operations
Semaphore semaphore = new Semaphore(100);

protected void handle(Work work) {
    try {
        semaphore.acquire();
        CompletableFuture.runAsync(() -> {
            process(work);
        }, executor).whenComplete((r, e) -> {
            semaphore.release();
        });
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

### Prevention
- Enable pinning warnings
- Profile thread usage
- Use virtual threads instead of platform threads
- Monitor carrier thread metrics

---

## Slow Startups

### Symptoms
- Application takes minutes to start
- Processes initialize slowly
- Dependencies take time to resolve
- High startup CPU usage

### Diagnosis Steps

1. **Profile startup time:**
   ```bash
   # Use Java Flight Recorder
   java -XX:StartFlightRecording=filename=startup.jfr,duration=30s \
        -jar app.jar

   # Analyze
   jfr print --events jdk.ExecutionSample startup.jfr | grep "main"
   ```

2. **Check module resolution:**
   ```bash
   # Time module loading
   time java --show-module-resolution -jar app.jar

   # Look for slow module downloads
   ```

3. **Monitor process spawning:**
   ```java
   Instant overallStart = Instant.now();

   protected void init() {
       Instant start = Instant.now();
       // Init code
       Duration elapsed = Duration.between(start, Instant.now());
       log.info("Init took: {}ms", elapsed.toMillis());
   }

   // At startup:
   log.info("Total startup: {}ms",
       Duration.between(overallStart, Instant.now()).toMillis());
   ```

### Solutions

#### Lazy Initialization
**Problem:** Starting too many processes at once
```java
// Bad: Start all processes eagerly
public void start() {
    for (ChildSpec spec : allSpecs) {
        supervisor.startChild(spec);  // Serial startup
    }
}

// Good: Start on-demand
public void start() {
    // Start only critical processes
    supervisor.startChild(criticalSpec);

    // Others start when first message arrives
}

protected void handle(FirstMessage msg) {
    if (!lazyProcessStarted) {
        spawnLazyProcess();
        lazyProcessStarted = true;
    }
    // Handle message
}
```

#### Parallel Startup
```java
// Start independent processes in parallel
public void start() {
    allSpecs.parallelStream()
        .forEach(spec -> {
            supervisor.startChild(spec);
        });
}
```

#### Optimize Module Path
```bash
# Use faster module resolution
java --enable-preview \
     --module-path /optimized/module/path \
     -m io.github.seanchatmangpt.jotp/Main

# Or create custom runtime image
jlink --module-path /path/to/modules \
      --add-modules io.github.seanchatmangpt.jotp \
      --output runtime
```

### Prevention
- Profile startup regularly
- Use lazy initialization where possible
- Parallelize independent startups
- Monitor startup time in CI/CD

---

## Quick Reference

### Profiling Tools

```bash
# Java Flight Recorder
java -XX:StartFlightRecording=filename=recording.jfr,duration=60s ...
jfr print recording.jfr

# Async Profiler
profiler.sh -d 30 -f cpu.svg <pid>

# JMX Monitoring
jconsole <pid>
jvisualvm

# Heap Analysis
jmap -dump:format=b,file=heap.hprof <pid>
# Open in Eclipse MAT
```

### JVM Flags for Performance

```bash
# G1GC (Recommended for Java 26)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200

# Virtual Thread Tuning
-Djdk.virtualThreadScheduler.parallelism=cpu
-Djdk.virtualThreadScheduler.maxPoolSize=256

# Memory Management
-Xms4g -Xmx4g
-XX:+UseStringDeduplication

# Diagnostics
-XX:+HeapDumpOnOutOfMemoryError
-Xlog:gc*:gc.log:time,uptime
```

### Monitoring Code

```java
// Add metrics to processes
class MetricProc extends Proc<S, M> {
    private final MeterRegistry registry = PrometheusMeterRegistry();

    protected void init() {
        registry.gauge("proc.mailbox.size", this, proc -> proc.mailboxSize());
    }

    protected void handle(M msg) {
        Timer.Sample sample = Timer.start(registry);
        try {
            process(msg);
        } finally {
            sample.stop(registry.timer("proc.handler.duration"));
        }
    }
}
```

---

## Related Issues

- **Runtime Issues:** If performance degrades over time, see `runtime-issues.md`
- **Build Issues:** If compilation is slow, see `build-issues.md`
- **Debugging:** For profiling techniques, see `debugging-techniques.md`
