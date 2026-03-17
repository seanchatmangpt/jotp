# Video 2: Your First Process (Proc Basics) - Complete Script

**Video ID:** 02
**Duration:** 15 minutes
**Target Audience:** Developers new to the Actor model

---

## Opening Sequence (0:00-0:45)

**[Title Card: Video 2 - Your First Process]**

**Narrator:** Welcome back to the JOTP Video Tutorial Series. In the last video, we learned about the philosophy behind OTP and why JOTP brings these patterns to Java. Today, we're going to get our hands dirty and build our first process.

**[Visual: Simple counter animation - 0, 1, 2, 3...]**

**Narrator:** We'll build a simple counter process that can increment, reset, and report its value. Along the way, you'll learn the core concepts that power all JOTP applications.

---

## Section 1: What is a Process? (0:45-3:45)

**[Visual: Process architecture diagram - mailbox, handler, state]**

**Narrator:** Let's start with the fundamentals. What exactly is a JOTP process?

**[Visual: Virtual thread icon (lightweight, 3.9KB)]**

**Narrator:** A process is a lightweight virtual thread that maintains isolated state. It only uses about 3.9 kilobytes of memory, which means you can create millions of them in a single JVM.

**[Visual: Process anatomy diagram]**

**Narrator:** Every process has three key components:

**[Visual: Highlight "Mailbox" component]**

**Narrator:** First, a mailbox - this is a queue where messages arrive from other processes. Messages are processed one at a time in the order they arrive.

**[Visual: Highlight "State" component]**

**Narrator:** Second, state - this is the process's internal data. It's completely isolated from other processes. No shared state means no race conditions.

**[Visual: Highlight "Handler" component]**

**Narrator:** Third, a handler function - this is a pure function that takes the current state and an incoming message, and returns the new state. The signature is `(State, Message) → State`.

**[Visual: Process lifecycle animation]**

**Narrator:** The process lifecycle is simple. You spawn a process with initial state. It processes messages from its mailbox one by one, updating its state each time. When you're done, you shut it down.

**[Visual: Comparison - Thread vs. Process]**

**Narrator:** Compare this to traditional threads. Threads share memory and require locks. Processes are isolated and communicate through messages. Threads are heavy. Processes are light. Threads are hard to get right. Processes are simple and predictable.

---

## Section 2: Defining State and Messages (3:45-6:45)

**[Code Window: Empty Counter.java file]**

**Narrator:** Let's write some code. We'll create a counter process. First, we need to define our state and our messages.

**[Type code - State definition]**

```java
// State is a simple record with the current count
record Counter(int value) {}
```

**[Visual: Record class explanation]**

**Narrator:** Our state is a record with a single integer value. Records are immutable, which is perfect for process state. The handler returns a new Counter instance each time, and the old one is discarded.

**[Type code - Message hierarchy]**

```java
// Messages form a sealed hierarchy
sealed interface CounterMsg permits Increment, Reset, Snapshot {}

record Increment(int by) implements CounterMsg {}
record Reset() implements CounterMsg {}
record Snapshot() implements CounterMsg {}
```

**[Visual: Sealed interface diagram]**

**Narrator:** Our messages form a sealed hierarchy. This is a Java 16 feature that gives us compiler-enforced exhaustive pattern matching. All message types must be explicitly listed in the permits clause.

**[Visual: Message type cards - Increment, Reset, Snapshot]**

**Narrator:** We have three message types: Increment adds to the counter, Reset sets it back to zero, and Snapshot asks for the current value.

**[Visual: Pattern matching animation]**

**Narrator:** The sealed interface ensures that we can't forget to handle a message type. If we add a new message type later, the compiler will force us to update all our switch statements. This eliminates a whole class of runtime bugs.

**[Visual: Compiler error example - missing case in switch]**

**Narrator:** For example, if we add a new `Decrement` message but forget to handle it, the compiler will complain. This is type safety at compile time.

---

## Section 3: Creating Your First Process (6:45-10:45)

**[Code Window: Continue Counter.java]**

**Narrator:** Now let's create the process itself. We'll use the Proc.spawn() factory method.

