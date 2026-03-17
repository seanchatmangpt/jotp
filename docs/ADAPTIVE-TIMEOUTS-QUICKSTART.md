# Adaptive Timeouts - Quick Start Guide

## 60-Second Overview

AdaptiveTimeouts learns optimal timeout values by monitoring response time distributions (p50, p99, p999). No more static timeouts!

**Key idea**: `timeout = p999 + (p999 - p50) * buffer_factor`

## 5-Minute Setup

### 1. Create Timeout Manager

```java
var timeouts = AdaptiveTimeouts.create();
```

### 2. Record Response Times

```java
long startNanos = System.nanoTime();
Result<Response, Exception> result = Result.of(() ->
    myService.call(request)
);
long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

timeouts.recordResponse("my-service", elapsedMs, result.isSuccess());
```

### 3. Use Adaptive Timeout

```java
Duration timeout = timeouts.getTimeout("my-service");
Future<Response> future = myService.ask(request, timeout);
```

### 4. Monitor Health

```java
var stats = timeouts.getStats("my-service");
if (stats.isUnderStress()) {
  alerting.alert("High timeout rate: " + stats.totalTimeouts());
}
```

## Common Patterns

### Pattern 1: Service Client Wrapper

```java
public class ResilientClient {
  private final AdaptiveTimeouts timeouts;
  private final RemoteService service;

  public Result<Data, Exception> fetch(String id) {
    long startNanos = System.nanoTime();
    try {
      Duration timeout = timeouts.getTimeout("data-service");
      Data result = service.fetch(id, timeout);
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      timeouts.recordResponse("data-service", elapsedMs, true);
      return Result.ok(result);
    } catch (TimeoutException e) {
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      timeouts.recordResponse("data-service", elapsedMs, false);
      return Result.err(e);
    }
  }
}
```

### Pattern 2: Multi-Service Pipeline

```java
public Result<Order, OrderError> processOrder(Order order) {
  // Step 1: Validate with adaptive timeout
  var validateResult = validate(order);
  if (validateResult.isError()) return Result.err(...);

  // Step 2: Reserve inventory with adaptive timeout
  var reserveResult = reserveInventory(order);
  if (reserveResult.isError()) return Result.err(...);

  // Step 3: Process payment with adaptive timeout
  var paymentResult = processPayment(order);
  if (paymentResult.isError()) return Result.err(...);

  return Result.ok(new Order(...));
}

private Result<Void, OrderError> validate(Order order) {
  long start = System.nanoTime();
  try {
    Duration timeout = timeouts.getTimeout("validation-service");
    validationService.validate(order, timeout);
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    timeouts.recordResponse("validation-service", elapsed, true);
    return Result.ok(null);
  } catch (TimeoutException e) {
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    timeouts.recordResponse("validation-service", elapsed, false);
    return Result.err(new OrderError("validation_timeout"));
  }
}
```

### Pattern 3: Load Balancing

```java
public String callFastest(String request, String... replicas) {
  // Find replica with lowest timeout (best performance)
  String fastestReplica = Arrays.stream(replicas)
      .min(Comparator.comparing(r ->
          timeouts.getStats(r).currentTimeoutMs()))
      .orElse(replicas[0]);

  Duration timeout = timeouts.getTimeout(fastestReplica);
  return serviceClient.call(fastestReplica, request, timeout);
}
```

### Pattern 4: Circuit Breaker Integration

```java
public boolean shouldOpenCircuit(String serviceName) {
  var stats = timeouts.getStats(serviceName);

  // Open if high timeout rate
  if (stats.isUnderStress()) return true;

  // Open if jitter is high (service degrading)
  if (stats.jitterRatio() > 2.0) return true;

  // Open if timeout at max bound
  if (stats.currentTimeoutMs() >= 30000) return true;

  return false;
}
```

## Configuration Reference

