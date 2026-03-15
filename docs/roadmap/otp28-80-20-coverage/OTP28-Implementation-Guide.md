# OTP 28 Framework - 80/20 Implementation Guide

**⚠️ HISTORICAL ROADMAP DOCUMENT - This is the original implementation planning document.**

**For current pattern implementation status, see:**
- **[Pattern Implementation Status](../../patterns/STATUS.md)** - Current status of all 34 patterns
- **[Pattern Library Executive Overview](../../PATTERN_OVERVIEW.md)** - Executive summary for all audiences
- **[Comprehensive Pattern Library](../../patterns/COMPREHENSIVE_PATTERN_LIBRARY.md)** - Detailed pattern guides

**For OTP core primitives documentation, see:**
- **[JOTP Main Documentation](../../README.md)** - Project overview
- **[Architecture Guide](../../explanations/architecture.md)** - System architecture
- **[OTP Equivalence](../../explanations/otp-equivalence.md)** - Erlang/OTP to Java mapping

## Executive Summary

**Current State:**
- ✅ 15 OTP primitives fully implemented
- ✅ 3 high-level abstractions (GenServer, PoolSupervisor, Application)
- ✅ 5 dogfood teaching examples
- **Total: ~5,000 LOC**

**80/20 Coverage Target:**
- Add **Tier 1 (5 classes)** = **~1,500-2,000 new LOC**
- This covers **80% of production use cases**

**Full Coverage:**
- Add Tier 1 + Tier 2 + Tier 3 = **~5,000 additional LOC**

---

## TIER 1: Immediate Production Needs (5 Classes)

### 1. Behavior<S,M> - Callback-based Handler Interface
**Impact:** 🔴 HIGH | **Effort:** LOW | **Priority:** 1/5

**Problem:** Some users prefer traditional OOP callbacks over sealed-type pattern matching.

**Solution:**
```java
package io.github.seanchatmangpt.jotp;

/**
 * Traditional callback interface for OTP-style handlers.
 * Alternative to pattern-matching in GenServer.
 *
 * @param <S> state type
 * @param <M> message type
 */
public interface Behavior<S, M> {
    /**
     * Handle synchronous call (request-reply).
     * @return new state
     */
    S handleCall(M request, S state);

    /**
     * Handle asynchronous cast (fire-and-forget).
     * @return new state
     */
    S handleCast(M request, S state);

    /**
     * Handle out-of-band info messages.
     * @return new state
     */
    S handleInfo(Object info, S state);
}
```

**Adapter to GenServer:**
```java
// User writes:
class CounterBehavior implements Behavior<Integer, CounterMessage> {
    public Integer handleCall(CounterMessage msg, Integer state) {
        return (msg instanceof Increment inc)
            ? state + inc.delta()
            : state;
    }
    // ...
}

// Framework adapts:
var counter = GenServer.from(new CounterBehavior(), 0);
```

**Est. Implementation:** 200-300 LOC (main + tests)

**Files to Create:**
- `src/main/java/io/github/seanchatmangpt/jotp/Behavior.java`
- `src/test/java/io/github/seanchatmangpt/jotp/BehaviorTest.java`

---

### 2. RetryPolicy - Configurable Retry Strategies
**Impact:** 🔴 HIGH | **Effort:** MEDIUM | **Priority:** 2/5

**Problem:** Users need production-grade retry logic with exponential backoff and jitter.

**Solution:**
```java
package io.github.seanchatmangpt.jotp.resilience;

/**
 * Sealed hierarchy of retry strategies.
 * Works with CrashRecovery for resilience.
 */
public sealed interface RetryPolicy permits
    ExponentialBackoffPolicy, FixedDelayPolicy, NoRetryPolicy {

    /**
     * Compute delay before next attempt.
     * @param attempt 0-indexed attempt number
     * @return delay duration
     */
    Duration delayBeforeAttempt(int attempt);

    /**
     * Max attempts allowed.
     */
    int maxAttempts();
}

public record ExponentialBackoffPolicy(
    int maxAttempts,
    Duration baseDelay,
    Duration maxDelay,
    double multiplier,      // typically 2.0
    boolean jitter          // prevent thundering herd
) implements RetryPolicy {

    @Override
    public Duration delayBeforeAttempt(int attempt) {
        if (attempt >= maxAttempts) {
            return Duration.ZERO; // no more attempts
        }
        var exponential = baseDelay.multipliedBy(
            (long) Math.pow(multiplier, attempt)
        );
        var capped = exponential.compareTo(maxDelay) > 0
            ? maxDelay
            : exponential;

        if (jitter) {
            // Add 0-50% random jitter
            long jitterMs = (long)(capped.toMillis() * 0.5 * Math.random());
            return capped.plus(Duration.ofMillis(jitterMs));
        }
        return capped;
    }
}
```

