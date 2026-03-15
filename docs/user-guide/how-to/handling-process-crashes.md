# Handling Process Crashes with Supervisor

## The Problem

In a concurrent system, processes crash. Databases go down, network calls fail, logic has bugs. The question isn't "will my process crash?" — it's "what do I do when it does?"

**Without supervision:** A crashed process leaves a hole. Other processes waiting for responses from it hang indefinitely. The entire system becomes brittle.

**With JOTP Supervisor:** Dead child processes are automatically restarted according to a strategy. This is the foundation of fault-tolerant systems.

## Joe Armstrong's Insight

> "In Erlang, we don't try to prevent process crashes. We let them crash and then supervise their restart. A supervisor is a process whose job is to keep its children alive."

The Erlang/OTP supervisor is battle-tested in production systems handling millions of concurrent connections. JOTP brings this exact approach to Java.

## Supervision Strategies

JOTP supports three OTP restart strategies:

### 1. ONE_FOR_ONE (Most Common)

**Rule:** If a child crashes, restart only that child. Other children keep running.

**Use case:** Independent workers, cache cleaners, background tasks.

```java
import io.github.seanchatmangpt.jotp.*;

sealed interface WorkerMsg {
    record DoWork(String task) implements WorkerMsg {}
    record Stop() implements WorkerMsg {}
}

// Create child process factories
var workerFactory = (String id) -> Proc.spawn(
    new WorkerState(id, 0),
    (state, msg) -> switch (msg) {
        case WorkerMsg.DoWork(var task) -> {
            System.out.println(state.id() + " working on: " + task);
            if (task.equals("crash")) {
                throw new RuntimeException("Simulated crash!");
            }
            yield state.withTaskCount(state.taskCount() + 1);
        }
        case WorkerMsg.Stop() -> {
            System.out.println(state.id() + " stopping");
            yield state;
        }
        default -> state;
    }
);

// Create supervisor with ONE_FOR_ONE strategy
var supervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
    .add("worker-1", () -> workerFactory.apply("worker-1"))
    .add("worker-2", () -> workerFactory.apply("worker-2"))
    .add("worker-3", () -> workerFactory.apply("worker-3"))
    .build();

supervisor.start();

// Get child references
var worker1 = supervisor.whereis("worker-1");
worker1.tell(new WorkerMsg.DoWork("task-1"));
worker1.tell(new WorkerMsg.DoWork("crash"));  // crashes the process

// Give supervisor time to restart
Thread.sleep(100);

// Process was automatically restarted — new worker-1 instance is ready
var newWorker1 = supervisor.whereis("worker-1");
newWorker1.tell(new WorkerMsg.DoWork("task-2"));  // works!

supervisor.stop();
```

### 2. ONE_FOR_ALL (Strict Dependency)

**Rule:** If any child crashes, stop all children and restart them all.

**Use case:** Tightly coupled services (e.g., a database connection and a query cache that depends on it).

```java
var supervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)
    .add("db", () -> Proc.spawn(...))
    .add("cache", () -> Proc.spawn(...))
    .add("indexer", () -> Proc.spawn(...))
    .build();

supervisor.start();

// If ANY child crashes, ALL are stopped and restarted together
// Useful for systems where children have strict startup order dependencies
```

### 3. REST_FOR_ONE (Ordered Dependency)

**Rule:** If a child crashes, restart that child and all children added after it (to its right).

**Use case:** Services with partial ordering (A → B → C, where C depends on B but not vice versa).

```java
var supervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.REST_FOR_ONE)
    .add("foundation", () -> Proc.spawn(...))     // Core service
    .add("api", () -> Proc.spawn(...))            // Depends on foundation
    .add("cache", () -> Proc.spawn(...))          // Depends on api and foundation
    .build();

supervisor.start();

// If "api" crashes: restart "api" and "cache" (all to the right)
// If "cache" crashes: restart only "cache" (nothing to its right)
// If "foundation" crashes: restart all (since all depend on it)
```

## Controlling Restarts: The Sliding Window

