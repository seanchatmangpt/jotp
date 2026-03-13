# Chapter 9: Scale with a Multi-Node Docker Swarm

A single Docker host is a single point of failure. Any maintenance window, hardware fault, or OOM kill takes down every TaskFlow container at once. This chapter provisions three nodes — one Swarm manager and two workers — using Terraform, configures Swarm overlay networking, updates the stack to run three replicas, and automates deployment via GitHub Actions SSH. It also introduces two JOTP patterns that become important at scale: **THE ATOMIC GROUP** shows how `ONE_FOR_ALL` supervision keeps tightly coupled services synchronized across failures, and **THE PARALLEL PIPELINE** shows how `Parallel.all` turns sequential board-loading into concurrent fan-out.

---

## Pattern: THE REPLICATED SERVICE

**Problem**

Running three replicas of a stateless HTTP service is trivial: point a load balancer at three identical containers. Running three replicas of a stateful actor system is harder. Each container has its own `ProcRegistry`. A board coordinator in container A holds board state that container B does not know about. If you route a WebSocket connection to container B, it has no coordinator to talk to.

You need a clear separation between the parts of TaskFlow that can be replicated freely and the parts that must run exactly once, with all replicas knowing how to reach the singleton.

**Context**

TaskFlow's architecture has two tiers:

1. **Stateless HTTP/WebSocket tier**: handles HTTP requests, validates tokens, upgrades WebSocket connections. These containers hold no application state and can be replicated freely.
2. **Coordinator tier**: runs the board supervisor tree, event manager, and persistent state machine. This runs as a single replica (or as a replicated stateful service with shared persistent storage — that complexity is out of scope here).

For this chapter, we run three replicas of the HTTP tier and one replica of the coordinator tier. The HTTP replicas look up the coordinator by name via the gRPC bridge (Chapter 10); for now, we treat the coordinator as a separate service in the stack.

**Solution**

Provision three EC2 instances with Terraform. The manager instance runs the Docker Swarm control plane. The two workers run container workloads.

```hcl
# terraform/main.tf
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.0" }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region"    { default = "us-east-1" }
variable "instance_type" { default = "t3.medium" }
variable "key_name"      {}
variable "vpc_id"        {}
variable "subnet_id"     {}

resource "aws_security_group" "swarm" {
  name   = "taskflow-swarm"
  vpc_id = var.vpc_id

  # Swarm management
  ingress { from_port = 2377; to_port = 2377; protocol = "tcp"; cidr_blocks = ["10.0.0.0/8"] }
  # Overlay network
  ingress { from_port = 4789; to_port = 4789; protocol = "udp"; cidr_blocks = ["10.0.0.0/8"] }
  # Gossip
  ingress { from_port = 7946; to_port = 7946; protocol = "tcp"; cidr_blocks = ["10.0.0.0/8"] }
  ingress { from_port = 7946; to_port = 7946; protocol = "udp"; cidr_blocks = ["10.0.0.0/8"] }
  # Application HTTP
  ingress { from_port = 8080; to_port = 8080; protocol = "tcp"; cidr_blocks = ["0.0.0.0/0"] }
  # SSH for CI deployment
  ingress { from_port = 22; to_port = 22; protocol = "tcp"; cidr_blocks = ["0.0.0.0/0"] }

  egress { from_port = 0; to_port = 0; protocol = "-1"; cidr_blocks = ["0.0.0.0/0"] }
}

resource "aws_instance" "manager" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.swarm.id]

  user_data = templatefile("${path.module}/scripts/manager-init.sh", {
    manager_ip = self.private_ip
  })

  tags = { Name = "taskflow-swarm-manager", Role = "manager" }
}

resource "aws_instance" "worker" {
  count                  = 2
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.swarm.id]

  user_data = templatefile("${path.module}/scripts/worker-init.sh", {
    manager_ip = aws_instance.manager.private_ip
  })

  tags = { Name = "taskflow-swarm-worker-${count.index}", Role = "worker" }
}

output "manager_public_ip" { value = aws_instance.manager.public_ip }
output "worker_ips"        { value = aws_instance.worker[*].private_ip }
```

The manager init script bootstraps the Swarm:

```bash
#!/usr/bin/env bash
# scripts/manager-init.sh
set -euo pipefail

# Install Docker
apt-get update -q
apt-get install -y -q docker.io

# Init Swarm on the manager's private IP
docker swarm init --advertise-addr ${manager_ip}

# Save worker join token to a well-known file (workers will fetch it via SSM)
docker swarm join-token worker -q > /var/lib/swarm-worker-token
```

