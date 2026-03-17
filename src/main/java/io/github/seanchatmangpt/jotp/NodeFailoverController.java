package io.github.seanchatmangpt.jotp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates failover when a distributed node goes down, migrating registered process
 * specifications to surviving nodes.
 *
 * <p>When a node is declared down via {@link #onNodeDown(String)}, this controller:
 *
 * <ol>
 *   <li>Identifies all processes whose owning node is the failed node
 *   <li>Calls each process factory to recreate the process on the local node
 *   <li>Registers the new process in {@link GlobalProcRegistry}
 *   <li>Fires all registered {@link FailoverEvent} listeners
 *   <li>Updates per-process status to {@link NodeProcStatus#RECOVERED}
 * </ol>
 *
 * <p>Failover execution runs in a virtual thread — {@link #onNodeDown(String)} returns immediately
 * without blocking the caller.
 *
 * <p>Thread-safety: all internal maps use {@link ConcurrentHashMap}. Listener lists use {@link
 * CopyOnWriteArrayList}.
 *
 * <pre>{@code
 * var controller = NodeFailoverController.create();
 * controller.registerSpec("counter", "node2", () -> Proc.spawn(0, (s, m) -> s + 1));
 * controller.onFailover(e -> log.info("Failover: {} from {}", e.procName(), e.fromNode()));
 * controller.onNodeDown("node2"); // non-blocking; runs failover in a virtual thread
 * }</pre>
 *
 * @since 1.0
 * @author JOTP Contributors
 */
public final class NodeFailoverController {

    // ── Inner types ───────────────────────────────────────────────────────────────

    /** Status of a managed process from this controller's perspective. */
    public enum NodeProcStatus {
        /** Process is registered and believed to be running on its owning node. */
        HEALTHY,
        /** Owning node has been declared down; process has not yet been recreated. */
        FAILED,
        /** Failover is in progress — factory called but registration not yet complete. */
        RECOVERING,
        /** Failover completed; process is now registered locally. */
        RECOVERED
    }

    /**
     * Specification for a process that can be failed over to the local node.
     *
     * @param procName logical name of the process
     * @param ownerNode node currently hosting this process
     * @param factory called to create a replacement process on failover
     */
    public record NodeProcSpec(String procName, String ownerNode, Supplier<Proc<?, ?>> factory) {}

    /**
     * Event fired for each successfully failed-over process.
     *
     * @param procName logical name of the process that was migrated
     * @param fromNode node that failed
     * @param toNode node that now hosts the process (this node's {@link
     *     GlobalProcRegistry#nodeName()})
     * @param timestamp when the failover completed
     */
    public record FailoverEvent(
            String procName, String fromNode, String toNode, Instant timestamp) {}

    // ── State ─────────────────────────────────────────────────────────────────────

    /** procName → spec */
    private final ConcurrentHashMap<String, NodeProcSpec> specs = new ConcurrentHashMap<>();

    /** procName → current status */
    private final ConcurrentHashMap<String, NodeProcStatus> statuses = new ConcurrentHashMap<>();

    /** Ordered history of all completed failover events. */
    private final List<FailoverEvent> history = new CopyOnWriteArrayList<>();

    /** Registered listeners, notified on each {@link FailoverEvent}. */
    private final List<Consumer<FailoverEvent>> listeners = new CopyOnWriteArrayList<>();

    /** Active failover threads, tracked so {@link #stop()} can join them. */
    private final List<Thread> activeThreads = new CopyOnWriteArrayList<>();

    private NodeFailoverController() {}

    // ── Factory ───────────────────────────────────────────────────────────────────

    /**
     * Create a new {@code NodeFailoverController} with no registered specs or listeners.
     *
     * @return a fresh controller
     */
    public static NodeFailoverController create() {
        return new NodeFailoverController();
    }

    // ── Spec registration ─────────────────────────────────────────────────────────

    /**
     * Register a failover specification for a named process.
     *
     * <p>When {@code ownerNode} is declared down via {@link #onNodeDown(String)}, the {@code
     * factory} will be invoked to recreate the process on this node.
     *
     * @param procName logical name of the process
     * @param ownerNode node currently hosting this process
     * @param factory called on failover to create a replacement; must not return {@code null}
     */
    public void registerSpec(String procName, String ownerNode, Supplier<Proc<?, ?>> factory) {
        var spec = new NodeProcSpec(procName, ownerNode, factory);
        specs.put(procName, spec);
        statuses.put(procName, NodeProcStatus.HEALTHY);
    }

    // ── Failover trigger ──────────────────────────────────────────────────────────

    /**
     * Declare that {@code nodeName} has failed, triggering failover for all processes whose owning
     * node matches.
     *
     * <p>This method returns immediately; the actual failover work runs in a virtual thread. If no
     * processes are registered for the given node, this is a no-op.
     *
     * @param nodeName the name of the node that has gone down
     */
    public void onNodeDown(String nodeName) {
        List<NodeProcSpec> affected =
                specs.values().stream().filter(s -> s.ownerNode().equals(nodeName)).toList();
        if (affected.isEmpty()) {
            return;
        }
        Thread failoverThread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        for (NodeProcSpec spec : affected) {
                                            try {
                                                failover(spec, nodeName);
                                            } catch (Exception e) {
                                                // Isolate per-proc failures so remaining procs are
                                                // still processed.
                                                statuses.put(
                                                        spec.procName(), NodeProcStatus.FAILED);
                                            }
                                        }
                                    } finally {
                                        activeThreads.remove(Thread.currentThread());
                                    }
                                });
        activeThreads.add(failoverThread);
    }

    /**
     * Notify the controller that {@code nodeName} has come back up.
     *
     * <p>This is a no-op in the current implementation — re-homing decisions after node recovery
     * are left to the application. The method exists to provide a stable API surface for future
     * distributed redistribution logic.
     *
     * @param nodeName the name of the node that has come back online
     */
    public void onNodeUp(String nodeName) {
        // No-op: re-homing after node recovery is application policy.
    }

    // ── Listeners ─────────────────────────────────────────────────────────────────

    /**
     * Subscribe to failover events. The listener is called once per successfully failed-over
     * process, from the virtual thread performing the failover.
     *
     * @param listener called with a {@link FailoverEvent} on each completed failover
     */
    public void onFailover(Consumer<FailoverEvent> listener) {
        listeners.add(listener);
    }

    // ── Queries ───────────────────────────────────────────────────────────────────

    /**
     * Returns the names of all processes registered with this controller.
     *
     * @return unmodifiable snapshot of managed process names
     */
    public Set<String> managedProcs() {
        return Set.copyOf(specs.keySet());
    }

    /**
     * Returns the current status of a managed process.
     *
     * @param procName the registered process name
     * @return the status, or {@link Optional#empty()} if not managed by this controller
     */
    public Optional<NodeProcStatus> statusOf(String procName) {
        return Optional.ofNullable(statuses.get(procName));
    }

    /**
     * Returns an unmodifiable snapshot of all past failover events in the order they completed.
     *
     * @return failover history
     */
    public List<FailoverEvent> failoverHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    /**
     * Stop the controller, waiting for any in-progress failover threads to complete.
     *
     * <p>Failover work runs on per-operation virtual threads. This method joins all active threads
     * so that callers can guarantee no background failover activity remains after {@code stop()}
     * returns.
     */
    public void stop() {
        for (Thread t : activeThreads) {
            if (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        activeThreads.clear();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    private void failover(NodeProcSpec spec, String fromNode) {
        String procName = spec.procName();
        statuses.put(procName, NodeProcStatus.FAILED);

        // Skip if a living process is already registered under this name.
        Optional<Proc<Object, Object>> existing = GlobalProcRegistry.whereis(procName);
        if (existing.isPresent()) {
            statuses.put(procName, NodeProcStatus.RECOVERED);
            return;
        }

        statuses.put(procName, NodeProcStatus.RECOVERING);

        Proc<?, ?> newProc = spec.factory().get();
        GlobalProcRegistry.register(procName, newProc);

        String toNode = GlobalProcRegistry.nodeName();
        FailoverEvent event = new FailoverEvent(procName, fromNode, toNode, Instant.now());
        history.add(event);
        statuses.put(procName, NodeProcStatus.RECOVERED);

        for (Consumer<FailoverEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // Listener crash must not abort other listeners or the failover loop.
            }
        }
    }
}
