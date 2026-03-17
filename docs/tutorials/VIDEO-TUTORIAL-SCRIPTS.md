# JOTP Video Tutorial Scripts

Complete scripts and outlines for producing JOTP video tutorials. Each script includes timing, visual cues, and code examples.

---

## Series Overview

**Target Audience**: Java developers interested in Erlang/OTP patterns
**Format**: Screen recording with voiceover + live coding
**Length**: 10-20 minutes per episode
**Prerequisites**: Java 21+, Maven, basic concurrency knowledge

---

## Episode 1: Introduction to JOTP (15 min)

### Opening (2 min)

**[00:00 - 00:30] Title Card**
- Text: "JOTP: Erlang/OTP Patterns for Java"
- Subtitle: "Fault-tolerant, distributed systems with virtual threads"
- Background: Animated process tree growing

**[00:30 - 02:00] Problem Statement**
```
[Slide: The Concurrency Problem]
❌ Shared mutable state = race conditions
❌ Complex exception handling = crashes
❌ Distributed systems = hard

[Slide: Erlang/OTP Solution]
✅ Let It Crash - supervisors restart failed processes
✅ Message passing - no shared state
✅ Location transparency - same code, local or distributed

[Slide: JOTP Brings This to Java]
✅ Virtual threads (Project Loom)
✅ Sealed types (Java 21)
✅ Pattern matching (Java 21)
✅ 15 OTP primitives implemented
```

### What is JOTP? (5 min)

**[02:00 - 04:00] Core Concepts**
```
[Screen: JOTP Architecture Diagram]

┌─────────────────────────────────────┐
│         Supervisor (root)           │
│  ┌──────────┐  ┌──────────┐         │
│  │ Worker 1 │  │ Worker 2 │  ...    │
│  └──────────┘  └──────────┘         │
└─────────────────────────────────────┘

[Voiceover]: "JOTP implements Erlang's OTP design patterns.
You get processes, supervisors, and state machines -
all running on lightweight virtual threads."
```

**[04:00 - 07:00] The 15 Primitives**
```
[Slide: Process Primitives]
• Proc<S,M> - Lightweight process with mailbox
• ProcRef<S,M> - Stable process reference
• ProcLink - Bidirectional crash propagation
• ProcMonitor - One-way death notification
• ProcRegistry - Name-based process lookup

[Slide: Supervision Primitives]
• Supervisor - Fault-tolerent process tree
• StateMachine - Sealed state transitions
• ProcTimer - Scheduled message delivery

[Slide: Advanced Primitives]
• Parallel - Structured concurrency
• EventManager - Typed event bus
• Result<T,E> - Railway-oriented error handling
• And more...
```

### Hello World Demo (5 min)

**[07:00 - 12:00] Live Coding**
```java
// [Type on screen]
import io.github.seanchatmangpt.jotp.*;
import static io.github.seanchatmangpt.jotp.Proc.spawn;

public class HelloJOTP {
    public static void main(String[] args) {
        // Define message protocol (sealed interface)
        sealed interface Message {
            String getName();
        }
        record Greet(String name) implements Message {}

        // Spawn a process
        Proc<Integer, Message> greeter = spawn(
            "greeter",        // name
            0,                // initial state
            (state, msg) -> { // handler function
                System.out.println("Hello, " + msg.getName() + "!");
                return state + 1;
            }
        );

        // Send messages
        greeter.send(new Greet("World"));
        greeter.send(new Greet("JOTP"));

        System.out.println("Greeted " + greeter.state() + " times");
    }
}

[Terminal output]:
Hello, World!
Hello, JOTP!
Greeted 2 times
```

**[12:00 - 13:00] Breakdown**
```
[Voiceover]: "Key points:
1. Sealed interface = type-safe messages
2. spawn() creates a virtual thread process
3. Handler is pure function: (state, msg) → newState
4. No locks, no synchronized blocks"
```

### What's Next (2 min)

