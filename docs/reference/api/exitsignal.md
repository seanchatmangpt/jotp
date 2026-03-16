# ExitSignal - Exit Signal Trapping

## Overview

`ExitSignal` implements OTP exit signals delivered as mailbox messages when a process has enabled exit trapping via `Proc.trapExits(true)`. In OTP, `process_flag(trap_exit, true)` causes incoming EXIT signals to appear in the process mailbox as `{'EXIT', FromPid, Reason}` tuples rather than killing the process. This record is the Java 26 equivalent.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `process_flag(trap_exit, true)` | `proc.trapExits(true)` |
| `{'EXIT', Pid, Reason}` | `ExitSignal(Throwable)` |
| `normal` exit | `ExitSignal(null)` |
| `exit(reason)` | `ExitSignal(exception)` |
| Receive in mailbox | Pattern match in message handler |

## Architecture

```
Linked Process A              Linked Process B
     │                              │
     │<─────link installed─────────>│
     │                              │
     │                        (trap_exits = true)
     │                              │
     │                    (process A crashes)
     │                              │
     │<───ExitSignal(exception)────│
     │  (delivered as mailbox       │
     │   message, not kill)         │
```

## Public API

### Record Definition

```java
public record ExitSignal(Throwable reason)
```

OTP exit signal delivered as a mailbox message when exit trapping is enabled.

**Parameters:**
- `reason` - The exit reason from the crashed linked process (`null` for normal exits)

## Usage

### Enabling Exit Trapping

```java
Proc<State, Message> proc = new Proc<>(initial, handler);
proc.trapExits(true);  // Trap exit signals instead of dying
```

### Pattern Matching on ExitSignal

```java
// Define message type to include ExitSignal
sealed interface Message permits Message.Work, Message.Stop {}

// Handle ExitSignal in message loop
BiFunction<State, Message, State> handler = (state, msg) -> switch (msg) {
    case ExitSignal(var reason) -> {
        if (reason == null) {
            System.out.println("Linked process stopped normally");
        } else {
            System.err.println("Linked process crashed: " + reason.getMessage());
            // Decide what to do - cleanup, restart, etc.
        }
        yield state;
    }
    case Message.Work w -> doWork(state, w);
    default -> state;
};
```

## Common Usage Patterns

### 1. Cleanup After Linked Process

```java
class ResourceGuard {
    static Proc<State, Message> start(Proc<?, ?> resource) {
        Proc<State, Message> guard = new Proc<>(new State(), (state, msg) -> switch (msg) {
            case ExitSignal(var reason) -> {
                // Resource process died, clean up
                System.err.println("Resource died: " + reason);
                cleanup(state);
                yield state;
            }
            default -> state;
        });

        guard.trapExits(true);
        ProcLink.link(guard, resource);
        return guard;
    }
}
```

### 2. Supervision with Custom Logic

```java
class CustomSupervisor {
    static Proc<State, Message> start() {
        Proc<State, Message> supervisor = new Proc<>(new State(), (state, msg) -> switch (msg) {
            case ExitSignal(var reason) -> {
                if (reason == null) {
                    System.out.println("Child stopped normally");
                } else {
                    System.err.println("Child crashed: " + reason);
                    // Restart with backoff
                    restartChild(state);
                }
                yield state;
            }
            default -> state;
        });

        supervisor.trapExits(true);
        return supervisor;
    }
}
```

### 3. Multi-Process Groups

```java
class ProcessGroup {
    static Proc<GroupState, Message> startGroup(List<Proc<?, ?>> members) {
        Proc<GroupState, Message> coordinator = new Proc<>(
            new GroupState(members),
            (state, msg) -> switch (msg) {
                case ExitSignal(var reason) -> {
                    System.err.println("Member died: " + reason);
                    // Remove dead member and restart
                    GroupState newState = removeDeadMember(state, reason);
                    restartMember(newState);
                    yield newState;
                }
                default -> state;
            }
        );

        coordinator.trapExits(true);
        members.forEach(member -> ProcLink.link(coordinator, member));
        return coordinator;
    }
}
```

