/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.observability.otel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for OpenTelemetryService. */
@DisplayName("OpenTelemetryService: Distributed Tracing Integration")
class OpenTelemetryServiceTest {


    @Test
    @DisplayName("Create service with default configuration")
    void createWithDefaultConfiguration() {
                """
                OpenTelemetryService provides a zero-setup integration point for distributed tracing
                and metrics export. The factory method creates a fully configured OpenTelemetry SDK
                with sensible defaults for development and production use.

                Current implementation is a placeholder — actual SDK integration pending.
                The architecture supports OTLP export to collectors like Grafana, Jaeger, and OTEL
                backends.
                """);

                """
                // Zero-configuration factory method
                OpenTelemetryService service = OpenTelemetryService.create();

                // Auto-configured with defaults:
                // - Service name: "jotp-service"
                // - OTLP endpoint: "http://localhost:4318"
                // - Metrics: enabled
                // - Tracing: enabled
                // - Logging: disabled
                """,
                "java");

        OpenTelemetryService service = OpenTelemetryService.create();

        assertThat(service).isNotNull();
        assertThat(service.name()).isEqualTo("otel-jotp-service");
        assertThat(service.sdk()).isNotNull();
        assertThat(service.resource()).isNotNull();
        assertThat(service.meterProvider()).isNotNull();
        assertThat(service.tracerProvider()).isNotNull();

                new String[][] {
                    {"Component", "Purpose", "Status"},
                    {"SDK", "OpenTelemetry SDK instance", "Placeholder"},
                    {"Resource", "Service identity metadata", "Configured"},
                    {"MeterProvider", "Metrics collection API", "Placeholder"},
                    {"TracerProvider", "Distributed tracing API", "Placeholder"}
                });

                Map.of(
                        "Service Name",
                        service.name(),
                        "SDK Type",
                        service.sdk().getClass().getSimpleName(),
                        "Resource",
                        service.resource().toString(),
                        "MeterProvider",
                        service.meterProvider().toString(),
                        "TracerProvider",
                        service.tracerProvider().toString()));

                "The placeholder pattern allows testing lifecycle and configuration without"
                        + " requiring the full OpenTelemetry SDK dependency. Integration is additive"
                        + " — no breaking changes when SDK is added.");
    }

    @Test
    @DisplayName("Create service with custom service name")
    void createWithCustomServiceName() {
        OpenTelemetryService service = OpenTelemetryService.create("my-custom-service");

        assertThat(service).isNotNull();
        assertThat(service.name()).isEqualTo("otel-my-custom-service");
    }

    @Test
    @DisplayName("Create service with custom configuration")
    void createWithCustomConfiguration() {
                """
                OpenTelemetry uses the OpenTelemetry Protocol (OTLP) for exporting telemetry data.
                Configuration includes endpoint, export intervals, timeouts, and feature flags for
                metrics/tracing/logging.

                OTLP supports both HTTP (port 4318) and gRPC (port 4317) transports. The default
                HTTP endpoint is compatible with most collectors including Grafana, Jaeger, and
                OpenTelemetry Collector.
                """);

                """
                // Builder pattern for custom configuration
                OtelConfiguration config = OtelConfiguration.builder()
                    .serviceName("test-service")
                    .otlpEndpoint("http://localhost:4318")
                    .exportInterval(Duration.ofSeconds(10))
                    .enableMetrics(true)
                    .enableTracing(false)
                    .build();

                OpenTelemetryService service = OpenTelemetryService.create(config);
                """,
                "java");

        OtelConfiguration config =
                OtelConfiguration.builder()
                        .serviceName("test-service")
                        .otlpEndpoint("http://localhost:4318")
                        .exportInterval(Duration.ofSeconds(10))
                        .enableMetrics(true)
                        .enableTracing(false)
                        .build();

        OpenTelemetryService service = OpenTelemetryService.create(config);

        assertThat(service).isNotNull();
        assertThat(service.name()).isEqualTo("otel-test-service");
        assertThat(service.configuration()).containsEntry("serviceName", "test-service");
        assertThat(service.configuration()).containsEntry("otlpEndpoint", "http://localhost:4318");
        assertThat(service.configuration()).containsEntry("exportInterval", Duration.ofSeconds(10));
        assertThat(service.configuration()).containsEntry("enableMetrics", true);
        assertThat(service.configuration()).containsEntry("enableTracing", false);

                new String[][] {
                    {"Setting", "Default", "Custom", "Impact"},
                    {"serviceName", "jotp-service", "test-service", "Resource identity"},
                    {
                        "otlpEndpoint",
                        "http://localhost:4318",
                        "http://localhost:4318",
                        "Export destination"
                    },
                    {"exportInterval", "60s", "10s", "Batch export frequency"},
                    {"enableMetrics", "true", "true", "Metrics collection"},
                    {"enableTracing", "true", "false", "Distributed tracing"}
                });

                Map.of(
                        "Configuration Type",
                        "OtelConfiguration",
                        "Pattern",
                        "Builder",
                        "Immutability",
                        "Record (immutable)",
                        "Export Format",
                        "OTLP (protobuf)",
                        "Transport",
                        "HTTP/2 or gRPC"));

                "Disable tracing (enableTracing=false) in high-throughput scenarios where span"
                        + " collection overhead is unacceptable. Metrics-only mode still provides"
                        + " visibility without performance impact.");
    }

    @Test
    @DisplayName("Configuration record equality")
    void configurationRecordEquality() {
        OtelConfiguration config1 = OtelConfiguration.defaults();
        OtelConfiguration config2 = OtelConfiguration.defaults();

        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    @DisplayName("Configuration builder pattern")
    void configurationBuilderPattern() {
                """
                Export tuning is critical for performance profiling in production systems:

                - exportInterval: How often to batch and send telemetry (default: 60s)
                - exportTimeout: Maximum time to wait for export ACK (default: 30s)
                - Feature flags: Selectively enable/disable telemetry types

                High-throughput systems may need shorter intervals (10-30s) to avoid memory buildup
                from buffered spans/metrics. Low-traffic systems can use longer intervals (60-120s)
                to reduce export overhead.
                """);

                """
                // High-throughput configuration: frequent exports, tracing only
                OtelConfiguration config = OtelConfiguration.builder()
                    .serviceName("builder-test")
                    .otlpEndpoint("http://collector:4317")
                    .exportInterval(Duration.ofSeconds(30))
                    .exportTimeout(Duration.ofSeconds(60))
                    .enableMetrics(false)
                    .enableTracing(true)
                    .enableLogging(false)
                    .build();
                """,
                "java");

        OtelConfiguration config =
                OtelConfiguration.builder()
                        .serviceName("builder-test")
                        .otlpEndpoint("http://collector:4317")
                        .exportInterval(Duration.ofSeconds(30))
                        .exportTimeout(Duration.ofSeconds(60))
                        .enableMetrics(false)
                        .enableTracing(true)
                        .enableLogging(false)
                        .build();

        assertThat(config.serviceName()).isEqualTo("builder-test");
        assertThat(config.otlpEndpoint()).isEqualTo("http://collector:4317");
        assertThat(config.exportInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.exportTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.enableMetrics()).isFalse();
        assertThat(config.enableTracing()).isTrue();
        assertThat(config.enableLogging()).isFalse();

                new String[][] {
                    {"Workload Type", "exportInterval", "enableMetrics", "enableTracing"},
                    {"High-throughput", "10-30s", "false", "true"},
                    {"Low-traffic", "60-120s", "true", "true"},
                    {"Metrics-only", "60s", "true", "false"},
                    {"Tracing-only", "30s", "false", "true"}
                });

                Map.of(
                        "Export Interval",
                        "30s",
                        "Export Timeout",
                        "60s",
                        "Metrics Enabled",
                        "false",
                        "Tracing Enabled",
                        "true",
                        "Logging Enabled",
                        "false",
                        "Use Case",
                        "High-throughput tracing"));

                "gRPC endpoint (4317) is preferred over HTTP (4318) for high-volume scenarios"
                        + " due to better throughput and lower latency. Use HTTP for compatibility"
                        + " with older collectors.");
    }

    @Test
    @DisplayName("Service name getter returns correct name")
    void serviceGetNameReturnsCorrectName() {
        OpenTelemetryService service = OpenTelemetryService.create("named-service");

        assertThat(service.name()).isEqualTo("otel-named-service");
    }

    @Test
    @DisplayName("Configuration returns immutable map")
    void configurationReturnsImmutableMap() {
        OpenTelemetryService service = OpenTelemetryService.create();

        var config = service.configuration();

        assertThat(config).isNotNull();
        // Verify it's an unmodifiable map by attempting to modify
        try {
            config.put("new-key", "new-value");
            assertThat(false).isTrue(); // Should not reach here
        } catch (UnsupportedOperationException e) {
            // Expected
            assertThat(true).isTrue();
        }
    }

    @Test
    @DisplayName("OnStop gracefully handles shutdown")
    void onStopGracefullyHandlesShutdown() {
        OpenTelemetryService service = OpenTelemetryService.create();
        // OnStop with null application should not throw
        service.onStop(null);

        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("Multiple service instances are independent")
    void multipleServiceInstancesAreIndependent() {
        OpenTelemetryService service1 = OpenTelemetryService.create("service-1");
        OpenTelemetryService service2 = OpenTelemetryService.create("service-2");

        assertThat(service1.name()).isEqualTo("otel-service-1");
        assertThat(service2.name()).isEqualTo("otel-service-2");
        assertThat(service1.sdk()).isNotSameAs(service2.sdk());
    }
}
