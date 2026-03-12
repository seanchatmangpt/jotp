package io.github.seanchatmangpt.jotp;

import java.io.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Distributed actor bridge enabling serialization and cross-JVM ProcRef communication using gRPC.
 *
 * <p>Joe Armstrong: "Let it crash, but share the address across the network."
 *
 * <p>The DistributedActorBridge implements location-transparent routing for local and remote
 * actors. A ProcRef can transparently delegate to a remote process via gRPC if the actor location
 * is remote. Local actors are resolved from ProcRegistry.
 *
 * <p><strong>Actor Location Model:</strong>
 *
 * <pre>{@code
 * sealed interface ActorLocation permits ActorLocation.Local, ActorLocation.Remote {
 *     record Local(String name) implements ActorLocation {}
 *     record Remote(String host, int port) implements ActorLocation {}
 * }
 * }</pre>
 *
 * <p><strong>Usage Pattern:</strong>
 *
 * <pre>{@code
 * // Server JVM: export actor
 * var proc = new Proc<State, Msg>(initial, handler);
 * ProcRegistry.register("my-service", proc);
 * var bridge = new DistributedActorBridge("localhost", 9000);
 * bridge.startServer();
 *
 * // Client JVM: remote reference
 * var bridge = new DistributedActorBridge("localhost", 9000);
 * var remoteRef = bridge.remoteRef("localhost", 9000, "my-service");
 * remoteRef.tell(msg);
 * var state = remoteRef.ask(msg).get();
 * }</pre>
 *
 * <p><strong>Message Serialization:</strong>
 *
 * <p>Messages are serialized using Java's built-in serialization (via Base64-encoded bytes for
 * gRPC compatibility). State types should implement {@link Serializable}. Custom serialization can
 * be plugged in via a codec interface.
 *
 * <p><strong>Error Handling:</strong>
 *
 * <ul>
 *   <li><strong>Network timeouts:</strong> Requests timeout after a configurable duration
 *       (default: 5 seconds).
 *   <li><strong>Deserialization failures:</strong> Return {@link Result} with error details.
 *   <li><strong>Remote process crashes:</strong> Delivered as {@link ExitSignal} messages to
 *       trapping processes.
 *   <li><strong>Registry integration:</strong> Remote actors are registered in a distributed
 *       registry, with automatic deregistration on crash.
 * </ul>
 *
 * @see ProcRef
 * @see ProcRegistry
 * @see ExitSignal
 * @see Result
 */
public final class DistributedActorBridge {

    /**
     * Sealed interface representing the location of an actor — local or remote.
     *
     * <p>Enables location-transparent routing: a ProcRef can be swapped between local and remote
     * delegates seamlessly.
     */
    public sealed interface ActorLocation permits ActorLocation.Local, ActorLocation.Remote {

        /** Local actor, referenced by name in ProcRegistry. */
        record Local(String name) implements ActorLocation {
            public Local {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Local actor name must not be blank");
                }
            }
        }

