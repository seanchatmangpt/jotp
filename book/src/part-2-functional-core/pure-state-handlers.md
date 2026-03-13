# Pattern 6: Pure State Handlers

## Context

You need logic that updates process state in response to messages. A fleet management system receives telemetry from thousands of vehicles -- GPS pings, fuel readings, engine alerts. Each message must update the vehicle's state.

## Problem

When you mix I/O with state transitions, you get code that is impossible to test without standing up half the world. A Spring `@Service` that reads from a database, writes to a cache, and updates an in-memory model all in one method cannot be tested without mocks. And mocks lie.

## Therefore

Write your handler as a `BiFunction<S, M, S>` with no side effects. State in, message in, new state out. Nothing else.

```java
sealed interface TelemetryMsg permits GpsUpdate, FuelReading, EngineAlert {}
record GpsUpdate(double lat, double lng, long timestamp) implements TelemetryMsg {}
record FuelReading(double level) implements TelemetryMsg {}
record EngineAlert(String code, String severity) implements TelemetryMsg {}

record VehicleState(
    double lat, double lng, long lastSeen,
    double fuelLevel, List<String> activeAlerts
) {}

BiFunction<VehicleState, TelemetryMsg, VehicleState> handler = (state, msg) ->
    switch (msg) {
        case GpsUpdate g -> new VehicleState(
            g.lat(), g.lng(), g.timestamp(),
            state.fuelLevel(), state.activeAlerts()
        );
        case FuelReading f -> new VehicleState(
            state.lat(), state.lng(), state.lastSeen(),
            f.level(), state.activeAlerts()
        );
        case EngineAlert a -> new VehicleState(
            state.lat(), state.lng(), state.lastSeen(),
            state.fuelLevel(),
            appendAlert(state.activeAlerts(), a.code())
        );
    };
```

The switch expression is exhaustive because `TelemetryMsg` is sealed. The compiler tells you when you forget a case. Each branch constructs a new immutable record -- no mutation, no shared state.

## Testability

Testing this handler requires zero infrastructure:

```java
var initial = new VehicleState(0, 0, 0L, 1.0, List.of());
var updated = handler.apply(initial, new GpsUpdate(40.7128, -74.0060, 1000L));

assertThat(updated.lat()).isEqualTo(40.7128);
assertThat(updated.lng()).isEqualTo(-74.0060);
assertThat(updated.fuelLevel()).isEqualTo(1.0); // unchanged
```

No `@SpringBootTest`. No `@MockBean`. No application context. You call a function and check the output. The test runs in under a millisecond.

## Contrast with the Spring approach

In a typical Spring service, the equivalent logic is entangled with JPA repositories, event publishers, and cache managers. You cannot test the GPS update rule without either mocking the repository or starting an embedded database. The rule itself -- "copy the new coordinates into state" -- is three lines of logic buried under thirty lines of framework ceremony.

With a pure handler, the rule is the code. The `Proc` that wraps this handler in a virtual thread and connects it to a mailbox is defined elsewhere. The handler does not know it runs inside a process. It does not need to.

## Consequences

Your business logic becomes a library of pure functions. You can compose them, reuse them, and test them independently. The imperative shell -- `new Proc<>(initialState, handler)` -- connects these functions to the outside world. But the logic itself stays clean.
