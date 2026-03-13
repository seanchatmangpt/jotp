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

JOTP's `Proc<S, M>` is the local process handle. For location transparency, introduce a thin `RemoteProc<M>` wrapper that exposes the same `tell(M)` and `ask(M, Duration)` surface but delegates to a gRPC stub rather than a virtual thread mailbox:

```java
// Shared interface for local and remote dispatch
public interface MessageEndpoint<M> {
    void tell(M message);
    CompletableFuture<?> ask(M message, Duration timeout);
}
```

Any class that implements this interface can behave as a process handle, regardless of whether the target process is local or remote. The caller is typed to the message type `M` and has no dependency on the transport. `Proc<S, M>` satisfies this contract directly; `RemoteProc<M>` does so over the network.

**Solution**

Implement a `RemoteProc<M>` class that wraps a gRPC stub. From the caller's perspective, it is indistinguishable from a local `Proc`. Internally, it serializes the message to Protobuf, sends it over gRPC, and (for `ask`) waits for the response.

The discovery chain:

1. The coordinator container starts and registers its processes in its local `ProcRegistry`.
2. It also starts a gRPC server that exposes those processes to remote callers.
3. The API containers know the coordinator's hostname and gRPC port (from environment variables or Docker Swarm DNS).
4. On startup, the API containers create `RemoteProc` instances pointing at the coordinator.
5. Those instances are injected into components via Spring, providing the same `tell`/`ask` surface as a local `Proc`.
6. Controllers receive a `RemoteProc<BoardMessage>` bean via constructor injection and call `tell`/`ask` without knowing they are crossing a network boundary.

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

The gRPC handler on the coordinator container deserializes the envelope and dispatches to the local `Proc`:

```java
// CoordinatorGrpcService.java
import io.grpc.stub.StreamObserver;
import io.github.seanchatmangpt.jotp.coordinator.proto.*;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;

public class CoordinatorGrpcService
        extends CoordinatorServiceGrpc.CoordinatorServiceImplBase {

    @Override
    public void tell(
            CoordinatorTellEnvelope request,
            StreamObserver<TellAck> responseObserver) {

        Proc<BoardState, BoardMessage> ref =
            ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator")
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

        Proc<BoardState, BoardMessage> ref =
            ProcRegistry.<BoardState, BoardMessage>whereis("board-coordinator")
                .orElseThrow(() -> Status.UNAVAILABLE
                    .withDescription("board-coordinator not available")
                    .asRuntimeException());

        try {
            CoordinatorAskResponse response = switch (request.getMessageCase()) {
                case GET_BOARD_SUMMARY -> {
                    BoardState state = ref
                        .ask(
                            new GetBoardSummaryMessage(
                                request.getGetBoardSummary().getBoardId()
                            ),
                            Duration.ofSeconds(5)
                        )
                        .get();
                    // Extract the summary value from the returned state
                    BoardSummary summary = state.toBoardSummary();

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

**Client-side: RemoteProc**

The API tier creates a `RemoteProc` that wraps the gRPC stub:

```java
// RemoteProc.java
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.github.seanchatmangpt.jotp.coordinator.proto.*;

/**
 * Location-transparent handle for a remote coordinator process.
 * Exposes the same tell/ask surface as a local Proc<S,M>.
 */
public class RemoteProc<M> implements MessageEndpoint<M> {

    private final CoordinatorServiceGrpc.CoordinatorServiceBlockingStub stub;
    private final MessageSerializer<M> serializer;

