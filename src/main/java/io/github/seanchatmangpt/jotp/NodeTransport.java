package io.github.seanchatmangpt.jotp;

import java.util.Set;

/**
 * Pluggable transport abstraction for sending messages between JOTP nodes.
 *
 * <p>A {@code NodeTransport} encapsulates the network-level concerns of node-to-node message
 * delivery. Callers register known remote node endpoints via {@link #registerNode}, then call
 * {@link #send} to deliver serialized messages. The transport is responsible for framing,
 * connection management, and acknowledgement.
 *
 * <p>Implementations must be thread-safe — {@link #send} may be called concurrently from many
 * virtual threads.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (NodeTransport t = NodeTransport.tcp()) {
 *     t.registerNode("node2", "10.0.0.2", 4369);
 *     t.startReceiver("node1", 4369, (procName, bytes) -> dispatch(procName, bytes));
 *     t.send("node2", "counter", serialized);
 * }
 * }</pre>
 *
 * @see TcpNodeTransport
 * @see RemoteProcProxy
 */
public interface NodeTransport extends AutoCloseable {

    /**
     * Send a serialized message to a named process on a remote node.
     *
     * @param nodeName the target node name (must be registered via {@link #registerNode})
     * @param procName the name of the process on the target node
     * @param serializedMessage the serialized message bytes
     * @return {@code true} if the message was accepted by the remote receiver (ACK received);
     *     {@code false} if delivery failed (node unreachable, connection refused, etc.)
     */
    boolean send(String nodeName, String procName, byte[] serializedMessage);

    /**
     * Start a receiver on the given port that accepts incoming messages and dispatches them to
     * {@code handler}. Binds to all interfaces. Accepts connections on virtual threads.
     *
     * <p>Passing port {@code 0} causes the OS to assign an ephemeral port; use {@link
     * TcpNodeTransport#getPort()} to retrieve the actual bound port.
     *
     * @param localNodeName the local node name (informational, used in logs)
     * @param port the TCP port to listen on; {@code 0} for ephemeral
     * @param handler called for every received message
     * @throws java.io.IOException if the server socket cannot be opened
     */
    void startReceiver(String localNodeName, int port, MessageHandler handler)
            throws java.io.IOException;

    /**
     * Callback interface invoked by the transport receiver for each inbound message.
     *
     * <p>Implementations must be thread-safe and non-blocking. Heavy processing should be offloaded
     * to a virtual thread or a {@link Proc}.
     */
    interface MessageHandler {
        /**
         * Called when a message arrives for a local process.
         *
         * @param procName the destination process name
         * @param serializedMessage the raw message bytes (as sent by the remote side)
         */
        void onMessage(String procName, byte[] serializedMessage);
    }

    /**
     * Register a remote node endpoint. Subsequent {@link #send} calls to {@code nodeName} will use
     * this host/port.
     *
     * @param nodeName logical name for the remote node
     * @param host hostname or IP address
     * @param port TCP port
     */
    void registerNode(String nodeName, String host, int port);

    /**
     * Unregister a previously registered remote node. After this call, {@link #send} to {@code
     * nodeName} will fail.
     *
     * @param nodeName the node to remove
     */
    void unregisterNode(String nodeName);

    /**
     * Returns a snapshot of all currently registered remote node names.
     *
     * @return unmodifiable set of node names
     */
    Set<String> knownNodes();

    /**
     * Close this transport, stopping the receiver (if started) and releasing all resources.
     * Idempotent — safe to call multiple times.
     */
    @Override
    void close();

    /**
     * Factory method: create a new TCP-based transport.
     *
     * @return a new {@link TcpNodeTransport}
     */
    static NodeTransport tcp() {
        return new TcpNodeTransport();
    }
}
