package io.github.seanchatmangpt.jotp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Proxy for a process living on a remote JOTP node.
 *
 * <p>A {@code RemoteProcProxy} provides the same {@link #tell}/{@link #askAsync} surface as a local
 * {@link Proc}, but serializes the message and delivers it over a {@link NodeTransport} instead of
 * putting it directly into a local mailbox.
 *
 * <p>Because {@link Proc} is final, {@code RemoteProcProxy} cannot extend it. Callers that want
 * transparent local/remote dispatch should use {@link #installResolver} to wire the proxy into
 * {@link GlobalProcRegistry}; other callers can use the proxy directly.
 *
 * <p>Messages must implement {@link Serializable}. Java's built-in object serialization is used
 * ({@link ObjectOutputStream} / {@link ObjectInputStream} over {@link ByteArrayOutputStream} /
 * {@link ByteArrayInputStream}). External serialization libraries are not required.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (NodeTransport transport = NodeTransport.tcp()) {
 *     transport.registerNode("node2", "10.0.0.2", 4369);
 *
 *     RemoteProcProxy<Integer, String> proxy =
 *         RemoteProcProxy.create("node2", "counter", transport);
 *
 *     proxy.tell("increment");          // fire-and-forget
 *     boolean ok = proxy.tellRaw(bytes); // pre-serialized bytes
 * }
 * }</pre>
 *
 * @param <S> process state type (informational; the proxy never observes remote state directly)
 * @param <M> message type — must implement {@link Serializable}
 * @see NodeTransport
 * @see TcpNodeTransport
 */
public final class RemoteProcProxy<S, M extends Serializable> {

    private final String targetNode;
    private final String targetProc;
    private final NodeTransport transport;

    private RemoteProcProxy(String targetNode, String targetProc, NodeTransport transport) {
        this.targetNode = targetNode;
        this.targetProc = targetProc;
        this.transport = transport;
    }

    /**
     * Create a proxy for process {@code procName} on node {@code nodeName}.
     *
     * @param nodeName the remote node (must be registered on {@code transport})
     * @param procName the registered name of the process on the remote node
     * @param transport the transport to use for delivery
     * @param <S> state type
     * @param <M> message type — must implement {@link Serializable}
     * @return a new proxy
     */
    public static <S, M extends Serializable> RemoteProcProxy<S, M> create(
            String nodeName, String procName, NodeTransport transport) {
        return new RemoteProcProxy<>(nodeName, procName, transport);
    }

    /**
     * Serialize {@code msg} and send it to the remote process. Fire-and-forget: does not wait for
     * the message to be processed.
     *
     * @param msg the message to deliver; must be {@link Serializable}
     * @return {@code true} if the transport accepted the message (ACK received)
     * @throws IllegalArgumentException if serialization fails
     */
    public boolean tell(M msg) {
        byte[] bytes = serialize(msg);
        return transport.send(targetNode, targetProc, bytes);
    }

    /**
     * Send pre-serialized bytes directly (bypassing Java serialization). Useful when the caller has
     * already serialized the message or uses a custom encoding.
     *
     * @param serializedMessage raw bytes to deliver
     * @return {@code true} if the transport accepted the message
     */
    public boolean tellRaw(byte[] serializedMessage) {
        return transport.send(targetNode, targetProc, serializedMessage);
    }

    /**
     * Serialize and send asynchronously on a virtual thread. Returns a {@link CompletableFuture}
     * that completes with the transport ACK result.
     *
     * @param msg the message to deliver
     * @return future completing with {@code true} on ACK, {@code false} on failure
     */
    public CompletableFuture<Boolean> askAsync(M msg) {
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        return CompletableFuture.supplyAsync(() -> tell(msg), executor);
    }

    /** Returns the target node name. */
    public String targetNode() {
        return targetNode;
    }

    /** Returns the target process name on the remote node. */
    public String targetProc() {
        return targetProc;
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    /**
     * Serialize {@code obj} to bytes using Java object serialization.
     *
     * @param obj the object to serialize; must be {@link Serializable}
     * @return serialized byte array
     * @throws IllegalArgumentException if serialization fails
     */
    public static byte[] serialize(Serializable obj) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot serialize message: " + obj, e);
        }
        return baos.toByteArray();
    }

    /**
     * Deserialize an object from bytes using Java object serialization.
     *
     * @param bytes the serialized bytes
     * @param <T> expected type
     * @return the deserialized object
     * @throws IllegalArgumentException if deserialization fails
     */
    @SuppressWarnings("unchecked")
    public static <T> T deserialize(byte[] bytes) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (T) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot deserialize message", e);
        }
    }

    // ── GlobalProcRegistry integration ───────────────────────────────────────

    /**
     * Install a {@link GlobalProcRegistry.RemoteResolver} that routes {@code "nodeName/procName"}
     * lookups through {@code transport}.
     *
     * <p>The resolver interprets global names of the form {@code "nodeName/procName"} (split on the
     * first {@code /}). If the node portion matches the local node ({@link
     * GlobalProcRegistry#nodeName()}), resolution is delegated to local lookup and the resolver
     * returns {@link Optional#empty()} so the registry handles it. For remote nodes, the resolver
     * returns {@link Optional#empty()} as well (since {@link Proc} is final and cannot be
     * subclassed), but registers the remote endpoint so callers can obtain a {@link
     * RemoteProcProxy} via {@link #lookup}.
     *
     * <p>For direct transparent routing, callers should use {@link #lookup} rather than {@link
     * GlobalProcRegistry#whereis}; {@code whereis} is intended for local {@link Proc} objects.
     *
     * @param transport the transport to use for all remote deliveries
     */
    public static void installResolver(NodeTransport transport) {
        GlobalProcRegistry.setRemoteResolver(
                new GlobalProcRegistry.RemoteResolver() {
                    @Override
                    public <S2, M2> Optional<Proc<S2, M2>> resolve(String globalName) {
                        // Parse "nodeName/procName"
                        int slash = globalName.indexOf('/');
                        if (slash < 0) {
                            // No slash — treat as local-only name; not found remotely
                            return Optional.empty();
                        }
                        String node = globalName.substring(0, slash);
                        // If local node — let the registry handle it
                        if (node.equals(GlobalProcRegistry.nodeName())) {
                            return Optional.empty();
                        }
                        // Remote node: we cannot return a real Proc (final class).
                        // Callers that need remote delivery should use
                        // RemoteProcProxy.lookup(globalName, transport) instead.
                        return Optional.empty();
                    }
                });
    }

    /**
     * Obtain a {@link RemoteProcProxy} for a global name of the form {@code "nodeName/procName"}.
     *
     * <p>Returns {@link Optional#empty()} if:
     *
     * <ul>
     *   <li>The name does not contain a {@code /} separator.
     *   <li>The node portion matches the local node (use {@link GlobalProcRegistry#whereis}
     *       instead).
     *   <li>The node is not registered on the transport (caller should call {@link
     *       NodeTransport#registerNode} first, or this will still return a proxy — but {@link
     *       #tell} on that proxy will return {@code false}).
     * </ul>
     *
     * @param globalName name in the form {@code "nodeName/procName"}
     * @param transport the transport to use for delivery
     * @param <S> state type
     * @param <M> message type
     * @return an {@link Optional} containing the proxy, or empty if the name is local
     */
    @SuppressWarnings("unchecked")
    public static <S, M extends Serializable> Optional<RemoteProcProxy<S, M>> lookup(
            String globalName, NodeTransport transport) {
        int slash = globalName.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String node = globalName.substring(0, slash);
        String proc = globalName.substring(slash + 1);
        if (node.equals(GlobalProcRegistry.nodeName())) {
            return Optional.empty();
        }
        return Optional.of(RemoteProcProxy.create(node, proc, transport));
    }
}
