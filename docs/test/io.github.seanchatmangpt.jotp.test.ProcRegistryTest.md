# io.github.seanchatmangpt.jotp.test.ProcRegistryTest

## Table of Contents

- [ProcRegistry: Unknown Name Lookup](#procregistryunknownnamelookup)
- [ProcRegistry: Auto-Deregistration on Stop](#procregistryautoderegistrationonstop)
- [ProcRegistry: Explicit Unregister](#procregistryexplicitunregister)
- [ProcRegistry: List All Registered Names](#procregistrylistallregisterednames)
- [ProcRegistry: Name Reuse After Process Death](#procregistrynamereuseafterprocessdeath)
- [ProcRegistry: Duplicate Name Protection](#procregistryduplicatenameprotection)
- [ProcRegistry: Auto-Deregistration on Crash](#procregistryautoderegistrationoncrash)
- [ProcRegistry: Name-Based Process Registration](#procregistrynamebasedprocessregistration)


## ProcRegistry: Unknown Name Lookup

Looking up an unregistered name returns Optional.empty(). This is the safe API - no null checks needed.

```java
var result = ProcRegistry.whereis("no-such-process");

// result.isEmpty() == true
// No NullPointerException risk
```

> [!NOTE]
> Optional return type forces explicit handling of missing processes. This is safer than returning null.

| Key | Value |
| --- | --- |
| `Queried Name` | `no-such-process` |
| `Result` | `Optional.empty()` |
| `Safe` | `Yes (no null)` |

## ProcRegistry: Auto-Deregistration on Stop

Graceful shutdown (stop()) also triggers auto-deregistration. The name is removed when the process terminates normally.

```java
var proc = counter();
ProcRegistry.register("stopper", proc);

// Graceful shutdown
proc.stop();

// Auto-deregistered
await().atMost(Duration.ofSeconds(3))
    .until(() -> ProcRegistry.whereis("stopper").isEmpty());
```

> [!NOTE]
> Auto-deregistration works for any process termination: crash, stop, or system exit. The registry monitors process lifecycle automatically.

| Key | Value |
| --- | --- |
| `Manual Cleanup` | `Not required` |
| `Registry Action` | `Auto-deregistered` |
| `Process Exit` | `Normal (stop())` |

## ProcRegistry: Explicit Unregister

unregister() removes the name from the registry but keeps the process running. Useful for dynamic name changes or temporary registration.

```java
var proc = counter();
ProcRegistry.register("temp-name", proc);

// Remove the name - process continues
ProcRegistry.unregister("temp-name");

// Name is gone
assertThat(ProcRegistry.whereis("temp-name")).isEmpty();

// Process still works
var count = proc.ask(new Msg.Ping()).join();
// count == 1
```

```mermaid
stateDiagram-v2
    [*] --> Registered: register()
    Registered --> Unregistered: unregister()
    Unregistered --> [*]: Process keeps running

    note right of Unregistered
        Name removed
        Process still alive
        Can accept messages
    end note
```

> [!NOTE]
> unregister is for name management, not process control. Use stop() to terminate the process, unregister() just removes the name.

## ProcRegistry: List All Registered Names

registered() returns a snapshot of all currently registered names. Useful for introspection and debugging.

```java
var a = counter();
var b = counter();

ProcRegistry.register("alpha", a);
ProcRegistry.register("beta", b);

// Get all registered names
var names = ProcRegistry.registered();

// names.contains("alpha") == true
// names.contains("beta") == true
```

> [!NOTE]
> The returned set is a snapshot - it won't change if processes are registered later. Call registered() again for the current state.

| Key | Value |
| --- | --- |
| `Registered Names` | `[concurrent-9-866, concurrent-23-866, alpha, concurrent-28-866, concurrent-43-866, concurrent-11-866, concurrent-0-867, concurrent-48-866, concurrent-16-866, concurrent-31-866, concurrent-36-866, concurrent-24-866, concurrent-29-866, concurrent-44-866, concurrent-12-866, concurrent-1-867, concurrent-49-866, concurrent-17-866, concurrent-32-866, concurrent-20-866, concurrent-6-866, concurrent-37-866, concurrent-25-866, concurrent-40-866, concurrent-45-866, concurrent-13-866, concurrent-2-867, concurrent-18-866, concurrent-33-866, concurrent-21-866, beta, concurrent-38-866, concurrent-7-866, concurrent-26-866, concurrent-41-866, concurrent-46-866, concurrent-14-866, concurrent-19-866, concurrent-34-866, concurrent-8-866, concurrent-22-866, concurrent-39-866, concurrent-27-866, concurrent-42-866, concurrent-10-866, concurrent-47-866, concurrent-15-866, concurrent-30-866, concurrent-4-866, concurrent-35-866]` |
| `Snapshot` | `Yes (immutable view)` |
| `Count` | `50` |

## ProcRegistry: Name Reuse After Process Death

Once a process dies and its name is auto-deregistered, the name becomes available for reuse. New processes can register under the same name.

```java
var first = counter();
ProcRegistry.register("reusable", first);

// First process dies
first.stop();

// Wait for auto-deregistration
await().atMost(Duration.ofSeconds(3))
    .until(() -> ProcRegistry.whereis("reusable").isEmpty());

// Register a new process with the same name
var second = counter();
ProcRegistry.register("reusable", second);

// Name now points to the new process
var found = ProcRegistry.<Integer, Msg>whereis("reusable");
// found.get() == second
```

> [!NOTE]
> Name reuse is intentional for service restarts. A new process instance can take over the same name after the previous one dies.

| Key | Value |
| --- | --- |
| `Second Process` | `Registered successfully` |
| `Name Points To` | `New process instance` |
| `First Process` | `Stopped and auto-deregistered` |
| `Name Status` | `Available` |

## ProcRegistry: Duplicate Name Protection

Registering a duplicate name throws IllegalStateException. This prevents accidental name collisions and ensures name uniqueness.

```java
var a = counter();
var b = counter();

ProcRegistry.register("shared-name", a);

// Second registration with same name throws
assertThatThrownBy(() -> ProcRegistry.register("shared-name", b))
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("shared-name");
```

> [!WARNING]
> Name conflicts indicate a configuration error. Two processes are trying to use the same name. Use unique names per process instance.

| Key | Value |
| --- | --- |
| `Protection` | `Name uniqueness enforced` |
| `Second Process` | `Rejected` |
| `Exception Type` | `IllegalStateException` |
| `First Process` | `Registered as 'x'` |

## ProcRegistry: Auto-Deregistration on Crash

When a registered process crashes, it's automatically removed from the registry. No manual cleanup needed.

```java
var proc = counter();
ProcRegistry.register("crasher", proc);

// Verify it's registered
assertThat(ProcRegistry.whereis("crasher").isPresent()).isTrue();

// Crash the process
proc.tell(new Msg.Crash());

// Auto-deregistered - name no longer exists
await().atMost(Duration.ofSeconds(3))
    .until(() -> ProcRegistry.whereis("crasher").isEmpty());
```

```mermaid
stateDiagram-v2
    [*] --> Registered: register(name, proc)
    Registered --> Crashing: proc.crash()
    Crashing --> AutoDeregister: Registry detects death
    AutoDeregister --> [*]: Name removed
```

> [!NOTE]
> Auto-deregistration prevents stale references. A crashed process can't be looked up - the name is immediately available for reuse.

| Key | Value |
| --- | --- |
| `Registry Action` | `Auto-deregistered` |
| `Initial State` | `Registered` |
| `Process Action` | `Crashed` |
| `Name Status` | `Available for reuse` |

## ProcRegistry: Name-Based Process Registration

ProcRegistry provides name-based process discovery, equivalent to Erlang's whereis/1. Register a process with a name, then lookup by that name.

```java
var proc = counter();
ProcRegistry.register("my-counter", proc);

// Find the process by name
var found = ProcRegistry.<Integer, Msg>whereis("my-counter");

// found.isPresent() == true
// found.get() == proc
```

```mermaid
sequenceDiagram
    participant C as Client
    participant R as Registry
    participant P as Process

    C->>R: register("my-counter", proc)
    R->>R: Map name -> proc
    R-->>C: OK

    C->>R: whereis("my-counter")
    R->>R: Lookup name
    R-->>C: Optional[proc]

    style R fill:#51cf66
```

> [!NOTE]
> Registered names are global within the JVM. Use descriptive names like 'user-session-service' or 'order-processor'. Names must be unique.

| Key | Value |
| --- | --- |
| `Registered Name` | `my-counter` |
| `Process Found` | `true` |
| `Same Instance` | `true` |

| Key | Value |
| --- | --- |
| `Process Status` | `Still running` |
| `Can Accept Messages` | `Yes` |
| `Message Processed` | `1` |
| `Name Status` | `Removed from registry` |

---
*Generated by [DTR](http://www.dtr.org)*
