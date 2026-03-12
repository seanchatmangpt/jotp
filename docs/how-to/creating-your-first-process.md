# Creating Your First JOTP Process

## Overview

A **process** in JOTP is a lightweight concurrent entity with its own mailbox and state. Think of it as a virtual actor: it receives messages asynchronously, processes them one at a time, and maintains internal state that can only be modified by handling messages.

This guide walks you through spawning your first process, sending it messages, and handling responses.

## Basic Example: A Simple Counter

Here's the simplest process you can create:

```java
import io.github.seanchatmangpt.jotp.*;

// 1. Create a process: Counter(state=0, handler=(state,msg) -> newState)
var counter = Proc.spawn(
    0,  // initial state (just a number)
    (state, msg) -> switch (msg) {
        case "increment" -> state + 1;
        case "reset" -> 0;
        default -> state;
    }
);

// 2. Send messages using tell() — non-blocking fire-and-forget
counter.tell("increment");
counter.tell("increment");
counter.tell("increment");

// 3. Read the state asynchronously
var future = counter.ask("get-count", 1000);
var count = future.join();  // blocks until response or timeout
System.out.println("Count: " + count);  // prints: Count: 3
```

### What Just Happened?

1. **`Proc.spawn(initial, handler)`** — Creates a new process with:
   - Initial state: `0`
   - Message handler: a function that takes `(state, message) -> newState`
   - Returns a `Proc<Integer, String>` (state type = Integer, message type = String)

2. **`tell(message)`** — Sends a message to the process's mailbox and returns immediately (non-blocking)

3. **`ask(message, timeout)`** — Sends a message and waits for a response, returns a `CompletableFuture`

## Typed Messages

In real applications, use **sealed types** to represent message variants:

```java
sealed interface CounterMsg {
    record Increment() implements CounterMsg {}
    record Decrement() implements CounterMsg {}
    record Reset() implements CounterMsg {}
    record GetCount() implements CounterMsg {}
}

var counter = Proc.spawn(
    0,  // initial state
    (state, msg) -> switch (msg) {
        case CounterMsg.Increment() -> state + 1;
        case CounterMsg.Decrement() -> state - 1;
        case CounterMsg.Reset() -> 0;
        case CounterMsg.GetCount() -> state;  // pattern matching
    }
);

counter.tell(new CounterMsg.Increment());
counter.tell(new CounterMsg.Increment());

var count = counter.ask(new CounterMsg.GetCount(), 1000).join();
System.out.println("Count: " + count);  // prints: Count: 2
```

## Ask Pattern: Request-Response

The `ask()` method sends a message and blocks until the process sends back a response:

```java
sealed interface Request {
    record Fetch(String key) implements Request {}
}

sealed interface Response {
    record Data(String value) implements Response {}
    record NotFound() implements Response {}
}

// Process that acts like a simple cache
var cache = Proc.spawn(
    Map.of("user", "alice", "role", "admin"),
    (state, msg) -> switch (msg) {
        case Request.Fetch(var key) -> {
            if (state.containsKey(key)) {
                sender().tell(new Response.Data(state.get(key)));
            } else {
                sender().tell(new Response.NotFound());
            }
            yield state;  // state unchanged
        }
        default -> state;
    }
);

// From another context, ask the cache
var futureResponse = cache.ask(new Request.Fetch("user"), 1000);
var response = futureResponse.join();

if (response instanceof Response.Data data) {
    System.out.println("Value: " + data.value());  // prints: Value: alice
}
```

## Stopping a Process

Processes run until explicitly stopped or until the JVM exits:

```java
counter.stop();  // blocks until the process thread exits
System.out.println("Counter stopped");
```

## Common Patterns

### Fire-and-Forget Messages

```java
// No response expected
counter.tell(new CounterMsg.Increment());
// continue immediately
```

### Request-Response (Ask)

```java
// Send request, wait for response
var futureResponse = cache.ask(new Request.Fetch("key"), 5000);
var response = futureResponse.join();  // blocks
```

### Sending to the Current Process (Self)

```java
var echo = Proc.spawn(
    "initialized",
    (state, msg) -> {
        // Send a message to yourself (current process)
        sender().tell("echoing: " + msg);
        return state;
    }
);
```

## What Next?

Now that you can spawn processes, explore:

- **[Linking Processes](linking-processes.md)** — Handle crashes and dependencies
- **[Handling Process Crashes](handling-process-crashes.md)** — Build resilient systems with Supervisor
- **[State Machine Workflow](state-machine-workflow.md)** — Complex stateful workflows
- **API Reference** — `Proc`, `ProcRef`, `ProcMonitor`, `ProcRegistry`

## Troubleshooting

**Q: My `ask()` times out.**
A: The process may be slow to respond, or the handler may not be sending a response. Check:
- Is the handler calling `sender().tell(response)` or returning the response value?
- Is the timeout long enough?

**Q: Multiple asks to the same process seem to race.**
A: The process handles one message at a time. If two processes ask concurrently, responses arrive in the order messages were received. Use `ask()` with proper timeouts to avoid deadlocks.

**Q: How do I share state between processes?**
A: Send messages! State is encapsulated within each process. To share data, send it as a message or have one process ask another (see Linking Processes).
