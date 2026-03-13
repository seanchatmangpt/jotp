# Pattern 24: State Machines for Complex Protocols

## The Problem

Some processes are not just "receive message, update state." They have phases. A vehicle tracker behaves differently when idle versus when actively tracking versus when undergoing maintenance. Stuffing all that into a single message handler means every handler branch starts with "but wait, what mode am I in?" -- and the compiler cannot help you when you forget a case.

## The Solution

`StateMachine<S, E, D>` separates three concerns that a plain `Proc` conflates: the **state** (which mode the machine is in), the **event** (what just happened), and the **data** (context carried across all states). The transition function is pure: `(S state, E event, D data) -> Transition<S, D>`.

Create one with `StateMachine.create`:

```java
var sm = StateMachine.create(initialState, initialData, transitionFn);
```

Send events with `send` (fire-and-forget) or `call` (returns a `CompletableFuture<D>` that completes after the transition). Query with `state()`, `data()`, and `isRunning()`. Shut down with `stop()`.

The `Transition` sealed interface has three variants, each a static factory:

- `Transition.nextState(newState, newData)` -- move to a different state with updated data
- `Transition.keepState(newData)` -- stay in the current state, update data only
- `Transition.stop(reason)` -- terminate the machine

Because `Transition` is sealed and its variants are records, the compiler enforces exhaustive handling in switch expressions. No forgotten cases.

## Vehicle Lifecycle Example

Define states and events as sealed interfaces of records:

```java
sealed interface VehicleState permits Idle, Tracking, Maintenance, Decommissioned {}
record Idle() implements VehicleState {}
record Tracking() implements VehicleState {}
record Maintenance() implements VehicleState {}
record Decommissioned() implements VehicleState {}

sealed interface VehicleEvent permits StartTracking, PauseTracking,
        BeginMaintenance, CompleteMaintenance, Decommission {}
record StartTracking() implements VehicleEvent {}
record PauseTracking() implements VehicleEvent {}
record BeginMaintenance() implements VehicleEvent {}
record CompleteMaintenance() implements VehicleEvent {}
record Decommission() implements VehicleEvent {}

record VehicleData(String vehicleId, int tripCount) {
    VehicleData withTripCount(int n) { return new VehicleData(vehicleId, n); }
}
```

Now the transition function. Each state handles only the events that make sense for it:

```java
var vehicle = StateMachine.create(
    new Idle(),
    new VehicleData("V-1001", 0),
    (state, event, data) -> switch (state) {
        case Idle _ -> switch (event) {
            case StartTracking _ -> Transition.nextState(
                new Tracking(), data.withTripCount(data.tripCount() + 1));
            case Decommission _ -> Transition.stop("decommissioned");
            default -> Transition.keepState(data);
        };
        case Tracking _ -> switch (event) {
            case PauseTracking _ -> Transition.nextState(new Idle(), data);
            case BeginMaintenance _ -> Transition.nextState(new Maintenance(), data);
            default -> Transition.keepState(data);
        };
        case Maintenance _ -> switch (event) {
            case CompleteMaintenance _ -> Transition.nextState(new Idle(), data);
            default -> Transition.keepState(data);
        };
        case Decommissioned _ -> Transition.keepState(data);
    }
);
```

Drive it:

```java
vehicle.send(new StartTracking());
vehicle.send(new PauseTracking());
vehicle.send(new StartTracking());

VehicleData result = vehicle.call(new BeginMaintenance()).join();
// result.tripCount() == 2
// vehicle.state() instanceof Maintenance
```

## Why This Matters

The transition function is pure. No side effects, no I/O. You can unit-test every state/event combination with plain assertions -- no process infrastructure needed. The sealed interfaces mean the compiler catches missing transitions at build time. And each `StateMachine` runs on its own virtual thread with its own mailbox, so ten thousand vehicles each running their own state machine costs about 10 MB of memory.

The key insight: protocols have phases. Model them explicitly and the compiler becomes your co-pilot.

## What to Watch For

`call` returns a `CompletableFuture<D>`. If the machine stops during a `call`, the future completes exceptionally with `IllegalStateException`. Always handle that case. And remember that `stop()` interrupts the machine's virtual thread and joins it -- call it from outside the machine, not from within the transition function.
