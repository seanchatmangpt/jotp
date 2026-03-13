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
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.Supervisor;

public class BoardCoordinatorService {

    public static BoardCoordinatorService start() {
        var supervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ALL,
            5,
            Duration.ofMinutes(1)
        );

        // supervise() returns a ProcRef that transparently redirects after restarts
        ProcRef<BoardState, BoardMessage> coordinatorRef =
            supervisor.supervise("board-coordinator", BoardState.empty(), BoardCoordinator::handle);
        ProcRef<EventState, EventMessage> eventRef =
            supervisor.supervise("event-manager", EventState.empty(), EventManager::handle);

        // Register the underlying Proc by stable name so other components can look it up
        ProcRegistry.register("board-coordinator", coordinatorRef.proc());
        ProcRegistry.register("event-manager", eventRef.proc());

        return new BoardCoordinatorService(supervisor, coordinatorRef, eventRef);
    }
}
```

Any other component — an HTTP controller, a WebSocket handler, a background metrics reporter — uses `whereis` to find the ref:

```java
// In BoardWebSocketHandler.java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;

@Component
public class BoardWebSocketHandler {

    public void onCardMoved(CardMovedEvent event) {
        ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator")
            .ifPresentOrElse(
                proc -> proc.tell(new MoveCardMessage(event.cardId(), event.toColumnId())),
                ()   -> log.error("board-coordinator not registered; dropping message")
            );
    }
}
```

`whereis` returns `Optional<Proc<S,M>>`. The empty case is not an error you ignore — it means the supervisor tree has not started yet or has exceeded its restart budget and given up. Handle it explicitly.

Register a startup bean that wires the supervisor tree and populates the global registry:

```java
@Configuration
public class JotpConfiguration {

    @Bean
    public BoardCoordinatorService boardCoordinatorService() {
        // BoardCoordinatorService.start() registers processes in ProcRegistry internally
        return BoardCoordinatorService.start();
    }
}
```

**Code Example**

A full integration test verifying the registry survives a coordinator restart:

```java
// BoardCoordinatorRegistryIT.java
class BoardCoordinatorRegistryIT implements WithAssertions {

    @Test
    void registry_returns_new_proc_after_supervisor_restart() throws Exception {
        ProcRegistry.reset(); // clear global registry between tests
        var service = BoardCoordinatorService.start();

        Proc<BoardState, BoardMessage> original =
            ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator").orElseThrow();

        // Force the coordinator to crash
        original.tell(new PoisonPill());

        // The supervisor restarts it; wait for re-registration
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
            assertThat(ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator"))
                .isPresent()
        );

        Proc<BoardState, BoardMessage> restarted =
            ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator").orElseThrow();

        // It should be a different Proc instance (new process)
        assertThat(restarted).isNotSameAs(original);

        // But it should still accept messages
        CompletableFuture<BoardState> result =
            restarted.ask(new GetBoardSummaryMessage("board-1"), Duration.ofSeconds(1));
        assertThat(result.get()).isNotNull();
    }
}
```

**Consequences**

`ProcRegistry` makes the process topology of your application explicit and testable. The integration test above would be impossible if you passed `Proc` references as constructor arguments — you would have to rebuild the entire object graph to test restart behavior. With the registry, you test the full lifecycle.

The tradeoff: `ProcRegistry` is per-JVM. It does not know about processes in other containers. That limitation is intentional — distributed lookup requires distributed coordination, and Chapter 10 covers the gRPC bridge that extends `ProcRegistry` semantics across JVM boundaries. For now, register local processes by name and accept that the registry is the local phone book, not the global DNS.

---

## What Have You Learned?

- **Plaintext secrets in CI environment variables expose you to insider abuse.** SOPS + Age keeps secrets encrypted in the repository; only the Age private key (stored as a single GitHub Actions secret) can decrypt them.
- **`age-keygen` produces a key pair; the public key encrypts, the private key decrypts.** Only the private key is sensitive.
- **Docker Swarm secrets are distributed over the encrypted control plane and mounted as read-only files.** Applications read them from `/run/secrets/<name>`, never from environment variables.
- **`deploy.sh` encodes the full deployment procedure** — pull, deploy, wait for convergence, rollback on timeout — so that the CI pipeline and manual deployments follow identical steps.
- **`./dx.sh deploy <tag>` is the single interface** to deployment, callable from a developer workstation or a GitHub Actions runner without modification.
- **`ProcRegistry.register("board-coordinator", proc)` names a process.** `ProcRegistry.whereis("board-coordinator")` looks it up. The `Optional` return forces you to handle the case where the process is not yet running.
- **Registries survive restarts.** When a supervisor recreates a child process, re-registration updates the registry entry. Callers that call `whereis` after the restart get the new `Proc` instance.
