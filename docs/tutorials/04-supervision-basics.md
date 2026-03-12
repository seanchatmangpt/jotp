# Tutorial 04: Supervision Basics

> "The secret to fault tolerance is not preventing failure. It's knowing what to do when failure happens. A supervisor watches its children. When a child crashes, the supervisor restarts it. The system continues."
> — Joe Armstrong

This tutorial teaches JOTP's supervision model: hierarchical process trees that automatically recover from failures without manual intervention.

**Prerequisites:** [Tutorial 03: Virtual Threads](03-virtual-threads.md)

**What you'll learn:**
- The three restart strategies and when to use each
- How to build a basic supervision tree
- How restart intensity limits prevent infinite loops
- How to test supervision behavior

---

## Part 1: The "Let It Crash" Philosophy

In traditional Java, you write defensive code:

```java
try {
    result = dangerousOperation();
} catch (Exception e) {
    log.error("Something went wrong", e);
    // Try to recover... maybe? hopefully?
    result = defaultValue;  // Is this correct? We don't know.
}
```

The problem: you're handling errors in code that doesn't have enough context to handle them correctly. The local code doesn't know if this is a temporary blip or a permanent failure. It doesn't know what state the system is in. Recovery logic is guesswork.

**Joe Armstrong's answer: Let it crash.**

Write processes that do their job. If they encounter a bad state, throw an exception. Let the supervisor handle recovery. The supervisor has the context: it knows the restart strategy, the restart limit, and what the process's initial state should be.

```java
// Process handler: just do the work
// If something is wrong, throw — the supervisor will restart this process
(state, msg) -> switch (msg) {
    case ProcessPayment(var amount) -> {
        var result = paymentGateway.charge(amount);  // May throw — that's OK
        return state.withTransaction(result);
    }
    case Rollback _ -> state.rollback();
}
```

No try-catch. No recovery logic. The supervisor handles it.

---

## The Three Restart Strategies

### ONE_FOR_ONE: Independent Recovery

When one child crashes, only that child restarts. Others continue running.

```
Before crash: [Worker-A] [Worker-B] [Worker-C]
Worker-B crashes
After restart: [Worker-A] [Worker-B'] [Worker-C]
              (B' = new instance with initialState)
              (A and C unchanged)
```

**Use when:** Workers are independent. A crash in one doesn't affect others' state.

### ONE_FOR_ALL: Coordinated Reset

When one child crashes, ALL children restart.

```
Before crash: [Auth] [Cache] [API]
Cache crashes
After restart: [Auth'] [Cache'] [API']
              (all restarted with initialState)
```

**Use when:** Children share state or must agree on a common starting point.

### REST_FOR_ONE: Dependency-Ordered Restart

When one child crashes, that child and all children started **after** it restart.

```
Start order: [Config] [Connection] [Worker] [Publisher]
Connection crashes
After restart: [Config] [Connection'] [Worker'] [Publisher']
               (Config unchanged — started before Connection)
               (Worker and Publisher restart — started after Connection)
```

**Use when:** Children have startup dependencies (e.g., config → connection → workers).

---

## Exercise 1: ONE_FOR_ONE — Independent Workers

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

public class OneForOneDemo {
    sealed interface WorkerMsg permits ProcessJob, CrashMe, GetCount {}
    record ProcessJob(String data) implements WorkerMsg {}
    record CrashMe() implements WorkerMsg {}
    record GetCount() implements WorkerMsg {}
    record WorkerState(int processed) {}

    public static void main(String[] args) throws Exception {
        // ONE_FOR_ONE: only crashed worker restarts
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofSeconds(60)
        );

        var worker1 = supervisor.supervise("worker-1", new WorkerState(0), workerHandler("W1"));
        var worker2 = supervisor.supervise("worker-2", new WorkerState(0), workerHandler("W2"));
        var worker3 = supervisor.supervise("worker-3", new WorkerState(0), workerHandler("W3"));

        // All workers process jobs
        worker1.tell(new ProcessJob("job-A"));
        worker2.tell(new ProcessJob("job-B"));
        worker3.tell(new ProcessJob("job-C"));
        Thread.sleep(100);

        // Verify state
        System.out.println("Worker 1 processed: " + worker1.ask(new GetCount(), Duration.ofSeconds(1)).get().processed());
        System.out.println("Worker 2 processed: " + worker2.ask(new GetCount(), Duration.ofSeconds(1)).get().processed());

        // Crash worker 2
        System.out.println("Crashing Worker 2...");
        worker2.tell(new CrashMe());
        Thread.sleep(200);  // Allow restart

        // Worker 2 restarts with initial state (0 processed)
        // Workers 1 and 3 are UNAFFECTED
        System.out.println("After restart:");
        System.out.println("Worker 1 still has state: " + worker1.ask(new GetCount(), Duration.ofSeconds(1)).get().processed());
        System.out.println("Worker 2 reset to: " + worker2.ask(new GetCount(), Duration.ofSeconds(1)).get().processed());
        System.out.println("Worker 3 unaffected: " + worker3.ask(new GetCount(), Duration.ofSeconds(1)).get().processed());

