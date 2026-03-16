# Pattern 28: Process Introspection

## The Problem

It is 3 AM. A vehicle tracker process is behaving strangely -- accepting messages but producing wrong outputs. You need to see its internal state. In a traditional system, you would add logging, redeploy, wait for the problem to recur, and read the logs. That cycle takes hours.

You need to look inside a running process without stopping it, without redeploying, without changing any code.

## The Solution

`ProcSys` provides runtime introspection for any `Proc`. Three capabilities, all non-blocking:

**Get the current state:**

```java
CompletableFuture<S> future = ProcSys.getState(proc);
S currentState = future.join();
```

This returns a future that completes after the process finishes processing its current message. The snapshot is consistent -- you see the state between messages, never mid-transition.

**Suspend and resume:**

```java
ProcSys.suspend(proc);
// Process stops dequeuing messages. Mailbox accumulates.
// Inspect state, attach debugger, whatever you need.
ProcSys.resume(proc);
// Process resumes from where it left off.
```

Suspend does not kill the process. It pauses message processing. Messages keep arriving in the mailbox -- they just pile up until you call `resume`. This is how OTP does hot code upgrades: suspend, swap the handler, resume.

**Get statistics:**

```java
ProcSys.Stats stats = ProcSys.statistics(proc);
// stats.messagesIn()   -- total messages received since start
// stats.messagesOut()  -- total messages processed since start
// stats.queueDepth()   -- current mailbox depth
```

The `Stats` record gives you a point-in-time snapshot of the process's throughput. If `queueDepth` is growing, the process is falling behind. If `messagesIn` is much higher than `messagesOut`, messages are being received faster than they can be handled.

## Live Debugging Example

Imagine a fleet monitoring dashboard that lets operators inspect any vehicle process:

```java
sealed interface DiagMsg permits InspectVehicle {}
record InspectVehicle(String vehicleId, CompletableFuture<DiagReport> reply)
    implements DiagMsg {}

record DiagReport(Object state, ProcSys.Stats stats) {}

var diagnostics = new Proc<>(
    Void.class.cast(null),
    (ignored, msg) -> switch (msg) {
        case InspectVehicle(var id, var reply) -> {
            Optional<Proc<Object, Object>> maybProc = ProcRegistry.whereis(id);
            maybProc.ifPresentOrElse(
                proc -> {
                    var state = ProcSys.getState(proc).join();
                    var stats = ProcSys.statistics(proc);
                    reply.complete(new DiagReport(state, stats));
                },
                () -> reply.completeExceptionally(
                    new IllegalArgumentException("Unknown vehicle: " + id))
            );
            yield ignored;
        }
    }
);
```

An operator queries from a REST endpoint:

```java
var report = new CompletableFuture<DiagReport>();
diagnostics.tell(new InspectVehicle("V-1001", report));
DiagReport diag = report.join();

System.out.println("State: " + diag.state());
System.out.println("Messages in: " + diag.stats().messagesIn());
System.out.println("Messages out: " + diag.stats().messagesOut());
System.out.println("Queue depth: " + diag.stats().queueDepth());
```

No restart. No redeploy. No log-and-pray. You see the live state of any process, from any other process, at any time.

## Detecting Backpressure

The statistics API makes backpressure visible:

```java
ProcSys.Stats stats = ProcSys.statistics(vehicleProc);
if (stats.queueDepth() > 1000) {
    log.warn("Vehicle {} mailbox backing up: {} messages queued",
        vehicleId, stats.queueDepth());
    // Consider: slow down producers, add more workers, or investigate handler
}
```

This is not guessing. This is measuring. The queue depth tells you exactly how far behind a process is, right now.

## When to Use This

Use `ProcSys.getState` for debugging and diagnostics. Use `statistics` for monitoring and alerting. Use `suspend`/`resume` for controlled maintenance operations -- hot code swaps, state migrations, or simply pausing a process while you investigate a problem.

Do not use `suspend` in production request paths. A suspended process accumulates messages without processing them. It is a debugging tool, not a flow-control mechanism.
