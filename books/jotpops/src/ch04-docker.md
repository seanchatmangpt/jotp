# Chapter 4: Dockerize Your JOTP Application

You packaged a JAR. Now you need to run it in a container that behaves identically on your laptop, in CI, and in production. This chapter covers the three patterns that matter when containerising a JOTP application: making the image a true immutable deployment unit, monitoring the actor system from outside so a health probe never accidentally kills what it is checking, and tuning the JVM for the memory constraints of a container.

The application is TaskFlow. The container image we build will be a multi-stage Docker build that compiles with mvnd in stage one and runs on a minimal JRE 26 image in stage two. The health endpoint is backed by a `ProcMonitor` so it reflects the actual state of the supervision tree, not just whether the HTTP server accepted a TCP connection.

---

### Pattern: THE IMMUTABLE DEPLOYMENT UNIT

**Problem**

A Docker image built on one machine must behave identically on every other machine that runs it. If the image depends on network resources at startup — downloading dependencies, running migrations, fetching configuration — it will behave differently in different environments. The image must be self-contained.

**Context**

Maven (and mvnd) downloads dependencies at build time. If the build runs inside the Docker build context, dependencies are resolved once and baked into the image layer. This is the correct model. But a naive Dockerfile that runs `mvnd package` as a single step rebuilds the entire dependency layer every time a source file changes. Docker layer caching means that only layers *after* the first changed layer are rebuilt. Structure your Dockerfile to copy the `pom.xml` and resolve dependencies before copying source code.

**Solution**

Use a multi-stage Dockerfile. Stage one uses a build image with mvnd and JDK 26. It copies `pom.xml` first, resolves dependencies into a cached layer, then copies source and compiles. Stage two uses a minimal JRE 26 runtime image and copies only the assembled JAR.

**Code Example**

```dockerfile
# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:26-jdk AS builder

# Install mvnd
ARG MVND_VERSION=2.0.0-rc-3
RUN apt-get update -q && apt-get install -y -q curl unzip \
    && curl -fsSL \
       "https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/mvnd-${MVND_VERSION}-linux-amd64.zip" \
       -o /tmp/mvnd.zip \
    && unzip -q /tmp/mvnd.zip -d /opt \
    && ln -s "/opt/mvnd-${MVND_VERSION}-linux-amd64/bin/mvnd" /usr/local/bin/mvnd \
    && rm /tmp/mvnd.zip

WORKDIR /build

# ── Layer 1: Dependency resolution ──────────────────────────────────────────
# Copy only the POM first. This layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvnd dependency:go-offline -q --no-transfer-progress

# ── Layer 2: Compile + test + package ────────────────────────────────────────
# This layer rebuilds whenever any source file changes.
COPY src ./src
RUN mvnd package --no-transfer-progress \
    && ls -lh target/*.jar

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:26-jre AS runtime

LABEL org.opencontainers.image.title="TaskFlow"
LABEL org.opencontainers.image.description="Real-time Kanban board powered by JOTP"
LABEL org.opencontainers.image.version="1.0"

WORKDIR /app

# Non-root user for security
RUN groupadd --system taskflow && useradd --system --gid taskflow taskflow
USER taskflow

# Copy only the fat JAR from the builder stage
COPY --from=builder --chown=taskflow:taskflow \
    /build/target/taskflow-1.0.jar \
    /app/taskflow.jar

# JVM tuning — see THE RESOURCE BUDGET pattern below
ENV JAVA_OPTS="\
    --enable-preview \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active=production"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/taskflow.jar"]
```

```text
# .dockerignore
.git
.claude
target/
*.md
docs/
*.sh
.mvn/
```

Build and run:

```bash
# Build the image
docker build -t taskflow:latest .

# Run with 512 MB memory limit (see THE RESOURCE BUDGET)
docker run \
    --memory=512m \
    --cpus=1 \
    -p 8080:8080 \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/taskflow \
    taskflow:latest
```

Tag and push to a registry:

```bash
docker tag taskflow:latest ghcr.io/seanchatmangpt/taskflow:1.0
docker push ghcr.io/seanchatmangpt/taskflow:1.0
```

**Consequences**

The dependency-resolution layer is cached. On a typical development machine where `pom.xml` rarely changes, a full source rebuild takes the time of `mvnd package` alone — perhaps thirty seconds — rather than the time of `mvnd package` plus downloading a hundred megabytes of JARs. On CI, where the Docker layer cache is warmer than the Maven local repository, the build is faster still.

The non-root user is not optional. Container runtimes in Kubernetes default to running as root unless you configure otherwise, which is a security liability. The `taskflow` user created in the Dockerfile costs nothing and reduces the blast radius of a container compromise.

