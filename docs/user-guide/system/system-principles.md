# System Principles

This guide explains how to construct, start, and operate a JOTP system in production.

---

## Starting and Stopping a JOTP System

A JOTP system is a tree of supervised processes. The entry point is the **root supervisor** — the top of the supervision tree. Everything else is a child.

### Minimal System

```java
public class Application {
    public static void main(String[] args) throws Exception {
        // Root supervisor — the application lifecycle is tied to this
        var root = Supervisor.oneForOne()
            .restartWindow(Duration.ofMinutes(1), 10)
            .add("gateway",  GatewayProc::start)
            .add("database", DatabaseProc::start)
            .add("cache",    CacheProc::start)
            .build();

        // System runs until root supervisor terminates
        root.awaitShutdown();
    }
}
```

### Graceful Shutdown

```java
// Register shutdown hook for SIGTERM / Ctrl+C
Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
    root.shutdown(Duration.ofSeconds(30));  // Drain in-flight requests
}));
```

---

## Supervision Tree Architecture

The supervision tree is the skeleton of every JOTP application. It defines:

1. **Which processes exist** and how they are grouped
2. **What happens on failure** (restart strategy)
3. **When to give up** (restart intensity)

### Recommended Tree Layout

```
RootSupervisor (one_for_one)
├── InfrastructureSupervisor (one_for_all)
│   ├── DatabaseConnectionProc
│   └── ConfigProc
├── ServiceSupervisor (one_for_one)
│   ├── PaymentServiceProc
│   ├── InventoryServiceProc
│   └── NotificationServiceProc
└── ApiGatewaySupervisor (rest_for_one)
    ├── HttpListenerProc
    └── RequestRouterProc
```

**Rules of thumb:**

- Group tightly-coupled processes under `ONE_FOR_ALL` — if any fails, restart all together
- Use `ONE_FOR_ONE` for independent workers
- Use `REST_FOR_ONE` when later processes depend on earlier ones (e.g., router depends on listener)
- Keep trees shallow (2-3 levels); deep trees obscure failure semantics

### Restart Windows

Prevent restart storms by configuring the restart window:

```java
Supervisor.oneForOne()
    .restartWindow(
        Duration.ofMinutes(1),  // Time window
        5                       // Max restarts before supervisor itself fails
    )
```

If restarts exceed the limit within the window, the supervisor terminates and its parent handles the failure — propagating up the tree.

---

## Application Structure (Packages and Module-Info)

### Recommended Package Layout

```
io.example.myapp/
├── module-info.java
├── Application.java           ← main entry point, root supervisor
├── gateway/
│   ├── GatewayProc.java       ← virtual-thread process
│   └── GatewayMsg.java        ← sealed message interface
├── service/
│   ├── PaymentProc.java
│   ├── PaymentMsg.java
│   └── PaymentSupervisor.java ← subsystem supervisor
└── infra/
    ├── DatabaseProc.java
    └── ConfigProc.java
```

### Module Declaration

```java
// module-info.java
module io.example.myapp {
    requires io.github.seanchatmangpt.jotp;
    requires java.logging;
    // No requires spring.* or other frameworks needed for core JOTP
}
```

### Message Types Live with Their Process

Co-locate each process and its sealed message type:

```java
// PaymentMsg.java
public sealed interface PaymentMsg permits Charge, Refund, GetBalance {}
public record Charge(String orderId, BigDecimal amount, ProcRef<Result<?, ?>, ?> replyTo) implements PaymentMsg {}
public record Refund(String orderId, BigDecimal amount) implements PaymentMsg {}
public record GetBalance(String accountId, ProcRef<BigDecimal, ?> replyTo) implements PaymentMsg {}
```

---

## Error Logging and Observability

JOTP processes are opaque virtual threads. Observability comes from:

### 1. ProcSys — Live Process Introspection

```java
// Inspect without stopping the process
var stats = ProcSys.statistics(targetProc);
System.out.println("Messages processed: " + stats.messageCount());
System.out.println("Uptime: " + stats.uptime());
System.out.println("State class: " + stats.stateClass());

// Suspend and resume (for debugging)
ProcSys.suspend(targetProc);
ProcSys.resume(targetProc);
```

### 2. ProcessMonitor — Crash Notifications

```java
// Health checker pattern
var monitor = ProcessMonitor.monitor(criticalProc);
monitor.onDown(notification -> {
    log.error("Critical process {} went down: reason={}",
        notification.pid(), notification.reason());
    alertingService.page("CRITICAL_PROCESS_DOWN");
});
```

### 3. Structured Logging in Handlers

```java
var proc = Proc.start(
    state -> msg -> {
        log.debug("Processing message: type={}, state={}", msg.getClass().getSimpleName(), state);
        return switch (msg) {
            case ProcessMsg m -> handleProcess(state, m);
            // ...
        };
    },
    initialState
);
```

### 4. EventManager for Audit Trails

```java
var auditBus = new EventManager<SystemEvent>();
auditBus.addHandler(new DatabaseAuditHandler());
auditBus.addHandler(new MetricsExportHandler());

// Emit from any process
auditBus.notify(new ProcessStarted(procId, Instant.now()));
auditBus.notify(new MessageProcessed(procId, msgType, duration));
```

---

## Release and Deployment Model

JOTP applications are standard JVM applications — no special runtime required.

### Build a Fat JAR

```bash
mvnd package -Dshade
# Produces: target/jotp-1.0-shaded.jar
```

### Run in Production

```bash
java \
  --enable-preview \
  -XX:+UseZGC \
  -Xmx4g \
  -jar target/myapp-shaded.jar
```

See the [Deployment Guide](deployment-guide.md) for Docker, health checks, and cloud-native patterns.

---

## The "Let It Crash" Philosophy in Java

Traditional Java error handling encourages defensive programming:

```java
// Traditional Java — fight every exception
try {
    result = service.call();
} catch (TimeoutException e) {
    // retry logic...
} catch (ServiceUnavailableException e) {
    // fallback logic...
} catch (UnexpectedStateException e) {
    // recovery logic...
}
```

JOTP inverts this. Processes crash fast; supervisors restart them:

```java
// JOTP — let it crash, supervisor handles recovery
var proc = Proc.start(
    state -> msg -> {
        // Just do the work. Throw if something is wrong.
        return service.call(msg);  // No try-catch needed
    },
    initialState
);

// Supervisor ensures the process restarts if it throws
var sup = Supervisor.oneForOne()
    .add("service-proc", () -> proc)
    .build();
```

**Why this works:**

1. A crashed process gets a **fresh state** on restart — the corrupt state is gone
2. The supervisor's **restart window** prevents infinite crash loops
3. Other processes in the tree **continue running** — no total failure
4. You write **business logic**, not error-recovery scaffolding

**When to still use try-catch:** At system **boundaries** — parsing external JSON, validating HTTP input, reading files. At those edges, use `Result<T,E>` for typed errors instead of exceptions.

```java
var result = Result.of(() -> Json.parse(rawInput));
return switch (result) {
    case Success(var data)  -> handleData(state, data);
    case Failure(var error) -> logAndDiscard(state, error);
};
```

---

*Next: [OTP Design Principles](otp-design-principles.md)*
