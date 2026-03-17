# JOTP Chaos Test Execution Plan

**Status**: Ready for execution (pending compilation fix)
**Estimated Duration**: 45 minutes
**Test Scope**: 10 chaos test suites, 50+ individual tests

---

## Pre-Execution Checklist

### 1. Environment Setup
- [ ] JDK 26 installed with `--enable-preview` support
- [ ] Maven 4+ or mvnd (Maven Daemon) installed
- [ ] 8GB+ RAM available for virtual thread scaling tests
- [ ] No other heavy processes running (to avoid GC interference)

### 2. Compilation Fixes Required
- [ ] Add `requires jdk.httpserver;` to `module-info.java`
- [ ] Add `requires java.logging;` to `module-info.java`
- [ ] Add `requires java.net.http;` to `module-info.java`
- [ ] Fix `AtomicLong` import in `RocksDBEventLog.java`
- [ ] Fix type inference issues in `ProcRef.java` and `DefaultGlobalProcRegistry.java`

### 3. Test Data Collection
- [ ] Create `/tmp/jotp-chaos-results/` directory
- [ ] Configure logging to capture detailed metrics
- [ ] Set up system resource monitoring (CPU, memory, GC)

---

## Test Execution Commands

### Phase 1: Core Chaos Tests (15 minutes)

```bash
# Test 1: Supervisor restart storms
make test TEST=SupervisorStormStressTest 2>&1 | tee /tmp/supervisor_storm.log

# Test 2: Link cascade propagation
make test TEST=LinkCascadeStressTest 2>&1 | tee /tmp/link_cascade.log

# Test 3: Registry race conditions
make test TEST=RegistryRaceStressTest 2>&1 | tee /tmp/registry_race.log

# Test 4: General chaos patterns
make test TEST=ChaosTest 2>&1 | tee /tmp/chaos_general.log
```

**Expected Results**:
- All tests pass with <30s timeout per test
- Specific breaking points documented in logs
- No test hangs or deadlocks

### Phase 2: Pattern Stress Tests (10 minutes)

```bash
# Test 5: Pattern validation under concurrency
make test TEST=PatternStressTest 2>&1 | tee /tmp/pattern_stress.log
```

**Expected Results**:
- 10K messages delivered with zero loss
- Supervisor handles 100 crashes across 5 children
- No cross-contamination in ScopedValue patterns

### Phase 3: Distributed System Tests (10 minutes)

```bash
# Test 6: Node failure detection
make test TEST=NodeFailureDetectionTest 2>&1 | tee /tmp/node_failure.log

# Test 7: Distributed failover
make test TEST=FailoverControllerTest 2>&1 | tee /tmp/failover.log

# Test 8: Global proc registry under distributed load
make test TEST=GlobalProcRegistryTest 2>&1 | tee /tmp/global_registry.log
```

**Expected Results**:
- Failure detection within 3 missed heartbeats
- Failover completes within 5s
- Registry remains consistent under network partitions

### Phase 4: Integration Stress Tests (10 minutes)

```bash
# Test 9: Integration stress patterns
make test TEST=IntegrationStressTest 2>&1 | tee /tmp/integration_stress.log

# Test 10: Architectural comparison benchmarks
make test TEST=ArchitecturalComparisonTest 2>&1 | tee /tmp/arch_comparison.log
```

**Expected Results**:
- JOTP achieves 150K msg/sec sustained
- Latency p99 remains <5ms under load
- CPU usage stays <25% at saturation

---

## Metrics to Capture

### 1. Process-Level Metrics
```
- Process spawn time (ms)
- Process crash detection time (ms)
- Supervisor restart latency (ms)
- Mailbox queue depth (messages)
- Virtual thread count (active)
```

### 2. System-Level Metrics
```
- Heap usage before/after tests (MB)
- GC pause times (G1 young + old)
- CPU utilization (%)
- Thread count (platform + virtual)
```

### 3. Distributed Metrics
```
- Node failure detection time (ms)
- Failover completion time (ms)
- Message propagation delay (ms)
- Registry consistency checks
```

---

## Breaking Point Validation

### Test 1: Supervisor Restart Limit
**Command**:
```bash
# Run with different maxRestarts values
for restarts in 1 3 5 10; do
  echo "Testing maxRestarts=$restarts"
  make test TEST=SupervisorStormStressTest -DmaxRestarts=$restarts
done
```

**Expected**: Supervisor dies exactly at maxRestarts+1 crashes

### Test 2: Link Cascade Depth
**Command**:
```bash
# Run with increasing chain depths
for depth in 100 200 500 1000; do
  echo "Testing chain depth=$depth"
  make test TEST=LinkCascadeStressTest -DchainDepth=$depth
done
```

**Expected**: Propagation time scales linearly (~10ms per hop)

### Test 3: Registry Stampede
**Command**:
```bash
# Run with increasing concurrent registrations
for threads in 10 50 100 500; do
  echo "Testing $threads concurrent registrations"
  make test TEST=RegistryRaceStressTest -Dcompetitors=$threads
done
```

