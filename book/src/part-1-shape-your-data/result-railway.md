# Pattern 4: Result Railway

## Context

FleetPulse needs to process dispatch requests that can fail at multiple stages: validation, authorization, scheduling. Each stage might succeed or fail, and you need to handle both outcomes cleanly without deeply nested try-catch blocks. JOTP processes return state through `ask()`, but the business logic within a handler often involves operations that can go wrong.

## Problem

Exceptions are invisible control flow. A method that throws does not declare it in its return type (unless checked, and Java developers have largely abandoned checked exceptions). When you chain three operations that each might throw, you end up with nested try-catch blocks or a single catch-all that swallows meaningful error context. Worse, exceptions cross process boundaries in ways that are hard to reason about.

## Forces

Java's exception model is deeply embedded in the ecosystem. Third-party libraries throw. I/O throws. Parsing throws. You cannot avoid exceptions entirely, but you can contain them at the boundary and work with values inside your logic.

## Therefore

**Use `Result<S, F>` to represent success or failure as a value.** JOTP's `Result` is a sealed interface with four variants -- two naming conventions for the same concept:

```java
public sealed interface Result<S, F>
        permits Result.Ok, Result.Err, Result.Success, Result.Failure {
    record Ok<S, F>(S value) implements Result<S, F> {}
    record Err<S, F>(F error) implements Result<S, F> {}
    record Success<S, F>(S value) implements Result<S, F> {}
    record Failure<S, F>(F error) implements Result<S, F> {}
}
```

`Ok`/`Err` are the short Erlang-style names. `Success`/`Failure` are more explicit. They are interchangeable -- use whichever reads better in your code.

**Creating results:**

```java
Result<Order, String> good = Result.ok(order);
Result<Order, String> bad  = Result.err("Vehicle not available");

// Wrapping code that throws:
Result<Route, Exception> route = Result.of(() -> routePlanner.calculate(origin, dest));
```

`Result.of()` is the bridge between exception-based APIs and railway programming. If the supplier throws, you get an `Err` containing the exception. If it succeeds, you get an `Ok` with the value.

**The railway: chaining operations with `map()` and `flatMap()`.**

Think of success and failure as two parallel tracks. Operations on the success track are skipped if you are already on the failure track:

```java
Result<ScheduledDispatch, DispatchError> result =
    validateRequest(request)                          // Result<ValidRequest, DispatchError>
        .flatMap(valid -> authorizeDispatcher(valid))  // Result<AuthorizedRequest, DispatchError>
        .flatMap(auth -> scheduleVehicle(auth))        // Result<ScheduledDispatch, DispatchError>
        .peek(dispatch -> auditLog.record(dispatch))   // side-effect on success only
        .recover(error -> {
            metrics.recordFailure(error);
            return Result.failure(error);              // stay on failure track
        });
```

If `validateRequest` returns an `Err`, neither `authorizeDispatcher` nor `scheduleVehicle` runs. The error propagates through the chain untouched. No try-catch. No null checks. No early returns.

**Key operations on the Result API:**

- **`map(fn)`** -- transform the success value. If this is a failure, the function is not called.
- **`flatMap(fn)`** -- chain to another operation that itself returns a `Result`. This is how you sequence fallible steps.
- **`peek(action)`** -- run a side-effect (logging, metrics) on success without changing the result.
- **`recover(fn)`** -- handle a failure by producing a new `Result`. This is the failure-track equivalent of `flatMap`.
- **`fold(onSuccess, onError)`** -- collapse both tracks into a single value. This is how you exit the railway.
- **`orElse(default)`** -- extract the success value, or return a default on failure.
- **`orElseThrow()`** -- extract the success value, or throw if this is a failure.
- **`isSuccess()` / `isError()`** -- boolean checks when you just need to branch.

**Pattern matching works too:**

```java
return switch (result) {
    case Ok(var dispatch) -> ResponseEntity.ok(dispatch);
    case Err(var error)   -> ResponseEntity.badRequest().body(error.message());
    // Success and Failure also match, but Ok/Err cover all cases
};
```

**Wrapping third-party code at the boundary:**

```java
Result<VehicleData, Exception> data = Result.of(() -> externalApi.fetchVehicle(id));

// Now you are on the railway -- chain away
data.map(VehicleData::toInternal)
    .flatMap(internal -> validateVehicle(internal))
    .peek(v -> cache.put(v.id(), v));
```

## Resulting Context

Error handling is explicit, composable, and type-safe. Every function signature tells you whether it can fail, and what the failure type is. You cannot forget to handle an error because the `Result` type forces you to unwrap it -- via `fold`, `orElse`, `orElseThrow`, or pattern matching.

The trade-off is verbosity compared to letting exceptions fly. You write more function signatures, more `flatMap` calls, more explicit handling. This is the point. Hidden error paths are not simpler; they are just hidden. Making them visible in the type system is how you build systems that handle failure by design rather than by accident.

## Related Patterns

- **Sealed Message Protocols** uses the same sealed-interface technique to close a type hierarchy.
- **State as Value** pairs naturally with Result -- a handler can return `Result<VehicleState, HandleError>` when a transition might fail.
- **Crash Recovery** (Part 2) uses `Result` as its return type: `CrashRecovery.retry()` returns `Result<T, Exception>`.

## Try It

Write three functions: `parseCoordinates(String raw)` returning `Result<Position, String>`, `validateBounds(Position p)` returning `Result<Position, String>`, and `geocode(Position p)` returning `Result<Address, String>`. Chain them with `flatMap` to go from raw input to a geocoded address. Then use `fold` to produce either a success message or an error message. Finally, wrap a throwing JSON parser with `Result.of()` and feed its output into your chain.
