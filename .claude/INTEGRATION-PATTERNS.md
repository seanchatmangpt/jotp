# Enterprise Integration: Adopting JOTP in Brownfield Java

**For:** Enterprise architects, tech leads planning gradual adoption in existing Spring Boot systems.

---

## Strategic Adoption Phases

### Phase 0: Assessment (Week 1-2)

**Goal:** Identify which services are JOTP candidates

**Scoring Criteria:**

| Criterion | JOTP Fit | Score |
|-----------|----------|-------|
| Stateful (maintains mutable state) | Yes | +20 |
| Concurrent requests (1K+/sec) | Yes | +15 |
| Fault tolerance critical | Yes | +15 |
| Complex error handling | Yes | +10 |
| Tight latency budget (p99 < 500ms) | Yes | +10 |
| Distributed state? | No | +5 |
| Spring only, no JOTP integration | N/A | -20 |

**Examples:**

```
Order Service
├─ Stateful: YES (order state machine)
├─ Concurrent: YES (100K orders/day)
├─ Fault-tolerant: YES (payment failures)
├─ Complex errors: YES (retry, backoff, compensation)
├─ Latency: YES (p99 < 300ms needed)
└─ SCORE: 75/100 → HIGH PRIORITY (Phase 1)

User Service
├─ Stateful: NO (mostly read from DB)
├─ Concurrent: YES (1M reqs/day)
├─ Fault-tolerant: NO (easy to retry)
├─ Complex errors: NO (simple validation)
├─ Latency: NO (p99 < 1s ok)
└─ SCORE: 15/100 → SKIP for now
```

---

### Phase 1: Pilot (3-6 months)

**Goal:** Prove JOTP in low-risk, high-impact service

**Selected service:** Order Coordinator (high score, isolated from other systems)

#### Step 1: Design Order State Machine

```java
public sealed interface OrderEvent permits
    OrderCreated,
    PaymentAuthorized,
    InventoryReserved,
    ShippingScheduled,
    OrderCompleted,
    OrderCancelled { }

public record OrderData(
    String orderId,
    List<LineItem> items,
    PaymentInfo payment,
    ShippingInfo shipping
) { }

public enum OrderState {
    INITIAL,        // Just created
    READY,          // Inventory & payment verified
    PROCESSING,     // In fulfillment
    SHIPPED,        // Left warehouse
    DELIVERED       // Destination
}

StateMachine<OrderState, OrderEvent, OrderData> fsm =
    StateMachine.builder()
        .initialState(INITIAL)
        .on(INITIAL, OrderCreated.class, (state, event, data) ->
            // Validate inventory, charge payment
            new Transition.Next(READY, data))
        .on(READY, PaymentAuthorized.class, (state, event, data) ->
            // Mark inventory reserved
            new Transition.Next(PROCESSING, data))
        .on(PROCESSING, ShippingScheduled.class, (state, event, data) ->
            new Transition.Next(SHIPPED, data))
        .build();
```

#### Step 2: Wrap in Supervisor

```java
Supervisor root = Supervisor.builder()
    .strategy(RestartStrategy.ONE_FOR_ONE)
    .maxRestarts(5)
    .withinWindow(Duration.ofSeconds(60))
    .supervise("order-coordinator",
        () -> new OrderCoordinator(fsm),
        OrderCoordinator.INIT_STATE)
    .build();
```

#### Step 3: Integrate with Spring Boot

```java
@Configuration
public class JotpConfig {
    @Bean
    public OrderCoordinator orderCoordinator(
        Supervisor supervisor,
        PaymentService paymentService,
        InventoryService inventoryService
    ) {
        return new OrderCoordinator(
            supervisor,
            paymentService,
            inventoryService
        );
    }
}

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderCoordinator coordinator;

    @PostMapping
    public Order createOrder(@RequestBody OrderRequest req) {
        // Validate via Spring, execute via JOTP
        return coordinator.ask(
            new CreateOrderMsg(req.items, req.customerId),
            Duration.ofSeconds(5)
        ).fold(
            order -> {
                orderRepo.save(order);
                return order;
            },
            error -> {
                logger.error("Order failed: {}", error);
                throw new OrderException(error);
            }
        );
    }
}
```

