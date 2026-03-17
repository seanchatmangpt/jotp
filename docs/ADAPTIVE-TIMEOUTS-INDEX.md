# Adaptive Timeouts - Complete Index

## Quick Navigation

### Getting Started
1. **[Quick Start Guide](ADAPTIVE-TIMEOUTS-QUICKSTART.md)** - 5-minute setup
   - 60-second overview
   - Basic integration steps
   - Common patterns with code examples
   - Configuration reference
   - Troubleshooting guide

2. **[Full Documentation](ADAPTIVE-TIMEOUTS.md)** - Deep dive
   - Algorithm explanation
   - Architecture overview
   - Feature descriptions
   - Performance characteristics
   - Best practices

### Code Examples

#### Main Implementation
- **`src/main/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeouts.java`** (588 lines)
  - Core adaptive timeout system
  - Online histogram for percentile computation
  - Per-service timeout state management
  - Configuration and customization
  - Public API documentation

#### Practical Examples
- **`src/main/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeoutsExample.java`** (300 lines)
  - AdaptiveServiceClient pattern
  - PaymentProcessor multi-stage pipeline
  - TimeoutMonitor for alerting
  - PostgreSQL history store example
  - Load balancing and circuit breaker integration

### Tests

#### Unit Tests
- **`src/test/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeoutsTest.java`** (600 lines, 25 tests)
  - Initial timeout behavior
  - Response time recording and percentile computation
  - Timeout increase under load
  - Algorithm correctness verification
  - Storm prevention with exponential backoff
  - Network latency awareness
  - Hysteresis preventing oscillation
  - Jitter ratio indicating instability
  - Bounds enforcement
  - Per-service independence
  - Custom history store integration
  - Burst traffic handling
  - Stress test with 50 services

#### Integration Tests
- **`src/test/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeoutsIntegrationTest.java`** (421 lines, 10 tests)
  - Load balancing across replicas
  - Graceful degradation detection
  - Network partition recovery
  - Cascading failure prevention
  - Circuit breaker integration
  - Multi-service coordination
  - History store recording
  - Concurrent service clients (20 threads, 50 services)
  - Real-world payment processing pipeline
  - Stress testing under concurrent load

## Key Concepts

### Algorithm
```
timeout = p999 + (p999 - p50) * buffer_factor
```
- Covers 99.9% of normal responses (p999)
- Adds margin for jitter: (p999 - p50)
- Configurable safety factor: buffer_factor

### Core Features
1. **Statistical Analysis**: Online histogram with p50, p99, p999 computation
2. **Adaptive Algorithm**: Data-driven timeout calculation
3. **Storm Prevention**: Exponential backoff on consecutive timeouts
4. **Hysteresis**: Prevent oscillation with configurable threshold
5. **Smooth Transitions**: Gradual timeout drift (max 10% per update)
6. **Load Awareness**: Scale based on JVM memory pressure
7. **Network Awareness**: Add base RTT estimate
8. **History & Audit**: PostgreSQL integration for timeout decisions
9. **Observability**: Per-service metrics and health indicators
10. **Extensibility**: Pluggable history store, configurable parameters

## File Structure

```
jotp/
├── src/main/java/io/github/seanchatmangpt/jotp/ai/
│   ├── AdaptiveTimeouts.java              (main implementation)
│   └── AdaptiveTimeoutsExample.java       (practical examples)
│
├── src/test/java/io/github/seanchatmangpt/jotp/ai/
│   ├── AdaptiveTimeoutsTest.java          (unit tests - 25 tests)
│   └── AdaptiveTimeoutsIntegrationTest.java (integration tests - 10 tests)
│
└── docs/
    ├── ADAPTIVE-TIMEOUTS.md               (comprehensive guide)
    ├── ADAPTIVE-TIMEOUTS-QUICKSTART.md    (quick reference)
    └── ADAPTIVE-TIMEOUTS-INDEX.md         (this file)
```

## Usage Quick Reference

### Basic 3-Step Integration

```java
// Step 1: Create timeout manager
var timeouts = AdaptiveTimeouts.create();

// Step 2: Record response times
long start = System.nanoTime();
Result<Response, Exception> result = Result.of(() -> service.call(request));
long elapsed = (System.nanoTime() - start) / 1_000_000;
timeouts.recordResponse("my-service", elapsed, result.isSuccess());

// Step 3: Use adaptive timeout
Duration timeout = timeouts.getTimeout("my-service");
Future<Response> future = service.ask(request, timeout);
```

## Configuration Parameters

| Parameter | Default | Purpose | Range |
|-----------|---------|---------|-------|
| `minTimeoutMs` | 100 | Lower bound | 50-500 |
| `maxTimeoutMs` | 30000 | Upper bound | 1000-60000 |
| `bufferFactor` | 0.5 | Jitter safety margin | 0.3-1.0 |
| `smoothingWindowMs` | 60000 | Update frequency | 1000-300000 |
| `hysteresisThreshold` | 0.05 | Change tolerance | 0.01-0.20 |
| `cpuScaleFactor` | 1.5 | Load scale factor | 1.1-3.0 |
| `estimatedNetworkLatencyMs` | 5 | Base RTT | 1-100 |
| `historyStore` | empty | PostgreSQL store | Optional |
| `statisticsUpdateIntervalMs` | 5000 | Stat update interval | 1000-10000 |

