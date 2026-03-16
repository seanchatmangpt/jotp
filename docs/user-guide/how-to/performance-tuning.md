# Performance Tuning JOTP Systems

## The Right Question to Ask

Joe Armstrong, reflecting on decades of building Erlang systems, put it plainly:
> "The real question is never 'how do I make this faster?' It is 'am I doing the right thing at all?'"

Performance work in JOTP starts from the same premise. The primitives are fast by design: `tell()` delivers a message in 125 ns (p50), process creation costs ~50 µs, and the scheduler handles millions of virtual threads without ceremony. When a JOTP system is slow, the cause is almost never the primitives themselves. It is one of three structural problems: mailboxes filling faster than handlers drain them, handlers doing work that blocks the virtual thread scheduler, or GC pressure from a heap sized for a different workload.

Micro-optimizing a `tell()` call when the real problem is a 200ms database query inside a handler is theater. This guide shows you how to find the actual bottleneck first, then fix it with the minimum intervention necessary.

---

## 1. Diagnosing First: The Three Bottlenecks

Every JOTP performance problem fits one of three patterns. Identify which one you have before touching JVM flags or rewriting handlers.

```
Request arrives
      |
      v
 [ Proc mailbox ]
      |
      |-- BOTTLENECK A: Mailbox saturation
      |   Messages accumulate faster than they are consumed.
      |   Signal: mailbox_size > 1000, growing.
      |
      v
 [ Handler function ]
      |
      |-- BOTTLENECK B: Handler slowness
      |   Handler blocks on I/O, holds locks, or does expensive computation.
      |   Signal: CPU < 30% but latency is high. Thread pinning events in JFR.
      |
      v
 [ Heap / GC ]
      |
      |-- BOTTLENECK C: GC pressure
      |   Short-lived allocation from message envelopes or handler state copies
      |   causes frequent minor GC pauses. With >50K processes, Old Gen fills.
      |   Signal: GC pause > 5ms in JFR, throughput drop during full GC.
      |
      v
  Response / state transition
```

Diagnose in order. A handler that looks slow (B) is often slow because its mailbox is always full (A) and work is stale before it starts. GC pressure (C) rarely appears until process count exceeds 50K or message rates exceed 50M/sec sustained.

---

## 2. Step 1: Measuring with ProcSys.getStatistics()

`ProcSys` is the `sys` module equivalent — process introspection without stopping the process. Run this as your baseline before anything else.

```java
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcStatistics;

// Retrieve a live snapshot from any ProcRef
ProcRef<OrderState, OrderMsg> orderProc = registry.whereis("order-processor");

ProcStatistics stats = ProcSys.of(orderProc).getStatistics();

// Log the three signals that matter most
System.out.printf(
    "[%s] mailbox_size=%d  restart_count=%d  last_error=%s%n",
    orderProc.name(),
    stats.mailboxSize(),       // current depth of the message queue
    stats.restartCount(),      // cumulative supervisor restarts
    stats.lastError()          // most recent exception class, or null
);
```

Run this on a 30-second interval in production via a scheduled virtual thread:

```java
Thread.ofVirtual().start(() -> {
    while (!Thread.currentThread().isInterrupted()) {
        logStats(orderProc);
        Thread.sleep(Duration.ofSeconds(30));
    }
});
```

**Interpret the numbers:**

| Signal | Healthy | Investigate | Critical |
|---|---|---|---|
| `mailboxSize` | < 100 | 100–1000 | > 1000 |
| `restartCount` (per hour) | 0–2 | 3–10 | > 10 |
| `lastError` | null | transient | recurring same class |

---

## 3. Step 2: JFR Profiling

Java Flight Recorder provides microsecond-resolution visibility into virtual thread scheduling, allocation, and GC without meaningful overhead (<1% in production). Enable it on startup:

```bash
java \
  -XX:+FlightRecorder \
  -XX:StartFlightRecording=duration=60s,filename=jotp-profile.jfr,settings=profile \
  --enable-preview \
  -jar your-service.jar
```

Or attach to a running process:

```bash
jcmd <pid> JFR.start duration=60s filename=jotp-profile.jfr settings=profile
jcmd <pid> JFR.stop
```

**Events to watch in JDK Mission Control (jmc):**

