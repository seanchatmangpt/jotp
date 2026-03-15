# JOTP Baseline Performance Benchmark Execution Report

**Date**: March 14, 2026
**Java Version**: Oracle GraalVM 26-dev+13.1
**JVM**: Java HotSpot(TM) 64-Bit Server VM (build 26+13-jvmci-b01, mixed mode, sharing)
**Status**: ❌ FAILED - Compilation errors prevent benchmark execution

---

## Executive Summary

**Objective**: Execute baseline performance benchmarks with Java 26 to establish reference metrics for validating observability overhead claims.

**Result**: Benchmark execution blocked by critical compilation errors in the JOTP codebase.

**Root Cause**: Missing OpenTelemetry dependencies, incomplete feature implementations, and cross-package dependencies causing build failures.

**Impact**: Unable to measure critical metrics:
- Proc.tell() baseline latency (target: <100ns)
- Framework overhead baseline (target: <1%)
- Memory allocation baseline
- Throughput baseline (target: >1M ops/sec)

---

## Environment Details

### Java 26 Installation
```
Installation: /Users/sac/.sdkman/candidates/java/26.ea.13-graal
Version: java version "26" 2026-03-17
Runtime: Java(TM) SE Runtime Environment Oracle GraalVM 26-dev+13.1
VM: Java HotSpot(TM) 64-Bit Server VM Oracle GraalVM 26-dev+13.1
Features: --enable-preview, Virtual Threads, Structured Concurrency
```

### Command Executed
```bash
JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal \
./mvnw clean test -Dtest=BaselinePerformanceBenchmark -Pbenchmark
```

---

## Compilation Errors Summary

### Critical Error Categories

#### 1. Missing OpenTelemetry Dependencies (100+ errors)
**Affected Files**:
- `io.github.seanchatmangpt.jotp.observability.otel.OpenTelemetryService.java`
- `io.github.seanchatmangpt.jotp.observability.otel.DistributedTracerBridge.java`

**Missing Classes**:
- `io.opentelemetry.api.trace.SpanKind`
- `io.opentelemetry.api.trace.Context`
- `io.opentelemetry.api.trace.Tracer`
- `io.opentelemetry.api.trace.StatusCode`
- `io.opentelemetry.api.trace.Span`
- `io.opentelemetry.context.Context`

**Solution**: Add to pom.xml:
```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.38.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>1.38.0</version>
</dependency>
```

#### 2. Missing Internal Classes (50+ errors)
**Affected Files**:
- `io.github.seanchatmangpt.jotp.reactive.EnhancedEventBus.java`
- `io.github.seanchatmangpt.jotp.messaging.Messaging.java`
- `io.github.seanchatmangpt.jotp.reactve.MigrationGuide.java`

**Missing Classes**:
- `io.github.seanchatmangpt.jotp.resource.HealthMonitor`
- `io.github.seanchatmangpt.jotp.Behavior`
- `io.github.seanchatmangpt.jotp.reactive.SupervisorMessageDispatcher`
- `io.github.seanchatmangpt.jotp.reactive.EnhancedEventBus` (incomplete)
- `io.github.seanchatmangpt.jotp.reactive.ProcPointToPointChannel`

**Solution**: Complete stub implementations or exclude packages.

#### 3. Type Mismatches (5 errors)
**Affected File**: `io.github.seanchatmangpt.jotp.observability.FrameworkMetrics.java`

**Errors**:
```
Line 223: incompatible types: Map<String,String> cannot be converted to Supplier<Double>
Line 233: incompatible types: Map<String,String> cannot be converted to Supplier<Double>
Line 240: incompatible types: Map<String,String> cannot be converted to Supplier<Double>
Line 266: incompatible types: Map<String,String> cannot be converted to Supplier<Double>
Line 273: incompatible types: Throwable cannot be converted to String
```

**Solution**: Fix metric registration API calls.

#### 4. Missing Command Implementations (5 errors)
**Affected File**: `io.github.seanchatmangpt.jotp.messaging.system.ControlBus.java`

**Missing Classes**:
- `ControlBus.ControlCommand.Suspend`
- `ControlBus.ControlCommand.Resume`
- `ControlBus.ControlCommand.GetStats`
- `ControlBus.ControlCommand.Reset`

---

## Files Modified During Attempt

### Fixed Compilation Errors
1. **FactoryMethodPatterns.java**
   - Fixed malformed JavaDoc code example (lines 330-336)
   - Added semicolons to switch expressions (lines 361-364)

2. **MessageBus.java**
   - Implemented `DeadLetterHandler.log()` method body (lines 425-428)

3. **Proc.java**
   - Removed extra closing brace (line 669)

4. **TestSupervisor.java**
   - Fixed lambda syntax in result.fold() (line 231)

5. **module-info.java**
   - Removed export of empty `dogfood.reactive` package (line 119)

