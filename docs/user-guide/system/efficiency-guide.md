# Efficiency Guide

Techniques for writing high-performance JOTP systems. This guide covers virtual thread mechanics, message-passing throughput, common anti-patterns, and profiling.

---

## Virtual Threads: The Foundation of Efficiency

Every `Proc<S,M>` runs in a **virtual thread** — a JVM-managed lightweight thread. Understanding virtual thread scheduling is the key to JOTP performance.

### Memory Characteristics

| Resource | Platform Thread | Virtual Thread | JOTP Process |
|----------|-----------------|----------------|--------------|
| Stack size (initial) | 512 KB – 1 MB | ~1 KB (grows on demand) | ~2-4 KB (mailbox + stack) |
| Max per JVM | ~10,000 | ~1,000,000+ | ~500,000+ |
| Kernel context switch | Yes | No | No |
| GC pressure | High | Low | Low |

A typical JOTP deployment can run **100,000+ concurrent processes** on a 4-core machine with 4 GB RAM.

### The Carrier Thread Model

Virtual threads are multiplexed over a pool of **carrier threads** (platform threads):

```
Carrier Threads (1 per CPU core, typically)
├── Virtual Thread: PaymentProc (blocked on mailbox)
├── Virtual Thread: InventoryProc (processing message)  ← running
├── Virtual Thread: NotificationProc (blocked on I/O)
└── Virtual Thread: CacheProc (processing message)      ← running
```

When a virtual thread **blocks** (waiting for mailbox, I/O, database), the JVM unmounts it from the carrier thread. The carrier becomes available to run other virtual threads. This is **cooperative scheduling** — not preemptive.

### What Causes Blocking (Good)

These blocking operations unmount from the carrier thread and allow other virtual threads to run:

- `mailbox.take()` — waiting for a message
- `Socket.read()`, `InputStream.read()` — network I/O
- `Thread.sleep()`
- `Lock.lock()`, `ReentrantLock`
- `CompletableFuture.get()`

### What Pins the Carrier Thread (Bad)

These operations **pin** the virtual thread to its carrier, blocking other virtual threads:

- `synchronized` blocks/methods
- Native method calls (`JNI`)
- `Object.wait()` inside `synchronized`

**Fix:** Replace `synchronized` with `ReentrantLock`:

```java
// Bad: pins carrier thread
synchronized void criticalSection() {
    doWork();
}

// Good: unmounts carrier when waiting
private final ReentrantLock lock = new ReentrantLock();
void criticalSection() {
    lock.lock();
    try { doWork(); }
    finally { lock.unlock(); }
}
```

JOTP's `Proc` already avoids `synchronized` internally — but your handler code should too.

---

## Message Passing Performance

### Throughput Benchmarks

Typical throughput on a 4-core machine (JMH, Java 26):

| Pattern | Messages/sec |
|---------|-------------|
| Fire-and-forget (single proc) | ~3,000,000 |
| Request-reply (single proc) | ~800,000 |
| Fan-out to 100 procs | ~50,000,000 total |
| ProcessRegistry lookup | ~5,000,000 |

### Mailbox Tuning

Default mailbox capacity is 1024. For high-throughput processes:

```java
// Set globally
System.setProperty("jotp.mailbox.capacity", "8192");

// Or per-process (if supported by Proc builder API)
Proc.builder()
    .mailboxCapacity(8192)
    .start(handler, initialState);
```

A full mailbox causes `send()` to **block**. This is intentional backpressure — the sender slows down when the receiver is overwhelmed.

### Batch Processing

Group small messages into batches to amortize mailbox overhead:

```java
// Instead of: 1000 individual sends
for (Item item : items) {
    proc.send(new ProcessItem(item));  // 1000 mailbox operations
}

// Better: one batched send
proc.send(new ProcessBatch(items));    // 1 mailbox operation
```

### Avoid Unnecessary `ask()`

`ask()` is synchronous — it blocks the caller until a reply arrives. Use fire-and-forget `send()` when you don't need the result:

```java
// Slow: blocks on every audit event
for (var event : events) {
    auditProc.ask(replyTo -> new AuditEvent(event, replyTo), timeout);
}

// Fast: async fan-out
for (var event : events) {
    auditProc.send(new AuditEvent(event));
}
```

---

## Anti-Patterns to Avoid

### 1. Shared Mutable State Between Processes

```java
// Bad: race condition
Map<String, Integer> shared = new ConcurrentHashMap<>();

var proc1 = Proc.start(state -> msg -> { shared.put("key", 1); return state; }, null);
var proc2 = Proc.start(state -> msg -> { shared.put("key", 2); return state; }, null);
// proc1 and proc2 race on 'shared'
```

```java
// Good: state is isolated per process; communicate via messages
var stateProc = Proc.start(
    (Map<String, Integer> state) -> msg -> switch (msg) {
        case Put(var k, var v)              -> { state.put(k, v); yield state; }
        case Get(var k, var replyTo)        -> { replyTo.send(state.get(k)); yield state; }
    },
    new HashMap<>()
);
```

