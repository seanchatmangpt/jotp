package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stable opaque handle to a supervised process — mirrors Erlang's {@code Pid}.
 *
 * <p>Joe Armstrong: "A process identifier should be opaque. Callers must not know or care whether
 * the process has restarted."
 *
 * <p>When a {@link Supervisor} restarts a crashed child, it atomically {@link #swap swaps} the
 * underlying {@link Proc}. All existing {@code ProcRef} handles transparently redirect to the new
 * process without any caller changes. This is Erlang location-transparency in Java.
 *
 * <p><b>Distributed Resolution:</b> A ProcRef can optionally be associated with a
 * {@link ProcessServiceDiscoveryProvider} for cluster-wide process lookup. This enables remote
 * processes to be discovered and messaged via {@link DistributedActorBridge}.
 *
 * @param <S> process state type
 * @param <M> message type (use a {@code Record} or sealed-Record hierarchy)
 */
public final class ProcRef<S, M> {

    private volatile Proc<S, M> delegate;

    /**
     * Snapshot of the most recent crashed proc (set before swap). Allows external observers to see
     * {@code lastError} on the crashed proc even after the delegate has been replaced.
     */
    private volatile Proc<S, M> lastCrashedProc;

    /**
     * Optional service discovery provider for remote process resolution.
     */
    private final Optional<ProcessServiceDiscoveryProvider> discoveryProvider;

    /**
     * Optional current node ID (used with discovery provider for remote routing).
     */
    private final Optional<NodeId> currentNodeId;

    /**
     * Create a local process reference.
     *
     * @param initial the initial process delegate
     */
    public ProcRef(Proc<S, M> initial) {
        this.delegate = initial;
        this.discoveryProvider = Optional.empty();
        this.currentNodeId = Optional.empty();
    }

    /**
     * Create a process reference with distributed discovery support.
     *
     * @param initial the initial process delegate
     * @param discoveryProvider optional provider for remote process lookup
     * @param currentNodeId the node ID of this process (required if discoveryProvider is present)
     */
    public ProcRef(
            Proc<S, M> initial,
            ProcessServiceDiscoveryProvider discoveryProvider,
            NodeId currentNodeId) {
        this.delegate = initial;
        this.discoveryProvider = Optional.ofNullable(discoveryProvider);
        this.currentNodeId = Optional.ofNullable(currentNodeId);
    }

    /**
     * Replace the underlying process (called by {@link Supervisor} on restart).
     *
     * <p>Records the previous (crashed) proc in {@link #lastCrashedProc} before swapping, so
     * observers waiting for {@code proc().lastError != null} can detect the crash event.
     */
    void swap(Proc<S, M> next) {
        this.lastCrashedProc = this.delegate;
        this.delegate = next;
    }

    /**
     * Fire-and-forget: enqueue {@code msg} without blocking.
     *
     * <p>If the process is mid-restart, the message goes to the stale process and is lost — callers
     * should use {@link #ask} with Awaitility retries if delivery during restart matters.
     */
    public void tell(M msg) {
        delegate.tell(msg);
    }

    /**
     * Request-reply: returns a {@link CompletableFuture} that completes with the process's state
     * after {@code msg} is processed. Times out naturally if the process is restarting.
     */
    public CompletableFuture<S> ask(M msg) {
        return delegate.ask(msg);
    }

    /**
     * Request-reply with timeout: returns a {@link CompletableFuture} that completes with the
     * process's state after {@code msg} is processed, or times out after the specified duration.
     *
     * @param msg the message to send
     * @param timeout maximum time to wait for response
     * @return a CompletableFuture that completes with the state or times out
     */
    public CompletableFuture<S> ask(M msg, Duration timeout) {
        return delegate.ask(msg).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Gracefully stop this process. Does <em>not</em> notify the supervisor — use {@link
     * Supervisor#shutdown()} to stop the whole tree.
     */
    public void stop() throws InterruptedException {
        delegate.stop();
    }

    /**
     * Returns the underlying {@link Proc} delegate, or the last crashed proc if a crash has been
     * recorded and not yet observed.
     *
     * <p>After a supervisor restart, returns the previously-crashed proc (which has {@code
     * lastError} set) until it has been observed at least once. This allows external observers
     * (e.g. Awaitility conditions) to detect crash events even after the live delegate has been
     * replaced. Subsequent calls return the current live delegate.
     *
     * <p>Primarily intended for monitoring infrastructure. Prefer {@link #tell} / {@link #ask} for
     * normal message-passing use.
     */
    public Proc<S, M> proc() {
        Proc<S, M> crashed = lastCrashedProc;
        if (crashed != null && crashed.lastError() != null) {
            return crashed;
        }
        return delegate;
    }

    /**
     * Tell a remote process by name (requires discovery provider).
     *
     * <p>Resolves the process name using the discovery provider:
     * <ol>
     *   <li>Check local ProcRegistry (fast path, ~625ns)
     *   <li>Check cache (if available)
     *   <li>Query discovery provider (50-100μs)
     *   <li>Route to DistributedActorBridge if remote
     * </ol>
     *
     * <p>If the process is on the current node, the message goes to the local delegate.
     * If remote, a DistributedActorBridge is used.
     *
     * <p>Network timeouts are handled gracefully: if the discovery provider times out,
     * the message is dropped (fire-and-forget).
     *
     * @param processName the globally unique process name
     * @param msg the message to send
     * @throws IllegalStateException if no discovery provider is configured
     */
    public void tellRemote(String processName, M msg) {
        if (discoveryProvider.isEmpty()) {
            throw new IllegalStateException("tellRemote() requires a discovery provider");
        }

        ProcessServiceDiscoveryProvider provider = discoveryProvider.get();

        // Fast path: check local registry first
        Optional<Proc<?, ?>> localProc = ProcRegistry.whereis(processName);
        if (localProc.isPresent()) {
            @SuppressWarnings("unchecked")
            Proc<S, M> local = (Proc<S, M>) localProc.get();
            local.tell(msg);
            return;
        }

        // Slow path: query discovery provider
        try {
            Optional<NodeId> remoteNode = provider.lookup(processName);
            if (remoteNode.isPresent() && !remoteNode.get().equals(currentNodeId.orElse(null))) {
                // Remote process: route via DistributedActorBridge
                routeToRemote(processName, remoteNode.get(), msg);
            } else if (remoteNode.isEmpty()) {
                // Not found: silently drop (fire-and-forget)
                System.err.println("Process not found: " + processName);
            }
        } catch (Exception e) {
            // Network timeout or error: drop message
            System.err.println("Failed to resolve process " + processName + ": " + e);
        }
    }

    /**
     * Ask a remote process by name (requires discovery provider).
     *
     * <p>Similar to {@link #tellRemote} but returns a CompletableFuture with the response.
     * Resolves process location and routes to local or remote handler.
     *
     * @param processName the globally unique process name
     * @param msg the message to send
     * @param timeout timeout for the ask operation
     * @return CompletableFuture completing with the remote process's state, or empty if not found
     * @throws IllegalStateException if no discovery provider is configured
     */
    public CompletableFuture<S> askRemote(String processName, M msg, Duration timeout) {
        if (discoveryProvider.isEmpty()) {
            CompletableFuture<S> cf = new CompletableFuture<>();
            cf.completeExceptionally(
                    new IllegalStateException("askRemote() requires a discovery provider"));
            return cf;
        }

        ProcessServiceDiscoveryProvider provider = discoveryProvider.get();

        // Fast path: check local registry first
        Optional<Proc<?, ?>> localProc = ProcRegistry.whereis(processName);
        if (localProc.isPresent()) {
            @SuppressWarnings("unchecked")
            Proc<S, M> local = (Proc<S, M>) localProc.get();
            return local.ask(msg).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        // Slow path: query discovery provider
        try {
            Optional<NodeId> remoteNode = provider.lookup(processName);
            if (remoteNode.isPresent() && !remoteNode.get().equals(currentNodeId.orElse(null))) {
                return askRemote(processName, remoteNode.get(), msg, timeout);
            } else {
                CompletableFuture<S> cf = new CompletableFuture<>();
                cf.completeExceptionally(
                        new IllegalStateException("Process not found: " + processName));
                return cf;
            }
        } catch (Exception e) {
            CompletableFuture<S> cf = new CompletableFuture<>();
            cf.completeExceptionally(
                    new RuntimeException("Failed to resolve process " + processName, e));
            return cf;
        }
    }

    /**
     * Returns the discovery provider (if configured).
     *
     * @return Optional containing the discovery provider
     */
    public Optional<ProcessServiceDiscoveryProvider> discoveryProvider() {
        return discoveryProvider;
    }

    /**
     * Returns the current node ID (if configured).
     *
     * @return Optional containing the node ID
     */
    public Optional<NodeId> nodeId() {
        return currentNodeId;
    }

    // ── Private helpers ──

    @SuppressWarnings("unchecked")
    private void routeToRemote(String processName, NodeId remoteNode, M msg) {
        // Use DistributedActorBridge to route to remote process
        // This is a placeholder; in production, a bridge instance would be injected
        var bridge =
                new DistributedActorBridge(
                        remoteNode.host(), remoteNode.port());
        var handle =
                bridge.<S, M>remoteRef(
                        remoteNode.host(), remoteNode.port(), processName);
        handle.tell(msg);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<S> askRemote(
            String processName, NodeId remoteNode, M msg, Duration timeout) {
        // Use DistributedActorBridge to route to remote process
        var bridge =
                new DistributedActorBridge(
                        remoteNode.host(), remoteNode.port());
        var handle =
                bridge.<S, M>remoteRef(
                        remoteNode.host(), remoteNode.port(), processName);
        return handle.ask(msg).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
