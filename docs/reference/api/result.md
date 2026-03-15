# Result<T,E> - Railway-Oriented Programming

## Overview

`Result<T,E>` is a sealed interface for success/failure outcomes (railway-oriented programming). Joe Armstrong: "In Erlang, we don't throw exceptions across process boundaries. We return `{ok, Value}` or `{error, Reason}`. This forces the caller to handle both cases explicitly."

This is the Java 26 equivalent of Erlang/OTP's tagged tuples. Instead of throwing exceptions that propagate invisibly up the call stack, operations return a `Result` that the caller must pattern-match on — making error handling explicit and composable.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `{ok, Value}` | `Result.ok(value)` / `Result.Ok<T,E>(var v)` |
| `{error, Reason}` | `Result.err(reason)` / `Result.Err<T,E>(var e)` |
| `case {ok, V} -> ...` | `case Result.Ok(var v) -> ...` |
| `case {error, E} -> ...` | `case Result.Err(var e) -> ...` |

## Railway-Oriented Programming

Think of success and failure as two parallel tracks. Operations on the success track (`map`, `flatMap`) are skipped if the result is already on the failure track. This lets you chain operations without nested if-statements:

```java
Result<Order, OrderError> result = Result.of(() -> validate(request))
    .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
    .flatMap(order -> Result.of(() -> reserveInventory(order)))
    .peek(order -> auditLog.add(order))
    .recover(error -> {
        metrics.recordFailure(error);
        return Result.failure(error);
    });
```

## Type Hierarchy

```java
public sealed interface Result<S, F> permits Result.Ok, Result.Err {
    record Ok<S, F>(S value) implements Result<S, F> {}
    record Err<S, F>(F error) implements Result<S, F> {}
}
```

**Type Parameters:**
- **`S`** - Success type (the value carried on success)
- **`F`** - Failure type (the error carried on failure, often `Exception` or sealed error hierarchy)

## Public API

### Factory Methods

#### `ok()` / `success()` - Create Success

```java
static <S, F> Result<S, F> ok(S value)
static <S, F> Result<S, F> success(S value)
```

Create a successful result containing `value`.

**Example:**
```java
Result<String, Exception> result = Result.ok("Hello");
Result<String, Exception> result2 = Result.success("Hello");  // Alias
```

#### `err()` / `failure()` - Create Failure

```java
static <S, F> Result<S, F> err(F error)
static <S, F> Result<S, F> failure(F error)
```

Create a failed result containing `error`.

**Example:**
```java
Result<String, Exception> result = Result.err(new IOException("Failed"));
Result<String, Exception> result2 = Result.failure(new IOException("Failed"));
```

#### `of()` - Wrap Throwing Supplier

```java
static <S, F> Result<S, F> of(Supplier<S> supplier)
```

Wrap a supplier that may throw — converts exceptions to `Err`.

**Example:**
```java
Result<String, Exception> result = Result.of(() -> {
    FileInputStream fis = new FileInputStream("data.txt");
    return readAll(fis);
});
// Returns Ok(data) or Err(IOException)
```

### Query Methods

#### `isSuccess()` - Check Success

```java
default boolean isSuccess()
```

Returns `true` if this is a successful result.

#### `isError()` / `isFailure()` - Check Failure

```java
default boolean isError()
default boolean isFailure()
```

Returns `true` if this is a failed result.

### Transformation Methods

#### `map()` - Transform Success Value

```java
default <T> Result<T, F> map(Function<? super S, ? extends T> mapper)
```

Transform the success value — railway "map" operation.

- If success: applies `mapper` to value and returns new result
- If failure: returns same failure unchanged (short-circuits)

**Example:**
```java
Result<Integer, Exception> r1 = Result.ok(5);
Result<String, Exception> r2 = r1.map(i -> "Number: " + i);
// Result.ok("Number: 5")

Result<Integer, Exception> r3 = Result.err(new Exception());
Result<String, Exception> r4 = r3.map(i -> "Number: " + i);
// Same Result.err(new Exception())
```

#### `flatMap()` - Chain Operations

```java
default <T> Result<T, F> flatMap(Function<? super S, ? extends Result<T, F>> mapper)
```

Chain operations that return Results — railway "flatMap" operation.

