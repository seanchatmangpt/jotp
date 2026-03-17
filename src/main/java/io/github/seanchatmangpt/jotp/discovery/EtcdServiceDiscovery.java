package io.github.seanchatmangpt.jotp.discovery;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * etcd v3 service discovery using HTTP/JSON (gRPC support could be added later).
 *
 * <p>Stores process registrations under keys like {@code /jotp/processes/{processName}} with
 * auto-expiring leases (TTL). Polls for membership changes.
 *
 * <p>Integration points:
 * <ul>
 *   <li>Register: PUT /v3/kv/put with key and lease for auto-cleanup
 *   <li>Deregister: DELETE /v3/kv/del with key
 *   <li>Query: GET /v3/kv/range with key prefix
 *   <li>Watch: Polling-based or etcd Watch API (simplified here to polling)
 * </ul>
 */
public final class EtcdServiceDiscovery implements ServiceDiscoveryProvider {

  private static final String KEY_PREFIX = "/jotp/processes/";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
  private static final Duration LEASE_TTL = Duration.ofMinutes(1);

  private final String etcdUrl;
  private final HttpClient httpClient;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Map<String, String> leaseIds = new HashMap<>();
  private final List<Consumer<Set<NodeId>>> watchers = new ArrayList<>();
  private volatile Set<NodeId> lastKnownNodes = Set.of();

  /**
   * Create an EtcdServiceDiscovery client.
   *
   * @param etcdUrl base URL of etcd (e.g., "http://localhost:2379")
   */
  public EtcdServiceDiscovery(String etcdUrl) {
    this.etcdUrl = etcdUrl.replaceAll("/$", "");
    this.httpClient = HttpClient.newHttpClient();
    this.executor = Executors.newScheduledThreadPool(1, r -> {
      Thread t = new Thread(r, "EtcdDiscoveryWatcher");
      t.setDaemon(true);
      return t;
    });
    startPolling();
  }

  @Override
  public CompletableFuture<Void> register(NodeId nodeId, ServiceInstance instance) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            // Create a lease (auto-expire after TTL)
            String leaseId = createLease(LEASE_TTL.toSeconds());
            String key = KEY_PREFIX + instance.processName();
            String value = instance.nodeId().wire() + ":" + instance.grpcPort();

            // Put key with lease
            putKeyWithLease(key, value, leaseId);

