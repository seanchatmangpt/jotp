# Production Operations with JOTP: The Complete Guide to Operating Fault-Tolerant Java Systems at Scale

**Target Audience:** DevOps engineers, SREs, platform operators
**Prerequisites:** Kubernetes experience, monitoring fundamentals
**Estimated Length:** 280 pages

---

## Table of Contents

**Part I: Operational Excellence (Chapters 1-5)**
- Chapter 1: JOTP Operations Overview
- Chapter 2: Deploying JOTP Applications
- Chapter 3: Monitoring JOTP Systems
- Chapter 4: Observability Deep Dive
- Chapter 5: Runbook: Common Incidents

**Part II: Disaster Recovery (Chapters 6-8)**
- Chapter 6: Backup and Recovery Strategies
- Chapter 7: Geographic Distribution and Multi-Region Deployments
- Chapter 8: Business Continuity Planning

**Part III: Security & Compliance (Chapters 9-11)**
- Chapter 9: Security Operations
- Chapter 10: Compliance and Audit
- Chapter 11: Incident Response and Forensics

**Part IV: Performance Optimization (Chapters 12-13)**
- Chapter 12: Performance Tuning
- Chapter 13: Capacity Planning

---

# Part I: Operational Excellence

# Chapter 1: JOTP Operations Overview

## 1.1 The JOTP Operational Model

JOTP brings Erlang/OTP's "let it crash" philosophy to Java 26, fundamentally changing how we approach operations. Instead of trying to prevent every failure, JOTP embraces failure as a normal operating condition and builds systems that gracefully handle it.

### 1.1.1 Core Operational Characteristics

**Supervisor Trees as the Foundation**

JOTP systems organize processes into supervisor trees, where each supervisor monitors its children and restarts them when they fail. This creates a self-healing system that automatically recovers from failures without human intervention.

```java
// Typical production supervisor tree
var paymentSupervisor = Supervisor.create(
    SupervisorStrategy.oneForOne(),
    List.of(
        ChildSpec.of("payment-gateway",
            () -> Proc.spawn(PaymentGateway::new,
                PaymentGatewayHandler::new)),
        ChildSpec.of("fraud-detector",
            () -> Proc.spawn(FraudDetector::new,
                FraudDetectorHandler::new)),
        ChildSpec.of("transaction-logger",
            () -> Proc.spawn(TransactionLogger::new,
                TransactionLoggerHandler::new))
    )
);
```

**Key operational implications:**
- Individual process failures are isolated to their subtree
- Restart latency is measured in microseconds (200 µs average)
- No cascading failures when properly configured
- Automatic recovery without manual intervention

### 1.1.2 The 99.95%+ SLA Architecture

JOTP enables five-nines availability (99.95%+ = 43.8 minutes downtime/year) through:

1. **Process Isolation**: Each process runs in its own virtual thread with an isolated mailbox
2. **Supervision Trees**: Hierarchical restart strategies prevent cascading failures
3. **Let It Crash**: Fast failure detection and recovery instead of complex error handling
4. **Stateless Workers**: Easy horizontal scaling without complex coordination

**SLA Calculation Example:**

```
Process failure rate: 1 failure per 10,000 messages
Supervisor restart time: 200 µs
Message rate: 10,000 msg/sec

Downtime per failure = 200 µs
Failures per hour = 3.6 (10,000 msg/sec * 3600 sec / 10,000,000 MTBF)
Total downtime per hour = 720 µs
Availability = 1 - (720 µs / 3,600,000,000 µs) = 99.99999998%
```

Even with pessimistic assumptions, JOTP achieves >99.99% availability through fast restarts.

### 1.1.3 Operational Metrics at a Glance

| Metric | Typical Value | Target | Alert Threshold |
|--------|---------------|--------|-----------------|
| Supervisor restart rate | 0.1-1 restarts/hour | <5/hour | >10/hour |
| Process mailbox size | 0-10 messages | <100 | >1000 |
| Ask timeout rate | <0.01% | <0.1% | >1% |
| Process restart latency | 150-250 µs | <500 µs | >1 ms |
| Heap usage per process | 1-10 MB | <100 MB | >500 MB |
| Virtual thread count | 10,000-100,000 | <1M | >10M |

## 1.2 Key Operational Concepts

### 1.2.1 Process Lifecycles

JOTP processes follow a predictable lifecycle that operators must understand:

```
spawn → running → [normal exit | crash | timeout]
              ↓
         supervisor monitors
              ↓
         [restart | terminate] based on strategy
```

**State transitions operators will see:**
- `starting`: Process initialization (typically <1 ms)
- `running`: Normal operation, processing messages
- `terminating`: Graceful shutdown (cleanup handlers)
- `restarting`: Supervisor-initiated restart (200 µs)
- `exited`: Final state, can be `normal` or `abnormal`

### 1.2.2 Mailbox Monitoring

Every JOTP process has a mailbox that operators must monitor:

**What to watch:**
- **Mailbox size**: Growing queue indicates blocked consumer
- **Message age**: Old messages indicate slow processing
- **Message types**: Certain message types may indicate problems

**Example: detecting mailbox issues**

```java
// ProcSys inspection reveals mailbox state
var procState = ProcSys.getState(pid);
System.out.println("Mailbox size: " + procState.mailboxSize());
System.out.println("Oldest message age: " + procState.oldestMessageAge());
```

**Alerting rule example:**
```yaml
- alert: HighMailboxSize
  expr: jotp_process_mailbox_size > 1000
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Process {{ $labels.process }} has large mailbox"
    description: "Mailbox size {{ $value }} indicates blocked consumer"
```

### 1.2.3 Supervisor Strategies

Operators must understand the four supervisor strategies:

**ONE_FOR_ONE** (most common):
- Only the crashed child restarts
- Use for: Independent workers where one doesn't affect others
- Example: Payment processing workers

```java
var strategy = SupervisorStrategy.oneForOne(
    10,      // max restarts
    Duration.ofSeconds(5),  // period
    Duration.ofSeconds(60)  // intensity window
);
```

**ONE_FOR_ALL**:
- All children restart when any crashes
- Use for: Tightly coupled processes with shared state
- Example: Database connection pool + cache

**REST_FOR_ONE**:
- Crashed child + all started after it restart
- Use for: Ordered dependencies
- Example: Pipeline stages

**SIMPLE_ONE_FOR_ONE**:
- Dynamic homogeneous pools
- Use for: Ephemeral task workers
- Example: Request handlers

### 1.2.4 Process Tracing and Debugging

JOTP provides `ProcSys` for non-invasive inspection:

**Available operations:**
```java
// Get current state (without killing process)
var state = ProcSys.getState(pid);

// Suspend/resume for debugging
ProcSys.suspend(pid);
// ... inspect state
ProcSys.resume(pid);

// Get statistics
var stats = ProcSys.getStatistics(pid);
System.out.println("Messages processed: " + stats.processedCount());
System.out.println("Uptime: " + stats.uptime());
```

**Critical operator rule:** Never use `Proc.getState()` from application code during production. Always use `ProcSys` for inspection.

## 1.3 Monitoring Stack Architecture

### 1.3.1 Three-Layer Monitoring Model

**Layer 1: Infrastructure Monitoring**
- Host: CPU, memory, disk, network
- Container: Resource limits, OOM kills
- JVM: Heap, GC, thread counts

**Layer 2: JOTP Process Monitoring**
- Process counts and restarts
- Mailbox sizes and ages
- Ask timeouts and failures
- Supervisor health

**Layer 3: Application Monitoring**
- Business metrics (orders processed, payments successful)
- End-to-end latency
- Error rates by operation type
- Customer-facing SLAs

### 1.3.2 Recommended Tooling

**Metrics Collection:**
- Prometheus + JOTP exporter (built-in)
- Micrometer integration for Spring Boot apps
- OpenTelemetry for distributed tracing

**Visualization:**
- Grafana dashboards (pre-built configs in Chapter 3)
- Kibana for log aggregation

**Alerting:**
- Alertmanager for routing alerts
- PagerDuty/OpsGenie for on-call rotation
- Slack/Teams for notifications

## 1.4 Key Metrics Dashboard

Every JOTP deployment needs a core metrics dashboard showing:

### 1.4.1 System Health Panel

```
┌─────────────────────────────────────────────────────────────┐
│ JOTP System Health                    Last updated: 2s ago  │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Total Processes:    12,456    ▲ 2.3%  │  Uptime: 45d 12h  │
│  Active Supervisors: 156       ▬ 0.0%  │  Region: us-east-1 │
│  Restarts (1h):      23        ▼ 15%   │  Zone: 3a         │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ Supervisor Restart Rate (last hour)                     │ │
│  │ 25 ┤                                                    │ │
│  │ 20 ┤     ●●●                                            │ │
│  │ 15 ┤   ●●●   ●●●                                        │ │
│  │ 10 ┤ ●●●       ●●●●●                                    │ │
│  │  5 ┤●●             ●●●●●●                               │ │
│  │  0 └─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─┬─                        │ │
│  │     0 1 2 3 4 5 6 7 8 9 10 11 12 (hours)              │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 1.4.2 Process Health Panel

```
┌─────────────────────────────────────────────────────────────┐
│ Process Health                              Status: HEALTHY │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─────────────────┬──────────┬──────────┬─────────────┐   │
│  │ Process Type    │ Running  │ Mailbox  │ Restarts/h  │   │
│  ├─────────────────┼──────────┼──────────┼─────────────┤   │
│  │ payment-gateway │ 8        │ 12       │ 0.5         │   │
│  │ fraud-detector  │ 16       │ 3        │ 0.1         │   │
│  │ txn-logger      │ 4        │ 1,234    │ 15.2 ⚠️     │   │
│  │ notification    │ 32       │ 0        │ 0.0         │   │
│  └─────────────────┴──────────┴──────────┴─────────────┘   │
│                                                               │
│  ⚠️ txn-logger: High mailbox size + restart rate            │
│     Likely blocked on database I/O                          │
└─────────────────────────────────────────────────────────────┘
```

### 1.4.3 Mailbox Analysis Panel

```
┌─────────────────────────────────────────────────────────────┐
│ Mailbox Analysis                                             │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  Total Messages: 1,234,567                                   │
│  Avg Queue Depth: 23.4                                       │
│  P95 Queue Depth: 156                                        │
│  P99 Queue Depth: 1,892                                      │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ Mailbox Size Distribution                               │ │
│  │                                                         │ │
│  │ 2000 ┤                                                  │ │
│  │ 1500 ┤  █                                               │ │
│  │ 1000 ┤  ██  █                                           │ │
│  │  500 ┤  ████████                                        │ │
│  │    0 ┤███████████████████████████████                   │ │
│  └─────────────────────────────────────────────────────────┘ │
│         0    500   1000   1500   2000   2500                │
│                                                               │
│  Top Processes by Queue Size:                                │
│  1. txn-logger:1 (1,234 msg) - DB writer blocked            │
│  2. payment-processor:7 (456 msg) - API throttling          │
│  3. email-sender:12 (234 msg) - SMTP slow                   │
└─────────────────────────────────────────────────────────────┘
```

## 1.5 Exercise: Set Up Monitoring Stack

**Goal:** Deploy a complete monitoring stack for JOTP in 30 minutes.

### Step 1: Deploy Prometheus

Create `prometheus.yml`:
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'jotp'
    static_configs:
      - targets: ['localhost:9095']
    metrics_path: '/metrics'
```