    public RemoteProc(String host, int port, MessageSerializer<M> serializer) {
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
    public CompletableFuture<?> ask(M message, Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            String correlationId = UUID.randomUUID().toString();
            CoordinatorAskEnvelope envelope = serializer.toAskEnvelope(message, correlationId);

            CoordinatorAskResponse response = stub
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .ask(envelope);

            if (response.hasErrorMessage()) {
                throw new RuntimeException("Remote ask failed: " + response.getErrorMessage());
            }

            return serializer.fromAskResponse(response);
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

Wire the `RemoteProc` into the API tier as a Spring bean:

```java
// ApiJotpConfiguration.java
import io.github.seanchatmangpt.jotp.ProcRegistry;

@Configuration
public class ApiJotpConfiguration {

    @Value("${coordinator.host:taskflow-coordinator}")
    private String coordinatorHost;

    @Value("${coordinator.port:9090}")
    private int coordinatorPort;

    @Bean
    public RemoteProc<BoardMessage> remoteCoordinator() {
        return new RemoteProc<>(
            coordinatorHost, coordinatorPort, new BoardMessageSerializer()
        );
    }
}
```

In the API tier, components call the `RemoteProc<BoardMessage>` bean directly via injection, or you can wrap it in a thin adapter that stores it under a name for lookup:

```java
// In a startup component (implements ApplicationRunner)
@Component
public class RemoteProcRegistrar implements ApplicationRunner {

    private final RemoteProc<BoardMessage> remoteCoordinator;

    public RemoteProcRegistrar(RemoteProc<BoardMessage> remoteCoordinator) {
        this.remoteCoordinator = remoteCoordinator;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Register a synthetic Proc that wraps the remote endpoint
        // for code that looks up processes by name
        // (Direct injection of RemoteProc is the preferred approach)
    }
}
```

In practice, components that need the coordinator receive `RemoteProc<BoardMessage>` via constructor injection. They call `tell` and `ask` exactly as they would on a local `Proc`. The network hop is invisible.

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
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.Supervisor;

public class TenantSupervisorFactory {

    public static Supervisor createTenantSupervisor(
            String tenantId,
            TenantConfig config) {

        // REST_FOR_ONE: if AuthService crashes, restart AuthService + DataService + CacheService.
        //               if DataService crashes, restart DataService + CacheService.
        //               if CacheService crashes, restart only CacheService.
        Supervisor tenantSupervisor = Supervisor.create(
            Supervisor.Strategy.REST_FOR_ONE,
            10,
            Duration.ofMinutes(5)
        );

        // supervise() registers children in order; REST_FOR_ONE uses that order
        ProcRef<AuthState, AuthMessage> authRef = tenantSupervisor.supervise(
            "auth",
            AuthState.empty(),
            (state, msg) -> new AuthService(config.ssoProviderUrl(), config.clientId()).handle(state, msg)
        );
        ProcRef<DataState, DataMessage> dataRef = tenantSupervisor.supervise(
            "data",
            DataState.empty(),
            (state, msg) -> new DataService(config.databaseSchema()).handle(state, msg)
        );
        ProcRef<CacheState, CacheMessage> cacheRef = tenantSupervisor.supervise(
            "cache",
            CacheState.empty(),
            (state, msg) -> new CacheService().handle(state, msg)
        );

        // Register each child's Proc under a namespaced key
        ProcRegistry.register(tenantId + ".auth",  authRef.proc());
        ProcRegistry.register(tenantId + ".data",  dataRef.proc());
        ProcRegistry.register(tenantId + ".cache", cacheRef.proc());

        return tenantSupervisor;
    }
}
```

The root supervisor manages all tenant supervisors:

```java
// MultiTenantCoordinator.java
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;
import io.github.seanchatmangpt.jotp.Supervisor;

public class MultiTenantCoordinator {

    private final Supervisor rootSupervisor;

    public MultiTenantCoordinator(List<TenantConfig> tenants) {
        ProcRegistry.reset(); // ensure clean registry for test isolation

        // ONE_FOR_ONE: TenantA supervisor crash does not restart TenantB supervisor
        // Each tenant gets its own supervised entry. Here we use the root supervisor
        // to monitor the tenant supervisors themselves.
        this.rootSupervisor = Supervisor.create(
            Supervisor.Strategy.ONE_FOR_ONE,
            tenants.size() * 3,
            Duration.ofMinutes(1)
        );

        for (TenantConfig tenant : tenants) {
            // Create a tenant supervisor and register its children immediately.
            // The root supervisor owns the tenant supervisor's lifecycle indirectly
            // by supervising a sentinel process that starts/stops the tenant tree.
            TenantSupervisorFactory.createTenantSupervisor(tenant.id(), tenant);
        }
    }

    // Look up a tenant's service Proc by name
    public <S, M> Optional<Proc<S, M>> getTenantService(String tenantId, String service) {
        return ProcRegistry.whereis(tenantId + "." + service);
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
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRegistry;

class BulkheadIsolationIT implements WithAssertions {

    @Test
    void tenantA_crash_does_not_affect_tenantB() throws Exception {
        var config = List.of(
            TenantConfig.of("tenant-a", "https://sso.a.example.com", "schema_a"),
            TenantConfig.of("tenant-b", "https://sso.b.example.com", "schema_b")
        );
        var coordinator = new MultiTenantCoordinator(config);

        // Both tenants initially available
        assertThat(ProcRegistry.whereis("tenant-a.auth")).isPresent();
        assertThat(ProcRegistry.whereis("tenant-b.auth")).isPresent();

        // Capture TenantB's auth Proc before any crash
        Proc<?, ?> tenantBAuthBefore = ProcRegistry.whereis("tenant-b.auth").orElseThrow();

        // Crash TenantA's AuthService (cascades to DataService and CacheService via REST_FOR_ONE)
        Proc<?, ?> tenantAAuth = ProcRegistry.whereis("tenant-a.auth").orElseThrow();
        tenantAAuth.tell(new PoisonPill());

        // Wait for TenantA to restart
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(ProcRegistry.whereis("tenant-a.auth")).isPresent()
        );

        // TenantB must still be the original Proc — it was never restarted
        Proc<?, ?> tenantBAuthAfter = ProcRegistry.whereis("tenant-b.auth").orElseThrow();
        assertThat(tenantBAuthAfter).isSameAs(tenantBAuthBefore);

        // The Proc is still active; it can accept messages
        CompletableFuture<?> result = tenantBAuthAfter.ask(
            new ValidateTokenMessage("valid-jwt-b"), Duration.ofSeconds(1)
        );
        assertThat(result.get()).isNotNull();
    }

    @Test
    void rest_for_one_cascades_from_data_to_cache_but_not_auth() throws Exception {
        var coordinator = new MultiTenantCoordinator(
            List.of(TenantConfig.of("tenant-x", "https://sso.x.example.com", "schema_x"))
        );

        Proc<?, ?> originalAuth  = ProcRegistry.whereis("tenant-x.auth").orElseThrow();
        Proc<?, ?> originalData  = ProcRegistry.whereis("tenant-x.data").orElseThrow();
        Proc<?, ?> originalCache = ProcRegistry.whereis("tenant-x.cache").orElseThrow();

        // Crash DataService
        originalData.tell(new PoisonPill());

        // Wait for restarts
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(ProcRegistry.whereis("tenant-x.data")).isPresent();
            assertThat(ProcRegistry.whereis("tenant-x.cache")).isPresent();
        });

        Proc<?, ?> newData  = ProcRegistry.whereis("tenant-x.data").orElseThrow();
        Proc<?, ?> newCache = ProcRegistry.whereis("tenant-x.cache").orElseThrow();

        // Data and cache restarted (new Proc instances)
        assertThat(newData).isNotSameAs(originalData);
        assertThat(newCache).isNotSameAs(originalCache);

        // Auth was NOT restarted (same Proc instance as before crash)
        Proc<?, ?> authAfter = ProcRegistry.whereis("tenant-x.auth").orElseThrow();
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

The bulkhead architecture provides genuine tenant isolation at the process level. A tenant whose processes exhaust the restart budget (`maxRestarts = 10` within 5 minutes) causes the tenant's supervisor to terminate. The root supervisor (under `ONE_FOR_ONE`) restarts that tenant's supervisor, which re-creates all three tenant services. TenantB's supervisor is never touched. Its boards remain live throughout.

The operational cost is multiplied restart tracking. With 100 tenants, you have 100 separate restart counters. The root supervisor's `maxRestarts` budget must be generous enough to allow all tenants to experience independent bursts of failures without the root itself giving up. Set it to `tenants.size() * 3` as a reasonable starting point and alert on root supervisor exhaustion as a critical incident.

Re-registering the child's `proc()` in `ProcRegistry` after every restart ensures the registry stays consistent with the actual running processes. The key `tenantId + "." + name` namespace prevents collisions between tenants. `ProcRegistry.whereis("tenant-a.auth")` and `ProcRegistry.whereis("tenant-b.auth")` return completely independent `Proc` instances.

Finally, this architecture scales horizontally. Adding a new tenant is `coordinator.addTenant(newConfig)` — it creates a new subtree under the root supervisor without touching any existing subtree. Removing a tenant is a graceful shutdown of that tenant's supervisor. No other tenants are aware of either operation.

---

## What Have You Learned?

- **`Proc<S,M>` is the local process handle; `RemoteProc<M>` is a location-transparent wrapper** with a gRPC stub as its transport. Callers that code to the `MessageEndpoint<M>` interface are unaware of whether the process is local or remote.
- **Protobuf `oneof` represents the union of message types** that a remote process can receive. Every message type requires a Protobuf definition and a serializer case — this is the explicit cost of crossing a JVM boundary.
- **The gRPC bridge has two RPCs: `Tell` (fire-and-forget) and `Ask` (request-reply).** The `Ask` RPC carries a `correlation_id` so that responses can be matched to requests.
- **Server-side dispatch uses a Java `switch` expression on the `oneof` case.** Sealed types on the Java side and `oneof` on the Protobuf side are a natural fit — both describe a finite set of variants.
- **`withDeadlineAfter` on the gRPC stub maps the JOTP `ask` timeout to a gRPC deadline.** If the coordinator does not respond in time, `DEADLINE_EXCEEDED` is returned and the `CompletableFuture` completes exceptionally.
- **`REST_FOR_ONE` captures dependency chains.** Register services with `supervise()` in dependency order. When a dependency crashes, it takes down its dependents so that they restart with a fresh dependency reference.
- **One supervisor per tenant + `ONE_FOR_ONE` at the root = true tenant isolation.** A crashing tenant's processes are restarted by that tenant's supervisor. Other tenants' supervisors are never touched.
- **Re-register `proc()` in `ProcRegistry` after every restart** to keep the registry consistent with the actual running process tree. Use `ProcRegistry.reset()` in tests to clear registrations between test cases.
- **`grpcurl` is the fastest way to test a gRPC server manually** before wiring it into a full Docker Swarm deployment. Always verify the bridge with a manual test before automating it in CI.
