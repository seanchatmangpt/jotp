# Advanced JOTP Tutorial: Fault Tolerance

## Learning Objectives

By the end of this tutorial, you will:
- Understand the "Let It Crash" philosophy in depth
- Master crash recovery patterns with `CrashRecovery`
- Use `ProcLink` for bilateral process monitoring
- Use `ProcMonitor` for unilateral process monitoring
- Build production-ready crash-resilient services

## Prerequisites

- Complete [Supervision Trees Tutorial](supervision-trees.md)
- Complete [State Machines Tutorial](state-machines.md)
- Understand process lifecycle and supervision
- Familiarity with error handling patterns

## Introduction: Let It Crash Philosophy

Traditional error handling:
```java
// ❌ Traditional: Defensive programming
try {
    processData(data);
} catch (ValidationException e) {
    // Handle validation error
} catch (NetworkException e) {
    // Retry logic
} catch (DatabaseException e) {
    // Fallback to cache
} catch (Exception e) {
    // Catch-all handler
}
```

JOTP "Let It Crash" approach:
```java
// ✅ JOTP: Let it crash, supervisor handles it
processData(data);  // Throw exceptions freely

// Supervisor automatically:
// 1. Logs the crash
// 2. Restarts the process
// 3. Restores state from persistence
```

**Benefits:**
- **Simpler code**: No complex error handling
- **Consistent recovery**: Supervisors guarantee restart logic
- **Better observability**: Crashes are visible events
- **Fault isolation**: Failures don't cascade

## Crash Recovery Patterns

### 1. Basic Crash Recovery

```java
import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.crash.*;

// Unreliable service that crashes randomly
record UnreliableState(int attempt) {}

class UnreliableService {
    private static final Random RANDOM = new Random();

    static Proc<UnreliableState, String> create() {
        return Proc.spawn(
            new UnreliableState(0),
            (state, msg) -> {
                // 30% chance of crashing
                if (RANDOM.nextDouble() < 0.3) {
                    throw new RuntimeException("Random failure!");
                }

                System.out.println("✓ Processed: " + msg + " (attempt " + state.attempt() + ")");
                return new Proc.Continue<>(new UnreliableState(state.attempt() + 1));
            }
        );
    }
}
```

### 2. Supervised Crash Recovery

```java
class CrashRecoveryExample {
    public static void main(String[] args) throws Exception {
        // Create supervisor with restart intensity
        var childSpec = new Supervisor.ChildSpec(
            "unreliable",
            () -> UnreliableService.create(),
            Supervisor.RestartStrategy.TRANSIENT,  // Restart on crash
            5,   // Max 5 restarts
            10   // Per 10 seconds
        );

        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            List.of(childSpec)
        );

        var service = supervisor.getChild("unreliable").orElseThrow();

        // Send messages - crashes are transparent
        for (int i = 0; i < 20; i++) {
            service.tell("Message-" + i);
            Thread.sleep(100);
        }

        supervisor.shutdown();
    }
}
```

### 3. Crash Recovery with State Restoration

```java
record ServiceState(
    String config,
    List<String> processed,
    boolean initialized
) {
    ServiceState(String config) {
        this(config, new ArrayList<>(), false);
    }
}

class StatefulService {
    private static final String PERSISTENCE_FILE = "/tmp/service_state.json";

    static Proc<ServiceState, String> create() {
        // Load persisted state if available
        var initialState = loadPersistedState();

        return Proc.spawn(
            initialState,
            (state, msg) -> {
                // Initialize if needed
                if (!state.initialized()) {
                    System.out.println("🔄 Initializing service...");
                    // Expensive initialization (DB connection, cache warming, etc.)
                    Thread.sleep(100);

                    var initialized = new ServiceState(
                        state.config(),
                        state.processed(),
                        true
                    );

                    persistState(initialized);
                    return new Proc.Continue<>(initialized);
                }

                // Process message
                var updated = new ServiceState(
                    state.config(),
                    append(state.processed(), msg),
            true
        );

                System.out.println("✓ Processed: " + msg);
                persistState(updated);
                return new Proc.Continue<>(updated);
            }
        );
    }

    private static ServiceState loadPersistedState() {
        try {
            // In production, load from database
            return new ServiceState("default-config");
        } catch (Exception e) {
            return new ServiceState("default-config");
        }
    }

    private static void persistState(ServiceState state) {
        // In production, save to database
        // For demo, we'll just continue
    }

    private static List<String> append(List<String> list, String item) {
        var result = new ArrayList<>(list);
        result.add(item);
        return List.copyOf(result);
    }
}
```