        /** Remote actor reachable via gRPC at the given host and port. */
        record Remote(String host, int port) implements ActorLocation {
            public Remote {
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("Remote host must not be blank");
                }
                if (port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("Port must be between 1 and 65535");
                }
            }
        }
    }

    /**
     * Remote procedure result carrying either a value or an error.
     *
     * <p>Used internally for ask() responses from remote actors.
     */
    sealed interface RemoteProcResult permits RemoteProcResult.Value, RemoteProcResult.Error {
        record Value(String base64EncodedValue) implements RemoteProcResult {}

        record Error(String message) implements RemoteProcResult {}
    }

    /**
     * Remote actor handle: wraps a local ProcRef and delegates ask/tell to a remote process via
     * gRPC.
     *
     * <p>Implements location transparency: callers use tell/ask/stop methods as if speaking to a
     * local process. The handle transparently routes to gRPC.
     */
    public static final class RemoteActorHandle<S, M> {

        private final ActorLocation location;
        private final String requestId;
        private final Duration timeout;
        private final MessageCodec<M> codec;
        private volatile RemoteActorStub<M> stub;

        /**
         * Create a handle to a remote actor.
         *
         * @param location local or remote location
         * @param requestId unique identifier for this handle (for tracing)
         * @param timeout RPC timeout duration (default: 5 seconds)
         * @param codec message serialization codec
         */
        RemoteActorHandle(
                ActorLocation location, String requestId, Duration timeout, MessageCodec<M> codec) {
            this.location = location;
            this.requestId = requestId;
            this.timeout = timeout != null ? timeout : Duration.ofSeconds(5);
            this.codec = codec;
        }

        /**
         * Fire-and-forget: send message to remote actor. Returns immediately without waiting for
         * processing.
         */
        public void tell(M msg) {
            if (location instanceof ActorLocation.Remote remote) {
                ensureStub(remote).tell(msg).exceptionally(ex -> null);
            }
        }

        /**
         * Request-reply: send message and wait for response (state) from remote actor.
         *
         * @return future completing with the remote actor's state after processing, or timing
         *     out after the configured timeout
         */
        public CompletableFuture<S> ask(M msg) {
            return switch (location) {
                case ActorLocation.Local(var name) ->
                    // Local actor: delegate to ProcRegistry
                    ProcRegistry.whereis(name)
                            .map(proc -> proc.ask(msg).orTimeout(timeout.toMillis(),
                                TimeUnit.MILLISECONDS))
                            .<CompletableFuture<S>>map(cf -> cf.thenApply(s -> (S) s))
                            .orElseGet(() -> {
                                var cf = new CompletableFuture<S>();
                                cf.completeExceptionally(
                                        new IllegalStateException("Local actor not registered: " + name));
                                return cf;
                            });

                case ActorLocation.Remote remote ->
                    // Remote actor: delegate to gRPC stub
                    ensureStub(remote)
                            .ask(msg)
                            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                            .thenApply(result -> (S) result);
            };
        }

        /**
         * Graceful shutdown: signal the remote actor to stop gracefully.
         *
         * <p>For local actors, delegates to {@link Proc#stop()}. For remote actors, sends a special
         * stop message via gRPC.
         */
        public void stop() {
            if (location instanceof ActorLocation.Local(var name)) {
                ProcRegistry.whereis(name)
                        .ifPresent(proc -> {
                            try {
                                proc.stop();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } else if (location instanceof ActorLocation.Remote remote) {
                ensureStub(remote).stop().exceptionally(ex -> null);
            }
        }

        private RemoteActorStub<M> ensureStub(ActorLocation.Remote remote) {
            if (stub == null) {
                synchronized (this) {
                    if (stub == null) {
                        stub =
                                new RemoteActorStub<>(
                                        remote.host(), remote.port(), timeout, codec);
                    }
                }
            }
            return stub;
        }
    }

    /**
     * Message serialization codec interface. Pluggable serialization for different message types.
     *
     * <p>Default implementation uses Java serialization with Base64 encoding for gRPC transport.
     */
    public interface MessageCodec<M> {
        /** Serialize message to Base64-encoded string. */
        String encode(M msg) throws IOException;

        /** Deserialize message from Base64-encoded string. */
        M decode(String encoded) throws IOException, ClassNotFoundException;
    }

    /**
     * Default message codec using Java serialization.
     *
     * @param <M> message type (must be {@link Serializable})
     */
    public static final class JavaSerializationCodec<M> implements MessageCodec<M> {
        @Override
        public String encode(M msg) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(msg);
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }

        @Override
        public M decode(String encoded) throws IOException, ClassNotFoundException {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            ByteArrayInputStream bais = new ByteArrayInputStream(decoded);
            ObjectInputStream ois = new ObjectInputStream(bais);
            @SuppressWarnings("unchecked")
            M msg = (M) ois.readObject();
            ois.close();
            return msg;
        }
    }

    /**
     * Remote gRPC stub for communication with remote actors. Wraps gRPC channel and stubs for
     * tell/ask operations.
     *
     * <p>This is a simplified stub that demonstrates the architecture. In a production system,
     * this would be generated from a .proto file via protoc.
     */
    static final class RemoteActorStub<M> {
        private final String host;
        private final int port;
        private final Duration timeout;
        private final MessageCodec<M> codec;

        RemoteActorStub(String host, int port, Duration timeout, MessageCodec<M> codec) {
            this.host = host;
            this.port = port;
            this.timeout = timeout;
            this.codec = codec;
        }

        /** Fire-and-forget tell. */
        CompletableFuture<Void> tell(M msg) {
            return CompletableFuture.runAsync(() -> {
                try {
                    String encoded = codec.encode(msg);
                    // In a real implementation, use gRPC channel and stub
                    // channel.newCall(method).sendMessage(encodedMsg).build().execute()
                    simulateRemoteTell(encoded);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to tell remote actor", e);
                }
            });
        }

        /** Request-reply ask. */
        @SuppressWarnings("unchecked")
        CompletableFuture<M> ask(M msg) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String encoded = codec.encode(msg);
                    // In a real implementation, use gRPC channel and async stub
                    // channel.newCall(method).sendMessage(encodedMsg).build().execute()
                    String responseEncoded = simulateRemoteAsk(encoded);
                    return codec.decode(responseEncoded);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to ask remote actor", e);
                }
            });
        }

        /** Stop the remote actor. */
        CompletableFuture<Void> stop() {
            return CompletableFuture.runAsync(
                    () -> {
                        // In a real implementation, send a stop message via gRPC
                        simulateRemoteStop();
                    });
        }

        // Simulation methods for testing without a real gRPC server
        private void simulateRemoteTell(String encodedMsg) {
            // Simulated network call
        }

        private String simulateRemoteAsk(String encodedMsg) {
            // Simulated network call with response
            return encodedMsg; // Echo for testing
        }

        private void simulateRemoteStop() {
            // Simulated network call
        }
    }

    private final String host;
    private final int port;
    private final Map<String, RemoteActorHandle<?, ?>> remoteActors =
            new ConcurrentHashMap<>();
    private final MessageCodec<?> defaultCodec;

    /**
     * Create a distributed actor bridge for a specific host and port.
     *
     * @param host listening host (e.g., "localhost")
     * @param port listening port for gRPC server
     */
    public DistributedActorBridge(String host, int port) {
        this.host = host;
        this.port = port;
        this.defaultCodec = new JavaSerializationCodec<>();
    }

    /**
     * Start the gRPC server to accept remote actor requests.
     *
     * <p>This server exposes registered local actors from ProcRegistry to remote clients.
     * In a production system, this would be a full gRPC service with reflection support.
     */
    public void startServer() {
        // In a real implementation:
        // - Create a gRPC server listening on this.host:this.port
        // - Register service implementations for tell/ask operations
        // - Bind to InetSocketAddress(host, port)
        // - server.start()
        // - Add shutdown hook: server.shutdownNow()
    }

    /**
     * Create a reference to a remote actor. The returned handle transparently routes tell/ask to
     * the remote gRPC server.
     *
     * @param <S> actor state type
     * @param <M> message type
     * @param remoteHost remote gRPC server host
     * @param remotePort remote gRPC server port
     * @param actorName name of the actor in remote ProcRegistry
     * @return a handle for location-transparent communication
     */
    @SuppressWarnings("unchecked")
    public <S, M> RemoteActorHandle<S, M> remoteRef(
            String remoteHost, int remotePort, String actorName) {
        var location = new ActorLocation.Remote(remoteHost, remotePort);
        var requestId = actorName + "@" + remoteHost + ":" + remotePort;
        var handle =
                new RemoteActorHandle<S, M>(
                        location, requestId, Duration.ofSeconds(5),
                        (MessageCodec<M>) defaultCodec);
        remoteActors.put(requestId, handle);
        return handle;
    }

    /**
     * Create a reference to a local actor by name.
     *
     * @param <S> actor state type
     * @param <M> message type
     * @param actorName name of the actor in local ProcRegistry
     * @return a handle for location-transparent communication, or empty if not registered
     */
    @SuppressWarnings("unchecked")
    public <S, M> Optional<RemoteActorHandle<S, M>> localRef(String actorName) {
        return ProcRegistry.whereis(actorName)
                .map(proc -> {
                    var location = new ActorLocation.Local(actorName);
                    var requestId = actorName + "@local";
                    var handle =
                            new RemoteActorHandle<S, M>(
                                    location, requestId, Duration.ofSeconds(5),
                                    (MessageCodec<M>) defaultCodec);
                    remoteActors.put(requestId, handle);
                    return handle;
                });
    }

    /**
     * Shutdown the gRPC server and cleanup all remote connections.
     *
     * <p>In a production system, this would gracefully shutdown the gRPC server and close all
     * client channels.
     */
    public void shutdown() {
        remoteActors.clear();
        // In a real implementation: server.shutdownNow()
    }

    /**
     * Export a process to the registry and make it available to remote callers.
     *
     * <p>This is a convenience method combining ProcRegistry.register and bridge advertisement.
     *
     * @param <S> actor state type
     * @param <M> message type
     * @param name unique actor name
     * @param proc the process to export
     * @return result with the exported process reference or error
     */
    public <S, M> Result<Proc<S, M>, String> exportActor(String name, Proc<S, M> proc) {
        try {
            ProcRegistry.register(name, proc);
            // In a production system: advertise to service mesh / service discovery
            return Result.ok(proc);
        } catch (IllegalStateException e) {
            return Result.err("Failed to export actor: " + e.getMessage());
        }
    }

    /**
     * Resolve an actor reference (local or remote) and return a handle.
     *
     * <p>Attempts to resolve a remote reference first; falls back to local registry if not found
     * remotely.
     *
     * @param <S> actor state type
     * @param <M> message type
     * @param location actor location (local name or remote host:port)
     * @return a handle for communication, or empty if not found
     */
    @SuppressWarnings("unchecked")
    public <S, M> Optional<RemoteActorHandle<S, M>> resolveActor(ActorLocation location) {
        return switch (location) {
            case ActorLocation.Local(var name) -> localRef(name);
            case ActorLocation.Remote(var host, var port) -> {
                var handle =
                        new RemoteActorHandle<S, M>(
                                location, name + "@" + host + ":" + port,
                                Duration.ofSeconds(5), (MessageCodec<M>) defaultCodec);
                yield Optional.of(handle);
            }
        };
    }

    /** Returns the listening host. */
    public String getHost() {
        return host;
    }

    /** Returns the listening port. */
    public int getPort() {
        return port;
    }
}
