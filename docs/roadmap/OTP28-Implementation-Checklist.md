# OTP 28 Framework - Implementation Checklist

## Quick Reference: What to Build Next

### TIER 1: Immediate 80/20 Production Coverage (Weeks 1-2)

**🎯 Goal: Add 5 classes, ~2,000 LOC, reach 80% production readiness**

---

## [ ] 1. Behavior<S,M> - Callback Interface
- **Package:** `io.github.seanchatmangpt.jotp`
- **Effort:** LOW (single interface + adapter)
- **Priority:** 1/5
- **Files:**
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/Behavior.java`
  - [ ] `src/test/java/io/github/seanchatmangpt/jotp/BehaviorTest.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/dogfood/otp/BehaviorExample.java`
  - [ ] `src/test/java/io/github/seanchatmangpt/jotp/dogfood/otp/BehaviorExampleTest.java`

- **Key Components:**
  - [ ] Sealed interface `Behavior<S,M>` with `handleCall/Cast/Info`
  - [ ] Factory method `GenServer.from(Behavior, initialState)`
  - [ ] Adapter wrapping callbacks to pattern matching
  - [ ] Example: `CounterBehavior implements Behavior<Integer, CounterMessage>`

- **Test Coverage:**
  - [ ] Basic call/cast/info dispatch
  - [ ] State maintenance across calls
  - [ ] Concurrent behavior
  - [ ] Error handling

- **Est. LOC:** 200-300 (main) + 150-250 (tests)

---

## [ ] 2. RetryPolicy - Exponential Backoff + Jitter
- **Package:** `io.github.seanchatmangpt.jotp.resilience`
- **Effort:** MEDIUM (sealed hierarchy)
- **Priority:** 2/5
- **Files:**
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/RetryPolicy.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/ExponentialBackoffPolicy.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/FixedDelayPolicy.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/NoRetryPolicy.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/ResilientOperation.java`
  - [ ] `src/test/java/io/github/seanchatmangpt/jotp/resilience/RetryPolicyTest.java`

- **Key Components:**
  - [ ] Sealed `RetryPolicy` interface with `delayBeforeAttempt/maxAttempts`
  - [ ] `ExponentialBackoffPolicy` with `multiplier` (default 2.0)
  - [ ] Jitter implementation (0-50% random)
  - [ ] Integration with `CrashRecovery.retry()`
  - [ ] Static factory: `RetryPolicy.exponential(maxAttempts, baseDelay, maxDelay)`

- **Test Coverage:**
  - [ ] Exponential calculation (2^n * base)
  - [ ] Jitter range (0-50%)
  - [ ] Max delay cap
  - [ ] Max attempts boundary
  - [ ] Integration with CrashRecovery

- **Est. LOC:** 300-400 (main) + 200-300 (tests)

---

## [ ] 3. CircuitBreaker<T> - CLOSED/OPEN/HALF_OPEN State Machine
- **Package:** `io.github.seanchatmangpt.jotp.resilience`
- **Effort:** MEDIUM (state machine)
- **Priority:** 3/5
- **Files:**
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/CircuitBreaker.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/CircuitBreakerException.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/resilience/CircuitBreakerOpenException.java`
  - [ ] `src/test/java/io/github/seanchatmangpt/jotp/resilience/CircuitBreakerTest.java`

- **Key Components:**
  - [ ] Sealed `State` interface: `Closed`, `Open(openedAt)`, `HalfOpen`
  - [ ] Fields: `failureThreshold`, `successThreshold`, `timeout`
  - [ ] Methods: `execute()` → `Result<T, Exception>`
  - [ ] State transitions: CLOSED→OPEN (on failures), OPEN→HALF_OPEN (after timeout), HALF_OPEN→CLOSED (on successes)
  - [ ] AtomicReference for thread-safe state
  - [ ] AtomicInteger for consecutive counter tracking

- **Test Coverage:**
  - [ ] CLOSED state: success path
  - [ ] CLOSED→OPEN: failure threshold exceeded
  - [ ] OPEN state: immediate failure
  - [ ] OPEN→HALF_OPEN: timeout reached
  - [ ] HALF_OPEN→CLOSED: success threshold
  - [ ] HALF_OPEN→OPEN: failure in half-open
  - [ ] Concurrent access

- **Est. LOC:** 400-500 (main) + 300-400 (tests)

---

## [ ] 4. BackpressureQueue<T> - Bounded Queue with Overflow Policy
- **Package:** `io.github.seanchatmangpt.jotp`
- **Effort:** LOW (wrapper pattern)
- **Priority:** 4/5
- **Files:**
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/BackpressureQueue.java`
  - [ ] `src/test/java/io/github/seanchatmangpt/jotp/BackpressureQueueTest.java`

