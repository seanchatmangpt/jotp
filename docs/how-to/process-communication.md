# How-to: Process Communication Patterns

## Overview

Processes communicate through message passing. JOTP provides several communication patterns inspired by Erlang/OTP's gen_server behaviors.

## Pattern 1: Fire-and-Forget Messages with `tell()`

Send a message without waiting for a response:

```java
import io.github.seanchatmangpt.jotp.Proc;

record Counter(int value) {}
sealed interface CounterMsg {}
record Increment() implements CounterMsg {}
record Decrement() implements CounterMsg {}

var counter = new Proc<>(new Counter(0), (state, msg) -> switch (msg) {
    case Increment _ -> new Counter(state.value() + 1);
    case Decrement _ -> new Counter(state.value() - 1);
});

// Send messages asynchronously
counter.tell(new Increment());
counter.tell(new Increment());
counter.tell(new Decrement());
```

## Pattern 2: Request-Reply with `ask()`

Send a message and wait for the process's state after handling it:

```java
import java.util.concurrent.CompletableFuture;

record GetCount() implements CounterMsg {}

var future = counter.ask(new GetCount());
// This future completes with the counter's state after processing the message

var state = future.join();  // blocks until reply
System.out.println("Counter value: " + state.value());
```

## Pattern 3: Timed Request-Reply with Timeout

Use `ask(msg, Duration)` to prevent hanging on unresponsive processes:

```java
import java.time.Duration;
import java.util.concurrent.TimeoutException;

try {
    var future = counter.ask(new GetCount(), Duration.ofSeconds(5));
    var state = future.join();
} catch (TimeoutException e) {
    System.out.println("Process did not respond in time");
}
```

## Pattern 4: Selective Message Handling

Use sealed interfaces to create type-safe message hierarchies:

```java
sealed interface Message {}

sealed interface Command extends Message {}
record Start(String name) implements Command {}
record Stop() implements Command {}

sealed interface Query extends Message {}
record GetStatus() implements Query {}

var processor = new Proc<>(
    new State(),
    (state, msg) -> switch (msg) {
        case Start cmd -> state.withName(cmd.name());
        case Stop _ -> throw new RuntimeException("Stopped");
        case GetStatus _ -> state;
        default -> state;
    }
);
```

## Pattern 5: Request-Reply with Custom Response Wrapper

For more control over responses, include a reply channel in your message:

```java
record QueryWithReply<T>(
    String question,
    Proc<?, CompletableFuture<T>> replyTo
) implements CounterMsg {}

var counter = new Proc<>(new Counter(0), (state, msg) -> {
    if (msg instanceof QueryWithReply<Integer> q) {
        q.replyTo().tell(CompletableFuture.completedFuture(state.value()));
    }
    return state;
});

// Send query and get response via mailbox
var future = new CompletableFuture<Integer>();
counter.tell(new QueryWithReply<>("value",
    new Proc<>(null, (s, m) -> {
        if (m instanceof CompletableFuture<?> cf) {
            future.complete((Integer) cf.join());
        }
        return s;
    })));

var result = future.join();
```

## Pattern 6: Process Discovery via Registry

Use `ProcessRegistry` to look up processes by name:

```java
import io.github.seanchatmangpt.jotp.ProcessRegistry;

// Register a process
ProcessRegistry.register("my-counter", counter);

// Look it up later
var found = ProcessRegistry.whereis("my-counter");
if (found != null) {
    found.tell(new Increment());
}

// Unregister when done
ProcessRegistry.unregister("my-counter");
```

## Pattern 7: Asynchronous Pipeline

Chain operations across multiple processes:

```java
// Fetch data from process A, send to process B, send result to C
var dataFuture = fetcherProcess.ask(new FetchRequest());
dataFuture
    .thenApply(data -> new ProcessRequest(data))
    .thenAccept(processorProcess::tell)
    .join();
```

## Best Practices

1. **Use sealed interfaces for type safety** — catches missing cases at compile time
2. **Always set timeouts on `ask()`** — prevents deadlocks
3. **Keep message handlers pure** — avoid side effects, return new state
4. **Use `tell()` for fire-and-forget** — fastest, most responsive pattern
5. **Name critical processes** — use `ProcessRegistry` for easy lookup

## Further Reading

- [Tutorial 02: Your First Process](../tutorials/02-first-process.md) — Basic examples
- [Reference: ProcessRegistry API](../reference/process-registry.md)
- [How-to: Process Links and Monitors](./process-links.md)
