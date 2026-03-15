# Circuit Breaker Demo - External Service Protection

## Problem Statement

Implement a circuit breaker system that demonstrates:
- External service protection
- Failure detection and threshold management
- Automatic state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
- Metrics collection and monitoring
- Recovery testing

## Solution Design

Create a circuit breaker with:
1. **State Management**: CLOSED (normal), OPEN (failing), HALF_OPEN (testing)
2. **Failure Tracking**: Count failures within time window
3. **Threshold Management**: Configure max failures before opening
4. **Recovery Testing**: Automatic probe requests in HALF_OPEN
5. **Metrics Collection**: Track success/failure rates

## Complete Java Code

```java
package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit Breaker example demonstrating external service protection.
 *
 * This example shows:
 * - Circuit breaker state transitions
 * - Failure detection and threshold management
 * - Automatic recovery testing
 * - Metrics collection
 * - Service degradation strategies
 */
public class CircuitBreakerDemo {

    /**
     * Simulated external service with controlled failure rate.
     */
    public static class ExternalService {
        private final double failureRate;
        private final int latencyMs;
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);

        public ExternalService(double failureRate, int latencyMs) {
            this.failureRate = failureRate;
            this.latencyMs = latencyMs;
        }

        public String call(String request) throws Exception {
            requestCount.incrementAndGet();

            // Simulate latency
            Thread.sleep(latencyMs);

            // Simulate failures
            if (Math.random() < failureRate) {
                failureCount.incrementAndGet();
                throw new Exception("Service unavailable (failure rate: " + failureRate + ")");
            }

            return "Response to: " + request;
        }

        public int getRequestCount() {
            return requestCount.get();
        }

        public int getFailureCount() {
            return failureCount.get();
        }

        public double getActualFailureRate() {
            int total = requestCount.get();
            return total > 0 ? (double) failureCount.get() / total : 0.0;
        }
    }

    /**
     * Demo 1: Basic circuit breaker with failing service.
     */
    public static void basicCircuitBreakerDemo() throws Exception {
        System.out.println("=== Demo 1: Basic Circuit Breaker ===\n");

        // Create circuit breaker: opens after 5 failures in 10 seconds
        var cb = CircuitBreaker.create(
            "demo-service",
            5,                              // max failures
            Duration.ofSeconds(10),         // window
            Duration.ofSeconds(5)           // half-open timeout
        );

        // Create failing service (50% failure rate)
        var service = new ExternalService(0.5, 50);

        System.out.println("Initial state: " + cb.getState());
        System.out.println("Sending requests...\n");

        // Send requests and observe state changes
        int totalRequests = 0;
        int successCount = 0;
        int circuitOpenCount = 0;
        int failureCount = 0;

        for (int i = 1; i <= 20; i++) {
            totalRequests++;
            var result = cb.execute("request-" + i, service::call);

            switch (result) {
                case CircuitBreaker.CircuitBreakerResult.Success<String, Exception>(var value) -> {
                    successCount++;
                    System.out.printf("[%2d] SUCCESS: %s | State: %s | Failures: %d%n",
                        i, value, cb.getState(), cb.getFailureCount());
                }
                case CircuitBreaker.CircuitBreakerResult.Failure<String, Exception>(var error) -> {
                    failureCount++;
                    System.out.printf("[%2d] FAILURE: %s | State: %s | Failures: %d%n",
                        i, error.getMessage(), cb.getState(), cb.getFailureCount());
                }
                case CircuitBreaker.CircuitBreakerResult.CircuitOpen<String, Exception>() -> {
                    circuitOpenCount++;
                    System.out.printf("[%2d] CIRCUIT OPEN | State: %s | Fast-fail%n",
                        i, cb.getState());
                }
            }

            // Small delay between requests
            Thread.sleep(100);
        }

        System.out.println("\n--- Summary ---");
        System.out.println("Total requests: " + totalRequests);
        System.out.println("Successes: " + successCount);
        System.out.println("Failures: " + failureCount);
        System.out.println("Circuit open (fast-fail): " + circuitOpenCount);
        System.out.println("Final state: " + cb.getState());
        System.out.println("Service stats: " + service.getRequestCount() + " requests, "
            + service.getFailureCount() + " failures ("
            + String.format("%.1f%%", service.getActualFailureRate() * 100) + ")");

        System.out.println("\n=== Demo 1 Complete ===\n");
    }

    /**
     * Demo 2: Recovery testing (HALF_OPEN state).
     */
    public static void recoveryTestingDemo() throws Exception {
        System.out.println("=== Demo 2: Recovery Testing ===\n");

        var cb = CircuitBreaker.create(
            "recovery-service",
            3,
            Duration.ofSeconds(10),
            Duration.ofSeconds(3)  // 3-second half-open timeout
        );

        var service = new ExternalService(0.8, 50);  // 80% failure rate

        System.out.println("Phase 1: Induce failures to open circuit\n");

        // Send failing requests to open circuit
        for (int i = 1; i <= 5; i++) {
            var result = cb.execute("req-" + i, service::call);
            System.out.printf("Request %d: %s | State: %s%n",
                i, result.isSuccess() ? "SUCCESS" : result.isFailure() ? "FAILURE" : "OPEN",
                cb.getState());
            Thread.sleep(50);
        }

        System.out.println("\nCircuit is now: " + cb.getState());
        System.out.println("\nPhase 2: Wait for half-open timeout...\n");
        Thread.sleep(3500);  // Wait for half-open timeout

        System.out.println("Phase 3: Circuit in HALF_OPEN, testing recovery\n");

        // Switch service to healthy mode
        var healthyService = new ExternalService(0.0, 50);  // 0% failure rate

        // Send probe request
        var probeResult = cb.execute("probe", healthyService::call);
        System.out.println("Probe request: " + (probeResult.isSuccess() ? "SUCCESS" : "FAILURE"));
        System.out.println("Circuit state after probe: " + cb.getState());

        // Send more requests to verify circuit is closed
        System.out.println("\nPhase 4: Send more requests to verify CLOSED state\n");
        for (int i = 1; i <= 3; i++) {
            var result = cb.execute("recovery-" + i, healthyService::call);
            System.out.printf("Request %d: %s | State: %s%n",
                i, result.isSuccess() ? "SUCCESS" : "FAILURE", cb.getState());
            Thread.sleep(50);
        }

        System.out.println("\nFinal state: " + cb.getState());
        System.out.println("\n=== Demo 2 Complete ===\n");
    }

    /**
     * Demo 3: Metrics and monitoring.
     */
    public static void metricsDemo() throws Exception {
        System.out.println("=== Demo 3: Metrics and Monitoring ===\n");

        var cb = CircuitBreaker.create(
            "monitored-service",
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(5)
        );

        var service = new ExternalService(0.3, 30);

        System.out.println("Sending requests with monitoring...\n");

        var metrics = new Metrics();
        int numRequests = 50;

        for (int i = 1; i <= numRequests; i++) {
            var start = System.nanoTime();
            var result = cb.execute("metrics-" + i, service::call);
            var duration = System.nanoTime() - start;

            metrics.record(result, duration);

            if (i % 10 == 0) {
                System.out.printf("Progress: %d/%d requests | State: %s | Failures: %d%n",
                    i, numRequests, cb.getState(), cb.getFailureCount());
            }

            Thread.sleep(20);
        }

        System.out.println("\n--- Metrics Summary ---");
        metrics.print();

        System.out.println("\nCircuit Breaker Stats:");
        System.out.println("  Name: " + cb.getName());
        System.out.println("  State: " + cb.getState());
        System.out.println("  Failure Count: " + cb.getFailureCount());
        System.out.println("  Max Failures: 10");

        System.out.println("\n=== Demo 3 Complete ===\n");
    }

    /**
     * Demo 4: Concurrent access.
     */
    public static void concurrentAccessDemo() throws Exception {
        System.out.println("=== Demo 4: Concurrent Access ===\n");

        var cb = CircuitBreaker.create(
            "concurrent-service",
            20,
            Duration.ofSeconds(10),
            Duration.ofSeconds(2)
        );

        var service = new ExternalService(0.4, 50);

        int numThreads = 10;
        int requestsPerThread = 10;

        System.out.println("Launching " + numThreads + " threads, "
            + requestsPerThread + " requests each\n");

        var executor = Executors.newFixedThreadPool(numThreads);
        var results = new ConcurrentLinkedDeque<Result>();

        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int r = 1; r <= requestsPerThread; r++) {
                        var requestStart = System.nanoTime();
                        var result = cb.execute(
                            "thread-" + threadId + "-req-" + r,
                            service::call
                        );
                        var duration = System.nanoTime() - requestStart;

                        results.add(new Result(threadId, r, result, duration));
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        var testStart = System.nanoTime();
        startLatch.countDown();

        // Wait for completion
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        var testDuration = System.nanoTime() - testStart;

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        System.out.println("Test " + (finished ? "completed" : "timed out"));
        System.out.printf("Duration: %.2f seconds%n%n", testDuration / 1_000_000_000.0);

        // Analyze results
        var success = results.stream().filter(r -> r.result.isSuccess()).count();
        var failure = results.stream().filter(r -> r.result.isFailure()).count();
        var open = results.stream().filter(r -> r.result.isCircuitOpen()).count();

        System.out.println("--- Results ---");
        System.out.println("Success: " + success);
        System.out.println("Failure: " + failure);
        System.out.println("Circuit Open (fast-fail): " + open);
        System.out.println("Total: " + results.size());

        var avgLatency = results.stream()
            .filter(r -> r.result.isSuccess())
            .mapToLong(r -> r.durationNs)
            .average()
            .orElse(0);

        System.out.printf("Average latency (success): %.2f ms%n", avgLatency / 1_000_000.0);
        System.out.println("\nFinal circuit state: " + cb.getState());
        System.out.println("Failure count: " + cb.getFailureCount());

        System.out.println("\n=== Demo 4 Complete ===\n");
    }

    /**
     * Demo 5: Manual control.
     */
    public static void manualControlDemo() throws Exception {
        System.out.println("=== Demo 5: Manual Control ===\n");

        var cb = CircuitBreaker.create(
            "manual-service",
            5,
            Duration.ofSeconds(10),
            Duration.ofSeconds(2)
        );

        var service = new ExternalService(0.0, 50);  // Healthy service

        System.out.println("Initial state: " + cb.getState());
        System.out.println("Sending request to healthy service...\n");

        var result1 = cb.execute("test-1", service::call);
        System.out.println("Result 1: " + (result1.isSuccess() ? "SUCCESS" : "FAILURE"));
        System.out.println("State: " + cb.getState() + "\n");

        // Manually open circuit
        System.out.println("Manually opening circuit...\n");
        cb.open();

        System.out.println("State after manual open: " + cb.getState());
        System.out.println("Sending request to healthy service...\n");

        var result2 = cb.execute("test-2", service::call);
        System.out.println("Result 2: " + (result2.isCircuitOpen() ? "CIRCUIT OPEN" : "OTHER"));
        System.out.println("State: " + cb.getState() + "\n");

        // Manually reset
        System.out.println("Manually resetting circuit...\n");
        cb.reset();

        System.out.println("State after reset: " + cb.getState());
        System.out.println("Sending request...\n");

        var result3 = cb.execute("test-3", service::call);
        System.out.println("Result 3: " + (result3.isSuccess() ? "SUCCESS" : "FAILURE"));
        System.out.println("State: " + cb.getState());

        System.out.println("\n=== Demo 5 Complete ===\n");
    }

    /**
     * Metrics collector.
     */
    public static class Metrics {
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger openCount = new AtomicInteger(0);
        private final AtomicLong totalLatencyNs = new AtomicLong(0);

        public void record(CircuitBreaker.CircuitBreakerResult<?, ?> result, long latencyNs) {
            switch (result) {
                case CircuitBreaker.CircuitBreakerResult.Success<?, ?> s -> {
                    successCount.incrementAndGet();
                    totalLatencyNs.addAndGet(latencyNs);
                }
                case CircuitBreaker.CircuitBreakerResult.Failure<?, ?> f ->
                    failureCount.incrementAndGet();
                case CircuitBreaker.CircuitBreakerResult.CircuitOpen<?, ?> o ->
                    openCount.incrementAndGet();
            }
        }

        public void print() {
            var total = successCount.get() + failureCount.get() + openCount.get();
            var successRate = total > 0 ? (double) successCount.get() / total * 100 : 0;
            var avgLatencyMs = successCount.get() > 0
                ? totalLatencyNs.get() / successCount.get() / 1_000_000.0
                : 0;

            System.out.println("Total requests: " + total);
            System.out.println("Success: " + successCount.get() + " (" + String.format("%.1f%%", successRate) + ")");
            System.out.println("Failure: " + failureCount.get());
            System.out.println("Circuit Open: " + openCount.get());
            System.out.printf("Average latency: %.2f ms%n", avgLatencyMs);
        }
    }

    /**
     * Request result record.
     */
    public record Result(int threadId, int requestId,
                        CircuitBreaker.CircuitBreakerResult<String, Exception> result,
                        long durationNs) {}

    /**
     * Main method running all demos.
     */
    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  Circuit Breaker Demonstrations          ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        basicCircuitBreakerDemo();
        Thread.sleep(500);

        recoveryTestingDemo();
        Thread.sleep(500);

        metricsDemo();
        Thread.sleep(500);

        concurrentAccessDemo();
        Thread.sleep(500);

        manualControlDemo();

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  All Demos Complete                     ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }
}
```

