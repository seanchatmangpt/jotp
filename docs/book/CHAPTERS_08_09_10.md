# Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP

---

# Chapter 8: Continuous Deployment and Repository Secrets

By the end of Chapter 7, TaskFlow ran on a single Docker host. You could push code, build an image, and deploy by typing commands at a terminal. That was fine for a prototype. It is not fine for anything a real team depends on. In this chapter we automate the entire path from a merged pull request to a running update in production, and we do it without ever storing a plaintext password in a GitHub Actions environment variable.

We will introduce three patterns. **THE SECRET ENVELOPE** describes how to encrypt secrets at rest using SOPS and Age so that only the right key can decrypt them. **THE DEPLOY SCRIPT** captures everything needed to safely update a running stack into a single repeatable command. **THE SERVICE REGISTRY** shows how `ProcRegistry` replaces hardcoded process addresses, making the deployment topology visible and changeable without touching application code.

---

## Pattern: THE SECRET ENVELOPE (SOPS + Age)

**Problem**

Your application needs a database password, a signing key for JWT tokens, and an API key for the notification service. You need GitHub Actions to access those values at deploy time. The obvious approach — storing them as GitHub Actions secrets and injecting them as environment variables — works but has a subtle flaw: anyone with write access to the repository can create a workflow that prints every secret to the build log. GitHub Actions secrets are protected from accidental exposure, not from intentional abuse. More importantly, the secret values live only in GitHub's vault. They are not version-controlled alongside the infrastructure that needs them, and they drift the moment someone changes the database password without updating the vault.

You need secrets that are encrypted in the repository, decryptable only by a specific private key, and automatically injected into the deployment pipeline without ever appearing in plaintext on disk or in logs.

**Context**

TaskFlow runs on Docker Swarm. It needs four secrets at runtime: `db_password`, `jwt_signing_key`, `notification_api_key`, and `sops_age_key` (used only during deployment, never mounted into containers). The repository is public on GitHub. Developers commit to feature branches, open pull requests, and merge to `main`. Every merge to `main` should trigger a deployment.

**Solution**

Use Mozilla SOPS (Secrets OPerationS) with Age asymmetric encryption to encrypt a `secrets.yaml` file that lives in the repository. The encrypted file is safe to commit. The private Age key lives in GitHub Actions as a single repository secret named `SOPS_AGE_KEY`. During deployment, the workflow decrypts the file, creates Docker secrets from the values, and discards the plaintext. Nothing else in the repository is sensitive.

Start by generating an Age key pair on your workstation:

```bash
age-keygen -o key.txt
# Public key: age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p
```

The public key is safe to share. Add it to your project documentation. The private key in `key.txt` goes into GitHub Actions as `SOPS_AGE_KEY` — that is the only secret you store in GitHub.

Create `secrets.yaml` with your plaintext values:

```yaml
db_password: "s3cur3-taskflow-db-pass"
jwt_signing_key: "HS512-production-signing-key-rotate-quarterly"
notification_api_key: "ntfy-prod-00xA9qlmZ2wT"
```

Encrypt the file using your Age public key:

```bash
sops --encrypt \
  --age age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p \
  --encrypted-regex '^(db_password|jwt_signing_key|notification_api_key)$' \
  secrets.yaml > secrets.enc.yaml
```

The resulting `secrets.enc.yaml` looks like this:

```yaml
db_password: ENC[AES256_GCM,data:G3kL...==,type:str]
jwt_signing_key: ENC[AES256_GCM,data:mPqR...==,type:str]
notification_api_key: ENC[AES256_GCM,data:nT2x...==,type:str]
sops:
    age:
        - recipient: age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p
          enc: |
              -----BEGIN AGE ENCRYPTED FILE-----
              ...
              -----END AGE ENCRYPTED FILE-----
    version: 3.8.1
```

Commit `secrets.enc.yaml`. Delete `secrets.yaml`. Add `secrets.yaml` to `.gitignore`. The plaintext never enters the repository.

Docker Swarm's secret system completes the chain. Secrets are distributed to worker nodes over the encrypted Swarm control plane, mounted into containers as read-only files under `/run/secrets/`, and never written to environment variables or the container filesystem layer. Your application reads them like regular files:

```java
// In your application bootstrap
private static String readDockerSecret(String name) throws IOException {
    Path secretPath = Path.of("/run/secrets/" + name);
    return Files.readString(secretPath).strip();
}
```

**Code Example**

The workflow step that decrypts and creates Docker secrets:

```yaml
# .github/workflows/deploy.yml (partial)
- name: Decrypt secrets
  env:
    SOPS_AGE_KEY: ${{ secrets.SOPS_AGE_KEY }}
  run: |
    sops --decrypt secrets.enc.yaml > secrets.plain.yaml

- name: Create Docker secrets
  run: |
    # Read values from decrypted file
    DB_PASS=$(yq '.db_password' secrets.plain.yaml)
    JWT_KEY=$(yq '.jwt_signing_key' secrets.plain.yaml)
    NOTIF_KEY=$(yq '.notification_api_key' secrets.plain.yaml)

    # Recreate secrets (Swarm secrets are immutable; delete then create)
    docker secret rm db_password jwt_signing_key notification_api_key 2>/dev/null || true
    printf '%s' "$DB_PASS"   | docker secret create db_password -
    printf '%s' "$JWT_KEY"   | docker secret create jwt_signing_key -
    printf '%s' "$NOTIF_KEY" | docker secret create notification_api_key -

    # Wipe plaintext immediately
    rm secrets.plain.yaml
    unset DB_PASS JWT_KEY NOTIF_KEY
```

In `stack.yml`, mount the secrets into the service:

```yaml
services:
  taskflow:
    image: ghcr.io/yourorg/taskflow:${IMAGE_TAG}
    secrets:
      - db_password
      - jwt_signing_key
      - notification_api_key
    environment:
      - SPRING_DATASOURCE_PASSWORD_FILE=/run/secrets/db_password

secrets:
  db_password:
    external: true
  jwt_signing_key:
    external: true
  notification_api_key:
    external: true
```

**Consequences**

The positive consequences are substantial. Secrets rotate by re-encrypting `secrets.enc.yaml` and re-running the deployment — no GitHub UI interaction needed. The diff in a secret rotation pull request shows the encrypted blob changing, which is auditable without being readable. Any developer can verify that a secret exists and has been rotated by looking at the commit history.

The tradeoff is operational ceremony. Losing the Age private key means you cannot decrypt the secrets. Store the private key in a password manager with at least two team members holding copies. Also, Docker Swarm treats secrets as immutable; you must delete and recreate a secret to change its value, which briefly removes it from the Swarm before the new version is distributed. Plan for a rolling update that creates the new secret, updates the service reference, and removes the old one — the example above handles this with `rm` before `create`.

---

## Pattern: THE DEPLOY SCRIPT (./dx.sh deploy)

**Problem**

