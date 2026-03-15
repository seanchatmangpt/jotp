# OpenTelemetry Integration

## Overview

JOTP provides native observability components (MetricsCollector, DistributedTracer, HealthMonitor) with OpenTelemetry bridges for cloud-native telemetry export.

## Quick Start

### 1. Start the OpenTelemetry Stack

```bash
make otel-start
```

This starts:
- **OpenTelemetry Collector** — OTLP receiver on port 4317/4318
- **Jaeger** — Tracing UI on http://localhost:16686
- **Prometheus** — Metrics UI on http://localhost:9090
- **Grafana** — Dashboards on http://localhost:3000

### 2. Run JOTP with OpenTelemetry

```bash
make otel-run
```

### 3. View Traces

```bash
make otel-traces
```

### 4. View Metrics

```bash
make otel-metrics
```

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────┐
│  JOTP Process   │────▶│ OTLP Exporter    │────▶│   Jaeger    │
│  (Virtual       │     │ (gRPC/HTTP)      │     │  (Traces)   │
│   Threads)      │     │                  │     └─────────────┘
└─────────────────┘     │                  │     ┌─────────────┐
                        │                  │────▶│ Prometheus  │
┌─────────────────┐     │                  │     │  (Metrics)  │
│  MetricsCollector│────│                  │     └─────────────┐
│  DistributedTracer│   │                  │     ┌─────────────┐
│  HealthMonitor   │───▶│                  │────▶│   Grafana   │
└─────────────────┘     └──────────────────┘     │ (Dashboards)│
                                                 └─────────────┘
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OTEL_SERVICE_NAME` | `jotp-service` | Service name for telemetry |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP endpoint |
| `OTEL_TRACES_EXPORTER` | `otlp` | Trace exporter |
| `OTEL_METRICS_EXPORTER` | `otlp` | Metrics exporter |
| `OTEL_LOGS_EXPORTER` | `otlp` | Logs exporter |

### Programmatic Configuration

```java
var config = OtelConfiguration.builder()
    .serviceName("my-jotp-app")
    .otlpEndpoint("http://otel-collector:4317")
    .exportInterval(Duration.ofSeconds(10))
    .enableMetrics(true)
    .enableTracing(true)
    .build();

var otelService = OpenTelemetryService.create(config);

// Bridge existing components
var metricsBridge = MetricsCollectorBridge.create(
    metricsCollector,
    otelService.meterProvider()
);

var tracerBridge = DistributedTracerBridge.create(
    distributedTracer,
    otelService.tracerProvider()
);
```

## Metrics

JOTP exposes the following metrics:

### Proc Metrics
- `proc.mailbox.size` — Current mailbox size
- `proc.messages.processed` — Total messages processed
- `proc.restart.count` — Process restart count

### Supervisor Metrics
- `supervisor.children.count` — Number of children
- `supervisor.restart.count` — Supervisor restart count

### Application Metrics
- `application.start.time` — Application startup time
- `application.uptime` — Application uptime

## Traces

JOTP automatically traces:

### Proc Lifecycle
- Process spawning (`Proc.spawn`)
- Message sending (`Proc.tell`, `Proc.ask`)
- Process termination

### Supervisor Actions
- Child restarts
- Supervisor tree traversal

### Application Lifecycle
- Application start/stop
- Dependency initialization

## Makefile Targets

| Target | Description |
|--------|-------------|
| `make otel-setup` | Create OpenTelemetry configuration directories |
| `make otel-start` | Start OpenTelemetry stack |
| `make otel-stop` | Stop OpenTelemetry stack |
| `make otel-restart` | Restart OpenTelemetry stack |
| `make otel-run` | Run JOTP with OpenTelemetry agent |
| `make otel-test` | Run tests with tracing |
| `make otel-metrics` | Open Prometheus UI |
| `make otel-traces` | Open Jaeger UI |
| `make otel-logs` | View stack logs |
| `make otel-validate` | Validate configuration |
| `make otel-status` | Show stack status |
| `make otel-clean` | Remove containers and volumes |

## Troubleshooting

### No Traces in Jaeger

1. Verify OTLP endpoint:
   ```bash
   curl http://localhost:4317
   ```

2. Check Collector logs:
   ```bash
   make otel-logs
   ```

3. Verify service name in Jaeger UI matches `OTEL_SERVICE_NAME`

### No Metrics in Prometheus

1. Check Prometheus targets:
   ```bash
   curl http://localhost:9090/api/v1/targets
   ```

2. Verify Collector metrics endpoint:
   ```bash
   curl http://localhost:8888/metrics
   ```

### High Memory Usage

1. Reduce `memory_limiter` limit in `collector-config.yaml`
2. Increase batch timeout to reduce export frequency
3. Filter out unwanted metrics/traces

## Advanced Topics

### Virtual Threads Context Propagation

OpenTelemetry 1.42+ supports virtual threads. Trace context automatically propagates across:

- `StructuredTaskScope` operations
- Virtual thread boundaries
- Asynchronous message passing

### Custom Spans

```java
var tracer = DistributedTracer.create("my-service");
var span = tracer.spanBuilder("custom-operation")
    .setAttribute("operation.type", "compute")
    .startSpan();

try (var scope = span.makeCurrent()) {
    // Do work
    performComputation();
    span.addEvent("computation-complete");
} finally {
    span.end();
}
```

### Custom Metrics

```java
var metrics = MetricsCollector.create("my-service");
var counter = metrics.counter("custom.counter");
counter.increment();

var timer = metrics.timer("custom.duration");
try (var t = timer.start()) {
    // Time operation
    performOperation();
}
```

## Docker Compose Services

| Service | URL | Credentials |
|---------|-----|-------------|
| Jaeger UI | http://localhost:16686 | None |
| Prometheus | http://localhost:9090 | None |
| Grafana | http://localhost:3000 | admin/admin |
| OTLP Collector | http://localhost:4317 | gRPC |
| OTLP Collector | http://localhost:4318 | HTTP |

## References

- [OpenTelemetry Specification](https://opentelemetry.io/docs/reference/specification/)
- [OpenTelemetry Java](https://opentelemetry.io/docs/instrumentation/java/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