- If success: applies `mapper` to get next result
- If failure: returns same failure unchanged (short-circuits)

**Example:**
```java
Result<String, Exception> validate(String input) {
    if (input == null || input.isEmpty()) {
        return Result.err(new IllegalArgumentException("Empty input"));
    }
    return Result.ok(input.trim());
}

Result<Integer, Exception> parse(String s) {
    return Result.of(() -> Integer.parseInt(s));
}

Result<Integer, Exception> result = validate(" 42 ")
    .flatMap(s -> parse(s));
// Result.ok(42)
```

#### `peek()` - Side-Effect on Success

```java
default Result<S, F> peek(Consumer<? super S> action)
```

Apply a side-effect action on success, without changing the result.

- If success: applies `action` to value and returns same result
- If failure: action not applied, returns same failure

**Example:**
```java
Result.of(() -> validate(request))
    .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
    .peek(order -> auditLog.add(order))
    .peek(order -> metrics.recordOrder(order));
```

#### `recover()` - Recover from Failure

```java
default Result<S, F> recover(Function<? super F, ? extends Result<S, F>> handler)
```

Recover from a failure by applying a recovery function.

- If success: returns same success unchanged
- If failure: applies `handler` to error and returns result

**Example:**
```java
Result<Data, Exception> result = Result.of(() -> fetchData())
    .recover(error -> {
        logger.warn("Fetch failed, using cache: " + error.getMessage());
        return Result.ok(loadFromCache());
    });
```

### Eliminator Methods

#### `orElse()` - Get Value or Default

```java
default S orElse(S defaultValue)
```

Get the success value, or a default if this is a failure.

**Example:**
```java
String value = result.orElse("default");
```

#### `orElseThrow()` - Get Value or Throw

```java
default S orElseThrow()
```

Get the success value, or throw if this is a failure.

- If failure value is `Throwable`, it's rethrown directly
- Otherwise, failure value is converted to string and wrapped in `RuntimeException`

**Example:**
```java
String value = result.orElseThrow();
```

#### `fold()` - Handle Both Cases

```java
default <T> T fold(
    Function<? super S, ? extends T> onSuccess,
    Function<? super F, ? extends T> onError
)
```

Fold both outcomes into a single value — "eliminator" for the Result type.

**Example:**
```java
String message = result.fold(
    value -> "Success: " + value,
    error -> "Error: " + error
);
```

## Common Usage Patterns

### 1. Validation Pipeline

```java
sealed interface ValidationResult permits Valid, Invalid {}
record Valid(String value) implements ValidationResult {}
record Invalid(String reason) implements ValidationResult {}

Result<Valid, Invalid> validateEmail(String email) {
    if (email == null || !email.contains("@")) {
        return Result.err(new Invalid("Invalid email"));
    }
    return Result.ok(new Valid(email));
}

Result<String, Invalid> normalizeEmail(Valid valid) {
    return Result.ok(valid.value().toLowerCase().trim());
}

// Chain validations
Result<String, Invalid> result = validateEmail(userInput)
    .flatMap(valid -> normalizeEmail(valid));
```

### 2. Error Recovery Chain

```java
Result<Data, Exception> fetchData() {
    return Result.of(() -> httpClient.get("https://api.example.com/data"))
        .recover(error -> {
            // Try fallback server
            return Result.of(() -> fallbackClient.get("https://backup.example.com/data"))
                .recover(e -> {
                    // Try cache
                    return Result.ok(cache.get("data"));
                });
        });
}
```

### 3. Logging and Metrics

```java
Result<Order, OrderError> processOrder(Request req) {
    return Result.of(() -> validate(req))
        .peek(valid -> metrics.increment("orders.validated"))
        .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
        .peek(order -> metrics.increment("orders.priced"))
        .flatMap(order -> Result.of(() -> reserveInventory(order)))
        .peek(order -> metrics.increment("orders.inventory_reserved"))
        .peek(order -> audit.log(order))
        .recover(error -> {
            metrics.increment("orders.failed");
            logger.error("Order processing failed", error);
            return Result.failure(error);
        });
}
```

### 4. Railway-Oriented Business Logic

