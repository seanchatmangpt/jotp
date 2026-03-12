# Monitoring Without Killing

## Overview

**Links** (bilateral crash propagation) and **Supervisors** (automatic restart) are great for building resilient systems where processes depend on each other.

But sometimes you just want to **observe** a process without crashing if it dies. You want to know "when X dies, do Y," but you don't want Y to die just because X did.

This is what **monitors** do.

## Monitors vs. Links

| Feature | Link | Monitor |
|---------|------|---------|
| Direction | **Bilateral** (both die) | **Unilateral** (only observer is notified) |
| Death propagation | Yes (A links to B, B crashes → A crashes) | No (A monitors B, B crashes → A notified but keeps running) |
| Setup | `ProcLink.link(A, B)` or `spawnLink()` | `ProcMonitor.monitor(A, B)` |
| Notification | `ExitSignal` (if trapping) | `DownMessage` |
| Use case | Dependent services | Observing health, logging, metrics |

## Basic Monitoring

```java
import io.github.seanchatmangpt.jotp.*;

sealed interface WorkerMsg {
    record Work(String task) implements WorkerMsg {}
    record Stop() implements WorkerMsg {}
}

// Worker process
var worker = Proc.spawn(
    "ready",
    (state, msg) -> switch (msg) {
        case WorkerMsg.Work(var task) -> {
            System.out.println("Working on: " + task);
            if (task.equals("crash")) throw new RuntimeException("oops");
            return state;
        }
        case WorkerMsg.Stop() -> {
            System.out.println("Worker stopping");
            return state;
        }
        default -> state;
    }
);

// Monitor the worker WITHOUT depending on it
var monitor = ProcMonitor.monitor(worker);

// Send work
worker.tell(new WorkerMsg.Work("normal"));     // succeeds
worker.tell(new WorkerMsg.Work("crash"));       // crashes

Thread.sleep(100);

// Monitor was notified of the crash
System.out.println("Monitor received DOWN: " + monitor.hasReceivedDown());  // true
System.out.println("Worker alive: " + worker.thread().isAlive());  // false

// But the monitor process itself is still alive!
System.out.println("Monitor alive: " + monitor.isAlive());  // true
```

## ProcMonitor API

`ProcMonitor.monitor(proc)` returns a `Monitor<M>` that tracks the target process:

```java
var monitor = ProcMonitor.monitor(worker);

// Check if DOWN was received
if (monitor.hasReceivedDown()) {
    System.out.println("Process is dead");
    var down = monitor.down();  // Get the DOWN message
    System.out.println("Death reason: " + down.reason());
}

// Cancel the monitor (optional, mainly for cleanup)
monitor.demonitor();
```

## Pattern: Health Checker with Monitors

Monitor multiple workers and track health:

```java
sealed interface HealthMsg {
    record CheckHealth() implements HealthMsg {}
    record WorkerDead(String workerId) implements HealthMsg {}
}

record HealthState(
    Map<String, Boolean> workerStatus,  // worker ID -> alive
    Map<String, Monitor> monitors
) {}

var healthChecker = Proc.spawn(
    new HealthState(Map.of("w1", true, "w2", true), Map.of()),
    (state, msg) -> switch (msg) {
        case HealthMsg.CheckHealth() -> {
            var workers = createWorkers();  // w1, w2, ...

            // Monitor each worker
            var newMonitors = new HashMap<String, Monitor>();
            var status = new HashMap<String, Boolean>();

            for (var entry : workers.entrySet()) {
                var workerId = entry.getKey();
                var worker = entry.getValue();
                var monitor = ProcMonitor.monitor(worker);
                newMonitors.put(workerId, monitor);
                status.put(workerId, true);
            }

            return new HealthState(status, newMonitors);
        }

        case HealthMsg.WorkerDead(var workerId) -> {
            // A worker died — update status
            var newStatus = new HashMap<>(state.workerStatus);
            newStatus.put(workerId, false);
            return new HealthState(newStatus, state.monitors);
        }

        default -> state;
    }
);
```

## Pattern: Supervisor with Monitoring

Use monitors alongside supervisor for better visibility:

