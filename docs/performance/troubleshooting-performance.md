# JOTP Performance Troubleshooting Guide

**Version:** 1.0
**Last Updated:** 2026-03-15

## Overview

This guide covers diagnosing and resolving common performance problems in JOTP applications, including high latency issues, memory leaks, thread pinning, GC pressure, and other performance bottlenecks.

---

## 1. High Latency Diagnosis

### 1.1 Symptoms

- P99 message latency >1ms
- Slow response times
- Mailbox depth increasing
- Consumer lag growing

### 1.2 Diagnostic Steps

**Step 1: Measure latency distribution**
```java
// Add latency tracking
@Component
public class LatencyTracker {
    private final Histogram latencyHistogram = Histogram.build()
        .name("jotp_message_latency")
        .help("Message delivery latency")
        .register();

    public void recordLatency(Duration duration) {
        latencyHistogram.observe(duration.toMillis());
    }
}

// Use in message handler
long startTime = System.nanoTime();
proc.tell(message);
long duration = System.nanoTime() - startTime;
latencyTracker.recordLatency(Duration.ofNanos(duration));
```

**Step 2: Check mailbox depth**
```bash
# Using JMX or metrics
jconsole -> io.github.seanchatmangpt.jotp -> MailboxDepth

# Or using Prometheus
avg(jotp_mailbox_depth) by (process_name)
```

**Step 3: Profile message processing**
```bash
# Use async-profiler to find slow handlers
profiler.sh -d 30 -f latency-profile.html -o flamegraph \
  --include 'io.github.seanchatmangpt.jotp.*' \
  pid
```

### 1.3 Common Causes and Solutions

| Cause | Diagnosis | Solution |
|-------|-----------|----------|
| **Slow message handler** | Flame graph shows wide handler method | Optimize handler logic, move to async |
| **Blocking operations** | Virtual thread pinned | Use `CompletableFuture`, avoid native code |
| **Large messages** | Allocation rate high | Use zero-copy, reduce message size |
| **GC pauses** | GC pause times >10ms | Tune GC, reduce allocation rate |
| **Contention** | Lock contention high | Use lock-free patterns, reduce synchronization |

### 1.4 Resolution Strategies

**Strategy 1: Optimize message handler**
```java
// BEFORE: Synchronous processing
@Override
protected S loop(S state) {
    M msg = mailbox.poll();
    if (msg != null) {
        return processSlowly(state, msg);  // Takes 10ms
    }
    return state;
}

// AFTER: Async processing
@Override
protected S loop(S state) {
    M msg = mailbox.poll();
    if (msg != null) {
        CompletableFuture<S> future = CompletableFuture.supplyAsync(
            () -> processSlowly(state, msg)
        );
        // Don't wait, process next message
        return state;
    }
    return state;
}
```

**Strategy 2: Increase parallelism**
```java
// Scale out consumers
Proc<S, M>[] workers = new Proc[10];
for (int i = 0; i < 10; i++) {
    workers[i] = Proc.create(state);
}

// Distribute messages
Dispatcher dispatcher = new Dispatcher(workers);
dispatcher.dispatch(message);
```

---

## 2. Memory Leak Detection

### 2.1 Symptoms

- Heap usage continuously increasing
- OOM errors after running for hours
- GC pause times increasing
- Process count not decreasing

### 2.2 Diagnostic Tools

**Tool 1: Heap dump analysis**
```bash
# Trigger heap dump
jmap -dump:live,format=b,file=heap.hprof <pid>

# Analyze with VisualVM
visualvm -J-Xmx2g heap.hprof

# Look for:
# - Increasing Proc instances
# - Large mailboxes
# - Unreleased message references
```

**Tool 2: Memory profiling**
```bash
# Profile allocations with async-profiler
profiler.sh -d 60 -f alloc.html --alloc -j pid

# Look for:
# - High allocation rate in hot path
# - Large objects allocated per message
# - Collections growing unbounded
```

**Tool 3: JFR memory events**
```bash
# Record with JFR
java -XX:StartFlightRecording=filename=memory.jfr,duration=60s \
     -jar target/jotp.jar

# Analyze allocation rate
jfr print --events jdk.ObjectAllocationInNewTLAB memory.jfr | \
  wc -l
```

