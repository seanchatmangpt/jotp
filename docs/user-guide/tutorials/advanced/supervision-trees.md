# Advanced JOTP Tutorial: Supervision Trees

## Learning Objectives

By the end of this tutorial, you will:
- Understand the "Let It Crash" philosophy and why supervision matters
- Master all three restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- Build hierarchical supervision trees with ChildSpec
- Configure supervisors with advanced options (max restarts, time windows)
- Design fault-tolerant payment processing systems with supervision

## Prerequisites

- Complete [Beginner Tutorial: Processes](../beginner/processes.md)
- Understand basic `Proc<S,M>` usage
- Familiarity with Java 26 records and sealed interfaces
- Basic understanding of exception handling in Java

## Introduction: Why Supervision?

In traditional programming, we try to catch and handle every exception. JOTP takes a different approach: **"Let It Crash."** Instead of defensive programming, we:
1. **Let processes fail fast** - No complex error handling
2. **Supervisors restart them** - Automatic recovery
3. **State is restored** - From persistent storage or initial state

This approach creates **self-healing systems** that automatically recover from failures.

## Basic Supervisor Setup

### 1. Simple Supervisor with ONE_FOR_ONE Strategy

```java
import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.supervisor.*;

// Define a simple worker process
sealed interface WorkerMessage permits WorkerMessage.Tick, WorkerMessage.Crash {
    record Tick() implements WorkerMessage {}
    record Crash() implements WorkerMessage {}
}

record WorkerState(int count) {}

class WorkerProc {
    static Proc<WorkerState, WorkerMessage> create() {
        return Proc.spawn(
            new WorkerState(0),
            (state, msg) -> {
                if (msg instanceof WorkerMessage.Tick) {
                    System.out.println("Worker tick: " + state.count());
                    return new Proc.Continue<>(new WorkerState(state.count() + 1));
                } else if (msg instanceof WorkerMessage.Crash) {
                    throw new RuntimeException("Simulated crash!");
                }
                return new Proc.Continue<>(state);
            }
        );
    }
}
```

### 2. Creating the Supervisor

```java
class SupervisorExample {
    public static void main(String[] args) throws Exception {
        // Define child specifications
        var childSpec = new Supervisor.ChildSpec(
            "worker1",                           // unique ID
            () -> WorkerProc.create(),           // factory
            Supervisor.RestartStrategy.PERMANENT // always restart
        );

        // Create supervisor with ONE_FOR_ONE strategy
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,  // restart only crashed child
            List.of(childSpec)
        );

        // Get reference to child process
        var workerRef = supervisor.getChild("worker1")
            .orElseThrow();

        // Send some messages
        workerRef.tell(new WorkerMessage.Tick());  // Output: Worker tick: 0
        workerRef.tell(new WorkerMessage.Tick());  // Output: Worker tick: 1

        // Cause a crash - supervisor will restart it
        workerRef.tell(new WorkerMessage.Crash());

        Thread.sleep(100); // Give time for restart

        // Worker is back with fresh state
        workerRef.tell(new WorkerMessage.Tick());  // Output: Worker tick: 0

        supervisor.shutdown();
    }
}
```

## Restart Strategies Deep Dive

### ONE_FOR_ONE

Only the crashed child process is restarted. Other children continue running.

**Use case:** Independent workers where one failure shouldn't affect others.

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    List.of(
        new Supervisor.ChildSpec("db", () -> DBProc.create()),
        new Supervisor.ChildSpec("cache", () -> CacheProc.create()),
        new Supervisor.ChildSpec("worker", () -> WorkerProc.create())
    )
);

// If 'worker' crashes, only 'worker' restarts
// 'db' and 'cache' continue running
```

### ONE_FOR_ALL

When any child crashes, **all** children are terminated and restarted.

**Use case:** Tightly coupled processes with shared state or dependencies.

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ALL,
    List.of(
        new Supervisor.ChildSpec("producer", () -> ProducerProc.create()),
        new Supervisor.ChildSpec("consumer", () -> ConsumerProc.create())
    )
);

// If 'producer' crashes, both 'producer' and 'consumer' restart
// Ensures they restart in sync
```

### REST_FOR_ONE

When a child crashes, it and **all children started after it** are restarted.

