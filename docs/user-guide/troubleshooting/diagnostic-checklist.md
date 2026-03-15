# Diagnostic Checklist for JOTP Issues

## Table of Contents
- [Quick Diagnosis Flowchart](#quick-diagnosis-flowchart)
- [Step-by-Step Diagnosis Procedures](#step-by-step-diagnosis-procedures)
- [Log File Analysis](#log-file-analysis)
- [Thread Dump Analysis](#thread-dump-analysis)
- [Heap Dump Analysis](#heap-dump-analysis)
- [Health Check Commands](#health-check-commands)

---

## Quick Diagnosis Flowchart

### Issue Category Selector

```
START
  │
  ├─ Build fails? ──────────────→ [Build Issues](#build-issues-checklist)
  │
  ├─ Process won't start? ───────→ [Runtime Issues](#runtime-issues-checklist)
  │
  ├─ Messages not delivered? ────→ [Messaging Issues](#messaging-issues-checklist)
  │
  ├─ Process crashed? ───────────→ [Supervision Issues](#supervision-issues-checklist)
  │
  ├─ System slow? ───────────────→ [Performance Issues](#performance-issues-checklist)
  │
  ├─ Tests failing? ─────────────→ [Testing Issues](#testing-issues-checklist)
  │
  └─ Unknown issue? ─────────────→ [General Diagnosis](#general-diagnosis-checklist)
```

---

## Step-by-Step Diagnosis Procedures

### Build Issues Checklist

#### Symptom: Compilation fails
- [ ] **Check Java version**
  ```bash
  java -version
  # Must be Java 26
  ```

- [ ] **Verify preview features enabled**
  ```bash
  grep -r "enable-preview" pom.xml .mvn/
  ```

- [ ] **Check Maven version**
  ```bash
  mvn -version
  # Must be Maven 4.0+
  ```

- [ ] **Clean build**
  ```bash
  mvnd clean compile
  ```

- [ ] **Check for dependency conflicts**
  ```bash
  mvnd dependency:tree -Dverbose
  ```

- [ ] **Verify module descriptor**
  ```bash
  cat src/main/java/module-info.java
  ```

- [ ] **Apply Spotless formatting**
  ```bash
  mvnd spotless:apply
  ```

**Next steps:** See [Build Issues Guide](/Users/sac/jotp/docs/troubleshooting/build-issues.md)

---

### Runtime Issues Checklist

#### Symptom: Process won't start
- [ ] **Check process state**
  ```java
  System.out.println("State: " + proc.state());
  System.out.println("Alive: " + proc.isAlive());
  ```

- [ ] **Verify ProcRegistry**
  ```java
  Optional<Proc<?,?>> proc = ProcRegistry.lookup("my-process");
  System.out.println("Found: " + proc.isPresent());
  ```

- [ ] **Enable debug logging**
  ```java
  DebugOptions options = DebugOptions.builder()
      .traceAll(true)
      .build();
  ```

- [ ] **Check for exceptions**
  ```java
  try {
      Proc<MyState, MyMsg> proc = Proc.spawn(spec);
  } catch (Exception e) {
      e.printStackTrace();
  }
  ```

- [ ] **Verify virtual thread support**
  ```bash
  java --version
  # Must be 21+ (19-20 with preview)
  ```

#### Symptom: Messages not delivered
- [ ] **Verify receiver is alive**
  ```java
  if (!receiver.isAlive()) {
      System.err.println("Receiver is dead!");
  }
  ```

- [ ] **Check mailbox size**
  ```java
  ProcInspection inspect = ProcSys.inspect(receiver.self());
  System.out.println("Mailbox: " + inspect.mailboxSize());
  ```

- [ ] **Enable message tracing**
  ```java
  DebugOptions.builder().traceMessages(true).build();
  ```

- [ ] **Verify message types**
  ```java
  // Check if message implements correct sealed interface
  if (!(msg instanceof MyMsg)) {
      System.err.println("Wrong message type!");
  }
  ```

- [ ] **Check for blocking handlers**
  ```java
  // Add timing to detect slow handlers
  long start = System.nanoTime();
  handle(msg);
  long duration = System.nanoTime() - start;
  if (duration > 1_000_000) {
      System.err.println("Slow handler: " + duration + "ns");
  }
  ```

**Next steps:** See [Runtime Issues Guide](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md)

---

### Messaging Issues Checklist

#### Symptom: Message latency
- [ ] **Measure handler execution time**
  ```java
  Instant start = Instant.now();
  handle(msg);
  Duration elapsed = Duration.between(start, Instant.now());
  if (elapsed.toMillis() > 100) {
      log.warn("Slow handler: {}ms", elapsed.toMillis());
  }
  ```

- [ ] **Check mailbox overflow**
  ```java
  ProcInspection inspect = ProcSys.inspect(proc.self());
  double usage = (double) inspect.mailboxSize() / inspect.mailboxCapacity();
  if (usage > 0.8) {
      log.warn("Mailbox {}% full", (int)(usage * 100));
  }
  ```

- [ ] **Profile with JFR**
  ```bash
  java -XX:StartFlightRecording=filename=recording.jfr,duration=60s ...
  ```

- [ ] **Check virtual thread pinning**
  ```bash
  java -Djdk.tracePinnedThreads=full ...
  ```

#### Symptom: Message loss
- [ ] **Enable message tracing**
  ```java
  DebugOptions.builder()
      .traceMessages(true)
      .logDelivery(true)
      .build();
  ```

- [ ] **Check for dropped messages**
  ```java
  AtomicInteger sent = new AtomicInteger(0);
  AtomicInteger received = new AtomicInteger(0);
  // Compare counts periodically
  ```

- [ ] **Verify process references**
  ```java
  // Check if referencing old process after restart
  ProcRef<MyState, MyMsg> ref = ProcRegistry.lookup("my-proc")
      .orElseThrow();
  ref.send(new MyMsg());
  ```

**Next steps:** See [Performance Issues Guide](/Users/sac/jotp/docs/troubleshooting/performance-issues.md)

---

### Supervision Issues Checklist

#### Symptom: Children not restarting
- [ ] **Check supervisor state**
  ```java
  SupervisorRef ref = supervisor.ref();
  System.out.println("State: " + ref.state());
  System.out.println("Strategy: " + ref.strategy());
  ```

- [ ] **Verify child specs**
  ```java
  ref.children().forEach((id, info) -> {
      System.out.println("Child " + id + ":");
      System.out.println("  State: " + info.state());
      System.out.println("  Restarts: " + info.restartCount());
      System.out.println("  Type: " + info.restartType());
  });
  ```

- [ ] **Check restart intensity**
  ```java
  if (supervisor.isMaxIntensityReached()) {
      System.err.println("Max restart intensity exceeded!");
  }
  ```

- [ ] **Enable supervisor logging**
  ```java
  supervisor.addListener(new SupervisorListener() {
      @Override
      public void childTerminated(String id, ExitReason reason) {
          log.error("Child {} terminated: {}", id, reason);
      }

      @Override
      public void childRestarted(String id, int restartCount) {
          log.info("Child {} restarted (#{})", id, restartCount);
      }
  });
  ```

#### Symptom: Supervisor crashes
- [ ] **Check for unhandled exceptions**
  ```java
  supervisor.addListener(new SupervisorListener() {
      @Override
      public void supervisorCrashed(Exception e) {
          log.error("Supervisor crashed!", e);
      }
  });
  ```

- [ ] **Verify child factories**
  ```java
  // Ensure factory creates new instances
  ChildSpec spec = ChildSpec.builder()
      .factory(() -> new MyProc())  // New instance each time
      .build();
  ```

- [ ] **Check for circular dependencies**
  ```java
  // Ensure supervisor doesn't monitor itself
  // or create circular supervision trees
  ```

**Next steps:** See [Supervision Issues Guide](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md)

---

### Performance Issues Checklist

#### Symptom: High latency
- [ ] **Profile application**
  ```bash
  # Java Flight Recorder
  java -XX:StartFlightRecording=filename=rec.jfr,duration=60s ...

  # VisualVM
  jvisualvm
  ```

- [ ] **Check GC pressure**
  ```bash
  # Enable GC logging
  -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10m
  ```

- [ ] **Measure throughput**
  ```java
  AtomicLong messagesProcessed = new AtomicLong(0);
  Instant start = Instant.now();

  // Periodically log rate
  long count = messagesProcessed.get();
  Duration elapsed = Duration.between(start, Instant.now());
  double rate = count / (double) elapsed.toSeconds();
  log.info("Throughput: {} msg/s", rate);
  ```

- [ ] **Check thread counts**
  ```bash
  # Monitor virtual thread usage
  jcmd <pid> Thread.vthread_summary
  ```

#### Symptom: Memory leaks
- [ ] **Take heap dump**
  ```bash
  jmap -dump:format=b,file=heap.hprof <pid>
  ```

- [ ] **Analyze with VisualVM**
  ```bash
  visualvm -J-Xmx2g heap.hprof
  ```

- [ ] **Check for unreleased processes**
  ```java
  Set<ProcId> allProcesses = ProcSys.allProcesses();
  System.out.println("Total processes: " + allProcesses.size());

  allProcesses.forEach(pid -> {
      ProcInspection inspect = ProcSys.inspect(pid);
      System.out.println(pid + ": " + inspect.state());
  });
  ```

- [ ] **Monitor message queues**
  ```java
  // Check for growing mailboxes
  ProcSys.allProcesses().forEach(pid -> {
      ProcInspection inspect = ProcSys.inspect(pid);
      int size = inspect.mailboxSize();
      if (size > 1000) {
          log.warn("Large mailbox {}: {}", pid, size);
      }
  });
  ```

**Next steps:** See [Performance Issues Guide](/Users/sac/jotp/docs/troubleshooting/performance-issues.md)

---

### Testing Issues Checklist

#### Symptom: Flaky tests
- [ ] **Add proper waits**
  ```java
  await()
      .atMost(5, TimeUnit.SECONDS)
      .pollInterval(100, TimeUnit.MILLISECONDS)
      .untilAsserted(() -> {
          assertEquals(expected, actual);
      });
  ```

- [ ] **Use test barriers**
  ```java
  CountDownLatch barrier = new CountDownLatch(1);
  // In process: barrier.countDown()
  // In test: assertTrue(barrier.await(5, TimeUnit.SECONDS));
  ```

- [ ] **Check for race conditions**
  ```bash
  # Run test multiple times
  mvnd test -Dtest=FlakyTest -Dit.test.repeat=10
  ```

- [ ] **Verify process cleanup**
  ```java
  @AfterEach
  void cleanup() {
      ProcSys.allProcesses().forEach(pid -> {
          Proc<?> proc = ProcRegistry.lookup(pid.toString()).orElseThrow();
          proc.shutdown();
      });
  }
  ```

#### Symptom: Test timeouts
- [ ] **Increase timeout**
  ```java
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void testSlowOperation() {
      // ...
  }
  ```

- [ ] **Check for blocking operations**
  ```java
  // Don't use Thread.sleep() in tests
  // Use Awaitility instead
  await().until(() -> conditionMet());
  ```

- [ ] **Verify async completion**
  ```java
  // Ensure async operations complete before assertions
  CompletableFuture<Void> future = asyncOperation();
  future.get(5, TimeUnit.SECONDS);
  ```

**Next steps:** See [Testing Issues Guide](/Users/sac/jotp/docs/troubleshooting/testing-issues.md)

---

### General Diagnosis Checklist

#### Step 1: Enable Debugging
- [ ] **Enable global debug tracing**
  ```java
  DebugOptions options = DebugOptions.builder()
      .traceAll(true)
      .build();
  Proc.setGlobalDebugOptions(options);
  ```

- [ ] **Enable JVM debug logging**
  ```bash
  -Djdk.tracePinnedThreads=full
  -Djava.util.logging.config.file=logging.properties
  ```

#### Step 2: Collect Information
- [ ] **Take thread dump**
  ```bash
  jcmd <pid> Thread.print_to_file -file=thread-dump.txt
  ```

- [ ] **Take heap dump**
  ```bash
  jmap -dump:format=b,file=heap.hprof <pid>
  ```

- [ ] **Collect logs**
  ```bash
  # Gather application logs
  # Gather JOTP debug logs
  # Gather JVM logs
  ```

- [ ] **Record JFR**
  ```bash
  java -XX:StartFlightRecording=filename=recording.jfr,duration=60s ...
  ```

#### Step 3: Analyze Data
- [ ] **Review thread dump**
  - Look for deadlocks
  - Check for blocked threads
  - Identify pinned virtual threads

- [ ] **Review heap dump**
  - Check for memory leaks
  - Identify large objects
  - Analyze process count

- [ ] **Review logs**
  - Search for errors
  - Check for patterns
  - Note timestamps

#### Step 4: Form Hypothesis
- [ ] **Identify symptoms**
- [ ] **Correlate events**
- [ ] **Check for recent changes**
- [ ] **Review configuration**

#### Step 5: Test Hypothesis
- [ ] **Create minimal reproducer**
- [ ] **Test fix in isolation**
- [ ] **Verify with metrics**
- [ ] **Document findings**

---

## Log File Analysis

### Collecting Logs

#### Application Logs
```bash
# If using Logback/Log4j
tail -f logs/application.log

# Search for errors
grep -i "error" logs/application.log

# Search for specific process
grep "my-process" logs/application.log
```

#### JOTP Debug Logs
```bash
# Enable JOTP debug logging
-Djotp.debug.enabled=true
-Djotp.debug.level=FINEST

# Collect debug output
grep "jotp.debug" logs/debug.log
```

#### JVM Logs
```bash
# GC logs
-Xlog:gc*:file=gc.log

# JIT compilation
-XX:+LogCompilation

# Flight Recorder
-XX:StartFlightRecording=filename=rec.jfr
```

### Analyzing Logs

#### Search for Errors
```bash
# Find all errors
grep -i "error" logs/*.log

# Find exceptions
grep -A 10 "exception" logs/*.log

# Find specific error codes
grep "E[0-9]\{4\}" logs/*.log
```

#### Timeline Analysis
```bash
# Extract timestamps
grep "^[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}" logs/*.log | sort

# Find events around specific time
grep "2024-01-15 10:23:" logs/*.log

# Calculate time between events
awk '/Event A/ {start=$1} /Event B/ {print $1-start}' logs/*.log
```

#### Pattern Detection
```bash
# Count error types
grep -i "error" logs/*.log | awk '{print $NF}' | sort | uniq -c

# Find repeated patterns
awk '{print $0}' logs/*.log | sort | uniq -c | sort -rn | head -20

# Correlate events
grep "Event A" logs/*.log | while read line; do
    grep "$(echo $line | awk '{print $1}')" logs/*.log | grep "Event B"
done
```

---

## Thread Dump Analysis

### Taking Thread Dumps

#### Method 1: jcmd
```bash
jcmd <pid> Thread.print_to_file -file=thread-dump.txt
```

#### Method 2: jstack
```bash
jstack <pid> > thread-dump.txt
```

#### Method 3: HotSpot VM
```bash
# Send QUIT signal
kill -QUIT <pid>

# Or on Windows: Ctrl+Break (if console)
```

### Analyzing Thread Dumps

#### Check for Deadlocks
```bash
# Look for "Found one Java-level deadlock"
grep -A 20 "deadlock" thread-dump.txt
```

#### Identify Blocked Threads
```bash
# Find blocked state
grep -A 5 "BLOCKED" thread-dump.txt

# Find waiting state
grep -A 5 "WAITING" thread-dump.txt
```

#### Check Virtual Threads
```bash
# Find pinned virtual threads
grep "pinned" thread-dump.txt

# Count virtual threads
grep "VirtualThread" thread-dump.txt | wc -l
```

#### Thread State Analysis
```bash
# Count thread states
grep "java.lang.Thread.State" thread-dump.txt | \
    sed 's/.*State: //' | \
    sort | uniq -c

# Find long-running threads
grep -A 10 "RUNNABLE" thread-dump.txt | \
    grep -B 5 "proc.handle" | \
    head -50
```

### Common Thread Dump Patterns

#### Pattern: Deadlock
```
Found one Java-level deadlock:
=============================
"Thread-1":
  waiting to lock Monitor@0x12345678 (Object@0xabcdef)
  which is held by "Thread-2"

"Thread-2":
  waiting to lock Monitor@0x87654321 (Object@0xfedcba)
  which is held by "Thread-1"
```

**Solution:** Break circular wait, use consistent lock ordering

#### Pattern: Mailbox Blocked
```
"VirtualThread-1":
  at Proc.waitForMessage(Proc.java:123)
  - waiting on Monitor@0x12345678
```

**Solution:** Check if producer is sending messages

#### Pattern: Handler Blocked
```
"VirtualThread-2":
  at MyHandler.handle(MyHandler.java:45)
  - blocked on java.util.concurrent.locks.ReentrantLock
```

**Solution:** Avoid blocking in handlers, use async patterns

---

## Heap Dump Analysis

### Taking Heap Dumps

#### Method 1: jmap
```bash
jmap -dump:format=b,file=heap.hprof <pid>
```

#### Method 2: jcmd
```bash
jcmd <pid> GC.heap_dump file=heap.hprof
```

#### Method 3: Automatic on OOM
```bash
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/path/to/dumps/
```

### Analyzing Heap Dumps

#### Using VisualVM
```bash
# Open heap dump
visualvm -J-Xmx4g heap.hprof

# Navigate:
# 1. Classes tab - Find largest classes
# 2. Objects tab - Find largest objects
# 3. References - Track object retention
```

#### Using Eclipse MAT
```bash
# Open heap dump
MemoryAnalyzer -vmargs -Xmx4g -jar heap.hprof

# Run Leak Suspects Report
# Run Dominator Tree
# Run Histogram
```

#### Key Metrics to Check
```bash
# Total heap size
# Total number of objects
# Number of Proc instances
# Number of mailbox queues
# Largest object sizes
```

### Common Heap Issues

#### Issue: Too Many Processes
```bash
# In VisualVM/MAT:
# 1. Search for Proc instances
# 2. Check if old processes are retained
# 3. Verify supervisor cleanup
```

**Solution:** Ensure proper process termination, use supervision

#### Issue: Growing Mailboxes
```bash
# 1. Find mailbox queues
# 2. Check queue sizes
# 3. Identify slow consumers
```

**Solution:** Implement backpressure, optimize handlers

#### Issue: Memory Leak in Messages
```bash
# 1. Search for message instances
# 2. Check for retained messages
# 3. Verify message cleanup
```

**Solution:** Use message batching, implement TTL

---

## Health Check Commands

### System Health

#### Check Process Count
```java
Set<ProcId> allProcesses = ProcSys.allProcesses();
System.out.println("Total processes: " + allProcesses.size());
```

#### Check Process States
```java
Map<ProcState, Long> counts = ProcSys.allProcesses().stream()
    .map(ProcSys::inspect)
    .collect(Collectors.groupingBy(
        ProcInspection::state,
        Collectors.counting()
    ));
System.out.println("Process states: " + counts);
```

#### Check Mailbox Sizes
```java
ProcSys.allProcesses().forEach(pid -> {
    ProcInspection inspect = ProcSys.inspect(pid);
    int size = inspect.mailboxSize();
    if (size > 1000) {
        log.warn("Large mailbox {}: {}", pid, size);
    }
});
```

### JVM Health

#### Memory Usage
```bash
# Check heap usage
jcmd <pid> GC.heap_info

# Check memory pools
jcmd <pid> VM.native_memory summary

# Check class loading
jcmd <pid> VM.classloader_stats
```

#### Thread Usage
```bash
# Check thread count
jcmd <pid> Thread.print

# Check virtual threads
jcmd <pid> Thread.vthread_summary

# Check thread contention
jcmd <pid> Thread.print | grep -A 5 "blocked"
```

#### GC Activity
```bash
# Check GC stats
jstat -gcutil <pid> 1000 10

# Check GC heap
jstat -gc <pid> 1000 10
```

### Application Health

#### Throughput Metrics
```java
AtomicLong messagesProcessed = new AtomicLong(0);
AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

// Periodically log
double rate = messagesProcessed.get() /
    ((System.currentTimeMillis() - startTime.get()) / 1000.0);
log.info("Throughput: {} msg/s", rate);
```

#### Latency Metrics
```java
// Add timing to handlers
Instant start = Instant.now();
handle(msg);
Duration elapsed = Duration.between(start, Instant.now());
histogram.record(elapsed.toMillis());
```

#### Error Rates
```java
AtomicLong errors = new AtomicLong(0);
AtomicLong total = new AtomicLong(0);

double errorRate = (double) errors.get() / total.get();
if (errorRate > 0.05) {  // 5% error rate
    log.error("High error rate: {}", errorRate);
}
```

---

## Quick Reference Commands

### Bash Commands
```bash
# Thread dump
jcmd <pid> Thread.print_to_file -file=threads.txt

# Heap dump
jmap -dump:format=b,file=heap.hprof <pid>

# Process info
jps -l
jinfo <pid>

# GC stats
jstat -gcutil <pid> 1000 10

# Virtual threads
jcmd <pid> Thread.vthread_summary

# Enable pinning warnings
java -Djdk.tracePinnedThreads=full ...

# Flight recording
java -XX:StartFlightRecording=filename=rec.jfr,duration=60s ...
```

### Java Code
```java
// Enable debug tracing
DebugOptions options = DebugOptions.builder()
    .traceAll(true)
    .build();
Proc.setGlobalDebugOptions(options);

// Inspect process
ProcInspection inspect = ProcSys.inspect(procId);

// Check all processes
Set<ProcId> all = ProcSys.allProcesses();

// Supervisor info
SupervisorRef ref = supervisor.ref();
ref.children().forEach((id, info) -> {
    System.out.println(id + ": " + info.state());
});

// Take thread dump programmatically
Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();

// Heap histogram
jcmd <pid> GC.class_histogram | head -50
```

---

## Diagnostic Templates

### Issue Report Template
```markdown
## Issue Description
[Brief description of the problem]

## Symptoms
- [ ] Process won't start
- [ ] Messages not delivered
- [ ] High latency
- [ ] Process crashes
- [ ] Other: ______

## Environment
- Java version: ______
- Maven version: ______
- JOTP version: ______
- OS: ______

## Steps to Reproduce
1.
2.
3.

## Expected Behavior
______

## Actual Behavior
______

## Logs
[Attach relevant logs]

## Diagnostics Performed
- [ ] Thread dump collected
- [ ] Heap dump collected
- [ ] Debug tracing enabled
- [ ] Metrics collected

## Additional Context
[Any other relevant information]
```

### Health Check Script Template
```bash
#!/bin/bash
# health-check.sh

PID=$1

echo "=== JOTP Health Check ==="
echo "PID: $PID"
echo ""

echo "=== Process Count ==="
# Count processes (requires JMX or custom endpoint)
echo ""

echo "=== JVM Memory ==="
jcmd $PID GC.heap_info
echo ""

echo "=== Thread Count ==="
jcmd $PID Thread.print | grep "java.lang.Thread.State" | wc -l
echo ""

echo "=== Virtual Threads ==="
jcmd $PID Thread.vthread_summary
echo ""

echo "=== GC Activity ==="
jstat -gcutil $PID 1000 5
echo ""

echo "=== Health Check Complete ==="
```

---

## Related Resources
- **Build Issues:** See `build-issues.md`
- **Runtime Issues:** See `runtime-issues.md`
- **Supervision Issues:** See `supervision-issues.md`
- **Performance Issues:** See `performance-issues.md`
- **Testing Issues:** See `testing-issues.md`
- **Debugging Techniques:** See `debugging-techniques.md`
- **Common Errors:** See `common-errors.md`