## ProcLink: Bilateral Monitoring

`ProcLink` creates a bidirectional crash relationship - if either process crashes, both terminate.

```java
import io.github.seanchatmangpt.jotp.link.*;

class ProcLinkExample {
    public static void main(String[] args) throws Exception {
        // Create two processes
        var proc1 = Proc.spawn(
            null,
            (state, msg) -> {
                System.out.println("Proc 1: " + msg);
                return new Proc.Continue<>(state);
            }
        );

        var proc2 = Proc.spawn(
            null,
            (state, msg) -> {
                System.out.println("Proc 2: " + msg);

                // Simulate crash on specific message
                if ("CRASH".equals(msg)) {
                    throw new RuntimeException("Proc 2 crashed!");
                }

                return new Proc.Continue<>(state);
            }
        );

        // Link them together
        ProcLink.link(proc1, proc2);

        // Send messages
        proc1.tell("Hello from 1");
        proc2.tell("Hello from 2");

        proc2.tell("CRASH");  // Both processes will terminate

        Thread.sleep(100);

        // Neither process is alive now
        System.out.println("Proc 1 alive: " + proc1.isAlive());
        System.out.println("Proc 2 alive: " + proc2.isAlive());
    }
}
```

### ProcLink Use Cases

1. **Parent-child relationships**: Parent and child must live or die together
2. **Critical pairs**: Two processes that depend on each other
3. **All-or-nothing**: Both must succeed or both fail

```java
// Example: Database connection pool manager
class ConnectionPoolManager {
    static void create() {
        var manager = Proc.spawn(null, (state, msg) -> {
            // Manager logic
            return new Proc.Continue<>(state);
        });

        var healthChecker = Proc.spawn(null, (state, msg) -> {
            // Health check logic - crash if unhealthy
            return new Proc.Continue<>(state);
        });

        // Link: if health checker crashes, manager also terminates
        ProcLink.link(manager, healthChecker);

        // Supervisor will restart both
        var spec = new Supervisor.ChildSpec(
            "pool_manager",
            () -> manager,
            Supervisor.RestartStrategy.PERMANENT
        );
    }
}
```

## ProcMonitor: Unilateral Monitoring

`ProcMonitor` allows one process to monitor another without being linked. The monitor receives a `DOWN` message when the monitored process crashes.

```java
import io.github.seanchatmangpt.jotp.monitor.*;

sealed interface MonitorMessage permits
    MonitorMessage.WorkerDone,
    MonitorMessage.Down {

    record WorkerDone() implements MonitorMessage {}
    record Down(ProcRef<?, ?> monitored, Throwable reason) implements MonitorMessage {}
}

record MonitorState(boolean workerAlive) {}

class MonitorExample {
    public static void main(String[] args) throws Exception {
        // Create worker process
        var worker = Proc.spawn(
            null,
            (state, msg) -> {
                System.out.println("Worker processing...");

                // Simulate crash after some work
                if (Math.random() < 0.3) {
                    throw new RuntimeException("Worker failed!");
                }

                return new Proc.Continue<>(state);
            }
        );

        // Create monitor process
        var monitor = Proc.spawn(
            new MonitorState(true),
            ProcMonitor.monitor(
                worker,
                (state, msg) -> {
                    if (msg instanceof String workMsg) {
                        // Regular message handling
                        return new Proc.Continue<>(state);

                    } else if (msg instanceof MonitorMessage.Down down) {
                        System.out.println("⚠️  Worker crashed: " + down.reason());

                        // Handle crash - maybe restart worker
                        return new Proc.Continue<>(
                            new MonitorState(false)
                        );
                    }

                    return new Proc.Continue<>(state);
                }
            )
        );

        // Send work to monitored process
        for (int i = 0; i < 10; i++) {
            worker.tell("Task-" + i);
            Thread.sleep(100);
        }

        Thread.sleep(1000);
        monitor.shutdown();
    }
}
```

### ProcMonitor Use Cases

1. **Health monitoring**: Detect and log crashes
2. **Alerting**: Send alerts when critical processes crash
3. **Graceful degradation**: Switch to fallback when process crashes
4. **Restart with delay**: Wait before restarting (exponential backoff)