6. **pom.xml**
   - Added default values for GPG properties:
     ```xml
     <gpg.keyname></gpg.keyname>
     <gpg.passphrase></gpg.passphrase>
     <central.token></central.token>
     ```

### Files Created
1. **SimpleBaselineBenchmark.java** (`src/test/java/io/github/seanchatmangpt/jotp/benchmark/`)
   - Simplified benchmark with 5 core tests
   - Avoids dependencies on problematic classes

2. **run-benchmark.sh**
   - Alternative standalone benchmark runner

---

## Benchmark Test Structure

### BaselinePerformanceBenchmark.java
**Location**: `src/test/java/io/github/seanchatmangpt/jotp/benchmark/BaselinePerformanceBenchmark.java`

**Configuration**:
- Warmup: 5 iterations × 1 second
- Measurement: 10 iterations × 1 second
- Forks: 3
- Mode: AverageTime
- Output Unit: Nanoseconds

**Test Methods** (10 benchmarks):
1. `procTellBaseline` - Single-threaded message passing (HOT PATH)
2. `procTellConcurrentBaseline` - 10-thread concurrent load
3. `frameworkOverheadSpawn` - Process creation overhead
4. `frameworkOverheadStateMachine` - State transition overhead
5. `frameworkOverheadSupervisor` - Supervisor operations
6. `memoryAllocationBaseline` - Message allocation rate (1000 ops)
7. `memoryAllocationMessageOnly` - Object allocation isolation
8. `memoryAllocationProcessLifecycle` - Full lifecycle cost
9. `throughputBatchMessaging` - Sustained throughput (100 ops)
10. `controlLoopOverhead` - JVM/JMH baseline

### SimpleBaselineBenchmark.java (Fallback)
**Reduced Test Set** (5 benchmarks only):
- `procTellBaseline`
- `procTellConcurrentBaseline`
- `memoryAllocationBaseline`
- `throughputBatchMessaging`
- `controlLoopOverhead`

---

## Expected Baseline Metrics (Design Targets)

### Hot Path Performance
| Metric | Target | Purpose |
|--------|--------|---------|
| **Proc.tell() latency** | <100 ns | Hot path message passing |
| **Observability overhead** | <1% | Framework overhead validation |
| **Fast path overhead** | <100 ns | Boundary crossing cost |

### Framework Overhead
| Component | Expected Range |
|-----------|----------------|
| **Proc.spawn()** | 1-10 μs (virtual thread) |
| **State transition** | 50-200 ns |
| **Supervisor operations** | 100-500 ns |
| **Process lifecycle** | 1-5 ms |

### Memory Allocation
| Operation | Expected Rate |
|-----------|---------------|
| **Message passing** | <100 bytes per message |
| **Process creation** | ~1 KB per process |
| **State transition** | ~0 bytes (pure functional) |

### Throughput Targets
| Scenario | Target |
|----------|--------|
| **Single-threaded messaging** | >10M msg/sec |
| **10-thread contention** | >50M msg/sec |
| **Batch processing** | >1M msg/sec sustained |

---

## Resolution Options

### Option A: Fix All Compilation Issues (Recommended)
**Effort**: 2-4 hours

**Steps**:
1. Add OpenTelemetry dependencies to pom.xml
2. Create stub implementations for missing classes
3. Fix type mismatches in FrameworkMetrics
4. Complete Command implementations in ControlBus
5. Resolve cross-package dependencies

**Advantage**: Complete codebase, all benchmarks runnable

### Option B: Exclude Problematic Packages
**Effort**: 30 minutes

**Steps**:
1. Add to pom.xml compiler excludes:
   ```xml
   <exclude>**/observability/**</exclude>
   <exclude>**/reactive/**</exclude>
   <exclude>**/messaging/**</exclude>
   <exclude>**/enterprise/**</exclude>
   ```
2. Remove cross-references from core code
3. Run SimpleBaselineBenchmark only

**Advantage**: Faster, core primitives only

### Option C: Use Existing Benchmark Data
**Status**: Already available in `/Users/sac/jotp/benchmark-results/`

**Available Results**:
- `jmh-results.json` - Framework metrics WITH observability
- `baseline-results.md` - Expected baselines and test structure
- `precision-results.md` - JMH precision benchmarks
- `throughput-results.md` - Throughput analysis

**Limitation**: No true baseline (without observability) for comparison

---

## POM Configuration Issues

### GPG Property Circular References
**Problem**: Properties self-referencing
```xml
<gpg.keyname>${gpg.keyname}</gpg.keyname>
<gpg.passphrase>${gpg.passphrase}</gpg.passphrase>
```

**Fix Applied**:
```xml
<gpg.keyname></gpg.keyname>
<gpg.passphrase></gpg.passphrase>
<central.token></central.token>
```

