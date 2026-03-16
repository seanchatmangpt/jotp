# Pattern 14: Stable References

## Context

Your processes are supervised. When one crashes, the supervisor restarts it with fresh state. But every caller holding a reference to the old process now has a stale pointer. Do you update every caller? Track every reference? Build a service locator?

## Problem

How do clients keep communicating with a process that gets restarted by its supervisor?

## Solution

Use `ProcRef`. It is a stable, opaque handle that wraps a `Proc` and survives restarts:

```java
public final class ProcRef<S, M> {

    public ProcRef(Proc<S, M> initial)

    public void tell(M msg)
    public CompletableFuture<S> ask(M msg)
    public void stop() throws InterruptedException
    public Proc<S, M> proc()
}
```

The constructor takes the initial `Proc`. After that, callers use `tell()` and `ask()` exactly like they would on a raw `Proc`. The difference is invisible until a restart happens.

## How Restart Transparency Works

Inside `ProcRef`, there is a `volatile Proc<S, M> delegate`. When the supervisor restarts a crashed child, it calls `swap()`:

```java
void swap(Proc<S, M> next)
```

This atomically replaces the delegate. Every subsequent `tell()` or `ask()` goes to the new process. Callers never know the restart happened. This is Armstrong's location transparency: a process identifier should be opaque. Callers must not know or care whether the process has restarted.

## Fleet Example

A fleet coordinator holds `ProcRef` handles to vehicle processes:

```java
Map<String, ProcRef<VehicleState, VehicleMsg>> fleet = new ConcurrentHashMap<>();

// Create a supervised vehicle
ProcRef<VehicleState, VehicleMsg> ref = supervisor.getRef("vehicle-42");

fleet.put("vehicle-42", ref);
```

Later, vehicle-42 crashes due to a malformed GPS message. The supervisor catches the crash, creates a new `Proc` with fresh initial state, and calls `ref.swap(newProc)`. The fleet coordinator's map still has the same `ProcRef`. The next `tell()` goes to the new process:

```java
// This works before, during, and after the restart
fleet.get("vehicle-42").tell(new UpdatePosition(lat, lon));
```

No map update. No callback. No event listener. The reference just works.

## The proc() Escape Hatch

Sometimes infrastructure code -- monitors, debugging tools, system introspection -- needs the underlying `Proc` directly:

```java
public Proc<S, M> proc()
```

This returns the current delegate. Use it only for monitoring infrastructure. For normal message passing, always go through `tell()` and `ask()` on the `ProcRef` itself.

## Never Hold Raw Proc

This is the rule: if a process is supervised, hold its `ProcRef`, not its `Proc`. A raw `Proc` reference becomes invalid after restart. A `ProcRef` stays valid across any number of restarts.

Think of it like a phone number versus a physical phone. The phone might break and get replaced, but the number stays the same. Callers dial the number, not the device.

```java
// Wrong: raw Proc goes stale after restart
Proc<VehicleState, VehicleMsg> vehicle = new Proc<>(init, handler);

// Right: ProcRef survives restarts
ProcRef<VehicleState, VehicleMsg> vehicleRef = new ProcRef<>(vehicle);
```

In practice, you rarely create `ProcRef` directly. The `Supervisor` creates it for you when you add a child, and you retrieve it with `supervisor.getRef(name)`.

## stop() and Lifecycle

Calling `stop()` on a `ProcRef` gracefully shuts down the current delegate. It does not notify the supervisor -- if you want to stop the whole supervision tree, use `Supervisor.shutdown()` instead.

## Therefore

Wrap supervised processes in `ProcRef` so clients hold stable handles that survive restarts. The supervisor calls `swap()` on restart; clients call `tell()` and `ask()` unchanged. Never hold a raw `Proc` reference to a supervised process.
