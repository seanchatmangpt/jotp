# Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP

## Part IV: Production Operations

---

# Chapter 11: Autoscaling and Optimizing Your Deployment Strategy

TaskFlow has a working Docker Swarm stack and a supervisor tree that restarts failing services. That is a fine starting point, but it is not a production system. A production system responds to load automatically, deploys new versions without dropping WebSocket connections, and refuses to cascade a failure in one service into a failure everywhere. This chapter adds all three capabilities. By the end you will have an AWS Auto Scaling Group that scales TaskFlow from two nodes to ten, a rolling-deploy strategy that keeps `ProcRef` handles alive across restarts, and two JOTP-native fault-tolerance mechanisms — backpressure and circuit breaking — that protect the cluster under load.

---

## Pattern: THE ELASTIC CLUSTER

**Problem**

You set the desired count for your Docker Swarm service to four replicas at 10 a.m. on a Monday. By 3 p.m. on a Friday you have four replicas and a wall of CloudWatch alarms telling you the CPU has been pinned at 95% for the past six hours, response times are climbing, and two containers have been restarted by their supervisors seventeen times. You never added more nodes because you had to do it manually — and you were in a meeting.

**Context**

TaskFlow's Kanban board accumulates real-time events: cards move, lists update, comments appear, webhooks fire. The load is bursty and predictable in aggregate (business hours) but unpredictable in detail (a viral product launch, a big customer demo). You cannot pre-provision ten nodes permanently — that doubles your AWS bill. You need the cluster to grow when it needs to and shrink when it does not.

**Solution**

Use an AWS Auto Scaling Group (ASG) as the node pool backing your Swarm manager. Define scale-out and scale-in CloudWatch alarms keyed on `CPUUtilization`. Combine the ASG with an Application Load Balancer (ALB) that health-checks every instance before routing traffic to it.

```hcl
# terraform/asg.tf
resource "aws_autoscaling_group" "taskflow" {
  name                = "taskflow-swarm-workers"
  min_size            = 2
  max_size            = 10
  desired_capacity    = 2
  health_check_type   = "ELB"                    # defer to ALB, not EC2 ping
  health_check_grace_period = 120                # give Docker Swarm time to join
  vpc_zone_identifier = var.private_subnet_ids

  launch_template {
    id      = aws_launch_template.taskflow_worker.id
    version = "$Latest"
  }

  tag {
    key                 = "swarm-role"
    value               = "worker"
    propagate_at_launch = true
  }
}

resource "aws_cloudwatch_metric_alarm" "scale_out" {
  alarm_name          = "taskflow-high-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "CPUUtilization"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Average"
  threshold           = 70
  alarm_actions       = [aws_autoscaling_policy.scale_out.arn]

  dimensions = {
    AutoScalingGroupName = aws_autoscaling_group.taskflow.name
  }
}

resource "aws_autoscaling_policy" "scale_out" {
  name                   = "taskflow-scale-out"
  autoscaling_group_name = aws_autoscaling_group.taskflow.name
  adjustment_type        = "ChangeInCapacity"
  scaling_adjustment     = 2           # add two nodes at a time
  cooldown               = 300         # wait 5 min before scaling again
}
```

The ALB sits in front of the cluster. Its target group uses a `/actuator/health` health check to determine whether a node is ready to receive traffic.

```hcl
# terraform/alb.tf
resource "aws_lb_target_group" "taskflow" {
  name        = "taskflow-api"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "instance"

  health_check {
    path                = "/actuator/health"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  stickiness {
    type            = "lb_cookie"
    cookie_duration = 86400     # 24 hours — keeps WebSocket clients on one node
    enabled         = true
  }
}
```

Sticky sessions are not optional for TaskFlow. A WebSocket connection carries session state: which board the user is watching, which `ProcRef` handles the server-side actor. Without stickiness the ALB routes requests round-robin and a reconnect lands on a different node that knows nothing about that session. ALB cookie stickiness pins a client to its original node for the duration of the cookie, which is long enough to survive normal usage while still allowing the cluster to absorb new connections on any node.

**Consequences**

You get elastic capacity without manual intervention. The two-node minimum guarantees you survive a single Availability Zone failure. The ten-node maximum caps your bill. The CloudWatch alarm fires within two minutes of CPU breaching the threshold — two evaluation periods of sixty seconds each — and the cooldown prevents thrashing when a sudden spike arrives and then recedes.

The tradeoff is complexity in the Terraform graph and a 120-second grace period during scale-out where new nodes absorb traffic before their containers are warmed up. The grace period is not optional: Docker needs time to pull images, Swarm needs time to schedule the tasks, and the JVM needs time to warm up its JIT caches.

---

## Pattern: THE BACKPRESSURE VALVE

**Problem**

Your board-state service is slow. Its mailbox is filling up. The HTTP thread handling the incoming request keeps waiting for an answer, holding onto a virtual thread, an open HTTP connection, and a slot in the ALB's connection table. If enough of these pile up, you run out of resources on the caller's side long before the board-state service recovers. The slow service drags down the fast one.

**Context**

In TaskFlow, `BoardService` is the stateful actor responsible for aggregating card positions, list order, and user presence into a consistent board snapshot. Every HTTP request to `GET /api/boards/{id}` asks this actor for the current state. Under heavy load — thirty concurrent users moving cards simultaneously — the actor's mailbox backs up because state transitions are serialized.

**Solution**

Use `ask()` with a hard deadline. If the actor does not respond within the deadline, fail fast with a `TimeoutException`. Surface that exception as an HTTP 503 to the client so the ALB health check remains green and the client can retry on a different node.

