# Supervision Issues Troubleshooting Guide

## Children Not Restarting

### Symptoms
- Child process crashes but supervisor doesn't restart it
- Child stays in EXITED state permanently
- Supervisor logs show "max restart intensity exceeded"
- Manual intervention required to restart children

### Diagnosis Steps

1. **Check supervisor restart strategy:**
   ```java
   Supervisor supervisor = new Supervisor(
       RestartStrategy.ONE_FOR_ONE,  // Verify strategy
       5,    // Max restarts
       Duration.ofSeconds(10)        // Time window
   );
   ```

2. **Monitor child restart count:**
   ```java
   SupervisorRef ref = supervisor.ref();
   Map<String, ChildRestartInfo> children = ref.children();

   children.forEach((id, info) -> {
       log.info("Child {} restarts: {}", id, info.restartCount());
   });
   ```

3. **Verify child spec:**
   ```java
   ChildSpec spec = new ChildSpec(
       "my-child",
       () -> new MyProc(),  // Ensure factory returns new instance
       RestartStrategy.PERMANENT  // Check restart type
   );
   ```

4. **Check for termination reasons:**
   ```java
   // Enable supervisor logging
   supervisor.addListener(new SupervisorListener() {
       @Override
       public void childTerminated(String id, ExitReason reason) {
           log.error("Child {} terminated: {}", id, reason);
       }
   });
   ```

### Solutions

#### Fix Restart Strategy Configuration
**Problem:** Intensity exceeded too quickly
```java
// Bad: Too restrictive (3 restarts in 5s)
Supervisor supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    3,                         // Too few
    Duration.ofSeconds(5)      // Too short
);

// Good: More lenient (10 restarts in 60s)
Supervisor supervisor = new ProcSupervisor(
    RestartStrategy.ONE_FOR_ONE,
    10,
    Duration.ofSeconds(60)
);
```

#### Use Correct Restart Type
```java
// TRANSIENT: Don't restart after normal shutdown
ChildSpec transientSpec = new ChildSpec(
    "temp-worker",
    () -> new Worker(),
    RestartType.TRANSIENT
);

// PERMANENT: Always restart
ChildSpec permanentSpec = new ChildSpec(
    "critical-service",
    () -> new Service(),
    RestartType.PERMANENT
);

// TEMPORARY: Restart only on abnormal exit
ChildSpec tempSpec = new ChildSpec(
    "worker",
    () -> new Worker(),
    RestartType.TEMPORARY
);
```

#### Fix Child Factory
**Problem:** Factory returns same instance instead of creating new one
```java
// Bad: Returns singleton
ChildSpec spec = new ChildSpec(
    "bad-child",
    () -> existingInstance,  // WRONG!
    RestartType.PERMANENT
);

// Good: Creates new instance
ChildSpec spec = new ChildSpec(
    "good-child",
    () -> new MyProc(),  // Fresh instance each time
    RestartType.PERMANENT
);
```

### Prevention
- Set appropriate restart intensity based on crash rate
- Use TRANSIENT for graceful shutdowns
- Monitor restart metrics in production
- Test failure scenarios in development

---

## Supervisor Crashes

### Symptoms
- Entire supervision tree dies
- Children become orphans
- No supervisor logs available
- Application hangs on shutdown

### Diagnosis Steps

1. **Check supervisor exception handling:**
   ```java
   // Supervisor should handle exceptions in child startup
   try {
       supervisorRef.startChild(spec);
   } catch (Exception e) {
       log.error("Failed to start child", e);
       // Should not crash supervisor
   }
   ```

2. **Verify supervisor's own supervisor:**
   ```java
   // Supervisors should be supervised
   Proc<String, String> supervisorSupervisor = new Proc<>("sup-sup") {
       @Override
       protected void init() {
           spawnChild(supervisorSpec);
       }
   };
   ```

3. **Check for infinite recursion:**
   ```java
   // Bad: Child spawns supervisor which spawns child...
   ChildSpec child = new ChildSpec("child", () -> {
       return new Proc<String, String>("child") {
           protected void init() {
               spawn(supervisor);  // INFINITE LOOP
           }
       };
   }, RestartType.PERMANENT);
   ```

### Solutions

#### Use Strategy-Specific Restart Logic
```java
// ONE_FOR_ONE: Only restart crashed child
Supervisor supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    10,
    Duration.ofMinutes(1)
);

// ONE_FOR_ALL: Restart all children if one crashes
Supervisor supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ALL,
    10,
    Duration.ofMinutes(1)
);

// REST_FOR_ONE: Restart crashed child and all started after it
Supervisor supervisor = new Supervisor(
    RestartStrategy.REST_FOR_ONE,
    10,
    Duration.ofMinutes(1)
);
```

