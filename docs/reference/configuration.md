# Reference: Configuration

JOTP runtime settings, tuning parameters, and production configuration.

---

## Quick Reference

```bash
# Minimum recommended JVM flags for production JOTP
java \
  --enable-preview \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -Djdk.virtualThreadScheduler.maxPoolSize=256 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -Xmx8g \
  -jar myapp.jar
```

---

## Virtual Thread Scheduler

JOTP processes run on virtual threads. The virtual thread scheduler controls how virtual threads are multiplexed onto OS threads (carrier threads).

| Property | Default | Valid Range | Description |
|----------|---------|-------------|-------------|
| `jdk.virtualThreadScheduler.parallelism` | `Runtime.getRuntime().availableProcessors()` | 1–512 | Number of carrier threads. Controls the maximum concurrent virtual thread executions. |
| `jdk.virtualThreadScheduler.maxPoolSize` | `256` | parallelism–32768 | Maximum carrier threads when work-stealing expands the pool. Rarely needs tuning. |
| `jdk.tracePinnedThreads` | not set | `short`, `full` | Log virtual thread pinning events. Use `short` for production diagnostics, `full` for debugging. |

**Set via JVM system properties:**
```bash
-Djdk.virtualThreadScheduler.parallelism=32
-Djdk.tracePinnedThreads=short
```

**When to tune `parallelism`:**
- **Default (= CPU cores):** Correct for I/O-bound workloads — the common JOTP case
- **Lower than CPU cores:** Memory-constrained containers where you want fewer OS threads
- **Higher than CPU cores:** **Never.** Causes OS oversubscription and degrades performance

**Detecting carrier thread saturation:** If all carrier threads are pinned (by `synchronized` blocks or blocking native calls), throughput collapses. Monitor with:
```bash
-Djdk.tracePinnedThreads=short
```
Output when pinned:
```
Thread[#42,ForkJoinPool-1-worker-1,5,CarrierThreads]
  io.github.seanchatmangpt.jotp.example.MyService.synchronizedMethod(MyService.java:42)
```

---

## Supervisor Configuration

Supervisor parameters control fault-tolerance behavior. All are set programmatically.

```java
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,  // restart strategy
    5,                                 // maxRestarts
    Duration.ofSeconds(60)             // restartWindow
);
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `strategy` | `Supervisor.Strategy` | `ONE_FOR_ONE` | Restart strategy: `ONE_FOR_ONE`, `ONE_FOR_ALL`, `REST_FOR_ONE` |
| `maxRestarts` | `int` | `5` | Maximum restarts within `restartWindow` before supervisor gives up |
| `restartWindow` | `Duration` | `Duration.ofSeconds(60)` | Time window for counting restarts |

### Restart Strategy Selection

| Strategy | When to Use | Behavior |
|----------|-------------|----------|
| `ONE_FOR_ONE` | Independent workers (default) | Only crashed child restarts |
| `ONE_FOR_ALL` | Shared-state children | All children restart when any crashes |
| `REST_FOR_ONE` | Pipeline dependencies | Crashed child and all children started after it restart |

**`maxRestarts` / `restartWindow` guidance:**

```
Too tight (maxRestarts=1, window=5s):
  → Supervisor gives up on first temporary failure
  → Application goes down on transient errors
  → BAD for network-dependent services

Too loose (maxRestarts=1000, window=60s):
  → Infinite restart loops on permanent failures
  → CPU/memory exhausted by restart thrashing
  → BAD for resource-constrained systems

Recommended (maxRestarts=5, window=60s):
  → Tolerates transient failures (5 in a minute)
  → Gives up on permanent failures (6th crash within 60s)
  → Supervisor itself crashes and propagates to parent supervisor
```

**Tuning for specific failure modes:**

```java
// High-availability service (tolerates transient network issues)
new Supervisor(Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5))

// Fast-fail service (crash immediately if dependency down)
new Supervisor(Strategy.ONE_FOR_ONE, 1, Duration.ofSeconds(10))

// Shared database connection pool (restart all on connection failure)
new Supervisor(Strategy.ONE_FOR_ALL, 3, Duration.ofMinutes(1))
```

---

## Process Registry

`ProcRegistry` is a global name table mapping `String` names to `Proc` instances.

There are no configurable limits — `ProcRegistry` uses a `ConcurrentHashMap` and grows dynamically. The practical limit is heap memory.

**Auto-deregistration:** When a process stops (normally or via crash), `ProcRegistry` automatically removes its entry. No cleanup required.

```java
// Register a named process
ProcRegistry.register("payment-coordinator", myProc);

// Lookup (returns null if not found)
Proc<?,?> proc = ProcRegistry.whereis("payment-coordinator");