**Integration with CrashRecovery:**
```java
public class ResilientOperation {
    public static <T> Result<T, Exception> executeWithRetry(
        Supplier<T> operation,
        RetryPolicy policy
    ) {
        for (int attempt = 0; attempt < policy.maxAttempts(); attempt++) {
            try {
                var result = operation.get();
                return Result.ok(result);
            } catch (Exception e) {
                if (attempt < policy.maxAttempts() - 1) {
                    Duration delay = policy.delayBeforeAttempt(attempt);
                    Thread.sleep(delay.toMillis());
                } else {
                    return Result.err(e);
                }
            }
        }
        return Result.err(new IllegalStateException("No attempts"));
    }
}
```

**Est. Implementation:** 300-400 LOC (main + tests)

**Files to Create:**
- `src/main/java/io/github/seanchatmangpt/jotp/resilience/RetryPolicy.java`
- `src/main/java/io/github/seanchatmangpt/jotp/resilience/ExponentialBackoffPolicy.java`
- `src/main/java/io/github/seanchatmangpt/jotp/resilience/FixedDelayPolicy.java`
- `src/test/java/io/github/seanchatmangpt/jotp/resilience/RetryPolicyTest.java`

---

### 3. CircuitBreaker<T> - Fail-Fast Pattern
**Impact:** 🔴 HIGH | **Effort:** MEDIUM | **Priority:** 3/5

**Problem:** Cascading failures in distributed systems (e.g., timeout to downstream → retry storm).

**Solution:**
```java
package io.github.seanchatmangpt.jotp.resilience;

/**
 * Circuit breaker pattern: CLOSED → OPEN → HALF_OPEN → CLOSED
 * Prevents cascading failures by failing fast.
 */
public class CircuitBreaker<T> {

    public sealed interface State {
        record Closed() implements State {}
        record Open(Instant openedAt) implements State {}
        record HalfOpen() implements State {}
    }

    private final Supplier<T> supplier;
    private final int failureThreshold;    // e.g., 5 failures
    private final int successThreshold;    // e.g., 2 successes
    private final Duration timeout;        // e.g., 30s to retry
    private final AtomicReference<State> state;
    private final AtomicInteger consecutiveFailures;
    private final AtomicInteger consecutiveSuccesses;

    /**
     * Execute supplier; open circuit on repeated failures.
     */
    public Result<T, Exception> execute() {
        return switch (state.get()) {
            case State.Closed _ -> executeClosed();
            case State.Open open -> executeOpen(open);
            case State.HalfOpen _ -> executeHalfOpen();
        };
    }

    private Result<T, Exception> executeClosed() {
        try {
            var result = supplier.get();
            consecutiveFailures.set(0);
            return Result.ok(result);
        } catch (Exception e) {
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                state.set(new State.Open(Instant.now()));
                return Result.err(
                    new CircuitBreakerOpenException("Circuit opened after " + failureThreshold + " failures")
                );
            }
            return Result.err(e);
        }
    }

    private Result<T, Exception> executeOpen(State.Open open) {
        if (Instant.now().isAfter(open.openedAt().plus(timeout))) {
            state.set(new State.HalfOpen());
            return executeHalfOpen();
        }
        return Result.err(new CircuitBreakerOpenException("Circuit is OPEN"));
    }

    private Result<T, Exception> executeHalfOpen() {
        try {
            var result = supplier.get();
            if (consecutiveSuccesses.incrementAndGet() >= successThreshold) {
                state.set(new State.Closed());
                consecutiveFailures.set(0);
                consecutiveSuccesses.set(0);
            }
            return Result.ok(result);
        } catch (Exception e) {
            state.set(new State.Open(Instant.now()));
            return Result.err(e);
        }
    }
}
```

**Est. Implementation:** 400-500 LOC (main + tests)

**Files to Create:**
- `src/main/java/io/github/seanchatmangpt/jotp/resilience/CircuitBreaker.java`
- `src/test/java/io/github/seanchatmangpt/jotp/resilience/CircuitBreakerTest.java`

---

### 4. BackpressureQueue<T> - Bounded Mailbox
**Impact:** 🔴 HIGH | **Effort:** LOW | **Priority:** 4/5

**Problem:** Unbounded mailbox queues can cause OOM in high-load scenarios.

