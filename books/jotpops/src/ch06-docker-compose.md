# Chapter 6: The Dev Environment and Docker Compose

Local development has a dirty secret. Most teams optimize for the wrong thing. They optimize for how fast the application starts in isolation, and then wonder why bugs appear in staging that never appeared locally. The staging environment has a database. The staging environment has Redis. The staging environment has a message bus. The local environment has none of those — just a running Spring Boot application pointed at an in-memory H2 database that behaves nothing like PostgreSQL.

This chapter is about closing that gap at the local level. You will run TaskFlow with a real PostgreSQL database, a Redis cache, and a Prometheus metrics collector using Docker Compose. You will wire `EventManager<TaskEvent>` to Spring WebSocket for real-time Kanban board updates. You will use `ProcTimer` to schedule daily digest emails. By the time you finish this chapter, your local environment is a faithful mirror of production, and the surprises that wait in staging are no longer surprises.

---

## Pattern: THE LOCAL PRODUCTION MIRROR

**Problem**

Bugs found in staging are expensive. They block releases, require context switching from the work you started after the merge, and often require reproducing a complex state that only existed in staging. Most staging bugs are caused by environmental differences that could have been caught locally.

**Context**

TaskFlow persists board state to PostgreSQL and uses Redis for session caching. The application depends on specific PostgreSQL behavior — `JSONB` queries, row-level locking, `LISTEN/NOTIFY` for change detection — that H2 does not replicate. Your local environment must run the same database.

**Solution**

Model your dev environment as a `docker-compose.yml` that starts all dependencies. The rule is: if it runs in production, it runs locally. You run the application itself with `mvnd spring-boot:run` (with hot reload), but every dependency — database, cache, metrics — runs in Docker.

Create `docker-compose.yml` at the repository root:

```yaml
version: "3.9"

services:
  postgres:
    image: postgres:16-alpine
    container_name: taskflow-postgres
    environment:
      POSTGRES_DB: taskflow
      POSTGRES_USER: taskflow
      POSTGRES_PASSWORD: taskflow_dev_password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./db/init:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U taskflow"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    container_name: taskflow-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
    restart: unless-stopped

  prometheus:
    image: prom/prometheus:v2.48.0
    container_name: taskflow-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.path=/prometheus"
      - "--web.console.libraries=/etc/prometheus/console_libraries"
      - "--web.console.templates=/etc/prometheus/consoles"
      - "--web.enable-lifecycle"
    depends_on:
      - taskflow
    restart: unless-stopped

  taskflow:
    image: ghcr.io/seanchatmangpt/taskflow:latest
    container_name: taskflow-app
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/taskflow
      SPRING_DATASOURCE_USERNAME: taskflow
      SPRING_DATASOURCE_PASSWORD: taskflow_dev_password
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped
    # For development with hot reload, comment out this service and run:
    # mvnd spring-boot:run -Dspring-boot.run.profiles=dev

volumes:
  postgres_data:
  redis_data:
  prometheus_data:
```

The Prometheus scrape configuration at `monitoring/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: "taskflow"
    static_configs:
      - targets: ["taskflow:8080"]
    metrics_path: /actuator/prometheus
    scheme: http
```

To start everything except the TaskFlow application itself (for local development with hot reload):

```bash
# Start infrastructure only
docker compose up postgres redis prometheus -d

# Wait for health checks, then run application with hot reload
mvnd spring-boot:run -Dspring-boot.run.profiles=dev
```

Spring application properties for the dev profile at `src/main/resources/application-dev.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/taskflow
spring.datasource.username=taskflow
spring.datasource.password=taskflow_dev_password
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.show-sql=false

spring.data.redis.host=localhost
spring.data.redis.port=6379

spring.devtools.restart.enabled=true
spring.devtools.livereload.enabled=true

management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.health.show-details=always
```

**Consequences**