**Use case:** Ordered dependencies where later children depend on earlier ones.

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.REST_FOR_ONE,
    List.of(
        new Supervisor.ChildSpec("db", () -> DBProc.create()),           // 1st
        new Supervisor.ChildSpec("cache", () -> CacheProc.create()),    // 2nd
        new Supervisor.ChildSpec("worker", () -> WorkerProc.create())   // 3rd
    )
);

// If 'cache' (2nd) crashes, 'cache' and 'worker' (3rd) restart
// 'db' (1st) continues running
```

## Advanced ChildSpec Configuration

### Restart Intensity

Control how many restarts are allowed within a time window:

```java
var childSpec = new Supervisor.ChildSpec(
    "payment_worker",
    () -> PaymentProc.create(),
    Supervisor.RestartStrategy.TRANSIENT,  // restart only on abnormal exit
    3,    // max restarts
    5     // within 5 seconds
);

// If this child crashes 4 times in 5 seconds,
// the supervisor gives up and stops restarting it
```

### Restart Strategies

- **PERMANENT**: Always restart, even on normal exit
- **TRANSIENT**: Restart only on abnormal exit (exception)
- **TEMPORARY**: Never restart

```java
var permanent = new Supervisor.ChildSpec(
    "critical_service",
    () -> CriticalServiceProc.create(),
    Supervisor.RestartStrategy.PERMANENT  // Always restart
);

var transient = new Supervisor.ChildSpec(
    "worker",
    () -> WorkerProc.create(),
    Supervisor.RestartStrategy.TRANSIENT  // Only on crash
);

var temporary = new Supervisor.ChildSpec(
    "one_shot",
    () -> OneShotProc.create(),
    Supervisor.RestartStrategy.TEMPORARY  // Never restart
);
```

## Building Supervision Trees

Supervisors can supervise other supervisors, creating hierarchical trees.

```java
class SupervisionTreeExample {
    public static void main(String[] args) throws Exception {
        // Level 2: Worker supervisor
        var workerChildSpecs = List.of(
            new Supervisor.ChildSpec("worker1", () -> WorkerProc.create()),
            new Supervisor.ChildSpec("worker2", () -> WorkerProc.create())
        );

        var workerSupervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            workerChildSpecs
        );

        // Level 1: Main supervisor
        var mainChildSpecs = List.of(
            new Supervisor.ChildSpec(
                "db",
                () -> DBProc.create(),
                Supervisor.RestartStrategy.PERMANENT
            ),
            new Supervisor.ChildSpec(
                "workers",
                () -> workerSupervisor,
                Supervisor.RestartStrategy.PERMANENT
            )
        );

        var mainSupervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            mainChildSpecs
        );

        // Tree structure:
        // main_supervisor
        //   ├── db
        //   └── worker_supervisor
        //         ├── worker1
        //         └── worker2

        mainSupervisor.shutdown();
    }
}
```

## Exercise: Payment Processing System

Build a fault-tolerant payment processing system with supervision.

### Requirements

1. **Payment Worker Process**
   - Processes payment messages
   - Randomly crashes (simulating transient failures)
   - Tracks successful payments in state

2. **Payment Supervisor**
   - Uses `ONE_FOR_ONE` strategy
   - Restarts crashed workers
   - Max 3 restarts per 5 seconds

3. **Payment Orchestrator**
   - Supervises payment workers and database
   - Uses `REST_FOR_ONE` strategy
   - Database restarts affect workers

### Solution

```java
// Payment messages
sealed interface PaymentMessage permits
    PaymentMessage.ProcessPayment,
    PaymentMessage.GetStats {

    record ProcessPayment(String id, double amount) implements PaymentMessage {}
    record GetStats(ProcRef<PaymentStats, PaymentResponse> replyTo) implements PaymentMessage {}
}

sealed interface PaymentResponse permits
    PaymentResponse.Stats,
    PaymentResponse.Error {

    record Stats(int processed, double total) implements PaymentResponse {}
    record Error(String message) implements PaymentResponse {}
}

// Payment worker state
record PaymentState(
    int processedCount,
    double totalAmount,
    int crashCount
) {
    PaymentState() {
        this(0, 0.0, 0);
    }
}

// Payment worker process
class PaymentProc {
    private static final Random RANDOM = new Random();