**[Type code - Main class with process creation]**

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class CounterDemo {
    public static void main(String[] args) throws Exception {
        // Create a supervisor first (we'll learn more about this later)
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // Spawn a counter process with initial state
        ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
            "counter",
            new Counter(0),
            (state, msg) -> switch (msg) {
                case Increment(var by) -> new Counter(state.value() + by);
                case Reset _           -> new Counter(0);
                case Snapshot _        -> state;
            }
        );
    }
}
```

**[Visual: Code breakdown - Proc.spawn parameters]**

**Narrator:** Let's break this down. We call `supervisor.supervise()` with three parameters:

**[Visual: Highlight "counter" string]**

**Narrator:** First, a name - "counter". This registers the process so we can look it up later. We'll cover the ProcRegistry in a later video.

**[Visual: Highlight "new Counter(0)"]**

**Narrator:** Second, initial state - a Counter with value 0. This is the state the process starts with.

**[Visual: Highlight the handler function]**

**Narrator:** Third, the handler function. This is a BiFunction that takes the current state and an incoming message, and returns the new state. We use a switch expression with pattern matching to handle each message type.

**[Visual: Handler logic animation]**

**Narrator:** Let's look at the handler logic more closely. For `Increment`, we add the value and return a new Counter. For `Reset`, we return a new Counter with value 0. For `Snapshot`, we just return the current state unchanged.

**[Visual: ProcRef return type]**

**Narrator:** The method returns a `ProcRef<Counter, CounterMsg>`. This is a stable reference to the process. Even if the process crashes and restarts, the ProcRef stays valid. Never hold onto a raw Proc - always use ProcRef.

---

## Section 4: Building a Counter Example (10:45-14:15)

**[Code Window: Complete CounterDemo.java]**

**Narrator:** Now let's send some messages to our counter. We'll use both `tell()` and `ask()` methods.

**[Type code - Sending messages]**

```java
// Tell: fire-and-forget
counter.tell(new Increment(5));
counter.tell(new Increment(3));

// Ask: request-reply with timeout
CompletableFuture<Counter> future = counter.ask(
    new Snapshot(),
    Duration.ofSeconds(1)
);

// Block and get the result (for demo purposes)
Counter state = future.get();
System.out.println("Count: " + state.value());  // Count: 8
```

**[Visual: Message flow animation]**

**Narrator:** Let's trace through what happens. We send an Increment(5) message. The process receives it, updates its state to Counter(5), and continues. Then we send Increment(3), and the state becomes Counter(8).

**[Visual: Ask call animation]**

**Narrator:** Then we call `ask()` with a Snapshot message. The `ask()` method sends the message and waits for a response. We provide a timeout of 1 second. The process receives the Snapshot message, returns its current state, and `ask()` completes with that state.

**[Visual: Tell vs. Ask comparison table]**

**Narrator:** The difference between `tell()` and `ask()` is important. `tell()` is fire-and-forget. It returns immediately and doesn't wait for a response. Use this for notifications, events, and commands where you don't need a reply.

**[Visual: Ask timeout animation]**

**Narrator:** `ask()` is request-reply. It sends a message and waits for a response, up to the timeout you specify. If the timeout expires, it throws a TimeoutException. Use this for queries and requests where you need a response.

**[Code Window: Complete working example]**

```java
public class CounterDemo {
    public static void main(String[] args) throws Exception {
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
            "counter",
            new Counter(0),
            (state, msg) -> switch (msg) {
                case Increment(var by) -> new Counter(state.value() + by);
                case Reset _           -> new Counter(0);
                case Snapshot _        -> state;
            }
        );

        // Send messages
        counter.tell(new Increment(5));
        counter.tell(new Increment(3));

        // Query state
        Counter state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
        System.out.println("Count: " + state.value());  // Count: 8

        // Reset and verify
        counter.tell(new Reset());
        state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
        System.out.println("After reset: " + state.value());  // After reset: 0

