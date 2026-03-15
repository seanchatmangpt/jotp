# JOTP Troubleshooting Guide

Welcome to the comprehensive JOTP troubleshooting guide. This section provides systematic approaches to diagnosing and resolving common issues in JOTP applications.

## Quick Start

### First Steps
1. **Identify the issue category** using the [Diagnostic Checklist](diagnostic-checklist.md)
2. **Enable debug tracing** to gather more information
3. **Follow the systematic diagnosis procedures** for your issue category
4. **Collect diagnostic information** (logs, thread dumps, heap dumps)
5. **Apply recommended solutions** from the relevant guide

### Emergency Quick Fixes

#### Build Won't Compile
```bash
# Clean and rebuild
mvnd clean compile

# Apply formatting
mvnd spotless:apply

# Check Java version (must be 26)
java -version
```

#### Process Won't Start
```java
// Enable debug tracing
DebugOptions options = DebugOptions.builder()
    .traceAll(true)
    .build();
Proc.setGlobalDebugOptions(options);

// Check if process already exists
Optional<Proc<?,?>> existing = ProcRegistry.lookup("my-process");
if (existing.isPresent()) {
    ProcRegistry.unregister("my-process");
}
```

#### Messages Not Delivered
```java
// Verify receiver is alive
if (!receiver.isAlive()) {
    System.err.println("Receiver is dead!");
}

// Check mailbox
ProcInspection inspect = ProcSys.inspect(receiver.self());
System.out.println("Mailbox size: " + inspect.mailboxSize());
```

#### System Slow
```bash
# Take thread dump
jcmd <pid> Thread.print_to_file -file=threads.txt

# Check for pinned threads
grep "pinned" threads.txt

# Profile with JFR
java -XX:StartFlightRecording=filename=rec.jfr,duration=60s ...
```

## Troubleshooting Guides

### Core Guides

#### [1. Build Issues](build-issues.md)
Problems with compilation, Maven, formatting, dependencies, and module system.
- Java 26 preview feature setup
- Maven compilation errors
- Spotless formatting issues
- Dependency conflicts
- Module system problems

**When to use:** Build fails, compilation errors, IDE shows red squiggles

#### [2. Runtime Issues](runtime-issues.md)
Problems with process lifecycle and message handling at runtime.
- Process won't start
- Messages not delivered
- Mailbox overflow
- Deadlock diagnosis
- Virtual thread pinning

**When to use:** Processes crash, messages timeout, system hangs

#### [3. Supervision Issues](supervision-issues.md)
Problems with process supervision and restart behavior.
- Children not restarting
- Supervisor crashes
- Restart intensity exceeded
- Orphaned processes
- Shutdown hangs

**When to use:** Supervision tree not working, processes not restarting

#### [4. Performance Issues](performance-issues.md)
Problems with system performance and resource usage.
- High message latency
- Memory leaks
- GC pressure
- Thread exhaustion
- Slow startups

**When to use:** System slow, high memory usage, poor throughput

#### [5. Testing Issues](testing-issues.md)
Problems with testing JOTP applications.
- Flaky async tests
- Awaitility timeouts
- Mock setup problems
- Test isolation failures

**When to use:** Tests fail intermittently, race conditions

### Reference Guides

#### [6. Debugging Techniques](debugging-techniques.md)
Techniques for debugging JOTP applications.
- Enable debug tracing
- ProcSys introspection
- Message flow tracing
- Supervisor tree inspection
- Breakpoint strategies

**When to use:** Need visibility into system behavior, investigating issues

#### [7. Common Errors](common-errors.md)
Reference for common error messages and their solutions.
- ClassCastException in message handler
- Process already registered
- Timeout exceptions
- Link/monitor confusion
- Mailbox overflow exception
- Process exit exceptions
- State machine errors
- Module system errors

**When to use:** Encountering specific error messages

#### [8. Diagnostic Checklist](diagnostic-checklist.md)
Systematic procedures for diagnosing issues.
- Quick diagnosis flowchart
- Step-by-step diagnosis procedures
- Log file analysis
- Thread dump analysis
- Heap dump analysis
- Health check commands

**When to use:** Starting investigation, need systematic approach

## Diagnostic Quick Reference

### Enable Debugging
```java
// Global debug tracing
DebugOptions options = DebugOptions.builder()
    .traceAll(true)
    .build();
Proc.setGlobalDebugOptions(options);

// Per-process debug
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .debugOptions(DebugOptions.builder()
        .traceMessages(true)
        .traceStateChanges(true)
        .build())
    .build();
```

### Collect Diagnostics
```bash
# Thread dump
jcmd <pid> Thread.print_to_file -file=threads.txt

# Heap dump
jmap -dump:format=b,file=heap.hprof <pid>

# Flight recording
java -XX:StartFlightRecording=filename=rec.jfr,duration=60s ...

# Enable pinned thread warnings
java -Djdk.tracePinnedThreads=full ...
```

### Inspect System
```java
// List all processes
Set<ProcId> all = ProcSys.allProcesses();

// Inspect process
ProcInspection inspect = ProcSys.inspect(procId);
System.out.println("State: " + inspect.state());
System.out.println("Mailbox: " + inspect.mailboxSize());

// Supervisor info
SupervisorRef ref = supervisor.ref();
ref.children().forEach((id, info) -> {
    System.out.println(id + ": " + info.state());
});
```

## Common Issue Patterns

### Pattern: Process Won't Start
1. Check Java version (must be 26)
2. Verify preview features enabled
3. Check for name conflicts in ProcRegistry
4. Enable debug tracing
5. Check for exceptions during spawn