#### Step 4: Run Parallel (Blue-Green)

**For first 4 weeks, run BOTH:**
- **Blue (Old):** Spring Boot + traditional request handling
- **Green (New):** JOTP coordinator + Spring Boot REST layer

**Traffic split:** 10% Green, 90% Blue (gradually shift)

```
Week 1: 10% Green (10 orders/day on JOTP)
Week 2: 25% Green
Week 3: 50% Green
Week 4: 75% Green
Week 5: 100% Green (kill Blue)
```

#### Step 5: Validation Criteria

**Before production, verify:**

```bash
✓ p99 latency ≤ 300ms (Green vs Blue comparison)
✓ Error rate ≤ 0.01% (same as Blue)
✓ Restart count ≤ 5/day (minimal crashes)
✓ No memory leaks (heap stable over 24h)
✓ Recovery time < 30s (process crash → restart)
✓ Team confidence level ≥ 80%
```

---

### Phase 2: Scale-Out (Months 6-12)

**Goal:** Adopt JOTP in 5-10 core services

**Lessons from Phase 1:**
- What worked well (keep pattern)
- What didn't (adjust)
- Training needed (upskill team)

**Roadmap:**
```
Month 6:  Order + Payment services → JOTP
Month 7:  Inventory + Shipping → JOTP
Month 8:  Cart + Recommendations → JOTP
Month 9:  Auth + SessionManagement → JOTP
Month 10: User preferences → JOTP
Month 11: Refactor cross-service communication
Month 12: Full deprecation of old patterns
```

---

### Phase 3: Ecosystem Integration (Year 2)

**Goal:** JOTP as standard architecture for stateful services

**Standardization:**
```
/templates/jotp-service/
├── pom.xml                    (JOTP + Spring Boot)
├── src/main/java/
│   ├── state/ServiceState.java
│   ├── event/ServiceEvent.java
│   ├── coordinator/ServiceCoordinator.java
│   └── rest/ServiceController.java
├── src/test/java/
│   ├── StateMachineTest.java
│   ├── SupervisorTest.java
│   └── IntegrationTest.java
└── docker/Dockerfile          (GraalVM native image)
```

**Onboarding new service:**
```bash
mvn archetype:generate \
  -DarchetypeGroupId=io.github.seanchatmangpt \
  -DarchetypeArtifactId=jotp-service-archetype \
  -DgroupId=com.company \
  -DartifactId=my-service

Result: New service ready in 10 minutes, JOTP defaults included
```

---

## Integration Patterns

### Pattern 1: Coordinated Multi-Service Saga

**Problem:** Order creation requires coordinating 3 services (payment, inventory, shipping)

**Without JOTP (Spring only):**
```java
// Fragile: if any service fails mid-way, rollback is manual
try {
    Payment payment = paymentService.charge(order);
    Inventory inv = inventoryService.reserve(order);
    Shipping ship = shippingService.schedule(order);
    order.confirm();
} catch (PaymentException e) {
    // What about inventory? Already reserved?
    // Need manual compensation logic
}
```

**With JOTP (State Machine):**
```
INITIAL
  ├─ PaymentRequested
  │  → PaymentService.ask(ChargeMsg, 5s)
  │     ├─ Success → PAYMENT_APPROVED
  │     └─ Failure → CANCELLED (automatic)
  │
  ├─ InventoryRequested
  │  → InventoryService.ask(ReserveMsg, 3s)
  │     ├─ Success → INVENTORY_RESERVED
  │     └─ Failure → CANCELLED + PaymentRollback
  │
  └─ ShippingRequested
     → ShippingService.ask(ScheduleMsg, 2s)
        ├─ Success → READY_TO_SHIP
        └─ Failure → CANCELLED + InventoryRollback + PaymentRollback
```