| Event | What it means for JOTP |
|---|---|
| `jdk.VirtualThreadPinned` | Handler called synchronized code or native method, pinning the carrier thread. Every pinned virtual thread removes one OS thread from the scheduler pool. |
| `jdk.ObjectAllocationInNewTLAB` | Allocation hot spots. In JOTP, look for message envelope classes or large handler state objects allocated at high frequency. |
| `jdk.GarbageCollection` | Pause duration and cause. G1 pauses > 5ms at >10K processes is a signal to evaluate ZGC. |
| `jdk.VirtualThreadStart` / `jdk.VirtualThreadEnd` | Process creation rate. Compare against the ~50 µs creation cost baseline. |
| `jdk.CPULoad` | Distinguish CPU-bound (handler computation) from IO-bound (blocking handler) workloads. |

Open `jotp-profile.jfr` in JDK Mission Control. Navigate to: **Thread** tab -> filter by `VirtualThread` prefix -> sort by wall-clock time. Handlers spending > 1ms in a single invocation are candidates for I/O extraction (see Step 4).

---

## 4. Step 3: Mailbox Saturation

**Detection:** `stats.mailboxSize() > 1000` and growing over consecutive samples. The mailbox never clears between polls.

Mailbox saturation has three and only three causes: producers are too fast, the handler is too slow, or there are too few process instances. The fixes map directly:

**Option A — Scale horizontally (preferred for stateless handlers)**

```java
// Replace one process with a pool of N
int poolSize = Runtime.getRuntime().availableProcessors() * 2;
List<ProcRef<OrderState, OrderMsg>> pool = IntStream.range(0, poolSize)
    .mapToObj(i -> Proc.spawn(OrderState::initial, OrderProcessor::handle, OrderState.initial()))
    .map(p -> ProcRegistry.register("order-pool-" + i, p))
    .toList();

// Round-robin dispatch
AtomicInteger counter = new AtomicInteger();
ProcRef<OrderState, OrderMsg> pick() {
    return pool.get(counter.getAndIncrement() % pool.size());
}
```

**Option B — Make the handler faster** (see Step 4)

**Option C — Bound the mailbox with back-pressure**

```java
// ask() instead of tell() at the producer; let timeout signal back-pressure
try {
    Ack ack = orderProc.ask(new PlaceOrder(orderId, items), Duration.ofMillis(100));
} catch (TimeoutException e) {
    // Producer slows down or sheds load — this IS the back-pressure signal
    metrics.increment("order.backpressure");
    throw new ServiceUnavailableException("Order processor overloaded");
}
```

Do not simply discard messages at the producer without surfacing the signal. Back-pressure must propagate upstream.

---

## 5. Step 4: Handler Optimization

The handler function is the unit of work in JOTP. It must be fast, pure, and non-blocking. Three rules cover 95% of handler optimization work.

**Rule 1: Keep handlers pure.**

A handler that only transforms state and returns a new state value is trivially fast. Every deviation from pure state transformation is a candidate for extraction.

```java
// Good: pure handler, no I/O, no locking
static OrderState handle(OrderState state, OrderMsg msg) {
    return switch (msg) {
        case PlaceOrder(var id, var items) -> state.withOrder(id, items);
        case CancelOrder(var id)           -> state.withCancelled(id);
    };
}
```

**Rule 2: Move I/O outside the handler using tell()-back.**

Never perform network calls, file I/O, or database queries inside a handler. Spawn the I/O on a separate virtual thread and `tell()` the result back.

```java
// Bad: handler blocks on database query
static OrderState handle(OrderState state, OrderMsg msg) {
    return switch (msg) {
        case FetchHistory(var customerId) -> {
            var history = db.query(customerId);   // blocks carrier thread
            yield state.withHistory(history);
        }
    };
}

// Good: spawn I/O, tell result back
static OrderState handle(OrderState state, OrderMsg msg) {
    return switch (msg) {
        case FetchHistory(var customerId) -> {
            var self = state.selfRef();
            Thread.ofVirtual().start(() -> {
                var history = db.query(customerId);
                self.tell(new HistoryResult(customerId, history));
            });
            yield state.withPendingHistory(customerId);  // interim state
        }
        case HistoryResult(var customerId, var history) ->
            state.withHistory(customerId, history);
    };
}
```

**Rule 3: Eliminate synchronized blocks.**

