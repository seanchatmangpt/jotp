# EventManager<E> API Reference

**OTP `gen_event` equivalent** — Decouples event producers from consumers with typed broadcasting.

## Overview

`EventManager<E>` implements OTP's `gen_event` behavior: manage a list of event handlers, broadcast events to all handlers, and isolate handler failures. A crashing handler is removed but does **not** kill the event manager.

### Key Characteristics

- **Decoupled Architecture:** Event producers broadcast without knowing consumers
- **Handler Isolation:** Crashing handler removed, manager continues
- **Six Message Types:** Notify, SyncNotify, Add, Delete, Call, Stop
- **Typed Events:** Event type `E` should be sealed interface of records
- **First-Class Process:** Manager is a `Proc` with mailbox (exactly as in OTP)

### OTP Equivalence

| Erlang gen_event | Java EventManager |
|------------------|-------------------|
| `gen_event:start({local,Name})` | `EventManager.start(name)` |
| `gen_event:add_handler/3` | `manager.addHandler(handler)` |
| `gen_event:notify/2` | `manager.notify(event)` |
| `gen_event:sync_notify/2` | `manager.syncNotify(event)` |
| `gen_event:delete_handler/3` | `manager.deleteHandler(handler)` |
| `gen_event:call/4` | `manager.call(handler, event)` |
| `gen_event:stop/1` | `manager.stop()` |

## Type Parameters

- **`<E>`** — Event type. Use a sealed interface of records for type-safe pattern matching.

## Public API

### Factory Methods

#### `start()`

Start a new event manager process with default 5-second timeout.

```java
var manager = EventManager.start();
```

**Returns:** Running `EventManager<E>` instance

**Timeout:** 5 seconds for synchronous operations (`syncNotify`, `deleteHandler`, `call`)

---

#### `start(Duration timeout)`

Start a new event manager with custom timeout.

```java
var manager = EventManager.start(Duration.ofSeconds(10));
```

**Parameters:**
- `timeout` — Timeout duration for synchronous operations; must be positive

**Throws:**
- `NullPointerException` if timeout is null
- `IllegalArgumentException` if timeout is not positive

---

#### `start(String name)`

Start a named event manager and register it.

**OTP equivalent:** `gen_event:start({local, Name})`

```java
var logger = EventManager.start("error-logger");
```

**Registration:** Manager is registered in `ProcRegistry` under `name`. Other processes can look it up via `ProcRegistry.whereis(name)`.

**Auto-unregister:** Registration removed when manager stops or crashes.

**Throws:** `IllegalStateException` if `name` is already registered

---

#### `start(String name, Duration timeout)`

Start a named event manager with custom timeout.

```java
var logger = EventManager.start("error-logger", Duration.ofSeconds(10));
```

---

#### `startLink(String name)`

Start a named event manager as part of a supervision tree.

**OTP equivalent:** `gen_event:start_link({local, Name})`

```java
var alarmHandler = EventManager.startLink("alarm-handler");
```

**Behavior:** Identical to `start(String name)` — provided for API symmetry with OTP.

**Note:** In Java, supervision is handled by `Supervisor` primitive, not linking.

---

### Handler Management

#### `addHandler(Handler<E> handler)`

Register an event handler.

**OTP equivalent:** `gen_event:add_handler/3`

```java
EventHandler handler = new EventHandler();
manager.addHandler(handler);
```

**Behavior:** Handler receives all subsequent events until removed or crashed.

**Thread-safe:** Multiple handlers can be added concurrently

---

#### `addSupHandler(Handler<E> handler)`

Register a supervised event handler.

**OTP equivalent:** `gen_event:add_sup_handler/3`

```java
manager.addSupHandler(handler);
// Handler automatically removed when calling thread terminates
```

**Behavior:**
- Handler is registered normally via `addHandler()`
- Virtual daemon thread monitors calling thread
- When calling thread terminates, handler is automatically removed
- `Handler.terminate(Throwable)` called with exit reason

