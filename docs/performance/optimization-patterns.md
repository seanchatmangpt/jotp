# JOTP Optimization Patterns

**Version:** 1.0
**Last Updated:** 2026-03-15

## Overview

This guide covers advanced optimization patterns for JOTP applications, including message batching, adaptive timeouts, memory reduction techniques, and zero-copy message passing strategies.

---

## 1. Message Batching for High-Frequency Scenarios

### 1.1 Batch Processing Pattern

**Problem:** High message frequency causes overhead from individual message handling

**Solution:** Process multiple messages in a single loop iteration

```java
// BEFORE: Process one message at a time
while (true) {
    M msg = mailbox.poll();
    if (msg != null) {
        process(msg);
    }
}

// AFTER: Batch processing
while (true) {
    List<M> batch = new ArrayList<>(100);
    mailbox.drainTo(batch, 100);

    if (batch.isEmpty()) {
        // Fall back to single message
        M msg = mailbox.poll(100, TimeUnit.MILLISECONDS);
        if (msg != null) {
            process(msg);
        }
    } else {
        // Process batch
        processBatch(batch);
    }
}

private void processBatch(List<M> batch) {
    // Batch processing logic
    // 20-30% faster than individual processing
}
```

**Performance improvement:** 20-30% throughput increase for >1K messages/sec

### 1.2 Batch Size Optimization

| Message Rate | Optimal Batch Size | Throughput | Latency Impact |
|--------------|-------------------|------------|----------------|
| <100/sec | 1 | Baseline | None |
| 1K/sec | 10-50 | +15% | +5ms P99 |
| 10K/sec | 100-500 | +25% | +10ms P99 |
| 100K/sec | 1000-5000 | +30% | +20ms P99 |
| 1M+/sec | 5000+ | +35% | +50ms P99 |

### 1.3 Adaptive Batching

**Dynamically adjust batch size based on load:**

```java
class AdaptiveBatchProc<S, M> extends Proc<S, M> {
    private static final int MIN_BATCH = 10;
    private static final int MAX_BATCH = 10000;
    private int currentBatchSize = 100;

    @Override
    protected S loop(S state) throws InterruptedException {
        List<M> batch = new ArrayList<>(currentBatchSize);
        mailbox.drainTo(batch, currentBatchSize);

        // Adapt batch size
        if (batch.size() == currentBatchSize && currentBatchSize < MAX_BATCH) {
            // Mailbox never emptied, increase batch
            currentBatchSize = Math.min(currentBatchSize * 2, MAX_BATCH);
        } else if (batch.size() < currentBatchSize / 2 && currentBatchSize > MIN_BATCH) {
            // Mailbox emptied quickly, decrease batch
            currentBatchSize = Math.max(currentBatchSize / 2, MIN_BATCH);
        }

        return processBatch(state, batch);
    }
}
```

---

## 2. Adaptive Timeouts

### 2.1 Dynamic Timeout Adjustment

**Problem:** Fixed timeouts don't adapt to changing system conditions

**Solution:** Adjust timeouts based on historical response times

```java
class AdaptiveTimeoutProc<S, M> extends Proc<S, M> {
    private static final int HISTORY_SIZE = 100;
    private final CircularFifoQueue<Duration> history =
        new CircularFifoQueue<>(HISTORY_SIZE);
    private Duration currentTimeout = Duration.ofMillis(100);

    private void updateTimeout(Duration actual) {
        history.add(actual);

        // Calculate P95 of recent times
        List<Duration> sorted = new ArrayList<>(history);
        sorted.sort(Duration::compareTo);
        Duration p95 = sorted.get((int) (sorted.size() * 0.95));

        // Set timeout to 2× P95
        currentTimeout = p95.multipliedBy(2);
    }

    public CompletableFuture<R> askWithTimeout(M message) {
        Duration timeout = currentTimeout;
        CompletableFuture<R> future = ask(message);

        // Record actual time
        long startTime = System.nanoTime();
        future.whenComplete((result, error) -> {
            Duration actual = Duration.ofNanos(System.nanoTime() - startTime);
            updateTimeout(actual);
        });

        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
```