Deploy:
```bash
docker run -d \
  --name prometheus \
  -p 9090:9090 \
  -v $(pwd)/prometheus.yml:/etc/prometheus/prometheus.yml \
  prom/prometheus
```

### Step 2: Deploy Grafana

```bash
docker run -d \
  --name grafana \
  -p 3000:3000 \
  grafana/grafana
```

Access: http://localhost:3000 (admin/admin)

### Step 3: Configure JOTP Metrics Exporter

Add to application:
```java
var exporter = JotpPrometheusExporter.create()
    .port(9095)
    .start();
```

Verify: http://localhost:9095/metrics

### Step 4: Import Dashboards

1. Login to Grafana
2. Add Prometheus datasource: http://prometheus:9090
3. Import dashboard from Chapter 3

### Step 5: Verify Data Flow

```bash
# Check Prometheus targets
curl http://localhost:9090/api/v1/targets

# Check metrics are available
curl http://localhost:9095/metrics | grep jotp

# Send test traffic
curl http://localhost:8080/api/test-load

# Verify metrics in Grafana
```

**Success criteria:**
- Prometheus scraping JOTP metrics ✓
- Grafana displaying real-time data ✓
- No data gaps in last 10 minutes ✓
- Alerts configured (Chapter 3) ✓

## 1.6 Common Operational Mistakes

### Mistake 1: Over-Monitoring

**Problem:** Collecting too many metrics creates noise and storage costs.

**Solution:** Focus on actionable metrics:
- Mailbox size > 1000: action needed
- Process restarts > 10/hour: investigate
- Ask timeout rate > 1%: SLA at risk

### Mistake 2: Ignoring Supervisor Restarts

**Problem:** Assuming restarts are "normal" and ignoring patterns.

**Solution:** Alert on restart rate increases:
```yaml
- alert: SupervisorRestartRate
  expr: rate(jotp_supervisor_restarts[5m]) > 0.1
  annotations:
    summary: "Supervisor restart rate increased"
```

### Mistake 3: Blocking Operations in Processes

**Problem:** Synchronous I/O in process handlers blocks message processing.

**Solution:** Use async I/O or separate processes for blocking ops:
```java
// Bad: blocks mailbox
public State handle(State s, BlockingDbCall msg) {
    db.call();  // blocks!
    return s;
}

// Good: async processing
public Transition<State, Message> handle(State s, AsyncDbCall msg) {
    asyncDb.call().thenAccept(result ->
        self().tell(new DbResult(result)));
    return Transition.keep();
}
```

### Mistake 4: No Circuit Breakers

**Problem:** Downstream services hang, causing mailbox buildup.

**Solution:** Implement circuit breakers (Chapter 5):
```java
var gateway = CircuitBreaker.of("payment-gateway",
    CircuitBreakerConfig.ofDefaults()
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(30))
);
```

## 1.7 Summary

JOTP operations differs from traditional Java operations in three key ways:

1. **Fast failure is expected:** Embrace 200 µs restarts instead of complex error handling
2. **Supervision is automatic:** Let trees self-heal, focus on systemic issues
3. **Mailbox size is king:** The single best indicator of process health

**Key takeaways:**
- Monitor mailbox sizes religiously
- Alert on restart rate changes, not absolute values
- Use ProcSys for inspection, never kill processes to debug
- Set up monitoring before deploying to production

**Next chapter:** Deploying JOTP applications to production with Docker and Kubernetes.

---

# Chapter 2: Deploying JOTP Applications

## 2.1 Containerization Strategy

### 2.1.1 Docker Image Best Practices

JOTP applications have specific containerization requirements due to virtual threads and supervisor trees.

**Base Image Selection:**

```dockerfile
# Multi-stage build for optimal image size
FROM eclipse-temurin:26-jre-alpine AS builder

# Build stage
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw package -DskipTests

# Runtime stage (minimal)
FROM eclipse-temurin:26-jre-alpine
RUN apk add --no-cache libc6-compat

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Create non-root user for security
RUN addgroup -S jotp && adduser -S jotp -G jotp
USER jotp

# Enable virtual threads (Java 26)
ENV JAVA_OPTS="--enable-preview -XX:+UseVirtualThreads"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD curl -f http://localhost:8080/health || exit 1

EXPOSE 8080 9095

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Critical JVM flags for JOTP:**
```bash
--enable-preview              # Required for virtual threads (Java 26)
-XX:+UseVirtualThreads        # Enable virtual threads by default
-XX:ActiveProcessorCount=2    # Limit carriers for containers
-Xmx512m                      # Set heap limit (no swap)
-XX:+UseStringDeduplication   # Reduce memory pressure
-XX:MaxGCPauseMillis=200      # Target GC pause time
```

### 2.1.2 Multi-Stage Build Example

**Dockerfile (production-ready):**

```dockerfile
# ========================
# Stage 1: Build
# ========================
FROM maven:3.9-eclipse-temurin-26 AS build

WORKDIR /build

# Copy dependency definitions first (better layer caching)
COPY pom.xml .
RUN mvnd dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvnd package -DskipTests -B

# ========================
# Stage 2: Runtime
# ========================
FROM eclipse-temurin:26-jre-alpine

# Install runtime dependencies
RUN apk add --no-cache \
    curl \
    tzdata \
    ca-certificates

# Set timezone
ENV TZ=UTC

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /build/target/jotp-*.jar app.jar

# Create dedicated user
RUN addgroup -S jotp && \
    adduser -S jotp -G jotp && \
    chown -R jotp:jotp /app

USER jotp

# JVM tuning for containers
ENV JAVA_OPTS=""
ENV JAVA_OPTS_APPEND="--enable-preview -XX:+UseVirtualThreads"

# Expose ports
EXPOSE 8080 9095

# Health checks
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Optimize for fast startup
ENTRYPOINT ["sh", "-c", "java \
  --enable-preview \
  -XX:+UseVirtualThreads \
  -XX:ActiveProcessorCount=2 \
  -Xmx512m \
  -Xss256k \
  -XX:+UseStringDeduplication \
  -XX:+ExitOnOutOfMemoryError \
  $JAVA_OPTS \
  -jar app.jar"]
```

**Build and test:**
```bash
# Build image
docker build -t jotp-payment:1.0.0 .

# Test locally
docker run -d --name jotp-test \
  -p 8080:8080 \
  -p 9095:9095 \
  -e JAVA_OPTS="-Djotp.env=dev" \
  jotp-payment:1.0.0

# Verify health
docker exec jotp-test curl -s http://localhost:8080/health

# Check logs
docker logs -f jotp-test
```

### 2.1.3 Image Size Optimization

**Techniques to reduce image size:**

1. **Use JRE instead of JDK:** Saves ~200 MB
2. **Alpine Linux:** Saves ~100 MB vs. Debian
3. **Multi-stage builds:** Exclude build tools
4. **JAR stripping:** Remove dev dependencies

**Example:分层依赖 JAR (Layered JAR)**

```dockerfile
# Use Spring Boot layered JARs for better caching
FROM eclipse-temurin:26-jre-alpine
WORKDIR /app

# Extract layers
COPY --from=builder /build/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Copy layers in order of change frequency
COPY dependencies/
COPY spring-boot-loader/
COPY snapshot-dependencies/
COPY application/

ENTRYPOINT ["java", "--enable-preview", "-XX:+UseVirtualThreads", "org.springframework.boot.loader.JarLauncher"]
```

**Size comparison:**
- Full JDK image: ~650 MB
- Optimized JRE: ~180 MB
- Alpine + layered: ~140 MB

## 2.2 Kubernetes Deployment

### 2.2.1 Deployment Manifest

**deployment.yaml:**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-payment
  labels:
    app: jotp-payment
    version: v1
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: jotp-payment
  template:
    metadata:
      labels:
        app: jotp-payment
        version: v1
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "9095"
        prometheus.io/path: "/metrics"
    spec:
      containers:
      - name: jotp-payment
        image: your-registry/jotp-payment:1.0.0
        imagePullPolicy: Always

        # Resource limits (critical for virtual threads)
        resources:
          requests:
            cpu: "500m"
            memory: "512Mi"
          limits:
            cpu: "2000m"
            memory: "1Gi"

        # Environment variables
        env:
        - name: JAVA_OPTS
          value: "--enable-preview -XX:+UseVirtualThreads"
        - name: JOTP_ENV
          value: "production"
        - name: OTEL_SERVICE_NAME
          value: "jotp-payment"
        - name: OTEL_EXPORTER_OTLP_ENDPOINT
          value: "http://otel-collector:4317"

        # Ports
        ports:
        - name: http
          containerPort: 8080
          protocol: TCP
        - name: metrics
          containerPort: 9095
          protocol: TCP

        # Startup probe (slow-starting apps)
        startupProbe:
          httpGet:
            path: /actuator/health/startup
            port: 8080
          failureThreshold: 30
          periodSeconds: 10

        # Liveness probe (restart if hung)
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          failureThreshold: 3
          periodSeconds: 10
          timeoutSeconds: 5

        # Readiness probe (stop traffic if not ready)
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          failureThreshold: 3
          periodSeconds: 5
          timeoutSeconds: 3

        # Graceful shutdown
        lifecycle:
          preStop:
            exec:
              command:
              - "sh"
              - "-c"
              - "curl -X POST http://localhost:8080/actuator/shutdown && sleep 15"

      # Graceful termination period
      terminationGracePeriodSeconds: 30

      # Affinity rules (spread across zones)
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - jotp-payment
              topologyKey: topology.kubernetes.io/zone

      # Node selector (dedicated nodes if possible)
      nodeSelector:
        workload: jotp

      # Tolerations for dedicated nodes
      tolerations:
      - key: "dedicated"
        operator: "Equal"
        value: "jotp"
        effect: "NoSchedule"
---
apiVersion: v1
kind: Service
metadata:
  name: jotp-payment
  labels:
    app: jotp-payment
spec:
  type: ClusterIP
  ports:
  - port: 8080
    targetPort: 8080
    protocol: TCP
    name: http
  - port: 9095
    targetPort: 9095
    protocol: TCP
    name: metrics
  selector:
    app: jotp-payment
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: jotp-payment
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: jotp-payment
```