**Solution:**
```java
package io.github.seanchatmangpt.jotp;

/**
 * Bounded queue wrapper for Proc mailbox.
 * Prevents OOM from unbounded message accumulation.
 */
public class BackpressureQueue<T> {

    public enum OverflowPolicy {
        DROP_OLDEST,    // Remove oldest message
        REJECT_NEW,     // Throw exception
        BLOCK           // Block until space available
    }

    private final LinkedBlockingQueue<T> queue;
    private final OverflowPolicy policy;

    public BackpressureQueue(int capacity, OverflowPolicy policy) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.policy = policy;
    }

    public void enqueue(T message) throws Exception {
        boolean offered = queue.offer(message);

        if (!offered) {
            switch (policy) {
                case DROP_OLDEST:
                    queue.poll(); // Remove oldest
                    queue.offer(message);
                    break;
                case REJECT_NEW:
                    throw new IllegalStateException(
                        "Queue full (capacity: " + queue.remainingCapacity() + ")"
                    );
                case BLOCK:
                    queue.put(message); // Blocks until space
                    break;
            }
        }
    }

    public T dequeue(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}

// Usage with Proc:
var bounded = new BackpressureQueue<Message>(1000, OverflowPolicy.DROP_OLDEST);
var proc = new Proc<>(initialState, (state, msg) -> {
    bounded.enqueue(msg); // Prevents unbounded growth
    return state;
});
```

**Est. Implementation:** 250-350 LOC (main + tests)

**Files to Create:**
- `src/main/java/io/github/seanchatmangpt/jotp/BackpressureQueue.java`
- `src/test/java/io/github/seanchatmangpt/jotp/BackpressureQueueTest.java`

---

### 5. HealthCheck / Health Monitor - Proactive Observability
**Impact:** 🔴 HIGH | **Effort:** LOW | **Priority:** 5/5

**Problem:** Need proactive health monitoring integrated with ProcSys.

**Solution:**
```java
package io.github.seanchatmangpt.jotp.observability;

/**
 * Health check record integrated with ProcSys.
 */
public record HealthCheck(
    String name,
    HealthStatus status,
    String message,
    Instant timestamp,
    Map<String, Object> metadata
) {

    public enum HealthStatus {
        HEALTHY,        // All systems nominal
        DEGRADED,       // Working but with issues
        UNHEALTHY       // Not functioning
    }
}

/**
 * Health monitor for process collections.
 */
public class HealthMonitor {

    private final Map<String, Proc<?, ?>> processes = new ConcurrentHashMap<>();
    private final Duration checkInterval;
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(1);

    public HealthMonitor(Duration checkInterval) {
        this.checkInterval = checkInterval;
    }

    public void addProcess(String name, Proc<?, ?> proc) {
        processes.put(name, proc);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::performHealthChecks,
            checkInterval.toMillis(),
            checkInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    private void performHealthChecks() {
        processes.forEach((name, proc) -> {
            try {
                var stats = ProcSys.statistics(proc);
                var status = stats.queueDepth() > 1000
                    ? HealthStatus.DEGRADED
                    : HealthStatus.HEALTHY;

                var check = new HealthCheck(
                    name,
                    status,
                    "Queue depth: " + stats.queueDepth(),
                    Instant.now(),
                    Map.of("msgs_in", stats.messagesIn(),
                           "msgs_out", stats.messagesOut())
                );

                handleHealthResult(check);
            } catch (Exception e) {
                handleHealthResult(new HealthCheck(
                    name,
                    HealthStatus.UNHEALTHY,
                    e.getMessage(),
                    Instant.now(),
                    Map.of()
                ));
            }
        });
    }

    private void handleHealthResult(HealthCheck check) {
        // Log, alert, or update metrics
        System.out.println("[HEALTH] " + check.name() + ": " + check.status());
    }
}
```

**Est. Implementation:** 200-300 LOC (main + tests)

**Files to Create:**
- `src/main/java/io/github/seanchatmangpt/jotp/observability/HealthCheck.java`
- `src/main/java/io/github/seanchatmangpt/jotp/observability/HealthMonitor.java`
- `src/test/java/io/github/seanchatmangpt/jotp/observability/HealthMonitorTest.java`

---

## TIER 2: Medium-Term Production (5 Classes)

### 6. RateLimiter - Token Bucket Algorithm
**Impact:** 🟡 MEDIUM | **Effort:** MEDIUM | **Priority:** 6/10

**Lines:** 300-400 | **Key Feature:** API rate limiting, backpressure

### 7. RequestContext - ScopedValue Wrapper
**Impact:** 🟡 MEDIUM | **Effort:** MEDIUM | **Priority:** 7/10

**Lines:** 250-350 | **Key Feature:** Context propagation, vthread-safe tracing

### 8. ChannelBuffer<T> - Go-Style Channels
**Impact:** 🟡 MEDIUM | **Effort:** MEDIUM | **Priority:** 8/10

**Lines:** 400-500 | **Key Feature:** Familiar interface for Go/Rust developers

### 9. SupervisorBuilder - Fluent API
**Impact:** 🟡 MEDIUM | **Effort:** LOW | **Priority:** 9/10

**Lines:** 150-250 | **Key Feature:** Better DX, reduced boilerplate

### 10. Metrics - Telemetry Collection
**Impact:** 🟡 MEDIUM | **Effort:** MEDIUM | **Priority:** 10/10

