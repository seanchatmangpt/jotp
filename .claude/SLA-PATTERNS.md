# SLA & Operational Excellence Patterns

**For:** SREs, platform engineers, ops teams responsible for 99.95%+ uptime.

---

## Operating JOTP at Scale

### Observability without Stopping

**JOTP's advantage:** `ProcSys` module allows introspection without killing processes.

**Get live state while running:**
```java
ProcSys sys = ProcSys.for(paymentCoordinator);

// Read state without blocking payments
StateMachineState current = sys.getState();  // ~1 µs latency
logger.info("Payments in flight: {}", current.pendingCount);

// Suspend a process for investigation
sys.suspend();  // Pauses process, queues new messages
// ... investigate state, run diagnostics ...
sys.resume();   // Resume, process caught-up messages
```

**vs. Traditional approach (with bugs):**
```java
// BAD: Thread.interrupt() doesn't work reliably
paymentThread.interrupt();
// Risk: Process in middle of state transition, half-corrupted state
```

---

## SLA Specification Template

### Example 1: Payment Service (Critical Path)

**SLO:** p99 ≤ 500ms, availability ≥ 99.99%

```
RootSupervisor (ONE_FOR_ONE)
├─ PaymentStateMachine (p99: 10ms)
│  └─ State transitions: {INITIAL → PENDING → CAPTURED → SETTLED}
├─ PaymentGatewayClient (p99: 400ms, upstream: 450ms)
│  └─ ask(ChargeMsg, 450ms timeout) applies backpressure
├─ AuditLogger (p99: 1ms, async tell)
│  └─ Crash doesn't affect payments (bulkhead)
└─ FailureRecovery (p99: 50ms)
   └─ Retry loop with exponential backoff
```

**Meeting the SLA:**

| Component | p99 Latency | Contribution |
|-----------|-------------|--------------|
| State machine | 10 ms | 2% of budget |
| Gateway ask() | 400 ms | 80% of budget |
| Serialization | 20 ms | 4% of budget |
| Jitter | 50 ms | 10% of budget |
| **Total p99** | **480 ms** | **< 500ms ✓** |

**Availability calculation:**