```java
// BoardController.java
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final ProcRef<BoardState, BoardMsg> boardRef;

    public BoardController(ProcRef<BoardState, BoardMsg> boardRef) {
        this.boardRef = boardRef;
    }

    @GetMapping("/{boardId}")
    public ResponseEntity<BoardSnapshot> getBoard(@PathVariable String boardId) {
        try {
            BoardSnapshot snapshot = boardRef
                .ask(new GetBoardMsg(boardId), Duration.ofMillis(500))
                .get();                          // blocks the virtual thread, not a platform thread
            return ResponseEntity.ok(snapshot);
        } catch (TimeoutException e) {
            // The actor is overloaded. Tell the client to try again.
            return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .build();
        } catch (ExecutionException e) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Board service error", e.getCause());
        }
    }
}
```

The 500-millisecond deadline is not arbitrary. Your SLO for `GET /api/boards/{id}` is p99 under one second. You spend 200 milliseconds on network, TLS, and ALB overhead. That leaves 800 milliseconds for application processing. A 500-millisecond budget for the actor gives you 300 milliseconds of slack for JSON serialization and response writing. If the actor cannot respond in 500 milliseconds, it is not going to respond in 800 milliseconds either, and you are better off failing fast than queuing the request where it will expire anyway.

The `ask()` call blocks a virtual thread, not a platform thread, so blocking is cheap. Java 26 virtual threads can park and resume in microseconds. The cost of parking ten thousand virtual threads waiting for actor responses is measured in kilobytes of stack memory, not in context-switch overhead.

**Consequences**

Flow control emerges from the timeout rather than from explicit queue management. When the actor is slow, callers fail fast and the load on the actor decreases naturally — clients get 503s, they back off (assuming exponential retry with jitter on the client), and the actor drains its mailbox. This is the same mechanism Erlang/OTP uses when a `gen_server:call` times out: the caller gives up and the server continues processing at whatever rate it can sustain.

The downside is that 503 responses are visible to users. You need a client-side retry strategy and you need your ALB health check to distinguish between "actor overloaded" (503, keep the instance in rotation) and "application crashed" (500, pull the instance). The two HTTP status codes do exactly that.

---

## Pattern: THE CIRCUIT BREAKER

**Problem**

The external webhook service that TaskFlow calls when a card moves to "Done" starts timing out. Every call takes the full five-second timeout before failing. With thirty webhook calls per minute you are burning 150 virtual thread-seconds per minute waiting for a service that will not respond. The webhook actor's mailbox fills up with unsent notifications. The supervisor restarts the actor. The restarted actor immediately picks up the backlog and starts timing out again. You are in a crash loop.

**Context**

JOTP's `Supervisor` has a sliding-window restart limit. After `maxRestarts` crashes within `withinWindow`, the supervisor itself crashes rather than continuing to restart a child that clearly has a permanent failure. This supervisor crash propagates upward and is visible to callers through `ProcessMonitor`. It is a circuit breaker implemented entirely through the supervision tree — no additional library required.

**Solution**

Configure the webhook actor's supervisor with a tight restart window. Three crashes in sixty seconds means the external service is not recovering on its own.

```java
// WebhookSupervisor.java
public class WebhookSupervisor {

    public static ProcRef<WebhookState, WebhookMsg> start(WebhookConfig config) {
        return Supervisor.builder()
            .strategy(RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(3)
            .withinWindow(Duration.ofMinutes(1))    // 3 crashes/min → supervisor crashes
            .supervise(
                "webhook-sender",
                (state) -> new WebhookSender(state, config),
                WebhookState.initial()
            )
            .build();
    }
}
```

Now wire the `ProcessMonitor` to detect when the circuit opens:

```java
// WebhookCircuitMonitor.java
public class WebhookCircuitMonitor {

    private static final Logger log = LoggerFactory.getLogger(WebhookCircuitMonitor.class);
    private final MeterRegistry registry;
    private volatile boolean circuitOpen = false;

    public WebhookCircuitMonitor(
            ProcRef<?, ?> webhookSupervisorRef,
            MeterRegistry registry) {

        this.registry = registry;

        ProcessMonitor.monitor(webhookSupervisorRef, reason -> {
            switch (reason) {
                case ExitSignal e -> {
                    circuitOpen = true;
                    log.error("Webhook supervisor crashed: {}. Circuit is open.", e.reason());
                    registry.counter("webhook.circuit.open").increment();
                    scheduleReset();
                }
                case Shutdown s ->
                    log.info("Webhook supervisor shut down gracefully.");
                case Timeout t -> {
                    log.warn("Webhook supervisor health check timed out.");
                    registry.counter("webhook.circuit.timeout").increment();
                }
            }
        });
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    private void scheduleReset() {
        // After 60 seconds, allow the supervisor to be restarted by the root supervisor
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(60));
                circuitOpen = false;
                log.info("Webhook circuit reset. Attempting recovery.");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
```

The `ProcessMonitor` is unilateral: it receives a DOWN notification when the webhook supervisor crashes, but the monitor itself does not crash when the supervisor crashes. This is the key difference from `Proc.link()`. A linked pair dies together. A monitored pair is asymmetric — the monitor outlives the monitored.

In the calling code, check the circuit before firing webhooks:

```java
// CardService.java  (excerpt)
public Result<Void, WebhookError> notifyCardMoved(CardMovedEvent event) {
    if (circuitMonitor.isCircuitOpen()) {
        log.warn("Webhook circuit open. Dropping notification for card {}.", event.cardId());
        registry.counter("webhook.dropped").increment();
        return Result.failure(new WebhookError.CircuitOpen());
    }

    try {
        webhookRef.ask(new SendWebhookMsg(event), Duration.ofMillis(500)).get();
        return Result.success(null);
    } catch (TimeoutException e) {
        return Result.failure(new WebhookError.Timeout());
    } catch (ExecutionException e) {
        return Result.failure(new WebhookError.SendFailed(e.getCause()));
    }
}
```

**Consequences**

The circuit breaker is implicit in the supervision topology rather than explicit in code. You do not maintain a counter, a half-open state, or a timer in application logic. The supervisor's sliding window does all of that. The `ProcessMonitor` callback is your hook to observe the transition from closed to open.