Deployment is not a single command. It is a sequence: pull the new image, verify the pull succeeded, update the stack configuration, wait for the health check to pass, and report success or rollback if health fails. When you type these steps at a terminal you will eventually skip one under pressure. When you encode them as inline shell in a GitHub Actions `run:` block you get a 40-line YAML string that nobody reads carefully and nobody tests locally.

You need a script that encodes the full deployment procedure, is executable on a local workstation for manual deployments, and is also the exact artifact that the CI pipeline invokes. One script, one path, no divergence.

**Context**

TaskFlow deploys to a Docker Swarm manager node. The CI pipeline runs on a GitHub-hosted runner that connects to the manager via SSH. The manager is the only node with authority to update the stack. Image tags follow the pattern `ghcr.io/yourorg/taskflow:<git-sha>`.

**Solution**

Create `deploy.sh` at the repository root. The `./dx.sh deploy` entrypoint delegates to it, passing the image tag as an argument. The script encapsulates the deployment logic, including rollback on failure.

```bash
#!/usr/bin/env bash
# deploy.sh — deploy or update the TaskFlow stack on Docker Swarm
set -euo pipefail

IMAGE_TAG="${1:?Usage: deploy.sh <image-tag>}"
STACK_NAME="taskflow"
DEPLOY_TIMEOUT=120  # seconds to wait for convergence
SERVICE_NAME="${STACK_NAME}_taskflow"

log() { echo "[$(date -u +%T)] $*"; }

# 1. Pull the image on all nodes (Swarm will also do this, but pulling first
#    lets us fail fast before touching the running service)
log "Pulling image ghcr.io/yourorg/taskflow:${IMAGE_TAG}"
docker pull "ghcr.io/yourorg/taskflow:${IMAGE_TAG}"

# 2. Export the tag so docker stack deploy picks it up from stack.yml
export IMAGE_TAG

# 3. Deploy (or update) the stack
log "Deploying stack ${STACK_NAME}"
docker stack deploy \
  --with-registry-auth \
  --compose-file stack.yml \
  "${STACK_NAME}"

# 4. Wait for convergence
log "Waiting up to ${DEPLOY_TIMEOUT}s for service convergence"
ELAPSED=0
while [[ $ELAPSED -lt $DEPLOY_TIMEOUT ]]; do
  DESIRED=$(docker service inspect "${SERVICE_NAME}" \
    --format '{{.Spec.Mode.Replicated.Replicas}}')
  RUNNING=$(docker service ps "${SERVICE_NAME}" \
    --filter desired-state=running \
    --format '{{.CurrentState}}' \
    | grep -c "Running")

  if [[ "$RUNNING" -eq "$DESIRED" ]]; then
    log "All ${DESIRED} replicas running. Deployment complete."
    exit 0
  fi

  sleep 5
  ELAPSED=$((ELAPSED + 5))
  log "  ${RUNNING}/${DESIRED} replicas running (${ELAPSED}s elapsed)"
done

# 5. Timeout — roll back and fail
log "ERROR: Convergence timeout. Rolling back."
docker service rollback "${SERVICE_NAME}"
exit 1
```

Wire this into `dx.sh`:

```bash
# dx.sh (partial)
case "${1:-}" in
  deploy)
    shift
    bash deploy.sh "$@"
    ;;
  # ... other commands
esac
```

The full GitHub Actions workflow now becomes readable:

```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [main]

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: yourorg/taskflow

jobs:
  build-and-push:
    runs-on: ubuntu-latest
    outputs:
      image-tag: ${{ steps.meta.outputs.version }}
    steps:
      - uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract metadata
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}
          tags: type=sha,prefix=

      - name: Build and push
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: ${{ steps.meta.outputs.tags }}
          cache-from: type=gha
          cache-to: type=gha,mode=max

  deploy:
    runs-on: ubuntu-latest
    needs: build-and-push
    environment: production
    steps:
      - uses: actions/checkout@v4

      - name: Install SOPS and Age
        run: |
          curl -fsSL https://github.com/getsops/sops/releases/latest/download/sops-v3.8.1.linux.amd64 \
            -o /usr/local/bin/sops && chmod +x /usr/local/bin/sops
          curl -fsSL https://github.com/FiloSottile/age/releases/latest/download/age-v1.1.1-linux-amd64.tar.gz \
            | tar -xz --strip-components=1 -C /usr/local/bin

      - name: Decrypt and create Docker secrets
        env:
          SOPS_AGE_KEY: ${{ secrets.SOPS_AGE_KEY }}
        run: |
          sops --decrypt secrets.enc.yaml > secrets.plain.yaml
          DB_PASS=$(yq '.db_password' secrets.plain.yaml)
          JWT_KEY=$(yq '.jwt_signing_key' secrets.plain.yaml)
          NOTIF_KEY=$(yq '.notification_api_key' secrets.plain.yaml)
          docker secret rm db_password jwt_signing_key notification_api_key 2>/dev/null || true
          printf '%s' "$DB_PASS"   | docker secret create db_password -
          printf '%s' "$JWT_KEY"   | docker secret create jwt_signing_key -
          printf '%s' "$NOTIF_KEY" | docker secret create notification_api_key -
          rm secrets.plain.yaml

      - name: Deploy via SSH
        uses: appleboy/ssh-action@v1.0.0
        with:
          host: ${{ secrets.SWARM_MANAGER_HOST }}
          username: deploy
          key: ${{ secrets.SWARM_DEPLOY_SSH_KEY }}
          script: |
            cd /opt/taskflow
            git pull origin main
            ./dx.sh deploy ${{ needs.build-and-push.outputs.image-tag }}
```

**Consequences**

`deploy.sh` is testable. Point it at a staging stack with a staging image tag and it behaves identically to production. The rollback logic runs in both environments. The script is also the documentation of what a deployment does — reading 50 lines of bash is faster than reading a 30-slide operations runbook.

The main limitation is that `deploy.sh` uses polling to detect convergence. Docker Swarm's service convergence model does not provide an event stream that a script can listen to, so polling is the right approach. Set `DEPLOY_TIMEOUT` conservatively — 120 seconds is usually enough for three replicas on healthy nodes, but cold starts after a long idle period may need 180.

---

## Pattern: THE SERVICE REGISTRY (ProcRegistry)

**Problem**

When TaskFlow runs across multiple containers, one container needs to send a message to a `Proc` running in another. With a single JVM you pass `ProcRef` objects directly. Across containers you have no shared object reference. The typical workaround is to hardcode service addresses — "the board coordinator is always at port 9090 on container `taskflow-coordinator`." Hardcoded addresses break the moment you scale, move services between nodes, or add a second coordinator for load balancing.

You need a name-based lookup that decouples the caller from the network location of the target.

**Context**

TaskFlow has two logical processes that need to be discoverable by name: `board-coordinator` (the root supervisor managing all active boards) and `event-manager` (the pub-sub bus for board events). Within a single JVM, process handles are passed directly. Across JVM boundaries (covered in depth in Chapter 10), the name is the stable identifier.

