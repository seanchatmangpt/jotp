# Pattern 5: Domain Types Over Primitives

## Context

FleetPulse passes vehicle identifiers, GPS coordinates, temperature readings, and fuel percentages between processes. These values flow through `tell()` and `ask()` calls, get stored in state records, and appear in message records. The system handles thousands of messages per second, and a misplaced value means a truck gets sent to the wrong coordinates or a maintenance alert fires for the wrong vehicle.

## Problem

Primitive obsession makes illegal states representable. When a vehicle ID is a `String`, a latitude is a `double`, and a temperature is a `double`, nothing stops you from passing a latitude where a temperature is expected. The compiler is happy. The types match. The bug ships.

Consider this handler signature:

```java
BiFunction<String, Double, String> handler;
```

What is the `String`? A vehicle ID? A driver name? A route code? What is the `Double`? Fuel percent? Temperature? Latitude? The signature communicates nothing. Every call site is a potential mix-up, and the compiler cannot help.

## Forces

Primitives are easy. `String` and `double` require no class definitions, no imports, no boilerplate. Before records, wrapping a primitive meant writing a class with a constructor, getter, `equals`, `hashCode`, and `toString`. The ceremony was not worth it for a vehicle ID.

Records changed this equation.

## Therefore

**Define single-field records for domain concepts. Use them everywhere a primitive would go.**

```java
record VehicleId(String value) {
    VehicleId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("VehicleId must not be blank");
        }
    }
}

record Position(double lat, double lng, Instant timestamp) {
    Position {
        if (lat < -90 || lat > 90) throw new IllegalArgumentException("Invalid latitude: " + lat);
        if (lng < -180 || lng > 180) throw new IllegalArgumentException("Invalid longitude: " + lng);
    }
}

record Temperature(double celsius) {
    boolean isCritical() { return celsius > 110.0; }
    double fahrenheit() { return celsius * 9.0 / 5.0 + 32.0; }
}

record FuelPercent(double value) {
    FuelPercent {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Fuel percent out of range: " + value);
    }
    boolean isLow() { return value < 15.0; }
}
```

Three lines per type. No boilerplate. Records give you `equals`, `hashCode`, `toString`, and destructuring for free.

**Validation lives in the compact constructor.** A `VehicleId` cannot be blank. A `Position` cannot have a latitude of 200. A `FuelPercent` cannot be negative. These constraints are checked once, at construction, and then the value is guaranteed valid for its entire lifetime. No defensive checks downstream.

**Composition builds richer types:**

```java
record VehicleState(
        VehicleId id,
        Position position,
        FuelPercent fuel,
        Temperature engineTemp,
        int engineRpm,
        VehicleState.Status status,
        Route currentRoute
) {
    enum Status { IDLE, EN_ROUTE, RETURNING, STOPPED, MAINTENANCE }
}
```

Now compare the message definition with primitives versus domain types:

```java
// Before: what do these doubles mean?
record GpsUpdate(String vehicleId, double lat, double lng, long timestamp) {}

// After: self-documenting, compiler-enforced
record GpsUpdate(VehicleId id, Position position) implements TelemetryMsg {}
```

The second version is impossible to misuse. You cannot pass a `Temperature` where a `Position` is expected. You cannot pass a raw `String` where a `VehicleId` is required. The compiler enforces the domain vocabulary.

**Domain types compose with pattern matching:**

```java
case GpsUpdate(var id, Position(var lat, var lng, var ts)) ->
    state.withPosition(new Position(lat, lng, ts));
```

Nested record patterns let you destructure through multiple layers in a single `case`. The types guide you; the compiler checks you.

## Resulting Context

Your code reads like the domain it models. Method signatures document themselves. Bugs that come from swapping two `double` arguments or two `String` parameters become compile errors. Validation happens once at construction, not scattered across every method that touches the value.

The trade-off is a small proliferation of types. A system might have twenty or thirty single-field records. This feels like a lot until you compare it to the alternative: twenty or thirty primitive values with comments explaining what they mean, and no compiler enforcement. Types are cheaper than bugs.

In a JOTP system specifically, domain types pay dividends in the mailbox. When a `Proc` receives a `GpsUpdate(VehicleId, Position)`, there is zero ambiguity about what arrived. The message is self-describing, immutable, validated, and type-safe. That is the foundation you need before adding concurrency.

## Related Patterns

- **Immutable Messages** uses domain types as fields inside message records.
- **State as Value** composes domain types into the process state record.
- **Sealed Message Protocols** defines which messages carry which domain types.
- **Result Railway** can carry domain errors: `Result<Position, GeocodeError>` is clearer than `Result<double[], String>`.

## Try It

Define domain types for a driver management subsystem: `DriverId(String)`, `HoursOnDuty(double)` with a compact constructor that rejects negative values, and `DutyStatus` as an enum (ON_DUTY, OFF_DUTY, ON_BREAK). Compose them into a `DriverState` record. Write a `ShiftStart` message that takes a `DriverId` and `VehicleId`. Try calling `new HoursOnDuty(-5)` and verify the compact constructor rejects it. Then deliberately swap a `DriverId` and `VehicleId` in a method call and confirm the compiler catches it.
