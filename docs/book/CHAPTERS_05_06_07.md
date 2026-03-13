# Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP

## Chapters 5, 6, and 7

---

# Chapter 5: Set Up Integration Pipelines with GitHub Actions

You have working code. You have tests. But "works on my machine" is not a deployment strategy. Every serious team has experienced the sinking feeling of merging a pull request that passed local tests, only to find the CI build red because someone forgot to check in a formatter change, left a `// TODO` in production code, or didn't run the integration tests. This chapter is about closing that gap permanently.

The solution is a pipeline that enforces quality mechanically, not socially. You stop relying on developers to remember to run the right commands. The pipeline runs them for you, fails fast on the cheapest checks, and only proceeds to expensive work when cheap work passes. By the end of this chapter, every push to the TaskFlow repository triggers a workflow that checks formatting, runs unit tests, validates guard rules, runs integration tests, builds a Docker image, and publishes it. You also get a nightly benchmark job that catches performance regressions before they ship.

---

## Pattern: THE QUALITY GATE PIPELINE

**Problem**

Your team has agreed on code standards — formatting, no TODOs in production, no mocks in main source, integration tests must pass. But agreement without enforcement is just aspiration. Developers forget. Reviews miss things. Standards drift.

**Context**

You are operating a Java 26 / JOTP codebase with Spotless for formatting, dx-guard for guard validation, Surefire for unit tests, and Failsafe for integration tests. You are using GitHub as your source control host and need repeatable, auditable quality enforcement on every change.

**Solution**

Define your quality gates as an ordered pipeline in a GitHub Actions workflow. The order matters: cheap, fast gates run first. If formatting is broken, do not spend five minutes running integration tests. Fail immediately, give the developer a clear signal, and let them fix it. The pipeline stages are:

1. **Format check** — `mvnd spotless:check` (ten seconds, zero ambiguity)
2. **Unit tests** — `mvnd test` with Surefire parallel execution (one to three minutes)
3. **Guard validation** — `./dx.sh validate` enforcing H_TODO, H_MOCK, H_STUB rules
4. **Integration tests** — `mvnd verify` with Failsafe (three to eight minutes)
5. **Docker build and push** — only if all previous gates pass

Each gate is a `step` in a single job. If any step fails, the job stops. No Docker image gets published from broken code.

**Code Example**

Create `.github/workflows/ci.yml`:

```yaml
name: TaskFlow CI

on:
  push:
    branches: [ "main", "develop" ]
  pull_request:
    branches: [ "main", "develop" ]
  schedule:
    # Nightly at 02:00 UTC — catches dependency drift and flaky tests
    - cron: "0 2 * * *"

env:
  JAVA_VERSION: "26"
  REGISTRY: ghcr.io
  IMAGE_NAME: seanchatmangpt/taskflow

jobs:
  build:
    name: Build and Verify
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 26
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven

      - name: Install mvnd
        run: |
          MVND_VERSION="2.0.0-rc-3"
          MVND_URL="https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz"
          curl -fsSL "$MVND_URL" -o /tmp/mvnd.tar.gz
          tar -xzf /tmp/mvnd.tar.gz -C /tmp
          sudo mv /tmp/maven-mvnd-${MVND_VERSION}-linux-amd64 /opt/mvnd
          sudo ln -sf /opt/mvnd/bin/mvnd /usr/local/bin/mvnd
          mvnd --version

      # Gate 1: Formatting — fastest gate, fails instantly on style drift
      - name: Check formatting (Spotless)
        run: mvnd spotless:check -q

      # Gate 2: Unit tests — parallel Surefire, no integration tests yet
      - name: Unit tests
        run: mvnd test -T1C
        # -T1C: one thread per CPU core for parallel module builds
        # Surefire parallel execution configured in pom.xml

      # Gate 3: Guard validation — no TODOs, no mocks, no stubs in main source
      - name: Guard validation
        run: ./dx.sh validate

      # Gate 4: Integration tests — Failsafe with @IT suffix, may spin up containers
      - name: Integration tests
        run: mvnd verify -DskipTests=false
        # verify runs compile, test (unit), package, integration-test, verify

      # Gate 5: Docker — only runs after all quality gates pass
      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata for Docker
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: |
            type=ref,event=branch
            type=ref,event=pr
            type=sha,prefix=sha-
            type=raw,value=latest,enable=${{ github.ref == 'refs/heads/main' }}

      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: ${{ github.event_name != 'pull_request' }}
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  benchmark:
    name: Nightly JMH Benchmarks
    runs-on: ubuntu-latest
    # Only run on schedule or when explicitly triggered; not on every push
    if: github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'
    needs: build

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 26
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven

      - name: Install mvnd
        run: |
          MVND_VERSION="2.0.0-rc-3"
          curl -fsSL "https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz" -o /tmp/mvnd.tar.gz
          tar -xzf /tmp/mvnd.tar.gz -C /tmp
          sudo mv /tmp/maven-mvnd-${MVND_VERSION}-linux-amd64 /opt/mvnd
          sudo ln -sf /opt/mvnd/bin/mvnd /usr/local/bin/mvnd

      - name: Run ActorBenchmark (JMH)
        run: |
          mvnd verify -Pbenchmark -Dbenchmark.class=ActorBenchmark \
            -Dbenchmark.fork=1 -Dbenchmark.iterations=5 \
            -Dbenchmark.output=benchmark-results.json

      - name: Upload benchmark results
        uses: actions/upload-artifact@v4
        with:
          name: benchmark-results-${{ github.sha }}
          path: benchmark-results.json

      - name: Check for regressions
        run: |
          # Compare against the baseline stored in the repository
          # Fail if any benchmark is >20% slower than baseline
          mvnd exec:java -Dexec.mainClass=io.github.seanchatmangpt.jotp.bench.RegressionChecker \
            -Dexec.args="benchmark-results.json .ci/benchmark-baseline.json 0.20"
```