The limitation is granularity. The supervisor tracks crash rate across all causes — a bug introduced by a bad deploy looks the same as an external service failure. You may want to log the crash reason (available in `ExitSignal.reason()`) to distinguish them in your runbook.

---

## Pattern: THE STABLE HANDLE

**Problem**

You are rolling out a new version of `BoardService`. Docker Swarm stops the old container and starts the new one. Every `ProcRef<BoardState, BoardMsg>` in memory on that node now points at a dead actor. Any in-flight `ask()` call returns a `DeadLetterException`. Clients see errors during the rolling deploy window.

**Context**

`ProcRef` is not a direct reference to a `Proc` instance. It is an indirection layer managed by the supervisor. When a supervisor restarts a child — whether due to a crash or an intentional rolling update — it creates a new `Proc` instance and updates the internal pointer behind the `ProcRef`. Callers holding the `ProcRef` automatically route to the new instance on the next message send.

**Solution**

Use `ProcRef` everywhere a long-lived handle to an actor is needed. Never hold a raw `Proc` reference. Configure Docker Swarm's rolling update to introduce a delay between stopping the old container and starting the new one, giving the supervisor time to complete the handoff.

```bash
# docker-stack.yml (services section, abbreviated)
services:
  taskflow-api:
    image: taskflow/api:${VERSION}
    deploy:
      replicas: 4
      update_config:
        parallelism: 1             # one container at a time
        delay: 30s                 # wait 30s after starting new before stopping old
        failure_action: rollback
        monitor: 60s               # monitor new containers for 60s before proceeding
        order: start-first         # start the new container before stopping the old
      restart_policy:
        condition: on-failure
        delay: 5s
        max_attempts: 3
        window: 120s
```

The `order: start-first` directive is critical. Swarm starts the new container, waits for it to pass the health check, and only then stops the old one. During the overlap window both containers are running and accepting new connections. The ALB routes new requests to the healthy new container while the old one drains.

On the Java side, the supervisor that owns `BoardService` actors registers their `ProcRef` instances in a `ProcRegistry`:

```java
// TaskFlowApplication.java
@SpringBootApplication
public class TaskFlowApplication {

    @Bean
    public ProcRef<BoardState, BoardMsg> boardServiceRef(
            ProcRegistry registry,
            BoardServiceConfig config) {

        ProcRef<BoardState, BoardMsg> ref = Supervisor.builder()
            .strategy(RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(5)
            .withinWindow(Duration.ofMinutes(1))
            .supervise("board-service", s -> new BoardService(s, config), BoardState.empty())
            .buildRef("board-service");     // returns the ProcRef, not the raw Proc

        registry.register("board-service", ref);
        return ref;
    }
}
```

When Docker Swarm rolling-updates the container, a new JVM starts, the Spring context initializes, the supervisor starts fresh, and a new `ProcRef` is registered. Clients on other nodes that were mid-request get a `DeadLetterException` on the node being restarted, see a 503 from the ALB (because the health check briefly fails during shutdown), and retry on the healthy new node. Clients that have already been migrated to the new node via `start-first` routing see no interruption at all.

For transient errors during the deploy window, use `CrashRecovery.retry()`:

```java
// BoardGateway.java
public BoardSnapshot fetchBoard(String boardId) {
    Result<BoardSnapshot, Exception> result = CrashRecovery.retry(3,
        () -> boardRef
            .ask(new GetBoardMsg(boardId), Duration.ofMillis(500))
            .get()
    );

    return result.fold(
        snapshot -> snapshot,
        error -> { throw new BoardUnavailableException(boardId, error); }
    );
}
```

`CrashRecovery.retry()` wraps each attempt in a fresh virtual thread. If the first attempt hits a `DeadLetterException` because the actor is being restarted, the second attempt — in a fresh virtual thread with no lingering state from the first — succeeds because the supervisor has by then created the new `Proc`.

**Consequences**

`ProcRef` stability transforms rolling deploys from a correctness problem into a latency blip. The p99 latency for `GET /api/boards/{id}` spikes for approximately the duration of one rolling-update window (30–60 seconds per container) and then returns to baseline. No requests are lost as long as the client or the gateway layer retries on 503.

The `start-first` policy doubles the resource consumption briefly — two containers per service during the overlap. This is acceptable on an ASG with capacity headroom but requires that your `max_size` includes enough overhead to run two copies of each service simultaneously.

To clean up dangling resources after a series of rolling updates, schedule a periodic prune on each worker node via a cron job in the launch template user-data script:

```bash
# /etc/cron.daily/docker-prune
#!/bin/bash
docker system prune --filter "until=24h" --force >> /var/log/docker-prune.log 2>&1
```

This removes stopped containers, unused images, and orphaned volumes that are more than 24 hours old. On a busy cluster this reclaims gigabytes of disk space per week.

---

## What Have You Learned?

- An AWS Auto Scaling Group with a CloudWatch CPU alarm gives you elastic capacity without manual intervention. Set `min_size=2` for HA and `health_check_type=ELB` so the ASG defers readiness decisions to the ALB.
- ALB sticky sessions are not optional for WebSocket workloads. Cookie-based stickiness pins a client to its node for the life of the cookie.
- `ask(msg, Duration.ofMillis(500))` is a backpressure valve: when the actor is slow, callers fail fast with a `TimeoutException`, reducing load on the overloaded actor rather than amplifying it.
- JOTP's supervisor restart limit (`maxRestarts(3).withinWindow(Duration.ofMinutes(1))`) is a circuit breaker implemented in the supervision topology. After the limit is exceeded, the supervisor crashes and `ProcessMonitor` delivers the DOWN signal.
- `ProcRef` is an indirection layer. It survives supervisor restarts because the supervisor updates the pointer behind the ref. Never hold raw `Proc` references in long-lived objects.
- `CrashRecovery.retry()` handles transient errors by running each retry in a fresh virtual thread, eliminating corrupted state from previous attempts.
- `docker service update --update-parallelism 1 --update-delay 30s --update-order start-first` implements a safe rolling deploy. The new container is healthy before the old one stops.

