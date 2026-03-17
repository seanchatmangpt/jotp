# io.github.seanchatmangpt.jotp.dogfood.errorhandling.ResultRailwayTest

## Table of Contents

- [Exception Wrapping with of()](#exceptionwrappingwithof)
- [Error Recovery with recover()](#errorrecoverywithrecover)
- [Side Effects with peek() and peekError()](#sideeffectswithpeekandpeekerror)
- [Branching with fold()](#branchingwithfold)
- [Railway-Oriented Programming Basics](#railwayorientedprogrammingbasics)
- [Monad: flatMap() for Chaining Operations](#monadflatmapforchainingoperations)
- [Functor: map() for Transforming Success](#functormapfortransformingsuccess)
- [Railway-Oriented Pipelines](#railwayorientedpipelines)


## Exception Wrapping with of()

The Result.of() factory method wraps a supplier, catching exceptions and converting them to Failure results. This bridges exception-based and railway-oriented code.

```java
// Wrap supplier that may throw
Result<Integer, RuntimeException> result = ResultRailway.of(() -> 42);

// Success case: value wrapped in Success
assertThat(result.isSuccess()).isTrue();
assertThat(((ResultRailway.Success<Integer, ?>) result).value()).isEqualTo(42);

// Failure case: exception caught and wrapped
Result<Integer, RuntimeException> failed = ResultRailway.<Integer, RuntimeException>of(() -> {
    throw new IllegalStateException("boom");
});

assertThat(failed.isFailure()).isTrue();
assertThat((Throwable) ((ResultRailway.Failure<Integer, ?>) failed).error())
    .isInstanceOf(IllegalStateException.class)
    .hasMessage("boom");
```

| Key | Value |
| --- | --- |
| `Bridge` | `Exception → Railway` |
| `Success Path` | `Value wrapped in Success` |
| `Failure Path` | `Exception caught and wrapped` |
| `Exception Type` | `RuntimeException` |

> [!NOTE]
> Result.of() is perfect for wrapping legacy exception-based APIs. The exception becomes a value that can be processed with map/flatMap instead of try-catch.

## Error Recovery with recover()

The recover() method provides fallback values for failures. This is similar to Optional.orElse() but for errors — convert failures into successes with defaults.

```java
// Recover with default value on failure
String value = ResultRailway.<String, String>failure("err")
    .recover(error -> "fallback");

// Result: "fallback"
assertThat(value).isEqualTo("fallback");

// Success returns value unchanged
String value2 = ResultRailway.<String, String>success("ok")
    .recover(error -> "fallback");

// Result: "ok"
assertThat(value2).isEqualTo("ok");
```

| Key | Value |
| --- | --- |
| `Result Type` | `Success value type (T)` |
| `Pattern` | `Error recovery` |
| `Success Path` | `Returns value unchanged` |
| `Failure Path` | `Applies recovery function` |

> [!NOTE]
> recover() is perfect for providing defaults: missing config → default value, API failure → cached response, validation error → sanitized input.

## Side Effects with peek() and peekError()

The peek() methods execute side effects without transforming values. peek() runs on success, peekError() runs on failure. Useful for logging, metrics, debugging.

```java
// Peek runs on success
var called = new AtomicBoolean(false);
ResultRailway.<String, String>success("ok")
    .peek(v -> called.set(true));

assertThat(called).isTrue();

// Peek doesn't run on failure
var called2 = new AtomicBoolean(false);
ResultRailway.<String, String>failure("err")
    .peek(v -> called2.set(true));

assertThat(called2).isFalse();

// peekError runs on failure
var called3 = new AtomicBoolean(false);
ResultRailway.<String, String>failure("err")
    .peekError(e -> called3.set(true));

assertThat(called3).isTrue();
```

| Key | Value |
| --- | --- |
| `peekError()` | `Runs on failure` |
| `Return` | `Original Result (chainable)` |
| `Use Case` | `Logging, metrics, debugging` |
| `peek()` | `Runs on success` |

> [!NOTE]
> peek() returns the original Result, enabling chaining. Use it for logging and observability without affecting the computation pipeline.

## Branching with fold()

The fold() method branches on success or failure, applying different functions to each case. This is the catamorphism pattern — collapsing a structure into a value.

```java
// Fold applies different functions based on variant
int result = ResultRailway.<String, String>success("hello")
    .fold(
        value -> value.length(),    // Success branch
        error -> -1                 // Failure branch
    );

// Result: 5 (length of "hello")
assertThat(result).isEqualTo(5);

// Failure case
int result2 = ResultRailway.<String, String>failure("err")
    .fold(
        value -> value.length(),
        error -> -1
    );

// Result: -1 (error marker)
assertThat(result2).isEqualTo(-1);
```

| Key | Value |
| --- | --- |
| `Pattern` | `Catamorphism` |
| `Success Path` | `Applies success function` |
| `Failure Path` | `Applies error function` |
| `Return Type` | `Same for both branches (R)` |

> [!NOTE]
> fold() is the most flexible way to extract values from Result. Unlike orElse(), it handles both success and failure cases explicitly.

## Railway-Oriented Programming Basics

Railway-oriented programming (ROP) treats errors as values, not exceptions. A Result<T,E> is either Success<T> or Failure<E>. This enables explicit error handling without try-catch blocks.

| Aspect | Error Handling | Composition |
| --- | --- | --- |
| Exception-Based | try-catch blocks | Difficult (exception flow) |
| Railway-Oriented | Pattern matching on Result | Easy (monadic chaining) |
| Error Propagation | Type Safety | Debugging |
| Implicit (stack unwinding) | Runtime checks | Stack traces |
| Explicit (return values) | Compile-time exhaustiveness | Explicit error path |

```java
// Create success result
Result<String, String> success = ResultRailway.success("hello");

assertThat(success.isSuccess()).isTrue();
assertThat(success.isFailure()).isFalse();
assertThat(success).isInstanceOf(ResultRailway.Success.class);

// Create failure result
Result<String, String> failure = ResultRailway.failure("error");

assertThat(failure.isFailure()).isTrue();
assertThat(failure.isSuccess()).isFalse();
assertThat(failure).isInstanceOf(ResultRailway.Failure.class);
```

| Key | Value |
| --- | --- |
| `Failure Variant` | `isFailure() = true` |
| `Success Variant` | `isSuccess() = true` |
| `Pattern` | `Sealed interface` |
| `Type Safety` | `Compile-time` |

> [!NOTE]
> Result<T,E> is a sealed interface with Success<T,E> and Failure<T,E> records. The compiler enforces exhaustive pattern matching, ensuring all cases are handled.

## Monad: flatMap() for Chaining Operations

The flatMap() method chains operations that return Results. This is the monad pattern — sequencing computations that might fail, short-circuiting on the first failure.

```java
// Chain operations that return Result
Result<Integer, String> result = ResultRailway.<String, String>success("42")
    .flatMap(s -> {
        try {
            int n = Integer.parseInt(s);
            return ResultRailway.success(n);
        } catch (NumberFormatException e) {
            return ResultRailway.failure("not a number");
        }
    })
    .map(n -> n * 2);

// Result: Success(84)
assertThat(result.isSuccess()).isTrue();
```

| Key | Value |
| --- | --- |
| `Railway` | `Fail → Fail` |
| `Pattern` | `Monad` |
| `Failure Chain` | `Short-circuits (skips rest)` |
| `Success Chain` | `Continues to next operation` |

> [!NOTE]
> flatMap() enables railway-oriented programming where operations are chained like track switches. If any operation fails, the rest of the chain is skipped.

## Functor: map() for Transforming Success

The map() method transforms the success value while preserving failures. This is the functor pattern — applying a function to the wrapped value without unwrapping.

```java
// Map only applies to Success
Result<String, String> success = ResultRailway.success("hello");
Result<String, String> upper = success.map(String::toUpperCase);

assertThat(upper.isSuccess()).isTrue();
assertThat(((ResultRailway.Success<String, ?>) upper).value()).isEqualTo("HELLO");

// Map passes through Failure unchanged
Result<String, String> failure = ResultRailway.failure("err");
Result<String, String> mapped = failure.map(String::toUpperCase);

assertThat(mapped.isFailure()).isTrue();
assertThat(((ResultRailway.Failure<?, String>) mapped).error()).isEqualTo("err");
```

| Key | Value |
| --- | --- |
| `Failure.map` | `Passes through unchanged` |
| `Success.map` | `Transforms value` |
| `Pattern` | `Functor` |
| `Composition` | `Chainable` |

> [!NOTE]
> map() is one-directional: it transforms Success but ignores Failure. For two-way transformation, use fold() or bimap() if available.

## Railway-Oriented Pipelines

Railway-oriented programming chains operations like track switches. Success moves forward; failure switches to a side track and bypasses remaining operations.

```java
// Railway pipeline: strip → parse → double → format
String result = ResultRailway.<String, String>success("  42  ")
    .map(String::strip)              // Success: "42"
    .flatMap(s ->
        s.matches("\d+")
            ? ResultRailway.success(Integer.parseInt(s))  // Success: 42
            : ResultRailway.failure("not a number")        // Failure: short-circuit
    )
    .map(n -> n * 2)                // Success: 84
    .fold(n -> "result=" + n, e -> "error=" + e);

// Result: "result=84"
assertThat(result).isEqualTo("result=84");
```

| Key | Value |
| --- | --- |
| `Failure Pipeline` | `parse fails → rest skipped` |
| `Failure Result` | `"failed: not a number"` |
| `Success Result` | `"result=84"` |
| `Pattern` | `Railway switches` |
| `Success Pipeline` | `strip → parse → double → format` |

> [!NOTE]
> Railway-oriented programming makes error paths explicit and type-safe. The compiler ensures all cases are handled, and the control flow is visible in the type system.

---
*Generated by [DTR](http://www.dtr.org)*
