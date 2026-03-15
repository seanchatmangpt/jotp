# Process Management in JOTP

## Problem Statement
You need to create, communicate with, and manage lightweight concurrent processes in your Java application, but traditional threads are too heavy and shared mutable state leads to race conditions.

## Solution Overview
JOTP's `Proc<S,M>` provides lightweight virtual-thread processes with mailboxes, implementing Erlang/OTP's actor model on Java 26. Processes share nothing, communicate only via message passing, and are supervised for fault tolerance.

## Step-by-Step Instructions

### 1. Spawn a Process

Use `Proc.spawn()` to create a lightweight process with an initial state and message handler:

```java
// Define your state (immutable record recommended)
record CounterState(int value) {}

// Define your messages (sealed interface of records)
sealed interface CounterMsg {
    record Increment(int by) implements CounterMsg {}
    record Decrement(int by) implements CounterMsg {}
    record GetValue() implements CounterMsg {}
}

// Create and start a process
Proc<CounterState, CounterMsg> counter = Proc.spawn(
    new CounterState(0),
    (state, msg) -> switch (msg) {
        case CounterMsg.Increment(int by) ->
            new CounterState(state.value() + by);
        case CounterMsg.Decrement(int by) ->
            new CounterState(state.value() - by);
        case CounterMsg.GetValue() ->
            state; // Return current state
    }
);
```

**Key Points:**
- State is never shared - only accessed by the process's virtual thread
- Handler function is pure: same input → same output
- Use sealed interfaces for type-safe pattern matching
- Virtual threads scale to millions of processes (~1 KB heap each)

### 2. Send Messages (Tell vs Ask)

**Fire-and-forget (tell):** Send without waiting for processing

```java
counter.tell(new CounterMsg.Increment(5));
// Returns immediately, message queued in mailbox
// Non-blocking, suitable for notifications
```

**Request-reply (ask):** Send and wait for result

```java
CompletableFuture<CounterState> future = counter.ask(new CounterMsg.GetValue());
CounterState state = future.join(); // Blocks until processed
```

**Request-reply with timeout:** Prevent deadlocks

```java
CounterState state = counter.ask(new CounterMsg.GetValue(), Duration.ofSeconds(5))
    .orTimeout(5, TimeUnit.SECONDS)
    .join();
```

**When to use each:**
- `tell()`: Notifications, events, commands where you don't need a response
- `ask()`: Queries, state reads, commands requiring confirmation
- `ask()` with timeout: Production code where you need to avoid hanging forever

### 3. Stop a Process

Graceful shutdown:

```java
counter.stop(); // Interrupts virtual thread, drops pending messages
```

**Important considerations:**
- Pending messages in the mailbox are dropped (not processed)
- Does NOT fire crash callbacks (normal exit reason)
- Use `join()` if you need to wait for termination:

```java
counter.thread().join(); // Wait for virtual thread to finish
```

**Clean shutdown pattern** (process all messages before stopping):

```java
sealed interface ShutdownMsg {
    record Stop() implements ShutdownMsg {}
}

var proc = Proc.spawn(initialState, (state, msg) -> {
    if (msg instanceof ShutdownMsg.Stop) {
        // Handle finalization, then...
        throw new RuntimeException("Normal shutdown"); // Or use a flag
    }
    // Normal message handling
});

// To stop cleanly
proc.tell(new ShutdownMsg.Stop());
proc.thread().join();
```

### 4. Inspect Process State

**Via ProcSys** (introspection without stopping):

```java
import io.github.seanchatmangpt.jotp.ProcSys;

// Get current state (non-blocking snapshot)
Object state = ProcSys.getState(counter);
CounterState cs = (CounterState) state;
System.out.println("Current value: " + cs.value());

// Get message statistics
var stats = ProcSys.statistics(counter);
System.out.println("Messages in: " + stats.messagesIn());
System.out.println("Messages out: " + stats.messagesOut());
System.out.println("Mailbox depth: " + stats.mailboxSize());
```

**Suspend/resume** (pause processing without killing):

```java
ProcSys.suspend(counter); // Pauses after current message
// Process is still alive, just not processing new messages

ProcSys.resume(counter); // Resumes processing
```

**Check for crashes:**

```java
if (counter.lastError() != null) {
    System.err.println("Process crashed: " + counter.lastError().getMessage());
}
```

## Common Mistakes

### 1. Mutable State in Records
```java
// BAD - mutable list in record
record BadState(List<String> items) { /* list is mutable! */ }

// GOOD - immutable state
record GoodState(List<String> items) {
    GoodState {
        items = List.copyOf(items); // Defensive copy
    }
}
```

### 2. Blocking Operations in Handler
```java
// BAD - blocks the process's virtual thread
(state, msg) -> {
    Thread.sleep(1000); // Don't do this!
    return nextState;
}

// GOOD - use async patterns or timeouts
(state, msg) -> {
    // Handle quickly, or delegate to another process
    return nextState;
}
```

### 3. Forgetting Timeouts on ask()
```java
// BAD - can deadlock forever
counter.ask(new GetValue()).join();

// GOOD - always timeout in production
counter.ask(new GetValue(), Duration.ofSeconds(5))
    .orTimeout(5, TimeUnit.SECONDS)
    .join();
```

### 4. Shared State Across Processes
```java
// BAD - sharing mutable state
List<String> shared = new ArrayList<>();
Proc.spawn(new State(shared), handler1);
Proc.spawn(new State(shared), handler2); // Race conditions!

// GOOD - copy state or use message passing
Proc.spawn(new State(List.copyOf(shared)), handler1);
Proc.spawn(new State(List.copyOf(shared)), handler2);
```

## Related Guides
- [Building Supervision Trees](./building-supervision-trees.md) - Manage process lifecycle automatically
- [Error Handling](./error-handling.md) - Handle process crashes gracefully
- [Testing Processes](./testing-processes.md) - Test process behavior with JUnit

## Advanced Patterns

### Process Registry (named processes)
```java
ProcRegistry.register("my-counter", counter);

// Later, from anywhere
ProcRef<?, ?> ref = ProcRegistry.where("my-counter");
ref.tell(new CounterMsg.Increment(1));
```

### Process Monitoring
```java
ProcMonitor.monitor(counter, reason -> {
    if (reason != null) {
        logger.error("Counter crashed: {}", reason.getMessage());
    } else {
        logger.info("Counter exited normally");
    }
});
```

### Linked Processes (bilateral crash propagation)
```java
ProcLink.link(counter1, counter2);
// If counter1 crashes, counter2 also crashes (and vice versa)
```

### Exit Trapping
```java
counter.trapExits(true); // Convert EXIT signals to mailbox messages

// Handler now receives ExitSignal messages
(state, msg) -> switch (msg) {
    case ExitSignal es -> handleExit(es.reason());
    // ... other cases
};
```