**Consequences**

You gain reproducible quality enforcement that runs identically on every developer machine and in CI. The ordering guarantees that expensive work is never done on broken input. The Docker image in `ghcr.io/seanchatmangpt/taskflow` is always a known-good build. The nightly benchmark job catches the quiet performance regressions that creep in through dependency upgrades and refactoring — the kind that never show up in correctness tests but add fifty milliseconds to every request.

The tradeoff is workflow complexity. Five gates mean five places that can fail, and debugging CI failures requires understanding which gate caught what. The payoff is that failures are always specific: if gate one fails, the problem is formatting; if gate four fails, the problem is an integration test. You never hunt for the cause.

---

## Pattern: THE REPRODUCIBLE BUILD

**Problem**

Your CI passes. Your colleague's machine fails. You pass a Docker image to QA and it behaves differently from what you tested locally. The root cause is always the same: environmental variation. Different JDK minor versions, different Maven plugin cache states, different locale settings.

**Context**

You are running Java 26 with preview features enabled via `--enable-preview`. This makes JDK version sensitivity acute — preview APIs change between builds. You need every build to use exactly the same JDK, the same mvnd version, and the same dependency resolution.

**Solution**

Lock everything. Use `actions/setup-java` with an explicit version and the `temurin` distribution. Pin mvnd to `2.0.0-rc-3`. Commit `pom.xml` with explicit plugin versions rather than relying on Maven's default lifecycle bindings, which change between Maven versions. Use the GitHub Actions cache for the Maven local repository to avoid re-downloading dependencies on every run, but use a cache key that includes the `pom.xml` hash so that dependency changes invalidate the cache.

The `pom.xml` configuration for reproducible builds:

```xml
<properties>
  <maven.compiler.source>26</maven.compiler.source>
  <maven.compiler.target>26</maven.compiler.target>
  <!-- Preview features: required for StructuredTaskScope and scoped values -->
  <maven.compiler.compilerArgs>--enable-preview</maven.compiler.compilerArgs>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <!-- Lock Surefire version explicitly -->
  <maven-surefire-plugin.version>3.2.5</maven-surefire-plugin.version>
  <maven-failsafe-plugin.version>3.2.5</maven-failsafe-plugin.version>
</properties>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>${maven-surefire-plugin.version}</version>
      <configuration>
        <!-- Parallel test execution: classes run in parallel -->
        <parallel>classes</parallel>
        <useUnlimitedThreads>true</useUnlimitedThreads>
        <!-- Pass preview flag to forked test JVMs -->
        <argLine>--enable-preview</argLine>
        <!-- Fail fast: stop on first failure in CI -->
        <failIfNoTests>false</failIfNoTests>
      </configuration>
    </plugin>

    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <version>${maven-failsafe-plugin.version}</version>
      <configuration>
        <argLine>--enable-preview</argLine>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>integration-test</goal>
            <goal>verify</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

The `Dockerfile` for the image pushed by CI:

```dockerfile
FROM eclipse-temurin:26-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S taskflow && adduser -S taskflow -G taskflow

COPY --chown=taskflow:taskflow target/taskflow-*.jar app.jar

USER taskflow

EXPOSE 8080