        supervisor.shutdown();
    }

    static java.util.function.BiFunction<WorkerState, WorkerMsg, WorkerState> workerHandler(String name) {
        return (state, msg) -> switch (msg) {
            case ProcessJob(var data) -> {
                System.out.printf("[%s] Processing: %s%n", name, data);
                yield new WorkerState(state.processed() + 1);
            }
            case CrashMe _ -> throw new RuntimeException(name + " intentionally crashed");
            case GetCount _ -> state;
        };
    }
}
```

**Expected output:**
```
Worker 1 processed: 1
Worker 2 processed: 1
Crashing Worker 2...
After restart:
Worker 1 still has state: 1      ← UNCHANGED
Worker 2 reset to: 0             ← RESTARTED with initialState
Worker 3 unaffected: 1           ← UNCHANGED
```

---

## Exercise 2: ONE_FOR_ALL — Coordinated Reset

Use ONE_FOR_ALL when workers must restart together to maintain consistency:

```java
public class OneForAllDemo {
    sealed interface ServiceMsg permits Start, Stop, GetStatus {}
    record Start() implements ServiceMsg {}
    record Stop() implements ServiceMsg {}
    record GetStatus() implements ServiceMsg {}
    record ServiceState(String name, boolean running) {}

    public static void main(String[] args) throws Exception {
        // ONE_FOR_ALL: if any service crashes, ALL restart
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ALL,
            3,
            Duration.ofSeconds(30)
        );

        var auth  = supervisor.supervise("auth",  new ServiceState("auth", false),  serviceHandler("auth"));
        var cache = supervisor.supervise("cache", new ServiceState("cache", false), cacheHandler("cache"));
        var api   = supervisor.supervise("api",   new ServiceState("api", false),   serviceHandler("api"));

        // Start all services
        auth.tell(new Start());
        cache.tell(new Start());
        api.tell(new Start());
        Thread.sleep(100);

        System.out.println("Auth running: " + auth.ask(new GetStatus(), Duration.ofSeconds(1)).get().running());
        System.out.println("API running: " + api.ask(new GetStatus(), Duration.ofSeconds(1)).get().running());

        // Cache crashes — ALL services restart
        System.out.println("Cache crashing...");
        cache.tell(new Stop());  // Stop triggers crash in cacheHandler
        Thread.sleep(300);

        // All services restarted with initialState (running=false)
        System.out.println("After ONE_FOR_ALL restart:");
        System.out.println("Auth reset to running=false: " + !auth.ask(new GetStatus(), Duration.ofSeconds(1)).get().running());
        System.out.println("API reset to running=false: " + !api.ask(new GetStatus(), Duration.ofSeconds(1)).get().running());

        supervisor.shutdown();
    }

    static java.util.function.BiFunction<ServiceState, ServiceMsg, ServiceState> serviceHandler(String name) {
        return (state, msg) -> switch (msg) {
            case Start _      -> new ServiceState(name, true);
            case Stop _       -> new ServiceState(name, false);
            case GetStatus _  -> state;
        };
    }

    static java.util.function.BiFunction<ServiceState, ServiceMsg, ServiceState> cacheHandler(String name) {
        return (state, msg) -> switch (msg) {
            case Start _      -> new ServiceState(name, true);
            case Stop _       -> throw new RuntimeException("Cache connection lost!");  // Crash!
            case GetStatus _  -> state;
        };
    }
}
```

---

## Exercise 3: Restart Intensity Limits

Supervisors have a maximum restart rate. If a child crashes too frequently, the supervisor gives up:

```java
public class RestartLimitsDemo {
    record AlwaysCrashMsg() {}
    record AlwaysCrashState() {}

    public static void main(String[] args) throws Exception {
        // Allow max 3 restarts within 5 seconds
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofSeconds(5)
        );

        var flaky = supervisor.supervise(
            "flaky-worker",
            new AlwaysCrashState(),
            (state, msg) -> {
                throw new RuntimeException("Always crashes");
            }
        );

        // Trigger crashes until supervisor gives up
        for (int i = 0; i < 5; i++) {
            try {
                flaky.tell(new AlwaysCrashMsg());
                Thread.sleep(200);
                System.out.println("Restart " + i + " completed");
            } catch (Exception e) {
                System.out.println("Error at " + i + ": " + e.getMessage());
            }
        }

        Thread.sleep(1000);

        // After 3 crashes in 5 seconds, supervisor terminates
        System.out.println("Supervisor still running: " + supervisor.isRunning());
        // Expected: false — supervisor gave up after restart limit exceeded