**Benefits:**
- Prevents premature timeouts during load spikes
- Reduces unnecessary waiting during quiet periods
- Adapts to changing system conditions

### 2.2 Timeout Strategies

| Strategy | Use Case | Formula |
|----------|----------|---------|
| **Fixed** | Stable latency | `timeout = constant` |
| **P95-based** | Variable latency | `timeout = 2 × P95` |
| **Exponential moving average** | Gradual changes | `timeout = 0.8 × timeout + 0.2 × actual` |
| **Predictive** | Seasonal patterns | `timeout = f(time_of_day, day_of_week)` |
| **Machine learning** | Complex patterns | `timeout = ML.predict(current_conditions)` |

---

## 3. Value Types for Memory Reduction

### 3.1 Using Java 26 Value Types

**Problem:** Reference types add overhead for small data structures

**Solution:** Use value types (Project Valhalla) for state and messages

```java
// BEFORE: Reference type (16 bytes header + data)
record Point(double x, double y, double z) {
    // Total: 16 + 8 + 8 + 8 = 40 bytes
}

// AFTER: Value type (data only)
value class Point {
    final double x;
    final double y;
    final double z;

    Point(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    // Total: 8 + 8 + 8 = 24 bytes (40% reduction)
}
```

**Memory savings:** 30-50% for small objects

### 3.2 Flattening Data Structures

**Avoid nested collections:**

```java
// BEFORE: Nested (high overhead)
record NestedState(List<Point> points) {
    // Each Point: 40 bytes
    // List overhead: 24 bytes
    // Total: 64 + (40 × N) bytes
}

// AFTER: Flattened array
value class FlatState {
    final double[] points;  // [x1, y1, z1, x2, y2, z2, ...]

    FlatState(double[] points) {
        this.points = points;
    }
    // Total: 24 + (24 × N) bytes (40% reduction)
}
```

### 3.3 Primitive Collections

**Use primitive collections instead of boxed types:**

```java
// BEFORE: Boxed integers (16 bytes per Integer)
List<Integer> ids = new ArrayList<>();
ids.add(123);  // 16 bytes

// AFTER: Primitive integers (4 bytes per int)
IntArrayList ids = new IntArrayList();
ids.add(123);  // 4 bytes (75% reduction)
```

---

## 4. Zero-Copy Message Passing

### 4.1 Read-Only Views

**Problem:** Copying large messages is expensive

**Solution:** Pass read-only views instead of copying

```java
// BEFORE: Copy message
record DataMessage(byte[] data) {
    DataMessage copy() {
        return new DataMessage(data.clone());  // Expensive!
    }
}

// AFTER: Share read-only view
record DataMessage(ByteBuffer data) {
    // ByteBuffer is a view, not a copy
    static DataMessage wrap(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).asReadOnlyBuffer();
        return new DataMessage(buffer);
    }
}
```

**Performance:** 10-100× faster for large messages (>1KB)

### 4.2 Flyweight Pattern

**Reuse common message instances:**

```java
class MessageFlyweight {
    private static final Map<String, Message> CACHE = new ConcurrentHashMap<>();

    public static Message getMessage(String type, String payload) {
        String key = type + ":" + payload;
        return CACHE.computeIfAbsent(key, k ->
            new Message(type, payload)
        );
    }
}

// Usage
Message msg = MessageFlyweight.getMessage("status", "ok");
// Same object returned for identical messages
```

**Benefits:**
- Reduces allocation rate
- Improves GC performance
- Saves memory for duplicate messages

### 4.3 Off-Heap Memory

**Use direct buffers for very large messages:**

```java
// Allocate off-heap memory
ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);  // 1MB

// Pass reference (no copy)
LargeMessage msg = new LargeMessage(buffer);

// JVM doesn't GC this memory
// Remember to deallocate when done
```

**Use cases:**
- Messages >1MB
- Intergop with native code
- Shared memory between JVMs

---

## 5. Process Pooling

### 5.1 Object Pool Pattern

**Problem:** High process creation/destruction overhead

**Solution:** Reuse processes instead of recreating

