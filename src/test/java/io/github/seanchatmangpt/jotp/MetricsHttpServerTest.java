package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for MetricsHttpServer. */
class MetricsHttpServerTest {

  private MetricsCollector metricsCollector;
  private MetricsHttpServer server;
  private static final int TEST_PORT = 18888;

  @BeforeEach
  void setup() throws IOException {
    ApplicationController.reset();
    metricsCollector = MetricsCollector.create("test-metrics");
    server = MetricsHttpServer.create(TEST_PORT, metricsCollector);
    server.start();
  }

  @AfterEach
  void teardown() {
    if (server != null && server.isRunning()) {
      server.stop();
    }
  }

  @Test
  void testServerStartsAndStops() {
    assertThat(server.isRunning()).isTrue();
    assertThat(server.getPort()).isEqualTo(TEST_PORT);

    server.stop();
    assertThat(server.isRunning()).isFalse();
  }

  @Test
  void testMetricsEndpoint() throws IOException, InterruptedException {
    metricsCollector.counter("test.counter").increment(5);
    metricsCollector.gauge("test.gauge", () -> 42.0).set(42.0);

    String response = getResponse("/metrics");

    assertThat(response).isNotEmpty();
    assertThat(response).contains("test.counter");
    assertThat(response).contains("test.gauge");
    assertThat(response).contains("\"test.counter\":5");
    assertThat(response).contains("\"test.gauge\":42");
  }

  @Test
  void testHealthEndpoint() throws IOException, InterruptedException {
    String response = getResponse("/health");

    assertThat(response).isNotEmpty();
    assertThat(response).contains("\"status\"");
    assertThat(response).contains("\"UP\"");
    assertThat(response).contains("\"timestamp\"");
  }

  @Test
  void testNodesEndpoint() throws IOException, InterruptedException {
    String response = getResponse("/nodes");

    assertThat(response).isNotEmpty();
    assertThat(response).contains("\"nodes\"");
  }

  private String getResponse(String path) throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + path))
            .GET()
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return response.body();
  }
}