---

# Chapter 12: Instrument Your Application with Logs and Metrics

You cannot operate what you cannot observe. Chapter 11 gave you a cluster that scales and restarts itself. This chapter gives you the eyes to see what it is doing. Observability in TaskFlow has two complementary streams: logs answer "what happened and when," metrics answer "how often and how fast." Both streams are structured, machine-readable, and shipped to durable storage. By the end of this chapter you will have Prometheus scraping TaskFlow's JVM internals and JOTP actor statistics, Grafana displaying a dashboard with restart rate, HTTP p99 latency, and virtual thread pool depth, and `ProcessMonitor` publishing a counter every time a supervisor goes down.

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

Register a custom gauge for supervisor restart counts:

```java
// SupervisorMetrics.java
@Component
public class SupervisorMetrics {

    private static final Logger log = LoggerFactory.getLogger(SupervisorMetrics.class);

    public SupervisorMetrics(
            MeterRegistry registry,
            Supervisor boardSupervisor,
            Supervisor webhookSupervisor) {

        Metrics.gauge(
            "supervisor.restart.count",
            Tags.of("supervisor", "board-service"),
            boardSupervisor,
            Supervisor::restartCount
        );

        Metrics.gauge(
            "supervisor.restart.count",
            Tags.of("supervisor", "webhook-sender"),
            webhookSupervisor,
            Supervisor::restartCount
        );

        log.info("Supervisor metrics registered.");
    }
}
```

`Supervisor::restartCount` returns the number of restarts within the current sliding window. Prometheus polls this gauge at its scrape interval (typically fifteen seconds). If you graph it in Grafana as a rate (`rate(supervisor_restart_count[5m])`), you see restarts per second averaged over five minutes — a value that should stay near zero in normal operation.

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

`ProcessMonitor.monitor()` delivers a DOWN notification synchronously when the monitored process terminates for any reason: a graceful shutdown, an unhandled exception, or a health check timeout. The notification is delivered on a fresh virtual thread, so you can log, increment a counter, or send a page without blocking the supervisor's own shutdown path.

**Solution**