**[13:00 - 15:00] Teaser**
```
[Slide: Coming Up Next]
Episode 2: Supervision Trees
- Let It Crash philosophy
- Restart strategies
- Live demo of crash recovery

Episode 3: State Machines
- Sealed states and events
- Event sourcing
- Workflow orchestration

[Slide: Resources]
• GitHub: github.com/seanchatmangpt/jotp
• Docs: jotp.io/docs
• Book: jotp.io/book
```

---

## Episode 2: Supervision Trees (18 min)

### Opening (2 min)

**[00:00 - 00:30] Recap**
```
[Slide: Last Episode]
• JOTP = Erlang/OTP patterns for Java
• Proc<S,M> = lightweight process with mailbox
• Pure function handlers

[Title Card]: Episode 2 - Supervision Trees
Subtitle: "Let It Crash - Fault Tolerance by Design"
```

**[00:30 - 02:00] The Philosophy**
```
[Slide: Let It Crash]
❌ Don't: Try-catch everywhere
✅ Do: Let supervisors handle failures

[Animation: Worker Crashes → Supervisor Restarts]
```

### Restart Strategies (6 min)

**[02:00 - 05:00] Three Strategies**
```
[Slide: ONE_FOR_ONE]
[W1] [W2] [W3] [W4]
  ↓    ↑
crash  restart
Only W2 restarts

[Slide: ONE_FOR_ALL]
[W1] [W2] [W3] [W4]
  ↓    ↓  ↓  ↓
crash restart restart restart restart
All children restart

[Slide: REST_FOR_ONE]
[W1] [W2] [W3] [W4]
  ↓    ↓  ↓
crash restart restart
W2 + W3 + W4 restart (everything after W1)
```

**[05:00 - 08:00] Live Demo Setup**
```java
// [Type on screen]
sealed interface Message {
    String getTaskId();
}
record Process(String taskId) implements Message {}
record Crash(String taskId) implements Message {}

Proc<Integer, Message> worker = spawn(
    "worker",
    0,
    (state, msg) -> switch (msg) {
        case Process(String t) -> {
            System.out.println("Processing " + t);
            yield state + 1;
        }
        case Crash(String t) -> {
            throw new RuntimeException("Task failed: " + t);
        }
    }
);
```

### Live Demo (8 min)

**[08:00 - 12:00] ONE_FOR_ONE Demo**
```java
// [Continue typing]
Supervisor supervisor = Supervisor.supervise(
    Supervisor.ChildSpec.builder()
        .name("worker")
        .proc(() -> worker)
        .strategy(Supervisor.RestartStrategy.ONE_FOR_ONE)
        .maxRestarts(5)
        .restartDuration(Duration.ofMinutes(1))
        .build()
);

// [Terminal]
worker.send(new Process("task-1"));  // state = 1
worker.send(new Process("task-2"));  // state = 2
worker.send(new Crash("task-3"));    // CRASH!
// [Show log]: Worker crashed, restarting...
worker.send(new Process("task-4"));  // state = 1 (restarted)
```

**[12:00 - 16:00] Comparing Strategies**
```java
// [Switch strategy]
.strategy(Supervisor.RestartStrategy.ONE_FOR_ALL)

// [Terminal output]
Starting worker: worker-1
Starting worker: worker-2
Crash in worker-1
Restarting ALL workers...

[Voiceover]: "See the difference?
ONE_FOR_ONE: Only crashed worker restarts
ONE_FOR_ALL: Everyone restarts (use for coupled services)"
```

### Production Tips (2 min)

**[16:00 - 18:00] Best Practices**
```
[Slide: Production Checklist]
✓ Add exponential backoff
✓ Use maxRestarts to prevent restart loops
✓ Monitor with ProcSys.getStatistics()
✓ Use CrashRecovery.wrap() for external calls

[Slide: When to Use Which Strategy]
ONE_FOR_ONE → Independent workers (most common)
ONE_FOR_ALL → Tightly coupled services
REST_FOR_ONE → Ordered startup dependencies
```

---

## Episode 3: State Machines (16 min)

