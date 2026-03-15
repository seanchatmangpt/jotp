## Part II: Fault Tolerance (Chapters 5-7)

## Chapter 5: Let It Crash Philosophy

**"Defensive programming is like trying to prevent cancer by never leaving your house. You might avoid some risks, but you're not actually living — and you'll still get sick eventually."**
— Joe Armstrong

### 5.1 Why Defensive Programming Fails

Defensive programming has been the dominant paradigm in enterprise Java for decades. The idea is simple: **prevent errors from happening in the first place**. Check every input, catch every exception, validate every assumption.

But this approach has fatal flaws in production systems.

#### The Fallacy of Error Prevention

Consider this typical defensive code:

```java
public class PaymentService {
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;
    private final Validator validator;
    private final FallbackProvider fallbackProvider;

    public Result<Payment, Error> processPayment(PaymentRequest request) {
        try {
            // Validate input
            var errors = validator.validate(request);
            if (!errors.isEmpty()) {
                return Result.failure(new ValidationError(errors));
            }

            // Check circuit breaker
            if (circuitBreaker.isOpen()) {
                return Result.failure(new CircuitBreakerOpenError());
            }

            // Try with retry
            return retryPolicy.execute(() -> {
                return gateway.charge(request);
            });

        } catch (GatewayTimeoutException e) {
            // Fallback to备用支付网关
            return fallbackProvider.process(request);
        } catch (DatabaseException e) {
            // Try cached result
            return Result.failure(new TemporaryError());
        } catch (Exception e) {
            // Catch-all: log and return generic error
            logger.error("Unexpected error", e);
            return Result.failure(new UnknownError());
        }
    }
}
```

**What's wrong with this code?**

1. **Mutation accumulation:** Each error handling path can leave state in an inconsistent state. Is the circuit breaker still accurate after that timeout? Did the retry policy update its counters correctly?

2. **Hidden bugs:** The catch-all `Exception` handler swallows unexpected errors. You'll never know if a new type of failure occurs until customers report it.

3. **Testing nightmare:** How do you test the retry logic? The fallback? The circuit breaker? Each conditional branch multiplies test complexity.

4. **False confidence:** The code looks robust, but it's fragile. A single missed edge case crashes the system.

#### The Erlang Revelation

Joe Armstrong discovered a counterintuitive truth while building telecommunications systems:

**Systems that expect failure are more reliable than systems that try to prevent it.**

Instead of trying to prevent every possible error, Erlang systems **embrace failure**. When something goes wrong, the process crashes immediately. But crucially, **the system as a whole keeps running**.

This is the "let it crash" philosophy.

### 5.2 Crash as a Signal, Not a Failure

In JOTP, a crash is not a failure — it's **information**.

Think of a crash like a pain signal in your body. When you touch a hot stove, your nerves send a pain signal to your brain. Your brain then decides what to do: pull away, run cold water, seek medical attention.

The pain signal itself isn't bad — it's useful information that helps you avoid worse damage.

**The same logic applies to software:**

```java
// Bad: Hide the crash
public Result<Payment, Error> processPayment(PaymentRequest request) {
    try {
        return gateway.charge(request);
    } catch (Exception e) {
        logger.error("Payment failed", e);
        return Result.failure(new PaymentError()); // Crash hidden!
    }
}

// Good: Let it crash, supervisor handles recovery
public Payment processPayment(PaymentRequest request) {
    // No try-catch! If gateway.charge() throws, process crashes.
    // Supervisor will restart with fresh state.
    return gateway.charge(request);
}
```

**Why the second approach is better:**

1. **Clean code:** No nested error handling. The happy path is clear.

2. **Fresh state:** After crash and restart, the process starts with a clean slate. No corrupted state, no stale caches.

3. **Supervisor control:** The supervisor decides how to handle the crash based on **policy**, not ad-hoc error handling.

4. **Observable:** Crashes are logged and monitored. You know exactly what's failing.

### 5.3 Supervisor Trees: Topology as Error Handling

The real power of "let it crash" emerges when you organize processes into **supervision trees**.

A supervision tree is a hierarchy where:
- **Supervisor processes** monitor child processes
- **Child processes** do the actual work
- When a child crashes, the supervisor decides what to do

#### Visualizing a Supervision Tree

```
                    [Application Supervisor]
                            |
            +---------------+---------------+
            |               |               |
    [Payment Supervisor] [Order Supervisor] [Inventory Supervisor]
            |               |               |
    +-------+-------+       |               |
    |       |       |       |               |
[Auth] [Gateway] [DB]  [OrderState]  [StockChecker]
```

**How it works:**

1. The **Application Supervisor** starts three child supervisors: Payment, Order, and Inventory.

2. Each **Department Supervisor** starts worker processes: Auth, Gateway, DB for payments; OrderState for orders; StockChecker for inventory.

3. If **[Gateway]** crashes:
   - The **Payment Supervisor** restarts it automatically
   - **[Auth]** and **[DB]** keep running (they're independent)
   - The **Order** and **Inventory** supervisors are unaffected

4. If the **Payment Supervisor** itself crashes (after too many restarts):
   - The **Application Supervisor** restarts the entire payment department
   - All payment workers (Auth, Gateway, DB) restart with fresh state

**This is topology as error handling.** The structure of your tree **is** your error handling strategy.

### 5.4 Crash Recovery Strategies

JOTP supervisors support three restart strategies, each suited for different scenarios.

#### ONE_FOR_ONE: Isolated Failures

**Strategy:** Only the crashed child is restarted. Other children are unaffected.

**Best for:** Independent workers with no shared state.

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                              // Max 5 restarts within window
    Duration.ofMinutes(1)           // 1-minute sliding window
);

// Start 10 independent workers
for (int i = 0; i < 10; i++) {
    supervisor.supervise(
        "worker-" + i,
        0,                          // Initial state
        (state, msg) -> state + 1   // Handler
    );
}

// If worker-3 crashes:
// - Only worker-3 is restarted
// - Workers 0-2, 4-9 keep running
```

**Use case:** Payment gateway pool. Each gateway connection is independent. If one crashes, restart just that connection.

#### ONE_FOR_ALL: Atomic Service Groups

**Strategy:** When any child crashes, **all children** are restarted.

**Best for:** Tightly coupled services where partial state is invalid.

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ALL,
    3,
    Duration.ofMinutes(5)
);

supervisor.supervise("database", DBState::new, DBHandler::handle);
supervisor.supervise("cache", CacheState::new, CacheHandler::handle);
supervisor.supervise("index", IndexState::new, IndexHandler::handle);

// If database crashes:
// - cache is restarted (drops stale cache)
// - index is restarted (rebuilds from DB)
// - All three start together with consistent state
```

**Use case:** Database + cache + search index. These must be consistent. If the database restarts, the cache and index must also restart to avoid stale data.

#### REST_FOR_ONE: Dependency-Ordered Restarts

**Strategy:** The crashed child **and all children started after it** are restarted.

**Best for:** Hierarchical dependencies where later children depend on earlier ones.

```java
var supervisor = Supervisor.create(
    Supervisor.Strategy.REST_FOR_ONE,
    5,
    Duration.ofMinutes(1)
);

// Start in dependency order
supervisor.supervise("config", ConfigState::new, ConfigHandler::handle);
supervisor.supervise("connection", ConnState::new, ConnHandler::handle);
supervisor.supervise("session", SessionState::new, SessionHandler::handle);
supervisor.supervise("transaction", TxState::new, TxHandler::handle);

// If connection crashes:
// - connection is restarted
// - session is restarted (depends on connection)
// - transaction is restarted (depends on session)
// - config keeps running (connection depends on config, not vice versa)
```

