package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrContextField;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: tests for VirtualThreadPatterns generated from concurrency/virtual-thread.tera.
 *
 * <p>Project Loom (JEP 444) introduces virtual threads — lightweight threads that enable millions
 * of concurrent operations without the scaling limits of platform threads.
 *
 * <p>Key benefits: 1) Create millions of virtual threads vs thousands of platform threads 2)
 * Blocking I/O no longer limits scalability 3) Structured concurrency with StructuredTaskScope 4)
 * Drop-in replacement for thread pools in most cases
 */
@DisplayName("VirtualThreadPatterns")
@DtrTest
class VirtualThreadPatternsTest implements WithAssertions {

    @DtrContextField private DtrContext ctx;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    @DisplayName("startNamed creates a virtual thread with the given name")
    void startNamedCreatesVirtualThread() throws InterruptedException {
        ctx.sayNextSection("Java 26: Virtual Threads vs Platform Threads");
        ctx.say(
                "Project Loom introduces virtual threads as lightweight, user-mode threads scheduled on"
                        + " carrier threads (platform threads). Unlike platform threads, virtual threads are cheap"
                        + " enough to create millions of them.");

        ctx.sayTable(
                new String[][] {
                    {"Characteristic", "Max Count", "Blocking I/O"},
                    {"Platform Threads", "~Thousands (OS-limited)", "Wastes thread resources"},
                    {
                        "Virtual Threads",
                        "~Millions (JVM-managed)",
                        "Parks thread, releases carrier"
                    },
                    {"Cost", "Creation", "Use Case"},
                    {"~2 MB stack per thread", "Thread() or Executors", "CPU-intensive work"},
                    {
                        "~1 KB heap per thread",
                        "Thread.ofVirtual()",
                        "I/O-bound, blocking operations"
                    }
                });

        ctx.sayCode(
                """
            var counter = new AtomicInteger();
            var thread = VirtualThreadPatterns.startNamed("test-thread", counter::incrementAndGet);

            thread.join(Duration.ofSeconds(2));
            assertThat(thread.isVirtual()).isTrue();
            """,
                "java");

        var counter = new AtomicInteger();
        var thread = VirtualThreadPatterns.startNamed("test-thread", counter::incrementAndGet);

        thread.join(Duration.ofSeconds(2));
        assertThat(counter.get()).isEqualTo(1);
        assertThat(thread.isVirtual()).isTrue();

        ctx.sayKeyValue(
                Map.of(
                        "Thread Type",
                        thread.isVirtual() ? "Virtual Thread" : "Platform Thread",
                        "Thread Name",
                        thread.getName(),
                        "Counter Value",
                        String.valueOf(counter.get())));

        ctx.sayNote(
                "Use platform threads for CPU-intensive work, virtual threads for I/O-bound work. The naming"
                        + " pattern ('test-thread') helps with debugging and observability.");
    }

    @Test
    @DisplayName("startFireAndForget runs the task")
    void startFireAndForgetRunsTask() throws InterruptedException {
        ctx.sayNextSection("Creating Millions of Lightweight Processes");
        ctx.say(
                "Virtual threads are so lightweight that creating millions of them is practical. This"
                        + " contrasts sharply with platform threads, which are limited to thousands by OS"
                        + " resources.");

        ctx.sayTable(
                new String[][] {
                    {"Operation", "10K Threads", "Context Switch"},
                    {"Virtual Threads", "~10 MB", "JVM scheduling"},
                    {"Platform Threads", "~20 GB", "OS scheduling"},
                    {"Creation Cost", "1M Threads", "Startup Time"},
                    {"~1 KB heap", "~1 GB", "<1 microsecond"},
                    {"~2 MB stack", "Out of Memory", "~1 millisecond"}
                });

        ctx.sayCode(
                """
            // Fire-and-forget pattern: start a virtual thread without tracking it
            var counter = new AtomicInteger();
            var thread = VirtualThreadPatterns.startFireAndForget(counter::incrementAndGet);

            // The thread runs immediately; we can join to wait for completion
            thread.join(Duration.ofSeconds(2));
            """,
                "java");

        var counter = new AtomicInteger();
        var thread = VirtualThreadPatterns.startFireAndForget(counter::incrementAndGet);

        thread.join(Duration.ofSeconds(2));
        assertThat(counter.get()).isEqualTo(1);

        ctx.sayKeyValue(
                Map.of(
                        "Execution Pattern",
                        "Fire-and-Forget",
                        "Thread Lifecycle",
                        "Terminates after task completes",
                        "Counter Value",
                        String.valueOf(counter.get())));

        ctx.sayNote(
                "Fire-and-forget virtual threads are ideal for logging, metrics, and async tasks where"
                        + " you don't need the result. However, for structured concurrency, prefer"
                        + " StructuredTaskScope to ensure proper cleanup and error handling.");
    }