The worker init script joins the Swarm. In practice you distribute the join token via AWS SSM Parameter Store or a shared mounted secret. For brevity:

```bash
#!/usr/bin/env bash
# scripts/worker-init.sh
set -euo pipefail

apt-get update -q
apt-get install -y -q docker.io

# Retrieve token from SSM (set by manager user_data)
JOIN_TOKEN=$(aws ssm get-parameter --name /taskflow/swarm-join-token \
  --with-decryption --query Parameter.Value --output text)

docker swarm join --token "$JOIN_TOKEN" ${manager_ip}:2377
```

Apply the infrastructure:

```bash
cd terraform
terraform init
terraform apply -var="key_name=taskflow-deploy" -var="vpc_id=vpc-..." -var="subnet_id=subnet-..."
```

Now update `stack.yml` to run three HTTP tier replicas:

```yaml
# stack.yml
version: "3.9"

services:
  taskflow-api:
    image: ghcr.io/yourorg/taskflow-api:${IMAGE_TAG}
    deploy:
      replicas: 3
      update_config:
        parallelism: 1
        delay: 10s
        order: start-first
        failure_action: rollback
      restart_policy:
        condition: on-failure
        delay: 5s
        max_attempts: 3
    ports:
      - "8080:8080"
    secrets:
      - jwt_signing_key
    environment:
      - COORDINATOR_HOST=taskflow-coordinator
      - COORDINATOR_PORT=9090
    networks:
      - taskflow-net

  taskflow-coordinator:
    image: ghcr.io/yourorg/taskflow-coordinator:${IMAGE_TAG}
    deploy:
      replicas: 1
      placement:
        constraints: [node.role == manager]
      restart_policy:
        condition: on-failure
    secrets:
      - db_password
      - jwt_signing_key
    networks:
      - taskflow-net

networks:
  taskflow-net:
    driver: overlay

secrets:
  db_password:     { external: true }
  jwt_signing_key: { external: true }
```

The `update_config` block in `taskflow-api` enables rolling deploys: one replica at a time, start the new container before stopping the old one (`order: start-first`), and roll back automatically if the new container fails health checks.

**Consequences**

Rolling deployments with `order: start-first` and a health check mean TaskFlow stays available during deploys. The tradeoff: you briefly run four replicas (three old plus one new) during each rolling step, so size your cluster to handle the peak. With `t3.medium` instances you have 2 vCPU and 4 GB per node, which comfortably holds two API replicas each.

The manager-only placement constraint on `taskflow-coordinator` is a simplification. A production setup would use a dedicated data node with persistent volume mounts. Chapter 10's distributed actor pattern removes this constraint entirely.

---

## Pattern: THE ATOMIC GROUP (ONE_FOR_ALL)

**Problem**

The board coordinator and the event manager are not independent. Every state change in the coordinator fires an event on the event manager. If the event manager crashes and restarts without the coordinator restarting, the coordinator holds a stale reference to the dead event manager process. Messages sent to that stale reference are silently dropped. The board state diverges from the event stream.

You need the two processes to restart together, atomically, so that the coordinator always holds a valid event manager reference.

**Context**

`BoardCoordinator` and `EventManager` share a dependency: when `BoardCoordinator` is created, it receives an `EventManager` ref at construction time. The two processes are created by the same `Supervisor`. If either one fails, the shared state assumption is violated.

**Solution**

Use `ONE_FOR_ALL` restart strategy. When any child under a `ONE_FOR_ALL` supervisor crashes, all children are stopped and restarted in order. The coordinator is started first (to establish its internal state), then the event manager is started with a reference to the coordinator, and both are re-registered in `ProcRegistry`.

```java
// CoordinatorSupervisor.java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.Supervisor;

public class CoordinatorSupervisor {

    public static Supervisor build() {
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ALL,
            5,
            Duration.ofMinutes(1)
        );

        // supervise() returns ProcRef<S,M>; register the underlying Proc by name
        ProcRef<BoardState, BoardMessage> coordinatorRef =
            supervisor.supervise("board-coordinator", BoardState.empty(), BoardCoordinator::handle);
        ProcRef<EventState, EventMessage> eventRef =
            supervisor.supervise("event-manager", EventState.empty(), EventManager::handle);

        ProcRegistry.register("board-coordinator", coordinatorRef.proc());
        ProcRegistry.register("event-manager", eventRef.proc());

        return supervisor;
    }
}
```

After each supervisor restart, the supervisor creates a new `Proc` for each child and returns it via `supervise()`. Re-registering the new `proc()` in `ProcRegistry` ensures callers that call `whereis` after the restart receive the current process. Callers should always call `whereis` at the time they need to send a message rather than caching the result.