**Solution**

`ProcRegistry` is JOTP's in-process name server. It maps string names to `ProcRef` instances. Any code that creates a `Proc` can register it; any code that needs to talk to it can look it up.

```java
// In BoardCoordinatorService.java (startup)
import io.github.seanchatmangpt.jotp.registry.ProcRegistry;

public class BoardCoordinatorService {

    public static BoardCoordinatorService start(ProcRegistry registry) {
        var supervisor = Supervisor.builder()
            .strategy(RestartStrategy.ONE_FOR_ALL)
            .maxRestarts(5)
            .withinWindow(Duration.ofMinutes(1))
            .supervise("board-coordinator", BoardCoordinator::new, BoardState.empty())
            .supervise("event-manager",     EventManager::new,     EventState.empty())
            .build();

        // Retrieve the refs that the supervisor created internally
        ProcRef<BoardMessage> coordinatorRef = supervisor.childRef("board-coordinator");
        ProcRef<EventMessage> eventRef       = supervisor.childRef("event-manager");

        // Register by stable name
        registry.register("board-coordinator", coordinatorRef);
        registry.register("event-manager", eventRef);

        return new BoardCoordinatorService(supervisor, coordinatorRef, eventRef);
    }
}
```

Any other component — an HTTP controller, a WebSocket handler, a background metrics reporter — uses `whereis` to find the ref:

```java
// In BoardWebSocketHandler.java
import io.github.seanchatmangpt.jotp.registry.ProcRegistry;

@Component
public class BoardWebSocketHandler {

    private final ProcRegistry registry;

    public BoardWebSocketHandler(ProcRegistry registry) {
        this.registry = registry;
    }

    public void onCardMoved(CardMovedEvent event) {
        registry.whereis("board-coordinator")
            .ifPresentOrElse(
                ref -> ref.tell(new MoveCardMessage(event.cardId(), event.toColumnId())),
                ()  -> log.error("board-coordinator not registered; dropping message")
            );
    }
}
```

`whereis` returns `Optional<ProcRef>`. The empty case is not an error you ignore — it means the supervisor tree has not started yet or has exceeded its restart budget and given up. Handle it explicitly.

Register the registry as a Spring bean so that every component gets the same instance:

```java
@Configuration
public class JotpConfiguration {

    @Bean
    public ProcRegistry procRegistry() {
        return ProcRegistry.create();
    }

    @Bean
    public BoardCoordinatorService boardCoordinatorService(ProcRegistry registry) {
        return BoardCoordinatorService.start(registry);
    }
}
```

**Code Example**

A full integration test verifying the registry survives a coordinator restart:

```java
// BoardCoordinatorRegistryIT.java
class BoardCoordinatorRegistryIT implements WithAssertions {

    @Test
    void registry_returns_new_ref_after_supervisor_restart() throws Exception {
        var registry = ProcRegistry.create();
        var service  = BoardCoordinatorService.start(registry);

        ProcRef<BoardMessage> original = registry.whereis("board-coordinator").orElseThrow();

        // Force the coordinator to crash
        original.tell(new PoisonPill());

        // The supervisor restarts it; wait for re-registration
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(registry.whereis("board-coordinator")).isPresent()
        );

        ProcRef<BoardMessage> restarted = registry.whereis("board-coordinator").orElseThrow();

        // It should be a different ref (new process)
        assertThat(restarted).isNotSameAs(original);

        // But it should still accept messages
        CompletableFuture<BoardSummary> result =
            restarted.ask(new GetBoardSummaryMessage("board-1"), Duration.ofSeconds(1));
        assertThat(result.get()).isNotNull();
    }
}
```

**Consequences**

`ProcRegistry` makes the process topology of your application explicit and testable. The integration test above would be impossible if you passed `ProcRef` instances as constructor arguments — you would have to rebuild the entire object graph to test restart behavior. With the registry, you test the full lifecycle.

The tradeoff: `ProcRegistry` is per-JVM. It does not know about processes in other containers. That limitation is intentional — distributed lookup requires distributed coordination, and Chapter 10 covers the gRPC bridge that extends `ProcRegistry` semantics across JVM boundaries. For now, register local processes by name and accept that the registry is the local phone book, not the global DNS.

---

## What Have You Learned?

- **Plaintext secrets in CI environment variables expose you to insider abuse.** SOPS + Age keeps secrets encrypted in the repository; only the Age private key (stored as a single GitHub Actions secret) can decrypt them.
- **`age-keygen` produces a key pair; the public key encrypts, the private key decrypts.** Only the private key is sensitive.
- **Docker Swarm secrets are distributed over the encrypted control plane and mounted as read-only files.** Applications read them from `/run/secrets/<name>`, never from environment variables.
- **`deploy.sh` encodes the full deployment procedure** — pull, deploy, wait for convergence, rollback on timeout — so that the CI pipeline and manual deployments follow identical steps.
- **`./dx.sh deploy <tag>` is the single interface** to deployment, callable from a developer workstation or a GitHub Actions runner without modification.
- **`ProcRegistry.register("board-coordinator", ref)` names a process.** `whereis("board-coordinator")` looks it up. The `Optional` return forces you to handle the case where the process is not yet running.
- **Registries survive restarts.** When a supervisor recreates a child process, re-registration updates the registry entry. Callers that call `whereis` after the restart get the new ref.

---

# Chapter 9: Scale with a Multi-Node Docker Swarm

A single Docker host is a single point of failure. Any maintenance window, hardware fault, or OOM kill takes down every TaskFlow container at once. This chapter provisions three nodes — one Swarm manager and two workers — using Terraform, configures Swarm overlay networking, updates the stack to run three replicas, and automates deployment via GitHub Actions SSH. It also introduces two JOTP patterns that become important at scale: **THE ATOMIC GROUP** shows how `ONE_FOR_ALL` supervision keeps tightly coupled services synchronized across failures, and **THE PARALLEL PIPELINE** shows how `Parallel.map` turns sequential board-loading into concurrent fan-out.

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
public class CoordinatorSupervisor {

    private final ProcRegistry registry;

    public CoordinatorSupervisor(ProcRegistry registry) {
        this.registry = registry;
    }

    public Supervisor build() {
        return Supervisor.builder()
            .strategy(RestartStrategy.ONE_FOR_ALL)
            .maxRestarts(5)
            .withinWindow(Duration.ofMinutes(1))
            .onChildStart((name, ref) -> registry.register(name, ref))
            .supervise(
                "board-coordinator",
                (eventMgrRef) -> new BoardCoordinator(eventMgrRef),
                BoardState.empty()
            )
            .supervise(
                "event-manager",
                () -> new EventManager(),
                EventState.empty()
            )
            .build();
    }
}
```

The `onChildStart` callback re-registers each child in the `ProcRegistry` every time a restart occurs. Callers that previously cached the old ref will find the new ref on their next `whereis` call.

To verify the atomic behavior, write a property test:

```java
// CoordinatorAtomicRestartIT.java
class CoordinatorAtomicRestartIT implements WithAssertions {

