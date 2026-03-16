# JOTP Production Monitoring Guide

**Version:** 1.0
**Last Updated:** 2026-03-15

## Overview

This guide covers monitoring JOTP applications in production using OpenTelemetry, Prometheus, Grafana, and alerting strategies for performance observability and incident response.

---

## 1. Process Metrics Collection

### 1.1 Core JOTP Metrics

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|---------------|
| `jotp_process_count` | Gauge | Total running processes | Application-specific |
| `jotp_process_spawn_rate` | Counter | Processes created per second | 100-10K ops/sec |
| `jotp_process_crash_rate` | Counter | Processes crashed per second | <1% of spawn rate |
| `jotp_message_throughput` | Histogram | Messages sent per second | 1M-10M ops/sec |
| `jotp_message_latency` | Histogram | Message delivery latency | P99 <1ms |
| `jotp_mailbox_depth` | Gauge | Messages per mailbox | <1000 |
| `jotp_mailbox_dropped` | Counter | Dropped messages | 0 |

### 1.2 Metrics Collection Implementation

**Using Micrometer:**
```java
// Create metrics registry
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

// Process creation metrics
Counter processSpawnCounter = Counter.builder("jotp_process_spawn_rate")
    .description("Processes created per second")
    .register(registry);

// Message throughput metrics
DistributionSummary messageThroughput = DistributionSummary.builder("jotp_message_throughput")
    .description("Messages sent per second")
    .tags("direction", "sent")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(registry);

// Mailbox depth metrics
Gauge.builder("jotp_mailbox_depth", proc, p -> p.getMailboxSize())
    .description("Messages in mailbox")
    .tags("process", proc.getName())
    .register(registry);
```

**Using OpenTelemetry:**
```java
// Set up OpenTelemetry
SdkMeterProvider meterProvider = SdkMeterProvider.builder()
    .registerMetricReader(
        PeriodicMetricReader.builder(
            PrometheusHttpServer.create()
        ).build()
    )
    .build();

// Create meter
Meter meter = meterProvider.get("jotp");

// Process metrics
LongCounter processCounter = meter.counterBuilder("jotp_process_spawn_rate")
    .setDescription("Processes created per second")
    .build();

// Message latency histogram
Histogram messageLatency = meter.histogramBuilder("jotp_message_latency")
    .setDescription("Message delivery latency")
    .setUnit("ms")
    .setExplicitBucketBoundaries(Arrays.asList(0.1, 0.5, 1.0, 5.0, 10.0))
    .build();
```

### 1.3 Supervisor Metrics

| Metric | Type | Description | Healthy Range |
|--------|------|-------------|---------------|
| `jotp_supervisor_restart_rate` | Counter | Restarts per second | <10% of child count |
| `jotp_supervisor_restart_intensity` | Gauge | Current restart count | <maxIntensity |
| `jotp_supervisor_children_count` | Gauge | Active children | Application-specific |
| `jotp_supervisor_tree_depth` | Gauge | Supervision tree depth | <10 levels |
| `jotp_supervisor_crash_propagation_time` | Histogram | Time to notify monitors | P99 <100ms |

---

## 2. OpenTelemetry Integration

### 2.1 Setup OpenTelemetry SDK

**Maven dependencies:**
```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.32.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>1.32.0</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>1.32.0</version>
</dependency>
```

### 2.2 Configure OpenTelemetry

**Initialization:**
```java
// Set up resource
Resource resource = Resource.getDefault()
    .merge(Resource.create(
        Attributes.of(
            AttributeKey.stringKey("service.name"), "jotp-application",
            AttributeKey.stringKey("service.version"), "1.0.0"
        )
    ));

// Set up OTLP exporter
OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
    .setEndpoint("http://otel-collector:4317")
    .build();

// Set up meter provider
SdkMeterProvider meterProvider = SdkMeterProvider.builder()
    .setResource(resource)
    .registerMetricReader(
        PeriodicMetricReader.builder(OTLPMetricExporter.builder()
            .setEndpoint("http://otel-collector:4317")
            .build())
        .build()
    )
    .build();

// Set up tracer provider
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .setResource(resource)
    .addSpanProcessor(
        BatchSpanProcessor.builder(exporter)
        .build()
    )
    .build();

// Register global
OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
    .setMeterProvider(meterProvider)
    .setTracerProvider(tracerProvider)
    .buildAndRegisterGlobal();
```

