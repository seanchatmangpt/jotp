# Pattern 18: Supervision Trees

## Context

You have dozens of vehicle telemetry processes in FleetPulse, grouped by geographic region. When a single vehicle process crashes, you want only that process restarted. But when an entire region's data feed goes bad, you want every process in that region recycled together. And if the region supervisor itself cannot cope, the root supervisor needs to know.

## Problem

Flat process management does not scale. If one supervisor watches 500 vehicle processes and 3 region coordinators, it cannot express different restart policies for different groups. You need hierarchy -- different restart strategies at different levels of the tree.

## Solution

JOTP's `Supervisor` organizes processes into trees. Each supervisor is created with a `Strategy`, a maximum restart count, and a time window. You then register child processes with `supervise()`, which returns a `ProcRef` -- a stable handle that survives restarts.

The three strategies determine what happens when a child crashes:

- **`Strategy.ONE_FOR_ONE`** -- only the crashed child is restarted. Other children are unaffected. Use this when children are independent.
- **`Strategy.ONE_FOR_ALL`** -- all children are restarted when any one crashes. Use this when children share state and must be consistent with each other.
- **`Strategy.REST_FOR_ONE`** -- the crashed child and all children started after it are restarted. Use this when children have startup-order dependencies.

Here is the FleetPulse supervision tree:

```java
// Root supervisor: if any region supervisor exceeds its restart limit,
// only that region is affected (ONE_FOR_ONE at the root).
var root = Supervisor.create(
    "fleet-root",
    Supervisor.Strategy.ONE_FOR_ONE,
    3,
    Duration.ofMinutes(1)
);

// Region supervisor: if any vehicle crashes, restart only that vehicle.
var westCoast = Supervisor.create(
    "region-west",
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofMinutes(1)
);

// Register vehicles under the region supervisor.
// supervise() returns a ProcRef -- a stable handle that transparently
// redirects to the restarted process after a crash.
ProcRef<VehicleState, TelemetryMsg> truck42 = westCoast.supervise(
    "truck-42",
    VehicleState.initial("T-42"),
    vehicleHandler
);

ProcRef<VehicleState, TelemetryMsg> truck43 = westCoast.supervise(
    "truck-43",
    VehicleState.initial("T-43"),
    vehicleHandler
);
```

The `ProcRef` returned by `supervise()` is the key to transparency. When `truck-42` crashes and the supervisor restarts it, the `ProcRef` swaps its internal reference to the new process. Any code holding that `ProcRef` -- a dashboard, an alert system, a route optimizer -- keeps sending messages without knowing a crash happened. The crash is invisible to the outside world.

The tree structure for FleetPulse looks like this:

```
fleet-root (ONE_FOR_ONE, 3 restarts/min)
 ├── region-west (ONE_FOR_ONE, 5 restarts/min)
 │    ├── truck-42
 │    ├── truck-43
 │    └── truck-44
 ├── region-east (ONE_FOR_ONE, 5 restarts/min)
 │    ├── truck-71
 │    └── truck-72
 └── analytics (ONE_FOR_ALL, 2 restarts/min)
      ├── aggregator
      └── reporter
```

The analytics group uses `ONE_FOR_ALL` because the aggregator and reporter share state -- if the aggregator crashes, the reporter's data is stale, so both must restart together.

## Forces

- `ONE_FOR_ONE` is the most common strategy. Default to it unless children are coupled.
- `ONE_FOR_ALL` is for atomic groups. If children must be consistent, restart them together.
- `REST_FOR_ONE` is for dependency chains. If process C depends on B which depends on A, and B crashes, C must also restart (but A is fine).
- `ProcRef` is always what you pass around. Never hold a raw `Proc` if a supervisor manages it -- the raw reference becomes stale after a restart.

## Therefore

Organize processes into supervision trees. Use `Supervisor.create(strategy, maxRestarts, window)` at each level. Register children with `supervise(id, initialState, handler)` and hold the returned `ProcRef`. The tree structure encodes your failure recovery policy as topology -- each level decides independently how to handle crashes in its children.
