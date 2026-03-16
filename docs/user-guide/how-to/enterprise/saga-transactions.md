# Distributed Saga Transactions

## Problem

You need to coordinate transactions across multiple microservices (e.g., e-commerce: reserve inventory, charge payment, ship order). Traditional 2-phase commit is too slow and brittle for distributed systems. If any step fails, you need to roll back previously completed steps.

**Symptoms:**
- Inconsistent state across services (inventory reserved but payment failed)
- No compensation logic to undo partial work
- Manual reconciliation required for failed transactions
- Customer frustration from "ghost orders"

## Solution

Implement the Saga pattern for distributed transactions. A saga executes a sequence of steps (forward actions) and can roll back via compensation (backward actions) if any step fails. JOTP's DistributedSagaCoordinator provides exactly-once semantics with idempotent compensation and durable step logging.

**Saga States:**
- **PENDING**: Saga registered, awaiting execution
- **IN_PROGRESS**: Executing forward steps
- **COMPLETED**: All steps succeeded
- **FAILED**: A step failed, starting compensation
- **COMPENSATED**: Compensation completed
- **ABORTED**: User-requested abort

## Prerequisites

- Java 26 with preview features enabled
- JOTP enterprise module: `io.github.seanchatmangpt.jotp.enterprise.saga`
- Understanding of compensating transactions

## Implementation

### Step 1: Define Saga Steps

```java
import io.github.seanchatmangpt.jotp.enterprise.saga.*;

// Define action steps (forward operations)
SagaStep.ReserveInventory reserveInventory = new SagaStep.ReserveInventory(
    "reserve-inventory",
    orderId -> {
        inventoryService.reserve(orderId, items);
        return "RESERVED";
    }
);

SagaStep.ChargePayment chargePayment = new SagaStep.ChargePayment(
    "charge-payment",
    orderId -> {
        paymentService.charge(orderId, amount);
        return "CHARGED";
    }
);

SagaStep.ShipOrder shipOrder = new SagaStep.ShipOrder(
    "ship-order",
    orderId -> {
        shippingService.ship(orderId);
        return "SHIPPED";
    }
);

// Define compensation steps (rollback operations)
SagaStep.ReleaseInventory releaseInventory = new SagaStep.ReleaseInventory(
    "release-inventory",
    orderId -> {
        inventoryService.release(orderId, items);
    }
);

SagaStep.RefundPayment refundPayment = new SagaStep.RefundPayment(
    "refund-payment",
    orderId -> {
        paymentService.refund(orderId, amount);
    }
);
```

### Step 2: Configure the Saga

```java
// Build saga with actions and compensations
List<SagaStep> steps = List.of(
    reserveInventory.compensatedBy(releaseInventory),
    chargePayment.compensatedBy(refundPayment),
    shipOrder  // No compensation needed (last step)
);

SagaConfig config = new SagaConfig(
    "order-fulfillment",
    steps
);
```

### Step 3: Create Saga Coordinator

```java
DistributedSagaCoordinator coordinator =
    DistributedSagaCoordinator.create(config);
```

### Step 4: Execute Saga

```java
// Execute saga asynchronously
CompletableFuture<SagaResult> future = coordinator.execute();

// Handle result
future.thenAccept(result -> {
    switch (result.status()) {
        case COMPLETED -> {
            System.out.println("Order completed successfully");
            System.out.println("Outputs: " + result.outputs());
        }
        case COMPENSATED -> {
            System.err.println("Order failed, rolled back: " + result.errorMessage());
            notifyCustomer(orderId, "Order cancelled due to payment failure");
        }
        case FAILED -> {
            System.err.println("Order failed: " + result.errorMessage());
        }
    }
});
```

### Step 5: Monitor Saga Execution

```java
// Add listener for saga events
coordinator.addListener(new DistributedSagaCoordinator.SagaListener() {
    @Override
    public void onSagaStarted(String sagaId, int stepCount) {
        logger.info("Saga started: {} with {} steps", sagaId, stepCount);
    }

    @Override
    public void onStepExecuted(String sagaId, String stepName, Object output) {
        logger.info("Step executed: {} -> {}", stepName, output);
    }

    @Override
    public void onCompensationStarted(String sagaId, int fromStep) {
        logger.warn("Compensation started from step {}", fromStep);
    }

    @Override
    public void onCompensationCompleted(String sagaId) {
        logger.warn("Compensation completed for saga {}", sagaId);
    }

    @Override
    public void onSagaCompleted(String sagaId, long durationMs) {
        logger.info("Saga completed in {} ms", durationMs);
    }

    @Override
    public void onSagaAborted(String sagaId, String reason) {
        logger.error("Saga aborted: {}", reason);
    }
});
```

