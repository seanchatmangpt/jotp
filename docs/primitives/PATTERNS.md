# JOTP Common Patterns - Best Practices

**A comprehensive guide to common patterns and best practices when using JOTP primitives.**

---

## Table of Contents

1. [Supervision Patterns](#supervision-patterns)
2. [Messaging Patterns](#messaging-patterns)
3. [State Management Patterns](#state-management-patterns)
4. [Error Handling Patterns](#error-handling-patterns)
5. [Testing Patterns](#testing-patterns)
6. [Performance Patterns](#performance-patterns)
7. [Integration Patterns](#integration-patterns)

---

## Supervision Patterns

### Pattern 1: Simple Worker Pool

**Problem**: Run multiple instances of the same worker process.

**Solution**: Use `SIMPLE_ONE_FOR_ONE` supervisor.

```java
// Create template for workers
var template = Supervisor.ChildSpec.worker(
    "worker",
    () -> new WorkerState(connectToDB()),
    workerHandler
);

// Create dynamic pool
var pool = Supervisor.createSimple(
    template,
    10, Duration.ofSeconds(30)  // Max 10 restarts in 30s
);

// Dynamically spawn workers
var worker1 = pool.startChild();
var worker2 = pool.startChild();
var worker3 = pool.startChild();

// Each worker is independently supervised
// Names are auto-generated: worker-1, worker-2, etc.
```

**When to Use**:
- Connection pools
- Worker farms
- Dynamic horizontal scaling

**Benefits**:
- Automatic restart of failed workers
- Dynamic worker creation
- Isolated failures (one worker doesn't affect others)

---

### Pattern 2: Critical Pair (All-or-Nothing)

**Problem**: Two processes must succeed or fail together.

**Solution**: Use `ONE_FOR_ALL` supervisor or direct linking.

```java
// Option 1: ONE_FOR_ALL supervisor
var supervisor = Supervisor.create(
    "critical-pair",
    Supervisor.Strategy.ONE_FOR_ALL,
    3, Duration.ofMinutes(5)
);

var db = supervisor.startChild(ChildSpec.worker("db", dbState, dbHandler));
var cache = supervisor.startChild(ChildSpec.worker("cache", cacheState, cacheHandler));

// If db crashes, both db and cache are restarted
// If cache crashes, both db and cache are restarted

// Option 2: Direct linking
var primary = Proc.spawn(primaryState, primaryHandler);
var backup = Proc.spawn(backupState, backupHandler);

ProcLink.link(primary, backup);

// If either crashes, both crash
```

**When to Use**:
- Tightly-coupled services
- Cache + source-of-truth pairs
- Primary + backup systems

**Benefits**:
- Ensures consistency
- Automatic failover
- No partial failures

---

### Pattern 3: Tiered Supervision

**Problem**: Organize complex system into hierarchical supervision trees.

**Solution**: Nest supervisors.

```java
// Root supervisor
var rootSup = Supervisor.create(
    "root",
    Supervisor.Strategy.ONE_FOR_ONE,
    10, Duration.ofMinutes(1)
);

// Application supervisor (child of root)
var appSupSpec = new Supervisor.ChildSpec<>(
    "app-supervisor",
    () -> Supervisor.create(
        "app",
        Supervisor.Strategy.ONE_FOR_ONE,
        5, Duration.ofSeconds(30)
    ),
    supHandler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Infinity(),
    Supervisor.ChildSpec.ChildType.SUPERVISOR,
    false
);

var appSup = rootSup.startChild(appSupSpec);

// Workers under app supervisor
var worker1 = appSup.startChild(ChildSpec.worker("worker-1", workerState, workerHandler));
var worker2 = appSup.startChild(ChildSpec.worker("worker-2", workerState, workerHandler));

// If root crashes -> app supervisor and its workers restart
// If app supervisor crashes -> only app supervisor restarts
// If worker crashes -> only worker restarts
```

**When to Use**:
- Large applications
- Isolated subsystems
- Different restart strategies per subsystem

**Benefits**:
- Fault isolation
- Granular restart policies
- Organized structure

---

### Pattern 4: Transient Workers

**Problem**: Workers should restart on crash but not on normal exit.

**Solution**: Use `TRANSIENT` restart type.

```java
var spec = new Supervisor.ChildSpec<>(
    "ephemeral-worker",
    () -> new WorkerState(),
    workerHandler,
    Supervisor.ChildSpec.RestartType.TRANSIENT,  // Restart on crash only
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(10)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);

var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

var worker = supervisor.startChild(spec);

// If worker crashes -> restarted
// If worker stops normally -> not restarted
```

**When to Use**:
- Task queues (one-shot workers)
- Temporary processing
- Jobs that complete successfully

**Benefits**:
- Automatic crash recovery
- No unnecessary restarts
- Clean shutdown

---

### Pattern 5: Supervised Initialization

**Problem**: Ensure worker is fully initialized before supervisor considers it "alive".

**Solution**: Use `ProcLib.startLink` for synchronous initialization.

```java
var result = ProcLib.startLink(
    initialState,
    state -> {
        // Perform initialization
        database.connect();
        cache.warmup();
        ProcRegistry.register("worker", self);

        // Signal ready (MUST be explicit)
        ProcLib.initAck();

        return state;
    },
    workerHandler
);

switch (result) {
    case ProcLib.StartResult.Ok(var proc) -> {
        // Add to supervisor (now guaranteed ready)
        var ref = new ProcRef<>(proc);
        supervisor.startChild(specWithRef(ref));
    }
    case ProcLib.StartResult.Err(var reason) -> {
        // Init failed - don't add to tree
        log.error("Worker failed to initialize: " + reason);
    }
}
```

**When to Use**:
- Complex initialization (DB connections, file handles)
- Need guaranteed readiness
- Preventing race conditions

**Benefits**:
- No race conditions during startup
- Explicit success/failure indication
- Parent knows child is ready

---

## Messaging Patterns

### Pattern 1: Request-Reply with Timeout

**Problem**: Send request and wait for response with timeout.

**Solution**: Use `proc.ask()` with timeout.

```java
var proc = ProcRegistry.whereis("database").orElseThrow();

// Send request with timeout
var future = proc.ask(new Query("SELECT * FROM users"))
    .orTimeout(5, TimeUnit.SECONDS);

try {
    var result = future.get();
    processResult(result);
} catch (TimeoutException e) {
    handleError("Query timed out");
}
```

**When to Use**:
- Database queries
- External API calls
- Need guaranteed response

**Benefits**:
- Built-in timeout handling
- Future-based composition
- Type-safe responses

---

### Pattern 2: Fire-and-Forget

**Problem**: Send message without waiting for response.

**Solution**: Use `proc.tell()`.

```java
var logger = ProcRegistry.whereis("logger").orElseThrow();

// Log message asynchronously
logger.tell(new LogMessage(LogLevel.INFO, "Request received"));

// Continue processing immediately
// No wait for logging to complete
```

**When to Use**:
- Logging
- Metrics
- Non-critical notifications

**Benefits**:
- Non-blocking
- High throughput
- No latency impact

---

### Pattern 3: Message Aggregation

**Problem**: Aggregate results from multiple processes.

**Solution**: Use `CompletableFuture.allOf` or `Parallel.all`.

```java
// Option 1: Manual aggregation
var proc1 = ProcRegistry.whereis("service-1").orElseThrow();
var proc2 = ProcRegistry.whereis("service-2").orElseThrow();
var proc3 = ProcRegistry.whereis("service-3").orElseThrow();

var future1 = proc1.ask(new Request(data1));
var future2 = proc2.ask(new Request(data2));
var future3 = proc3.ask(new Request(data3));

// Aggregate results
CompletableFuture.allOf(future1, future2, future3)
    .thenAccept(v -> {
        var result1 = future1.join();
        var result2 = future2.join();
        var result3 = future3.join();
        processAll(result1, result2, result3);
    });

// Option 2: Use Parallel primitive
var tasks = List.of(
    () -> service1.fetch(data1),
    () -> service2.fetch(data2),
    () -> service3.fetch(data3)
);

var result = Parallel.all(tasks);

switch (result) {
    case Result.Success(var results) -> processAll(results);
    case Result.Failure(var ex) -> handleError(ex);
}
```

**When to Use**:
- Fan-out operations
- Parallel data fetching
- Map-reduce patterns

**Benefits**:
- Parallel execution
- Fail-fast semantics
- Composable results

---

### Pattern 4: Message Filtering

**Problem**: Route messages to different handlers based on content.

**Solution**: Use sealed message types with pattern matching.

```java
// Define message hierarchy
sealed interface NetworkMsg permits
    NetworkMsg.Connect,
    NetworkMsg.Disconnect,
    NetworkMsg.Data {}

record Connect(String host, int port) implements NetworkMsg {}
record Disconnect() implements NetworkMsg {}
record Data(byte[] payload) implements NetworkMsg {}

// Handler with pattern matching
BiFunction<State, NetworkMsg, State> handler = (state, msg) -> switch (msg) {
    case NetworkMsg.Connect(var host, var port) ->
        handleConnect(state, host, port);
    case NetworkMsg.Disconnect() ->
        handleDisconnect(state);
    case NetworkMsg.Data(var payload) ->
        handleData(state, payload);
};

// Each message type is handled separately
// Compiler enforces exhaustiveness
```

**When to Use**:
- Protocol implementation
- Message routing
- Type-safe messaging

**Benefits**:
- Type-safe routing
- Exhaustive matching
- Clear message flow

---

## State Management Patterns

### Pattern 1: Immutable State

**Problem**: Maintain state without race conditions.

**Solution**: Use immutable records and return new state.

```java
// State is an immutable record
record CounterState(int count, Instant lastModified) {}

// Handler returns new state (never mutates)
BiFunction<CounterState, CounterMsg, CounterState> handler = (state, msg) -> switch (msg) {
    case CounterMsg.Increment() ->
        new CounterState(state.count() + 1, Instant.now());
    case CounterMsg.Reset() ->
        new CounterState(0, Instant.now());
};

// No race conditions - each message sees consistent state
// No locks needed - state is never mutated
```

**When to Use**:
- All process state
- Concurrent systems
- Prevent race conditions

**Benefits**:
- Thread-safe
- No race conditions
- Predictable state transitions

---

### Pattern 2: State Machine

**Problem**: Model complex state transitions with timeouts.

**Solution**: Use `StateMachine` primitive.

```java
// States
sealed interface LockState permits Locked, Open {}
record Locked() implements LockState {}
record Open() implements LockState {}

// Data
record LockData(String entered, String code) {
    LockData withEntered(String s) { return new LockData(s, code); }
}

// State machine
var sm = StateMachine.of(
    new Locked(),
    new LockData("", "1234"),
    (state, event, data) -> switch (state) {
        case Locked() -> switch (event) {
            case SMEvent.User(PushButton(var b)) -> {
                var entered = data.entered() + b;
                if (entered.equals(data.code())) {
                    return Transition.nextState(
                        new Open(),
                        data.withEntered(""),
                        Action.stateTimeout(10_000, "lock")
                    );
                }
                yield Transition.keepState(data.withEntered(entered));
            }
            case SMEvent.StateTimeout(var _) ->
                Transition.keepState(data.withEntered(""));
            default -> Transition.keepState(data);
        };
        case Open() -> switch (event) {
            case SMEvent.StateTimeout(var _) ->
                Transition.nextState(new Locked(), data);
            case SMEvent.User(var _) ->
                Transition.keepState(data, Action.postpone());
            default -> Transition.keepState(data);
        };
    }
);
```

**When to Use**:
- Complex workflows
- Protocols with timeouts
- Stateful business logic

**Benefits**:
- Explicit state transitions
- Timeout handling
- Event postponement
- Enter/exit callbacks

---

### Pattern 3: Ephemeral State

**Problem**: Maintain temporary state during operation.

**Solution**: Use state field in record.

```java
// State includes ephemeral data
record SessionState(
    String userId,
    Instant loginTime,
    Map<String, String> tempData  // Ephemeral session data
) {}

// Handler updates ephemeral state
BiFunction<SessionState, SessionMsg, SessionState> handler = (state, msg) -> switch (msg) {
    case SessionMsg.SetTempData(var key, var value) -> {
        var newTempData = new HashMap<>(state.tempData());
        newTempData.put(key, value);
        yield new SessionState(state.userId(), state.loginTime(), newTempData);
    }
    case SessionMsg.ClearTempData() ->
        new SessionState(state.userId(), state.loginTime(), Map.of());
};
```

**When to Use**:
- Session data
- Temporary calculations
- Caching within process

**Benefits**:
- Type-safe ephemeral state
- Automatic cleanup on process exit
- No shared mutable state

---

## Error Handling Patterns

### Pattern 1: Railway-Oriented Error Handling

**Problem**: Chain multiple fallible operations cleanly.

**Solution**: Use `Result` type with railway operations.

```java
Result<Order, OrderError> result = Result.of(() -> validate(request))
    .flatMap(valid -> Result.of(() -> calculatePrice(valid)))
    .flatMap(order -> Result.of(() -> reserveInventory(order)))
    .peek(order -> auditLog.add(order))
    .recover(error -> {
        metrics.recordFailure(error);
        return Result.failure(error);
    });

switch (result) {
    case Result.Ok(var order) -> shipOrder(order);
    case Result.Err(var error) -> notifyCustomer(error);
}
```

**When to Use**:
- Multi-step validation
- Chained operations
- Explicit error handling

**Benefits**:
- No nested try-catch
- Composable error handling
- Explicit error paths

---

### Pattern 2: Let It Crash

**Problem**: Handle failures by restarting with fresh state.

**Solution**: Don't catch exceptions - let supervisor restart.

```java
// DON'T DO THIS - don't catch exceptions locally
var proc = Proc.spawn(state, (s, m) -> {
    try {
        return handle(s, m);
    } catch (Exception e) {
        // Don't catch - let supervisor restart
        return s;  // BAD: error state persists
    }
});

// DO THIS - let supervisor handle restarts
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5, Duration.ofSeconds(60)
);

// Handler throws exception on error
BiFunction<State, Msg, State> handler = (state, msg) -> {
    if (isInvalid(msg)) {
        throw new IllegalArgumentException("Invalid message");
    }
    return handle(state, msg);
};

// Supervisor automatically restarts with fresh state
supervisor.supervise("worker", initialState, handler);
```

**When to Use**:
- Production systems
- Stateful services
- Want automatic recovery

**Benefits**:
- Fresh state on restart
- No corrupted state
- Automatic recovery
- Supervisor handles failures

---

### Pattern 3: Graceful Degradation

**Problem**: Provide fallback when service fails.

**Solution**: Use `Result.recover()` or `CrashRecovery.retry()`.

```java
// Option 1: Result recovery
var result = Result.of(() -> fetchFromCache(key))
    .recover(error -> {
        // Cache failed - fetch from database
        return Result.of(() -> fetchFromDatabase(key));
    });

// Option 2: Retry with fallback
var result = CrashRecovery.retry(3, () -> {
    return fetchFromService(key);
});

if (result.isFailure()) {
    // All retries failed - use default value
    result = Result.success(getDefaultValue(key));
}
```

**When to Use**:
- Redundant services
- Cache + source patterns
- Fault-tolerant operations

**Benefits**:
- Automatic fallback
- Multiple attempts
- Better availability

---

### Pattern 4: Exit Trapping

**Problem**: Detect linked process crashes without dying.

**Solution**: Enable exit trapping and handle `ExitSignal`.

```java
var parent = Proc.spawn(parentState, parentHandler);
parent.trapExits(true);  // Enable exit trapping

var child = ProcLink.spawnLink(parent, childState, childHandler);

// Parent handler receives ExitSignal
BiFunction<ParentState, Object, ParentState> parentHandler = (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> {
        if (reason == null) {
            System.out.println("Child stopped normally");
        } else {
            System.err.println("Child crashed: " + reason);
            // Handle crash (restart, alert, etc.)
        }
        yield state;
    }
    // ... handle other messages
};
```

**When to Use**:
- Need to detect crashes
- Want custom crash handling
- Building supervisors

**Benefits**:
- Crash visibility
- Custom handling
- Process stays alive

---

## Testing Patterns

### Pattern 1: Process Testing with Awaitility

**Problem**: Test asynchronous process behavior.

**Solution**: Use Awaitility for async assertions.

```java
@Test
void testCounterIncrement() {
    var counter = Proc.spawn(new CounterState(0), counterHandler);

    // Send increment message
    counter.tell(new CounterMsg.Increment());

    // Wait for state change
    await().atMost(5, TimeUnit.SECONDS)
        .until(() -> {
            var state = ProcSys.getState(counter).get();
            return state.count() == 1;
        });
}

@Test
void testSupervisorRestart() {
    var supervisor = Supervisor.create(
        Supervisor.Strategy.ONE_FOR_ONE,
        5, Duration.ofSeconds(60)
    );

    var worker = supervisor.supervise("worker", initialState, handler);

    // Get initial process reference
    var initialProc = worker.proc();

    // Crash the worker
    worker.tell(new CrashMessage());

    // Wait for restart
    await().atMost(5, TimeUnit.SECONDS)
        .until(() -> worker.proc() != initialProc);

    // Verify worker is running again
    var newProc = worker.proc();
    assertThat(newProc.lastError()).isNotNull();
}
```

**When to Use**:
- Testing async behavior
- Supervisor restarts
- Message handling

**Benefits**:
- Readable async tests
- Explicit timeouts
- No arbitrary sleeps

---

### Pattern 2: Message Capture

**Problem**: Verify messages sent by process.

**Solution**: Use test probe process.

```java
// Test probe that captures messages
class TestProbe {
    private final List<Object> messages = new CopyOnWriteArrayList<>();
    private final Proc<Void, Object> probe;

    TestProbe() {
        probe = Proc.spawn(null, (_, msg) -> {
            messages.add(msg);
            return null;
        });
    }

    Proc<Void, Object> proc() { return probe; }

    @SuppressWarnings("unchecked")
    <T> T expectMessage(Class<T> type) {
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> messages.stream().anyMatch(type::isInstance));
        return (T) messages.stream()
            .filter(type::isInstance)
            .findFirst()
            .orElseThrow();
    }

    void clearMessages() { messages.clear(); }
}

// Use in test
@Test
void testMessageSending() {
    var probe = new TestProbe();
    var worker = Proc.spawn(initialState, workerHandler);

    // Send request that should reply to probe
    worker.tell(new Request(probe.proc()));

    // Verify reply
    var reply = probe.expectMessage(Reply.class);
    assertThat(reply.data()).isEqualTo("expected");
}
```

**When to Use**:
- Testing message flows
- Verifying replies
- Protocol testing

**Benefits**:
- Explicit message capture
- Type-safe assertions
- Clear test intentions

---

### Pattern 3: State Inspection

**Problem**: Verify internal process state in tests.

**Solution**: Use `ProcSys.getState()`.

```java
@Test
void testInternalState() {
    var proc = Proc.spawn(initialState, handler);

    // Send messages
    proc.tell(new UpdateMessage("data1"));
    proc.tell(new UpdateMessage("data2"));

    // Get internal state
    var state = ProcSys.getState(proc).join();

    // Verify state
    assertThat(state.data()).contains("data1", "data2");
    assertThat(state.lastModified()).isBefore(Instant.now());
}
```

**When to Use**:
- Testing state transitions
- Verifying internal logic
- Debugging tests

**Benefits**:
- Direct state access
- Non-blocking
- Test internal behavior

---

## Performance Patterns

### Pattern 1: Batch Processing

**Problem**: Process multiple items efficiently.

**Solution**: Send batch messages.

```java
// Batch message
record BatchMessage(List<Item> items) implements Msg {}

// Handler processes batch
BiFunction<State, Msg, State> handler = (state, msg) -> switch (msg) {
    case BatchMessage(var items) -> {
        // Process all items in one go
        var results = items.stream()
            .map(this::processItem)
            .toList();
        yield updateState(state, results);
    }
};

// Send batches
var items = IntStream.range(0, 1000)
    .mapToObj(i -> new Item(i))
    .toList();

proc.tell(new BatchMessage(items));
```

**When to Use**:
- High-volume processing
- Reduce message overhead
- Bulk operations

**Benefits**:
- Reduced message overhead
- Better throughput
- Efficient processing

---

### Pattern 2: Selective Message Handling

**Problem**: Process only certain message types now, defer others.

**Solution**: Use state machine postponement.

```java
var sm = StateMachine.of(
    new Busy(),
    null,
    (state, event, data) -> switch (state) {
        case Busy() -> switch (event) {
            case SMEvent.User(var _) ->
                // Postpone all user events while busy
                Transition.keepState(data, Action.postpone());
            case SMEvent.Internal(var "ready") ->
                Transition.nextState(new Ready(), data);
            default -> Transition.keepState(data);
        };
        case Ready() -> switch (event) {
            case SMEvent.User(var msg) -> {
                // Handle postponed events
                handleMessage(msg);
                yield Transition.keepState(data);
            }
            default -> Transition.keepState(data);
        };
    }
);
```

**When to Use**:
- State-dependent processing
- Throttling
- Priority handling

**Benefits**:
- Message deferral
- State-driven processing
- No lost messages

---

### Pattern 3: Hot Path Optimization

**Problem**: Optimize frequently executed code paths.

**Solution**: Avoid boxing, use primitives, minimize allocations.

```java
// BAD: Boxing and allocations
record BadState(Integer count, List<String> items) {}

// GOOD: Primitives and immutable collections
record GoodState(int count, ImmutableSet<String> items) {}

// Handler with minimal allocations
BiFunction<GoodState, Msg, GoodState> handler = (state, msg) -> switch (msg) {
    case Increment() ->
        new GoodState(state.count() + 1, state.items());  // Reuse items
};
```

**When to Use**:
- Hot message paths
- High-frequency operations
- Performance-critical code

**Benefits**:
- Reduced allocations
- Better throughput
- Lower latency

---

## Integration Patterns

### Pattern 1: Request Bridge

**Problem**: Integrate JOTP with blocking APIs (HTTP, DB).

**Solution**: Offload blocking work to separate process.

```java
// Bridge process for blocking I/O
var bridge = Proc.spawn(bridgeState, (state, request) -> {
    // Perform blocking operation
    var result = blockingApiClient.call(request);

    // Reply to original requester
    request.replyTo().tell(new Response(result));

    return state;
});

// Non-blocking client API
public CompletableFuture<Response> callApi(Request req) {
    var replyTo = Proc.spawn(
        new ResponseState(),
        responseHandler
    );

    var future = new CompletableFuture<Response>();
    responseHandler.setFuture(future);

    bridge.tell(new BridgeRequest(req, replyTo));

    return future;
}
```

**When to Use**:
- Integrating blocking APIs
- HTTP clients
- Database drivers

**Benefits**:
- Non-blocking interface
- Isolated blocking work
- Clean separation

---

### Pattern 2: Event Bridge

**Problem**: Bridge JOTP events to external systems.

**Solution**: Use `EventManager` with handlers.

```java
// Event manager for external integration
var eventMgr = EventManager.start("event-bridge");

// Handler sends to external system
eventMgr.addHandler(new EventManager.Handler<DomainEvent>() {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void handleEvent(DomainEvent event) {
        // Send to external webhook
        httpClient.send(webhookRequest(event));
    }

    @Override
    public void terminate(Throwable reason) {
        // Cleanup on shutdown
        httpClient.close();
    }
});

// Anywhere in codebase:
eventMgr.notify(new UserCreatedEvent(userId));
eventMgr.notify(new UserDeletedEvent(userId));
```

**When to Use**:
- Webhook integration
- Event streaming
- Audit logging

**Benefits**:
- Decoupled event handling
- Fault isolation
- Multiple handlers

---

### Pattern 3: Rate Limiting

**Problem**: Limit request rate to external service.

**Solution**: Use state machine with token bucket.

```java
record RateLimiterState(int tokens, Instant lastRefill) {}

var rateLimiter = StateMachine.of(
    new RateLimiterState(10, Instant.now()),  // 10 tokens
    null,
    (state, event, data) -> switch (event) {
        case SMEvent.User(Request(var req, var reply)) -> {
            // Refill tokens based on time
            var now = Instant.now();
            var elapsed = Duration.between(state.lastRefill(), now).toSeconds();
            var newTokens = Math.min(10, state.tokens() + (int) elapsed);

            if (newTokens > 0) {
                // Allow request
                reply.complete(Result.success(process(req)));
                yield new RateLimiterState(newTokens - 1, now);
            } else {
                // Rate limited
                reply.complete(Result.failure(new RateLimitException()));
                yield new RateLimiterState(newTokens, now);
            }
        }
        default -> Transition.keepState(state);
    }
);
```

**When to Use**:
- API rate limiting
- Throttling
- Resource management

**Benefits**:
- Token bucket algorithm
- Time-based refills
- Clean state management

---

## Conclusion

These patterns represent proven best practices for building production systems with JOTP. By following these patterns, you can build fault-tolerant, scalable concurrent systems that embody Joe Armstrong's "let it crash" philosophy on the JVM.

**Key Principles**:
1. **Let it crash**: Use supervisors for automatic restart
2. **Immutable state**: Never mutate state directly
3. **Message passing**: Communicate via messages, not shared state
4. **Fault isolation**: Use supervisors and links to contain failures
5. **Explicit errors**: Use `Result` type for error handling

**Next Steps**:
- Review `/docs/primitives/ANTI-PATTERNS.md` to avoid mistakes
- Study `/docs/primitives/PERFORMANCE.md` for optimization techniques
- Read `/docs/primitives/REFERENCE.md` for complete API documentation