Running the production database locally eliminates an entire class of staging surprises. PostgreSQL's `JSONB` behavior, constraint handling, and isolation levels are now part of your local test loop. The tradeoff is that starting the dev environment requires Docker and takes fifteen to thirty seconds for health checks. This is a minor inconvenience compared to the debugging cost of staging-only failures.

The `docker compose up postgres redis prometheus -d` pattern — starting infrastructure separately from the application — preserves fast iteration. You restart the Spring Boot application in seconds while the database retains its state between restarts.

---

## Pattern: THE EVENT BUS

**Problem**

A Kanban board is a collaborative, real-time surface. When one user moves a card from "In Progress" to "Done," every other user viewing the same board must see the update immediately. The naive solution is polling: every client asks the server "has anything changed?" every two seconds. This produces unnecessary load, introduces latency, and doesn't scale.

The refined solution is server-sent events or WebSocket: the server pushes changes to connected clients when they happen. But the implementation question remains: how does the component that processes a task state change notify the WebSocket handler that is holding a connection for each connected client, without tight coupling between them?

**Context**

TaskFlow uses Spring WebSocket (STOMP) for real-time board updates. The `TaskService` processes task state transitions. The `BoardWebSocketHandler` manages client connections and subscriptions. These two components should not know about each other. Adding a new real-time feature should not require modifying `TaskService`.

**Solution**

Use `EventManager<TaskEvent>` as the application-internal event bus. `TaskService` publishes `TaskEvent` instances after processing each transition. `BoardWebSocketHandler` subscribes to those events and pushes them to connected clients. Neither component holds a reference to the other.

First, define the event hierarchy using sealed interfaces for exhaustive handling:

```java
package io.github.seanchatmangpt.taskflow.domain.event;

public sealed interface TaskEvent
    permits TaskEvent.TaskCreated,
            TaskEvent.TaskMoved,
            TaskEvent.TaskAssigned,
            TaskEvent.TaskCompleted {

    record TaskCreated(String boardId, String taskId, String title, String column) implements TaskEvent {}
    record TaskMoved(String boardId, String taskId, String fromColumn, String toColumn) implements TaskEvent {}
    record TaskAssigned(String boardId, String taskId, String assigneeId) implements TaskEvent {}
    record TaskCompleted(String boardId, String taskId, long completedAtEpochMs) implements TaskEvent {}
}
```

Wire the `EventManager` in a `@Configuration` class (satisfying the architectural fence from Chapter 5):

```java
package io.github.seanchatmangpt.taskflow.config;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.taskflow.domain.event.TaskEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JotpConfiguration {

    @Bean
    public EventManager<TaskEvent> taskEventBus() {
        return EventManager.create();
    }
}
```

Publish events from `TaskService` after each state transition:

```java
package io.github.seanchatmangpt.taskflow.service;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.taskflow.domain.Task;
import io.github.seanchatmangpt.taskflow.domain.event.TaskEvent;
import io.github.seanchatmangpt.taskflow.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final EventManager<TaskEvent> taskEventBus;

    public TaskService(TaskRepository taskRepository, EventManager<TaskEvent> taskEventBus) {
        this.taskRepository = taskRepository;
        this.taskEventBus = taskEventBus;
    }

    @Transactional
    public Task moveTask(String taskId, String fromColumn, String toColumn) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        task.setColumn(toColumn);
        Task saved = taskRepository.save(task);

        // Notify all subscribers after the transaction commits
        // The EventManager delivers to each handler in isolation:
        // a crashed WebSocket handler does not affect the audit handler
        taskEventBus.notify(new TaskEvent.TaskMoved(
            task.getBoardId(), taskId, fromColumn, toColumn
        ));

        return saved;
    }

    @Transactional
    public Task createTask(String boardId, String title, String initialColumn) {
        Task task = new Task(boardId, title, initialColumn);
        Task saved = taskRepository.save(task);

        taskEventBus.notify(new TaskEvent.TaskCreated(
            boardId, saved.getId(), title, initialColumn
        ));

        return saved;
    }
}
```

Subscribe in `BoardWebSocketHandler`, which registers its handler at startup:

