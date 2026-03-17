package io.github.seanchatmangpt.jotp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP heartbeat server for in-JVM node liveness monitoring.
 *
 * <p>Listens on a TCP port and responds to heartbeat pings from {@link NodeFailureDetector}. Each
 * connection receives a 1-byte ping (0x01) and responds with the node name as UTF-8 bytes followed
 * by a newline character.
 *
 * <p>Uses virtual threads: one acceptor thread that spawns one handler thread per incoming
 * connection.
 *
 * <p>Erlang analogue: {@code net_kernel} heartbeat mechanism used by distributed Erlang nodes to
 * detect node failures via the EPMD ticker interval.
 *
 * <pre>{@code
 * NodeHeartbeat heartbeat = NodeHeartbeat.start("node1", 9000);
 * // heartbeat running: accepts TCP connections, responds with node name + timestamp
 *
 * heartbeat.nodeName();  // "node1"
 * heartbeat.port();      // 9000
 * heartbeat.isRunning(); // true
 * heartbeat.stop();      // shuts down gracefully
 * }</pre>
 *
 * <p>Port 0 causes the OS to assign a free ephemeral port; use {@link #port()} to retrieve the
 * actual port after startup.
 *
 * @see NodeFailureDetector
 */
public final class NodeHeartbeat implements AutoCloseable {

    private final String nodeName;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread acceptorThread;

    private NodeHeartbeat(String nodeName, int port) throws IOException {
        this.nodeName = nodeName;
        this.serverSocket = new ServerSocket(port);
        this.acceptorThread = startAcceptor();
    }

    /**
     * Start a heartbeat server for the given node name on the specified port.
     *
     * @param nodeName the logical node name to advertise in heartbeat responses
     * @param port the TCP port to listen on; use 0 for OS-assigned ephemeral port
     * @return a running {@code NodeHeartbeat} instance
     * @throws IOException if the server socket cannot be opened
     */
    public static NodeHeartbeat start(String nodeName, int port) throws IOException {
        return new NodeHeartbeat(nodeName, port);
    }

    /** Returns the logical node name advertised in heartbeat responses. */
    public String nodeName() {
        return nodeName;
    }

    /**
     * Returns the TCP port this heartbeat server is listening on. When constructed with port 0 this
     * returns the OS-assigned ephemeral port.
     */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Returns {@code true} if the heartbeat server is currently accepting connections. */
    public boolean isRunning() {
        return running.get() && !serverSocket.isClosed();
    }

    /**
     * Stop the heartbeat server and close the underlying server socket.
     *
     * <p>In-progress handler threads are interrupted but allowed to complete their current response
     * before the acceptor exits.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing is best-effort
            }
            acceptorThread.interrupt();
        }
    }

    /** Implements {@link AutoCloseable} — delegates to {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Thread startAcceptor() {
        Thread t =
                Thread.ofVirtual()
                        .name("jotp-heartbeat-acceptor-" + nodeName)
                        .start(this::acceptLoop);
        return t;
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual()
                        .name("jotp-heartbeat-handler-" + nodeName)
                        .start(() -> handleConnection(client));
            } catch (IOException e) {
                if (running.get()) {
                    // unexpected error while still running — log and continue
                    Thread.currentThread().interrupt();
                }
                // if not running, server was stopped — exit the loop
                break;
            }
        }
    }

    private void handleConnection(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            int ping = in.read();
            if (ping == 0x01) {
                // Respond: nodeName + '\n' + timestamp millis + '\n'
                String response = nodeName + "\n" + Instant.now().toEpochMilli() + "\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // client disconnected mid-handshake — treat as failed heartbeat
        }
    }
}
