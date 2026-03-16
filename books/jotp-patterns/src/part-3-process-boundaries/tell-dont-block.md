# Pattern 12: Tell, Don't Block

## Context

You have processes running. They need to communicate. The first instinct from years of synchronous programming is to call a method and wait for the result. But most of the time, you do not need the result. You just need the message delivered.

## Problem

How should processes communicate when the sender does not need a response?

## Solution

Use `tell()`. It is fire-and-forget. The sender enqueues the message and moves on immediately:

```java
public void tell(M msg)
```

That is the full signature. No return value. No future. No waiting. The message goes into the process's `LinkedTransferQueue` mailbox and the caller's thread is free to do other work.

## Fleet Telemetry at Scale

Picture a fleet management system receiving GPS updates from 100,000 vehicles. Each update goes to the right vehicle process:

```java
// Telemetry receiver -- runs on its own virtual thread
void onGpsUpdate(String vehicleId, double lat, double lon) {
    Proc<VehicleState, VehicleMsg> vehicle = fleet.get(vehicleId);
    vehicle.tell(new UpdatePosition(lat, lon));
    // Returns immediately. Vehicle processes the update on its own time.
}
```

The telemetry receiver does not care when the vehicle process handles the update. It does not need confirmation. It fires the message and moves on to the next GPS packet. At 100,000 vehicles sending updates every second, that is 100,000 `tell()` calls per second -- each completing in 50-150 nanoseconds because `LinkedTransferQueue` is lock-free.

## Why the Mailbox Matters

Each `Proc` has its own `LinkedTransferQueue` instance. This is a lock-free multi-producer, multi-consumer queue, though JOTP uses it in a multi-producer, single-consumer pattern (many callers tell, one process reads).

When you call `tell()`, internally this happens:

```java
public void tell(M msg) {
    mailbox.add(new Envelope<>(msg, null));
}
```

The `null` reply handle means nobody is waiting for a response. The envelope sits in the queue until the process's message loop picks it up, applies the handler, and moves on to the next one.

If the process is slow, messages queue up. If the process is fast, the queue stays near-empty. The mailbox acts as a natural buffer between producer speed and consumer speed. No explicit backpressure configuration needed for fire-and-forget.

## When Tell Is the Right Choice

`tell()` is the default. Use it when:

- **Notifications:** "Here is a GPS update." The sender does not need acknowledgment.
- **Commands:** "Start processing this order." The sender will check results later through a different channel.
- **Events:** "A user logged in." Multiple listeners might care, none need to respond.
- **Logging:** "Record this metric." The sender must not block on I/O.

The pattern from OTP is clear: the `!` (send) operator is the primary communication mode. Most messages in a well-designed system are fire-and-forget. Request-reply is the exception, not the rule.

## Multiple Senders, One Receiver

The beauty of `tell()` is that any number of threads can call it simultaneously on the same process:

```java
// From the REST handler thread
vehicle.tell(new UpdatePosition(lat, lon));

// From the alert monitoring thread
vehicle.tell(new CheckGeofence(boundaryId));

// From the maintenance scheduler thread
vehicle.tell(new ScheduleService(nextDate));
```

All three messages land in the same mailbox. The vehicle process handles them one at a time, in arrival order. No coordination between senders required.

## Therefore

Default to `tell()` for all process communication. It is non-blocking, lock-free, and completes in nanoseconds. Reserve `ask()` for the cases where you genuinely need the response before you can proceed.
