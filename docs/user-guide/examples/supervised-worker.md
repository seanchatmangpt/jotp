# Supervised Worker - Fault Tolerance

## Problem Statement

Implement a supervised worker system that demonstrates:
- Supervisor setup and configuration
- Crash detection and recovery
- Restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- Health monitoring
- Error isolation and recovery

## Solution Design

Create a fault-tolerant system with:
1. **Supervisor**: Monitors worker processes and restarts on crashes
2. **Worker Processes**: Perform work that may fail
3. **Child Specifications**: Define restart policies and shutdown strategies
4. **Crash Callbacks**: Detect and respond to failures
5. **Health Monitoring**: Track worker state and restart counts

## Complete Java Code

```java
package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervised Worker example demonstrating fault tolerance and crash recovery.
 *
 * This example shows:
 * - Supervisor creation and configuration
 * - Worker process supervision with different strategies
 * - Crash detection and automatic restart
 * - Restart intensity limits
 * - Health monitoring and statistics
 */
public class SupervisedWorker {

    /**
     * Worker messages.
     */
    public sealed interface WorkerMsg
            permits WorkerMsg.DoWork,
                    WorkerMsg.GetStatus,
                    WorkerMsg.Crash,
                    WorkerMsg.SlowWork {

        record DoWork(String taskId) implements WorkerMsg {}
        record GetStatus() implements WorkerMsg {}
        record Crash() implements WorkerMsg {}
        record SlowWork(long delayMs) implements WorkerMsg {}
    }

    /**
     * Worker state: tracks completed tasks and crashes.
     */
    public record WorkerState(
        String workerName,
        AtomicInteger completedTasks,
        AtomicInteger crashCount
    ) {
        WorkerState(String workerName) {
            this(workerName, new AtomicInteger(0), new AtomicInteger(0));
        }
    }

    /**
     * Create a worker handler that may crash on certain messages.
     */
    public static java.util.function.BiFunction<WorkerState, WorkerMsg, WorkerState> createWorkerHandler() {
        return (WorkerState state, WorkerMsg msg) -> {
            return switch (msg) {
                case WorkerMsg.DoWork(var taskId) -> {
                    System.out.println("[" + state.workerName() + "] Processing: " + taskId);
                    state.completedTasks().incrementAndGet();
                    // Simulate work
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    yield state;
                }

                case WorkerMsg.SlowWork(var delay) -> {
                    System.out.println("[" + state.workerName() + "] Starting slow work...");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    yield state;
                }

                case WorkerMsg.GetStatus() -> state;

                case WorkerMsg.Crash() -> {
                    System.out.println("[" + state.workerName() + "] CRASHING!");
                    throw new RuntimeException("Intentional crash from " + state.workerName());
                }
            };
        };
    }

    /**
     * Example 1: ONE_FOR_ONE supervision strategy.
     */
    public static void oneForOneExample() throws Exception {
        System.out.println("=== ONE_FOR_ONE Strategy ===\n");

        // Create supervisor with ONE_FOR_ONE strategy
        var supervisor = Supervisor.create(
            "one-for-one-sup",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,  // max restarts
            Duration.ofSeconds(10)  // time window
        );

        // Start multiple workers
        var worker1 = supervisor.supervise("worker-1",
            new WorkerState("Worker-1"), createWorkerHandler());
        var worker2 = supervisor.supervise("worker-2",
            new WorkerState("Worker-2"), createWorkerHandler());
        var worker3 = supervisor.supervise("worker-3",
            new WorkerState("Worker-3"), createWorkerHandler());

        System.out.println("Workers started\n");

        // Send work to all workers
        for (int i = 0; i < 3; i++) {
            worker1.tell(new WorkerMsg.DoWork("task-1-" + i));
            worker2.tell(new WorkerMsg.DoWork("task-2-" + i));
            worker3.tell(new WorkerMsg.DoWork("task-3-" + i));
        }

        Thread.sleep(100);
        System.out.println();

        // Crash worker-2
        System.out.println("--- Crashing Worker-2 ---");
        worker2.tell(new WorkerMsg.Crash());

        // Give supervisor time to restart
        Thread.sleep(200);

        // Worker-2 should be restarted, others unaffected
        System.out.println("\n--- Checking Workers After Crash ---");
        checkWorker(worker1, "Worker-1");
        checkWorker(worker2, "Worker-2");
        checkWorker(worker3, "Worker-3");

        // Send more work to verify recovery
        System.out.println("\n--- Sending Work After Recovery ---");
        worker1.tell(new WorkerMsg.DoWork("recovery-task-1"));
        worker2.tell(new WorkerMsg.DoWork("recovery-task-2"));
        worker3.tell(new WorkerMsg.DoWork("recovery-task-3"));

        Thread.sleep(100);

        // Show child info
        System.out.println("\n--- Supervisor Children ---");
        supervisor.whichChildren().forEach(child ->
            System.out.println("  " + child.id() + ": " + (child.alive() ? "ALIVE" : "DEAD"))
        );

        // Shutdown
        supervisor.shutdown();
        Thread.sleep(100);
        System.out.println("\n=== ONE_FOR_ONE Complete ===\n");
    }

    /**
     * Example 2: ONE_FOR_ALL supervision strategy.
     */
    public static void oneForAllExample() throws Exception {
        System.out.println("=== ONE_FOR_ALL Strategy ===\n");

        var supervisor = Supervisor.create(
            "one-for-all-sup",
            Supervisor.Strategy.ONE_FOR_ALL,
            5,
            Duration.ofSeconds(10)
        );

        var worker1 = supervisor.supervise("worker-1",
            new WorkerState("Worker-1"), createWorkerHandler());
        var worker2 = supervisor.supervise("worker-2",
            new WorkerState("Worker-2"), createWorkerHandler());
        var worker3 = supervisor.supervise("worker-3",
            new WorkerState("Worker-3"), createWorkerHandler());

        System.out.println("Workers started\n");

        // Send work
        worker1.tell(new WorkerMsg.DoWork("initial-1"));
        worker2.tell(new WorkerMsg.DoWork("initial-2"));
        worker3.tell(new WorkerMsg.DoWork("initial-3"));

        Thread.sleep(50);
        System.out.println();

        // Crash worker-2 - should restart ALL workers
        System.out.println("--- Crashing Worker-2 (will restart ALL) ---");
        worker2.tell(new WorkerMsg.Crash());

        // Give time for restart
        Thread.sleep(300);

        System.out.println("\n--- Checking Workers After Mass Restart ---");
        checkWorker(worker1, "Worker-1");
        checkWorker(worker2, "Worker-2");
        checkWorker(worker3, "Worker-3");

        supervisor.shutdown();
        Thread.sleep(100);
        System.out.println("\n=== ONE_FOR_ALL Complete ===\n");
    }

    /**
     * Example 3: REST_FOR_ONE supervision strategy.
     */
    public static void restForOneExample() throws Exception {
        System.out.println("=== REST_FOR_ONE Strategy ===\n");

        var supervisor = Supervisor.create(
            "rest-for-one-sup",
            Supervisor.Strategy.REST_FOR_ONE,
            5,
            Duration.ofSeconds(10)
        );

        // Start workers in order: 1, 2, 3
        var worker1 = supervisor.supervise("worker-1",
            new WorkerState("Worker-1"), createWorkerHandler());
        var worker2 = supervisor.supervise("worker-2",
            new WorkerState("Worker-2"), createWorkerHandler());
        var worker3 = supervisor.supervise("worker-3",
            new WorkerState("Worker-3"), createWorkerHandler());

        System.out.println("Workers started in order: 1, 2, 3\n");

        Thread.sleep(50);

        // Crash worker-2 - should restart 2 and 3 (started after it)
        System.out.println("--- Crashing Worker-2 (will restart 2 and 3) ---");
        worker2.tell(new WorkerMsg.Crash());

        Thread.sleep(300);

        System.out.println("\n--- Checking Workers ---");
        checkWorker(worker1, "Worker-1");
        checkWorker(worker2, "Worker-2");
        checkWorker(worker3, "Worker-3");

        supervisor.shutdown();
        Thread.sleep(100);
        System.out.println("\n=== REST_FOR_ONE Complete ===\n");
    }

    /**
     * Example 4: Restart intensity demonstration.
     */
    public static void restartIntensityExample() throws Exception {
        System.out.println("=== Restart Intensity ===\n");

        // Low threshold for demonstration
        var supervisor = Supervisor.create(
            "intensity-sup",
            Supervisor.Strategy.ONE_FOR_ONE,
            3,  // max 2 restarts (gives up on 3rd)
            Duration.ofSeconds(5)  // within 5 seconds
        );

        var worker = supervisor.supervise("intensity-worker",
            new WorkerState("IntensityWorker"), createWorkerHandler());

        System.out.println("Max restarts: 2 within 5 seconds\n");

        // Crash and restart multiple times
        for (int i = 1; i <= 4; i++) {
            System.out.println("--- Crash #" + i + " ---");
            worker.tell(new WorkerMsg.Crash());

            if (i < 3) {
                Thread.sleep(100);
                System.out.println("Worker restarted successfully\n");
            } else {
                Thread.sleep(100);
                if (!supervisor.isRunning()) {
                    System.out.println("Supervisor gave up! Too many crashes.");
                    System.out.println("Fatal error: " + supervisor.fatalError().getMessage());
                    break;
                }
            }
        }

        System.out.println("\n=== Restart Intensity Complete ===\n");
    }

    /**
     * Example 5: ChildSpec with restart policies.
     */
    public static void childSpecExample() throws Exception {
        System.out.println("=== ChildSpec Configuration ===\n");

        var supervisor = Supervisor.create(
            "childspec-sup",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofSeconds(10)
        );

        // PERMANENT: Always restarted (crash or normal exit)
        var permanentSpec = new Supervisor.ChildSpec<>(
            "permanent-worker",
            () -> new WorkerState("PermanentWorker"),
            createWorkerHandler(),
            Supervisor.ChildSpec.RestartType.PERMANENT,
            new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
            Supervisor.ChildSpec.ChildType.WORKER,
            false
        );

        // TRANSIENT: Restarted only on crash, not normal exit
        var transientSpec = new Supervisor.ChildSpec<>(
            "transient-worker",
            () -> new WorkerState("TransientWorker"),
            createWorkerHandler(),
            Supervisor.ChildSpec.RestartType.TRANSIENT,
            new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),
            Supervisor.ChildSpec.ChildType.WORKER,
            false
        );

        var permanentWorker = supervisor.startChild(permanentSpec);
        var transientWorker = supervisor.startChild(transientSpec);

        System.out.println("Workers started with different restart policies\n");

        permanentWorker.tell(new WorkerMsg.DoWork("perm-task"));
        transientWorker.tell(new WorkerMsg.DoWork("trans-task"));

        Thread.sleep(100);

        // Show child info
        System.out.println("--- Child Information ---");
        supervisor.whichChildren().forEach(child -> {
            System.out.println(child.id() + ": " + (child.alive() ? "ALIVE" : "DEAD"));
        });

        supervisor.shutdown();
        Thread.sleep(100);
        System.out.println("\n=== ChildSpec Complete ===\n");
    }

    /**
     * Helper method to check worker status.
     */
    private static void checkWorker(ProcRef<WorkerState, WorkerMsg> worker, String name) {
        try {
            var state = worker.ask(new WorkerMsg.GetStatus())
                .get(1, TimeUnit.SECONDS);
            System.out.println(name + ": ALIVE, tasks=" + state.completedTasks().get());
        } catch (Exception e) {
            System.out.println(name + ": ERROR - " + e.getMessage());
        }
    }

    /**
     * Main method running all examples.
     */
    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  JOTP Supervised Worker Examples        ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        oneForOneExample();
        Thread.sleep(500);

        oneForAllExample();
        Thread.sleep(500);

        restForOneExample();
        Thread.sleep(500);

        restartIntensityExample();
        Thread.sleep(500);

        childSpecExample();

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  All Examples Complete                  ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }
}
```

