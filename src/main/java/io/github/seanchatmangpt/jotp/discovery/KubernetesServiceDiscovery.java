package io.github.seanchatmangpt.jotp.discovery;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Kubernetes service discovery using the Endpoints API.
 *
 * <p>When running in Kubernetes, reads the ServiceAccount token and talks to the K8s API server.
 * Registers processes via ConfigMap annotations or Endpoints updates.
 *
 * <p>Integration points:
 * <ul>
 *   <li>ServiceAccount: Read from /var/run/secrets/kubernetes.io/serviceaccount/
 *   <li>API Server: https://kubernetes.default.svc.cluster.local
 *   <li>Register: PATCH Service annotations or create/update ConfigMap
 *   <li>Query: GET /api/v1/namespaces/{ns}/endpoints/{serviceName}
 *   <li>Watch: Polling-based (etcd watch API also available)
 * </ul>
 */
public final class KubernetesServiceDiscovery implements ServiceDiscoveryProvider {

  private static final String SA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount";
  private static final String CONFIG_MAP_NAME = "jotp-processes";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

  private final String namespace;
  private final String apiServer;
  private final String token;
  private final HttpClient httpClient;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Map<String, ServiceInstance> registeredServices = new HashMap<>();
  private final List<Consumer<Set<NodeId>>> watchers = new ArrayList<>();
  private volatile Set<NodeId> lastKnownNodes = Set.of();

  /**
   * Create a KubernetesServiceDiscovery client. Assumes running in a K8s pod with a valid
   * ServiceAccount mounted.
   *
   * @throws RuntimeException if ServiceAccount files are not readable
   */
  public KubernetesServiceDiscovery() {
    try {
      this.namespace = readFile(SA_PATH + "/namespace");
      this.token = readFile(SA_PATH + "/token");
      this.apiServer = "https://kubernetes.default.svc.cluster.local";
    } catch (Exception e) {
      throw new RuntimeException("Not running in Kubernetes or ServiceAccount not mounted", e);
    }

    this.httpClient =
        HttpClient.newBuilder().sslContext(createInsecureSSLContext()).build();
    this.executor = Executors.newScheduledThreadPool(1, r -> {
      Thread t = new Thread(r, "K8sDiscoveryWatcher");
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
            synchronized (registeredServices) {
              registeredServices.put(instance.processName(), instance);
            }
            // Update ConfigMap with process info
            updateConfigMap(instance);
          } catch (Exception e) {
            throw new RuntimeException("Failed to register with Kubernetes: " + e.getMessage(), e);
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
            synchronized (registeredServices) {
              registeredServices.forEach(
                  (name, instance) -> {
                    if (instance.nodeId().equals(nodeId)) {
                      toRemove.add(name);
                    }
                  });
            }
            synchronized (registeredServices) {
              toRemove.forEach(registeredServices::remove);
            }
            updateConfigMap(null); // Signal removal
          } catch (Exception e) {
            System.err.println("Failed to deregister from Kubernetes: " + e.getMessage());
          }
        },
        executor);
  }

  @Override
  public Optional<NodeId> lookup(String processName) {
    try {
      Set<NodeId> nodes = queryService(processName);
      return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.iterator().next());
    } catch (Exception e) {
      return Optional.empty();
    }
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
    onMembership.accept(listRegisteredServices());
  }

  @Override
  public boolean isHealthy() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(apiServer + "/api/v1/namespaces"))
              .header("Authorization", "Bearer " + token)
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

  /** Poll K8s endpoints and notify watchers if membership changed. */
  private void pollAndNotify() {
    if (!running.get()) return;

    try {
      Set<NodeId> currentNodes = listRegisteredServices();
      if (!currentNodes.equals(lastKnownNodes)) {
        lastKnownNodes = currentNodes;
        synchronized (watchers) {
          for (Consumer<Set<NodeId>> watcher : watchers) {
            try {
              watcher.accept(currentNodes);
            } catch (Exception e) {
              System.err.println("K8sDiscovery watcher failed: " + e.getMessage());
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("K8sDiscovery polling error: " + e.getMessage());
    }
  }

  /** List all registered services. */
  private Set<NodeId> listRegisteredServices() {
    Set<NodeId> nodes = new HashSet<>();
    synchronized (registeredServices) {
      registeredServices.values().forEach(instance -> nodes.add(instance.nodeId()));
    }
    return nodes;
  }

  /** Query K8s Endpoints for a specific service. */
  private Set<NodeId> queryService(String serviceName) throws Exception {
    Set<NodeId> nodes = new HashSet<>();
    String url =
        apiServer
            + "/api/v1/namespaces/"
            + namespace
            + "/endpoints/"
            + serviceName;

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(5))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 200) {
      parseEndpointsResponse(response.body(), nodes);
    }
    return nodes;
  }

  /** Update a ConfigMap with current registered services. */
  private void updateConfigMap(ServiceInstance instance) throws Exception {
    String json = buildConfigMapPatch(instance);
    String url =
        apiServer
            + "/api/v1/namespaces/"
            + namespace
            + "/configmaps/"
            + CONFIG_MAP_NAME;

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/strategic-merge-patch+json")
            .PATCH(HttpRequest.BodyPublishers.ofString(json))
            .timeout(Duration.ofSeconds(5))
            .build();

    try {
      httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      System.err.println("Failed to update K8s ConfigMap: " + e.getMessage());
    }
  }

  /** Parse K8s Endpoints response and extract addresses. */
  private void parseEndpointsResponse(String responseBody, Set<NodeId> nodes) {
    // Simple regex-based parsing: look for "ip":"10.x.x.x"
    Pattern p = Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"");
    Matcher m = p.matcher(responseBody);
    while (m.find()) {
      String ip = m.group(1);
      nodes.add(new NodeId("k8s-pod", ip, 9999));
    }
  }

  /** Build JSON patch for ConfigMap update. */
  private String buildConfigMapPatch(ServiceInstance instance) {
    if (instance == null) {
      return "{}";
    }

    Map<String, String> data = new HashMap<>();
    synchronized (registeredServices) {
      registeredServices.forEach(
          (name, inst) -> {
            data.put(name, inst.nodeId().wire() + ":" + inst.grpcPort());
          });
    }

    StringBuilder json = new StringBuilder("{\"data\": {");
    int count = 0;
    for (Map.Entry<String, String> e : data.entrySet()) {
      if (count > 0) json.append(",");
      json.append("\"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
      count++;
    }
    json.append("}}");
    return json.toString();
  }

  /** Read a file as a String (e.g., ServiceAccount token). */
  private static String readFile(String path) throws Exception {
    return Files.readString(Path.of(path)).trim();
  }

  /** Create an insecure SSL context (for self-signed K8s API server cert). */
  private static javax.net.ssl.SSLContext createInsecureSSLContext() {
    try {
      javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
      sc.init(null, new javax.net.ssl.TrustManager[] {
        new javax.net.ssl.X509TrustManager() {
          public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
          public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
          public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
        }
      }, new java.security.SecureRandom());
      return sc;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create insecure SSL context", e);
    }
  }
}