**Use case:** Connection pooling. Config → Connection → Session → Transaction. If the connection layer crashes, sessions and transactions must restart, but config can stay.

### 5.5 Complete Example: Crash Demo with Chaos Monkey

Let's build a complete example that demonstrates crash and recovery. We'll create a **Chaos Monkey** that randomly kills processes and observe how the supervisor responds.

#### Step 1: Define Message Types

```java
package com.example.crashdemo;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

// Sealed message hierarchy for type safety
sealed interface WorkerMsg permits WorkerMsg.Increment, WorkerMsg.Crash, WorkerMsg.Get {
    record Increment() implements WorkerMsg {}
    record Crash() implements WorkerMsg {}  // Triggers intentional crash
    record Get() implements WorkerMsg {}
}

// Worker state: just a counter
record WorkerState(int value, int crashCount) {}
```

#### Step 2: Create a Flaky Worker

```java
class FlakyWorker {
    private static final Random RANDOM = new Random();

    public static Proc<WorkerState, WorkerMsg> create(String id) {
        var handler = (WorkerState state, WorkerMsg msg) -> switch (msg) {
            case WorkerMsg.Increment() -> {
                // 10% chance of random crash
                if (RANDOM.nextInt(10) == 0) {
                    throw new RuntimeException("Random crash in " + id);
                }
                yield new WorkerState(state.value() + 1, state.crashCount());
            }
            case WorkerMsg.Crash() -> {
                // Intentional crash
                throw new RuntimeException("Intentional crash in " + id);
            }
            case WorkerMsg.Get() -> state;
        };

        return Proc.spawn(
            new WorkerState(0, 0),
            handler
        );
    }
}
```

#### Step 3: Create the Supervisor

```java
class CrashDemo {
    public static void main(String[] args) throws Exception {
        // Create supervisor with ONE_FOR_ONE strategy
        var supervisor = Supervisor.create(
            "crash-demo-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            10,                         // Allow up to 10 restarts per minute
            Duration.ofMinutes(1)
        );

        // Start 5 flaky workers
        for (int i = 0; i < 5; i++) {
            String workerId = "worker-" + i;
            supervisor.supervise(
                workerId,
                new WorkerState(0, 0),
                (state, msg) -> {
                    // Same handler as FlakyWorker
                    if (msg instanceof WorkerMsg.Increment && Math.random() < 0.1) {
                        throw new RuntimeException("Random crash");
                    }
                    if (msg instanceof WorkerMsg.Crash) {
                        throw new RuntimeException("Intentional crash");
                    }
                    if (msg instanceof WorkerMsg.Increment) {
                        return new WorkerState(state.value() + 1, state.crashCount());
                    }
                    return state; // Get
                }
            );
        }

        System.out.println("Started 5 flaky workers. Let the chaos begin!");

        // Send messages and observe crashes
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 5; j++) {
                var ref = supervisor.whichChildren().get(j);
                if (ref.alive()) {
                    // Send random message
                    if (Math.random() < 0.1) {
                        ref.tell(new WorkerMsg.Crash());  // 10% chance to trigger crash
                    } else {
                        ref.tell(new WorkerMsg.Increment());
                    }
                }
            }
            Thread.sleep(100);
        }

        // Check final state
        System.out.println("\nFinal state after chaos:");
        for (var child : supervisor.whichChildren()) {
            if (child.alive()) {
                var state = child.ask(new WorkerMsg.Get(), Duration.ofSeconds(1)).get();
                System.out.println(child.id() + ": value=" + state.value() +
                                 ", crashes=" + state.crashCount());
            }
        }

        supervisor.shutdown();
    }
}
```

#### Step 4: Run and Observe

```bash
mvnd compile
mvnd exec:java -Dexec.mainClass="com.example.crashdemo.CrashDemo"
```

**Expected output:**

```
Started 5 flaky workers. Let the chaos begin!
[INFO] worker-2 crashed: RuntimeException: Random crash
[INFO] Restarting worker-2 with fresh state
[INFO] worker-0 crashed: RuntimeException: Intentional crash
[INFO] Restarting worker-0 with fresh state
[INFO] worker-3 crashed: RuntimeException: Random crash
[INFO] Restarting worker-3 with fresh state
[INFO] worker-2 crashed: RuntimeException: Random crash
[INFO] Restarting worker-2 with fresh state

Final state after chaos:
worker-0: value=18, crashes=3
worker-1: value=22, crashes=0
worker-2: value=15, crashes=5
worker-3: value=19, crashes=2
worker-4: value=21, crashes=0
```

**Key observations:**

1. Workers crash randomly (chaos)
2. Supervisor automatically restarts them
3. Other workers keep running (isolation)
4. System remains operational despite crashes
5. Each crash resets that worker's state (fresh start)

### 5.6 Exercise: Build a Crash-Recovery Demo

**Exercise 5.1:** Implement a payment service that crashes randomly.

```java
package com.example.exercise;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.Random;

sealed interface PaymentMsg permits PaymentMsg.Charge, PaymentMsg.GetStatus {}
record Charge(String orderId, double amount) implements PaymentMsg {}
record GetStatus() implements PaymentMsg {}

record PaymentState(boolean processing, String lastOrderId, int crashCount) {}

class PaymentService {
    private static final Random RANDOM = new Random();

    public static void main(String[] args) throws Exception {
        // TODO: Create a supervisor with ONE_FOR_ONE strategy
        var supervisor = Supervisor.create(
            "payment-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // TODO: Create a payment handler that:
        // - Crashes 10% of the time when processing charges
        // - Tracks crash count in state
        // - Prints "Processing payment for <orderId>" when working
        var handler = (PaymentState state, PaymentMsg msg) -> switch (msg) {
            case Charge(var orderId, var amount) -> {
                System.out.println("Processing payment for " + orderId + ": $" + amount);

                // TODO: Add 10% crash chance here
                if (RANDOM.nextInt(10) == 0) {
                    throw new RuntimeException("Payment gateway timeout");
                }

                yield new PaymentState(false, orderId, state.crashCount());
            }
            case GetStatus() -> state;
        };

        // TODO: Supervise the payment handler
        supervisor.supervise("payment-worker", new PaymentState(false, "", 0), handler);

        // TODO: Send 50 payment charges and observe crashes
        for (int i = 0; i < 50; i++) {
            var ref = supervisor.whichChildren().get(0);
            if (ref.alive()) {
                ref.tell(new Charge("order-" + i, 99.99));
            }
            Thread.sleep(50);
        }

        // TODO: Print final crash count
        var finalState = ref.ask(new GetStatus(), Duration.ofSeconds(1)).get();
        System.out.println("\nFinal crash count: " + finalState.crashCount());

        supervisor.shutdown();
    }
}
```

**Exercise 5.2:** Compare ONE_FOR_ONE vs ONE_FOR_ALL strategies.

Create two supervisors with different strategies. Start 3 workers under each. Crash worker-1 in both supervisors. Observe the difference in restart behavior.

**Exercise 5.3:** Implement a dependency chain with REST_FOR_ONE.

Create workers with dependencies: Config → Database → Cache → API. Use REST_FOR_ONE strategy. Crash the Database worker and verify that Cache and API restart, but Config doesn't.

