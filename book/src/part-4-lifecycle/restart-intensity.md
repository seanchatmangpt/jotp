# Pattern 19: Restart Intensity as Circuit Breaker

## Context

Your FleetPulse region supervisor watches 30 vehicle processes. One of them, truck-88, has a hardware fault that sends corrupt telemetry every 200 milliseconds. The supervisor dutifully restarts it each time. The process crashes again immediately. Restart, crash, restart, crash -- a tight loop that burns CPU and fills logs.

## Problem

Unlimited restarts turn a single faulty process into a system-wide problem. The supervisor spends all its time restarting the same child. Log storage fills up. Other children in the same supervisor get slower because the event loop is busy handling crashes. You need the supervisor to recognize when restarting is futile and give up.

## Solution

Every JOTP `Supervisor` is created with two parameters that control restart intensity: `maxRestarts` and `window`. If a child crashes more than `maxRestarts` times within `window`, the supervisor stops trying. It terminates all its children and shuts itself down. If this supervisor is itself a child of a parent supervisor, that parent sees the crash and applies its own restart strategy.

This is a circuit breaker built into the supervision hierarchy.

```java
// Region supervisor: allow at most 5 restarts per minute.
var regionWest = Supervisor.create(
    "region-west",
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                          // maxRestarts
    Duration.ofMinutes(1)       // window
);

regionWest.supervise("truck-88", VehicleState.initial("T-88"), vehicleHandler);
```

Here is what happens when truck-88 starts crash-looping:

```
t=0s   truck-88 crashes (restart 1/5 in window)  -> supervisor restarts
t=0.2s truck-88 crashes (restart 2/5 in window)  -> supervisor restarts
t=0.4s truck-88 crashes (restart 3/5 in window)  -> supervisor restarts
t=0.6s truck-88 crashes (restart 4/5 in window)  -> supervisor restarts
t=0.8s truck-88 crashes (restart 5/5 in window)  -> supervisor restarts
t=1.0s truck-88 crashes (restart 6/5 -- EXCEEDED)
       -> region-west supervisor terminates ALL children
       -> region-west supervisor itself crashes
       -> fleet-root sees region-west crash
       -> fleet-root applies ONE_FOR_ONE: restarts region-west
```

The escalation chain continues upward. If the restarted `region-west` immediately hits the same problem and exceeds its limit again, `fleet-root` counts that as another restart. If `fleet-root` itself exceeds its limit (say, 3 restarts per minute), the entire application goes down. At that point, the problem is systemic and needs human intervention.

```
fleet-root (3 restarts/min)
 └── region-west (5 restarts/min)
      └── truck-88 (crash-looping)

Escalation:
  truck-88 crashes 6 times in 1 min
    -> region-west gives up, crashes
       -> fleet-root restarts region-west (1/3)
  truck-88 crashes 6 times in 1 min again
    -> region-west gives up, crashes
       -> fleet-root restarts region-west (2/3)
  truck-88 crashes 6 times in 1 min again
    -> region-west gives up, crashes
       -> fleet-root restarts region-west (3/3)
  truck-88 crashes again
    -> region-west gives up
       -> fleet-root EXCEEDED (4/3) -- fleet-root terminates
```

You can check whether a supervisor gave up by calling `fatalError()`:

```java
if (!regionWest.isRunning() && regionWest.fatalError() != null) {
    logger.error("Region supervisor exceeded restart limit",
                 regionWest.fatalError());
}
```

## Forces

- Lower supervisors should have higher restart limits than their parents. A vehicle process might crash 5 times before the region gives up. The region might crash 3 times before the root gives up. This creates a funnel: local problems are handled locally; only persistent problems escalate.
- The time window matters as much as the count. Five crashes spread over an hour is normal churn. Five crashes in one second is a crisis. Set the window to match your operational expectations.
- When a supervisor crashes, it takes all its children with it. This is intentional. If the supervisor cannot maintain its invariants, its children's state is suspect too.

## Therefore

Set `maxRestarts` and `window` on every supervisor to match the failure rate you consider acceptable. When the rate is exceeded, the supervisor crashes and escalates to its parent. This automatic circuit breaker prevents crash loops from consuming your system. Tune the numbers at each level of the tree: generous at the leaves, strict at the root.