---

### Pattern: THE HEALTH SENTINEL (ProcMonitor)

**Problem**

Kubernetes and Docker healthchecks probe your application periodically. If the probe fails, the container is restarted. But if the health check itself crashes the actor it is interrogating — because it calls an internal method that throws an exception, or because it shares state with a fragile component — the probe becomes a source of failures rather than a detector of them.

**Context**

JOTP's `ProcMonitor` implements the "linked process" pattern from Erlang OTP. You attach a monitor to a `Proc` by calling `ProcMonitor.monitor(proc, downHandler)`. When the monitored process terminates — for any reason, including normal shutdown and abnormal crash — the `downHandler` callback receives a `Throwable`: `null` means the process stopped normally (via `Proc#stop()`), and a non-null value carries the exception that killed it. The monitor and the monitored process are connected in one direction: if the monitor crashes, the monitored process is unaffected. If the monitored process crashes, the monitor's callback fires.

This makes `ProcMonitor` the right building block for a health endpoint. The monitor's callback runs on the monitored process's virtual thread. It cannot accidentally kill what it is checking.

**Solution**

Create a component that calls `ProcMonitor.monitor(boardRef.proc(), downHandler)` after the supervisor and its children are started. The handler maintains an `AtomicBoolean healthy` flag. When a non-null `Throwable` arrives, it flips the flag to false and logs the reason. A null `Throwable` means graceful shutdown and must not flip the flag. The Spring Boot Actuator `/health` endpoint calls a `HealthIndicator` that reads the flag.

**Code Example**

```java
// taskflow/health/SupervisorHealthMonitor.java
package io.github.seanchatmangpt.taskflow.health;

import io.github.seanchatmangpt.jotp.ProcMonitor;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.taskflow.board.BoardState;
import io.github.seanchatmangpt.taskflow.board.BoardMsg;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SupervisorHealthMonitor {

    private static final Logger log =
        LoggerFactory.getLogger(SupervisorHealthMonitor.class);

    private final ProcRef<BoardState, BoardMsg> boardRef;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicReference<String> lastDownReason =
        new AtomicReference<>("none");

    public SupervisorHealthMonitor(ProcRef<BoardState, BoardMsg> boardRef) {
        this.boardRef = boardRef;
    }

    @PostConstruct
    public void startMonitoring() {
        // ProcMonitor.monitor() takes the raw Proc and a Consumer<Throwable>.
        // null  → process stopped normally (Proc#stop())
        // non-null → process crashed with this exception
        ProcMonitor.monitor(boardRef.proc(), cause -> {
            if (cause == null) {
                // Graceful shutdown — not a health failure
                log.info("Board proc shut down gracefully");
            } else {
                log.error("Board proc crashed: {}", cause.getMessage(), cause);
                healthy.set(false);
                lastDownReason.set("Crash: " + cause.getMessage());
            }
        });
        log.info("ProcMonitor attached to board proc");
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public String lastDownReason() {
        return lastDownReason.get();
    }
}
```

```java
// taskflow/health/BoardSupervisorHealthIndicator.java
package io.github.seanchatmangpt.taskflow.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("boardSupervisor")
public class BoardSupervisorHealthIndicator implements HealthIndicator {

    private final SupervisorHealthMonitor monitor;

    public BoardSupervisorHealthIndicator(SupervisorHealthMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        if (monitor.isHealthy()) {
            return Health.up()
                .withDetail("supervisor", "board-supervisor")
                .withDetail("status", "running")
                .build();
        }
        return Health.down()
            .withDetail("supervisor", "board-supervisor")
            .withDetail("reason", monitor.lastDownReason())
            .build();
    }
}
```

Configure Actuator in `application.properties`:

```properties
# Expose health endpoint with full details
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=always
management.endpoint.health.show-components=always

# The supervisor health indicator participates in the overall status
management.health.boardSupervisor.enabled=true
```

The `/actuator/health` endpoint now returns:

```json
{
  "status": "UP",
  "components": {
    "boardSupervisor": {
      "status": "UP",
      "details": {
        "supervisor": "board-supervisor",
        "status": "running"
      }
    },
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  }
}
```

Configure the Docker healthcheck to use this endpoint:

```dockerfile
# Add to the runtime stage in the Dockerfile
HEALTHCHECK \
    --interval=15s \
    --timeout=5s \
    --start-period=30s \
    --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health | \
        python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d['status']=='UP' else 1)"
```

