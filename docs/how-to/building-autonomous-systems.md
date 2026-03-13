# Building Autonomous Systems with JOTP

**Target Audience:** Enterprise architects, tech leads, and system designers considering the shift from microservices to autonomous agent architectures.

---

## The Paradigm Shift: From Services to Agents

For 15 years, the industry has organized distributed systems as **services**:
- Services are stateless, horizontal, replaceable
- Requests flow in → responses flow out
- Failure is handled by retry logic, circuit breakers, bulkheads

By 2030, this model will be **obsolete**. Enterprise systems are shifting to **autonomous agents**:
- Agents are stateful, independent, self-healing
- Agents communicate asynchronously through typed messages
- Failure is embraced—agents crash and are automatically restarted by supervisors

**JOTP makes this shift possible in Java.**

---

## Why Now? Why Java 26?

Three forces converge:

### 1. **Virtual Threads** (Java 21+)
Traditional Java threads were expensive (~1 MB each). Virtual threads are lightweight (~1 KB each), enabling millions of concurrent agents on a single JVM.

```java
// Before: 100 threads max per service
ExecutorService executor = Executors.newFixedThreadPool(100);

// After: 1 million agents on one JVM
for (int i = 0; i < 1_000_000; i++) {
    Proc.spawn(init, handler, args);  // 1 KB overhead per agent
}
```

### 2. **Sealed Types + Pattern Matching** (Java 17+)
Distributed systems are intricate state machines. Sealed types + pattern matching make impossible states impossible.

```java
// Sealed event hierarchy—compiler ensures all cases handled
sealed interface Event {
    record UserAction(int userId, String action) implements Event {}
    record SystemFailure(String reason) implements Event {}
    record Timeout() implements Event {}
}

// Pattern matching—exhaustiveness checking
switch (event) {
    case UserAction(var userId, var action) -> handleUser(userId, action);
    case SystemFailure(var reason) -> logFailure(reason);
    case Timeout() -> retryOperation();
    // Compiler error if you miss a case!
}
```

### 3. **OTP Patterns** (40 Years Proven)
Erlang's OTP has solved distributed systems problems for 40 years. JOTP brings those patterns to Java.

```java
// OTP supervisor tree: if a worker fails, restart it automatically
var supervisor = Supervisor.create()
    .withStrategy(RestartStrategy.ONE_FOR_ONE)
    .withChild(ChildSpec.of(
        id,
        () -> Proc.spawn(agentInit, agentHandler, args),
        RestartType.PERMANENT
    ))
    .start();
```

---

## Core Concept 1: Processes Are Agents

In JOTP, a **process** is the primary unit of computation. Each process:
- Owns its state (no shared memory)
- Has its own virtual thread (truly concurrent)
- Receives messages asynchronously
- Can crash and be restarted independently

```java
// Define a simple counter agent
record CounterState(int value) {}

record Increment() {}
record Get() {}

// Spawn the agent
var counter = Proc.<CounterState, Object>spawn(
    () -> new CounterState(0),  // init state
    (state, msg) -> switch (msg) {
        case Increment() ->
            new StateResult<>(new CounterState(state.value + 1), null);
        case Get() ->
            new StateResult<>(state, state.value);
        default ->
            new StateResult<>(state, null);
    },
    null  // args
);

// Send messages to the agent
counter.tell(new Increment());
counter.tell(new Increment());
int result = (int) counter.ask(new Get(), Duration.ofSeconds(1));
System.out.println(result);  // 2
```

**Why agents instead of objects?**
- **Isolation:** No shared memory = no locks = no deadlocks
- **Resilience:** If one agent crashes, others continue
- **Distribution:** Agents can move across networks (future JOTP feature)
- **Observability:** Every agent can be independently monitored, suspended, restarted

---

## Core Concept 2: Supervision Trees

An agent's job is to work correctly under normal conditions. A **supervisor's** job is to handle abnormal conditions.

```java
// Supervisor tree: typical banking system
var supervisor = Supervisor.create()
    .withStrategy(RestartStrategy.ONE_FOR_ONE)

    // Worker: process transactions
    .withChild(ChildSpec.of(
        "transaction-processor",
        () -> Proc.spawn(transactionInit, transactionHandler, args),
        RestartType.PERMANENT
    ))

    // Worker: log events
    .withChild(ChildSpec.of(
        "event-logger",
        () -> Proc.spawn(loggerInit, loggerHandler, args),
        RestartType.PERMANENT
    ))

    // Worker: monitor system health
    .withChild(ChildSpec.of(
        "health-monitor",
        () -> Proc.spawn(healthInit, healthHandler, args),
        RestartType.PERMANENT
    ))

    .start();
```

