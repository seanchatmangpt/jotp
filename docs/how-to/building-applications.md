# Building Applications with JOTP

This guide explains how to structure and build complete applications using the JOTP framework, building on the concepts from the [Application Lifecycle](../../src/main/java/io/github/seanchatmangpt/jotp/examples/ApplicationLifecycleExample.java) example.

---

## Overview

JOTP provides a complete application lifecycle framework inspired by Erlang/OTP's `application` module. This allows you to:

- **Structure applications** as supervision trees
- **Configure applications** with environment variables
- **Control startup** with different start types (NORMAL, TAKEOVER, FAILOVER)
- **Handle failures** with automatic restart strategies
- **Manage lifecycle** with graceful startup and shutdown

---

## Application Architecture

### The Application Callback

Every JOTP application implements the `Application` interface:

```java
public interface Application {
    Result<Supervisor, Exception> start(StartType type, Object... args);
}
```

**Key Concepts:**
- **StartType**: How the application is started (NORMAL, TAKEOVER, FAILOVER)
- **Supervisor**: Root of the supervision tree
- **Result**: Railway-oriented error handling

### Application Configuration

Use `ApplicationConfig` to manage environment-specific settings:

```java
var config = ApplicationConfig.builder()
    .env("max_channels", 100)
    .env("log_level", "info")
    .env("database_url", "jdbc:postgresql://localhost:5432/mydb")
    .build();

ApplicationConfig.setCurrent(config);
```

---

## Building a Complete Application

### Step 1: Define Your Application Class

```java
public class MyChannelApp implements Application {
    private static final System.Logger LOG = System.getLogger(MyChannelApp.class.getName());

    @Override
    public Result<Supervisor, Exception> start(StartType type, Object... args) {
        try {
            LOG.log(System.Logger.Level.INFO, "Starting MyChannelApp with type: " + type);

            // Retrieve configuration
            var config = ApplicationConfig.getCurrent();
            int maxChannels = (Integer) config.env().getOrDefault("max_channels", 100);
            String logLevel = (String) config.env().getOrDefault("log_level", "info");

            // Build supervision tree
            var childSpecs = List.of(
                ChildSpec.builder()
                    .childId("channel-manager")
                    .restart(ChildSpec.Restart.PERMANENT)
                    .shutdown(ChildSpec.Shutdown.TIMEOUT_5000MS)
                    .startFunction(() -> ChannelManager.start(maxChannels))
                    .build(),

                ChildSpec.builder()
                    .childId("connection-pool")
                    .restart(ChildSpec.Restart.TRANSIENT)
                    .shutdown(ChildSpec.Shutdown.TIMEOUT_3000MS)
                    .startFunction(() -> ConnectionPool.start(50))
                    .build()
            );

            // Create root supervisor
            var supervisor = Supervisor.create(
                "my-channel-app",
                Supervisor.RestartStrategy.ONE_FOR_ALL,
                10,
                Duration.ofSeconds(60),
                childSpecs
            );

            return Result.ok(supervisor);

        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Failed to start application", e);
            return Result.error(e);
        }
    }
}
```

### Step 2: Define Worker Processes

```java
public class ChannelManager {
    public static Result<ProcRef<ChannelState, ChannelMessage>, Exception> start(int maxChannels) {
        var initialState = new ChannelState(0, maxChannels, new ArrayList<>());

        return Proc.spawn(
            "channel-manager",
            state -> msg -> switch (msg) {
                case OpenChannel open -> {
                    if (state.activeChannels() < state.maxChannels()) {
                        var newState = state.withChannel(open.channelId());
                        yield Result.ok(newState);
                    } else {
                        yield Result.error(new IllegalStateException("Max channels reached"));
                    }
                }

                case CloseChannel close -> {
                    var newChannels = state.channels().stream()
                        .filter(id -> !id.equals(close.channelId()))
                        .toList();
                    yield Result.ok(state.withChannels(newChannels));
                }

                default -> Result.ok(state);
            },
            initialState
        );
    }
}

record ChannelState(int activeChannels, int maxChannels, List<String> channels) {
    ChannelState withChannel(String channelId) {
        var updated = new ArrayList<>(channels);
        updated.add(channelId);
        return new ChannelState(updated.size(), maxChannels, updated);
    }

    ChannelState withChannels(List<String> channels) {
        return new ChannelState(channels.size(), maxChannels, channels);
    }
}

sealed interface ChannelMessage permits OpenChannel, CloseChannel {
    String channelId();
}

record OpenChannel(String channelId) implements ChannelMessage {}

record CloseChannel(String channelId) implements ChannelMessage {}
```

### Step 3: Use ApplicationController

