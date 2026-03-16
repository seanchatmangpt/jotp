# ProcMonitor - Unilateral DOWN Notifications

## Overview

`ProcMonitor` provides unilateral DOWN notifications when a target process terminates. Unlike `ProcLink` (which kills the monitoring process on crash), monitoring is unidirectional: when the monitored process terminates for any reason (normal or abnormal), the monitoring side receives a DOWN notification carrying the exit reason. The monitoring process itself is never killed.

This is the mechanism `GenServer.call/3` uses to implement call timeouts: it monitors the target, sends a request, and either receives a reply or a DOWN notification first.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `monitor(process, Pid)` | `ProcMonitor.monitor(Proc, Consumer)` |
| `demonitor(MonitorRef)` | `ProcMonitor.demonitor(MonitorRef)` |
| `{'DOWN', Ref, process, Pid, Reason}` | `Consumer<Throwable>` (null = normal) |
| `erlang:monitor/2` | Installation of termination callback |
| `erlang:demonitor/1` | Removal of termination callback |

## Architecture

```
Monitoring Process          Target Process
       │                            │
       │──monitor(target, handler)──>│
       │                            │
       │                    (crashes or stops)
       │                            │
       │<──DOWN notification────────│
       │  (handler called with      │
       │   reason or null)          │
```

## Public API

### `MonitorRef<S,M>` - Opaque Monitor Handle

```java
public record MonitorRef<S, M>(
    Proc<S, M> target,
    Consumer<Throwable> callback
)
```

Opaque handle returned by `monitor()`. Pass to `demonitor()` to cancel.

**Type Parameters:**
- `S` - Target process state type
- `M` - Target process message type

### `monitor()` - Install Termination Callback

```java
public static <S, M> MonitorRef<S, M> monitor(
    Proc<S, M> target,
    Consumer<Throwable> downHandler
)
```

Start monitoring `target`. When `target` terminates for any reason, `downHandler` is invoked.

**Parameters:**
- `target` - Process to monitor
- `downHandler` - Called when target terminates; `null` argument = normal exit, non-null `Throwable` = crash

**Returns:**
- `MonitorRef` - Keep this to cancel via `demonitor()`

**Behavior:**
- The `downHandler` is called on the target's virtual thread (must not block)
- Use it to enqueue a message into the monitoring process's mailbox if needed
- Multiple monitors can be installed on the same target

**Example:**
```java
Proc<State, Message> target = new Proc<>(initial, handler);
MonitorRef<State, Message> ref = ProcMonitor.monitor(
    target,
    reason -> {
        if (reason == null) {
            System.out.println("Target stopped normally");
        } else {
            System.err.println("Target crashed: " + reason);
        }
    }
);
```

### `demonitor()` - Cancel Monitor

```java
public static void demonitor(MonitorRef<?, ?> ref)
```

Cancel a monitor. After this call, the `downHandler` will not be invoked even if the target subsequently terminates.

**Parameters:**
- `ref` - The monitor reference returned by `monitor()`

**Behavior:**
- Safe to call after the target has already terminated (no-op)
- Removes the termination callback from the target's callback list

**Example:**
```java
MonitorRef<State, Message> ref = ProcMonitor.monitor(target, handler);
// ... later ...
ProcMonitor.demonitor(ref);  // Cancel monitoring
```

## Common Usage Patterns

### 1. Detect Process Crash

```java
Proc<State, Message> target = new Proc<>(initial, handler);

ProcMonitor.monitor(target, reason -> {
    if (reason != null) {
        System.err.println("Process crashed: " + reason.getMessage());
        // Take corrective action
    }
});
```

### 2. Implement Call Timeout

```java
public <T> CompletableFuture<T> callWithTimeout(
    Proc<State, Message> target,
    Message request,
    Duration timeout
) {
    var responseFuture = new CompletableFuture<T>();

    // Monitor the target
    ProcMonitor.monitor(target, reason -> {
        if (!responseFuture.isDone()) {
            responseFuture.completeExceptionally(
                new TimeoutException("Process terminated")
            );
        }
    });

    // Send request
    target.tell(request);

    // Set timeout
    CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .execute(() -> {
            if (!responseFuture.isDone()) {
                responseFuture.completeExceptionally(
                    new TimeoutException("Call timed out")
                );
            }
        });

    return responseFuture;
}
```

