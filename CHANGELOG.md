# Changelog

## [2026.1.0-Alpha] - 2026-03-17

### Initial Alpha Release

JOTP is a production-ready Java 26 framework implementing all 15 Erlang/OTP primitives for Java developers.

### 15 OTP Primitives

| Primitive | Description |
|-----------|-------------|
| `Proc<S,M>` | Lightweight virtual-thread process with mailbox; pure `(S,M)→S` handler |
| `Supervisor` | Fault-tolerant process tree; ONE_FOR_ONE / ONE_FOR_ALL / REST_FOR_ONE strategies |
| `StateMachine<S,E,D>` | Full gen_statem contract; sealed S and E required for exhaustive switches |
| `ProcRef<S,M>` | Stable handle that survives supervisor restarts |
| `ProcMonitor` | One-way DOWN notification |
| `ProcLink` | Bidirectional crash propagation between two processes |
| `ProcRegistry` | Name-based process lookup |
| `ProcTimer` | Scheduled message delivery to a Proc |
| `ProcSys` | Live introspection: suspend/resume/statistics |
| `ProcLib` | Process utility functions (init_ack handshake pattern) |
| `CrashRecovery` | Wraps supplier in isolated virtual thread; returns `Result<T, Exception>` |
| `Parallel` | Structured concurrency via `StructuredTaskScope` |
| `EventManager<E>` | Typed pub-sub event bus; handler crash doesn't kill bus |
| `Result<T,E>` | Railway-oriented error handling with sealed Ok/Err variants |
| `ExitSignal` | Exit reason carrier for linked/monitored processes (trap_exit pattern) |

### New in This Release

#### Distributed Infrastructure
- `NodeHeartbeat` — TCP server responding to heartbeat pings with node name
- `NodeFailureDetector` — Monitors remote nodes, fires onNodeDown/onNodeUp callbacks
- `NodeFailoverController` — Migrates proc registrations on node failure
- `NodeTransport` — Pluggable transport interface for node communication
- `TcpNodeTransport` — TCP transport with 4-byte length-prefixed framing
- `RemoteProcProxy` — Transparent proxy for remote proc communication

#### Event Infrastructure
- `EventStreamQuery` — Fluent query builder over EventStore with pagination, filtering, grouping
- `MessageRecorder` — Wraps a Proc and records all messages with sequence numbers
- `MessageReplayer` — Replays recorded message log against fresh process

#### Fault Testing
- `FaultInjectionSupervisor` — Wraps Supervisor to inject controlled faults for testing

#### Distributed Registry
- `GlobalProcRegistry` — Cluster-wide process registry with pluggable backends
- Persistent backend: RocksDB-backed with dual-write crash safety

### Technical Requirements
- Java 26 with `--enable-preview`
- Maven 4
- Module: `io.github.seanchatmangpt.jotp`

### Design Principles
- **Let It Crash**: Supervisors restart failed processes
- **Message Passing**: No shared state, communicate via immutable messages
- **Supervision Trees**: Hierarchical restart strategies contain failures
- **Virtual Threads**: Millions of lightweight processes (~1KB heap each)
- **Sealed Types**: Type-safe message protocols at compile time