- **Key Components:**
  - [ ] Enum `OverflowPolicy`: DROP_OLDEST, REJECT_NEW, BLOCK
  - [ ] Constructor: `BackpressureQueue(int capacity, OverflowPolicy)`
  - [ ] Methods: `enqueue(T)`, `dequeue(Duration)`, `isEmpty()`, `size()`
  - [ ] Underlying: `LinkedBlockingQueue<T>`
  - [ ] Integration note: Alternative mailbox for `Proc`

- **Test Coverage:**
  - [ ] Normal enqueue/dequeue
  - [ ] DROP_OLDEST overflow
  - [ ] REJECT_NEW overflow (exception)
  - [ ] BLOCK overflow (waits for space)
  - [ ] Queue full condition
  - [ ] Concurrent producers/consumers

- **Est. LOC:** 250-350 (main) + 150-250 (tests)

---

## [ ] 5. HealthCheck / HealthMonitor - Proactive System Health
- **Package:** `io.github.seanchatmangpt.jotp.observability`
- **Effort:** LOW (record + simple scheduler)
- **Priority:** 5/5
- **Files:**
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/observability/HealthCheck.java`
  - [ ] `src/main/java/io/github/seanchatmangpt/jotp/observability/HealthMonitor.java`
  - [ ] `src/test/java/io/github/seanchatmangpt/jotp/observability/HealthMonitorTest.java`

- **Key Components:**
  - [ ] Record `HealthCheck(name, status, message, timestamp, metadata)`
  - [ ] Enum `HealthStatus`: HEALTHY, DEGRADED, UNHEALTHY
  - [ ] Class `HealthMonitor` with `addProcess(name, proc)`
  - [ ] Scheduled checks (configurable interval)
  - [ ] Integration with `ProcSys.statistics()`
  - [ ] Queue depth → DEGRADED (threshold: 1000)

- **Test Coverage:**
  - [ ] Add/monitor processes
  - [ ] Healthy process detection
  - [ ] Degraded process (high queue depth)
  - [ ] Unhealthy process (exception)
  - [ ] Scheduled periodic checks
  - [ ] Health result handling

- **Est. LOC:** 200-300 (main) + 150-250 (tests)

---

## Summary: TIER 1 Checklist

**Total New Code:** ~1,500-2,000 LOC (main) + 1,000-1,300 LOC (tests)
**Total Est. Effort:** 60-80 developer hours
**Expected Timeline:** 2 weeks
**Production Readiness:** 40% → 80%

### Pre-Implementation Checklist
- [ ] Review `OTP28-80-20-Coverage.puml` diagram
- [ ] Read `OTP28-Implementation-Guide.md` detailed specs
- [ ] Create feature branch: `claude/add-otp-resilience-tier1-<session>`
- [ ] Set up spotless/formatting for new packages

### Implementation Checklist (Sequential)
- [ ] **Behavior<S,M>** (1 day)
- [ ] **RetryPolicy** (2 days)
- [ ] **CircuitBreaker<T>** (2 days)
- [ ] **BackpressureQueue<T>** (1.5 days)
- [ ] **HealthCheck/Monitor** (1.5 days)
- [ ] Integration tests + dogfood examples (1 day)
- [ ] Documentation + module-info updates (1 day)

### Post-Implementation Checklist
- [ ] All tests passing
- [ ] Build verification: `mvnd verify`
- [ ] Spotless formatting: `mvnd spotless:apply`
- [ ] Dogfood examples compile and run
- [ ] Documentation complete
- [ ] Git commit with comprehensive message
- [ ] Git push to designated branch

---

## TIER 2: Medium-Term Production (Weeks 3-4)

**🎯 Goal: Add 5 more classes, ~1,500-2,000 LOC, reach 95% production readiness**

### [ ] 6. RateLimiter - Token Bucket Algorithm
- **Package:** `io.github.seanchatmangpt.jotp.resilience`
- **Effort:** MEDIUM
- **Est. LOC:** 300-400 (main) + 200-300 (tests)

### [ ] 7. RequestContext - ScopedValue Wrapper
- **Package:** `io.github.seanchatmangpt.jotp.context`
- **Effort:** MEDIUM
- **Est. LOC:** 250-350 (main) + 150-250 (tests)

### [ ] 8. ChannelBuffer<T> - Go-Style Channels
- **Package:** `io.github.seanchatmangpt.jotp.channels`
- **Effort:** MEDIUM
- **Est. LOC:** 400-500 (main) + 250-350 (tests)

### [ ] 9. SupervisorBuilder - Fluent API
- **Package:** `io.github.seanchatmangpt.jotp`
- **Effort:** LOW
- **Est. LOC:** 150-250 (main) + 100-200 (tests)

### [ ] 10. Metrics - Counter/Gauge/Histogram/Timer
- **Package:** `io.github.seanchatmangpt.jotp.observability`
- **Effort:** MEDIUM
- **Est. LOC:** 350-450 (main) + 250-350 (tests)

---

## TIER 3: Specialized Features (Weeks 5-6+)

Only implement if needed for specific use cases.

---

## Files to Create/Modify

### New Directories
```
src/main/java/io/github/seanchatmangpt/jotp/
├── resilience/              [NEW]
│   ├── RetryPolicy.java
│   ├── ExponentialBackoffPolicy.java
│   ├── CircuitBreaker.java
│   └── ...
└── observability/           [NEW]
    ├── HealthCheck.java
    ├── HealthMonitor.java
    └── ...