**Lines:** 350-450 | **Key Feature:** Counter, Gauge, Histogram, Timer

---

## TIER 3: Specialized/Optional (5 Classes)

### 11. GenServerBehavior - Erlang-Style Callbacks
**Low impact, enables Erlang-familiar patterns**

### 12. Saga<T> - Distributed Transactions
**High complexity, enables workflow coordination**

### 13. DistributedRegistry - Multi-Node Clustering
**High complexity, requires serialization + networking**

### 14. TransactionLog - Event Sourcing
**Medium complexity, enables event-driven architectures**

### 15. Custom Patterns - Domain-Specific Extensions
**Extensibility point for user implementations**

---

## Implementation Roadmap

### Phase 1 (Weeks 1-2): Tier 1 Core - 80/20 Value
```
Week 1:
  Day 1-2: Behavior<S,M> interface + adapter
  Day 3-4: RetryPolicy sealed hierarchy
  Day 5:   CircuitBreaker<T> state machine

Week 2:
  Day 1-2: BackpressureQueue<T> bounded wrapper
  Day 3-4: HealthCheck + HealthMonitor
  Day 5:   Integration tests + dogfood examples
```

**Estimated effort:** 60-80 developer hours
**New LOC:** 1,500-2,000
**Test coverage:** 40+ new test methods

### Phase 2 (Weeks 3-4): Tier 2 Observability/DX
```
Week 3:
  Day 1-2: RateLimiter token bucket
  Day 3-4: RequestContext ScopedValue wrapper
  Day 5:   ChannelBuffer<T> Go-style channels

Week 4:
  Day 1-2: SupervisorBuilder fluent API
  Day 3-4: Metrics Counter/Gauge/Histogram/Timer
  Day 5:   Documentation + examples
```

**Estimated effort:** 80-100 developer hours
**New LOC:** 1,500-2,000
**Test coverage:** 35+ new test methods

### Phase 3 (Weeks 5-6+): Tier 3 Specialized
Only if needed for specific use cases.

---

## Coverage Matrix

| Category | TIER 0 | +T1 | +T2 | +T3 |
|----------|--------|-----|-----|-----|
| **Core OTP** | ✅ 15 | ✅ 15 | ✅ 15 | ✅ 15 |
| **Abstractions** | ✅ 3 | ✅ 8 | ✅ 13 | ✅ 18 |
| **Resilience** | ❌ 0 | ✅ 3 | ✅ 4 | ✅ 5 |
| **Observability** | ⚠️ 1 | ✅ 2 | ✅ 5 | ✅ 6 |
| **DX/Tooling** | ❌ 0 | ❌ 0 | ✅ 2 | ✅ 3 |
| **Specialized** | ❌ 0 | ❌ 0 | ❌ 0 | ✅ 5 |
| **TOTAL LOC** | ~5,000 | ~6,500 | ~8,000 | ~10,000+ |
| **Production Ready** | ✅ 40% | ✅ 80% | ✅ 95% | ✅ 100% |

---

## Estimated Impact

### TIER 1 (Immediate 80/20)
- **Production readiness:** 40% → 80%
- **Covers:** Retry, circuit breaker, bounded queues, health checks, OOP handlers
- **Cost:** 1,500-2,000 LOC + tests
- **Effort:** 60-80 hours
- **ROI:** Highest - solves core production needs

### TIER 2 (Medium-term)
- **Production readiness:** 80% → 95%
- **Covers:** Rate limiting, request context, channels, metrics, builder DX
- **Cost:** 1,500-2,000 LOC + tests
- **Effort:** 80-100 hours
- **ROI:** High - improves observability and DX

### TIER 3 (Full 100%)
- **Production readiness:** 95% → 100%
- **Covers:** Distributed systems, event sourcing, domain-specific patterns
- **Cost:** 1,500-2,000+ LOC
- **Effort:** 100+ hours
- **ROI:** Medium - specialized use cases

---

## Summary: What to Build Next

**Start with TIER 1 in this order:**
1. **Behavior<S,M>** - Easy win, high adoption
2. **RetryPolicy** - Critical for production
3. **CircuitBreaker<T>** - Prevents cascades
4. **BackpressureQueue<T>** - Prevents OOM
5. **HealthCheck** - Essential observability

This gets you to **80% production readiness** with **~2,000 LOC** in **2 weeks**.

Then add TIER 2 for **95% readiness** with additional **~2,000 LOC** in **2 more weeks**.

---

## Visualization

See `OTP28-80-20-Coverage.puml` for PlantUML diagram showing:
- All 15 OTP primitives (TIER 0)
- 3 recent abstractions (TIER 0.5)
- 5 Tier 1 immediate classes
- 5 Tier 2 medium-term classes
- 5 Tier 3 specialized classes
- Dependency relationships
- Impact/effort annotations
