package io.github.seanchatmangpt.jotp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP metrics endpoint for JOTP observability.
 *
 * <p>Exposes metrics, health status, and cluster membership via JSON HTTP endpoints.
 *
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>GET /metrics → JSON dump of all metrics
 *   <li>GET /health → cluster health status
 *   <li>GET /nodes → list of registered nodes
 * </ul>
 */
public final class MetricsHttpServer {
  private final int port;
  private final MetricsCollector metricsCollector;
  private HttpServer httpServer;
  private volatile boolean running = false;

  private MetricsHttpServer(int port, MetricsCollector metricsCollector) {
    this.port = port;
    this.metricsCollector = metricsCollector;
  }

  public static MetricsHttpServer create(int port, MetricsCollector metricsCollector) {
    return new MetricsHttpServer(port, metricsCollector);
  }

  public void start() throws IOException {
    if (running) return;

    httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

    httpServer.createContext(
        "/metrics",
        exchange -> {
          try {
            String json = metricsToJson(metricsCollector.snapshot());
            sendJsonResponse(exchange, 200, json);
          } catch (Exception e) {
            sendJsonResponse(exchange, 500, errorJson("Failed to generate metrics: " + e.getMessage()));
          }
        });

    httpServer.createContext(
        "/health",
        exchange -> {
          try {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "UP");
            health.put("timestamp", System.currentTimeMillis());
            String json = mapToJson(health);
            sendJsonResponse(exchange, 200, json);
          } catch (Exception e) {
            sendJsonResponse(exchange, 500, errorJson("Health check failed: " + e.getMessage()));
          }
        });

    httpServer.createContext(
        "/nodes",
        exchange -> {
          try {
            Map<String, Object> nodes = new LinkedHashMap<>();
            nodes.put("nodes", new ArrayList<>());
            String json = mapToJson(nodes);
            sendJsonResponse(exchange, 200, json);
          } catch (Exception e) {
            sendJsonResponse(exchange, 500, errorJson("Failed to list nodes: " + e.getMessage()));
          }
        });

    httpServer.setExecutor(null);
    httpServer.start();
    running = true;
  }

  public void stop() {
    if (running) {
      httpServer.stop(0);
      running = false;
    }
  }

  public boolean isRunning() {
    return running;
  }

  public int getPort() {
    return port;
  }

  private void sendJsonResponse(com.sun.net.httpserver.HttpExchange exchange, int statusCode, String json)
      throws IOException {
    byte[] response = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private String metricsToJson(Map<String, Object> metrics) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : metrics.entrySet()) {
      if (!first) sb.append(",");
      sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
      appendJsonValue(sb, entry.getValue());
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  private String mapToJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) sb.append(",");
      sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
      appendJsonValue(sb, entry.getValue());
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private void appendJsonValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String str) {
      sb.append("\"").append(escapeJson(str)).append("\"");
    } else if (value instanceof Number num) {
      sb.append(num);
    } else if (value instanceof Boolean bool) {
      sb.append(bool);
    } else if (value instanceof Map map) {
      sb.append(mapToJson(map));
    } else if (value instanceof List list) {
      sb.append("[");
      boolean first = true;
      for (Object item : list) {
        if (!first) sb.append(",");
        appendJsonValue(sb, item);
        first = false;
      }
      sb.append("]");
    } else {
      sb.append("\"").append(escapeJson(value.toString())).append("\"");
    }
  }

  private String escapeJson(String str) {
    return str.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  private String errorJson(String message) {
    return "{\"error\":\"" + escapeJson(message) + "\"}";
  }
}