// List all registered names
Set<String> names = ProcRegistry.registered();

// Manual deregister (optional — happens automatically on process stop)
ProcRegistry.unregister("payment-coordinator");
```

**Naming conventions:**

```
service-name                     # Top-level service
service-name/worker-N            # Numbered workers
tenant-{tenantId}/coordinator    # Tenant-scoped services
region-{region}/gateway          # Region-scoped processes
```

---

## Timeout Defaults

All JOTP timeouts are explicit — there are no hidden global defaults. Every `ask()` call requires a `Duration`.

```java
// REQUIRED: explicit timeout on every ask()
proc.ask(new PaymentMsg(amount), Duration.ofSeconds(5));

// Common patterns
Duration.ofMillis(100)     // Fast internal services
Duration.ofSeconds(5)      // External API calls
Duration.ofSeconds(30)     // Database queries (slow queries)
Duration.ofMinutes(1)      // Long-running operations
```

**Why no global default timeout?** Explicit timeouts make SLA reasoning visible. When reviewing code, "5 seconds" communicates intent. A global default hides assumptions that later cause production incidents.

### ProcTimer Granularity

`ProcTimer` delivers messages via Java's `ScheduledExecutorService`. The minimum reliable granularity is the OS scheduler tick (~1ms on Linux, ~10-15ms on Windows).

```java
// Minimum reliable interval: ~1ms on Linux
ProcTimer.sendAfter(Duration.ofMillis(1), proc, new TickMsg());  // OK on Linux

// Windows: ~15ms minimum reliable
ProcTimer.sendAfter(Duration.ofMillis(15), proc, new TickMsg()); // OK everywhere
```

For sub-millisecond scheduling, use spinning within the process handler (not recommended for battery-powered devices or shared VMs).

---

## Logging Configuration

JOTP uses Java Util Logging (JUL). Configure via standard JUL properties or SLF4J bridge.

```properties
# logging.properties
io.github.seanchatmangpt.jotp.level = INFO
io.github.seanchatmangpt.jotp.Supervisor.level = FINE  # supervisor restart events
io.github.seanchatmangpt.jotp.ProcRegistry.level = FINER  # registry operations
```

```bash
# Set via system property
-Djava.util.logging.config.file=/etc/myapp/logging.properties
```

**SLF4J bridge (recommended for production):**
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>jul-to-slf4j</artifactId>
    <version>2.0.x</version>
</dependency>
```

```java
// Install bridge at application startup
SLF4JBridgeHandler.removeHandlersForRootLogger();
SLF4JBridgeHandler.install();
```

### Structured Logging with Process Context

Add process identity to MDC for correlated log analysis:

```java
// In process handler, add process name to MDC
(state, msg) -> {
    MDC.put("proc", "payment-coordinator");
    MDC.put("tenant", state.tenantId());
    try {
        return processMessage(state, msg);
    } finally {
        MDC.clear();
    }
}
```

Logback pattern:
```xml
<pattern>%d{ISO8601} [%thread] [proc=%X{proc}] [tenant=%X{tenant}] %-5level %logger{36} - %msg%n</pattern>
```

---

## Performance Profiles

Three pre-tested configuration profiles for common deployment patterns.

### Profile 1: Latency-Optimized

Best for: API gateways, real-time bidding, payment processing.
Goal: Sub-millisecond `ask()` latency.

```bash
java \
  --enable-preview \
  -Djdk.virtualThreadScheduler.parallelism=32 \
  -XX:+UseZGC \
  -XX:MaxGCPauseMillis=1 \
  -XX:+AlwaysPreTouch \
  -Xms8g -Xmx8g \
  -jar myapp.jar
```

Key choices:
- ZGC for sub-millisecond GC pauses (at cost of higher CPU)
- Fixed heap (`-Xms = -Xmx`) to prevent heap resizing pauses
- `AlwaysPreTouch` to pre-allocate all pages at startup
- Higher parallelism for faster virtual thread scheduling

### Profile 2: Throughput-Optimized

Best for: Batch processing, event pipelines, async workers.
Goal: Maximum messages per second.

```bash
java \
  --enable-preview \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -XX:G1NewSizePercent=40 \
  -XX:G1MaxNewSizePercent=60 \
  -Xmx32g \
  -jar myapp.jar
```

Key choices:
- G1GC for better throughput vs ZGC
- Large young generation for short-lived message records
- Larger heap to reduce GC frequency
- Lower parallelism (I/O-bound, CPU headroom for GC)

### Profile 3: Memory-Constrained

Best for: Container deployments with tight memory limits (512 MB - 2 GB).