```java
sealed interface OrderError implements Supplier<String> {
    record InvalidInput(String reason) implements OrderError {
        public String get() { return reason; }
    }
    record OutOfStock(String item) implements OrderError {
        public String get() { return item; }
    }
    record PaymentFailed(String reason) implements OrderError {
        public String get() { return reason; }
    }
}

Result<Order, OrderError> processOrder(OrderRequest req) {
    return validateOrder(req)
        .flatMap(validated -> checkInventory(validated))
        .flatMap(order -> processPayment(order))
        .map(paid -> shipOrder(paid));
}
```

### 5. Async Integration

```java
CompletableFuture<Result<Data, Exception>> fetchDataAsync() {
    return CompletableFuture.supplyAsync(() ->
        Result.of(() -> httpClient.get(url))
    );
}

// Usage
fetchDataAsync().thenAccept(result -> result.fold(
    data -> process(data),
    error -> handleError(error)
));
```

## Java 26 Features Used

**Sealed Interface:**
- `public sealed interface Result<S, F>` restricts implementations to `Ok` and `Err`
- Enables exhaustive pattern matching and compiler verification

**Records:**
- Each variant is a record (immutable, transparent, with destructuring support)

**Pattern Matching:**
```java
switch (result) {
    case Result.Ok(var v) -> System.out.println("Success: " + v);
    case Result.Err(var e) -> System.err.println("Error: " + e);
}
```

**Type-Safe Railway:**
- All transformations (`map`, `flatMap`, `fold`) leverage generics
- Type safety maintained across both tracks

## Performance Characteristics

- **No boxing overhead:** Records are transparent, no wrapper objects
- **Pattern matching:** JVM-level switch optimization
- **Immutable:** Safe to share across threads
- **Stack allocation:** JVM may allocate records on stack

## Related Classes

- **`CrashRecovery`** - Retry logic with Result return type
- **`Parallel`** - Parallel execution returning Result
- **`Optional<T>`** - Java's standard optional type (no error track)
- **`Either<L,R>`** - Functional programming alternative (Vavr, etc.)

## Best Practices

1. **Use sealed error types:** Define error hierarchy with records
2. **FlatMap for chaining:** Use `flatMap` to chain operations that return Result
3. **Peek for side effects:** Use `peek` for logging/metrics without changing value
4. **Recover for fallbacks:** Use `recover` to implement fallback strategies
5. **Pattern match to consume:** Use `switch` with sealed patterns for consumption

## Design Rationale

**Why Result instead of exceptions?**

1. **Explicit error handling:** Compiler forces you to handle both cases
2. **Composable:** Chain operations without nested try-catch
3. **Type-safe:** Error type is part of the type signature
4. **Debuggable:** Error flow is visible in code, not hidden in stack traces
5. **Functional:** Fits functional programming paradigm

**Migration from exceptions:**

```java
// Old way (exceptions)
try {
    String data = fetchData();
    Order order = validate(data);
    Result result = process(order);
} catch (IOException e) {
    logger.error("Failed", e);
}

// New way (Result)
Result<String, IOException> data = Result.of(() -> fetchData());
Result<Order, ValidationError> order = data.flatMap(d -> validate(d));
Result<Result, ProcessError> result = order.flatMap(o -> process(o));
```

## Comparison with Other Languages

| Language | Type | JOTP Equivalent |
|----------|------|-----------------|
| Rust | `Result<T, E>` | `Result<S, F>` |
| Haskell | `Either E T` | `Result<S, F>` |
| Scala | `Try[T]` | `Result<S, Exception>` |
| Elm | `Result e a` | `Result<S, F>` |
| Erlang | `{ok, V} \| {error, R}` | `Result.Ok<S,F>` \| `Result.Err<S,F>` |

## Two Naming Conventions

JOTP supports both Erlang-style and railway-oriented naming:

- **`ok()` / `err()`** - Erlang-style short names
- **`success()` / `failure()`** - Explicit railway-oriented names

Both produce identical behavior. Use one convention consistently:

```java
// Erlang style
Result<String, Exception> r1 = Result.ok("value");
Result<String, Exception> r2 = Result.err(new Exception());

// Railway style
Result<String, Exception> r3 = Result.success("value");
Result<String, Exception> r4 = Result.failure(new Exception());
```