    @Test
    @DisplayName("startAll creates multiple virtual threads")
    void startAllCreatesMultipleThreads() throws InterruptedException {
        ctx.sayNextSection("Blocking Without Scaling Limits");
        ctx.say(
                "With platform threads, blocking I/O wastes thread resources. A thread pool of 200"
                        + " threads can only handle 200 concurrent blocking operations. Virtual threads change the"
                        + " equation: blocking just parks the virtual thread, releasing the carrier thread.");

        ctx.sayCode(
                """
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
            """,
                "java");

        var counter = new AtomicInteger();
        List<Runnable> tasks = List.of(counter::incrementAndGet, counter::incrementAndGet);

        var threads = VirtualThreadPatterns.startAll("worker-", tasks);
        for (var thread : threads) {
            thread.join(Duration.ofSeconds(2));
        }

        assertThat(counter.get()).isEqualTo(2);
        assertThat(threads).allMatch(Thread::isVirtual);

        ctx.sayKeyValue(
                Map.of(
                        "Threads Created",
                        String.valueOf(threads.size()),
                        "All Virtual",
                        String.valueOf(threads.stream().allMatch(Thread::isVirtual)),
                        "Naming Pattern",
                        "worker-0, worker-1, ...",
                        "Counter Value",
                        String.valueOf(counter.get())));

        ctx.sayNote(
                "The naming prefix ('worker-') plus counter creates thread names like 'worker-0',"
                        + " 'worker-1', etc. This is critical for debugging and observability in production.");
    }

    @Test
    @DisplayName("processAllConcurrently processes all items")
    void processAllConcurrentlyProcessesAllItems() throws InterruptedException {
        ctx.sayNextSection("Structured Concurrency Patterns");
        ctx.say(
                "Structured concurrency ensures that concurrent tasks complete as a unit, with proper error"
                        + " propagation and resource cleanup. Java 26 provides StructuredTaskScope for this purpose.");

        ctx.sayCode(
                """
            // Process multiple I/O-bound items concurrently using virtual threads
            var items = List.of(1, 2, 3, 4, 5);
            var results = VirtualThreadPatterns.processAllConcurrently(items, i -> i * 2);

            // Each item is processed in its own virtual thread
            // Results are collected when all threads complete
            assertThat(results).hasSize(5);
            """,
                "java");

        var items = List.of(1, 2, 3, 4, 5);

        var results = VirtualThreadPatterns.processAllConcurrently(items, i -> i * 2);

        assertThat(results).hasSize(5).containsExactlyInAnyOrder(2, 4, 6, 8, 10);

        ctx.sayKeyValue(
                Map.of(
                        "Input Items",
                        String.valueOf(items.size()),
                        "Results Collected",
                        String.valueOf(results.size()),
                        "Processing Pattern",
                        "Concurrent, unordered",
                        "Thread Type",
                        "Virtual (per-item)"));

        ctx.sayNote(
                "While this example uses CountDownLatch for compatibility, production code should prefer"
                        + " StructuredTaskScope (JEP 453) for proper structured concurrency with automatic"
                        + " error propagation and timeout handling.");
    }

