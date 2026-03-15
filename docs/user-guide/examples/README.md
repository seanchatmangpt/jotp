# JOTP Code Examples

This directory contains comprehensive code examples demonstrating JOTP (Java OTP) patterns and best practices.

## Overview

JOTP brings Erlang/OTP's battle-tested concurrency patterns to Java 26 using virtual threads, sealed types, and pattern matching. These examples demonstrate:

- **Lightweight Processes**: Millions of concurrent processes (~1 KB heap each)
- **Message Passing**: Lock-free, type-safe communication
- **Supervision Trees**: Hierarchical fault tolerance
- **State Machines**: Complex workflows with sealed transitions
- **Event Management**: Pub/sub for decoupled systems
- **Circuit Breakers**: External service protection

## Example Files

### 1. [Hello World](hello-world.md)
**Difficulty**: Beginner
**Concepts**: Process creation, messaging, shutdown

Demonstrates the simplest possible JOTP process with fire-and-forget and request-reply messaging.

- Creating processes with `Proc.spawn()`
- `tell()` for fire-and-forget messaging
- `ask()` for request-reply with futures
- Graceful shutdown with `stop()`

### 2. [Counter](counter.md)
**Difficulty**: Beginner
**Concepts**: State management, concurrent access

Builds a counter process with immutable state and concurrent message handling.

- Sealed message types for exhaustive pattern matching
- Immutable state with each message
- Concurrent access from multiple threads
- Unit testing patterns for processes

### 3. [Chat Room](chat-room.md)
**Difficulty**: Intermediate
**Concepts**: Multi-process communication, broadcast messaging

Implements a multi-user chat system with user management and message history.

- Multiple processes coordinating through a central manager
- Event-driven architecture
- User join/leave management
- Message broadcasting patterns

### 4. [Echo Server](echo-server.md)
**Difficulty**: Intermediate
**Concepts**: Request/response pattern, client management

Builds a client-server system with statistics tracking and graceful shutdown.

- Request-reply pattern with `ask()`
- Client lifecycle management
- Statistics tracking with atomic counters
- Concurrent client handling
- Timeout management

### 5. [Supervised Worker](supervised-worker.md)
**Difficulty**: Intermediate
**Concepts**: Fault tolerance, crash recovery

Demonstrates supervisor strategies and automatic restart on failures.

