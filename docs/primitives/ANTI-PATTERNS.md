# JOTP Anti-Patterns - Common Mistakes to Avoid

**A comprehensive guide to anti-patterns and common mistakes when using JOTP primitives.**

---

## Table of Contents

1. [Process Anti-Patterns](#process-anti-patterns)
2. [Supervision Anti-Patterns](#supervision-anti-patterns)
3. [Messaging Anti-Patterns](#messaging-anti-patterns)
4. [State Management Anti-Patterns](#state-management-anti-patterns)
5. [Error Handling Anti-Patterns](#error-handling-anti-patterns)
6. [Testing Anti-Patterns](#testing-anti-patterns)
7. [Performance Anti-Patterns](#performance-anti-patterns)

---

## Process Anti-Patterns

### Anti-Pattern 1: Shared Mutable State

**Problem**: Sharing mutable state between processes causes race conditions.

**Don't Do This**:
```java
// BAD: Shared list between processes
var sharedList = new ArrayList<>();

var proc1 = Proc.spawn(state, (s, m) -> {
    sharedList.add(m);  // NOT thread-safe!
    return s;
});

var proc2 = Proc.spawn(state, (s, m) -> {
    sharedList.add(m);  // RACE CONDITION!
    return s;
});

// Both processes mutate sharedList concurrently
// Data corruption, lost updates, unpredictable behavior
```

**Do This Instead**:
```java
// GOOD: Each process has its own immutable state
record State(List<String> items) {}

var proc1 = Proc.spawn(new State(List.of()), (state, msg) -> {
    var newItems = new ArrayList<>(state.items());
    newItems.add(msg.data());
    return new State(Collections.unmodifiableList(newItems));
});

var proc2 = Proc.spawn(new State(List.of()), (state, msg) -> {
    var newItems = new ArrayList<>(state.items());
    newItems.add(msg.data());
    return new State(Collections.unmodifiableList(newItems));
});

// Each process has its own state
// No race conditions
```

**Why It's Bad**:
- Race conditions
- Data corruption
- Unpredictable behavior
- Violates shared-nothing principle

---

### Anti-Pattern 2: Blocking in Message Handlers

**Problem**: Blocking operations in handlers freeze the process.

**Don't Do This**:
```java
// BAD: Blocking I/O in handler
var proc = Proc.spawn(state, (s, m) -> {
    // Blocks for seconds - process can't handle other messages!
    var result = httpClient.get(url);  // BLOCKING CALL
    return handle(s, result);
});

// While processing HTTP request, process can't handle:
// - Other messages (mailbox backs up)
// - Exit signals
// - System requests (getState, etc.)
```

**Do This Instead**:
```java
// GOOD: Offload blocking work to separate process
var worker = Proc.spawn(workerState, workerHandler);
var bridge = Proc.spawn(bridgeState, (state, request) -> {
    // Perform blocking operation in dedicated bridge process
    var result = blockingApiClient.call(request);

    // Reply when done
    request.replyTo().tell(new Response(result));

    return state;
});

// Or use virtual thread for blocking I/O
BiFunction<State, Request, State> asyncHandler = (state, request) -> {
    Thread.ofVirtual().start(() -> {
        var result = httpClient.get(url);
        request.replyTo().tell(new Response(result));
    });
    return state;
};
```

**Why It's Bad**:
- Process becomes unresponsive
- Mailbox backs up
- Delayed crash detection
- Poor throughput

---

### Anti-Pattern 3: Holding Raw Proc References from Supervisors

**Problem**: Raw `Proc` references become stale after restart.

**Don't Do This**:
```java
// BAD: Holding raw Proc from supervisor
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

Proc<WorkerState, WorkerMsg> rawProc = supervisor.supervise("worker", state, handler);

// Later...
rawProc.tell(new WorkMessage());  // Might be stale!

// If worker crashed and restarted, rawProc points to DEAD process
// Messages are lost, no errors reported
```

**Do This Instead**:
```java
// GOOD: Use ProcRef for stable references
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

ProcRef<WorkerState, WorkerMsg> procRef = supervisor.supervise("worker", state, handler);

// Later...
procRef.tell(new WorkMessage());  // Always points to live process

// ProcRef transparently redirects to restarted process
// No stale references
```

**Why It's Bad**:
- Stale references after restart
- Messages lost
- No errors or warnings
- Silent failures

---

### Anti-Pattern 4: Forgetting to Enable Exit Trapping

**Problem**: Link without trapping exits causes unwanted crashes.

**Don't Do This**:
```java
// BAD: Link without trapping exits
var parent = Proc.spawn(parentState, parentHandler);
var child = ProcLink.spawnLink(parent, childState, childHandler);

// If child crashes, parent crashes too!
// No chance to handle the crash gracefully
```

**Do This Instead**:
```java
// GOOD: Enable exit trapping if you want to handle crashes
var parent = Proc.spawn(parentState, parentHandler);
parent.trapExits(true);  // Enable exit trapping

var child = ProcLink.spawnLink(parent, childState, childHandler);

// Parent receives ExitSignal instead of crashing
BiFunction<ParentState, Object, ParentState> parentHandler = (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> {
        if (reason == null) {
            System.out.println("Child stopped normally");
        } else {
            System.err.println("Child crashed: " + reason);
            // Handle crash gracefully
        }
        yield state;
    }
    // ... handle other messages
};
```

**Why It's Bad**:
- Unwanted crash propagation
- No graceful handling
- Cascading failures
- Violates principle of intent

---

## Supervision Anti-Patterns

### Anti-Pattern 1: Infinite Restart Loops

**Problem**: No max restarts limit causes infinite restart loops.

**Don't Do This**:
```java
// BAD: No restart intensity limit
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    Integer.MAX_VALUE,  // No practical limit
    Duration.ofDays(365)
);

// Worker crashes every second due to bug
// Supervisor restarts it forever
// Never gives up, wastes resources, logs fill up
```

**Do This Instead**:
```java
// GOOD: Set reasonable restart limits
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                       // Max 5 restarts
    Duration.ofSeconds(60)   // Within 60 seconds
);

// If worker crashes 5+ times in 60 seconds, supervisor gives up
// Prevents infinite restart loops
// Logs fatal error for investigation
```

**Why It's Bad**:
- Infinite restart loops
- Resource exhaustion
- Log spam
- Never fails fast

---

### Anti-Pattern 2: Wrong Restart Strategy

**Problem**: Using ONE_FOR_ALL for independent workers causes massive restarts.

**Don't Do This**:
```java
// BAD: ONE_FOR_ALL for independent workers
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ALL,  // Wrong choice!
    5, Duration.ofSeconds(60)
);

var worker1 = supervisor.supervise("worker-1", state1, handler1);
var worker2 = supervisor.supervise("worker-2", state2, handler2);
var worker3 = supervisor.supervise("worker-3", state3, handler3);

// If worker1 crashes, ALL workers restart!
// Massive disruption, unnecessary restarts
```

**Do This Instead**:
```java
// GOOD: ONE_FOR_ONE for independent workers
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,  // Correct choice!
    5, Duration.ofSeconds(60)
);

// If worker1 crashes, only worker1 restarts
// Other workers unaffected
// Minimal disruption
```

**Why It's Bad**:
- Massive unnecessary restarts
- Service disruption
- Lost work in unrelated workers
- Poor availability

---

### Anti-Pattern 3: Mutable State in State Factory

**Problem**: Sharing mutable state across restarts causes bugs.

**Don't Do This**:
```java
// BAD: Mutable state in factory
var sharedState = new MutableState();

var spec = new Supervisor.ChildSpec<>(
    "worker",
    () -> sharedState,  // Same instance on every restart!
    handler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);

// Every restart uses the SAME MutableState instance
// Corrupted state carried over from crash
// Race conditions, unpredictable behavior
```

**Do This Instead**:
```java
// GOOD: Fresh state on each restart
var spec = new Supervisor.ChildSpec<>(
    "worker",
    () -> {
        // Create NEW state on each restart
        return new WorkerState(connectToDB());
    },
    handler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);

// Every restart gets fresh, clean state
// No corrupted state carried over
// Clean slate on every restart
```

**Why It's Bad**:
- Corrupted state carried over
- Race conditions
- Unpredictable behavior
- Violates isolation principle

---

### Anti-Pattern 4: Forgetting Shutdown Timeouts

**Problem**: Infinite shutdown timeouts cause hanging shutdowns.

**Don't Do This**:
```java
// BAD: Infinity shutdown for workers
var spec = new Supervisor.ChildSpec<>(
    "worker",
    () -> new WorkerState(),
    handler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Infinity(),  // BAD!
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);

// During shutdown, worker never stops
// Supervisor hangs forever
// Application can't exit
```

**Do This Instead**:
```java
// GOOD: Reasonable shutdown timeout
var spec = new Supervisor.ChildSpec<>(
    "worker",
    () -> new WorkerState(),
    handler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);

// Worker has 5 seconds to shut down gracefully
// After timeout, forcibly interrupted
// Application can exit
```

**Why It's Bad**:
- Hanging shutdowns
- Application can't exit
- Unresponsive during shutdown
- Poor user experience

---

## Messaging Anti-Patterns

### Anti-Pattern 1: Request-Reply Without Timeout

**Problem**: Unbounded request-reply calls can deadlock.

**Don't Do This**:
```java
// BAD: No timeout on request-reply
var proc = ProcRegistry.whereis("service").orElseThrow();
var future = proc.ask(new Request(data));

// What if service never replies?
// Future never completes
// Caller blocks forever
// Deadlock
```

**Do This Instead**:
```java
// GOOD: Always use timeout
var proc = ProcRegistry.whereis("service").orElseThrow();
var future = proc.ask(new Request(data))
    .orTimeout(5, TimeUnit.SECONDS);

try {
    var result = future.get();
    processResult(result);
} catch (TimeoutException e) {
    handleError("Request timed out");
    // Retry, log, alert, etc.
}
```

**Why It's Bad**:
- Deadlocks
- Hanging requests
- Resource leaks
- Poor reliability

---

### Anti-Pattern 2: Fire-and-Forget for Critical Operations

**Problem**: Using tell() for critical operations loses errors.

**Don't Do This**:
```java
// BAD: Fire-and-forget for critical operation
var database = ProcRegistry.whereis("database").orElseThrow();
database.tell(new SaveUser(user));  // No way to know if it succeeded!

// What if database is down?
// What if user is invalid?
// Silent failure
```

**Do This Instead**:
```java
// GOOD: Request-reply for critical operations
var database = ProcRegistry.whereis("database").orElseThrow();
var future = database.ask(new SaveUser(user))
    .orTimeout(5, TimeUnit.SECONDS);

try {
    var result = future.get();
    if (result.isSuccess()) {
        System.out.println("User saved");
    } else {
        handleError(result.error());
    }
} catch (TimeoutException e) {
    handleError("Save timed out");
}
```

**Why It's Bad**:
- Silent failures
- No error visibility
- Can't retry
- Poor reliability

---

### Anti-Pattern 3: Large Messages

**Problem**: Sending large messages hurts performance.

**Don't Do This**:
```java
// BAD: Sending large data structures
var largeData = IntStream.range(0, 1_000_000)
    .mapToObj(i -> new Item(i))
    .toList();

proc.tell(new ProcessLargeData(largeData));  // 10MB+ message!

// Slow message serialization
// Slow message copying
// Slow GC overhead
```

**Do This Instead**:
```java
// GOOD: Send references or IDs
var dataId = dataStore.store(largeData);
proc.tell(new ProcessDataById(dataId));

// Or stream in chunks
proc.tell(new StartStreamingData(dataId));
// Send chunks as separate messages
```

**Why It's Bad**:
- Poor throughput
- High latency
- GC pressure
- Memory spikes

---

### Anti-Pattern 4: Message Ordering Assumptions

**Problem**: Assuming FIFO ordering across multiple senders.

**Don't Do This**:
```java
// BAD: Assuming strict FIFO ordering
var proc1 = Proc.spawn(state, handler);
var proc2 = Proc.spawn(state, handler);
var target = Proc.spawn(targetState, targetHandler);

// Both senders send to same target
proc1.tell(new Message(1));
proc2.tell(new Message(2));
proc1.tell(new Message(3));

// Target receives in FIFO order PER SENDER
// But NOT guaranteed globally across senders
// Target might receive: 1, 3, 2 (FIFO per proc1, then proc2)
```

**Do This Instead**:
```java
// GOOD: Don't assume ordering, or use sequence numbers
record Message(int id, int sequence) {}

proc1.tell(new Message(1, 1));
proc2.tell(new Message(2, 1));
proc1.tell(new Message(3, 2));

// Target reorders by sequence number if needed
// Or use single sender if ordering is critical
```

**Why It's Bad**:
- Incorrect assumptions
- Race conditions
- Subtle bugs
- Hard to reproduce

---

## State Management Anti-Patterns

### Anti-Pattern 1: Mutating State Directly

**Problem**: Direct state mutation breaks immutability.

**Don't Do This**:
```java
// BAD: Mutating state directly
class MutableState {
    private int count;
    private List<String> items = new ArrayList<>();

    // Setters (BAD!)
    void setCount(int c) { this.count = c; }
    void addItem(String item) { this.items.add(item); }
}

var state = new MutableState();

var proc = Proc.spawn(state, (s, m) -> {
    s.setCount(s.getCount() + 1);  // MUTATION!
    s.addItem(m.data());            // MUTATION!
    return s;
});

// State is mutated, not replaced
// Breaks immutability principle
// Race conditions if multiple futures access state
```

**Do This Instead**:
```java
// GOOD: Immutable state
record State(int count, List<String> items) {}

var proc = Proc.spawn(new State(0, List.of()), (state, msg) -> {
    var newCount = state.count() + 1;
    var newItems = new ArrayList<>(state.items());
    newItems.add(msg.data());
    return new State(newCount, Collections.unmodifiableList(newItems));
});

// State is replaced, not mutated
// Immutable
// Thread-safe
```

**Why It's Bad**:
- Race conditions
- Breaks immutability
- Thread-unsafe
- Violates functional principles

---

### Anti-Pattern 2: State Explosion

**Problem**: Storing too much data in process state.

**Don't Do This**:
```java
// BAD: Storing large data in state
record State(
    List<User> allUsers,       // Millions of users!
    List<Order> allOrders,     // Millions of orders!
    List<Product> allProducts  // Millions of products!
) {}

var proc = Proc.spawn(
    new State(loadAllUsers(), loadAllOrders(), loadAllProducts()),
    handler
);

// State is gigabytes
// Every message copies entire state
// Massive memory overhead
// Slow garbage collection
```

**Do This Instead**:
```java
// GOOD: Store IDs or references
record State(
    DataSource userDb,
    DataSource orderDb,
    Cache cache  // Small hot cache only
) {}

var proc = Proc.spawn(
    new State(connectToUserDB(), connectToOrderDB(), new Cache(1000)),
    handler
);

// State is small (KBs, not GBs)
// Load data on-demand from DB
// Cache only hot items
// Fast message processing
```

**Why It's Bad**:
- Memory bloat
- Slow message processing
- GC pressure
- Poor performance

---

### Anti-Pattern 3: State Synchronization

**Problem**: Trying to synchronize state between processes.

**Don't Do This**:
```java
// BAD: Trying to sync state across processes
var sharedState = new AtomicReference<State>();

var proc1 = Proc.spawn(initialState, (s, m) -> {
    var newState = process(s, m);
    sharedState.set(newState);  // Try to "sync" state
    return newState;
});

var proc2 = Proc.spawn(initialState, (s, m) -> {
    // Try to use "synced" state
    var synced = sharedState.get();
    return process(synced, m);
});

// Race conditions
// Eventual consistency issues
// Breaks isolation
// Violates shared-nothing
```

**Do This Instead**:
```java
// GOOD: Each process has its own state
// If they need to share data, use message passing
var proc1 = Proc.spawn(state1, handler1);
var proc2 = Proc.spawn(state2, handler2);

// If proc2 needs proc1's data, ask for it
var future = proc1.ask(new GetDataRequest());
var data = future.get();
proc2.tell(new UpdateWithData(data));

// Clear communication
// No shared mutable state
// Predictable behavior
```

**Why It's Bad**:
- Race conditions
- Complex synchronization
- Breaks isolation
- Violates OTP principles

---

## Error Handling Anti-Patterns

### Anti-Pattern 1: Catching All Exceptions

**Problem**: Catching all exceptions prevents "let it crash".

**Don't Do This**:
```java
// BAD: Catch all exceptions
var proc = Proc.spawn(state, (s, m) -> {
    try {
        return handle(s, m);
    } catch (Exception e) {
        // Catch everything - never crashes
        log.error("Error", e);
        return s;  // Return old state
    }
});

// Process never crashes
- Supervisor never restarts
// Corrupted state persists
// Errors are hidden
```

**Do This Instead**:
```java
// GOOD: Let it crash
var proc = Proc.spawn(state, (s, m) -> {
    // No try-catch - let exceptions propagate
    return handle(s, m);
});

// If handler throws, process crashes
// Supervisor restarts with fresh state
// Errors are visible
// System self-heals
```

**Why It's Bad**:
- Prevents "let it crash"
- Corrupted state persists
- Supervisor can't help
- Errors are hidden

---

### Anti-Pattern 2: Ignoring Result Types

**Problem**: Ignoring `Result.Err` causes silent failures.

**Don't Do This**:
```java
// BAD: Ignoring Result.Err
Result<Data, Error> result = fetchData();

// Don't check result
Data data = result.orElse(defaultData);  // Silently uses default!

// Error is lost
// No logging
// No alerting
// Silent failure
```

**Do This Instead**:
```java
// GOOD: Always handle Result.Err
Result<Data, Error> result = fetchData();

switch (result) {
    case Result.Ok(var data) ->
        processData(data);
    case Result.Err(var error) -> {
        log.error("Failed to fetch data", error);
        alertTeam(error);
        // Retry, fallback, etc.
    }
}
```

**Why It's Bad**:
- Silent failures
- No error visibility
- Can't diagnose issues
- Poor reliability

---

### Anti-Pattern 3: Using Exceptions for Control Flow

**Problem**: Using exceptions for normal flow is inefficient.

**Don't Do This**:
```java
// BAD: Exceptions for control flow
BiFunction<State, Msg, State> handler = (state, msg) -> {
    try {
        if (isInvalid(msg)) {
            throw new ValidationException("Invalid");  // Control flow!
        }
        return process(state, msg);
    } catch (ValidationException e) {
        return handleError(state, e);
    }
};

// Exceptions are slow
// Confuses error handling
// Not "let it crash"
```

**Do This Instead**:
```java
// GOOD: Use Result type for control flow
BiFunction<State, Msg, State> handler = (state, msg) -> {
    var validationResult = validate(msg);

    switch (validationResult) {
        case Result.Ok(var valid) ->
            return process(state, valid);
        case Result.Err(var error) ->
            return handleError(state, error);
    }
};

// Clear control flow
// Efficient
// Type-safe
```

**Why It's Bad**:
- Slow (exception overhead)
- Confuses real errors
- Inefficient
- Poor code clarity

---

## Testing Anti-Patterns

### Anti-Pattern 1: Thread.sleep() for Timing

**Problem**: Using sleep() makes tests slow and flaky.

**Don't Do This**:
```java
// BAD: Thread.sleep() in tests
@Test
void testCounter() {
    var counter = Proc.spawn(new CounterState(0), handler);
    counter.tell(new Increment());

    Thread.sleep(1000);  // HOPE it's done by now

    var state = ProcSys.getState(counter).join();
    assertThat(state.count()).isEqualTo(1);
}

// Tests are slow (1 second per test)
// Flaky (might not be long enough)
// Unreliable
```

**Do This Instead**:
```java
// GOOD: Use Awaitility
@Test
void testCounter() {
    var counter = Proc.spawn(new CounterState(0), handler);
    counter.tell(new Increment());

    await().atMost(5, TimeUnit.SECONDS)
        .until(() -> {
            var state = ProcSys.getState(counter).join();
            return state.count() == 1;
        });

    // Test is fast (ms, not seconds)
    // Reliable (explicit condition)
    // Clear intent
}
```

**Why It's Bad**:
- Slow tests
- Flaky tests
- Unreliable
- Arbitrary timeouts

---

### Anti-Pattern 2: Testing Implementation Details

**Problem**: Testing internal implementation breaks encapsulation.

**Don't Do This**:
```java
// BAD: Testing private/internal state
@Test
void testInternalState() {
    var proc = Proc.spawn(initialState, handler);

    // Access internal state via ProcSys
    var internalState = ProcSys.getState(proc).join();

    // Testing internal fields is brittle
    assertThat(internalState.privateField()).isEqualTo(expected);
    // If implementation changes, test breaks
}

// Tests are brittle
// Refactoring is hard
// Tests implementation, not behavior
```

**Do This Instead**:
```java
// GOOD: Test observable behavior
@Test
void testBehavior() {
    var proc = Proc.spawn(initialState, handler);

    // Send request
    var future = proc.ask(new Request(data));

    // Verify response (observable behavior)
    var response = future.join();
    assertThat(response.result()).isEqualTo(expected);

    // Tests behavior, not implementation
    // Refactoring-friendly
    // Clear test intent
}
```

**Why It's Bad**:
- Brittle tests
- Hard to refactor
- Tests implementation, not behavior
- False confidence

---

## Performance Anti-Patterns

### Anti-Pattern 1: Excessive Message Passing

**Problem**: Sending too many messages hurts throughput.

**Don't Do This**:
```java
// BAD: Sending millions of tiny messages
for (int i = 0; i < 10_000_000; i++) {
    proc.tell(new Increment(i));  // 10 million messages!
}

// Each message has overhead (~50-150ns)
// Total overhead: ~1-2 seconds
// Poor throughput
```

**Do This Instead**:
```java
// GOOD: Batch messages
var batchSize = 1000;
for (int batch = 0; batch < 10_000; batch++) {
    var items = new ArrayList<Increment>(batchSize);
    for (int i = 0; i < batchSize; i++) {
        items.add(new Increment(batch * batchSize + i));
    }
    proc.tell(new BatchIncrement(items));
}

// 10K messages instead of 10M
// 1000x less overhead
// Better throughput
```

**Why It's Bad**:
- Poor throughput
- High overhead
- CPU waste
- Slower processing

---

### Anti-Pattern 2: Premature Optimization

**Problem**: Optimizing before measuring.

**Don't Do This**:
```java
// BAD: Optimizing without profiling
// "I think messages are slow, let's optimize"

// Complex batching logic
// Caching everywhere
// Primitive types everywhere
// inline everything

// Code becomes unreadable
// But is it actually faster? Who knows!
```

**Do This Instead**:
```java
// GOOD: Profile first, optimize second

// 1. Profile with JProfiler/VisualVM
// 2. Find actual bottlenecks
// 3. Optimize only the hot paths
// 4. Measure again

// Keep code simple
// Optimize based on data
// Not guesses
```

**Why It's Bad**:
- Wasted effort
- Complex code
- No measurable benefit
- Hard to maintain

---

### Anti-Pattern 3: Ignoring Virtual Thread Best Practices

**Problem**: Using virtual threads for CPU-bound work.

**Don't Do This**:
```java
// BAD: CPU-bound work in virtual thread
var proc = Proc.spawn(state, (s, m) -> {
    // CPU-intensive calculation
    var result = complexCalculation(m.data());  // Takes 100ms!
    return process(s, result);
});

// Virtual threads are for I/O, not CPU work
// Blocks the virtual thread scheduler
// Poor throughput
```

**Do This Instead**:
```java
// GOOD: Use platform threads for CPU-bound work
var executor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors()
);

var proc = Proc.spawn(state, (s, m) -> {
    // Submit CPU work to platform thread pool
    var future = executor.submit(() -> complexCalculation(m.data()));

    // Return immediately, handle result asynchronously
    s.setPendingCalculation(future);
    return s;
});

// Virtual threads for I/O, platform threads for CPU
// Respects virtual thread design
// Better throughput
```

**Why It's Bad**:
- Poor throughput
- Blocks scheduler
- Misuse of virtual threads
- Suboptimal performance

---

## Conclusion

By avoiding these anti-patterns, you can build robust, performant JOTP systems that embody the "let it crash" philosophy. Remember:

**Key Principles**:
1. **No shared mutable state** - Each process has its own immutable state
2. **Let it crash** - Don't catch exceptions, let supervisors handle restarts
3. **Always use timeouts** - Unbounded waits cause deadlocks
4. **Use appropriate patterns** - Match the pattern to the problem
5. **Profile before optimizing** - Measure first, optimize second

**Red Flags to Watch For**:
- Shared mutable state
- Blocking in handlers
- Catching all exceptions
- No timeouts on requests
- Mutable state factories
- Fire-and-forget for critical operations

**Resources**:
- `/docs/primitives/PATTERNS.md` - Correct patterns to use
- `/docs/primitives/REFERENCE.md` - Complete API documentation
- `/docs/primitives/PERFORMANCE.md` - Performance guidelines