### 4. Graceful Shutdown

```java
class GracefulShutdown {
    static Proc<State, Message> start() {
        Proc<State, Message> proc = new Proc<>(new State(), (state, msg) -> switch (msg) {
            case ExitSignal(var reason) -> {
                if (reason == null) {
                    // Normal shutdown - clean up gracefully
                    System.out.println("Shutting down gracefully");
                    cleanup(state);
                } else {
                    // Crash - log and maybe restart
                    System.err.println("Unexpected crash: " + reason);
                }
                yield state;
            }
            default -> state;
        });

        proc.trapExits(true);
        return proc;
    }
}
```

### 5. Error Reporting

```java
class ErrorReporter {
    static Proc<State, Message> start() {
        Proc<State, Message> reporter = new Proc<>(new State(), (state, msg) -> switch (msg) {
            case ExitSignal(var reason) -> {
                if (reason != null) {
                    // Send error report to monitoring system
                    ErrorReport report = new ErrorReport(
                        Instant.now(),
                        reason.getClass().getName(),
                        reason.getMessage()
                    );
                    monitoringService.send(report);
                }
                yield state;
            }
            default -> state;
        });

        reporter.trapExits(true);
        return reporter;
    }
}
```

## Exit Semantics

### Normal Exit vs Crash

```java
// Linked process stops normally
linkedProc.stop();  // Normal exit
// → ExitSignal(null) delivered to trapping process

// Linked process crashes
linkedProc throws new RuntimeException("Crash!");
// → ExitSignal(RuntimeException) delivered to trapping process
```

### Linking vs Trapping

```java
Proc<AState, Msg> procA = new Proc<>(initA, handlerA);
Proc<BState, Msg> procB = new Proc<>(initB, handlerB);

// Without trapping: crash propagates
ProcLink.link(procA, procB);
// If procA crashes, procB is killed (and vice versa)

// With trapping: crash becomes message
procB.trapExits(true);
ProcLink.link(procA, procB);
// If procA crashes, procB receives ExitSignal(exception)
// procB decides what to do
```

## Thread-Safety Guarantees

- **Delivery on target thread:** ExitSignal delivered on crashed process's thread
- **Message ordering:** ExitSignal ordered with other messages in mailbox
- **No blocking:** Exit signal delivery is non-blocking
- **Immutable record:** ExitSignal is immutable (record)

## Performance Characteristics

- **Delivery cost:** O(1) - enqueue to mailbox
- **Memory overhead:** ~32 bytes per ExitSignal (record + Throwable reference)
- **No blocking:** Trap prevents process death, no interrupt needed
- **Processing cost:** Handler logic cost (user-defined)

## Related Classes

- **`ProcLink`** - Establishes bidirectional links between processes
- **`ProcMonitor`** - Unilateral DOWN notifications (alternative to trapping)
- **`Proc<S,M>`** - Process with `trapExits(boolean)` method
- **`ProcSys`** - Process introspection (can detect trapped exits)

## Best Practices

1. **Check for null:** `null` reason = normal exit, non-null = crash
2. **Handle both cases:** Normal exit and crash may need different handling
3. **Clean up resources:** Use trapped exits to release resources
4. **Log errors:** Report crashes for monitoring
5. **Decide on propagation:** Choose whether to restart or terminate

## Design Rationale

**Why trap exits instead of using monitoring?**

**Monitoring (`ProcMonitor`):**
- Unilateral observation
- Monitor not killed by target's crash
- Receive DOWN notification
- No relationship needed

**Trapping exits (`ProcLink` + `trapExits`):**
- Bilateral relationship (link)
- Target's crash becomes message
- Process decides what to do
- Can clean up or restart

**When to use each:**

