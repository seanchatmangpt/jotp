# Observability with Service Mesh: JOTP Distributed Tracing & Metrics

**Overview:** Comprehensive observability strategy for JOTP applications running on service mesh (Istio/Linkerd) with distributed tracing, metrics, and logging.

---

## Table of Contents

1. [Distributed Tracing](#distributed-tracing)
2. [Metrics Collection](#metrics-collection)
3. [Logging Strategy](#logging-strategy)
4. [Dashboards](#dashboards)
5. [Alerting](#alerting)
6. [Production Setup](#production-setup)

---

## Distributed Tracing

### OpenTelemetry Integration

**Add OpenTelemetry Dependencies:**

```xml
<!-- pom.xml -->
<dependencies>
    <!-- OpenTelemetry API -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-api</artifactId>
        <version>1.35.0</version>
    </dependency>

    <!-- OpenTelemetry SDK -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-sdk</artifactId>
        <version>1.35.0</version>
    </dependency>

    <!-- OpenTelemetry Exporter for Jaeger -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
        <version>1.35.0</version>
    </dependency>

    <!-- OpenTelemetry Instrumentation for HTTP/gRPC -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-instrumentation-annotations</artifactId>
        <version>1.32.0</version>
    </dependency>

    <!-- Spring Boot integration (if using Spring) -->
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-spring-boot-starter</artifactId>
        <version>1.32.0</version>
    </dependency>
</dependencies>
```

### Configure OpenTelemetry

**OpenTelemetry Configuration:**

```java
// TelemetryConfiguration.java
package io.github.seanchatmangpt.jotp.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.resources.Resource;

import java.time.Duration;

public class TelemetryConfiguration {

    private static final String SERVICE_NAME = "jotp-telemetry";
    private static final String JAEGER_ENDPOINT = "http://jaeger-collector.istio-system:4317";

    public static OpenTelemetry initializeOpenTelemetry() {
        Resource resource = Resource.getDefault()
            .toBuilder()
            .put("service.name", SERVICE_NAME)
            .put("service.version", "1.0.0")
            .put("deployment.environment", "production")
            .build();

        // Use batch span processor for better performance
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(JAEGER_ENDPOINT)
            .setTimeout(Duration.ofSeconds(30))
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.create(spanExporter))
            .setResource(resource)
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal();
    }

    public static Tracer getTracer() {
        OpenTelemetry openTelemetry = initializeOpenTelemetry();
        return openTelemetry.getTracer(SERVICE_NAME, "1.0.0");
    }
}
```

### JOTP DistributedTracer Integration

**Bridge JOTP DistributedTracer with OpenTelemetry:**

```java
// OpenTelemetryTracerBridge.java
package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.DistributedTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.Map;

public class OpenTelemetryTracerBridge {

    private final DistributedTracer jotpTracer;
    private final Tracer openTelemetryTracer;

    public OpenTelemetryTracerBridge(
        DistributedTracer jotpTracer,
        Tracer openTelemetryTracer
    ) {
        this.jotpTracer = jotpTracer;
        this.openTelemetryTracer = openTelemetryTracer;
    }

    /**
     * Create a span that is recorded in both JOTP and OpenTelemetry.
     */
    public DistributedTracer.Span createSpan(String name, Map<String, Object> attributes) {
        // Create JOTP span
        DistributedTracer.Span jotpSpan = jotpTracer.spanBuilder(name)
            .build()
            .startSpan();

        // Create OpenTelemetry span
        Span otelSpan = openTelemetryTracer.spanBuilder(name)
            .setParent(Context.current())
            .startSpan();

        try (var scope = otelSpan.makeCurrent()) {
            // Add attributes to both spans
            attributes.forEach((key, value) -> {
                jotpSpan.setAttribute(key, String.valueOf(value));
                otelSpan.setAttribute(key, String.valueOf(value));
            });

            return new BridgeSpan(jotpSpan, otelSpan);
        }
    }

    /**
     * Span that bridges JOTP and OpenTelemetry.
     */
    private static class BridgeSpan implements DistributedTracer.Span {
        private final DistributedTracer.Span jotpSpan;
        private final Span otelSpan;

        BridgeSpan(DistributedTracer.Span jotpSpan, Span otelSpan) {
            this.jotpSpan = jotpSpan;
            this.otelSpan = otelSpan;
        }

        @Override
        public String name() {
            return jotpSpan.name();
        }

        @Override
        public TraceContext context() {
            return jotpSpan.context();
        }

        @Override
        public SpanKind kind() {
            return jotpSpan.kind();
        }

        @Override
        public StatusCode status() {
            return jotpSpan.status();
        }

        @Override
        public String statusDescription() {
            return jotpSpan.statusDescription();
        }

        @Override
        public Instant startTime() {
            return jotpSpan.startTime();
        }

        @Override
        public Instant endTime() {
            return jotpSpan.endTime();
        }

        @Override
        public Duration duration() {
            return jotpSpan.duration();
        }

        @Override
        public boolean isEnded() {
            return jotpSpan.isEnded();
        }

        @Override
        public boolean isRecording() {
            return jotpSpan.isRecording();
        }

        @Override
        public void setStatus(StatusCode status) {
            jotpSpan.setStatus(status);
            otelSpan.setStatus(toOtelStatus(status));
        }

        @Override
        public void setStatus(StatusCode status, String description) {
            jotpSpan.setStatus(status, description);
            otelSpan.setStatus(toOtelStatus(status), description);
        }

        @Override
        public void addEvent(String name) {
            jotpSpan.addEvent(name);
            otelSpan.addEvent(name);
        }

        @Override
        public void addEvent(String name, Map<String, Object> attributes) {
            jotpSpan.addEvent(name, attributes);
            otelSpan.addEvent(name, attributes.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> String.valueOf(e.getValue())
                )));
        }

        @Override
        public void setAttribute(String key, String value) {
            jotpSpan.setAttribute(key, value);
            otelSpan.setAttribute(key, value);
        }

        @Override
        public void setAttribute(String key, long value) {
            jotpSpan.setAttribute(key, value);
            otelSpan.setAttribute(key, value);
        }

        @Override
        public void setAttribute(String key, double value) {
            jotpSpan.setAttribute(key, value);
            otelSpan.setAttribute(key, value);
        }

        @Override
        public void setAttribute(String key, boolean value) {
            jotpSpan.setAttribute(key, value);
            otelSpan.setAttribute(key, value);
        }

        @Override
        public void recordException(Throwable exception) {
            jotpSpan.recordException(exception);
            otelSpan.recordException(exception);
        }

        @Override
        public void end() {
            jotpSpan.end();
            otelSpan.end();
        }

        @Override
        public void end(Throwable exception) {
            jotpSpan.end(exception);
            otelSpan.end(exception);
        }

        @Override
        public SpanScope makeCurrent() {
            var jotpScope = jotpSpan.makeCurrent();
            var otelScope = otelSpan.makeCurrent();
            return () -> {
                jotpScope.close();
                otelScope.close();
            };
        }

        private io.opentelemetry.api.trace.StatusCode toOtelStatus(StatusCode status) {
            return switch (status) {
                case OK -> io.opentelemetry.api.trace.StatusCode.OK;
                case ERROR -> io.opentelemetry.api.trace.StatusCode.ERROR;
                default -> io.opentelemetry.api.trace.StatusCode.UNSET;
            };
        }
    }
}
```

### Trace Context Propagation

**Propagate Trace Context Across JOTP Processes:**

```java
// TraceContextPropagation.java
package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.Map;

public class TraceContextPropagation {

    private final Tracer tracer;

    public TraceContextPropagation(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Wrap a JOTP message handler with automatic trace context propagation.
     */
    public <S, M> BiFunction<S, M, S> withTracing(
        BiFunction<S, M, S> handler,
        String processName
    ) {
        return (state, message) -> {
            // Extract trace context from message if present
            Context parentContext = extractContext(message);

            // Create span for message processing
            Span span = tracer.spanBuilder(processName + "-process")
                .setParent(parentContext)
                .setAttribute("message.type", message.getClass().getSimpleName())
                .startSpan();

            try (var scope = span.makeCurrent()) {
                span.addEvent("processing-start");

                // Handle message
                S newState = handler.apply(state, message);

                span.addEvent("processing-complete");
                span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
                return newState;
            } catch (Exception e) {
                span.recordException(e);
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                span.end();
            }
        };
    }

    private Context extractContext(M message) {
        // Extract trace context from message metadata
        if (message instanceof TracedMessage tm) {
            return tm.getTraceContext();
        }
        return Context.current();
    }

    /**
     * Inject trace context into outgoing messages.
     */
    public <M> M injectContext(M message) {
        if (message instanceof TracedMessage tm) {
            tm.setTraceContext(Context.current());
        }
        return message;
    }
}

// Message interface for traced messages
interface TracedMessage {
    Context getTraceContext();
    void setTraceContext(Context context);
}
```

### Trace Naming Conventions

**Standardized Span Names for JOTP:**

```java
// SpanNamingConventions.java
public class SpanNamingConventions {

    // Process lifecycle spans
    public static final String PROCESS_CREATE = "jotp.process.create";
    public static final String PROCESS_START = "jotp.process.start";
    public static final String PROCESS_STOP = "jotp.process.stop";
    public static final String PROCESS_CRASH = "jotp.process.crash";
    public static final String PROCESS_RESTART = "jotp.process.restart";

    // Message handling spans
    public static final String MESSAGE_SEND = "jotp.message.send";
    public static final String MESSAGE_RECEIVE = "jotp.message.receive";
    public static final String MESSAGE_PROCESS = "jotp.message.process";

    // Supervisor spans
    public static final String SUPERVISOR_MONITOR = "jotp.supervisor.monitor";
    public static final String SUPERVISOR_RESTART = "jotp.supervisor.restart";
    public static final String SUPERVISOR_STRATEGY = "jotp.supervisor.strategy";

    // Registry spans
    public static final String REGISTRY_REGISTER = "jotp.registry.register";
    public static final String REGISTRY_LOOKUP = "jotp.registry.lookup";
    public static final String REGISTRY_UNREGISTER = "jotp.registry.unregister";

    // Example usage
    public static Span createProcessSpan(Tracer tracer, String operation, String processId) {
        return tracer.spanBuilder(operation)
            .setAttribute("jotp.process.id", processId)
            .setAttribute("jotp.process.type", "user-process")
            .startSpan();
    }
}
```

---

## Metrics Collection

### Golden Metrics for JOTP

**Define the four golden signals:**

```java
// GoldenMetrics.java
package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.observability.ProcessMetrics;

public class GoldenMetrics {

    private final ProcessMetrics processMetrics;

    public GoldenMetrics(ProcessMetrics processMetrics) {
        this.processMetrics = processMetrics;
    }

    /**
     * 1. LATENCY - Time to process messages
     */
    public double getMessageLatencyMs() {
        // Calculate from ProcessMetrics
        var snapshot = processMetrics.snapshot();
        return snapshot.averageProcessLifetimeMs();
    }

    /**
     * 2. TRAFFIC - Messages per second
     */
    public double getTrafficRate() {
        var snapshot = processMetrics.snapshot();
        return snapshot.messagesSent() / 60.0;  // per minute
    }

    /**
     * 3. ERRORS - Crash and error rate
     */
    public double getErrorRate() {
        var snapshot = processMetrics.snapshot();
        return snapshot.crashRate();
    }

    /**
     * 4. SATURATION - Resource utilization
     */
    public double getSaturation() {
        // Queue depth as saturation indicator
        var snapshot = processMetrics.snapshot();
        double currentQueue = snapshot.currentQueueDepth();
        double maxQueue = snapshot.maxQueueDepth();
        return maxQueue > 0 ? (currentQueue / maxQueue) * 100.0 : 0.0;
    }

    /**
     * Custom JOTP metrics
     */
    public JOTPMetricsSnapshot getJOTPMetrics() {
        var snapshot = processMetrics.snapshot();
        return new JOTPMetricsSnapshot(
            snapshot.processesCreated(),
            snapshot.processesCrashed(),
            snapshot.processesRestarted(),
            snapshot.messagesSent(),
            snapshot.messagesReceived(),
            snapshot.messagesProcessed(),
            snapshot.currentQueueDepth(),
            snapshot.healthyProcesses(),
            snapshot.degradedProcesses(),
            snapshot.unhealthyProcesses()
        );
    }

    public record JOTPMetricsSnapshot(
        long processesCreated,
        long processesCrashed,
        long processesRestarted,
        long messagesSent,
        long messagesReceived,
        long messagesProcessed,
        long currentQueueDepth,
        long healthyProcesses,
        long degradedProcesses,
        long unhealthyProcesses
    ) {
        public double messageSuccessRate() {
            return messagesReceived > 0
                ? (messagesProcessed * 100.0) / messagesReceived
                : 0.0;
        }

        public double processHealthPercentage() {
            long total = healthyProcesses + degradedProcesses + unhealthyProcesses;
            return total > 0
                ? (healthyProcesses * 100.0) / total
                : 0.0;
        }
    }
}
```

### Prometheus Integration

**Expose JOTP Metrics for Prometheus:**

```java
// PrometheusMetricsExporter.java
package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.observability.ProcessMetrics;
import io.github.seanchatmangpt.jotp.DistributedTracer;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;

public class PrometheusMetricsExporter {

    private final ProcessMetrics processMetrics;
    private final DistributedTracer tracer;

    // Prometheus metrics
    private final Counter messagesSent;
    private final Counter messagesReceived;
    private final Counter processesCrashed;
    private final Counter processesRestarted;
    private final Gauge processCount;
    private final Gauge queueDepth;
    private final Histogram messageLatency;

    public PrometheusMetricsExporter(
        ProcessMetrics processMetrics,
        DistributedTracer tracer
    ) {
        this.processMetrics = processMetrics;
        this.tracer = tracer;

        // Initialize Prometheus metrics
        this.messagesSent = Counter.build()
            .name("jotp_messages_sent_total")
            .help("Total messages sent by JOTP processes")
            .labelNames("process_type")
            .register();

        this.messagesReceived = Counter.build()
            .name("jotp_messages_received_total")
            .help("Total messages received by JOTP processes")
            .labelNames("process_type")
            .register();

        this.processesCrashed = Counter.build()
            .name("jotp_processes_crashed_total")
            .help("Total process crashes")
            .labelNames("crash_type")
            .register();

        this.processesRestarted = Counter.build()
            .name("jotp_processes_restarted_total")
            .help("Total supervisor-initiated restarts")
            .labelNames("restart_reason")
            .register();

        this.processCount = Gauge.build()
            .name("jotp_process_count")
            .help("Current number of JOTP processes")
            .labelNames("state")  // healthy, degraded, unhealthy
            .register();

        this.queueDepth = Gauge.build()
            .name("jotp_queue_depth")
            .help("Current queue depth for processes")
            .labelNames("process_id")
            .register();

        this.messageLatency = Histogram.build()
            .name("jotp_message_latency_seconds")
            .help("Message processing latency")
            .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
            .labelNames("process_type", "message_type")
            .register();
    }

    /**
     * Update Prometheus metrics from ProcessMetrics.
     */
    public void updateMetrics() {
        var snapshot = processMetrics.snapshot();

        // Update gauges
        processCount.labels("healthy").set(snapshot.healthyProcesses());
        processCount.labels("degraded").set(snapshot.degradedProcesses());
        processCount.labels("unhealthy").set(snapshot.unhealthyProcesses());

        queueDepth.labels("all").set(snapshot.currentQueueDepth());

        // Update counters (incremental)
        messagesSent.labels("all").inc(snapshot.messagesSent());
        messagesReceived.labels("all").inc(snapshot.messagesReceived());
        processesCrashed.labels("all").inc(snapshot.processesCrashed());
        processesRestarted.labels("all").inc(snapshot.processesRestarted());
    }

    /**
     * Export metrics in Prometheus format.
     */
    public String exportMetrics() throws IOException {
        updateMetrics();

        StringWriter writer = new StringWriter();
        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        return writer.toString();
    }

    /**
     * Record message latency.
     */
    public void recordMessageLatency(String processType, String messageType, Duration latency) {
        messageLatency.labels(processType, messageType).observe(latency.toSeconds());
    }
}
```

### HTTP Metrics Endpoint

**Expose metrics endpoint:**

```java
// MetricsEndpoint.java
package io.github.seanchatmangpt.jotp.observability;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;

@Path("/metrics")
public class MetricsEndpoint {

    private final PrometheusMetricsExporter exporter;

    public MetricsEndpoint(PrometheusMetricsExporter exporter) {
        this.exporter = exporter;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getMetrics() {
        try {
            return exporter.exportMetrics();
        } catch (IOException e) {
            return "# Error exporting metrics: " + e.getMessage();
        }
    }
}
```

---

## Logging Strategy

### Structured Logging with Trace Context

**Configure structured logging:**

```java
// StructuredLogger.java
package io.github.seanchatmangpt.jotp.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class StructuredLogger {

    private static final Logger log = LoggerFactory.getLogger(StructuredLogger.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Tracer tracer;

    public StructuredLogger(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Log structured message with trace context.
     */
    public void log(String level, String message, Map<String, Object> data) {
        Map<String, Object> logEntry = new HashMap<>();
        logEntry.put("@timestamp", Instant.now().toString());
        logEntry.put("level", level);
        logEntry.put("message", message);
        logEntry.put("service", "jotp-telemetry");

        // Add trace context
        Span.current().getSpanContext().getTraceId().ifPresent(traceId ->
            logEntry.put("trace_id", traceId)
        );
        Span.current().getSpanContext().getSpanId().ifPresent(spanId ->
            logEntry.put("span_id", spanId)
        );

        // Add data
        if (data != null) {
            logEntry.putAll(data);
        }

        try {
            String json = objectMapper.writeValueAsString(logEntry);
            switch (level.toLowerCase()) {
                case "error" -> log.error(json);
                case "warn" -> log.warn(json);
                case "info" -> log.info(json);
                case "debug" -> log.debug(json);
                default -> log.info(json);
            }
        } catch (Exception e) {
            log.error("Failed to serialize log entry: {}", e.getMessage());
        }
    }

    public void info(String message, Map<String, Object> data) {
        log("info", message, data);
    }

    public void error(String message, Map<String, Object> data) {
        log("error", message, data);
    }

    public void warn(String message, Map<String, Object> data) {
        log("warn", message, data);
    }
}
```

### JOTP Process Logging

**Log process lifecycle events:**

```java
// ProcessLifecycleLogger.java
package io.github.seanchatmangpt.jotp.observability;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import java.util.Map;

public class ProcessLifecycleLogger {

    private final StructuredLogger logger;
    private final Tracer tracer;

    public ProcessLifecycleLogger(StructuredLogger logger, Tracer tracer) {
        this.logger = logger;
        this.tracer = tracer;
    }

    public void logProcessCreated(ProcRef<?, ?> procRef, String processType) {
        Span span = tracer.spanBuilder("jotp.process.created")
            .setAttribute("process.id", procRef.processId())
            .setAttribute("process.type", processType)
            .startSpan();

        try (var scope = span.makeCurrent()) {
            logger.info("Process created", Map.of(
                "process_id", procRef.processId(),
                "process_type", processType,
                "timestamp", System.currentTimeMillis()
            ));
        } finally {
            span.end();
        }
    }

    public void logProcessCrashed(String processId, String reason, Throwable exception) {
        Span span = tracer.spanBuilder("jotp.process.crashed")
            .setAttribute("process.id", processId)
            .setAttribute("crash.reason", reason)
            .startSpan();

        try (var scope = span.makeCurrent()) {
            span.recordException(exception);

            logger.error("Process crashed", Map.of(
                "process_id", processId,
                "reason", reason,
                "exception", exception.getMessage(),
                "stack_trace", getStackTrace(exception)
            ));
        } finally {
            span.end();
        }
    }

    public void logProcessRestarted(String processId, String reason) {
        Span span = tracer.spanBuilder("jotp.process.restarted")
            .setAttribute("process.id", processId)
            .setAttribute("restart.reason", reason)
            .startSpan();

        try (var scope = span.makeCurrent()) {
            logger.warn("Process restarted", Map.of(
                "process_id", processId,
                "reason", reason,
                "timestamp", System.currentTimeMillis()
            ));
        } finally {
            span.end();
        }
    }

    private String getStackTrace(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
```

---

## Dashboards

### Grafana Dashboard Configuration

**Complete JOTP observability dashboard:**

```json
{
  "dashboard": {
    "title": "JOTP Service Mesh Observability",
    "panels": [
      {
        "title": "Request Rate (Mesh)",
        "targets": [
          {
            "expr": "sum(rate(request_total{namespace=\"jotp-system\"}[1m])) by (deployment)"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Latency P95 (Mesh)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(response_latency_ms_bucket{namespace=\"jotp-system\"}[5m])) by (le, deployment))"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Success Rate (Mesh)",
        "targets": [
          {
            "expr": "sum(rate(response_total{namespace=\"jotp-system\",classification=\"success\"}[5m])) / sum(rate(response_total{namespace=\"jotp-system\"}[5m]))"
          }
        ],
        "type": "graph"
      },
      {
        "title": "JOTP Process Count",
        "targets": [
          {
            "expr": "jotp_process_count"
          }
        ],
        "type": "graph"
      },
      {
        "title": "JOTP Message Throughput",
        "targets": [
          {
            "expr": "rate(jotp_messages_sent_total[1m])"
          }
        ],
        "type": "graph"
      },
      {
        "title": "JOTP Process Crash Rate",
        "targets": [
          {
            "expr": "rate(jotp_processes_crashed_total[5m])"
          }
        ],
        "type": "graph"
      },
      {
        "title": "JOTP Supervisor Restart Rate",
        "targets": [
          {
            "expr": "rate(jotp_processes_restarted_total[5m])"
          }
        ],
        "type": "graph"
      },
      {
        "title": "JOTP Queue Depth",
        "targets": [
          {
            "expr": "jotp_queue_depth"
          }
        ],
        "type": "graph"
      },
      {
        "title": "JOTP Message Latency",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, jotp_message_latency_seconds)"
          }
        ],
        "type": "graph"
      },
      {
        "title": "Service Map",
        "targets": [
          {
            "expr": "sum(rate(request_total{namespace=\"jotp-system\"}[5m])) by (source_deployment, destination_deployment)"
          }
        ],
        "type": "sankey"
      }
    ]
  }
}
```

### Service Map Visualization

**Service topology:**

```bash
# Use Linkerd Viz or Kiali for service map
linkerd viz dashboard

# Or use Kiali for Istio
istioctl dashboard kiali
```

---

## Alerting

### Prometheus Alert Rules

**Alert rules for JOTP:**

```yaml
# alert-rules.yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: jotp-alerts
  namespace: jotp-system
spec:
  groups:
  - name: jotp-process-health
    rules:
    # Alert on high crash rate
    - alert: JOTPHighCrashRate
      expr: |
        rate(jotp_processes_crashed_total[5m]) > 0.1
      for: 2m
      labels:
        severity: warning
        component: jotp
      annotations:
        summary: "High JOTP process crash rate"
        description: "JOTP processes are crashing at rate {{ $value }} crashes/sec"

    # Alert on high restart rate
    - alert: JOTPHighRestartRate
      expr: |
        rate(jotp_processes_restarted_total[5m]) > 0.05
      for: 5m
      labels:
        severity: warning
        component: jotp
      annotations:
        summary: "High JOTP supervisor restart rate"
        description: "Supervisor restarts at rate {{ $value }} restarts/sec"

    # Alert on high queue depth
    - alert: JOTPHighQueueDepth
      expr: |
        jotp_queue_depth > 10000
      for: 5m
      labels:
        severity: warning
        component: jotp
      annotations:
        summary: "High JOTP queue depth"
        description: "Queue depth is {{ $value }} messages"

    # Alert on high latency
    - alert: JOTPHighMessageLatency
      expr: |
        histogram_quantile(0.95, jotp_message_latency_seconds) > 1.0
      for: 5m
      labels:
        severity: warning
        component: jotp
      annotations:
        summary: "High JOTP message latency"
        description: "P95 latency is {{ $value }} seconds"

    # Alert on low success rate
    - alert: JOTPLowSuccessRate
      expr: |
        rate(jotp_messages_processed_total[5m]) / rate(jotp_messages_received_total[5m]) < 0.95
      for: 5m
      labels:
        severity: critical
        component: jotp
      annotations:
        summary: "Low JOTP message success rate"
        description: "Message success rate is {{ $value | humanizePercentage }}"

  - name: jotp-mesh-health
    rules:
    # Alert on mesh latency
    - alert: JOTPMeshHighLatency
      expr: |
        histogram_quantile(0.95, sum(rate(response_latency_ms_bucket{namespace=\"jotp-system\"}[5m])) by (le)) > 1000
      for: 5m
      labels:
        severity: warning
        component: mesh
      annotations:
        summary: "High mesh latency for JOTP services"
        description: "P95 mesh latency is {{ $value }}ms"

    # Alert on mesh error rate
    - alert: JOTPMeshHighErrorRate
      expr: |
        sum(rate(response_total{namespace=\"jotp-system\",classification!=\"success\"}[5m])) /
        sum(rate(response_total{namespace=\"jotp-system\"}[5m])) > 0.01
      for: 5m
      labels:
        severity: warning
        component: mesh
      annotations:
        summary: "High mesh error rate for JOTP services"
        description: "Error rate is {{ $value | humanizePercentage }}"
```

### AlertManager Configuration

**Route alerts to correct channels:**

```yaml
# alertmanager-config.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: alertmanager-config
  namespace: monitoring
data:
  alertmanager.yaml: |
    global:
      resolve_timeout: 5m

    route:
      group_by: ['alertname', 'cluster', 'service']
      group_wait: 10s
      group_interval: 10s
      repeat_interval: 12h
      receiver: 'default'
      routes:
      - match:
          severity: critical
        receiver: 'pagerduty'
      - match:
          severity: warning
        receiver: 'slack'

    receivers:
    - name: 'default'
      webhook_configs:
      - url: 'http://webhook:5000/alerts'

    - name: 'pagerduty'
      pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_KEY'

    - name: 'slack'
      slack_configs:
      - api_url: 'YOUR_SLACK_WEBHOOK'
        channel: '#jotp-alerts'
        title: 'JOTP Alert: {{ .GroupLabels.alertname }}'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
```

---

## Production Setup

### Complete Monitoring Stack

**Deploy complete observability stack:**

```yaml
# observability-stack.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: monitoring
---
# Prometheus Operator
apiVersion: source.toolkit.fluxcd.io/v1beta2
kind: HelmRepository
metadata:
  name: prometheus-community
  namespace: monitoring
spec:
  interval: 1h
  url: https://prometheus-community.github.io/helm-charts
---
apiVersion: helm.toolkit.fluxcd.io/v2beta1
kind: HelmRelease
metadata:
  name: prometheus-operator
  namespace: monitoring
spec:
  interval: 1h
  chart:
    spec:
      chart: kube-prometheus-stack
      version: ">=45.0.0"
      sourceRef:
        kind: HelmRepository
        name: prometheus-community
  values:
    prometheus:
      prometheusSpec:
        retention: 15d
        resources:
          requests:
            memory: "2Gi"
            cpu: "500m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        storageSpec:
          volumeClaimTemplate:
            spec:
              storageClassName: fast
              accessModes: ["ReadWriteOnce"]
              resources:
                requests:
                  storage: 50Gi
    grafana:
      enabled: true
      adminPassword: prom-operator
      resources:
        requests:
          memory: "256Mi"
          cpu: "100m"
        limits:
          memory: "512Mi"
          cpu: "500m"
---
# Jaeger for tracing
apiVersion: v1
kind: Namespace
metadata:
  name: istio-system
---
apiVersion: helm.toolkit.fluxcd.io/v2beta1
kind: HelmRelease
metadata:
  name: jaeger
  namespace: istio-system
spec:
  interval: 1h
  chart:
    spec:
      chart: jaeger
      version: ">=0.50.0"
      sourceRef:
        kind: HelmRepository
        name: jaegertracing
        namespace: istio-system
  values:
    provisionDataStore:
      cassandra: false
      elasticsearch: true
    storage:
      type: elasticsearch
      elasticsearch:
        host: elasticsearch
        port: 9200
    collector:
      enabled: true
      replicas: 2
      resources:
        requests:
          memory: "512Mi"
          cpu: "200m"
        limits:
          memory: "1Gi"
          cpu: "1000m"
    query:
      enabled: true
      replicas: 2
      resources:
        requests:
          memory: "256Mi"
          cpu: "100m"
        limits:
          memory: "512Mi"
          cpu: "500m"
```

### Verify Setup

```bash
# Verify Prometheus is scraping JOTP metrics
kubectl port-forward -n monitoring svc/prometheus-operated 9090:9090
open http://localhost:9090

# Check for JOTP targets
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job == "jotp-telemetry")'

# Verify Grafana dashboards
kubectl port-forward -n monitoring svc/grafana 3000:80
open http://localhost:3000

# Verify Jaeger is receiving traces
kubectl port-forward -n istio-system svc/jaeger-query 16686:16686
open http://localhost:16686
```

---

## References

- [OpenTelemetry Java Documentation](https://opentelemetry.io/docs/instrumentation/java/)
- [Prometheus Best Practices](https://prometheus.io/docs/practices/)
- [Grafana Dashboard Best Practices](https://grafana.com/docs/grafana/latest/best-practices/)
- [JOTP DistributedTracer](/Users/sac/jotp/src/main/java/io/github/seanchatmangpt/jotp/DistributedTracer.java)
- [Istio Observability](/Users/sac/jotp/docs/distributed/ISTIO-INTEGRATION.md)
- [Linkerd Observability](/Users/sac/jotp/docs/distributed/LINKERD-INTEGRATION.md)