```java
package io.github.seanchatmangpt.taskflow.websocket;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.taskflow.domain.event.TaskEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class BoardWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final EventManager<TaskEvent> taskEventBus;

    public BoardWebSocketHandler(
            SimpMessagingTemplate messagingTemplate,
            EventManager<TaskEvent> taskEventBus) {
        this.messagingTemplate = messagingTemplate;
        this.taskEventBus = taskEventBus;
    }

    @PostConstruct
    public void registerHandlers() {
        // Handler isolation: if this handler throws, other handlers still run
        taskEventBus.addHandler(this::handleTaskEvent);
    }

    private void handleTaskEvent(TaskEvent event) {
        // Pattern matching on sealed interface — exhaustive, compiler-checked
        String destination = switch (event) {
            case TaskEvent.TaskCreated e -> "/topic/board/" + e.boardId() + "/tasks";
            case TaskEvent.TaskMoved e   -> "/topic/board/" + e.boardId() + "/tasks";
            case TaskEvent.TaskAssigned e -> "/topic/board/" + e.boardId() + "/tasks";
            case TaskEvent.TaskCompleted e -> "/topic/board/" + e.boardId() + "/tasks";
        };

        messagingTemplate.convertAndSend(destination, event);
    }
}
```

The handler isolation guarantee matters here. Imagine you later add a second handler that writes events to an audit log. If the audit log handler throws a `RuntimeException` — because the database is temporarily unavailable, because the event serialization fails — the WebSocket handler still runs. Connected clients still receive their board updates. This is the fundamental difference between `EventManager` and a synchronous observer list: one crashing observer does not poison the event delivery chain.

**Consequences**

`EventManager<TaskEvent>` decouples event producers from event consumers at the application level. `TaskService` does not know that `BoardWebSocketHandler` exists. Adding a new consumer — say, a Slack notification handler for task completions — requires adding one `addHandler` call. No changes to `TaskService`, no changes to `BoardWebSocketHandler`.

The tradeoff is that event handling is now asynchronous and non-transactional from the perspective of the producer. `taskEventBus.notify(...)` returns immediately. If the WebSocket handler is slow, the notification is still delivered (the `EventManager` does not block the publisher), but there is no delivery guarantee if the JVM crashes between `taskRepository.save(task)` and `taskEventBus.notify(...)`. For a Kanban board, this is acceptable: a missed real-time update is a minor inconvenience, not a data loss. For a payment system, you would add a transactional outbox pattern on top.

---

## Pattern: THE HEARTBEAT

**Problem**

TaskFlow sends daily digest emails to board members summarizing what was completed, what is in progress, and what is blocked. The naive implementation is a Spring `@Scheduled` method. This works until you add more than one instance of the application — then every instance fires the digest at midnight and every board member gets three emails.

The JOTP approach gives you a scheduler that lives within the actor supervision tree, restarts cleanly on failure, and can be tested by sending a message rather than waiting for a clock.

**Context**

TaskFlow runs in a single-JVM configuration for now. The digest coordinator is a supervised process that receives a `SendDigest` message at a configured interval and dispatches digest emails. The interval is configurable.

**Solution**

Use `ProcTimer.sendInterval` to send a `SendDigest` message to the digest coordinator every twenty-four hours. The coordinator processes the message by querying completed tasks for the period and dispatching emails. The timer and the coordinator are separate actors — the timer is not doing work, it is merely signaling.

Define the message type:

```java
package io.github.seanchatmangpt.taskflow.digest;

public sealed interface DigestMessage
    permits DigestMessage.SendDigest, DigestMessage.DigestForBoard {

    record SendDigest(long triggeredAtEpochMs) implements DigestMessage {}
    record DigestForBoard(String boardId, long fromEpochMs, long toEpochMs) implements DigestMessage {}
}
```

Wire the digest coordinator and timer in `JotpConfiguration`:

```java
package io.github.seanchatmangpt.taskflow.config;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.taskflow.digest.DigestCoordinatorHandler;
import io.github.seanchatmangpt.taskflow.digest.DigestMessage;
import io.github.seanchatmangpt.taskflow.domain.event.TaskEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class JotpConfiguration {

    @Bean
    public EventManager<TaskEvent> taskEventBus() {
        return EventManager.create();
    }

    @Bean
    public ProcRef<DigestState, DigestMessage> digestCoordinator(
            DigestCoordinatorHandler handler) {

        Proc<DigestState, DigestMessage> proc = new Proc<>(
            DigestState.INITIAL,
            handler::handle
        );

        ProcRef<DigestState, DigestMessage> ref = proc.ref();

        // Register for discovery by name — other components can find this by name
        // without holding a direct reference
        ProcRegistry.register("digest-coordinator", ref);

        // Supervised: restart up to 3 times per minute if the coordinator crashes
        Supervisor.builder()
            .strategy(RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(3)
            .withinWindow(Duration.ofMinutes(1))
            .supervise("digest-coordinator", () -> proc)
            .build()
            .start();

        // Send a SendDigest message every 24 hours
        // The timer is independent of the coordinator: timer failures don't affect
        // the coordinator, and coordinator crashes don't stop the timer
        ProcTimer.sendInterval(
            ref,
            new DigestMessage.SendDigest(System.currentTimeMillis()),
            Duration.ofHours(24)
        );

        return ref;
    }
}
```

The digest coordinator handler:

```java
package io.github.seanchatmangpt.taskflow.digest;

import io.github.seanchatmangpt.taskflow.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class DigestCoordinatorHandler {

    private final TaskRepository taskRepository;
    private final DigestEmailService digestEmailService;
    private final BoardRepository boardRepository;

    public DigestCoordinatorHandler(
            TaskRepository taskRepository,
            DigestEmailService digestEmailService,
            BoardRepository boardRepository) {
        this.taskRepository = taskRepository;
        this.digestEmailService = digestEmailService;
        this.boardRepository = boardRepository;
    }

    public DigestState handle(DigestState state, DigestMessage message) {
        return switch (message) {
            case DigestMessage.SendDigest(var triggeredAt) -> {
                Instant from = Instant.ofEpochMilli(triggeredAt).minus(24, ChronoUnit.HOURS);
                Instant to = Instant.ofEpochMilli(triggeredAt);

                // Query completed tasks in the window, group by board, dispatch emails
                boardRepository.findAllActive().forEach(board -> {
                    var completedTasks = taskRepository.findCompletedBetween(
                        board.getId(), from, to
                    );
                    var inProgressTasks = taskRepository.findByBoardAndColumn(
                        board.getId(), "In Progress"
                    );
                    var blockedTasks = taskRepository.findByBoardAndColumn(
                        board.getId(), "Blocked"
                    );

                    digestEmailService.sendDigest(board, completedTasks, inProgressTasks, blockedTasks);
                });

                yield state.withLastDigestAt(triggeredAt);
            }

            case DigestMessage.DigestForBoard(var boardId, var from, var to) -> {
                // On-demand digest for a specific board (e.g., from a manual trigger)
                var board = boardRepository.findById(boardId).orElseThrow();
                var completedTasks = taskRepository.findCompletedBetween(board.getId(),
                    Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
                digestEmailService.sendDigest(board, completedTasks, java.util.List.of(), java.util.List.of());

                yield state;
            }
        };
    }
}
```

To send a one-off message at a specific time in the future — for example, to remind a user twenty-four hours before a task deadline — use `ProcTimer.sendAfter`:

```java
// Remind the assignee 24 hours before the task due date
ProcTimer.sendAfter(
    reminderRef,
    new ReminderMessage.TaskDueSoon(taskId, assigneeId),
    Duration.between(Instant.now(), dueDate.minus(24, ChronoUnit.HOURS))
);
```

**The key architectural benefit** of `ProcTimer` over `@Scheduled` is testability. To test the `DigestCoordinatorHandler` in isolation, you send it a `SendDigest` message directly. No clock manipulation. No `Thread.sleep`. No `ScheduledExecutorService` mocking. The handler processes the message and you assert the output.

