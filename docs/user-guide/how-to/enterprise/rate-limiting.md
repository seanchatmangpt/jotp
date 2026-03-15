# Rate Limiting Pattern

## Problem

Your API is overwhelmed by excessive requests from clients, causing service degradation, increased costs, and poor user experience. You need to control request rates per client, per endpoint, or globally.

**Symptoms:**
- API abuse from aggressive clients
- Resource exhaustion from too many concurrent requests
- High cloud computing bills from over-provisioning
- Poor latency for legitimate users

## Solution

Implement rate limiting to control the rate of incoming requests. JOTP's RateLimiter provides multiple algorithms:

- **Token Bucket**: Smooth traffic flow with burst tolerance
- **Sliding Window**: Precise request counting over time window
- **Per-Client**: Rate limiting per client identifier (API key, IP, tenant)

## Prerequisites

- Java 26 with preview features enabled
- JOTP core module: `io.github.seanchatmangpt.jotp`
- Understanding of rate limiting algorithms

## Implementation

### Step 1: Choose Your Algorithm

```java
import io.github.seanchatmangpt.jotp.RateLimiter;
import java.time.Duration;

// Token bucket: 100 requests per second with burst capacity
RateLimiter limiter = RateLimiter.tokenBucket(100, 100.0);

// Sliding window: 1000 requests per minute
RateLimiter limiter = RateLimiter.slidingWindow(1000, Duration.ofMinutes(1));

// Per-second convenience
RateLimiter limiter = RateLimiter.perSecond(100);

// Per-minute convenience
RateLimiter limiter = RateLimiter.perMinute(6000);
```

### Step 2: Use Rate Limiter

```java
// Check if request is allowed
if (limiter.tryAcquire()) {
    // Process request
    return handleRequest();
} else {
    // Rate limit exceeded
    return Response.of(429, "Rate limit exceeded");
}

// Request multiple permits
if (limiter.tryAcquire(5)) {
    // Process expensive operation (costs 5 permits)
    return handleExpensiveOperation();
} else {
    return Response.of(429, "Insufficient permits");
}
```

### Step 3: Per-Client Rate Limiting

```java
// Create per-client rate limiter
RateLimiter.PerClient<String> perClientLimiter =
    new RateLimiter.PerClient<>(clientId -> RateLimiter.perSecond(100));

// Use with client identifier
if (perClientLimiter.tryAcquire(clientId)) {
    return handleRequest(clientId);
} else {
    return Response.of(429, "Rate limit exceeded for client: " + clientId);
}

// Request multiple permits for a client
if (perClientLimiter.tryAcquire(clientId, 10)) {
    return handleExpensiveOperation(clientId);
} else {
    return Response.of(429, "Insufficient permits");
}

// Reset specific client
perClientLimiter.reset(clientId);
```

## Complete Examples

### Token Bucket Rate Limiter

```java
public class ApiGateway {
    private final RateLimiter limiter;

    public ApiGateway() {
        // 100 requests/second with 200 burst capacity
        this.limiter = RateLimiter.tokenBucket(200, 100.0);
    }

    public Response handle(Request request) {
        if (limiter.tryAcquire()) {
            return service.process(request);
        } else {
            return Response.of(429, "Rate limit exceeded")
                .withHeader("X-RateLimit-Limit", "100")
                .withHeader("X-RateLimit-Remaining", String.valueOf(limiter.availablePermits()));
        }
    }
}
```

### Sliding Window Rate Limiter

```java
public class ApiKeyRateLimiter {
    private final RateLimiter.PerClient<String> perClientLimiter;

    public ApiKeyRateLimiter() {
        // 1000 requests per minute per API key
        this.perClientLimiter = new RateLimiter.PerClient<>(
            apiKey -> RateLimiter.slidingWindow(1000, Duration.ofMinutes(1))
        );
    }

    public Response handle(Request request) {
        String apiKey = request.getHeader("X-API-Key");

        if (perClientLimiter.tryAcquire(apiKey)) {
            return service.process(request);
        } else {
            return Response.of(429, "API key rate limit exceeded: " + apiKey);
        }
    }
}
```