### 2.2.2 Horizontal Pod Autoscaler

**hpa.yaml:**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: jotp-payment-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: jotp-payment
  minReplicas: 3
  maxReplicas: 20
  metrics:
  # Scale on CPU usage
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70

  # Scale on memory usage
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80

  # Scale on custom metric (JOTP process count)
  - type: Pods
    pods:
      metric:
        name: jotp_process_count
      target:
        type: AverageValue
        averageValue: "10000"

  # Scale on custom metric (mailbox backlog)
  - type: Pods
    pods:
      metric:
        name: jotp_mailbox_size_p95
      target:
        type: AverageValue
        averageValue: "100"

  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
      - type: Percent
        value: 100
        periodSeconds: 30
      - type: Pods
        value: 4
        periodSeconds: 30
      selectPolicy: Max
```

### 2.2.3 Resource Management Strategies

**CPU Allocation for Virtual Threads:**

JOTP uses virtual threads, which are lightweight but require carrier threads for blocking operations.

**Guidelines:**
```yaml
resources:
  requests:
    cpu: "500m"      # 0.5 CPU cores
    memory: "512Mi"  # 512 MB heap
  limits:
    cpu: "2000m"     # 2 CPU cores
    memory: "1Gi"    # 1 GB heap
```

**Why these values:**
- **CPU request (500m):** Minimum for carrier thread pool
- **CPU limit (2000m):** Headroom for GC and I/O
- **Memory request (512Mi):** Typical heap for 10K processes
- **Memory limit (1Gi):** Prevent OOM kills, allow GC headroom

**Tuning for workloads:**

| Workload Type | CPU Request | CPU Limit | Memory Request | Memory Limit |
|---------------|-------------|-----------|----------------|--------------|
| CPU-intensive | 1000m | 4000m | 256Mi | 512Mi |
| I/O-intensive | 500m | 2000m | 512Mi | 2Gi |
| Mixed | 500m | 2000m | 512Mi | 1Gi |
| Low-traffic | 250m | 1000m | 256Mi | 512Mi |

### 2.2.4 ConfigMaps and Secrets

**configmap.yaml:**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: jotp-payment-config
data:
  application.yaml: |
    jotp:
      supervisor:
        strategy: ONE_FOR_ONE
        restart-intensity: 10
        restart-period: 5s
      process:
        mailbox-size-limit: 1000
        ask-timeout: 5s

    management:
      endpoints:
        web:
          exposure:
            include: health,metrics,prometheus
      metrics:
        export:
          prometheus:
            enabled: true

    logging:
      level:
        io.github.seanchatmangpt.jotp: INFO
        io.github.seanchatmangpt.jotp.supervisor: WARN
```

**secret.yaml:**

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: jotp-payment-secrets
type: Opaque
stringData:
  database-url: "postgresql://db:5432/payment"
  database-username: "payment_user"
  database-password: "secret_password"
  api-key: "your_api_key"
```

**Mount in deployment:**
```yaml
spec:
  containers:
  - name: jotp-payment
    volumeMounts:
    - name: config
      mountPath: /app/config
    - name: secrets
      mountPath: /app/secrets
      readOnly: true
    envFrom:
    - configMapRef:
        name: jotp-payment-config
    - secretRef:
        name: jotp-payment-secrets
  volumes:
  - name: config
    configMap:
      name: jotp-payment-config
  - name: secrets
    secret:
      secretName: jotp-payment-secrets
```

## 2.3 Deployment Strategies

### 2.3.1 Blue-Green Deployment

**Strategy:** Maintain two identical production environments (blue and green). Switch traffic instantly.

**blue-green.yaml:**

```yaml
---
# Blue deployment (current)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-payment-blue
spec:
  replicas: 10
  selector:
    matchLabels:
      app: jotp-payment
      color: blue
  template:
    metadata:
      labels:
        app: jotp-payment
        color: blue
    spec:
      containers:
      - name: jotp-payment
        image: your-registry/jotp-payment:1.0.0
---
# Green deployment (new version)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-payment-green
spec:
  replicas: 10
  selector:
    matchLabels:
      app: jotp-payment
      color: green
  template:
    metadata:
      labels:
        app: jotp-payment
        color: green
    spec:
      containers:
      - name: jotp-payment
        image: your-registry/jotp-payment:1.1.0
---
# Service pointing to blue
apiVersion: v1
kind: Service
metadata:
  name: jotp-payment
spec:
  selector:
    app: jotp-payment
    color: blue  # Change to green to switch
  ports:
  - port: 8080
```

**Deployment procedure:**

```bash
# 1. Deploy green (new version)
kubectl apply -f green-deployment.yaml

# 2. Wait for green to be ready
kubectl rollout status deployment/jotp-payment-green

# 3. Run smoke tests on green
kubectl run smoke-test --image=curlimages/curl --rm -it --restart=Never -- \
  curl http://jotp-payment-green:8080/health

# 4. Switch traffic to green
kubectl patch service jotp-payment -p '{"spec":{"selector":{"color":"green"}}}'

# 5. Monitor green for issues
kubectl logs -f deployment/jotp-payment-green

# 6. Rollback if needed (switch back to blue)
kubectl patch service jotp-payment -p '{"spec":{"selector":{"color":"blue"}}}'
```

### 2.3.2 Canary Deployment

**Strategy:** Gradually roll out to small percentage of traffic.

**canary.yaml:**

```yaml
# Main deployment (95% traffic)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-payment-stable
spec:
  replicas: 19
  selector:
    matchLabels:
      app: jotp-payment
      version: stable
  template:
    metadata:
      labels:
        app: jotp-payment
        version: stable
    spec:
      containers:
      - name: jotp-payment
        image: your-registry/jotp-payment:1.0.0
---
# Canary deployment (5% traffic)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-payment-canary
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jotp-payment
      version: canary
  template:
    metadata:
      labels:
        app: jotp-payment
        version: canary
    spec:
      containers:
      - name: jotp-payment
        image: your-registry/jotp-payment:1.1.0
```

**Using Istio for traffic splitting:**

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: jotp-payment
spec:
  hosts:
  - jotp-payment
  http:
  - match:
    - headers:
        x-canary:
          exact: "true"
    route:
    - destination:
        host: jotp-payment
        subset: canary
  - route:
    - destination:
        host: jotp-payment
        subset: stable
      weight: 95
    - destination:
        host: jotp-payment
        subset: canary
      weight: 5  # Gradually increase
---
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: jotp-payment
spec:
  host: jotp-payment
  subsets:
  - name: stable
    labels:
      version: stable
  - name: canary
    labels:
      version: canary
```

### 2.3.3 Rolling Update (Default)

**Deployment manifest (built-in Kubernetes):**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jotp-payment
spec:
  replicas: 10
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 2        # Add 2 pods during update
      maxUnavailable: 0  # Never have fewer than 10 pods
  minReadySeconds: 30   # Wait 30s before considering pod ready
```

**Update procedure:**

```bash
# Update image
kubectl set image deployment/jotp-payment \
  jotp-payment=your-registry/jotp-payment:1.1.0

# Watch rollout status
kubectl rollout status deployment/jotp-payment

# Check revision history
kubectl rollout history deployment/jotp-payment

# Rollback if needed
kubectl rollout undo deployment/jotp-payment
```

## 2.4 Health Checks

### 2.4.1 Implementing Health Endpoints

**Spring Boot Actuator integration:**

```java
package io.github.seanchatmangpt.jotp.monitoring;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcSys;

@Component
public class JotpHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            var stats = ProcSys.getSystemStatistics();

            if (stats.restartRate() > 10) {
                return Health.down()
                    .withDetail("restart_rate", stats.restartRate())
                    .withDetail("reason", "High restart rate")
                    .build();
            }

            if (stats.avgMailboxSize() > 1000) {
                return Health.down()
                    .withDetail("avg_mailbox_size", stats.avgMailboxSize())
                    .withDetail("reason", "Mailbox backlog")
                    .build();
            }

            return Health.up()
                .withDetail("process_count", stats.processCount())
                .withDetail("supervisor_count", stats.supervisorCount())
                .withDetail("avg_mailbox_size", stats.avgMailboxSize())
                .build();

        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

### 2.4.2 Liveness vs. Readiness

**Liveness probe:** Restart pod if it's deadlocked
```java
@Component
public class JotpLivenessIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // Simple check: Are supervisors alive?
        var alive = ProcRegistry.whereis("root-supervisor") != null;

        return alive
            ? Health.up().build()
            : Health.down().withDetail("reason", "Root supervisor not found").build();
    }
}
```

**Readiness probe:** Stop traffic if overloaded
```java
@Component
public class JotpReadinessIndicator implements HealthIndicator {

    @Override
    public Health health() {
        var stats = ProcSys.getSystemStatistics();

        // Not ready if mailbox backlog is too high
        if (stats.avgMailboxSize() > 500) {
            return Health.down()
                .withDetail("avg_mailbox_size", stats.avgMailboxSize())
                .withDetail("reason", "Mailbox backlog")
                .build();
        }

        // Not ready if too many recent restarts
        if (stats.restartsLastMinute() > 50) {
            return Health.down()
                .withDetail("restarts_last_minute", stats.restartsLastMinute())
                .withDetail("reason", "High restart rate")
                .build();
        }

        return Health.up().build();
    }
}
```

### 2.4.3 Startup Probe

**For applications with slow initialization:**

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/startup
    port: 8080
  failureThreshold: 30  # Allow 30 failures (30 * 10s = 5 minutes)
  periodSeconds: 10
```

```java
@Component
public class JotpStartupIndicator implements HealthIndicator {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        // Perform initialization
        initializeSupervisors();

        // Mark as ready
        ready.set(true);
    }

    @Override
    public Health health() {
        return ready.get()
            ? Health.up().build()
            : Health.down().withDetail("reason", "Initializing").build();
    }
}
```

## 2.5 Exercise: Deploy to Production

**Goal:** Deploy a JOTP application to production with blue-green strategy.

### Step 1: Build and Push Image

```bash
# Build image
docker build -t your-registry/jotp-payment:1.0.0 .

# Push to registry
docker push your-registry/jotp-payment:1.0.0
```

### Step 2: Create Kubernetes Resources

```bash
# Create namespace
kubectl create namespace jotp-production

# Apply configs
kubectl apply -f k8s/configmap.yaml -n jotp-production
kubectl apply -f k8s/secret.yaml -n jotp-production

# Deploy blue (current version)
kubectl apply -f k8s/blue-deployment.yaml -n jotp-production