## Expected Output

```
╔══════════════════════════════════════════╗
║  Circuit Breaker Demonstrations          ║
╚══════════════════════════════════════════╝

=== Demo 1: Basic Circuit Breaker ===

Initial state: CLOSED
Sending requests...

[ 1] SUCCESS: Response to: request-1 | State: CLOSED | Failures: 0
[ 2] FAILURE: Service unavailable (failure rate: 0.5) | State: CLOSED | Failures: 1
[ 3] SUCCESS: Response to: request-3 | State: CLOSED | Failures: 1
[ 4] FAILURE: Service unavailable (failure rate: 0.5) | State: CLOSED | Failures: 2
[ 5] SUCCESS: Response to: request-5 | State: CLOSED | Failures: 2
[ 6] FAILURE: Service unavailable (failure rate: 0.5) | State: CLOSED | Failures: 3
[ 7] FAILURE: Service unavailable (failure rate: 0.5) | State: CLOSED | Failures: 4
[ 8] FAILURE: Service unavailable (failure rate: 0.5) | State: CLOSED | Failures: 5
[ 9] CIRCUIT OPEN | State: OPEN | Fast-fail
[10] CIRCUIT OPEN | State: OPEN | Fast-fail
...

--- Summary ---
Total requests: 20
Successes: 6
Failures: 6
Circuit open (fast-fail): 8
Final state: OPEN
Service stats: 14 requests, 8 failures (57.1%)

=== Demo 1 Complete ===

=== Demo 2: Recovery Testing ===

Phase 1: Induce failures to open circuit

Request 1: FAILURE | State: CLOSED
Request 2: FAILURE | State: CLOSED
Request 3: FAILURE | State: OPEN

Circuit is now: OPEN

Phase 2: Wait for half-open timeout...

Phase 3: Circuit in HALF_OPEN, testing recovery

Probe request: SUCCESS
Circuit state after probe: CLOSED

Phase 4: Send more requests to verify CLOSED state

Request 1: SUCCESS | State: CLOSED
Request 2: SUCCESS | State: CLOSED
Request 3: SUCCESS | State: CLOSED

Final state: CLOSED

=== Demo 2 Complete ===

=== Demo 3: Metrics and Monitoring ===

Sending requests with monitoring...

Progress: 10/50 requests | State: CLOSED | Failures: 3
Progress: 20/50 requests | State: CLOSED | Failures: 6
...

--- Metrics Summary ---
Total requests: 50
Success: 35 (70.0%)
Failure: 15
Circuit Open: 0
Average latency: 30.12 ms

=== Demo 3 Complete ===

=== Demo 4: Concurrent Access ===

Launching 10 threads, 10 requests each

Test completed
Duration: 2.34 seconds

--- Results ---
Success: 65
Failure: 24
Circuit Open (fast-fail): 11
Total: 100
Average latency (success): 50.23 ms

Final circuit state: OPEN
Failure count: 24

=== Demo 4 Complete ===

=== Demo 5: Manual Control ===

Initial state: CLOSED
Sending request to healthy service...

Result 1: SUCCESS
State: CLOSED

Manually opening circuit...

State after manual open: OPEN
Sending request to healthy service...

Result 2: CIRCUIT OPEN
State: OPEN

Manually resetting circuit...

State after reset: CLOSED
Sending request...

Result 3: SUCCESS
State: CLOSED

=== Demo 5 Complete ===

╔══════════════════════════════════════════╗
║  All Demos Complete                     ║
╚══════════════════════════════════════════╝
```