### Pattern: Messages Not Delivered
1. Verify receiver is alive
2. Check mailbox size (not full)
3. Enable message tracing
4. Verify message types match
5. Check for blocking handlers

### Pattern: System Slow
1. Profile with JFR
2. Check for pinned virtual threads
3. Monitor mailbox sizes
4. Measure handler execution time
5. Check GC pressure

### Pattern: Tests Flaky
1. Add proper waits with Awaitility
2. Use test barriers
3. Verify process cleanup
4. Check for race conditions
5. Run tests multiple times

## Getting Help

### Before Asking for Help
1. **Read the relevant guide** for your issue category
2. **Follow the diagnostic checklist** to gather information
3. **Enable debug tracing** and collect logs
4. **Search existing issues** in the repository
5. **Prepare a minimal reproducer**

### Issue Report Template
When reporting issues, include:
- JOTP version
- Java version
- Maven version
- OS and version
- Steps to reproduce
- Expected vs actual behavior
- Relevant logs
- Diagnostic information (thread dumps, heap dumps, etc.)

### Resources
- **GitHub Issues:** [JOTP Issues](https://github.com/seanchatmangpt/jotp/issues)
- **Documentation:** [Main Docs](/Users/sac/jotp/docs/)
- **Examples:** [Example Code](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/examples/)
- **Book:** [JOTP Book](/Users/sac/jotp/docs/book/)

## Prevention Best Practices

### Development
- **Enable debug tracing** during development
- **Use type-safe message protocols** (sealed interfaces)
- **Implement proper error handling** in all handlers
- **Add health checks** for critical processes
- **Write tests** for supervision trees

### Testing
- **Use Awaitility** for async assertions
- **Test with realistic load**
- **Verify process cleanup** in tests
- **Test crash recovery** scenarios
- **Run tests multiple times** to detect flakiness

### Production
- **Monitor process counts** and mailbox sizes
- **Set up alerting** for error rates
- **Collect metrics** on throughput and latency
- **Use structured logging** for analysis
- **Implement circuit breakers** for external calls

## Contributing

Found an issue not covered here? Please:
1. Follow the diagnostic procedures
2. Document your findings
3. Submit a pull request to improve this guide
4. Include code examples where applicable

---

**Last Updated:** 2026-03-15
**JOTP Version:** 1.0.0
**Maintainer:** JOTP Core Team

---

## Index by Symptom

### Build & Compilation
- [Compilation fails with "preview features are not enabled"](/Users/sac/jotp/docs/troubleshooting/build-issues.md#java-26-preview-feature-setup)
- ["cannot find symbol" errors](/Users/sac/jotp/docs/troubleshooting/build-issues.md#maven-compilation-errors)
- [Spotless check failed](/Users/sac/jotp/docs/troubleshooting/build-issues.md#spotless-formatting-issues)
- [Dependency conflicts](/Users/sac/jotp/docs/troubleshooting/build-issues.md#dependency-conflicts)
- [Module system errors](/Users/sac/jotp/docs/troubleshooting/build-issues.md#module-system-problems)

### Runtime
- [Process won't start](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#process-wont-start)
- [Messages not delivered](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#messages-not-delivered)
- [Mailbox overflow](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#mailbox-overflow)
- [Application hangs](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#deadlock-diagnosis)
- [Virtual thread pinning](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#virtual-thread-pinning)

### Supervision
- [Children not restarting](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md#children-not-restarting)
- [Supervisor crashes](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md#supervisor-crashes)
- [Restart intensity exceeded](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md#restart-intensity-exceeded)
- [Orphaned processes](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md#orphaned-processes)
- [Shutdown hangs](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md#shutdown-hangs)

### Performance
- [High message latency](/Users/sac/jotp/docs/troubleshooting/performance-issues.md#high-message-latency)
- [Memory leaks](/Users/sac/jotp/docs/troubleshooting/performance-issues.md#memory-leaks)
- [GC pressure](/Users/sac/jotp/docs/troubleshooting/performance-issues.md#gc-pressure)
- [Thread exhaustion](/Users/sac/jotp/docs/troubleshooting/performance-issues.md#thread-exhaustion)
- [Slow startup](/Users/sac/jotp/docs/troubleshooting/performance-issues.md#slow-startups)

### Testing
- [Flaky async tests](/Users/sac/jotp/docs/troubleshooting/testing-issues.md#flaky-async-tests)
- [Awaitility timeouts](/Users/sac/jotp/docs/troubleshooting/testing-issues.md#awaitility-timeouts)
- [Mock setup problems](/Users/sac/jotp/docs/troubleshooting/testing-issues.md#mock-setup-problems)
- [Test isolation failures](/Users/sac/jotp/docs/troubleshooting/testing-issues.md#test-isolation-failures)

### Common Errors
- [ClassCastException in message handler](/Users/sac/jotp/docs/troubleshooting/common-errors.md#classcastexception-in-message-handler)
- [Process already registered](/Users/sac/jotp/docs/troubleshooting/common-errors.md#process-already-registered)
- [Timeout exceptions](/Users/sac/jotp/docs/troubleshooting/common-errors.md#timeout-exceptions)
- [Link/monitor confusion](/Users/sac/jotp/docs/troubleshooting/common-errors.md#linkmonitor-confusion)
- [Mailbox overflow exception](/Users/sac/jotp/docs/troubleshooting/common-errors.md#mailbox-overflow-exception)
- [Process exit exceptions](/Users/sac/jotp/docs/troubleshooting/common-errors.md#process-exit-exceptions)
- [State machine errors](/Users/sac/jotp/docs/troubleshooting/common-errors.md#state-machine-errors)

---

**Need help?** Start with the [Diagnostic Checklist](diagnostic-checklist.md) for systematic problem-solving.