        // Cleanup
        supervisor.shutdown();
    }
}
```

**[Visual: Console output animation]**

**Narrator:** When we run this, we see the counter increment to 8, then reset to 0. The process handles each message sequentially, updating its state each time.

---

## Section 5: Supervision Basics (14:15-15:00)

**[Visual: Supervisor diagram]**

**Narrator:** You might be wondering why we created a Supervisor before spawning our process. In JOTP, every process should be supervised. This provides automatic crash recovery.

**[Visual: Process crash animation]**

**Narrator:** If our counter process crashes - maybe a bug in our handler, or a NullPointerException - the supervisor will detect the crash and restart the process with its initial state. The whole process takes less than 500 microseconds.

**[Visual: ONE_FOR_ONE strategy diagram]**

**Narrator:** We used the ONE_FOR_ONE strategy, which means only the crashed process is restarted. Other supervised processes are unaffected. We'll dive deeper into supervision strategies in Video 4.

**[Visual: Call to action]**

**Narrator:** For now, just remember: always supervise your processes. It gives you fault tolerance for free.

**[End card: Next Video - Messaging Patterns]**

**Narrator:** In the next video, we'll explore messaging patterns in depth and learn when to use tell vs. ask, how to handle timeouts, and how to build multi-process workflows. See you there.

---

## Visual Aids Specification

### Diagram 1: Process Architecture
**Type:** Static diagram with animated reveals
**Duration:** 30 seconds
**Description:**
- Three boxes arranged vertically: Mailbox → Handler → State
- Mailbox: Queue icon with messages (envelopes)
- Handler: Gear icon with function notation f(state, msg)
- State: Database icon with value
- Animated message flow: Envelope enters mailbox → Handler processes → State updates

### Diagram 2: Sealed Interface Pattern Matching
**Type:** Animated code visualization
**Duration:** 20 seconds
**Description:**
- Show sealed interface declaration
- Highlight "permits" clause
- Show each implementing record
- Animate switch expression with pattern matching
- Show compiler error for missing case

### Diagram 3: Tell vs. Ask Comparison
**Type:** Split-screen animation
**Duration:** 25 seconds
**Description:**
- Left side: tell() - envelope flies one way, returns immediately
- Right side: ask() - envelope flies, waits, response flies back
- Color coding: tell() in blue, ask() in green
- Timeout animation for ask()

### Diagram 4: Supervisor Restart Animation
**Type:** Animated diagram
**Duration:** 15 seconds
**Description:**
- Supervisor box at top
- Counter process box below
- Counter process explodes (crash)
- Supervisor detects (pulse animation)
- Counter process reappears (restart)
- Timer shows "200 µs"

---

## Code Demo Specification

### Demo 1: Complete Counter Process
**Duration:** 8 minutes
**File:** CounterDemo.java
**Sections:**
1. Import statements (30 seconds)
2. State and message definitions (2 minutes)
3. Process creation with supervisor (3 minutes)
4. Sending messages (tell and ask) (2 minutes)
5. Running and output (30 seconds)

**Key Points to Demonstrate:**
- Live coding with explanations
- Show compiler errors for missing switch cases
- Run the program and show output
- Use IDE features (code completion, type hints)
- Show process isolation (two independent counters)

### Demo 2: Crash and Restart Behavior
**Duration:** 2 minutes (optional)
**File:** CounterCrashDemo.java
**Sections:**
1. Add buggy message handler that throws exception
2. Run and show crash
3. Show supervisor restart
4. Verify process is back with initial state

---

## Quiz Questions

1. **What is the memory footprint of a typical JOTP process?**
   - A) 1 MB
   - B) 3.9 KB
   - C) 100 KB
   - D) 10 KB

   **Answer:** B

2. **Why use sealed interfaces for messages?**
   - A) They're faster than regular interfaces
   - B) They enable compile-time exhaustive pattern matching
   - C) They use less memory
   - D) They're required by Java

   **Answer:** B

3. **What's the difference between tell() and ask()?**
   - A) tell() is faster, ask() is slower
   - B) tell() is fire-and-forget, ask() is request-reply
   - C) tell() requires a supervisor, ask() doesn't
   - D) There's no difference

   **Answer:** B

4. **What happens if an ask() call times out?**
   - A) The process crashes
   - B) It returns null
   - C) It throws a TimeoutException
   - D) It blocks forever

   **Answer:** C

5. **Why should you always supervise processes?**
   - A) It's required by JOTP
   - B) It provides automatic crash recovery
   - C) It makes processes faster
   - D) It reduces memory usage

   **Answer:** B

---

## Production Notes

### Key Phrases to Emphasize
- "Isolated state"
- "Message passing over shared state"
- "Pure function: (State, Message) → State"
- "Fire-and-forget" vs. "Request-reply"
- "Always supervise your processes"

### Code Style
- Use modern Java 26 syntax (pattern matching, switch expressions)
- Keep examples simple and focused
- Use meaningful variable names
- Add inline comments for key concepts

### Tone and Pacing
- Clear, step-by-step explanations
- Pause after each code section
- Speak code clearly (variable names, syntax)
- Encourage viewers to follow along

### Accessibility
- Full captions for code explanations
- Large, readable code font (18pt minimum)
- High contrast syntax highlighting
- Keyboard shortcuts visible on screen

---

## Complete Code Examples

### Example 1: Simple Counter (Complete)
```java
package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