```java
class ProcPool<S, M> {
    private final Queue<Proc<S, M>> available = new ConcurrentLinkedQueue<>();
    private final Supplier<S> initialStateSupplier;
    private final int maxSize;

    public Proc<S, M> acquire() {
        Proc<S, M> proc = available.poll();
        if (proc == null) {
            proc = Proc.create(initialStateSupplier.get());
        }
        return proc;
    }

    public void release(Proc<S, M> proc) {
        if (available.size() < maxSize) {
            proc.reset(initialStateSupplier.get());
            available.offer(proc);
        } else {
            proc.stop();
        }
    }
}
```

**Performance:** 5-10× faster than creating new processes

### 5.2 Work Stealing Pool

**Distribute work across pooled processes:**

```java
class WorkStealingPool<S, M> {
    private final Proc<S, M>[] workers;
    private final AtomicInteger current = new AtomicInteger(0);

    public void submit(M message) {
        // Round-robin distribution
        int index = current.getAndIncrement() % workers.length;
        workers[index].tell(message);
    }

    // Workers steal from each other if idle
    public void enableWorkStealing() {
        for (Proc<S, M> worker : workers) {
            worker.tell(new EnableStealingMessage(workers));
        }
    }
}
```

---

## 6. Specialized Data Structures

### 6.1 Ring Buffer for Mailboxes

**Use lock-free ring buffer for single-producer, single-consumer:**

```java
class RingBufferMailbox<M> {
    private final M[] buffer;
    private final int mask;
    private final AtomicLong tail = new AtomicLong(0);
    private final AtomicLong head = new AtomicLong(0);

    @SuppressWarnings("unchecked")
    public RingBufferMailbox(int capacity) {
        // Must be power of 2
        if ((capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("Capacity must be power of 2");
        }
        this.buffer = (M[]) new Object[capacity];
        this.mask = capacity - 1;
    }

    public boolean offer(M message) {
        long currentTail = tail.get();
        long nextTail = currentTail + 1;

        if (nextTail - head.get() > buffer.length) {
            return false;  // Full
        }

        buffer[(int) (currentTail & mask)] = message;
        tail.lazySet(nextTail);
        return true;
    }

    public M poll() {
        long currentHead = head.get();
        if (currentHead >= tail.get()) {
            return null;  // Empty
        }

        M message = buffer[(int) (currentHead & mask)];
        head.lazySet(currentHead + 1);
        return message;
    }
}
```

**Performance:** 2-3× faster than LinkedTransferQueue for SPMC scenarios

### 6.2 Concurrent HashSet for Registry

**Use non-blocking hash set for process registry:**

```java
class ProcRegistry {
    private final ConcurrentHashMap<String, ProcRef<S, M>> registry =
        new ConcurrentHashMap<>();

    private final LongAddator lookupCount = new LongAdder();

    public ProcRef<S, M> lookup(String name) {
        lookupCount.increment();
        return registry.get(name);
    }

    public void register(String name, ProcRef<S, M> ref) {
        registry.put(name, ref);
    }
}
```

---

## 7. CPU Cache Optimization

### 7.1 Cache-Friendly Data Layout

**Arrange data for cache line efficiency:**

```java
// BEFORE: Cache line bouncing
class Counter {
    volatile long count1;  // Cache line 0
    volatile long count2;  // Cache line 0 (false sharing!)
}

// AFTER: Pad to prevent false sharing
class Counter {
    volatile long p1, p2, p3, p4, p5, p6, p7;
    volatile long count1;  // Cache line 0
    volatile long p8, p9, p10, p11, p12, p13, p14, p15;
    volatile long count2;  // Cache line 1
}
```

### 7.2 Hot/Cold Data Separation

**Separate frequently accessed from rarely accessed:**

```java
// BEFORE: Mixed hot/cold
class ProcessState {
    // Hot (accessed every message)
    int counter;

    // Cold (accessed rarely)
    Map<String, Object> metadata;
    List<StackTraceElement> lastCrash;
}

// AFTER: Separate
class HotState {
    int counter;  // Cache-friendly
}

class ColdState {
    Map<String, Object> metadata;
    List<StackTraceElement> lastCrash;
}
```