            synchronized (leaseIds) {
              leaseIds.put(instance.processName(), leaseId);
            }
          } catch (Exception e) {
            throw new RuntimeException("Failed to register with etcd: " + e.getMessage(), e);
          }
        },
        executor);
  }

  @Override
  public CompletableFuture<Void> deregister(NodeId nodeId) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            List<String> toRemove = new ArrayList<>();
            synchronized (leaseIds) {
              leaseIds.forEach(
                  (processName, leaseId) -> {
                    String key = KEY_PREFIX + processName;
                    deleteKey(key);
                    toRemove.add(processName);
                  });
            }
            synchronized (leaseIds) {
              toRemove.forEach(leaseIds::remove);
            }
          } catch (Exception e) {
            System.err.println("Failed to deregister from etcd: " + e.getMessage());
          }
        },
        executor);
  }

  @Override
  public Optional<NodeId> lookup(String processName) {
    Set<NodeId> nodes = queryService(processName);
    return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.iterator().next());
  }

  @Override
  public Set<NodeId> listNodes() {
    return new HashSet<>(lastKnownNodes);
  }

  @Override
  public void watch(Consumer<Set<NodeId>> onMembership) {
    synchronized (watchers) {
      watchers.add(onMembership);
    }
    onMembership.accept(listRegisteredNodes());
  }

  @Override
  public boolean isHealthy() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(etcdUrl + "/version"))
              .timeout(Duration.ofSeconds(2))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public CompletableFuture<Void> shutdown() {
    running.set(false);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    return CompletableFuture.completedFuture(null);
  }

  /** Start background polling for membership changes. */
  private void startPolling() {
    executor.scheduleAtFixedRate(
        this::pollAndNotify, 1, POLL_INTERVAL.toSeconds(), TimeUnit.SECONDS);
  }

  /** Poll etcd for all registered processes and notify watchers if membership changed. */
  private void pollAndNotify() {
    if (!running.get()) return;

    try {
      Set<NodeId> currentNodes = listRegisteredNodes();
      if (!currentNodes.equals(lastKnownNodes)) {
        lastKnownNodes = currentNodes;
        synchronized (watchers) {
          for (Consumer<Set<NodeId>> watcher : watchers) {
            try {
              watcher.accept(currentNodes);
            } catch (Exception e) {
              System.err.println("EtcdDiscovery watcher failed: " + e.getMessage());
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("EtcdDiscovery polling error: " + e.getMessage());
    }
  }

  /** List all registered nodes from etcd. */
  private Set<NodeId> listRegisteredNodes() {
    Set<NodeId> nodes = new HashSet<>();
    try {
      String rangeEndKey = KEY_PREFIX.replaceAll("/$", "") + "~";
      String json =
          String.format(
              """
              {
                "key": "%s",
                "range_end": "%s"
              }""",
              encodeBase64(KEY_PREFIX), encodeBase64(rangeEndKey));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(etcdUrl + "/v3/kv/range"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(5))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        parseKvRangeResponse(response.body(), nodes);
      }
    } catch (Exception e) {
      System.err.println("etcd query failed: " + e.getMessage());
    }
    return nodes;
  }

  /** Query etcd for a specific service. */
  private Set<NodeId> queryService(String processName) {
    Set<NodeId> nodes = new HashSet<>();
    try {
      String key = KEY_PREFIX + processName;
      String json =
          String.format(
              """
              {
                "key": "%s"
              }""",
              encodeBase64(key));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(etcdUrl + "/v3/kv/range"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .timeout(Duration.ofSeconds(5))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        parseKvRangeResponse(response.body(), nodes);
      }
    } catch (Exception e) {
      System.err.println("etcd service query failed: " + e.getMessage());
    }
    return nodes;
  }

  /** Create a lease with the given TTL (in seconds). Returns the lease ID. */
  private String createLease(long ttlSeconds) throws Exception {
    String json = String.format("""
        {
          "TTL": %d
        }""", ttlSeconds);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(etcdUrl + "/v3/lease/grant"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(5))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 200) {
      // Extract lease ID from response: {"ID":"xxxxx","TTL":60}
      Pattern p = Pattern.compile("\"ID\"\\s*:\\s*\"([^\"]+)\"");
      Matcher m = p.matcher(response.body());
      if (m.find()) {
        return m.group(1);
      }
    }
    throw new RuntimeException("Failed to create lease: " + response.body());
  }

  /** Put a key-value pair with an associated lease. */
  private void putKeyWithLease(String key, String value, String leaseId) throws Exception {
    String json =
        String.format(
            """
            {
              "key": "%s",
              "value": "%s",
              "lease": "%s"
            }""",
            encodeBase64(key), encodeBase64(value), leaseId);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(etcdUrl + "/v3/kv/put"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(5))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new RuntimeException("etcd put failed: " + response.statusCode());
    }
  }

  /** Delete a key from etcd. */
  private void deleteKey(String key) throws Exception {
    String json =
        String.format(
            """
            {
              "key": "%s"
            }""",
            encodeBase64(key));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(etcdUrl + "/v3/kv/del"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(5))
            .build();

    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Parse etcd kv/range response and extract NodeIds. */
  private void parseKvRangeResponse(String responseBody, Set<NodeId> nodes) {
    Pattern p = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    Matcher m = p.matcher(responseBody);
    while (m.find()) {
      try {
        String encodedValue = m.group(1);
        String value = new String(Base64.getDecoder().decode(encodedValue));
        // Format: "name:host:port:grpcPort"
        String[] parts = value.split(":");
        if (parts.length >= 3) {
          NodeId nodeId = new NodeId(parts[0], parts[1], Integer.parseInt(parts[2]));
          nodes.add(nodeId);
        }
      } catch (Exception e) {
        System.err.println("Failed to parse etcd value: " + e.getMessage());
      }
    }
  }

  /** Base64 encode a string for etcd API. */
  private static String encodeBase64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }
}
