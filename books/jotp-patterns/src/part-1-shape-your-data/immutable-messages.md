# Pattern 1: Immutable Messages

## Context

You are building a concurrent system where multiple virtual threads communicate by passing messages. In FleetPulse, thousands of vehicles send telemetry updates -- GPS coordinates, engine readings, fuel levels -- and dispatchers issue commands that those vehicles must act on.

## Problem

Shared mutable state causes race conditions. If a sender creates a message object, passes it to a process, and then modifies it, the receiver sees corrupted data. This is the single most common source of concurrency bugs in Java, and no amount of `synchronized` blocks fully eliminates it when objects are shared across thread boundaries.

## Forces

Java defaults to mutable objects. Classes have setters. Lists are mutable. Even "data transfer objects" tend to accumulate mutation methods over time. Developers must actively choose immutability, and the language historically made that choice verbose and tedious.

## Therefore

**Use records as messages.** Records are immutable by construction -- their fields are final, their constructors are canonical, and they provide structural equality and destructuring out of the box.

Here is FleetPulse's telemetry protocol:

```java
sealed interface TelemetryMsg permits GpsUpdate, EngineStatus, FuelLevel {
    record GpsUpdate(VehicleId id, double lat, double lng, Instant timestamp)
            implements TelemetryMsg {}
    record EngineStatus(VehicleId id, int rpm, double tempCelsius)
            implements TelemetryMsg {}
    record FuelLevel(VehicleId id, double percent)
            implements TelemetryMsg {}
}
```

This buys you three things immediately:

**No defensive copies.** When a `GpsUpdate` arrives in a process's mailbox via `tell()`, the sender cannot mutate it afterward. The receiver sees exactly what was sent. There is no need to clone, copy, or freeze anything.

**Safe across virtual threads.** JOTP's `Proc<S, M>` delivers messages through a `LinkedTransferQueue`. Because records are immutable, they can traverse that queue without synchronization beyond what the queue itself provides. You get thread safety for free.

**Natural destructuring.** Java 26 pattern matching lets you pull records apart in switch expressions:

```java
BiFunction<VehicleState, TelemetryMsg, VehicleState> handler = (state, msg) ->
    switch (msg) {
        case GpsUpdate(var id, var lat, var lng, var ts) ->
            state.withPosition(new Position(lat, lng, ts));
        case EngineStatus(var id, var rpm, var temp) ->
            state.withEngine(rpm, temp);
        case FuelLevel(var id, var pct) ->
            state.withFuel(pct);
    };
```

No casting. No `instanceof` chains. The compiler verifies you handled every variant.

## Resulting Context

Messages are values. You can log them, serialize them, compare them with `equals()`, store them in collections, and replay them for testing -- all without worrying about mutation. When a process crashes and the supervisor restarts it, the messages in the queue are still intact because nobody can change them.

The trade-off is that you cannot update a message in place. If you need a modified version, you create a new record. For messages, this is not a cost -- it is the whole point. Messages represent something that happened. Events do not change.

## Related Patterns

- **Sealed Message Protocols** closes the type hierarchy so the compiler enforces exhaustiveness.
- **State as Value** applies the same immutability principle to process state.
- **Domain Types Over Primitives** ensures the fields inside your records are meaningful types, not raw primitives.

## Try It

Define a `DispatchCommand` sealed interface with three record variants: `AssignRoute(VehicleId id, Route route)`, `RecallVehicle(VehicleId id, String reason)`, and `EmergencyStop(VehicleId id)`. Write a switch expression that handles all three. Then add a fourth variant and watch the compiler tell you exactly where to update your code.
