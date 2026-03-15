# ProcSys - Process Introspection

## Overview

`ProcSys` implements OTP's `sys` module - process introspection, debug tracing, and hot code change without stopping the process.

Joe Armstrong: "You must be able to look inside a running process without stopping it. A process you cannot inspect is a black box you cannot trust in production."

OTP's `sys` module is what makes `gen_server`/`gen_statem` processes **observable** at runtime. This class provides the Java 26 equivalents: get state, suspend/resume, statistics, tracing, logging, and hot state transformation.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `sys:get_state(Pid)` | `ProcSys.getState(proc)` |
| `sys:suspend(Pid)` | `ProcSys.suspend(proc)` |
| `sys:resume(Pid)` | `ProcSys.resume(proc)` |
| `sys:statistics(Pid, get)` | `ProcSys.statistics(proc)` |
| `sys:trace(Pid, true\|false)` | `ProcSys.trace(proc, enable)` |
| `sys:get_log(Pid)` | `ProcSys.getLog(proc)` |
| `sys:handle_debug/4` | `ProcSys.handleDebug(...)` |
| `system_code_change/4` | `ProcSys.codeChange(proc, transformer)` |

## Architecture

```
Caller Process                Target Process
     │                              │
     │──SysRequest(GetState)────────>│
     │                              │ (processes between messages)
     │<────future(state)────────────│
     │                              │
     │──SysRequest(CodeChange)──────>│
     │                              │ (transforms state atomically)
     │<────future(newState)─────────│
```

## System Message Protocol

All `getState` and `codeChange` operations enqueue a `SysRequest` into the process's high-priority sys channel rather than accessing internal fields directly. This mirrors OTP's `{system, From, Request}` protocol where the **process itself** handles system messages between user messages.

**Benefits:**
- Consistent state snapshot (taken between two user messages)
- No race conditions from direct field access
- Process controls when to handle sys requests
- Hot code upgrade without stopping process

## Public API

### Statistics

#### `Stats` Record

```java
public record Stats(long messagesIn, long messagesOut, int queueDepth)
```

Snapshot of process statistics.

**Fields:**
- `messagesIn` - Total messages received since process start
- `messagesOut` - Total messages processed (state transitions) since process start
- `queueDepth` - Current number of messages waiting in the mailbox

#### `statistics()` - Get Statistics Snapshot

```java
public static Stats statistics(Proc<?, ?> proc)
```

Get a point-in-time statistics snapshot - mirrors OTP `sys:statistics(Pid, get)`.

**Behavior:**
- Reads atomic counters maintained by the process loop
- Snapshot is not transactionally consistent (counters read independently)
- Accurate within one message processing cycle

**Example:**
```java
Stats stats = ProcSys.statistics(proc);
System.out.println("Throughput: " + stats.messagesOut() + " messages processed");
System.out.println("Queue depth: " + stats.queueDepth());
```

### State Inspection

#### `getState()` - Fetch Current State

```java
public static <S, M> CompletableFuture<S> getState(Proc<S, M> proc)
```

Asynchronously fetch the current state - mirrors OTP `sys:get_state(Pid)`.

**Behavior:**
- Enqueues `SysRequest.GetState` into process's high-priority sys channel
- Future completes after process finishes current in-flight message
- Ensures consistent snapshot
- If process has terminated, future completes exceptionally with `IllegalStateException`

**Example:**
```java
CompletableFuture<State> future = ProcSys.getState(proc);
future.thenAccept(state -> System.out.println("Current state: " + state));
```

#### `suspend()` - Pause Message Processing

```java
public static void suspend(Proc<?, ?> proc)
```

Pause message processing - mirrors OTP `sys:suspend(Pid)`.

**Behavior:**
- Process will not dequeue further messages until `resume()` is called
- Messages continue to accumulate in the mailbox
- Used in OTP for hot-code upgrades: suspend → swap code → resume

**Example:**
```java
ProcSys.suspend(proc);
// ... perform hot code upgrade ...
ProcSys.resume(proc);
```

#### `resume()` - Resume Message Processing

```java
public static void resume(Proc<?, ?> proc)
```

Resume message processing after `suspend()` - mirrors OTP `sys:resume(Pid)`.

**Example:**
```java
ProcSys.suspend(proc);
// Critical section
ProcSys.resume(proc);
```

### Tracing and Logging

#### `trace()` - Enable/Disable Live Tracing