By default, JOTP allows unlimited restart attempts. To prevent restart storms (child keeps crashing and restarting rapidly), configure a sliding window:

```java
var supervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
    .maxRestarts(3)              // Allow 3 restarts...
    .restartWindow(Duration.ofSeconds(5))  // ...within 5 seconds
    .add("worker", () -> Proc.spawn(...))
    .build();

supervisor.start();

// If the child crashes 3+ times in 5 seconds, the supervisor itself crashes
// This prevents infinite restart loops from taking down the entire system
```

## Accessing Child Processes

Once a supervisor is running, access its children by name:

```java
// Get a child's Proc reference
var childRef = supervisor.whereis("worker-1");
childRef.tell(new WorkerMsg.DoWork("task"));

// Get all registered children
var allChildren = supervisor.children();
allChildren.forEach((name, proc) -> {
    System.out.println("Child: " + name);
});
```

## Nesting Supervisors (Supervision Trees)

Supervisors can supervise other supervisors, creating a tree:

```java
// Leaf supervisor: manages individual worker processes
var workerSupervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
    .add("worker-1", () -> Proc.spawn(...))
    .add("worker-2", () -> Proc.spawn(...))
    .build();

// Root supervisor: manages the worker supervisor + other services
var rootSupervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)
    .add("workers", () -> workerSupervisor)
    .add("logger", () -> Proc.spawn(...))
    .add("monitor", () -> Proc.spawn(...))
    .build();

rootSupervisor.start();
```

If the worker supervisor crashes, it's restarted by the root. If a single worker crashes, it's restarted by the worker supervisor (not the root).

## Stopping the Supervisor

```java
// Stop gracefully: stop all children, then the supervisor
supervisor.stop();

// All child processes are now dead
// supervisor.whereis() will return null
```

## Common Patterns

### Pattern: Health Check Child

```java
var healthSupervisor = Supervisor.builder()
    .strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)
    .add("db", () -> Proc.spawn(...))
    .add("api", () -> Proc.spawn(...))
    .add("health-check", () -> Proc.spawn(
        new HealthCheckState(0),
        (state, msg) -> switch (msg) {
            case "check" -> {
                // Poll children for health
                var db = supervisor.whereis("db");
                var api = supervisor.whereis("api");
                var dbHealth = db.ask("health", 1000).join();
                var apiHealth = api.ask("health", 1000).join();

                if (dbHealth.equals("ok") && apiHealth.equals("ok")) {
                    System.out.println("System healthy");
                } else {
                    throw new RuntimeException("Health check failed!");
                }
                yield state.withCheckCount(state.checkCount() + 1);
            }
            default -> state;
        }
    ))
    .build();
```

### Pattern: Exponential Backoff on Restart

(JOTP supervisors restart immediately by default. For application-level backoff, wrap the child's init handler:)

```java
.add("worker", () -> {
    Thread.sleep(100);  // Backoff before starting
    return Proc.spawn(...);
})
```

## Troubleshooting

**Q: My supervisor is restarting the child constantly.**
A: Set `maxRestarts` and `restartWindow` to detect crash loops:
```java
.maxRestarts(3)
.restartWindow(Duration.ofSeconds(5))
```

**Q: How do I know a child restarted?**
A: The child process will be a different `Proc` instance. Use `ProcMonitor` to be notified of crashes:
```java
var monitor = ProcMonitor.monitor(supervisor.whereis("worker"));
// When "worker" crashes and restarts, monitor notifies the listener
```

**Q: Can I restart a supervisor?**
A: Supervisors are meant to be restarted by their parent supervisor (or manually). For manual restart:
```java
supervisor.stop();
var newSupervisor = Supervisor.builder()
    .strategy(strategy)
    .add(...)
    .build();
newSupervisor.start();
```

## Next Steps

- **[Linking Processes](linking-processes.md)** — Handle process crashes with links (bilateral crash propagation)
- **[Monitoring Without Killing](monitoring-without-killing.md)** — Observe process crashes without restarting them
- **API Reference** — `Supervisor`, `RestartStrategy`, `ProcMonitor`
