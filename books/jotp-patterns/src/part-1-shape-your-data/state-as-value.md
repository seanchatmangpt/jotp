# Pattern 3: State as Value

## Context

A JOTP process maintains state that evolves over time. In FleetPulse, each vehicle process tracks position, fuel level, engine metrics, assigned route, and operational status. Every incoming message potentially changes this state. When a process crashes, the supervisor restarts it with a known initial state.

## Problem

Mutable state objects make crash recovery unreliable. If state is a mutable POJO with setters, a crash mid-update can leave it in an inconsistent state -- position updated but fuel level stale, route assigned but status still showing "idle." Worse, if anyone holds a reference to the state object, they see partial mutations. Debugging becomes archaeology.

There is a subtler problem too. With mutable state, you cannot answer the question "what was the state before this message?" The old state is gone, overwritten. You lose the ability to reason about transitions, to log them, to replay them.

## Forces

Object-oriented tradition says state lives in fields and mutates in place. This feels natural for a single-threaded program, but in a concurrent system where processes crash and restart, mutable state is a liability. You need state that is a snapshot -- a value you can hold, compare, log, and discard without side effects.

## Therefore

**Represent state as an immutable record. The handler `BiFunction<S, M, S>` takes the old state and a message, and returns a new state. The old state is never modified.**

Here is FleetPulse's vehicle state:

```java
record VehicleState(
        VehicleId id,
        Position position,
        double fuelPercent,
        int engineRpm,
        double engineTempCelsius,
        Status status,
        Route currentRoute
) {
    enum Status { IDLE, EN_ROUTE, RETURNING, STOPPED, MAINTENANCE }

    VehicleState withPosition(Position p) {
        return new VehicleState(id, p, fuelPercent, engineRpm, engineTempCelsius, status, currentRoute);
    }

    VehicleState withFuel(double pct) {
        return new VehicleState(id, position, pct, engineRpm, engineTempCelsius, status, currentRoute);
    }

    VehicleState withEngine(int rpm, double temp) {
        return new VehicleState(id, position, fuelPercent, rpm, temp, status, currentRoute);
    }

    VehicleState withStatus(Status s) {
        return new VehicleState(id, position, fuelPercent, engineRpm, engineTempCelsius, s, currentRoute);
    }

    VehicleState withRoute(Route r) {
        return new VehicleState(id, position, fuelPercent, engineRpm, engineTempCelsius, status, r);
    }
}
```

The "wither" methods are the key idiom. Each one returns a new `VehicleState` with one field changed. They compose naturally:

```java
VehicleState next = state
    .withPosition(new Position(lat, lng, now))
    .withFuel(85.2)
    .withStatus(Status.EN_ROUTE);
```

Now the handler becomes a pure function -- old state in, new state out:

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

**Why this matters for crash recovery.** When a process crashes, the supervisor creates a new `Proc` with the initial state. Because state is a value, the old process's state does not leak into the new one. There is no partially-mutated object lingering on the heap. The new process starts clean.

**Why this matters for `ask()`.** When you call `proc.ask(msg)`, JOTP returns the state after the message is processed. Because state is immutable, the caller gets a snapshot that will never change, even as the process continues handling subsequent messages.

## Resulting Context

State transitions are explicit and traceable. You can log the before-and-after of every message. You can write property-based tests that verify state transitions without mocking anything -- just call the handler function directly with different inputs. You can snapshot state for debugging.

The cost is allocation. Every state transition creates a new record. For FleetPulse, where a vehicle might process hundreds of messages per second, this means hundreds of short-lived objects. The JVM's generational garbage collector handles this efficiently -- young-generation objects that die quickly are collected nearly for free. This is not a bottleneck in practice.

## Related Patterns

- **Immutable Messages** applies the same principle to what goes in. State as Value applies it to what comes out.
- **Result Railway** handles the case where a state transition can fail.
- **Domain Types Over Primitives** ensures the fields inside your state record carry meaning.

## Try It

Write a `DriverState` record with fields for `DriverId`, `currentVehicle` (optional), `hoursOnDuty`, and `DutyStatus` (ON_DUTY, OFF_DUTY, ON_BREAK). Add wither methods. Then write a handler function that processes `ShiftStart`, `BreakStart`, `BreakEnd`, and `ShiftEnd` messages, returning new state each time. Verify that calling the handler never modifies the original state reference.