// State: immutable record
record Counter(int value) {}

// Messages: sealed hierarchy
sealed interface CounterMsg permits Increment, Reset, Snapshot {}
record Increment(int by) implements CounterMsg {}
record Reset() implements CounterMsg {}
record Snapshot() implements CounterMsg {}

public class CounterDemo {
    public static void main(String[] args) throws Exception {
        // Create supervisor
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // Spawn counter process
        ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
            "counter",
            new Counter(0),
            (state, msg) -> switch (msg) {
                case Increment(var by) -> new Counter(state.value() + by);
                case Reset _           -> new Counter(0);
                case Snapshot _        -> state;
            }
        );

        // Send messages (fire-and-forget)
        counter.tell(new Increment(5));
        counter.tell(new Increment(3));

        // Query state (request-reply)
        CompletableFuture<Counter> future = counter.ask(
            new Snapshot(),
            Duration.ofSeconds(1)
        );
        Counter state = future.get();
        System.out.println("Count: " + state.value());  // Count: 8

        // Reset
        counter.tell(new Reset());
        state = counter.ask(new Snapshot(), Duration.ofSeconds(1)).get();
        System.out.println("After reset: " + state.value());  // After reset: 0

        // Cleanup
        supervisor.shutdown();
    }
}
```

### Example 2: Crash Demo (Optional)
```java
package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

record Counter(int value) {}
sealed interface CounterMsg permits Increment, Crash, Snapshot {}
record Increment(int by) implements CounterMsg {}
record Crash() implements CounterMsg {}
record Snapshot() implements CounterMsg {}

public class CounterCrashDemo {
    public static void main(String[] args) throws Exception {
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        ProcRef<Counter, CounterMsg> counter = supervisor.supervise(
            "counter",
            new Counter(0),
            (state, msg) -> switch (msg) {
                case Increment(var by) -> new Counter(state.value() + by);
                case Crash _           -> { throw new RuntimeException("Boom!"); }
                case Snapshot _        -> state;
            }
        );

        // Normal operation
        counter.tell(new Increment(5));
        System.out.println("Count: " + counter.ask(new Snapshot(), Duration.ofSeconds(1)).get().value());

        // Crash the process
        System.out.println("Crashing process...");
        counter.tell(new Crash());
        Thread.sleep(100);  // Give supervisor time to restart

        // Process is back with initial state
        System.out.println("After crash: " + counter.ask(new Snapshot(), Duration.ofSeconds(1)).get().value());

        supervisor.shutdown();
    }
}
```

---

## Related Resources

### Documentation
- [Proc API Reference](/docs/reference/api/proc.md)
- [Supervisor API Reference](/docs/reference/api/supervisor.md)
- [Your First Process Tutorial](/docs/user-guide/tutorials/your-first-process.mdx)

### Code Examples
- [Counter Example](/src/main/java/io/github/seanchatmangpt/jotp/examples/CounterDemo.java)
- [Process Examples](/src/main/java/io/github/seanchatmangpt/jotp/examples/)

### Java 26 Features
- [Pattern Matching](https://openjdk.org/projects/jdk/21/)
- [Sealed Types](https://openjdk.org/projects/jdk/17/)
- [Records](https://openjdk.org/projects/jdk/16/)

---

**Script Status:** Ready for Review
**Last Updated:** 2026-03-16
**Video Length:** 15 minutes
**Complexity:** Beginner
**Prerequisites:** Java 26, Maven 4
