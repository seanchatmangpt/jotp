# ProcTimer - Timed Message Delivery

## Overview

`ProcTimer` implements OTP-style process timers - `timer:send_after/3` and `timer:send_interval/3`. In OTP, processes model timeouts by receiving timed messages rather than using blocking sleep or callback-based APIs. `timer:send_after(Ms, Pid, Msg)` sends `Msg` to `Pid` after `Ms` milliseconds; the process handles it like any other message in its main receive loop.

This approach integrates seamlessly with the process model: timeouts are just messages, processed in order with all other messages, with no blocking or callback hell.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `timer:send_after(Ms, Pid, Msg)` | `ProcTimer.sendAfter(ms, proc, msg)` |
| `timer:send_interval(Ms, Pid, Msg)` | `ProcTimer.sendInterval(ms, proc, msg)` |
| `timer:cancel(Tref)` | `ProcTimer.cancel(ref)` or `ref.cancel()` |
| `erlang:send_after/3` | Same as `timer:send_after/3` |
| `erlang:start_timer/3` | Not yet implemented |

## Architecture

```
Shared Scheduler (Single Daemon Thread)
         │
         ├──Timer Wheel (CPU-bound scheduling)
         │
         └───Proc.tell() (Non-blocking enqueue)
              │
              ▼
           Target Process Mailbox
              │
              └───Processed in message loop order
```

## Public API

### `TimerRef` - Opaque Timer Handle

```java
public record TimerRef(ScheduledFuture<?> future) {
    public boolean cancel()
}
```

Opaque handle returned by `sendAfter()` and `sendInterval()`. Use `cancel()` to stop the timer.

**Methods:**
- `cancel()` - Cancel this timer; returns `true` if timer was still pending

### `sendAfter()` - One-Shot Delayed Message

```java
public static <M> TimerRef sendAfter(
    long delayMs,
    Proc<?, M> target,
    M msg
)
```

Send `msg` to `target` after `delayMs` milliseconds (one-shot).

**Parameters:**
- `delayMs` - Delay in milliseconds
- `target` - The process that will receive the message
- `msg` - The message to deliver

**Returns:**
- `TimerRef` that can be used to cancel before delivery

**Example:**
```java
Proc<State, Message> proc = new Proc<>(init, handler);

// Send timeout message after 5 seconds
TimerRef timer = ProcTimer.sendAfter(5000, proc, new Message.Timeout());

// Cancel if no longer needed
timer.cancel();
```

### `sendInterval()` - Periodic Message Delivery

```java
public static <M> TimerRef sendInterval(
    long periodMs,
    Proc<?, M> target,
    M msg
)
```

Send `msg` to `target` every `periodMs` milliseconds (repeating).

**Parameters:**
- `periodMs` - Period in milliseconds
- `target` - The process that will receive the message
- `msg` - The message to deliver on each tick

**Returns:**
- `TimerRef` that can be used to stop the interval

**Behavior:**
- First delivery occurs after one full period
- Timer continues indefinitely until `cancel()` or JVM exits
- Cancelling a stopped `Proc`'s interval timer is the caller's responsibility

**Example:**
```java
Proc<State, Message> proc = new Proc<>(init, handler);

// Send tick message every 100ms
TimerRef ticker = ProcTimer.sendInterval(100, proc, new Message.Tick());

// Later: stop the ticker
ticker.cancel();
```

### Duration-Based Overloads

```java
public static <M> TimerRef sendAfter(
    Duration delay,
    Proc<?, M> target,
    M msg
)

public static <M> TimerRef sendInterval(
    Duration interval,
    Proc<?, M> target,
    M msg
)
```

Convenience overloads using `java.time.Duration` instead of milliseconds.

**Example:**
```java
ProcTimer.sendAfter(Duration.ofSeconds(5), proc, msg);
ProcTimer.sendInterval(Duration.ofMillis(100), proc, tickMsg);
```

### `cancel()` - Cancel Timer