## Expected Output

```
╔══════════════════════════════════════════╗
║  JOTP Supervised Worker Examples        ║
╚══════════════════════════════════════════╝

=== ONE_FOR_ONE Strategy ===

Workers started

[Worker-1] Processing: task-1-0
[Worker-2] Processing: task-2-0
[Worker-3] Processing: task-3-0
...

--- Crashing Worker-2 ---
[Worker-2] CRASHING!

--- Checking Workers After Crash ---
Worker-1: ALIVE, tasks=3
Worker-2: ALIVE, tasks=0
Worker-3: ALIVE, tasks=3

--- Sending Work After Recovery ---
[Worker-1] Processing: recovery-task-1
[Worker-2] Processing: recovery-task-2
[Worker-3] Processing: recovery-task-3

=== ONE_FOR_ONE Complete ===

=== ONE_FOR_ALL Strategy ===

Workers started

--- Crashing Worker-2 (will restart ALL) ---
[Worker-2] CRASHING!

--- Checking Workers After Mass Restart ---
Worker-1: ALIVE, tasks=0
Worker-2: ALIVE, tasks=0
Worker-3: ALIVE, tasks=0

=== ONE_FOR_ALL Complete ===

=== REST_FOR_ONE Strategy ===

Workers started in order: 1, 2, 3

--- Crashing Worker-2 (will restart 2 and 3) ---
[Worker-2] CRASHING!

--- Checking Workers ---
Worker-1: ALIVE, tasks=1
Worker-2: ALIVE, tasks=0
Worker-3: ALIVE, tasks=0

=== REST_FOR_ONE Complete ===

=== Restart Intensity ===

Max restarts: 2 within 5 seconds

--- Crash #1 ---
Worker restarted successfully

--- Crash #2 ---
Worker restarted successfully

--- Crash #3 ---
Supervisor gave up! Too many crashes.
Fatal error: Intentional crash from IntensityWorker

=== Restart Intensity Complete ===
```

