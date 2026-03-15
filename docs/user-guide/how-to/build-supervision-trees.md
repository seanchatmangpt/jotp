# How-To: Build Supervision Trees

> "The big idea is that you structure your program as a set of communicating processes, each handling a single concern, organized into a tree where failures bubble up to nodes that know how to handle them."
> — Joe Armstrong

This guide covers designing and implementing multi-level supervision hierarchies — the primary pattern for enterprise fault tolerance in JOTP.

**Prerequisites:** [Tutorial 04: Supervision Basics](../tutorials/04-supervision-basics.md)

---

## The Mental Model

A supervision tree is a nested hierarchy of supervisors and workers:

```
Root Supervisor (ONE_FOR_ONE)
├─ HttpSupervisor (ONE_FOR_ONE)
│  ├─ Handler-1 (worker)
│  ├─ Handler-2 (worker)
│  └─ Handler-3 (worker)
├─ DatabaseSupervisor (ONE_FOR_ALL)
│  ├─ ConnectionPool (worker)
│  └─ QueryProcessor (worker)
└─ MetricsSupervisor (ONE_FOR_ONE)
   ├─ Collector (worker)
   └─ Reporter (worker)
```

**Design principle:** Each supervisor owns exactly one concern. HTTP handlers can fail without affecting the database layer. The database layer can restart without affecting metrics collection.

---

## Pattern 1: Simple Worker Pool

The most common pattern: N independent workers under ONE_FOR_ONE supervision.

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class WorkerPool {
    sealed interface WorkerMsg permits ProcessJob, GetStats {}
    record ProcessJob(String jobId, String payload) implements WorkerMsg {}
    record GetStats() implements WorkerMsg {}

    record WorkerState(int processed, int failed) {}

    public static void main(String[] args) throws Exception {
        var supervisor = new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofSeconds(60)
        );

        int workerCount = 10;
        List<ProcRef<WorkerState, WorkerMsg>> workers = new ArrayList<>();

        // Spawn N workers
        for (int i = 0; i < workerCount; i++) {
            var workerId = "worker-" + i;
            ProcRef<WorkerState, WorkerMsg> ref = supervisor.supervise(
                workerId,
                new WorkerState(0, 0),
                (state, msg) -> switch (msg) {
                    case ProcessJob(var id, var payload) -> {
                        // Process the job (may throw, triggering restart)
                        var result = processPayload(payload);
                        System.out.printf("[%s] Processed job %s%n", workerId, id);
                        yield new WorkerState(state.processed() + 1, state.failed());
                    }
                    case GetStats _ -> state;
                }
            );
            workers.add(ref);
        }

        // Route work to workers (simple round-robin)
        for (int j = 0; j < 100; j++) {
            workers.get(j % workerCount).tell(new ProcessJob("job-" + j, "payload-" + j));
        }

        Thread.sleep(500);  // Allow processing
        supervisor.shutdown();
    }

    static String processPayload(String payload) {
        if (payload.contains("bad")) throw new RuntimeException("Bad payload: " + payload);
        return "processed: " + payload;
    }
}
```

**What happens when a worker crashes:**
1. `Supervisor` (ONE_FOR_ONE) detects the crash
2. Only the crashed worker restarts with `WorkerState(0, 0)`
3. Other 9 workers continue processing
4. `ProcRef` for the crashed worker now points to the new instance

---

## Pattern 2: Two-Level Hierarchy (Subsystem Isolation)

Group related workers under their own supervisor. Failures in one subsystem don't cascade.

```java
public class TwoLevelHierarchy {
    // HTTP layer
    sealed interface HttpMsg permits HandleRequest, GetMetrics {}
    record HandleRequest(String path) implements HttpMsg {}
    record GetMetrics() implements HttpMsg {}
    record HttpState(int requests) {}

    // Database layer
    sealed interface DbMsg permits ExecuteQuery, HealthCheck {}
    record ExecuteQuery(String sql) implements DbMsg {}
    record HealthCheck() implements DbMsg {}
    record DbState(boolean connected, int queries) {}

    public static void main(String[] args) throws Exception {
        // Root supervisor: if ANY subsystem exceeds limits, the root decides what to do
        var root = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 1, Duration.ofMinutes(5));