**Implementation:**
```java
StateMachine<OrderState, OrderEvent, OrderData> saga =
    StateMachine.builder()
        .on(INITIAL, PaymentRequested.class,
            (state, event, data) -> {
                CompletableFuture<PaymentResult> future =
                    paymentService.ask(new ChargeMsg(data.amount), Duration.ofSeconds(5));

                future.thenAccept(result -> {
                    if (result.success()) {
                        coordinator.tell(new PaymentAuthorized(result.transactionId));
                    } else {
                        coordinator.tell(new PaymentFailed(result.reason));
                    }
                });

                return new Transition.Keep(AWAITING_PAYMENT);
            })
        .on(AWAITING_PAYMENT, PaymentAuthorized.class,
            (state, event, data) ->
                new Transition.Next(INVENTORY_RESERVED, data))
        .on(AWAITING_PAYMENT, PaymentFailed.class,
            (state, event, data) ->
                new Transition.Next(CANCELLED, data))
        .build();
```

**Guarantee:** Either ALL steps succeed, or NONE do (no half-committed state)

---

### Pattern 2: Tenant-Per-Spring-Boot-Instance

**Problem:** Multi-tenant SaaS where each tenant needs isolation

**Architecture:**
```
Load Balancer
├─ TenantA_JVM (1-10 instances)
│  └─ TenantA's Supervisor Tree
│     ├─ Auth
│     ├─ DataService
│     └─ Cache
├─ TenantB_JVM (1-10 instances)
│  └─ TenantB's Supervisor Tree
└─ Shared_JVM
   └─ Metrics, Analytics, Admin
```

**Benefits:**
- **Blast radius:** TenantA crash → only TenantA affected
- **Resource control:** Each tenant JVM gets CPU limits (Kubernetes)
- **Compliance:** Regulatory isolation (GDPR, PCI-DSS)

**Implementation:**
```java
// On startup, read TENANT_ID from env/config
String tenantId = System.getenv("TENANT_ID");

Supervisor tenantSupervisor = Supervisor.builder()
    .strategy(RestartStrategy.ONE_FOR_ALL)
    .supervise("auth-" + tenantId, AuthService::new, state)
    .supervise("data-" + tenantId, DataService::new, state)
    .supervise("cache-" + tenantId, CacheService::new, state)
    .build();

ProcessRegistry.register("tenant-" + tenantId, rootSupervisor);
```

---

### Pattern 3: Dual-Write (Gradual Migration)

**Problem:** Can't switch old system → new system all at once

**Solution:** Write to both, read from new (with fallback)

```java
@RestController
public class OrderController {
    @PostMapping("/orders")
    Order createOrder(@RequestBody OrderRequest req) {
        // Write to BOTH old (Spring) and new (JOTP)
        Order oldResult = oldOrderService.create(req);  // Spring DB
        Order newResult = coordinator.ask(               // JOTP state
            new CreateOrderMsg(req),
            Duration.ofSeconds(5)
        ).get();

        // Return new system result
        return newResult;

        // Monitor: Are both systems producing same result?
        // If divergence detected, alert and fallback to oldResult
    }
}
```

**Monitoring divergence:**
```
SELECT old_order_id, new_order_id
FROM orders_comparison
WHERE old_order_id != new_order_id
  AND created_time > NOW() - INTERVAL 1 HOUR;

If any rows: Alert team, pause new system, investigate
```

---

### Pattern 4: Event Sourcing Integration

**Problem:** JOTP state machine changes aren't persisted

**Solution:** Append-only audit log

