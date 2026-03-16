# Parallel - Structured Fan-Out

## Overview

`Parallel` implements parallel fan-out with fail-fast semantics, inspired by Erlang's supervisor philosophy. Joe Armstrong: "If any process fails, fail fast. Don't mask errors — let a supervisor handle them."

This mirrors `erlang:pmap/2`: parallel map with all-or-nothing semantics. Uses Java 26 `StructuredTaskScope` to run all tasks concurrently on virtual threads, and on the first failure the scope cancels all remaining tasks immediately (crash one, crash all). Returns `Result<List<T>, Exception>` integrating with the existing railway-oriented `Result` type.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `pmap(Fun, List)` | `Parallel.all(List<Supplier>)` |
| `spawn_monitor` + collect | `StructuredTaskScope` + joiner |
| All-or-nothing semantics | Fail-fast with `awaitAllSuccessfulOrThrow` |
| Link propagation | Subtask cancellation on first failure |

## Architecture

```
StructuredTaskScope
    │
    ├─ Subtask 1 (virtual thread)
    ├─ Subtask 2 (virtual thread)
    ├─ Subtask 3 (virtual thread)
    └─ Subtask N (virtual thread)
         │
         ├─ Success → collect result
         │
         └─ First Failure → cancel all, return error
```

## Public API

### `all()` - Parallel Execution with Fail-Fast

```java
public static <T> Result<List<T>, Exception> all(
    List<Supplier<T>> tasks
)
```

Run all `tasks` concurrently on virtual threads with fail-fast semantics.

**Type Parameters:**
- `T` - Result type of each task

**Parameters:**
- `tasks` - List of suppliers to run in parallel (each runs on a virtual thread)

**Returns:**
- `Success(List)` with results in fork order, or
- `Failure(Exception)` with the first failure

**Behavior:**
- All tasks run concurrently on virtual threads
- On first failure, all remaining tasks are cancelled immediately
- Success results are returned in fork order (not completion order)
- Uses try-with-resources for proper cleanup

**Example:**

```java
var tasks = List.of(
    () -> service.fetchUser(1),
    () -> service.fetchUser(2),
    () -> service.fetchUser(3)
);

var result = Parallel.all(tasks);

switch (result) {
    case Result.Success(var users) ->
        users.forEach(u -> System.out.println("Got: " + u));
    case Result.Failure(var ex) ->
        System.err.println("Fetch failed: " + ex);
}
```

## Common Usage Patterns

### 1. Parallel API Calls

```java
class UserService {
    Result<List<User>, Exception> fetchUsers(List<Long> ids) {
        var tasks = ids.stream()
            .map(id -> (Supplier<User>) () -> api.fetchUser(id))
            .toList();

        return Parallel.all(tasks);
    }
}

// Usage:
switch (userService.fetchUsers(List.of(1L, 2L, 3L))) {
    case Result.Success(var users) -> processUsers(users);
    case Result.Failure(var ex) -> handleError(ex);
}
```

### 2. Parallel Data Processing

```java
class DataProcessor {
    Result<List<ProcessedData>, Exception> processBatch(List<RawData> batch) {
        var tasks = batch.stream()
            .map(raw -> (Supplier<ProcessedData>) () -> process(raw))
            .toList();

        return Parallel.all(tasks);
    }

    private ProcessedData process(RawData raw) {
        // Expensive computation
        return transform(raw);
    }
}
```

### 3. Parallel Validation

```java
sealed interface ValidationResult permits Valid, Invalid {}

class RequestValidator {
    Result<List<ValidationResult>, Exception> validateAll(List<Request> requests) {
        var tasks = requests.stream()
            .map(req -> (Supplier<ValidationResult>) () -> validate(req))
            .toList();

        return Parallel.all(tasks);
    }

    private ValidationResult validate(Request req) {
        if (req.invalid()) {
            throw new IllegalArgumentException("Invalid request: " + req);
        }
        return new Valid(req);
    }
}
```

### 4. Fan-Out with Recovery

```java
class RobustService {
    Result<List<Data>, Exception> fetchWithRecovery(List<Long> ids) {
        var tasks = ids.stream()
            .map(id -> (Supplier<Data>) () -> {
                try {
                    return api.fetch(id);
                } catch (Exception e) {
                    // Try fallback
                    return cache.get(id);
                }
            })
            .toList();

        return Parallel.all(tasks);
    }
}
```

### 5. Conditional Parallel Execution

```java
class SmartExecutor {
    <T> Result<List<T>, Exception> execute(List<Supplier<T>> tasks, boolean parallel) {
        if (!parallel || tasks.size() < 2) {
            // Sequential
            return Result.of(() -> tasks.stream()
                .map(Supplier::get)
                .toList());
        }

        // Parallel
        return Parallel.all(tasks);
    }
}
```