# Apply service
kubectl apply -f k8s/service.yaml -n jotp-production
```

### Step 3: Verify Deployment

```bash
# Check pods are running
kubectl get pods -n jotp-production -l app=jotp-payment

# Check logs
kubectl logs -f deployment/jotp-payment-blue -n jotp-production

# Port forward for local testing
kubectl port-forward svc/jotp-payment 8080:8080 -n jotp-production

# Test health endpoint
curl http://localhost:8080/actuator/health
```

### Step 4: Deploy Green (New Version)

```bash
# Deploy new version
kubectl apply -f k8s/green-deployment.yaml -n jotp-production

# Wait for green to be ready
kubectl wait --for=condition=available \
  deployment/jotp-payment-green -n jotp-production --timeout=300s

# Run smoke tests
kubectl run smoke-test --rm -it --image=curlimages/curl \
  -n jotp-production --restart=Never -- \
  curl http://jotp-payment-green:8080/actuator/health

# Switch traffic
kubectl patch svc jotp-payment -n jotp-production \
  -p '{"spec":{"selector":{"color":"green"}}}'
```

### Step 5: Monitor and Validate

```bash
# Monitor green pods
kubectl logs -f deployment/jotp-payment-green -n jotp-production

# Check metrics
kubectl port-forward svc/jotp-payment 9095:9095 -n jotp-production
curl http://localhost:9095/metrics | grep jotp

# Verify traffic is flowing
kubectl top pods -n jotp-production -l app=jotp-payment
```

**Success criteria:**
- Green pods running and healthy ✓
- Traffic switched successfully ✓
- No increase in error rate ✓
- Metrics within normal range ✓

## 2.6 Summary

**Key deployment practices:**

1. **Use multi-stage Docker builds** to minimize image size
2. **Set appropriate resource limits** for virtual thread workloads
3. **Implement proper health checks** (liveness, readiness, startup)
4. **Choose deployment strategy** based on risk tolerance:
   - Blue-green: Zero downtime, instant rollback
   - Canary: Gradual rollout, low risk
   - Rolling: Simple, but slower rollback

**Critical configuration:**
- JVM flags: `--enable-preview -XX:+UseVirtualThreads`
- Container resources: Match workload type
- Probes: Prevent traffic to unhealthy pods

**Next chapter:** Monitoring JOTP systems with Prometheus and Grafana.

---

# Chapter 3: Monitoring JOTP Systems

## 3.1 The USE Method for JOTP

The USE Method (Utilization, Saturation, Errors) provides a systematic approach to monitoring.

### 3.1.1 Utilization Metrics

**Definition:** Percentage of time a resource is busy.

**JOTP-specific utilization metrics:**

| Metric | Description | Target | Alert Threshold |
|--------|-------------|--------|-----------------|
| CPU utilization by carrier threads | Time carrier threads are executing | <70% | >90% |
| Heap utilization | Memory used by heap | <70% | >85% |
| Virtual thread utilization | Active virtual threads / total | <50% | >80% |
| Mailbox utilization | Messages in mailbox / capacity | <10% | >50% |

**Prometheus queries:**
```promql
# CPU utilization
rate(jvm_process_cpu_seconds_total[5m]) * 100

