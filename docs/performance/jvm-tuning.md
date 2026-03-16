# JOTP JVM Tuning Guide

**Version:** 1.0.0-SNAPSHOT
**Last Updated:** 2026-03-15
**Java Version:** OpenJDK 26 with `--enable-preview`

## Executive Summary

JOTP leverages Java 26's virtual threads and structured concurrency to achieve Erlang/OTP-level performance. This guide covers JVM configuration for optimal throughput, latency, and memory usage.

### Quick Start Configurations

| Use Case | GC | Heap Size | Parallelism | Flags |
|----------|-------|-----------|-------------|-------|
| **Real-time** | ZGC | 8 GB fixed | Physical cores | `-XX:+UseZGC -XX:+ZGenerational` |
| **Throughput** | G1GC | 4-16 GB | Auto-detect | `-XX:+UseG1GC -XX:MaxGCPauseMillis=50` |
| **Memory-Constrained** | SerialGC | 512 MB | 2 cores | `-XX:+UseSerialGC` |

---

## 1. Virtual Thread Configuration

### 1.1 Virtual Thread Scheduler Parallelism

**Default:** Auto-detect (number of physical CPU cores)

**Configuration:**
```bash
# Set parallelism to physical core count
java -Djdk.virtualThreadScheduler.parallelism=16 -jar app.jar

# Set to auto-detect (default)
java -Djdk.virtualThreadScheduler.parallelism=0 -jar app.jar

# Limit parallelism (for shared environments)
java -Djdk.virtualThreadScheduler.parallelism=4 -jar app.jar
```

**Tuning Guidelines:**

| Scenario | Parallelism | Rationale |
|----------|-------------|-----------|
| **CPU-bound handlers** | Physical cores | Avoid oversubscription |
| **I/O-bound handlers** | 2× physical cores | Hide I/O latency |
| **Mixed workload** | 1.5× physical cores | Balance CPU and I/O |
| **Containerized** | CPU quota | Respect limits |

**Example:**
```bash
# 16-core server, I/O-bound handlers
java -Djdk.virtualThreadScheduler.parallelism=32 -jar app.jar

# 4-core container, CPU-bound handlers
java -Djdk.virtualThreadScheduler.parallelism=4 -jar app.jar
```

### 1.2 Virtual Thread Stack Size

**Default:** Platform-dependent (typically 1 MB for platform threads, much smaller for virtual)

**Configuration:**
```bash
# Not directly configurable for virtual threads
# Virtual thread stacks grow on-demand, ~1KB typical
```

**Memory Impact:**
```
Stack Usage = Number of Processes × Average Stack Depth

1M processes × 1KB average stack = 1 GB total
```

---

## 2. Heap Sizing for Millions of Processes

### 2.1 Memory Calculator

**Formula:**
```
heap_min = (process_count × 1.2KB) +
           (message_rate × avg_message_size × latency_p99) × 4
```

**Examples:**

| Processes | Msg Rate | Avg Msg | P99 Latency | Min Heap | Recommended Heap |
|-----------|----------|---------|-------------|----------|------------------|
| **1,000** | 100K/s | 256B | 1ms | 230 MB | 1 GB |
| **10,000** | 1M/s | 1KB | 5ms | 5.8 GB | 24 GB |
| **100,000** | 10M/s | 128B | 500μs | 8.2 GB | 32 GB |
| **1,000,000** | 100M/s | 64B | 200μs | 14 GB | 64 GB |

**Example Calculation (10K processes, 1M msg/s):**
```
process_memory = 10,000 × 1.2KB = 12 MB
message_memory = 1,000,000 msg/s × 256B × 0.005s = 1.28 GB
message_memory_peak = 1.28 GB × 4 (headroom) = 5.12 GB
total_heap = 12 MB + 5.12 GB = ~5.2 GB minimum
recommended_heap = 5.2 GB × 4 = ~20 GB
```

### 2.2 Heap Sizing Strategies

**Fixed Heap (Latency-Optimized):**
```bash
# No resize pauses, predictable latency
-Xms8g -Xmx8g -XX:SoftMaxHeapSize=7g
```

**Dynamic Heap (Throughput-Optimized):**
```bash
# Grows/shrinks based on workload, higher throughput
-Xms4g -Xmx16g
```

**Container-Aware Heap:**
```bash
# Respect container memory limits (Java 8u191+)
-XX:MaxRAMPercentage=75.0
# For 8GB container: Uses 6GB heap
```

---

## 3. GC Tuning

### 3.1 G1GC (Default for <50K Processes)

**Configuration:**
```bash
# Enable G1GC with target pause time
-XX:+UseG1GC
-XX:MaxGCPauseMillis=50
-XX:G1HeapRegionSize=32m
-XX:+UseStringDeduplication
```

**Characteristics:**

| Property | Value |
|----------|-------|
| **Pause Time Target** | 50 ms (configurable) |
| **Process Limit** | <50K processes |
| **Message Rate** | <10M msg/s |
| **Throughput** | High (90%+) |
| **Footprint** | Medium |

**When to Use:**
- Process count <50K
- Mixed workloads (CPU + I/O)
- Need balance between latency and throughput
- Default choice for most scenarios