src/test/java/io/github/seanchatmangpt/jotp/
├── resilience/              [NEW]
│   └── ...
└── observability/           [NEW]
    └── ...
```

### Modified Files
```
src/main/java/module-info.java
  - Add exports for new packages

src/main/java/io/github/seanchatmangpt/jotp/GenServer.java
  - Add static factory: GenServer.from(Behavior, initialState)
```

---

## Success Criteria

### TIER 1 Complete When:
- ✅ All 5 classes implemented with comprehensive tests
- ✅ 50+ new test methods, 90%+ code coverage
- ✅ 5 dogfood examples demonstrating each class
- ✅ Full documentation with javadoc
- ✅ Module-info updated
- ✅ Spotless formatting applied
- ✅ Build passing: `mvnd verify`
- ✅ Git commit pushed to `claude/add-otp-resilience-tier1-<session>`

### Production Readiness:
- ✅ Retry logic works end-to-end
- ✅ Circuit breaker prevents cascading failures
- ✅ Bounded queues prevent OOM
- ✅ Health monitoring detects issues
- ✅ OOP-style handlers (Behavior) work with GenServer

---

## Related Documentation

- **Full Implementation Guide:** `OTP28-Implementation-Guide.md`
- **Architecture Diagram:** `OTP28-80-20-Coverage.puml`
- **Original OTP Coverage:** See Claude.md

---

**Ready to start? Pick TIER 1 class #1 (Behavior<S,M>) and begin!** 🚀
