# Chapter 12: Instrument Your Application with Logs and Metrics

You cannot operate what you cannot observe. Chapter 11 gave you a cluster that scales and restarts itself. This chapter gives you the eyes to see what it is doing. Observability in TaskFlow has two complementary streams: logs answer "what happened and when," metrics answer "how often and how fast." Both streams are structured, machine-readable, and shipped to durable storage. By the end of this chapter you will have Prometheus scraping TaskFlow's JVM internals and JOTP actor statistics, Grafana displaying a dashboard with restart rate, HTTP p99 latency, and virtual thread pool depth, and `ProcMonitor` publishing a counter every time a supervisor goes down.

---

## Pattern: THE STRUCTURED LOG

**Problem**

Your application writes log lines like this:

```
2026-03-12 14:23:11.483 INFO  BoardService - Board abc123 loaded 47 tasks
2026-03-12 14:23:11.612 ERROR WebhookSender - Failed to send webhook: connection refused
```

When you grep for `abc123` in CloudWatch Logs, you get hits from four different log groups, three different services, and two different time zones. You cannot write a CloudWatch Insights query that counts webhook failures by board ID because the board ID is buried in a free-text message, not a structured field.

**Context**

Docker Swarm captures `stdout` from each container and forwards it to the log driver. If your log lines are JSON, CloudWatch Logs Insights can parse and query them natively. If they are free text, you are doing text parsing in your head.

**Solution**

Configure Logback to emit JSON using the ECS (Elastic Common Schema) layout and add request IDs to every log line via MDC (Mapped Diagnostic Context).

Add the ECS Logback encoder to `pom.xml`:

```xml
<!-- pom.xml (dependencies section) -->
<dependency>
    <groupId>co.elastic.logging</groupId>
    <artifactId>logback-ecs-encoder</artifactId>
    <version>1.6.0</version>
</dependency>
```

Configure `src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="co.elastic.logging.logback.EcsEncoder">
      <serviceName>taskflow-api</serviceName>
      <serviceVersion>${APP_VERSION:-unknown}</serviceVersion>
      <includeMarkers>true</includeMarkers>
    </encoder>
  </appender>

  <!-- Async wrapper: log calls return immediately, I/O happens on a background thread -->
  <appender name="ASYNC_STDOUT" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="STDOUT"/>
    <queueSize>512</queueSize>
    <discardingThreshold>0</discardingThreshold>
  </appender>

  <root level="INFO">
    <appender-ref ref="ASYNC_STDOUT"/>
  </root>

  <!-- Suppress noisy framework logs -->
  <logger name="org.springframework" level="WARN"/>
  <logger name="io.netty" level="WARN"/>
</configuration>
```

Inject request IDs via a Spring `HandlerInterceptor`:

```java
// RequestIdInterceptor.java
@Component
public class RequestIdInterceptor implements HandlerInterceptor {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_KEY = "request_id";

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        String requestId = Optional
            .ofNullable(request.getHeader(REQUEST_ID_HEADER))
            .orElse(UUID.randomUUID().toString());

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {

        MDC.remove(MDC_KEY);
    }
}
```

Now every log line from any class processing this request automatically carries the `request_id` field in the JSON output. You can paste a request ID from an error report into CloudWatch Logs Insights and retrieve the complete trace across all services in seconds.

**Consequences**

Structured logs cost slightly more in CPU (JSON serialization) and storage (JSON is more verbose than text) than plain text logs. The async appender absorbs the serialization cost off the request path. The storage cost is offset by not having to run a log parsing pipeline. CloudWatch Logs Insights queries against structured fields are an order of magnitude faster than regex queries against free text.

MDC values survive the calling virtual thread's lifetime. They do not automatically propagate to child virtual threads spawned by `Thread.ofVirtual()`. If you spawn work inside a request handler, copy the MDC map to the child thread explicitly:

```java
Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
Thread.ofVirtual().start(() -> {
    MDC.setContextMap(mdcCopy != null ? mdcCopy : Map.of());
    doBackgroundWork();
    MDC.clear();
});
```

---

## Pattern: THE RESTART RATE GAUGE

**Problem**

Your supervisor is restarting the `BoardService` actor, but you do not know how often. Is it twice a day? Twice a minute? The distinction matters: twice a day is a bug worth tracking; twice a minute is a production incident.

**Context**

JOTP's `Supervisor` tracks internal restart counts for its sliding window check. Exposing that count as a Micrometer gauge lets Prometheus scrape it and Grafana alert on it. The metric is a gauge rather than a counter because a restart that happened an hour ago should not inflate the current reading — you want the rate within the current window, not cumulative since boot.

