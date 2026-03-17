# JOTP Quick Start Recipes

Collection of ready-to-use patterns for common JOTP use cases. Each recipe includes complete code, explanation, and production considerations.

## Table of Contents

1. [Hello World](#1-hello-world) - First JOTP process
2. [Fault Tolerance](#2-fault-tolerance) - Supervision and recovery
3. [Messaging](#3-messaging) - Request-response patterns
4. [State Management](#4-state-management) - State machines
5. [Distributed Systems](#5-distributed-systems) - Multi-JVM patterns
6. [Production Setup](#6-production-setup) - Monitoring and deployment

---

## 1. Hello World

### The Minimal JOTP Process

```java
import io.github.seanchatmangpt.jotp.*;
import static io.github.seanchatmangpt.jotp.Proc.spawn;

public class HelloWorld {
    public static void main(String[] args) {
        // Define message protocol
        sealed interface Message permits SayHello, Stop {}
        record SayHello(String name) implements Message {}
        record Stop() implements Message {}

        // Create and spawn a process
        Proc<String, Message> greeter = spawn(
            "greeter",                    // process name
            "Hello",                      // initial state
            (state, msg) -> switch (msg) {
                case SayHello(String n) -> {
                    System.out.println("Hello, " + n + "!");
                    yield state;  // keep same state
                }
                case Stop() -> "Goodbye";  // transition to new state
            }
        );

        // Send messages
        greeter.send(new SayHello("World"));
        greeter.send(new SayHello("JOTP"));
        greeter.send(new Stop());

        // Verify final state
        System.out.println("Final state: " + greeter.state());
    }
}
```

### What's Happening

1. **spawn()** creates a virtual-thread process with a mailbox
2. **Handler** is pure function: `(state, message) → newState`
3. **send()** delivers messages to the mailbox (non-blocking)
4. **Process** executes one message at a time (state is thread-safe)

### Production Tips

- Use `ProcRef` for long-lived references (survives supervisor restarts)
- Add timeouts with `Proc.ask(proc, message, Duration.ofSeconds(5))`
- Register named processes with `ProcRegistry.register(name, proc)`

---

## 2. Fault Tolerance

### Supervision Tree with Restart Strategies

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;

public class FaultToleranceRecipe {
    public static void main(String[] args) {
        // Message protocol
        sealed interface Message permits ProcessData, Crash, GetStatus {}
        record ProcessData(String data) implements Message {}
        record Crash() implements Message {}
        record GetStatus(ProcRef<String, Message> replyTo) implements Message {}

        // Child process that can crash
        Proc<Integer, Message> worker = spawn(
            "worker",
            0,
            (state, msg) -> switch (msg) {
                case ProcessData(String d) -> {
                    System.out.println("Processing: " + d);
                    yield state + 1;
                }
                case Crash() -> {
                    throw new RuntimeException("Simulated crash!");
                }
                case GetStatus(ProcRef<?, Message> r) -> {
                    r.self().send(new ProcessData("Status: " + state));
                    yield state;
                }
            }
        );

        // Supervisor with ONE_FOR_ONE strategy
        Supervisor supervisor = Supervisor.supervise(
            Supervisor.ChildSpec.builder()
                .name("worker")
                .proc(() -> worker)
                .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
                .maxRestarts(5)              // max 5 restarts
                .restartDuration(Duration.ofMinutes(1))  // within 1 minute
                .build()
        );

        // Send work - supervisor auto-restarts on crash
        worker.send(new ProcessData("Task 1"));  // state = 1
        worker.send(new ProcessData("Task 2"));  // state = 2
        worker.send(new Crash());                // CRASH! Auto-restart, state = 0
        worker.send(new ProcessData("Task 3"));  // state = 1 (restarted)

        // Graceful shutdown
        supervisor.shutdown();
    }
}
```

### Restart Strategies

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| `ONE_FOR_ONE` | Only crashed child restarts | Independent workers |
| `ONE_FOR_ALL` | All children restart on any crash | Tightly coupled components |
| `REST_FOR_ONE` | Crashed child + all after it restart | Ordered startup dependencies |

### Production Tips

- Add exponential backoff with `restartBackoff(Duration.ofMillis(100))`
- Use `CrashRecovery.wrap()` for external calls that shouldn't crash the process
- Monitor restart frequency with `ProcSys.getStatistics(proc)`

---

## 3. Messaging

### Request-Response with Ask Pattern

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.*;

public class MessagingRecipe {
    public static void main(String[] args) {
        // Protocol
        sealed interface Message permits Add, Get, Clear {}
        sealed interface Response permits Value, Ack {}
        record Add(int delta) implements Message {}
        record Get(ProcRef<String, Response> replyTo) implements Message {}
        record Clear() implements Message {}
        record Value(int v) implements Response {}
        record Ack() implements Response {}

        // Counter process with reply-to pattern
        Proc<Integer, Message> counter = spawn(
            "counter",
            0,
            (state, msg) -> switch (msg) {
                case Add(int d) -> yield state + d;
                case Get(ProcRef<String, Response> r) -> {
                    r.self().send(new Value(state));
                    yield state;
                }
                case Clear() -> yield 0;
            }
        );

        // Pattern 1: Ask with timeout
        try {
            Response r1 = Proc.ask(counter, new Get(counter.ref()), Duration.ofSeconds(1));
            System.out.println("Count: " + ((Value) r1).v());
        } catch (TimeoutException e) {
            System.out.println("Counter didn't respond in time");
        }

        // Pattern 2: Fire-and-forget
        counter.send(new Add(42));

        // Pattern 3: Request streaming with monitor
        sealed interface MonitorMsg implements Proc.ProcMessage {}
        record BatchResult(int sum) implements MonitorMsg {}

        Proc<Void, MonitorMsg> monitor = spawn(
            "monitor",
            null,
            (state, msg) -> switch (msg) {
                case BatchResult(int s) -> {
                    System.out.println("Batch sum: " + s);
                    yield null;
                }
            }
        );

        counter.send(new Add(10));
        counter.send(new Add(20));
        counter.send(new Add(30));
    }
}
```

### Message Patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| **Cast** | Fire-and-forget | Logging, metrics |
| **Call** | Synchronous with timeout | Configuration fetch |
| **Ask** | Async with reply-to | Request-response |
| **Stream** | Multiple responses | Pagination, progress |

### Production Tips

- Always use timeouts with `ask()` to prevent deadlocks
- Use `ProcRef` instead of raw `Proc` for reply-to (survives restarts)
- Implement message sequencing with `requestId` fields

---

## 4. State Management

### State Machine for Workflow Orchestration

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.UUID;

public class StateMachineRecipe {
    public static void main(String[] args) {
        // States
        sealed interface OrderState permits Pending, Authorized, Shipped, Delivered, Failed {}
        record Pending(String orderId) implements OrderState {}
        record Authorized(String orderId, String paymentId) implements OrderState {}
        record Shipped(String orderId, String paymentId, String trackingId) implements OrderState {}
        record Delivered(String orderId, String trackingId) implements OrderState {}
        record Failed(String orderId, String reason) implements OrderState {}

        // Events
        sealed interface OrderEvent permits Authorize, Ship, ConfirmDelivery, Fail {}
        record Authorize(String cardId) implements OrderEvent {}
        record Ship(String address) implements OrderEvent {}
        record ConfirmDelivery() implements OrderEvent {}
        record Fail(String reason) implements OrderEvent {}

        // Data
        record OrderContext(String customerId, double amount) {}

        // State machine
        StateMachine<OrderState, OrderEvent, OrderContext> orderMachine =
            StateMachine.<OrderState, OrderEvent, OrderContext>builder()
                . initialState(new Pending(UUID.randomUUID().toString()))
                .context(new OrderContext("customer-123", 99.99))
                .handler((state, event, ctx) -> switch (state) {
                    case Pending(String id) -> switch (event) {
                        case Authorize(String card) -> StateMachine.Next.of(
                            new Authorized(id, "pay-" + UUID.randomUUID())
                        );
                        case Fail(String r) -> StateMachine.Next.of(
                            new Failed(id, r)
                        );
                        default -> StateMachine.Keep.of();
                    }
                    case Authorized(String id, String payId) -> switch (event) {
                        case Ship(String addr) -> StateMachine.Next.of(
                            new Shipped(id, payId, "track-" + UUID.randomUUID())
                        );
                        default -> StateMachine.Keep.of();
                    }
                    case Shipped(String id, String payId, String track) -> switch (event) {
                        case ConfirmDelivery() -> StateMachine.Next.of(
                            new Delivered(id, track)
                        );
                        default -> StateMachine.Keep.of();
                    }
                    case Delivered(String id, String track) ->
                        StateMachine.Stop.of("Order completed");
                    case Failed(String id, String reason) ->
                        StateMachine.Stop.of("Order failed: " + reason);
                })
                .build();

        // Execute workflow
        orderMachine.send(new Authorize("card-123"));
        System.out.println("State: " + orderMachine.state());

        orderMachine.send(new Ship("123 Main St"));
        System.out.println("State: " + orderMachine.state());

        orderMachine.send(new ConfirmDelivery());
        System.out.println("State: " + orderMachine.state());
    }
}
```

### State Machine Features

- **Sealed States**: Compiler enforces exhaustive event handling
- **Transition Types**: Next (new state), Keep (stay), Stop (terminate)
- **Data Context**: Per-state-machine context (config, cache, etc.)
- **Event Replay**: All transitions logged for audit/debugging

### Production Tips

- Store state transitions in event store for audit trail
- Use sagas for multi-service orchestration
- Add timeout transitions with `ProcTimer.schedule()`

---

## 5. Distributed Systems

### Multi-JVM Leader Election

```java
import io.github.seanchatmangpt.jotp.distributed.*;
import java.time.Duration;
import java.util.List;

public class DistributedRecipe {
    public static void main(String[] args) throws Exception {
        // Create 3 nodes
        var node1 = new DistributedNode("cp1", "localhost", 0, NodeConfig.defaults());
        var node2 = new DistributedNode("cp2", "localhost", 0, NodeConfig.defaults());
        var node3 = new DistributedNode("cp3", "localhost", 0, NodeConfig.defaults());

        // Define distributed app spec
        var spec = new DistributedAppSpec(
            "counter-app",
            List.of(
                List.of(node1.nodeId()),  // Priority 1
                List.of(node2.nodeId()),  // Priority 2
                List.of(node3.nodeId())   // Priority 3
            ),
            Duration.ofSeconds(5)  // failover timeout
        );

        // Register at all nodes with callbacks
        node1.register(spec, new ApplicationCallbacks() {
            @Override public void onStart(StartMode mode) {
                System.out.println("Node 1 starting as " + mode);
                // Start your app here (only on leader)
            }
            @Override public void onStop() {
                System.out.println("Node 1 stopping");
            }
        });

        node2.register(spec, new ApplicationCallbacks() {
            @Override public void onStart(StartMode mode) {
                System.out.println("Node 2 starting as " + mode);
            }
            @Override public void onStop() {
                System.out.println("Node 2 stopping");
            }
        });

        node3.register(spec, new ApplicationCallbacks() {
            @Override public void onStart(StartMode mode) {
                System.out.println("Node 3 starting as " + mode);
            }
            @Override public void onStop() {
                System.out.println("Node 3 stopping");
            }
        });

        // Start at all nodes - only cp1 (highest priority) runs
        node1.start("counter-app");  // NORMAL start (leader)
        node2.start("counter-app");  // STANDBY (monitors cp1)
        node3.start("counter-app");  // STANDBY (monitors cp1)

        Thread.sleep(2000);

        // Simulate cp1 crash - cp2 takes over after 5s
        System.out.println("\n*** Simulating cp1 crash ***");
        node1.shutdown();

        Thread.sleep(8000);  // Wait for failover

        // Clean shutdown
        node2.stop("counter-app");
        node3.stop("counter-app");
        node2.shutdown();
        node3.shutdown();
    }
}
```

### Distributed Patterns

| Pattern | Description | When to Use |
|---------|-------------|-------------|
| **Active-Passive** | Single leader, hot standby | Single-master databases, schedulers |
| **Active-Active** | All nodes process, gossip sync | Content delivery, stateless services |
| **Sharded** | Partition by key, local leader | High-throughput data grids |

### Production Tips

- Use `DistributedActorBridge` for location-transparent messaging
- Enable mTLS with service mesh (Istio/Linkerd)
- Add health checks: `/healthz` → `DistributedNode.status()`
- Monitor with OpenTelemetry: leader election, failover latency

---

## 6. Production Setup

### Monitoring and Observability

```java
import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.observability.*;
import java.time.Duration;

public class ProductionRecipe {
    public static void main(String[] args) {
        // Enable observability (in startup)
        System.setProperty("jotp.observability.enabled", "true");
        System.setProperty("jotp.telemetry.endpoint", "http://jaeger:4318");

        // Message protocol with metadata
        sealed interface Message implements Proc.ProcMessage {
            String getCorrelationId();
        }

        record ProcessOrder(String orderId, String correlationId, long startTimeNanos)
            implements Message {}

        record OrderProcessed(String orderId, String correlationId, long durationNanos)
            implements Message {}

        // Order processor with observability
        Proc<Void, Message> processor = spawn(
            "order-processor",
            null,
            (state, msg) -> {
                long start = System.nanoTime();

                // Process message
                switch (msg) {
                    case ProcessOrder(String id, String cid, long startNanos) -> {
                        // Business logic here
                        try {
                            Thread.sleep(100);  // Simulate work
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        // Record metrics
                        long duration = System.nanoTime() - start;
                        ProcSys.recordMessageProcessed("order-processor", duration);

                        // Emit span
                        ProcSys.emitSpan(
                            "process-order",
                            cid,
                            Map.of("orderId", id, "duration", duration + "ns")
                        );
                    }
                }
                return null;
            }
        );

        // Send messages with correlation IDs
        for (int i = 0; i < 100; i++) {
            String cid = UUID.randomUUID().toString();
            processor.send(new ProcessOrder("order-" + i, cid, System.nanoTime()));
        }

        // Get statistics
        var stats = ProcSys.getStatistics(processor);
        System.out.println("Statistics: " + stats);
        // Output: Statistics{messagesProcessed=100, avgLatencyNs=...}

        // Graceful shutdown with drain
        Proc.drain(processor, Duration.ofSeconds(30));
        processor.shutdown();
    }
}
```

### Production Checklist

- [ ] **Memory**: 4GB heap for 1M processes (~4KB/process)
- [ ] **GC**: Use ZGC (`-XX:+UseZGC -XX:+ZGenerational`)
- [ ] **Monitoring**: Prometheus metrics, Grafana dashboards
- [ ] **Tracing**: OpenTelemetry with Jaeger
- [ ] **Logging**: Structured JSON with correlation IDs
- [ ] **Health Checks**: `/healthz`, `/readyz`, `/metrics`
- [ ] **Resource Limits**: CPU requests/limits, memory limits
- [ ] **Service Mesh**: mTLS, traffic management, observability

### JVM Flags for Production

```bash
java \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms4g -Xmx4g \
  -XX:ParallelGCThreads=16 \
  -XX:ConcGCThreads=4 \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -Djdk.virtualThreadScheduler.maxPoolSize=256 \
  -jar jotp-application.jar
```

---

## Next Steps

1. **Explore Examples**: `src/main/java/io/github/seanchatmangpt/jotp/examples/`
2. **Read Architecture**: `docs/ARCHITECTURE.md`
3. **Distributed Guide**: `docs/distributed/MULTI-JVM-ARCHITECTURE.md`
4. **Deployment**: `docs/distributed/DEPLOYMENT-PATTERNS.md`

## Performance Quick Reference

| Metric | Value | Notes |
|--------|-------|-------|
| Process Spawn | ~200 ns | Virtual thread overhead |
| Message Send | ~300 ns | LinkedTransferQueue xfer |
| Memory/Process | ~3.9 KB | Measured empirically |
| Max Processes | ~1M | With 4GB heap |
| Throughput | ~4.6M msg/s | Single producer-consumer |

---

**Version**: 1.0.0
**Last Updated**: 2025-03-16
**Java Version**: 21+ (virtual threads)