```java
// Example: Health monitor with alerting
class HealthMonitor {
    static Proc<?, ?> create(
        ProcRef<?, ?> service,
        String serviceName
    ) {
        return Proc.spawn(
            null,
            ProcMonitor.monitor(
                service,
                (state, msg) -> {
                    if (msg instanceof MonitorMessage.Down down) {
                        // Send alert
                        sendAlert(
                            serviceName + " crashed: " + down.reason().getMessage()
                        );

                        // Log incident
                        logIncident(serviceName, down.reason());

                        // Optional: Restart with delay
                        Thread.sleep(5000);  // 5 second backoff
                        restartService(serviceName);
                    }

                    return new Proc.Continue<>(state);
                }
            )
        );
    }

    private static void sendAlert(String message) {
        System.out.println("🚨 ALERT: " + message);
    }

    private static void logIncident(String service, Throwable reason) {
        System.out.println("📋 Incident logged: " + service);
    }

    private static void restartService(String serviceName) {
        System.out.println("🔄 Restarting: " + serviceName);
    }
}
```

## Advanced Pattern: Crash Recovery with Retry

```java
import io.github.seanchatmangpt.jotp.result.*;

record RetryState<T, E>(
    int attempts,
    Duration delay
) {}

class RetryProc {
    static <T, E> Proc<RetryState<T, E>, Result<T, E>> create(
        java.util.function.Supplier<Result<T, E>> operation,
        int maxAttempts,
        Duration initialDelay
    ) {
        return Proc.spawn(
            new RetryState<T, E>(0, initialDelay),
            (state, msg) -> {
                if (msg instanceof Result<T, E> result) {
                    return switch (result) {
                        case Result.Ok<T, E> ok -> {
                            System.out.println("✓ Operation succeeded");
                            yield new Proc.Continue<>(state);
                        }

                        case Result.Err<T, E> err -> {
                            int attempts = state.attempts() + 1;

                            if (attempts >= maxAttempts) {
                                System.out.println("✗ Max retries exceeded");
                                yield new Proc.Continue<>(state);
                            }

                            System.out.printf(
                                "⚠️  Attempt %d failed, retrying in %dms%n",
                                attempts,
                                state.delay().toMillis()
                            );

                            // Exponential backoff
                            var nextDelay = state.delay().multipliedBy(2);

                            // Schedule retry
                            var self = Proc.self();
                            ProcTimer.sendOnce(
                                msg,
                                state.delay(),
                                self
                            );

                            yield new Proc.Continue<>(
                                new RetryState<>(attempts, nextDelay)
                            );
                        }
                    };
                }

                return new Proc.Continue<>(state);
            }
        );
    }
}
```

## Exercise: Crash-Resilient Payment Service

Build a payment service that handles crashes gracefully.

### Requirements

1. **Payment Worker**: Processes payments, randomly crashes
2. **Crash Recovery**: Restarts worker, maintains in-flight payment tracking
3. **Monitoring**: Monitors worker health, sends alerts
4. **State Persistence**: Saves payment state before processing
5. **Retry Logic**: Retries failed payments with exponential backoff

### Solution