**Solution**

Add the Micrometer Prometheus registry:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Expose the Prometheus endpoint in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: taskflow-api
      environment: ${APP_ENV:local}
```

Register a custom counter that increments each time a supervisor's monitored process crashes. Because `Supervisor` exposes `isRunning()` and `fatalError()` but not a cumulative restart count, the idiomatic approach is to increment a Micrometer counter inside a `ProcMonitor` callback attached to each critical child `ProcRef`, rather than polling a non-existent method on the supervisor:

```java
// SupervisorMetrics.java
@Component
public class SupervisorMetrics {

    private static final Logger log = LoggerFactory.getLogger(SupervisorMetrics.class);

    public SupervisorMetrics(
            MeterRegistry registry,
            ProcRef<BoardState, BoardMsg> boardRef,
            ProcRef<WebhookState, WebhookMsg> webhookRef) {

        attachRestartCounter(registry, "board-service", boardRef);
        attachRestartCounter(registry, "webhook-sender", webhookRef);

        log.info("Supervisor metrics registered.");
    }

    private <S, M> void attachRestartCounter(
            MeterRegistry registry, String name, ProcRef<S, M> ref) {
        // ProcMonitor.monitor takes a Proc; unwrap the ProcRef with .proc().
        // null reason = normal exit; non-null = crash requiring a restart counter increment.
        ProcMonitor.monitor(ref.proc(), reason -> {
            if (reason != null) {
                registry.counter("supervisor.restart.count", "supervisor", name).increment();
            }
        });
    }
}
```

The `supervisor.restart.count` counter is monotonically increasing. Prometheus polls it at its scrape interval (typically fifteen seconds). Graph it in Grafana as a rate (`rate(supervisor_restart_count_total[5m])`) to see restarts per second averaged over five minutes — a value that should stay near zero in normal operation.

In addition to custom gauges, Spring Boot Actuator with Micrometer registers a comprehensive set of default metrics automatically:

```
# Sample output from /actuator/prometheus (abbreviated)
jvm_memory_used_bytes{area="heap",...}          4.82e+08
jvm_gc_pause_seconds_max{cause="G1 Minor GC"}  0.023
jvm_threads_live_threads                        312
jvm_threads_daemon_threads                      47
http_server_requests_seconds_count{uri="/api/boards/{boardId}",status="200"} 14387
http_server_requests_seconds_max{uri="/api/boards/{boardId}",status="200"}   0.487
process_cpu_usage                               0.31
```

Virtual thread count is available via `jvm_threads_live_threads`. In a healthy TaskFlow cluster under moderate load, this number hovers between 200 and 500. If it climbs into the thousands and keeps climbing, your actors are blocking — likely on a slow external service — and you need to investigate.

**Consequences**

Every gauge carries a small CPU cost for the scrape. A hundred gauges scraped every fifteen seconds is negligible. Ten thousand gauges scraped every second is not. Register gauges for actors that matter to your SLOs, not for every actor in the system.

Micrometer's `Metrics.gauge()` holds a weak reference to the object being measured. If the `Supervisor` instance is garbage collected, the gauge silently disappears from Prometheus output. Make sure your supervisor instances are Spring beans with singleton scope — not local variables.

---

## Pattern: THE LIVENESS SENTINEL

**Problem**

You want to know the moment a supervisor goes down, not five minutes later when someone notices the error rate climbing in Grafana. The supervisor restart rate gauge tells you the current restart count, but it does not fire an event when the supervisor itself fails.

**Context**

`ProcMonitor.monitor()` delivers a DOWN notification when the monitored process terminates for any reason. The `Consumer<Throwable>` callback receives `null` for a graceful shutdown (`Proc.stop()`) or the `Throwable` exit cause for an abnormal crash. The notification is delivered on the target's virtual thread, so the callback must not block; use it to enqueue a message or update an atomic flag.

**Solution**

Attach a `ProcMonitor` to each critical supervisor at application startup:

```java
// SupervisorHealthMonitor.java
@Component
public class SupervisorHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(SupervisorHealthMonitor.class);
    private final MeterRegistry registry;

    public SupervisorHealthMonitor(
            MeterRegistry registry,
            ProcRef<BoardState, BoardMsg> boardSupervisorRef,
            ProcRef<WebhookState, WebhookMsg> webhookSupervisorRef) {

        this.registry = registry;

        attachMonitor("board-supervisor", boardSupervisorRef);
        attachMonitor("webhook-supervisor", webhookSupervisorRef);
    }

    private <S, M> void attachMonitor(String name, ProcRef<S, M> ref) {
        // ProcMonitor.monitor takes a Proc directly; use ref.proc() to unwrap the ProcRef.
        // The Consumer<Throwable> receives null for a normal (graceful) exit,
        // or the Throwable that caused an abnormal termination.
        ProcMonitor.monitor(ref.proc(), reason -> {
            if (reason != null) {
                log.error("Supervisor '{}' crashed. Reason: {}", name, reason.getMessage());
                registry
                    .counter("supervisor.down", "supervisor", name, "cause", "crash")
                    .increment();
            } else {
                log.info("Supervisor '{}' shut down gracefully.", name);
                registry
                    .counter("supervisor.down", "supervisor", name, "cause", "shutdown")
                    .increment();
            }
        });

        log.info("Liveness sentinel attached to '{}'.", name);
    }
}
```

The `supervisor.down` counter is a Prometheus counter — it only goes up. In Grafana, graphing `increase(supervisor_down_total[10m])` gives you supervisor failures in the last ten minutes. A non-zero value at 3 a.m. is worth a PagerDuty alert.

The unilateral nature of `ProcMonitor` is worth emphasizing. When `boardSupervisorRef` goes down, the `SupervisorHealthMonitor` is unaffected. It does not crash, it does not stop monitoring `webhookSupervisorRef`, and it does not propagate the failure to the Spring application context. You can use this guarantee to write monitoring logic that runs for the lifetime of the JVM regardless of what the supervised actors are doing.

To integrate with Spring Boot Actuator's liveness probe:

```java
// SupervisorHealthIndicator.java
@Component
public class SupervisorHealthIndicator implements HealthIndicator {