### 2.3 Distributed Tracing

**Trace message passing:**
```java
// Get tracer
Tracer tracer = openTelemetry.getTracer("jotp", "1.0.0");

// Span for message send
Span span = tracer.spanBuilder("proc.tell")
    .setAttribute("message.type", message.getClass().getSimpleName())
    .setAttribute("proc.name", proc.getName())
    .startSpan();

try (Scope scope = span.makeCurrent()) {
    proc.tell(message);
    span.setStatus(StatusCode.OK);
} catch (Exception e) {
    span.recordException(e);
    span.setStatus(StatusCode.ERROR, e.getMessage());
} finally {
    span.end();
}
```

**Trace supervisor restarts:**
```java
// Span for restart operation
Span restartSpan = tracer.spanBuilder("supervisor.restart")
    .setAttribute("supervisor.name", supervisor.getName())
    .setAttribute("child.name", childSpec.name())
    .setAttribute("restart.reason", reason)
    .startSpan();

try (Scope scope = restartSpan.makeCurrent()) {
    ProcRef<S, M> newChild = supervisor.restartChild(childSpec);
    restartSpan.setStatus(StatusCode.OK);
    restartSpan.setAttribute("new.proc.id", newChild.toString());
} catch (Exception e) {
    restartSpan.recordException(e);
    restartSpan.setStatus(StatusCode.ERROR);
} finally {
    restartSpan.end();
}
```

---

## 3. Prometheus Integration

### 3.1 Prometheus Server Setup

**Docker Compose:**
```yaml
version: '3'
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - grafana-storage:/var/lib/grafana

volumes:
  grafana-storage:
```

**Prometheus configuration (prometheus.yml):**
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'jotp'
    static_configs:
      - targets: ['localhost:9095']  # Prometheus exporter port
    metrics_path: '/metrics'
```

### 3.2 Prometheus Exporter

**Using Micrometer Prometheus:**
```java
// Create Prometheus registry
PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

// Start HTTP server
HttpServer server = HttpServer.create(new InetSocketAddress(9095), 0);
server.createContext("/metrics", exchange -> {
    String response = registry.scrape();
    exchange.sendResponseHeaders(200, response.getBytes().length);
    try (OutputStream os = exchange.getResponseBody()) {
        os.write(response.getBytes());
    }
});
server.start();
```

### 3.3 Prometheus Queries

**Common queries:**
```promql
# Message throughput rate
rate(jotp_message_throughput_count[5m])

# P99 message latency
histogram_quantile(0.99, rate(jotp_message_latency_bucket[5m]))

# Average mailbox depth
avg(jotp_mailbox_depth) by (process_name)

# Process crash rate
rate(jotp_process_crash_rate_count[5m])

