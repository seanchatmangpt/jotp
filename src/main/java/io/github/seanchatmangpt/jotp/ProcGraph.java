package io.github.seanchatmangpt.jotp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live process supervision tree visualizer served over HTTP.
 *
 * <p>ProcGraph starts an in-process HTTP server that serves a live D3.js visualization of a JOTP
 * supervision tree — analogous to Erlang's {@code observer:start()} but accessible via any browser.
 *
 * <p><strong>Endpoints:</strong>
 *
 * <ul>
 *   <li>{@code GET /} — HTML page with embedded D3.js supervision tree visualization
 *   <li>{@code GET /api/snapshot} — JSON snapshot of the current process tree state
 *   <li>{@code GET /events} — Server-Sent Events stream pushing updates every 500ms
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var supervisor = Supervisor.create(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 * var handle = ProcGraph.start(supervisor, 8765);  // http://localhost:8765
 * // or
 * var handle = ProcGraph.start(supervisor);        // random available port
 * // ...
 * ProcGraph.stop(handle);
 * }</pre>
 *
 * <p><strong>Implementation Notes:</strong> Uses {@code com.sun.net.httpserver.HttpServer} from the
 * JDK — no external dependencies required. State is refreshed every 500ms on a background virtual
 * thread. All mutable shared state uses {@link ConcurrentHashMap} or {@link CopyOnWriteArrayList}
 * for thread safety.
 *
 * @see Supervisor
 * @see ProcSys
 */
public final class ProcGraph {