    private final SupervisorHealthMonitor monitor;
    private final AtomicBoolean boardSupervisorDown = new AtomicBoolean(false);

    public SupervisorHealthIndicator(SupervisorHealthMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        if (boardSupervisorDown.get()) {
            return Health.down()
                .withDetail("board-supervisor", "DOWN — circuit open")
                .build();
        }
        return Health.up()
            .withDetail("board-supervisor", "UP")
            .build();
    }
}
```

When `boardSupervisorDown` is `true`, `/actuator/health` returns HTTP 503. The ALB health check sees the 503, marks the instance unhealthy, and stops routing traffic to it. The Auto Scaling Group eventually replaces the instance. This is the self-healing loop: JOTP's supervision tree fails fast, `ProcMonitor` surfaces the failure, and the health indicator triggers AWS's instance replacement.

**Consequences**

`ProcMonitor` is the right tool for observing failures you cannot prevent. You cannot prevent the board supervisor from crashing if a bug is introduced in the board service. What you can do is detect the crash within milliseconds, record it in Prometheus, and surface it in Grafana before a human notices the elevated error rate.

The one discipline required is that your `ProcMonitor` callbacks must never throw unchecked exceptions. If the callback itself crashes, the DOWN notification is swallowed silently. Wrap callback logic in a `try-catch(Exception)` and log at error level if anything goes wrong.

---

## What Have You Learned?

- Structured JSON logs (ECS format via Logback's ECS encoder) make CloudWatch Logs Insights queries fast and precise. Free-text logs are for humans; structured logs are for machines.
- MDC propagates context (request IDs, user IDs, board IDs) automatically to every log line within the same thread. For child virtual threads, copy the MDC map explicitly.
- `micrometer-registry-prometheus` + Spring Boot Actuator exposes JVM heap, GC pauses, virtual thread count, and HTTP request latency with zero application code.
- Attaching a `ProcMonitor` callback on each child `ProcRef` and incrementing a `supervisor.restart.count` counter on crash exposes restart activity to Prometheus. Graph as a rate (`rate(supervisor_restart_count_total[5m])`) to distinguish occasional restarts from restart storms.
- `ProcMonitor.monitor(ref.proc(), reason -> {...})` delivers a DOWN notification when a process crashes (`reason != null`) or exits normally (`reason == null`). It is unilateral — the monitor does not crash when the supervised process crashes.
- Integrating `ProcMonitor` with a Spring Boot `HealthIndicator` closes the self-healing loop: crash → monitor → health endpoint returns 503 → ALB removes instance → ASG replaces it.
- The async Logback appender (`AsyncAppender`) absorbs log I/O off the request path. Without it, every log statement adds filesystem I/O latency to your request handling.

---
