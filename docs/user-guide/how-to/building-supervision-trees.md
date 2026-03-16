# Building Supervision Trees in JOTP

## Problem Statement
You need to manage fault-tolerant concurrent systems where worker processes can crash and restart automatically, with hierarchical error containment and recovery strategies.

## Solution Overview
JOTP's `Supervisor` implements OTP's supervision tree pattern: supervisors monitor child processes and restart them based on configurable strategies when crashes occur, preventing cascading failures.

## Step-by-Step Instructions

### 1. Create a Supervisor

**Basic supervisor with ONE_FOR_ONE strategy:**

```java
import java.time.Duration;
import io.github.seanchatmangpt.jotp.Supervisor;

Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE, // Only crashed child restarts
    5,                              // Max 5 restarts in window
    Duration.ofSeconds(60)          // 60-second window
);
```

**Restart strategies explained:**
- `ONE_FOR_ONE`: Only the crashed child is restarted (default for most cases)
- `ONE_FOR_ALL`: All children restart when any crashes (for tightly-coupled processes)
- `REST_FOR_ONE`: Crashed child + all children started after it restart
- `SIMPLE_ONE_FOR_ONE`: Dynamic homogeneous pool (all children from same template)

**Named supervisor** (useful for debugging):

```java
Supervisor supervisor = Supervisor.create(
    "database-supervisor",                    // Name for thread identification
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);
```

### 2. Add Child Processes

**Simple child supervision** (permanent worker):

```java
record DbState(Connection conn) {}
sealed interface DbMsg {
    record Query(String sql) implements DbMsg {}
    record Update(String sql) implements DbMsg {}
}

var dbWorker = supervisor.supervise(
    "db-worker-1",                          // Child ID
    new DbState(createConnection()),        // Initial state
    (state, msg) -> switch (msg) {          // Message handler
        case DbMsg.Query(var sql) -> executeQuery(state, sql);
        case DbMsg.Update(var sql) -> executeUpdate(state, sql);
    }
);
```

**Child with explicit ChildSpec** (full control):

```java
var spec = new Supervisor.ChildSpec<>(
    "db-worker-1",                          // ID
    () -> new DbState(createConnection()),  // State factory (called on each restart)
    (state, msg) -> handleMessage(state, msg), // Handler
    Supervisor.ChildSpec.RestartType.PERMANENT, // Restart policy
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(10)), // Shutdown
    Supervisor.ChildSpec.ChildType.WORKER,  // Worker or supervisor
    false                                   // Significant for auto-shutdown
);

var dbWorker = supervisor.startChild(spec);
```

**Restart types:**
- `PERMANENT`: Always restarted (crash or normal exit) - default for workers
- `TRANSIENT`: Restarted only on crash, not normal exit
- `TEMPORARY`: Never restarted

### 3. Choose Restart Strategy

**ONE_FOR_ONE** (most common):

```java
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

supervisor.supervise("worker-1", state1, handler1);
supervisor.supervise("worker-2", state2, handler2);
supervisor.supervise("worker-3", state3, handler3);

// If worker-2 crashes:
// - Only worker-2 is restarted
// - Workers 1 and 3 continue unaffected
```

**ONE_FOR_ALL** (tightly coupled processes):

```java
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ALL,
    5,
    Duration.ofSeconds(60)
);

supervisor.supervise("writer", writerState, writerHandler);
supervisor.supervise("reader", readerState, readerHandler);

// If writer crashes:
// - Both writer AND reader restart
// - Use when processes share resources or have tight coupling
```

**REST_FOR_ONE** (dependent processes):

```java
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.REST_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

supervisor.supervise("database", dbState, dbHandler);
supervisor.supervise("cache", cacheState, cacheHandler);
supervisor.supervise("api", apiState, apiHandler);

// If cache crashes:
// - cache and api restart (started after cache)
// - database continues (started before cache)
```

**SIMPLE_ONE_FOR_ONE** (dynamic pools):

