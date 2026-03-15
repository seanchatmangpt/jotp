# How-to: Supervision Trees and Fault Tolerance

## Overview

Supervision trees are hierarchical structures that manage process lifecycles and implement the "let it crash" philosophy. When a child process crashes, its supervisor automatically restarts it according to a strategy.

## Pattern 1: Create a Basic Supervisor

```java
import io.github.seanchatmangpt.jotp.Supervisor;
import java.time.Duration;

// Create a supervisor with ONE_FOR_ONE strategy:
// Only the failed child is restarted
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                          // max restarts
    Duration.ofSeconds(60)      // time window
);
```

## Pattern 2: ONE_FOR_ONE Strategy

Restart only the failed child process. Used when children are independent:

```java
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

// If childA crashes, only childA is restarted
// childB and childC continue running
```

**Best for:** Independent workers, logging, metrics collection

## Pattern 3: ALL_FOR_ONE Strategy

Restart all children when any child crashes. Used for tightly coupled processes:

```java
var supervisor = new Supervisor(
    Supervisor.Strategy.ALL_FOR_ONE,
    5,
    Duration.ofSeconds(60)
);

// If childA crashes, all children (A, B, C) are restarted
// Ensures consistent state across all children
```

**Best for:** Database connections, state holders, coordinated services

## Pattern 4: REST_FOR_ONE Strategy

Restart the failed child and all children started after it. Used when start order matters:

```java
var supervisor = new Supervisor(
    Supervisor.Strategy.REST_FOR_ONE,
    3,
    Duration.ofSeconds(60)
);

// Children: [A, B, C, D] (started in order)
// If B crashes, restart B and C and D, but not A
// Useful when C depends on B, D depends on C
```

**Best for:** Initialization chains, dependency ordered services

## Pattern 5: Process Links for Bidirectional Crash Propagation

Link two processes so both crash together if either fails:

```java
import io.github.seanchatmangpt.jotp.ProcLink;

var processA = new Proc<>(state1, handler1);
var processB = new Proc<>(state2, handler2);

// Create bidirectional link
ProcLink.link(processA, processB);

// If A crashes, B receives an EXIT signal and crashes too
// If B crashes, A receives an EXIT signal and crashes too
```

## Pattern 6: Process Monitoring for Unilateral Notifications

Monitor a process without crashing if it fails:

```java
import io.github.seanchatmangpt.jotp.ProcMonitor;

var process = new Proc<>(state, handler);
var monitor = ProcMonitor.monitor(process, null, failure -> {
    if (failure == null) {
        System.out.println("Process exited normally");
    } else {
        System.out.println("Process crashed: " + failure.getMessage());
    }
});

// Later, if you want to stop monitoring:
// ProcMonitor.demonitor(monitor);
```

## Pattern 7: Exit Signal Trapping

Trap exit signals from linked processes and handle them as messages:

```java
// Enable exit trapping
supervisor.trapExits(true);

// Now link to a child
ProcLink.link(supervisor, child);

// When child crashes, supervisor receives ExitSignal message
var supervisor2 = new Proc<>(0, (state, msg) -> {
    if (msg instanceof ExitSignal signal) {
        System.out.println("Child crashed: " + signal.reason());
        return state + 1;  // count crashes
    }
    return state;
});

supervisor2.trapExits(true);
ProcLink.link(supervisor2, child);
```

## Pattern 8: Restart Limits and Time Windows

Prevent restart storms with sliding window limits:

```java
// 5 restarts within 60 seconds max
// If 5+ crashes occur within any 60-second window,
// supervision stops and the supervisor itself crashes
var supervisor = new Supervisor(
    Supervisor.Strategy.ONE_FOR_ONE,
    5,                          // max restarts
    Duration.ofSeconds(60)      // window duration
);
```

## Best Practices

1. **Use ONE_FOR_ONE by default** — more resilient than others
2. **Set reasonable restart limits** — prevent infinite restart loops
3. **Use monitoring for optional dependencies** — only crash if critical
4. **Use links for critical dependencies** — crash together if either fails
5. **Trap exits for custom error handling** — graceful degradation
6. **Use ProcLib.startLink() for guaranteed startup** — init handshake ensures safe startup

## Common Patterns

### Pattern: Restart a Failed Worker

```java
var worker = new Proc<>(0, (state, msg) -> {
    if (msg instanceof DoWork w) {
        return process(w);
    }
    return state;
});

var supervisor = new Proc<>(0, (state, msg) -> {
    if (msg instanceof ExitSignal) {
        // Worker crashed, restart it
        var newWorker = new Proc<>(0, handler);
        return state + 1;
    }
    return state;
});

supervisor.trapExits(true);
ProcLink.link(supervisor, worker);
```

### Pattern: Cascading Supervision

```java
// Top-level supervisor
var top = new Supervisor(Strategy.ONE_FOR_ONE, 10, Duration.ofSeconds(60));

// Mid-level supervisor (child of top)
var mid = new Proc<>(0, (state, msg) -> {
    var childSupervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
    return state;
});

// Workers (children of mid-level supervisor)
ProcLink.link(mid, worker1);
ProcLink.link(mid, worker2);
```

## Further Reading

- [How-to: Error Handling](./error-handling.md)
- [Reference: Supervisor API](../reference/supervisor.md)
- [Tutorial: Fault-Tolerant Applications](../tutorials/04-supervision-trees.md)
- [PhD Thesis: Supervision Trees](../phd-thesis-otp-java26.md#section-4-supervision)
