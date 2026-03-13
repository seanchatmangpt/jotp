# Pattern 17: Let It Crash

## Context

You have a vehicle telemetry process in FleetPulse. Sometimes it receives corrupt GPS data -- a malformed protobuf, a negative latitude, a timestamp from the year 2087. You could validate every field, catch every possible parse error, and write defensive code for edge cases you have not yet imagined. Or you could do what Erlang taught the world 40 years ago.

## Problem

Defensive error handling accumulates. Every try/catch adds a branch that might itself contain bugs. The catch block logs, retries, sets a flag, maybe swallows the exception. Over time, the process limps along in a half-corrupted state -- the GPS coordinate is stale, the speed is NaN, the heading is null. The process is alive but useless. Worse, it is alive and *wrong*.

## Solution

Let the process crash. When a vehicle telemetry handler encounters data it cannot make sense of, let the exception propagate. The supervisor will catch the crash, discard the corrupted state, and restart the process with a fresh initial state. The next message the process receives will be handled by clean code with clean data.

```java
// The handler does NOT catch unknown exceptions.
// If telemetry is corrupt, the process crashes.
BiFunction<VehicleState, TelemetryMsg, VehicleState> handler = (state, msg) ->
    switch (msg) {
        case GpsUpdate(var lat, var lon, var ts) -> {
            // No try/catch. If lat is NaN or ts is negative,
            // the downstream math throws. Process crashes.
            // Supervisor restarts with fresh VehicleState.
            var position = new GeoPoint(lat, lon);
            var speed = state.position().distanceTo(position)
                / Duration.between(state.lastUpdate(), ts).toSeconds();
            yield state.withPosition(position)
                       .withSpeed(speed)
                       .withLastUpdate(ts);
        }
        case Heartbeat() -> state;
    };
```

When the supervisor restarts this process, the new instance gets a default `VehicleState` -- zeroed speed, no stale coordinates, clean slate. The next valid GPS update brings it back to life with correct data.

Contrast this with the try/catch-everything approach:

```java
// The "safe" way that is actually dangerous
BiFunction<VehicleState, TelemetryMsg, VehicleState> defensiveHandler = (state, msg) ->
    switch (msg) {
        case GpsUpdate(var lat, var lon, var ts) -> {
            try {
                var position = new GeoPoint(lat, lon);
                var speed = state.position().distanceTo(position)
                    / Duration.between(state.lastUpdate(), ts).toSeconds();
                yield state.withPosition(position).withSpeed(speed);
            } catch (Exception e) {
                logger.warn("Bad telemetry, keeping old state", e);
                yield state; // State is now stale. How stale? Nobody knows.
            }
        }
        case Heartbeat() -> state;
    };
```

That `yield state` looks safe. It is the opposite. The process now reports the vehicle at its last known position with its last known speed. If the corrupt data keeps coming, the position drifts further from reality with every swallowed exception. The dashboard shows a vehicle parked at a gas station while it is actually driving across the state.

## Forces

- Fresh state on restart restores known-good invariants. You defined what "good" looks like when you wrote the initial state. The supervisor enforces it on every restart.
- Crashes are observable. A supervisor counts restarts. Swallowed exceptions are invisible.
- Handler code stays simple. No defensive branches means fewer bugs in the error path.
- Not every exception should crash the process. Expected, recoverable conditions (like a cache miss) should be handled normally. The let-it-crash philosophy applies to *unexpected* failures -- the ones you did not and could not anticipate.

## Therefore

Do not catch exceptions you do not understand. Let the process crash. The supervisor will restart it with fresh state, and the next message will be handled correctly. Your error handling strategy is your supervision tree, not your catch blocks.
