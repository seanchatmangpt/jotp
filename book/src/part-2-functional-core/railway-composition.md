# Pattern 8: Railway Composition

## Context

Your fleet management system needs to process maintenance requests. Each request must be validated, the vehicle looked up, parts availability checked, a work order created, and an audit entry logged. Any step can fail.

## Problem

Nested try/catch blocks obscure the happy path. You end up with five levels of indentation, each catch block doing something slightly different. The actual business flow -- validate, lookup, check, create, log -- is invisible under the error handling scaffolding.

```java
// This is what we want to avoid
try {
    var valid = validate(request);
    try {
        var vehicle = lookupVehicle(valid.vehicleId());
        try {
            var parts = checkParts(vehicle);
            // ... three more levels deep
        } catch (PartsException e) { ... }
    } catch (VehicleNotFoundException e) { ... }
} catch (ValidationException e) { ... }
```

## Therefore

Use `Result<S, F>` to compose fallible operations as a pipeline. `Result.of()` wraps a supplier that might throw, converting exceptions into the error track. `flatMap()` chains operations that themselves return Results. `map()` transforms the success value. `fold()` collapses both tracks into a final answer.

```java
Result<WorkOrder, Exception> pipeline =
    Result.of(() -> validate(request))
        .flatMap(valid -> Result.of(() -> lookupVehicle(valid.vehicleId())))
        .flatMap(vehicle -> Result.of(() -> checkPartsAvailability(vehicle)))
        .flatMap(parts -> Result.of(() -> createWorkOrder(parts)))
        .peek(order -> auditLog.record(order));
```

Read that top to bottom. Validate. Look up. Check parts. Create order. Log it. The happy path is the code. If any step fails, the remaining steps are skipped -- `flatMap` short-circuits on the error track, just like Erlang's `{error, Reason}` propagates without explicit handling at each step.

## Finishing with fold

When you need a final value from both tracks, use `fold()`:

```java
String response = pipeline.fold(
    order -> "Work order " + order.id() + " created",
    error -> "Maintenance request failed: " + error.getMessage()
);
```

`fold()` is the eliminator -- it forces you to handle both outcomes. The compiler will not let you ignore the error case. This is the Java equivalent of Erlang's pattern match on `{ok, Value} | {error, Reason}`.

## Recovery

Sometimes a failure is not final. Use `recover()` to switch from the error track back to success:

```java
Result<WorkOrder, Exception> withFallback = pipeline
    .recover(error -> {
        if (error instanceof PartsUnavailableException) {
            return Result.ok(createBackorderWorkOrder(request));
        }
        return Result.err(error);
    });
```

`recover()` is the mirror of `flatMap()` -- it operates on the error track and can produce either a new success or a different error.

## Side effects with peek

Need to log or record metrics without changing the pipeline? `peek()` runs a side effect on the success track and returns the same Result unchanged:

```java
Result.of(() -> validate(request))
    .peek(valid -> metrics.recordValidation(valid))
    .flatMap(valid -> Result.of(() -> process(valid)))
    .peek(result -> metrics.recordProcessing(result));
```

If the Result is on the error track, `peek()` does nothing. Your side effects only fire on success.

## Pattern matching as an alternative

For simple cases, Java 26's pattern matching on sealed types works just as well:

```java
switch (pipeline) {
    case Result.Ok(var order) -> dispatch(order);
    case Result.Err(var error) -> escalate(error);
}
```

The sealed interface guarantees exhaustiveness. The compiler catches missing cases.

## Consequences

Your business logic reads as a linear pipeline instead of a nested tree. Error handling is structural, not exceptional. Each step is independently testable -- `validate()`, `lookupVehicle()`, and `checkPartsAvailability()` are plain methods that return values or throw. `Result.of()` bridges them into the railway. And `fold()` at the end ensures you never forget to handle failure.
