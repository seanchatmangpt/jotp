# Debugging Techniques for JOTP Applications

## Table of Contents
- [Enable Debug Tracing](#enable-debug-tracing)
- [ProcSys Introspection](#procsys-introspection)
- [Message Flow Tracing](#message-flow-tracing)
- [Supervisor Tree Inspection](#supervisor-tree-inspection)
- [Breakpoint Strategies](#breakpoint-strategies)

---

## Enable Debug Tracing

### Symptoms
- Need visibility into process behavior
- Message flow unclear
- Performance bottlenecks invisible
- Race conditions hard to reproduce

### Diagnosis Steps

1. **Identify what needs tracing:**
```java
// What to trace?
// - Process lifecycle (spawn, crash, restart)
// - Message delivery and processing
// - State transitions
// - Supervisor actions
// - Timer events
```

2. **Check available debug options:**
```java
DebugOptions options = DebugOptions.builder()
    .traceSpawn(true)          // Process creation
    .traceMessages(true)       // Message delivery
    .traceStateChanges(true)   // State transitions
    .traceSupervision(true)    // Supervisor actions
    .traceTimers(true)         // Timer events
    .logDelivery(true)         // Message delivery logging
    .logProcessingTime(true)   // Performance metrics
    .build();
```

### Solutions

#### Solution 1: Enable Global Debug Tracing
```java
// In application startup
public class Main {
    public static void main(String[] args) {
        // Enable debug tracing globally
        DebugOptions options = DebugOptions.builder()
            .traceAll(true)  // Enable all traces
            .build();

        Proc.setGlobalDebugOptions(options);

        // Start application
        Application app = Application.start();
    }
}
```

#### Solution 2: Per-Process Debug Tracing
```java
// Enable debug for specific process
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .name("debug-process")
    .debugOptions(DebugOptions.builder()
        .traceMessages(true)
        .traceStateChanges(true)
        .build())
    .handler(handler)
    .build();

Proc<MyState, MyMsg> proc = Proc.spawn(spec);
```

#### Solution 3: Conditional Debug Tracing
```java
// Enable debug only for specific conditions
DebugOptions options = DebugOptions.builder()
    .traceMessages(true)
    .traceStateChanges(true)
    .filter(event -> {
        // Only trace messages from specific sender
        if (event instanceof MessageEvent msgEvent) {
            return msgEvent.sender().toString().contains("critical");
        }
        return false;
    })
    .build();
```

#### Solution 4: Debug Observers
```java
// Add custom debug observer
DebugObserver observer = new DebugObserver() {
    @Override
    public void onEvent(DebugEvent event) {
        // Custom event handling
        switch (event) {
            case MessageEvent msg -> {
                System.out.println("[MSG] " + msg.from() + " -> " + msg.to());
            }
            case StateChangeEvent state -> {
                System.out.println("[STATE] " + state.from() + " -> " + state.to());
            }
            case SpawnEvent spawn -> {
                System.out.println("[SPAWN] " + spawn.procId());
            }
        }
    }
};

Proc.addDebugObserver(observer);
```

#### Solution 5: Integration with Logging Frameworks
```java
// Integrate with SLF4J
DebugObserver slf4jObserver = new DebugObserver() {
    private final Logger logger = LoggerFactory.getLogger("jotp.debug");

    @Override
    public void onEvent(DebugEvent event) {
        if (event instanceof MessageEvent msg) {
            logger.debug("Message: {} -> {} : {}",
                msg.from(), msg.to(), msg.message());
        } else if (event instanceof StateChangeEvent state) {
            logger.info("State change: {} : {} -> {}",
                state.procId(), state.from(), state.to());
        }
    }
};

Proc.addDebugObserver(slf4jObserver);
```

### Prevention
- Enable debug tracing in development environments
- Use structured logging for easier analysis
- Set up log aggregation (ELK, Splunk, etc.)
- Monitor debug overhead in production
- Use sampling for high-throughput scenarios

### Related Issues
- [Message Flow Tracing](#message-flow-tracing)
- [ProcSys Introspection](#procsys-introspection)

---

## ProcSys Introspection

### Symptoms
- Need to inspect running processes
- Want to understand system state
- Debugging stuck processes
- Monitoring system health

### Diagnosis Steps

1. **List all processes:**
```java
Set<ProcId> allProcesses = ProcSys.allProcesses();
System.out.println("Total processes: " + allProcesses.size());
```

2. **Get process details:**
```java
ProcInspection inspect = ProcSys.inspect(procId);
System.out.println("State: " + inspect.state());
System.out.println("Mailbox size: " + inspect.mailboxSize());
System.out.println("Uptime: " + inspect.uptime());
```

3. **Check process relationships:**
```java
ProcInspection inspect = ProcSys.inspect(procId);
System.out.println("Parent: " + inspect.parentId());
System.out.println("Children: " + inspect.childrenIds());
```

### Solutions

#### Solution 1: Live Process Monitoring
```java
public class ProcessMonitor {
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(1);

    public void start() {
        scheduler.scheduleAtFixedRate(this::monitor, 0, 5, TimeUnit.SECONDS);
    }

    private void monitor() {
        ProcSys.allProcesses().forEach(pid -> {
            ProcInspection inspect = ProcSys.inspect(pid);
            log.info("Process {}: state={}, mailbox={}, uptime={}s",
                pid,
                inspect.state(),
                inspect.mailboxSize(),
                inspect.uptime().toSeconds());
        });
    }
}
```

#### Solution 2: Process Tree Visualization
```java
public class ProcessTreeVisualizer {
    public void printTree() {
        // Find root processes (no parent)
        Set<ProcId> roots = ProcSys.allProcesses().stream()
            .filter(pid -> {
                ProcInspection inspect = ProcSys.inspect(pid);
                return inspect.parentId().isEmpty();
            })
            .collect(Collectors.toSet());

        // Print each tree
        for (ProcId root : roots) {
            printSubtree(root, 0);
        }
    }

    private void printSubtree(ProcId pid, int indent) {
        ProcInspection inspect = ProcSys.inspect(pid);
        String prefix = "  ".repeat(indent);
        System.out.println(prefix + "- " + pid + " [" + inspect.state() + "]");

        // Recursively print children
        for (ProcId child : inspect.childrenIds()) {
            printSubtree(child, indent + 1);
        }
    }
}
```

#### Solution 3: Mailbox Inspection
```java
public class MailboxInspector {
    public void inspectMailboxes() {
        ProcSys.allProcesses().forEach(pid -> {
            ProcInspection inspect = ProcSys.inspect(pid);
            int size = inspect.mailboxSize();
            int capacity = inspect.mailboxCapacity();
            double usage = (double) size / capacity;

            if (usage > 0.8) {
                log.warn("Mailbox nearly full: {} ({}/{})",
                    pid, size, capacity);
            }
        });
    }
}
```

#### Solution 4: State Snapshot
```java
public class StateSnapshotter {
    public Map<ProcId, Object> captureState() {
        Map<ProcId, Object> snapshot = new HashMap<>();
        ProcSys.allProcesses().forEach(pid -> {
            ProcInspection inspect = ProcSys.inspect(pid);
            inspect.currentState().ifPresent(state -> {
                snapshot.put(pid, state);
            });
        });
        return snapshot;
    }

    public void compareSnapshots(
        Map<ProcId, Object> before,
        Map<ProcId, Object> after
    ) {
        before.forEach((pid, stateBefore) -> {
            Object stateAfter = after.get(pid);
            if (!stateBefore.equals(stateAfter)) {
                log.info("Process {} state changed: {} -> {}",
                    pid, stateBefore, stateAfter);
            }
        });
    }
}
```

#### Solution 5: Deadlock Detection
```java
public class DeadlockDetector {
    public void detectDeadlocks() {
        Map<ProcId, Set<ProcId>> waitGraph = new HashMap<>();

        // Build wait graph
        ProcSys.allProcesses().forEach(pid -> {
            ProcInspection inspect = ProcSys.inspect(pid);
            Set<ProcId> waitingFor = inspect.waitingFor();
            if (!waitingFor.isEmpty()) {
                waitGraph.put(pid, waitingFor);
            }
        });

        // Detect cycles
        detectCycles(waitGraph).forEach(cycle -> {
            log.error("Deadlock detected: {}",
                String.join(" -> ", cycle));
        });
    }

    private List<List<String>> detectCycles(Map<ProcId, Set<ProcId>> graph) {
        // Implement cycle detection using DFS
        // Returns list of cycles
        return Collections.emptyList(); // Simplified
    }
}
```

### Prevention
- Regularly monitor process state
- Set up alerts for unusual patterns
- Log state transitions
- Use health checks
- Implement circuit breakers

### Related Issues
- [Debug Tracing](#enable-debug-tracing)
- [Supervisor Tree Inspection](#supervisor-tree-inspection)

---

## Message Flow Tracing

### Symptoms
- Need to trace message paths
- Understanding message ordering
- Debugging message loss
- Performance analysis of message delivery

### Diagnosis Steps

1. **Enable message tracing:**
```java
DebugOptions options = DebugOptions.builder()
    .traceMessages(true)
    .logDelivery(true)
    .build();
```

2. **Add message correlation:**
```java
interface TrackedMessage {
    String correlationId();
    Instant timestamp();
}
```

3. **Monitor message throughput:**
```java
AtomicLong messagesSent = new AtomicLong(0);
AtomicLong messagesReceived = new AtomicLong(0);
```

### Solutions

#### Solution 1: Message Flow Diagram
```java
public class MessageFlowTracer {
    private final Map<String, List<MessageEvent>> flows = new ConcurrentHashMap<>();

    public void traceMessage(TrackedMessage msg, ProcId from, ProcId to) {
        String correlationId = msg.correlationId();
        MessageEvent event = new MessageEvent(
            correlationId,
            from,
            to,
            Instant.now(),
            msg
        );

        flows.computeIfAbsent(correlationId, k -> new ArrayList<>()).add(event);
    }

    public void printFlow(String correlationId) {
        List<MessageEvent> events = flows.get(correlationId);
        if (events != null) {
            System.out.println("Message flow: " + correlationId);
            events.forEach(e -> {
                System.out.println("  " + e.timestamp() + ": " +
                    e.from() + " -> " + e.to());
            });
        }
    }
}
```

#### Solution 2: Distributed Tracing Integration
```java
// Integration with OpenTelemetry
public class OtelTracing {
    private final Tracer tracer;

    public void sendMessage(ProcId from, ProcId to, Message msg) {
        Span span = tracer.spanBuilder("jotp.message.send")
            .setAttribute("from", from.toString())
            .setAttribute("to", to.toString())
            .setAttribute("message.type", msg.getClass().getSimpleName())
            .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Send message
            to.send(msg);
        } finally {
            span.end();
        }
    }
}
```

#### Solution 3: Message Latency Tracking
```java
public class MessageLatencyTracker {
    private final Map<String, Instant> sendTimes = new ConcurrentHashMap<>();

    public void trackSent(String messageId) {
        sendTimes.put(messageId, Instant.now());
    }

    public void trackReceived(String messageId) {
        Instant sent = sendTimes.remove(messageId);
        if (sent != null) {
            Duration latency = Duration.between(sent, Instant.now());
            log.info("Message latency: {}ms", latency.toMillis());
        }
    }
}
```

#### Solution 4: Message Ordering Verification
```java
public class MessageOrderVerifier {
    private final Map<ProcId, AtomicLong> sequenceNumbers = new ConcurrentHashMap<>();

    public void verifyOrder(ProcId proc, long sequenceNumber) {
        AtomicLong expected = sequenceNumbers.computeIfAbsent(
            proc, k -> new AtomicLong(0)
        );

        long expectedValue = expected.get();
        if (sequenceNumber != expectedValue) {
            log.error("Out of order message for {}: expected {}, got {}",
                proc, expectedValue, sequenceNumber);
        } else {
            expected.incrementAndGet();
        }
    }
}
```

#### Solution 5: Message Loss Detection
```java
public class MessageLossDetector {
    private final Map<String, Boolean> deliveryStatus = new ConcurrentHashMap<>();

    public void trackMessage(String messageId) {
        deliveryStatus.put(messageId, false);
    }

    public void markDelivered(String messageId) {
        deliveryStatus.put(messageId, true);
    }

    public void checkForUndelivered() {
        deliveryStatus.entrySet().stream()
            .filter(e -> !e.getValue())
            .forEach(e -> {
                log.warn("Message possibly not delivered: {}", e.getKey());
            });
    }
}
```

### Prevention
- Use message tracing in development
- Implement message correlation
- Monitor delivery rates
- Use idempotent message handlers
- Implement dead letter queues

### Related Issues
- [Debug Tracing](#enable-debug-tracing)
- [Messages Not Delivered](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#messages-not-delivered)

---

## Supervisor Tree Inspection

### Symptoms
- Need to understand supervision hierarchy
- Children not restarting properly
- Supervisor crashes unclear
- Restart intensity issues

### Diagnosis Steps

1. **Get supervisor info:**
```java
SupervisorRef ref = supervisor.ref();
System.out.println("Strategy: " + ref.strategy());
System.out.println("Children: " + ref.children());
```

2. **Check child states:**
```java
ref.children().forEach((id, info) -> {
    System.out.println("Child " + id + ": " +
        info.state() + " (restarts: " + info.restartCount() + ")");
});
```

3. **Monitor restarts:**
```java
ref.addRestartListener((childId, oldRestart, newRestart) -> {
    log.info("Child {} restarted: {} -> {}",
        childId, oldRestart, newRestart);
});
```

### Solutions

#### Solution 1: Supervisor Tree Visualization
```java
public class SupervisorTreeVisualizer {
    public void visualize(SupervisorRef root) {
        printSupervisor(root, 0);
    }

    private void printSupervisor(SupervisorRef supervisor, int indent) {
        String prefix = "  ".repeat(indent);
        System.out.println(prefix + "Supervisor: " + supervisor.id());
        System.out.println(prefix + "  Strategy: " + supervisor.strategy());

        // Print children
        supervisor.children().forEach((id, info) -> {
            System.out.println(prefix + "  - Child: " + id);
            System.out.println(prefix + "    State: " + info.state());
            System.out.println(prefix + "    Restarts: " + info.restartCount());

            // Recursively print child supervisors
            if (info instanceof SupervisorRef childSup) {
                printSupervisor(childSup, indent + 2);
            }
        });
    }
}
```

#### Solution 2: Restart History Tracking
```java
public class RestartHistoryTracker {
    private final Map<String, List<RestartEvent>> history = new ConcurrentHashMap<>();

    public void trackRestart(String childId, RestartReason reason) {
        RestartEvent event = new RestartEvent(
            childId,
            Instant.now(),
            reason
        );

        history.computeIfAbsent(childId, k -> new ArrayList<>())
            .add(event);

        // Keep last 100 events per child
        List<RestartEvent> events = history.get(childId);
        if (events.size() > 100) {
            events.remove(0);
        }
    }

    public void analyzeRestartPatterns(String childId) {
        List<RestartEvent> events = history.get(childId);
        if (events != null && !events.isEmpty()) {
            long totalRestarts = events.size();
            Map<RestartReason, Long> reasonCounts = events.stream()
                .collect(Collectors.groupingBy(
                    RestartEvent::reason,
                    Collectors.counting()
                ));

            System.out.println("Restart analysis for " + childId + ":");
            System.out.println("  Total restarts: " + totalRestarts);
            reasonCounts.forEach((reason, count) -> {
                System.out.println("  " + reason + ": " + count);
            });
        }
    }
}
```

#### Solution 3: Supervisor Health Check
```java
public class SupervisorHealthCheck {
    public HealthStatus checkHealth(SupervisorRef supervisor) {
        // Check if supervisor is alive
        if (!supervisor.isAlive()) {
            return HealthStatus.UNHEALTHY;
        }

        // Check restart intensity
        if (supervisor.isMaxIntensityReached()) {
            return HealthStatus.DEGRADED;
        }

        // Check children health
        long unhealthyChildren = supervisor.children().values().stream()
            .filter(child -> child.state() != ProcState.RUNNING)
            .count();

        if (unhealthyChildren > 0) {
            return HealthStatus.DEGRADED;
        }

        return HealthStatus.HEALTHY;
    }
}
```

#### Solution 4: Restart Strategy Validation
```java
public class RestartStrategyValidator {
    public void validate(SupervisorRef supervisor) {
        SupervisorStrategy strategy = supervisor.strategy();
        int maxRestarts = supervisor.maxRestarts();
        Duration window = supervisor.restartWindow();

        System.out.println("Strategy: " + strategy);
        System.out.println("Max restarts: " + maxRestarts);
        System.out.println("Time window: " + window);

        // Simulate restart pattern
        supervisor.children().forEach((id, info) -> {
            long restarts = info.restartCount();
            if (restarts > maxRestarts) {
                log.warn("Child {} exceeded max restarts: {} > {}",
                    id, restarts, maxRestarts);
            }
        });
    }
}
```

#### Solution 5: Supervisor Event Logging
```java
public class SupervisorEventLogger {
    private final Logger logger = LoggerFactory.getLogger("supervisor");

    public void logAllEvents(SupervisorRef supervisor) {
        supervisor.addListener(new SupervisorListener() {
            @Override
            public void childSpawned(String id) {
                logger.info("Child spawned: {}", id);
            }

            @Override
            public void childTerminated(String id, ExitReason reason) {
                logger.info("Child terminated: {} - {}", id, reason);
            }

            @Override
            public void childRestarted(String id, int restartCount) {
                logger.info("Child restarted: {} (restart #{})",
                    id, restartCount);
            }

            @Override
            public void maxIntensityReached() {
                logger.error("Max restart intensity reached!");
            }
        });
    }
}
```

### Prevention
- Monitor supervisor health regularly
- Set appropriate restart intensity
- Log all supervisor events
- Use proper restart strategies
- Test supervision trees

### Related Issues
- [Supervision Issues](/Users/sac/jotp/docs/troubleshooting/supervision-issues.md)
- [ProcSys Introspection](#procsys-introspection)

---

## Breakpoint Strategies

### Symptoms
- Need to debug process behavior
- Understanding message handling
- Tracing state transitions
- Debugging concurrency issues

### Diagnosis Steps

1. **Identify debug points:**
```java
// Where to set breakpoints?
// - Message handler entry/exit
// - State transitions
// - Supervisor callbacks
// - Timer callbacks
```

2. **Check thread context:**
```java
// Are you in a virtual thread?
Thread.currentThread().isVirtual();

// What's the current process?
ProcId.current(); // Returns current process ID
```

### Solutions

#### Solution 1: Conditional Breakpoints
```java
// In IDE: Set conditional breakpoint on message type
protected void handle(Message msg) {
    // Breakpoint here with condition:
    // msg instanceof CriticalMessage
    // && ((CriticalMessage) msg).priority() == 1

    processMessage(msg);
}
```

#### Solution 2: Message Logging Breakpoints
```java
protected void handle(Message msg) {
    // Log without stopping
    if (log.isDebugEnabled()) {
        log.debug("Handling: {} in thread: {}",
            msg, Thread.currentThread());
    }

    // Actual breakpoint here
    processMessage(msg);
}
```

#### Solution 3: State Transition Breakpoints
```java
public MyState transition(MyState oldState, MyState newState) {
    // Breakpoint on specific state transitions
    if (oldState.status() == Status.PROCESSING &&
        newState.status() == Status.COMPLETED) {
        log.info("Transition: PROCESSING -> COMPLETED");
        // Set breakpoint here
    }

    return newState;
}
```

#### Solution 4: Exception Breakpoints
```java
// In IDE: Set exception breakpoint for:
// - java.lang.Exception
// - io.github.seanchatmangpt.jotp.ProcessException
// - Custom exceptions

protected void handle(Message msg) {
    try {
        riskyOperation(msg);
    } catch (ProcessException e) {
        log.error("Process error", e);
        // Exception breakpoint will catch this
        throw e;
    }
}
```

#### Solution 5: Thread-Specific Breakpoints
```java
protected void handle(Message msg) {
    // Breakpoint only for specific process
    if (ProcId.current().toString().equals("critical-process")) {
        log.debug("In critical process");
        // Set breakpoint here
    }

    processMessage(msg);
}
```

### Best Practices

#### DO:
- Use conditional breakpoints to reduce noise
- Log state at breakpoints
- Inspect thread context
- Check virtual thread state
- Use field watchpoints
- Set exception breakpoints

#### DON'T:
- Don't block in message handlers (causes deadlocks)
- Don't set breakpoints in hot paths (performance)
- Don't forget to remove breakpoints before commit
- Don't use System.out.println (use logging)
- Don't ignore virtual thread warnings

### Prevention
- Use debug logging instead of breakpoints in production
- Set up remote debugging carefully
- Use conditional breakpoints
- Document breakpoint locations
- Clean up breakpoints after debugging

### Related Issues
- [Debug Tracing](#enable-debug-tracing)
- [Deadlock Diagnosis](/Users/sac/jotp/docs/troubleshooting/runtime-issues.md#deadlock-diagnosis)

---

## Quick Reference

### Enable Debugging
```java
// Global debug
DebugOptions options = DebugOptions.builder()
    .traceAll(true)
    .build();
Proc.setGlobalDebugOptions(options);

// Per-process debug
ProcSpec<MyState, MyMsg> spec = ProcSpec.<MyState, MyMsg>builder()
    .debugOptions(options)
    .build();
```

### Inspect Processes
```java
// List all processes
Set<ProcId> all = ProcSys.allProcesses();

// Inspect specific process
ProcInspection inspect = ProcSys.inspect(procId);

// Get process state
ProcState state = inspect.state();
int mailboxSize = inspect.mailboxSize();
Duration uptime = inspect.uptime();
```

### Trace Messages
```java
// Enable message tracing
DebugOptions options = DebugOptions.builder()
    .traceMessages(true)
    .logDelivery(true)
    .build();

// Add message observer
Proc.addDebugObserver(new DebugObserver() {
    @Override
    public void onEvent(DebugEvent event) {
        if (event instanceof MessageEvent msg) {
            System.out.println("Message: " + msg);
        }
    }
});
```

### Debug Supervisor
```java
// Get supervisor info
SupervisorRef ref = supervisor.ref();
ref.children().forEach((id, info) -> {
    System.out.println(id + ": " + info.state());
});

// Add listener
supervisor.addListener(new SupervisorListener() {
    @Override
    public void childRestarted(String id, int restartCount) {
        System.out.println("Restarted: " + id);
    }
});
```

---

## Related Issues
- **Runtime Issues:** See `runtime-issues.md` for process problems
- **Supervision:** See `supervision-issues.md` for restart issues
- **Testing:** See `testing-issues.md` for test debugging