    @Test
    void when_event_manager_crashes_both_restart_together() throws Exception {
        var registry    = ProcRegistry.create();
        var supervisor  = new CoordinatorSupervisor(registry).build();

        ProcRef<?> originalCoordinator = registry.whereis("board-coordinator").orElseThrow();
        ProcRef<?> originalEventMgr    = registry.whereis("event-manager").orElseThrow();

        // Crash only the event manager
        originalEventMgr.tell(new PoisonPill());

        // Both should restart
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ProcRef<?> newCoordinator = registry.whereis("board-coordinator").orElseThrow();
            ProcRef<?> newEventMgr    = registry.whereis("event-manager").orElseThrow();

            assertThat(newCoordinator).isNotSameAs(originalCoordinator);
            assertThat(newEventMgr).isNotSameAs(originalEventMgr);
        });

        // After restart, a message to the coordinator still works
        ProcRef<BoardMessage> coord = (ProcRef<BoardMessage>)
            registry.whereis("board-coordinator").orElseThrow();

        BoardSummary summary = coord
            .ask(new GetBoardSummaryMessage("board-1"), Duration.ofSeconds(2))
            .get();

        assertThat(summary).isNotNull();
    }
}
```

**Consequences**

`ONE_FOR_ALL` makes the supervision contract explicit in code. Anyone reading `CoordinatorSupervisor` immediately understands that `board-coordinator` and `event-manager` are a coupled unit. Compare this to the alternative: ad-hoc crash handlers scattered through the codebase that attempt to maintain consistency by carefully restarting processes in the right order when they notice something is wrong. That approach fails under concurrent failures.

The downside: a crash in one service takes down a healthy sibling. If `event-manager` has a bug that causes frequent restarts and `board-coordinator` is healthy, you are restarting both. The restart budget (`maxRestarts(5)` in a 1-minute window) must accommodate this: a rapidly crashing event manager will exhaust the budget five times faster than it would under `ONE_FOR_ONE`. Set the budget conservatively and alert on supervisor exhaustion as a high-priority operational signal.

---

## Pattern: THE PARALLEL PIPELINE (Parallel)

**Problem**

The TaskFlow dashboard loads the summary of every board the user has access to. A user with access to forty boards triggers forty calls to the board coordinator — one per board. Each call involves a message round-trip and a database read. Done sequentially, forty boards at 30ms each is 1.2 seconds of latency before the dashboard renders. That is unacceptable.

You could use a thread pool and `Future.get()`. But managing thread pools manually means choosing a pool size, handling rejection policies, and writing cleanup code. You want structured fan-out that scales to the available parallelism, fails fast if any sub-task throws, and returns when all results are ready.

**Context**

The dashboard endpoint receives a `List<String> boardIds` of up to 100 IDs. Each ID requires one `ask()` to the board coordinator. The `ask()` calls are independent — there is no ordering requirement, no shared state, and no result depends on another result.

**Solution**

`Parallel.map` applies a function to every element of a list using structured concurrency. All invocations start together and run concurrently on virtual threads. The call blocks until all complete (or any one throws).

```java
// DashboardService.java
import io.github.seanchatmangpt.jotp.parallel.Parallel;

@Service
public class DashboardService {

    private final ProcRegistry registry;

    public DashboardService(ProcRegistry registry) {
        this.registry = registry;
    }

    public DashboardView loadDashboard(List<String> boardIds) {
        ProcRef<BoardMessage> coordinator = registry
            .whereis("board-coordinator")
            .orElseThrow(() -> new IllegalStateException("board-coordinator not available"));

        // Fan-out: all asks start concurrently
        List<BoardSummary> summaries = Parallel.map(boardIds, id ->
            coordinator
                .ask(new GetBoardSummaryMessage(id), Duration.ofSeconds(2))
                .get()
        );

        return DashboardView.of(summaries);
    }
}
```

Under the covers, `Parallel.map` opens a `StructuredTaskScope.ShutdownOnFailure` scope, submits one virtual-thread subtask per list element, waits for all to complete, and either returns the collected results or re-throws the first exception. This is Java 26 structured concurrency in its intended form: forked tasks that live and die within the scope of a single method call.

The latency for forty boards at 30ms each in parallel is approximately 30ms plus the overhead of virtual thread scheduling — typically 1-5ms. That is a 40x improvement over sequential execution.

For the benchmark-minded, here is a property test that verifies parallelism actually occurs:

```java
// ParallelPipelineTest.java
class ParallelPipelineTest implements WithAssertions {

    @Property
    void parallel_map_completes_faster_than_sequential(
        @ForAll @Size(min = 5, max = 20) List<@AlphaChars String> ids
    ) throws Exception {
        // Simulate a 50ms-per-board coordinator
        java.util.function.Function<String, String> slowFetch = id -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "summary:" + id;
        };