```java
var template = Supervisor.ChildSpec.worker(
    "connection",                        // ID prefix
    () -> new ConnState(connect()),      // Factory for each instance
    this::handleConnection               // Handler
);

Supervisor pool = Supervisor.createSimple(
    template,
    10,                                  // Max restarts
    Duration.ofSeconds(30)               // Window
);

// Dynamically add instances
ProcRef<ConnState, ConnMsg> conn1 = pool.startChild(); // ID: connection-1
ProcRef<ConnState, ConnMsg> conn2 = pool.startChild(); // ID: connection-2
ProcRef<ConnState, ConnMsg> conn3 = pool.startChild(); // ID: connection-3
```

### 4. Handle Child Crashes

**Automatic restart** (default behavior):

```java
// Child crashes with exception
throw new RuntimeException("Database connection failed");

// Supervisor automatically:
// 1. Detects crash via crash callback
// 2. Checks restart intensity (max 5 in 60s)
// 3. Calls state factory for fresh state
// 4. Spawns new process with same handler
// 5. Updates ProcRef to point to new process

// All existing ProcRefs transparently redirect to new process
dbWorker.tell(new DbMsg.Query("SELECT * FROM users")); // Works even after restart
```

**Prevent restart loops** (intensity limiting):

```java
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                              // Max 5 restarts
    Duration.ofSeconds(60)          // Within 60 seconds
);

// If child crashes 5 times in 60 seconds:
// - Supervisor gives up
// - Supervisor shuts down entire tree
// - supervisor.fatalError() contains the last crash reason
```

**Access crash information:**

```java
// Check if supervisor is still running
if (!supervisor.isRunning()) {
    Throwable cause = supervisor.fatalError();
    logger.error("Supervisor terminated: {}", cause.getMessage(), cause);
}

// Check last crash on a specific child
Proc<DbState, DbMsg> proc = dbWorker.proc();
if (proc.lastError() != null) {
    logger.warn("Last crash: {}", proc.lastError().getMessage());
}
```

### 5. Graceful Shutdown

**Stop entire supervision tree:**

```java
supervisor.shutdown(); // Blocks until all children stopped

// Children stopped in reverse order (LIFO):
// 1. youngest child stopped first
// 2. oldest child stopped last
// 3. supervisor terminates
```

**Stop specific child** (keeps spec in tree):

```java
supervisor.terminateChild("db-worker-1");
// Child stopped but can be restarted later
supervisor.startChild(spec); // Re-add with same ID
```

**Remove child completely** (including spec):

```java
supervisor.terminateChild("db-worker-1");
supervisor.deleteChild("db-worker-1");
// Child removed from tree, cannot restart
```

**Auto-shutdown on significant child exit:**

```java
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60),
    Supervisor.AutoShutdown.ANY_SIGNIFICANT // Shutdown when any significant child exits
);

var spec = new Supervisor.ChildSpec<>(
    "essential-worker",
    stateFactory,
    handler,
    Supervisor.ChildSpec.RestartType.TRANSIENT, // Restart on crash only
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
    Supervisor.ChildSpec.ChildType.WORKER,
    true // Significant - supervisor shuts down if this exits normally
);

supervisor.startChild(spec);

// If essential-worker exits normally (not crash):
// - Supervisor detects normal exit
// - Supervisor initiates graceful shutdown
// - Entire tree stops cleanly
```

## Common Mistakes

### 1. Mutable Shared State in State Factory
```java
// BAD - sharing mutable connection
Connection conn = DriverManager.getConnection(url);
supervisor.supervise("worker-1", new DbState(conn), handler);
// All restarts share same (possibly corrupted) connection

// GOOD - factory creates fresh state
supervisor.supervise("worker-1", () -> new DbState(connect()), handler);
// Each restart gets a new connection
```

### 2. Wrong Restart Strategy
```java
// BAD - ONE_FOR_ALL for independent workers
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ALL, // Restarts everything!
    5, Duration.ofSeconds(60)
);
supervisor.supervise("worker-1", state1, handler1);
supervisor.supervise("worker-2", state2, handler2);
// If worker-1 crashes, worker-2 also restarts (unnecessary)

// GOOD - ONE_FOR_ONE for independent workers
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE, // Only restart crashed worker
    5, Duration.ofSeconds(60)
);
```

