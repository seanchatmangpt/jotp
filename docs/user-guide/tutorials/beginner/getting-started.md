# Getting Started with JOTP

## Learning Objectives

By the end of this tutorial, you will be able to:
- Set up a Java 26 development environment with preview features
- Build JOTP from source using Maven
- Run your first JOTP process
- Execute the test suite to verify your installation
- Create a simple "Hello World" JOTP application

## Prerequisites

Before starting this tutorial, ensure you have:
- Basic familiarity with Java programming
- Understanding of object-oriented programming concepts
- Familiarity with using a terminal/command line
- A code editor (VS Code, IntelliJ IDEA, or similar)

## Table of Contents

1. [Installing Java 26](#installing-java-26)
2. [Installing Maven](#installing-maven)
3. [Building JOTP](#building-jotp)
4. [Running Tests](#running-tests)
5. [Your First JOTP Process](#your-first-jotp-process)
6. [Understanding the Example](#understanding-the-example)
7. [What You Learned](#what-you-learned)
8. [Next Steps](#next-steps)
9. [Exercise](#exercise)

---

## Installing Java 26

JOTP requires Java 26 with preview features enabled. Java 26 introduces Project Loom's virtual threads, which JOTP uses to create lightweight, scalable processes.

### Installing OpenJDK 26

**On macOS with Homebrew:**
```bash
brew install --openjks@26
```

**On Linux (Ubuntu/Debian):**
```bash
sudo apt update
sudo apt install openjdk-26-jdk
```

**On Windows:**
Download from [Adoptium](https://adoptium.net/) or [Oracle](https://www.oracle.com/java/technologies/downloads/)

### Verifying Your Installation

After installation, verify Java 26 is installed:

```bash
java -version
```

Expected output:
```
openjdk version "26.0.0" 2025-03-18
OpenJDK Runtime Environment (build 26.0.0+36)
OpenJDK 64-Bit Server VM (build 26.0.0+36, mixed mode, sharing)
```

### Understanding Preview Features

Java 26 includes preview features - new APIs and language changes that aren't yet finalized. JOTP uses several of these:
- **Virtual Threads** (Project Loom): Lightweight threads that JOTP uses for processes
- **Pattern Matching for switch**: More expressive message handling
- **Sealed Classes**: Type-safe message protocols

You must enable preview features when running Java:
```bash
java --enable-preview ...
```

---

## Installing Maven

JOTP uses Maven 4 for build automation. Maven handles dependencies, compilation, testing, and packaging.

### Installing Maven

**On macOS with Homebrew:**
```bash
brew install maven
```

**On Linux (Ubuntu/Debian):**
```bash
sudo apt install maven
```

**On Windows:**
Download from [Maven's website](https://maven.apache.org/download.cgi)

### Using the Included Maven Wrapper

JOTP includes a Maven wrapper (`mvnw`) that downloads and uses the correct Maven version automatically. You don't need to install Maven separately - just use the wrapper:

```bash
./mvnw --version
```

This is the recommended approach for consistency.

---

## Building JOTP

Let's clone the repository and build JOTP from source.

### Step 1: Clone the Repository

```bash
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp
```

### Step 2: Compile the Project

Use Maven to compile JOTP:

```bash
./mvnw compile
```

This command:
- Downloads all dependencies
- Compiles Java source files with Java 26 preview features
- Creates `.class` files in `target/classes/`

**Expected output:**
```
[INFO] Scanning for projects...
[INFO] Building JOTP 1.0.0
[INFO] --------------------------------[ jar ]--------------------------------
[INFO] --- compiler:3.13.0:compile (default-compile) @ jotp ---
[INFO] Changes detected - recompiling the module!
[INFO] Compiling 150 source files with javac --enable-preview to target/classes
[INFO] BUILD SUCCESS
```

### Step 3: Package as JAR

To create a distributable JAR file:

```bash
./mvnw package
```

This creates `target/jotp-1.0.0.jar` containing all compiled classes.

---

## Running Tests

JOTP has comprehensive tests. Running them verifies your installation works correctly.

### Run All Tests

```bash
./mvnw test
```

This runs all unit tests. You should see output like:
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running io.github.seanchatmangpt.jotp.ProcTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Run a Specific Test Class

To run tests for a specific component:

```bash
./mvnw test -Dtest=ProcTest
```

Common test classes:
- `ProcTest` - Core process functionality
- `SupervisorTest` - Supervision trees
- `StateMachineTest` - State machines
- `EventManagerTest` - Event broadcasting

### Run Full Quality Checks

For complete validation (tests + formatting + documentation):

```bash
./mvnw verify
```

This includes:
- Unit tests
- Integration tests
- Code formatting checks (Spotless with Google Java Format)
- Javadoc validation
- Static analysis

---

## Your First JOTP Process

Let's create a simple "Hello World" JOTP application that demonstrates the core concept: lightweight processes communicating via message passing.

### Step 1: Create the Example File

Create a file `HelloWorldExample.java`:

```java
import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.Executors;

/**
 * A simple "Hello World" JOTP example.
 * Demonstrates creating a process and sending it a message.
 */
public class HelloWorldExample {

    // Define our message type
    sealed interface Message permits SayHello {
        record SayHello(String name) implements Message {}
    }

    public static void main(String[] args) throws Exception {
        // Create a virtual thread executor (required for JOTP processes)
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create a process that handles messages
        Proc<StringBuilder, Message> helloProcess = Proc.spawn(
            executor,
            new StringBuilder(), // Initial state (empty buffer)
            (state, msg) -> {
                // Message handler using pattern matching
                switch (msg) {
                    case Message.SayHello(var name) -> {
                        state.append("Hello, ").append(name).append("!\n");
                        System.out.println(state.toString());
                    }
                }
                return state; // Return updated (or unchanged) state
            }
        );

        // Send a message to the process (asynchronous - fire and forget)
        helloProcess.tell(new Message.SayHello("World"));

        // Wait a moment for the message to be processed
        Thread.sleep(100);

        // Shutdown the executor when done
        executor.shutdown();
    }
}
```

### Step 2: Compile the Example

Save this file in the project root and compile:

```bash
javac --enable-preview -cp target/classes HelloWorldExample.java
```

### Step 3: Run the Example

```bash
java --enable-preview -cp target/classes:. HelloWorldExample
```

**Expected output:**
```
Hello, World!
```

---

## Understanding the Example

Let's break down what just happened:

### 1. Message Types with Sealed Interfaces

```java
sealed interface Message permits SayHello {
    record SayHello(String name) implements Message {}
}
```

- JOTP uses **sealed interfaces** to define message types
- This ensures type safety - the compiler knows all possible messages
- `record` creates immutable data carriers for message payloads

### 2. Creating a Process

```java
Proc<StringBuilder, Message> helloProcess = Proc.spawn(
    executor,
    new StringBuilder(),     // Initial state
    (state, msg) -> { ... }  // Message handler
);
```

`Proc<S, M>` is a generic process:
- `S` is the **state type** (`StringBuilder` here)
- `M` is the **message type** (`Message` here)
- Processes run on **virtual threads** (extremely lightweight)
- Each process has its own **mailbox** for incoming messages

### 3. Message Handler with Pattern Matching

```java
(state, msg) -> {
    switch (msg) {
        case Message.SayHello(var name) -> {
            state.append("Hello, ").append(name).append("!\n");
            System.out.println(state.toString());
        }
    }
    return state;
}
```

- Java 26's **pattern matching** makes message handling elegant
- `case Message.SayHello(var name)` extracts the `name` field
- The handler receives the current state and message
- It returns the (possibly updated) state

### 4. Sending Messages

```java
helloProcess.tell(new Message.SayHello("World"));
```

- `tell()` is **asynchronous** (fire-and-forget)
- The message goes into the process's mailbox
- The process handles it when it gets to that message
- `tell()` returns immediately - no waiting

### 5. Virtual Thread Executor

```java
var executor = Executors.newVirtualThreadPerTaskExecutor();
```

- Virtual threads are lightweight (can create millions)
- JOTP processes run on virtual threads
- This is how JOTP achieves massive concurrency

---

## What You Learned

In this tutorial, you:
- Installed Java 26 with preview features
- Built JOTP from source using Maven
- Ran the test suite to verify your installation
- Created your first JOTP process
- Learned about sealed interfaces, pattern matching, and virtual threads
- Understood the core `Proc<S, M>` abstraction

**Key Takeaways:**
- JOTP brings Erlang/OTP's concurrency model to Java
- Virtual threads enable lightweight, scalable processes
- Message passing is the foundation of JOTP communication
- Type-safe message protocols use sealed interfaces
- Pattern matching makes message handling elegant

---

## Next Steps

Continue your JOTP journey with the next tutorial:
→ **[First Process](first-process.md)** - Deep dive into creating processes, message types, and message handlers

---

## Exercise

**Task:** Modify the `HelloWorldExample` to:
1. Add a second message type `SayGoodbye`
2. Handle both `SayHello` and `SayGoodbye` messages
3. Send multiple messages to demonstrate state accumulation

**Hints:**
- Add `record SayGoodbye(String name) implements Message {}` to the sealed interface
- Add a second case to the switch statement
- Send multiple messages: `helloProcess.tell(...)`

**Expected behavior:**
```
Hello, World!
Hello, JOTP!
Goodbye, World!
```

<details>
<summary>Click to see solution</summary>

```java
import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.Executors;

public class HelloWorldExercise {
    sealed interface Message permits SayHello, SayGoodbye {
        record SayHello(String name) implements Message {}
        record SayGoodbye(String name) implements Message {}
    }

    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        Proc<StringBuilder, Message> helloProcess = Proc.spawn(
            executor,
            new StringBuilder(),
            (state, msg) -> {
                switch (msg) {
                    case Message.SayHello(var name) -> {
                        String greeting = "Hello, " + name + "!\n";
                        state.append(greeting);
                        System.out.print(greeting);
                    }
                    case Message.SayGoodbye(var name) -> {
                        String farewell = "Goodbye, " + name + "!\n";
                        state.append(farewell);
                        System.out.print(farewell);
                    }
                }
                return state;
            }
        );

        helloProcess.tell(new Message.SayHello("World"));
        helloProcess.tell(new Message.SayHello("JOTP"));
        helloProcess.tell(new Message.SayGoodbye("World"));

        Thread.sleep(100);
        executor.shutdown();
    }
}
```

</details>

---

## Troubleshooting

**Problem:** `java.lang.UnsupportedClassVersionError: class file has wrong version 65.0`

**Solution:** You're using an older Java version. Install Java 26 and verify with `java -version`.

**Problem:** `Preview features are not enabled`

**Solution:** Add `--enable-preview` to all `javac` and `java` commands.

**Problem:** Tests fail with `ClassNotFoundException`

**Solution:** Ensure you've run `./mvnw compile` first to build the project.

**Problem:** "Process doesn't seem to be running"

**Solution:** Make sure you created a virtual thread executor and haven't called `shutdown()` too early. Add a `Thread.sleep()` to give the process time to handle messages.

---

## Additional Resources

- [JOTP GitHub Repository](https://github.com/seanchatmangpt/jotp)
- [Java 26 Documentation](https://openjdk.org/projects/jdk/26/)
- [Project Loom: Virtual Threads](https://openjdk.org/jeps/444)
- [JOTP Book](../../book/src/SUMMARY.md)
