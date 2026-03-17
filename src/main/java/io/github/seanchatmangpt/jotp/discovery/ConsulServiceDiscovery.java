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
 * Consul-based service discovery.
 *
 * <p>Registers processes as Consul services and polls for membership changes. Uses Java's built-in
 * HttpClient (no external dependencies beyond what Maven already has).
 *
 * <p>Integration points:
 * <ul>
 *   <li>Register: PUT /v1/agent/service/register with JSON payload
 *   <li>Deregister: PUT /v1/agent/service/deregister/{serviceId}
 *   <li>Query: GET /v1/catalog/service/{serviceName}
 *   <li>Watch: Poll-based (5s interval) for membership changes
 * </ul>
 */
public final class ConsulServiceDiscovery implements ServiceDiscoveryProvider {

  private static final String CONSUL_VERSION = "1.0";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
  private static final Pattern JSON_NODES_PATTERN =
      Pattern.compile("\"ServiceAddress\"\\s*:\\s*\"([^\"]+)\"");

  private final String consulUrl;
  private final HttpClient httpClient;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Map<String, ServiceInstance> registeredServices = new HashMap<>();
  private final List<Consumer<Set<NodeId>>> watchers = new ArrayList<>();
  private volatile Set<NodeId> lastKnownNodes = Set.of();

  /**
   * Create a ConsulServiceDiscovery client.
   *
   * @param consulUrl base URL of Consul (e.g., "http://localhost:8500")
   */
  public ConsulServiceDiscovery(String consulUrl) {
    this.consulUrl = consulUrl.replaceAll("/$", ""); // Remove trailing slash
    this.httpClient = HttpClient.newHttpClient();
    this.executor = Executors.newScheduledThreadPool(1, r -> {
      Thread t = new Thread(r, "ConsulDiscoveryWatcher");
      t.setDaemon(true);
      return t;
    });
    startPolling();
  }

  @Override
  public CompletableFuture<Void> register(NodeId nodeId, ServiceInstance instance) {
    return CompletableFuture.runAsync(
        () -> {
          String serviceId = serviceId(instance.processName(), nodeId);
          String json = buildServiceRegistration(serviceId, instance);

          HttpRequest request =
              HttpRequest.newBuilder()
                  .uri(URI.create(consulUrl + "/v1/agent/service/register"))
                  .header("Content-Type", "application/json")
                  .PUT(HttpRequest.BodyPublishers.ofString(json))
                  .timeout(Duration.ofSeconds(5))
                  .build();

          try {
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
              synchronized (registeredServices) {
                registeredServices.put(serviceId, instance);
              }
            } else {
              throw new RuntimeException(
                  "Consul register failed: " + response.statusCode() + " " + response.body());
            }
          } catch (Exception e) {
            throw new RuntimeException("Failed to register with Consul: " + e.getMessage(), e);
          }
        },
        executor);
  }

  @Override
  public CompletableFuture<Void> deregister(NodeId nodeId) {
    return CompletableFuture.runAsync(
        () -> {
          List<String> toRemove = new ArrayList<>();
          synchronized (registeredServices) {
            registeredServices.forEach(
                (id, instance) -> {
                  if (instance.nodeId().equals(nodeId)) {
                    toRemove.add(id);
                  }
                });
          }

          for (String serviceId : toRemove) {
            HttpRequest request =
                HttpRequest.newBuilder()
                    .uri(URI.create(consulUrl + "/v1/agent/service/deregister/" + serviceId))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();

            try {
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());
              synchronized (registeredServices) {
                registeredServices.remove(serviceId);
              }
            } catch (Exception e) {
              System.err.println("Failed to deregister from Consul: " + e.getMessage());
            }
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
    // Immediate notification
    onMembership.accept(listKnownServices());
  }

  @Override
  public boolean isHealthy() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(consulUrl + "/v1/agent/self"))
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

  /** Poll Consul for all services and notify watchers if membership changed. */
  private void pollAndNotify() {
    if (!running.get()) return;

    try {
      Set<NodeId> currentNodes = listKnownServices();
      if (!currentNodes.equals(lastKnownNodes)) {
        lastKnownNodes = currentNodes;
        synchronized (watchers) {
          for (Consumer<Set<NodeId>> watcher : watchers) {
            try {
              watcher.accept(currentNodes);
            } catch (Exception e) {
              System.err.println("ConsulDiscovery watcher failed: " + e.getMessage());
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("ConsulDiscovery polling error: " + e.getMessage());
    }
  }

  /** Query Consul catalog for all instances of a service. */
  private Set<NodeId> queryService(String serviceName) {
    Set<NodeId> nodes = new HashSet<>();
    try {
      String encoded = URLEncoder.encode(serviceName, StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(consulUrl + "/v1/catalog/service/" + encoded))
              .timeout(Duration.ofSeconds(5))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        // Simple regex-based parsing: extract ServiceAddress and NodeName
        String body = response.body();
        Matcher m = JSON_NODES_PATTERN.matcher(body);
        while (m.find()) {
          String address = m.group(1);
          // Reconstruct a NodeId (simplified: assumes default port)
          nodes.add(new NodeId(serviceName, address, 9999));
        }
      }
    } catch (Exception e) {
      System.err.println("Consul query failed: " + e.getMessage());
    }
    return nodes;
  }

  /** Get all known services. */
  private Set<NodeId> listKnownServices() {
    Set<NodeId> nodes = new HashSet<>();
    synchronized (registeredServices) {
      registeredServices.values().forEach(instance -> nodes.add(instance.nodeId()));
    }
    return nodes;
  }

  /** Build JSON payload for Consul service registration. */
  private String buildServiceRegistration(String serviceId, ServiceInstance instance) {
    return String.format(
        """
        {
          "ID": "%s",
          "Name": "%s",
          "Address": "%s",
          "Port": %d,
          "Meta": {
            "grpc_port": "%d"
          }
        }""",
        serviceId, instance.processName(), instance.nodeId().host(), instance.nodeId().port(),
        instance.grpcPort());
  }

  /** Generate a unique service ID for this instance. */
  private static String serviceId(String processName, NodeId nodeId) {
    return processName + "_" + nodeId.name() + "_" + nodeId.port();
  }
}