```java
// After every state transition, log it
coordinator.onTransition(event -> {
    AuditLog.append(new AuditEntry(
        orderId,
        event.fromState,
        event.toState,
        event.message,
        System.currentTimeMillis()
    ));
});

// On JVM restart, replay logs to reconstruct state
List<AuditEntry> logs = AuditLog.getAll(orderId);
for (AuditEntry entry : logs) {
    coordinator.tell(entry.message);  // Replay
}
```

**Result:** JOTP acts as cache, audit log is source of truth

---

## Team & Process Integration

### Code Review Checklist for JOTP PRs

```
[ ] State machine:
    - [ ] All states documented
    - [ ] All events handled for each state
    - [ ] Transition function is pure (no side effects)
    - [ ] Sealed interface used for events

[ ] Error handling:
    - [ ] Uses Result<T, E> (not exceptions)
    - [ ] ask() calls have timeout
    - [ ] tell() never throws (async)
    - [ ] Supervisor restart limit reasonable

[ ] Testing:
    - [ ] State machine tested independently (no Spring)
    - [ ] Happy path + 3 failure scenarios
    - [ ] Integration test with Supervisor

[ ] Monitoring:
    - [ ] Metrics (latency, errors, mailbox size)
    - [ ] Logs structured (include state, event, duration)
    - [ ] Alerts configured
```

### Training Program

**Week 1: Fundamentals**
- What is an actor? (message, state, behavior)
- Why Supervisor? (fault tolerance)
- Exercise: Build simple bank account Proc

**Week 2: State Machines**
- StateMachine<S, E, D> semantics
- Sealed types for exhaustiveness
- Exercise: Order state machine

**Week 3: Integration**
- Supervisor + Spring Boot
- Testing patterns
- Exercise: Integrate into real service

**Week 4: Production**
- SLA patterns
- Observability
- Incident response

---

## Migration Risk Assessment

### High-Risk Scenarios (Avoid)

❌ **Don't do:**
- Migrate services with complex Spring transaction semantics (JPA)
- Services with global mutable state (ConcurrentHashMap caches)
- Services requiring hot reloading
- Services with external distributed consensus (Zookeeper)

**Why:** JOTP is better for stateful, isolated services. For distributed coordination, stick with Spring + Kafka.

### Low-Risk Scenarios (Go for it)

✅ **Do:**
- Coordinate multi-service workflows (Saga pattern)
- Stateful services with complex error handling
- Services with strict latency requirements
- Multi-tenant isolation scenarios

---

## Rollback Plan

**If Phase 1 fails, rollback in < 1 hour:**

```
Hour 0:00
  Blue: 100% traffic
  Green: 0% traffic (JOTP on standby)

Hour 0:55
  Incident detected (JOTP error rate > 1%)
  Alert: "JOTP coordinator unstable"

Hour 0:57
  Traffic shifted: Blue 100%, Green 0%
  Load balancer: Remove JOTP JVM instances

Hour 0:58
  Existing Green requests drained (graceful shutdown)
  New requests go to Blue only

Hour 1:00
  Service stabilized
  RCA started (what went wrong in JOTP?)

Hour 2:00
  Fix deployed to Green (new JVM instance)
  Sanity tests pass
  Decision: Retry or defer
```

**Key:** No permanent data loss (all writes went to Spring DB first)

---

## Long-Term Vision

**Year 1:** 10-20% of services using JOTP
**Year 2:** 40-60% of services using JOTP
**Year 3:** JOTP is standard for stateful services, exceptions need justification

**Result:**
- Architecture clarity (stateless: Spring Cloud, stateful: JOTP)
- SLA improvements (99.95% default for JOTP services)
- Team productivity (faster feature delivery, fewer bugs)

---

## Conclusion

JOTP adoption is a **gradual, low-risk journey:**
1. Start small (1 service, 3-6 months)
2. Validate gains (latency ↓, reliability ↑)
3. Scale systematically (5-10 more services)
4. Become standard (new default for stateful services)

**Go-to-market strategy:** Make boring stateful services reliable, then scale to mission-critical paths.