**Tuning Guidelines:**
```bash
# Reduce pause time (increases GC overhead)
-XX:MaxGCPauseMillis=20

# Increase pause time (reduces GC overhead)
-XX:MaxGCPauseMillis=100

# Adjust region size (must be power of 2, 1MB-32MB)
-XX:G1HeapRegionSize=16m   # Smaller regions for better parallelism
-XX:G1HeapRegionSize=32m   # Larger regions for less fragmentation
```

### 3.2 ZGC (Required for >50K Processes)

**Configuration:**
```bash
# Enable ZGC (Java 21+)
-XX:+UseZGC
-XX:+ZGenerational  # Generational ZGC (Java 21+)
-XX:SoftMaxHeapSize=12g  # Leave headroom before hard limit
```

**Characteristics:**

| Property | Value |
|----------|-------|
| **Pause Time Target** | <1 ms |
| **Process Limit** | >100K processes |
| **Message Rate** | >10M msg/s |
| **Throughput** | Medium (85%+) |
| **Footprint** | High (10% overhead) |

**When to Use:**
- Process count >50K
- Low-latency requirements (<1 ms GC pauses)
- High allocation rates
- Large heaps (>16 GB)

**Tuning Guidelines:**
```bash
# Enable concurrent relocation (Java 21+)
-XX:+ZGenerational

# Set soft heap limit (start GC before hard limit)
-Xms16g -Xmx32g -XX:SoftMaxHeapSize=28g

# Tune ZGC threads (auto by default)
-XX:ZWorkerThreads=8
-XX:ZCollectionThreads=2
```

### 3.3 SerialGC (Memory-Constrained)

**Configuration:**
```bash
# Enable SerialGC (lowest overhead)
-XX:+UseSerialGC
-Xms256m -Xmx512m
```

**Characteristics:**

| Property | Value |
|----------|-------|
| **Pause Time** | 10-100 ms (single-threaded) |
| **Process Limit** | <5K processes |
| **Message Rate** | <1M msg/s |
| **Throughput** | Low (70%+) |
| **Footprint** | Low (minimal overhead) |

**When to Use:**
- Small heap (<1 GB)
- Containerized deployments
- Low memory environments
- Development/testing

---

## 4. Thread Pool Configuration

### 4.1 Carrier Thread Pool

**Virtual threads are scheduled on carrier threads (ForkJoinPool).**

**Configuration:**
```bash
# Set carrier thread parallelism
-Djdk.virtualThreadScheduler.parallelism=16

# Monitor carrier thread usage
-Djdk.virtualThreadScheduler.implicitTasks=true
```

**Monitoring:**
```bash
# Use JFR to monitor carrier threads
jcmd <pid> JFR.start name=vt dur=60s
jcmd <pid> JFR.stop

# Open in JDK Mission Control
# Look for: jdk.VirtualThreadStart, jdk.VirtualThreadEnd
```

### 4.2 Custom Thread Pools

**For blocking I/O in handlers:**

```java
// Create custom executor for blocking operations
ExecutorService blockingPool = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors() * 2,
    new ThreadFactoryBuilder()
        .setNameFormat("blocking-pool-%d")
        .setDaemon(true)
        .build()
);

// Use in handler
static State handle(State state, Msg msg) {
    return switch (msg) {
        case FetchData(var id) -> {
            // Offload blocking I/O to custom pool
            var result = CompletableFuture.supplyAsync(
                () -> db.query(id),
                blockingPool
            ).join();
            yield state.withData(result);
        }
        default -> state;
    };
}
```

---

## 5. Performance Profiles

### 5.1 Latency-Optimized Profile

**Use Case:** Trading desks, real-time fraud detection

```bash
java \
  --enable-preview \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms8g -Xmx8g \
  -XX:SoftMaxHeapSize=7g \
  -Djdk.virtualThreadScheduler.parallelism=16 \
  -XX:+AlwaysPreTouch \
  -XX:ReservedCodeCacheSize=512m \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseTransparentHugePages \
  -jar your-service.jar
```

**Characteristics:**
- P99 latency: <1 ms
- GC pauses: <1 ms
- Heap: Fixed (no resize)
- Throughput: Medium

### 5.2 Throughput-Optimized Profile

**Use Case:** Batch processing, event pipelines

```bash
java \
  --enable-preview \
  -XX:+UseG1GC \
  -Xms4g -Xmx16g \
  -XX:G1HeapRegionSize=32m \
  -XX:MaxGCPauseMillis=50 \
  -Djdk.virtualThreadScheduler.parallelism=0 \
  -XX:+UseStringDeduplication \
  -XX:+OptimizeStringConcat \
  -jar your-service.jar
```

**Characteristics:**
- P99 latency: <50 ms
- GC pauses: <50 ms
- Heap: Dynamic (4-16 GB)
- Throughput: High

### 5.3 Memory-Constrained Profile

**Use Case:** Containerized, sidecar deployments

