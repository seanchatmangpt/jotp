# Pattern 2: Sealed Message Protocols

## Context

A JOTP process handles messages through a `BiFunction<S, M, S>` handler. The handler receives a message, pattern-matches on it, and returns the next state. In FleetPulse, a vehicle process must respond to telemetry updates, dispatch commands, and health check pings -- but nothing else.

## Problem

Open type hierarchies allow unexpected messages. If your message type is a plain interface, any class in the codebase can implement it. A logging module could accidentally send a `LogEntry` that implements `VehicleMsg`, and the process would receive a message it was never designed to handle. The compiler cannot help you because the set of possible implementations is unbounded.

Worse, when you add a new message variant to an open hierarchy, existing handlers silently ignore it. There is no compile-time signal that says "you forgot to handle this case." The bug shows up at runtime, usually in production, usually at 2 AM.

## Forces

Java's traditional interface model is open by design. Any class can implement any interface. This is powerful for extensibility but dangerous for protocols, where the set of valid messages must be known and fixed. You need a closed world, not an open one.

## Therefore

**Use sealed interfaces to define closed message protocols.** The `sealed` keyword restricts which classes can implement an interface, and the compiler uses that restriction to enforce exhaustive pattern matching.

Here is FleetPulse's command protocol:

```java
sealed interface VehicleCommand
        permits AssignRoute, RecallVehicle, EmergencyStop, RequestStatus {
    record AssignRoute(VehicleId id, Route route) implements VehicleCommand {}
    record RecallVehicle(VehicleId id, String reason) implements VehicleCommand {}
    record EmergencyStop(VehicleId id) implements VehicleCommand {}
    record RequestStatus(VehicleId id) implements VehicleCommand {}
}
```

Now the handler uses an exhaustive switch:

```java
BiFunction<VehicleState, VehicleCommand, VehicleState> handler = (state, cmd) ->
    switch (cmd) {
        case AssignRoute(var id, var route) ->
            state.withRoute(route).withStatus(Status.EN_ROUTE);
        case RecallVehicle(var id, var reason) ->
            state.withStatus(Status.RETURNING).withRecallReason(reason);
        case EmergencyStop(var id) ->
            state.withStatus(Status.STOPPED);
        case RequestStatus(var id) ->
            state;  // State unchanged; ask() returns current state to caller
    };
```

**The compiler is your ally.** Suppose next quarter the product team adds a `Reroute` command. You add it to the sealed interface:

```java
sealed interface VehicleCommand
        permits AssignRoute, RecallVehicle, EmergencyStop, RequestStatus, Reroute {
    // ... existing records ...
    record Reroute(VehicleId id, Route newRoute, String reason) implements VehicleCommand {}
}
```

Every switch expression that matches on `VehicleCommand` now fails to compile with an error like:

```
error: the switch expression does not cover all possible input values
    switch (cmd) {
    ^
```

You cannot forget. You cannot defer. The code does not compile until every handler accounts for `Reroute`. This is the difference between "we have a convention to check all cases" and "the compiler checks all cases."

## Resulting Context

Your message protocols are closed, documented, and compiler-verified. When a `Proc<VehicleState, VehicleCommand>` receives a message, you know at compile time that the handler covers every possibility. No default branch hiding unhandled cases. No `ClassCastException` at runtime.

The trade-off is rigidity. You cannot add a message variant from outside the package without modifying the sealed interface. For protocols between processes inside a system, this is exactly what you want. For extension points exposed to third-party code, prefer a different mechanism (like an `EventManager<E>` for open-ended subscriptions).

## Related Patterns

- **Immutable Messages** ensures each variant in the protocol is a record -- immutable and safe to pass between threads.
- **State as Value** pairs with sealed protocols: the handler's switch returns new state values, making transitions explicit.
- **Result Railway** uses the same sealed-interface technique for success/failure outcomes.

## Try It

Define a `MaintenanceEvent` sealed interface with variants `OilChangeRequired(VehicleId id, int mileage)`, `TireRotationDue(VehicleId id, Instant dueDate)`, and `BrakeInspection(VehicleId id, double padThickness)`. Write a handler that returns a priority level (1-3) for each event. Then add `EngineWarning` and verify the compiler catches the missing case.
