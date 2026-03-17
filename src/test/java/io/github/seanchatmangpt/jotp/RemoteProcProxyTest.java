package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RemoteProcProxy + TcpNodeTransport: distributed message passing")
class RemoteProcProxyTest {

    private static final Duration AWAIT = Duration.ofSeconds(10);

    // Transports created per test; closed in @AfterEach
    private final List<TcpNodeTransport> transports = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        GlobalProcRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        for (TcpNodeTransport t : transports) {
            t.close();
        }
        transports.clear();
    }

    private TcpNodeTransport newTransport() {
        TcpNodeTransport t = new TcpNodeTransport();
        transports.add(t);
        return t;
    }

    // ── 1. startReceiver binds successfully ───────────────────────────────────

    @Test
    @DisplayName("startReceiver: binds to ephemeral port and getPort() returns non-zero value")
    void startReceiver_bindsToEphemeralPort() throws Exception {
        TcpNodeTransport transport = newTransport();
        transport.startReceiver("node1", 0, (proc, bytes) -> {});

        int port = transport.getPort();
        assertThat(port).isGreaterThan(0);
    }

    // ── 2. send delivers bytes to receiver's MessageHandler ───────────────────

    @Test
    @DisplayName("send: delivers message to receiver's MessageHandler")
    void send_deliversMessageToHandler() throws Exception {
        TcpNodeTransport receiver = newTransport();
        AtomicBoolean received = new AtomicBoolean(false);

        receiver.startReceiver("nodeA", 0, (proc, bytes) -> received.set(true));
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("nodeA", "127.0.0.1", port);

        boolean ack = sender.send("nodeA", "my-proc", new byte[] {1, 2, 3});

        assertThat(ack).isTrue();
        await().atMost(AWAIT).untilTrue(received);
    }

    // ── 3. Round-trip byte equality ───────────────────────────────────────────

    @Test
    @DisplayName("send: received bytes are identical to sent bytes")
    void send_bytesAreReceivedIntact() throws Exception {
        byte[] payload = RemoteProcProxy.serialize("round-trip-test");

        TcpNodeTransport receiver = newTransport();
        AtomicReference<byte[]> captured = new AtomicReference<>();

        receiver.startReceiver("nodeB", 0, (proc, bytes) -> captured.set(bytes));
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("nodeB", "127.0.0.1", port);
        sender.send("nodeB", "target", payload);

        await().atMost(AWAIT).until(() -> captured.get() != null);
        assertThat(captured.get()).isEqualTo(payload);
    }

    // ── 4. RemoteProcProxy.tell serializes and delivers a String ──────────────

    @Test
    @DisplayName("RemoteProcProxy.tell: serializes and delivers a String message")
    void proxy_tell_deliversString() throws Exception {
        TcpNodeTransport receiver = newTransport();
        AtomicReference<String> receivedMsg = new AtomicReference<>();

        receiver.startReceiver(
                "node2",
                0,
                (proc, bytes) -> {
                    String msg = RemoteProcProxy.deserialize(bytes);
                    receivedMsg.set(msg);
                });
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("node2", "127.0.0.1", port);

        RemoteProcProxy<Integer, String> proxy = RemoteProcProxy.create("node2", "counter", sender);

        boolean ack = proxy.tell("hello from proxy");
        assertThat(ack).isTrue();

        await().atMost(AWAIT).until(() -> receivedMsg.get() != null);
        assertThat(receivedMsg.get()).isEqualTo("hello from proxy");
    }

    // ── 5. Multiple sends to the same proxy ──────────────────────────────────

    @Test
    @DisplayName("RemoteProcProxy.tell: multiple successive sends all delivered")
    void proxy_tell_multipleSendsAllDelivered() throws Exception {
        TcpNodeTransport receiver = newTransport();
        List<String> received = new CopyOnWriteArrayList<>();

        receiver.startReceiver(
                "node3",
                0,
                (proc, bytes) -> {
                    String msg = RemoteProcProxy.deserialize(bytes);
                    received.add(msg);
                });
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("node3", "127.0.0.1", port);

        RemoteProcProxy<Integer, String> proxy =
                RemoteProcProxy.create("node3", "aggregator", sender);

        int count = 5;
        for (int i = 0; i < count; i++) {
            proxy.tell("msg-" + i);
        }

        await().atMost(AWAIT).until(() -> received.size() == count);
        assertThat(received).containsExactlyInAnyOrder("msg-0", "msg-1", "msg-2", "msg-3", "msg-4");
    }

    // ── 6. knownNodes reflects registered nodes ───────────────────────────────

    @Test
    @DisplayName("knownNodes: reflects all registered remote nodes")
    void knownNodes_reflectsRegistrations() {
        TcpNodeTransport transport = newTransport();
        assertThat(transport.knownNodes()).isEmpty();

        transport.registerNode("alpha", "10.0.0.1", 4369);
        transport.registerNode("beta", "10.0.0.2", 4369);

        assertThat(transport.knownNodes()).containsExactlyInAnyOrder("alpha", "beta");
    }

    // ── 7. unregisterNode removes node ────────────────────────────────────────

    @Test
    @DisplayName("unregisterNode: removes node from knownNodes")
    void unregisterNode_removesNode() {
        TcpNodeTransport transport = newTransport();
        transport.registerNode("gamma", "10.0.0.3", 4369);
        assertThat(transport.knownNodes()).contains("gamma");

        transport.unregisterNode("gamma");
        assertThat(transport.knownNodes()).doesNotContain("gamma");
    }

    // ── 8. close stops the receiver ───────────────────────────────────────────

    @Test
    @DisplayName("close: stops the receiver; subsequent sends return false")
    void close_stopsReceiver() throws Exception {
        TcpNodeTransport receiver = newTransport();
        receiver.startReceiver("nodeC", 0, (proc, bytes) -> {});
        int port = receiver.getPort();
        assertThat(port).isGreaterThan(0);

        receiver.close();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("nodeC", "127.0.0.1", port);

        // After close the receiver is no longer accepting — send should fail
        await().atMost(AWAIT).until(() -> !sender.send("nodeC", "any", new byte[] {0}));
    }

    // ── 9. lookup returns proxy for remote globalName ─────────────────────────

    @Test
    @DisplayName("RemoteProcProxy.lookup: returns proxy for remote node, empty for local")
    void lookup_returnsProxyForRemoteEmptyForLocal() {
        System.setProperty("jotp.node.name", "mynode");
        try {
            TcpNodeTransport transport = newTransport();
            transport.registerNode("othernode", "127.0.0.1", 9999);

            // Remote name → proxy returned
            var remote = RemoteProcProxy.<Integer, String>lookup("othernode/counter", transport);
            assertThat(remote).isPresent();
            assertThat(remote.get().targetNode()).isEqualTo("othernode");
            assertThat(remote.get().targetProc()).isEqualTo("counter");

            // Local name → empty
            var local = RemoteProcProxy.<Integer, String>lookup("mynode/counter", transport);
            assertThat(local).isEmpty();

            // No slash → empty
            var noSlash = RemoteProcProxy.<Integer, String>lookup("bare-name", transport);
            assertThat(noSlash).isEmpty();
        } finally {
            System.clearProperty("jotp.node.name");
        }
    }

    // ── 10. procName is delivered correctly ──────────────────────────────────

    @Test
    @DisplayName("send: procName header is delivered to MessageHandler")
    void send_procNameDeliveredCorrectly() throws Exception {
        TcpNodeTransport receiver = newTransport();
        AtomicReference<String> capturedProc = new AtomicReference<>();

        receiver.startReceiver("nodeD", 0, (proc, bytes) -> capturedProc.set(proc));
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("nodeD", "127.0.0.1", port);

        sender.send("nodeD", "specific-proc-name", new byte[] {42});

        await().atMost(AWAIT).until(() -> capturedProc.get() != null);
        assertThat(capturedProc.get()).isEqualTo("specific-proc-name");
    }

    // ── 11. askAsync returns CompletableFuture ────────────────────────────────

    @Test
    @DisplayName("RemoteProcProxy.askAsync: completes with true on ACK")
    void proxy_askAsync_completesWithAck() throws Exception {
        TcpNodeTransport receiver = newTransport();
        receiver.startReceiver("nodeE", 0, (proc, bytes) -> {});
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("nodeE", "127.0.0.1", port);

        RemoteProcProxy<Integer, String> proxy = RemoteProcProxy.create("nodeE", "worker", sender);

        var future = proxy.askAsync("async-hello");
        assertThat(future.get()).isTrue();
    }

    // ── 12. send to unknown node returns false ────────────────────────────────

    @Test
    @DisplayName("send: returns false for unregistered node name")
    void send_unknownNode_returnsFalse() {
        TcpNodeTransport transport = newTransport();
        boolean result = transport.send("nonexistent-node", "proc", new byte[] {1});
        assertThat(result).isFalse();
    }

    // ── 13. Multiple concurrent sends ────────────────────────────────────────

    @Test
    @DisplayName("TcpNodeTransport: concurrent sends from multiple virtual threads")
    void concurrentSends_allDelivered() throws Exception {
        int messageCount = 20;
        TcpNodeTransport receiver = newTransport();
        AtomicInteger counter = new AtomicInteger(0);

        receiver.startReceiver("nodeF", 0, (proc, bytes) -> counter.incrementAndGet());
        int port = receiver.getPort();

        TcpNodeTransport sender = newTransport();
        sender.registerNode("nodeF", "127.0.0.1", port);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            final int idx = i;
            Thread t =
                    Thread.ofVirtual()
                            .start(
                                    () ->
                                            sender.send(
                                                    "nodeF",
                                                    "proc-" + idx,
                                                    new byte[] {(byte) idx}));
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }

        await().atMost(AWAIT).until(() -> counter.get() == messageCount);
        assertThat(counter.get()).isEqualTo(messageCount);
    }
}
