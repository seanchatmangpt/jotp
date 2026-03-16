# Pattern 20: Supervised Startup

## Context

Your FleetPulse vehicle process needs to load its last known position from a database and register itself with a route optimizer before it can handle telemetry messages. If the database is down during startup, the process should not silently enter the message loop with empty state -- it should fail explicitly, and the supervisor should know about it.

## Problem

When a supervisor spawns a child with `supervise()`, the child starts processing messages immediately. There is no distinction between "the child is initializing" and "the child is ready." If the child needs to perform setup work -- connecting to a database, loading configuration, registering with a service -- the supervisor has no way to know whether that setup succeeded before declaring the child alive.

This is a race condition. The supervisor thinks the child is running. The child is actually stuck on a failed database connection. Messages pile up in the mailbox. The first real message triggers a NullPointerException because initialization never finished.

## Solution

`ProcLib.startLink()` adds an initialization handshake. The caller blocks until the child explicitly signals readiness by calling `ProcLib.initAck()`. If the child crashes during init or never calls `initAck()` within the timeout, the caller gets an error result.

The return type is `StartResult<S, M>` -- a sealed interface with two cases:

- `StartResult.Ok(Proc<S, M> proc)` -- the child initialized successfully and is ready for messages.
- `StartResult.Err(Throwable reason)` -- the child crashed during init or timed out.

```java
var result = ProcLib.startLink(
    VehicleState.empty("T-42"),
    // Init handler: runs once before the message loop.
    state -> {
        // Load last known position from database.
        var lastPosition = positionRepo.findLatest("T-42");
        var initialized = state.withPosition(lastPosition);

        // Register with route optimizer.
        routeOptimizer.register("T-42");

        // Signal readiness. This unblocks the caller.
        ProcLib.initAck();

        return initialized;
    },
    // Message loop handler: only runs after init succeeds.
    (state, msg) -> switch (msg) {
        case GpsUpdate(var lat, var lon, var ts) ->
            state.withPosition(new GeoPoint(lat, lon));
        case Heartbeat() -> state;
    }
);

// Pattern match on the result.
switch (result) {
    case ProcLib.StartResult.Ok(var proc) -> {
        logger.info("truck-42 initialized, ready for telemetry");
        proc.tell(new Heartbeat());
    }
    case ProcLib.StartResult.Err(var reason) -> {
        logger.error("truck-42 failed to initialize", reason);
        // Escalate: maybe retry, maybe alert ops team.
    }
}
```

The default timeout is 5 seconds. If your init handler does slow I/O, you can pass an explicit timeout:

```java
var result = ProcLib.startLink(
    initialState,
    initHandler,
    loopHandler,
    Duration.ofSeconds(30)   // Wait up to 30 seconds for initAck()
);
```

The key rule: **the init handler must call `ProcLib.initAck()`**. If it returns without calling `initAck()`, the caller times out and gets an `Err`. If the init handler throws before calling `initAck()`, the exception is captured and returned as the `Err` reason.

This mirrors OTP's `proc_lib:start_link/3` and `proc_lib:init_ack/1` protocol exactly. The parent blocks, the child does its setup, the child says "I'm ready," and only then does the parent proceed.

## Forces

- Use `ProcLib.startLink()` when initialization has preconditions. Database connections, service registration, configuration loading -- anything that can fail.
- Use plain `supervise()` when the child can start handling messages immediately with its initial state. Not every process needs a handshake.
- The init handler runs inside the child's virtual thread. It can do blocking I/O without affecting the caller's thread (the caller blocks on the `CountDownLatch`, not on I/O).
- `initAck()` is safe to call outside a `startLink` context -- it is a no-op. You will not get a runtime error from calling it in the wrong place.

## Therefore

When a process must complete setup before accepting messages, use `ProcLib.startLink()` with an init handler that calls `ProcLib.initAck()`. Pattern match on `StartResult.Ok` or `StartResult.Err` to distinguish successful startup from initialization failure. This eliminates the race between "process exists" and "process is ready."
