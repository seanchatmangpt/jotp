# io.github.seanchatmangpt.jotp.dogfood.concurrency.VirtualThreadPatternsTest

## Table of Contents

- [Structured Concurrency Patterns](#structuredconcurrencypatterns)
- [Handling Timeout and Interruption](#handlingtimeoutandinterruption)
- [Migration from Thread Pools](#migrationfromthreadpools)
- [Timeout and Cancellation in Virtual Threads](#timeoutandcancellationinvirtualthreads)
- [Creating Millions of Lightweight Processes](#creatingmillionsoflightweightprocesses)
- [Java 26: Virtual Threads vs Platform Threads](#java26virtualthreadsvsplatformthreads)
- [Blocking Without Scaling Limits](#blockingwithoutscalinglimits)


## Structured Concurrency Patterns

Structured concurrency ensures that concurrent tasks complete as a unit, with proper error propagation and resource cleanup. Java 26 provides StructuredTaskScope for this purpose.

```java
// Process multiple I/O-bound items concurrently using virtual threads
var items = List.of(1, 2, 3, 4, 5);
var results = VirtualThreadPatterns.processAllConcurrently(items, i -> i * 2);

// Each item is processed in its own virtual thread
// Results are collected when all threads complete
assertThat(results).hasSize(5);
```

| Key | Value |
| --- | --- |
| `Processing Pattern` | `Concurrent, unordered` |
| `Input Items` | `5` |
| `Thread Type` | `Virtual (per-item)` |
| `Results Collected` | `5` |

> [!NOTE]
> While this example uses CountDownLatch for compatibility, production code should prefer StructuredTaskScope (JEP 453) for proper structured concurrency with automatic error propagation and timeout handling.

## Handling Timeout and Interruption

When a virtual thread times out, interrupt it to release resources. The task should check Thread.interrupted() or handle InterruptedException cooperatively.

```java
// Task that exceeds timeout
boolean completed = VirtualThreadPatterns.runWithTimeout(
    () -> {
        try {
            Thread.sleep(Duration.ofSeconds(10));  // Longer than timeout
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();  // Restore interrupt flag
        }
    },
    Duration.ofMillis(100)  // Timeout before task completes
);

assertThat(completed).isFalse();
```

## Migration from Thread Pools

Virtual threads eliminate the need for thread pools in most I/O-bound scenarios. Instead of managing a fixed-size thread pool, create a virtual thread per task and let the JVM handle scheduling.

| Aspect | Queue full → reject | Minimal per-thread overhead |
| --- | --- | --- |
| Thread Pool | Never rejects (creates thread) | Typical Use |
| Virtual Threads | Thread Reuse | Blocking I/O (legacy) |
| Resource Management | Required for efficiency | Blocking I/O (modern) |
| Fixed pool size | Not needed (cheap to create) | Configuration |
| Unlimited virtual threads | Memory Footprint | Complex (tuning required) |
| Rejection Policy | Pool overhead | Simple (no tuning) |

```java
// Old approach: fixed thread pool
// ExecutorService pool = Executors.newFixedThreadPool(10);
// Future<?>[] futures = requests.stream()
//     .map(r -> pool.submit(() -> handler.accept(r)))
//     .toArray(Future[]::new);
// for (var f : futures) f.get();

// New approach: virtual threads (simpler, scales better)
var counter = new AtomicInteger();
var requests = List.of("a", "b", "c");
VirtualThreadPatterns.handleRequests(requests, r -> counter.incrementAndGet());

// Each request gets its own virtual thread
// No pool size tuning, no rejection handling
assertThat(counter.get()).isEqualTo(requests.size());
```

| Key | Value |
| --- | --- |
| `Pool Required` | `No` |
| `Requests Processed` | `3` |
| `Thread Strategy` | `One virtual thread per request` |
| `Handler Invocations` | `3` |

> [!NOTE]
> Virtual threads make thread pools obsolete for I/O-bound work. Keep thread pools only for CPU-intensive tasks where parallelism is limited by CPU cores, not I/O.

## Timeout and Cancellation in Virtual Threads

Virtual threads support interruption via Thread.interrupt(). For timeout handling, join the thread with a timeout and interrupt if still alive. This pattern is safer than platform threads where interruption might be delayed.

```java
// Run a task on a virtual thread with timeout
boolean completed = VirtualThreadPatterns.runWithTimeout(
    () -> {
        // Fast task completes within timeout
    },
    Duration.ofSeconds(2)
);

assertThat(completed).isTrue();
```

| Key | Value |
| --- | --- |
| `Timeout Pattern` | `join() + interrupt()` |
| `Task Completed` | `Within timeout` |
| `Result` | `true` |
| `Thread Interrupted` | `No` |

> [!NOTE]
> For more sophisticated timeout handling, use StructuredTaskScope with ShutdownOnSuccess or ShutdownOnFailure policies. These provide better error aggregation and cancellation propagation.

## Creating Millions of Lightweight Processes

Virtual threads are so lightweight that creating millions of them is practical. This contrasts sharply with platform threads, which are limited to thousands by OS resources.

| Operation | 10K Threads | Context Switch |
| --- | --- | --- |
| Virtual Threads | ~10 MB | JVM scheduling |
| Platform Threads | ~20 GB | OS scheduling |
| Creation Cost | 1M Threads | Startup Time |
| ~1 KB heap | ~1 GB | <1 microsecond |
| ~2 MB stack | Out of Memory | ~1 millisecond |

```java
// Fire-and-forget pattern: start a virtual thread without tracking it
var counter = new AtomicInteger();
var thread = VirtualThreadPatterns.startFireAndForget(counter::incrementAndGet);

// The thread runs immediately; we can join to wait for completion
thread.join(Duration.ofSeconds(2));
```

| Key | Value |
| --- | --- |
| `Counter Value` | `1` |
| `Thread Lifecycle` | `Terminates after task completes` |
| `Execution Pattern` | `Fire-and-Forget` |

> [!NOTE]
> Fire-and-forget virtual threads are ideal for logging, metrics, and async tasks where you don't need the result. However, for structured concurrency, prefer StructuredTaskScope to ensure proper cleanup and error handling.

## Java 26: Virtual Threads vs Platform Threads

Project Loom introduces virtual threads as lightweight, user-mode threads scheduled on carrier threads (platform threads). Unlike platform threads, virtual threads are cheap enough to create millions of them.

| Characteristic | Max Count | Blocking I/O |
| --- | --- | --- |
| Platform Threads | ~Thousands (OS-limited) | Wastes thread resources |
| Virtual Threads | ~Millions (JVM-managed) | Parks thread, releases carrier |
| Cost | Creation | Use Case |
| ~2 MB stack per thread | Thread() or Executors | CPU-intensive work |
| ~1 KB heap per thread | Thread.ofVirtual() | I/O-bound, blocking operations |

```java
var counter = new AtomicInteger();
var thread = VirtualThreadPatterns.startNamed("test-thread", counter::incrementAndGet);

thread.join(Duration.ofSeconds(2));
assertThat(thread.isVirtual()).isTrue();
```

| Key | Value |
| --- | --- |
| `Thread Type` | `Virtual Thread` |
| `Counter Value` | `1` |
| `Thread Name` | `test-thread` |

> [!NOTE]
> Use platform threads for CPU-intensive work, virtual threads for I/O-bound work. The naming pattern ('test-thread') helps with debugging and observability.

## Blocking Without Scaling Limits

With platform threads, blocking I/O wastes thread resources. A thread pool of 200 threads can only handle 200 concurrent blocking operations. Virtual threads change the equation: blocking just parks the virtual thread, releasing the carrier thread.

```java
// Create multiple virtual threads with automatic naming
var counter = new AtomicInteger();
List<Runnable> tasks = List.of(
    counter::incrementAndGet,
    counter::incrementAndGet,
    counter::incrementAndGet  // Add more tasks as needed
);

var threads = VirtualThreadPatterns.startAll("worker-", tasks);

// All threads run concurrently; join to wait for completion
for (var thread : threads) {
    thread.join(Duration.ofSeconds(2));
}

// All tasks executed
assertThat(counter.get()).isEqualTo(tasks.size());
```

| Key | Value |
| --- | --- |
| `Threads Created` | `2` |
| `Naming Pattern` | `worker-0, worker-1, ...` |
| `Counter Value` | `2` |
| `All Virtual` | `true` |

> [!NOTE]
> The naming prefix ('worker-') plus counter creates thread names like 'worker-0', 'worker-1', etc. This is critical for debugging and observability in production.

| Key | Value |
| --- | --- |
| `Task Completed` | `Exceeded timeout` |
| `Task Response` | `Caught InterruptedException` |
| `Result` | `false` |
| `Thread Interrupted` | `Yes (after timeout)` |

> [!NOTE]
> Always handle InterruptedException properly: either propagate it or restore the interrupt flag with Thread.currentThread().interrupt(). This ensures callers know the thread was interrupted.

---
*Generated by [DTR](http://www.dtr.org)*