        // HTTP subsystem: high restart tolerance (handlers can be flaky)
        var httpSupervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));
        var handler1 = httpSupervisor.supervise("handler-1", new HttpState(0), httpHandler("handler-1"));
        var handler2 = httpSupervisor.supervise("handler-2", new HttpState(0), httpHandler("handler-2"));

        // Database subsystem: low restart tolerance (cascade failure = real problem)
        var dbSupervisor = new Supervisor(Supervisor.Strategy.ONE_FOR_ALL, 3, Duration.ofSeconds(30));
        var primaryDb = dbSupervisor.supervise("primary", new DbState(true, 0), dbHandler("primary"));
        var replicaDb = dbSupervisor.supervise("replica", new DbState(true, 0), dbHandler("replica"));

        // Use the processes
        handler1.tell(new HandleRequest("/api/users"));
        primaryDb.tell(new ExecuteQuery("SELECT * FROM users"));

        Thread.sleep(200);

        // Simulate: HTTP handler crashes (3 times)
        // → HTTP subsystem keeps restarting (10 restarts allowed)
        // → Database subsystem UNAFFECTED

        root.shutdown();
    }

    static java.util.function.BiFunction<HttpState, HttpMsg, HttpState> httpHandler(String name) {
        return (state, msg) -> switch (msg) {
            case HandleRequest(var path) -> {
                System.out.printf("[%s] Handling request: %s%n", name, path);
                yield new HttpState(state.requests() + 1);
            }
            case GetMetrics _ -> state;
        };
    }

    static java.util.function.BiFunction<DbState, DbMsg, DbState> dbHandler(String name) {
        return (state, msg) -> switch (msg) {
            case ExecuteQuery(var sql) -> {
                System.out.printf("[%s] Executing: %s%n", name, sql);
                yield new DbState(state.connected(), state.queries() + 1);
            }
            case HealthCheck _ -> state;
        };
    }
}
```

---

## Pattern 3: Pipeline with REST_FOR_ONE

Use REST_FOR_ONE when processes have a startup dependency chain.

```java
public class Pipeline {
    // Pipeline stages: Config → Connection → Transformer → Publisher
    // If Connection crashes, Transformer and Publisher must also restart
    // (They depend on Connection's state)

    sealed interface ConfigMsg permits LoadConfig {}
    sealed interface ConnectionMsg permits Connect, Disconnect, IsConnected {}
    sealed interface TransformerMsg permits Transform, GetCount {}
    sealed interface PublisherMsg permits Publish, Flush {}

    record ConfigState(String url, int timeout) {}
    record ConnectionState(boolean connected, int retries) {}
    record TransformerState(int count) {}
    record PublisherState(int published) {}

    public static void main(String[] args) throws Exception {
        // REST_FOR_ONE: if Connection crashes, Transformer and Publisher restart too
        // Config is NOT affected (it was started before Connection)
        var supervisor = new Supervisor(Supervisor.Strategy.REST_FOR_ONE, 3, Duration.ofSeconds(30));

        // Stage 1: Load config (always stays up)
        var config = supervisor.supervise(
            "config",
            new ConfigState("kafka://localhost:9092", 5000),
            (state, msg) -> state  // Config never crashes
        );

        // Stage 2: Connection (depends on nothing, but others depend on it)
        var connection = supervisor.supervise(
            "connection",
            new ConnectionState(false, 0),
            (state, msg) -> switch (msg) {
                case Connect _ -> new ConnectionState(true, 0);
                case Disconnect _ -> new ConnectionState(false, state.retries() + 1);
                case IsConnected _ -> state;
            }
        );

        // Stage 3: Transformer (depends on connection being up)
        var transformer = supervisor.supervise(
            "transformer",
            new TransformerState(0),
            (state, msg) -> switch (msg) {
                case Transform _ -> new TransformerState(state.count() + 1);
                case GetCount _ -> state;
            }
        );

        // Stage 4: Publisher (depends on transformer + connection)
        var publisher = supervisor.supervise(
            "publisher",
            new PublisherState(0),
            (state, msg) -> switch (msg) {
                case Publish _ -> new PublisherState(state.published() + 1);
                case Flush _ -> state;
            }
        );

        // If connection crashes:
        //   - config continues running (started BEFORE connection)
        //   - connection restarts
        //   - transformer restarts (started AFTER connection)
        //   - publisher restarts (started AFTER connection)

        connection.tell(new Connect());
        transformer.tell(new Transform());
        publisher.tell(new Publish());

        Thread.sleep(100);
        supervisor.shutdown();
    }
}
```

---

## Pattern 4: Multi-Tenant Isolation

Each tenant gets a dedicated supervisor. Tenant A's failures cannot cascade to Tenant B.

```java
public class MultiTenantSystem {
    sealed interface TenantMsg permits Process, GetMetrics, Shutdown {}
    record Process(String data) implements TenantMsg {}
    record GetMetrics() implements TenantMsg {}
    record Shutdown() implements TenantMsg {}
    record TenantState(String tenantId, int requestCount) {}

    public static class TenantSupervisor {
        private final Supervisor supervisor;
        private final ProcRef<TenantState, TenantMsg> worker;

        public TenantSupervisor(String tenantId) {
            this.supervisor = new Supervisor(
                Supervisor.Strategy.ONE_FOR_ONE,
                5,
                Duration.ofSeconds(60)
            );
            this.worker = supervisor.supervise(
                "worker",
                new TenantState(tenantId, 0),
                (state, msg) -> switch (msg) {
                    case Process(var data) -> {
                        System.out.printf("[Tenant %s] Processing: %s%n", tenantId, data);
                        yield new TenantState(tenantId, state.requestCount() + 1);
                    }
                    case GetMetrics _ -> state;
                    case Shutdown _ -> state;
                }
            );
        }

        public ProcRef<TenantState, TenantMsg> worker() { return worker; }
        public void shutdown() throws InterruptedException { supervisor.shutdown(); }
    }