---

## 8. Lock-Free Algorithms

### 8.1 Compare-And-Swap (CAS) Loops

**Use CAS instead of synchronized:**

```java
// BEFORE: Synchronized (blocking)
class SynchronizedCounter {
    private int count = 0;

    public synchronized int increment() {
        return ++count;
    }
}

// AFTER: CAS (non-blocking)
class CasCounter {
    private final AtomicInteger count = new AtomicInteger(0);

    public int increment() {
        int prev, next;
        do {
            prev = count.get();
            next = prev + 1;
        } while (!count.compareAndSet(prev, next));
        return next;
    }
}
```

**Performance:** 5-10× faster under contention

### 8.2 LazySet for Non-Volatile Writes

**Use lazySet for non-critical writes:**

```java
class LazyProc<S, M> extends Proc<S, M> {
    private volatile boolean stopped = false;

    public void stop() {
        // No immediate visibility needed
        // Stopped flag checked in next loop iteration
        stopped = true;  // Regular write
    }

    // Or use lazySet for even better performance
    public void stopLazy() {
        // Write happens eventually
        // Safe for one-time flags
        Stopped.lazySet(true);
    }
}
```

---

## 9. Optimization Checklist

### 9.1 Performance Optimization

- [ ] Profile before optimizing (use JMH)
- [ ] Identify hot spots with flame graphs
- [ ] Optimize critical path first
- [ ] Measure after each change
- [ ] Document trade-offs

### 9.2 Memory Optimization

- [ ] Use value types for small objects
- [ ] Flatten nested data structures
- [ ] Use primitive collections
- [ ] Implement object pooling
- [ ] Reduce allocation rate

### 9.3 Throughput Optimization

- [ ] Implement message batching
- [ ] Use specialized data structures
- [ ] Eliminate locks in hot path
- [ ] Use lock-free algorithms
- [ ] Optimize cache usage

### 9.4 Latency Optimization

- [ ] Minimize message size
- [ ] Use zero-copy passing
- [ ] Implement adaptive timeouts
- [ ] Reduce GC pressure
- [ ] Pin critical threads

---

## 10. Common Pitfalls

### 10.1 Premature Optimization

**Problem:** Optimizing without measuring

**Solution:**
```java
// BAD: Assume this is slow
for (int i = 0; i < 1000; i++) {
    process(messages.get(i));
}

// GOOD: Measure first, then optimize
long start = System.nanoTime();
for (int i = 0; i < 1000; i++) {
    process(messages.get(i));
}
long duration = System.nanoTime() - start;
// Only optimize if duration > threshold
```

### 10.2 Over-Optimization

**Problem:** Optimizing non-critical code

**Solution:**
- Use Amdahl's Law to guide optimization
- Focus on code that executes frequently
- Consider maintenance cost vs. performance gain

### 10.3 Breaking Correctness

**Problem:** Optimizations introduce bugs

**Solution:**
- Keep comprehensive tests
- Use property-based testing
- Validate with formal methods
- Code review all optimizations

---

## 11. Quick Reference

### Optimization Priority

1. **Algorithm selection** - Biggest impact
2. **Data structures** - Significant impact
3. **Memory layout** - Moderate impact
4. **Lock-free patterns** - Moderate impact
5. **CPU cache tuning** - Small impact
6. **Bytecode optimization** - Minimal impact

### Performance Improvement Potential

| Optimization | Potential Gain | Cost |
|--------------|----------------|------|
| **Algorithm change** | 10-100× | High |
| **Batch processing** | 20-30% | Low |
| **Lock-free patterns** | 5-10× | Medium |
| **Value types** | 30-50% memory | Medium |
| **Zero-copy** | 10-100× | Low |

---

**Next Steps:**
- [ ] Profile application: `profiling.md`
- [ ] Tune JVM: `jvm-tuning.md`
- [ ] Monitor production: `production-monitoring.md`
- [ ] Troubleshoot issues: `troubleshooting-performance.md`
