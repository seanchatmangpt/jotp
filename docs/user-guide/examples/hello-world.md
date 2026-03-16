# Hello World - JOTP Process Basics

## Problem Statement

Create a simple JOTP process that demonstrates:
- Process creation and startup
- Message sending (fire-and-forget)
- Message querying (request-reply)
- Graceful process shutdown

## Solution Design

We'll create a simple "Greeter" process that:
1. Maintains a greeting count as state
2. Responds to `Greet` messages by incrementing the count
3. Responds to `GetCount` messages by returning the current count
4. Uses sealed records for type-safe message handling

## Complete Java Code

```java
package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hello World example demonstrating basic JOTP process operations.
 *
 * This example shows:
 * - Creating a process with Proc.spawn()
 * - Fire-and-forget messaging with tell()
 * - Request-reply messaging with ask()
 * - Graceful shutdown with stop()
 */
public class HelloWorld {

    /**
     * Message types for the Greeter process.
     * Using a sealed interface ensures exhaustive pattern matching.
     */
    public sealed interface GreeterMsg permits GreeterMsg.Greet, GreeterMsg.GetCount {
        /**
         * Fire-and-forget message to increment the greeting count.
         * No response is sent back.
         */
        record Greet() implements GreeterMsg {}

        /**
         * Request-reply message to get the current greeting count.
         * The process responds with its current state (the count).
         */
        record GetCount() implements GreeterMsg {}
    }

    /**
     * Main method demonstrating the Greeter process.
     */
    public static void main(String[] args) throws Exception {
        // Create a Greeter process with initial state of 0
        Proc<Integer, GreeterMsg> greeter = Proc.spawn(
            0,  // Initial state: greeting count starts at 0
            (Integer state, GreeterMsg msg) -> {
                // Pattern match on the message type
                return switch (msg) {
                    case GreeterMsg.Greet() -> {
                        System.out.println("Hello, World! (Greeting #" + (state + 1) + ")");
                        yield state + 1;  // Increment count
                    }
                    case GreeterMsg.GetCount() -> state;  // Return current count
                };
            }
        );

        System.out.println("=== JOTP Hello World Example ===\n");

        // Send 3 greeting messages (fire-and-forget)
        System.out.println("Sending 3 greeting messages...");
        greeter.tell(new GreeterMsg.Greet());
        greeter.tell(new GreeterMsg.Greet());
        greeter.tell(new GreeterMsg.Greet());

        // Give the process time to handle messages
        Thread.sleep(100);

        // Query the current count (request-reply)
        System.out.println("\nQuerying greeting count...");
        CompletableFuture<Integer> future = greeter.ask(new GreeterMsg.GetCount());
        Integer count = future.get(1, TimeUnit.SECONDS);
        System.out.println("Current greeting count: " + count);

        // Send more greetings
        System.out.println("\nSending 2 more greetings...");
        greeter.tell(new GreeterMsg.Greet());
        greeter.tell(new GreeterMsg.Greet());

        Thread.sleep(100);

        // Final count query
        System.out.println("\nFinal greeting count:");
        count = greeter.ask(new GreeterMsg.GetCount()).get(1, TimeUnit.SECONDS);
        System.out.println("Total greetings: " + count);

        // Graceful shutdown
        System.out.println("\nShutting down greeter process...");
        greeter.stop();

        System.out.println("=== Example Complete ===");
    }
}
```

## Expected Output

```
=== JOTP Hello World Example ===

Sending 3 greeting messages...
Hello, World! (Greeting #1)
Hello, World! (Greeting #2)
Hello, World! (Greeting #3)

Querying greeting count...
Current greeting count: 3

Sending 2 more greetings...
Hello, World! (Greeting #4)
Hello, World! (Greeting #5)

Final greeting count:
Total greetings: 5

Shutting down greeter process...
=== Example Complete ===
```

## Testing Instructions

### Compile and Run

```bash
# Compile (requires Java 26 with --enable-preview)
cd /Users/sac/jotp
javac --enable-preview -source 26 \
    -cp target/classes:target/test-classes \
    -d target/examples \
    docs/examples/HelloWorld.java

# Run
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.HelloWorld
```

### Using Maven

If you prefer to use Maven's build system:

```bash
# Place the file in: src/main/java/io/github/seanchatmangpt/jotp/examples/HelloWorld.java
# Then compile and run with:
mvnd compile exec:java -Dexec.mainClass="io.github.seanchatmangpt.jotp.examples.HelloWorld"
```

## Variations and Extensions

### 1. Add Message History

Track and return all greeting messages:

```java
record GreetState(int count, List<String> history) {}

var greeter = Proc.spawn(
    new GreetState(0, new ArrayList<>()),
    (GreetState state, GreeterMsg msg) -> switch (msg) {
        case GreeterMsg.Greet() -> {
            String greeting = "Hello #" + (state.count + 1);
            var newHistory = new ArrayList<>(state.history);
            newHistory.add(greeting);
            System.out.println(greeting);
            yield new GreetState(state.count + 1, newHistory);
        }
        case GreeterMsg.GetCount() -> state;
    }
);
```

### 2. Add Timeout Handling

Use timed ask to prevent blocking:

```java
// Ask with 2-second timeout
Integer count = greeter
    .ask(new GreeterMsg.GetCount(), java.time.Duration.ofSeconds(2))
    .get(3, TimeUnit.SECONDS);
```

### 3. Multiple Process Types

Create different greeter behaviors:

```java
sealed interface GreeterMsg permits FormalGreet, CasualGreet, GetCount {
    record FormalGreet() implements GreeterMsg {}
    record CasualGreet() implements GreeterMsg {}
}

var formalGreeter = Proc.spawn(0, (state, msg) -> switch (msg) {
    case FormalGreet() -> {
        System.out.println("Good day to you, sir/madam!");
        yield state + 1;
    }
    default -> state;
});
```

### 4. Process Monitoring

Add a callback to detect crashes:

```java
var greeter = Proc.spawn(0, handler);
greeter.addCrashCallback(() -> {
    System.err.println("Greeter process crashed!");
    // Could restart, log metrics, send alerts, etc.
});
```

## Related Patterns

- **Counter Example**: More complex state management
- **Echo Server**: Request-response pattern with multiple clients
- **Supervised Worker**: Adding fault tolerance with supervisors
- **Event Manager**: Pub/sub messaging for multiple processes

## Key JOTP Concepts Demonstrated

1. **Proc.spawn()**: Factory method for creating processes (preferred over constructor)
2. **Sealed Interfaces**: Type-safe message protocols with exhaustive pattern matching
3. **tell()**: Fire-and-forget messaging (asynchronous, no response)
4. **ask()**: Request-reply messaging (synchronous, returns future with state)
5. **State Immutability**: Each message returns a new state (functional programming style)
6. **Virtual Threads**: Processes run on lightweight virtual threads (~1 KB heap each)
7. **Graceful Shutdown**: stop() interrupts the process and waits for termination

## Performance Characteristics

- **Process Creation**: ~1 µs (virtual thread startup)
- **Message Latency**: 50-150 ns round-trip (lock-free mailbox)
- **Memory per Process**: ~1 KB heap (virtual thread + mailbox)
- **Throughput**: Millions of messages per second per process

## Common Pitfalls

1. **Mutable State**: Don't use mutable collections as state - create new instances
2. **Blocking Operations**: Avoid long-running operations in the handler
3. **Forgetting Timeouts**: Always use timeouts on ask() to prevent deadlocks
4. **Exception Handling**: Unhandled exceptions crash the process (supervisors can restart)
