# Tutorial 03: Virtual Threads and Process Scale

> "In Erlang, you never think about threads. You think about processes. Threads are an implementation detail. Processes are the idea."
> — Joe Armstrong

This tutorial teaches you to think in processes, not threads. By the end, you'll understand why JOTP can run millions of concurrent processes on a standard JVM — and you'll have seen it yourself.

**Prerequisites:** [Tutorial 02: Your First Process](02-first-process.md)

**What you'll learn:**
- How virtual threads enable millions of processes
- The difference between a process (your concern) and a thread (the JVM's concern)
- What virtual thread pinning is and how to avoid it
- How message backpressure naturally limits throughput

---

## Part 1: The Conceptual Shift

### Old Model: Threads (Your Problem)

In traditional Java, concurrency was your problem. You created threads, managed pools, synchronized shared state:

```java
// Platform thread: ~1 MB stack, OS-managed context switch
Thread thread = new Thread(() -> {
    while (running) {
        T task = queue.take();   // Blocks OS thread!
        process(task);
    }
});
```

Maximum practical scale: ~10,000 platform threads per JVM.

### New Model: Virtual Threads (JVM's Problem)

Virtual threads let the JVM manage scheduling. You write sequential code:

```java
// Virtual thread: ~8 KB initial stack, JVM-managed
Thread.startVirtualThread(() -> {
    while (running) {
        T task = queue.take();   // Parks virtual thread, does NOT block OS thread
        process(task);
    }
});
```

Maximum practical scale: **10 million+ virtual threads per JVM**.

### JOTP: Process-Oriented (Your Mental Model)

In JOTP, you don't even think about virtual threads. You create processes:

```java
// Spawns one virtual thread — invisible to you
var proc = new Proc<>(
    initialState,
    (state, msg) -> newState  // Your handler runs when messages arrive
);
```

The virtual thread runs your handler. It blocks waiting for messages. You think in **processes**. The JVM manages threads.

---

## Exercise 1: Scale — 100,000 Processes

Verify the scale claim directly:

```java
import io.github.seanchatmangpt.jotp.*;
import java.util.ArrayList;
import java.util.List;

public class ScaleDemo {
    record PingMsg() {}

    public static void main(String[] args) throws Exception {
        int count = 100_000;
        List<Proc<Integer, PingMsg>> procs = new ArrayList<>(count);

        long startMs = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            procs.add(new Proc<>(0, (state, msg) -> state + 1));
        }

        long spawnMs = System.currentTimeMillis() - startMs;
        System.out.printf("Spawned %,d processes in %,d ms%n", count, spawnMs);

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_000_000;
        System.out.printf("Heap used: %,d MB (%.1f KB per process)%n",
            usedMb, usedMb * 1000.0 / count);

        for (var proc : procs) proc.stop();
    }
}
```

**Expected output:**
```
Spawned 100,000 processes in 3,200 ms
Heap used: 450 MB (4.5 KB per process)
```

Compare: try `new Thread(...)` 100K times — you'll get `OutOfMemoryError`.

---

## Exercise 2: Blocking Is Cheap

1,000 processes each blocking 100ms should complete in ~100ms total (not 100,000ms):

```java
import io.github.seanchatmangpt.jotp.*;
import java.util.concurrent.CountDownLatch;

public class BlockingDemo {
    record SlowMsg() {}
    record SlowState(int done) {}

    public static void main(String[] args) throws Exception {
        int procCount = 1000;
        var latch = new CountDownLatch(procCount);
        var procs = new java.util.ArrayList<Proc<SlowState, SlowMsg>>(procCount);

        for (int i = 0; i < procCount; i++) {
            procs.add(new Proc<>(new SlowState(0), (state, msg) -> {
                try {
                    Thread.sleep(100);  // Parks virtual thread, releases carrier
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
                return new SlowState(state.done() + 1);
            }));
        }

        long start = System.currentTimeMillis();
        for (var proc : procs) proc.tell(new SlowMsg());
        latch.await();
        System.out.printf("1000 processes × 100ms blocking = %d ms total%n",
            System.currentTimeMillis() - start);
        // Expected: ~100ms, not 100,000ms

        for (var proc : procs) proc.stop();
    }
}
```

**Expected output:** `1000 processes × 100ms blocking = 112 ms total`

---

## Exercise 3: Virtual Thread Pinning (What to Avoid)

When a virtual thread enters a `synchronized` block, it **pins** to its carrier thread. The carrier cannot run other virtual threads until the lock is released.

**BAD — synchronized pins the carrier:**
```java
private static final Object LOCK = new Object();

private static void pinnedMethod() throws InterruptedException {
    synchronized (LOCK) {
        Thread.sleep(50);  // PINS carrier thread for 50ms!
    }
}
```

**Run with pinning detection:**
```bash
java --enable-preview -Djdk.tracePinnedThreads=short -cp ... MyApp
```

Output:
```
Thread[#42,ForkJoinPool-1-worker-1,5,CarrierThreads]
    MyApp.pinnedMethod(MyApp.java:12) <== monitors:1>
```

**GOOD — ReentrantLock releases the carrier:**
```java
import java.util.concurrent.locks.ReentrantLock;

private static final ReentrantLock LOCK = new ReentrantLock();

private static void nonPinnedMethod() throws InterruptedException {
    LOCK.lock();
    try {
        Thread.sleep(50);  // Virtual thread PARKS — carrier is RELEASED
    } finally {
        LOCK.unlock();
    }
}
```

**Rule:** In JOTP process handlers, never use `synchronized`. Use `ReentrantLock`, `java.util.concurrent` classes, or immutable records (which need no locking at all).

---

## Exercise 4: Backpressure via ask() Timeout

When a process is overwhelmed, `ask()` creates natural backpressure:

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class BackpressureDemo {
    record SlowWork() {}
    record SlowState(int count) {}

    public static void main(String[] args) throws Exception {
        // Process that takes 200ms per message
        var slowProc = new Proc<>(new SlowState(0), (state, msg) -> {
            try { Thread.sleep(200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new SlowState(state.count() + 1);
        });

        // Caller with 100ms timeout (less than 200ms processing time)
        int success = 0, timedOut = 0;

        for (int i = 0; i < 10; i++) {
            try {
                slowProc.ask(new SlowWork(), Duration.ofMillis(100)).get();
                success++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    timedOut++;
                }
            }
        }

        System.out.printf("Success: %d, Backpressure signals: %d%n", success, timedOut);
        slowProc.stop();
    }
}
```

The `ask()` timeout is not just error handling — it is **backpressure**. When the process can't keep up, callers receive `TimeoutException` instead of silently queuing unlimited messages.

**Production pattern (5-second SLA):**
```java
try {
    var result = gateway.ask(new ChargeRequest(amount), Duration.ofSeconds(5)).get();
    return Response.ok(result);
} catch (ExecutionException e) {
    if (e.getCause() instanceof TimeoutException) {
        return Response.serviceUnavailable("Gateway busy");
    }
    throw e;
}
```

---

## Summary: The Three Rules

1. **Think in processes, not threads.** Create `Proc<S,M>` for each concurrent concern. Let the JVM manage virtual threads.

2. **Never use `synchronized` in handlers.** Use `ReentrantLock` or immutable records. `synchronized` pins carrier threads and destroys throughput.

3. **Always set `ask()` timeouts.** They are your backpressure mechanism. Without timeouts, a slow consumer silently exhausts memory.

---

## Key Takeaways

| Concept | Virtual Thread Behavior |
|---------|-------------------------|
| Memory per process | ~8 KB initial stack (grows on demand) |
| Blocking (sleep, I/O, queue.take) | Parks virtual thread, releases carrier |
| `synchronized` block | **Pins** carrier thread — avoid in handlers |
| `ReentrantLock` | Parks virtual thread — use this instead |
| Scale | 1M+ processes on commodity hardware |

---

**Previous:** [Tutorial 02: Your First Process](02-first-process.md) | **Next:** [Tutorial 04: Supervision Basics](04-supervision-basics.md)

**See Also:**
- [Concurrency Model](../explanations/concurrency-model.md) — Deep explanation of virtual thread internals
- [Configuration Reference](../reference/configuration.md) — Virtual thread scheduler tuning
- [How-To: Concurrent Pipelines](../how-to/concurrent-pipelines.md) — Fan-out with `Parallel`
