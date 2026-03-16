# Chapter 13: Create Custom Metrics and Grafana Alerts

Chapter 12 got Prometheus scraping TaskFlow and Grafana displaying the results. This chapter pushes further. You will instrument the interior of your JOTP actors — mailbox depth, state machine transition rate, per-state latency — and you will build Grafana alert rules that page you before users notice something is wrong. The chapter ends with a production deployment of the full TaskFlow stack and an end-to-end smoke test.

---

## Pattern: THE MAILBOX DEPTH GAUGE

**Problem**

A `Proc` actor's mailbox fills up before the actor processes messages fast enough. From the outside, you see elevated HTTP latency and increased `TimeoutException` counts. From the inside, you cannot see anything because you have no metric for mailbox depth. You are flying blind.

**Context**

`ProcSys.statistics(proc).queueDepth()` returns the current number of unprocessed messages in a `Proc`'s mailbox. `ProcSys.statistics()` takes a `Proc` directly; use `ref.proc()` to unwrap a `ProcRef`. The `Stats` record also carries `messagesIn` and `messagesOut` totals. The `queueDepth` field is a point-in-time snapshot, not an average, which makes it appropriate for a Micrometer `Gauge` — the gauge reflects the current state at scrape time.

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

        // ProcSys.statistics() takes a Proc, not a ProcRef.
        // ref.proc() unwraps the stable ProcRef to its current underlying Proc.
        // queueDepth() is a field on the Stats record returned by statistics().
        Metrics.gauge(
            "proc.mailbox.depth",
            Tags.of("actor", actorName),
            ref,
            r -> (double) ProcSys.statistics(r.proc()).queueDepth()
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

`ProcSys.statistics(proc).queueDepth()` calls `mailbox.size()` on the underlying `LinkedTransferQueue`, which traverses the queue and is O(n) with a volatile read per element. Do not call it in a tight loop. Micrometer's scrape interval of fifteen seconds is safe. If you need sub-second resolution, instrument the actor's handler function to record the queue depth directly instead.

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

- `ProcSys.statistics(ref.proc()).queueDepth()` gives you the mailbox depth of any actor. Register it as a Micrometer gauge with `Metrics.gauge("proc.mailbox.depth", ...)` so Prometheus can track it.
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

The journey from "one node, one JVM" to "distributed, observable, self-healing" required exactly five JOTP primitives: `Proc`, `Supervisor`, `ProcRef`, `ProcMonitor`, and `CrashRecovery`. You did not introduce a new framework, a new language, a new messaging system, or a new deployment model. You added fault tolerance to an existing Spring Boot application by wrapping stateful logic in actors and wiring those actors into supervision trees.

This is the promise of the synthesis strategy that motivated JOTP's design. The Spring Boot ecosystem handles HTTP routing, dependency injection, configuration, and persistence. JOTP handles state, fault tolerance, backpressure, and crash recovery. The two layers compose without friction because JOTP is ordinary Java — no bytecode manipulation, no code generation, no aspect weaving.

## The Migration Path

If you have an existing Spring Boot application and you want to identify which parts are good candidates for JOTP adoption, run `/simplify` on your codebase. The skill reviews your Java code for stateful services with complex error handling and suggests where supervision trees and state machines would reduce complexity.

The ranking is based on three signals: statefulness (does the class maintain mutable fields?), error handling complexity (does it have deeply nested try-catch blocks?), and concurrency footprint (does it use `synchronized`, `ReentrantLock`, or `AtomicReference` heavily?). A service that scores high on all three is a textbook actor candidate. A stateless REST controller that scores near zero on all three should stay exactly as it is.

A typical migration for a high-scoring service takes one sprint:

1. Extract the state into a typed record (`BoardState`, `WebhookState`).
2. Extract the message protocol into a sealed interface with record variants.
3. Write the transition function — pure, no side effects, takes state and message, returns new state.
4. Wrap the transition function in `StateMachine.builder()`.
5. Wrap the `StateMachine` in a `Supervisor` with restart limits (e.g. `Supervisor.create(Strategy.ONE_FOR_ONE, 5, Duration.ofMinutes(1))`).
6. Replace direct method calls to the old service with `ProcRef.ask()` and `ProcRef.tell()` calls to the new actor.
7. Attach a `ProcMonitor` and register mailbox depth gauges.

The migration is incremental by design. The rest of the application does not change. Spring Boot still owns the HTTP layer. Hibernate still owns persistence. JOTP owns the stateful coordination in between.

## Gaps and the Roadmap

JOTP's current limitations are worth naming directly. Distributed actors — `ProcRef` handles that are location-transparent across JVM boundaries — are on the roadmap for Q2 2026. Until then, cross-JVM communication requires Kafka, gRPC, or a message broker. Within a single JVM, JOTP's `LinkedTransferQueue`-backed mailboxes deliver lower latency than any of those options, but you pay a coordination penalty at JVM boundaries.

GraalVM native image support is targeted for Q1 2026. The current workaround is traditional JVM deployment with JIT warmup, which has a ninety-second startup cost on a cold container. For latency-sensitive use cases, keep at least two warm instances running at all times — which the Auto Scaling Group minimum of two already enforces.

Hot code reloading is not on the near-term roadmap. Blue-green deploys and Docker Swarm rolling updates with `start-first` ordering cover the operational need. Hot reload matters most for stateful processes where restart cost is high; JOTP's 200-microsecond restart time makes the distinction largely academic.

## The 12 Million Developer Advantage

Every fault-tolerance decision in TaskFlow is expressible in standard Java. A new hire who has never heard of JOTP can read `Supervisor.create(Strategy.ONE_FOR_ONE, 3, Duration.ofMinutes(1))` and understand exactly what it does. They can write a `ProcMonitor` callback that pattern-matches on whether the `Throwable` reason is null or non-null. They can test a `StateMachine` transition function with plain JUnit because it is a pure function that takes values and returns values.

Compare this to onboarding someone onto an Erlang/OTP system. The language is different, the runtime is different, the deployment toolchain is different, the debugging tools are different, and the community is a fraction the size. The knowledge transfer cost for a ten-engineer team moving from Java to Erlang is six to twelve months of reduced velocity. The knowledge transfer cost for a ten-engineer team adopting JOTP within an existing Java shop is one sprint.

This is not a soft advantage. It is a compounding one. Every Java developer who joins your team already understands virtual threads, Spring Boot, and Micrometer. They need to learn five JOTP primitives and one mental model — actors supervised in trees — not a new language and runtime.

## Apply One Pattern This Week

The patterns in this book are not an all-or-nothing proposition. Each one solves a specific problem independently of the others. You do not need to adopt all of them to benefit from any of them.

Pick one pattern from this book that matches a problem you have in production right now. If you have a service that crashes and corrupts its state, introduce a `Supervisor` with `Strategy.ONE_FOR_ONE` restart strategy. If you have a slow downstream dependency that is causing timeouts to cascade upstream, chain `.orTimeout(500, TimeUnit.MILLISECONDS)` onto your `ask(msg)` call and return 503 on `TimeoutException`. If you have no visibility into your actor internals, register a mailbox depth gauge this afternoon — it takes fifteen lines of code.

The goal is not a complete architecture overhaul. The goal is one production system that is more observable, more resilient, and more explainable than it was before. Start there. The rest follows.

---

*Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP* closes here. TaskFlow is running in production on AWS, scaling automatically, deploying safely, monitoring itself, and alerting when things go wrong. The supervision tree handles faults without human intervention. The Grafana dashboards give you the information you need to intervene when the supervision tree reaches its limits. The 12 million Java developers in the world are available to maintain and extend what you have built.

Build something reliable.
