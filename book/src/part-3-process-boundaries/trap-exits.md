# Pattern 16: Trap Exits

## Context

You have a fleet coordinator process linked to hundreds of vehicle processes. When a vehicle process crashes -- bad GPS data, sensor overflow, corrupted message -- the default behavior in OTP is for the crash to propagate. The linked coordinator dies too. That is fine when you want fail-together semantics. But the coordinator is not the one that broke. It should stay alive and decide what to do about the crash.

## Problem

How does a process observe the death of linked processes without dying itself?

## Solution

Call `trapExits(true)` on the process. Instead of crashing when a linked process dies, the exit signal arrives as an `ExitSignal` message in the mailbox:

```java
public void trapExits(boolean trap)
```

And the signal itself is a record:

```java
public record ExitSignal(Throwable reason) {}
```

The `reason` is the exception that killed the linked process, or `null` for a normal exit.

## Fleet Coordinator Trapping Exits

Here is the coordinator pattern. The coordinator manages vehicle processes and needs to know when they crash without crashing itself:

```java
var coordinator = new Proc<>(FleetState.empty(), (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> handleCrash(state, reason);
    case VehicleConnected v    -> addVehicle(state, v);
    case VehicleUpdate u       -> updateVehicle(state, u);
    default                    -> state;
});
coordinator.trapExits(true);
```

The key line is `coordinator.trapExits(true)`. Without it, when vehicle-42 throws an exception, the exit signal propagates through the link and interrupts the coordinator. With it, the signal becomes an `ExitSignal` message that the coordinator handles like any other message.

## Pattern Matching on ExitSignal

The `ExitSignal` record supports Java 26 deconstruction patterns. In the handler, you pattern-match to extract the reason:

```java
case ExitSignal(var reason) -> handleCrash(state, reason);
```

The `handleCrash` method can inspect the reason and decide what to do:

```java
FleetState handleCrash(FleetState state, Throwable reason) {
    if (reason instanceof SensorOverflowException) {
        return state.markVehicleDegraded(vehicleId);
    }
    return state.removeVehicle(vehicleId);
}
```

Some crashes are recoverable (sensor overflow -- mark degraded, wait for recovery). Others are not (unknown exception -- remove from fleet). The coordinator makes the decision, not the supervision tree.

## How It Works Internally

When a linked process dies, JOTP calls `deliverExitSignal(reason)` on the linked partner. The method checks whether exit trapping is enabled:

```java
public void deliverExitSignal(Throwable reason) {
    if (trappingExits) {
        mailbox.add(new Envelope<>((M) new ExitSignal(reason), null));
    } else {
        interruptAbnormally(reason);
    }
}
```

With trapping on, the exit signal is cast to the message type and placed in the mailbox as a regular message. The process handles it in order, after any messages that arrived before the crash. With trapping off, the process is interrupted immediately -- the default OTP behavior.

## Message Type Considerations

Notice that `ExitSignal` is cast to `M` -- the process's message type. For this to work with pattern matching, your message type must accommodate `ExitSignal`. The simplest approach is to use `Object` as the message type when trapping exits:

```java
var coordinator = new Proc<FleetState, Object>(FleetState.empty(), (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> handleCrash(state, reason);
    case VehicleConnected v    -> addVehicle(state, v);
    default                    -> state;
});
```

Or, if you prefer a sealed hierarchy, have your sealed message interface permit `ExitSignal` as shown in the ExitSignal javadoc.

## When to Trap Exits

Trap exits when a process needs to **observe** failures without **participating** in them. Coordinators, dispatchers, health monitors -- these are the processes that should trap exits. Worker processes generally should not. Let workers crash and let supervisors restart them.

The distinction maps to organizational structure. A manager gets notified when an employee has a problem. The manager does not also have the problem. That is what `trapExits(true)` gives you: notification without contagion.

## Therefore

Call `trapExits(true)` on coordinator processes that need to observe linked process crashes. Exit signals arrive as `ExitSignal(reason)` messages in the mailbox, handled through pattern matching alongside regular messages. The coordinator stays alive and decides the response.
