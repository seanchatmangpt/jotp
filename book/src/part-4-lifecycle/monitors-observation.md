# Pattern 22: Monitors for Observation

## Context

Your FleetPulse dashboard process displays the status of 30 vehicles. When a vehicle process crashes, the dashboard should update that vehicle's status to "offline" and show a warning icon. But the dashboard itself must not crash -- it is serving a browser session, and killing it because a vehicle died would be a terrible user experience.

## Problem

Links (Pattern 21) are bilateral: if the monitored process crashes, the monitoring process crashes too. That is wrong here. The dashboard does not share fate with any single vehicle. It needs to *observe* crashes without being dragged down. You need one-way crash notification.

## Solution

`ProcMonitor.monitor()` installs a unidirectional DOWN callback. When the target process terminates -- for any reason, normal or abnormal -- the callback fires with the exit reason. The monitoring process stays alive.

```java
// Dashboard process monitors each vehicle.
// When a vehicle crashes, the dashboard updates its display.
for (var entry : vehicleRefs.entrySet()) {
    String vehicleId = entry.getKey();
    Proc<VehicleState, TelemetryMsg> vehicle = entry.getValue();

    ProcMonitor.MonitorRef<VehicleState, TelemetryMsg> monRef =
        ProcMonitor.monitor(vehicle, reason -> {
            if (reason == null) {
                // Normal shutdown -- vehicle gracefully stopped.
                dashboard.tell(new VehicleOffline(vehicleId, "shutdown"));
            } else {
                // Crash -- vehicle died from an exception.
                dashboard.tell(new VehicleOffline(vehicleId,
                    "crashed: " + reason.getMessage()));
            }
        });
}
```

The `monitor()` call returns a `MonitorRef` -- an opaque handle you keep if you later want to stop monitoring. The callback receives a `Throwable`: `null` means normal exit (the process called `stop()`), non-null means the process crashed with that exception.

The callback runs on the target's virtual thread, so it must not block. The pattern is to enqueue a message into the monitoring process's mailbox, as shown above with `dashboard.tell()`.

To cancel a monitor before the target terminates, call `demonitor()`:

```java
// Stop watching truck-42 (maybe it was decommissioned).
ProcMonitor.demonitor(monRef);
```

After `demonitor()`, the callback will not fire even if the target subsequently crashes. It is safe to call `demonitor()` after the target has already terminated -- it is a no-op.

Here is how monitors fit into the FleetPulse architecture:

```
fleet-root (ONE_FOR_ONE)
 ├── region-west (ONE_FOR_ONE)
 │    ├── truck-42  ─── monitored by ──→  dashboard
 │    ├── truck-43  ─── monitored by ──→  dashboard
 │    └── truck-44  ─── monitored by ──→  dashboard
 └── dashboard (survives any vehicle crash)
```

The arrows are one-way. A truck crash sends a DOWN notification to the dashboard. The dashboard crash does *not* affect any truck -- the monitor is unidirectional.

Compare with links:

| Aspect | Link (`ProcLink`) | Monitor (`ProcMonitor`) |
|--------|-------------------|------------------------|
| Direction | Bilateral | Unilateral |
| Observer crashes? | Yes | No |
| Normal stop propagates? | No | Yes (with null reason) |
| Use case | Shared fate | Observation |

Monitors are also composable with links and supervision. A process can be simultaneously supervised (the supervisor restarts it), linked to a peer (shared fate with a partner), and monitored by an observer (dashboard gets notified). All three mechanisms use separate callback lists and operate independently.

## Forces

- The DOWN callback runs on the target's thread. Keep it fast -- enqueue a message rather than doing real work in the callback.
- Monitors fire on both normal and abnormal termination. Check the `reason` parameter: `null` means clean shutdown, non-null means crash.
- Keep the `MonitorRef` if you need to cancel. If you never cancel, you can ignore it.
- A monitor does not prevent the target from being garbage collected after termination. The `MonitorRef` holds a reference to the target, but once the callback fires, the monitor is spent.

## Therefore

When a process needs to observe another process's lifecycle without sharing its fate, use `ProcMonitor.monitor(target, downHandler)`. The handler receives `null` for normal exit or the crash exception for abnormal termination. Use `ProcMonitor.demonitor(ref)` to cancel. Monitors are the safe alternative to links when the observer must survive the observed process's death.