### Step 6: Query Saga Status

```java
// Get current status
SagaTransaction transaction = coordinator.getStatus(sagaId);
System.out.println("Status: " + transaction);

// Get event log
List<SagaEvent> events = coordinator.getSagaLog(sagaId);
events.forEach(event -> {
    System.out.println("Event: " + event);
});
```

### Step 7: Manual Abort (Optional)

```java
// Abort saga in progress
coordinator.abort(sagaId, "Customer requested cancellation");
```

## Complete Example

```java
public class OrderFulfillmentService {
    private final DistributedSagaCoordinator sagaCoordinator;
    private final InventoryService inventory;
    private final PaymentService payment;
    private final ShippingService shipping;

    public OrderFulfillmentService(
        InventoryService inventory,
        PaymentService payment,
        ShippingService shipping
    ) {
        this.inventory = inventory;
        this.payment = payment;
        this.shipping = shipping;

        // Build saga
        SagaStep reserve = new SagaStep.Action<>(
            "reserve-inventory",
            ctx -> inventory.reserve(ctx.orderId(), ctx.items())
        ).compensatedBy(new SagaStep.Compensation<>(
            "release-inventory",
            ctx -> inventory.release(ctx.orderId(), ctx.items())
        ));

        SagaStep charge = new SagaStep.Action<>(
            "charge-payment",
            ctx -> payment.charge(ctx.orderId(), ctx.amount())
        ).compensatedBy(new SagaStep.Compensation<>(
            "refund-payment",
            ctx -> payment.refund(ctx.orderId(), ctx.amount())
        ));

        SagaStep ship = new SagaStep.Action<>(
            "ship-order",
            ctx -> shipping.ship(ctx.orderId())
        );

        List<SagaStep> steps = List.of(reserve, charge, ship);
        SagaConfig config = new SagaConfig("order-fulfillment", steps);

        this.sagaCoordinator = DistributedSagaCoordinator.create(config);

        // Add monitoring
        sagaCoordinator.addListener(new SagaMonitoringListener());
    }

    public CompletableFuture<String> processOrder(Order order) {
        return sagaCoordinator.execute()
            .thenApply(result -> {
                if (result.status() == SagaResult.Status.COMPLETED) {
                    return "Order completed: " + order.id();
                } else {
                    throw new OrderFailedException(result.errorMessage());
                }
            });
    }

    public void shutdown() {
        sagaCoordinator.shutdown();
    }
}
```

## Configuration Guidelines

### Step Ordering

```java
// GOOD: Dependent steps in order
List.of(
    reserveInventory,  // Must be first
    chargePayment,     // Depends on inventory
    shipOrder          // Depends on payment
);

// BAD: Dependencies out of order
List.of(
    shipOrder,         // Can't ship before payment
    chargePayment,
    reserveInventory
);
```

### Compensation Design

```java
// GOOD: Idempotent compensation
SagaStep.Compensation<Context> refund = new SagaStep.Compensation<>(
    "refund-payment",
    ctx -> {
        // Check if already refunded
        if (payment.isRefunded(ctx.orderId())) {
            return; // Idempotent
        }
        payment.refund(ctx.orderId(), ctx.amount());
    }
);

// BAD: Non-idempotent (will crash on retry)
SagaStep.Compensation<Context> badRefund = new SagaStep.Compensation<>(
    "refund-payment",
    ctx -> payment.refund(ctx.orderId(), ctx.amount())  // May duplicate
);
```

### Step Context

```java
// Pass context between steps
record OrderContext(
    String orderId,
    List<OrderItem> items,
    BigDecimal amount
) {}

// Use context in steps
new SagaStep.Action<OrderContext, String>(
    "charge-payment",
    ctx -> payment.charge(ctx.orderId(), ctx.amount())
);
```

## Performance Considerations

### Memory
- **Per-saga overhead**: ~5 KB (state + event log)
- **Event log size**: O(stepCount) × event size
- **Scaling**: 10,000 concurrent sagas ≈ 50 MB heap