**Use case:** Handlers tied to process lifecycle (e.g., per-connection event handlers)

**Implementation:** Virtual thread waits on `Thread.currentThread().join()`, then calls `deleteHandler()`.

---

#### `deleteHandler(Handler<E> handler)`

Remove a handler.

**OTP equivalent:** `gen_event:delete_handler/3`

```java
boolean removed = manager.deleteHandler(handler);
```

**Behavior:**
- Calls `Handler.terminate(null)` on removed handler
- Uses manager's configured timeout (default 5 seconds)
- Returns `true` if handler was registered and removed

**Thread-safe:** Handler removal is serialized in manager's event loop

---

#### `deleteHandler(Handler<E> handler, Duration timeout)`

Remove a handler with custom timeout.

```java
boolean removed = manager.deleteHandler(handler, Duration.ofSeconds(10));
```

**Parameters:**
- `handler` — Handler to remove
- `timeout` — Timeout duration; must be positive

**Throws:**
- `IllegalArgumentException` if timeout is null or not positive

---

### Event Broadcasting

#### `notify(E event)`

Broadcast `event` to all handlers asynchronously.

**OTP equivalent:** `gen_event:notify/2`

```java
manager.notify(new ErrorEvent("Database connection failed"));
```

**Behavior:**
- Returns immediately
- Event processed in manager's virtual thread
- Handlers that throw are removed silently
- Manager continues serving other handlers

**Performance:** Lock-free enqueue to manager's mailbox (~50-100ns)

**Use cases:**
- High-throughput event streaming
- Fire-and-forget notifications
- Non-blocking event delivery

---

#### `syncNotify(E event)`

Broadcast `event` and wait until all handlers have processed it.

**OTP equivalent:** `gen_event:sync_notify/2`

