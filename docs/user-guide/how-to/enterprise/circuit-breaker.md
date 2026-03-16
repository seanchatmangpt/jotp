# Circuit Breaker Pattern

## Problem

Your microservices architecture experiences cascading failures when a downstream service becomes unresponsive. Slow or failing services cause thread pool exhaustion, increased latency, and eventually system-wide outages.

**Symptoms:**
- Thread pools exhausted waiting on slow services
- Cascading timeouts across service boundaries
- Increased error rates during partial outages
- Resource exhaustion from retry storms

## Solution

Implement the Circuit Breaker pattern to fail fast when a service exceeds failure thresholds. The circuit breaker automatically trips after detecting failures, preventing requests from reaching the unhealthy service and allowing it time to recover.

JOTP's circuit breaker uses Supervisor restart intensity to automatically trip the circuit:

- **CLOSED**: Requests pass through normally
- **OPEN**: Circuit tripped, requests fail-fast immediately
- **HALF_OPEN**: Testing if service has recovered (allowing limited requests)

## Prerequisites

- Java 26 with preview features enabled
- JOTP core module: `io.github.seanchatmangpt.jotp`
- Understanding of Supervisor pattern

## Implementation

### Step 1: Configure the Circuit Breaker

```java
import io.github.seanchatmangpt.jotp.enterprise.circuitbreaker.*;
import java.time.Duration;

// Create configuration with sensible defaults
CircuitBreakerConfig config = CircuitBreakerConfig.of("payment-gateway");

// Or customize for your needs
CircuitBreakerConfig customConfig = new CircuitBreakerConfig(
    "payment-gateway",        // Service name
    3,                        // Max restarts (failures before trip)
    Duration.ofSeconds(60),   // Time window for counting failures
    Duration.ofSeconds(10),   // How long to stay OPEN before HALF_OPEN
    3                         // Failure threshold to trip circuit
);
```

### Step 2: Create the Circuit Breaker

```java
CircuitBreakerPattern breaker = CircuitBreakerPattern.create(config);
```

### Step 3: Execute Requests Through the Circuit Breaker

```java
// Define your service call
CircuitBreakerTask<PaymentResponse> task = timeout -> {
    return paymentGateway.process(payment);
};

// Execute with timeout
Result<PaymentResponse> result = breaker.execute(task, Duration.ofSeconds(5));

// Handle result
switch (result) {
    case Result.Success<PaymentResponse>(var response) -> {
        // Request succeeded
        System.out.println("Payment processed: " + response);
    }
    case Result.Failure<PaymentResponse>(var error) -> {
        // Circuit open or request failed
        System.err.println("Circuit breaker error: " + error.getMessage());
    }
}
```

### Step 4: Monitor Circuit State

```java
// Get current state
CircuitBreakerPattern.CircuitState state = breaker.getState();

System.out.println("Circuit status: " + state.status());
System.out.println("Failure count: " + state.failureCount());
System.out.println("Last failure: " + state.lastFailureTime());
```

### Step 5: Handle State Transitions

```java
// Register listener for state changes
breaker.addListener((from, to) -> {
    System.out.println("Circuit transitioned: " + from + " -> " + to);

    // Take action when circuit opens
    if (to == CircuitBreakerPattern.CircuitState.Status.OPEN) {
        alertTeam("Payment gateway circuit OPEN");
        enableFallbackMechanism();
    }

    // Celebrate when circuit closes
    if (to == CircuitBreakerPattern.CircuitState.Status.CLOSED) {
        notifyTeam("Payment gateway recovered");
    }
});
```

### Step 6: Manual Reset (Optional)

```java
// Force reset to CLOSED state (use with caution)
breaker.reset();
```

## Complete Example

```java
public class PaymentService {
    private final CircuitBreakerPattern breaker;
    private final PaymentGateway gateway;

    public PaymentService(PaymentGateway gateway) {
        this.gateway = gateway;
        this.breaker = CircuitBreakerPattern.create(
            CircuitBreakerConfig.of("payment-gateway")
        );

        // Add monitoring
        breaker.addListener((from, to) -> {
            metrics.recordCircuitState("payment-gateway", to);
        });
    }

    public Result<PaymentResponse> processPayment(Payment payment) {
        return breaker.execute(
            timeout -> gateway.process(payment),
            Duration.ofSeconds(5)
        );
    }

    public void shutdown() {
        breaker.shutdown();
    }
}
```