`synchronized` pins a virtual thread to its carrier OS thread. Even one pinned thread in a hot handler reduces effective parallelism by one OS thread for the entire JVM. Use `java.util.concurrent.locks.ReentrantLock` with `lockInterruptibly()` if mutual exclusion is truly needed, or redesign to make the process itself the serialization point (the OTP way).

---

## 6. Step 5: GC Tuning

**Baseline:** For systems under 50K concurrent processes, G1GC with default settings is appropriate. Tune heap size first before switching collectors.

**Heap sizing:** JOTP processes are lightweight (~2KB base per virtual thread stack). A system with 10K active processes needs roughly:

```
heap_min = process_count * 2KB (stacks) + message_rate * avg_message_size * latency_p99
         = 10,000 * 2KB + 1,000,000 msg/s * 256B * 0.0005s
         = 20MB + 128MB
         = ~150MB minimum heap for message working set
```

In practice, add 4x headroom for GC breathing room:

```bash
-Xms600m -Xmx2g   # for 10K processes, 1M msg/sec
```

**Switch to ZGC when process count exceeds 50K.**

ZGC is a concurrent, pauseless collector. Its sub-millisecond pause guarantee prevents the GC-induced latency spikes that can cascade into mailbox saturation under high process counts.

```bash
# ZGC configuration for large JOTP deployments
-XX:+UseZGC \
-XX:+ZGenerational \       # Generational ZGC (Java 21+, recommended)
-Xms4g -Xmx16g \
-XX:SoftMaxHeapSize=12g    # Leave 4g as GC headroom before hard limit
```

**GC monitoring in production:**

```bash
# Log GC pauses to file, rotate daily
-Xlog:gc*:file=/var/log/jotp/gc-%t.log:time,uptime,level,tags:filecount=7,filesize=100m
```

Alert if `jdk.GarbageCollection` pause duration p99 exceeds 10ms. That is the threshold where GC pauses begin contributing measurably to `ask()` timeout rates.

---

## 7. Step 6: ask() Timeout Cascade

The most common production incident in JOTP systems is a timeout cascade: downstream timeouts are longer than upstream timeouts, so upstream callers retry while downstream is still processing, multiplying load.

**The rule:** Every upstream `ask()` timeout must be strictly shorter than the downstream `ask()` timeout it initiates.

```java
// BROKEN: upstream timeout (500ms) > downstream timeout (1000ms)
// If downstream takes 800ms, upstream has already timed out and retried.
// The downstream process now receives two requests for the same order.

// Upstream service (API layer)
OrderResult result = orderProc.ask(new PlaceOrder(id, items), Duration.ofMillis(500));

// Inside orderProc handler — BROKEN
InventoryResult inv = inventoryProc.ask(new Reserve(items), Duration.ofMillis(1000));


// CORRECT: upstream (500ms) > downstream (300ms) with explicit budget
// Upstream caller sets the deadline. Each hop consumes part of the budget.

// Upstream service
OrderResult result = orderProc.ask(new PlaceOrder(id, items), Duration.ofMillis(500));

// Inside orderProc handler — CORRECT
// Reserve 200ms for own processing; give downstream 300ms
InventoryResult inv = inventoryProc.ask(new Reserve(items), Duration.ofMillis(300));
// Remaining 200ms covers handler logic + response serialization
```

Pass deadlines as `Instant` in the message when crossing process boundaries so each hop can compute its remaining budget:

```java
record PlaceOrder(String orderId, List<Item> items, Instant deadline) implements OrderMsg {}

// In handler:
Duration remaining = Duration.between(Instant.now(), msg.deadline()).minus(Duration.ofMillis(50));
if (remaining.isNegative()) {
    return state.withError(orderId, "deadline already exceeded");
}
inventoryProc.ask(new Reserve(msg.items(), msg.deadline()), remaining);
```

---

## 8. Benchmarking Your System

JOTP includes a JMH benchmark suite covering core primitive performance. Run it before and after any tuning change to confirm the delta.

```bash
# Run full benchmark suite (takes ~10 minutes)
mvnd verify -Pbenchmark

# Run a specific benchmark class
mvnd verify -Pbenchmark -Dbenchmark.include=ProcBenchmark

# Output formats: text (default), csv, json
mvnd verify -Pbenchmark -Dbenchmark.format=csv
```

**What to measure and compare:**