For Kubernetes, use a separate HTTP liveness and readiness probe rather than the Docker `HEALTHCHECK`:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
```

Add liveness and readiness groups to `application.properties`:

```properties
management.endpoint.health.group.liveness.include=boardSupervisor,ping
management.endpoint.health.group.readiness.include=boardSupervisor,diskSpace
```

**Consequences**

The health probe and the production system are decoupled. The `ProcMonitor` callback fires asynchronously when the monitored process terminates. The health indicator reads an `AtomicBoolean`. Neither operation can cause a crash that triggers a supervisor restart. If the health check HTTP handler itself panics — say, because of an NPE in the JSON serialiser — the board actors are completely unaffected.

The subtlety is the null case. A graceful shutdown of the process (triggered by `SIGTERM` during a rolling deploy) delivers a `null` cause to the callback. A graceful shutdown must not flip `healthy` to `false`. If it did, the readiness probe would fail, Kubernetes would mark the pod as unhealthy during the shutdown window, and the termination would look like a crash in your metrics. Always check `cause != null` before marking the system unhealthy.

---

### Pattern: THE RESOURCE BUDGET (Container JVM Tuning)

**Problem**

The JVM was designed for bare-metal servers with gigabytes of heap and dozens of cores. A container is a different environment: it has explicit memory limits and CPU quotas. An untuned JVM inside a 512 MB container will attempt to size its heap based on the host machine's total memory, allocate too much, and be killed by the OOM killer — which looks, from the outside, like a crash.

**Context**

Java 26 is container-aware. It reads `cgroups` limits and adjusts heap sizing accordingly. But the defaults still need tuning for JOTP's virtual thread workload. Virtual threads use carrier threads from `ForkJoinPool.commonPool()`. The pool size defaults to the number of available processors as seen by the JVM — but in a container, this may be fractional CPUs. ZGC with generational mode (`-XX:+ZGenerational`) is the right garbage collector for latency-sensitive JOTP workloads: it runs concurrently, avoids stop-the-world pauses longer than a millisecond, and scales well to the small heaps typical of containers.

**Solution**

Set `-XX:MaxRAMPercentage` rather than `-Xmx`. This tells the JVM to use a percentage of the container's memory limit as its maximum heap. Set it to 75 — leave 25% for the JVM's non-heap memory (Metaspace, code cache, virtual thread stacks, and the OS itself). Enable preview features with `--enable-preview` because Java 26 preview features — including some virtual thread enhancements — are not available by default. Use ZGC with generational mode.

**Code Example**

Set the JVM flags in the Dockerfile `ENV` (shown in the Dockerfile above) and in a `docker-compose.yml` for local development:

```yaml
# docker-compose.yml
version: "3.9"

services:
  taskflow:
    image: taskflow:latest
    build:
      context: .
      target: runtime
    ports:
      - "8080:8080"
    environment:
      JAVA_OPTS: >-
        --enable-preview
        -XX:MaxRAMPercentage=75.0
        -XX:+UseZGC
        -XX:+ZGenerational
        -Djava.security.egd=file:/dev/./urandom
        -Dspring.profiles.active=development
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/taskflow
    deploy:
      resources:
        limits:
          memory: 512m
          cpus: "1.0"
    depends_on:
      db:
        condition: service_healthy
    healthcheck:
      test: >-
        curl -sf http://localhost:8080/actuator/health ||
        exit 1
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 30s

  db:
    image: postgres:16
    environment:
      POSTGRES_DB: taskflow
      POSTGRES_USER: taskflow
      POSTGRES_PASSWORD: taskflow_dev
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U taskflow"]
      interval: 5s
      timeout: 3s
      retries: 5