- Supervisor configuration (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- Crash detection and automatic restart
- Restart intensity limits
- ChildSpec with restart policies
- Health monitoring

### 6. [State Machine - Order Processing](state-machine-order.md)
**Difficulty**: Advanced
**Concepts**: Complex workflows, state transitions

Implements an order processing system with timeout handling and validation.

- Sealed state types with exhaustive pattern matching
- State-enter callbacks
- Timeout-based automatic transitions
- Event-driven processing
- Error recovery with retry

### 7. [Circuit Breaker Demo](circuit-breaker-demo.md)
**Difficulty**: Advanced
**Concepts**: External service protection, failure detection

Shows circuit breaker patterns for protecting against failing services.

- State transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
- Failure threshold management
- Automatic recovery testing
- Metrics collection
- Concurrent access patterns

### 8. [Distributed Cache](distributed-cache.md)
**Difficulty**: Advanced
**Concepts**: Multi-node systems, data replication

Implements a distributed cache with replication and consistency handling.

- Multi-node coordination
- Data replication strategies
- Eventual consistency
- Failure detection and recovery
- Anti-entropy and read repair

## Running the Examples

### Prerequisites

- **Java 26** with preview features enabled
- **Maven 4** (or use included Maven Wrapper)
- Optional: `mvnd` (Maven Daemon) for faster builds

### Compilation

```bash
# Compile all examples
javac --enable-preview -source 26 \
    -cp target/classes:target/test-classes \
    -d target/examples \
    docs/examples/*.java
```

### Running Individual Examples

```bash
# Hello World
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.HelloWorld

# Counter
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.Counter

# Chat Room
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.ChatRoom

# Echo Server
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.EchoServer

# Supervised Worker
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.SupervisedWorker

# Order Processing State Machine
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.OrderProcessing

# Circuit Breaker Demo
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.CircuitBreakerDemo

# Distributed Cache
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.DistributedCache
```

### Using Maven

If you prefer to use the Maven build system:

```bash
# Place examples in src/main/java/io/github/seanchatmangpt/jotp/examples/
# Then run:
mvnd compile exec:java -Dexec.mainClass="io.github.seanchatmangpt.jotp.examples.HelloWorld"
```

## Learning Path

We recommend progressing through examples in this order:

1. **Hello World** → Understand basic process concepts
2. **Counter** → Learn state management and concurrency
3. **Echo Server** → Explore request/response patterns
4. **Chat Room** → Build multi-process systems
5. **Supervised Worker** → Add fault tolerance
6. **State Machine** → Model complex workflows
7. **Circuit Breaker** → Protect external services
8. **Distributed Cache** → Scale across nodes

## Key JOTP Concepts

### Processes

```java
// Create a process
Proc<S, M> proc = Proc.spawn(initialState, handler);

// Fire-and-forget message
proc.tell(new Message());

// Request-reply with timeout
var future = proc.ask(new Message(), Duration.ofSeconds(5));
var result = future.get(6, TimeUnit.SECONDS);
```

### Supervisors

```java
// Create supervisor with restart strategy
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,  // max restarts
    Duration.ofSeconds(60)  // time window
);

// Supervise a child
var worker = supervisor.supervise("worker-1", initialState, handler);
```

### State Machines

```java
// Create state machine with transitions
var sm = StateMachine.of(
    initialState,
    initialData,
    (state, event, data) -> switch (state) {
        case StateA() -> handleStateA(event, data);
        case StateB() -> handleStateB(event, data);
    }
);
```

### Event Managers

```java
// Start event manager
var mgr = EventManager.start();

// Add handler
mgr.addHandler(new EventHandler());

// Broadcast event
mgr.notify(new MyEvent());
```

### Circuit Breakers

```java
// Create circuit breaker
var cb = CircuitBreaker.create(
    "my-service",
    5,  // max failures
    Duration.ofSeconds(60),  // window
    Duration.ofSeconds(30)   // half-open timeout
);

// Execute protected call
var result = cb.execute(request, service::call);
```

## Performance Characteristics

| Operation | Latency | Throughput | Notes |
|-----------|---------|------------|-------|
| Process Creation | ~1 µs | 1M/sec | Virtual thread startup |
| Message Send (tell) | 50-150 ns | 10M/sec | Lock-free mailbox |
| Message Send (ask) | 100-200 ns | 5M/sec | With future creation |
| State Transition | 1-5 µs | 200K/sec | Pure function application |
| Supervisor Restart | 75-100 ms | 10/sec | With delay to absorb crashes |

## Best Practices

### 1. Use Sealed Types
Always use sealed interfaces for messages and states:

```java
public sealed interface Msg permits Msg.A, Msg.B {
    record A() implements Msg {}
    record B() implements Msg {}
}
```

### 2. Keep State Immutable
Return new state objects instead of mutating:

```java
// Good
return switch (msg) {
    case Increment() -> new State(state.count + 1);
};

// Bad (don't do this)
state.count++;
return state;
```

### 3. Always Use Timeouts
Prevent indefinite blocking:

```java
var result = proc.ask(new Msg(), Duration.ofSeconds(5))
    .get(6, TimeUnit.SECONDS);
```

### 4. Handle Exceptions
Processes crash on unhandled exceptions:

```java
proc.addCrashCallback(() -> {
    logger.error("Process crashed!");
    // Restart, notify, etc.
});
```

### 5. Test Concurrent Access
Verify thread safety:

```java
var threads = IntStream.range(0, 50)
    .mapToObj(i -> Thread.ofVirtual().start(() -> {
        proc.tell(new Msg());
    }))
    .toList();

for (var t : threads) t.join();
```

## Common Pitfalls

### 1. Mutable State in Records
```java
// Bad: Mutable list in record
record State(List<String> items) {}  // items can be mutated!

// Good: Immutable list
record State(List<String> items) {
    State() {
        this(new CopyOnWriteArrayList<>());  // Or use List.of()
    }
}
```

### 2. Blocking in Handlers
```java
// Bad: Long-running operation in handler
(state, msg) -> {
    Thread.sleep(10000);  // Blocks the process!
    return state;
}

// Good: Spawn separate process for blocking work
(state, msg) -> {
    spawnWorker(msg);
    return state;
}
```

### 3. Forgetting Timeouts
```java
// Bad: May block forever
var result = proc.ask(new Msg()).get();

// Good: Always timeout
var result = proc.ask(new Msg(), Duration.ofSeconds(5))
    .get(6, TimeUnit.SECONDS);
```

### 4. Exception Handling
```java
// Bad: Exceptions crash the process
(state, msg) -> {
    throw new RuntimeException("Oops!");  // Process dies!
}

// Good: Return error state or use Result type
(state, msg) -> switch (msg) {
    case Validate(v) -> isValid(v) ? state : new ErrorState("Invalid");
    // ...
};
```

## Further Reading

- [JOTP Book](../book/) - Comprehensive guide
- [Technical Documentation](../) - Architecture and patterns
- [Thesis](../phd-thesis-otp-java26.md) - Formal OTP ↔ Java 26 equivalence proofs
- [API Reference](../../apidocs/) - Complete API documentation

## Contributing

Found a bug or have an improvement? Please:

1. Check existing issues
2. Create a new issue with details
3. Submit a pull request with tests
4. Follow the code style (Google Java Format)

## License

Copyright © 2024 JOTP Project. Licensed under the same terms as JOTP.