# Supervisor restart rate
rate(jotp_supervisor_restart_rate_count[5m])
```

---

## 4. Grafana Dashboards

### 4.1 Dashboard Configuration

**Import dashboard (JSON):**
```json
{
  "dashboard": {
    "title": "JOTP Performance Dashboard",
    "panels": [
      {
        "title": "Message Throughput",
        "targets": [
          {
            "expr": "rate(jotp_message_throughput_count[5m])",
            "legendFormat": "{{process_name}}"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Message Latency (P99)",
        "targets": [
          {
            "expr": "histogram_quantile(0.99, rate(jotp_message_latency_bucket[5m]))",
            "legendFormat": "P99"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Mailbox Depth",
        "targets": [
          {
            "expr": "jotp_mailbox_depth",
            "legendFormat": "{{process_name}}"
          }
        ],
        "type": "graph"
      }
    ]
  }
}
```

### 4.2 Essential Panels

**Panel 1: Process Health**
- Process count gauge
- Spawn rate (counter)
- Crash rate (counter)
- Restart rate (counter)

**Panel 2: Message Performance**
- Throughput (graph)
- Latency percentiles (P50, P95, P99)
- Error rate
- Message size distribution

**Panel 3: Mailbox Health**
- Average depth
- Max depth
- Drop rate
- Age distribution

**Panel 4: Supervisor Health**
- Restart rate by supervisor
- Children count
- Tree depth
- Crash propagation time

---

## 5. Alerting Strategies

### 5.1 Alert Thresholds

| Alert | Threshold | Duration | Severity |
|-------|-----------|----------|----------|
| **High message latency** | P99 > 10ms | 5m | Warning |
| **Critical message latency** | P99 > 100ms | 1m | Critical |
| **High mailbox depth** | Depth > 10,000 | 5m | Warning |
| **Critical mailbox depth** | Depth > 50,000 | 1m | Critical |
| **High crash rate** | Rate > 10% | 5m | Warning |
| **Critical crash rate** | Rate > 50% | 1m | Critical |
| **Message drops** | Rate > 1% | 1m | Critical |
| **Supervisor restart storm** | >100 restarts/min | 1m | Critical |

### 5.2 Prometheus Alert Rules

**Alert configuration:**
```yaml
groups:
  - name: jotp_alerts
    rules:
      - alert: JOTPHighMessageLatency
        expr: |
          histogram_quantile(0.99,
            rate(jotp_message_latency_bucket[5m])) > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High message latency for {{ $labels.process_name }}"
          description: "P99 latency is {{ $value }}ms (threshold: 10ms)"

      - alert: JITPCriticalMessageLatency
        expr: |
          histogram_quantile(0.99,
            rate(jotp_message_latency_bucket[5m])) > 0.1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Critical message latency for {{ $labels.process_name }}"
          description: "P99 latency is {{ $value }}ms (threshold: 100ms)"

      - alert: JOTPHighMailboxDepth
        expr: jotp_mailbox_depth > 10000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High mailbox depth for {{ $labels.process_name }}"
          description: "Mailbox depth is {{ $value }} (threshold: 10,000)"

      - alert: JITPCriticalCrashRate
        expr: |
          rate(jotp_process_crash_rate_count[5m]) /
          rate(jotp_process_spawn_rate_count[5m]) > 0.5
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Critical crash rate"
          description: "Crash rate is {{ $value }}% (threshold: 50%)"
```

### 5.3 Alert Routing

**Alertmanager configuration:**
```yaml
route:
  group_by: ['alertname', 'severity']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 1h
  receiver: 'default'

  routes:
    - match:
        severity: critical
      receiver: 'critical'
      continue: true

    - match:
        severity: warning
      receiver: 'warning'

receivers:
  - name: 'critical'
    pagerduty_configs:
      - service_key: '<pagerduty-key>'

  - name: 'warning'
    slack_configs:
      - api_url: '<slack-webhook>'
        channel: '#jotp-alerts'
```

---

## 6. Performance Dashboards

### 6.1 Real-Time Performance Dashboard

**Panels:**
1. **Message Throughput** (ops/sec) - Line graph
2. **Message Latency** (ms) - Heatmap
3. **Process Count** - Single stat
4. **Mailbox Depth** - Gauge chart
5. **Error Rate** (%) - Single stat
6. **GC Pause Time** (ms) - Line graph
7. **Heap Usage** (%) - Gauge chart
8. **CPU Usage** (%) - Gauge chart

### 6.2 Supervisor Health Dashboard

**Panels:**
1. **Restart Rate** (restarts/min) - Line graph
2. **Children Count** - Single stat
3. **Tree Depth** - Single stat
4. **Crash Propagation Time** (ms) - Histogram
5. **Active Supervisors** - Table
6. **Restarts by Supervisor** - Pie chart

### 6.3 Incident Response Dashboard

**Panels:**
1. **Active Alerts** - Table
2. **Alert History** - Timeline
3. **MTTR** (Mean Time To Repair) - Single stat
4. **Incident Rate** (incidents/day) - Line graph
5. **Affected Services** - Table
6. **Recovery Actions** - Log

---

## 7. Success Metrics

### 7.1 Performance SLIs (Service Level Indicators)

| SLI | Measurement | Target |
|-----|-------------|--------|
| **Message Throughput** | ops/sec | ≥1M ops/sec |
| **Message Latency (P99)** | ms | <1ms |
| **Process Availability** | % | >99.9% |
| **Error Rate** | % | <0.1% |
| **MTTR** | minutes | <5min |
| **MTTD** (Mean Time To Detect) | seconds | <30s |

### 7.2 SLOs (Service Level Objectives)

**Example SLOs:**
- 99.9% of messages delivered within 1ms (P99)
- 99.95% of processes available per month
- <5 minutes to detect and respond to incidents
- <0.1% message drop rate

### 7.3 Error Budgets

**Calculation:**
```
Error Budget = (100% - SLO) × Time Period

Example: 99.9% SLO over 30 days
Error Budget = 0.1% × 30 days = 43.2 minutes/month
```

**Tracking error budget consumption:**
```promql
# Error budget remaining
(1 - (rate(jotp_message_latency_count{le="1.0"}[30d]) /
      rate(jotp_message_latency_count[30d]))) * 100
```

---

## 8. Monitoring Checklist

### 8.1 Pre-Production

- [ ] Set up metrics collection (Micrometer/OpenTelemetry)
- [ ] Configure Prometheus scraping
- [ ] Create Grafana dashboards
- [ ] Define alert thresholds
- [ ] Test alert routing
- [ ] Document runbooks

### 8.2 Production

- [ ] Verify metrics are flowing
- [ ] Check dashboards are accessible
- [ ] Validate alerts are firing correctly
- [ ] Set up on-call rotation
- [ ] Train team on incident response
- [ ] Establish communication channels

### 8.3 Ongoing

- [ ] Review alert thresholds weekly
- [ ] Update dashboards quarterly
- [ ] Conduct incident post-mortems
- [ ] Refine SLOs based on data
- [ ] Optimize monitoring overhead
- [ ] Archive historical data

---

## 9. Troubleshooting Guide

### 9.1 High Message Latency

**Symptoms:**
- P99 latency > 1ms
- Mailbox depth increasing
- Consumer lag growing

**Diagnosis:**
```promql
# Check latency
histogram_quantile(0.99, rate(jotp_message_latency_bucket[5m]))

# Check mailbox depth
avg(jotp_mailbox_depth) by (process_name)

# Check consumer rate
rate(jotp_message_processed_count[5m])
```

**Actions:**
1. Scale up consumer processes
2. Optimize message handlers
3. Check for blocking operations
4. Review GC pause times

### 9.2 High Crash Rate

**Symptoms:**
- Crash rate > 10%
- Restart rate increasing
- Supervisor approaching intensity limit

**Diagnosis:**
```promql
# Crash rate
rate(jotp_process_crash_rate_count[5m])

# Restart intensity
jotp_supervisor_restart_intensity

# Crash reasons
topk(10, sum by (crash_reason) (jotp_process_crashed_total))
```

**Actions:**
1. Identify crash patterns
2. Fix root causes
3. Adjust supervisor strategy
4. Increase intensity limits

### 9.3 Mailbox Overflow

**Symptoms:**
- Mailbox depth > 10,000
- Message drops increasing
- Memory usage high

**Diagnosis:**
```promql
# Mailbox depth
jotp_mailbox_depth

# Drop rate
rate(jotp_mailbox_dropped_count[5m])

# Memory usage
jvm_memory_used_bytes{area="heap"}
```

**Actions:**
1. Increase mailbox capacity
2. Scale up consumers
3. Implement backpressure
4. Reduce message rate

---

## 10. Quick Reference

### Metrics Export Endpoints

| System | Endpoint | Format |
|--------|----------|--------|
| **Prometheus** | `http://localhost:9095/metrics` | Prometheus text |
| **OpenTelemetry** | `http://localhost:4318/v1/metrics` | Prometheus text |
| **Health** | `http://localhost:8080/health` | JSON |

### Alert Severity Levels

| Severity | Response Time | Escalation |
|----------|---------------|------------|
| **P1 - Critical** | 15 minutes | Director |
| **P2 - High** | 1 hour | Manager |
| **P3 - Medium** | 4 hours | Team lead |
| **P4 - Low** | 1 day | Developer |

---

**Next Steps:**
- [ ] Set up profiling: `profiling.md`
- [ ] Review JVM tuning: `jvm-tuning.md`
- [ ] Optimize performance: `optimization-patterns.md`
- [ ] Troubleshoot issues: `troubleshooting-performance.md`