```java
public static boolean cancel(TimerRef ref)
```

Cancel a timer. Convenience alias for `TimerRef.cancel()`.

**Returns:**
- `true` if the timer was still pending and was successfully cancelled
- `false` if the timer already fired or was already cancelled

**Behavior:**
- Safe to call after the timer has already fired (no-op)
- Safe to call multiple times (idempotent)

**Example:**
```java
TimerRef timer = ProcTimer.sendAfter(5000, proc, msg);
// ... later ...
ProcTimer.cancel(timer);  // Stop the timer
```

## Common Usage Patterns

### 1. Request Timeout

```java
Proc<State, Message> proc = new Proc<>(init, handler);

// Send request and set timeout
proc.tell(new Message.Request(data));
ProcTimer.sendAfter(5000, proc, new Message.Timeout());

// In handler:
BiFunction<State, Message, State> handler = (state, msg) -> switch (msg) {
    case Message.Timeout t -> {
        System.err.println("Request timed out");
        yield handleTimeout(state);
    }
    case Message.Response r -> handleResponse(state, r)
    // ...
};
```

### 2. Periodic Health Check

```java
Proc<State, Message> proc = new Proc<>(init, handler);

// Send health check every 30 seconds
ProcTimer.sendInterval(30_000, proc, new Message.HealthCheck());

// In handler:
BiFunction<State, Message, State> handler = (state, msg) -> switch (msg) {
    case Message.HealthCheck hc -> {
        // Perform health check
        yield checkHealth(state);
    }
    // ...
};
```

### 3. Retry with Backoff

```java
class RetryState {
    final int attempts;
    final Duration delay;
    RetryState(int attempts, Duration delay) {
        this.attempts = attempts;
        this.delay = delay;
    }
}

Proc<RetryState, Message> proc = new Proc<>(
    new RetryState(0, Duration.ofSeconds(1)),
    (state, msg) -> switch (msg) {
        case Message.Retry r -> {
            if (state.attempts >= 5) {
                System.err.println("Max retries exceeded");
                yield state;
            }
            // Schedule next retry with exponential backoff
            Duration nextDelay = state.delay.multipliedBy(2);
            ProcTimer.sendAfter(nextDelay, proc, new Message.Retry());
            yield new RetryState(state.attempts + 1, nextDelay);
        }
        // ...
    }
);
```

### 4. Debouncing User Input

```java
Proc<State, Message> proc = new Proc<>(init, handler);
AtomicReference<TimerRef> lastTimer = new AtomicReference<>();

// On user input:
void onUserInput(String input) {
    // Cancel previous timer
    TimerRef prev = lastTimer.getAndSet(null);
    if (prev != null) prev.cancel();

    // Set new timer (debounce delay)
    TimerRef newTimer = ProcTimer.sendAfter(300, proc, new Message.ProcessInput(input));
    lastTimer.set(newTimer);
}

// Only the last input (after 300ms of no input) will be processed
```

### 5. Heartbeat Monitoring

```java
class HeartbeatState {
    long lastHeartbeat;
    HeartbeatState(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
}

Proc<HeartbeatState, Message> proc = new Proc<>(
    new HeartbeatState(System.currentTimeMillis()),
    (state, msg) -> switch (msg) {
        case Message.Heartbeat hb -> {
            // Update last heartbeat time
            yield new HeartbeatState(System.currentTimeMillis());
        }
        case Message.CheckTimeout ct -> {
            long elapsed = System.currentTimeMillis() - state.lastHeartbeat;
            if (elapsed > 5000) {
                System.err.println("Heartbeat timeout!");
                // Take action
            }
            // Schedule next check
            ProcTimer.sendAfter(1000, proc, new Message.CheckTimeout());
            yield state;
        }
        // ...
    }
);

// Start timeout checker
ProcTimer.sendAfter(1000, proc, new Message.CheckTimeout());
```

## Java 26 Design Rationale