Attach a `ProcessMonitor` to each critical supervisor at application startup:

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

    private void attachMonitor(String name, ProcRef<?, ?> ref) {
        ProcessMonitor.monitor(ref, reason -> {
            switch (reason) {
                case ExitSignal e -> {
                    log.error("Supervisor '{}' crashed. Reason: {}", name, e.reason());
                    registry
                        .counter("supervisor.down", "supervisor", name, "cause", "crash")
                        .increment();
                }
                case Shutdown s -> {
                    log.info("Supervisor '{}' shut down gracefully.", name);
                    registry
                        .counter("supervisor.down", "supervisor", name, "cause", "shutdown")
                        .increment();
                }
                case Timeout t -> {
                    log.warn("Supervisor '{}' health check timed out.", name);
                    registry
                        .counter("supervisor.down", "supervisor", name, "cause", "timeout")
                        .increment();
                }
            }
        });

        log.info("Liveness sentinel attached to '{}'.", name);
    }
}
```

The `supervisor.down` counter is a Prometheus counter — it only goes up. In Grafana, graphing `increase(supervisor_down_total[10m])` gives you supervisor failures in the last ten minutes. A non-zero value at 3 a.m. is worth a PagerDuty alert.

The unilateral nature of `ProcessMonitor` is worth emphasizing. When `boardSupervisorRef` goes down, the `SupervisorHealthMonitor` is unaffected. It does not crash, it does not stop monitoring `webhookSupervisorRef`, and it does not propagate the failure to the Spring application context. You can use this guarantee to write monitoring logic that runs for the lifetime of the JVM regardless of what the supervised actors are doing.

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

When `boardSupervisorDown` is `true`, `/actuator/health` returns HTTP 503. The ALB health check sees the 503, marks the instance unhealthy, and stops routing traffic to it. The Auto Scaling Group eventually replaces the instance. This is the self-healing loop: JOTP's supervision tree fails fast, `ProcessMonitor` surfaces the failure, and the health indicator triggers AWS's instance replacement.

**Consequences**

`ProcessMonitor` is the right tool for observing failures you cannot prevent. You cannot prevent the board supervisor from crashing if a bug is introduced in the board service. What you can do is detect the crash within milliseconds, record it in Prometheus, and surface it in Grafana before a human notices the elevated error rate.

The one discipline required is that your `ProcessMonitor` callbacks must never throw unchecked exceptions. If the callback itself crashes, the DOWN notification is swallowed silently. Wrap callback logic in a `try-catch(Exception)` and log at error level if anything goes wrong.

---

## What Have You Learned?

- Structured JSON logs (ECS format via Logback's ECS encoder) make CloudWatch Logs Insights queries fast and precise. Free-text logs are for humans; structured logs are for machines.
- MDC propagates context (request IDs, user IDs, board IDs) automatically to every log line within the same thread. For child virtual threads, copy the MDC map explicitly.
- `micrometer-registry-prometheus` + Spring Boot Actuator exposes JVM heap, GC pauses, virtual thread count, and HTTP request latency with zero application code.
- `Metrics.gauge("supervisor.restart.count", ...)` exposes JOTP supervisor restart counts to Prometheus. Graph as a rate to distinguish occasional restarts from restart storms.
- `ProcessMonitor.monitor(ref, reason -> {...})` delivers a DOWN notification when a supervisor crashes. It is unilateral — the monitor does not crash when the supervised process crashes.
- Integrating `ProcessMonitor` with a Spring Boot `HealthIndicator` closes the self-healing loop: crash → monitor → health endpoint returns 503 → ALB removes instance → ASG replaces it.
- The async Logback appender (`AsyncAppender`) absorbs log I/O off the request path. Without it, every log statement adds filesystem I/O latency to your request handling.

---

# Chapter 13: Create Custom Metrics and Grafana Alerts

Chapter 12 got Prometheus scraping TaskFlow and Grafana displaying the results. This chapter pushes further. You will instrument the interior of your JOTP actors — mailbox depth, state machine transition rate, per-state latency — and you will build Grafana alert rules that page you before users notice something is wrong. The chapter ends with a production deployment of the full TaskFlow stack and an end-to-end smoke test.

---

## Pattern: THE MAILBOX DEPTH GAUGE

**Problem**

A `Proc` actor's mailbox fills up before the actor processes messages fast enough. From the outside, you see elevated HTTP latency and increased `TimeoutException` counts. From the inside, you cannot see anything because you have no metric for mailbox depth. You are flying blind.

**Context**

`ProcSys.queueSize(ref)` returns the current number of unprocessed messages in a `ProcRef`'s mailbox. This is a point-in-time snapshot, not an average, which makes it appropriate for a Micrometer `Gauge` — the gauge reflects the current state at scrape time.

**Solution**

Register a gauge for each critical actor's mailbox depth at application startup:

```java
// ActorMailboxMetrics.java
@Component
public class ActorMailboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(ActorMailboxMetrics.class);

    public ActorMailboxMetrics(
            MeterRegistry registry,
            ProcRef<BoardState, BoardMsg> boardRef,
            ProcRef<WebhookState, WebhookMsg> webhookRef,
            ProcRef<PresenceState, PresenceMsg> presenceRef) {

        registerMailboxGauge(registry, "board-service", boardRef);
        registerMailboxGauge(registry, "webhook-sender", webhookRef);
        registerMailboxGauge(registry, "presence-tracker", presenceRef);

        log.info("Mailbox depth gauges registered for {} actors.", 3);
    }

    private <S, M> void registerMailboxGauge(
            MeterRegistry registry,
            String actorName,
            ProcRef<S, M> ref) {

        Metrics.gauge(
            "proc.mailbox.depth",
            Tags.of("actor", actorName),
            ref,
            r -> (double) ProcSys.queueSize(r)
        );
    }
}
```

The Prometheus output looks like:

```
# HELP proc_mailbox_depth Number of messages waiting in actor mailbox
# TYPE proc_mailbox_depth gauge
proc_mailbox_depth{actor="board-service",application="taskflow-api",...}    3.0
proc_mailbox_depth{actor="webhook-sender",application="taskflow-api",...}  47.0
proc_mailbox_depth{actor="presence-tracker",application="taskflow-api",...} 1.0
```

The `webhook-sender` mailbox depth of 47 is the early warning sign you wanted. It tells you the sender is falling behind before the mailbox fills to the point where `ask()` calls start timing out. Your on-call engineer can act on a mailbox depth of 47 before it becomes a mailbox depth of 5000.

Build a Grafana heatmap to visualize mailbox depth across actors over time:

```json
// Grafana panel definition (PromQL queries)
{
  "title": "Actor Mailbox Depth",
  "type": "heatmap",
  "targets": [
    {
      "expr": "proc_mailbox_depth{application='taskflow-api'}",
      "legendFormat": "{{actor}}"
    }
  ],
  "options": {
    "calculate": false,
    "color": {
      "scheme": "Oranges",
      "steps": 64
    }
  }
}
```

**Consequences**

Mailbox depth is the most direct indicator of actor health. A mailbox that grows monotonically without shrinking means the actor cannot keep up with its input rate. A mailbox that oscillates between zero and ten is healthy. A mailbox that is always zero might mean the actor is idle, which is also worth knowing.

`ProcSys.queueSize()` traverses the mailbox queue to count elements. On a `LinkedTransferQueue`, this is O(n) and involves a volatile read per element. Do not call it in a tight loop. Micrometer's scrape interval of fifteen seconds is safe. If you need sub-second resolution, instrument the actor's handler function to record the queue size after each `take()` instead.

---

## Pattern: THE STATE MACHINE THROUGHPUT METER

**Problem**

TaskFlow's `BoardStateMachine` transitions between states: `Idle`, `Loading`, `Active`, `Saving`, `Error`. You want to know how often each transition is taken and how long each state is occupied. Without this data, you cannot identify which state is the bottleneck or which transition is unexpectedly frequent.

**Context**

Micrometer's `Counter` is appropriate for transition counts — it increases monotonically and Prometheus can compute rates over it. `Timer` is appropriate for state residence time — it records durations and exposes them as histograms for percentile calculation.

**Solution**

Wrap the `StateMachine` transition function to record metrics:

```java
// InstrumentedBoardStateMachine.java
public class InstrumentedBoardStateMachine {

    private static final Logger log =
        LoggerFactory.getLogger(InstrumentedBoardStateMachine.class);

    private final MeterRegistry registry;
    private final Map<BoardState, Long> stateEntryTimes = new ConcurrentHashMap<>();