To verify the atomic behavior, write a property test:

```java
// CoordinatorAtomicRestartIT.java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.Supervisor;

class CoordinatorAtomicRestartIT implements WithAssertions {

    @Test
    void when_event_manager_crashes_both_restart_together() throws Exception {
        ProcRegistry.reset();
        var supervisor = CoordinatorSupervisor.build();

        Proc<?, ?> originalCoordinator =
            ProcRegistry.whereis("board-coordinator").orElseThrow();
        Proc<?, ?> originalEventMgr =
            ProcRegistry.whereis("event-manager").orElseThrow();

        // Crash only the event manager
        originalEventMgr.tell(new PoisonPill());

        // Both should restart (ONE_FOR_ALL restarts all children)
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Proc<?, ?> newCoordinator = ProcRegistry.whereis("board-coordinator").orElseThrow();
            Proc<?, ?> newEventMgr    = ProcRegistry.whereis("event-manager").orElseThrow();

            assertThat(newCoordinator).isNotSameAs(originalCoordinator);
            assertThat(newEventMgr).isNotSameAs(originalEventMgr);
        });

        // After restart, a message to the coordinator still works
        Proc<BoardState, BoardMessage> coord =
            ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator").orElseThrow();

        BoardState state = coord
            .ask(new GetBoardSummaryMessage("board-1"), Duration.ofSeconds(2))
            .get();

        assertThat(state).isNotNull();
    }
}
```

**Consequences**

`ONE_FOR_ALL` makes the supervision contract explicit in code. Anyone reading `CoordinatorSupervisor` immediately understands that `board-coordinator` and `event-manager` are a coupled unit. Compare this to the alternative: ad-hoc crash handlers scattered through the codebase that attempt to maintain consistency by carefully restarting processes in the right order when they notice something is wrong. That approach fails under concurrent failures.

The downside: a crash in one service takes down a healthy sibling. If `event-manager` has a bug that causes frequent restarts and `board-coordinator` is healthy, you are restarting both. The restart budget (`maxRestarts = 5` in a 1-minute window) must accommodate this: a rapidly crashing event manager will exhaust the budget five times faster than it would under `ONE_FOR_ONE`. Set the budget conservatively and alert on supervisor exhaustion as a high-priority operational signal.

---

## Pattern: THE PARALLEL PIPELINE (Parallel)

**Problem**

The TaskFlow dashboard loads the summary of every board the user has access to. A user with access to forty boards triggers forty calls to the board coordinator — one per board. Each call involves a message round-trip and a database read. Done sequentially, forty boards at 30ms each is 1.2 seconds of latency before the dashboard renders. That is unacceptable.

You could use a thread pool and `Future.get()`. But managing thread pools manually means choosing a pool size, handling rejection policies, and writing cleanup code. You want structured fan-out that scales to the available parallelism, fails fast if any sub-task throws, and returns when all results are ready.

**Context**

The dashboard endpoint receives a `List<String> boardIds` of up to 100 IDs. Each ID requires one `ask()` to the board coordinator. The `ask()` calls are independent — there is no ordering requirement, no shared state, and no result depends on another result.

**Solution**

`Parallel.all` runs a list of `Supplier` tasks concurrently on virtual threads using structured concurrency. All tasks start together. The call returns `Result<List<T>, Exception>`: either all results in fork order, or the first exception that caused any task to fail.

```java
// DashboardService.java
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.Result;

@Service
public class DashboardService {

    public DashboardView loadDashboard(List<String> boardIds) {
        Proc<BoardState, BoardMessage> coordinator =
            ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator")
                .orElseThrow(() -> new IllegalStateException("board-coordinator not available"));

        // Build one Supplier per board ID; Parallel.all runs them concurrently
        List<Supplier<BoardState>> tasks = boardIds.stream()
            .<Supplier<BoardState>>map(id -> () -> {
                try {
                    return coordinator
                        .ask(new GetBoardSummaryMessage(id), Duration.ofSeconds(2))
                        .get();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load board " + id, e);
                }
            })
            .toList();

        Result<List<BoardState>, Exception> result = Parallel.all(tasks);

        return switch (result) {
            case Result.Success<List<BoardState>, Exception>(var states) ->
                DashboardView.of(states);
            case Result.Failure<List<BoardState>, Exception>(var ex) ->
                throw new RuntimeException("Dashboard load failed", ex);
        };
    }
}
```