#### Add Exception Handling to Supervisor
```java
Proc<String, String> supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    10,
    Duration.ofMinutes(1)
) {
    @Override
    protected void handle(Exception e) {
        log.error("Supervisor error (not crashing)", e);
        // Supervisor stays alive
    }

    @Override
    protected void handleChildStartFailure(String id, Exception e) {
        log.error("Child {} failed to start: {}", id, e.getMessage());
        // Decide: retry or give up
        if (isTransient(e)) {
            scheduleRetry(id, Duration.ofSeconds(5));
        } else {
            log.error("Permanent failure, not restarting {}", id);
        }
    }
};
```

#### Supervise the Supervisor
```java
// Top-level supervisor for application supervisors
Proc<String, String> topSupervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    5,
    Duration.ofMinutes(5)
) {
    @Override
    protected void init() {
        spawnChild(new ChildSpec(
            "service-supervisor",
            () -> new ServiceSupervisor(),
            RestartType.PERMANENT
        ));
    }
};
```

### Prevention
- Always supervise supervisors
- Handle exceptions in supervisor callbacks
- Test crash scenarios
- Monitor supervisor health metrics

---

## Restart Intensity Exceeded

### Symptoms
- Supervisor stops restarting children
- Logs show "intensity exceeded, giving up"
- Children stay in crashed state
- Application degrades over time

### Diagnosis Steps

1. **Calculate restart rate:**
   ```java
   SupervisorRef ref = supervisor.ref();

   long restartsInWindow = ref.getRestartCount(
       Instant.now().minus(Duration.ofSeconds(10)),
       Instant.now()
   );

   log.info("Restarts in last 10s: {}", restartsInWindow);
   ```

2. **Identify crash patterns:**
   ```java
   Map<String, List<ExitReason>> crashHistory = ref.getCrashHistory();

   crashHistory.forEach((childId, crashes) -> {
       if (crashes.size() > threshold) {
           log.error("Child {} crashing repeatedly: {}", childId, crashes);
       }
   });
   ```

3. **Check for cascading failures:**
   ```java
   // Monitor dependency chains
   // If A crashes, does it cause B to crash?
   ref.addListener(new SupervisorListener() {
       @Override
       public void childTerminated(String id, ExitReason reason) {
           List<String> dependents = findDependents(id);
           log.info("Dependents of {}: {}", id, dependents);
       }
   });
   ```

### Solutions

#### Adjust Intensity Parameters
```java
// Analyze crash frequency
// Example: Child crashes 8 times per minute

// Bad: Too strict (5 restarts per 10s)
Supervisor supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(10)  // Too short window
);

// Good: Matches observed pattern
Supervisor supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    20,                         // Allow more restarts
    Duration.ofMinutes(5)       // Longer window
);
```

#### Implement Circuit Breaker
```java
Proc<String, String> supervisor = new Supervisor(
    RestartStrategy.ONE_FOR_ONE,
    10,
    Duration.ofMinutes(1)
) {
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Override
    protected void childTerminated(String id, ExitReason reason) {
        CircuitBreaker breaker = breakers.computeIfAbsent(
            id, k -> new CircuitBreaker(5, Duration.ofMinutes(2))
        );

        if (breaker.recordFailure()) {
            log.error("Circuit opened for {}, stopping restarts", id);
            stopRestarting(id);
            scheduleCircuitReset(id, Duration.ofMinutes(5));
        } else {
            super.childTerminated(id, reason);
        }
    }
};
```

#### Fix Root Cause of Crashes
```java
// Example: Resource exhaustion causes crashes
Proc<String, String> child = new Proc<>("child") {
    @Override
    protected void init() {
        // Bad: Opens file but never closes
        file = new File("data.txt");
        stream = new FileInputStream(file);

        // Good: Uses try-with-resources
        try (FileInputStream stream = new FileInputStream(file)) {
            // Auto-closed
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
};
```

### Prevention
- Monitor crash frequency metrics
- Set intensity based on observed behavior
- Fix underlying crash causes
- Use circuit breakers for flaky services

---

## Orphaned Processes

### Symptoms
- Processes still running after application shutdown
- "Process already registered" errors on restart
- Memory leaks from undead processes
- Port conflicts on restart

### Diagnosis Steps

1. **Check process registry:**
   ```java
   ProcRegistry registry = ProcRegistry.getInstance();

   Map<String, ProcRef<?, ?>> allProcesses = registry.getAll();
   allProcesses.forEach((name, ref) -> {
       log.info("Process {} state: {}", name, ref.state());
   });
   ```

2. **Verify shutdown hooks:**
   ```java
   // Check if shutdown hook is registered
   Runtime.getRuntime().addShutdownHook(new Thread(() -> {
       supervisor.shutdown(Duration.ofSeconds(30));
       ProcRegistry.getInstance().shutdownAll();
   }));
   ```

3. **Test graceful shutdown:**
   ```bash
   # Send SIGTERM
   kill -TERM <pid>

   # Wait 30s
   sleep 30

   # Check if processes exited
   jps -l | grep jotp
   ```

### Solutions