### 3. Forgetting Shutdown
```java
// BAD - supervisor never stops
Supervisor supervisor = Supervisor.create(...);
// Leaks virtual threads forever

// GOOD - always shutdown
try {
    supervisor.shutdown();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

### 4. Intensity Too High
```java
// BAD - allows too many restarts
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    1000,                       // Way too high
    Duration.ofSeconds(60)
);
// Broken process can restart 999 times in a minute

// GOOD - reasonable intensity
Supervisor supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                          // Allows 4 crashes, gives up on 5th
    Duration.ofSeconds(60)
);
```

## Complete Example: Database Connection Pool

```java
public class DatabaseSupervisor {
    public static void main(String[] args) throws InterruptedException {
        // Create supervisor
        Supervisor supervisor = Supervisor.create(
            "db-pool",
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofMinutes(1)
        );

        // Create connection pool spec
        var connSpec = Supervisor.ChildSpec.worker(
            "db-conn",
            () -> {
                var conn = DriverManager.getConnection(
                    "jdbc:postgresql://localhost/mydb",
                    "user", "pass"
                );
                return new DbState(conn);
            },
            DatabaseSupervisor::handleMessage
        );

        // Start pool
        for (int i = 0; i < 10; i++) {
            supervisor.startChild(connSpec);
        }

        // Use pool
        var children = supervisor.whichChildren();
        System.out.println("Active connections: " + children.stream()
            .filter(ChildInfo::alive)
            .count());

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                supervisor.shutdown();
                System.out.println("Pool shut down gracefully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private static DbState handleMessage(DbState state, DbMsg msg) {
        return switch (msg) {
            case DbMsg.Query(var sql) -> executeQuery(state, sql);
            case DbMsg.Update(var sql) -> executeUpdate(state, sql);
        };
    }
}
```

## Related Guides
- [Process Management](./process-management.md) - Basic process spawning and messaging
- [Error Handling](./error-handling.md) - Crash recovery patterns
- [State Machines](./implementing-state-machines.md) - Complex stateful workflows

## Advanced Patterns

### Nested Supervisors (hierarchical trees)

```java
// Root supervisor
Supervisor root = Supervisor.create("root", ONE_FOR_ONE, 5, Duration.ofSeconds(60));

// Child supervisor
Supervisor dbSupervisor = Supervisor.create("db-team", ONE_FOR_ONE, 3, Duration.ofSeconds(30));
root.startChild(Supervisor.ChildSpec.supervisor("db-team", () -> dbSupervisor));

// Add workers to child supervisor
dbSupervisor.supervise("conn-1", connState1, handler1);
dbSupervisor.supervise("conn-2", connState2, handler2);

// If conn-1 crashes repeatedly:
// - dbSupervisor gives up after 3 crashes in 30s
// - dbSupervisor shuts down
// - Root supervisor sees db-team child crash
// - Root restarts entire dbSupervisor (including conn-1 and conn-2)
```

### Dynamic Child Addition/Removal

```java
// Add children at runtime
var spec = Supervisor.ChildSpec.worker(
    "temp-worker",
    () -> new WorkerState(),
    this::handle
);
ProcRef<WorkerState, WorkerMsg> ref = supervisor.startChild(spec);

// Remove when no longer needed
supervisor.terminateChild("temp-worker");
supervisor.deleteChild("temp-worker");
```

### ProcRef Stability Across Restarts

```java
// ProcRef transparently survives restarts
ProcRef<DbState, DbMsg> dbRef = supervisor.supervise("db", initialState, handler);

// Store the ref somewhere (e.g., in a registry)
ProcRegistry.register("database", dbRef);

// Later, even after crashes:
ProcRef<?, ?> ref = ProcRegistry.where("database");
ref.tell(new DbMsg.Query("SELECT * FROM users"));
// Works even if database process restarted multiple times
```