**What happens when a worker crashes?**

1. **The supervisor detects the crash** (the process exits)
2. **The supervisor restarts the worker** (with fresh state)
3. **Other workers continue** (unaffected)
4. **The system self-heals** (no manual intervention)

This is **"let it crash"** in action. Instead of defensive programming:

```java
// ❌ Defensive programming (traditional Java)
try {
    processTransaction();
} catch (Exception e) {
    logError(e);
    retry();  // Ad-hoc retry logic
}

// ✅ Declarative supervision (JOTP)
// "If this worker crashes, restart it automatically"
// No try/catch needed—crashes are features, not bugs
```

---

## Core Concept 3: Failure is Information

In JOTP, every crash generates structured data that the system can learn from.

```java
// Supervisor with restart policy
var supervisor = Supervisor.create()
    .withStrategy(RestartStrategy.ONE_FOR_ONE)
    .withMaxRestarts(3)  // Max 3 restarts...
    .withWindowSeconds(60)  // ...in 60 seconds

    .withChild(ChildSpec.of(
        "worker",
        () -> workerProcess(),
        RestartType.PERMANENT
    ))

    .onChildExit((childId, exitReason) -> {
        // Log the failure for analysis
        if (exitReason instanceof ExitSignal.Abnormal(var error)) {
            System.out.println("Worker crashed: " + error);
            metrics.recordFailure(childId, error);
        }
    })

    .start();
```

**By 2030**, systems will:
- Automatically detect failure patterns
- Adjust restart strategies based on historical data
- Route around failures in real-time
- Become *more* resilient with every crash (because they learn)

**This is what "intelligent systems" really means:** Systems that improve through failure, not despite it.

---

## Core Concept 4: The Agent Lifecycle

An agent's lifecycle:

```
┌──────────────────────────────────────────────────────┐
│ AGENT LIFECYCLE                                      │
├──────────────────────────────────────────────────────┤
│                                                      │
│ 1. SPAWNED                                           │
│    ├─ Virtual thread created                         │
│    ├─ Mailbox initialized                            │
│    └─ Init function called                           │
│                                                      │
│ 2. RUNNING                                           │
│    ├─ Receive messages (tell/ask)                    │
│    ├─ Handle each message                            │
│    ├─ Update state                                   │
│    └─ Send replies (if ask)                          │
│                                                      │
│ 3. CRASHED or EXITED                                 │
│    ├─ Supervisor detects exit                        │
│    ├─ Supervisor evaluates restart policy            │
│    └─ Restart OR shut down child                     │
│                                                      │
│ 4. RESTARTED                                         │
│    └─ Go to step 1 (fresh state)                     │
│                                                      │
└──────────────────────────────────────────────────────┘
```

**Key insight:** An agent can crash, be restarted, and forget all about the crash. The supervisor remembers and learns.

---

## Example: Building a Real System

Let's build a **distributed cache** using JOTP:

### Architecture

```
┌─────────────────────────────────────────────┐
│ Cache Supervisor                            │
│  ├─ Cache Worker 1 (partition 1)            │
│  ├─ Cache Worker 2 (partition 2)            │
│  ├─ Cache Worker 3 (partition 3)            │
│  └─ Health Monitor                          │
└─────────────────────────────────────────────┘
```

### Implementation

```java
// Cache partition state
record CachePartition(Map<String, Object> data) {}

// Messages
record CacheGet(String key) {}
record CacheSet(String key, Object value) {}
record CacheDelete(String key) {}

// Worker that handles one partition
class CachePartitionWorker {
    static Proc<CachePartition, Object> spawn(int id) {
        return Proc.spawn(
            () -> new CachePartition(new ConcurrentHashMap<>()),
            (state, msg) -> switch (msg) {
                case CacheGet(var key) ->
                    new StateResult<>(state, state.data.get(key));

                case CacheSet(var key, var value) -> {
                    state.data.put(key, value);
                    yield new StateResult<>(state, null);
                }

                case CacheDelete(var key) -> {
                    state.data.remove(key);
                    yield new StateResult<>(state, null);
                }

                default -> new StateResult<>(state, null);
            },
            null
        );
    }
}

// Supervisor managing the cache cluster
class Cache {
    private final Supervisor supervisor;
    private final Proc<?, Object>[] partitions;
    private static final int PARTITIONS = 3;

    public Cache() {
        var builder = Supervisor.create()
            .withStrategy(RestartStrategy.ONE_FOR_ONE)
            .withMaxRestarts(5)
            .withWindowSeconds(30);

        for (int i = 0; i < PARTITIONS; i++) {
            final int id = i;
            builder = builder.withChild(ChildSpec.of(
                "partition-" + id,
                () -> CachePartitionWorker.spawn(id),
                RestartType.PERMANENT
            ));
        }

        this.supervisor = builder.start();
        this.partitions = new Proc[PARTITIONS];
        // Retrieve process references after startup
    }

    public void set(String key, Object value) {
        int partition = Math.abs(key.hashCode()) % PARTITIONS;
        partitions[partition].tell(new CacheSet(key, value));
    }

    public Object get(String key) {
        int partition = Math.abs(key.hashCode()) % PARTITIONS;
        try {
            return partitions[partition].ask(new CacheGet(key), Duration.ofSeconds(1));
        } catch (TimeoutException e) {
            return null;
        }
    }
}
```

