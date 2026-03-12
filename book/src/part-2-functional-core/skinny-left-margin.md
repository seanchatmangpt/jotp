# Pattern 10: Skinny Left Margin

## Context

You are writing functional-style Java. You have pipelines, switch expressions, lambda bodies, and record constructors. Each layer of nesting pushes your code further to the right. Your handler is correct but hard to read because the actual logic starts at column 24.

## Problem

Deep nesting is a code smell even in pure functions. A `flatMap` inside a `switch` inside a lambda inside a method produces code that is technically clean but visually hostile. The reader's eye tracks indentation levels instead of logic flow. Reviews miss bugs hiding in the rightward drift.

```java
// Before: the logic is correct but buried
BiFunction<State, Msg, State> handler = (state, msg) -> {
    return switch (msg) {
        case ProcessOrder o -> {
            var result = Result.of(() -> validate(o))
                .flatMap(valid -> {
                    return Result.of(() -> {
                        if (valid.quantity() > 0) {
                            return createOrder(valid);
                        } else {
                            throw new IllegalArgumentException("bad qty");
                        }
                    });
                })
                .fold(
                    order -> state.withOrder(order),
                    error -> state.withError(error.getMessage())
                );
            yield result;
        }
        default -> state;
    };
};
```

Five indentation levels. The important line -- `createOrder(valid)` -- sits at column 28. The closing braces stack up like geological strata.

## Therefore

Apply three techniques to keep the left margin skinny: extract named methods, use switch expressions directly, and prefer early returns.

### Extract named methods

Pull lambda bodies into named static methods. Names document intent. Indentation stays flat.

```java
BiFunction<State, Msg, State> handler = (state, msg) ->
    switch (msg) {
        case ProcessOrder o -> processOrder(state, o);
        default -> state;
    };

static State processOrder(State state, ProcessOrder order) {
    return Result.of(() -> validate(order))
        .flatMap(valid -> checkQuantity(valid))
        .fold(
            confirmed -> state.withOrder(confirmed),
            error -> state.withError(error.getMessage())
        );
}

static Result<Order, Exception> checkQuantity(ValidOrder valid) {
    if (valid.quantity() <= 0) {
        return Result.err(new IllegalArgumentException("bad quantity"));
    }
    return Result.of(() -> createOrder(valid));
}
```

Maximum indentation: two levels. Every method fits on a screen. The handler switch is three lines.

### Use switch expressions, not statements

Switch expressions return values directly. No `yield`, no braces around single-expression cases, no `break`.

```java
// Statement style -- extra ceremony
var label = switch (severity) {
    case "critical" -> { yield "CRITICAL"; }
    case "warning" -> { yield "WARNING"; }
    default -> { yield "INFO"; }
};

// Expression style -- flat and direct
var label = switch (severity) {
    case "critical" -> "CRITICAL";
    case "warning"  -> "WARNING";
    default         -> "INFO";
};
```

### Prefer early returns over else branches

When a method has a guard condition, return early instead of wrapping the rest in an `else` block.

```java
// Before: else branch adds one indent level
static VehicleState applyAlert(VehicleState state, EngineAlert alert) {
    if (alert.severity().equals("critical")) {
        return state.withShutdown(true);
    } else {
        return new VehicleState(
            state.lat(), state.lng(), state.lastSeen(),
            state.fuelLevel(),
            appendAlert(state.activeAlerts(), alert.code())
        );
    }
}

// After: early return, no else
static VehicleState applyAlert(VehicleState state, EngineAlert alert) {
    if (alert.severity().equals("critical")) {
        return state.withShutdown(true);
    }
    return new VehicleState(
        state.lat(), state.lng(), state.lastSeen(),
        state.fuelLevel(),
        appendAlert(state.activeAlerts(), alert.code())
    );
}
```

One fewer indentation level. The main path is at the top level of the method.

## The one-indent rule

Aim for a maximum of two indentation levels in any method. If you hit three, extract. Class body is level zero. Method body is level one. A switch or if inside the method is level two. If you need level three, you need a new method.

## Consequences

Code that hugs the left margin is code that reads top-to-bottom. Reviewers scan it in one pass. New team members follow the logic without mentally tracking brace-matching. And because each extracted method has a name, the code documents itself -- `checkQuantity` tells you what it does without reading its body.