```java
public static <S, M> void trace(Proc<S, M> proc, boolean enable)
```

Enable or disable live event tracing using default stdout formatter - mirrors OTP `sys:trace(Pid, true|false)`.

**Behavior:**
- When enabled, every message received/processed is printed to `System.out`
- Format: `"proc event = <event>"` (matching OTP `write_debug/3` convention)
- Up to 100 recent events retained for `getLog()`
- `enable = false` detaches observer immediately

**Example:**
```java
ProcSys.trace(proc, true);  // Start tracing
proc.tell(new Message.Work());
// Output: proc event = in: Work(...)
// Output: proc event = out: Work(...) -> State(...)
ProcSys.trace(proc, false); // Stop tracing
```

#### `trace()` - Custom Formatter

```java
public static <S, M> void trace(
    Proc<S, M> proc,
    boolean enable,
    DebugFormatter<M> formatter
)
```

Enable tracing with custom formatter.

**Example:**
```java
DebugFormatter<Message> formatter = (out, event, info) -> {
    out.println("[" + info + "] " + event);
};

ProcSys.trace(proc, true, formatter);
```

#### `getLog()` - Retrieve Event Log

```java
public static <S, M> List<DebugEvent<M>> getLog(Proc<S, M> proc)
```

Retrieve event log from observer attached by `trace()` - mirrors OTP `sys:get_log(Pid)`.

**Returns:**
- Immutable list of most-recent events (oldest first, capped at 100)
- Empty list if no observer installed

**Example:**
```java
ProcSys.trace(proc, true);
// ... process messages ...
List<DebugEvent<Message>> log = ProcSys.getLog(proc);
log.forEach(event -> System.out.println("Event: " + event));
```

### Debug Options (Process-Internal)

#### `debugOptions()` - Create Debug State

```java
public static <M> DebugOptions<M> debugOptions()
public static <M> DebugOptions<M> debugOptions(boolean trace)
public static <M> DebugOptions<M> debugOptions(boolean trace, int maxLog)
```

Create `DebugOptions` for process-internal debugging - mirrors OTP `sys:debug_options([])`.

**Example:**
```java
DebugOptions<Message> deb = ProcSys.debugOptions(true, 100);
```

#### `handleDebug()` - Record Debug Event

```java
public static <M> DebugOptions<M> handleDebug(
    DebugOptions<M> deb,
    DebugFormatter<M> formatter,
    Object info,
    DebugEvent<M> event
)
```

Record a debug event - mirrors OTP `sys:handle_debug(Deb, Func, Info, Event)`.

**Returns:**
- Updated `DebugOptions` with event recorded

**Example:**
```java
deb = ProcSys.handleDebug(deb, formatter, "myproc", new DebugEvent.In<>(msg));
```

### Hot Code Change

#### `codeChange()` - Transform State Atomically

```java
public static <S, M> S codeChange(
    Proc<S, M> proc,
    Function<S, S> transformer
) throws InterruptedException
```

Apply state-transformation function to running process atomically between messages - mirrors OTP's `system_code_change/4` hot code upgrade protocol.

**Behavior:**
- Enqueues `SysRequest.CodeChange` into process's sys channel
- Process applies `transformer` to current state between two user messages
- Returns future with new state
- Process is never paused - transformation applied at next sys-drain checkpoint

**Throws:**
- `InterruptedException` if calling thread interrupted while waiting
- `IllegalStateException` if process terminated or transformation threw

**Example:**

```java
// Erlang: system_code_change(OldState, _Module, _OldVsn, _Extra) ->
//           {ok, migrate(OldState)}.

// Java:
State newState = ProcSys.codeChange(proc, old -> {
    // Migrate from old schema to new schema
    return new NewState(old.field1(), 0);
});
```

## Common Usage Patterns

### 1. Monitor Process Health

```java
class ProcessMonitor {
    void printHealth(Proc<?, ?> proc) {
        Stats stats = ProcSys.statistics(proc);
        System.out.println("Messages in: " + stats.messagesIn());
        System.out.println("Messages out: " + stats.messagesOut());
        System.out.println("Queue depth: " + stats.queueDepth());
    }
}
```

### 2. Snapshot Process State

```java
class StateInspector {
    State getStateSynchronously(Proc<State, ?> proc) {
        try {
            return ProcSys.getState(proc).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state", e);
        }
    }
}
```

### 3. Debug Message Flow