### 2.3 Common Memory Leaks

**Leak 1: Unbounded mailboxes**
```java
// BAD: Unbounded mailbox with slow consumer
Proc<S, M> proc = Proc.create(state);  // No capacity limit

// GOOD: Bounded mailbox
Proc<S, M> proc = Proc.create(state, 10_000);
```

**Leak 2: Message accumulation**
```java
// BAD: Accumulating messages in state
record State(List<M> pending) {}  // Grows unbounded

// GOOD: Process immediately or batch
record State(Deque<M> pending, int maxSize) {
    State add(M msg) {
        Deque<M> newPending = new ArrayDeque<>(pending);
        if (newPending.size() >= maxSize) {
            newPending.pollFirst();  // Drop oldest
        }
        newPending.addLast(msg);
        return new State(newPending, maxSize);
    }
}
```

**Leak 3: Uncleaned references**
```java
// BAD: Strong references prevent GC
class ProcessRegistry {
    private final Map<String, Proc<S, M>> registry = new HashMap<>();

    void register(String name, Proc<S, M> proc) {
        registry.put(name, proc);  // Never removed
    }
}

// GOOD: Use weak references or cleanup
class ProcessRegistry {
    private final Map<String, WeakReference<Proc<S, M>>> registry =
        new HashMap<>();

    void register(String name, Proc<S, M> proc) {
        registry.put(name, new WeakReference<>(proc));
    }
}
```

### 2.4 Resolution Strategies

**Strategy 1: Implement mailbox limits**
```java
Proc<S, M> proc = Proc.builder()
    .initialState(state)
    .mailboxCapacity(10_000)
    .onMessageDropped((msg) -> {
        log.warn("Message dropped: {}", msg);
        droppedMessages.increment();
    })
    .build();
```

**Strategy 2: Add periodic cleanup**
```java
class CleanupProc<S, M> extends Proc<S, M> {
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(5);

    @Override
    protected S loop(S state) throws InterruptedException {
        M msg = mailbox.poll(CLEANUP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        if (msg == null) {
            // Timeout - perform cleanup
            return cleanup(state);
        }

        return handleMessage(state, msg);
    }

    private S cleanup(S state) {
        // Remove stale entries, clear caches, etc.
        log.debug("Performing periodic cleanup");
        return state.cleanup();
    }
}
```

---

## 3. Thread Pinning Issues

### 3.1 Symptoms

- Carrier threads exhausted
- Poor throughput despite available virtual threads
- Virtual thread parking not working
- High CPU usage with low throughput

### 3.2 Diagnosis

**Check for pinned threads:**
```bash
# Using JFR
java -XX:StartFlightRecording=filename=pinning.jfr,duration=60s \
     -jar target/jotp.jar

# Analyze pinning events
jfr print --events jdk.VirtualThreadPinned pinning.jfr
```

**Profile carrier thread usage:**
```bash
# Check carrier thread pool
jconsole -> java.util.concurrent -> ForkJoinPool

# Look for:
# - All carrier threads busy
# - Long park times
# - Pinned virtual threads
```

### 3.3 Common Pinning Causes

**Cause 1: Synchronized blocks**
```java
// BAD: Pins virtual thread
synchronized (lock) {
    doBlockingOperation();  // Pins carrier thread
}

// GOOD: Use ReentrantLock
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    doBlockingOperation();  // Doesn't pin
} finally {
    lock.unlock();
}
```

**Cause 2: Native code**
```java
// BAD: Native code pins thread
public native void nativeOperation();

// GOOD: Offload to platform thread
CompletableFuture.runAsync(
    () -> nativeOperation(),
    Executors.newCachedThreadPool()  // Platform thread pool
);
```

**Cause 3: ThreadLocal operations**
```java
// BAD: Heavy ThreadLocal usage
ThreadLocal<LargeObject> threadLocal = new ThreadLocal<>();

// GOOD: Use scoped values (Java 21+)
static final ScopedValue<LargeObject> SCOPED_VALUE =
    ScopedValue.newInstance();

ScopedValue.where(SCOPED_VALUE, largeObject)
    .run(() -> {
        // Use SCOPED_VALUE.get()
    });
```

