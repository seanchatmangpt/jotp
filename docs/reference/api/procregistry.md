# ProcRegistry - Global Process Name Registry

## Overview

`ProcRegistry` implements a global process name registry - OTP's `register/2`, `whereis/1`, and `unregister/1`. In OTP, every process can be registered under a unique atom name. Other processes look up the name via `whereis/1` to obtain a Pid without needing to pass the Pid explicitly through the call stack. Names are automatically de-registered when the process terminates.

## OTP Equivalence

| Erlang/OTP | Java 26 (JOTP) |
|------------|----------------|
| `register(Name, Pid)` | `ProcRegistry.register(name, proc)` |
| `whereis(Name)` | `ProcRegistry.whereis(name)` |
| `unregister(Name)` | `ProcRegistry.unregister(name)` |
| `registered()` | `ProcRegistry.registered()` |
| Atom name | `String` name |

## Architecture

```
Global Registry (ConcurrentHashMap)
    │
    ├─ "name1" → Proc<S1, M1>
    ├─ "name2" → Proc<S2, M2>
    └─ "name3" → Proc<S3, M3>
         │
         │ (termination callback)
         │
         └──Auto-remove entry on process termination
```

## Public API

### `register()` - Register Process Name

```java
public static void register(String name, Proc<?, ?> proc)
```

Register `proc` under `name`.

**Behavior:**
- Registration is automatically removed when `proc` terminates (normal or abnormal)
- `whereis()` will return empty once the process is gone

**Throws:**
- `IllegalStateException` if `name` is already registered to a living process

**Example:**
```java
Proc<State, Message> proc = new Proc<>(initial, handler);
ProcRegistry.register("worker", proc);

// Later, look up by name
Optional<Proc<State, Message>> found = ProcRegistry.whereis("worker");
```

### `whereis()` - Look Up Process by Name

```java
public static <S, M> Optional<Proc<S, M>> whereis(String name)
```

Look up a process by name.

**Returns:**
- The registered `Proc`, or empty if no process is registered under `name`

**Behavior:**
- If registered process has terminated, eagerly removes stale entry and returns empty
- OTP: dead process is invisible — eagerly remove the stale entry

**Example:**
```java
ProcRegistry.whereis("worker")
    .ifPresent(proc -> proc.tell(new Message.Work()));
```

### `unregister()` - Explicitly Remove Name

```java
public static void unregister(String name)
```

Explicitly remove a name from the registry. The process itself is not stopped.

**Behavior:**
- Mirrors Erlang's `unregister(Name)` BIF
- Safe to call even if the name is not registered (no-op)

**Example:**
```java
ProcRegistry.unregister("worker");  // Remove name, process continues
```

### `registered()` - List All Registered Names

```java
public static Set<String> registered()
```

Returns the set of all currently registered names - mirrors Erlang's `registered()`.

**Returns:**
- A snapshot (not live) of registered names

**Example:**
```java
Set<String> names = ProcRegistry.registered();
System.out.println("Registered processes: " + names);
```

### `reset()` - Clear All Registrations

```java
public static void reset()
```

Clear all registrations - for use in tests only.

**Warning:** Do not use in production code.

## Common Usage Patterns

### 1. Named Service Process

```java
class DatabaseService {
    static void start() {
        Proc<State, Message> proc = new Proc<>(initial, handler);
        ProcRegistry.register("database", proc);
    }

    static void sendQuery(Message query) {
        ProcRegistry.whereis("database")
            .ifPresent(proc -> proc.tell(query));
    }
}
```

### 2. Process Discovery

```java
class ProcessDiscovery {
    static void broadcastMessage(String serviceName, Message msg) {
        // Find process by name and send message
        ProcRegistry.whereis(serviceName)
            .ifPresent(proc -> proc.tell(msg));
    }

    static Optional<Proc<State, Message>> findService(String name) {
        return ProcRegistry.whereis(name);
    }
}
```

### 3. Dynamic Registration

```java
class DynamicWorkerPool {
    void addWorker(String id, Proc<State, Message> worker) {
        String name = "worker_" + id;
        ProcRegistry.register(name, worker);
    }

    void removeWorker(String id) {
        ProcRegistry.unregister("worker_" + id);
    }

    void sendMessageToWorker(String id, Message msg) {
        ProcRegistry.whereis("worker_" + id)
            .ifPresent(worker -> worker.tell(msg));
    }
}
```

### 4. Singleton Process

```java
class SingletonService {
    private static final String NAME = "singleton_service";

    static Proc<State, Message> getOrCreate() {
        Optional<Proc<State, Message>> existing = ProcRegistry.whereis(NAME);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Create and register
        Proc<State, Message> proc = new Proc<>(initial, handler);
        ProcRegistry.register(NAME, proc);
        return proc;
    }
}
```

### 5. Health Checking All Services

