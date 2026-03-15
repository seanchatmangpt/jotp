# Message Passing in JOTP

## Learning Objectives

By the end of this tutorial, you will be able to:
- Understand how virtual threads enable JOTP's lightweight processes
- Explain mailbox-based message delivery and ordering
- Design message protocols using sealed interfaces
- Implement asynchronous communication patterns
- Build a multi-process chat room application

## Prerequisites

Before starting this tutorial, you should have:
- Completed [First Process](first-process.md) tutorial
- Understanding of `Proc<S, M>` creation and message handlers
- Familiarity with pattern matching in Java 26
- Basic understanding of concurrency concepts

## Table of Contents

1. [Virtual Threads: The Foundation](#virtual-threads-the-foundation)
2. [Mailboxes and Message Delivery](#mailboxes-and-message-delivery)
3. [Message Protocol Design](#message-protocol-design)
4. [Asynchronous Communication](#asynchronous-communication)
5. [Building a Chat Room](#building-a-chat-room)
6. [What You Learned](#what-you-learned)
7. [Next Steps](#next-steps)
8. [Exercise](#exercise)

---

## Virtual Threads: The Foundation

JOTP processes are built on Java 26's **virtual threads** (Project Loom). Understanding virtual threads is key to understanding JOTP's scalability.

### Platform Threads vs Virtual Threads

**Platform Threads (Traditional):**
- 1:1 mapping with OS threads
- Heavyweight (~2 MB stack memory each)
- Limited to thousands of concurrent threads
- Context switching is expensive (OS-level)

**Virtual Threads (JOTP):**
- Many-to-many mapping with OS threads
- Extremely lightweight (~1 KB memory each)
- Can create millions of virtual threads
- Context switching is cheap (JVM-level)

### Why Virtual Threads Matter for JOTP

```java
// Traditional approach: Limited scalability
ExecutorService platformExecutor = Executors.newFixedThreadPool(10);
// Can only run 10 concurrent operations

// JOTP approach: Massive scalability
ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
// Can run MILLIONS of concurrent processes
```

### Virtual Thread Characteristics

1. **Lightweight Creation**
```java
// Creating a virtual thread is nearly free
Thread vt = Thread.startVirtualThread(() -> {
    // Process code here
});
```

2. **Blocking is Cheap**
```java
// In virtual threads, blocking doesn't waste resources
proc.tell(new Message());      // Non-blocking
Thread.sleep(1000);            // Blocking, but OK in virtual threads
String response = proc.ask();  // Blocking, but OK
```

3. **Automatic Scaling**
```java
// The JVM schedules virtual threads onto available platform threads
// No need to manage thread pools manually
```

### JOTP's Use of Virtual Threads

Every `Proc<S, M>` runs on its own virtual thread:

```java
Proc<State, Msg> proc = Proc.spawn(executor, initialState, handler);
// Creates a new virtual thread
// Virtual thread runs a message-processing loop
// Process waits for messages in its mailbox
// Nearly zero overhead
```

**Scalability Example:**
```java
var executor = Executors.newVirtualThreadPerTaskExecutor();
List<Proc<StringBuilder, Message>> processes = new ArrayList<>();

// Can easily create 100,000 processes
for (int i = 0; i < 100_000; i++) {
    processes.add(Proc.spawn(executor, ...));
}
// Each process uses ~1 KB memory
// Total: ~100 MB for 100,000 processes
```

---

## Mailboxes and Message Delivery

Every JOTP process has a **mailbox** - a FIFO queue for incoming messages.

### Mailbox Properties

1. **Message Ordering**
```java
proc.tell(msg1);
proc.tell(msg2);
proc.tell(msg3);
// Messages are processed in order: msg1, then msg2, then msg3
```

2. **Sequential Processing**
```java
// Even if multiple threads send messages simultaneously,
// the process handles them one at a time
Thread t1 = spawn(() -> proc.tell(msgA));
Thread t2 = spawn(() -> proc.tell(msgB));
// Process handles msgA and msgB sequentially, never concurrently
```

3. **Unbounded (Default)**
```java
// Mailboxes grow as needed
for (int i = 0; i < 1_000_000; i++) {
    proc.tell(new Message(i));
}
// All 1 million messages are queued
```

### Message Delivery Process

```
Sender Process          Mailbox           Receiver Process
     |                     |                      |
     |-- tell(msg) ------> |                      |
     |                     |    [msg]             |
     |                     |                      |
     |                     |         <--- dequeue |
     |                     |                      |-- handle(msg)
     |                     |                      |-- update state
     |                     |                      |-- wait for next
```

### tell() Internals

```java
public void tell(M message) {
    // 1. Add message to mailbox
    this.mailbox.offer(message);

    // 2. Virtual thread waiting in mailbox.poll() wakes up
    // 3. Message is passed to handler function
    // 4. State is updated
    // 5. Process waits for next message
}
```

### Message Guarantees

1. **At-Most-Once Delivery**
   - Messages are never delivered twice
   - If process crashes, messages in mailbox are lost

2. **Order Preservation**
   - Messages from sender A to process B arrive in order sent
   - Messages from multiple senders are interleaved fairly

3. **No Delivery Acknowledgment**
   - `tell()` returns immediately (fire-and-forget)
   - No guarantee message was processed

### Debugging Mailboxes

```java
// Check mailbox size (not exposed in basic API, but useful concept)
// In production, use monitoring tools
System.out.println("Process has " + mailbox.size() + " pending messages");
```

---

## Message Protocol Design

Message protocols define how processes communicate. Good protocols are:
- **Type-safe**: Compiler validates all messages
- **Clear**: Intent is obvious from message types
- **Extensible**: Easy to add new message types
- **Immutable**: Messages can't be modified after sending

### Protocol Design Patterns

#### Pattern 1: Command Messages

```java
sealed interface CounterCommand permits Increment, Decrement, Reset {
    record Increment() implements CounterCommand {}
    record Decrement() implements CounterCommand {}
    record Reset() implements CounterCommand {}
}
```

**Use when:** Sending commands without expecting responses

#### Pattern 2: Query Messages

```java
sealed interface UserQuery permits GetUser, ListUsers, SearchUsers {
    record GetUser(int userId) implements UserQuery {}
    record ListUsers() implements UserQuery {}
    record SearchUsers(String query) implements UserQuery {}
}
```

**Use when:** Requesting data from a process

#### Pattern 3: Event Messages

```java
sealed interface SystemEvent permits UserLoggedIn, OrderPlaced, PaymentFailed {
    record UserLoggedIn(int userId, java.time.Instant timestamp) implements SystemEvent {}
    record OrderPlaced(String orderId, BigDecimal amount) implements SystemEvent {}
    record PaymentFailed(String transactionId, String reason) implements SystemEvent {}
}
```

**Use when:** Broadcasting notifications

#### Pattern 4: Request-Response Messages

```java
sealed interface DatabaseRequest permits Query, Execute {
    record Query(String sql, Object[] params) implements DatabaseRequest {}
    record Execute(String sql, Object[] params) implements DatabaseRequest {}
}

sealed interface DatabaseResponse permits ResultRow, Error {
    record ResultRow(Map<String, Object> data) implements DatabaseResponse {}
    record Error(String message) implements DatabaseResponse {}
}
```

**Use when:** Two-way communication with `ask()`

### Advanced: Recursive Message Types

```java
sealed interface Expr permits Constant, Add, Multiply {
    record Expr(int value) implements Constant {}
    record Add(Expr left, Expr right) implements Expr {}
    record Multiply(Expr left, Expr right) implements Expr {}
}

// Represents: 2 + (3 * 4)
Expr expression = new Add(
    new Constant(2),
    new Multiply(new Constant(3), new Constant(4))
);
```

**Use when:** Building tree structures, expression evaluators, etc.

### Protocol Best Practices

1. **Use descriptive names**
   ```java
   // Good
   record GetUserProfile(int userId) implements UserQuery {}

   // Bad
   record Get(int id) implements Msg {}
   ```

2. **Include all necessary data**
   ```java
   // Good
   record SendEmail(String to, String subject, String body) implements Command {}

   // Bad - requires additional context
   record SendEmail(String body) implements Command {}
   ```

3. **Use primitive or immutable types**
   ```java
   // Good
   record ProcessOrder(Order order, Instant deadline) implements Command {}

   // Bad - mutable list can be modified after sending
   record ProcessItems(List<Item> items) implements Command {}
   ```

4. **Version protocols carefully**
   ```java
   sealed interface ApiMessage permits V1Message, V2Message {}
   // Support old and new versions during migration
   ```

---

## Asynchronous Communication

JOTP processes communicate **asynchronously** - they don't wait for each other.

### Synchronous vs Asynchronous

**Synchronous (Traditional):**
```java
// Traditional method call - blocks until complete
String result = database.query("SELECT * FROM users");
// Caller waits for database to respond
```

**Asynchronous (JOTP):**
```java
// JOTP tell() - returns immediately
database.tell(new Query("SELECT * FROM users"));
// Caller continues immediately, doesn't wait
```

### Benefits of Asynchrony

1. **Non-blocking**
```java
logger.tell(new LogMessage("Starting operation"));
// Continue immediately, don't wait for logging
processData();
logger.tell(new LogMessage("Finished operation"));
```

2. **Loose Coupling**
```java
// Sender doesn't need to know if receiver is slow
emailService.tell(new SendEmail(user, "Welcome!"));
// EmailService might take 1 second, but sender doesn't care
```

3. **Natural Concurrency**
```java
// These can all happen concurrently
validator.tell(new Validate(order));
inventory.tell(new CheckStock(order));
shipping.tell(new CalculateShipping(order));
```

### Asynchronous Patterns

#### Pattern 1: Fire and Forget

```java
metrics.tell(new RecordMetric("requests", 1));
// No response needed
```

#### Pattern 2: Send and Continue

```java
proc.tell(new StartProcessing(data));
// Continue with other work
doOtherThings();
// Later, check result
proc.tell(new GetStatus());
```

#### Pattern 3: Callback via Message

```java
// Sender tells receiver to send result to callback process
sealed interface TaskMessage permits Execute, Completed {
    record Execute(String task, Proc<Result, ResultMessage> callback) implements TaskMessage {}
    record Completed(Result result) implements ResultMessage {}
}

// In sender
Proc<Result, ResultMessage> callback = Proc.spawn(executor, ...);
worker.tell(new TaskMessage.Execute("process-file", callback));

// Callback process receives result when ready
```

#### Pattern 4: Future-Style Requests

```java
// Use ask() when you need to wait for response
User user = userRegistry.ask(
    new FindUser(userId),
    5,
    TimeUnit.SECONDS
);
// Blocks for up to 5 seconds waiting for response
```

### Handling Responses

**Response Messages:**
```java
sealed interface CalculatorResponse permits Success, Error {
    record Success(BigDecimal result) implements CalculatorResponse {}
    record Error(String message) implements CalculatorResponse {}
}
```

**Sender handles response:**
```java
Proc<CalculatorState, CalculatorMessage> calculator = ...;

// Send request
calculator.tell(new CalculatorMessage.Add(2, 3));

// Later, process will send response
// (Implementation depends on your protocol design)
```

---

## Building a Chat Room

Let's build a multi-process chat room demonstrating message passing.

### Architecture

```
UserProcess1    UserProcess2    UserProcess3
     |               |               |
     |---------------|---------------|
                     |
               ChatRoomProcess
                     |
            (Broadcasts messages
             to all users)
```

### Step 1: Define Messages

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import java.util.List;
import java.util.ArrayList;

/**
 * Multi-process chat room demonstrating asynchronous message passing.
 */
public class ChatRoomExample {

    // Chat room messages
    sealed interface ChatRoomMessage permits
        Join,
        Leave,
        Broadcast,
        ListUsers {

        record Join(String username, Proc<UserState, UserMessage> userProc)
            implements ChatRoomMessage {}

        record Leave(String username) implements ChatRoomMessage {}

        record Broadcast(String username, String message) implements ChatRoomMessage {}

        record ListUsers() implements ChatRoomMessage {}
    }

    // User process messages
    sealed interface UserMessage permits
        ReceiveMessage,
        UserJoined,
        UserLeft {

        record ReceiveMessage(String from, String message) implements UserMessage {}

        record UserJoined(String username) implements UserMessage {}

        record UserLeft(String username) implements UserMessage {}
    }

    // User process state
    record UserState(String username) {}
```

### Step 2: Create Chat Room Process

```java
    // Chat room state
    record ChatRoomState(List<String> users) {
        ChatRoomState {
            if (users == null) users = new ArrayList<>();
        }
    }

    private static ChatRoomState handleChatRoom(
        ChatRoomState state,
        ChatRoomMessage msg
    ) {
        switch (msg) {
            case ChatRoomMessage.Join(var username, var userProc) -> {
                var newUsers = new ArrayList<>(state.users());
                newUsers.add(username);
                System.out.println("[CHAT] " + username + " joined");
                // Notify all existing users
                state.users().forEach(user -> {
                    // In real implementation, would send to user processes
                });
                return new ChatRoomState(newUsers);
            }

            case ChatRoomMessage.Leave(var username) -> {
                var newUsers = new ArrayList<>(state.users());
                newUsers.remove(username);
                System.out.println("[CHAT] " + username + " left");
                return new ChatRoomState(newUsers);
            }

            case ChatRoomMessage.Broadcast(var from, var message) -> {
                System.out.println("[CHAT] " + from + ": " + message);
                // Broadcast to all users (implementation detail)
                return state;
            }

            case ChatRoomMessage.ListUsers() -> {
                System.out.println("[CHAT] Users: " + state.users());
                return state;
            }
        }
    }
```

### Step 3: Create User Process

```java
    private static UserState handleUser(
        UserState state,
        UserMessage msg
    ) {
        switch (msg) {
            case UserMessage.ReceiveMessage(var from, var message) -> {
                System.out.println("[" + state.username() + "] " + from + ": " + message);
                return state;
            }

            case UserMessage.UserJoined(var username) -> {
                System.out.println("[" + state.username() + "] *** " + username + " joined");
                return state;
            }

            case UserMessage.UserLeft(var username) -> {
                System.out.println("[" + state.username() + "] *** " + username + " left");
                return state;
            }
        }
    }
```

### Step 4: Main Application

```java
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create chat room
        Proc<ChatRoomState, ChatRoomMessage> chatRoom = Proc.spawn(
            executor,
            new ChatRoomState(new ArrayList<>()),
            ChatRoomExample::handleChatRoom
        );

        System.out.println("=== JOTP Chat Room Started ===\n");

        // Create users
        Proc<UserState, UserMessage> alice = Proc.spawn(
            executor,
            new UserState("Alice"),
            ChatRoomExample::handleUser
        );

        Proc<UserState, UserMessage> bob = Proc.spawn(
            executor,
            new UserState("Bob"),
            ChatRoomExample::handleUser
        );

        Proc<UserState, UserMessage> charlie = Proc.spawn(
            executor,
            new UserState("Charlie"),
            ChatRoomExample::handleUser
        );

        // Users join chat room
        chatRoom.tell(new ChatRoomMessage.Join("Alice", alice));
        Thread.sleep(100);

        chatRoom.tell(new ChatRoomMessage.Join("Bob", bob));
        Thread.sleep(100);

        chatRoom.tell(new ChatRoomMessage.Join("Charlie", charlie));
        Thread.sleep(100);

        // Users send messages
        chatRoom.tell(new ChatRoomMessage.Broadcast("Alice", "Hello everyone!"));
        Thread.sleep(100);

        chatRoom.tell(new ChatRoomMessage.Broadcast("Bob", "Hi Alice!"));
        Thread.sleep(100);

        chatRoom.tell(new ChatRoomMessage.Broadcast("Charlie", "Hey all!"));
        Thread.sleep(100);

        // List users
        chatRoom.tell(new ChatRoomMessage.ListUsers());
        Thread.sleep(100);

        // User leaves
        chatRoom.tell(new ChatRoomMessage.Leave("Bob"));
        Thread.sleep(100);

        chatRoom.tell(new ChatRoomMessage.Broadcast("Alice", "Bye Bob!"));
        Thread.sleep(100);

        // Cleanup
        Thread.sleep(500);
        executor.shutdown();
    }
}
```

### Expected Output

```
=== JOTP Chat Room Started ===

[CHAT] Alice joined
[CHAT] Bob joined
[CHAT] Charlie joined
[CHAT] Alice: Hello everyone!
[CHAT] Bob: Hi Alice!
[CHAT] Charlie: Hey all!
[CHAT] Users: [Alice, Bob, Charlie]
[CHAT] Bob left
[CHAT] Alice: Bye Bob!
```

---

## What You Learned

In this tutorial, you:
- Understood how virtual threads enable JOTP's massive scalability
- Learned how mailboxes provide FIFO message delivery and ordering
- Designed message protocols using sealed interfaces
- Implemented asynchronous communication patterns
- Built a multi-process chat room application

**Key Takeaways:**
- **Virtual threads** are lightweight (~1 KB) and enable millions of processes
- **Mailboxes** provide FIFO, ordered, sequential message processing
- **Sealed interfaces** define type-safe message protocols
- **Asynchronous communication** enables non-blocking, scalable systems
- **Message passing** is the foundation of JOTP concurrency

---

## Next Steps

Continue your JOTP journey:
→ **[State Management](state-management.md)** - Learn patterns for managing immutable state

---

## Exercise

**Task:** Enhance the chat room with:

1. **Private messages**: Add `SendPrivateMessage(String to, String message)` to chat room
2. **Message history**: Track last 10 messages in chat room state
3. **User limit**: Reject join attempts if more than 5 users in room
4. **Kick user**: Add `KickUser(String admin, String targetUser)` message

**Hints:**
- Add new message types to sealed interface
- Store message history: `record ChatRoomState(List<String> users, List<String> history)`
- Use guarded patterns: `case Join(...) when users.size() < 5`
- Implement admin logic (simplified - any user can kick any other)

**Expected behavior:**
```java
chatRoom.tell(new Join("Alice", alice));
chatRoom.tell(new Join("Bob", bob));
chatRoom.tell(new SendPrivateMessage("Alice", "Bob", "Hi Bob privately"));
chatRoom.tell(new Join("Charlie", charlie));
chatRoom.tell(new Join("Dave", dave));
chatRoom.tell(new Join("Eve", eve));
chatRoom.tell(new Join("Frank", frank));
chatRoom.tell(new Join("Grace", grace)); // Should be rejected - too many users
chatRoom.tell(new KickUser("Alice", "Bob"));
chatRoom.tell(new GetMessageHistory());
```

<details>
<summary>Click to see solution</summary>

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.*;
import java.util.concurrent.Executors;

public class EnhancedChatRoom {

    // Enhanced messages
    sealed interface ChatRoomMessage permits
        Join,
        Leave,
        Broadcast,
        SendPrivateMessage,
        ListUsers,
        KickUser,
        GetMessageHistory {

        record Join(String username, Proc<UserState, UserMessage> userProc)
            implements ChatRoomMessage {}

        record Leave(String username) implements ChatRoomMessage {}

        record Broadcast(String username, String message) implements ChatRoomMessage {}

        record SendPrivateMessage(String from, String to, String message)
            implements ChatRoomMessage {}

        record ListUsers() implements ChatRoomMessage {}

        record KickUser(String admin, String targetUser) implements ChatRoomMessage {}

        record GetMessageHistory() implements ChatRoomMessage {}
    }

    sealed interface UserMessage permits
        ReceiveMessage,
        ReceivePrivateMessage,
        UserJoined,
        UserLeft,
        UserKicked {

        record ReceiveMessage(String from, String message) implements UserMessage {}

        record ReceivePrivateMessage(String from, String message) implements UserMessage {}

        record UserJoined(String username) implements UserMessage {}

        record UserLeft(String username) implements UserMessage {}

        record UserKicked(String admin) implements UserMessage {}
    }

    record UserState(String username) {}

    // Enhanced state with history
    record ChatRoomState(
        Map<String, Proc<UserState, UserMessage>> users,
        List<String> messageHistory
    ) {
        ChatRoomState {
            if (users == null) users = new HashMap<>();
            if (messageHistory == null) messageHistory = new ArrayList<>();
        }

        private static final int MAX_HISTORY = 10;
    }

    private static ChatRoomState handleChatRoom(
        ChatRoomState state,
        ChatRoomMessage msg
    ) {
        switch (msg) {
            case ChatRoomMessage.Join(var username, var userProc)
                when state.users().size() < 5 -> {

                var newUsers = new HashMap<>(state.users());
                newUsers.put(username, userProc);

                var newHistory = new ArrayList<>(state.messageHistory());
                newHistory.add(username + " joined");
                if (newHistory.size() > ChatRoomState.MAX_HISTORY) {
                    newHistory.remove(0);
                }

                System.out.println("[CHAT] " + username + " joined (users: " + newUsers.size() + ")");

                // Notify all users
                newUsers.values().forEach(user -> {
                    user.tell(new UserMessage.UserJoined(username));
                });

                return new ChatRoomState(newUsers, newHistory);
            }

            case ChatRoomMessage.Join(var username, var _) -> {
                System.out.println("[CHAT] REJECTED: " + username + " - room full (5 users max)");
                return state;
            }

            case ChatRoomMessage.Leave(var username) -> {
                if (!state.users().containsKey(username)) {
                    System.out.println("[CHAT] ERROR: " + username + " not in room");
                    return state;
                }

                var newUsers = new HashMap<>(state.users());
                newUsers.remove(username);

                var newHistory = new ArrayList<>(state.messageHistory());
                newHistory.add(username + " left");
                if (newHistory.size() > ChatRoomState.MAX_HISTORY) {
                    newHistory.remove(0);
                }

                System.out.println("[CHAT] " + username + " left");

                // Notify all users
                newUsers.values().forEach(user -> {
                    user.tell(new UserMessage.UserLeft(username));
                });

                return new ChatRoomState(newUsers, newHistory);
            }

            case ChatRoomMessage.Broadcast(var from, var message) -> {
                var newHistory = new ArrayList<>(state.messageHistory());
                newHistory.add(from + ": " + message);
                if (newHistory.size() > ChatRoomState.MAX_HISTORY) {
                    newHistory.remove(0);
                }

                System.out.println("[CHAT] " + from + ": " + message);

                // Broadcast to all users
                state.users().values().forEach(user -> {
                    user.tell(new UserMessage.ReceiveMessage(from, message));
                });

                return new ChatRoomState(state.users(), newHistory);
            }

            case ChatRoomMessage.SendPrivateMessage(var from, var to, var message) -> {
                var targetUser = state.users().get(to);
                if (targetUser == null) {
                    System.out.println("[CHAT] ERROR: User " + to + " not found");
                    return state;
                }

                System.out.println("[CHAT] [PRIVATE] " + from + " -> " + to + ": " + message);
                targetUser.tell(new UserMessage.ReceivePrivateMessage(from, message));

                return state;
            }

            case ChatRoomMessage.ListUsers() -> {
                System.out.println("[CHAT] Users: " + new ArrayList<>(state.users().keySet()));
                return state;
            }

            case ChatRoomMessage.KickUser(var admin, var target) -> {
                if (!state.users().containsKey(target)) {
                    System.out.println("[CHAT] ERROR: " + target + " not in room");
                    return state;
                }

                var targetProc = state.users().get(target);
                targetProc.tell(new UserMessage.UserKicked(admin));

                var newUsers = new HashMap<>(state.users());
                newUsers.remove(target);

                var newHistory = new ArrayList<>(state.messageHistory());
                newHistory.add(target + " kicked by " + admin);
                if (newHistory.size() > ChatRoomState.MAX_HISTORY) {
                    newHistory.remove(0);
                }

                System.out.println("[CHAT] " + target + " kicked by " + admin);

                return new ChatRoomState(newUsers, newHistory);
            }

            case ChatRoomMessage.GetMessageHistory() -> {
                System.out.println("[CHAT] Message history:");
                state.messageHistory().forEach(msg -> System.out.println("  - " + msg));
                return state;
            }
        }
    }

    private static UserState handleUser(UserState state, UserMessage msg) {
        switch (msg) {
            case UserMessage.ReceiveMessage(var from, var message) -> {
                System.out.println("[" + state.username() + "] " + from + ": " + message);
                return state;
            }

            case UserMessage.ReceivePrivateMessage(var from, var message) -> {
                System.out.println("[" + state.username() + "] [PRIVATE] " + from + ": " + message);
                return state;
            }

            case UserMessage.UserJoined(var username) -> {
                System.out.println("[" + state.username() + "] *** " + username + " joined");
                return state;
            }

            case UserMessage.UserLeft(var username) -> {
                System.out.println("[" + state.username() + "] *** " + username + " left");
                return state;
            }

            case UserMessage.UserKicked(var admin) -> {
                System.out.println("[" + state.username() + "] *** You were kicked by " + admin);
                return state;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        Proc<ChatRoomState, ChatRoomMessage> chatRoom = Proc.spawn(
            executor,
            new ChatRoomState(new HashMap<>(), new ArrayList<>()),
            EnhancedChatRoom::handleChatRoom
        );

        System.out.println("=== Enhanced JOTP Chat Room ===\n");

        var alice = createUser(executor, "Alice");
        var bob = createUser(executor, "Bob");
        var charlie = createUser(executor, "Charlie");
        var dave = createUser(executor, "Dave");
        var eve = createUser(executor, "Eve");
        var frank = createUser(executor, "Frank");
        var grace = createUser(executor, "Grace");

        // Users join
        chatRoom.tell(new ChatRoomMessage.Join("Alice", alice));
        Thread.sleep(50);
        chatRoom.tell(new ChatRoomMessage.Join("Bob", bob));
        Thread.sleep(50);
        chatRoom.tell(new ChatRoomMessage.Join("Charlie", charlie));
        Thread.sleep(50);
        chatRoom.tell(new ChatRoomMessage.Join("Dave", dave));
        Thread.sleep(50);
        chatRoom.tell(new ChatRoomMessage.Join("Eve", eve));
        Thread.sleep(50);

        // Try to add 6th user (should be rejected)
        chatRoom.tell(new ChatRoomMessage.Join("Frank", frank));
        Thread.sleep(50);

        // Public messages
        chatRoom.tell(new ChatRoomMessage.Broadcast("Alice", "Hello everyone!"));
        Thread.sleep(50);

        // Private message
        chatRoom.tell(new ChatRoomMessage.SendPrivateMessage("Alice", "Bob", "Hi Bob privately"));
        Thread.sleep(50);

        // Get history
        chatRoom.tell(new ChatRoomMessage.GetMessageHistory());
        Thread.sleep(50);

        // Kick user
        chatRoom.tell(new ChatRoomMessage.KickUser("Alice", "Bob"));
        Thread.sleep(50);

        // Try to add Grace (should work now)
        chatRoom.tell(new ChatRoomMessage.Join("Grace", grace));
        Thread.sleep(50);

        Thread.sleep(500);
        executor.shutdown();
    }

    private static Proc<UserState, UserMessage> createUser(
        ExecutorService executor,
        String username
    ) {
        return Proc.spawn(executor, new UserState(username), EnhancedChatRoom::handleUser);
    }
}
```

</details>

---

## Additional Resources

- [Virtual Threads Documentation](https://openjdk.org/jeps/444)
- [Mailbox Queue Implementation](../../../src/main/java/io/github/seanchatmangpt/jotp/Proc.java)
- [Sealed Interface Best Practices](https://openjdk.org/jeps/409)
- [Async Programming Patterns](https://docs.oracle.com/en/java/javase/21/core/asynchronous-programming.html)