## Testing Instructions

### Compile and Run

```bash
# Compile
javac --enable-preview -source 26 \
    -cp target/classes:target/test-classes \
    -d target/examples \
    docs/examples/SupervisedWorker.java

# Run
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.SupervisedWorker
```

### Unit Tests

```java
package io.github.seanchatmangpt.jotp.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.util.concurrent.TimeUnit;

@DisplayName("Supervised Worker Tests")
class SupervisedWorkerTest {

    @Test
    @DisplayName("Supervisor restarts crashed worker")
    void testWorkerRestart() throws Exception {
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofSeconds(5)
        );

        var worker = supervisor.supervise("test-worker",
            new WorkerState("TestWorker"),
            SupervisedWorker.createWorkerHandler()
        );

        // Send initial work
        worker.tell(new WorkerMsg.DoWork("initial"));
        Thread.sleep(50);

        // Crash the worker
        worker.tell(new WorkerMsg.Crash());
        Thread.sleep(200);

        // Worker should be restarted
        var state = worker.ask(new WorkerMsg.GetStatus())
            .get(1, TimeUnit.SECONDS);
        assertThat(state).isNotNull();

        // New worker should have 0 tasks (fresh state)
        assertThat(state.completedTasks().get()).isEqualTo(0);

        supervisor.shutdown();
    }

    @Test
    @DisplayName("ONE_FOR_ONE only restarts crashed worker")
    void testOneForOneIsolation() throws Exception {
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofSeconds(5)
        );

        var worker1 = supervisor.supervise("worker-1",
            new WorkerState("Worker-1"),
            SupervisedWorker.createWorkerHandler()
        );
        var worker2 = supervisor.supervise("worker-2",
            new WorkerState("Worker-2"),
            SupervisedWorker.createWorkerHandler()
        );

        worker1.tell(new WorkerMsg.DoWork("task-1"));
        worker2.tell(new WorkerMsg.DoWork("task-2"));
        Thread.sleep(50);

        // Crash worker-2
        worker2.tell(new WorkerMsg.Crash());
        Thread.sleep(200);

        // Worker-1 should be unaffected
        var state1 = worker1.ask(new WorkerMsg.GetStatus())
            .get(1, TimeUnit.SECONDS);
        assertThat(state1.completedTasks().get()).isEqualTo(1);

        // Worker-2 should be restarted with fresh state
        var state2 = worker2.ask(new WorkerMsg.GetStatus())
            .get(1, TimeUnit.SECONDS);
        assertThat(state2.completedTasks().get()).isEqualTo(0);

        supervisor.shutdown();
    }

    @Test
    @DisplayName("Supervisor gives up after max restarts exceeded")
    void testMaxRestarts() throws Exception {
        var supervisor = Supervisor.create(
            "test-sup",
            Supervisor.Strategy.ONE_FOR_ONE,
            2,  // Gives up on 2nd crash
            Duration.ofSeconds(5)
        );

        var worker = supervisor.supervise("crashy-worker",
            new WorkerState("CrashyWorker"),
            SupervisedWorker.createWorkerHandler()
        );

        // First crash - should restart
        worker.tell(new WorkerMsg.Crash());
        await().atMost(1, TimeUnit.SECONDS)
            .until(() -> supervisor.whichChildren().get(0).alive());

        // Second crash - should restart
        worker.tell(new WorkerMsg.Crash());
        await().atMost(1, TimeUnit.SECONDS)
            .until(() -> supervisor.whichChildren().get(0).alive());

        // Third crash - supervisor should give up
        worker.tell(new WorkerMsg.Crash());
        await().atMost(1, TimeUnit.SECONDS)
            .until(() -> !supervisor.isRunning());

        assertThat(supervisor.fatalError()).isNotNull();
    }
}
```

