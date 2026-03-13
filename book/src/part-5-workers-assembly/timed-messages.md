# Pattern 26: Timed Messages

## The Problem

Your vehicle tracker needs a heartbeat. Every 10 seconds, each vehicle process should check whether its GPS device has reported in. If not, raise an alert. You also need a one-shot timeout: if a vehicle does not acknowledge a command within 5 seconds, mark the command as failed.

The tempting approach is `Thread.sleep` or a `ScheduledExecutorService` with callbacks. But sleep blocks the message loop, and callbacks execute on a different thread -- outside the process's mailbox, outside its single-threaded safety guarantee.

## The Solution

`ProcTimer` sends messages to a process after a delay or on a recurring interval. The message arrives in the process's mailbox like any other message. The process handles it in its normal message loop, with its normal state, on its normal virtual thread. No callbacks, no thread-safety concerns.

Two operations:

```java
// One-shot: send msg to target after delayMs milliseconds
TimerRef ref = ProcTimer.sendAfter(5000, target, new CheckTimeout());

// Repeating: send msg to target every periodMs milliseconds
TimerRef ref = ProcTimer.sendInterval(10000, target, new Heartbeat());
```

Both return a `TimerRef`. Cancel with `ProcTimer.cancel(ref)` or `ref.cancel()`. Cancellation is idempotent -- safe to call after the timer has already fired.

## Heartbeat Pattern

Define messages that include timer concerns alongside business messages:

```java
sealed interface TrackerMsg permits GpsUpdate, Heartbeat, CheckTimeout, SendCommand {}
record GpsUpdate(double lat, double lon, long timestamp) implements TrackerMsg {}
record Heartbeat() implements TrackerMsg {}
record CheckTimeout(String commandId) implements TrackerMsg {}
record SendCommand(String commandId, String payload) implements TrackerMsg {}
```

The vehicle process handles heartbeats in its normal message handler:

```java
record TrackerState(long lastGpsTimestamp, boolean alertSent) {}

var tracker = new Proc<>(
    new TrackerState(System.currentTimeMillis(), false),
    (state, msg) -> switch (msg) {
        case GpsUpdate(var lat, var lon, var ts) ->
            new TrackerState(ts, false);

        case Heartbeat() -> {
            long elapsed = System.currentTimeMillis() - state.lastGpsTimestamp();
            if (elapsed > 30_000 && !state.alertSent()) {
                alertService.tell(new VehicleAlert("V-1001", "GPS silent for 30s"));
                yield new TrackerState(state.lastGpsTimestamp(), true);
            }
            yield state;
        }

        case CheckTimeout(var cmdId) -> {
            // If command still pending after timeout, mark it failed
            commandLog.tell(new CommandFailed(cmdId, "timeout"));
            yield state;
        }

        case SendCommand(var cmdId, var payload) -> {
            deviceGateway.tell(new DeviceCommand(cmdId, payload));
            // Schedule a timeout check in 5 seconds
            ProcTimer.sendAfter(5000, trackerRef, new CheckTimeout(cmdId));
            yield state;
        }
    }
);
```

Start the heartbeat interval when the process is created:

```java
var heartbeatTimer = ProcTimer.sendInterval(10_000, tracker, new Heartbeat());
```

Every 10 seconds, a `Heartbeat` message lands in the tracker's mailbox. The handler checks the elapsed time since the last GPS update. If it has been more than 30 seconds, it sends an alert -- but only once (the `alertSent` flag prevents duplicate alerts until a fresh GPS update clears it).

## Timeout Detection

When the process sends a command to a device, it schedules a one-shot `CheckTimeout` message 5 seconds in the future. If the device responds before then, the process can cancel the timer. If not, the timeout message arrives and the process marks the command as failed.

```java
// Device responded -- cancel the timeout
ProcTimer.cancel(timeoutRef);
```

## What Makes This Work

The key property is that timer messages are just messages. They go through the same mailbox, processed in the same order, handled by the same function. There is no separate callback thread, no synchronization needed, no race conditions between the timer firing and the process handling a regular message.

The timer infrastructure itself uses a single daemon platform thread internally -- a `ScheduledExecutorService` with one thread. The actual work it does is trivial: call `target.tell(msg)`, which is a non-blocking enqueue. Thousands of timers cost almost nothing.

## Cleanup

Always cancel interval timers when the process shuts down. A timer referencing a stopped process will keep calling `tell` on a dead mailbox -- harmless but wasteful. Store the `TimerRef` and cancel it during shutdown.