### Existing Exclusions (Already in POM)
```xml
<exclude>**/BulkheadIsolation.java</exclude>
<exclude>**/DistributedSagaCoordinator.java</exclude>
<exclude>**/EventSourcingAuditLog.java</exclude>
<exclude>**/examples/**</exclude>
<exclude>**/enterprise/**</exclude>
<exclude>**/messagepatterns/**</exclude>
```

**Issue**: Active code still references excluded classes.

---

## Recommendations

### Immediate Actions (Priority Order)

1. **Add OpenTelemetry Dependencies**
   ```bash
   ./mvnw dependency:add -Dartifact=io.opentelemetry:opentelemetry-api:1.38.0
   ```

2. **Create Stub Implementations**
   - `HealthMonitor` class (empty implementation)
   - `Behavior` interface (marker interface)
   - `SupervisorMessageDispatcher` (stub with TODO comments)

3. **Fix FrameworkMetrics Type Errors**
   - Change metric registration calls to use `Supplier<Double>`
   - Fix error message formatting

4. **Run Simplified Benchmark**
   ```bash
   JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal \
   ./mvnw test -Dtest=SimpleBaselineBenchmark -Pbenchmark
   ```

### Long-term Solutions

1. **Modularize Codebase**
   - Separate core primitives from experimental features
   - Use JPMS modules to enforce boundaries
   - Prevent cross-package dependencies

2. **Continuous Integration**
   - Add compilation check to CI/CD
   - Run benchmarks on every commit
   - Track performance over time

3. **Dependency Management**
   - Audit all external dependencies
   - Use dependency management BOM
   - Version pinning for stability

---

## Performance Claims Requiring Validation

Once benchmarks execute, validate these claims:

1. **"<1% hot path overhead"**
   - Measurement: `procTellBaseline` with vs without observability
   - Calculation: `((Observed - Baseline) / Baseline) × 100`
   - Pass criteria: Overhead < 1%

2. **"<100ns fast path overhead"**
   - Measurement: `controlLoopOverhead` subtracted from `procTellBaseline`
   - Pass criteria: Net overhead < 100ns

3. **"Zero contamination of hot code paths"**
   - Measurement: Compare assembly output for hot paths
   - Pass criteria: No observability bytecodes in critical loops

4. **"Virtual thread scalability"**
   - Measurement: Throughput with 1, 10, 100 threads
   - Pass criteria: Linear scaling up to 10 threads

---

## Existing Benchmark Results Reference

### WITH Observability (From jmh-results.json)

**Process Creation Throughput**:
```
15,234.567 ops/sec (±234.567)
Confidence Interval: [14,900, 15,569]
```

**Message Processing Throughput**:
```
28,567.890 ops/sec (±456.789)
Confidence Interval: [28,100, 29,035]
```

**Critical Path Latency**:
```
Average: 456 ns (P50)
P95: 478 ns
P99: 485 ns
```

**Implication**: If baseline is <100ns, observability adds ~350ns (~76% overhead)

---

## Conclusion

**Current Status**: ❌ Baseline benchmarks cannot execute due to compilation errors

**Blocking Issues**:
- Missing OpenTelemetry dependencies (100+ errors)
- Incomplete feature implementations (50+ errors)
- Type mismatches in metric registration (5 errors)

**Path Forward**:
1. **Quick Win**: Run SimpleBaselineBenchmark (30 min effort)
2. **Complete Fix**: Resolve all compilation errors (2-4 hours)
3. **Long-term**: Modularize codebase to prevent future issues

**Value of Execution**:
Once running, benchmarks will provide:
- Definitive Proc.tell() baseline latency (target: <100ns)
- Framework overhead measurement (target: <1%)
- Competitive comparison vs Erlang/OTP and Akka
- Production capacity planning with confidence intervals

**Estimated Time to Resolution**: 2-4 hours development work

---

## Appendix: Commands to Retry

### After Fixing Compilation Errors
```bash
# Full baseline suite
JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal \
./mvnw clean test -Dtest=BaselinePerformanceBenchmark -Pbenchmark

# Simplified benchmark (fallback)
JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal \
./mvnw clean test -Dtest=SimpleBaselineBenchmark -Pbenchmark

# With custom output
JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal \
./mvnw test -Dtest=BaselinePerformanceBenchmark \
  -Djmh.includes=.* \
  -Djmh.output=benchmark-results/baseline-2026-03-14.json
```

### Verify Java 26 Installation
```bash
export JAVA_HOME=/Users/sac/.sdkman/candidates/java/26.ea.13-graal
$JAVA_HOME/bin/java -version
$JAVA_HOME/bin/javac --enable-preview --version
```

---

**Report Generated**: March 14, 2026
**Java Version**: Oracle GraalVM 26-dev+13.1
**JOTP Version**: 1.0.0-Alpha
**Status**: Compilation errors block benchmark execution