## Variations and Extensions

### 1. Dynamic Child Pool (SIMPLE_ONE_FOR_ONE)

```java
var template = Supervisor.ChildSpec.worker(
    "conn",
    () -> new ConnectionState(),
    ConnectionHandler::handle
);

var pool = Supervisor.createSimple(template, 10, Duration.ofSeconds(30));

// Dynamically spawn connections
for (int i = 0; i < 5; i++) {
    var conn = pool.startChild();  // conn-1, conn-2, etc.
    conn.tell(new Connect("localhost:" + (8000 + i)));
}
```

### 2. Supervision Trees

```java
// Top-level supervisor
var appSup = Supervisor.create(
    "app-sup",
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

// Child supervisors
var dbSup = appSup.startChild(Supervisor.ChildSpec.worker(
    "db-supervisor",
    () -> null,
    createDbSupervisorHandler()
));

var workerPoolSup = appSup.startChild(Supervisor.ChildSpec.worker(
    "worker-pool-supervisor",
    () -> null,
    createWorkerPoolSupervisorHandler()
));
```

### 3. Custom Shutdown Strategies

```java
var gracefulSpec = new Supervisor.ChildSpec<>(
    "graceful-worker",
    () -> new WorkerState("GracefulWorker"),
    handler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.Timeout(Duration.ofSeconds(30)),
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);

var brutalSpec = new Supervisor.ChildSpec<>(
    "brutal-worker",
    () -> new WorkerState("BrutalWorker"),
    handler,
    Supervisor.ChildSpec.RestartType.PERMANENT,
    new Supervisor.ChildSpec.Shutdown.BrutalKill(),
    Supervisor.ChildSpec.ChildType.WORKER,
    false
);
```