### Opening (2 min)

**[00:00 - 02:00] Motivation**
```
[Slide: State Management is Hard]
❌ if/else spaghetti
❌ Forgotten edge cases
❌ Invalid state transitions

[Slide: Sealed State Machines]
✅ Compiler-enforced exhaustive handling
✅ Explicit transitions
✅ Event sourcing built-in
```

### Sealed States (6 min)

**[02:00 - 05:00] The Pattern**
```java
// [Type on screen]
// States (sealed)
sealed interface OrderState permits
    Pending, Authorized, Shipped, Delivered {}

record Pending(String orderId) implements OrderState {}
record Authorized(String orderId, String paymentId)
    implements OrderState {}
record Shipped(String orderId, String trackingId)
    implements OrderState {}
record Delivered(String orderId) implements OrderState {}

// Events (sealed)
sealed interface OrderEvent permits
    Authorize, Ship, ConfirmDelivery {}

record Authorize(String cardId) implements OrderEvent {}
record Ship(String address) implements OrderEvent {}
record ConfirmDelivery() implements OrderEvent {}
```

**[05:00 - 08:00] Building the Machine**
```java
// [Continue typing]
StateMachine<OrderState, OrderEvent, Void> machine =
    StateMachine.<OrderState, OrderEvent, Void>builder()
        .initialState(new Pending("order-123"))
        .handler((state, event, ctx) -> switch (state) {
            case Pending(String id) -> switch (event) {
                case Authorize(String card) ->
                    StateMachine.Next.of(new Authorized(
                        id,
                        "pay-" + UUID.randomUUID()
                    ));
                default -> StateMachine.Keep.of();
            };
            case Authorized(String id, String payId) ->
                switch (event) {
                    case Ship(String addr) ->
                        StateMachine.Next.of(new Shipped(
                            id,
                            "track-" + UUID.randomUUID()
                        ));
                    default -> StateMachine.Keep.of();
                };
            // ... more cases
        })
        .build();
```

### Live Demo (6 min)

**[08:00 - 14:00] Running the Machine**
```java
// [Terminal]
machine.send(new Authorize("card-123"));
// [Output]: State transition: Pending → Authorized
// State: Authorized(orderId=order-123, paymentId=pay-abc)

machine.send(new Ship("123 Main St"));
// [Output]: State transition: Authorized → Shipped
// State: Shipped(orderId=order-123, trackingId=track-xyz)

// [Show invalid transition]
machine.send(new Authorize("card-456"));
// [Output]: Event ignored in current state
// State: Shipped(...) [unchanged]

[Voiceover]: "See how invalid transitions are ignored?
The compiler made us handle every state-event combination!"
```

### Production Patterns (2 min)

**[14:00 - 16:00] Event Sourcing**
```
[Slide: Event Sourcing with StateMachine]
✅ All transitions logged automatically
✅ Replay events to rebuild state
✅ Audit trail built-in

[Code snippet]
machine.getTransitionHistory()
// [List of all state changes]

// Rebuild from events
StateMachine.replay(events)
```

---

## Episode 4: Distributed JOTP (20 min)

### Opening (3 min)

**[00:00 - 01:00] Title Card**
```
Episode 4: Distributed JOTP
Subtitle: "Multi-JVM Fault Tolerance"
```

**[01:00 - 03:00] The Challenge**
```
[Slide: Distributed Systems Problems]
❌ Single point of failure
❌ Split-brain scenarios
❌ Network partitions

[Slide: JOTP Distributed Solution]
✅ Leader election with priority lists
✅ Automatic failover (200ms monitoring)
✅ Deterministic re-election (no split-brain)
```

### Architecture Demo (7 min)

