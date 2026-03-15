# Creating Your First JOTP Process

## Learning Objectives

By the end of this tutorial, you will be able to:
- Create a `Proc<S, M>` with custom state and message types
- Define sealed interface message hierarchies
- Implement message handlers using pattern matching
- Use `tell()` for asynchronous message sending
- Use `ask()` for synchronous request-response patterns
- Build a working counter process from scratch

## Prerequisites

Before starting this tutorial, you should have:
- Completed [Getting Started with JOTP](getting-started.md)
- Java 26 installed with preview features enabled
- JOTP built and tests passing
- Basic understanding of lambda expressions and functional interfaces

## Table of Contents

1. [Understanding Proc<S, M>](#understanding-proc-s-m)
2. [Defining Message Types](#defining-message-types)
3. [Creating a Process](#creating-a-process)
4. [Message Handlers with Pattern Matching](#message-handlers-with-pattern-matching)
5. [tell() vs ask() Patterns](#tell-vs-ask-patterns)
6. [Building a Counter Process](#building-a-counter-process)
7. [What You Learned](#what-you-learned)
8. [Next Steps](#next-steps)
9. [Exercise](#exercise)

---

## Understanding Proc<S, M>

`Proc<S, M>` is the heart of JOTP. It represents a lightweight process with:
- **State**: Type `S` - The process's internal data
- **Messages**: Type `M` - Types of messages it can receive
- **Mailbox**: FIFO queue for incoming messages
- **Virtual Thread**: Execution context (extremely lightweight)

### Generic Type Parameters

```java
Proc<S, M>
// S = State type (what the process remembers)
// M = Message type (what the process receives)
```

### Process Lifecycle

1. **Spawn**: Create a process with initial state and message handler
2. **Run**: Process starts on a virtual thread, waiting for messages
3. **Receive**: Messages arrive in the mailbox and are handled sequentially
4. **Update**: Handler processes message and returns new state
5. **Loop**: Process waits for next message (preserving state)

### Key Properties

- **Sequential Message Processing**: Messages are handled one at a time in order
- **State Isolation**: Each process has its own private state
- **No Shared Mutable State**: Processes communicate only via messages
- **Lightweight**: Virtual threads use ~1 KB of memory each

---

## Defining Message Types

JOTP uses **sealed interfaces** to define message types. This provides:
- **Type safety**: Compiler ensures all message types are known
- **Exhaustive matching**: Pattern matching warns if you miss a case
- **Immutability**: Messages are immutable (using records)

### Basic Message Type

```java
sealed interface Message permits MyMessage {
    record MyMessage(String data) implements Message {}
}
```

### Multiple Message Types

```java
sealed interface CounterMessage permits Increment, Decrement, GetValue {
    record Increment() implements CounterMessage {}
    record Decrement() implements CounterMessage {}
    record GetValue() implements CounterMessage {}
}
```

### Messages with Payloads

```java
sealed interface CalculatorMessage permits Add, Multiply {
    record Add(double a, double b) implements CalculatorMessage {}
    record Multiply(double a, double b) implements CalculatorMessage {}
}
```

### Best Practices

1. **Use records** for message implementations (immutable, compact)
2. **Name messages as verbs** (Increment, SendEmail, ProcessOrder)
3. **Include necessary data** in message payloads
4. **Keep messages simple** - they should be data, not behavior
5. **Use primitive types or other records** for payload fields

---

## Creating a Process

### The spawn() Method

`Proc.spawn()` creates a new process:

```java
Proc<S, M> spawn(
    ExecutorService executor,    // Virtual thread executor
    S initialState,               // Starting state
    BiFunction<S, M, S> handler   // Message handler
)
```

### Example: Creating a Simple Process

```java
import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.Executors;

// Define state and messages
record CounterState(int value) {}
sealed interface CounterMsg permits Increment {}
record Increment() implements CounterMsg {}

public class CreateProcessExample {
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create process
        Proc<CounterState, CounterMsg> counter = Proc.spawn(
            executor,
            new CounterState(0),                    // Initial state: count = 0
            (state, msg) -> {
                switch (msg) {
                    case Increment() -> {
                        return new CounterState(state.value() + 1);
                    }
                }
                return state;
            }
        );

        System.out.println("Process created: " + counter);

        executor.shutdown();
    }
}
```

### Process Lifecycle Methods

```java
Proc<StringBuilder, Message> proc = Proc.spawn(...);

// Get process identifier
ProcRef<StringBuilder, Message> ref = proc.ref();

// Check if process is alive
boolean alive = proc.isAlive();

// Stop the process
proc.stop();
```

---

## Message Handlers with Pattern Matching

### Handler Signature

```java
BiFunction<S, M, S> handler
```

A handler receives:
- `S state` - Current process state
- `M msg` - Message to process
- Returns `S` - New state (or unchanged state)

### Pattern Matching Basics

Java 26's pattern matching for switch makes message handlers elegant:

```java
(state, msg) -> {
    switch (msg) {
        case MessageA(var data) -> {
            // Extract 'data' from MessageA
            return newState;
        }
        case MessageB(var x, var y) -> {
            // Extract 'x' and 'y' from MessageB
            return newState;
        }
    }
}
```

### Guarded Patterns (When Clauses)

```java
(state, msg) -> {
    switch (msg) {
        case ProcessAmount(int amount) when amount > 0 -> {
            // Only match if amount > 0
            return processPositive(state, amount);
        }
        case ProcessAmount(int amount) -> {
            // Handle non-positive amounts
            return handleInvalid(state, amount);
        }
    }
}
```

### Exhaustive Matching

The compiler ensures you handle all message types:

```java
sealed interface Message permits A, B, C {}

// This will NOT compile - missing case C:
(state, msg) -> {
    switch (msg) {
        case A() -> { /* ... */ }
        case B() -> { /* ... */ }
        // Error: switch expression does not cover all possible inputs
    }
}
```

### Handler Best Practices

1. **Keep handlers pure** - Don't cause side effects outside the state
2. **Return new state objects** - Don't mutate existing state
3. **Use records for state** - Immutable and compact
4. **Handle all cases** - Let the compiler enforce completeness
5. **Extract complex logic** - Move business logic to separate methods

---

## tell() vs ask() Patterns

JOTP supports two communication patterns:

### tell(): Fire and Forget

**Asynchronous** - Send message without waiting for response.

```java
proc.tell(new Increment());
// Message sent, continues immediately
// No return value, no waiting
```

**Characteristics:**
- Non-blocking
- No response expected
- Fastest option
- Use for: Notifications, events, commands without responses

**Example:**
```java
logger.tell(new LogMessage("INFO", "Application started"));
eventBus.tell(new UserLoggedInEvent(userId));
metrics.tell(new RecordMetric("requests", 1));
```

### ask(): Request-Response

**Synchronous** - Send message and wait for response.

```java
String response = proc.ask(new GetValue(), 1, TimeUnit.SECONDS);
// Waits up to 1 second for response
// Returns the response value
```

**Characteristics:**
- Blocking (with timeout)
- Response expected
- Slightly slower (synchronization overhead)
- Use for: Queries, configuration requests, state reads

**Example:**
```java
int count = counter.ask(new GetValue(), 5, TimeUnit.SECONDS);
User user = userRegistry.ask(new FindUser(id), 2, TimeUnit.SECONDS);
```

### Choosing Between tell() and ask()

| Factor | tell() | ask() |
|--------|--------|-------|
| Response needed? | No | Yes |
| Blocking? | No | Yes (with timeout) |
| Speed | Fastest | Slightly slower |
| Use case | Events, commands | Queries, reads |
| Failure mode | Silent | TimeoutException |

### ask() Implementation Pattern

To support `ask()`, your process needs to return response values:

```java
sealed interface CounterMessage permits Increment, GetValue {
    record Increment() implements CounterMessage {}
    record GetValue() implements CounterMessage {}
}

Proc<CounterState, CounterMessage> counter = Proc.spawn(
    executor,
    new CounterState(0),
    (state, msg) -> {
        switch (msg) {
            case CounterMessage.Increment() -> {
                return new CounterState(state.value() + 1);
            }
            case CounterMessage.GetValue() -> {
                // For ask(), we return the value as state
                // (Process will extract and return it)
                return state; // Value returned via Proc mechanism
            }
        }
        return state;
    }
);

// Using ask()
int value = counter.ask(new GetValue(), 1, TimeUnit.SECONDS);
```

---

## Building a Counter Process

Let's build a complete counter process that demonstrates all concepts.

### Step 1: Define State and Messages

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A counter process that maintains a mutable integer counter.
 * Demonstrates state management, message handling, and ask/tell patterns.
 */
public class CounterProcessExample {

    // Immutable state using record
    record CounterState(int value) {}

    // Sealed interface for all message types
    sealed interface CounterMessage permits
        Increment,
        Decrement,
        GetValue,
        SetValue,
        Reset {

        record Increment() implements CounterMessage {}
        record Decrement() implements CounterMessage {}
        record GetValue() implements CounterMessage {}
        record SetValue(int newValue) implements CounterMessage {}
        record Reset() implements CounterMessage {}
    }
```

### Step 2: Create the Process

```java
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Spawn counter process with initial value 0
        Proc<CounterState, CounterMessage> counter = Proc.spawn(
            executor,
            new CounterState(0),
            CounterProcessExample::handleMessage
        );

        System.out.println("Counter process created");
```

### Step 3: Implement Message Handler

```java
    private static CounterState handleMessage(CounterState state, CounterMessage msg) {
        switch (msg) {
            case CounterMessage.Increment() -> {
                int newValue = state.value() + 1;
                System.out.println("Incremented to: " + newValue);
                return new CounterState(newValue);
            }

            case CounterMessage.Decrement() -> {
                int newValue = state.value() - 1;
                System.out.println("Decremented to: " + newValue);
                return new CounterState(newValue);
            }

            case CounterMessage.GetValue() -> {
                // For ask() requests, return state as-is
                // (Proc mechanism extracts and returns value)
                System.out.println("Getting value: " + state.value());
                return state;
            }

            case CounterMessage.SetValue(int newValue) -> {
                System.out.println("Setting value to: " + newValue);
                return new CounterState(newValue);
            }

            case CounterMessage.Reset() -> {
                System.out.println("Resetting to 0");
                return new CounterState(0);
            }
        }
    }
```

### Step 4: Send Messages

```java
        // Use tell() for commands (fire and forget)
        counter.tell(new CounterMessage.Increment());
        counter.tell(new CounterMessage.Increment());
        counter.tell(new CounterMessage.Increment());
        Thread.sleep(100); // Give time for processing

        // Use ask() for queries (request-response)
        // Note: For this example, we'll use a polling approach
        // since ask() requires special response handling
        counter.tell(new CounterMessage.GetValue());
        Thread.sleep(100);

        // More commands
        counter.tell(new CounterMessage.Decrement());
        counter.tell(new CounterMessage.SetValue(100));
        counter.tell(new CounterMessage.GetValue());
        Thread.sleep(100);

        // Reset
        counter.tell(new CounterMessage.Reset());
        counter.tell(new CounterMessage.GetValue());
        Thread.sleep(100);

        executor.shutdown();
    }
}
```

### Expected Output

```
Counter process created
Incremented to: 1
Incremented to: 2
Incremented to: 3
Getting value: 3
Decremented to: 2
Setting value to: 100
Getting value: 100
Resetting to 0
Getting value: 0
```

---

## What You Learned

In this tutorial, you:
- Understood the `Proc<S, M>` abstraction with state and message types
- Defined sealed interface message hierarchies
- Implemented message handlers using pattern matching
- Learned the difference between `tell()` and `ask()` patterns
- Built a complete counter process with multiple message types
- Practiced immutable state management with records

**Key Takeaways:**
- **Sealed interfaces** provide type-safe message protocols
- **Pattern matching** makes message handlers elegant and exhaustive
- **tell()** is for fire-and-forget commands (fast, async)
- **ask()** is for request-response queries (blocking, with timeout)
- **Records** are ideal for immutable state
- **Virtual threads** make processes extremely lightweight

---

## Next Steps

Continue your JOTP journey:
→ **[Message Passing](message-passing.md)** - Deep dive into mailboxes, virtual threads, and async communication

---

## Exercise

**Task:** Enhance the counter process with:

1. **Add message type**: `Add(int amount)` - Add arbitrary amount (can be negative)
2. **Add message type**: `Multiply(int factor)` - Multiply current value
3. **Add validation**: Reject multiplication by zero with an error message
4. **Add query**: `GetHistory()` - Return list of all operations performed

**Hints:**
- Add `record Add(int amount)` to sealed interface
- Store operation history in state: `record CounterState(int value, List<String> history)`
- Use guarded patterns: `case Multiply(int factor) when factor != 0`
- Create a new state record that includes history

**Expected behavior:**
```java
counter.tell(new Add(5));      // 0 -> 5
counter.tell(new Multiply(2)); // 5 -> 10
counter.tell(new Add(-3));     // 10 -> 7
counter.tell(new Multiply(0)); // Error: Cannot multiply by zero
counter.tell(new GetHistory());
// Output: ["Add(5) -> 5", "Multiply(2) -> 10", "Add(-3) -> 7", "Error: factor=0"]
```

<details>
<summary>Click to see solution</summary>

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class EnhancedCounterExample {

    // State with history tracking
    record CounterState(int value, List<String> history) {
        CounterState {
            if (history == null) history = new ArrayList<>();
        }
    }

    // Enhanced message types
    sealed interface CounterMessage permits
        Increment,
        Decrement,
        Add,
        Multiply,
        GetValue,
        GetHistory {

        record Increment() implements CounterMessage {}
        record Decrement() implements CounterMessage {}
        record Add(int amount) implements CounterMessage {}
        record Multiply(int factor) implements CounterMessage {}
        record GetValue() implements CounterMessage {}
        record GetHistory() implements CounterMessage {}
    }

    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        Proc<CounterState, CounterMessage> counter = Proc.spawn(
            executor,
            new CounterState(0, new ArrayList<>()),
            EnhancedCounterExample::handleMessage
        );

        // Test enhanced functionality
        counter.tell(new CounterMessage.Add(5));
        counter.tell(new CounterMessage.Multiply(2));
        counter.tell(new CounterMessage.Add(-3));
        counter.tell(new CounterMessage.Multiply(0)); // Error
        counter.tell(new CounterMessage.GetHistory());

        Thread.sleep(500);
        executor.shutdown();
    }

    private static CounterState handleMessage(CounterState state, CounterMessage msg) {
        switch (msg) {
            case CounterMessage.Increment() -> {
                int newValue = state.value() + 1;
                var newHistory = new ArrayList<>(state.history());
                newHistory.add("Increment() -> " + newValue);
                System.out.println("Incremented to: " + newValue);
                return new CounterState(newValue, newHistory);
            }

            case CounterMessage.Decrement() -> {
                int newValue = state.value() - 1;
                var newHistory = new ArrayList<>(state.history());
                newHistory.add("Decrement() -> " + newValue);
                System.out.println("Decremented to: " + newValue);
                return new CounterState(newValue, newHistory);
            }

            case CounterMessage.Add(int amount) -> {
                int newValue = state.value() + amount;
                var newHistory = new ArrayList<>(state.history());
                newHistory.add("Add(" + amount + ") -> " + newValue);
                System.out.println("Added " + amount + ", now: " + newValue);
                return new CounterState(newValue, newHistory);
            }

            case CounterMessage.Multiply(int factor) when factor != 0 -> {
                int newValue = state.value() * factor;
                var newHistory = new ArrayList<>(state.history());
                newHistory.add("Multiply(" + factor + ") -> " + newValue);
                System.out.println("Multiplied by " + factor + ", now: " + newValue);
                return new CounterState(newValue, newHistory);
            }

            case CounterMessage.Multiply(int factor) -> {
                System.out.println("Error: Cannot multiply by zero");
                var newHistory = new ArrayList<>(state.history());
                newHistory.add("Error: Multiply(0) rejected");
                return new CounterState(state.value(), newHistory);
            }

            case CounterMessage.GetValue() -> {
                System.out.println("Current value: " + state.value());
                return state;
            }

            case CounterMessage.GetHistory() -> {
                System.out.println("Operation history:");
                state.history().forEach(op -> System.out.println("  - " + op));
                return state;
            }
        }
    }
}
```

</details>

---

## Additional Resources

- [Proc.java API Documentation](../../../src/main/java/io/github/seanchatmangpt/jotp/Proc.java)
- [Java Pattern Matching](https://openjdk.org/jeps/420)
- [Virtual Threads Guide](https://openjdk.org/jeps/444)
- [Sealed Classes Tutorial](https://openjdk.org/jeps/409)
