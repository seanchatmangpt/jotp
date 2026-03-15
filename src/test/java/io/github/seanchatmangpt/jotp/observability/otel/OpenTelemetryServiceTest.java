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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for OpenTelemetryService. */
@DisplayName("OpenTelemetryService Tests")
class OpenTelemetryServiceTest {

    @Test
    @DisplayName("Create service with default configuration")
    void createWithDefaultConfiguration() {
        OpenTelemetryService service = OpenTelemetryService.create();

        assertThat(service).isNotNull();
        assertThat(service.name()).isEqualTo("otel-jotp-service");
        assertThat(service.sdk()).isNotNull();
        assertThat(service.resource()).isNotNull();
        assertThat(service.meterProvider()).isNotNull();
        assertThat(service.tracerProvider()).isNotNull();
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
        ApplicationMock app = new ApplicationMock();

        // Should not throw
        service.onStop(app);

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

    // Mock Application for testing
    private static final class ApplicationMock {
        // Minimal mock for testing
    }
}