    public static void main(String[] args) throws Exception {
        // Root supervisor: if any tenant exceeds limit, only that tenant goes down
        var root = new Supervisor(Supervisor.Strategy.ONE_FOR_ONE, 1, Duration.ofMinutes(5));

        // Create per-tenant supervisors
        var tenantA = new TenantSupervisor("tenant-A");
        var tenantB = new TenantSupervisor("tenant-B");
        var tenantC = new TenantSupervisor("tenant-C");

        // Tenant A processes 1000 requests
        for (int i = 0; i < 10; i++) {
            tenantA.worker().tell(new Process("request-" + i));
        }

        // Tenant A crashes
        // → Tenant A restarts (independent from B and C)
        // → Tenant B and C are completely unaffected

        tenantA.worker().tell(new GetMetrics());

        Thread.sleep(200);

        tenantA.shutdown();
        tenantB.shutdown();
        tenantC.shutdown();
        root.shutdown();
    }
}
```

---

## Monitoring Supervisor Health

Track supervisor health in production using `isRunning()` and `fatalError()`:

```java
// Health check endpoint
public boolean isSupervisorHealthy(Supervisor supervisor) {
    if (!supervisor.isRunning()) {
        log.error("Supervisor down. Last cause: {}", supervisor.fatalError());
        alerting.critical("supervisor_down", supervisor.fatalError());
        return false;
    }
    return true;
}

// Prometheus metrics (example)
supervisor.addHealthListener(event -> {
    if (event instanceof SupervisorEvent.ChildRestarted restart) {
        metrics.increment("supervisor.restarts", "child", restart.childId());
    }
    if (event instanceof SupervisorEvent.RestartLimitExceeded) {
        metrics.increment("supervisor.fatal_errors");
    }
});
```

---

## Common Mistakes and How to Fix Them

### Mistake 1: Wrong Strategy for Shared State

```java
// BAD: ONE_FOR_ONE with shared-state children
// If ConnectionPool crashes, QueryProcessor still has references to the old pool
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
var pool = supervisor.supervise("pool", new PoolState(), poolHandler);
var processor = supervisor.supervise("processor", new ProcessorState(pool), processorHandler);

// GOOD: ONE_FOR_ALL ensures processor gets a fresh pool reference
var supervisor = new Supervisor(Strategy.ONE_FOR_ALL, 5, Duration.ofSeconds(60));
```

### Mistake 2: Restart Window Too Tight

```java
// BAD: Too tight — supervisor gives up on first temporary network glitch
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 1, Duration.ofSeconds(1));

// GOOD: Tolerates transient failures
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
```

### Mistake 3: Not Using ProcRef

```java
// BAD: Holding direct Proc reference — won't see restarted process
Proc<State, Msg> proc = new Proc<>(state, handler);  // Unsupervised!

// GOOD: Always use ProcRef from supervisor
var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
ProcRef<State, Msg> ref = supervisor.supervise("worker", state, handler);
// ref transparently follows restarts
```

### Mistake 4: Over-Nesting Supervisors

```java
// BAD: Too many layers for simple systems
Root
└─ SubSupervisor
   └─ SubSubSupervisor
      └─ Worker (1 worker!)

// GOOD: Flatten for simple systems
Root
└─ Worker
```

**Rule:** Only add a level of hierarchy when the workers at that level have different fault tolerance requirements.

---

## Testing Supervision Trees

```java
@Test
void supervisorRestartsChildOnCrash() throws Exception {
    var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 3, Duration.ofSeconds(10));

    var crashCount = new java.util.concurrent.atomic.AtomicInteger(0);
    sealed interface TestMsg permits Crash, GetCount {}
    record Crash() implements TestMsg {}
    record GetCount() implements TestMsg {}

    ProcRef<Integer, TestMsg> worker = supervisor.supervise(
        "worker", 0,
        (state, msg) -> switch (msg) {
            case Crash _ -> {
                crashCount.incrementAndGet();
                throw new RuntimeException("intentional crash");
            }
            case GetCount _ -> state;
        }
    );

    // Verify process works initially
    assertThat(worker.ask(new GetCount(), Duration.ofSeconds(1)).get()).isEqualTo(0);

    // Cause a crash
    worker.tell(new Crash());
    Thread.sleep(100);  // Allow restart

    // Verify supervisor restarted the worker with fresh state
    assertThat(worker.ask(new GetCount(), Duration.ofSeconds(1)).get()).isEqualTo(0);
    assertThat(crashCount.get()).isEqualTo(1);

    supervisor.shutdown();
}
```

---

**Previous:** [Test Concurrent Code](test-concurrent-code.md) | **Next:** [Migrate from Erlang](migrate-from-erlang.md)

**See Also:**
- [Supervisor API](../reference/api-supervisor.md) — Complete API reference
- [Tutorial 04: Supervision Basics](../tutorials/04-supervision-basics.md) — Introduction
- [SLA Patterns](../../.claude/SLA-PATTERNS.md) — Meeting 99.95%+ SLA with supervision trees