        long parallelMs = timedRun(() -> Parallel.map(ids, slowFetch::apply));
        long seqMs      = timedRun(() -> ids.stream().map(slowFetch).toList());

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

`Parallel.map` is appropriate when all items can be processed independently. It is wrong when items share mutable state, when the result of one item feeds into the processing of another, or when you need streaming results rather than waiting for all items. For those cases, use sequential streams, `CompletableFuture` pipelines, or a `SubmissionPublisher`.

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
- **`Parallel.map(boardIds, id -> fetchBoard(id))`** fans out independent work across virtual threads using structured concurrency. Forty 30ms operations complete in ~30ms, not 1.2 seconds.
- **Nginx `ip_hash`** provides sticky sessions for WebSocket connections. Without stickiness, WebSocket frame routing becomes undefined across replicas.
- **The `onChildStart` callback on `Supervisor`** re-registers children in `ProcRegistry` after every restart, ensuring that callers always find the current ref via `whereis`.

---

# Chapter 10: Distributed JOTP

Everything in the previous two chapters ran within a single JVM. `ProcRef` objects are local handles — they hold a reference to a virtual thread queue inside one JVM process. Serialize a `ProcRef` to bytes, send those bytes to a different JVM, and you have nothing. The remote JVM cannot reach into the first JVM's memory to find the queue.

This is not a bug. It is a design boundary. Within a JVM, `ProcRef` gives you near-zero-overhead message passing. Crossing JVM boundaries requires a transport layer with its own serialization, error handling, and latency profile. The goal of distributed JOTP is to keep the interface identical — callers still call `tell` and `ask` — while the implementation below the interface transparently crosses the JVM boundary.

This chapter introduces three patterns. **THE LOCATION TRANSPARENT HANDLE** defines the design contract that makes a remote ref look like a local ref. **THE GRPC BRIDGE** implements that contract using gRPC and Protobuf. **THE BULKHEAD** combines per-tenant supervisor trees with `REST_FOR_ONE` dependency chains to isolate tenant failures.

---

## Pattern: THE LOCATION TRANSPARENT HANDLE (Distributed ProcRef)

**Problem**

The TaskFlow board coordinator runs in the `taskflow-coordinator` container. The API tier runs in three `taskflow-api` containers. An API container needs to send a `MoveCardMessage` to the coordinator. How?

The naive answer is to add a `RestTemplate` call or a `WebClient` call directly where you would otherwise call `ref.tell(message)`. That works, but it destroys the abstraction. Now every caller knows it is talking to a remote service. Every test that used to mock a `ProcRef` now needs to mock an HTTP client. The message types are no longer Java records — they are JSON or form payloads with serialization concerns scattered through the codebase.

You want the caller to remain ignorant of whether the target is local or remote. The caller calls `tell`. Whether that tell crosses a virtual thread queue or a network socket is not the caller's business.

**Context**

JOTP's `ProcRef<M>` is an interface:

```java
public interface ProcRef<M> {
    void tell(M message);
    <R> CompletableFuture<R> ask(M message, Duration timeout);
}
```

Any class that implements this interface can behave as a process handle, regardless of whether the target process is local or remote. The caller is typed to the message type `M` and has no dependency on the transport.

**Solution**

Implement a `RemoteProcRef<M>` class that wraps a gRPC stub. From the caller's perspective, it is indistinguishable from a local `ProcRef`. Internally, it serializes the message to Protobuf, sends it over gRPC, and (for `ask`) waits for the response.

The discovery chain:

1. The coordinator container starts and registers its processes in its local `ProcRegistry`.
2. It also starts a gRPC server that exposes those processes to remote callers.
3. The API containers know the coordinator's hostname and gRPC port (from environment variables or Docker Swarm DNS).
4. On startup, the API containers create `RemoteProcRef` instances pointing at the coordinator.
5. Those refs are registered in the API containers' local `ProcRegistry` under the same names as the local refs.
6. Controllers call `registry.whereis("board-coordinator")` and receive the `RemoteProcRef` transparently.

The coordinator hostname in Docker Swarm is simply the service name: `taskflow-coordinator`. Swarm's internal DNS resolves it to the coordinator container's overlay IP.

---

## Pattern: THE GRPC BRIDGE

**Problem**

You need to define a wire protocol for TaskFlow messages that:
- Encodes every message type the coordinator understands
- Supports both fire-and-forget (`tell`) and request-reply (`ask`)
- Is language-neutral (so future clients written in Python or Go can interoperate)
- Carries enough type information that the server can dispatch to the right handler

**Context**

TaskFlow has five coordinator message types: `MoveCard`, `CreateBoard`, `ArchiveBoard`, `GetBoardSummary`, and `AddMember`. `GetBoardSummary` is a request-reply; the rest are fire-and-forget. The response to `GetBoardSummary` is a `BoardSummary` value type.

**Solution**

Define a Protobuf service with two RPCs: `Tell` for fire-and-forget and `Ask` for request-reply. Use `oneof` to represent the union of all message types.

```protobuf
// src/main/proto/coordinator.proto
syntax = "proto3";
package io.github.seanchatmangpt.jotp.coordinator;
option java_multiple_files = true;
option java_package = "io.github.seanchatmangpt.jotp.coordinator.proto";

// --- Message types ---

message MoveCardRequest {
    string card_id     = 1;
    string to_column   = 2;
    string moved_by    = 3;
}

message CreateBoardRequest {
    string board_id   = 1;
    string owner_id   = 2;
    string board_name = 3;
}

message ArchiveBoardRequest {
    string board_id = 1;
}

message GetBoardSummaryRequest {
    string board_id = 1;
}

message AddMemberRequest {
    string board_id = 1;
    string user_id  = 2;
    string role     = 3;
}

// Union of all tell-able messages
message CoordinatorTellEnvelope {
    oneof message {
        MoveCardRequest     move_card     = 1;
        CreateBoardRequest  create_board  = 2;
        ArchiveBoardRequest archive_board = 3;
        AddMemberRequest    add_member    = 4;
    }
}

// Union of all ask-able messages
message CoordinatorAskEnvelope {
    string correlation_id = 1;
    oneof message {
        GetBoardSummaryRequest get_board_summary = 2;
    }
}

// --- Response types ---

message BoardSummary {
    string board_id     = 1;
    string board_name   = 2;
    int32  card_count   = 3;
    int32  member_count = 4;
    string last_updated = 5;
}

message CoordinatorAskResponse {
    string correlation_id = 1;
    oneof result {
        BoardSummary board_summary = 2;
        string       error_message = 3;
    }
}

message TellAck {
    bool accepted = 1;
}

// --- Service ---

service CoordinatorService {
    rpc Tell (CoordinatorTellEnvelope) returns (TellAck);
    rpc Ask  (CoordinatorAskEnvelope) returns (CoordinatorAskResponse);
}
```

**Server-side: gRPC handler**

The gRPC handler on the coordinator container deserializes the envelope and dispatches to the local `ProcRef`:

```java
// CoordinatorGrpcService.java
import io.grpc.stub.StreamObserver;
import io.github.seanchatmangpt.jotp.coordinator.proto.*;
import io.github.seanchatmangpt.jotp.registry.ProcRegistry;

public class CoordinatorGrpcService
        extends CoordinatorServiceGrpc.CoordinatorServiceImplBase {

    private final ProcRegistry registry;

    public CoordinatorGrpcService(ProcRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void tell(
            CoordinatorTellEnvelope request,
            StreamObserver<TellAck> responseObserver) {

        ProcRef<BoardMessage> ref = registry.whereis("board-coordinator")
            .map(r -> (ProcRef<BoardMessage>) r)
            .orElseThrow(() -> Status.UNAVAILABLE
                .withDescription("board-coordinator not available")
                .asRuntimeException());

        BoardMessage message = switch (request.getMessageCase()) {
            case MOVE_CARD -> new MoveCardMessage(
                request.getMoveCard().getCardId(),
                request.getMoveCard().getToColumn(),
                request.getMoveCard().getMovedBy()
            );
            case CREATE_BOARD -> new CreateBoardMessage(
                request.getCreateBoard().getBoardId(),
                request.getCreateBoard().getOwnerId(),
                request.getCreateBoard().getBoardName()
            );
            case ARCHIVE_BOARD -> new ArchiveBoardMessage(
                request.getArchiveBoard().getBoardId()
            );
            case ADD_MEMBER -> new AddMemberMessage(
                request.getAddMember().getBoardId(),
                request.getAddMember().getUserId(),
                request.getAddMember().getRole()
            );
            case MESSAGE_NOT_SET -> throw Status.INVALID_ARGUMENT
                .withDescription("message field not set")
                .asRuntimeException();
        };

        ref.tell(message);
        responseObserver.onNext(TellAck.newBuilder().setAccepted(true).build());
        responseObserver.onCompleted();
    }

    @Override
    public void ask(
            CoordinatorAskEnvelope request,
            StreamObserver<CoordinatorAskResponse> responseObserver) {

        ProcRef<BoardMessage> ref = registry.whereis("board-coordinator")
            .map(r -> (ProcRef<BoardMessage>) r)
            .orElseThrow(() -> Status.UNAVAILABLE
                .withDescription("board-coordinator not available")
                .asRuntimeException());

        try {
            CoordinatorAskResponse response = switch (request.getMessageCase()) {
                case GET_BOARD_SUMMARY -> {
                    BoardSummary summary = ((ProcRef<GetBoardSummaryMessage>) ref)
                        .ask(
                            new GetBoardSummaryMessage(
                                request.getGetBoardSummary().getBoardId()
                            ),
                            Duration.ofSeconds(5)
                        )
                        .get();

                    yield CoordinatorAskResponse.newBuilder()
                        .setCorrelationId(request.getCorrelationId())
                        .setBoardSummary(toProto(summary))
                        .build();
                }
                case MESSAGE_NOT_SET -> throw Status.INVALID_ARGUMENT
                    .withDescription("message field not set")
                    .asRuntimeException();
            };

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (ExecutionException e) {
            responseObserver.onNext(CoordinatorAskResponse.newBuilder()
                .setCorrelationId(request.getCorrelationId())
                .setErrorMessage(e.getCause().getMessage())
                .build());
            responseObserver.onCompleted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.CANCELLED
                .withDescription("interrupted")
                .asException());
        }
    }

    private io.github.seanchatmangpt.jotp.coordinator.proto.BoardSummary toProto(
            BoardSummary summary) {
        return io.github.seanchatmangpt.jotp.coordinator.proto.BoardSummary.newBuilder()
            .setBoardId(summary.boardId())
            .setBoardName(summary.boardName())
            .setCardCount(summary.cardCount())
            .setMemberCount(summary.memberCount())
            .setLastUpdated(summary.lastUpdated().toString())
            .build();
    }
}
```

Start the gRPC server in the coordinator's main class:

```java
// CoordinatorApplication.java
@SpringBootApplication
public class CoordinatorApplication {

    public static void main(String[] args) throws Exception {
        var context = SpringApplication.run(CoordinatorApplication.class, args);

        ProcRegistry registry = context.getBean(ProcRegistry.class);

        Server grpcServer = ServerBuilder.forPort(9090)
            .addService(new CoordinatorGrpcService(registry))
            .build()
            .start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            grpcServer.shutdown();
            try { grpcServer.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        }));

        grpcServer.awaitTermination();
    }
}
```

**Client-side: RemoteProcRef**

The API tier creates a `RemoteProcRef` that wraps the gRPC stub:

```java
// RemoteProcRef.java
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.github.seanchatmangpt.jotp.coordinator.proto.*;

public class RemoteProcRef<M> implements ProcRef<M> {

    private final CoordinatorServiceGrpc.CoordinatorServiceBlockingStub stub;
    private final MessageSerializer<M> serializer;

    public RemoteProcRef(String host, int port, MessageSerializer<M> serializer) {
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()  // Use TLS in production: .useTransportSecurity()
            .build();
        this.stub       = CoordinatorServiceGrpc.newBlockingStub(channel);
        this.serializer = serializer;
    }

    @Override
    public void tell(M message) {
        CoordinatorTellEnvelope envelope = serializer.toTellEnvelope(message);
        stub.tell(envelope);
        // Response (TellAck) is discarded; this is fire-and-forget from the caller's perspective
    }

    @Override
    public <R> CompletableFuture<R> ask(M message, Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            String correlationId = UUID.randomUUID().toString();
            CoordinatorAskEnvelope envelope = serializer.toAskEnvelope(message, correlationId);

            CoordinatorAskResponse response = stub
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .ask(envelope);

            if (response.hasErrorMessage()) {
                throw new RuntimeException("Remote ask failed: " + response.getErrorMessage());
            }

            return (R) serializer.fromAskResponse(response);
        });
    }
}
```

The `MessageSerializer<M>` interface encapsulates the Protobuf mapping:

```java
// BoardMessageSerializer.java — serializes BoardMessage subtypes to Protobuf envelopes
public class BoardMessageSerializer implements MessageSerializer<BoardMessage> {

    @Override
    public CoordinatorTellEnvelope toTellEnvelope(BoardMessage message) {
        CoordinatorTellEnvelope.Builder builder = CoordinatorTellEnvelope.newBuilder();
        return switch (message) {
            case MoveCardMessage m -> builder.setMoveCard(
                MoveCardRequest.newBuilder()
                    .setCardId(m.cardId())
                    .setToColumn(m.toColumnId())
                    .setMovedBy(m.movedBy())
                    .build()
            ).build();
            case CreateBoardMessage m -> builder.setCreateBoard(
                CreateBoardRequest.newBuilder()
                    .setBoardId(m.boardId())
                    .setOwnerId(m.ownerId())
                    .setBoardName(m.boardName())
                    .build()
            ).build();
            // ... other cases
            default -> throw new IllegalArgumentException("Unhandled message: " + message);
        };
    }

    @Override
    public CoordinatorAskEnvelope toAskEnvelope(BoardMessage message, String correlationId) {
        return switch (message) {
            case GetBoardSummaryMessage m ->
                CoordinatorAskEnvelope.newBuilder()
                    .setCorrelationId(correlationId)
                    .setGetBoardSummary(
                        GetBoardSummaryRequest.newBuilder()
                            .setBoardId(m.boardId())
                            .build()
                    ).build();
            default -> throw new IllegalArgumentException("Cannot ask with: " + message);
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R fromAskResponse(CoordinatorAskResponse response) {
        return switch (response.getResultCase()) {
            case BOARD_SUMMARY -> (R) fromProto(response.getBoardSummary());
            case ERROR_MESSAGE -> throw new RuntimeException(response.getErrorMessage());
            case RESULT_NOT_SET -> throw new IllegalStateException("No result in response");
        };
    }

    private io.github.seanchatmangpt.jotp.domain.BoardSummary fromProto(
            io.github.seanchatmangpt.jotp.coordinator.proto.BoardSummary proto) {
        return new io.github.seanchatmangpt.jotp.domain.BoardSummary(
            proto.getBoardId(),
            proto.getBoardName(),
            proto.getCardCount(),
            proto.getMemberCount(),
            Instant.parse(proto.getLastUpdated())
        );
    }
}
```

Wire the `RemoteProcRef` into the API tier's `ProcRegistry` at startup:

```java
// ApiJotpConfiguration.java
@Configuration
public class ApiJotpConfiguration {

    @Value("${coordinator.host:taskflow-coordinator}")
    private String coordinatorHost;

    @Value("${coordinator.port:9090}")
    private int coordinatorPort;

    @Bean
    public ProcRegistry procRegistry() {
        var registry = ProcRegistry.create();

        // Register a remote ref that delegates to the coordinator container
        RemoteProcRef<BoardMessage> remoteCoordinator = new RemoteProcRef<>(
            coordinatorHost, coordinatorPort, new BoardMessageSerializer()
        );
        registry.register("board-coordinator", remoteCoordinator);

        return registry;
    }
}
```

Controllers call `registry.whereis("board-coordinator")` and receive the `RemoteProcRef`. They call `tell` and `ask` exactly as before. The network hop is invisible.

**Manual end-to-end test**

Before automating in Swarm, verify the bridge locally with two JVM processes:

```bash
# Terminal 1: Start coordinator (gRPC server on port 9090)
mvnd exec:java -Dexec.mainClass=io.github.seanchatmangpt.jotp.coordinator.CoordinatorApplication

# Terminal 2: Send a test message via grpcurl
grpcurl -plaintext \
  -d '{"move_card": {"card_id": "card-42", "to_column": "done", "moved_by": "alice"}}' \
  localhost:9090 \
  io.github.seanchatmangpt.jotp.coordinator.CoordinatorService/Tell

# Expected response
{
  "accepted": true
}

# Test ask
grpcurl -plaintext \
  -d '{"correlation_id": "test-1", "get_board_summary": {"board_id": "board-1"}}' \
  localhost:9090 \
  io.github.seanchatmangpt.jotp.coordinator.CoordinatorService/Ask

# Expected response
{
  "correlationId": "test-1",
  "boardSummary": {
    "boardId": "board-1",
    "boardName": "Sprint 12",
    "cardCount": 24,
    "memberCount": 5,
    "lastUpdated": "2026-03-12T09:15:00Z"
  }
}
```

In Docker Swarm, the coordinator service is discoverable via DNS. No hardcoded IPs. The environment variable `COORDINATOR_HOST=taskflow-coordinator` in `stack.yml` is the only configuration the API tier needs.

**Consequences**

The gRPC bridge achieves location transparency at the cost of a serialization layer. Every message type requires a Protobuf definition and a corresponding serializer case. This is deliberate: the explicit schema is also a public API contract. When the Python analytics service needs to move a card, it generates a client from `coordinator.proto` and calls the same gRPC endpoint.

The `withDeadlineAfter` on the stub maps the JOTP `ask` timeout to a gRPC deadline. If the coordinator does not respond within the timeout, gRPC returns a `DEADLINE_EXCEEDED` status and the `CompletableFuture` completes exceptionally. The caller handles this the same way it handles a local `ask` timeout: with `exceptionally` or `handle` on the future.

Use TLS for the gRPC channel in production. Replace `.usePlaintext()` with `.useTransportSecurity()` and configure mutual TLS with certificates managed by your infrastructure (AWS ACM Private CA or Let's Encrypt). The Protobuf schema and message payloads carry sensitive board data; do not transmit them in plaintext.

---

## Pattern: THE BULKHEAD (Per-Tenant Supervisor)

**Problem**

TaskFlow is a multi-tenant SaaS product. TenantA is a design agency with thirty active boards. TenantB is a software team with five boards. If TenantA's board coordinator crashes repeatedly and exhausts its restart budget, the supervisor gives up. Under a flat process architecture, that could take down TenantB's boards too.

You need TenantA's failure to be completely contained. TenantB's boards should stay running regardless of what TenantA's processes do. And within each tenant, you need dependency-ordered restarts: authentication must be running before the data service starts, and the data service must be running before the cache service starts.

**Context**

The coordinator container manages boards for all tenants. Each tenant has three internal services: `AuthService` (validates JWT tokens for that tenant's SSO provider), `DataService` (reads and writes board state to the shared database, but with tenant-scoped queries), and `CacheService` (in-memory board state cache, populated from the DataService). These form a dependency chain: `AuthService` → `DataService` → `CacheService`. If `DataService` restarts, `CacheService` must also restart (its cache is stale). If `AuthService` restarts, both `DataService` and `CacheService` must restart (their sessions are invalid).

**Solution**

Create one supervisor per tenant. Under each tenant supervisor, use `REST_FOR_ONE` to capture the dependency chain. The root supervisor uses `ONE_FOR_ONE` so that TenantA's supervisor crashing does not affect TenantB's supervisor.

```java
// TenantSupervisorFactory.java
public class TenantSupervisorFactory {

    public static Supervisor createTenantSupervisor(
            String tenantId,
            TenantConfig config,
            ProcRegistry registry) {

        // REST_FOR_ONE: if AuthService crashes, restart AuthService + DataService + CacheService.
        //               if DataService crashes, restart DataService + CacheService.
        //               if CacheService crashes, restart only CacheService.
        Supervisor tenantSupervisor = Supervisor.builder()
            .strategy(RestartStrategy.REST_FOR_ONE)
            .maxRestarts(10)
            .withinWindow(Duration.ofMinutes(5))
            .onChildStart((name, ref) ->
                registry.register(tenantId + "." + name, ref)
            )
            .supervise(
                "auth",
                () -> new AuthService(config.ssoProviderUrl(), config.clientId()),
                AuthState.empty()
            )
            .supervise(
                "data",
                () -> new DataService(config.databaseSchema()),
                DataState.empty()
            )
            .supervise(
                "cache",
                () -> new CacheService(),
                CacheState.empty()
            )
            .build();

        registry.register(tenantId + ".supervisor", tenantSupervisor.selfRef());
        return tenantSupervisor;
    }
}
```

The root supervisor manages all tenant supervisors:

```java
// MultiTenantCoordinator.java
public class MultiTenantCoordinator {

    private final ProcRegistry registry;
    private final Supervisor rootSupervisor;

    public MultiTenantCoordinator(
            List<TenantConfig> tenants,
            ProcRegistry registry) {

        this.registry = registry;

        // ONE_FOR_ONE: TenantA supervisor crash does not restart TenantB supervisor
        Supervisor.Builder rootBuilder = Supervisor.builder()
            .strategy(RestartStrategy.ONE_FOR_ONE)
            .maxRestarts(tenants.size() * 3)
            .withinWindow(Duration.ofMinutes(1));

        for (TenantConfig tenant : tenants) {
            rootBuilder.supervise(
                tenant.id(),
                () -> TenantSupervisorFactory.createTenantSupervisor(
                    tenant.id(), tenant, registry
                ),
                TenantState.of(tenant)
            );
        }

        this.rootSupervisor = rootBuilder.build();
    }

    // Look up a tenant's service ref by name
    public <M> Optional<ProcRef<M>> getTenantService(String tenantId, String service) {
        return registry.whereis(tenantId + "." + service)
            .map(ref -> (ProcRef<M>) ref);
    }
}
```

The process tree at runtime for two tenants:

```
RootSupervisor (ONE_FOR_ONE)
├── TenantA_Supervisor (REST_FOR_ONE)
│   ├── AuthService_A    ← If this crashes: restart AuthService_A, DataService_A, CacheService_A
│   ├── DataService_A    ← If this crashes: restart DataService_A, CacheService_A
│   └── CacheService_A   ← If this crashes: restart only CacheService_A
└── TenantB_Supervisor (REST_FOR_ONE)
    ├── AuthService_B    ← Independent of TenantA entirely
    ├── DataService_B
    └── CacheService_B
```

Verify the bulkhead property in an integration test:

```java
// BulkheadIsolationIT.java
class BulkheadIsolationIT implements WithAssertions {

    @Test
    void tenantA_crash_does_not_affect_tenantB() throws Exception {
        var registry = ProcRegistry.create();
        var config   = List.of(
            TenantConfig.of("tenant-a", "https://sso.a.example.com", "schema_a"),
            TenantConfig.of("tenant-b", "https://sso.b.example.com", "schema_b")
        );
        var coordinator = new MultiTenantCoordinator(config, registry);

        // Both tenants initially available
        assertThat(registry.whereis("tenant-a.auth")).isPresent();
        assertThat(registry.whereis("tenant-b.auth")).isPresent();

        // Crash TenantA's AuthService (cascades to DataService and CacheService via REST_FOR_ONE)
        ProcRef<?> tenantAAuth = registry.whereis("tenant-a.auth").orElseThrow();
        tenantAAuth.tell(new PoisonPill());

        // Wait for TenantA to restart
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(registry.whereis("tenant-a.auth")).isPresent()
        );

        // TenantB must still be the original ref — it was never restarted
        ProcRef<?> tenantBAuthAfter = registry.whereis("tenant-b.auth").orElseThrow();
        // The ref is still active; it can accept messages
        CompletableFuture<?> result = tenantBAuthAfter.ask(
            new ValidateTokenMessage("valid-jwt-b"), Duration.ofSeconds(1)
        );
        assertThat(result.get()).isNotNull();
    }

    @Test
    void rest_for_one_cascades_from_data_to_cache_but_not_auth() throws Exception {
        var registry    = ProcRegistry.create();
        var coordinator = new MultiTenantCoordinator(
            List.of(TenantConfig.of("tenant-x", "https://sso.x.example.com", "schema_x")),
            registry
        );

        ProcRef<?> originalAuth  = registry.whereis("tenant-x.auth").orElseThrow();
        ProcRef<?> originalData  = registry.whereis("tenant-x.data").orElseThrow();
        ProcRef<?> originalCache = registry.whereis("tenant-x.cache").orElseThrow();

        // Crash DataService
        originalData.tell(new PoisonPill());

        // Wait for restarts
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(registry.whereis("tenant-x.data")).isPresent();
            assertThat(registry.whereis("tenant-x.cache")).isPresent();
        });

        ProcRef<?> newData  = registry.whereis("tenant-x.data").orElseThrow();
        ProcRef<?> newCache = registry.whereis("tenant-x.cache").orElseThrow();

        // Data and cache restarted (new refs)
        assertThat(newData).isNotSameAs(originalData);
        assertThat(newCache).isNotSameAs(originalCache);

        // Auth was NOT restarted (same ref as before crash)
        ProcRef<?> authAfter = registry.whereis("tenant-x.auth").orElseThrow();
        assertThat(authAfter).isSameAs(originalAuth);
    }
}
```

**REST_FOR_ONE in Practice**

`REST_FOR_ONE` names the pattern precisely: when a child crashes, restart that child and all children registered after it (the "REST" of the supervised list). This makes the ordering of `supervise()` calls semantically significant. Always register services in dependency order — dependencies first, dependents after.

An easy way to remember the three strategies:

| Strategy | Mnemonic | Meaning |
|---|---|---|
| `ONE_FOR_ONE` | Surgeon — one patient at a time | Restart only the crashed child |
| `ONE_FOR_ALL` | Army — everyone goes down together | Restart all children |
| `REST_FOR_ONE` | Dominos — the crash knocks over what follows | Restart crashed child + all registered after it |

**Consequences**

The bulkhead architecture provides genuine tenant isolation at the process level. A tenant whose integration exhausts its restart budget (`maxRestarts(10)` in 5 minutes) causes the tenant's supervisor to crash. The root supervisor restarts that supervisor under `ONE_FOR_ONE`, which re-creates all three tenant services. TenantB never restarts. Its boards remain live throughout.

The operational cost is multiplied restart tracking. With 100 tenants, you have 100 separate restart counters. The root supervisor's `maxRestarts` budget must be generous enough to allow all tenants to experience independent bursts of failures without the root itself giving up. Set it to `tenants.size() * 3` as a reasonable starting point and alert on root supervisor exhaustion as a critical incident.

The `onChildStart` callback ensures that the `ProcRegistry` stays consistent with the actual running processes. The key `tenantId + "." + name` namespace prevents collisions between tenants. `registry.whereis("tenant-a.auth")` and `registry.whereis("tenant-b.auth")` return completely independent refs.

Finally, this architecture scales horizontally. Adding a new tenant is `coordinator.addTenant(newConfig)` — it creates a new subtree under the root supervisor without touching any existing subtree. Removing a tenant is a graceful shutdown of that tenant's supervisor. No other tenants are aware of either operation.

---

## What Have You Learned?

- **`ProcRef<M>` is an interface, not a class.** `RemoteProcRef<M>` implements the same interface with a gRPC stub as its transport. Callers that code to the interface are location-transparent.
- **Protobuf `oneof` represents the union of message types** that a remote process can receive. Every message type requires a Protobuf definition and a serializer case — this is the explicit cost of crossing a JVM boundary.
- **The gRPC bridge has two RPCs: `Tell` (fire-and-forget) and `Ask` (request-reply).** The `Ask` RPC carries a `correlation_id` so that responses can be matched to requests.
- **Server-side dispatch uses a Java `switch` expression on the `oneof` case.** Sealed types on the Java side and `oneof` on the Protobuf side are a natural fit — both describe a finite set of variants.
- **`withDeadlineAfter` on the gRPC stub maps the JOTP `ask` timeout to a gRPC deadline.** If the coordinator does not respond in time, `DEADLINE_EXCEEDED` is returned and the `CompletableFuture` completes exceptionally.
- **`REST_FOR_ONE` captures dependency chains.** Register services in dependency order. When a dependency crashes, it takes down its dependents so that they restart with a fresh dependency reference.
- **One supervisor per tenant + `ONE_FOR_ONE` at the root = true tenant isolation.** A crashing TenantA supervisor is restarted by the root. TenantB's supervisor is never touched.
- **The `onChildStart` callback on each supervisor re-registers processes in `ProcRegistry` after every restart**, keeping the registry consistent with the actual running process tree.
- **`grpcurl` is the fastest way to test a gRPC server manually** before wiring it into a full Docker Swarm deployment. Always verify the bridge with a manual test before automating it in CI.