| Benchmark | Baseline (p50) | Alert threshold |
|---|---|---|
| `tell()` latency p50 | 125 ns | > 200 ns |
| `tell()` latency p99 | 625 ns | > 2 µs |
| `ask()` round trip p50 | <1 µs | > 5 µs |
| Process creation | 50 µs | > 100 µs |
| Supervisor restart p50 | 150 µs | > 500 µs |
| Throughput (enabled) | 4.6M msg/sec | < 3M msg/sec |

Compare benchmark results across JVM flag changes using the JMH `-rf csv` output piped into a spreadsheet. A 10% regression in `tell()` throughput after a flag change is a clear signal to revert.

---

## 9. Performance Profiles

Three validated JVM flag sets for common JOTP deployment scenarios:

### Latency-Optimized (trading desks, real-time fraud detection)

Goal: minimize `ask()` p99. Accept higher memory usage.

```bash
java \
  --enable-preview \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms8g -Xmx8g \                          # Fixed heap: no resize pauses
  -XX:SoftMaxHeapSize=7g \
  -Djdk.virtualThreadScheduler.parallelism=16 \   # Pin to physical core count
  -XX:+AlwaysPreTouch \                     # Pre-fault heap pages at startup
  -XX:ReservedCodeCacheSize=512m \
  -jar your-service.jar
```

### Throughput-Optimized (batch processing, event pipelines)

Goal: maximize `tell()` message/sec. Accept occasional GC pauses.

```bash
java \
  --enable-preview \
  -XX:+UseG1GC \
  -Xms4g -Xmx16g \
  -XX:G1HeapRegionSize=32m \
  -XX:MaxGCPauseMillis=50 \
  -Djdk.virtualThreadScheduler.parallelism=0 \    # 0 = auto-detect all cores
  -XX:+UseStringDeduplication \
  -jar your-service.jar
```

### Memory-Constrained (containerized, sidecar deployments)

Goal: run at low memory with predictable GC. Accept moderate latency.

```bash
java \
  --enable-preview \
  -XX:+UseSerialGC \                        # Lowest memory overhead
  -Xms256m -Xmx512m \
  -Djdk.virtualThreadScheduler.parallelism=2 \
  -XX:TieredStopAtLevel=1 \                 # Reduce JIT code cache
  -jar your-service.jar
```

---

## 10. What NOT to Optimize

Premature optimization in JOTP follows predictable patterns. These changes add complexity, maintenance burden, and occasionally make performance worse. Avoid them until JFR data specifically implicates the primitive in question.

**Do not pool processes.** Object pools exist to amortize allocation cost. Process creation at ~50 µs is already amortized via virtual threads. A process pool adds coordination overhead (the pool itself is a contention point) that exceeds the cost it saves.

**Do not batch `tell()` calls manually.** The mailbox is a lock-free queue. Batching messages in an array and sending them together does not reduce scheduler overhead — it increases handler complexity and introduces head-of-line blocking within a batch.

**Do not tune `virtualThreadScheduler.parallelism` before profiling.** The default (auto-detect) matches physical cores. Increasing it above core count does not add CPU — it adds context switching. Decreasing it below core count reduces throughput on CPU-bound handlers. Change this only when JFR shows a specific carrier thread starvation pattern.

**Do not replace `ask()` with `tell()` + polling to reduce latency.** This replaces a single round trip with a polling loop that burns CPU and adds latency variance. Use `ask()` when you need a reply. It costs ~500 ns. That is the right abstraction.

**Do not add caching inside handlers.** A handler that caches external data is no longer pure. Cache at the supervisor level as a separate process, or use a dedicated `CacheProc` that the handler `ask()`s. The OTP way is to make each process responsible for exactly one piece of state.

---

## Quick Reference

```
Baseline numbers (intra-JVM, single host):

  tell()           p50: 125 ns     p99: 625 ns     throughput: 4.6M msg/sec
  ask()            p50: <1 µs      p99: <100 µs
  Proc creation        :  50 µs
  Supervisor restart  p50: 150 µs     p99: <1 ms

Tuning decision tree:

  mailbox_size > 1000?  → Scale horizontally or extract I/O from handler
  VirtualThreadPinned?  → Remove synchronized, use ReentrantLock or redesign
  GC pause > 5ms?       → Switch to ZGC if process count > 50K
  ask() timeouts?       → Check timeout cascade (upstream < downstream rule)
  CPU < 30%, high lat?  → Handler blocking; use tell()-back pattern for I/O
```