#### Implement Proper Shutdown
```java
// Bad: Processes exit immediately on signal
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    System.exit(0);  // Processes don't clean up
}));

// Good: Graceful shutdown
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutting down...");

    // Stop accepting new work
    supervisor.drain();

    // Wait for in-flight work
    boolean stopped = supervisor.awaitTermination(30, TimeUnit.SECONDS);

    if (!stopped) {
        log.warn("Forcing shutdown after timeout");
        supervisor.shutdownNow();
    }

    log.info("Shutdown complete");
}));
```

#### Use Process Links
```java
// Parent links to child for automatic cleanup
Proc<String, String> parent = new Proc<>("parent") {
    @Override
    protected void init() {
        Proc<String, String> child = spawn("child");
        link(child);  // Auto-exit if child crashes
    }

    @Override
    protected void handleExit(ProcRef<?, ?> ref, ExitReason reason) {
        log.info("Child exited: {}", reason);
        // Decide: restart or shutdown
    }
};
```

#### Cleanup on Startup
```java
// Check for stale processes on startup
public static void main(String[] args) {
    ProcRegistry registry = ProcRegistry.getInstance();

    // Clean up any stale processes from previous run
    registry.cleanupStaleProcesses();

    // Start fresh
    Supervisor supervisor = new Supervisor(...);
    supervisor.start();
}
```

### Prevention
- Always use shutdown hooks
- Link critical processes
- Test graceful shutdown
- Monitor process lifecycle

---

## Shutdown Hangs

### Symptoms
- Application doesn't exit after SIGTERM
- Processes stuck in "STOPPING" state
- Timeout waiting for message processing
- Need SIGKILL to terminate

### Diagnosis Steps

1. **Identify stuck processes:**
   ```java
   supervisor.awaitTermination(30, TimeUnit.SECONDS);

   List<ProcRef<?, ?>> stuck = supervisor.getChildren().stream()
       .filter(ref -> ref.state() == ProcState.STOPPING)
       .toList();

   log.error("Stuck processes: {}", stuck);
   ```

2. **Check for blocking operations:**
   ```java
   // Look for:
   @Override
   protected void terminate() {
       Thread.sleep(10000);  // Blocks shutdown
   }
   ```

3. **Monitor message queue during shutdown:**
   ```java
   long messagesInFlight = ref.mailboxSize();
   if (messagesInFlight > 0) {
       log.warn("Shutting down with {} messages pending", messagesInFlight);
   }
   ```

### Solutions

#### Implement Graceful Shutdown
```java
Proc<String, String> proc = new Proc<>("worker") {
    @Override
    protected void handle(Shutdown signal) {
        log.info("Received shutdown signal");

        // Flush pending work
        flushQueue();

        // Notify parent we're done
        reply(new ShutdownAck());
    }

    private void flushQueue() {
        // Process remaining messages
        List<Message> pending = drainMailbox();
        pending.forEach(this::handle);
    }
};
```

#### Use Shutdown Timeout
```java
// Set timeout in supervisor
Supervisor supervisor = new Supervisor(...) {
    @Override
    protected void shutdown() {
        // Phase 1: Stop accepting work
        broadcast(new StopAccepting());

        // Phase 2: Wait for completion
        awaitChildrenTermination(10, TimeUnit.SECONDS);

        // Phase 3: Force if needed
        if (hasRunningChildren()) {
            log.warn("Forcing shutdown");
            shutdownNow();
        }
    }
};
```

#### Avoid Blocking in Terminate
```java
// Bad: Blocks shutdown
@Override
protected void terminate() {
    database.close();  // Might block
}

// Good: Async close
@Override
protected void terminate() {
    CompletableFuture.runAsync(() -> {
        database.close();
    });

    // Or use timeout
    try {
        database.close(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        log.warn("Database close timeout, forcing");
    }
}
```

### Prevention
- Set shutdown timeouts at all levels
- Test shutdown under load
- Monitor shutdown duration metrics
- Use force shutdown as last resort

---

## Quick Reference

### Common Restart Strategies

| Strategy | Description | Use Case |
|----------|-------------|----------|
| ONE_FOR_ONE | Only restart crashed child | Independent services |
| ONE_FOR_ALL | Restart all children | Tightly coupled components |
| REST_FOR_ONE | Restart crashed + subsequent | Ordered startup dependencies |

### Restart Types

| Type | Behavior | Example |
|------|----------|---------|
| PERMANENT | Always restart | Critical services |
| TEMPORARY | Restart on abnormal exit | Workers |
| TRANSIENT | Never restart | One-shot tasks |

### Monitoring Commands

```java
// Check supervisor state
SupervisorRef ref = supervisor.ref();
log.info("Children: {}", ref.getChildren());
log.info("Restarts: {}", ref.getTotalRestarts());
log.info("Crash history: {}", ref.getCrashHistory());

// Force restart child
ref.restartChild("child-id");

// Stop child permanently
ref.stopChild("child-id");

// Shutdown entire tree
ref.shutdown(Duration.ofSeconds(30));
```

---

## Related Issues

- **Runtime Issues:** If children crash during startup, see `runtime-issues.md`
- **Performance:** If restarts cause performance degradation, see `performance-issues.md`
- **Debugging:** For inspection techniques, see `debugging-techniques.md`