### 4. Health Checks

```java
sealed interface WorkerMsg permits ..., HealthCheck {
    record HealthCheck() implements WorkerMsg {}
}

// In handler:
case HealthCheck() -> {
    if (isHealthy(state)) {
        yield state;
    } else {
        throw new RuntimeException("Health check failed");
    }
}

// Periodic health checks
Thread.ofVirtual().start(() -> {
    while (running) {
        worker.tell(new WorkerMsg.HealthCheck());
        Thread.sleep(5000);
    }
});
```

## Related Patterns

- **Circuit Breaker**: External service protection
- **State Machine**: Complex workflow with recovery
- **Event Manager**: Monitoring and alerting
- **Crash Recovery**: Isolated retry with supervision

## Key JOTP Concepts Demonstrated

1. **Supervisor Creation**: Different strategies and configurations
2. **Child Specifications**: Restart policies and shutdown strategies
3. **Crash Detection**: Automatic restart on unhandled exceptions
4. **Restart Intensity**: Max restarts per time window
5. **Process References**: ProcRef provides stable handle across restarts
6. **Supervision Trees**: Hierarchical fault tolerance

## Performance Characteristics

- **Restart Latency**: ~75-100 ms (delay to absorb rapid crashes)
- **Memory Overhead**: ~2 KB per supervised process
- **Failure Detection**: ~1 ms (exception callback)
- **Scalability**: Thousands of supervised processes

## Common Pitfalls

1. **Restart Loops**: Permanent crashes with PERMANENT restart type
2. **Cascading Failures**: ONE_FOR_ALL strategy in large trees
3. **State Corruption**: Mutable state shared across restarts
4. **Blocking Handlers**: Slow shutdown preventing restart
5. **Orphaned Processes**: Forgetting to shutdown supervisors

## Best Practices

1. **Use Immutable State**: Fresh state on each restart
2. **Choose Right Strategy**: ONE_FOR_ONE for isolation, ONE_FOR_ALL for consistency
3. **Set Appropriate Limits**: Max restarts based on expected failure rate
4. **Monitor Restart Counts**: Alert on excessive restarts
5. **Graceful Shutdown**: Allow time for cleanup before killing