- **Gateway availability:** 99.9% (4.3 hrs/month downtime)
  - Supervisor restart limit: 3/60s
  - After limit: fail-fast (don't cascade)
  - Recovery: RTO 30 seconds

- **State machine availability:** 99.999% (26 seconds/month downtime)
  - Crash: restart 200 µs later
  - No message loss (queue persists)

- **Audit logger availability:** 99.5% (3.6 hrs/month downtime)
  - But crash doesn't affect payments (ONE_FOR_ONE)

- **Overall availability:** 99.99% (43 seconds/month)
  - Formula: min(gateway, state_machine) = 99.9%
  - Plus: graceful degradation when audit fails
  - **Practical:** 99.99% achievable

---

### Example 2: Multi-Tenant SaaS (Isolation SLA)

**SLO:** Each tenant ≥ 99.95%, no cross-tenant impact

```
Root (ONE_FOR_ONE)
├─ TenantA_Supervisor (ONE_FOR_ALL)  [max 5 restarts/min]
│  ├─ AuthA
│  ├─ DataA
│  └─ CacheA
├─ TenantB_Supervisor (ONE_FOR_ALL)
│  ├─ AuthB
│  ├─ DataB
│  └─ CacheB
└─ SharedMetrics (ONE_FOR_ONE)
```

**Tenant isolation guarantee:**

**Scenario 1:** TenantA crashes 6 times in 60 seconds
```
Restart 1/5: ✓
Restart 2/5: ✓
Restart 3/5: ✓
Restart 4/5: ✓
Restart 5/5: ✓
Restart 6/5: ❌ TenantA_Supervisor crashes → TenantA DOWN
```

**Impact:**
- TenantA: Unavailable (restarts JVM to recover)
- TenantB: Unaffected (still online)
- SharedMetrics: Unaffected (running)
- **TenantB SLA:** 99.95% ✓ (not affected by TenantA)

**RTO:** 30 seconds (JVM restart + DynamoDB reload)
**Impact window:** TenantA only

**Cost of isolation:** ~8 MB memory (2000 supervisors × 1K each)

---

## Runbook: Handling Common Failures

### Runbook 1: Payment Gateway Timeout Loop

**Symptom:** p99 latency jumps to 5+ seconds, timeout errors appear

**Root cause:** Upstream payment gateway slow (2x normal latency)

**JOTP behavior:**
```
Request 1: ask(ChargeMsg, 450ms) → blocks 400ms → returns
Request 2: ask(ChargeMsg, 450ms) → blocks 400ms → returns
Request 3: ask(ChargeMsg, 450ms) → blocks 400ms → returns
Request 4: ask(ChargeMsg, 450ms) → TIMEOUT (slow gateway)
Request 5-10: Also TIMEOUT
After 10 timeouts/60s: Circuit breaker opens (all requests fail fast)
```

**Resolution steps:**

1. **Immediate (< 5 min):**
   ```bash
   # Get live metrics
   curl http://localhost:8080/metrics | grep gateway_timeout_rate
   ```

   ```java
   // Check supervisor restart count via ProcSys introspection
   ProcSys sys = ProcSys.of(paymentGatewayRef);
   var stats = sys.getStatistics();
   logger.info("Restart count: {}, last error: {}", stats.restartCount(), stats.lastError());
   ```

2. **Short-term (5-30 min):**
   ```java
   // Get live process statistics via ProcSys
   ProcSys.Stats stats = ProcSys.statistics(paymentGatewayProc);
   logger.info("queue_depth={}, messages_in={}, messages_out={}",
       stats.queueDepth(), stats.messagesIn(), stats.messagesOut());
   // queue_depth growing with no messages_out → process blocked on upstream

   // Option A: Wait for gateway recovery (usually 10-15 min)
   //           Backpressure automatically reduces request rate

   // Option B: Fail-over to backup gateway
   //           Update PaymentGatewayConfig, restart child under supervisor

   // Option C: Increase ask() timeout to 600ms (accept slower responses)
   //           Risk: p99 latency increases to 600ms (violates SLA)
   ```

3. **Post-incident:**
   - Root cause: Upstream database migration
   - Fix: Add connection pooling, increase timeout to 600ms for future
   - Monitor: Set alert on `gateway_timeout_rate > 0.1%`

---

### Runbook 2: Process Memory Leak

**Symptom:** Heap grows linearly, GC pauses increase

**JOTP issue:** Message queue in some Proc is not draining (blocked consumer)

**Investigation:**

```bash
# Enable JFR (Java Flight Recorder) for real-time profiling
java -jar app.jar -XX:+UnlockCommercialFeatures -XX:+FlightRecorder \
  -XX:StartFlightRecording=name=leak-profile,disk=true,filename=/tmp/leak.jfr

# After 5 minutes, check which Proc has unbounded queue
jcmd <pid> JFR.dump name=leak-profile filename=/tmp/dump.jfr

# Analyze: Identify which Proc queue has 100K+ pending messages
./jfr-analyze.sh /tmp/dump.jfr | grep "queue_size > 10000"
Output:
  AuthService.mailbox: queue_size=100K (blocked on external API call)
  DataService.mailbox: queue_size=50K (normal)
  CacheService.mailbox: queue_size=5K (normal)
```

**Root cause:** AuthService.ask() to external API never returned (deadlock)

**Fix:**

```java
// BAD: No timeout
CompletableFuture<User> future = externalAPI.ask(msg);  // Hangs forever

// GOOD: Timeout prevents queue buildup
CompletableFuture<User> future = externalAPI.ask(msg, Duration.ofSeconds(5));
```

**Prevention:**

- **Alert:** `mailbox_size > 1000` for any Proc
- **Runbook:** "Check for deadlock: if mailbox only grows, kill the ask() call"

---

### Runbook 3: Cascading Restart (Dog-Piling)

**Symptom:** Supervisor restarts a process 100+ times/minute

**Root cause:** Service dependency (e.g., database) is down, so every process crashes

**JOTP behavior:**

```
Restart 1: ✓ (process tries DB, fails immediately)
Restart 2: ✓ (process tries DB, fails immediately)
Restart 3: ✓
...
Restart 5: ❌ Supervisor limit exceeded → Supervisor crashes
```

**Resolution:**

1. **Immediate:** Wait for database recovery
   - Supervisor stops restarting (stopped crashing)
   - Manual restart required to resume: `kill -HUP <pid>`

2. **Alternative:** Implement graceful degradation
   ```java
   StateMachine<State, Event, Data> fsm = ...;
   // On database fail, transition to DEGRADED mode
   // Serve cached responses instead of crashing
   transition(READY, DatabaseFailed, cached_data)
      → new Transition.Keep(DEGRADED)  // Stay alive, lower fidelity
   ```

3. **Post-incident:**
   - Add health check for database before starting Proc
   - Increase restart limit for tolerating temporary DB hiccups

---

## Monitoring Checklist

### Golden Signals (USE Method)

For every Proc:

**1. Utilization (U)**
```bash
# How busy is this process?
Metric: messages_per_second / max_throughput
Alert: > 80% utilization
Action: Scale up (add more Proc instances)
```

**2. Saturation (S)**
```bash
# How many messages are queued?
Metric: mailbox_size
Alert: > 1000 queued messages
Action: Process is slow (investigate ask() timeout)
```

**3. Errors (E)**
```bash
# How many messages fail?
Metric: exception_rate
Alert: > 0.1% error rate
Action: Investigate root cause (upstream service? invalid message?)
```

### JOTP-Specific Metrics

| Metric | Good | Warning | Critical |
|--------|------|---------|----------|
| `supervisor_restart_count` | 0-1/min | 5-10/min | > 10/min (likely pattern issue) |
| `mailbox_size` | < 100 | 100-1000 | > 1000 (process slow) |
| `ask_timeout_rate` | 0% | 0.1% | > 1% (backpressure limit reached) |
| `process_memory` | Stable | +1%/hr | +10%/hr (memory leak) |
| `message_latency_p99` | < 100ms | 100-500ms | > 500ms (SLA breach) |

---

## Deployment Patterns

### Blue-Green Deployment (Zero Downtime)

**Day 1:**
```
Load Balancer
├─ Cluster A (Blue)
│  └─ 10 JVM instances, 10M processes
└─ Cluster B (Green, new code)
   └─ Preparing...
```

**Day 2:**
```
// Deploy new code to Cluster B (10 instances)
for instance in cluster_b:
    deploy(instance)
    wait_for_health_check()
    run_smoke_tests()
```

**Day 3:**
```
// Switch traffic (all at once)
load_balancer.setTarget(CLUSTER_B)

// If disaster, rollback instantly
load_balancer.setTarget(CLUSTER_A)  // Revert to Blue
```

**Guarantee:** No message loss, no SLA breach during switch

---

### Graceful Shutdown (Drain In-Flight Requests)

**When shutting down a JVM:**

```java
// 1. Stop accepting new requests
loadBalancer.deregister(this);

// 2. Wait for in-flight requests to complete
var deadline = Instant.now().plusSeconds(30);
int queueDepth = ProcSys.statistics(paymentProc).queueDepth();

while (queueDepth > 0 && Instant.now().isBefore(deadline)) {
    logger.info("Draining {} in-flight requests...", queueDepth);
    Thread.sleep(1_000);
    queueDepth = ProcSys.statistics(paymentProc).queueDepth();
}

// 3. Force kill if timeout exceeded
if (queueDepth > 0) {
    logger.warn("Force killing {} pending requests", queueDepth);
}

// 4. JVM exits
System.exit(0);
```

**Result:** Existing clients get response, new clients fail fast (not waiting forever)

---

## Disaster Recovery (DR)

### RTO/RPO Requirements

**Scenario: Production JVM Crash**

| Component | RTO | RPO | How |
|-----------|-----|-----|-----|
| **State machine state** | 30s | 0s | Replay audit log (if persisted to DB/Kafka) |
| **In-flight messages** | 30s | **Lost** | Heap memory — `LinkedTransferQueue` is gone on JVM crash; recover via idempotent retry or WAL |
| **Customer data** | 30s | 0s | DynamoDB (persistent) |
| **Metrics/analytics** | 5min | 1min | Kafka (replay last 1min) |

### What Is Lost on JVM Crash

When the JVM crashes (SIGKILL, OOM, hardware fault), all heap state is gone:

- **Mailbox messages** (`LinkedTransferQueue`) — lost; callers get `IOException`/timeout
- **Process state** (`Proc<S,M>` state field) — lost unless checkpointed to durable store
- **`ProcTimer` scheduled messages** — lost; timers must be re-registered after restart
- **`ProcRegistry` name table** — lost; rebuilt automatically as processes restart
- **`ask()` callers** — their `CompletableFuture` never completes; client sees timeout

> **Note:** Within a running JVM, a process crash does NOT lose mailbox messages —
> the supervisor restarts the process and the queue continues draining. The above
> only applies to **full JVM termination**.

### Joe Armstrong's Answer: Idempotent Operations + ACK Retry

> *"There are only two hard problems in distributed systems: messages can get lost
> and processes can die. The solution is not to prevent this — it's to design
> protocols that survive it."*

Design every operation to be **idempotent** (safe to replay) and have the **sender
retry until it receives an ACK**. The receiver deduplicates by idempotency key. This
is how Erlang handles node crashes in distributed systems: the sender monitors the
node, detects the `DOWN` signal, and resends.

```java
// Sender: retry until ACK (Joe's model)
record ChargeMsg(String idempotencyKey, Amount amount) {}

Result<Receipt, Exception> result = CrashRecovery.retry(3, () ->
    gatewayProc.ask(new ChargeMsg(UUID.randomUUID().toString(), amount),
                    Duration.ofSeconds(5))
);
// If JVM crashed mid-flight, sender retries → same key → receiver deduplicates
```

**When you need stronger durability (higher RPO requirements):**
1. **Checkpoint + replay** (RPO: checkpoint interval) — periodically persist state snapshot to DB; reload on restart
2. **Write-ahead log** (RPO: ~0) — persist each message to Kafka/DB before processing; replay on restart
3. **Event sourcing** (RPO: 0) — state derived entirely from immutable event log; no checkpoints needed

### Reconstruction Steps

**After JVM crashes:**

```
1. New JVM starts (15s)
2. Load supervisor hierarchy (5s)
3. Replay audit log to restore state machine (5s)
4. Supervisor links to new Kafka broker (5s)
5. Health checks pass, load balancer adds instance (5s)
Total RTO: ~35 seconds
```

**Validation:**

```bash
# Verify no message loss
SELECT COUNT(*) FROM audit_log WHERE timestamp > crash_time;
Expected: > 0 (should have messages to replay)

# Verify state correctness
SELECT state FROM payment_log WHERE id IN (...)
Expected: Matches new JVM after replay
```

---

## SLA Dashboard (Prometheus Example)

```yaml
# .claude/prometheus-rules.yml
groups:
  - name: jotp-slas
    rules:
      - alert: PaymentServiceLatency
        expr: histogram_quantile(0.99, payment_latency_seconds) > 0.5
        annotations:
          summary: "Payment p99 latency > 500ms, SLA at risk"

      - alert: TenantIsolationBreach
        expr: |
          (max by (tenant) (process_restart_count_rate))
          > (min by (tenant) (process_restart_count_rate))
        annotations:
          summary: "One tenant failing more than others, check isolation"

      - alert: MailboxBuildup
        expr: mailbox_size > 1000
        annotations:
          summary: "Process {{ $labels.proc_name }} has {{ $value }} queued messages"
```

---

## Conclusion

**JOTP enables 99.95%+ SLA:**
1. Isolation (no cascading failures)
2. Fast recovery (200 µs restarts)
3. Backpressure (no queue explosion)
4. Observability (ProcSys introspection)

**Key principle:** "Fail fast, recover quick, don't cascade"

This is OTP's 40-year battle-tested philosophy, now in Java.