        if (!supervisor.isRunning() && supervisor.fatalError() != null) {
            System.out.println("Supervisor fatal error: " + supervisor.fatalError().getMessage());
        }
    }
}
```

**What happens when restart limit is exceeded:**
1. Child crashes for the (maxRestarts+1)th time within the window
2. Supervisor terminates itself (via `fatalError()`)
3. Supervisor's parent detects the crash
4. Parent applies its own restart strategy to restart the entire subsystem

This prevents infinite restart loops while allowing recovery at a higher level.

---

## Exercise 4: Nested Supervision Tree

Real applications use multiple levels of supervision:

```java
public class NestedSupervisionDemo {
    sealed interface HttpMsg permits HandleRequest {}
    sealed interface DbMsg permits ExecuteQuery {}
    record HandleRequest(String path) implements HttpMsg {}
    record ExecuteQuery(String sql) implements DbMsg {}
    record HttpState(int count) {}
    record DbState(int count) {}

    public static void main(String[] args) throws Exception {
        // Root: owns the entire application
        var root = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 1, Duration.ofMinutes(5));

        // HTTP subsystem: high tolerance (10 restarts/min)
        var httpSup = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));
        var h1 = httpSup.supervise("h1", new HttpState(0),
            (s, m) -> { System.out.println("HTTP: " + ((HandleRequest)m).path()); return new HttpState(s.count()+1); });
        var h2 = httpSup.supervise("h2", new HttpState(0),
            (s, m) -> { System.out.println("HTTP: " + ((HandleRequest)m).path()); return new HttpState(s.count()+1); });

        // DB subsystem: low tolerance (3 restarts/30s)
        var dbSup = new Supervisor(Supervisor.Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(30));
        var db1 = dbSup.supervise("primary", new DbState(0),
            (s, m) -> { System.out.println("DB: " + ((ExecuteQuery)m).sql()); return new DbState(s.count()+1); });

        // Use the processes
        h1.tell(new HandleRequest("/api/users"));
        h2.tell(new HandleRequest("/api/orders"));
        db1.tell(new ExecuteQuery("SELECT * FROM users"));

        Thread.sleep(200);

        // HTTP handler 1 crashes 3 times
        // → httpSup restarts it (10 restarts allowed)
        // → dbSup and its children are COMPLETELY UNAFFECTED

        System.out.println("HTTP sup running: " + httpSup.isRunning());
        System.out.println("DB sup running: " + dbSup.isRunning());

        httpSup.shutdown();
        dbSup.shutdown();
        root.shutdown();
    }
}
```

**Blast radius principle:** Each supervisor bounds the failure impact. An HTTP handler crashing 10 times can never take down the database subsystem.

---

## Common Mistakes

### Mistake 1: Wrong Strategy for Shared State

```java
// BAD: ONE_FOR_ONE when workers share state
// If pool crashes, processor still references the old (dead) pool
var pool = supervisor.supervise("pool", new PoolState(), poolHandler);
var processor = supervisor.supervise("processor", new ProcessorState(), processorHandler);
// If pool crashes → processor has a stale reference!

// GOOD: ONE_FOR_ALL ensures consistent restart
var supervisor = new Supervisor(Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(30));
```

### Mistake 2: No Timeout on ask()

```java
// BAD: if process is dead, this hangs forever
supervisor.supervise("worker", state, handler).ask(new GetState()).get();

// GOOD: always use timeout
supervisor.supervise("worker", state, handler)
    .ask(new GetState(), Duration.ofSeconds(5)).get();
```

### Mistake 3: Catching Exceptions That Should Crash the Process

```java
// BAD: catching prevents supervisor from seeing the crash
(state, msg) -> {
    try {
        return riskyOperation(state, msg);
    } catch (Exception e) {
        log.error("Error", e);
        return state;  // Pretend nothing happened — wrong state!
    }
}

// GOOD: let it crash — supervisor restarts with correct initial state
(state, msg) -> riskyOperation(state, msg)  // Throw propagates to supervisor
```

---

## Summary

Supervision trees are JOTP's answer to production reliability:

1. **ONE_FOR_ONE** — Default for independent workers. Only the crashed worker restarts.
2. **ONE_FOR_ALL** — For interdependent workers that must reset together.
3. **REST_FOR_ONE** — For pipeline stages where later stages depend on earlier ones.

The key principle: **a crash is a data point, not an emergency**. The supervisor has the policy for handling it. The worker just does its job and crashes cleanly when something is wrong.

---

**Previous:** [Tutorial 03: Virtual Threads](03-virtual-threads.md) | **Next:** [How-To Guides](../how-to/)

**See Also:**
- [Supervisor API](../reference/api-supervisor.md) — Complete API reference
- [How-To: Build Supervision Trees](../how-to/build-supervision-trees.md) — Advanced patterns
- [How-To: Handle Process Failures](../how-to/handling-process-crashes.md) — Recovery patterns