## Testing Scenarios

### Covered By Unit Tests
- ✓ Initial behavior and reset
- ✓ Percentile computation accuracy
- ✓ Timeout adjustment under load
- ✓ Algorithm correctness
- ✓ Storm prevention
- ✓ Network latency awareness
- ✓ Hysteresis stability
- ✓ Jitter detection
- ✓ Bounds enforcement
- ✓ Multi-service isolation
- ✓ Failure tracking
- ✓ Smooth transitions
- ✓ History store integration

### Covered By Integration Tests
- ✓ Load balancing across replicas
- ✓ Service degradation detection
- ✓ Network partition recovery
- ✓ Cascading failure prevention
- ✓ Circuit breaker integration
- ✓ Multi-service pipelines
- ✓ Concurrent operation (20 threads, 50 services)
- ✓ Real-world payment processing
- ✓ Stress testing

## PostgreSQL Schema

For history store:

```sql
CREATE TABLE timeout_history (
  id BIGSERIAL PRIMARY KEY,
  service_name VARCHAR(255) NOT NULL,
  previous_timeout_ms BIGINT NOT NULL,
  new_timeout_ms BIGINT NOT NULL,
  p99_ms BIGINT NOT NULL,
  total_requests BIGINT NOT NULL,
  reason TEXT,
  recorded_at TIMESTAMP DEFAULT NOW(),
  INDEX (service_name, recorded_at DESC)
);
```

## Monitoring Metrics

```java
var stats = timeouts.getStats("service-name");

// Core metrics
stats.p50Ms()               // 50th percentile
stats.p99Ms()               // 99th percentile
stats.p999Ms()              // 99.9th percentile
stats.currentTimeoutMs()    // Current timeout in use
stats.recommendedTimeoutMs()// Calculated optimal timeout

// Counts
stats.totalRequests()       // Total requests recorded
stats.totalTimeouts()       // Total timeout failures

// Health indicators
stats.jitterRatio()         // (p999 - p50) / p50
stats.isUnderStress()       // > 1% timeout rate
stats.timeoutHealth()       // current / recommended ratio
```

## Alerting Examples

```java
// High timeout rate
if (stats.isUnderStress()) {
  alert("Service under stress: " + stats.totalTimeouts() + " timeouts");
}

// Service degradation (jitter)
if (stats.jitterRatio() > 2.0) {
  alert("High jitter detected: " + stats.jitterRatio());
}

// Timeout too tight
if (stats.timeoutHealth() < 0.7) {
  alert("Timeout too aggressive: " + stats.timeoutHealth());
}

// Timeout too loose
if (stats.timeoutHealth() > 1.5) {
  alert("Timeout too lenient: " + stats.timeoutHealth());
}
```

## Common Patterns

### Pattern 1: Service Wrapper
Wrap service calls with automatic timeout tracking

### Pattern 2: Multi-Stage Pipeline
Different timeouts for different pipeline stages (e.g., auth vs billing)

### Pattern 3: Load Balancing
Route to replica with best expected response time

### Pattern 4: Circuit Breaker
Open circuit when jitterRatio > threshold

### Pattern 5: Monitoring
Periodic checks for stress, degradation, and bounds violations

## Performance Characteristics

- **Space**: O(services) - ~2.5MB per service
- **Time**: O(1) - constant time operations
- **Throughput**: 1000s of responses/sec per thread
- **Latency**: <1ms for timeout lookup

## Erlang/OTP Inspiration

"In Erlang, supervisors know when to restart. We need systems that know when to timeout."

AdaptiveTimeouts brings OTP's principles to the JVM:
- Observe actual behavior (percentile distributions)
- Make principled decisions (p999 + margin)
- Adapt continuously (dynamic adjustment)
- Prevent cascades (exponential backoff)
- Be observable (full history and metrics)

## Real-World Use Cases

1. **Payment Processing**: Auth → Inventory → Billing pipeline
2. **Search Engine**: Query → Ranking → Formatting pipeline
3. **E-commerce**: Browse → Cart → Checkout flow
4. **Analytics**: Ingestion → Processing → Storage pipeline
5. **Real-time Systems**: Capture → Analyze → Respond pattern

## Next Steps

1. **Read**: [Quick Start Guide](ADAPTIVE-TIMEOUTS-QUICKSTART.md)
2. **Study**: [Full Documentation](ADAPTIVE-TIMEOUTS.md)
3. **Explore**: Example code in `AdaptiveTimeoutsExample.java`
4. **Test**: Review unit and integration tests
5. **Integrate**: Add to your JOTP services
6. **Monitor**: Set up alerts and dashboards
7. **Tune**: Adjust parameters based on workload

## Support & Reference

- Main implementation: `AdaptiveTimeouts.java`
- Examples: `AdaptiveTimeoutsExample.java`
- Tests: `AdaptiveTimeoutsTest.java`, `AdaptiveTimeoutsIntegrationTest.java`
- Docs: This directory

For questions or issues, review the test cases for working examples.