### 3. Cleanup on Termination

```java
Proc<State, Message> worker = new Proc<>(initial, handler);
SharedResource resource = new SharedResource();

ProcMonitor.monitor(worker, reason -> {
    // Cleanup regardless of how process terminated
    resource.release();
    System.out.println("Worker terminated, resource released");
});
```

### 4. Multiple Monitors on Same Process

```java
Proc<State, Message> target = new Proc<>(initial, handler);

// Multiple independent monitors
ProcMonitor.monitor(target, reason -> logTermination("monitor1", reason));
ProcMonitor.monitor(target, reason -> logTermination("monitor2", reason));
ProcMonitor.monitor(target, reason -> sendAlert("monitor3", reason));

// All handlers will be called on termination
```

### 5. Self-Monitoring (Process Watches Itself)

```java
Proc<State, Message> proc = new Proc<>(initial, (state, msg) -> {
    // ... handle messages ...
});

ProcMonitor.monitor(proc, reason -> {
    // This runs on proc's virtual thread during termination
    System.out.println("I'm terminating: " + (reason == null ? "normal" : reason));
});
```

## Thread-Safety Guarantees

- **Callback execution:** The `downHandler` is called on the target's virtual thread
- **Non-blocking requirement:** Callbacks must not block (use `tell()` to enqueue messages)
- **Concurrent monitoring:** Multiple monitors can be installed concurrently
- **Atomic cancellation:** `demonitor()` atomically removes the callback

## Composability

A process can be simultaneously:
1. **Supervised** (via `Supervisor`) - Restart on crash
2. **Linked** (via `ProcLink`) - Bilateral crash propagation
3. **Monitored** (via `ProcMonitor`) - Unilateral DOWN notifications

All three are orthogonal; they use separate callback lists on `Proc`.

```java
Proc<State, Message> proc = new Proc<>(initial, handler);

// All three can coexist
Supervisor supervisor = new Supervisor();
supervisor.startChild(spec);           // Supervision
ProcLink.link(proc, peer);             // Linking
ProcMonitor.monitor(proc, handler);    // Monitoring
```

## Performance Characteristics

- **Installation cost:** O(1) - adds callback to target's list
- **Termination cost:** O(n) - invokes all n registered callbacks
- **Memory overhead:** ~32 bytes per monitor (callback + target reference)
- **No blocking:** Callbacks are invoked, no waiting for response

## Related Classes

- **`ProcLink`** - Bilateral crash propagation (kills linked process on crash)
- **`ProcRef`** - Stable process references surviving supervisor restart
- **`Supervisor`** - Automatic restart on crash (can be combined with monitoring)
- **`ExitSignal`** - Trapped exit signals delivered as mailbox messages

## Best Practices

1. **Don't block in callbacks:** Callbacks run on the target's virtual thread
2. **Enqueue messages:** Use `tell()` to send notifications to monitoring process
3. **Check for null:** `null` reason = normal exit, non-null = crash
4. **Cancel when done:** Use `demonitor()` to clean up unused monitors
5. **Use for timeouts:** Implement call timeouts by monitoring + delayed future

## Design Rationale

**Why Monitoring instead of Linking?**

In Erlang/OTP, monitoring is preferred over linking when:
- The monitoring process should not be killed by the target's crash
- You need to detect termination without propagating crashes
- Implementing timeouts (like `GenServer.call/3`)

JOTP preserves this distinction:
- **Linking:** Bilateral relationship - crash propagates both ways
- **Monitoring:** Unilateral observation - crash notification only

This enables patterns like:
- Timeout detection without killing the caller
- Cleanup on termination without suicide
- Multiple independent observers of one process

Monitoring is the primitive that makes OTP's "let it crash" philosophy practical - you can detect failures without being killed by them.