## Java 26 Structured Concurrency

**Virtual Threads:**
- Each task runs on its own virtual thread spawned by the scope
- Zero overhead, thousands per process
- Automatically blocked on I/O without consuming platform threads

**StructuredTaskScope:**
- Java 26's primary concurrency primitive for structured fan-out
- Try-with-resources ensures proper cleanup and cancellation semantics
- No orphaned tasks or resource leaks

**Joiner Semantics:**
- `awaitAllSuccessfulOrThrow()` enforces fail-fast
- If any task throws, all remaining tasks are cancelled immediately
- Success path carries results in fork order

**Railway-Oriented Result:**
- Integrates with `Result<T,E>` type
- Success path: `List<T>` (results in fork order)
- Failure path: first `Exception` (subtasks cancelled)

## Performance Characteristics

- **Concurrency:** True parallelism - all tasks run simultaneously
- **Fail-fast:** O(1) cancellation propagation on first error
- **Memory overhead:** ~1 KB per virtual thread (vs ~1 MB per platform thread)
- **Scheduling:** Fork-join pool with work-stealing
- **Cancellation:** Immediate interruption of remaining tasks

## Thread-Safety Guarantees

- **Task isolation:** Each task runs in its own virtual thread
- **Result collection:** Atomic collection of successful results
- **Exception propagation:** Single exception propagated to caller
- **Scope cleanup:** Guaranteed cleanup via try-with-resources

## Related Classes

- **`Result<T,E>`** - Railway-oriented error handling
- **`CrashRecovery`** - Retry logic for single-task execution
- **`StructuredTaskScope`** - Java 26 structured concurrency primitive
- **`Supplier<T>`** - Task representation (lazy evaluation)

## Best Practices

1. **Keep tasks pure:** Avoid shared mutable state between tasks
2. **Handle failures:** Use pattern matching on `Result`
3. **Resource cleanup:** Tasks should clean up on cancellation
4. **Idempotent operations:** Tasks may be cancelled mid-execution
5. **Measure before optimizing:** Parallel has overhead; profile first

## Design Rationale

**Why structured concurrency instead of CompletableFuture?**

Java 26's `StructuredTaskScope` provides several advantages over traditional `CompletableFuture`:

1. **Structured lifetime:** Scope lifecycle is clear (try-with-resources)
2. **Automatic cancellation:** Fail-fast is built-in via joiner
3. **No orphaned tasks:** Guaranteed cleanup on exception
4. **Virtual threads:** True lightweight concurrency vs platform threads
5. **Error handling:** First exception wins (no exception suppression)

**Comparison:**

```java
// Old way (CompletableFuture)
CompletableFuture[] futures = ids.stream()
    .map(id -> CompletableFuture.supplyAsync(() -> fetch(id)))
    .toArray(CompletableFuture[]::new);
CompletableFuture.allOf(futures).join();
// No automatic cancellation on failure!

// New way (StructuredTaskScope)
var tasks = ids.stream()
    .map(id -> (Supplier<Data>) () -> fetch(id))
    .toList();
Result<List<Data>, Exception> result = Parallel.all(tasks);
// Automatic cancellation on first failure!
```

## Armstrong's "Let It Crash" Philosophy

> "If any process fails, fail fast. Don't mask errors — let a supervisor handle them."

`Parallel` embodies this philosophy:

1. **Fail-fast:** First error cancels all remaining tasks
2. **No error masking:** Exceptions are not caught or hidden
3. **Supervisor recovery:** Use `CrashRecovery.retry()` for retry logic
4. **Explicit error handling:** `Result` forces caller to handle both cases

This is the opposite of "try-catch everything" - it makes failures visible and forces the caller to decide how to handle them.

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `pmap(Fun, List)` | `Parallel.all(List<Supplier>)` |
| `spawn_monitor` + collect | `StructuredTaskScope` + joiner |
| `exit(normal)` | `Result.success(value)` |
| `exit(reason)` | `Result.failure(exception)` |
| Link propagation | Subtask cancellation |

## Performance Tips

1. **Batch size:** Parallel overhead is negligible for 10+ tasks
2. **I/O-bound tasks:** Virtual threads shine - excellent for network calls
3. **CPU-bound tasks:** Still benefits, but limited by CPU cores
4. **Avoid over-parallelization:** More tasks ≠ more throughput
5. **Profile first:** Measure before optimizing

## Common Pitfalls

1. **Shared mutable state:** Tasks should not share state
2. **Blocking in tasks:** OK for I/O, but avoid `Thread.sleep()`
3. **Resource exhaustion:** Don't spawn 1M tasks (use batching)
4. **Ignoring cancellation:** Tasks should handle `InterruptedException`
5. **Leaked resources:** Always use try-with-resources for resources