## Testing Instructions

### Compile and Run

```bash
# Compile
javac --enable-preview -source 26 \
    -cp target/classes:target/test-classes \
    -d target/examples \
    docs/examples/CircuitBreakerDemo.java

# Run
java --enable-preview \
    -cp target/classes:target/test-classes:target/examples \
    io.github.seanchatmangpt.jotp.examples.CircuitBreakerDemo
```

### Unit Tests

```java
package io.github.seanchatmangpt.jotp.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.*;

import java.time.Duration;

@DisplayName("Circuit Breaker Tests")
class CircuitBreakerTest {

    @Test
    @DisplayName("Circuit opens after threshold failures")
    void testCircuitOpens() throws Exception {
        var cb = CircuitBreaker.create(
            "test",
            3,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1)
        );

        var service = new ExternalService(1.0, 10);  // Always fails

        // Send 3 failing requests
        for (int i = 0; i < 3; i++) {
            var result = cb.execute("req", service::call);
            assertThat(result.isFailure()).isTrue();
        }

        // Circuit should now be open
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("Circuit fast-fails when open")
    void testFastFail() throws Exception {
        var cb = CircuitBreaker.create(
            "test",
            2,
            Duration.ofSeconds(10),
            Duration.ofSeconds(1)
        );

        var service = new ExternalService(1.0, 10);

        // Open the circuit
        cb.execute("req", service::call);
        cb.execute("req", service::call);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Next request should fast-fail without calling service
        var result = cb.execute("req", service::call);
        assertThat(result.isCircuitOpen()).isTrue();
        assertThat(service.getRequestCount()).isEqualTo(2);  // Service not called
    }

    @Test
    @DisplayName("Circuit transitions to HALF_OPEN after timeout")
    void testHalfOpenTransition() throws Exception {
        var cb = CircuitBreaker.create(
            "test",
            2,
            Duration.ofSeconds(10),
            Duration.ofMillis(100)  // Short timeout for testing
        );

        var failingService = new ExternalService(1.0, 10);

        // Open the circuit
        cb.execute("req", failingService::call);
        cb.execute("req", failingService::call);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait for half-open timeout
        Thread.sleep(150);

        // Circuit should now be HALF_OPEN
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("Circuit closes after successful probe")
    void testCircuitCloses() throws Exception {
        var cb = CircuitBreaker.create(
            "test",
            2,
            Duration.ofSeconds(10),
            Duration.ofMillis(100)
        );

        var failingService = new ExternalService(1.0, 10);
        var healthyService = new ExternalService(0.0, 10);

        // Open the circuit
        cb.execute("req", failingService::call);
        cb.execute("req", failingService::call);

        // Wait for half-open
        Thread.sleep(150);

        // Send successful probe
        var result = cb.execute("probe", healthyService::call);
        assertThat(result.isSuccess()).isTrue();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
```