```java
// In DigestCoordinatorHandlerTest.java
@Test
void send_digest_queries_completed_tasks_for_24_hour_window() {
    var handler = new DigestCoordinatorHandler(
        mockTaskRepository, mockEmailService, mockBoardRepository
    );
    long now = Instant.now().toEpochMilli();

    handler.handle(DigestState.INITIAL, new DigestMessage.SendDigest(now));

    // Verify the repository was queried with the correct time window
    verify(mockTaskRepository).findCompletedBetween(
        any(),
        eq(Instant.ofEpochMilli(now).minus(24, ChronoUnit.HOURS)),
        eq(Instant.ofEpochMilli(now))
    );
}
```

**Consequences**

The `sendInterval` pattern gives you a supervised, testable scheduler. The supervisor restarts the coordinator if it crashes during digest processing — a database timeout or a transient email service failure does not permanently kill the digest feature. The `ProcTimer` is not the coordinator; it merely sends messages. If the coordinator is restarted by the supervisor, the next interval message arrives and processing resumes.

The limitation is that `ProcTimer` intervals are not persistent. If the JVM restarts at 23:59 and the next digest was scheduled for 00:00, the timer resets and the digest fires twenty-four hours later. For non-critical notifications like daily digests, this is acceptable. For billing operations or SLA-critical notifications, you would combine `ProcTimer` with a persistent schedule store and use `sendAfter` for precision timing.

---

## Moving to Docker Swarm

Once TaskFlow outgrows a single machine, Docker Compose becomes Docker Swarm. The conceptual change is smaller than you might expect. Docker Swarm uses the same `docker-compose.yml` format with minor extensions. The `docker stack deploy` command replaces `docker compose up`.

The significant change is service discovery. In Compose, services find each other by container name (`postgres`, `redis`). In Swarm, services find each other by service name within a named overlay network. Your `application-prod.properties` uses the same hostnames — the Swarm DNS resolver handles the rest.

The JOTP architecture adapts cleanly to multi-instance deployment, with one consideration: `ProcRegistry` is JVM-local. `ProcRegistry.whereis("digest-coordinator")` returns the reference registered in the current JVM. If you run three TaskFlow instances in Swarm, each has its own `digest-coordinator`. This is correct for the digest use case — you want only one instance firing the digest, which you achieve with a Swarm global service (`mode: global`) and a single replica for the digest coordinator service.

For future cross-JVM actor communication, the roadmap includes location-transparent `ProcRef` serialization. Until then, the Kafka bridge pattern from the architecture reference works: publish a `SEND_DIGEST` message to a Kafka topic with a single consumer group, ensuring exactly one instance processes each trigger.

---

## What Have You Learned?

- **The local production mirror** eliminates staging surprises. Running PostgreSQL locally — not H2 — means your queries are tested against the database you deploy.

- **Docker Compose separates infrastructure from application.** Start `postgres redis prometheus` with Compose, run the application with `mvnd spring-boot:run`. Infrastructure persists across application restarts.

- **`EventManager<TaskEvent>` provides handler isolation.** A crashing WebSocket handler does not prevent the audit handler from running. You add new consumers by calling `addHandler`, not by modifying producers.

- **Sealed interfaces and pattern matching make event handling exhaustive.** The compiler tells you when you add a new `TaskEvent` variant and forget to handle it in a `switch` expression.

- **`ProcTimer.sendInterval` separates the clock from the work.** The timer sends a message; the coordinator processes it. You test the coordinator by sending a message directly — no clock manipulation required.

- **`ProcTimer.sendAfter` schedules one-off messages.** Use it for deadline reminders, timeout escalations, and retry backoff — anything that should happen once after a delay.

- **`ProcRegistry.register` gives processes discoverable names.** Components that need to communicate with the digest coordinator call `ProcRegistry.whereis("digest-coordinator")` rather than holding a direct reference that might become stale after a supervisor restart.

---