### Tiered Rate Limiting

```java
public class TieredRateLimiter {
    private final Map<String, RateLimiter> tierLimiters = new HashMap<>();

    public TieredRateLimiter() {
        tierLimiters.put("free", RateLimiter.perMinute(100));
        tierLimiters.put("pro", RateLimiter.perMinute(1000));
        tierLimiters.put("enterprise", RateLimiter.perMinute(10000));
    }

    public Response handle(Request request) {
        String tier = getUserTier(request);
        RateLimiter limiter = tierLimiters.get(tier);

        if (limiter.tryAcquire()) {
            return service.process(request);
        } else {
            return Response.of(429, "Rate limit exceeded for tier: " + tier);
        }
    }
}
```

### Endpoint-Specific Rate Limiting

```java
public class EndpointRateLimiter {
    private final Map<String, RateLimiter> endpointLimiters = new ConcurrentHashMap<>();

    public Response handle(Request request) {
        String endpoint = request.path();

        RateLimiter limiter = endpointLimiters.computeIfAbsent(endpoint, e -> {
            // Different limits for different endpoints
            return switch (e) {
                case "/api/search" -> RateLimiter.perSecond(100);
                case "/api/export" -> RateLimiter.perMinute(10);
                case "/api/upload" -> RateLimiter.perMinute(5);
                default -> RateLimiter.perSecond(50);
            };
        });

        if (limiter.tryAcquire()) {
            return service.process(request);
        } else {
            return Response.of(429, "Rate limit exceeded for: " + endpoint);
        }
    }
}
```

## Distributed Rate Limiting with Redis

```java
public class RedisRateLimiter {
    private final JedisPool redisPool;

    public boolean tryAcquire(String key, int limit, Duration window) {
        try (Jedis redis = redisPool.getResource()) {
            String key = "rate_limit:" + clientId;

            Pipeline pipeline = redis.pipelined();

            // Increment counter
            Response<Long> current = pipeline.incr(key);

            // Set expiry on first request
            pipeline.expire(key, (int) window.getSeconds());

            pipeline.sync();

            long count = current.get();
            return count <= limit;
        }
    }

    public Response handle(Request request) {
        String clientId = request.getClientId();

        if (tryAcquire(clientId, 100, Duration.ofMinutes(1))) {
            return service.process(request);
        } else {
            return Response.of(429, "Rate limit exceeded");
        }
    }
}
```

## Configuration Guidelines

### Token Bucket vs Sliding Window

```java
// Token bucket: Smooth traffic, allows bursts
RateLimiter.tokenBucket(capacity, refillRate);
// Use when: You want to allow short bursts within average rate

// Sliding window: Strict rate enforcement
RateLimiter.slidingWindow(maxRequests, window);
// Use when: You need strict request counting over time window
```

### Capacity and Refill Rate

```java
// Conservative: Low rate, small burst
RateLimiter.tokenBucket(10, 10.0);  // 10 req/s, 10 burst

// Balanced: Moderate rate, moderate burst
RateLimiter.tokenBucket(100, 50.0);  // 50 req/s, 100 burst

// Aggressive: High rate, large burst
RateLimiter.tokenBucket(1000, 100.0);  // 100 req/s, 1000 burst
```

### Time Windows

```java
// Per-second
Duration.ofSeconds(1);

// Per-minute
Duration.ofMinutes(1);

// Per-hour
Duration.ofHours(1);

// Per-day
Duration.ofDays(1);
```

## Performance Considerations

### Memory
- **Token bucket**: ~100 bytes per limiter
- **Sliding window**: ~1 KB per limiter (timestamp tracking)
- **Per-client**: O(clients) memory usage
- **Scaling**: 10,000 clients ≈ 1 MB heap

### Latency
- **Token bucket**: ~0.1 μs (atomic counter)
- **Sliding window**: ~1 μs (timestamp cleanup)
- **Per-client**: ~0.5 μs (map lookup + limiter)
- **No blocking**: All operations are lock-free