### 3.4 Resolution Strategies

**Strategy 1: Replace synchronized with locks**
```java
// Find synchronized blocks
javap -v MyClass.class | grep synchronized

// Replace with ReentrantLock
private final ReentrantLock lock = new ReentrantLock();

public void criticalSection() {
    lock.lock();
    try {
        // Critical section
    } finally {
        lock.unlock();
    }
}
```

**Strategy 2: Move blocking off carrier threads**
```java
// Offload blocking operations
public CompletableFuture<Result> asyncOperation(Input input) {
    return CompletableFuture.supplyAsync(
        () -> {
            // Blocking operation here
            return doBlocking(input);
        },
        Executors.newCachedThreadPool()  // Platform thread pool
    );
}
```

---

## 4. GC Pressure Analysis

### 4.1 Symptoms

- Frequent GC pauses
- High GC CPU usage
- Long pause times (>100ms)
- Low allocation rate

### 4.2 Diagnostic Tools

**Tool 1: GC logging**
```bash
# Enable GC logging
java -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -jar target/jotp.jar

# Analyze GC logs
grep "GC(gc)" gc.log | \
  awk '{print $6}' | \
  sort -n | \
  tail -10
```

**Tool 2: JFR GC events**
```bash
# Record GC events
java -XX:StartFlightRecording=filename=gc.jfr,duration=60s \
     -jar target/jotp.jar

# Analyze GC pauses
jfr print --events jdk.GCPhasePause gc.jfr
```

### 4.3 Common GC Issues

**Issue 1: High allocation rate**
```java
// BAD: Allocate per message
@Override
protected S loop(S state) {
    M msg = mailbox.poll();
    if (msg != null) {
        List<Result> results = new ArrayList<>();  // Allocation!
        return process(state, msg, results);
    }
    return state;
}

// GOOD: Reuse objects
private final ThreadLocal<List<Result>> resultBuffer =
    ThreadLocal.withInitial(() -> new ArrayList<>(1000));

@Override
protected S loop(S state) {
    M msg = mailbox.poll();
    if (msg != null) {
        List<Result> results = resultBuffer.get();
        results.clear();
        return process(state, msg, results);
    }
    return state;
}
```

**Issue 2: Large objects**
```java
// BAD: Large message objects
record LargeMessage(byte[] data) {  // 1MB per message
}

// GOOD: Use references
record LargeMessageReference(ByteBuffer data) {
    static LargeMessageReference wrap(byte[] data) {
        return new LargeMessageReference(
            ByteBuffer.wrap(data).asReadOnlyBuffer()
        );
    }
}
```

### 4.4 Resolution Strategies

**Strategy 1: Tune GC**
```bash
# Use G1GC for low latency
java -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=50 \
     -XX:G1HeapRegionSize=16m \
     -jar target/jotp.jar

# Or ZGC for very low latency
java -XX:+UseZGC \
     -jar target/jotp.jar
```

**Strategy 2: Reduce allocation rate**
```java
// Use object pooling
class MessagePool {
    private static final int MAX_SIZE = 10000;
    private final Queue<Message> pool = new ConcurrentLinkedQueue<>();

    public Message acquire() {
        Message msg = pool.poll();
        return msg != null ? msg : new Message();
    }

    public void release(Message msg) {
        if (pool.size() < MAX_SIZE) {
            msg.reset();
            pool.offer(msg);
        }
    }
}
```

---

## 5. Throughput Degradation

### 5.1 Symptoms

- Message throughput decreasing over time
- CPU usage high but throughput low
- Increasing latency
- Contention warnings

### 5.2 Diagnostic Steps

**Step 1: Measure throughput over time**
```bash
# Plot throughput rate
rate(jotp_message_throughput_count[5m])

# Look for:
# - Declining trend
# - Step changes
# - Periodic patterns
```

**Step 2: Check for contention**
```bash
# Profile locks
java -XX:StartFlightRecording=filename=contention.jfr,duration=60s \
     -Djdk.Lock.preamble=true \
     -jar target/jotp.jar

jfr print --events jdk.JavaMonitorEnter contention.jfr
```