```java
var config = new AdaptiveTimeouts.Config(
    // Bounds
    100,           // minTimeoutMs - never go below this
    30000,         // maxTimeoutMs - never exceed this

    // Algorithm
    0.5,           // bufferFactor - safety margin on jitter (0.3-0.7)
    60000,         // smoothingWindowMs - update interval (ms)
    0.05,          // hysteresisThreshold - oscillation prevention (0.05-0.20)

    // Load awareness
    1.5,           // cpuScaleFactor - scale when CPU high (1.5-2.0)
    5,             // estimatedNetworkLatencyMs - base RTT (5-50ms)

    // Storage
    Optional.empty(),  // or Optional.of(postgresStore)

    // Frequency
    5000           // statisticsUpdateIntervalMs - how often to recompute (1000-10000)
);

var timeouts = AdaptiveTimeouts.create(config);
```

## Monitoring Checklist

```java
// Regular health checks
for (var stats : timeouts.getAllStats().values()) {
  String service = stats.serviceName();

  // Alert on high timeout rate
  if (stats.isUnderStress()) {
    logger.warn("{}: {} timeouts out of {} requests",
        service, stats.totalTimeouts(), stats.totalRequests());
  }

  // Alert on high jitter (service degrading)
  if (stats.jitterRatio() > 2.0) {
    logger.warn("{}: High jitter ratio = {}", service, stats.jitterRatio());
  }

  // Alert if timeout is too aggressive
  if (stats.timeoutHealth() < 0.8) {
    logger.info("{}: Timeout is {} below optimal", service,
        String.format("%.0f%%", (1 - stats.timeoutHealth()) * 100));
  }

  // Alert if timeout is too lenient
  if (stats.timeoutHealth() > 1.5) {
    logger.info("{}: Timeout is {} above optimal", service,
        String.format("%.0f%%", (stats.timeoutHealth() - 1) * 100));
  }

  // Record metrics for dashboards
  metrics.gauge("timeout." + service, stats.currentTimeoutMs());
  metrics.gauge("p99." + service, stats.p99Ms());
  metrics.gauge("p999." + service, stats.p999Ms());
}
```

## Testing Guide

### Test 1: Verify Timeout Increases Under Load

```java
@Test
void timeoutIncreases() {
  var timeouts = AdaptiveTimeouts.create();

  // Fast responses
  for (int i = 0; i < 50; i++) {
    timeouts.recordResponse("api", 50, true);
  }
  long fastTimeout = timeouts.getTimeout("api").toMillis();

  // Slow responses
  for (int i = 0; i < 50; i++) {
    timeouts.recordResponse("api", 500, true);
  }
  Thread.sleep(100);
  long slowTimeout = timeouts.getTimeout("api").toMillis();

  assertThat(slowTimeout).isGreaterThan(fastTimeout);
}
```

### Test 2: Verify Storm Prevention

```java
@Test
void preventTimeoutStorms() {
  var timeouts = AdaptiveTimeouts.create();

  // Record 10 consecutive timeouts
  for (int i = 0; i < 10; i++) {
    timeouts.recordResponse("flaky", 10000, false);
  }
  Thread.sleep(100);

  var stats = timeouts.getStats("flaky");
  assertThat(stats.isUnderStress()).isTrue();
  // Timeout should be significantly increased
  assertThat(stats.currentTimeoutMs()).isGreaterThan(500);
}
```

### Test 3: Simulate Network Degradation

```java
@Test
void networkDegradation() {
  var timeouts = AdaptiveTimeouts.create();

  // Good network: 50ms p99
  for (int i = 0; i < 100; i++) {
    timeouts.recordResponse("api", 50, true);
  }
  Thread.sleep(100);
  var goodStats = timeouts.getStats("api");

  // Bad network: 500ms p99
  for (int i = 0; i < 100; i++) {
    timeouts.recordResponse("api", 500, true);
  }
  Thread.sleep(100);
  var badStats = timeouts.getStats("api");

  assertThat(badStats.p99Ms()).isGreaterThan(goodStats.p99Ms());
  assertThat(badStats.currentTimeoutMs()).isGreaterThan(goodStats.currentTimeoutMs());
}
```