**Resilience properties:**
- If partition 1 crashes → supervisor restarts it with fresh state
- Other partitions continue serving traffic
- No manual recovery needed
- No distributed consensus required

---

## Core Concept 5: Thinking in Agents

The biggest mental shift from services to agents:

| Service Mindset | Agent Mindset |
|---|---|
| "How do I prevent failures?" | "How do I recover from failures?" |
| Defensive programming (try/catch everywhere) | Declarative supervision |
| Shared mutable state + locks | Isolated state + message passing |
| Horizontal scaling (more instances) | Vertical scaling (more agents) |
| Request-response (RPC) | Asynchronous messages |
| Long-lived connections | Stateless connections |
| Complex state machines | Simple state handlers |

**Example: Payment Processing**

### Service Approach (Traditional)
```java
@RestController
public class PaymentService {
    @PostMapping("/pay")
    public Response pay(PaymentRequest req) {
        try {
            validateCard(req.cardId);
            debitAccount(req.fromId, req.amount);
            creditAccount(req.toId, req.amount);
            logTransaction(req);
            notifyUser(req.userId);
            return Response.success();
        } catch (ValidationException e) {
            retry();  // ad-hoc retry
        } catch (Exception e) {
            return Response.error();  // customer loses money or doesn't know
        }
    }
}
```

**Problems:**
- If validation crashes mid-debit, ledger is inconsistent
- Retry logic is ad-hoc and incomplete
- No way to recover from partial failures
- Observability is difficult (logs buried in monolith)

### Agent Approach (JOTP)
```java
// State machine for a single payment
sealed interface PaymentState {
    record Pending() implements PaymentState {}
    record Validated() implements PaymentState {}
    record Debited() implements PaymentState {}
    record Credited() implements PaymentState {}
    record Complete() implements PaymentState {}
}

sealed interface PaymentEvent {
    record InitiatePayment(PaymentRequest req) implements PaymentEvent {}
    record ValidationComplete() implements PaymentEvent {}
    record DebitComplete() implements PaymentEvent {}
    record CreditComplete() implements PaymentEvent {}
    record PaymentFailed(String reason) implements PaymentEvent {}
}

// State machine engine
var paymentProcessor = StateMachine.<PaymentState, PaymentEvent, PaymentContext>create()
    .withInitialState(new PaymentState.Pending())

    // Pending → Validated
    .withTransition(PaymentState.Pending.class, PaymentEvent.InitiatePayment.class,
        (state, event, ctx) -> {
            ctx.payment = event.req();
            ctx.validationService.validate(event.req());  // Fire-and-forget async
            return new Transition.NextState(
                new PaymentState.Validated(),
                List.of(new Action.Set(() -> validationTimeout()))
            );
        })

    // Validated → Debited
    .withTransition(PaymentState.Validated.class, PaymentEvent.ValidationComplete.class,
        (state, event, ctx) -> {
            ctx.ledger.debit(ctx.payment.fromId(), ctx.payment.amount());
            ctx.ledger.onDebitComplete(ctx.payment.id());
            return new Transition.NextState(
                new PaymentState.Debited(),
                List.of(new Action.Set(() -> creditTimeout()))
            );
        })

    // ... more transitions

    .start();

// Process payments through supervisor tree
var paymentSupervisor = Supervisor.create()
    .withStrategy(RestartStrategy.ONE_FOR_ONE)

    // Each active payment is a state machine child
    .withChild(ChildSpec.of(
        "payment-" + paymentId,
        () -> paymentProcessor,
        RestartType.TEMPORARY  // Remove after complete
    ))

    .start();
```

**Benefits:**
- Each payment is independent; if one crashes, others continue
- State is always consistent (state machine enforces valid transitions)
- Retry is automatic (supervisor restarts) with smart backoff
- Observability: each payment is a distinct agent with its own identity
- By 2030: ML models learn optimal restart policies per payment type

---

## Migration Path: Spring Boot → JOTP