```java
try {
    manager.syncNotify(new ShutdownEvent());
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

**Behavior:**
- Uses manager's configured timeout (default 5 seconds)
- Blocks until all handlers complete
- Crashing handlers are removed before completion

**Throws:** `InterruptedException` if calling thread interrupted while waiting

**Use cases:**
- Shutdown notifications
- Configuration changes requiring all handlers
- Testing handler synchronization

---

#### `syncNotify(E event, Duration timeout)`

Broadcast `event` and wait with custom timeout.

```java
try {
    manager.syncNotify(event, Duration.ofSeconds(10));
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

**Parameters:**
- `event` — Event to broadcast
- `timeout` — Timeout duration; must be positive

**Throws:**
- `InterruptedException` if interrupted while waiting
- `IllegalArgumentException` if timeout is null or not positive

---

### Individual Handler Calls

#### `call(Handler<E> handler, E event)`

Synchronously call a specific handler with `event`.

**OTP equivalent:** `gen_event:call/4`

```java
manager.call(handler, new ConfigEvent("new config"));
```

**Behavior:**
- Only the specified handler receives the event
- Other handlers are not notified
- Uses manager's configured timeout (default 5 seconds)
- If handler not registered, no-op

**Use cases:**
- Handler-specific queries
- Per-handler configuration
- Testing individual handlers

---

#### `call(Handler<E> handler, E event, Duration timeout)`

Call a specific handler with custom timeout.

```java
manager.call(handler, event, Duration.ofSeconds(10));
```

**Parameters:**
- `handler` — Specific handler to call
- `event` — Event to deliver
- `timeout` — Timeout duration; must be positive

**Throws:**
- `IllegalArgumentException` if timeout is null or not positive

---

### Lifecycle

#### `stop()`

Shut down the event manager.

**OTP equivalent:** `gen_event:stop/1`

```java
manager.stop();
```

**Behavior:**
- Calls `Handler.terminate(null)` on all registered handlers
- Stops the manager process
- Blocks until manager virtual thread terminates

**Exception handling:** `InterruptedException` is caught and thread interrupt status restored

---

### Non-Event Messages

#### `info(Object info)`

Deliver a non-event info message to all handlers.

**OTP equivalent:** `handle_info/2`

```java
manager.info(new ExitSignal(crashReason));
```

**Behavior:**
- Unlike `notify`, handler that throws from `Handler.handleInfo()` is **not** removed
- Models OTP behavior where `handle_info` errors are logged but do not uninstall handler

**Use cases:**
- Exit signals when manager is linked to other processes
- Timeout notifications
- System messages

---

#### `codeChange(Object oldVsn, Object extra)`

Trigger a hot code upgrade on all handlers.

**OTP equivalent:** `code_change/3`

```java
manager.codeChange("v1.0.0", new UpgradeConfig());
```

**Behavior:**
- Calls `Handler.codeChange(oldVsn, extra)` synchronously on each handler
- Waits for all handlers to complete
- Handlers that throw are silently skipped
- Uses manager's configured timeout (default 5 seconds)

**Use case:** Runtime code upgrades without restarting the manager

---

#### `codeChange(Object oldVsn, Object extra, Duration timeout)`

Trigger hot code upgrade with custom timeout.

```java
manager.codeChange(oldVsn, extra, Duration.ofSeconds(10));
```

**Parameters:**
- `oldVsn` — Old version identifier (e.g., version string)
- `extra` — Extra data for upgrade (application-defined)
- `timeout` — Timeout duration; must be positive

**Throws:** `IllegalArgumentException` if timeout is null or not positive

---

## Nested Types

### `Handler<E>` (Interface)

OTP `gen_event` handler behavior — implement this interface to receive events.

**Methods:**

#### `handleEvent(E event)`

Called for each event broadcast via `notify()` or `syncNotify()`.

```java
@Override
public void handleEvent(ErrorEvent event) {
    logger.error("Error: " + event.message());
}
```

**Exception handling:**
- Exceptions caught by manager
- Handler removed from manager
- Manager continues serving other handlers
- `terminate(Throwable)` called with exception

---

#### `terminate(Throwable reason)`

Called when handler is removed — by `deleteHandler()` or due to crash.

**OTP equivalent:** `terminate/2`

```java
@Override
public void terminate(Throwable reason) {
    if (reason == null) {
        logger.info("Handler stopped normally");
    } else {
        logger.error("Handler crashed", reason);
    }
}
```

**Parameters:**
- `reason` — `null` for normal removal, non-null if crashed

**Default:** Empty method (no-op)

---

#### `handleInfo(Object info)`

Handle non-event messages.

**OTP equivalent:** `handle_info/2`

```java
@Override
public void handleInfo(Object info) {
    if (info instanceof ExitSignal signal) {
        logger.warn("Linked process died: " + signal.reason());
    }
}
```

**Exception handling:**
- Crashing `handleInfo` does **not** remove handler
- Exceptions are silently swallowed
- Models OTP `handle_info` semantics

**Default:** Empty method (no-op)

---

#### `codeChange(Object oldVsn, Object extra)`

Called during hot code upgrade.

**OTP equivalent:** `code_change/3`

```java
@Override
public void codeChange(Object oldVsn, Object extra) {
    logger.info("Upgrading from " + oldVsn);
    // Update internal state for new version
}
```

**Parameters:**
- `oldVsn` — Old version identifier
- `extra` — Extra data from upgrade coordinator

**Default:** Empty method (no-op)

---

## Implementation Details

### Internal Architecture

**Event manager is a `Proc<List<Handler<E>>, Msg<E>>`:**

- **State:** `CopyOnWriteArrayList<Handler<E>>` (thread-safe handler list)
- **Messages:** Sealed `Msg<E>` hierarchy (Notify, SyncNotify, Add, Delete, Call, Stop, Info, CodeChange)
- **Handler:** Pattern matching on sealed `Msg` types routes to appropriate logic

### Message Loop

```java
private static <E> List<Handler<E>> handle(List<Handler<E>> handlers, Msg<E> msg) {
    return switch (msg) {
        case Msg.Add<E>(var h) -> {
            handlers.add(h);
            yield handlers;
        }
        case Msg.Delete<E>(var h, var result) -> {
            boolean removed = handlers.remove(h);
            if (removed) h.terminate(null);
            result.complete(removed);
            yield handlers;
        }
        case Msg.Notify<E>(var event) -> {
            broadcast(handlers, event);
            yield handlers;
        }
        case Msg.SyncNotify<E>(var event, var done) -> {
            broadcast(handlers, event);
            done.complete(null);
            yield handlers;
        }
        case Msg.Call<E>(var h, var event, var done) -> {
            if (handlers.contains(h)) {
                try {
                    h.handleEvent(event);
                } catch (RuntimeException e) {
                    handlers.remove(h);
                    h.terminate(e);
                }
            }
            done.complete(null);
            yield handlers;
        }
        case Msg.Stop<E>() -> {
            for (Handler<E> h : new ArrayList<>(handlers)) {
                h.terminate(null);
            }
            handlers.clear();
            yield handlers;
        }
        case Msg.Info<E>(var info) -> {
            for (Handler<E> h : handlers) {
                try {
                    h.handleInfo(info);
                } catch (RuntimeException ignored) {
                    // handle_info crash does not remove handler
                }
            }
            yield handlers;
        }
        case Msg.CodeChange<E>(var oldVsn, var extra, var done) -> {
            for (Handler<E> h : handlers) {
                try {
                    h.codeChange(oldVsn, extra);
                } catch (RuntimeException ignored) {
                    // code_change errors do not remove handler
                }
            }
            done.complete(null);
            yield handlers;
        }
    };
}
```

### Broadcast Logic

```java
private static <E> void broadcast(List<Handler<E>> handlers, E event) {
    List<Handler<E>> toRemove = new ArrayList<>();
    for (Handler<E> h : handlers) {
        try {
            h.handleEvent(event);
        } catch (RuntimeException e) {
            toRemove.add(h);
            h.terminate(e);
        }
    }
    handlers.removeAll(toRemove);
}
```

**Failure isolation:**
- Crashing handlers collected during iteration
- Removed after iteration completes
- `terminate(Throwable)` called with exception
- Other handlers continue processing

### Thread Safety

- **Handler list:** `CopyOnWriteArrayList` (lock-free reads, synchronized writes)
- **Message mailbox:** `LinkedTransferQueue` (lock-free MPMC)
- **Event loop:** Single-threaded (virtual thread only)
- **addSupHandler monitoring:** Virtual daemon thread per handler

## Common Usage Patterns

### Simple Event Logger

```java
sealed interface LogEvent permits ErrorEvent, WarningEvent, InfoEvent {}
record ErrorEvent(String message) implements LogEvent {}
record WarningEvent(String message) implements LogEvent {}
record InfoEvent(String message) implements LogEvent {}

// Start event manager
var loggerMgr = EventManager.start("error-logger");

// Add handler
loggerMgr.addHandler(new Handler<LogEvent>() {
    @Override
    public void handleEvent(LogEvent event) {
        if (event instanceof ErrorEvent(var msg)) {
            System.err.println("[ERROR] " + msg);
        } else if (event instanceof WarningEvent(var msg)) {
            System.out.println("[WARN] " + msg);
        } else if (event instanceof InfoEvent(var msg)) {
            System.out.println("[INFO] " + msg);
        }
    }
});

// Broadcast events
loggerMgr.notify(new ErrorEvent("Database connection failed"));
loggerMgr.notify(new WarningEvent("High memory usage"));
```

### Multiple Independent Handlers

```java
var manager = EventManager.start();

// File handler
manager.addHandler(new Handler<LogEvent>() {
    @Override
    public void handleEvent(LogEvent event) {
        Files.writeString(Path.of("app.log"), event + "\n", StandardOpenOption.APPEND);
    }
});

// Console handler
manager.addHandler(new Handler<LogEvent>() {
    @Override
    public void handleEvent(LogEvent event) {
        System.out.println(event);
    }
});

// Metrics handler
manager.addHandler(new Handler<LogEvent>() {
    @Override
    public void handleEvent(LogEvent event) {
        if (event instanceof ErrorEvent) {
            errorCounter.increment();
        }
    }
});

// All handlers receive every event
manager.notify(new ErrorEvent("Something failed"));
```

### Supervised Handler (Auto-Cleanup)

```java
public class ConnectionHandler {
    private final EventManager<ConnEvent> eventMgr;

    public ConnectionHandler() {
        this.eventMgr = EventManager.start();
    }

    public void handleConnection(Socket socket) {
        // Add handler supervised by this thread
        eventMgr.addSupHandler(new Handler<ConnEvent>() {
            @Override
            public void handleEvent(ConnEvent event) {
                // Handle connection-specific events
            }

            @Override
            public void terminate(Throwable reason) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        });

        // When handleConnection() returns (thread terminates),
        // handler is automatically removed
    }
}
```

### Alarm Handler with Timeouts

```java
var alarmMgr = EventManager.start("alarm-handler", Duration.ofSeconds(10));

alarmMgr.addHandler(new Handler<AlarmEvent>() {
    @Override
    public void handleEvent(AlarmEvent event) {
        if (event instanceof CriticalAlarm(var msg)) {
            // Send pager notification (with timeout)
            try {
                pagerService.send(msg);
            } catch (Exception e) {
                throw new RuntimeException("Pager failed", e);
            }
        }
    }

    @Override
    public void terminate(Throwable reason) {
        if (reason != null) {
            System.err.println("Alarm handler crashed: " + reason);
            // Restart handler or escalate
        }
    }
});

// Broadcast alarm (non-blocking)
alarmMgr.notify(new CriticalAlarm("Server room temperature high"));
```

### Hot Code Upgrade

```java
// Old version handler
class EventHandlerV1 implements Handler<AppEvent> {
    @Override
    public void handleEvent(AppEvent event) {
        // Version 1 logic
    }

    @Override
    public void codeChange(Object oldVsn, Object extra) {
        if ("v1.0.0".equals(oldVsn)) {
            // Migrate state to v2.0.0
            System.out.println("Migrating to version 2.0.0");
        }
    }
}

// Trigger upgrade
manager.codeChange("v1.0.0", new UpgradeConfig("enable-new-feature"));
```

## Best Practices

### 1. Use Sealed Event Types

```java
// Good: Sealed interface for exhaustive pattern matching
sealed interface Event permits A, B, C {}
record A() implements Event {}
record B() implements Event {}
record C() implements Event {}

// Bad: Unsealed interface
interface Event {} // Missing 'sealed'
```

### 2. Handle Exceptions in Handlers

```java
// Good: Catch and handle expected exceptions
@Override
public void handleEvent(Event event) {
    try {
        processEvent(event);
    } catch (IOException e) {
        // Log but don't crash handler
        logger.error("IO error", e);
    }
}

// Bad: Let unexpected exceptions crash handler
@Override
public void handleEvent(Event event) {
    processEvent(event); // May crash and remove handler
}
```

### 3. Use syncNotify for Critical Events

```java
// Good: Wait for all handlers to process shutdown
manager.syncNotify(new ShutdownEvent());
manager.stop();

// Bad: Stop without waiting
manager.notify(new ShutdownEvent());
manager.stop(); // Handlers may not have processed shutdown
```

### 4. Clean Up Resources in terminate()

```java
@Override
public void terminate(Throwable reason) {
    // Always clean up resources
    try {
        connection.close();
        fileWriter.close();
    } catch (IOException e) {
        logger.error("Failed to clean up", e);
    }

    if (reason != null) {
        logger.error("Handler crashed", reason);
    }
}
```

### 5. Use Appropriate Timeouts

```java
// Fast handlers: Short timeout
var fastMgr = EventManager.start(Duration.ofSeconds(1));

// Slow handlers: Long timeout
var slowMgr = EventManager.start(Duration.ofSeconds(30));

// No timeout guarantee: Use very long timeout
var unsafeMgr = EventManager.start(Duration.ofHours(1));
```

## Gotchas

### 1. Handler Exceptions Remove Handler

```java
manager.addHandler(new Handler<Event>() {
    @Override
    public void handleEvent(Event event) {
        throw new RuntimeException("Handler crashes");
        // Handler is REMOVED from manager!
    }
});
```

**Solution:** Catch expected exceptions:

```java
@Override
public void handleEvent(Event event) {
    try {
        riskyOperation();
    } catch (ExpectedException e) {
        logger.error("Expected error", e); // Handler stays registered
    }
}
```

### 2. handleInfo Crashes Don't Remove Handler

```java
manager.info(new ExitSignal(crashReason));

// Handler that throws from handleInfo:
@Override
public void handleInfo(Object info) {
    throw new RuntimeException("Crash!");
    // Handler is NOT removed (OTP semantics)
}
```

### 3. addSupHandler Ties to Calling Thread

```java
public void setupHandler() {
    manager.addSupHandler(handler);
    // Handler tied to this thread
}
// When setupHandler() returns, handler is removed (thread terminated)
```

**Solution:** Use from long-lived thread or use regular `addHandler()`:

```java
// From main thread (lives forever)
manager.addSupHandler(handler);

// Or use regular addHandler (not tied to thread)
manager.addHandler(handler);
```

### 4. notify() Returns Immediately

```java
manager.notify(new CriticalEvent("System failure"));
// Returns immediately, handlers may not have processed yet!
system.shutdown(); // May shutdown before handlers process event
```

**Solution:** Use `syncNotify()` for critical events:

```java
manager.syncNotify(new CriticalEvent("System failure"));
// Waits for all handlers to complete
system.shutdown();
```

## Related Classes

- **[`Proc`](proc.md)** — Lightweight process (EventManager is built on Proc)
- **[`Supervisor`](supervisor.md)** — Hierarchical process supervision
- **[`ProcRegistry`](../proc_registry.md)** — Global process name table

## Performance Considerations

### Overhead

- **Per manager:** ~2 KB heap (virtual thread + handler list)
- **Per handler:** ~100 bytes (reference in CopyOnWriteArrayList)
- **Event broadcast:** ~1μs per handler (function call overhead)
- **Handler removal:** ~10μs (CopyOnWriteArrayList write)

### Scaling

- **Handlers per manager:** Thousands (limited by memory)
- **Event throughput:** ~1M events/sec per core (lock-free queues)
- **Latency:** ~50-100ns for `notify()` (enqueue only)

### When to Use EventManager

- **Decoupled event broadcasting** (producers don't know consumers)
- **Multiple independent handlers** for same event stream
- **Handler isolation** (crashes don't affect other handlers)
- **Dynamic handler registration** (add/remove at runtime)

### When NOT to Use EventManager

- **Single handler** (use `Proc` directly)
- **Request-response** (use `Proc.ask()`)
- **Stateful event processing** (use `StateMachine`)
- **High-performance pipelines** (use `Proc` with direct messaging)

## See Also

- [JOTP Architecture](../architecture.md)
- [Proc API](proc.md)
- [Supervisor API](supervisor.md)
- [Event Handler Patterns](../patterns/event_handlers.md)
- [OTP gen_event Behaviour](http://erlang.org/doc/man/gen_event.html)