### 5.3 Common Causes

**Cause 1: Lock contention**
```java
// BAD: Contended lock
private final Lock lock = new ReentrantLock();

public void process(Message msg) {
    lock.lock();
    try {
        // Critical section held too long
        heavyComputation(msg);
    } finally {
        lock.unlock();
    }
}

// GOOD: Minimize critical section
public void process(Message msg) {
    Result result = precompute(msg);
    lock.lock();
    try {
        // Only update shared state
        updateSharedState(result);
    } finally {
        lock.unlock();
    }
}
```

**Cause 2: Excessive synchronization**
```java
// BAD: Synchronized on frequently called method
public synchronized void tell(Message msg) {
    mailbox.offer(msg);
}

// GOOD: Use concurrent collection
public void tell(Message msg) {
    mailbox.offer(msg);  // LinkedTransferQueue is thread-safe
}
```

### 5.4 Resolution Strategies

**Strategy 1: Reduce lock scope**
```java
// BEFORE: Large critical section
synchronized (lock) {
    readData();
    processData();
    writeData();
}

// AFTER: Minimal critical section
readData();
synchronized (lock) {
    updateSharedState();
}
processData();
synchronized (lock) {
    writeData();
}
```

**Strategy 2: Use lock-free data structures**
```java
// Replace synchronized collections
// BEFORE: Collections.synchronizedList(new ArrayList<>())
// AFTER: new ConcurrentLinkedQueue<>()

// Replace synchronized maps
// BEFORE: Collections.synchronizedMap(new HashMap<>())
// AFTER: new ConcurrentHashMap<>()
```

---

## 6. Troubleshooting Checklist

### 6.1 Initial Diagnosis

- [ ] Measure current performance (throughput, latency)
- [ ] Check system resources (CPU, memory, GC)
- [ ] Profile with async-profiler
- [ ] Analyze GC logs
- [ ] Check for pinned threads

### 6.2 Common Problems

- [ ] High latency → Profile handlers, check GC
- [ ] Memory leaks → Heap dump, analyze references
- [ ] Thread pinning → Check synchronized, native code
- [ ] GC pressure → Reduce allocation, tune GC
- [ ] Low throughput → Check contention, scale consumers

### 6.3 Resolution

- [ ] Identify root cause (profile, measure)
- [ ] Implement fix
- [ ] Verify improvement
- [ ] Document for future reference
- [ ] Update monitoring/alerting

---

## 7. Quick Reference

### Diagnostic Commands

```bash
# Heap dump
jmap -dump:live,format=b,file=heap.hprof <pid>

# Thread dump
jstack <pid> > thread-dump.txt

# GC logging
java -Xlog:gc*:file=gc.log -jar app.jar

# JFR recording
java -XX:StartFlightRecording=filename=recording.jfr -jar app.jar

# Async profiler
profiler.sh -d 30 -f profile.html -o flamegraph <pid>
```

### Performance Metrics

| Metric | Healthy | Warning | Critical |
|--------|---------|---------|----------|
| **Message latency P99** | <1ms | 1-10ms | >10ms |
| **Throughput** | >1M ops/sec | 100K-1M ops/sec | <100K ops/sec |
| **GC pause time** | <10ms | 10-50ms | >50ms |
| **Heap usage** | <70% | 70-90% | >90% |
| **Carrier threads** | <80% busy | 80-95% busy | >95% busy |

### Common Issues Quick Fix

| Issue | Quick Fix | Long-term Solution |
|-------|-----------|-------------------|
| **High latency** | Scale consumers | Optimize handlers |
| **Memory leak** | Restart JVM | Fix leak, add limits |
| **Thread pinning** | Increase carriers | Remove synchronized |
| **GC pressure** | Increase heap | Reduce allocation |

---

**Next Steps:**
- [ ] Set up monitoring: `production-monitoring.md`
- [ ] Profile application: `profiling.md`
- [ ] Optimize performance: `optimization-patterns.md`
- [ ] Review JVM tuning: `jvm-tuning.md`
