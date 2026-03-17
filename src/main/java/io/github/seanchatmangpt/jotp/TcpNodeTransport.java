package io.github.seanchatmangpt.jotp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP-based implementation of {@link NodeTransport}.
 *
 * <p>Uses a simple connection-per-message protocol: each {@link #send} opens a fresh TCP connection
 * to the target node, writes the framed message, reads a 1-byte ACK, then closes the connection.
 * This keeps the implementation simple at the cost of connection-setup overhead per message.
 *
 * <h2>Wire frame (sender → receiver)</h2>
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 4 bytes  │ big-endian length of procName UTF-8               │
 * │ N bytes  │ procName encoded as UTF-8                         │
 * │ 4 bytes  │ big-endian length of serializedMessage            │
 * │ M bytes  │ serializedMessage bytes                           │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Wire frame (receiver → sender, ACK)</h2>
 *
 * <pre>
 * ┌──────────────────────┐
 * │ 1 byte │ 0x01 = ACK  │
 * └──────────────────────┘
 * </pre>
 *
 * <p>The receiver runs on a virtual thread per connection and dispatches incoming messages to the
 * provided {@link NodeTransport.MessageHandler}.
 *
 * <p>Port {@code 0} is supported in {@link #startReceiver}: the OS assigns an ephemeral port. Use
 * {@link #getPort()} to retrieve the actual bound port after {@code startReceiver} returns.
 */
public final class TcpNodeTransport implements NodeTransport {

    private static final byte ACK = 0x01;

    /** nodeName → endpoint record */
    private final ConcurrentHashMap<String, Endpoint> nodes = new ConcurrentHashMap<>();

    private volatile ServerSocket serverSocket = null;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Immutable endpoint descriptor. */
    private record Endpoint(String host, int port) {}

    // ── NodeTransport: registry ───────────────────────────────────────────────

    @Override
    public void registerNode(String nodeName, String host, int port) {
        nodes.put(nodeName, new Endpoint(host, port));
    }

    @Override
    public void unregisterNode(String nodeName) {
        nodes.remove(nodeName);
    }

    @Override
    public Set<String> knownNodes() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    // ── NodeTransport: send ───────────────────────────────────────────────────

    /**
     * Send {@code serializedMessage} to process {@code procName} on node {@code nodeName}.
     *
     * <p>Opens a new TCP connection, writes the framed message, waits for a 1-byte ACK, then closes
     * the connection. Returns {@code false} on any I/O error.
     */
    @Override
    public boolean send(String nodeName, String procName, byte[] serializedMessage) {
        Endpoint ep = nodes.get(nodeName);
        if (ep == null) {
            return false;
        }
        try (Socket socket = new Socket(ep.host(), ep.port())) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            byte[] nameBytes = procName.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.writeInt(serializedMessage.length);
            out.write(serializedMessage);
            out.flush();

            // Wait for ACK
            int ack = socket.getInputStream().read();
            return ack == ACK;
        } catch (IOException e) {
            return false;
        }
    }

    // ── NodeTransport: receiver ───────────────────────────────────────────────

    /**
     * Start a virtual-thread TCP server that accepts inbound messages and dispatches them to {@code
     * handler}. Uses port {@code 0} for ephemeral assignment when requested.
     *
     * <p>Each accepted connection is handled on its own virtual thread: read frame → dispatch →
     * send ACK → close.
     *
     * @throws IOException if the server socket cannot be bound
     */
    @Override
    public void startReceiver(String localNodeName, int port, MessageHandler handler)
            throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("Transport is closed");
        }
        ServerSocket ss = new ServerSocket(port);
        serverSocket = ss;

        Thread.ofVirtual()
                .name("tcp-receiver-" + localNodeName)
                .start(
                        () -> {
                            while (!ss.isClosed()) {
                                try {
                                    Socket conn = ss.accept();
                                    Thread.ofVirtual()
                                            .name("tcp-conn-" + localNodeName)
                                            .start(() -> handleConnection(conn, handler));
                                } catch (IOException e) {
                                    if (!ss.isClosed()) {
                                        // Unexpected error — log and continue accepting
                                        Thread.currentThread()
                                                .getUncaughtExceptionHandler()
                                                .uncaughtException(Thread.currentThread(), e);
                                    }
                                    // If closed, exit the accept loop silently
                                }
                            }
                        });
    }

    /** Handle a single inbound connection: read frame, dispatch to handler, write ACK, close. */
    private static void handleConnection(Socket conn, MessageHandler handler) {
        try (conn) {
            DataInputStream in = new DataInputStream(conn.getInputStream());

            int nameLen = in.readInt();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String procName = new String(nameBytes, StandardCharsets.UTF_8);

            int msgLen = in.readInt();
            byte[] msgBytes = new byte[msgLen];
            in.readFully(msgBytes);

            handler.onMessage(procName, msgBytes);

            conn.getOutputStream().write(ACK);
            conn.getOutputStream().flush();
        } catch (IOException e) {
            // Connection reset or malformed frame — drop silently
        }
    }

    // ── Port introspection ────────────────────────────────────────────────────

    /**
     * Returns the actual port the receiver is bound to. Must be called after {@link
     * #startReceiver}.
     *
     * @return the bound port, or {@code -1} if the receiver has not been started
     */
    public int getPort() {
        ServerSocket ss = serverSocket;
        if (ss == null) {
            return -1;
        }
        return ss.getLocalPort();
    }

    // ── AutoCloseable ─────────────────────────────────────────────────────────

    /** Close the receiver (if started) and release all resources. Idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // already closed
        }
        ServerSocket ss = serverSocket;
        if (ss != null && !ss.isClosed()) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // Best-effort close
            }
        }
    }

    /** Returns an unmodifiable view of the internal node map for inspection (testing). */
    Map<String, ?> nodeMap() {
        return Collections.unmodifiableMap(nodes);
    }
}