---

## Chapter 6: Supervision in Practice

**"A supervisor is not a manager. It's a restart policy expressed as a process."**
— Joe Armstrong

### 6.1 Creating Supervisors

JOTP provides multiple ways to create supervisors, from simple to advanced.

#### Basic Supervisor Creation

The simplest way to create a supervisor:

```java
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;

// Create supervisor with strategy and restart limits
var supervisor = Supervisor.create(
    Supervisor.Strategy.ONE_FOR_ONE,  // Restart only crashed child
    5,                                 // Max 5 crashes within window
    Duration.ofMinutes(1)              // 1-minute sliding window
);
```

**Parameters explained:**

- **Strategy:** Which children to restart when one crashes (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- **maxRestarts:** Maximum number of crashes allowed within the time window. If exceeded, supervisor gives up and terminates itself.
- **window:** Time window for counting crashes. Uses a sliding window algorithm.

**What happens when maxRestarts is exceeded?**

```java
// Supervisor allows 5 crashes in 1 minute
// Crash pattern: 12:00:00, 12:00:10, 12:00:20, 12:00:30, 12:00:40, 12:00:45
// 6th crash at 12:00:45 → Supervisor gives up and terminates
// All children are stopped

// Sliding window: At 12:00:50, the window is [12:00:45, 12:00:50]
// Crashes at 12:00:00-12:00:40 are outside the window and no longer counted
```

#### Named Supervisors

For debugging and monitoring, give your supervisor a name:

```java
var supervisor = Supervisor.create(
    "payment-department",             // Name appears in thread dumps and logs
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofMinutes(1)
);
```

### 6.2 Child Specifications: Fine-Grained Control

For production systems, use `ChildSpec` to control every aspect of child process behavior.

#### ChildSpec Fields

```java
import io.github.seanchatmangpt.jotp.Supervisor.ChildSpec;
import java.time.Duration;

var spec = new ChildSpec<>(
    "payment-gateway",                // Unique ID
    () -> new GatewayState(0),        // State factory (creates fresh state on restart)
    GatewayHandler::handle,           // Message handler

    // Restart policy: when to restart this child
    ChildSpec.RestartType.PERMANENT,  // Always restart (crash or normal exit)

    // Shutdown strategy: how to stop this child
    new ChildSpec.Shutdown.Timeout(Duration.ofSeconds(5)),

    // Child type: worker or supervisor
    ChildSpec.ChildType.WORKER,

    // Significant: affects auto-shutdown behavior
    false                             // Not significant
);
```

#### RestartType: When to Restart

```java
public enum RestartType {
    /** Always restarted — on crash or normal exit */
    PERMANENT,

    /** Restarted only on abnormal exit (crash). Normal exit is not restarted. */
    TRANSIENT,

    /** Never restarted */
    TEMPORARY
}
```

**Examples:**

```java
// PERMANENT: Critical service, must always be running
var dbSpec = ChildSpec.worker("database", DBState::new, DBHandler::handle);

// TRANSIENT: Job that completes successfully and shouldn't restart
var jobSpec = new ChildSpec<>(
    "one-time-job",
    JobState::new,
    JobHandler::handle,
    ChildSpec.RestartType.TRANSIENT,  // Only restart if it crashes
    new ChildSpec.Shutdown.Timeout(Duration.ofSeconds(30)),
    ChildSpec.ChildType.WORKER,
    false
);

// TEMPORARY: Diagnostic or monitoring process, can be stopped permanently
var monitorSpec = new ChildSpec<>(
    "temp-monitor",
    MonitorState::new,
    MonitorHandler::handle,
    ChildSpec.RestartType.TEMPORARY,  // Never restart
    new ChildSpec.Shutdown.Timeout(Duration.ofSeconds(1)),
    ChildSpec.ChildType.WORKER,
    false
);
```

#### Shutdown Strategy: How to Stop

```java
public sealed interface Shutdown {
    /** Unconditional immediate termination */
    record BrutalKill() implements Shutdown {}

    /** Graceful shutdown with timeout */
    record Timeout(Duration duration) implements Shutdown {}

    /** Wait indefinitely for child to stop */
    record Infinity() implements Shutdown {}
}
```

**When to use each:**

```java
// BrutalKill: Use for untrusted or hung processes
var untrustedSpec = new ChildSpec<>(
    "untrusted-plugin",
    PluginState::new,
    PluginHandler::handle,
    ChildSpec.RestartType.PERMANENT,
    new ChildSpec.Shutdown.BrutalKill(),  // Kill immediately
    ChildSpec.ChildType.WORKER,
    false
);

// Timeout: Use for most workers (give them time to clean up)
var workerSpec = ChildSpec.worker("worker", WorkerState::new, WorkerHandler::handle);

// Infinity: Use for supervisors and trusted workers
var supervisorSpec = new ChildSpec<>(
    "child-supervisor",
    () -> Supervisor.create(...),
    SupervisorHandler::handle,
    ChildSpec.RestartType.PERMANENT,
    new ChildSpec.Shutdown.Infinity(),  // Wait forever for graceful shutdown
    ChildSpec.ChildType.SUPERVISOR,
    false
);
```

### 6.3 Building a Multi-Layer Supervision Tree

Let's build a realistic e-commerce payment processing system with multiple supervision layers.

#### Architecture

```
[Application Supervisor]
        |
    [Payment Supervisor]
        |
    +---+---+---+
    |   |   |   |
[Auth] [GW] [DB] [Logger]
```

#### Implementation

```java
package com.example.payment;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.Map;

// ============================================================================
// Message Types
// ============================================================================

sealed interface PaymentMsg permits PaymentMsg.ProcessPayment, PaymentMsg.GetStatus {}
record ProcessPayment(String orderId, double amount) implements PaymentMsg {}
record GetStatus() implements PaymentMsg {}

sealed interface AuthMsg permits AuthMsg.Authenticate, AuthMsg.Validate {}
record Authenticate(String token) implements AuthMsg {}
record Validate(String token) implements AuthMsg {}

sealed interface GatewayMsg permits GatewayMsg.Charge, GatewayMsg.Refund {}
record Charge(String orderId, double amount) implements GatewayMsg {}
record Refund(String transactionId) implements GatewayMsg {}

// ============================================================================
// State Types
// ============================================================================

record PaymentState(int processedCount, double totalAmount) {}
record AuthState(boolean authenticated, String token) {}
record GatewayState(int transactionCount, double totalVolume) {}

// ============================================================================
// Handlers
// ============================================================================

class PaymentHandlers {
    public static BiFunction<PaymentState, PaymentMsg, PaymentState> paymentHandler() {
        return (state, msg) -> switch (msg) {
            case ProcessPayment(var orderId, var amount) -> {
                System.out.println("Processing payment for " + orderId + ": $" + amount);
                yield new PaymentState(
                    state.processedCount() + 1,
                    state.totalAmount() + amount
                );
            }
            case GetStatus() -> state;
        };
    }

    public static BiFunction<AuthState, AuthMsg, AuthState> authHandler() {
        return (state, msg) -> switch (msg) {
            case Authenticate(var token) -> {
                // Simulate auth: 10% chance of failure
                if (Math.random() < 0.1) {
                    throw new RuntimeException("Authentication failed");
                }
                yield new AuthState(true, token);
            }
            case Validate(var token) -> state;
        };
    }

    public static BiFunction<GatewayState, GatewayMsg, GatewayState> gatewayHandler() {
        return (state, msg) -> switch (msg) {
            case Charge(var orderId, var amount) -> {
                System.out.println("Charging $" + amount + " for " + orderId);
                // Simulate gateway: 5% chance of timeout
                if (Math.random() < 0.05) {
                    throw new RuntimeException("Gateway timeout");
                }
                yield new GatewayState(
                    state.transactionCount() + 1,
                    state.totalVolume() + amount
                );
            }
            case Refund(var transactionId) -> state;
        };
    }
}

// ============================================================================
// Payment Processing System
// ============================================================================

public class PaymentSystem {
    public static void main(String[] args) throws Exception {
        // ====================================================================
        // Level 1: Application Supervisor
        // ====================================================================
        var appSupervisor = Supervisor.create(
            "payment-app",
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofMinutes(5)
        );

        // ====================================================================
        // Level 2: Payment Department Supervisor
        // ====================================================================
        var paymentSupervisor = appSupervisor.supervise(
            "payment-dept",
            () -> Supervisor.create(
                "payment-dept-sup",
                Supervisor.Strategy.ONE_FOR_ONE,
                5,
                Duration.ofMinutes(1)
            ),
            (supervisor, msg) -> supervisor  // Supervisor handles messages
        );

        // ====================================================================
        // Level 3: Payment Workers
        // ====================================================================

        // Auth Service
        var authSpec = ChildSpec.worker(
            "auth-service",
            AuthState::new,
            PaymentHandlers.authHandler()
        );
        paymentSupervisor.startChild(authSpec);

        // Payment Gateway
        var gatewaySpec = ChildSpec.worker(
            "payment-gateway",
            GatewayState::new,
            PaymentHandlers.gatewayHandler()
        );
        paymentSupervisor.startChild(gatewaySpec);

        // Payment Processor (coordinates auth + gateway)
        var processorSpec = ChildSpec.worker(
            "payment-processor",
            PaymentState::new,
            PaymentHandlers.paymentHandler()
        );
        paymentSupervisor.startChild(processorSpec);

        System.out.println("Payment system started with supervision tree:");
        printSupervisionTree(appSupervisor, 0);

        // ====================================================================
        // Process Payments
        // ====================================================================
        System.out.println("\nProcessing payments...");
        for (int i = 0; i < 20; i++) {
            var orderId = "order-" + i;
            var amount = 99.99 + (i * 10);

            // Get processor ref
            var children = paymentSupervisor.whichChildren();
            var processorRef = children.stream()
                .filter(c -> c.id().equals("payment-processor"))
                .findFirst()
                .orElseThrow();

            if (processorRef.alive()) {
                processorRef.tell(new ProcessPayment(orderId, amount));
            }

            Thread.sleep(200);
        }

        // ====================================================================
        // Check Final State
        // ====================================================================
        Thread.sleep(1000);  // Let final messages process

        System.out.println("\nFinal system state:");
        for (var child : paymentSupervisor.whichChildren()) {
            if (child.alive()) {
                var state = child.ask(new GetStatus(), Duration.ofSeconds(1)).get();
                System.out.println(child.id() + ": " + state);
            }
        }

        appSupervisor.shutdown();
    }

    private static void printSupervisionTree(Supervisor supervisor, int indent) {
        var prefix = "  ".repeat(indent);
        System.out.println(prefix + "├─ Supervisor: " + supervisor);

        for (var child : supervisor.whichChildren()) {
            System.out.println(prefix + "│  ├─ Child: " + child.id());
        }
    }
}
```

#### Running the Example

```bash
mvnd compile
mvnd exec:java -Dexec.mainClass="com.example.payment.PaymentSystem"
```

**Expected output:**

```
Payment system started with supervision tree:
├─ Supervisor: payment-app
│  ├─ Child: payment-dept
│  │  ├─ Child: auth-service
│  │  ├─ Child: payment-gateway
│  │  ├─ Child: payment-processor

Processing payments...
Processing payment for order-0: $99.99
Charging $99.99 for order-0
Processing payment for order-1: $109.99
[INFO] auth-service crashed: RuntimeException: Authentication failed
[INFO] Restarting auth-service with fresh state
Charging $109.99 for order-1
Processing payment for order-2: $119.99
[INFO] payment-gateway crashed: RuntimeException: Gateway timeout
[INFO] Restarting payment-gateway with fresh state
Charging $119.99 for order-2
...

Final system state:
auth-service: AuthState[authenticated=true, token=...]
payment-gateway: GatewayState[transactionCount=18, totalVolume=2149.82]
payment-processor: PaymentState[processedCount=20, totalAmount=2199.80]
```

### 6.4 Dynamic Child Management

Supervisors support dynamic child management: start, stop, and remove children at runtime.

#### Starting Children Dynamically

```java
var supervisor = Supervisor.create(
    "dynamic-pool",
    Supervisor.Strategy.SIMPLE_ONE_FOR_ONE,
    10,
    Duration.ofMinutes(1)
);

// Define template for all children
var template = ChildSpec.worker(
    "worker",                      // ID prefix (will be worker-0, worker-1, ...)
    () -> new WorkerState(0),
    WorkerHandler::handle
);

// Start children on demand
for (int i = 0; i < 5; i++) {
    var ref = supervisor.startChild(template);
    System.out.println("Started: " + ref.id());
}
```

#### Terminating Children

```java
// Terminate a specific child
var children = supervisor.whichChildren();
var childToStop = children.stream()
    .filter(c -> c.id().equals("worker-2"))
    .findFirst()
    .orElseThrow();

supervisor.terminateChild(childToStop.id());
System.out.println("Terminated: " + childToStop.id());
```

#### Deleting Children

```java
// Terminate and remove child from supervision
supervisor.deleteChild("worker-3");
System.out.println("Deleted: worker-3");
```

#### Querying Children

```java
// Get all children
var allChildren = supervisor.whichChildren();

// Filter by status
var aliveChildren = allChildren.stream()
    .filter(Supervisor.ChildInfo::alive)
    .toList();

var deadChildren = allChildren.stream()
    .filter(c -> !c.alive())
    .toList();

System.out.println("Alive: " + aliveChildren.size());
System.out.println("Dead: " + deadChildren.size());
```

### 6.5 Monitoring Supervisor Health

Production systems need observability. Here's how to track supervisor health.

#### Restart Counter

```java
public class SupervisorMetrics {
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final Instant startTime = Instant.now();

    public void recordRestart() {
        restartCount.incrementAndGet();
    }

    public int getRestartCount() {
        return restartCount.get();
    }

    public double getRestartRatePerMinute() {
        var elapsed = Duration.between(startTime, Instant.now()).toMinutes();
        return elapsed > 0 ? (double) restartCount.get() / elapsed : 0;
    }

    public void printReport() {
        System.out.println("=== Supervisor Health Report ===");
        System.out.println("Total restarts: " + restartCount.get());
        System.out.println("Restart rate: " + String.format("%.2f", getRestartRatePerMinute()) + " /min");
        System.out.println("Uptime: " + Duration.between(startTime, Instant.now()).toSeconds() + "s");
    }
}
```

#### Integration with Supervisor

```java
var metrics = new SupervisorMetrics();

var supervisor = Supervisor.create(
    "monitored-supervisor",
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofMinutes(1)
);

// Add crash callback to track restarts
supervisor.addCrashCallback((childId, cause) -> {
    metrics.recordRestart();
    System.out.println("[ALERT] Child " + childId + " crashed: " + cause.getMessage());

    // Alert if restart rate is too high
    if (metrics.getRestartRatePerMinute() > 2.0) {
        System.out.println("[CRITICAL] High restart rate detected!");
        // Send alert to monitoring system
    }
});

// Start work
supervisor.supervise("worker-1", 0, handler);

// Periodically print report
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(() -> {
    metrics.printReport();
}, 30, 30, TimeUnit.SECONDS);
```

### 6.6 Exercise: Build a 3-Layer Supervision Tree

**Exercise 6.1:** Implement a web server supervision tree.

```
[App Supervisor]
    |
[HTTP Supervisor] [DB Supervisor]
    |                |
[Server-1] [Server-2]  [Connection Pool]
```

Requirements:
- App supervisor uses ONE_FOR_ONE strategy
- HTTP supervisor uses ONE_FOR_ALL strategy (all servers restart together)
- DB supervisor uses REST_FOR_ONE strategy
- Each server crashes 5% of the time on each request
- Monitor and print restart statistics

```java
package com.example.exercise;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

sealed interface ServerMsg permits ServerMsg.HandleRequest, ServerMsg.GetStats {}
record HandleRequest(String path) implements ServerMsg {}
record GetStats() implements ServerMsg {}

record ServerState(int requestsHandled, int crashCount) {}

class WebServerExercise {
    public static void main(String[] args) throws Exception {
        // TODO: Create app supervisor
        var appSupervisor = Supervisor.create(
            "web-app",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );

        // TODO: Create and supervise HTTP supervisor (ONE_FOR_ALL)
        var httpSupervisor = appSupervisor.supervise(
            "http-dept",
            () -> Supervisor.create(
                "http-dept-sup",
                Supervisor.Strategy.ONE_FOR_ALL,
                3,
                Duration.ofSeconds(30)
            ),
            (sup, msg) -> sup
        );

        // TODO: Start 2 server workers under HTTP supervisor
        var serverHandler = (ServerState state, ServerMsg msg) -> switch (msg) {
            case HandleRequest(var path) -> {
                // 5% crash chance
                if (Math.random() < 0.05) {
                    throw new RuntimeException("Server crashed handling " + path);
                }
                System.out.println("Handled: " + path);
                yield new ServerState(state.requestsHandled() + 1, state.crashCount());
            }
            case GetStats() -> state;
        };

        httpSupervisor.supervise("server-1", new ServerState(0, 0), serverHandler);
        httpSupervisor.supervise("server-2", new ServerState(0, 0), serverHandler);

        // TODO: Create and supervise DB supervisor (REST_FOR_ONE)
        var dbSupervisor = appSupervisor.supervise(
            "db-dept",
            () -> Supervisor.create(
                "db-dept-sup",
                Supervisor.Strategy.REST_FOR_ONE,
                3,
                Duration.ofSeconds(30)
            ),
            (sup, msg) -> sup
        );

        // TODO: Start connection pool workers under DB supervisor
        // Start 3 connections in dependency order
        for (int i = 0; i < 3; i++) {
            var finalI = i;
            var connHandler = (ServerState state, ServerMsg msg) -> switch (msg) {
                case HandleRequest(var path) -> {
                    if (Math.random() < 0.03) {
                        throw new RuntimeException("Connection-" + finalI + " failed");
                    }
                    yield new ServerState(state.requestsHandled() + 1, state.crashCount());
                }
                case GetStats() -> state;
            };
            dbSupervisor.supervise("conn-" + i, new ServerState(0, 0), connHandler);
        }

        // TODO: Send requests and observe behavior
        System.out.println("Sending requests...");
        for (int i = 0; i < 100; i++) {
            // Send to servers
            for (var child : httpSupervisor.whichChildren()) {
                if (child.alive()) {
                    child.tell(new HandleRequest("/api/request-" + i));
                }
            }

            // Send to connections
            for (var child : dbSupervisor.whichChildren()) {
                if (child.alive()) {
                    child.tell(new HandleRequest("/db/query-" + i));
                }
            }

            Thread.sleep(50);
        }

        // TODO: Print final statistics
        Thread.sleep(1000);
        System.out.println("\n=== Final Statistics ===");

        for (var child : httpSupervisor.whichChildren()) {
            if (child.alive()) {
                var stats = child.ask(new GetStats(), Duration.ofSeconds(1)).get();
                System.out.println(child.id() + ": " + stats);
            }
        }

        for (var child : dbSupervisor.whichChildren()) {
            if (child.alive()) {
                var stats = child.ask(new GetStats(), Duration.ofSeconds(1)).get();
                System.out.println(child.id() + ": " + stats);
            }
        }

        appSupervisor.shutdown();
    }
}
```

**Exercise 6.2:** Add dynamic child management.

Modify the exercise to:
- Start with 1 server
- Add servers dynamically when load increases
- Remove servers when load decreases
- Track minimum and maximum server count

**Exercise 6.3:** Implement health monitoring.

Add a `SupervisorMetrics` class that:
- Tracks restart rate per minute
- Alerts if restart rate exceeds threshold
- Prints health report every 30 seconds
- Exposes metrics via HTTP endpoint

---

## Chapter 7: Testing Fault-Tolerant Systems

**"If you can't test it, you can't trust it. If you can't trust it, don't ship it."**
— Unknown SRE

### 7.1 Testing State Handlers in Isolation

The first rule of testing JOTP systems: **test handlers as pure functions before testing processes**.

A handler is just `BiFunction<S, M, S>`. Test it like any other pure function.

#### Unit Testing a Handler

```java
package com.example.testing;

import io.github.seanchatmangpt.jotp.Proc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.*;

sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.Reset, CounterMsg.Get {}
record Increment(int by) implements CounterMsg {}
record Reset() implements CounterMsg {}
record Get() implements CounterMsg {}

@DisplayName("Counter Handler Tests")
class CounterHandlerTest {

    // Extract handler to a method for testing
    private static Integer handle(Integer state, CounterMsg msg) {
        return switch (msg) {
            case Increment(var by) -> {
                if (by < 0) {
                    throw new IllegalArgumentException("Cannot increment by negative");
                }
                yield state + by;
            }
            case Reset() -> 0;
            case Get() -> state;
        };
    }

    @Test
    @DisplayName("Increment adds value to state")
    void testIncrement() {
        // Given
        var initialState = 5;
        var msg = new Increment(3);

        // When
        var newState = handle(initialState, msg);

        // Then
        assertThat(newState).isEqualTo(8);
    }

    @Test
    @DisplayName("Reset sets state to zero")
    void testReset() {
        // Given
        var initialState = 100;
        var msg = new Reset();

        // When
        var newState = handle(initialState, msg);

        // Then
        assertThat(newState).isEqualTo(0);
    }

    @Test
    @DisplayName("Get returns current state")
    void testGet() {
        // Given
        var initialState = 42;
        var msg = new Get();

        // When
        var newState = handle(initialState, msg);

        // Then
        assertThat(newState).isEqualTo(42);
    }

    @Test
    @DisplayName("Increment by negative throws exception")
    void testIncrementByNegative() {
        // Given
        var initialState = 10;
        var msg = new Increment(-5);

        // When/Then
        assertThatThrownBy(() -> handle(initialState, msg))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Cannot increment by negative");
    }

    @Test
    @DisplayName("Multiple transitions compose correctly")
    void testMultipleTransitions() {
        // Given
        var state = 0;

        // When
        state = handle(state, new Increment(5));  // 0 -> 5
        state = handle(state, new Increment(3));  // 5 -> 8
        state = handle(state, new Reset());       // 8 -> 0
        state = handle(state, new Increment(10)); // 0 -> 10

        // Then
        assertThat(state).isEqualTo(10);
    }
}
```

**Why test handlers in isolation?**

1. **Fast:** No process creation, no threading, no async
2. **Deterministic:** Pure functions always return the same output for the same input
3. **Debuggable:** Stack traces point to exact line of failure
4. **Composable:** Test state transition sequences easily

### 7.2 Testing Processes with JUnit 5

Once handlers are tested, test the full process with message passing.

#### Process Test Example

```java
package com.example.testing;

import io.github.seanchatmangpt.jotp.Proc;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@DisplayName("Counter Process Tests")
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class CounterProcessTest {

    private Proc<Integer, CounterMsg> counter;

    @BeforeEach
    void setUp() {
        counter = Proc.spawn(0, CounterHandlerTest::handle);
    }

    @AfterEach
    void tearDown() {
        if (counter != null) {
            counter.stop();
        }
    }

    @Test
    @DisplayName("Process handles tell() messages")
    void testTellFireAndForget() throws Exception {
        // When
        counter.tell(new Increment(5));
        counter.tell(new Increment(3));
        Thread.sleep(100);  // Let messages process

        // Then
        var state = counter.ask(new Get(), Duration.ofSeconds(1)).get();
        assertThat(state).isEqualTo(8);
    }

    @Test
    @DisplayName("Process handles ask() with timeout")
    void testAskWithTimeout() throws Exception {
        // When
        var future = counter.ask(new Get(), Duration.ofMillis(100));
        var state = future.get(1, TimeUnit.SECONDS);

        // Then
        assertThat(state).isEqualTo(0);
    }

    @Test
    @DisplayName("Process crashes on invalid input")
    void testProcessCrash() throws Exception {
        // Given
        counter.tell(new Increment(10));
        var stateBefore = counter.ask(new Get(), Duration.ofSeconds(1)).get();
        assertThat(stateBefore).isEqualTo(10);

        // When: Send invalid message
        counter.tell(new Increment(-1));

        // Then: Process should crash
        Thread.sleep(100);
        var future = counter.ask(new Get(), Duration.ofMillis(100));

        assertThatThrownBy(() -> future.get())
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Multiple processes are isolated")
    void testProcessIsolation() throws Exception {
        // Given: Two independent processes
        var counter1 = Proc.spawn(0, CounterHandlerTest::handle);
        var counter2 = Proc.spawn(100, CounterHandlerTest::handle);

        // When: Send different messages
        counter1.tell(new Increment(1));
        counter2.tell(new Increment(10));

        Thread.sleep(100);

        // Then: Processes have independent state
        var state1 = counter1.ask(new Get(), Duration.ofSeconds(1)).get();
        var state2 = counter2.ask(new Get(), Duration.ofSeconds(1)).get();

        assertThat(state1).isEqualTo(1);
        assertThat(state2).isEqualTo(110);

        // Cleanup
        counter1.stop();
        counter2.stop();
    }
}
```

### 7.3 Testing Supervisors and Restart Behavior

Supervisor tests verify that crashes trigger correct restart behavior.

#### Supervisor Test Example

```java
package com.example.testing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

@DisplayName("Supervisor Restart Tests")
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SupervisorRestartTest {

    private Supervisor supervisor;
    private AtomicInteger startCount;

    @BeforeEach
    void setUp() {
        supervisor = Supervisor.create(
            "test-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );
        startCount = new AtomicInteger(0);
    }

    @AfterEach
    void tearDown() {
        if (supervisor != null) {
            supervisor.shutdown();
        }
    }

    @Test
    @DisplayName("Supervisor restarts crashed child")
    void testChildRestart() throws Exception {
        // Given: A child that crashes on specific message
        var crashHandler = (Integer state, CounterMsg msg) -> switch (msg) {
            case Increment(var by) -> {
                if (by == 999) {
                    throw new RuntimeException("Intentional crash");
                }
                yield state + by;
            }
            case Reset() -> 0;
            case Get() -> state;
        };

        var childRef = supervisor.supervise(
            "crashy-child",
            0,
            crashHandler
        );

        // When: Send crash message
        childRef.tell(new Increment(999));

        // Then: Child should be restarted
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> {
                var children = supervisor.whichChildren();
                return children.stream()
                    .anyMatch(c -> c.id().equals("crashy-child") && c.alive());
            });

        // And: State should be reset to initial value
        var state = childRef.ask(new Get(), Duration.ofSeconds(1)).get();
        assertThat(state).isEqualTo(0);
    }

    @Test
    @DisplayName("ONE_FOR_ONE only restarts crashed child")
    void testOneForOneStrategy() throws Exception {
        // Given: Three children
        var child1 = supervisor.supervise("child-1", 0, CounterHandlerTest::handle);
        var child2 = supervisor.supervise("child-2", 0, CounterHandlerTest::handle);
        var child3 = supervisor.supervise("child-3", 0, CounterHandlerTest::handle);

        // Set initial states
        child1.tell(new Increment(10));
        child2.tell(new Increment(20));
        child3.tell(new Increment(30));
        Thread.sleep(100);

        // When: Child-2 crashes
        child2.tell(new Increment(-1));  // Triggers crash in handler
        Thread.sleep(200);

        // Then: Child-1 and Child-3 should be alive and unchanged
        var state1 = child1.ask(new Get(), Duration.ofSeconds(1)).get();
        var state3 = child3.ask(new Get(), Duration.ofSeconds(1)).get();

        assertThat(state1).isEqualTo(10);
        assertThat(state3).isEqualTo(30);

        // And: Child-2 should be restarted with fresh state
        var state2 = child2.ask(new Get(), Duration.ofSeconds(1)).get();
        assertThat(state2).isEqualTo(0);  // Reset to initial state
    }

    @Test
    @DisplayName("Supervisor gives up after max restarts exceeded")
    void testSupervisorGivesUp() throws Exception {
        // Given: Supervisor with low restart threshold
        var strictSupervisor = Supervisor.create(
            "strict-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            2,  // Allow only 2 crashes
            Duration.ofSeconds(10)
        );

        var crashHandler = (Integer state, CounterMsg msg) -> switch (msg) {
            case Increment(var by) -> {
                throw new RuntimeException("Always crashes");
            }
            case Reset() -> 0;
            case Get() -> state;
        };

        var childRef = strictSupervisor.supervise("always-crash", 0, crashHandler);

        // When: Crash child 3 times (exceeds maxRestarts=2)
        childRef.tell(new Increment(1));  // Crash 1
        Thread.sleep(100);

        childRef.tell(new Increment(1));  // Crash 2
        Thread.sleep(100);

        childRef.tell(new Increment(1));  // Crash 3 - supervisor gives up
        Thread.sleep(500);

        // Then: Supervisor should terminate all children
        var children = strictSupervisor.whichChildren();
        assertThat(children).isEmpty();

        strictSupervisor.shutdown();
    }

    @Test
    @DisplayName("Supervisor tracks restart history in sliding window")
    void testSlidingWindow() throws Exception {
        // Given: Supervisor with 1-second window
        var windowSupervisor = Supervisor.create(
            "window-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofSeconds(1)
        );

        var crashHandler = (Integer state, CounterMsg msg) -> switch (msg) {
            case Increment(var by) -> {
                throw new RuntimeException("Crash");
            }
            case Reset() -> 0;
            case Get() -> state;
        };

        var childRef = windowSupervisor.supervise("window-child", 0, crashHandler);

        // When: Crash 3 times within 1 second
        childRef.tell(new Increment(1));  // Crash 1 at t=0
        Thread.sleep(200);

        childRef.tell(new Increment(1));  // Crash 2 at t=0.2
        Thread.sleep(200);

        childRef.tell(new Increment(1));  // Crash 3 at t=0.4 (exceeds limit)
        Thread.sleep(500);

        // Then: Supervisor should give up
        var children = windowSupervisor.whichChildren();
        assertThat(children).isEmpty();

        windowSupervisor.shutdown();
    }
}
```

### 7.4 Chaos Testing with JUnit 5

Chaos testing validates that your system heals automatically under random failures.

#### ChaosMonkey Test Framework

```java
package com.example.testing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

/**
 * Chaos test that randomly kills processes and verifies system remains operational.
 */
@DisplayName("Chaos Monkey Test")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChaosMonkeyTest {

    private Supervisor supervisor;
    private ExecutorService chaosExecutor;
    private AtomicBoolean chaosRunning;
    private AtomicInteger crashCount;

    @BeforeEach
    void setUp() {
        supervisor = Supervisor.create(
            "chaos-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            10,
            Duration.ofMinutes(1)
        );
        chaosExecutor = Executors.newSingleThreadExecutor();
        chaosRunning = new AtomicBoolean(true);
        crashCount = new AtomicInteger(0);
    }

    @AfterEach
    void tearDown() {
        chaosRunning.set(false);
        chaosExecutor.shutdownNow();
        if (supervisor != null) {
            supervisor.shutdown();
        }
    }

    @Test
    @DisplayName("System survives 30 seconds of chaos")
    void testSystemSurvivesChaos() throws Exception {
        // Given: Start 10 workers
        var workers = new ArrayList<ProcRef<Integer, CounterMsg>>();
        for (int i = 0; i < 10; i++) {
            var worker = supervisor.supervise(
                "worker-" + i,
                0,
                (state, msg) -> {
                    if (msg instanceof CounterMsg.Increment(var by) && Math.random() < 0.05) {
                        throw new RuntimeException("Random crash");
                    }
                    if (msg instanceof CounterMsg.Increment(var by)) {
                        return state + by;
                    }
                    return state;
                }
            );
            workers.add(worker);
        }

        // When: Start chaos monkey
        var chaosFuture = chaosExecutor.submit(this::chaosMonkey);

        // And: Send continuous workload
        var workloadExecutor = Executors.newFixedThreadPool(5);
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        for (int i = 0; i < 5; i++) {
            workloadExecutor.submit(() -> {
                var random = new Random();
                for (int j = 0; j < 1000; j++) {
                    try {
                        var worker = workers.get(random.nextInt(workers.size()));
                        if (worker.alive()) {
                            worker.tell(new CounterMsg.Increment(1));
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                        Thread.sleep(10);
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }
            });
        }

        // And: Run for 30 seconds
        Thread.sleep(30_000);
        chaosRunning.set(false);
        chaosFuture.get(5, TimeUnit.SECONDS);
        workloadExecutor.shutdownNow();
        workloadExecutor.awaitTermination(5, TimeUnit.SECONDS);

        // Then: System should still be operational
        var aliveWorkers = workers.stream()
            .filter(ProcRef::alive)
            .count();

        assertThat(aliveWorkers).isGreaterThan(8);  // At least 8/10 workers alive

        // And: Most operations should succeed
        var total = successCount.get() + failureCount.get();
        var successRate = (double) successCount.get() / total;
        assertThat(successRate).isGreaterThan(0.95);  // 95%+ success rate

        // And: Crashes should have occurred
        assertThat(crashCount.get()).isGreaterThan(0);

        System.out.println("=== Chaos Test Results ===");
        System.out.println("Duration: 30 seconds");
        System.out.println("Total operations: " + total);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Success rate: " + String.format("%.2f%%", successRate * 100));
        System.out.println("Crashes: " + crashCount.get());
        System.out.println("Alive workers: " + aliveWorkers + "/10");
    }

    /**
     * Chaos monkey that randomly kills workers.
     */
    private void chaosMonkey() {
        var random = new Random();
        while (chaosRunning.get()) {
            try {
                // Pick a random worker
                var children = supervisor.whichChildren();
                if (!children.isEmpty()) {
                    var victim = children.get(random.nextInt(children.size()));

                    // 10% chance to kill
                    if (random.nextDouble() < 0.1 && victim.alive()) {
                        victim.tell(new CounterMsg.Increment(-999));  // Triggers crash
                        crashCount.incrementAndGet();
                        System.out.println("[CHAOS] Killed " + victim.id());
                    }
                }

                Thread.sleep(100);  // Chaos every 100ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
```

#### Running the Chaos Test

```bash
mvnd test -Dtest=ChaosMonkeyTest
```

**Expected output:**

```
[CHAOS] Killed worker-3
[INFO] worker-3 crashed: RuntimeException: Random crash
[INFO] Restarting worker-3 with fresh state
[CHAOS] Killed worker-7
[INFO] worker-7 crashed: RuntimeException: Random crash
[INFO] Restarting worker-7 with fresh state
[CHAOS] Killed worker-2
...

=== Chaos Test Results ===
Duration: 30 seconds
Total operations: 50000
Successful: 48756
Failed: 1244
Success rate: 97.51%
Crashes: 47
Alive workers: 10/10
```

### 7.5 Property-Based Testing with jqwik

Property-based testing generates hundreds of random inputs to verify invariants.

#### jqwik Example

```java
package com.example.testing;

import io.github.seanchatmangpt.jotp.Proc;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class CounterPropertyTest {

    @Property
    @DisplayName("Increment is associative: (a + b) + c = a + (b + c)")
    void testIncrementAssociative(
        @ForAll @IntRange(min = 0, max = 100) int a,
        @ForAll @IntRange(min = 0, max = 100) int b,
        @ForAll @IntRange(min = 0, max = 100) int c
    ) throws Exception {
        // Given
        var counter1 = Proc.spawn(0, CounterHandlerTest::handle);
        var counter2 = Proc.spawn(0, CounterHandlerTest::handle);

        // When: (a + b) + c
        counter1.tell(new CounterMsg.Increment(a));
        counter1.tell(new CounterMsg.Increment(b));
        counter1.tell(new CounterMsg.Increment(c));

        // And: a + (b + c)
        counter2.tell(new CounterMsg.Increment(a));
        counter2.tell(new CounterMsg.Increment(b + c));

        Thread.sleep(100);

        // Then: Same result
        var state1 = counter1.ask(new CounterMsg.Get(), Duration.ofSeconds(1)).get();
        var state2 = counter2.ask(new CounterMsg.Get(), Duration.ofSeconds(1)).get();

        assertThat(state1).isEqualTo(state2);

        counter1.stop();
        counter2.stop();
    }

    @Property
    @DisplayName("Reset always returns to zero")
    void testResetAlwaysZero(
        @ForAll @IntRange(min = 0, max = 1000) int initialValue,
        @ForAll @IntRange(min = 0, max = 100) int increment1,
        @ForAll @IntRange(min = 0, max = 100) int increment2
    ) throws Exception {
        // Given
        var counter = Proc.spawn(initialValue, CounterHandlerTest::handle);

        // When
        counter.tell(new CounterMsg.Increment(increment1));
        counter.tell(new CounterMsg.Increment(increment2));
        counter.tell(new CounterMsg.Reset());

        Thread.sleep(100);

        // Then: Always zero
        var state = counter.ask(new CounterMsg.Get(), Duration.ofSeconds(1)).get();
        assertThat(state).isEqualTo(0);

        counter.stop();
    }

    @Property
    @DisplayName("Negative increments throw exception")
    void testNegativeIncrementThrows(
        @ForAll @IntRange(min = -100, max = -1) int negative
    ) {
        // Given
        var counter = Proc.spawn(0, CounterHandlerTest::handle);

        // When/Then
        counter.tell(new CounterMsg.Increment(negative));
        // Process crashes

        Thread.sleep(100);

        // Process should be dead
        assertThatThrownBy(() -> {
            counter.ask(new CounterMsg.Get(), Duration.ofMillis(100)).get();
        }).isInstanceOf(ExecutionException.class);

        counter.stop();
    }
}
```

### 7.6 Measuring Recovery SLA

Service Level Agreements (SLAs) define recovery time objectives. Here's how to measure them.

#### Recovery Time Metrics

```java
package com.example.testing;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

@DisplayName("Recovery SLA Test")
class RecoverySLATest {

    record RecoveryMetric(String childId, Instant crashTime, Instant recoveryTime, long recoveryTimeMs) {}

    private Supervisor supervisor;
    private List<RecoveryMetric> recoveryMetrics;

    @BeforeEach
    void setUp() {
        supervisor = Supervisor.create(
            "sla-supervisor",
            Supervisor.Strategy.ONE_FOR_ONE,
            10,
            Duration.ofMinutes(1)
        );
        recoveryMetrics = new CopyOnWriteArrayList<>();

        // Track recovery times
        supervisor.addCrashCallback((childId, cause) -> {
            var crashTime = Instant.now();

            // Wait for recovery
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> {
                    var children = supervisor.whichChildren();
                    return children.stream()
                        .anyMatch(c -> c.id().equals(childId) && c.alive());
                });

            var recoveryTime = Instant.now();
            var recoveryTimeMs = Duration.between(crashTime, recoveryTime).toMillis();
            recoveryMetrics.add(new RecoveryMetric(childId, crashTime, recoveryTime, recoveryTimeMs));
        });
    }

    @AfterEach
    void tearDown() {
        if (supervisor != null) {
            supervisor.shutdown();
        }
    }

    @Test
    @DisplayName("Recovery time meets SLA: p50 < 100ms, p99 < 500ms, p99.9 < 1s")
    void testRecoverySLA() throws Exception {
        // Given: Start 5 workers
        for (int i = 0; i < 5; i++) {
            supervisor.supervise(
                "worker-" + i,
                0,
                (state, msg) -> {
                    if (msg instanceof CounterMsg.Increment(var by) && Math.random() < 0.2) {
                        throw new RuntimeException("Random crash");
                    }
                    if (msg instanceof CounterMsg.Increment(var by)) {
                        return state + by;
                    }
                    return state;
                }
            );
        }

        // When: Send workload and induce crashes
        for (int i = 0; i < 100; i++) {
            for (var child : supervisor.whichChildren()) {
                if (child.alive()) {
                    child.tell(new CounterMsg.Increment(1));
                }
            }
            Thread.sleep(50);
        }

        // Then: Collect recovery metrics
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> recoveryMetrics.size() >= 10);  // Wait for at least 10 crashes

        // Calculate percentiles
        var recoveryTimes = recoveryMetrics.stream()
            .mapToLong(RecoveryMetric::recoveryTimeMs)
            .sorted()
            .toArray();

        var p50 = percentile(recoveryTimes, 50);
        var p99 = percentile(recoveryTimes, 99);
        var p99_9 = percentile(recoveryTimes, 99.9);

        System.out.println("=== Recovery SLA Report ===");
        System.out.println("Total recoveries: " + recoveryTimes.length);
        System.out.println("p50: " + p50 + "ms");
        System.out.println("p99: " + p99 + "ms");
        System.out.println("p99.9: " + p99_9 + "ms");

        // Assert SLA
        assertThat(p50).isLessThan(100);     // p50 < 100ms
        assertThat(p99).isLessThan(500);     // p99 < 500ms
        assertThat(p99_9).isLessThan(1000);  // p99.9 < 1s
    }

    private long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
```

### 7.7 Exercise: Build a Complete Test Suite

**Exercise 7.1:** Test a payment state machine.

```java
package com.example.exercise;

import io.github.seanchatmangpt.jotp.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

sealed interface PaymentEvent permits PaymentEvent.Authorize, PaymentEvent.Capture, PaymentEvent.Refund {}
record Authorize(double amount) implements PaymentEvent {}
record Capture(double amount) implements PaymentEvent {}
record Refund(double amount) implements PaymentEvent {}

sealed interface PaymentState permits PaymentState.Pending, PaymentState.Authorized, PaymentState.Captured {}
record Pending() implements PaymentState {}
record Authorized(double authorizedAmount) implements PaymentState {}
record Captured(double capturedAmount) implements PaymentState {}

@DisplayName("Payment State Machine Tests")
class PaymentStateMachineTest {

    // TODO: Implement handler
    private static PaymentState handle(PaymentState state, PaymentEvent event) {
        // Implement state transitions:
        // Pending + Authorize(amount) → Authorized(amount)
        // Authorized + Capture(amount) → Captured(amount)
        // Captured + Refund(amount) → Authorized(authorized - amount)
        // Throw exception for invalid transitions
        throw new UnsupportedOperationException("TODO: Implement handler");
    }

    @Test
    @DisplayName("Pending → Authorized on authorize")
    void testAuthorize() {
        // TODO: Test transition
    }

    @Test
    @DisplayName("Authorized → Captured on capture")
    void testCapture() {
        // TODO: Test transition
    }

    @Test
    @DisplayName("Captured → Authorized on refund")
    void testRefund() {
        // TODO: Test transition
    }

    @Test
    @DisplayName("Invalid transition throws exception")
    void testInvalidTransition() {
        // TODO: Test that invalid transitions throw
    }
}
```

**Exercise 7.2:** Chaos test the payment system.

```java
@DisplayName("Payment Chaos Test")
class PaymentChaosTest {

    @Test
    @DisplayName("Payment system survives chaos")
    void testPaymentChaos() throws Exception {
        // TODO:
        // 1. Create supervisor with payment workers
        // 2. Start chaos monkey that randomly kills workers
        // 3. Send continuous payment requests
        // 4. Verify 99%+ success rate
        // 5. Verify all workers alive after test
    }
}
```

**Exercise 7.3:** Measure recovery SLA for payment system.

```java
@DisplayName("Payment Recovery SLA Test")
class PaymentRecoverySLATest {

    @Test
    @DisplayName("Recovery meets SLA: p99 < 200ms")
    void testRecoverySLA() throws Exception {
        // TODO:
        // 1. Track crash and recovery times
        // 2. Induce 50+ crashes
        // 3. Calculate p50, p99, p99.9 recovery times
        // 4. Assert p99 < 200ms
    }
}
```