```bash
java \
  --enable-preview \
  -Djdk.virtualThreadScheduler.parallelism=4 \
  -Djdk.virtualThreadScheduler.maxPoolSize=32 \
  -XX:+UseSerialGC \
  -Xmx512m \
  -jar myapp.jar
```

Key choices:
- SerialGC (no parallel GC threads = smaller footprint)
- Low parallelism to limit carrier thread count
- Small heap — accept more frequent GC cycles

---

## Observability: JFR Configuration

Java Flight Recorder (JFR) provides low-overhead production profiling.

```bash
# Start recording at application launch
java --enable-preview \
  -XX:StartFlightRecording=duration=0,filename=/tmp/jotp.jfr,dumponexit=true \
  -jar myapp.jar

# Dynamically start/stop recording
jcmd <pid> JFR.start name=jotp duration=120s filename=/tmp/jotp-snapshot.jfr
jcmd <pid> JFR.dump name=jotp filename=/tmp/jotp-snapshot.jfr
jcmd <pid> JFR.stop name=jotp
```

**Events to capture:**

```bash
# Full configuration for JOTP diagnostics
java \
  -XX:StartFlightRecording=\
    name=jotp,\
    duration=0,\
    dumponexit=true,\
    filename=/tmp/jotp.jfr,\
    settings=profile  # includes virtual thread events
```

Key JFR events for JOTP:
- `jdk.VirtualThreadStart/End` — tracks process creation and termination
- `jdk.VirtualThreadPinned` — identifies synchronized pinning issues
- `jdk.ObjectAllocationInNewTLAB` — shows message allocation hotspots
- `jdk.GarbageCollection` — correlates GC pauses with latency spikes

---

## Memory Tuning

### Heap Sizing by Process Count

Each virtual thread stack starts at ~8 KB and grows on demand (up to configurable max). Each process additionally stores its state `S` and active mailbox messages.

| Active Processes | Typical Heap | Notes |
|-----------------|--------------|-------|
| 1,000 | 256 MB | Development, small services |
| 10,000 | 1 GB | Mid-size service |
| 100,000 | 4 GB | Large service |
| 1,000,000 | 16–32 GB | High-scale deployment |
| 10,000,000 | 128 GB | Erlang-scale deployment |

**Memory breakdown per process (approximate):**
```
Virtual thread stack:   8–64 KB (grows with call depth)
Process state (S):      100–2000 bytes (depends on your records)
Mailbox queue:          200 bytes base + 64 bytes per queued message
ProcRef wrapper:        48 bytes
Total per process:      ~10–100 KB typical
```

### Virtual Thread Stack Size

Virtual thread stacks grow on demand. For processes with deep call stacks (recursion, many nested calls), you may see stack overflow with default settings.

```bash
# Increase maximum virtual thread stack size (default: unlimited / OS-managed)
# Virtual threads use continuation stacks, not full OS stacks
# You cannot directly configure virtual thread stack size in Java 26
# Instead, reduce call depth in handlers or increase native stack size:
-Xss2m  # Increases platform thread stack (affects carrier threads, not virtual threads)
```

**Best practice:** Keep handler call depth shallow. If your handler calls 20+ nested methods, refactor to reduce depth. Deep stacks also hurt GC performance.

---

## Production Configuration Checklist

Before deploying a JOTP service to production, verify:

- [ ] `--enable-preview` flag present in JVM args
- [ ] Supervisor `maxRestarts` set appropriately for failure tolerance target
- [ ] Supervisor `restartWindow` matches expected failure rate
- [ ] All `ask()` calls have explicit timeouts (no unbounded waits)
- [ ] Heap size calculated for peak process count + 50% headroom
- [ ] GC algorithm selected for latency/throughput target
- [ ] JFR enabled with `dumponexit=true` for post-mortem analysis
- [ ] `jdk.tracePinnedThreads=short` enabled in staging to detect pinning issues
- [ ] Logging configured with process context in MDC
- [ ] Health endpoint exposes `ProcSys` metrics (supervisor restart count, mailbox size)
- [ ] Alert on `supervisor_restart_count > 5/min` for any supervisor
- [ ] Alert on `mailbox_size > 1000` for any process (backpressure signal)

---

## Environment Variables

JOTP does not read any custom environment variables at runtime. All configuration is via:
1. JVM system properties (`-D` flags)
2. Programmatic API (Supervisor constructor, ProcTimer parameters, etc.)

```bash
# NOT a JOTP env var — example of setting JVM properties via env
export JAVA_TOOL_OPTIONS="-Djdk.virtualThreadScheduler.parallelism=32 --enable-preview"
```

---

**Related:** [Troubleshooting](troubleshooting.md) | [API Overview](api.md) | [Architecture Overview](../explanations/architecture-overview.md)
