# Common JOTP Errors and Solutions

## Table of Contents
- [ClassCastException in Message Handler](#classcastexception-in-message-handler)
- [Process Already Registered](#process-already-registered)
- [Timeout Exceptions](#timeout-exceptions)
- [Link/Monitor Confusion](#linkmonitor-confusion)
- [Mailbox Overflow Exception](#mailbox-overflow-exception)
- [Process Exit Exceptions](#process-exit-exceptions)
- [State Machine Errors](#state-machine-errors)
- [Module System Errors](#module-system-errors)

---

## ClassCastException in Message Handler

### Symptoms
- `ClassCastException` when receiving messages
- Message handler not matching expected type
- Pattern matching fails unexpectedly
- "Cannot cast X to Y" errors

### Root Causes
1. **Missing case in sealed type pattern matching**
2. **Raw message types without type safety**
3. **Inconsistent message protocols**
4. **Generic type erasure issues**

### Diagnosis Steps

1. **Check message type definition:**
```java
// Verify sealed interface
sealed interface MyMsg permits PingMsg, PongMsg {}

// Check all implementations
record PingMsg(int seq) implements MyMsg {}
record PongMsg(int seq) implements MyMsg {}
```

2. **Verify handler pattern matching:**
```java
// Check for exhaustive matching
return switch (msg) {
    case PingMsg p -> handlePing(p);
    case PongMsg p -> handlePong(p);
    // Compiler error if missing case
};
```

3. **Enable type checking:**
```bash
# Compile with all checks
javac -Xlint:all --enable-preview ...
```

### Solutions

#### Solution 1: Use Sealed Interfaces Correctly
**Problem:** Missing permit clause

```java
// Wrong: Missing permits
sealed interface MyMsg {}

// Correct: Include all implementations
sealed interface MyMsg permits PingMsg, PongMsg, AckMsg {}
```

#### Solution 2: Exhaustive Pattern Matching
**Problem:** Missing case in switch

```java
// Wrong: Not exhaustive
return switch (msg) {
    case PingMsg p -> state;
    // Missing PongMsg case - compiler error
};

// Correct: Handle all cases
return switch (msg) {
    case PingMsg p -> handlePing(p, state);
    case PongMsg p -> handlePong(p, state);
    case AckMsg a -> handleAck(a, state);
};
```

#### Solution 3: Type-Safe Message Protocols
**Problem:** Using raw Object type

```java
// Wrong: Raw message type
Proc<Object, Object> proc = Proc.spawn(...);

// Correct: Typed messages
Proc<MyState, MyMsg> proc = Proc.spawn(
    ProcSpec.<MyState, MyMsg>builder()
        .initialState(new MyState())
        .handler((state, msg) -> {
            return switch (msg) {
                case PingMsg p -> /* ... */;
                case PongMsg p -> /* ... */;
            };
        })
        .build()
);
```

#### Solution 4: Generic Type Preservation
**Problem:** Type erasure causing ClassCastException

```java
// Wrong: Generic message type loses information
interface GenericMsg<T> {
    T payload();
}

// Correct: Use specific message types
sealed interface MyMsg permits StringMsg, IntMsg {}
record StringMsg(String payload) implements MyMsg {}
record IntMsg(int payload) implements MyMsg {}
```

#### Solution 5: Message Validation
**Problem:** Invalid message types sent

```java
// Add validation in handler
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .handler((state, msg) -> {
        // Validate message type
        if (!(msg instanceof MyMsg)) {
            log.error("Invalid message type: {}", msg.getClass());
            return state; // or throw
        }

        return switch ((MyMsg) msg) {
            case PingMsg p -> /* ... */;
            case PongMsg p -> /* ... */;
        };
    })
    .build();
```

### Prevention
- Always use sealed interfaces for messages
- Enable exhaustive pattern matching
- Use specific message types, not generics
- Validate message types at boundaries
- Use compiler warnings (`-Xlint:all`)

### Related Issues
- [Message Delivery Issues](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#messages-not-delivered)
- [Testing Issues](/Users/sac/jotp/docs/troubleshooting/testing-issues.md)

---

## Process Already Registered

### Symptoms
- `IllegalStateException: Process already registered`
- `ProcRegistry.register()` fails
- Cannot spawn process with existing name
- Name conflict errors

### Root Causes
1. **Process not properly unregistered**
2. **Supervisor restart creating duplicate names**
3. **Manual name collision**
4. **Previous process not terminated**

### Diagnosis Steps

1. **Check if name exists:**
```java
Optional<Proc<?,?>> existing = ProcRegistry.lookup("my-process");
if (existing.isPresent()) {
    System.err.println("Process already exists: " + existing.get());
}
```

2. **Verify process state:**
```java
Proc<?,?> proc = ProcRegistry.lookup("my-process").orElseThrow();
System.out.println("State: " + proc.state());
System.out.println("Alive: " + proc.isAlive());
```

3. **Check registry contents:**
```java
Set<String> allNames = ProcRegistry.allNames();
System.out.println("Registered: " + allNames);
```

### Solutions

#### Solution 1: Unregister Before Re-registering
```java
// Check and unregister
String name = "my-process";
if (ProcRegistry.lookup(name).isPresent()) {
    ProcRegistry.unregister(name);
}

// Now register
Proc<MyState, MyMsg> proc = Proc.spawn(spec.name(name));
ProcRegistry.register(name, proc);
```

#### Solution 2: Use Auto-Generated Names
```java
// Let JOTP generate unique name
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    // Don't set name - auto-generated
    .initialState(new MyState())
    .handler(handler)
    .build();

Proc<MyState, MyMsg> proc = Proc.spawn(spec);
String generatedName = proc.name();
System.out.println("Generated name: " + generatedName);
```

#### Solution 3: Use ProcRef for Stable References
```java
// ProcRef survives supervisor restarts
ProcRef<MyState, MyMsg> ref = ProcRegistry.register(
    "stable-name",
    proc
);

// Later, after restart:
Proc<MyState, MyMsg> currentProc = ref.get();
// Always points to current process, even after restart
```

#### Solution 4: Fix Supervisor Restart Strategy
**Problem:** Supervisor restarts but old name still registered

```java
// Use unique names for child processes
SupervisorSpec spec = SupervisorSpec.builder()
    .strategy(RestartStrategy.ONE_FOR_ONE)
    .childSpec(ChildSpec.builder()
        .name("worker-" + UUID.randomUUID()) // Unique name
        .factory(() -> new Worker())
        .restartType(RestartType.PERMANENT)
        .build())
    .build();
```

#### Solution 5: Implement Name Collision Handling
```java
public class SafeProcRegistry {
    public static Proc<MyState, MyMsg> registerOrGet(
        String name,
        Supplier<Proc<MyState, MyMsg>> supplier
    ) {
        Optional<Proc<?,?>> existing = ProcRegistry.lookup(name);

        if (existing.isPresent()) {
            Proc<?,?> proc = existing.get();
            if (proc.isAlive()) {
                return (Proc<MyState, MyMsg>) proc;
            } else {
                // Dead process, unregister and recreate
                ProcRegistry.unregister(name);
            }
        }

        // Create and register new process
        Proc<MyState, MyMsg> proc = supplier.get();
        ProcRegistry.register(name, proc);
        return proc;
    }
}
```

### Prevention
- Use unique process names
- Implement proper cleanup on termination
- Use ProcRef for stable references
- Check registry before registering
- Use UUIDs for dynamic names

### Related Issues
- [Process Won't Start](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#process-wont-start)
- [Supervision Issues](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md)

---

## Timeout Exceptions

### Symptoms
- `TimeoutException` in `ask()` calls
- `ask()` never returns
- Processes hanging indefinitely
- Request-response pattern failures

### Root Causes
1. **No timeout specified**
2. **Receiver process crashed**
3. **Receiver mailbox full**
4. **Message handler blocked**
5. **Wrong process reference**

### Diagnosis Steps

1. **Check if receiver is alive:**
```java
if (!receiver.isAlive()) {
    System.err.println("Receiver is dead!");
}
```

2. **Check mailbox status:**
```java
ProcInspection inspect = ProcSys.inspect(receiver.self());
System.out.println("Mailbox: " + inspect.mailboxSize());
```

3. **Enable message tracing:**
```java
DebugOptions options = DebugOptions.builder()
    .traceMessages(true)
    .logDelivery(true)
    .build();
```

### Solutions

#### Solution 1: Always Use Timeouts
**Problem:** Missing timeout causes hang

```java
// Wrong: No timeout - potential deadlock
Response res = proc.ask(new Request());

// Correct: Always specify timeout
try {
    Response res = proc.ask(new Request(), Duration.ofSeconds(5));
} catch (TimeoutException e) {
    log.error("Ask timeout after 5s");
    // Handle timeout
}
```

#### Solution 2: Handle Timeout Gracefully
```java
Response res = proc.ask(new Request(), Duration.ofSeconds(5))
    .orElseGet(() -> {
        // Fallback response
        log.warn("Using fallback response");
        return new DefaultResponse();
    });

// Or use exception handling
try {
    Response res = proc.ask(new Request(), Duration.ofSeconds(5));
} catch (TimeoutException e) {
    // Escalate to supervisor
    throw new ProcessException("Request timeout", e);
}
```

#### Solution 3: Use Circuit Breaker Pattern
```java
public class AskingCircuitBreaker {
    private final CircuitBreaker breaker;

    public Response askWithBreaker(
        Proc<MyState, MyMsg> proc,
        Request req
    ) {
        return breaker.executeSupplier(() -> {
            return proc.ask(req, Duration.ofSeconds(5))
                .orElseThrow(() -> new TimeoutException());
        });
    }
}
```

#### Solution 4: Implement Retry with Backoff
```java
public Response askWithRetry(
    Proc<MyState, MyMsg> proc,
    Request req,
    int maxRetries
) {
    int attempt = 0;
    Duration timeout = Duration.ofSeconds(1);

    while (attempt < maxRetries) {
        try {
            return proc.ask(req, timeout)
                .orElseThrow();
        } catch (TimeoutException e) {
            attempt++;
            if (attempt >= maxRetries) {
                throw new RuntimeException("Max retries exceeded", e);
            }
            // Exponential backoff
            timeout = timeout.multipliedBy(2);
            Thread.sleep(timeout.toMillis());
        }
    }

    throw new RuntimeException("Should not reach here");
}
```

#### Solution 5: Use Async Pattern Instead
**Problem:** Synchronous ask() causes timeouts

```java
// Instead of ask(), use async fire-and-forget
public CompletableFuture<Response> askAsync(
    Proc<MyState, MyMsg> proc,
    Request req
) {
    CompletableFuture<Response> future = new CompletableFuture<>();

    // Create temporary process for response
    Proc<Response, Request> responder = Proc.spawn(
        ProcSpec.<Response, Request>builder()
            .handler((state, msg) -> {
                if (msg instanceof Response res) {
                    future.complete(res);
                    return state;
                }
                return state;
            })
            .build()
    );

    // Send request with reply port
    proc.send(new RequestWithReply(req, responder.self()));

    return future;
}

// Usage
askAsync(proc, request)
    .orTimeout(5, TimeUnit.SECONDS)
    .thenAccept(response -> {
        // Handle response
    });
```

### Prevention
- Always use timeouts on `ask()`
- Implement fallback responses
- Monitor ask latency
- Use circuit breakers
- Consider async patterns
- Set appropriate timeout values

### Related Issues
- [Deadlock Diagnosis](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#deadlock-diagnosis)
- [Messages Not Delivered](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#messages-not-delivered)

---

## Link/Monitor Confusion

### Symptoms
- Processes not propagating crashes
- Unexpected bidirectional termination
- Monitor notifications not received
- Confusion about when to use link vs monitor

### Root Causes
1. **Using link when monitor is appropriate**
2. **Forgetting to trap exit signals**
3. **Monitoring processes that don't exist**
4. **Not handling DOWN messages**

### Diagnosis Steps

1. **Understand the difference:**
```java
// Link: Bidirectional - if either crashes, both die
ProcLink.link(procA, procB);

// Monitor: Unidirectional - get notified if other crashes
ProcMonitor.monitor(procA, procB);
```

2. **Check if monitoring is active:**
```java
Set<ProcId> monitored = ProcMonitor.monitoredProcesses(procA);
System.out.println("Monitoring: " + monitored);
```

3. **Verify exit signal trapping:**
```java
// Check if process traps exits
boolean trapping = proc.trapsExit();
System.out.println("Trapping exits: " + trapping);
```

### Solutions

#### Solution 1: Use Link for Bidirectional Crashes
**Use case:** Parent-child where child crash should kill parent

```java
Proc<MyState, MyMsg> parent = Proc.spawn(parentSpec);
Proc<MyState, MyMsg> child = Proc.spawn(childSpec);

// If child crashes, parent dies too
ProcLink.link(parent, child);

// If parent crashes, child dies too
ProcLink.link(child, parent);
```

#### Solution 2: Use Monitor for Unidirectional Notification
**Use case:** Observer pattern - get notified without dying

```java
Proc<MyState, MyMsg> observer = Proc.spawn(observerSpec);
Proc<MyState, MyMsg> observed = Proc.spawn(observedSpec);

// Observer gets DOWN message if observed crashes
// Observer does NOT crash
ProcMonitor.monitor(observer, observed);

// In observer handler
.handler((state, msg) -> {
    if (msg instanceof ProcMonitor.Down down) {
        log.info("Observed process died: {}", down.pid());
        // Handle notification
    }
    return state;
})
```

#### Solution 3: Trap Exit Signals
**Problem:** Process crashes when linked process dies

```java
// Enable exit signal trapping
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .trapExit(true)  // Trap exit signals
    .handler((state, msg) -> {
        if (msg instanceof ExitSignal signal) {
            log.info("Linked process died: {}", signal.reason());
            // Handle gracefully, don't crash
            return state.withoutLink();
        }
        return state;
    })
    .build();
```

#### Solution 4: Handle DOWN Messages
```Problem:** DOWN messages not processed

```java
// Correct DOWN message handling
.handler((state, msg) -> {
    return switch (msg) {
        case ProcMonitor.Down down -> {
            log.info("Process {} died: {}", down.pid(), down.reason());
            // Remove from monitoring set
            yield state.withoutMonitored(down.pid());
        }
        case OtherMessage m -> handleOther(m, state);
    };
})
```

#### Solution 5: Monitor Decision Tree
```java
public class LinkOrMonitor {
    public static void connect(
        Proc<MyState, MyMsg> observer,
        Proc<MyState, MyMsg> observed,
        boolean bidirectional
    ) {
        if (bidirectional) {
            // Use link if both should crash together
            ProcLink.link(observer, observed);
        } else {
            // Use monitor if observer should survive
            ProcMonitor.monitor(observer, observed);
        }
    }
}

// Usage
// Worker pool - monitor workers, don't crash with them
ProcLink.link(supervisor, worker);

// Observer pattern - just monitor
ProcMonitor.monitor(observer, subject);
```

### Prevention
- Understand semantic difference: link = die together, monitor = notify
- Always handle DOWN messages when monitoring
- Use trapExit when you need to handle exits gracefully
- Document link/monitor relationships
- Test crash propagation

### Related Issues
- [Supervision Issues](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md)
- [Process Exit Exceptions](#process-exit-exceptions)

---

## Mailbox Overflow Exception

### Symptoms
- `MailboxOverflowException`
- Messages silently dropped
- `IllegalStateException: Mailbox full`
- Process becomes unresponsive

### Root Causes
1. **Producer faster than consumer**
2. **Mailbox capacity too small**
3. **Handler blocking on messages**
4. **Message storm (burst of messages)**

### Diagnosis Steps

1. **Check mailbox size:**
```java
ProcInspection inspect = ProcSys.inspect(proc.self());
System.out.println("Mailbox: " + inspect.mailboxSize() + "/" +
    inspect.mailboxCapacity());
```

2. **Monitor message throughput:**
```java
// Add metrics
AtomicLong processed = new AtomicLong(0);
AtomicLong dropped = new AtomicLong(0);
```

3. **Check handler duration:**
```java
Instant start = Instant.now();
// ... handle message
Duration duration = Duration.between(start, Instant.now());
if (duration.toMillis() > 100) {
    log.warn("Slow message: {}ms", duration.toMillis());
}
```

### Solutions

#### Solution 1: Increase Mailbox Capacity
```java
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .name("my-process")
    .mailboxSize(10000) // Increase from default
    .handler(handler)
    .build();
```

#### Solution 2: Implement Backpressure
```java
public void sendWithBackpressure(
    Proc<MyState, MyMsg> proc,
    MyMsg msg
) {
    while (true) {
        ProcInspection inspect = ProcSys.inspect(proc.self());
        if (inspect.mailboxSize() < inspect.mailboxCapacity() * 0.8) {
            proc.send(msg);
            return;
        }
        // Back off
        Thread.sleep(10);
    }
}
```

#### Solution 3: Use Worker Pool
**Problem:** Single consumer can't keep up

```java
List<Proc<MyState, MyMsg>> workers = IntStream.range(0, 4)
    .mapToObj(i -> Proc.spawn(spec.name("worker-" + i)))
    .toList();

// Distribute work
Proc<MyState, MyMsg> dispatcher = Proc.spawn(
    ProcSpec.<MyState, MyMsg>builder()
        .name("dispatcher")
        .handler((state, msg) -> {
            Proc<MyState, MyMsg> worker = selectWorker(workers);
            worker.send(msg);
            return state;
        })
        .build()
);
```

#### Solution 4: Handle Overflow Gracefully
```java
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .mailboxPolicy(MailboxPolicy.BOUNDED)
    .mailboxSize(1000)
    .onFull((msg) -> {
        log.warn("Mailbox full, dropping: {}", msg);
        // Or send to dead letter queue
        deadLetterQueue.send(msg);
    })
    .build();
```

#### Solution 5: Message Batching
**Problem:** Too many small messages

```java
// Batch small messages
List<MyMsg> batch = new ArrayList<>(BATCH_SIZE);
for (MyMsg msg : messages) {
    batch.add(msg);
    if (batch.size() >= BATCH_SIZE) {
        proc.send(new BatchMsg(batch));
        batch.clear();
    }
}
```

### Prevention
- Monitor mailbox sizes
- Set appropriate capacity
- Implement backpressure
- Use worker pools for high throughput
- Batch small messages
- Profile handler performance

### Related Issues
- [Mailbox Overflow](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#mailbox-overflow)
- [Performance Issues](/Users/sac/jotp/docs/troubleshooting/performance-issues.md)

---

## Process Exit Exceptions

### Symptoms
- `ProcessExitException`
- Process terminates unexpectedly
- Exit reason unclear
- Stack trace lost

### Root Causes
1. **Unhandled exception in handler**
2. **Explicit exit() call**
3. **Linked process crash**
4. **Supervisor shutdown**

### Diagnosis Steps

1. **Check exit reason:**
```java
try {
    Proc<MyState, MyMsg> proc = Proc.spawn(spec);
    proc.await(); // Wait for exit
} catch (ProcessExitException e) {
    System.err.println("Exit reason: " + e.reason());
    System.err.println("Stack trace: " + e.stackTrace());
}
```

2. **Enable exit logging:**
```java
DebugOptions options = DebugOptions.builder()
    .logExits(true)
    .logExceptions(true)
    .build();
```

3. **Check supervisor logs:**
```java
supervisor.addListener(new SupervisorListener() {
    @Override
    public void childTerminated(String id, ExitReason reason) {
        log.error("Child {} terminated: {}", id, reason);
    }
});
```

### Solutions

#### Solution 1: Handle Exceptions in Handler
**Problem:** Unhandled exception crashes process

```java
// Wrong: Exception crashes process
.handler((state, msg) -> {
    riskyOperation(); // May throw
    return state;
})

// Correct: Handle exceptions
.handler((state, msg) -> {
    try {
        riskyOperation();
        return state;
    } catch (Exception e) {
        log.error("Handler error", e);
        return state; // Process continues
    }
})
```

#### Solution 2: Use Result Type for Errors
**Problem:** Exceptions not handled systematically

```java
// Use Result type
.handler((state, msg) -> {
    Result<NewState, Error> result = handleMessage(state, msg);
    return result.match(
        newState -> newState,
        error -> {
            log.error("Error: {}", error);
            return state; // Keep old state on error
        }
    );
})
```

#### Solution 3: Trap Exit Signals
**Problem:** Linked process crash kills this process

```java
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .trapExit(true) // Trap exit signals
    .handler((state, msg) -> {
        if (msg instanceof ExitSignal signal) {
            log.info("Linked process died: {}", signal.reason());
            // Handle gracefully
            return state.withoutLink();
        }
        return state;
    })
    .build();
```

#### Solution 4: Explicit Exit with Reason
```java
// Exit with clear reason
.handler((state, msg) -> {
    if (msg instanceof ShutdownMsg) {
        proc.exit(ExitReason.normal("Shutdown requested"));
        return state;
    }
    return state;
})
```

#### Solution 5: Supervisor Crash Recovery
**Problem:** Process crashes, supervisor doesn't restart

```java
SupervisorSpec spec = SupervisorSpec.builder()
    .strategy(RestartStrategy.ONE_FOR_ONE)
    .childSpec(ChildSpec.builder()
        .name("my-child")
        .factory(() -> new MyProc())
        .restartType(RestartType.PERMANENT) // Always restart
        .build())
    .maxRestarts(10)
    .restartWindow(Duration.ofMinutes(1))
    .build();
```

### Prevention
- Handle exceptions in handlers
- Use Result types for errors
- Trap exit signals when needed
- Set up proper supervision
- Log exit reasons
- Test crash scenarios

### Related Issues
- [Supervision Issues](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md)
- [Link/Monitor Confusion](#linkmonitor-confusion)

---

## State Machine Errors

### Symptoms
- `IllegalStateException` in state machine
- Invalid state transitions
- State not updated correctly
- Missing state cases

### Root Causes
1. **Incomplete state pattern matching**
2. **Invalid state transitions**
3. **State mutation issues**
4. **Missing transition handlers**

### Diagnosis Steps

1. **Check state definition:**
```java
sealed interface State permits Idle, Processing, Completed {}
record Idle() implements State {}
record Processing(int progress) implements State {}
record Completed(Result result) implements State {}
```

2. **Verify transitions:**
```java
StateMachine<State, Event, Data> sm = newStateMachine(
    initialState,
    (state, event, data) -> {
        // Validate transition
        if (!isValidTransition(state, event)) {
            throw new IllegalStateException(
                "Invalid transition: " + state + " + " + event
            );
        }
        return nextState;
    }
);
```

3. **Enable state tracing:**
```java
DebugOptions options = DebugOptions.builder()
    .traceStateChanges(true)
    .build();
```

### Solutions

#### Solution 1: Complete State Pattern Matching
**Problem:** Missing state cases

```java
// Wrong: Not exhaustive
return switch (state) {
    case Idle i -> /* ... */;
    // Missing Processing and Completed
};

// Correct: Handle all states
return switch (state) {
    case Idle i -> handleIdle(i, event);
    case Processing p -> handleProcessing(p, event);
    case Completed c -> handleCompleted(c, event);
};
```

#### Solution 2: Validate State Transitions
```java
public class StateTransitionValidator {
    private final Map<State, Set<Event>> validTransitions;

    public boolean isValidTransition(State from, Event event) {
        return validTransitions
            .getOrDefault(from, Set.of())
            .contains(event);
    }
}

// Usage
if (!validator.isValidTransition(currentState, event)) {
    log.error("Invalid transition: {} + {}", currentState, event);
    return currentState; // Stay in current state
}
```

#### Solution 3: Immutable State Updates
**Problem:** State mutation causing issues

```java
// Wrong: Mutable state
record Processing(int progress) implements State {
    void increment() {
        this.progress++; // Mutation - bad!
    }
}

// Correct: Create new state
Processing newProcessing = new Processing(old.progress + 1);
return newProcessing;
```

#### Solution 4: Use State Machine Builder
```java
StateMachine<State, Event, Data> sm = StateMachine.<State, Event, Data>builder()
    .initialState(new Idle())
    .transition(Idle.class, StartEvent.class, (idle, event, data) -> {
        return new Processing(0);
    })
    .transition(Processing.class, ProgressEvent.class, (proc, event, data) -> {
        return new Processing(proc.progress() + event.delta());
    })
    .transition(Processing.class, CompleteEvent.class, (proc, event, data) -> {
        return new Completed(event.result());
    })
    .onInvalidTransition((state, event) -> {
        log.error("Invalid transition: {} + {}", state, event);
        return state; // Stay in current state
    })
    .build();
```

#### Solution 5: State Machine Testing
```java
@Test
void testStateTransitions() {
    StateMachine<State, Event, Data> sm = createStateMachine();

    // Test valid transition
    State s1 = sm.handleEvent(new StartEvent());
    assertTrue(s1 instanceof Processing);

    // Test invalid transition
    State s2 = sm.handleEvent(new InvalidEvent());
    assertEquals(s1, s2); // Should stay in same state
}
```

### Prevention
- Use sealed types for states
- Implement exhaustive pattern matching
- Validate transitions
- Keep state immutable
- Test state machines thoroughly

### Related Issues
- [ClassCastException in Message Handler](#classcastexception-in-message-handler)
- [Testing Issues](/Users/sac/jotp/docs/troubleshooting/testing-issues.md)

---

## Quick Reference

### Common Error Patterns
```java
// ClassCastException
// Use sealed interfaces and exhaustive matching
sealed interface Msg permits A, B, C {}
return switch (msg) { case A a -> ...; case B b -> ...; case C c -> ...; };

// Process Already Registered
// Check before registering
if (ProcRegistry.lookup(name).isPresent()) {
    ProcRegistry.unregister(name);
}

// Timeout Exceptions
// Always use timeouts
proc.ask(req, Duration.ofSeconds(5))
    .orElseThrow(() -> new TimeoutException());

// Link vs Monitor
// Link: bidirectional crash
ProcLink.link(parent, child);
// Monitor: unidirectional notification
ProcMonitor.monitor(observer, observed);

// Mailbox Overflow
// Increase capacity or implement backpressure
ProcSpec.builder().mailboxSize(10000).build();
```

### Error Handling Best Practices
- Always handle exceptions in handlers
- Use Result types for errors
- Trap exit signals when needed
- Validate state transitions
- Set appropriate timeouts
- Monitor mailbox sizes

---

## Related Issues
- **Runtime Issues:** See `runtime-issues.md` for process problems
- **Supervision:** See `supervision-issues.md` for restart issues
- **Build Issues:** See `build-issues.md` for compilation errors