Under the covers, `Parallel.all` opens a `StructuredTaskScope` with `awaitAllSuccessfulOrThrow` joiner semantics, submits one virtual-thread subtask per supplier, waits for all to complete, and either returns `Result.success(List)` with results in fork order or `Result.failure(Exception)` with the first failure. This is Java 26 structured concurrency in its intended form: forked tasks that live and die within the scope of a single method call.

The latency for forty boards at 30ms each in parallel is approximately 30ms plus the overhead of virtual thread scheduling — typically 1-5ms. That is a 40x improvement over sequential execution.

For the benchmark-minded, here is a property test that verifies parallelism actually occurs:

```java
// ParallelPipelineTest.java
import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.Result;

class ParallelPipelineTest implements WithAssertions {

    @Property
    void parallel_all_completes_faster_than_sequential(
        @ForAll @Size(min = 5, max = 20) List<@AlphaChars String> ids
    ) throws Exception {
        // Simulate a 50ms-per-board coordinator
        java.util.function.Function<String, String> slowFetch = id -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "summary:" + id;
        };

        long parallelMs = timedRun(() -> {
            List<Supplier<String>> tasks = ids.stream()
                .<Supplier<String>>map(id -> () -> slowFetch.apply(id))
                .toList();
            return Parallel.all(tasks);
        });
        long seqMs = timedRun(() -> ids.stream().map(slowFetch).toList());

        // Parallel should be at least 3x faster for 5+ items
        assertThat(parallelMs).isLessThan(seqMs / 3);
    }

    private long timedRun(java.util.concurrent.Callable<?> task) throws Exception {
        long start = System.currentTimeMillis();
        task.call();
        return System.currentTimeMillis() - start;
    }
}
```

**Consequences**

`Parallel.all` is appropriate when all items can be processed independently. It is wrong when items share mutable state, when the result of one item feeds into the processing of another, or when you need streaming results rather than waiting for all items. For those cases, use sequential streams, `CompletableFuture` pipelines, or a `SubmissionPublisher`.

Also consider backpressure: if the board coordinator is itself the bottleneck, fan-out from every HTTP request amplifies the load. Add a semaphore or rate limiter at the dashboard service level if the coordinator cannot handle concurrent ask bursts:

```java
private final Semaphore dashboardSemaphore = new Semaphore(10);

public DashboardView loadDashboard(List<String> boardIds) throws InterruptedException {
    if (!dashboardSemaphore.tryAcquire(1, TimeUnit.SECONDS)) {
        throw new ServiceUnavailableException("Dashboard service overloaded; try again shortly");
    }
    try {
        return loadDashboardInternal(boardIds);
    } finally {
        dashboardSemaphore.release();
    }
}
```

---

## Nginx and Sticky Sessions

WebSocket connections require sticky sessions when running multiple API replicas. A WebSocket connection is a persistent, stateful connection. If a client's HTTP upgrade request routes to replica A but subsequent WebSocket frames route to replica B, the connection is broken.

Nginx handles this with `ip_hash` upstream balancing:

```nginx
upstream taskflow_api {
    ip_hash;  # Sticky: same client IP always routes to same upstream
    server taskflow-api-1:8080;
    server taskflow-api-2:8080;
    server taskflow-api-3:8080;
}

server {
    listen 80;

    location / {
        proxy_pass         http://taskflow_api;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_set_header   Host       $host;
        proxy_read_timeout 3600s;
    }
}
```

In Docker Swarm, deploy Nginx as a service on the manager node. The `taskflow-api` replicas are accessible via Swarm's overlay network using the service name as DNS.

---

## What Have You Learned?

- **Three Terraform-managed EC2 instances** (one manager, two workers) form a production-capable Swarm cluster. Swarm distributes service replicas across workers and handles restarts on node failure.
- **`replicas: 3` in `stack.yml`** distributes the stateless API tier across all worker nodes. `update_config` with `order: start-first` enables zero-downtime rolling deploys.
- **`ONE_FOR_ALL`** is the right restart strategy when two processes share a dependency established at construction time. Both restart together, keeping their shared reference consistent.
- **`Parallel.all(tasks)`** fans out independent work across virtual threads using structured concurrency. Forty 30ms operations complete in ~30ms, not 1.2 seconds. The method takes `List<Supplier<T>>` and returns `Result<List<T>, Exception>`.
- **Nginx `ip_hash`** provides sticky sessions for WebSocket connections. Without stickiness, WebSocket frame routing becomes undefined across replicas.
- **Re-register `proc()` in `ProcRegistry` after every supervisor restart** to ensure callers always find the current `Proc` instance via `ProcRegistry.whereis()`.