Fortune 500 enterprises can't rewrite everything at once. JOTP enables **gradual migration**:

### Phase 0: Assessment
```bash
# Analyze codebase for migration candidates
bin/jgen refactor --source ./legacy --score
# Output: Services sorted by migration ROI
```

### Phase 1: Pilot
- Identify 1-2 services that are:
  - High-traffic (benefit from agents)
  - Self-contained (few dependencies)
  - Fault-prone (high restart frequency)

- Refactor to JOTP agents in parallel
- Run both versions, compare metrics

### Phase 2: Dual-Write
```java
// Spring service continues to run...
@RestController
public class LegacyPaymentService {
    @PostMapping("/pay")
    public Response pay(PaymentRequest req) {
        // Also send to new JOTP agent system
        jotp_paymentAgent.tell(new InitiatePayment(req));

        // Continue serving from legacy for now
        return Response.success();
    }
}

// ...while new JOTP system learns the traffic patterns
```

### Phase 3: Cutover
- Route new traffic to JOTP agents
- Keep legacy for fallback
- Monitor failure rates (should decrease with agent learning)

### Phase 4: Ecosystem
- JOTP agents coordinate across services
- Spring services become thin wrappers around JOTP agents
- Legacy logic replaced with agent state machines

---

## Enterprise Patterns

JOTP supports common enterprise requirements:

### Bulkhead Isolation
```java
// Limit resource consumption per customer
var bulkhead = Supervisor.create()
    .withStrategy(RestartStrategy.REST_FOR_ONE)
    .withMaxRestarts(10)  // Per customer
    .withWindowSeconds(60)

    .withChild(ChildSpec.of(
        "customer-" + customerId,
        () -> customerAgent(customerId),
        RestartType.PERMANENT
    ))

    .start();
```

### Circuit Breaker
```java
// Fail fast if external service is down
if (externalService.recentFailureRate() > 0.5) {
    // Don't even try—respond immediately
    return Result.err(new CircuitBreakerOpen());
}

// Otherwise try the request
try {
    externalService.call();
} catch (TimeoutException e) {
    failureCount.increment();
}
```

### Saga Coordination
```java
// Multi-step distributed transaction
var saga = Supervisor.create()
    .withChild(sagaOrchestrator(step1, step2, step3))
    .start();

// If step 2 fails, automatically compensate step 1
// (order → payment → inventory)
// If inventory fails → refund payment → cancel order
```

---

## The 2030 Vision

By 2030, enterprises using JOTP will:

1. **Run mission-critical systems with zero manual restarts**
   - Failures are automatic, contained, and logged
   - Supervisors learn optimal recovery strategies

2. **Scale to millions of agents per JVM**
   - Virtual threads make lightweight concurrency default
   - Each customer, each order, each transaction is an agent

3. **Build systems that improve through failures**
   - Every crash is training data
   - ML models optimize restart policies, timeout values, bulkhead sizes

4. **Eliminate distributed consensus complexity**
   - No Kafka, Redis, or Zookeeper for basic coordination
   - Message passing and supervisor trees handle all orchestration

5. **Achieve 99.99%+ SLAs**
   - Through automated, intelligent recovery
   - Not through defensive programming or additional layers

---

## Next Steps

1. **Clone the JOTP example repository**
   ```bash
   git clone https://github.com/seanchatmangpt/jotp-examples.git
   cd jotp-examples/cache
   mvn verify
   ```

2. **Run the chaos demo**
   ```bash
   java -Dcom.sun.management.jmxremote=true \
        -cp target/classes \
        io.github.seanchatmangpt.jotp.examples.CacheChaosDemo
   # Watch: Kill agents randomly, system self-heals
   ```

3. **Build your first agent**
   ```java
   var myAgent = Proc.spawn(
       () -> new MyState(),
       (state, msg) -> handleMessage(state, msg),
       null
   );
   ```

4. **Explore the Spring Boot integration**
   ```bash
   cd ../spring-integration
   mvn spring-boot:run
   ```

---

## References

- **JOTP Core:** `io.github.seanchatmangpt.jotp` — All 15 primitives
- **Architecture:** `docs/ARCHITECTURE.md` — Executive summary + competitive analysis
- **SLA Patterns:** `docs/SLA-PATTERNS.md` — Operational runbooks
- **PhD Thesis:** `docs/phd-thesis-otp-java26.md` — Formal OTP equivalence
- **Erlang/OTP:** *Designing for Scalability with Erlang/OTP* by Steve Vinoski
- **Virtual Threads:** JEP 425 (https://openjdk.org/jeps/425)
- **Sealed Types:** JEP 409 (https://openjdk.org/jeps/409)