```java
class HealthChecker {
    static Map<String, String> checkAllServices() {
        Map<String, String> status = new HashMap<>();
        for (String name : ProcRegistry.registered()) {
            ProcRegistry.whereis(name).ifPresentOrElse(
                proc -> {
                    Stats stats = ProcSys.statistics(proc);
                    status.put(name, "OK (queue: " + stats.queueDepth() + ")");
                },
                () -> status.put(name, "NOT_FOUND")
            );
        }
        return status;
    }
}
```

## Thread-Safety Guarantees

- **ConcurrentHashMap:** Backed by concurrent hash map for thread-safe operations
- **Atomic registration:** `register()` uses `putIfAbsent()` for atomic check-and-act
- **Eager cleanup:** Dead processes removed immediately on lookup
- **Snapshot consistency:** `registered()` returns immutable snapshot

## Automatic Cleanup

When a process is registered, a termination callback is automatically installed:

```java
proc.addTerminationCallback(_ -> REGISTRY.remove(name, proc));
```

This ensures:
- Names are automatically removed when processes terminate
- No stale entries in the registry
- No memory leaks from dead processes

## Performance Characteristics

- **Registration:** O(1) - concurrent hash map insertion
- **Lookup:** O(1) - concurrent hash map lookup
- **Unregister:** O(1) - concurrent hash map removal
- **List all:** O(n) - iterates all registered names
- **Memory overhead:** ~100 bytes per registration (String key + Proc value)

## Scope and Namespacing

**Global scope:** Registration is global (JVM-scoped, not per-supervisor).

**Namespacing:** For scoped name spaces, use separate `Map<String, Proc<?,?>>` instances:

```java
class NamespaceRegistry {
    private final Map<String, Proc<?, ?>> namespace;

    NamespaceRegistry(String prefix) {
        this.namespace = new ConcurrentHashMap<>();
    }

    void register(String name, Proc<?, ?> proc) {
        String fullName = prefix + ":" + name;
        namespace.put(fullName, proc);
    }

    Optional<Proc<?, ?>> whereis(String name) {
        return Optional.ofNullable(namespace.get(prefix + ":" + name));
    }
}

// Usage
NamespaceRegistry services = new NamespaceRegistry("services");
NamespaceRegistry workers = new NamespaceRegistry("workers");

services.register("database", dbProc);
workers.register("worker1", workerProc);
```

## Related Classes

- **`Proc<S,M>`** - Process being registered
- **`ProcRef<S,M>`** - Stable reference that can be registered
- **`ProcSys`** - Process introspection (can be combined with registry)
- **`ConcurrentHashMap`** - Underlying thread-safe map implementation

## Best Practices

1. **Use descriptive names:** Choose names that clearly identify the process
2. **Namespace prefixes:** Use prefixes to avoid collisions (e.g., "services:database")
3. **Check registration:** Always check `Optional` from `whereis()`
4. **Auto-cleanup:** Trust automatic cleanup, don't manually unregister on shutdown
5. **Avoid excessive lookups:** Cache Proc references if calling repeatedly

## Design Rationale

**Why a global registry instead of local names?**

In Erlang/OTP, the process registry is global because:
- Processes need to be discoverable across module boundaries
- Names are unique within the distributed system
- Enables location transparency (process may be on another node)

JOTP preserves this design:
- **Global scope:** JVM-wide visibility (not per-supervisor)
- **String names:** Simple, flexible naming scheme
- **Auto-cleanup:** No stale entries when processes die
- **Type-safe lookup:** Generic `whereis()` maintains type safety

**Alternative: Scoped registries**

For local-only namespacing, create separate registry instances:

```java
class SupervisorRegistry {
    private final Map<String, Proc<?, ?>> children = new ConcurrentHashMap<>();

    void register(String name, Proc<?, ?> proc) {
        children.put(name, proc);
    }

    Optional<Proc<?, ?>> whereis(String name) {
        return Optional.ofNullable(children.get(name));
    }
}
```

## Migration from Erlang

| Erlang | JOTP |
|--------|------|
| `register(Name, Pid)` | `ProcRegistry.register(name, proc)` |
| `whereis(Name)` | `ProcRegistry.whereis(name)` |
| `unregister(Name)` | `ProcRegistry.unregister(name)` |
| `registered()` | `ProcRegistry.registered()` |
| Atom name | `String` name |
| `global:register_name(Name, Pid)` | Custom distributed registry (not yet implemented) |

## Common Pitfalls

1. **Name collisions:** Multiple processes trying to register same name
2. **Race conditions:** Check-then-act is not atomic (use `register()` directly)
3. **Stale lookups:** Process may terminate between `whereis()` and use
4. **Memory leaks:** Forgetting to `unregister()` in custom registries
5. **Global namespace:** All names in single namespace (use prefixes)

## Testing Considerations

The `reset()` method is provided for test cleanup:

```java
@BeforeEach
void setUp() {
    ProcRegistry.reset();  // Clear any previous registrations
}

@Test
void testNamedProcess() {
    Proc<State, Message> proc = new Proc<>(initial, handler);
    ProcRegistry.register("test", proc);

    assertTrue(ProcRegistry.whereis("test").isPresent());
}
```

**Warning:** Never call `reset()` in production code.
