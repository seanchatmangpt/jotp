# Pattern 7: Compose by Purpose

## Context

Your pure state handler works well with three message types. Then requirements grow. The fleet management system now handles GPS updates, fuel readings, engine alerts, maintenance schedules, driver assignments, geofence events, and regulatory compliance checks. Your switch expression is forty lines long and climbing.

## Problem

A giant switch with inline logic for every message type is unreadable. You scroll past GPS coordinate math to find the geofence logic. A change to fuel alerts risks breaking driver assignment. The handler is pure, but it is also a wall of code that no one wants to review.

## Therefore

Delegate each case to a focused method. The handler's switch expression becomes a table of contents -- one line per message type, each pointing to a method that does exactly one thing.

```java
BiFunction<VehicleState, TelemetryMsg, VehicleState> handler = (state, msg) ->
    switch (msg) {
        case GpsUpdate g      -> Telemetry.applyGps(state, g);
        case FuelReading f    -> Telemetry.applyFuel(state, f);
        case EngineAlert a    -> Alerts.evaluate(state, a);
        case MaintenanceDue m -> Scheduling.applyMaintenance(state, m);
        case DriverAssign d   -> Drivers.assign(state, d);
        case GeofenceEvent e  -> Geofencing.check(state, e);
    };
```

Read that switch and you know exactly what this handler does. You do not need to understand *how* GPS coordinates are applied to understand the overall message flow. The switch is a dispatch table, not an implementation.

Each delegate is a static method with the same shape: state in, specific message in, state out.

```java
final class Telemetry {
    static VehicleState applyGps(VehicleState state, GpsUpdate g) {
        return new VehicleState(
            g.lat(), g.lng(), g.timestamp(),
            state.fuelLevel(), state.activeAlerts()
        );
    }

    static VehicleState applyFuel(VehicleState state, FuelReading f) {
        return new VehicleState(
            state.lat(), state.lng(), state.lastSeen(),
            f.level(), state.activeAlerts()
        );
    }
}
```

## Independent testability

Each delegate is independently testable. You do not need to construct every message type to test GPS handling. You call `Telemetry.applyGps(state, gpsUpdate)` and assert on the result. When the geofencing rules change, you modify and test `Geofencing.check` without touching telemetry code.

```java
@Test
void gpsUpdatePreservesFuelLevel() {
    var state = new VehicleState(0, 0, 0L, 0.75, List.of());
    var updated = Telemetry.applyGps(state, new GpsUpdate(51.5, -0.1, 500L));

    assertThat(updated.fuelLevel()).isEqualTo(0.75);
    assertThat(updated.lat()).isEqualTo(51.5);
}
```

## Naming matters

Name delegate classes by domain purpose, not by technical role. `Telemetry`, not `TelemetryHandler`. `Alerts`, not `AlertProcessor`. The class name tells you what area of the business it covers. The method name tells you what it does. `Alerts.evaluate` reads like English.

## When to split

A reasonable rule of thumb: if a case branch exceeds five lines, extract it. If the switch exceeds ten cases, group related cases into domain classes. The handler switch should fit on a single screen.

## Consequences

The handler remains a single `BiFunction<S, M, S>` -- the `Proc` does not know or care that it delegates internally. But developers working on the codebase can navigate by purpose. Need to change how engine alerts work? Open `Alerts.java`. Need to add a new message type? Add the sealed permit, add one line to the switch, write the delegate. The structure scales with your domain.