**Why a single daemon thread instead of virtual threads?**

The shared scheduler uses a single daemon platform thread (backing a timer wheel) because:

1. **Timer wheels are CPU-bound:** Managing the timer wheel requires precise scheduling and interrupt handling—work best suited to a single platform thread with kernel-level timer semantics.

2. **Virtual threads for I/O:** Virtual threads excel at I/O-bound workloads (network, files); they add overhead for CPU-bound tasks like timer management.

3. **Minimal callback overhead:** The actual callback work is just a non-blocking `Proc.tell()` enqueue into the target process's mailbox, so the timer thread itself never blocks or yields to application logic.

4. **Fairness:** A single thread ensures all timers are processed with uniform latency guarantees, avoiding the fairness issues of thread pools.

While Java 26 adds `newVirtualThreadPerTaskExecutor()`, using it for timer management would violate the "use virtual threads for I/O, not CPU work" principle and would increase overhead without benefit.

## Thread-Safety Guarantees

- **Scheduler thread:** Single daemon platform thread with timer wheel
- **Thread-safe enqueue:** `Proc.tell()` is thread-safe
- **Concurrent timers:** Multiple timers can be created/cancelled concurrently
- **Atomic cancel:** Cancel operation is atomic with timer firing

## Performance Characteristics

- **Scheduler overhead:** O(log n) per timer (timer wheel)
- **Message delivery:** O(1) - non-blocking enqueue
- **Memory footprint:** ~200 bytes per timer (ScheduledFuture overhead)
- **Cancellation cost:** O(1) - future cancellation
- **One thread:** All timers share a single scheduler thread

## Related Classes

- **`Proc<S,M>`** - Target process for timed messages
- **`ProcMonitor`** - Implement call timeouts with monitoring
- **`CompletableFuture`** - Alternative timeout mechanism (orphaned)
- **`ScheduledExecutorService`** - Underlying scheduler implementation

## Best Practices

1. **Cancel when done:** Always cancel timers when no longer needed
2. **Use Duration:** Prefer `Duration` over raw milliseconds for readability
3. **Message type safety:** Include timer messages in your sealed message hierarchy
4. **Avoid accumulation:** Cancel old timers before creating new ones (debouncing)
5. **Check process liveness:** Timer messages to stopped processes are dropped

## Design Philosophy

**Why timed messages instead of callbacks?**

In traditional Java, timeouts are implemented with callbacks or `CompletableFuture.orTimeout()`. In OTP, timeouts are just messages:

**Benefits:**
- **Unified message loop:** Timeouts processed in order with other messages
- **No callback hell:** No nested callbacks or complex futures
- **Composable:** Can combine with monitoring, supervision, etc.
- **Process-centric:** Timeouts are part of the process state machine
- **Debuggable:** All messages (including timeouts) visible in trace logs

**Example: Unified Message Loop**

```java
BiFunction<State, Message, State> handler = (state, msg) -> switch (msg) {
    // Regular messages
    case Message.Request r -> handleRequest(state, r);

    // Timeout (just another message)
    case Message.Timeout t -> handleTimeout(state, t);

    // Periodic tick (just another message)
    case Message.Tick tick -> handleTick(state, tick);

    // No special cases - all processed in order
};
```

This is the OTP way: everything is a message, everything is composable, everything is debuggable.

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `timer:send_after(5000, Pid, msg)` | `ProcTimer.sendAfter(5000, proc, msg)` |
| `timer:send_interval(100, Pid, msg)` | `ProcTimer.sendInterval(100, proc, msg)` |
| `timer:cancel(Tref)` | `ProcTimer.cancel(ref)` or `ref.cancel()` |
| `erlang:send_after(5000, Pid, msg)` | Same as `timer:send_after` |
| `receive after 5000 -> ... end` | `ProcTimer.sendAfter` + message handler |
| `receive Msg -> ... after 5000 -> ... end` | `ProcTimer.sendAfter` + select logic |