```java
class Debugger {
    void debugProcess(Proc<State, Message> proc, String name) {
        DebugFormatter<Message> formatter = (out, event, info) -> {
            out.println("[" + name + "] " + event.getClass().getSimpleName());
        };

        ProcSys.trace(proc, true, formatter);
    }
}
```

### 4. Hot Code Upgrade

```java
class CodeUpgrader {
    void upgradeProcess(Proc<OldState, Message> proc) {
        try {
            // Suspend to ensure consistent state
            ProcSys.suspend(proc);

            // Transform state from OldState to NewState
            NewState newState = ProcSys.codeChange(proc, old -> {
                return new NewState(old.getData(), 0);
            });

            System.out.println("Upgraded to: " + newState);

            // Resume processing
            ProcSys.resume(proc);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Upgrade interrupted", e);
        }
    }
}
```

### 5. Collect Process Metrics

```java
class MetricsCollector {
    ProcessMetrics collectMetrics(Proc<?, ?> proc) {
        Stats stats = ProcSys.statistics(proc);

        return new ProcessMetrics(
            stats.messagesIn(),
            stats.messagesOut(),
            stats.queueDepth(),
            stats.messagesOut() > 0 ? (double)stats.messagesIn() / stats.messagesOut() : 0.0
        );
    }
}
```

## Thread-Safety Guarantees

- **Non-blocking operations:** Statistics and control operations are non-blocking
- **Atomic state snapshot:** `getState()` returns consistent state between messages
- **Concurrent tracing:** Multiple observers can be attached (last one wins)
- **Thread-safe logs:** Event log synchronization for concurrent access

## Performance Characteristics

- **Statistics read:** O(1) - atomic counter reads
- **State fetch:** O(n) where n = messages in queue (sys request priority)
- **Trace overhead:** ~100 ns per event (ring buffer + formatter)
- **Code change:** O(1) - single state transformation
- **Memory overhead:** ~10 KB per traced process (100 events * ~100 bytes)

## Related Classes

- **`Proc<S,M>`** - Target process for introspection
- **`DebugEvent<M>`** - Sealed hierarchy of debug events
- **`DebugFormatter<M>`** - Formatter for trace output
- **`DebugOptions<M>`** - Process-internal debug state
- **`SysRequest`** - Internal system message protocol

## Best Practices

1. **Use for debugging:** Enable tracing in development, disable in production
2. **Monitor queue depth:** Growing queue indicates bottleneck
3. **Snapshot before changes:** Use `getState()` before code changes
4. **Suspend for upgrades:** Ensure consistent state during hot code upgrade
5. **Log to files:** Use custom `PrintWriter` for file-based tracing

## Design Rationale

**Why sys message protocol instead of direct field access?**

Direct field access has race conditions:

```java
// Bad: race condition!
State state = proc.state;  // May be inconsistent

// Good: consistent snapshot
State state = ProcSys.getState(proc).get();  // Atomic between messages
```

The sys message protocol ensures:
- **Consistency:** State snapshot taken between message boundaries
- **No races:** Process controls when to handle sys requests
- **Hot upgrade:** Process decides when it's safe to upgrade

Joe Armstrong: "The key is that the process itself decides when it is safe to upgrade — between message boundaries."

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `sys:get_state(Pid)` | `ProcSys.getState(proc).get()` |
| `sys:suspend(Pid)` | `ProcSys.suspend(proc)` |
| `sys:statistics(Pid, get)` | `ProcSys.statistics(proc)` |
| `sys:trace(Pid, true)` | `ProcSys.trace(proc, true)` |
| `sys:get_log(Pid)` | `ProcSys.getLog(proc)` |
| `system_code_change/4` | `ProcSys.codeChange(proc, transformer)` |
| `{system, From, Req}` | `SysRequest` (internal) |

## Common Pitfalls

1. **Blocking on getState:** Use timeout to avoid hanging
2. **Forgetting to resume:** Always pair suspend/resume
3. **Tracing in production:** High overhead, use sparingly
4. **Ignoring queue depth:** Growing queue indicates problems
5. **Code change exceptions:** Handle transformation errors gracefully

## Debugging Tips

1. **Enable tracing early:** Trace from process start for full history
2. **Use custom formatters:** Format output for your use case
3. **Monitor queue depth:** Alert on growing queues
4. **Snapshot state:** Capture state before/after changes
5. **Combine with logging:** Use `getLog()` for post-mortem analysis