```java
public class Main {
    public static void main(String[] args) {
        // Build configuration
        var config = ApplicationConfig.builder()
            .env("max_channels", 100)
            .env("log_level", "debug")
            .build();

        ApplicationConfig.setCurrent(config);

        // Load the application
        var result = Application.load(MyChannelApp.class);

        result.match(
            appController -> {
                // Start the application
                var startResult = appController.start();

                startResult.match(
                    supervisor -> {
                        System.out.println("Application started successfully");

                        // Application is now running
                        // Do work here...

                        // Graceful shutdown
                        appController.stop();
                        return null;
                    },
                    error -> {
                        System.err.println("Failed to start: " + error);
                        return null;
                    }
                );

                return null;
            },
            error -> {
                System.err.println("Failed to load application: " + error);
                return null;
            }
        );
    }
}
```

---

## Application Lifecycle

### Startup Modes

**NORMAL**: Standard application startup
```java
appController.start(); // Uses StartType.NORMAL
```

**TAKEOVER**: Take over from a failed node
```java
appController.takeover(); // Uses StartType.TAKEOVER
```

**FAILOVER**: Failover from another node
```java
appController.failover(); // Uses StartType.FAILOVER
```

### Graceful Shutdown

```java
appController.stop(Duration.ofSeconds(30));
```

This will:
1. Stop all children in reverse order
2. Wait for graceful shutdown (timeout configurable)
3. Force-kill any remaining processes

---

## Supervision Strategies

### ONE_FOR_ONE
Only restart the crashed child:

```java
Supervisor.create(
    "app",
    Supervisor.RestartStrategy.ONE_FOR_ONE,
    maxRestarts,
    duration,
    childSpecs
);
```

**Use when**: Children are independent

### ONE_FOR_ALL
Restart all children if any crashes:

```java
Supervisor.create(
    "app",
    Supervisor.RestartStrategy.ONE_FOR_ALL,
    maxRestarts,
    duration,
    childSpecs
);
```

**Use when**: Children have dependencies

### REST_FOR_ONE
Restart the crashed child and all children started after it:

```java
Supervisor.create(
    "app",
    Supervisor.RestartStrategy.REST_FOR_ONE,
    maxRestarts,
    duration,
    childSpecs
);
```

**Use when**: Startup order matters

---

## Child Specifications

### ChildSpec Options

```java
ChildSpec.builder()
    .childId("my-process")           // Unique identifier
    .restart(ChildSpec.Restart.PERMANENT)  // Restart strategy
    .shutdown(ChildSpec.Shutdown.TIMEOUT_5000MS)  // Shutdown timeout
    .startFunction(() -> MyProc.start())  // Start function
    .build()
```

**Restart Types:**
- **PERMANENT**: Always restart
- **TRANSIENT**: Restart only on abnormal exit
- **TEMPORARY**: Never restart

**Shutdown Options:**
- **TIMEOUT_1000MS** through **TIMEOUT__INFINITY**
- **BRUTAL_KILL**: Immediate termination

---

## Configuration Management

### Environment Variables

```java
var config = ApplicationConfig.getCurrent();
int maxChannels = (Integer) config.env().getOrDefault("max_channels", 100);
String dbUrl = (String) config.env().get("database_url");
```

### Configuration Sources

1. **Default values**: Hardcoded in application
2. **Environment variables**: `System.getenv()`
3. **System properties**: `System.getProperty()`
4. **Configuration files**: Load from properties/YAML

---

## Best Practices

### 1. Design for Failure

```java
// Good: Processes handle crashes via supervision
public Result<State, Message> handle(State state, Message msg) {
    try {
        // Process message
        return Result.ok(newState);
    } catch (Exception e) {
        // Let it crash - supervisor will restart
        throw e;
    }
}
```

### 2. Use Typed Messages

```java
// Good: Sealed interface for type safety
sealed interface AppMessage permits Start, Stop, Status {}

// Bad: Untyped messages
Object msg = ...;
```

### 3. Keep State Immutable

```java
// Good: Record with immutable fields
record AppState(int count, List<String> items) {
    AppState withIncrementedCount() {
        return new AppState(count + 1, items);
    }
}
```

### 4. Structure Supervision Trees

```
my-app (root)
├── connection-pool
├── channel-manager
│   ├── channel-1
│   ├── channel-2
│   └── channel-3
└── event-logger
```

### 5. Test in Isolation

```java
@Test
void testChannelManagerStart() {
    var result = ChannelManager.start(10);
    assertTrue(result.isOk());

    var procRef = result.get();
    assertNotNull(procRef);

    // Cleanup
    Proc.exit(procRef);
}
```

---

## Example Applications

See the [ApplicationLifecycleExample](../../src/main/java/io/github/seanchatmangpt/jotp/examples/ApplicationLifecycleExample.java) for a complete working example demonstrating:
- Application callback implementation
- Supervision tree creation
- Configuration management
- Process communication
- Graceful shutdown

---

## Further Reading

- [Supervision Trees](./supervision-and-fault-tolerance.md)
- [Creating Processes](./creating-your-first-process.md)
- [Process Communication](./process-communication.md)
- [State Machines](./state-machine-workflow.md)
- [Testing JOTP Processes](./testing-jotp-processes.md)

---

*Last updated: 2026-03-15*