### Throughput
- **Single limiter**: 100M+ checks/second
- **Per-client**: 50M+ checks/second
- **Limited by**: Atomic operations, not algorithm

## Monitoring

### Key Metrics

```java
record RateLimiterMetrics(
    String limiterName,
    String algorithm,            // TOKEN_BUCKET, SLIDING_WINDOW
    long totalRequests,          // Total requests checked
    long allowedRequests,        // Requests allowed
    long rejectedRequests,       // Requests rejected
    double currentRate,          // Current request rate
    long availablePermits,       // Available permits/tokens
    double utilizationPercent    // Utilization (0-100)
) {}
```

### Alerting

```java
// Alert on high rejection rate
if (rejectionRate > 5%) {
    alertService.send(AlertPriority.MEDIUM,
        "High rejection rate: " + limiterName);
}

// Alert on rate limiter exhaustion
if (availablePermits == 0 for > 1 minute) {
    alertService.send(AlertPriority.HIGH,
        "Rate limiter exhausted: " + limiterName);
}

// Alert on abuse detection
if (clientRejectionRate > 50%) {
    alertService.send(AlertPriority.CRITICAL,
        "Potential abuse from client: " + clientId);
}
```

## Common Pitfalls

### 1. Rate Limit Too Strict
```java
// BAD: Frustrates legitimate users
RateLimiter.perSecond(1);  // 1 request per second

// GOOD: Reasonable limits
RateLimiter.perSecond(100);  // 100 requests per second
```

### 2. No Per-Client Limits
```java
// BAD: One abusive client affects everyone
RateLimiter limiter = RateLimiter.perSecond(1000);

// GOOD: Per-client limits
RateLimiter.PerClient<String> perClient = new RateLimiter.PerClient<>(
    client -> RateLimiter.perSecond(100)
);
```

### 3. Forgetting Sliding Window Cleanup
```java
// BAD: Memory leak (old timestamps never cleaned)
// (JOTP handles this automatically)

// GOOD: JOTP's sliding window auto-expires old timestamps
RateLimiter.slidingWindow(1000, Duration.ofMinutes(1));
```

## Advanced Patterns

### Graduated Rate Limiting

```java
public class GraduatedRateLimiter {
    private final List<Tier> tiers = List.of(
        new Tier(100, Duration.ofMinutes(1), "free"),
        new Tier(1000, Duration.ofMinutes(1), "pro"),
        new Tier(10000, Duration.ofMinutes(1), "enterprise")
    );

    public RateLimiter getLimiterForUser(User user) {
        Tier tier = getTierForUser(user);
        return RateLimiter.slidingWindow(tier.limit(), tier.window());
    }
}
```

### Dynamic Rate Limiting

```java
public class DynamicRateLimiter {
    private volatile RateLimiter limiter;

    public void adjustLimit(int newLimit) {
        this.limiter = RateLimiter.perSecond(newLimit);
    }

    public boolean tryAcquire() {
        RateLimiter current = limiter;
        return current.tryAcquire();
    }
}
```

### Rate Limit Header Injection

```java
public Response handle(Request request) {
    boolean allowed = limiter.tryAcquire();

    return Response.builder()
        .status(allowed ? 200 : 429)
        .header("X-RateLimit-Limit", "100")
        .header("X-RateLimit-Remaining", String.valueOf(limiter.availablePermits()))
        .header("X-RateLimit-Reset", String.valueOf(getResetTime()))
        .body(allowed ? "OK" : "Rate limit exceeded")
        .build();
}
```

## Related Guides

- **[Circuit Breaker](./circuit-breaker.md)** - Fail fast for failing services
- **[Bulkhead Isolation](./bulkhead-isolation.md)** - Isolate resource-intensive features
- **[Distributed Locks](./distributed-locks.md)** - Coordinate across instances

## References

- **RateLimiter**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/RateLimiter.java`
- **ApiGateway**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/ApiGateway.java`
- **Examples**: `/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/MultiTenantSaaSPlatform.java`