```bash
java \
  --enable-preview \
  -XX:+UseSerialGC \
  -Xms256m -Xmx512m \
  -Djdk.virtualThreadScheduler.parallelism=2 \
  -XX:TieredStopAtLevel=1 \
  -XX:ReservedCodeCacheSize=64m \
  -XX:MaxRAMPercentage=75.0 \
  -jar your-service.jar
```

**Characteristics:**
- P99 latency: <500 ms
- GC pauses: <100 ms
- Heap: Fixed (512 MB)
- Throughput: Low

---

## 6. Monitoring and Diagnostics

### 6.1 JVM Flags for Monitoring

```bash
# Enable GC logging
-Xlog:gc*:file=/var/log/jotp/gc-%t.log:time,uptime,level,tags:filecount=7,filesize=100m

# Enable JFR
-XX:+FlightRecorder
-XX:StartFlightRecording=duration=60s,filename=jotp-profile.jfr,settings=profile

# Enable heap dumps on OOM
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/jotp/heapdump.hprof

# Enable class sharing
-Xshare:off  # Disabled for preview features
```

### 6.2 Key JVM Metrics

**GC Metrics:**
```bash
# Monitor GC pause times
jstat -gc <pid> 1000

# Look for:
# - FGC (Full GC count): Should be 0 for ZGC, rare for G1GC
# - FGCT (Full GC time): Should be <1s per hour
# - GCT (Total GC time): Should be <5% of uptime
```

**Heap Metrics:**
```bash
# Monitor heap usage
jcmd <pid> GC.heap_info

# Look for:
# - Used heap: Should be <80% of max
# - Old Gen usage: Should be <70% of max
# - Metaspace usage: Should be stable
```

**Thread Metrics:**
```bash
# Monitor thread counts
jcmd <pid> Thread.print

# Look for:
# - Virtual thread count: Should be <10K per 1GB heap
# - Carrier thread count: Should match parallelism
# - Blocked threads: Should be 0
```

---

## 7. Common JVM Issues

### 7.1 Thread Pinning

**Symptom:** High latency despite virtual threads

**Detection:**
```bash
# Enable JFR
java -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s -jar app.jar

# Open in JDK Mission Control
# Look for: jdk.VirtualThreadPinned
```

**Solution:**
```java
// Bad: synchronized blocks pin virtual threads
synchronized (lock) {
    // This pins the virtual thread to carrier thread
}

// Good: Use ReentrantLock
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    // This does NOT pin virtual thread
} finally {
    lock.unlock();
}
```

### 7.2 Native Memory Overhead

**Symptom:** JVM uses more memory than heap size

**Detection:**
```bash
# Check total memory usage
ps -o pid,vsz,rss,comm -p <pid>

# Use NMT (Native Memory Tracking)
java -XX:NativeMemoryTracking=summary -jar app.jar
jcmd <pid> VM.native_memory summary
```

**Solution:**
```bash
# Tune native memory areas
-XX:MaxDirectMemorySize=512m  # Direct buffers
-XX:CompressedClassSpaceSize=256m  # Compressed class pointers
-XX:MaxMetaspaceSize=256m  # Metaspace
```

### 7.3 GC Thrashing

**Symptom:** Continuous GC cycles, low throughput

**Detection:**
```bash
# Monitor GC frequency
jstat -gc <pid> 1000

# Look for:
# - GC interval <1 second
# - Heap usage >90% before GC
# - Little memory reclaimed per GC
```

**Solution:**
```bash
# Increase heap size
-Xmx16g  # Double current size

# Or switch to ZGC (for large heaps)
-XX:+UseZGC -XX:+ZGenerational
```

---

## Appendix A: JVM Flag Reference

### A.1 Essential Flags

| Flag | Description | Default | Recommendation |
|------|-------------|---------|----------------|
| `--enable-preview` | Enable preview features (required) | off | **Always enable** |
| `-XX:+UseZGC` | Use ZGC | off | Enable for >50K processes |
| `-XX:+ZGenerational` | Generational ZGC | off | Enable with ZGC |
| `-XX:+UseG1GC` | Use G1GC | on | Default for <50K processes |
| `-Xms` | Initial heap size | Calculated | Set to `-Xmx` for fixed heap |
| `-Xmx` | Maximum heap size | Calculated | 4× working set |
| `-XX:MaxGCPauseMillis` | G1GC pause target | 200ms | Set to 50ms for latency-sensitive |
| `-XX:SoftMaxHeapSize` | ZGC soft limit | 100% | Set to 90% of `-Xmx` |

### A.2 Diagnostic Flags

| Flag | Description | When to Use |
|------|-------------|-------------|
| `-Xlog:gc*` | GC logging | Always in production |
| `-XX:+FlightRecorder` | Enable JFR | For debugging |
| `-XX:+HeapDumpOnOutOfMemoryError` | Heap dump on OOM | Always in production |
| `-XX:NativeMemoryTracking` | Track native memory | For memory issues |

---

**Document Version:** 1.0.0
**Last Updated:** 2026-03-15
**Related Documents:**
- `/Users/sac/jotp/docs/performance/performance-characteristics.md`
- `/Users/sac/jotp/docs/performance/profiling.md`
- `/Users/sac/jotp/docs/performance/tuning-mailbox.md`