    private ProcGraph() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Stable handle returned by {@link #start} — carries the bound port and convenience URL.
     *
     * @param port the TCP port the HTTP server is listening on
     * @param url the full URL to open in a browser (e.g. {@code http://localhost:8765})
     */
    public record Handle(
            int port, String url, HttpServer server, ScheduledExecutorService scheduler) {

        /** Compact constructor validates port range. */
        public Handle {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }
    }

    /**
     * Start a ProcGraph server on a random available port.
     *
     * @param supervisor the supervision tree to visualize
     * @return a {@link Handle} with the bound port and URL
     * @throws IOException if the server cannot be started
     */
    public static Handle start(Supervisor supervisor) throws IOException {
        return start(supervisor, 0);
    }

    /**
     * Start a ProcGraph server on the specified port (0 = random available port).
     *
     * @param supervisor the supervision tree to visualize
     * @param port the TCP port to bind (0 for random)
     * @return a {@link Handle} with the bound port and URL
     * @throws IOException if the server cannot be started
     */
    public static Handle start(Supervisor supervisor, int port) throws IOException {
        var snapshotRef = new AtomicReference<>(buildSnapshot(supervisor));
        var sseClients = new CopyOnWriteArrayList<OutputStream>();

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 16);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // GET / — embedded HTML + D3.js visualization
        httpServer.createContext(
                "/",
                exchange -> {
                    if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    byte[] body = buildHtml().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });

        // GET /api/snapshot — JSON snapshot
        httpServer.createContext(
                "/api/snapshot",
                exchange -> {
                    if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    byte[] body = snapshotRef.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });

        // GET /events — Server-Sent Events
        httpServer.createContext(
                "/events",
                exchange -> {
                    if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    sseClients.add(out);
                    // Send initial snapshot immediately
                    try {
                        sendSseEvent(out, snapshotRef.get());
                    } catch (IOException ignored) {
                        sseClients.remove(out);
                    }
                });

        httpServer.start();

        int boundPort = httpServer.getAddress().getPort();

        // Background virtual thread: refresh snapshot and push SSE every 500ms
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> Thread.ofVirtual().name("procgraph-refresh").unstarted(r));
        scheduler.scheduleAtFixedRate(
                () -> {
                    String json = buildSnapshot(supervisor);
                    snapshotRef.set(json);
                    List<OutputStream> dead = new ArrayList<>();
                    for (OutputStream client : sseClients) {
                        try {
                            sendSseEvent(client, json);
                        } catch (IOException e) {
                            dead.add(client);
                        }
                    }
                    sseClients.removeAll(dead);
                },
                500,
                500,
                TimeUnit.MILLISECONDS);

        return new Handle(boundPort, "http://localhost:" + boundPort, httpServer, scheduler);
    }

    /**
     * Shut down a running ProcGraph server.
     *
     * @param handle the handle returned by {@link #start}
     */
    public static void stop(Handle handle) {
        handle.scheduler().shutdownNow();
        handle.server().stop(0);
    }

    // ── Snapshot builder ──────────────────────────────────────────────────────

    private static String buildSnapshot(Supervisor supervisor) {
        List<Supervisor.ChildInfo> children = supervisor.whichChildren();

        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\",");
        sb.append("\"nodes\":[");

        // Supervisor node
        sb.append("{\"id\":\"supervisor-root\",\"type\":\"supervisor\",\"name\":\"Supervisor\",");
        sb.append("\"status\":\"")
                .append(supervisor.isRunning() ? "running" : "stopped")
                .append("\",");
        sb.append("\"childCount\":").append(children.size()).append("}");

        for (Supervisor.ChildInfo child : children) {
            sb.append(",{");
            sb.append("\"id\":\"proc-").append(jsonEscape(child.id())).append("\",");
            sb.append("\"type\":\"")
                    .append(
                            child.type() == Supervisor.ChildSpec.ChildType.SUPERVISOR
                                    ? "supervisor"
                                    : "process")
                    .append("\",");
            sb.append("\"name\":\"").append(jsonEscape(child.id())).append("\",");
            sb.append("\"status\":\"").append(child.alive() ? "running" : "stopped").append("\"");
            sb.append("}");
        }

        sb.append("],\"edges\":[");

        boolean firstEdge = true;
        for (Supervisor.ChildInfo child : children) {
            if (!firstEdge) sb.append(",");
            sb.append("{");
            sb.append("\"source\":\"supervisor-root\",");
            sb.append("\"target\":\"proc-").append(jsonEscape(child.id())).append("\",");
            sb.append("\"type\":\"supervises\"");
            sb.append("}");
            firstEdge = false;
        }

        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── SSE helper ────────────────────────────────────────────────────────────

    private static void sendSseEvent(OutputStream out, String data) throws IOException {
        String event = "data: " + data + "\n\n";
        out.write(event.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ── Embedded HTML ─────────────────────────────────────────────────────────

    private static String buildHtml() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>ProcGraph — JOTP Live Supervision Tree</title>
                  <script src="https://cdnjs.cloudflare.com/ajax/libs/d3/7.8.5/d3.min.js"
                          crossorigin="anonymous"></script>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                      font-family: 'Segoe UI', system-ui, sans-serif;
                      background: #0d1117;
                      color: #e6edf3;
                      height: 100vh;
                      display: flex;
                      flex-direction: column;
                    }
                    header {
                      padding: 12px 20px;
                      background: #161b22;
                      border-bottom: 1px solid #30363d;
                      display: flex;
                      align-items: center;
                      gap: 12px;
                    }
                    header h1 { font-size: 18px; font-weight: 600; color: #58a6ff; }
                    header .subtitle { font-size: 12px; color: #8b949e; }
                    #status-badge {
                      margin-left: auto;
                      padding: 4px 10px;
                      border-radius: 12px;
                      font-size: 12px;
                      font-weight: 500;
                      background: #1f6feb33;
                      color: #58a6ff;
                    }
                    #status-badge.live { background: #1a7f3733; color: #3fb950; }
                    #container {
                      flex: 1;
                      overflow: hidden;
                      position: relative;
                    }
                    svg { width: 100%; height: 100%; }
                    .node circle {
                      stroke-width: 2;
                      cursor: pointer;
                      transition: r 0.2s ease;
                    }
                    .node.supervisor circle {
                      fill: #1f6feb;
                      stroke: #58a6ff;
                    }
                    .node.process circle {
                      fill: #238636;
                      stroke: #3fb950;
                    }
                    .node.stopped circle {
                      fill: #6e7681;
                      stroke: #8b949e;
                    }
                    .node text {
                      fill: #e6edf3;
                      font-size: 11px;
                      pointer-events: none;
                      font-family: 'Segoe UI', system-ui, sans-serif;
                    }
                    .link {
                      stroke: #30363d;
                      stroke-width: 1.5;
                      fill: none;
                    }
                    #info-panel {
                      position: absolute;
                      top: 12px;
                      right: 12px;
                      background: #161b22;
                      border: 1px solid #30363d;
                      border-radius: 8px;
                      padding: 12px 16px;
                      font-size: 12px;
                      min-width: 180px;
                    }
                    #info-panel h3 { font-size: 13px; color: #58a6ff; margin-bottom: 8px; }
                    #info-panel .row { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 4px; }
                    #info-panel .label { color: #8b949e; }
                    #info-panel .value { color: #e6edf3; font-weight: 500; }
                    #timestamp { font-size: 11px; color: #8b949e; margin-top: 8px; }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>ProcGraph</h1>
                    <span class="subtitle">JOTP Live Supervision Tree</span>
                    <span id="status-badge">Connecting...</span>
                  </header>
                  <div id="container">
                    <svg id="graph"></svg>
                    <div id="info-panel">
                      <h3>Supervision Tree</h3>
                      <div class="row"><span class="label">Nodes</span><span class="value" id="node-count">—</span></div>
                      <div class="row"><span class="label">Running</span><span class="value" id="running-count">—</span></div>
                      <div class="row"><span class="label">Stopped</span><span class="value" id="stopped-count">—</span></div>
                      <div id="timestamp"></div>
                    </div>
                  </div>
                  <script>
                    const width = () => document.getElementById('container').clientWidth;
                    const height = () => document.getElementById('container').clientHeight;

                    const svg = d3.select('#graph');
                    const g = svg.append('g');

                    svg.call(d3.zoom().scaleExtent([0.3, 4]).on('zoom', e => g.attr('transform', e.transform)));

                    let simulation = null;

                    function render(data) {
                      const nodes = data.nodes || [];
                      const edges = data.edges || [];

                      // Update info panel
                      document.getElementById('node-count').textContent = nodes.length;
                      const running = nodes.filter(n => n.status === 'running').length;
                      const stopped = nodes.filter(n => n.status !== 'running').length;
                      document.getElementById('running-count').textContent = running;
                      document.getElementById('stopped-count').textContent = stopped;
                      document.getElementById('timestamp').textContent = 'Updated: ' + new Date(data.timestamp).toLocaleTimeString();

                      const w = width(), h = height();

                      if (simulation) simulation.stop();

                      simulation = d3.forceSimulation(nodes)
                        .force('link', d3.forceLink(edges).id(d => d.id).distance(80))
                        .force('charge', d3.forceManyBody().strength(-200))
                        .force('center', d3.forceCenter(w / 2, h / 2))
                        .force('collision', d3.forceCollide(35));

                      g.selectAll('*').remove();

                      const link = g.append('g')
                        .selectAll('line')
                        .data(edges)
                        .join('line')
                        .attr('class', 'link');

                      const node = g.append('g')
                        .selectAll('g')
                        .data(nodes)
                        .join('g')
                        .attr('class', d => 'node ' + d.type + (d.status !== 'running' ? ' stopped' : ''))
                        .call(d3.drag()
                          .on('start', (event, d) => { if (!event.active) simulation.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })
                          .on('drag', (event, d) => { d.fx = event.x; d.fy = event.y; })
                          .on('end', (event, d) => { if (!event.active) simulation.alphaTarget(0); d.fx = null; d.fy = null; }));

                      node.append('circle').attr('r', d => d.type === 'supervisor' ? 20 : 14);
                      node.append('text').attr('dy', '0.35em').attr('text-anchor', 'middle').text(d => d.name.length > 10 ? d.name.slice(0, 9) + '…' : d.name);

                      simulation.on('tick', () => {
                        link
                          .attr('x1', d => d.source.x).attr('y1', d => d.source.y)
                          .attr('x2', d => d.target.x).attr('y2', d => d.target.y);
                        node.attr('transform', d => `translate(${d.x},${d.y})`);
                      });
                    }

                    // Connect SSE
                    const badge = document.getElementById('status-badge');
                    const evtSource = new EventSource('/events');
                    evtSource.onopen = () => { badge.textContent = 'Live'; badge.className = 'live'; };
                    evtSource.onmessage = e => { try { render(JSON.parse(e.data)); } catch(_) {} };
                    evtSource.onerror = () => { badge.textContent = 'Disconnected'; badge.className = ''; };

                    // Initial load via snapshot API
                    fetch('/api/snapshot')
                      .then(r => r.json())
                      .then(render)
                      .catch(() => {});
                  </script>
                </body>
                </html>
                """;
    }
}