ENTRYPOINT ["java", \
  "--enable-preview", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", \
  "-jar", "app.jar"]
```

**Consequences**

Pinning versions makes upgrades explicit rather than accidental. You will know when you upgrade mvnd because you changed the number. You will know when you change Surefire because you changed the number. This is more verbose than using Maven's default binding resolution, but it eliminates the class of failures where "it worked last week" and the only change is a transitive plugin version bump.

---

## Pattern: THE ARCHITECTURAL FENCE

**Problem**

JOTP's `Proc<S,M>` is powerful and straightforward to instantiate — which means developers will create processes anywhere: in service classes, in controllers, inside lambda expressions passed to Spring event listeners. Over time this produces a codebase where process lifecycle is unmanaged, supervision is bypassed, and faults propagate in ways the supervision tree was designed to prevent.

**Context**

You have a rule: `Proc` instances must only be created inside Spring `@Configuration` classes, where they can be properly supervised and registered with `ProcRegistry`. This rule is architectural, not stylistic — violation is a defect, not a style preference.

**Solution**

Encode the architectural rule in ArchUnit and run it as part of the test suite. ArchUnit lets you express dependency and creation constraints as Java code, with failure messages that tell developers exactly what went wrong and how to fix it.

Create `src/test/java/io/github/seanchatmangpt/taskflow/arch/ArchitectureTest.java`:

```java
package io.github.seanchatmangpt.taskflow.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class ArchitectureTest {

    private static final JavaClasses TASKFLOW_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.github.seanchatmangpt.taskflow");

    /**
     * Proc instances must only be created inside @Configuration classes.
     * Creating a Proc outside a Configuration class bypasses supervision and
     * ProcRegistry registration, producing unmanaged processes with no fault isolation.
     *
     * Fix: Move Proc creation to a @Configuration class and register with ProcRegistry.
     */
    @Test
    void proc_creation_only_in_configuration_classes() {
        ArchRule rule = noClasses()
            .that()
            .areNotAnnotatedWith(org.springframework.context.annotation.Configuration.class)
            .should()
            .callConstructor(io.github.seanchatmangpt.jotp.Proc.class, Object.class, java.util.function.BiFunction.class)
            .because(
                "Proc instances must be created inside @Configuration classes to ensure " +
                "proper supervision and ProcRegistry registration. " +
                "Unmanaged processes cannot be supervised and bypass fault isolation."
            );

        rule.check(TASKFLOW_CLASSES);
    }

    /**
     * Service classes must not import JOTP primitives directly.
     * Services communicate through ProcRef (a stable handle) or via message passing.
     * Direct imports of Proc couple services to lifecycle management concerns.
     */
    @Test
    void services_do_not_depend_on_proc_directly() {
        ArchRule rule = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("io.github.seanchatmangpt.jotp.Proc")
            .because(
                "Service classes must use ProcRef<S,M> for actor references, not Proc<S,M> directly. " +
                "ProcRef is a stable handle that survives supervisor restarts."
            );

        rule.check(TASKFLOW_CLASSES);
    }

    /**
     * EventManager instances must be declared in @Configuration classes or @Component classes.
     * Ad-hoc EventManager creation in request handlers loses the isolation guarantee —
     * a crashed handler would take down the EventManager itself.
     */
    @Test
    void event_managers_declared_in_managed_beans() {
        ArchRule rule = noClasses()
            .that()
            .areNotAnnotatedWith(org.springframework.context.annotation.Configuration.class)
            .and()
            .areNotAnnotatedWith(org.springframework.stereotype.Component.class)
            .and()
            .areNotAnnotatedWith(org.springframework.stereotype.Service.class)
            .should()
            .callMethod(
                io.github.seanchatmangpt.jotp.EventManager.class,
                "create"
            )
            .because(
                "EventManager instances must be managed Spring beans. " +
                "Transient EventManager instances created per-request lose handler registration " +
                "and the isolation guarantee between handlers."
            );

        rule.check(TASKFLOW_CLASSES);
    }
}
```

The ArchUnit dependency in `pom.xml`:

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version>
  <scope>test</scope>
</dependency>
```

ArchUnit tests run under Surefire as ordinary unit tests. No special configuration is required. When a developer creates a `new Proc<>(...)` in a service class, the `proc_creation_only_in_configuration_classes` test fails with the message you wrote: "Proc instances must be created inside @Configuration classes to ensure proper supervision and ProcRegistry registration."

**Consequences**

The architectural fence is documentation that cannot be ignored. It lives next to your code, runs every time tests run, and gives actionable failure messages. Unlike ADRs or wiki pages, it cannot drift out of date — it reflects the actual structure of the codebase.

The limitation is that ArchUnit rules require explicit maintenance. When you add new constraints, you add new tests. When you intentionally violate a rule (for testing infrastructure, for example), you add an `@ArchIgnore` annotation with a comment explaining why. This is the right tradeoff: explicit exceptions are better than silent non-enforcement.

---

## What Have You Learned?

- **Gates are ordered by cost.** Formatting check before unit tests before integration tests before Docker build. Cheap failures never let expensive work begin.

- **`mvnd spotless:check` as the first gate** provides a ten-second signal that fails fast, produces no ambiguous output, and teaches developers to run formatting before committing rather than after.

- **`./dx.sh validate`** enforces H_TODO, H_MOCK, and H_STUB rules in the same pipeline that enforces tests. Guard violations are CI failures, not optional warnings.

- **Surefire parallel execution** (`-T1C`, `<parallel>classes</parallel>`) cuts unit test time in proportion to core count. This matters when the test suite grows: parallel execution on eight cores cuts eight-minute test suites to under two minutes.

- **ArchUnit rules make architectural decisions enforceable.** "Proc only in @Configuration" is not a guideline — it is a failing test.

- **Nightly JMH benchmarks catch regressions that tests cannot.** A benchmark that fails on twenty percent regression means you find the slow commit the morning after it merges, not three months later when production is sluggish.

- **GitHub Actions `schedule` trigger** runs the full pipeline without a push event. This catches dependency drift: a library you did not change publishes a new version overnight and breaks your build. You find out before your team does.

---

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

# Chapter 7: The Production Environment and Packer

There is a rite of passage in every software team's history: the first time someone has to recover a production server and nobody knows exactly how it was set up. The original engineer who configured it left six months ago. The runbook says "install Java and deploy the jar" but doesn't specify which Java, which version of the jar, or what environment variables are needed. The recovery takes eight hours.

The solution is not better documentation. Documentation rots. The solution is that your production environment is defined as code, built into an artifact, and re-created from that artifact. This chapter covers that full cycle: you create the AWS production environment manually to understand what you're automating, use `terraform import` to bring it under infrastructure-as-code control, build a gold machine image with Packer, and wire everything together. You also add `CrashRecovery.retry` to the AWS API calls that talk to EC2 and RDS, because AWS APIs are distributed systems and distributed systems have transient failures.

---

## Pattern: THE GOLD IMAGE

**Problem**

Server configuration that happens after deployment — installing Java, configuring systemd units, setting environment variables, pulling Docker images — is fragile, slow, and hard to test. If a server fails and you need to replace it, you re-run the configuration process and hope nothing changed in the package repositories overnight.

**Context**

TaskFlow runs on EC2 instances in AWS. Each instance needs JDK 26, the TaskFlow Docker image pre-pulled, a systemd unit for the application, CloudWatch agent configuration, and specific kernel parameters for Java virtual thread performance. These requirements are stable between deployments — they change when the Java version changes, not when the application changes.

**Solution**

Build a machine image (AMI) that has everything pre-installed and pre-configured. When you launch a new instance, it boots with Java already installed, Docker already configured, and the application image already pulled. Startup time drops from ten minutes to under sixty seconds. Configuration drift is impossible because every new instance is created from the same image.

Packer builds the image. You define a Packer template in HCL2, run `packer build`, and get an AMI ID that you then reference in your Terraform `aws_launch_template`.

Create `packer/taskflow.pkr.hcl`:

```hcl
packer {
  required_plugins {
    amazon = {
      version = ">= 1.3.0"
      source  = "github.com/hashicorp/amazon"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "taskflow_version" {
  type    = string
  default = "latest"
}

variable "base_ami_id" {
  type        = string
  description = "Ubuntu 22.04 LTS AMI ID for the target region"
  default     = "ami-0c7217cdde317cfec"  # us-east-1 Ubuntu 22.04 LTS
}

source "amazon-ebs" "taskflow" {
  region        = var.aws_region
  source_ami    = var.base_ami_id
  instance_type = "t3.medium"  # Same type as production for reliable benchmarking

  ssh_username = "ubuntu"

  ami_name        = "taskflow-${var.taskflow_version}-{{timestamp}}"
  ami_description = "TaskFlow application image with JDK 26 and pre-pulled Docker image"

  tags = {
    Name            = "taskflow"
    Version         = var.taskflow_version
    BuildTimestamp  = "{{timestamp}}"
    ManagedBy       = "packer"
  }

  # Security: restrict SSH to the Packer build host only during build
  temporary_security_group_source_cidrs = ["0.0.0.0/0"]
}

build {
  name    = "taskflow-gold-image"
  sources = ["source.amazon-ebs.taskflow"]

  # Wait for cloud-init to complete before provisioning
  provisioner "shell" {
    inline = [
      "cloud-init status --wait",
      "sudo apt-get update -qq"
    ]
  }

  # Install Docker
  provisioner "shell" {
    inline = [
      "sudo apt-get install -y -qq ca-certificates curl gnupg",
      "sudo install -m 0755 -d /etc/apt/keyrings",
      "curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg",
      "sudo chmod a+r /etc/apt/keyrings/docker.gpg",
      "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable\" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null",
      "sudo apt-get update -qq",
      "sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin",
      "sudo systemctl enable docker",
      "sudo usermod -aG docker ubuntu"
    ]
  }

  # Install OpenJDK 26
  provisioner "shell" {
    inline = [
      "JDK26_URL='https://download.java.net/java/GA/jdk26/c3cc523845074aa0af4f5e1e1ed4151d/35/GPL/openjdk-26_linux-x64_bin.tar.gz'",
      "curl -fsSL \"$JDK26_URL\" -o /tmp/jdk26.tar.gz",
      "sudo mkdir -p /usr/lib/jvm",
      "sudo tar -xzf /tmp/jdk26.tar.gz -C /usr/lib/jvm",
      "sudo mv /usr/lib/jvm/jdk-26* /usr/lib/jvm/openjdk-26",
      "sudo ln -sf /usr/lib/jvm/openjdk-26 /opt/jdk",
      "rm /tmp/jdk26.tar.gz",
      "echo 'JAVA_HOME=/usr/lib/jvm/openjdk-26' | sudo tee -a /etc/environment",
      "echo 'PATH=/usr/lib/jvm/openjdk-26/bin:$PATH' | sudo tee -a /etc/environment",
      "/usr/lib/jvm/openjdk-26/bin/java -version"
    ]
  }

  # Kernel tuning for virtual threads and high connection counts
  provisioner "shell" {
    inline = [
      "echo 'net.core.somaxconn = 65535' | sudo tee -a /etc/sysctl.conf",
      "echo 'net.ipv4.tcp_max_syn_backlog = 65535' | sudo tee -a /etc/sysctl.conf",
      "echo 'fs.file-max = 1000000' | sudo tee -a /etc/sysctl.conf",
      "echo '* soft nofile 1000000' | sudo tee -a /etc/security/limits.conf",
      "echo '* hard nofile 1000000' | sudo tee -a /etc/security/limits.conf"
    ]
  }

  # Copy systemd unit for TaskFlow
  provisioner "file" {
    source      = "../systemd/taskflow.service"
    destination = "/tmp/taskflow.service"
  }

  provisioner "shell" {
    inline = [
      "sudo mv /tmp/taskflow.service /etc/systemd/system/taskflow.service",
      "sudo systemctl daemon-reload",
      "sudo systemctl enable taskflow"
      # Don't start it — EC2 user data will start it on first boot with env vars injected
    ]
  }

  # Pre-pull the TaskFlow Docker image to speed up first boot
  provisioner "shell" {
    inline = [
      "sudo docker pull ghcr.io/seanchatmangpt/taskflow:${var.taskflow_version} || true"
      # The || true ensures the build doesn't fail if the image isn't yet published.
      # In production pipelines, remove the || true to enforce the image exists.
    ]
  }

  # Install CloudWatch agent
  provisioner "shell" {
    inline = [
      "curl -fsSL 'https://s3.amazonaws.com/amazoncloudwatch-agent/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb' -o /tmp/cloudwatch-agent.deb",
      "sudo dpkg -i /tmp/cloudwatch-agent.deb",
      "rm /tmp/cloudwatch-agent.deb"
    ]
  }
}
```

The systemd unit at `systemd/taskflow.service`:

```ini
[Unit]
Description=TaskFlow Application
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=simple
Restart=always
RestartSec=5s
User=ubuntu

# Environment variables are injected via EC2 user data or SSM Parameter Store
EnvironmentFile=-/etc/taskflow/env

ExecStartPre=-/usr/bin/docker stop taskflow
ExecStartPre=-/usr/bin/docker rm taskflow
ExecStart=/usr/bin/docker run \
  --name taskflow \
  --rm \
  --publish 8080:8080 \
  --env-file /etc/taskflow/env \
  ghcr.io/seanchatmangpt/taskflow:${TASKFLOW_VERSION:-latest}

ExecStop=/usr/bin/docker stop taskflow

[Install]
WantedBy=multi-user.target
```

Build the AMI:

```bash
cd packer
packer init taskflow.pkr.hcl
packer validate taskflow.pkr.hcl
packer build taskflow.pkr.hcl
# Output: AMI ID: ami-0a1b2c3d4e5f6a7b8
```

**Consequences**

The gold image makes instance replacement trivial and fast. New instances boot from a known-good image; there is no configuration phase that can fail or drift. The tradeoff is that the AMI must be rebuilt when the base configuration changes — when you upgrade from JDK 26 to JDK 27, or when the Docker version changes. This is the right tradeoff: explicit, versioned rebuilds versus invisible configuration drift.

---

## Pattern: INFRASTRUCTURE IMPORT

**Problem**

Your operations team created the production EC2 instance, RDS database, and security groups manually in the AWS console. The infrastructure exists and works. But it is not under version control. The next person who needs to create a new environment must guess at the configuration, or ask the person who set it up. When that person leaves, institutional knowledge walks out with them.

**Context**

You have a running AWS environment: one EC2 `t3.medium` instance running TaskFlow, one RDS PostgreSQL instance, security groups restricting access, an Application Load Balancer. You want all of this under Terraform without destroying and recreating it.

**Solution**

Use `terraform import` to import existing AWS resources into Terraform state. You write the Terraform configuration that describes the resource, then import the existing resource ID into that configuration. Terraform then manages the resource without recreating it.

Create `terraform/main.tf`:

```hcl
terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = "taskflow-terraform-state"
    key    = "production/terraform.tfstate"
    region = "us-east-1"
    # State locking via DynamoDB
    dynamodb_table = "taskflow-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "taskflow_ami_id" {
  type        = string
  description = "AMI ID produced by packer build"
}

variable "db_password" {
  type      = string
  sensitive = true
}

# VPC (import existing)
resource "aws_vpc" "taskflow" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "taskflow-vpc"
  }
}

# Security group: application tier
resource "aws_security_group" "taskflow_app" {
  name        = "taskflow-app"
  description = "TaskFlow application security group"
  vpc_id      = aws_vpc.taskflow.id

  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "TaskFlow application port"
  }

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]
    description = "SSH from VPC only"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "taskflow-app"
  }
}

# Security group: database tier
resource "aws_security_group" "taskflow_db" {
  name        = "taskflow-db"
  description = "TaskFlow database security group"
  vpc_id      = aws_vpc.taskflow.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.taskflow_app.id]
    description     = "PostgreSQL from app tier only"
  }

  tags = {
    Name = "taskflow-db"
  }
}

# Launch template — uses the Packer-built AMI
resource "aws_launch_template" "taskflow" {
  name_prefix   = "taskflow-"
  image_id      = var.taskflow_ami_id
  instance_type = "t3.medium"

  vpc_security_group_ids = [aws_security_group.taskflow_app.id]

  iam_instance_profile {
    name = aws_iam_instance_profile.taskflow.name
  }

  # User data: inject environment variables at boot time
  # These are populated from SOPS-encrypted secrets at deploy time
  user_data = base64encode(templatefile("${path.module}/user-data.sh.tpl", {
    db_host     = aws_db_instance.taskflow.address
    db_password = var.db_password
  }))

  tag_specifications {
    resource_type = "instance"
    tags = {
      Name = "taskflow"
    }
  }
}

# RDS PostgreSQL
resource "aws_db_instance" "taskflow" {
  identifier        = "taskflow-postgres"
  engine            = "postgres"
  engine_version    = "16.1"
  instance_class    = "db.t3.medium"
  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = "taskflow"
  username = "taskflow"
  password = var.db_password

  vpc_security_group_ids = [aws_security_group.taskflow_db.id]
  skip_final_snapshot    = false
  final_snapshot_identifier = "taskflow-final-snapshot"

  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "mon:04:00-mon:05:00"

  tags = {
    Name = "taskflow-postgres"
  }
}

# EC2 instance (the existing one you're importing)
resource "aws_instance" "taskflow" {
  ami           = var.taskflow_ami_id
  instance_type = "t3.medium"

  vpc_security_group_ids = [aws_security_group.taskflow_app.id]
  iam_instance_profile   = aws_iam_instance_profile.taskflow.name

  tags = {
    Name = "taskflow"
  }
}

# IAM role for EC2 instances — allows SSM Parameter Store access and CloudWatch metrics
resource "aws_iam_role" "taskflow" {
  name = "taskflow-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.taskflow.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "cloudwatch" {
  role       = aws_iam_role.taskflow.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_instance_profile" "taskflow" {
  name = "taskflow-ec2-profile"
  role = aws_iam_role.taskflow.name
}
```

Now import the existing resources. The instance ID `i-0abc123` is what you see in the EC2 console:

```bash
cd terraform
terraform init

# Import the existing EC2 instance
terraform import aws_instance.taskflow i-0abc123def456789

# Import the existing RDS instance
terraform import aws_db_instance.taskflow taskflow-postgres

# Import the existing VPC
terraform import aws_vpc.taskflow vpc-0abc123def456789

# Import security groups
terraform import aws_security_group.taskflow_app sg-0abc123app
terraform import aws_security_group.taskflow_db sg-0abc123db
```

After import, run `terraform plan` to see the diff between your Terraform configuration and the actual state. The first plan will often show changes — the resource was configured with values that differ from what you wrote. This is the import workflow: import, plan, reconcile the configuration to match reality, plan again until the diff is clean.

```bash
terraform plan -var="taskflow_ami_id=ami-placeholder" \
               -var="db_password=$(sops -d secrets/prod.yaml | yq '.db_password')"
```

**Managing secrets with SOPS and Age**

Database passwords, API keys, and JWT secrets cannot live in your Terraform files or environment variables visible in process listings. SOPS (Secrets OPerationS) encrypts secret files using Age keys; the encrypted file is safe to commit to Git. Decryption requires the private Age key, which lives in your secrets manager or on developer machines.

Create an Age key pair:

```bash
# Generate an Age key pair
age-keygen -o ~/.age/taskflow-prod.txt
# Output: Public key: age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p

# Add the public key to .sops.yaml
cat > .sops.yaml <<EOF
creation_rules:
  - path_regex: secrets/.*\.yaml$
    age: age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p
EOF
```

Create and encrypt `secrets/prod.yaml`:

```bash
sops secrets/prod.yaml
```

SOPS opens an editor with a YAML template. Enter your secrets:

```yaml
db_password: ENC[AES256_GCM,data:verySecretPassword123,...]
jwt_secret: ENC[AES256_GCM,data:anotherSecret...,...]
```

Decrypt at deploy time:

```bash
# Decrypt and pass to terraform
DB_PASSWORD=$(sops -d secrets/prod.yaml | python3 -c "import sys,yaml; print(yaml.safe_load(sys.stdin)['db_password'])")
terraform apply -var="db_password=${DB_PASSWORD}" -var="taskflow_ami_id=${AMI_ID}"
```

**Consequences**

`terraform import` lets you bring existing infrastructure under IaC control without downtime or recreation. The first plan shows the gap between your Terraform configuration and reality — this gap is valuable information. It tells you what was configured manually that your configuration doesn't model yet.

The SOPS + Age workflow keeps secrets encrypted at rest and in Git while making them available at deploy time. The Age private key is the only credential that must be protected; everything else is derived from it.

---

## Pattern: THE RETRY ENVELOPE

**Problem**

AWS APIs are themselves distributed systems. They are reliable in aggregate but fail transiently in the specific. An `ec2:DescribeInstances` call can return a `500 Internal Server Error` or a `RequestLimitExceeded` throttling error at any moment. Production code that calls AWS APIs without retry logic will fail under load, during high-traffic periods, or simply during AWS's routine maintenance windows.

**Context**

TaskFlow's deployment tooling calls AWS EC2 and RDS APIs directly — to verify that new instances have started, to check RDS availability before updating DNS, to describe security group rules during validation. These calls happen in a Java deployment tool built with the AWS SDK for Java v2.

**Solution**

Wrap each AWS API call in `CrashRecovery.retry`. The `retry` method runs the supplier in an isolated virtual thread. If the supplier throws an exception, the method runs it again, up to the specified count. It returns a `Result<T, Exception>` — success or the final exception, never a raw throw.

```java
package io.github.seanchatmangpt.taskflow.deploy;

import io.github.seanchatmangpt.jotp.CrashRecovery;
import io.github.seanchatmangpt.jotp.Result;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;

import java.time.Duration;
import java.util.List;

public class AwsDeploymentVerifier {

    private final Ec2Client ec2Client;
    private final RdsClient rdsClient;

    public AwsDeploymentVerifier(Ec2Client ec2Client, RdsClient rdsClient) {
        this.ec2Client = ec2Client;
        this.rdsClient = rdsClient;
    }

    /**
     * Verify that new EC2 instances launched from the given AMI are running.
     * Retries 3 times to handle transient AWS API errors.
     */
    public List<Instance> describeTaskflowInstances(String amiId) {
        Result<List<Instance>, Exception> result = CrashRecovery.retry(3, () -> {
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(
                    Filter.builder().name("image-id").values(amiId).build(),
                    Filter.builder().name("instance-state-name").values("running").build()
                )
                .build();

            return ec2Client.describeInstancesPaginator(request)
                .stream()
                .flatMap(page -> page.reservations().stream())
                .flatMap(reservation -> reservation.instances().stream())
                .toList();
        });

        return result.fold(
            instances -> instances,
            error -> {
                throw new DeploymentVerificationException(
                    "Failed to describe EC2 instances after 3 attempts", error
                );
            }
        );
    }

    /**
     * Wait for RDS to be in 'available' state after modification.
     * The RDS API can return transient errors during state transitions.
     */
    public DBInstance describeRdsInstance(String dbInstanceIdentifier) {
        Result<DBInstance, Exception> result = CrashRecovery.retry(3, () -> {
            var response = rdsClient.describeDBInstances(
                DescribeDbInstancesRequest.builder()
                    .dbInstanceIdentifier(dbInstanceIdentifier)
                    .build()
            );

            return response.dbInstances().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "RDS instance not found: " + dbInstanceIdentifier
                ));
        });

        return result.fold(
            instance -> instance,
            error -> {
                throw new DeploymentVerificationException(
                    "Failed to describe RDS instance after 3 attempts", error
                );
            }
        );
    }

    /**
     * Verify that the TaskFlow application is responding on a given instance.
     * EC2 health checks can lag instance start by 30-60 seconds.
     * Use CrashRecovery.retry with backoff for startup verification.
     */
    public boolean verifyInstanceHealthy(String instanceId) {
        Result<Boolean, Exception> result = CrashRecovery.retry(5, () -> {
            DescribeInstanceStatusRequest request = DescribeInstanceStatusRequest.builder()
                .instanceIds(instanceId)
                .includeAllInstances(true)
                .build();

            var statuses = ec2Client.describeInstanceStatus(request).instanceStatuses();

            if (statuses.isEmpty()) {
                throw new IllegalStateException("No status found for instance: " + instanceId);
            }

            var status = statuses.getFirst();
            var systemStatus = status.systemStatus().statusAsString();
            var instanceStatus = status.instanceStatus().statusAsString();

            if (!"ok".equals(systemStatus) || !"ok".equals(instanceStatus)) {
                // Not ready yet — throw to trigger retry
                throw new IllegalStateException(
                    "Instance " + instanceId + " not healthy yet: " +
                    "system=" + systemStatus + ", instance=" + instanceStatus
                );
            }

            return true;
        });

        return result.fold(
            healthy -> healthy,
            error -> {
                throw new DeploymentVerificationException(
                    "Instance " + instanceId + " did not become healthy after 5 attempts", error
                );
            }
        );
    }
}
```

The `Result.fold` pattern handles the two cases without null checks or explicit exception propagation. The success case returns the value; the failure case either re-throws a domain exception or returns a default. The calling code does not need to know that retries happened.

For deployment pipelines that need exponential backoff between retries, wrap `CrashRecovery.retry` with a `Thread.sleep` inside the supplier:

```java
// Retry with exponential backoff for rate-limited APIs
private <T> T retryWithBackoff(int maxAttempts, java.util.function.Supplier<T> supplier) {
    int[] attempt = {0};

    Result<T, Exception> result = CrashRecovery.retry(maxAttempts, () -> {
        int currentAttempt = attempt[0]++;
        if (currentAttempt > 0) {
            // Exponential backoff: 1s, 2s, 4s, ...
            long backoffMs = (long) Math.pow(2, currentAttempt - 1) * 1000L;
            Thread.sleep(backoffMs);
        }
        return supplier.get();
    });

    return result.fold(
        value -> value,
        error -> { throw new RuntimeException("All attempts failed", error); }
    );
}
```

**The full deployment pipeline** combining Packer, Terraform, and retry-wrapped verification:

```bash
#!/usr/bin/env bash
# deploy.sh — Full production deployment pipeline
set -euo pipefail

TASKFLOW_VERSION="${1:?Usage: deploy.sh <version>}"

echo "==> Building gold image for version ${TASKFLOW_VERSION}"
cd packer
AMI_ID=$(packer build \
  -var="taskflow_version=${TASKFLOW_VERSION}" \
  -machine-readable taskflow.pkr.hcl \
  | grep "artifact,0,id" \
  | cut -d: -f2)
echo "Built AMI: ${AMI_ID}"
cd ..

echo "==> Decrypting secrets"
DB_PASSWORD=$(sops -d secrets/prod.yaml | python3 -c \
  "import sys,yaml; print(yaml.safe_load(sys.stdin)['db_password'])")

echo "==> Applying Terraform with new AMI"
cd terraform
terraform apply \
  -var="taskflow_ami_id=${AMI_ID}" \
  -var="db_password=${DB_PASSWORD}" \
  -auto-approve
cd ..

echo "==> Verifying deployment"
mvnd exec:java \
  -Dexec.mainClass=io.github.seanchatmangpt.taskflow.deploy.DeploymentVerifier \
  -Dexec.args="${AMI_ID}"

echo "==> Deployment complete: TaskFlow ${TASKFLOW_VERSION}"
```

**Consequences**

`CrashRecovery.retry` makes transient failure handling explicit and testable. The retry count is visible in the code. The `Result<T, Exception>` return type forces callers to handle both success and failure. You cannot accidentally swallow an exception; the compiler requires you to handle the failure case through `fold`.

The limitation is that `CrashRecovery.retry` retries immediately by default. For rate-limited APIs like AWS, you need the backoff wrapper shown above. For the majority of AWS API usage — describe operations, status checks — immediate retry is sufficient because transient errors are not correlated: a `500 Internal Server Error` on attempt one is almost always resolved by attempt two.

The combination of Packer gold images, Terraform infrastructure-as-code, SOPS-encrypted secrets, and retry-wrapped API calls produces a deployment pipeline where every step is repeatable, auditable, and recoverable. The eight-hour recovery incident becomes a ten-minute `deploy.sh` run.

---

## What Have You Learned?

- **Gold images shift configuration left.** Everything that can be pre-installed is pre-installed at image build time. New instances boot in under sixty seconds with no configuration phase that can fail or drift.

- **`packer build` produces an AMI ID.** That ID flows directly into Terraform's `aws_launch_template`. The Packer output is Terraform's input. The pipeline is a pipeline.

- **`terraform import` brings existing infrastructure under IaC control without downtime.** Import, plan, reconcile the configuration to match reality, plan again. The first plan shows the gap between your intent and the actual state — that gap is information.

- **SOPS + Age encrypts secrets at rest in Git.** The encrypted file is safe to commit. The Age private key is the only credential that must be protected. Decryption at deploy time requires one command.

- **`CrashRecovery.retry(3, () -> awsApiCall())`** wraps flaky AWS APIs in an isolation envelope. Transient failures trigger retries automatically. The `Result<T, Exception>` return type forces explicit handling of all-attempts-failed.

- **`Result.fold(success, failure)`** eliminates null checks and exception-propagation boilerplate. The success function returns a value; the failure function either re-throws a domain exception or returns a sentinel. Callers always know which case they are handling.

- **The full deployment pipeline is three commands:** `packer build` to create the gold image, `terraform apply` to update infrastructure, deployment verification to confirm the new instances are healthy. Each step is independently repeatable and independently testable.