**[03:00 - 06:00] Setup**
```java
// [Type on screen]
// Create 3 nodes
var node1 = new DistributedNode(
    "cp1", "localhost", 5001,
    NodeConfig.defaults()
);
var node2 = new DistributedNode(
    "cp2", "localhost", 5002,
    NodeConfig.defaults()
);
var node3 = new DistributedNode(
    "cp3", "localhost", 5003,
    NodeConfig.defaults()
);

// Define app spec with priority list
var spec = new DistributedAppSpec(
    "myapp",
    List.of(
        List.of(node1.nodeId()),  // Priority 1
        List.of(node2.nodeId()),  // Priority 2
        List.of(node3.nodeId())   // Priority 3
    ),
    Duration.ofSeconds(5)  // failover timeout
);
```

**[06:00 - 10:00] Callbacks and Start**
```java
// [Continue typing]
node1.register(spec, new ApplicationCallbacks() {
    @Override
    public void onStart(StartMode mode) {
        System.out.println("cp1: STARTING as " + mode);
        // Start your app here (only on leader)
    }
    @Override
    public void onStop() {
        System.out.println("cp1: STOPPING");
    }
});

// Register at node2, node3...

// Start ALL nodes - only cp1 runs
node1.start("myapp");  // [Output]: cp1: STARTING as Normal
node2.start("myapp");  // [Output]: cp2: STANDBY
node3.start("myapp");  // [Output]: cp3: STANDBY
```

### Failover Demo (8 min)

**[10:00 - 14:00] Crash Simulation**
```java
// [Terminal simulation]
[cp1]$ Running app...
[cp2]$ Monitoring cp1...
[cp3]$ Monitoring cp1...

// [Kill cp1 process]
[KILL SIGNAL]

[cp2]$ cp1 unreachable!
[cp2]$ Waiting 5s for failover timeout...
[cp2]$ cp1 still down
[cp2]$ I am the new leader!
[cp2]$ cp2: STARTING as Failover

[cp3]$ Now monitoring cp2...
```

**[14:00 - 18:00] Visual Diagram**
```
[Animation: Timeline]

t=0s     t=5s      t=10s
cp1 [LEADER] [DEAD]
cp2 [STANDBY] → [ELECTION] → [LEADER]
cp3 [STANDBY]           [STANDBY]

[Voiceover]: "Notice:
1. 5s failover timeout (configurable)
2. Deterministic election - no split brain
3. No external consensus needed (Raft, Zab)"
```

### Production Deployment (2 min)

**[18:00 - 20:00] Kubernetes**
```
[Slide: Production Deployment]
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: jotp-cluster
spec:
  replicas: 3
  serviceName: jotp-headless
  template:
    spec:
      containers:
      - name: jotp
        env:
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name

[Slide: Key Points]
✅ StatefulSet for stable network identities
✅ Headless service for peer-to-peer
✅ Readiness probe for leader readiness
```

---

## Episode 5: Production Deployment (18 min)

### Opening (2 min)

**[00:00 - 02:00] Overview**
```
[Slide: Production Checklist]
✓ Memory sizing
✓ GC tuning
✓ Monitoring
✓ Tracing
✓ Resource limits
✓ Service mesh integration

[Title Card]: Episode 5 - Production Deployment
```

### Memory and GC (5 min)

**[02:00 - 05:00] Process Memory**
```
[Slide: Memory per Process]
Empirically measured: ~3.9 KB/process

1M processes = ~4 GB heap

[Slide: JVM Configuration]
-Xms4g -Xmx4g
-XX:+UseZGC
-XX:+ZGenerational
-XX:ParallelGCThreads=16
```

**[05:00 - 07:00] Live Demo**
```bash
# [Terminal]
$ java -Xms4g -Xmx4g -XX:+UseZGC -jar jotp.jar

# [ProcessMemoryAnalysisTest output]
Processes: 1,000,000
Total heap: 3,987,123,456 bytes
Bytes per process: 3,987
```

### Observability (6 min)

**[07:00 - 10:00] Metrics**
```java
// [Type on screen]
// Enable observability
System.setProperty("jotp.observability.enabled", "true");

// Get statistics
var stats = ProcSys.getStatistics(process);
// → messagesProcessed, avgLatencyNs, mailboxSize

// Prometheus metrics endpoint
// /metrics
jotp_process_mailbox_size{process="order-processor"} 42
jotp_process_messages_processed_total{process="order-processor"} 15234
```

