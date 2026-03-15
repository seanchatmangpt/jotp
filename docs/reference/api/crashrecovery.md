# CrashRecovery - "Let It Crash" with Supervised Retry

## Overview

`CrashRecovery` implements Joe Armstrong's "let it crash" philosophy in Java. Armstrong: "The key to building reliable systems is to design for failure, not to try to prevent it. If you can crash and recover, you're reliable."

Each attempt runs in an isolated virtual thread (no shared state between attempts), mirroring Erlang's lightweight process model: crash the process, supervisor retries with a fresh one.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| Supervisor restart strategy | `CrashRecovery.retry(maxAttempts, supplier)` |
| `spawn` + crash + restart | Virtual thread per attempt |
| Stateless restart | Fresh virtual thread, no state carried over |
| `gen_server:call` timeout | Isolated exception per attempt |

## Design Philosophy

> "Processes share nothing" — Joe Armstrong

Each virtual thread is a fresh "process" with no state carried over from a previous crash. Use `CrashRecovery` for resilient single-task execution; use `Supervisor` for persistent process management.

## Public API

### `retry()` - Retry with Isolation

```java
public static <T> Result<T, Exception> retry(
    int maxAttempts,
    Supplier<T> supplier
)
```

Attempt `supplier` up to `maxAttempts` times, each in an isolated virtual thread.

**Type Parameters:**
- `T` - Result type

**Parameters:**
- `maxAttempts` - Maximum number of attempts (must be >= 1)
- `supplier` - Work to attempt (runs in a fresh virtual thread each time)

**Returns:**
- `Success` with the first successful result, or
- `Failure` with the last exception if all attempts fail

**Throws:**
- `NullPointerException` if `supplier` is null
- `IllegalArgumentException` if `maxAttempts < 1`

**Example:**

```java
var result = CrashRecovery.retry(3, () -> {
    var response = http.get("https://api.example.com/data");
    if (response.status() >= 500) {
        throw new RuntimeException("Server error");
    }
    return response.body();
});

switch (result) {
    case Result.Success(var body) -> System.out.println("Got: " + body);
    case Result.Failure(var ex) -> System.err.println("Failed after 3 attempts: " + ex);
}
```

## Common Usage Patterns

### 1. Retry External API Calls

```java
class APIClient {
    Result<Data, Exception> fetchData(String url) {
        return CrashRecovery.retry(3, () -> {
            HttpResponse<String> response = httpClient.get(url);
            if (response.statusCode() >= 500) {
                throw new IOException("Server error: " + response.statusCode());
            }
            return parseData(response.body());
        });
    }
}
```

### 2. Retry Database Operations

```java
class Database {
    Result<User, Exception> getUserWithRetry(long id) {
        return CrashRecovery.retry(5, () -> {
            try (Connection conn = dataSource.getConnection()) {
                return queryUser(conn, id);
            } catch (SQLException e) {
                throw new RuntimeException("Database error", e);
            }
        });
    }
}
```

### 3. Retry with Exponential Backoff

```java
class SmartRetry {
    Result<Data, Exception> fetchWithBackoff(String url) {
        int[] delays = {1000, 2000, 4000, 8000};  // Exponential backoff

        for (int attempt = 0; attempt < delays.length; attempt++) {
            var result = CrashRecovery.retry(1, () -> fetchData(url));

            if (result.isSuccess()) {
                return result;
            }

            // Wait before next retry
            if (attempt < delays.length - 1) {
                Thread.sleep(delays[attempt]);
            }
        }

        return Result.failure(new Exception("All retries failed"));
    }
}
```

### 4. Retry with Circuit Breaker

```java
class CircuitBreaker {
    private final AtomicInteger failures = new AtomicInteger(0);
    private static final int THRESHOLD = 5;

    Result<Data, Exception> callWithCircuitBreaker(String url) {
        if (failures.get() >= THRESHOLD) {
            return Result.failure(new Exception("Circuit breaker open"));
        }

        Result<Data, Exception> result = CrashRecovery.retry(3, () -> fetchData(url));

        if (result.isFailure()) {
            failures.incrementAndGet();
        } else {
            failures.set(0);  // Reset on success
        }

        return result;
    }
}
```

### 5. Retry with Fallback

```java
class ResilientService {
    Result<Data, Exception> fetchDataOrFallback(String url) {
        return CrashRecovery.retry(3, () -> primaryApi.get(url))
            .recover(error -> {
                logger.warn("Primary failed, trying fallback: " + error.getMessage());
                return CrashRecovery.retry(3, () -> fallbackApi.get(url))
                    .recover(e -> {
                        logger.error("Fallback also failed: " + e.getMessage());
                        return Result.ok(loadFromCache(url));
                    });
            });
    }
}
```

## Implementation Details

### Virtual Thread Isolation

Each attempt runs in a fresh virtual thread:

```java
private static <T> Result<T, Exception> runInVirtualThread(Supplier<T> supplier) {
    var ref = new Object() { Result<T, Exception> result; };
    var thread = Thread.ofVirtual().start(() -> ref.result = Result.of(supplier::get));
    try {
        thread.join();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Result.failure(e);
    }
    return ref.result;
}
```

**Benefits:**
- No shared state between attempts
- Crashes in one attempt don't affect next attempt
- Lightweight (~1 KB heap per virtual thread)
- Exception propagation via `Result.of()`

### Retry Logic

```java
Result<T, Exception> last = Result.failure(new IllegalStateException("no attempts"));
for (int attempt = 0; attempt < maxAttempts; attempt++) {
    last = runInVirtualThread(supplier);
    if (last.isSuccess()) {
        return last;  // Early exit on first success
    }
}
return last;  // Return last failure if all attempts fail
```

**Characteristics:**
- Immediate retry on failure (no backoff by default)
- Returns first success immediately
- Returns last failure if all attempts exhausted
- No state carried between attempts

## Thread-Safety Guarantees

- **Isolated attempts:** Each attempt runs in separate virtual thread
- **No shared state:** Each attempt starts fresh
- **Exception safety:** Exceptions converted to `Result.Err`
- **Interrupt handling:** Parent thread interrupt handled gracefully

## Performance Characteristics

- **Memory overhead:** ~1 KB per virtual thread attempt
- **CPU overhead:** Minimal (virtual threads are lightweight)
- **I/O bound:** Ideal for network/disk operations
- **Fail-fast:** Returns immediately on first success

## Related Classes

- **`Result<T,E>`** - Railway-oriented error handling
- **`Parallel`** - Parallel fan-out with fail-fast semantics
- **`Supervisor`** - Persistent process supervision and restart
- **`Thread.ofVirtual()`** - Java 21+ virtual thread factory

## Best Practices

1. **Set reasonable max attempts:** 3-5 for external APIs, 1-2 for fast fallbacks
2. **Combine with Result:** Always check `isSuccess()` before using value
3. **Add logging:** Log failures before final attempt
4. **Use for I/O:** Ideal for network/disk operations, not CPU-bound work
5. **Combine with backoff:** Add sleep between retries for external APIs

## Design Rationale

**Why not use CompletableFuture.retry()?**

Java's `CompletableFuture` doesn't have built-in retry. JOTP's `CrashRecovery` provides:

1. **Isolation:** Each attempt in fresh virtual thread (no shared state)
2. **Explicit error handling:** `Result` forces error handling
3. **Composable:** Integrates with railway-oriented programming
4. **Lightweight:** Virtual threads vs platform threads
5. **OTP semantics:** Matches Erlang's crash-and-restart model

**Comparison with Supervisor:**

| Feature | CrashRecovery | Supervisor |
|---------|---------------|------------|
| Use case | Single-task retry | Long-running process |
| State | Stateless | Persistent state |
| Restart | Fresh virtual thread | Swap Proc delegate |
| Monitoring | No | Built-in statistics |
| Children | No | Supervision tree |

Use `CrashRecovery` for one-shot operations; use `Supervisor` for long-lived processes.

## Armstrong's "Let It Crash" Philosophy

> "If you can crash and recover, you're reliable." — Joe Armstrong

Traditional error handling:

```java
// Defensive programming (fragile)
try {
    doSomething();
} catch (Exception e) {
    // Handle every possible error
    handleSpecificError(e);
}
```

OTP-style "let it crash":

```java
// "Let it crash" (resilient)
Result<Data, Exception> result = CrashRecovery.retry(3, () -> {
    return doSomething();  // Crash if needed
});

// Supervisor handles recovery
```

**Benefits:**
- **Simpler code:** No defensive if-statements
- **Focus on happy path:** Assume success, handle failure at edges
- **Isolation:** Crashes don't corrupt shared state
- **Recovery:** Supervisor decides recovery strategy

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `spawn(Module, Func, Args)` | `Thread.ofVirtual().start(...)` |
| Supervisor restart | `CrashRecovery.retry(maxAttempts, ...)` |
| `exit(normal)` | `Result.success(value)` |
| `exit(reason)` | `Result.failure(exception)` |
| Stateless restart | Fresh virtual thread per attempt |

## Common Pitfalls

1. **Too many retries:** More retries ≠ more reliability (exponential backoff helps)
2. **Retrying non-idempotent operations:** Ensure operations are safe to retry
3. **Ignoring failures:** Always check `isSuccess()` before using value
4. **Retrying CPU-bound work:** Virtual threads help with I/O, not CPU
5. **No backoff:** Immediate retry can overwhelm failing services

## Performance Tips

1. **Measure first:** Profile before adding retry logic
2. **Add backoff:** Sleep between retries for external APIs
3. **Use circuit breakers:** Stop retrying after threshold
4. **Combine with fallbacks:** Try alternatives before giving up
5. **Monitor failures:** Track retry statistics for tuning