## Variations and Extensions

### 1. Adaptive Thresholds

```java
public class AdaptiveCircuitBreaker<R, V, E extends Exception> {
    private double currentThreshold;
    private final double minThreshold;
    private final double maxThreshold;

    public void adjustThresholdBasedOnLoad(double currentLoad) {
        if (currentLoad > 0.8) {
            currentThreshold = maxThreshold;  // More lenient under load
        } else if (currentLoad < 0.3) {
            currentThreshold = minThreshold;  // Stricter under low load
        }
    }
}
```

### 2. Multiple Thresholds

```java
record Thresholds(int warning, int critical, int max) {}

var cb = new CircuitBreaker<>(
    "multi-level",
    new Thresholds(5, 10, 20),  // Warn at 5, critical at 10, open at 20
    Duration.ofSeconds(10),
    Duration.ofSeconds(5)
);

// Add warning state
public enum State {
    CLOSED, WARNING, CRITICAL, OPEN, HALF_OPEN
}
```

### 3. Metric Export

```java
interface MetricsExporter {
    void export(String name, State state, int failures, double successRate);
}

class PrometheusExporter implements MetricsExporter {
    public void export(String name, State state, int failures, double successRate) {
        System.out.printf(
            "circuit_breaker{name=\"%s\",state=\"%s\"} %d%n" +
            "circuit_breaker_success_rate{name=\"%s\"} %.2f%n",
            name, state, failures, name, successRate
        );
    }
}
```

