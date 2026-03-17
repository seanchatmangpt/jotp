package io.github.seanchatmangpt.jotp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple HTTP metrics export server — zero external dependencies.
 *
 * <p>Java developers running production services need a way to scrape process metrics without
 * pulling in Micrometer, Prometheus client libraries, or a separate metrics registry. This server
 * uses the JDK's built-in {@code com.sun.net.httpserver.HttpServer} (stable since Java 6, zero
 * heap overhead) to expose {@link MetricsCollector} snapshots over HTTP.
 *
 * <p>Two endpoints are available:
 *
 * <ul>
 *   <li>{@code GET /metrics} — Prometheus-compatible text format (scrape-ready)
 *   <li>{@code GET /metrics/json} — Simple JSON key-value snapshot
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Quick start — expose the default MetricsCollector on port 9090
 * MetricsCollector metrics = MetricsCollector.create("my-service");
 * metrics.counter("requests.total").increment();
 *
 * try (ProcMetricsServer server = ProcMetricsServer.start(9090, metrics)) {
 *     // GET http://localhost:9090/metrics
 *     // # TYPE jotp_requests_total counter
 *     // jotp_requests_total{collector="my-service"} 1
 * }
 *
 * // Multiple collectors
 * ProcMetricsServer server = ProcMetricsServer.start(9090);
 * server.register("workers", workerMetrics);
 * server.register("db", dbMetrics);
 * server.close(); // stops the HTTP server
 * }</pre>
 *
 * <p><strong>Prometheus text format:</strong> Counter and gauge values are emitted as plain numbers.
 * Histogram and timer values emit one line per sub-metric (count, mean, min, max, p50, p95, p99).
 * Metric names are sanitized: {@code .} and {@code -} become {@code _}, the {@code jotp_} prefix
 * is prepended, and collector name is added as a label.
 *
 * @see MetricsCollector
 */
public final class ProcMetricsServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final ConcurrentHashMap<String, MetricsCollector> collectors =
            new ConcurrentHashMap<>();

    // ── Constructor / factory ─────────────────────────────────────────────────────

    private ProcMetricsServer(int port) throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        this.httpServer.createContext("/metrics/json", this::handleJson);
        this.httpServer.createContext("/metrics", this::handlePrometheus);
        this.httpServer.setExecutor(null); // virtual-thread-friendly: JVM default executor
        this.httpServer.start();
    }

    /**
     * Start a metrics server on the given port with no collectors pre-registered. Add collectors
     * via {@link #register(String, MetricsCollector)}.
     *
     * @param port TCP port to bind (e.g. 9090)
     * @return a running server
     * @throws IOException if the port cannot be bound
     */
    public static ProcMetricsServer start(int port) throws IOException {
        return new ProcMetricsServer(port);
    }

    /**
     * Start a metrics server on the given port with one pre-registered collector.
     *
     * @param port TCP port to bind
     * @param metrics the collector to expose immediately
     * @return a running server
     * @throws IOException if the port cannot be bound
     */
    public static ProcMetricsServer start(int port, MetricsCollector metrics) throws IOException {
        var server = new ProcMetricsServer(port);
        server.register(metrics.name(), metrics);
        return server;
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Register a metrics collector under {@code name}. The name appears as the {@code collector}
     * label in the Prometheus output.
     *
     * @param name collector label (appears in {@code collector="..."} label)
     * @param collector the metrics to expose
     */
    public void register(String name, MetricsCollector collector) {
        collectors.put(name, collector);
    }

    /**
     * Unregister a previously registered collector.
     *
     * @param name the collector name to remove
     */
    public void unregister(String name) {
        collectors.remove(name);
    }

    /** Stop the HTTP server. Idempotent. */
    @Override
    public void close() {
        httpServer.stop(0);
    }

    // ── Handlers ──────────────────────────────────────────────────────────────────

    private void handlePrometheus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        var sb = new StringBuilder();
        for (var entry : collectors.entrySet()) {
            String collectorLabel = entry.getKey();
            var snapshot = entry.getValue().snapshot();
            appendPrometheus(sb, collectorLabel, snapshot);
        }
        send(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", sb.toString());
    }

    private void handleJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        var sb = new StringBuilder();
        sb.append("{");
        boolean firstCollector = true;
        for (var entry : collectors.entrySet()) {
            if (!firstCollector) sb.append(",");
            firstCollector = false;
            sb.append("\"").append(entry.getKey()).append("\":{");
            var snapshot = entry.getValue().snapshot();
            boolean firstMetric = true;
            for (var metric : snapshot.entrySet()) {
                if (!firstMetric) sb.append(",");
                firstMetric = false;
                sb.append("\"").append(metric.getKey()).append("\":");
                appendJsonValue(sb, metric.getValue());
            }
            sb.append("}");
        }
        sb.append("}");
        send(exchange, 200, "application/json", sb.toString());
    }

    // ── Formatting helpers ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void appendPrometheus(
            StringBuilder sb, String collectorLabel, Map<String, Object> snapshot) {
        for (var entry : snapshot.entrySet()) {
            String metricName = sanitize(entry.getKey());
            Object value = entry.getValue();
            String label = "{collector=\"" + collectorLabel + "\"}";

            if (value instanceof Number num) {
                // Counter or gauge
                sb.append("# TYPE jotp_").append(metricName).append(" counter\n");
                sb.append("jotp_")
                        .append(metricName)
                        .append(label)
                        .append(" ")
                        .append(num.longValue())
                        .append("\n");
            } else if (value instanceof Map<?, ?> subMap) {
                // Histogram or timer — emit one line per sub-metric
                for (var subEntry : ((Map<String, Object>) subMap).entrySet()) {
                    String subName = sanitize(subEntry.getKey());
                    Object subVal = subEntry.getValue();
                    if (subVal instanceof Number subNum) {
                        sb.append("jotp_")
                                .append(metricName)
                                .append("_")
                                .append(subName)
                                .append(label)
                                .append(" ")
                                .append(subNum)
                                .append("\n");
                    }
                }
            }
        }
    }

    private void appendJsonValue(StringBuilder sb, Object value) {
        switch (value) {
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append("{");
                boolean first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(e.getKey()).append("\":");
                    appendJsonValue(sb, e.getValue());
                }
                sb.append("}");
            }
            default -> sb.append("\"").append(value).append("\"");
        }
    }

    /** Sanitize a metric name for Prometheus: replace {@code .} and {@code -} with {@code _}. */
    private static String sanitize(String name) {
        return name.replace('.', '_').replace('-', '_');
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