**Expected**: Exactly 1 winner regardless of competitor count

### Test 4: Memory Pressure
**Command**:
```bash
# Run with increasing allocation sizes
for size in 1MB 5MB 10MB; do
  echo "Testing $size allocations"
  make test TEST=ChaosTest -DallocationSize=$size
done
```

**Expected**: No OutOfMemoryError; heap growth bounded

---

## Success Criteria

### Must Pass (Critical)
- [ ] All supervisor restart boundaries enforced correctly
- [ ] Zero message loss in concurrent crash storms
- [ ] Exactly 1 winner in registration stampedes
- [ ] No phantom registry entries after crash storms
- [ ] No deadlocks in bilateral crash scenarios

### Should Pass (Important)
- [ ] Link cascades complete within timeout for tested depths
- [ ] Supervisor handles sustained crash storms without degradation
- [ ] Node failure detection within threshold
- [ ] Failover completes within 5s
- [ ] Heap growth remains bounded under memory pressure

### Nice to Have (Optimization)
- [ ] Latency p99 <5ms at 150K TPS
- [ ] CPU usage <25% at saturation
- [ ] GC pauses <10ms under load
- [ ] Zero cross-contamination in ScopedValue patterns

---

## Failure Handling

### If Test Times Out (>30s)
1. Check for deadlocks in thread dumps
2. Verify supervisor event loop not blocked
3. Check for unbounded wait conditions
4. Review crash callback execution

### If Test Fails with AssertionError
1. Review assertion message for specific invariant broken
2. Check test logs for crash counts, restart counts
3. Verify expected vs actual values
4. Identify if breaking point different than expected

### If Test Fails with Exception
1. Capture full stack trace
2. Identify root cause (NPE, IllegalStateException, etc.)
3. Check for missing null checks, race conditions
4. Verify proper initialization in @BeforeEach

---

## Post-Execution Analysis

### 1. Collect Results
```bash
# Gather all test logs
cat /tmp/*.log > /tmp/jotp-chaos-full-run.log

# Extract metrics
grep "Breaking point" /tmp/*.log
grep "elapsed=" /tmp/*.log
grep "Propagation time" /tmp/*.log
```

### 2. Generate Report
```bash
# Create summary report
cat > /tmp/chaos-test-summary.md <<EOF
# JOTP Chaos Test Summary

## Test Execution Date: $(date)

## Tests Executed
- SupervisorStormStressTest: $(grep "Tests run" /tmp/supervisor_storm.log | head -1)
- LinkCascadeStressTest: $(grep "Tests run" /tmp/link_cascade.log | head -1)
- RegistryRaceStressTest: $(grep "Tests run" /tmp/registry_race.log | head -1)
- ChaosTest: $(grep "Tests run" /tmp/chaos_general.log | head -1)
- PatternStressTest: $(grep "Tests run" /tmp/pattern_stress.log | head -1)

## Breaking Points Found
$(grep -A2 "Breaking point" /tmp/*.log | sort -u)

## Failures
$(grep -i "failure\|error" /tmp/*.log | grep -v "BUILD FAILURE")
EOF
```

### 3. Compare to Baselines
```bash
# Compare against expected breaking points
echo "Expected Breaking Points:"
echo "- Supervisor: 3 restarts per 2s window"
echo "- Link cascade: 10ms per hop"
echo "- Registry: exactly 1 winner"
echo "- Throughput: 150K msg/sec"

echo ""
echo "Actual Breaking Points:"
grep "Breaking point" /tmp/*.log | sort -u
```

---

## Continuous Integration

### GitHub Actions Workflow
```yaml
name: Chaos Tests

on:
  push:
    branches: [main]
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM

jobs:
  chaos:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 26
        uses: actions/setup-java@v3
        with:
          java-version: '26'
          distribution: 'temurin'
      - name: Run chaos tests
        run: |
          make test TEST=SupervisorStormStressTest
          make test TEST=LinkCascadeStressTest
          make test TEST=RegistryRaceStressTest
          make test TEST=ChaosTest
      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: chaos-test-results
          path: /tmp/*.log
```

---

## Troubleshooting Guide

### Issue: Tests fail to compile
**Fix**: Apply module-info.java patches from compilation errors section

### Issue: Tests timeout at 30s
**Fix**: Increase timeout in `@Timeout(30)` annotation; check for deadlocks

### Issue: Intermittent test failures
**Fix**: Add more robust awaitility timeouts; check for race conditions

### Issue: OutOfMemoryError during tests
**Fix**: Increase Maven heap with `MAVEN_OPTS=-Xmx4g`

### Issue: Virtual thread exhaustion
**Fix**: Increase `jdk.virtualThreadScheduler.parallelism` system property

---

**Next Steps**: Execute tests once compilation issues are resolved, then update CHAOS-ENGINEERING-REPORT.md with actual measured breaking points.