    static Proc<PaymentState, PaymentMessage> create() {
        return Proc.spawn(
            new PaymentState(),
            (state, msg) -> {
                if (msg instanceof PaymentMessage.ProcessPayment p) {
                    // Simulate random crash (10% chance)
                    if (RANDOM.nextDouble() < 0.1) {
                        state = new PaymentState(
                            state.processedCount(),
                            state.totalAmount(),
                            state.crashCount() + 1
                        );
                        throw new RuntimeException(
                            "Payment worker crashed (crash #" + state.crashCount() + ")"
                        );
                    }

                    // Process payment successfully
                    var newState = new PaymentState(
                        state.processedCount() + 1,
                        state.totalAmount() + p.amount(),
                        state.crashCount()
                    );

                    System.out.printf(
                        "✓ Payment %s: $%.2f (total processed: %d)%n",
                        p.id(), p.amount(), newState.processedCount()
                    );

                    return new Proc.Continue<>(newState);

                } else if (msg instanceof PaymentMessage.GetStats g) {
                    g.replyTo().tell(new PaymentResponse.Stats(
                        state.processedCount(),
                        state.totalAmount()
                    ));
                    return new Proc.Continue<>(state);
                }

                return new Proc.Continue<>(state);
            }
        );
    }
}

// Database process (simplified)
sealed interface DBMessage permits DBMessage.Store, DBMessage.Query {}

record DBState() {}

class DBProc {
    static Proc<DBState, DBMessage> create() {
        return Proc.spawn(
            new DBState(),
            (state, msg) -> {
                System.out.println("🗄️  DB: Processing " + msg);
                return new Proc.Continue<>(state);
            }
        );
    }
}

// Main payment system
class PaymentSystem {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Payment Processing System ===\n");

        // Create payment workers supervisor
        var paymentWorkers = List.of(
            new Supervisor.ChildSpec(
                "payment1",
                () -> PaymentProc.create(),
                Supervisor.RestartStrategy.TRANSIENT,
                3,  // max 3 restarts
                5   // per 5 seconds
            )
        );

        var paymentSupervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            paymentWorkers
        );

        // Create main supervisor
        var mainChildSpecs = List.of(
            new Supervisor.ChildSpec(
                "db",
                () -> DBProc.create(),
                Supervisor.RestartStrategy.PERMANENT
            ),
            new Supervisor.ChildSpec(
                "payments",
                () -> paymentSupervisor,
                Supervisor.RestartStrategy.PERMANENT
            )
        );

        var mainSupervisor = Supervisor.create(
            Supervisor.Strategy.REST_FOR_ONE,
            mainChildSpecs
        );

        // Get reference to payment worker
        var paymentRef = paymentSupervisor.getChild("payment1")
            .orElseThrow();

        // Process payments
        for (int i = 1; i <= 20; i++) {
            paymentRef.tell(new PaymentMessage.ProcessPayment(
                "PAY-" + i,
                100.0 + i
            ));
            Thread.sleep(200);
        }

        // Get final stats
        var statsRef = Proc.spawn(
            null,
            (state, msg) -> new Proc.Continue<>(state)
        );

        paymentRef.tell(new PaymentMessage.GetStats(statsRef));

        Thread.sleep(1000);

        System.out.println("\n=== Payment System Complete ===");
        mainSupervisor.shutdown();
    }
}
```

## What You Learned

- **"Let It Crash" philosophy**: Embrace failure instead of defensive programming
- **Three restart strategies**: ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE
- **ChildSpec configuration**: Control restart behavior with intensity and strategies
- **Hierarchical trees**: Build complex supervision hierarchies
- **Real-world patterns**: Fault-tolerant payment processing system

## Next Steps

- [State Machines Tutorial](state-machines.md) - Add stateful workflows to your processes
- [Fault Tolerance Tutorial](fault-tolerance.md) - Advanced crash recovery patterns
- [Event Management Tutorial](event-management.md) - Pub/sub messaging patterns

## Additional Exercises

1. **Dynamic Child Management**: Add methods to start/stop children dynamically
2. **Supervisor Monitoring**: Add a monitoring process that tracks supervisor events
3. **Hot Code Swapping**: Implement code reloading without stopping the supervisor
4. **Custom Strategies**: Implement domain-specific restart strategies
5. **Observability**: Add metrics collection for restarts and crashes

## Further Reading

- [Erlang/OTP Supervision Trees](https://www.erlang.org/doc/design_principles/sup_princ)
- [Let It Crash Philosophy](https://www.erlang.org/doc/design_principles/error_handling)
- [JOTP Supervisor API](../../api/io/github/seanchatmangpt/jotp/supervisor/Supervisor.html)