**[10:00 - 13:00] Tracing**
```
[Slide: OpenTelemetry Integration]
System.setProperty("jotp.telemetry.endpoint",
    "http://jaeger:4318");

[Jaeger UI screenshot]
Showing trace:
→ Receive Order
→ Validate Payment
→ Charge Card
→ Ship Order
→ Send Confirmation
```

### Kubernetes Deployment (5 min)

**[13:00 - 15:00] Manifests**
```yaml
# k8s/statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: jotp-cluster
spec:
  replicas: 3
  serviceName: jotp-headless
  template:
    spec:
      containers:
      - name: jotp
        image: jotp:1.0.0
        resources:
          requests:
            memory: "4Gi"
            cpu: "2"
          limits:
            memory: "6Gi"
            cpu: "4"
```

**[15:00 - 18:00] Service Mesh**
```
[Slide: Istio Integration]
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-mesh
spec:
  http:
  - match:
    - headers:
        x-jotp-leader:
          exact: "true"
    route:
    - destination:
        host: jotp-cluster
        subset: leader

[Voiceover]: "Service mesh gives you:
mTLS, traffic management, observability"
```

---

## Production Notes

### Recording Setup

**Hardware:**
- Mac Studio M2 Ultra or equivalent
- 32GB+ RAM
- 4K monitor (for code readability)

**Software:**
- Screen recording: OBS Studio or CleanShot X
- Code: VS Code with JOTP syntax highlighting
- Terminal: iTerm2 with Dracula theme
- Font: JetBrains Mono 14pt

**Recording Settings:**
- Resolution: 3840×2160 (4K)
- Frame rate: 30 fps
- Bitrate: 15 Mbps
- Audio: 44.1 kHz, mono

### Editing Guidelines

1. **Opening**: 2-3 seconds of title card with fade-in
2. **Transitions**: Simple cross-fade (0.5s)
3. **Code typing**: Speed 2-3×, show cursor
4. **Terminal**: Clear previous commands, show prompt
5. **Diagrams**: Animated entrance (0.3s per element)
6. **Closing**: 3-5 seconds with resources slide

### Voiceover Tips

- **Tone**: Conversational, not lecture
- **Pace**: 150-160 words per minute
- **Emphasis**: Stress key terms (sealed, supervisor, mailbox)
- **Pauses**: 1-2 seconds after complex concepts
- **Energy**: Vary pitch, avoid monotone

### Thumbnail Templates

```
┌─────────────────────────────────────┐
│     [JOTP Logo]    Episode 1       │
│                                     │
│  Introduction to JOTP              │
│  Erlang/OTP Patterns for Java      │
│                                     │
│  [Your Name] | [Duration]          │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│     🌳 Episode 2 🌳                │
│                                     │
│  Supervision Trees                 │
│  Let It Crash - Live Demo          │
│                                     │
│  18:32 | Java, Concurrency         │
└─────────────────────────────────────┘
```

---

## Additional Episodes (Outlines)

### Episode 6: Event Management (15 min)
- Typed event bus
- Crash isolation
- Pub/sub patterns
- Live demo: Trading system

### Episode 7: Error Handling (14 min)
- Result<T,E> type
- Railway-oriented programming
- Error recovery patterns
- Live demo: Payment processing

### Episode 8: Performance Tuning (16 min)
- JMH benchmarks
- Throughput optimization
- Memory profiling
- Live demo: Profiling session

### Episode 9: Testing JOTP (17 min)
- Property-based testing with jqwik
- Deterministic testing
- Chaos testing
- Live demo: Writing tests

### Episode 10: Real-World Architecture (19 min)
- Case study: Payment system
- Supervision tree design
- Distributed setup
- Migration from Spring Boot

---

**Version**: 1.0.0
**Total Duration**: ~3 hours (10 episodes)
**Target Release**: Q2 2025