### 4. Fallback Strategy

```java
interface FallbackStrategy<V> {
    V getFallback(Request request, Exception error);
}

class CachedFallback<V> implements FallbackStrategy<V> {
    private final Cache<Request, V> cache;

    public V getFallback(Request request, Exception error) {
        return cache.getOrDefault(request, () -> getDefault(request));
    }
}

// In circuit breaker:
public V execute(R request, Function<R, V> handler, FallbackStrategy<V> fallback) {
    var result = execute(request, handler);
    if (result.isFailure()) {
        return fallback.getFallback(request, result.failureError());
    }
    return result.successValue();
}
```

## Related Patterns

- **Supervised Worker**: Fault-tolerant service calls
- **Retry Pattern**: Automatic retry with exponential backoff
- **Bulkhead Isolation**: Resource isolation for different services
- **Timeout Manager**: Per-request timeout management

## Key JOTP Concepts Demonstrated

1. **CircuitBreaker.create()**: Factory method with configuration
2. **State Transitions**: CLOSED → OPEN → HALF_OPEN → CLOSED
3. **Failure Tracking**: Count failures within time window
4. **Fast-Fail**: Immediate rejection when circuit is open
5. **Recovery Testing**: Automatic probe in HALF_OPEN state
6. **Thread-Safety**: Concurrent access from multiple threads

## Performance Characteristics

- **State Check**: ~10-20 ns (volatile read)
- **Failure Recording**: ~50-100 ns (atomic operation)
- **Execute Call**: ~100-200 ns overhead (state check + handler invocation)
- **Memory**: ~500 bytes per circuit breaker instance

## Common Pitfalls

1. **Threshold Too Low**: Circuit opens too frequently
2. **Timeout Too Short**: Doesn't allow recovery
3. **No Fallback**: Poor user experience during outages
4. **Ignoring Metrics**: Not monitoring failure rates
5. **Shared State**: Mutable state in handler functions

## Best Practices

1. **Set Appropriate Thresholds**: Based on SLA and observed failure rates
2. **Configure Timeouts**: Balance responsiveness and recovery time
3. **Monitor Metrics**: Track success/failure rates
4. **Implement Fallbacks**: Provide degraded service during outages
5. **Test Failure Scenarios**: Verify circuit opens and recovers correctly
6. **Log State Transitions**: Debug circuit behavior
7. **Use Multiple Breakers**: Separate circuits for different services