## Troubleshooting

### Issue: Timeout keeps increasing

**Cause**: Service is degrading or experiencing cascading failures
**Fix**: Check `jitterRatio()` - if > 2.0, circuit break the service

### Issue: Timeout at max bound (30000ms)

**Cause**: p999 is very high
**Fix**: Investigate if service needs scaling, or increase maxTimeoutMs

### Issue: Frequent timeout adjustments (oscillating)

**Cause**: hysteresisThreshold too low
**Fix**: Increase hysteresisThreshold to 0.10-0.20

### Issue: Timeouts too aggressive (false positives)

**Cause**: bufferFactor too low
**Fix**: Increase bufferFactor from 0.5 to 0.7 or 1.0

### Issue: Timeouts too lenient (cascading failures)

**Cause**: bufferFactor too high
**Fix**: Decrease bufferFactor from 0.5 to 0.3

## PostgreSQL Schema

If using history store:

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

## Advanced: Custom History Store

```java
class PostgresTimeoutStore implements TimeoutHistoryStore {
  private final DataSource ds;

  @Override
  public void recordAdjustment(String serviceName, long prevTimeout,
      long newTimeout, long p99Ms, long totalRequests, String reason) {
    try (var conn = ds.getConnection()) {
      String sql = "INSERT INTO timeout_history " +
          "(service_name, previous_timeout_ms, new_timeout_ms, " +
          "p99_ms, total_requests, reason) " +
          "VALUES (?, ?, ?, ?, ?, ?)";
      var stmt = conn.prepareStatement(sql);
      stmt.setString(1, serviceName);
      stmt.setLong(2, prevTimeout);
      stmt.setLong(3, newTimeout);
      stmt.setLong(4, p99Ms);
      stmt.setLong(5, totalRequests);
      stmt.setString(6, reason);
      stmt.executeUpdate();
    } catch (SQLException e) {
      logger.error("Failed to record timeout", e);
    }
  }

  @Override
  public List<Map<String, Object>> queryHistory(String serviceName, int limit) {
    try (var conn = ds.getConnection()) {
      String sql = "SELECT * FROM timeout_history WHERE service_name = ? " +
          "ORDER BY recorded_at DESC LIMIT ?";
      var stmt = conn.prepareStatement(sql);
      stmt.setString(1, serviceName);
      stmt.setInt(2, limit);
      var rs = stmt.executeQuery();

      var results = new ArrayList<Map<String, Object>>();
      while (rs.next()) {
        var record = new HashMap<String, Object>();
        record.put("service", rs.getString("service_name"));
        record.put("prev", rs.getLong("previous_timeout_ms"));
        record.put("new", rs.getLong("new_timeout_ms"));
        record.put("p99", rs.getLong("p99_ms"));
        results.add(record);
      }
      return results;
    } catch (SQLException e) {
      logger.error("Failed to query history", e);
      return List.of();
    }
  }
}
```

## Next Steps

1. **Basic integration**: Wrap your service clients with AdaptiveTimeouts
2. **Monitoring**: Set up alerts for high jitter and timeout rates
3. **Testing**: Run load tests with network degradation simulation
4. **Production**: Enable PostgreSQL history store and monitor adjustments
5. **Tuning**: Adjust bufferFactor and hysteresisThreshold based on observations

## Links

- Main documentation: `docs/ADAPTIVE-TIMEOUTS.md`
- Examples: `src/main/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeoutsExample.java`
- Tests: `src/test/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeoutsTest.java`
- Integration tests: `src/test/java/io/github/seanchatmangpt/jotp/ai/AdaptiveTimeoutsIntegrationTest.java`