```java
sealed interface SupervisorMsg {
    record MonitorChild(String name, Proc child) implements SupervisorMsg {}
}

var supervisor = Proc.spawn(
    new SupervisorState(),
    (state, msg) -> switch (msg) {
        case SupervisorMsg.MonitorChild(var name, var child) -> {
            // Keep the child alive with supervisor restart
            // ALSO monitor it for logging/metrics
            var monitor = ProcMonitor.monitor(child);

            // Spawn a monitor listener process
            var monitorListener = Proc.spawn(
                name,
                (listenerState, listenerMsg) -> {
                    if (monitor.hasReceivedDown()) {
                        System.out.println("Child " + name + " died: " +
                            monitor.down().reason());
                        // Log to metrics, alert, etc.
                    }
                    return listenerState;
                }
            );

            return state.withChild(name, child);
        }
        default -> state;
    }
);
```

## Pattern: Timeout Watcher

Monitor a process and timeout if it doesn't respond:

```java
sealed interface WatcherMsg {
    record Start(Proc target, long timeoutMs) implements WatcherMsg {}
    record TimedOut(Proc target) implements WatcherMsg {}
}

var watcher = Proc.spawn(
    new WatcherState(),
    (state, msg) -> switch (msg) {
        case WatcherMsg.Start(var target, var timeoutMs) -> {
            // Monitor the target
            var monitor = ProcMonitor.monitor(target);

            // Set a timeout in case process is slow to die
            ProcTimer.sendAfter(timeoutMs, sender(), new WatcherMsg.TimedOut(target));

            return state.withMonitor(target, monitor);
        }

        case WatcherMsg.TimedOut(var target) -> {
            var monitor = state.getMonitor(target);
            if (monitor != null && !monitor.hasReceivedDown()) {
                System.out.println("Timeout! Process " + target + " is hung");
                target.stop();  // Force kill
            }
            return state;
        }

        default -> state;
    }
);
```

## Difference: Monitor vs. Ask

Both monitors and ask-patterns wait for something:

| Pattern | Use Case | Behavior |
|---------|----------|----------|
| **Ask** | Request-response | Send message, wait for response |
| **Monitor** | Observe health | Receive DOWN when process dies |

Example:
```java
// Ask: "Are you alive?"
var futureResponse = process.ask("ping", 1000);
// Monitor: "Tell me when you die"
var monitor = ProcMonitor.monitor(process);
```

## Demonitor

To stop monitoring:

```java
var monitor = ProcMonitor.monitor(process);

// Later, stop monitoring
monitor.demonitor();

// Now, even if process dies, we won't receive DOWN
process.stop();
```

## Common Patterns

### Pattern: Cascade Shutdown with Monitoring

Monitor a parent, shutdown children when parent dies:

```java
var parentMonitor = ProcMonitor.monitor(parentProcess);

if (parentMonitor.hasReceivedDown()) {
    System.out.println("Parent died, shutting down children...");
    children.forEach(Proc::stop);
}
```

### Pattern: Metrics Collection via Monitors

Instrument every child process with a monitor:

```java
for (var child : children) {
    var monitor = ProcMonitor.monitor(child);
    metrics.register(child.name(), () -> {
        if (monitor.hasReceivedDown()) {
            return "dead";
        } else {
            return "alive";
        }
    });
}
```

### Pattern: Graceful Degradation

When a critical service dies, downgrade to fallback:

```java
var primaryMonitor = ProcMonitor.monitor(primaryService);
var fallbackService = createFallback();

// Periodically check primary health
ProcTimer.sendInterval(5000, self(), "check-health");

// In message handler:
case "check-health" -> {
    if (primaryMonitor.hasReceivedDown()) {
        System.out.println("Primary down, using fallback");
        useService = fallbackService;
    }
    yield state;
}
```

## Troubleshooting

**Q: I monitored a process but never received DOWN.**
A: The process may still be running. Check:
```java
monitor.hasReceivedDown()  // false = still alive
monitor.isAlive()           // true = still running
```

**Q: Can I monitor a monitor?**
A: No, monitors are lightweight and don't run as separate processes. You can only monitor `Proc` instances.

**Q: If I monitor a process that crashes and restarts, does the monitor follow?**
A: No, monitors are tied to the original process instance. If the process is restarted by a supervisor, you'd need to create a new monitor.

## Next Steps

- **[Handling Process Crashes](handling-process-crashes.md)** — Supervisor-based restart
- **[Linking Processes](linking-processes.md)** — Bilateral crash propagation
- **API Reference** — `ProcMonitor`, `Monitor`, `DownMessage`
