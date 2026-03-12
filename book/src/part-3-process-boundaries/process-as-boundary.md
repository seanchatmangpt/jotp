# Pattern 11: Process as Boundary

## Context

You have a pure handler function -- a `BiFunction<S, M, S>` that takes state and a message, returns new state. It is easy to test, easy to reason about, and completely single-threaded. Now you need it to run concurrently alongside thousands of other handlers, each with its own state, without any of them stepping on each other.

## Problem

How do you take pure logic and make it concurrent without introducing locks, synchronized blocks, or shared mutable state?

## Solution

Wrap it in a `Proc`. One line turns a pure function into a concurrent actor:

```java
var vehicle = new Proc<>(VehicleState.initial(), (state, msg) -> switch (msg) {
    case UpdatePosition p -> state.withPosition(p.lat(), p.lon());
    case UpdateSpeed s    -> state.withSpeed(s.kph());
});
```

That is the constructor: `new Proc<>(initialState, handler)`. The first argument is your initial state. The second is the handler function you already wrote and tested in Part 2. Nothing else changes about the handler -- it is still a pure function from `(S, M) -> S`.

What `Proc` gives you behind that one line:

**Sequential processing.** Messages arrive in the `LinkedTransferQueue` mailbox and are processed one at a time. Your handler never runs concurrently with itself. No locks needed, because there is no concurrent access to state.

**Isolated state.** The state `S` lives inside the virtual thread's scope. No other process can read it or write it. The only way to affect state is to send a message.

**Concurrent execution.** Each `Proc` runs on its own virtual thread. Ten thousand vehicles means ten thousand virtual threads, each processing its own mailbox independently. The JVM schedules them across platform threads automatically.

## The Boundary in Practice

Think of the `Proc` constructor as a membrane. Inside the membrane, everything is pure and sequential. Outside, everything is concurrent and asynchronous. The membrane itself -- the mailbox, the virtual thread, the message loop -- handles the translation.

```
Outside (concurrent)          Membrane (Proc)         Inside (sequential)

vehicle.tell(msg1) ──┐
vehicle.tell(msg2) ──┤──► LinkedTransferQueue ──► handler.apply(state, msg)
vehicle.tell(msg3) ──┘       (mailbox)              returns new state
```

Multiple callers can `tell()` simultaneously. The mailbox serializes them. The handler sees one message at a time.

## Testing Stays Simple

The handler is still just a function. You do not need a running `Proc` to test it:

```java
BiFunction<VehicleState, VehicleMsg, VehicleState> handler = (state, msg) -> switch (msg) {
    case UpdatePosition p -> state.withPosition(p.lat(), p.lon());
    case UpdateSpeed s    -> state.withSpeed(s.kph());
};

var result = handler.apply(VehicleState.initial(), new UpdatePosition(37.7749, -122.4194));
assertThat(result.lat()).isEqualTo(37.7749);
```

No actor system to boot. No mailbox to drain. No timeouts to wait for. The `Proc` is a deployment decision, not a logic decision.

## Therefore

Use `new Proc<>(initialState, handler)` to wrap any pure handler into a concurrent process. The handler remains independently testable. The Proc provides sequential processing, isolated state, and concurrent execution -- all from a single constructor call.