| Use Case | Primitive |
|----------|-----------|
| Observe crashes without dying | `ProcMonitor` |
| Cleanup after linked process | `ExitSignal` + `trapExits()` |
| Implement custom supervision | `ExitSignal` + `trapExits()` |
| Detect termination | `ProcMonitor` |
| Handle crashes gracefully | `ExitSignal` + `trapExits()` |

## Armstrong's Philosophy

> "Trap exits when you need to clean up after a linked process dies, or when you want to make a deliberate decision about whether to propagate the crash."

**Default behavior (no trap):**
- Linked crashes kill the process
- All-or-nothing semantics
- Fault containment

**With trap:**
- Linked crashes become messages
- Process decides what to do
- Custom error handling

This enables sophisticated error handling strategies beyond simple "crash all" semantics.

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `process_flag(trap_exit, true)` | `proc.trapExits(true)` |
| `receive {'EXIT', Pid, Reason} -> ... end` | Pattern match on `ExitSignal` in handler |
| `normal` | `ExitSignal(null)` |
| `exit(reason)` | `ExitSignal(exception)` |
| `unlink(Pid)` | Remove crash callbacks (manual) |

## Common Pitfalls

1. **Forgetting to trap:** Process will die on linked crash
2. **Not checking null:** `null` = normal exit, not error
3. **Blocking on handling:** Handler runs on process's virtual thread
4. **Ignoring crashes:** Always handle ExitSignal explicitly
5. **Infinite loops:** Avoid restarting immediately without backoff

## Debugging Tips

1. **Log all ExitSignals:** Debug unexpected process deaths
2. **Check reason type:** Differentiate exception types
3. **Monitor with ProcSys:** Track process statistics
4. **Test crashes:** Verify trapped exits work as expected
5. **Use tracing:** Enable trace to see message flow

## Advanced Patterns

### Circuit Breaker Pattern

```java
class CircuitBreaker {
    private static final int THRESHOLD = 5;
    private int failures = 0;

    BiFunction<State, Message, State> handler = (state, msg) -> switch (msg) {
        case ExitSignal(var reason) -> {
            if (reason != null) {
                failures++;
                if (failures >= THRESHOLD) {
                    System.err.println("Circuit breaker open!");
                    // Stop restarting
                    yield state.withCircuitOpen(true);
                }
            }
            yield state;
        }
        default -> state;
    };
}
```

### Stateful Supervision

```java
class StatefulSupervisor {
    BiFunction<SupervisorState, Message, SupervisorState> handler = (state, msg) -> switch (msg) {
        case ExitSignal(var reason) -> {
            if (reason == null) {
                // Normal exit - decrement restart count
                yield state.withRestarts(state.restarts() - 1);
            } else {
                // Crash - increment restart count
                int newRestarts = state.restarts() + 1;
                if (newRestarts > state.maxRestarts()) {
                    System.err.println("Max restarts exceeded, giving up");
                    yield state.withGivingUp(true);
                } else {
                    // Restart with backoff
                    restartChild(state);
                    yield state.withRestarts(newRestarts);
                }
            }
        }
        default -> state;
    };
}
```

### Deadlock Detection

```java
class DeadlockDetector {
    BiFunction<State, Message, State> handler = (state, msg) -> switch (msg) {
        case ExitSignal(var reason) -> {
            if (reason instanceof DeadlockException) {
                System.err.println("Deadlock detected!");
                // Take corrective action
                resolveDeadlock(state);
            }
            yield state;
        }
        default -> state;
    };
}
```

## Comparison with Other Languages

| Language | Mechanism | JOTP Equivalent |
|----------|-----------|-----------------|
| Erlang | `trap_exit` | `Proc.trapExits(true)` + `ExitSignal` |
| Akka | `DeathWatch` | `ProcMonitor` |
| Go | No direct equivalent | Manual channel communication |
| Rust | No direct equivalent | Manual channel communication |
| Kotlin | No direct equivalent | Manual channel communication |

JOTP's `ExitSignal` provides Erlang's trapping semantics in Java 26, enabling sophisticated error handling patterns not available in traditional actor frameworks.
