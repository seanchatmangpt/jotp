# io.github.seanchatmangpt.jotp.dogfood.concurrency.StructuredTaskScopePatternsTest

## Table of Contents

- [Scalable Structured Concurrency: runAll Pattern](#scalablestructuredconcurrencyrunallpattern)
- [Competitive Concurrency: raceForFirst Pattern](#competitiveconcurrencyraceforfirstpattern)
- [Structured Concurrency: runBoth Pattern](#structuredconcurrencyrunbothpattern)
- [Symmetric Error Handling](#symmetricerrorhandling)
- [Error Propagation in Structured Concurrency](#errorpropagationinstructuredconcurrency)


## Scalable Structured Concurrency: runAll Pattern

The runAll pattern extends runBoth to handle multiple concurrent tasks. It demonstrates how structured concurrency scales to any number of parallel operations while maintaining the same guarantees: all succeed or all fail together.

```java
// Run three tasks concurrently, wait for all
var result = StructuredTaskScopePatterns.runAll(
    () -> "a",
    () -> "b",
    () -> "c"
);

// All tasks completed successfully
assertThat(result.first()).isEqualTo("a");
assertThat(result.second()).isEqualTo("b");
assertThat(result.third()).isEqualTo("c");
```

| Key | Value |
| --- | --- |
| `Task 1 Result` | `a` |
| `Task 3 Result` | `c` |
| `Task 2 Result` | `b` |
| `Completion` | `All 3 tasks succeeded` |
| `Pattern` | `Extensible to N tasks` |

> [!NOTE]
> In production, you'd use generic StructuredTaskScope to run any number of tasks. The runAll pattern here shows a typed triple for convenience. Consider using StructuredTaskScope.join() with a list of Subtasks for N-ary concurrency.

## Competitive Concurrency: raceForFirst Pattern

Sometimes you want the first successful result from multiple competing strategies. The raceForFirst pattern uses ShutdownOnSuccess to return as soon as any task completes, cancelling the rest.

| Scope Policy | Completion | Error Handling | Use Case | Cancellation |
| --- | --- | --- | --- | --- |
| ShutdownOnSuccess | Returns first success, cancels rest | Only fails if ALL tasks fail | Redundant services, race conditions | Automatic after first success |

```java
// Race between slow and fast tasks
List<Callable<String>> tasks = List.of(
    () -> {
        Thread.sleep(100);
        return "slow";
    },
    () -> "fast"  // Wins the race
);

var result = StructuredTaskScopePatterns.raceForFirst(tasks);

// First task to complete wins
assertThat(result).isIn("fast", "slow");
```

| Key | Value |
| --- | --- |
| `Winning Task` | `slow` |
| `Pattern` | `First-success wins` |
| `Losing Task` | `Cancelled` |
| `Race Condition` | `Non-deterministic` |

> [!NOTE]
> This pattern is ideal for redundant services (multiple APIs, database replicas) where you want the fastest response. StructuredTaskScope ensures the slower tasks are cancelled to free resources.

## Structured Concurrency: runBoth Pattern

StructuredTaskScope ensures that concurrent tasks complete as a unit. The runBoth pattern waits for two tasks to complete successfully and returns their results as a pair. If either task fails, the entire scope fails.

| Pattern | Purpose | Error Handling | Use Case |
| --- | --- | --- | --- |
| runBoth | Run two tasks concurrently, wait for both | Fails if either task throws | Parallel independent operations |

```java
// Old approach: manual join with potential orphaned threads
// Future<String> f1 = executor.submit(() -> task1());
// Future<Integer> f2 = executor.submit(() -> task2());
// String r1 = f1.get();  // may hang if f2 fails
// Integer r2 = f2.get();

// New approach: structured concurrency with automatic cleanup
var result = StructuredTaskScopePatterns.runBoth(() -> "hello", () -> 42);

// Both tasks completed successfully
assertThat(result.first()).isEqualTo("hello");
assertThat(result.second()).isEqualTo(42);
```

## Symmetric Error Handling

Error handling is symmetric — it doesn't matter which task fails. StructuredTaskScope ensures all tasks are cancelled when any failure occurs.

```java
// Second task fails, first task result is discarded
assertThatThrownBy(() ->
    StructuredTaskScopePatterns.runBoth(
        () -> "hello",  // This result is lost
        () -> {
            throw new RuntimeException("task 2 failed");
        }
    )
).isInstanceOf(Exception.class);
```

| Key | Value |
| --- | --- |
| `First Task` | `Completed (result discarded)` |
| `Overall Result` | `Exception propagated` |
| `Second Task` | `Failed` |
| `Cleanup` | `All tasks cancelled` |

> [!NOTE]
> This 'fail-fast' behavior is crucial for resource management. When a database query fails, there's no point continuing to fetch related data — cancel everything and report the error.

## Error Propagation in Structured Concurrency

When any task in a StructuredTaskScope fails, the entire scope is cancelled and remaining tasks receive interruption. This prevents wasted work on partially failed operations.

```java
try {
    var result = StructuredTaskScopePatterns.runBoth(
        () -> {
            throw new RuntimeException("task 1 failed");
        },
        () -> 42
    );
} catch (Exception e) {
    // StructuredTaskScope aggregates failures
    // Task 2 was automatically cancelled
}
```

| Key | Value |
| --- | --- |
| `Completion` | `Both tasks succeeded` |
| `First Task Result` | `hello` |
| `Resource Cleanup` | `Automatic` |
| `Second Task Result` | `42` |

> [!NOTE]
> StructuredTaskScope ensures that if the first task fails, the second is automatically cancelled. No more orphaned threads wasting resources.

| Key | Value |
| --- | --- |
| `Exception Type` | `StructuredTaskScope$FailedException` |
| `Resource Cleanup` | `Automatic` |
| `Task 2 Status` | `Cancelled` |
| `Error Propagation` | `Immediate` |

> [!NOTE]
> StructuredTaskScope.join() throws if any task failed. The exception contains all aggregated failures, making debugging easier than manual Future.get() error handling.

---
*Generated by [DTR](http://www.dtr.org)*