# Heap utilization
(jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100

# Virtual thread utilization
(jotp_virtual_threads_active / jotp_virtual_threads_max) * 100

# Mailbox utilization
rate(jotp_mailbox_size[5m])
```

### 3.1.2 Saturation Metrics

**Definition:** Degree to which a resource is queued or overloaded.

**JOTP-specific saturation metrics:**

| Metric | Description | Target | Alert Threshold |
|--------|-------------|--------|-----------------|
| Mailbox queue depth | Messages waiting | <100 | >1000 |
| Virtual thread queue | Tasks waiting for carrier | <10 | >100 |
| GC pause time | Time spent in GC | <50ms | >200ms |
| Ask queue depth | Pending synchronous calls | <10 | >50 |

**Prometheus queries:**
```promql
# Mailbox queue depth P95
quantile_over_time(0.95, jotp_mailbox_size[5m])

# Virtual thread queue
jotp_virtual_threads_queued

# GC pause time P99
rate(jvm_gc_pause_seconds_sum[5m]) / rate(jvm_gc_pause_seconds_count[5m])

# Ask queue depth
jotp_ask_queue_depth
```

### 3.1.3 Errors Metrics

**Definition:** Rate of error conditions.

**JOTP-specific error metrics:**

| Metric | Description | Target | Alert Threshold |
|--------|-------------|--------|-----------------|
| Process crash rate | Processes terminating abnormally | <0.1/min | >1/min |
| Ask timeout rate | Synchronous calls timing out | <0.01% | >0.1% |
| Supervisor restarts | Child process restarts | <5/min | >10/min |
| Message delivery failures | Undeliverable messages | <0.001% | >0.01% |

**Prometheus queries:**
```promql
# Process crash rate
rate(jotp_process_crashes_total[5m])

# Ask timeout rate
rate(jotp_ask_timeouts_total[5m]) / rate(jotp_ask_total[5m])

# Supervisor restart rate
rate(jotp_supervisor_restarts_total[5m])

# Message delivery failure rate
rate(jotp_message_delivery_failures_total[5m])
```

## 3.2 Core JOTP Metrics

### 3.2.1 Supervisor Metrics

**jotp_supervisor_restarts_total**
- **Description:** Total number of child process restarts
- **Type:** Counter
- **Labels:** supervisor_name, child_spec, restart_reason
- **Critical for:** Detecting unstable processes

**Example query:**
```promql
# Restart rate per supervisor
rate(jotp_supervisor_restarts_total[5m])

# Top 10 supervisors by restart rate
topk(10, sum by (supervisor_name) (
  rate(jotp_supervisor_restarts_total[5m])
))

# Restart rate trending up (over 1 hour)
rate(jotp_supervisor_restarts_total[1h]) >
  rate(jotp_supervisor_restarts_total[5m] offset 1h) * 1.5
```

### 3.2.2 Process Metrics

**jotp_process_count**
- **Description:** Current number of processes
- **Type:** Gauge
- **Labels:** state (running, restarting, terminated)
- **Critical for:** Capacity planning

**Example queries:**
```promql
# Total process count
sum(jotp_process_count)

# Process count by state
sum by (state) (jotp_process_count)

# Process creation rate
rate(jotp_process_created_total[5m])

# Process termination rate
rate(jotp_process_terminated_total[5m])
```

### 3.2.3 Mailbox Metrics

**jotp_mailbox_size**
- **Description:** Current mailbox size for each process
- **Type:** Gauge
- **Labels:** process_name, process_id
- **Critical for:** Detecting blocked consumers

**Example queries:**
```promql
# P95 mailbox size across all processes
quantile_over_time(0.95,
  jotp_mailbox_size[5m]
)

# Processes with largest mailboxes
topk(10, jotp_mailbox_size)

# Mailbox size trending up (potential deadlock)
predict_linear(jotp_mailbox_size[5m], 300) > 1000

# Average mailbox size per process type
avg by (process_name) (jotp_mailbox_size)
```

### 3.2.4 Ask Metrics

**jotp_ask_duration_seconds**
- **Description:** Duration of synchronous ask calls
- **Type:** Histogram
- **Labels:** from_process, to_process, message_type
- **Critical for:** Performance analysis

**Example queries:**
```promql
# P99 ask duration
histogram_quantile(0.99,
  rate(jotp_ask_duration_seconds_bucket[5m])
)

# Ask timeout rate
rate(jotp_ask_timeouts_total[5m]) /
rate(jotp_ask_total[5m])

# Average ask duration
rate(jotp_ask_duration_seconds_sum[5m]) /
rate(jotp_ask_duration_seconds_count[5m])

# Slowest ask pairs
topk(10,
  avg by (from_process, to_process) (
    rate(jotp_ask_duration_seconds_sum[5m]) /
    rate(jotp_ask_duration_seconds_count[5m])
  )
)
```

## 3.3 Prometheus Configuration

### 3.3.1 Complete Prometheus Config

**prometheus.yml:**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    cluster: 'jotp-production'
    region: 'us-east-1'

# Alertmanager configuration
alerting:
  alertmanagers:
  - static_configs:
    - targets:
      - alertmanager:9093

# Rule files
rule_files:
  - '/etc/prometheus/rules/jotp_alerts.yml'
  - '/etc/prometheus/rules/recording_rules.yml'

# Scrape configurations
scrape_configs:
  # JOTP application metrics
  - job_name: 'jotp-application'
    static_configs:
      - targets:
        - 'jotp-payment-1:9095'
        - 'jotp-payment-2:9095'
        - 'jotp-payment-3:9095'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s
    relabel_configs:
    - source_labels: [__address__]
      target_label: instance
    - source_labels: [__address__]
      regex: 'jotp-payment-([0-9]+):.*'
      target_label: pod
      replacement: 'jotp-payment-$1'

  # JVM metrics
  - job_name: 'jvm'
    static_configs:
      - targets:
        - 'jotp-payment-1:9095'
        - 'jotp-payment-2:9095'
        - 'jotp-payment-3:9095'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s

  # Kubernetes API server (for pod discovery)
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs:
    - role: pod
      namespaces:
        names:
        - jotp-production
    relabel_configs:
    - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
      action: keep
      regex: true
    - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
      action: replace
      target_label: __metrics_path__
      regex: (.+)
    - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
      action: replace
      regex: ([^:]+)(?::\d+)?;(\d+)
      replacement: $1:$2
      target_label: __address__
    - source_labels: [__meta_kubernetes_namespace]
      action: replace
      target_label: kubernetes_namespace
    - source_labels: [__meta_kubernetes_pod_name]
      action: replace
      target_label: kubernetes_pod_name

  # Kubernetes nodes
  - job_name: 'kubernetes-nodes'
    kubernetes_sd_configs:
    - role: node
    relabel_configs:
    - action: labelmap
      regex: __meta_kubernetes_node_label_(.+)

# Storage configuration
storage:
  tsdb:
    path: /prometheus
    retention.time: 15d
    retention.size: 10GB
```

### 3.3.2 Recording Rules

**recording_rules.yml:**

```yaml
groups:
- name: jotp_recording_rules
  interval: 30s
  rules:
  # Supervisor restart rate
  - record: job:jotp_supervisor_restart_rate:5m
    expr: |
      sum by (supervisor_name) (
        rate(jotp_supervisor_restarts_total[5m])
      )

  # P95 mailbox size
  - record: job:jotp_mailbox_size_p95:5m
    expr: |
      quantile_over_time(0.95, jotp_mailbox_size[5m])

  # Ask timeout rate
  - record: job:jotp_ask_timeout_rate:5m
    expr: |
      sum (
        rate(jotp_ask_timeouts_total[5m])
      ) / sum (
        rate(jotp_ask_total[5m])
      )

  # Process creation rate
  - record: job:jotp_process_creation_rate:5m
    expr: |
      sum by (process_type) (
        rate(jotp_process_created_total[5m])
      )

  # Heap utilization percentage
  - record: job:jvm_heap_utilization:percentage
    expr: |
      (jvm_memory_used_bytes{area="heap"} /
       jvm_memory_max_bytes{area="heap"}) * 100

  # CPU utilization percentage
  - record: job:jvm_cpu_utilization:percentage
    expr: |
      rate(jvm_process_cpu_seconds_total[5m]) * 100

  # GC pause time P99
  - record: job:jvm_gc_pause_p99_seconds
    expr: |
      histogram_quantile(0.99,
        rate(jvm_gc_pause_seconds_bucket[5m])
      )

  # Total message throughput
  - record: job:jotp_message_throughput:5m
    expr: |
      sum (
        rate(jotp_messages_processed_total[5m])
      )

  # Average process mailbox size
  - record: job:jotp_avg_mailbox_size
    expr: |
      avg(jotp_mailbox_size)
```

## 3.4 Alerting Rules

### 3.4.1 Critical Alerts

**jotp_alerts.yml:**

```yaml
groups:
- name: jotp_critical_alerts
  interval: 30s
  rules:
  # ========================================
  # CRITICAL ALERTS (page immediately)
  # ========================================

  # Supervisor crash storm
  - alert: JotpSupervisorCrashStorm
    expr: |
      sum by (supervisor_name) (
        rate(jotp_supervisor_restarts_total[1m])
      ) > 10
    for: 2m
    labels:
      severity: critical
      team: platform
    annotations:
      summary: "Supervisor {{ $labels.supervisor_name }} crash storm detected"
      description: "Supervisor is restarting children at {{ $value }} restarts/sec"
      runbook: "https://runbooks.example.com/jotp-supervisor-crash-storm"
      impact: "High - May cause service degradation"

  # Mailbox overflow (service degradation)
  - alert: JotpMailboxOverflow
    expr: |
      jotp_mailbox_size > 10000
    for: 5m
    labels:
      severity: critical
      team: platform
    annotations:
      summary: "Process {{ $labels.process_name }} mailbox overflow"
      description: "Mailbox size {{ $value }} indicates blocked consumer"
      runbook: "https://runbooks.example.com/jotp-mailbox-overflow"
      impact: "High - Messages being delayed or dropped"

  # Ask timeout rate high (SLA breach)
  - alert: JotpAskTimeoutRateHigh
    expr: |
      (
        sum (rate(jotp_ask_timeouts_total[5m])) /
        sum (rate(jotp_ask_total[5m]))
      ) > 0.01
    for: 5m
    labels:
      severity: critical
      team: platform
    annotations:
      summary: "Ask timeout rate {{ $value | humanizePercentage }} exceeds 1%"
      description: "Synchronous calls failing at high rate - SLA at risk"
      runbook: "https://runbooks.example.com/jotp-ask-timeouts"
      impact: "Critical - User-facing errors"

  # Heap exhaustion risk
  - alert: JotpHeapExhaustionRisk
    expr: |
      (jvm_memory_used_bytes{area="heap"} /
       jvm_memory_max_bytes{area="heap"}) > 0.9
    for: 2m
    labels:
      severity: critical
      team: platform
    annotations:
      summary: "Heap utilization {{ $value | humanizePercentage }} exceeds 90%"
      description: "Risk of OutOfMemoryError - immediate action required"
      runbook: "https://runbooks.example.com/jotp-heap-exhaustion"
      impact: "Critical - Pod may crash"

  # ========================================
  # WARNING ALERTS (investigate within 15min)
  # ========================================

  # Supervisor restart rate elevated
  - alert: JotpSupervisorRestartRateElevated
    expr: |
      sum by (supervisor_name) (
        rate(jotp_supervisor_restarts_total[5m])
      ) > 1
    for: 10m
    labels:
      severity: warning
      team: platform
    annotations:
      summary: "Supervisor {{ $labels.supervisor_name }} restart rate elevated"
      description: "Restart rate {{ $value }} restarts/sec is above baseline"
      runbook: "https://runbooks.example.com/jotp-restart-rate-elevated"

  # Mailbox size growing (early warning)
  - alert: JotpMailboxSizeGrowing
    expr: |
      predict_linear(jotp_mailbox_size[10m], 300) > 1000
    for: 5m
    labels:
      severity: warning
      team: platform
    annotations:
      summary: "Process {{ $labels.process_name }} mailbox size growing"
      description: "Mailbox will exceed 1000 messages in 5 minutes if trend continues"
      runbook: "https://runbooks.example.com/jotp-mailbox-growing"

  # Ask timeout rate elevated
  - alert: JotpAskTimeoutRateElevated
    expr: |
      (
        sum (rate(jotp_ask_timeouts_total[5m])) /
        sum (rate(jotp_ask_total[5m]))
      ) > 0.001
    for: 15m
    labels:
      severity: warning
      team: platform
    annotations:
      summary: "Ask timeout rate {{ $value | humanizePercentage }} above baseline"
      description: "Synchronous call timeouts are increasing"

  # GC pause time high
  - alert: JotpGcPauseTimeHigh
    expr: |
      histogram_quantile(0.99,
        rate(jvm_gc_pause_seconds_bucket[5m])
      ) > 0.2
    for: 10m
    labels:
      severity: warning
      team: platform
    annotations:
      summary: "GC pause time P99 is {{ $value }}s"
      description: "Long GC pauses may cause ask timeouts"

  # Process count anomaly
  - alert: JotpProcessCountAnomaly
    expr: |
      abs(
        jotp_process_count -
        avg_over_time(jotp_process_count[1h])
      ) / avg_over_time(jotp_process_count[1h]) > 0.5
    for: 15m
    labels:
      severity: warning
      team: platform
    annotations:
      summary: "Process count {{ $value }} deviates from baseline"
      description: "Process count changed by >50% in last hour"

  # ========================================
  # INFO ALERTS (for trend analysis)
  # ========================================

  # Supervisor restart detected
  - alert: JotpSupervisorRestartDetected
    expr: |
      increase(jotp_supervisor_restarts_total[5m]) > 0
    labels:
      severity: info
      team: platform
    annotations:
      summary: "Supervisor {{ $labels.supervisor_name }} restarted child {{ $labels.child_spec }}"
      description: "Reason: {{ $labels.restart_reason }}"

  # Process spawned
  - alert: JotpProcessSpawned
    expr: |
      increase(jotp_process_created_total[1m]) > 10
    labels:
      severity: info
      team: platform
    annotations:
      summary: "{{ $value }} processes spawned in last minute"

  # Message throughput anomaly
  - alert: JotpMessageThroughputAnomaly
    expr: |
      abs(
        sum (rate(jotp_messages_processed_total[5m])) -
        avg_over_time(sum (rate(jotp_messages_processed_total[5m]))[1h])
      ) / avg_over_time(sum (rate(jotp_messages_processed_total[5m]))[1h]) > 0.3
    for: 15m
    labels:
      severity: info
      team: platform
    annotations:
      summary: "Message throughput deviated by >30% from baseline"
```

## 3.5 Grafana Dashboards

### 3.5.1 Main Operations Dashboard

**dashboard.json (excerpt):**

```json
{
  "dashboard": {
    "title": "JOTP Operations Dashboard",
    "tags": ["jotp", "operations"],
    "timezone": "browser",
    "panels": [
      {
        "id": 1,
        "title": "System Health Score",
        "type": "stat",
        "targets": [
          {
            "expr": "100 - (\n  (avg(jotp_mailbox_size) / 1000) * 30 +\n  (rate(jotp_supervisor_restarts_total[5m]) * 10) * 20 +\n  (rate(jotp_ask_timeouts_total[5m]) / rate(jotp_ask_total[5m]) * 1000) * 30 +\n  (jvm_memory_used_bytes{area=\"heap\"} / jvm_memory_max_bytes{area=\"heap\"} * 100 - 70) * 20\n)",
            "legendFormat": "Health Score"
          }
        ],
        "options": {
          "colorMode": "value",
          "graphMode": "area",
          "thresholds": {
            "steps": [
              {"color": "red", "value": 0},
              {"color": "yellow", "value": 70},
              {"color": "green", "value": 90}
            ]
          }
        }
      },
      {
        "id": 2,
        "title": "Supervisor Restart Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "sum by (supervisor_name) (rate(jotp_supervisor_restarts_total[5m]))",
            "legendFormat": "{{supervisor_name}}"
          }
        ]
      },
      {
        "id": 3,
        "title": "Mailbox Size P95",
        "type": "graph",
        "targets": [
          {
            "expr": "quantile_over_time(0.95, jotp_mailbox_size[5m])",
            "legendFormat": "P95 Mailbox Size"
          }
        ],
        "alert": {
          "conditions": [
            {
              "evaluator": {"params": [1000], "type": "gt"},
              "operator": {"type": "and"},
              "query": {"params": ["A", "5m", "now"]},
              "reducer": {"params": [], "type": "avg"},
              "type": "query"
            }
          ]
        }
      },
      {
        "id": 4,
        "title": "Ask Timeout Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(rate(jotp_ask_timeouts_total[5m])) / sum(rate(jotp_ask_total[5m])) * 100",
            "legendFormat": "Timeout Rate %"
          }
        ]
      },
      {
        "id": 5,
        "title": "Process Count",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(jotp_process_count)",
            "legendFormat": "Total Processes"
          }
        ]
      },
      {
        "id": 6,
        "title": "Message Throughput",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(rate(jotp_messages_processed_total[5m]))",
            "legendFormat": "Messages/sec"
          }
        ]
      },
      {
        "id": 7,
        "title": "Heap Utilization",
        "type": "graph",
        "targets": [
          {
            "expr": "(jvm_memory_used_bytes{area=\"heap\"} / jvm_memory_max_bytes{area=\"heap\"}) * 100",
            "legendFormat": "Heap %"
          }
        ]
      },
      {
        "id": 8,
        "title": "Top Processes by Mailbox Size",
        "type": "table",
        "targets": [
          {
            "expr": "topk(10, jotp_mailbox_size)",
            "legendFormat": "{{process_name}}"
          }
        ],
        "transformations": [
          {
            "id": "organize",
            "options": {
              "excludeByName": {"Time": true},
              "indexByName": {"Value": 1},
              "renameByName": {"Value": "Mailbox Size"}
            }
          }
        ]
      }
    ]
  }
}
```

## 3.6 Exercise: Build Alerting Rules

**Goal:** Create a complete alerting rule for detecting mailbox overflow.

### Step 1: Define Alert Conditions

```yaml
# Alert: JotpMailboxOverflow
# Condition: Mailbox size > 10000 for 5 minutes
# Severity: Critical
# Action: Page on-call engineer
```

### Step 2: Write Prometheus Rule

```yaml
- alert: JotpMailboxOverflow
  expr: jotp_mailbox_size > 10000
  for: 5m
  labels:
    severity: critical
    team: platform
  annotations:
    summary: "Process {{ $labels.process_name }} mailbox overflow"
    description: "Mailbox size {{ $value }} exceeds threshold"
    runbook: "https://docs.example.com/runbooks/mailbox-overflow"
```

### Step 3: Test Alert

```bash
# Load rule into Prometheus
curl -X POST http://localhost:9090/api/v1/rules

# Trigger alert (simulate mailbox overflow)
# Use JOTP test harness to flood mailbox

# Verify alert fired
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.labels.alertname=="JotpMailboxOverflow")'
```

## 3.7 Summary

**Monitoring stack components:**
1. **Prometheus:** Metrics collection and storage
2. **Recording rules:** Pre-compute expensive queries
3. **Alerting rules:** Define alert conditions
4. **Alertmanager:** Route alerts to receivers
5. **Grafana:** Visualization and dashboards

**Key metrics to monitor:**
- Supervisor restart rate
- Mailbox size (P95, P99)
- Ask timeout rate
- Process count
- Message throughput

**Alert severity levels:**
- **Critical:** Page immediately (supervisor crash storm, mailbox overflow)
- **Warning:** Investigate within 15min (elevated restart rate)
- **Info:** For trend analysis only

**Next chapter:** Distributed tracing and observability deep dive.

---

# Chapter 4: Observability Deep Dive

## 4.1 Distributed Tracing with OpenTelemetry

### 4.1.1 Why Distributed Tracing Matters

JOTP systems consist of thousands of processes communicating asynchronously. When a request fails or is slow, traditional logging makes it difficult to understand the full request path across multiple processes.

**Distributed tracing provides:**
- Request journey across all processes
- Latency breakdown per process
- Failed process identification
- Parent-child relationships between operations

### 4.1.2 OpenTelemetry Integration

**Add dependencies:**

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.38.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-instrumentation-annotations</artifactId>
    <version>1.38.0</version>
</dependency>
```

**Configure tracing:**

```java
package io.github.seanchatmangpt.jotp.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public class TracingProc<S, M> implements Proc.Handler<S, M> {

    private final Tracer tracer;
    private final Proc.Handler<S, M> delegate;

    public TracingProc(Proc.Handler<S, M> delegate) {
        this.tracer = OpenTelemetry.getGlobalTracer("jotp");
        this.delegate = delegate;
    }

    @Override
    public Transition<S, M> apply(S state, M message) {
        var spanName = "process." + message.getClass().getSimpleName();
        var span = tracer.spanBuilder(spanName)
            .setParent(Context.current())
            .startSpan();

        try (var scope = span.makeCurrent()) {
            // Add message type as attribute
            span.setAttribute("jotp.message.type", message.getClass().getSimpleName());
            span.setAttribute("jotp.process.state", state.toString());

            // Process message
            var result = delegate.apply(state, message);

            span.setStatus(StatusCode.OK);
            return result;

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
```

**Wrap process with tracing:**

```java
var handler = new TracingProc<>(new PaymentHandler());
var pid = Proc.spawn(PaymentState::new, handler);
```

### 4.1.3 Trace Context Propagation

**Problem:** Trace context must be propagated across process boundaries.

**Solution:** Inject trace context into messages:

```java
package io.github.seanchatmangpt.jotp.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

public class TracePropagator {

    private static final TextMapPropagator propagator =
        OpenTelemetry.getGlobalPropagators().getTextMapPropagator();

    // Inject trace context into message metadata
    public static <M> void injectContext(M message, Metadata metadata) {
        propagator.inject(Context.current(), metadata, Metadata::put);
    }

    // Extract trace context from message metadata
    public static Context extractContext(Metadata metadata) {
        return propagator.extract(Context.current(), metadata, Metadata::get);
    }
}
```

### 4.1.4 Span Naming Conventions

**Standard span names:**

| Operation | Span Name | Example |
|-----------|-----------|---------|
| Process message | `process.<MessageType>` | `process.ProcessPayment` |
| Ask call | `ask.<TargetProcess>` | `ask.FraudDetector` |
| Supervisor restart | `supervisor.restart.<ChildSpec>` | `supervisor.restart.PaymentGateway` |
| Database query | `db.<operation>` | `db.query.select_transactions` |
| External API | `http.<method>.<service>` | `http.POST.bank_api` |

## 4.2 Structured Logging with Context

### 4.2.1 Correlation IDs

Every request should have a correlation ID for log aggregation:

```java
package io.github.seanchatmangpt.jotp.logging;

import java.util.UUID;
import io.opentelemetry.api.trace.Span;

public class CorrelationContext {

    private static final ThreadLocal<String> correlationId =
        new ThreadLocal<>();

    public static String getCorrelationId() {
        var id = correlationId.get();
        if (id == null) {
            // Generate from OpenTelemetry trace ID
            var span = Span.current();
            if (span.getSpanContext().isValid()) {
                id = span.getSpanContext().getTraceId();
            } else {
                id = UUID.randomUUID().toString();
            }
            correlationId.set(id);
        }
        return id;
    }
}
```

### 4.2.2 Structured JSON Logging

**Logback configuration:**

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeContext>true</includeContext>
            <includeStructuredArguments>true</includeStructuredArguments>
            <includeMdc>true</includeMdc>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

## 4.3 ProcSys for Live Inspection

### 4.3.1 Non-Invasive State Inspection

**ProcSys provides operations without killing processes:**

```java
// Get current state (without killing)
var pid = ProcRegistry.whereis("payment-gateway");
var state = ProcSys.getState(pid);

System.out.println("State: " + state);
System.out.println("Mailbox size: " + ProcSys.getMailboxSize(pid));
```

### 4.3.2 Live Statistics Dashboard

**Create a live stats endpoint:**

```java
@RestController
@RequestMapping("/admin/jotp")
public class JotpAdminController {

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        var stats = ProcSys.getSystemStatistics();

        return Map.of(
            "process_count", stats.processCount(),
            "supervisor_count", stats.supervisorCount(),
            "avg_mailbox_size", stats.avgMailboxSize(),
            "restarts_last_hour", stats.restartsLastHour(),
            "uptime_hours", stats.uptime().toHours()
        );
    }
}
```

### 4.3.3 Suspend/Resume for Debugging

**Temporarily pause a process for inspection:**

```java
@PostMapping("/admin/jotp/processes/{name}/suspend")
public void suspendProcess(@PathVariable String name) {
    var pid = ProcRegistry.whereis(name);
    ProcSys.suspend(pid);
}

@PostMapping("/admin/jotp/processes/{name}/resume")
public void resumeProcess(@PathVariable String name) {
    var pid = ProcRegistry.whereis(name);
    ProcSys.resume(pid);
}
```

## 4.4 Health Checks Without Killing

### 4.4.1 Safe Health Check Pattern

**Problem:** Calling `Proc.getState()` from application code can kill processes.

**Solution:** Use `ProcSys` for health checks:

```java
@Component
public class JotpHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            var stats = ProcSys.getSystemStatistics();

            if (stats.restartRate() > 10) {
                return Health.down()
                    .withDetail("restart_rate", stats.restartRate())
                    .build();
            }

            return Health.up()
                .withDetail("process_count", stats.processCount())
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

## 4.5 Exercise: Implement Distributed Tracing

**Goal:** Add distributed tracing to a JOTP application.

### Step 1: Add OpenTelemetry Dependencies

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.38.0</version>
</dependency>
```

### Step 2: Configure OpenTelemetry

```java
@Configuration
public class OpenTelemetryConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        var exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint("http://otel-collector:4317")
            .build();

        var tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
    }
}
```

### Step 3: Wrap Process Handler

```java
public class TracedPaymentHandler implements Proc.Handler<PaymentState, PaymentMessage> {

    private final Tracer tracer;
    private final PaymentHandler delegate;

    public TracedPaymentHandler(PaymentHandler delegate) {
        this.tracer = OpenTelemetry.getGlobalTracer("jotp");
        this.delegate = delegate;
    }

    @Override
    public Transition<PaymentState, PaymentMessage> apply(PaymentState state, PaymentMessage msg) {
        var span = tracer.spanBuilder("process.ProcessPayment")
            .setParent(Context.current())
            .startSpan();

        try (var scope = span.makeCurrent()) {
            span.setAttribute("payment.id", msg.paymentId());
            var result = delegate.apply(state, msg);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

## 4.6 Summary

**Observability pillars:**

1. **Metrics:** Quantitative data (Prometheus)
2. **Logs:** Qualitative events (Elasticsearch)
3. **Traces:** Request journeys (OpenTelemetry)

**Key patterns:**

1. **Distributed tracing:** Follow requests across processes
2. **Structured logging:** JSON logs with correlation IDs
3. **Live inspection:** ProcSys for non-invasive debugging
4. **Safe health checks:** Never use Proc.getState() in production

**Best practices:**

- Always wrap handlers with tracing
- Use correlation IDs for log aggregation
- Expose admin endpoints for live inspection
- Implement readiness checks for traffic control

**Next chapter:** Runbooks for common incidents.

---

# Chapter 5: Runbook: Common Incidents

## 5.1 Incident 1: Timeout Loop

### 5.1.1 Symptoms

**User-observable:**
- API requests timing out after 30 seconds
- "Payment gateway unavailable" errors
- Increasing response times

**System-level:**
- Mailbox size growing for payment-gateway process
- Ask timeout rate increasing
- CPU usage normal or low

**Example metric snapshot:**
```
jotp_mailbox_size{process="payment-gateway"} = 15,234
jotp_ask_timeout_rate = 2.3%
jotp_process_mailbox_age_max_seconds = 120
```

### 5.1.2 Diagnosis

**Step 1: Confirm mailbox buildup**

```bash
# Check mailbox size for affected process
kubectl exec -it jotp-payment-xxx -- curl -s http://localhost:8080/admin/jotp/processes/payment-gateway | jq .
```

**Step 2: Identify blocked operation**

```bash
# Check process logs for errors
kubectl logs -f deployment/jotp-payment -n jotp-production | grep "ERROR"
```

**Step 3: Verify upstream dependency**

```bash
# Test upstream dependency health
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl -X POST https://bank-api.example.com/health --max-time 5
```

### 5.1.3 Root Cause Analysis

**Common causes:**

1. **Upstream timeout:** Bank API is slow or down
2. **Insufficient timeout:** Default timeout too low for upstream
3. **No circuit breaker:** Calls continue to failing service
4. **No retry logic:** Transient failures cause permanent failures

### 5.1.4 Resolution

**Immediate action (restore service):**

```bash
# Option 1: Increase timeout temporarily
kubectl patch deployment jotp-payment -n jotp-production -p '
{
  "spec": {
    "template": {
      "spec": {
        "containers": [{
          "name": "jotp-payment",
          "env": [{
            "name": "PAYMENT_GATEWAY_TIMEOUT_SECONDS",
            "value": "30"
          }]
        }]
      }
    }
  }
}'

# Option 2: Open circuit breaker to stop timeout loop
curl -X POST http://localhost:8080/admin/circuit-breakers/payment-gateway/open
```

**Long-term fixes:**

1. **Add circuit breaker** (prevents cascading failures):

```java
var gateway = CircuitBreaker.of("payment-gateway",
    CircuitBreakerConfig.custom()
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build()
);
```

2. **Add timeout with retry**:

```java
var response = Retry.decorateSupplier(
    Retry.ofDefaults("payment-gateway"),
    () -> Timeout.decorateSupplier(
        Timeout.of(Duration.ofSeconds(5)),
        () -> bankApi.processPayment(payment)
    )
);
```

3. **Add fallback**:

```java
var response = gateway.executeSupplier(() ->
    bankApi.processPayment(payment)
).recover(throwable -> {
    log.warn("Payment gateway failed, using fallback", throwable);
    return FallbackResponse.processLater();
});
```

## 5.2 Incident 2: Memory Leak

### 5.2.1 Symptoms

**User-observable:**
- Pods being OOMKilled
- Intermittent service availability
- Slow response times before restart

**System-level:**
- Heap usage increasing over time
- GC pause times increasing
- Process count increasing
- Swap usage increasing

**Example metric snapshot:**
```
jvm_memory_used_bytes{area="heap"} = 8.5GB / 10GB
jvm_gc_pause_seconds_sum P99 = 2.3 seconds
jotp_process_count = 125,000 (baseline: 50,000)
kube_pod_status_phase{reason="OOMKilled"} = 3 pods
```

### 5.2.2 Diagnosis

**Step 1: Confirm memory leak**

```bash
# Check heap usage trend
curl -s 'http://prometheus:9090/api/v1/query_range?query=jvm_memory_used_bytes{area="heap"}&start=2026-03-13T00:00:00Z&end=2026-03-13T12:00:00Z&step=5m' | jq .
```

**Step 2: Identify leak source**

```bash
# Get heap dump (before OOM)
kubectl exec -it jotp-payment-xxx -- jcmd 1 GC.heap_dump /tmp/heap.hprof

# Copy heap dump locally
kubectl cp jotp-payment-xxx:/tmp/heap.hprof ./heap.hprof

# Analyze with Eclipse MAT or VisualVM
```

### 5.2.3 Root Cause Analysis

**Common causes:**

1. **Process leak:** Temporary processes not terminating
2. **Mailbox leak:** Messages accumulating in dead processes
3. **Cache leak:** Unbounded caches growing forever
4. **Reference leak:** ProcRef not released after use

**Example root cause:**

```java
// BAD: Process leak - temp process never terminates
public void processInParallel(List<Item> items) {
    for (var item : items) {
        // Spawns process but never terminates it
        Proc.spawn(() -> new TempState(), new TempHandler(item));
    }
}
```

### 5.2.4 Resolution

**Immediate action:**

```bash
# Option 1: Rollback to previous version (if recent deployment)
kubectl rollout undo deployment/jotp-payment -n jotp-production

# Option 2: Scale up to buy time
kubectl scale deployment jotp-payment --replicas=10 -n jotp-production
```

**Long-term fixes:**

1. **Fix process leak** - ensure processes terminate:

```java
// GOOD: Process terminates after processing
public void processInParallel(List<Item> items) {
    var supervisor = Supervisor.createSimple(
        SupervisorStrategy.oneForOne()
    );

    for (var item : items) {
        var pid = Proc.spawn(() -> new TempState(), new TempHandler(item));
        // Process will terminate when done
    }
}
```

2. **Add cache eviction**:

```java
// GOOD: Cache with eviction
public class CacheManager {
    private final Cache<String, Object> cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build();
}
```

## 5.3 Incident 3: Cascading Restart

### 5.3.1 Symptoms

**User-observable:**
- Complete service outage
- All pods restarting simultaneously
- No traffic being served

**System-level:**
- Supervisor restart rate spike (100+ restarts/sec)
- All pods in CrashLoopBackOff
- No healthy endpoints

**Example metric snapshot:**
```
jotp_supervisor_restarts_total = 15,234 (5 minutes)
rate(jotp_supervisor_restarts_total[5m]) = 50.8 restarts/sec
kube_pod_status_phase{phase="Running"} = 0
kube_pod_status_phase{phase="CrashLoopBackOff"} = 10
```

### 5.3.2 Diagnosis

**Step 1: Check pod status**

```bash
# Check all pods are restarting
kubectl get pods -n jotp-production
```

**Step 2: Check supervisor logs**

```bash
# Check supervisor restart logs
kubectl logs -f deployment/jotp-payment -n jotp-production --tail=100 | grep "supervisor"
```

**Step 3: Identify crash trigger**

```bash
# Get logs from first pod
kubectl logs jotp-payment-xxx --previous -n jotp-production | tail -200
```

### 5.3.3 Root Cause Analysis

**Common causes:**

1. **Initialization bug:** All processes crash on startup
2. **Dependency failure:** Shared service (DB, cache) is down
3. **Configuration error:** Wrong env vars, missing secrets
4. **Resource exhaustion:** Out of memory, CPU throttling

**Example root cause:**

```java
// BUG: NullPointerException on startup
@PostConstruct
public void init() {
    // database is null (not injected)
    database.query("SELECT * FROM config");  // NPE!
}
```

### 5.3.4 Resolution

**Immediate action:**

```bash
# Option 1: Scale to zero, fix, scale up
kubectl scale deployment jotp-payment --replicas=0 -n jotp-production
# Fix the bug
kubectl scale deployment jotp-payment --replicas=3 -n jotp-production

# Option 2: Rollback immediately
kubectl rollout undo deployment/jotp-payment -n jotp-production
```

**Long-term fixes:**

1. **Add startup validation**:

```java
@PostConstruct
public void init() {
    if (database == null) {
        throw new IllegalStateException("Database not initialized");
    }

    // Test connection
    try {
        database.query("SELECT 1");
    } catch (Exception e) {
        throw new IllegalStateException("Database connection failed", e);
    }
}
```

2. **Add circuit breakers for dependencies**:

```java
@Component
public class DatabaseHealthCheck implements HealthIndicator {

    @Override
    public Health health() {
        try {
            database.query("SELECT 1");
            return Health.up().build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

## 5.4 Incident 4: Supervisor Shutdown

### 5.4.1 Symptoms

**User-observable:**
- Partial service degradation (some features unavailable)
- "Service not available" errors for specific operations

**System-level:**
- Root supervisor not found
- Registered process count = 0
- No restarts (supervisor is gone, not restarting)

**Example metric snapshot:**
```
jotp_supervisor_count = 0 (baseline: 5)
jotp_process_count = 0 (baseline: 50,000)
jotp_supervisor_restarts_total = 0 (not increasing)
```

### 5.4.2 Diagnosis

**Step 1: Check supervisor registration**

```bash
# Check registered supervisors
curl http://localhost:8080/admin/jotp/supervisors | jq .

# Expected output: empty list
[]
```

**Step 2: Check crash logs**

```bash
# Check logs for supervisor crash
kubectl logs deployment/jotp-payment -n jotp-production --tail=500 | grep -i "supervisor"
```

### 5.4.3 Root Cause Analysis

**Common causes:**

1. **AutoShutdown triggered:** All significant children exited
2. **Restart intensity exceeded:** Too many crashes in window
3. **Initialization failure:** Supervisor failed to start
4. **Manual termination:** Operator manually killed supervisor

**Example root cause:**

```java
// AutoShutdown triggered - all children are TEMPORARY
var supervisor = Supervisor.create(
    SupervisorStrategy.oneForOne(),
    List.of(
        ChildSpec.builder()
            .name("worker")
            .restart(RestartType.temporary)  // Temporary child
            .build()
    ),
    Supervisor.AutoShutdown.allSignificant()  // Shuts down when all children exit
);

// If worker crashes with non-normal exit, supervisor terminates
```

### 5.4.4 Resolution

**Immediate action:**

```bash
# Option 1: Restart deployment
kubectl rollout restart deployment/jotp-payment -n jotp-production

# Option 2: Scale up (if all pods crashed)
kubectl scale deployment jotp-payment --replicas=3 -n jotp-production
```

**Long-term fixes:**

1. **Fix child restart types**:

```java
// GOOD: Workers are PERMANENT
ChildSpec.builder()
    .name("worker")
    .restart(RestartType.permanent)  // Correct
    .build()
```

2. **Adjust AutoShutdown**:

```java
// For critical services, disable AutoShutdown
var supervisor = Supervisor.create(
    SupervisorStrategy.oneForOne(),
    children,
    Supervisor.AutoShutdown.never()  // Never auto-shutdown
);
```

## 5.5 Exercise: Simulate Incidents

**Goal:** Practice incident response by simulating common failures.

### Step 1: Simulate Timeout Loop

```bash
# Deploy test application with slow upstream
kubectl apply -f exercises/timeout-loop/deployment.yaml

# Monitor mailbox size
watch -n 5 'kubectl exec -it timeout-loop-xxx -- curl -s http://localhost:8080/admin/jotp/processes/slow-gateway | jq .mail_box_size'

# Implement fix (circuit breaker)
kubectl apply -f exercises/timeout-loop/fix.yaml

# Verify mailbox size decreases
```

### Step 2: Simulate Memory Leak

```bash
# Deploy leaky application
kubectl apply -f exercises/memory-leak/deployment.yaml

# Monitor heap usage
watch -n 30 'kubectl exec -it memory-leak-xxx -- curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | jq .measurements[0].value'

# Trigger heap dump before OOM
kubectl exec -it memory-leak-xxx -- jcmd 1 GC.heap_dump /tmp/heap.hprof

# Analyze heap dump with MAT

# Deploy fixed version
kubectl apply -f exercises/memory-leak/fix.yaml
```

## 5.6 Summary

**Incident response workflow:**

1. **Detect:** Alert fires (Prometheus/Grafana)
2. **Diagnose:** Gather metrics, logs, traces
3. **Contain:** Immediate action to restore service
4. **Resolve:** Fix root cause permanently
5. **Verify:** Confirm fix works
6. **Prevent:** Improve monitoring and resilience

**Key runbooks:**

| Incident | Symptom | Immediate Action | Long-term Fix |
|----------|---------|------------------|---------------|
| Timeout loop | Mailbox growing | Increase timeout, open circuit breaker | Add circuit breaker, retry |
| Memory leak | Heap increasing | Rollback, scale up | Fix process/cache leaks |
| Cascading restart | All pods crashing | Rollback immediately | Fix initialization bug |
| Supervisor shutdown | No supervisors | Restart deployment | Fix restart types, AutoShutdown |

**Prevention strategies:**

1. Implement circuit breakers for all external dependencies
2. Set memory limits and monitor heap usage
3. Validate dependencies on startup
4. Use PERMANENT restart type for long-running processes
5. Never use AutoShutdown.ALL_SIGNIFICANT for critical services

**Next chapter:** Disaster recovery and backup strategies.

---

# Part II: Disaster Recovery (Chapters 6-8) - OUTLINE

# Chapter 6: Backup and Recovery Strategies

## 6.1 Data Backup Architecture
- 6.1.1 Supervisor state persistence
- 6.1.2 Process registry snapshots
- 6.1.3 Mailbox message durability
- 6.1.4 Backup frequency and retention policies

## 6.2 Backup Implementation
- 6.2.1 Hot backups (without downtime)
- 6.2.2 Cold backups (with downtime)
- 6.2.3 Incremental vs. full backups
- 6.2.4 Backup validation and integrity checks

## 6.3 Recovery Procedures
- 6.3.1 Single supervisor recovery
- 6.3.2 Full system recovery
- 6.3.3 Point-in-time recovery
- 6.3.4 Recovery time objectives (RTO)

## 6.4 Backup Storage
- 6.4.1 Local storage vs. cloud storage
- 6.4.2 Encryption at rest
- 6.4.3 Access control and auditing
- 6.4.4 Cross-region replication

## 6.5 Exercise: Implement Backup Strategy
- 6.5.1 Configure automated backups
- 6.5.2 Test recovery procedure
- 6.5.3 Verify backup integrity

---

# Chapter 7: Geographic Distribution and Multi-Region Deployments

## 7.1 Multi-Region Architecture
- 7.1.1 Active-active vs. active-passive
- 7.1.2 Data synchronization across regions
- 7.1.3 Conflict resolution strategies
- 7.1.4 Latency considerations

## 7.2 Disaster Scenarios
- 7.2.1 Region failure
- 7.2.2 Network partition
- 7.2.3 Partial failure (single zone)
- 7.2.4 Graceful degradation

## 7.3 Failover Procedures
- 7.3.1 Automated failover
- 7.3.2 Manual failover
- 7.3.3 Failback procedures
- 7.3.4 Data consistency validation

## 7.4 Multi-Region Monitoring
- 7.4.1 Cross-region health checks
- 7.4.2 Data replication lag monitoring
- 7.4.3 Regional performance metrics
- 7.4.4 Alerting for regional issues

## 7.5 Exercise: Deploy Multi-Region System
- 7.5.1 Set up second region
- 7.5.2 Configure cross-region replication
- 7.5.3 Test failover to DR region

---

# Chapter 8: Business Continuity Planning

## 8.1 Business Impact Analysis
- 8.1.1 Critical process identification
- 8.1.2 RTO and RPO targets
- 8.1.3 Dependencies and single points of failure
- 8.1.4 Risk assessment matrix

## 8.2 Continuity Strategies
- 8.2.1 High availability architecture
- 8.2.2 Redundancy patterns
- 8.2.3 Capacity planning for failures
- 8.2.4 Vendor diversification

## 8.3 Incident Response Plan
- 8.3.1 Roles and responsibilities
- 8.3.2 Communication procedures
- 8.3.3 Escalation matrix
- 8.3.4 Post-incident review process

## 8.4 Testing and Drills
- 8.4.1 Chaos engineering practices
- 8.4.2 Game day exercises
- 8.4.3 Tabletop simulations
- 8.4.4 Continuous improvement

## 8.5 Exercise: Conduct Disaster Drill
- 8.5.1 Plan disaster scenario
- 8.5.2 Execute failover procedures
- 8.5.3 Document lessons learned
- 8.5.4 Update runbooks

---

# Part III: Security & Compliance (Chapters 9-11) - OUTLINE

# Chapter 9: Security Operations

## 9.1 Threat Model
- 9.1.1 Attack surface analysis
- 9.1.2 Common vulnerabilities in JOTP systems
- 9.1.3 Threat prioritization
- 9.1.4 Security controls inventory

## 9.2 Access Control
- 9.2.1 Authentication mechanisms
- 9.2.2 Authorization models
- 9.2.3 Role-based access control (RBAC)
- 9.2.4 Service-to-service authentication

## 9.3 Data Protection
- 9.3.1 Encryption at rest
- 9.3.2 Encryption in transit
- 9.3.3 Key management
- 9.3.4 Secrets management

## 9.4 Security Monitoring
- 9.4.1 Intrusion detection
- 9.4.2 Anomaly detection
- 9.4.3 Security information and event management (SIEM)
- 9.4.4 Incident response workflows

## 9.5 Exercise: Implement Security Controls
- 9.5.1 Configure authentication
- 9.5.2 Enable encryption
- 9.5.3 Set up security monitoring

---

# Chapter 10: Compliance and Audit

## 10.1 Regulatory Requirements
- 10.1.1 GDPR compliance
- 10.1.2 PCI DSS requirements
- 10.1.3 SOC 2 controls
- 10.1.4 Industry-specific regulations

## 10.2 Audit Trail
- 10.2.1 Event logging requirements
- 10.2.2 Tamper-evident logs
- 10.2.3 Log retention policies
- 10.2.4 Audit report generation

## 10.3 Compliance Monitoring
- 10.3.1 Continuous compliance monitoring
- 10.3.2 Policy-as-code
- 10.3.3 Automated compliance checks
- 10.3.4 Remediation and enforcement

## 10.4 Certifications
- 10.4.1 ISO 27001
- 10.4.2 SOC 2 Type II
- 10.4.3 PCI DSS certification
- 10.4.4 Compliance audit preparation

## 10.5 Exercise: Prepare for Compliance Audit
- 10.5.1 Implement audit logging
- 10.5.2 Configure compliance monitoring
- 10.5.3 Generate compliance reports

---

# Chapter 11: Incident Response and Forensics

## 11.1 Incident Response Lifecycle
- 11.1.1 Preparation
- 11.1.2 Detection and analysis
- 11.1.3 Containment, eradication, recovery
- 11.1.4 Post-incident activity

## 11.2 Forensics and Investigation
- 11.2.1 Evidence collection
- 11.2.2 Chain of custody
- 11.2.3 Log analysis techniques
- 11.2.4 Root cause analysis

## 11.3 Malicious Process Detection
- 11.3.1 Behavior analysis
- 11.3.2 Signature-based detection
- 11.3.3 Anomaly-based detection
- 11.3.4 Process isolation and sandboxing

## 11.4 Recovery from Security Incidents
- 11.4.1 System restoration
- 11.4.2 Data recovery
- 11.4.3 Vulnerability patching
- 11.4.4 Security improvements

## 11.5 Exercise: Security Incident Response
- 11.5.1 Simulate security breach
- 11.5.2 Collect forensic evidence
- 11.5.3 Perform root cause analysis
- 11.5.4 Implement security improvements

---

# Part IV: Performance Optimization (Chapters 12-13) - OUTLINE

# Chapter 12: Performance Tuning

## 12.1 JVM Tuning for Virtual Threads
- 12.1.1 Heap sizing strategies
- 12.1.2 GC tuning for JOTP workloads
- 12.1.3 Thread pool configuration
- 12.1.4 JIT compilation optimization

## 12.2 Process Performance
- 12.2.1 Message throughput optimization
- 12.2.2 Mailbox sizing and configuration
- 12.2.3 Process granularity
- 12.2.4 Hot spot identification

## 12.3 Supervisor Performance
- 12.3.1 Restart strategy optimization
- 12.3.2 Child process configuration
- 12.3.3 Monitoring overhead reduction
- 12.3.4 Batch processing optimization

## 12.4 Network Performance
- 12.4.1 Inter-process communication optimization
- 12.4.2 Serialization strategies
- 12.4.3 Connection pooling
- 12.4.4 Load balancing

## 12.5 Exercise: Performance Tuning Lab
- 12.5.1 Benchmark baseline performance
- 12.5.2 Identify bottlenecks
- 12.5.3 Apply optimizations
- 12.5.4 Measure improvements

---

# Chapter 13: Capacity Planning

## 13.1 Capacity Modeling
- 13.1.1 Resource utilization forecasting
- 13.1.2 Growth projections
- 13.1.3 Seasonal patterns
- 13.1.4 Traffic modeling

## 13.2 Scaling Strategies
- 13.2.1 Horizontal scaling
- 13.2.2 Vertical scaling
- 13.2.3 Auto-scaling policies
- 13.2.4 Cost optimization

## 13.3 Load Testing
- 13.3.1 Load test design
- 13.3.2 Test scenario creation
- 13.3.3 Test execution and monitoring
- 13.3.4 Results analysis

## 13.4 Right-Sizing
- 13.4.1 CPU capacity planning
- 13.4.2 Memory capacity planning
- 13.4.3 Network bandwidth planning
- 13.4.4 Storage capacity planning

## 13.5 Exercise: Capacity Planning Workshop
- 13.5.1 Model resource requirements
- 13.5.2 Design load tests
- 13.5.3 Execute tests and analyze results
- 13.5.4 Create scaling plan

---

# Appendix

## A. Quick Reference
- A.1 Common commands
- A.2 Metric reference
- A.3 Alert rules reference
- A.4 Troubleshooting checklist

## B. Configuration Templates
- B.1 Prometheus configuration
- B.2 Grafana dashboards
- B.3 Kubernetes manifests
- B.4 Docker Compose files

## C. Runbook Templates
- C.1 Incident response template
- C.2 Postmortem template
- C.3 Change log template
- C.4 Capacity plan template

## D. Glossary
- JOTP terminology
- Kubernetes terminology
- Monitoring terminology
- Security terminology

## E. Further Reading
- E.1 Erlang/OTP resources
- E.2 Java 26 resources
- E.3 Operations best practices
- E.4 Site reliability engineering

---

**End of Book**

**Note:** This book provides complete content for Chapters 1-5 (Operational Excellence, ~12,500 words), with detailed outlines for the remaining 8 chapters covering disaster recovery, security/compliance, and performance optimization. The full 280-page book would expand these outlines into complete chapters following the same depth and practical focus as Chapters 1-5.