    public InstrumentedBoardStateMachine(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Wrap a transition function with metrics instrumentation.
     * Returns a new function that records transition counts and state residence times.
     */
    public BiFunction<BoardState, BoardEvent, Transition<BoardState, BoardData>>
            instrument(BiFunction<BoardState, BoardEvent, Transition<BoardState, BoardData>> fn) {

        return (state, event) -> {
            long startNanos = System.nanoTime();

            Transition<BoardState, BoardData> result = fn.apply(state, event);

            long durationNanos = System.nanoTime() - startNanos;

            // Count the transition
            registry.counter(
                "statemachine.transitions",
                "from", state.name(),
                "event", event.getClass().getSimpleName(),
                "outcome", transitionOutcome(result)
            ).increment();

            // Record transition latency
            registry.timer(
                "statemachine.transition.duration",
                "from", state.name(),
                "event", event.getClass().getSimpleName()
            ).record(durationNanos, TimeUnit.NANOSECONDS);

            // If transitioning to a new state, record time spent in previous state
            if (result instanceof Transition.Next<BoardState, BoardData> next) {
                Long entryTime = stateEntryTimes.put(next.state(), System.nanoTime());
                if (entryTime != null) {
                    registry.timer(
                        "statemachine.state.residence",
                        "state", state.name()
                    ).record(System.nanoTime() - entryTime, TimeUnit.NANOSECONDS);
                }
            }

            return result;
        };
    }

    private String transitionOutcome(Transition<?, ?> t) {
        return switch (t) {
            case Transition.Next<?> n -> "next";
            case Transition.Keep<?> k -> "keep";
            case Transition.Stop<?> s -> "stop";
        };
    }
}
```

Use the instrumented transition function when building the `StateMachine`:

```java
// BoardServiceConfig.java
@Configuration
public class BoardServiceConfig {

    @Bean
    public StateMachine<BoardState, BoardEvent, BoardData> boardStateMachine(
            MeterRegistry registry) {

        InstrumentedBoardStateMachine instrumentation =
            new InstrumentedBoardStateMachine(registry);

        BiFunction<BoardState, BoardEvent, Transition<BoardState, BoardData>> raw =
            BoardStateMachine::transition;

        return StateMachine.builder(BoardState.IDLE, BoardData.empty())
            .transition(instrumentation.instrument(raw))
            .build();
    }
}
```

The Prometheus output for transitions looks like:

```
# Transition counts (use rate() in Grafana)
statemachine_transitions_total{from="IDLE",event="LoadBoardEvent",outcome="next",...}   1482.0
statemachine_transitions_total{from="LOADING",event="BoardLoadedEvent",outcome="next",...} 1480.0
statemachine_transitions_total{from="ACTIVE",event="MoveCardEvent",outcome="keep",...}  23847.0
statemachine_transitions_total{from="ACTIVE",event="SaveEvent",outcome="next",...}       8234.0
statemachine_transitions_total{from="SAVING",event="SavedEvent",outcome="next",...}      8231.0
statemachine_transitions_total{from="SAVING",event="SaveFailedEvent",outcome="next",...}    3.0
```

Notice the asymmetry: 8234 saves were started, 8231 succeeded, and 3 failed. Without this counter you would never know about those three failures unless they caused a user-visible error.

Build a Grafana panel showing transition rate by source state:

```
# PromQL for "transitions per second by source state"
sum by (from) (
  rate(statemachine_transitions_total{application="taskflow-api"}[5m])
)
```

**Consequences**

Wrapping the transition function rather than modifying `StateMachine` itself keeps the instrumentation concern separate from the state machine logic. The `InstrumentedBoardStateMachine` class can be tested independently, and the `BoardStateMachine.transition` function remains a pure function with no side effects.

The `ConcurrentHashMap` for state entry times introduces shared mutable state. If two events are processed concurrently — which cannot happen in a properly serialized actor, but could happen in a test harness — the entry times can be corrupted. Treat the state residence timer as a best-effort metric, not a precise measurement.

---

## Pattern: THE ALERT THRESHOLD

**Problem**

Grafana shows you beautiful dashboards during business hours when someone is watching. At 2 a.m., nobody is watching. You need the dashboards to watch themselves and call someone when something goes wrong.

**Context**

Grafana alert rules evaluate PromQL expressions on a schedule. When an expression exceeds a threshold for a specified duration, Grafana fires an alert to a notification channel — PagerDuty, Slack, OpsGenie, or email. Alert rules live in the same Grafana instance as your dashboards and can be version-controlled as JSON.

**Solution**

Define three alert rules that cover the signals established in this chapter and the previous one:

```yaml
# grafana/alerts/taskflow-alerts.yaml
# Grafana provisioning format for alert rules

apiVersion: 1

groups:
  - name: taskflow-production
    folder: TaskFlow
    interval: 1m
    rules:

      # Alert 1: Supervisor restart storm
      - uid: taskflow-supervisor-restarts
        title: "Supervisor Restart Storm"
        condition: C
        data:
          - refId: A
            relativeTimeRange:
              from: 300      # 5 minutes
              to: 0
            datasourceUid: prometheus
            model:
              expr: >
                increase(supervisor_restart_count{application="taskflow-api"}[5m])
              intervalMs: 15000
              maxDataPoints: 43200
          - refId: C
            datasourceUid: __expr__
            model:
              type: threshold
              conditions:
                - evaluator:
                    params: [5]    # more than 5 restarts in 5 minutes
                    type: gt
                  query:
                    params: [A]
        noDataState: OK
        execErrState: Error
        for: 2m                    # must exceed threshold for 2 continuous minutes
        annotations:
          summary: "Supervisor {{ $labels.supervisor }} is restarting frequently"
          description: >
            {{ $labels.supervisor }} has restarted {{ $values.A.Value | printf "%.0f" }}
            times in the last 5 minutes on {{ $labels.instance }}.
            Check logs: kubectl logs -l app=taskflow --since=10m
        labels:
          severity: critical
          team: platform

      # Alert 2: Mailbox depth — actor falling behind
      - uid: taskflow-mailbox-depth
        title: "Actor Mailbox Depth Critical"
        condition: C
        data:
          - refId: A
            relativeTimeRange:
              from: 300
              to: 0
            datasourceUid: prometheus
            model:
              expr: >
                max by (actor) (
                  proc_mailbox_depth{application="taskflow-api"}
                )
          - refId: C
            datasourceUid: __expr__
            model:
              type: threshold
              conditions:
                - evaluator:
                    params: [100]  # mailbox deeper than 100 messages
                    type: gt
                  query:
                    params: [A]
        for: 2m
        annotations:
          summary: "Actor {{ $labels.actor }} mailbox is backing up"
          description: >
            Mailbox depth for {{ $labels.actor }} is {{ $values.A.Value | printf "%.0f" }}.
            This actor is processing messages slower than they are arriving.
        labels:
          severity: warning
          team: platform

