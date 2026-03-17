package io.github.seanchatmangpt.jotp;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Node-wide process name registry with a hook for distributed resolution.
 *
 * <p>Extends the single-JVM {@link ProcRegistry} concept with node-prefixed name display and a
 * pluggable {@link RemoteResolver} hook for future distributed lookup. In phase 1 (in-JVM), this
 * is functionally identical to {@link ProcRegistry} but provides a stable API surface for
 * distributed extensions without breaking callers.
 *
 * <p>Node name is set via the {@code jotp.node.name} system property (default: {@code "local"}).
 * All registered names are stored without the node prefix; the prefix is only applied in
 * {@link #allNames()} for distributed display purposes.
 *
 * <p>Like {@link ProcRegistry}, registrations are automatically removed when the process
 * terminates — normal or abnormal.
 *
 * <p><b>Phase 1 usage (in-JVM):</b>
 *
 * <pre>{@code
 * var proc = Proc.spawn(0, (state, msg) -> state + 1);
 * GlobalProcRegistry.register("counter", proc);
 *
 * GlobalProcRegistry.whereis("counter")
 *     .ifPresent(p -> p.tell(1));
 *
 * // Display all registered names with node prefix
 * Set<String> names = GlobalProcRegistry.allNames();
 * // e.g. {"local/counter"}
 * }</pre>
 *
 * <p><b>Phase 2 usage (distributed):</b>
 *
 * <pre>{@code
 * // Register a resolver that queries remote nodes
 * GlobalProcRegistry.setRemoteResolver(globalName -> {
 *     // ... lookup via gRPC, Consul, etc.
 *     return Optional.empty(); // if not found remotely
 * });
 * }</pre>
 *
 * @see ProcRegistry
 */
public final class GlobalProcRegistry {

    private GlobalProcRegistry() {}

    private static final ConcurrentHashMap<String, Proc<?, ?>> REGISTRY =
            new ConcurrentHashMap<>();

    private static volatile RemoteResolver remoteResolver = null;

    // ── Node identity ─────────────────────────────────────────────────────────────

    /**
     * Returns the local node name, read from the {@code jotp.node.name} system property. Defaults
     * to {@code "local"} if not set.
     *
     * <p>Set the property at JVM startup: {@code -Djotp.node.name=node1}.
     */
    public static String nodeName() {
        return System.getProperty("jotp.node.name", "local");
    }

    // ── Core operations ───────────────────────────────────────────────────────────

    /**
     * Register {@code proc} under {@code name} in the global registry.
     *
     * <p>The registration is automatically removed when {@code proc} terminates (any reason).
     *
     * @param name unique name for this process
     * @param proc the process to register
     * @throws IllegalStateException if {@code name} is already registered to a living process
     */
    public static void register(String name, Proc<?, ?> proc) {
        if (REGISTRY.putIfAbsent(name, proc) != null) {
            throw new IllegalStateException("Name already registered globally: " + name);
        }
        proc.addTerminationCallback(_ -> REGISTRY.remove(name, proc));
    }

    /**
     * Look up a process by name, checking the local registry first and then the {@link
     * RemoteResolver} if one is set.
     *
     * @param name the registered name
     * @param <S> process state type
     * @param <M> process message type
     * @return the process if found and alive; empty otherwise
     */
    @SuppressWarnings("unchecked")
    public static <S, M> Optional<Proc<S, M>> whereis(String name) {
        Proc<S, M> proc = (Proc<S, M>) REGISTRY.get(name);
        if (proc != null) {
            if (!proc.thread().isAlive()) {
                REGISTRY.remove(name, proc); // eager cleanup of stale entry
                return Optional.empty();
            }
            return Optional.of(proc);
        }
        // Phase 2: fall through to remote resolver
        if (remoteResolver != null) {
            return remoteResolver.resolve(name);
        }
        return Optional.empty();
    }

    /**
     * Explicitly remove a name from the registry. The process itself is not stopped.
     *
     * @param name the name to remove
     */
    public static void unregister(String name) {
        REGISTRY.remove(name);
    }

    /**
     * Returns all registered names formatted as {@code "nodeName/procName"} — suitable for
     * distributed display. Only reflects processes registered on THIS node.
     *
     * @return unmodifiable snapshot of all registered names with node prefix
     */
    public static Set<String> allNames() {
        String node = nodeName();
        return REGISTRY.keySet().stream()
                .map(name -> node + "/" + name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns just the local registered names (without node prefix).
     *
     * @return unmodifiable snapshot of local registered names
     */
    public static Set<String> localNames() {
        return Set.copyOf(REGISTRY.keySet());
    }

    // ── Phase 2: distributed resolution ──────────────────────────────────────────

    /**
     * Pluggable hook for distributed process lookup.
     *
     * <p>When {@link #whereis} finds no local registration, it calls this resolver. Implementations
     * may query a gRPC peer, Consul service registry, or any other distributed store.
     *
     * <p>Return {@link Optional#empty()} if the process is not found on any remote node.
     */
    public interface RemoteResolver {
        /**
         * Resolve a global name to a process. May return a proxy {@link Proc} that delegates
         * message delivery to the remote node.
         *
         * @param globalName the name to resolve
         * @param <S> process state type
         * @param <M> process message type
         * @return the process if found remotely; empty otherwise
         */
        <S, M> Optional<Proc<S, M>> resolve(String globalName);
    }

    /**
     * Set the remote resolver for distributed lookup. Pass {@code null} to clear.
     *
     * @param resolver the remote resolver, or {@code null} to disable remote resolution
     */
    public static void setRemoteResolver(RemoteResolver resolver) {
        remoteResolver = resolver;
    }

    /** Returns the current remote resolver, or {@code null} if none is set. */
    public static RemoteResolver remoteResolver() {
        return remoteResolver;
    }

    // ── Test support ──────────────────────────────────────────────────────────────

    /** Clear all registrations and reset the remote resolver. For use in tests only. */
    public static void reset() {
        REGISTRY.clear();
        remoteResolver = null;
    }
}