```java
// Payment states
sealed interface PaymentState permits
    PaymentState.Pending,
    PaymentState.Processing,
    PaymentState.Completed,
    PaymentState.Failed {

    record Pending() implements PaymentState {}
    record Processing(String paymentId) implements PaymentState {}
    record Completed(String transactionId) implements PaymentState {}
    record Failed(String reason) implements PaymentState {}
}

// Payment messages
sealed interface PaymentServiceMessage permits
    PaymentServiceMessage.ProcessPayment,
    PaymentServiceMessage.PaymentResult,
    PaymentServiceMessage.WorkerDown {

    record ProcessPayment(
        String paymentId,
        double amount,
        ProcRef<PaymentState, PaymentResponse> replyTo
    ) implements PaymentServiceMessage {}

    record PaymentResult(
        String paymentId,
        PaymentState status
    ) implements PaymentServiceMessage {}

    record WorkerDown(Throwable reason) implements PaymentServiceMessage {}
}

// Response messages
sealed interface PaymentResponse permits
    PaymentResponse.Accepted,
    PaymentResponse.Completed,
    PaymentResponse.Failed {

    record Accepted() implements PaymentResponse {}
    record Completed(String transactionId) implements PaymentResponse {}
    record Failed(String reason) implements PaymentResponse {}
}

// Service state with in-flight tracking
record PaymentServiceState(
    Map<String, PaymentState> inFlightPayments
) {
    PaymentServiceState() {
        this(new ConcurrentHashMap<>());
    }
}

class PaymentService {
    static Proc<PaymentServiceState, PaymentServiceMessage> create() {
        return Proc.spawn(
            new PaymentServiceState(),
            (state, msg) -> {
                if (msg instanceof PaymentServiceMessage.ProcessPayment p) {
                    // Save payment state to persistent storage
                    var paymentState = new PaymentState.Processing(p.paymentId());
                    state.inFlightPayments().put(p.paymentId(), paymentState);

                    System.out.println("💳 Processing payment: " + p.paymentId());

                    // Simulate payment processing with possible crash
                    if (Math.random() < 0.2) {
                        throw new RuntimeException("Payment gateway timeout");
                    }

                    // Payment succeeded
                    var completed = new PaymentState.Completed("TXN-" + p.paymentId());
                    state.inFlightPayments().put(p.paymentId(), completed);

                    p.replyTo().tell(new PaymentResponse.Completed("TXN-" + p.paymentId()));

                    return new Proc.Continue<>(state);

                } else if (msg instanceof PaymentServiceMessage.WorkerDown down) {
                    System.out.println("⚠️  Worker crashed: " + down.reason().getMessage());

                    // Recover in-flight payments
                    for (var entry : state.inFlightPayments().entrySet()) {
                        if (entry.getValue() instanceof PaymentState.Processing proc) {
                            System.out.println("🔄 Recovering payment: " + entry.getKey());

                            // Mark as failed and retry
                            var failed = new PaymentState.Failed("Service crash - retrying");
                            state.inFlightPayments().put(entry.getKey(), failed);

                            // Trigger retry (in real system, would use proper retry mechanism)
                            System.out.println("🔄 Scheduling retry for: " + entry.getKey());
                        }
                    }

                    return new Proc.Continue<>(state);
                }

                return new Proc.Continue<>(state);
            }
        );
    }

    static Proc<?, ?> createMonitor(ProcRef<PaymentServiceState, PaymentServiceMessage> service) {
        return Proc.spawn(
            null,
            ProcMonitor.monitor(
                service,
                (state, msg) -> {
                    if (msg instanceof PaymentServiceMessage.WorkerDown down) {
                        // Notify monitoring system
                        sendAlert("Payment service crashed: " + down.reason().getMessage());

                        // Notify will restart via supervisor
                    }
                    return new Proc.Continue<>(state);
                }
            )
        );
    }

    private static void sendAlert(String message) {
        System.out.println("🚨 ALERT: " + message);
    }
}

// Main payment system
class CrashResilientPaymentSystem {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Crash-Resilient Payment System ===\n");

        // Create payment service with supervision
        var childSpec = new Supervisor.ChildSpec(
            "payment_service",
            () -> PaymentService.create(),
            Supervisor.RestartStrategy.TRANSIENT,
            5,  // Max 5 restarts
            10  // Per 10 seconds
        );

        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            List.of(childSpec)
        );

        var service = supervisor.getChild("payment_service").orElseThrow();

        // Create monitor
        var monitor = PaymentService.createMonitor(service);

        // Create client process
        var client = Proc.spawn(
            null,
            (state, msg) -> {
                if (msg instanceof PaymentResponse response) {
                    System.out.println("Client received: " + response);
                }
                return new Proc.Continue<>(state);
            }
        );

        // Process payments
        for (int i = 1; i <= 20; i++) {
            var paymentId = "PAY-" + i;
            service.tell(new PaymentServiceMessage.ProcessPayment(
                paymentId,
                100.0 + i,
                client
            ));
            Thread.sleep(200);
        }

        Thread.sleep(2000);

        System.out.println("\n=== Payment System Complete ===");
        supervisor.shutdown();
        monitor.shutdown();
        client.shutdown();
    }
}
```

## What You Learned

- **Let It Crash philosophy**: Embracing failure for simpler, more reliable code
- **Crash recovery**: Automatic restart with state restoration
- **ProcLink**: Bilateral process monitoring for critical pairs
- **ProcMonitor**: Unilateral monitoring for health checks and alerting
- **Retry patterns**: Exponential backoff for transient failures
- **Production patterns**: Crash-resilient payment processing service

## Next Steps

- [Event Management Tutorial](event-management.md) - Broadcast crash events to listeners
- [Distributed Systems Tutorial](distributed-systems.md) - Multi-node failure detection
- [Advanced State Machines](state-machines.md) - Add state machines to crash recovery

## Additional Exercises

1. **Circuit Breaker**: Implement circuit breaker pattern for failing services
2. **Bulkheading**: Isolate failures using separate process groups
3. **Dead Letter Queue**: Capture and analyze failed messages
4. **Chaos Engineering**: Build tools to randomly crash processes for testing
5. **Metrics Collection**: Track crash rates, recovery times, and MTTR

## Further Reading

- [Circuit Breaker Pattern](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Let It Crash (Joe Armstrong)](https://www.erlang.org/doc/design_principles/error_handling)
- [Release It! (Michael Nygard)](https://www.oreilly.com/library/view/release-it-/9781680506793/)