      # Alert 3: HTTP p99 latency exceeds SLO
      - uid: taskflow-http-p99
        title: "HTTP p99 Latency Breaching SLO"
        condition: C
        data:
          - refId: A
            relativeTimeRange:
              from: 300
              to: 0
            datasourceUid: prometheus
            model:
              expr: >
                histogram_quantile(0.99,
                  sum by (le, uri) (
                    rate(
                      http_server_requests_seconds_bucket{
                        application="taskflow-api",
                        uri="/api/boards/{boardId}"
                      }[5m]
                    )
                  )
                )
          - refId: C
            datasourceUid: __expr__
            model:
              type: threshold
              conditions:
                - evaluator:
                    params: [1.0]  # p99 > 1 second
                    type: gt
                  query:
                    params: [A]
        for: 5m
        annotations:
          summary: "TaskFlow HTTP p99 latency exceeds 1s SLO"
          description: >
            p99 latency for GET /api/boards/{boardId} is
            {{ $values.A.Value | printf "%.2f" }}s, exceeding the 1s SLO.
            Check board-service mailbox depth and supervisor restart rate.
        labels:
          severity: critical
          team: platform
```

Configure Slack as the notification channel in Grafana's alerting configuration:

```yaml
# grafana/alerting/contact-points.yaml
apiVersion: 1

contactPoints:
  - name: taskflow-slack
    receivers:
      - uid: taskflow-slack-receiver
        type: slack
        settings:
          url: "${SLACK_WEBHOOK_URL}"
          channel: "#taskflow-alerts"
          title: |
            [{{ .Status | toUpper }}] {{ .CommonAnnotations.summary }}
          text: |
            *Environment:* production
            *Details:* {{ .CommonAnnotations.description }}
            *Runbook:* https://wiki.example.com/runbooks/taskflow
          iconEmoji: ":rotating_light:"

policies:
  - receiver: taskflow-slack
    group_by: [severity, team]
    group_wait: 30s
    group_interval: 5m
    repeat_interval: 4h
    matchers:
      - severity =~ "warning|critical"
```

**Consequences**

Alert rules encode your SLOs as code. The Grafana provisioning YAML lives in your repository, is reviewed in pull requests, and is deployed alongside your application. When you change the p99 SLO from one second to 500 milliseconds, the change is visible in git history.

The `for: 2m` and `for: 5m` pending durations prevent alert storms from momentary spikes. A single 503 response does not fire the HTTP latency alert. A sustained degradation for two or five minutes does. Tune the pending duration against your tolerance for false positives versus false negatives.

---

## Final Production Deployment

You have all the pieces. Deploy the complete TaskFlow stack to AWS:

```bash
# Build and push Docker images
./dx.sh build
docker push "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/taskflow/api:${VERSION}"

# Deploy to Swarm via SSH to the manager node
./dx.sh deploy

# Inside dx.sh deploy:
# docker stack deploy \
#   --with-registry-auth \
#   --compose-file docker-stack.yml \
#   taskflow
```

Verify the stack is running:

```bash
docker stack services taskflow
# ID            NAME                   MODE        REPLICAS   IMAGE
# x7k9m2p3q4r5  taskflow_taskflow-api  replicated  4/4        taskflow/api:1.2.0
# y8l0n3q4r5s6  taskflow_prometheus    replicated  1/1        prom/prometheus:latest
# z9m1o4r5s6t7  taskflow_grafana       replicated  1/1        grafana/grafana:latest
```

Run the smoke test suite against the live cluster:

```bash
./dx.sh smoke-test

# smoke-test script:
# 1. Create a board  →  expect HTTP 201
# 2. Add three cards →  expect HTTP 201 x3
# 3. Move a card     →  expect HTTP 200 + WebSocket event
# 4. Check metrics   →  expect proc_mailbox_depth gauge present in Prometheus
# 5. Check health    →  expect /actuator/health to return {"status":"UP"}
# 6. Check alerts    →  expect zero firing alerts in Grafana
```

The smoke test exercises the full path: ALB → Spring Boot → `BoardController` → `boardRef.ask()` → `BoardStateMachine` → Micrometer → Prometheus scrape → Grafana dashboard. If all six checks pass, you have a production deployment.

---

## What Have You Learned?

- `ProcSys.queueSize(ref)` gives you the mailbox depth of any `ProcRef`. Register it as a Micrometer gauge with `Metrics.gauge("proc.mailbox.depth", ...)` so Prometheus can track it.
- Mailbox depth is the earliest warning sign of a slow actor. It rises before `TimeoutException` rates rise and long before users notice degraded performance.
- Wrap the `StateMachine` transition function — do not modify it — to inject metrics. The instrumented wrapper records transition counts and state residence times without contaminating the pure transition logic.
- `statemachine_transitions_total` is a Prometheus counter. Use `rate()` in Grafana to see transitions per second and `increase()` to see total transitions in a time window.
- Grafana alert rules with PromQL expressions encode your SLOs as version-controlled code. The `for: 2m` pending duration prevents false positives from momentary spikes.
- Three alerts cover the critical signals for a JOTP-based application: supervisor restart rate, mailbox depth, and HTTP p99 latency. Together they tell you whether the supervision tree is stable, whether actors are keeping up with load, and whether users are experiencing acceptable response times.
- `./dx.sh deploy` followed by `./dx.sh smoke-test` is the final integration check. A passing smoke test means the ALB, Spring Boot, JOTP actors, Micrometer, and Prometheus are all wired together correctly.

---

# Wrapping Up the Journey

You started with a TaskFlow application running on a single node. It was correct — the Kanban board worked, cards moved, WebSocket events fired — but it was not yet a production system. Over the course of this book you added a Docker Swarm cluster, a supervisor tree that restarts failing services, an Auto Scaling Group that adjusts capacity to load, rolling deploys that preserve actor handles across version updates, structured logs that CloudWatch can query, Prometheus metrics that Grafana can visualize, and alert rules that page you before users notice something is wrong. TaskFlow is now observable, self-healing, and elastic.

## What JOTPOps Gave You

The journey from "one node, one JVM" to "distributed, observable, self-healing" required exactly five JOTP primitives: `Proc`, `Supervisor`, `ProcRef`, `ProcessMonitor`, and `CrashRecovery`. You did not introduce a new framework, a new language, a new messaging system, or a new deployment model. You added fault tolerance to an existing Spring Boot application by wrapping stateful logic in actors and wiring those actors into supervision trees.

This is the promise of the synthesis strategy that motivated JOTP's design. The Spring Boot ecosystem handles HTTP routing, dependency injection, configuration, and persistence. JOTP handles state, fault tolerance, backpressure, and crash recovery. The two layers compose without friction because JOTP is ordinary Java — no bytecode manipulation, no code generation, no aspect weaving.

## The Migration Path

If you have an existing Spring Boot application and you want to identify which parts are good candidates for JOTP adoption, run `/simplify` on your codebase. The skill reviews your Java code for stateful services with complex error handling and suggests where supervision trees and state machines would reduce complexity.

The ranking is based on three signals: statefulness (does the class maintain mutable fields?), error handling complexity (does it have deeply nested try-catch blocks?), and concurrency footprint (does it use `synchronized`, `ReentrantLock`, or `AtomicReference` heavily?). A service that scores high on all three is a textbook actor candidate. A stateless REST controller that scores near zero on all three should stay exactly as it is.

A typical migration for a high-scoring service takes one sprint:

1. Extract the state into a typed record (`BoardState`, `WebhookState`).
2. Extract the message protocol into a sealed interface with record variants.
3. Write the transition function — pure, no side effects, takes state and message, returns new state.
4. Wrap the transition function in `StateMachine.builder()`.
5. Wrap the `StateMachine` in a `Supervisor` with restart limits.
6. Replace direct method calls to the old service with `ProcRef.ask()` and `ProcRef.tell()` calls to the new actor.
7. Attach a `ProcessMonitor` and register mailbox depth gauges.

The migration is incremental by design. The rest of the application does not change. Spring Boot still owns the HTTP layer. Hibernate still owns persistence. JOTP owns the stateful coordination in between.

## Gaps and the Roadmap

JOTP's current limitations are worth naming directly. Distributed actors — `ProcRef` handles that are location-transparent across JVM boundaries — are on the roadmap for Q2 2026. Until then, cross-JVM communication requires Kafka, gRPC, or a message broker. Within a single JVM, JOTP's `LinkedTransferQueue`-backed mailboxes deliver lower latency than any of those options, but you pay a coordination penalty at JVM boundaries.

GraalVM native image support is targeted for Q1 2026. The current workaround is traditional JVM deployment with JIT warmup, which has a ninety-second startup cost on a cold container. For latency-sensitive use cases, keep at least two warm instances running at all times — which the Auto Scaling Group minimum of two already enforces.

Hot code reloading is not on the near-term roadmap. Blue-green deploys and Docker Swarm rolling updates with `start-first` ordering cover the operational need. Hot reload matters most for stateful processes where restart cost is high; JOTP's 200-microsecond restart time makes the distinction largely academic.

## The 12 Million Developer Advantage

Every fault-tolerance decision in TaskFlow is expressible in standard Java. A new hire who has never heard of JOTP can read `Supervisor.builder().maxRestarts(3).withinWindow(Duration.ofMinutes(1))` and understand exactly what it does. They can write a `ProcessMonitor` callback with the help of the sealed interface's exhaustiveness checking. They can test a `StateMachine` transition function with plain JUnit because it is a pure function that takes values and returns values.

Compare this to onboarding someone onto an Erlang/OTP system. The language is different, the runtime is different, the deployment toolchain is different, the debugging tools are different, and the community is a fraction the size. The knowledge transfer cost for a ten-engineer team moving from Java to Erlang is six to twelve months of reduced velocity. The knowledge transfer cost for a ten-engineer team adopting JOTP within an existing Java shop is one sprint.

This is not a soft advantage. It is a compounding one. Every Java developer who joins your team already understands virtual threads, Spring Boot, and Micrometer. They need to learn five JOTP primitives and one mental model — actors supervised in trees — not a new language and runtime.

## Apply One Pattern This Week

The patterns in this book are not an all-or-nothing proposition. Each one solves a specific problem independently of the others. You do not need to adopt all of them to benefit from any of them.

Pick one pattern from this book that matches a problem you have in production right now. If you have a service that crashes and corrupts its state, introduce a `Supervisor` with `ONE_FOR_ONE` restart strategy. If you have a slow downstream dependency that is causing timeouts to cascade upstream, add `ask(msg, Duration.ofMillis(500))` at the call site and return 503 on `TimeoutException`. If you have no visibility into your actor internals, register a mailbox depth gauge this afternoon — it takes fifteen lines of code.

The goal is not a complete architecture overhaul. The goal is one production system that is more observable, more resilient, and more explainable than it was before. Start there. The rest follows.

---

*Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP* closes here. TaskFlow is running in production on AWS, scaling automatically, deploying safely, monitoring itself, and alerting when things go wrong. The supervision tree handles faults without human intervention. The Grafana dashboards give you the information you need to intervene when the supervision tree reaches its limits. The 12 million Java developers in the world are available to maintain and extend what you have built.

Build something reliable.