### Latency
- **Sequential execution**: Sum of all step latencies
- **Compensation cost**: O(failedStepIndex) compensations
- **Optimization**: Parallel independent steps (future enhancement)

### Throughput
- **No blocking**: All operations are async
- **High concurrency**: Millions of sagas/hour
- **Limitation**: I/O latency of downstream services

## Monitoring

### Key Metrics

```java
record SagaMetrics(
    String sagaName,
    String status,              // PENDING/IN_PROGRESS/COMPLETED/FAILED/COMPENSATED
    int totalSteps,             // Total steps in saga
    int completedSteps,          // Steps executed
    int failedStep,             // Step that failed (-1 if none)
    Duration duration,           // Total execution time
    Duration compensationTime,   // Compensation duration
    List<SagaEvent> eventLog    // Full execution log
) {}
```

### Alerting

```java
// Alert on compensation
if (status == Status.COMPENSATED) {
    alertService.send(AlertPriority.HIGH,
        "Saga compensated: " + sagaName + " at step " + failedStep);
}

// Alert on repeated failures
if (failureRate > 10% for sagaName) {
    alertService.send(AlertPriority.CRITICAL,
        "High saga failure rate: " + sagaName);
}

// Alert on slow sagas
if (duration > SLA.threshold) {
    alertService.send(AlertPriority.MEDIUM,
        "Slow saga: " + sagaName + " took " + duration);
}
```

## Common Pitfalls

### 1. Missing Compensation
```java
// BAD: No compensation for reservable resource
new SagaStep.Action<>("reserve", ctx -> inventory.reserve(ctx.id()))

// GOOD: Always compensate reserved resources
new SagaStep.Action<>("reserve", ctx -> inventory.reserve(ctx.id()))
    .compensatedBy(new SagaStep.Compensation<>("release", ctx -> inventory.release(ctx.id())))
```

### 2. Non-Idempotent Steps
```java
// BAD: Will charge twice on retry
ctx -> payment.charge(ctx.orderId(), ctx.amount())

// GOOD: Idempotent charge
ctx -> {
    if (payment.isCharged(ctx.orderId())) {
        return payment.getCharge(ctx.orderId());
    }
    return payment.charge(ctx.orderId(), ctx.amount());
}
```

### 3. Ignoring Saga Results
```java
// BAD: Fire and forget
coordinator.execute();

// GOOD: Handle result
coordinator.execute().thenAccept(result -> {
    if (result.status() == SagaResult.Status.COMPENSATED) {
        notifyCustomer("Order cancelled");
    }
});
```

## Advanced Patterns

### Parallel Steps

```java
// Execute independent steps in parallel (custom implementation)
CompletableFuture<SagaResult> executeParallel() {
    List<CompletableFuture<Object>> futures = List.of(
        CompletableFuture.supplyAsync(() -> step1.execute()),
        CompletableFuture.supplyAsync(() -> step2.execute()),
        CompletableFuture.supplyAsync(() -> step3.execute())
    );

    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> aggregateResults(futures));
}
```

### Saga Chaining

```java
// Chain multiple sagas
public CompletableFuture<SagaResult> processComplexOrder(Order order) {
    return executeSaga(reserveInventory)
        .thenCompose(r1 -> executeSaga(chargePayment))
        .thenCompose(r2 -> executeSaga(shipOrder))
        .thenCompose(r3 -> executeSaga(sendConfirmation));
}
```

### Timeout Handling

```java
// Add timeout to saga execution
CompletableFuture<SagaResult> future = coordinator.execute();
try {
    SagaResult result = future.get(30, TimeUnit.SECONDS);
    return result;
} catch (TimeoutException e) {
    coordinator.abort(sagaId, "Timeout");
    return SagaResult.timeout();
}
```

## Related Guides

- **[Circuit Breaker](./circuit-breaker.md)** - Protect saga steps from failures
- **[Event Sourcing](./event-sourcing.md)** - Persist saga events
- **[CQRS](./cqrs.md)** - Separate command and query models

## References

- **DistributedSagaCoordinator**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/saga/DistributedSagaCoordinator.java`
- **SagaConfig**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/saga/SagaConfig.java`
- **Test**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/saga/DistributedSagaCoordinatorTest.java`
