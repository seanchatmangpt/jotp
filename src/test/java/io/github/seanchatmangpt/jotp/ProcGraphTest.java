package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProcGraph} — live process supervision tree visualizer.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>{@link ProcGraph#start(Supervisor)} returns a valid handle with port and URL
 *   <li>GET {@code /api/snapshot} returns 200 with valid JSON
 *   <li>GET {@code /} returns 200 HTML containing "ProcGraph"
 *   <li>{@link ProcGraph#stop(ProcGraph.Handle)} shuts down cleanly
 * </ul>
 */
@DisplayName("ProcGraph: Live Supervision Tree Visualizer")
class ProcGraphTest {

    private Supervisor supervisor;
    private ProcGraph.Handle handle;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        supervisor = Supervisor.create(Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
        supervisor.supervise("worker-1", 0, (state, msg) -> state);
        supervisor.supervise("worker-2", 0, (state, msg) -> state);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (handle != null) {
            ProcGraph.stop(handle);
            handle = null;
        }
        supervisor.shutdown();
    }

    @Test
    @DisplayName("start() returns a Handle with valid port and URL")
    void startReturnsValidHandle() throws IOException {
        handle = ProcGraph.start(supervisor);

        assertThat(handle).isNotNull();
        assertThat(handle.port()).isBetween(1, 65535);
        assertThat(handle.url()).startsWith("http://localhost:");
        assertThat(handle.url()).contains(String.valueOf(handle.port()));
    }

    @Test
    @DisplayName("start(supervisor, port) binds to specified port")
    void startWithExplicitPortBindsCorrectly() throws IOException {
        handle = ProcGraph.start(supervisor, 0); // 0 = OS-assigned port

        assertThat(handle.port()).isGreaterThan(0);
        assertThat(handle.url()).isEqualTo("http://localhost:" + handle.port());
    }

    @Test
    @DisplayName("GET /api/snapshot returns HTTP 200 with valid JSON")
    void snapshotEndpointReturnsJson() throws IOException, InterruptedException {
        handle = ProcGraph.start(supervisor);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(handle.url() + "/api/snapshot"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).isNotBlank();
        // Validate JSON structure
        assertThat(body).contains("\"timestamp\"");
        assertThat(body).contains("\"nodes\"");
        assertThat(body).contains("\"edges\"");
        // Should contain our supervisor and workers
        assertThat(body).contains("supervisor-root");
        assertThat(body).contains("worker-1");
        assertThat(body).contains("worker-2");
    }

    @Test
    @DisplayName("GET / returns HTTP 200 HTML containing 'ProcGraph'")
    void rootEndpointReturnsHtml() throws IOException, InterruptedException {
        handle = ProcGraph.start(supervisor);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(handle.url() + "/"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        assertThat(body).contains("ProcGraph");
        assertThat(body).containsIgnoringCase("<!DOCTYPE html>");
        assertThat(body).contains("/api/snapshot");
    }

    @Test
    @DisplayName("stop() shuts down the server cleanly")
    void stopShutsDownCleanly() throws IOException, InterruptedException {
        handle = ProcGraph.start(supervisor);
        int port = handle.port();

        ProcGraph.stop(handle);

        // After stop, connections should be refused
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/snapshot"))
                        .timeout(Duration.ofMillis(500))
                        .GET()
                        .build();

        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);

        handle = null; // prevent tearDown from calling stop again
    }

    @Test
    @DisplayName("snapshot reflects supervisor status")
    void snapshotReflectsSupervisorStatus() throws IOException, InterruptedException {
        handle = ProcGraph.start(supervisor);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(handle.url() + "/api/snapshot"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"running\"");
        assertThat(response.body()).contains("\"childCount\":2");
    }
}
