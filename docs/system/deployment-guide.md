# Deployment Guide

How to package, configure, and run JOTP applications in production — containers, JVM tuning, health checks, and graceful shutdown.

---

## Packaging as a Fat JAR

JOTP applications ship as a single self-contained JAR using Maven's shade plugin:

```bash
mvnd package -Dshade
# Output: target/myapp-1.0-shaded.jar (~15-30 MB depending on dependencies)
```

In `pom.xml`, the shade profile is already configured:

```xml
<profile>
    <id>shade</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <configuration>
                    <transformers>
                        <transformer implementation="...ManifestResourceTransformer">
                            <mainClass>com.example.Application</mainClass>
                        </transformer>
                    </transformers>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

Run the fat JAR:

```bash
java \
  --enable-preview \
  -XX:+UseZGC \
  -Xmx4g \
  -jar target/myapp-1.0-shaded.jar
```

---

## Docker / Container Deployment

### Multi-Stage Dockerfile

```dockerfile
# Build stage — uses mvnd for fast builds
FROM eclipse-temurin:26-jdk AS build
WORKDIR /app

# Cache Maven dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -q

# Build fat JAR
COPY src ./src
RUN mvn package -Dshade -q

# Runtime stage — minimal JRE
FROM eclipse-temurin:26-jre AS runtime
WORKDIR /app

# Non-root user for security
RUN useradd -r -u 1001 jotp
USER jotp

COPY --from=build /app/target/myapp-1.0-shaded.jar app.jar

# Virtual threads need this flag
ENTRYPOINT ["java", \
  "--enable-preview", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

EXPOSE 8080
```

### Docker Compose (with Health Check)

```yaml
version: "3.9"
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - JAVA_OPTS=-Xmx2g
      - jotp.mailbox.capacity=4096
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      timeout: 5s
      retries: 3
      start_period: 30s
    restart: unless-stopped
```

---

## JVM Tuning for Virtual Threads

### Recommended Production Flags

```bash
java \
  --enable-preview \

  # GC: ZGC for low-pause, important for high process counts
  -XX:+UseZGC \
  -XX:ZUncommitDelay=300 \

  # Memory: let JVM scale to container limits
  -XX:MaxRAMPercentage=75.0 \

  # Virtual thread scheduler: match carrier threads to CPU cores
  -Djdk.virtualThreadScheduler.parallelism=4 \

  # Logging: show virtual thread pinning events (for debugging only)
  # -Djdk.tracePinnedThreads=full \

  # JOTP: tune mailbox capacity
  -Djotp.mailbox.capacity=4096 \

  -jar app.jar
```

### Sizing Guidelines

| Use Case | Heap | Carrier Threads | Mailbox |
|----------|------|-----------------|---------|
| 1,000 processes | 512 MB | 2-4 | 1024 |
| 10,000 processes | 2 GB | 4-8 | 1024 |
| 100,000 processes | 8 GB | 8-16 | 512 |
| 1,000,000 processes | 32 GB | 16-32 | 256 |

Rule of thumb: ~4-8 KB per idle process (mailbox + virtual thread stack).

### G1GC Alternative

If ZGC is not available, G1GC is the next best option:

```bash
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=100 \
-XX:G1HeapRegionSize=16m
```

---

## Health Checks and Readiness Probes

### Liveness Probe

A liveness probe checks that the JVM is alive. Use a simple HTTP endpoint:

```java
@Component
public class LivenessEndpoint {
    // Spring Boot Actuator exposes /actuator/health automatically
    // Or implement manually:

    @GetMapping("/health/live")
    public ResponseEntity<String> liveness() {
        return ResponseEntity.ok("UP");
    }
}
```

### Readiness Probe

A readiness probe checks that JOTP processes are initialized and ready:

```java
@Component
public class ReadinessEndpoint {
    private final Supervisor rootSupervisor;

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> readiness() {
        var status = new HashMap<String, String>();
        var allReady = true;

        for (var name : List.of("order-service", "inventory-service", "database")) {
            var ref = ProcessRegistry.whereis(name);
            if (ref.isEmpty()) {
                status.put(name, "NOT_REGISTERED");
                allReady = false;
            } else {
                status.put(name, "UP");
            }
        }

        return allReady
            ? ResponseEntity.ok(status)
            : ResponseEntity.status(503).body(status);
    }
}
```

### Process-Level Health Check

Use `ProcessMonitor` to watch critical processes and update health status:

```java
@Component
public class ProcessHealthMonitor {
    private final Map<String, String> processHealth = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (var criticalProc : List.of("payment", "inventory")) {
            var ref = ProcessRegistry.whereis(criticalProc)
                .orElseThrow(() -> new IllegalStateException(criticalProc + " not registered"));

            processHealth.put(criticalProc, "UP");

            var monitor = ProcessMonitor.monitor(ref);
            monitor.onDown(notification -> {
                processHealth.put(criticalProc, "DOWN: " + notification.reason());
                // Supervisor will restart it; update health when it re-registers
            });
        }
    }

    public Map<String, String> getHealth() {
        return Collections.unmodifiableMap(processHealth);
    }
}
```

---

## Graceful Shutdown

JOTP supervisors drain in-flight messages before shutting down:

```java
// In main() or Spring @PreDestroy
Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
    log.info("Shutdown initiated — draining in-flight requests...");

    // 1. Stop accepting new work (close HTTP listener first)
    httpListenerRef.send(new StopAccepting());

    // 2. Wait for in-flight requests to complete (up to 30s)
    try {
        boolean clean = rootSupervisor.shutdown(Duration.ofSeconds(30));
        if (!clean) {
            log.warn("Shutdown timed out — some requests may be incomplete");
        }
    } catch (Exception e) {
        log.error("Error during shutdown", e);
    }

    log.info("Shutdown complete");
}));
```

### Kubernetes Rolling Update

Configure `terminationGracePeriodSeconds` to match your shutdown timeout:

```yaml
spec:
  template:
    spec:
      terminationGracePeriodSeconds: 60  # Must be > shutdown timeout
      containers:
        - name: app
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 5"]  # Allow load balancer to drain
```

---

## Cloud-Native Considerations

### Environment-Based Configuration

Pass JOTP configuration through environment variables:

```bash
# Docker run / Kubernetes env:
JAVA_OPTS="-Djotp.mailbox.capacity=8192 -Djotp.supervisor.defaultMaxRestarts=20"
```

Or in your `application.properties` (Spring Boot):

```properties
jotp.mailbox.capacity=${JOTP_MAILBOX_CAPACITY:4096}
jotp.supervisor.defaultMaxRestarts=${JOTP_MAX_RESTARTS:10}
```

### 12-Factor App Compliance

JOTP applications are naturally 12-factor compliant:

| Factor | JOTP Approach |
|--------|--------------|
| Config | System properties / env vars |
| Processes | Each `Proc` is an independent process |
| Concurrency | Scale via supervisor tree size |
| Disposability | Fast startup (~1-2s), graceful shutdown |
| Logs | Write to stdout/stderr — let orchestrator aggregate |
| Admin processes | `ProcSys` introspection, JFR profiling |

### Horizontal Scaling

JOTP processes are in-JVM — scale horizontally by running multiple JVM instances behind a load balancer. Use `ProcessRegistry` for in-JVM coordination; use a message broker (Kafka, RabbitMQ) for cross-JVM coordination.

```
Load Balancer
├── JVM Instance 1 (100K processes)
├── JVM Instance 2 (100K processes)
└── JVM Instance 3 (100K processes)
     ↕ Kafka (cross-instance coordination)
```

---

*Back to: [System Documentation](README.md)*