    @Test
    @DisplayName("handleRequests dispatches to virtual threads")
    void handleRequestsDispatchesToVirtualThreads() throws InterruptedException {
        ctx.sayNextSection("Migration from Thread Pools");
        ctx.say(
                "Virtual threads eliminate the need for thread pools in most I/O-bound scenarios. Instead of"
                        + " managing a fixed-size thread pool, create a virtual thread per task and let the JVM"
                        + " handle scheduling.");

        ctx.sayTable(
                new String[][] {
                    {"Aspect", "Queue full → reject", "Minimal per-thread overhead"},
                    {"Thread Pool", "Never rejects (creates thread)", "Typical Use"},
                    {"Virtual Threads", "Thread Reuse", "Blocking I/O (legacy)"},
                    {"Resource Management", "Required for efficiency", "Blocking I/O (modern)"},
                    {"Fixed pool size", "Not needed (cheap to create)", "Configuration"},
                    {"Unlimited virtual threads", "Memory Footprint", "Complex (tuning required)"},
                    {"Rejection Policy", "Pool overhead", "Simple (no tuning)"}
                });

        ctx.sayCode(
                """
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
            """,
                "java");

        var counter = new AtomicInteger();
        var requests = List.of("a", "b", "c");

        VirtualThreadPatterns.handleRequests(requests, r -> counter.incrementAndGet());

        assertThat(counter.get()).isEqualTo(3);

        ctx.sayKeyValue(
                Map.of(
                        "Requests Processed",
                        String.valueOf(requests.size()),
                        "Handler Invocations",
                        String.valueOf(counter.get()),
                        "Thread Strategy",
                        "One virtual thread per request",
                        "Pool Required",
                        "No"));

        ctx.sayNote(
                "Virtual threads make thread pools obsolete for I/O-bound work. Keep thread pools only for"
                        + " CPU-intensive tasks where parallelism is limited by CPU cores, not I/O.");
    }

    @Test
    @DisplayName("runWithTimeout completes within timeout")
    void runWithTimeoutCompletesWithinTimeout() throws InterruptedException {
        ctx.sayNextSection("Timeout and Cancellation in Virtual Threads");
        ctx.say(
                "Virtual threads support interruption via Thread.interrupt(). For timeout handling, join"
                        + " the thread with a timeout and interrupt if still alive. This pattern is safer than"
                        + " platform threads where interruption might be delayed.");

        ctx.sayCode(
                """
            // Run a task on a virtual thread with timeout
            boolean completed = VirtualThreadPatterns.runWithTimeout(
                () -> {
                    // Fast task completes within timeout
                },
                Duration.ofSeconds(2)
            );

            assertThat(completed).isTrue();
            """,
                "java");

        var result =
                VirtualThreadPatterns.runWithTimeout(
                        () -> {
                            // fast task
                        },
                        Duration.ofSeconds(2));

        assertThat(result).isTrue();

        ctx.sayKeyValue(
                Map.of(
                        "Task Completed",
                        "Within timeout",
                        "Result",
                        String.valueOf(result),
                        "Thread Interrupted",
                        "No",
                        "Timeout Pattern",
                        "join() + interrupt()"));

        ctx.sayNote(
                "For more sophisticated timeout handling, use StructuredTaskScope with ShutdownOnSuccess"
                        + " or ShutdownOnFailure policies. These provide better error aggregation and"
                        + " cancellation propagation.");
    }

    @Test
    @DisplayName("runWithTimeout returns false when task exceeds timeout")
    void runWithTimeoutReturnsFalseOnTimeout() throws InterruptedException {
        ctx.sayNextSection("Handling Timeout and Interruption");
        ctx.say(
                "When a virtual thread times out, interrupt it to release resources. The task should check"
                        + " Thread.interrupted() or handle InterruptedException cooperatively.");

        ctx.sayCode(
                """
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
            """,
                "java");

        var result =
                VirtualThreadPatterns.runWithTimeout(
                        () -> {
                            try {
                                Thread.sleep(Duration.ofSeconds(10));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        Duration.ofMillis(100));

        assertThat(result).isFalse();

        ctx.sayKeyValue(
                Map.of(
                        "Task Completed",
                        "Exceeded timeout",
                        "Result",
                        String.valueOf(result),
                        "Thread Interrupted",
                        "Yes (after timeout)",
                        "Task Response",
                        "Caught InterruptedException"));

        ctx.sayNote(
                "Always handle InterruptedException properly: either propagate it or restore the interrupt"
                        + " flag with Thread.currentThread().interrupt(). This ensures callers know the thread"
                        + " was interrupted.");
    }
}