### 2. Blocking Inside a Handler

```java
// Bad: blocks the virtual thread on a long operation inline
var proc = Proc.start(
    state -> msg -> {
        Thread.sleep(5000);  // Blocks! (Actually OK for virtual threads, but wastes mailbox capacity)
        return doWork(msg);
    },
    initialState
);
```

```java
// Good: delegate long work to a separate process / CrashRecovery
var proc = Proc.start(
    state -> msg -> {
        var result = CrashRecovery.withRetry(
            () -> externalService.call(msg),
            RetryPolicy.exponentialBackoff(3, Duration.ofMillis(100))
        );
        return state.withResult(result);
    },
    initialState
);
```

### 3. Overly Deep Supervision Trees

Each supervision level adds restart latency. Keep trees to 2-3 levels:

```java
// Too deep (5 levels) — restart propagation is slow and hard to reason about
Root → RegionSup → ServiceSup → SubsystemSup → FeatureSup → WorkerProc

// Good (2-3 levels)
Root → ServiceSup → WorkerProc
```

### 4. Calling `ask()` from Inside a Handler

This can deadlock if the callee tries to call back into the caller's mailbox:

```java
// Risky: A asks B, B tries to ask A → deadlock
var handlerA = state -> msg -> {
    int result = procB.ask(replyTo -> new Req(replyTo), timeout);  // A waits for B
    return state + result;
};
var handlerB = state -> msg -> {
    int result = procA.ask(replyTo -> new Req(replyTo), timeout);  // B waits for A → DEADLOCK
    return state + result;
};
```

Avoid circular `ask()` chains. Use `send()` + reply-to pattern for bidirectional communication.

### 5. Storing Non-Serializable State

Process state does not need to be serializable, but it must be **immutable or single-owner**:

```java
// Bad: mutable state shared outside the process
List<String> mutableList = new ArrayList<>();
var proc = Proc.start(
    state -> msg -> { mutableList.add(msg.toString()); return state; },  // mutableList escapes!
    initialState
);
mutableList.add("external mutation");  // race condition

// Good: state is owned entirely by the process
var proc = Proc.start(
    (List<String> state) -> msg -> {
        var newState = new ArrayList<>(state);
        newState.add(msg.toString());
        return Collections.unmodifiableList(newState);
    },
    new ArrayList<>()
);
```

---

## Supervision Tree Sizing

### Estimating Process Count

A rule of thumb:

- 1 process per **independent lifecycle unit** (one per tenant, one per connection, one per resource)
- Not one process per request — use `Parallel` for short-lived fan-out instead

```java
// Good: 1 process per tenant (long-lived, supervised)
for (var tenant : tenants) {
    tenantSupervisor.add("tenant-" + tenant.id(), () -> TenantProc.start(tenant));
}

// Good: Parallel for per-request fan-out (short-lived)
var results = Parallel.map(requests, req -> processRequest(req));
```

### JVM Flags for High Process Counts

```bash
java \
  --enable-preview \
  -XX:+UseZGC \                    # Low-pause GC (important for many virtual threads)
  -XX:ZUncommitDelay=300 \         # Keep memory available for burst traffic
  -Djdk.virtualThreadScheduler.parallelism=8 \  # Carrier thread count (default: CPU cores)
  -jar myapp.jar
```

---

## Profiling and Benchmarking

### JFR (Java Flight Recorder)

```bash
java \
  --enable-preview \
  -XX:StartFlightRecording=duration=60s,filename=jotp-profile.jfr \
  -jar myapp.jar

# Analyze
jfr print --events jdk.VirtualThreadPinned jotp-profile.jfr
```

`jdk.VirtualThreadPinned` events indicate carrier thread pinning — high counts mean you have `synchronized` blocks to fix.

### JMH Benchmarks

JOTP includes JMH in its test dependencies. Write benchmarks for critical paths:

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ProcBenchmark {
    private Proc<Integer, Integer> proc;

    @Setup
    public void setup() {
        proc = Proc.start(state -> msg -> state + msg, 0);
    }

    @Benchmark
    public void sendThroughput() {
        proc.send(1);
    }

    @Benchmark
    public int askRoundTrip() throws Exception {
        return proc.ask(r -> 1, Duration.ofSeconds(1));
    }
}
```

```bash
mvnd test -Dtest=ProcBenchmark -Djmh.run=true
```

### `ProcSys` for Runtime Metrics

```java
// Periodically collect stats from all processes
var stats = ProcessRegistry.registered().stream()
    .map(name -> ProcessRegistry.whereis(name))
    .flatMap(Optional::stream)
    .map(ref -> ProcSys.statistics(ref))
    .toList();

stats.forEach(s -> {
    log.info("proc={} messages={} queueLen={} uptime={}",
        s.name(), s.messageCount(), s.queueLength(), s.uptime());
});
```

---

*Next: [Interoperability Guide](interoperability.md)*