```

For production Kubernetes, set resource requests and limits in the `Deployment`:

```yaml
# k8s/taskflow-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: taskflow
spec:
  replicas: 2
  selector:
    matchLabels:
      app: taskflow
  template:
    metadata:
      labels:
        app: taskflow
    spec:
      containers:
        - name: taskflow
          image: ghcr.io/seanchatmangpt/taskflow:1.0
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "1000m"
          env:
            - name: JAVA_OPTS
              value: >-
                --enable-preview
                -XX:MaxRAMPercentage=75.0
                -XX:+UseZGC
                -XX:+ZGenerational
                -Djava.security.egd=file:/dev/./urandom
          ports:
            - containerPort: 8080
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 15
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
```

**Understanding the flags in detail:**

`--enable-preview` — Required for Java 26 preview features. Virtual thread enhancements and certain pattern matching refinements are preview in Java 26. The flag must appear on both the compiler invocation (in `pom.xml`, already configured with `<arg>--enable-preview</arg>`) and the runtime invocation.

`-XX:MaxRAMPercentage=75.0` — In a 512 MB container, this allocates approximately 384 MB to the JVM heap. The remaining 128 MB covers Metaspace (typically 50–80 MB for a Spring Boot application), code cache, virtual thread stacks (each virtual thread uses at most a few KB on the carrier thread stack while parked), and the OS page cache.

`-XX:+UseZGC -XX:+ZGenerational` — ZGC is a concurrent garbage collector that moves objects while application threads are running. Stop-the-world pauses are bounded at under one millisecond regardless of heap size. Generational ZGC (available since Java 21, production-ready in Java 23+) separates short-lived and long-lived objects, substantially reducing the volume of work in each collection cycle. For JOTP applications, where virtual threads live for microseconds and board state records live for the session duration, generational GC delivers significantly better throughput.

`-Djava.security.egd=file:/dev/./urandom` — `SecureRandom` seeding on Linux defaults to `/dev/random`, which can block for several seconds in a container with limited entropy (common in CI and in freshly started VMs). The `./urandom` path (note the dot) still uses the urandom pool but bypasses the blocking behaviour of `/dev/random` while satisfying the JCA security provider's path checks.

**Consequences**

`-XX:MaxRAMPercentage` scales correctly as you resize the container. If you change the memory limit from 512 MB to 1 GB, the heap grows to 768 MB without any flag change. This makes the Dockerfile portable across different deployment sizes.

The penalty for misconfigured memory is severe: the OOM killer terminates the container without a Java stack trace. If your container keeps restarting with exit code 137 (killed by signal 9), the first thing to check is whether `MaxRAMPercentage` is set and whether the container memory limit is large enough for your workload.

Virtual thread count does not map to container memory the way traditional thread count does. In a JOTP application, you may have 10,000 virtual threads active simultaneously. Each parked virtual thread uses a few hundred bytes on the heap for its continuation. 10,000 virtual threads ≈ a few megabytes of heap. This is categorically different from 10,000 platform threads (which would require roughly 80 GB of stack space). Size your container for heap and Metaspace, not for thread stacks.

---

### A Note on the Build Cache

The multi-stage Dockerfile's dependency-resolution layer is only useful if Docker reuses it. Docker reuses a layer if the instruction that created it — and every instruction before it — is identical to a previous build. Since the dependency-resolution layer copies only `pom.xml`, it is invalidated only when `pom.xml` changes. In a project where dependencies change infrequently, this means the vast majority of CI builds skip the network download entirely.

If you use GitHub Actions or another CI system with Docker layer caching, configure the cache:

```yaml
# .github/workflows/build.yml (excerpt)
- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v3

- name: Build and push
  uses: docker/build-push-action@v5
  with:
    context: .
    push: true
    tags: ghcr.io/seanchatmangpt/taskflow:${{ github.sha }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

The `type=gha` cache persists Docker layer cache between workflow runs. Combined with the two-layer Dockerfile structure, this reduces a cold CI build from four minutes to under ninety seconds once dependencies are cached.

---

### What Have You Learned?

- The **Immutable Deployment Unit** is a multi-stage Dockerfile with dependency resolution in its own layer. Copy `pom.xml` first, run `mvnd dependency:go-offline`, then copy source. Layer caching turns expensive Maven downloads into a one-time cost.
- The **Health Sentinel** uses `ProcMonitor` to watch a supervised process without sharing mutable state with the actors it monitors. A `null` cause in the callback means graceful shutdown and must not trigger a health failure — only a non-null `Throwable` indicates a crash.
- Spring Boot Actuator's `HealthIndicator` interface is the right integration point. Wire it to an `AtomicBoolean` that the `ProcMonitor` callback sets. The health endpoint is then a non-blocking read of a flag — it cannot cause a crash.
- The **Resource Budget** starts with `-XX:MaxRAMPercentage=75.0`. Forget `-Xmx` in containers — it does not scale with the memory limit. ZGC with `-XX:+ZGenerational` keeps GC pauses below one millisecond and is the right collector for virtual thread workloads.
- `--enable-preview` must appear in both the compiler configuration (already set in `pom.xml`) and the runtime JVM flags. Missing it at runtime produces a `java.lang.UnsupportedClassVersionError` that is confusing until you see it once.
- Exit code 137 means OOM. Exit code 1 means your application crashed. Know the difference before you spend an hour reading application logs for a JVM that was simply given too little memory.
- `.dockerignore` keeps the build context small. Excluding `.git`, `target/`, and documentation directories can reduce the context from hundreds of megabytes to a few kilobytes — which matters when the Docker daemon is remote (as it is in many CI configurations).
