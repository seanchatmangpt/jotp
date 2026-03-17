package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcMetricsServer: HTTP metrics export")
class ProcMetricsServerTest {

    private ProcMetricsServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        ApplicationController.reset();
        port = findFreePort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    // ── start / close ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start: server binds to port and accepts requests")
    void start_bindsPortAndServesRequests() throws Exception {
        server = ProcMetricsServer.start(port);
        var response = get("/metrics");
        assertThat(response.status).isEqualTo(200);
    }

    @Test
    @DisplayName("start(port, collector): exposes collector immediately")
    void startWithCollector_exposesMetrics() throws Exception {
        var metrics = MetricsCollector.create("test-service");
        metrics.counter("requests").increment();
        metrics.counter("requests").increment();

        server = ProcMetricsServer.start(port, metrics);

        var response = get("/metrics");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.body).contains("jotp_requests");
        assertThat(response.body).contains("2");
        assertThat(response.body).contains("test-service");
    }

    @Test
    @DisplayName("close: server stops accepting requests")
    void close_stopsServer() throws Exception {
        server = ProcMetricsServer.start(port);
        server.close();
        server = null;

        assertThatThrownBy(() -> get("/metrics"))
                .isInstanceOfAny(IOException.class, java.net.ConnectException.class);
    }

    // ── Prometheus format (/metrics) ──────────────────────────────────────────────

    @Test
    @DisplayName("/metrics: returns 200 with text/plain content type")
    void metrics_returnsTextPlain() throws Exception {
        server = ProcMetricsServer.start(port);
        var response = get("/metrics");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.contentType).startsWith("text/plain");
    }

    @Test
    @DisplayName("/metrics: counter value appears in output")
    void metrics_counterAppearsInOutput() throws Exception {
        var metrics = MetricsCollector.create("svc");
        metrics.counter("errors.total").increment();
        metrics.counter("errors.total").increment();
        metrics.counter("errors.total").increment();

        server = ProcMetricsServer.start(port, metrics);

        var body = get("/metrics").body;
        assertThat(body).contains("jotp_errors_total"); // dots sanitized to underscores
        assertThat(body).contains("3");
    }

    @Test
    @DisplayName("/metrics: multiple collectors are all present")
    void metrics_multipleCollectors() throws Exception {
        server = ProcMetricsServer.start(port);
        var m1 = MetricsCollector.create("service-a");
        var m2 = MetricsCollector.create("service-b");
        m1.counter("hits").increment();
        m2.counter("misses").increment();

        server.register("service-a", m1);
        server.register("service-b", m2);

        var body = get("/metrics").body;
        assertThat(body).contains("service-a");
        assertThat(body).contains("service-b");
        assertThat(body).contains("jotp_hits");
        assertThat(body).contains("jotp_misses");
    }

    @Test
    @DisplayName("/metrics: metric names with dots are sanitized to underscores")
    void metrics_dotSanitization() throws Exception {
        var metrics = MetricsCollector.create("app");
        metrics.counter("jvm.gc.collections").increment();

        server = ProcMetricsServer.start(port, metrics);

        var body = get("/metrics").body;
        assertThat(body).contains("jotp_jvm_gc_collections");
        assertThat(body).doesNotContain("jvm.gc.collections");
    }

    // ── JSON format (/metrics/json) ───────────────────────────────────────────────

    @Test
    @DisplayName("/metrics/json: returns 200 with application/json content type")
    void metricsJson_returnsJson() throws Exception {
        server = ProcMetricsServer.start(port);
        var response = get("/metrics/json");
        assertThat(response.status).isEqualTo(200);
        assertThat(response.contentType).startsWith("application/json");
    }

    @Test
    @DisplayName("/metrics/json: counter values appear in JSON output")
    void metricsJson_containsCounterValues() throws Exception {
        var metrics = MetricsCollector.create("api");
        metrics.counter("calls").increment();
        metrics.counter("calls").increment();

        server = ProcMetricsServer.start(port, metrics);

        var body = get("/metrics/json").body;
        assertThat(body).contains("\"api\"");
        assertThat(body).contains("\"calls\"");
        assertThat(body).contains("2");
    }

    // ── register / unregister ─────────────────────────────────────────────────────

    @Test
    @DisplayName("register: adds collector after server start")
    void register_addsCollectorAfterStart() throws Exception {
        server = ProcMetricsServer.start(port);

        var metrics = MetricsCollector.create("late");
        metrics.counter("ops").increment();
        server.register("late", metrics);

        var body = get("/metrics").body;
        assertThat(body).contains("late");
        assertThat(body).contains("jotp_ops");
    }

    @Test
    @DisplayName("unregister: removes collector from output")
    void unregister_removesCollector() throws Exception {
        var metrics = MetricsCollector.create("temp");
        metrics.counter("x").increment();
        server = ProcMetricsServer.start(port, metrics);

        var beforeBody = get("/metrics").body;
        assertThat(beforeBody).contains("temp");

        server.unregister("temp");

        var afterBody = get("/metrics").body;
        assertThat(afterBody).doesNotContain("temp");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private record Response(int status, String contentType, String body) {}

    private Response get(String path) throws IOException {
        @SuppressWarnings("deprecation") // URL(String) deprecated in Java 20, use URI.toURL()
        var url = new URL("http://localhost:" + port + path);
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int status = conn.getResponseCode();
        String contentType = conn.getContentType();
        InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        String body = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        return new Response(status, contentType != null ? contentType : "", body);
    }

    private static int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
