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
            // ProcRef.ask(msg) returns a CompletableFuture; chain orTimeout for a deadline.
            BoardSnapshot snapshot = boardRef
                .ask(new GetBoardMsg(boardId))
                .orTimeout(500, TimeUnit.MILLISECONDS)
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

JOTP's `Supervisor` has a sliding-window restart limit. After `maxRestarts` crashes within the time `window`, the supervisor itself crashes rather than continuing to restart a child that clearly has a permanent failure. This supervisor crash propagates upward and is visible to callers through `ProcMonitor`. It is a circuit breaker implemented entirely through the supervision tree — no additional library required.

**Solution**

Configure the webhook actor's supervisor with a tight restart window. Three crashes in sixty seconds means the external service is not recovering on its own.

```java
// WebhookSupervisor.java
public class WebhookSupervisor {

    public static ProcRef<WebhookState, WebhookMsg> start(WebhookConfig config) {
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            3,
            Duration.ofMinutes(1)               // 3 crashes/min → supervisor crashes
        );
        return supervisor.supervise(
            "webhook-sender",
            WebhookState.initial(),
            (state, msg) -> new WebhookSender(config).handle(state, msg)
        );
    }
}
```

Now wire the `ProcMonitor` to detect when the circuit opens:

```java
// WebhookCircuitMonitor.java
public class WebhookCircuitMonitor {

    private static final Logger log = LoggerFactory.getLogger(WebhookCircuitMonitor.class);
    private final MeterRegistry registry;
    private volatile boolean circuitOpen = false;

    public WebhookCircuitMonitor(
            ProcRef<WebhookState, WebhookMsg> webhookRef,
            MeterRegistry registry) {

        this.registry = registry;

        // ProcMonitor.monitor takes a Proc directly; use ref.proc() to unwrap the ProcRef.
        // The handler receives null for a normal exit, or the Throwable that caused the crash.
        ProcMonitor.monitor(webhookRef.proc(), reason -> {
            if (reason != null) {
                // Abnormal termination — supervisor crashed
                circuitOpen = true;
                log.error("Webhook supervisor crashed: {}. Circuit is open.", reason.getMessage());
                registry.counter("webhook.circuit.open").increment();
                scheduleReset();
            } else {
                // reason == null means graceful shutdown
                log.info("Webhook supervisor shut down gracefully.");
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

The `ProcMonitor` is unilateral: it receives a DOWN notification when the webhook supervisor crashes, but the monitor itself does not crash when the supervisor crashes. This is the key difference from `ProcLink`. A linked pair dies together. A monitored pair is asymmetric — the monitor outlives the monitored.

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
        // ProcRef.ask(msg) has no built-in timeout; chain orTimeout on the returned future.
        webhookRef.ask(new SendWebhookMsg(event))
            .orTimeout(500, TimeUnit.MILLISECONDS)
            .get();
        return Result.success(null);
    } catch (TimeoutException e) {
        return Result.failure(new WebhookError.Timeout());
    } catch (ExecutionException e) {
        return Result.failure(new WebhookError.SendFailed(e.getCause()));
    }
}
```

**Consequences**

The circuit breaker is implicit in the supervision topology rather than explicit in code. You do not maintain a counter, a half-open state, or a timer in application logic. The supervisor's sliding window does all of that. The `ProcMonitor` callback is your hook to observe the transition from closed to open.

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

        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,
            Duration.ofMinutes(1)
        );
        ProcRef<BoardState, BoardMsg> ref = supervisor.supervise(
            "board-service",
            BoardState.empty(),
            (state, msg) -> new BoardService(config).handle(state, msg)
        );

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
            .ask(new GetBoardMsg(boardId))
            .orTimeout(500, TimeUnit.MILLISECONDS)
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
- `ask(msg).orTimeout(500, TimeUnit.MILLISECONDS)` is a backpressure valve: when the actor is slow, callers fail fast with a `TimeoutException`, reducing load on the overloaded actor rather than amplifying it.
- JOTP's supervisor restart limit (e.g. `Supervisor.create(Strategy.ONE_FOR_ONE, 3, Duration.ofMinutes(1))`) is a circuit breaker implemented in the supervision topology. After the limit is exceeded, the supervisor crashes and `ProcMonitor` delivers the DOWN signal.
- `ProcRef` is an indirection layer. It survives supervisor restarts because the supervisor updates the pointer behind the ref. Never hold raw `Proc` references in long-lived objects.
- `CrashRecovery.retry()` handles transient errors by running each retry in a fresh virtual thread, eliminating corrupted state from previous attempts.
- `docker service update --update-parallelism 1 --update-delay 30s --update-order start-first` implements a safe rolling deploy. The new container is healthy before the old one stops.

---