## Configuration Guidelines

### Failure Threshold

```java
// Conservative: Trip after 5 failures
new CircuitBreakerConfig(service, 5, window, reset, 5);

// Aggressive: Trip after 2 failures
new CircuitBreakerConfig(service, 2, window, reset, 2);

// Balanced: Trip after 3 failures (default)
new CircuitBreakerConfig.of(service);
```

### Reset Timeout

```java
// Quick recovery: 5 seconds
Duration.ofSeconds(5)

// Standard: 10 seconds (default)
Duration.ofSeconds(10)

// Slow recovery: 60 seconds
Duration.ofMinutes(1)
```

### Time Window

```java
// Narrow window: 30 seconds
Duration.ofSeconds(30)

// Standard: 60 seconds (default)
Duration.ofSeconds(60)

// Wide window: 5 minutes
Duration.ofMinutes(5)
```

## Performance Considerations

### Memory
- **Per-circuit overhead**: ~1 KB (state tracking + listeners)
- **Failure window**: O(threshold) timestamps in memory
- **Scaling**: 1000 circuits ≈ 1 MB heap

### Latency
- **CLOSED state**: ~0 μs overhead (just counter increment)
- **OPEN state**: ~1 μs overhead (state check + fail-fast)
- **HALF_OPEN**: Same as CLOSED for test requests

### Throughput
- **No contention**: Circuit state is volatile read (no locks)
- **High concurrency**: Millions of requests/second per circuit
- **Scaling**: Linear with number of circuits

## Monitoring

### Key Metrics

```java
// Track these metrics:
record CircuitBreakerMetrics(
    String circuitName,
    String status,              // CLOSED/OPEN/HALF_OPEN
    long failureCount,          // Current failures
    long totalRequests,         // Total requests made
    long failedRequests,        // Total failed requests
    long tripCount,             // Times circuit opened
    Duration lastTripTime,      // Last trip timestamp
    Duration recoveryTime       // Time to recover from OPEN
) {}
```

### Alerting

```java
// Alert on circuit state changes
if (newStatus == Status.OPEN) {
    alertService.send(AlertPriority.HIGH,
        "Circuit breaker OPEN for " + circuitName);
}

// Alert on repeated trips
if (tripCount > 5 in lastHour) {
    alertService.send(AlertPriority.CRITICAL,
        "Circuit breaker flapping: " + circuitName);
}
```

## Common Pitfalls

### 1. Threshold Too High
```java
// BAD: Never trips
new CircuitBreakerConfig(service, 1000, window, reset, 1000);

// GOOD: Reasonable threshold
new CircuitBreakerConfig.of(service); // 3 failures
```

### 2. Reset Too Short
```java
// BAD: Constant flapping
Duration.ofSeconds(1) // Trip, recover, trip, recover...

// GOOD: Allow recovery time
Duration.ofSeconds(10) // Default
```

### 3. Forgetting Shutdown
```java
// BAD: Resource leak
CircuitBreakerPattern breaker = CircuitBreakerPattern.create(config);
// Never call shutdown()

// GOOD: Clean shutdown
try {
    // Use breaker
} finally {
    breaker.shutdown();
}
```

## Related Guides

- **[Bulkhead Isolation](./bulkhead-isolation.md)** - Isolate resource-intensive features
- **[Saga Transactions](./saga-transactions.md)** - Coordinate distributed transactions
- **[Rate Limiting](./rate-limiting.md)** - Protect services from overload
- **[Backpressure](../resilience/backpressure.md)** - Flow control for producers

## References

- **CircuitBreakerPattern**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/circuitbreaker/CircuitBreakerPattern.java`
- **CircuitBreakerConfig**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/enterprise/circuitbreaker/CircuitBreakerConfig.java`
- **Test**: `/Users/sac/jotp/src/test/java/io/github/seanchatmangpt/jotp/enterprise/circuitbreaker/`
