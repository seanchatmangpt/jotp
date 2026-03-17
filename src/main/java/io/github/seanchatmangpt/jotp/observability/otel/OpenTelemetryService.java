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

import io.github.seanchatmangpt.jotp.Application;
import java.util.Map;
import java.util.Objects;

/**
 * OpenTelemetry service for JOTP observability integration.
 *
 * <p>This service provides OpenTelemetry integration for metrics, tracing, and logging. It acts as
 * an {@link Application.Infrastructure} component, automatically managing the lifecycle of
 * OpenTelemetry SDK instances.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Create with defaults
 * OpenTelemetryService service = OpenTelemetryService.create();
 *
 * // Create with custom service name
 * OpenTelemetryService service = OpenTelemetryService.create("my-service");
 *
 * // Create with custom configuration
 * OtelConfiguration config = OtelConfiguration.builder()
 *     .serviceName("my-service")
 *     .otlpEndpoint("http://collector:4317")
 *     .enableMetrics(true)
 *     .enableTracing(true)
 *     .build();
 * OpenTelemetryService service = OpenTelemetryService.create(config);
 * }</pre>
 *
 * <p><strong>Integration with ApplicationController:</strong>
 *
 * <pre>{@code
 * var spec = ApplicationSpec.builder("otel-app")
 *     .description("OpenTelemetry integration")
 *     .mod((startType, args) -> OpenTelemetryService.create())
 *     .build();
 * ApplicationController.load(spec);
 * ApplicationController.start("otel-app", RunType.PERMANENT);
 * }</pre>
 *
 * @see OtelConfiguration
 * @see Application.Infrastructure
 * @since 1.0
 */
public final class OpenTelemetryService implements Application.Infrastructure {

    private final String name;
    private final OtelConfiguration configuration;
    private final OpenTelemetrySdk sdk;
    private final Object resource;
    private final Object meterProvider;
    private final Object tracerProvider;
    private volatile boolean shutdown;

    private OpenTelemetryService(String name, OtelConfiguration configuration) {
        this.name = name;
        this.configuration = configuration;
        this.sdk = new OpenTelemetrySdk(configuration);
        this.resource = sdk.resource();
        this.meterProvider = sdk.meterProvider();
        this.tracerProvider = sdk.tracerProvider();
        this.shutdown = false;
    }

    /**
     * Creates an OpenTelemetry service with default configuration.
     *
     * <p>The service will be named "otel-jotp-service" with default OTLP endpoint and all features
     * enabled.
     *
     * @return new OpenTelemetryService instance
     */
    public static OpenTelemetryService create() {
        return create(OtelConfiguration.defaults());
    }

    /**
     * Creates an OpenTelemetry service with a custom service name.
     *
     * <p>The service name will be prefixed with "otel-" for consistency.
     *
     * @param serviceName custom service name
     * @return new OpenTelemetryService instance
     */
    public static OpenTelemetryService create(String serviceName) {
        OtelConfiguration config = OtelConfiguration.builder().serviceName(serviceName).build();
        return create(config);
    }

    /**
     * Creates an OpenTelemetry service with custom configuration.
     *
     * @param configuration custom configuration
     * @return new OpenTelemetryService instance
     */
    public static OpenTelemetryService create(OtelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration cannot be null");
        String name = "otel-" + configuration.serviceName();
        return new OpenTelemetryService(name, configuration);
    }

    /**
     * Returns the service name.
     *
     * @return service name (prefixed with "otel-")
     */
    public String name() {
        return name;
    }

    /**
     * Returns the configuration as an immutable map.
     *
     * @return immutable map of configuration values
     */
    public Map<String, Object> configuration() {
        return configuration.toMap();
    }

    /**
     * Returns the OpenTelemetry SDK instance.
     *
     * <p>Note: This returns a placeholder Object until actual OpenTelemetry SDK is integrated.
     *
     * @return OpenTelemetry SDK instance (placeholder)
     */
    public Object sdk() {
        return sdk;
    }

    /**
     * Returns the OpenTelemetry Resource.
     *
     * <p>Note: This returns a placeholder Object until actual OpenTelemetry SDK is integrated.
     *
     * @return OpenTelemetry Resource (placeholder)
     */
    public Object resource() {
        return resource;
    }

    /**
     * Returns the MeterProvider for metrics collection.
     *
     * <p>Note: This returns a placeholder Object until actual OpenTelemetry SDK is integrated.
     *
     * @return MeterProvider instance (placeholder)
     */
    public Object meterProvider() {
        return meterProvider;
    }

    /**
     * Returns the TracerProvider for distributed tracing.
     *
     * <p>Note: This returns a placeholder Object until actual OpenTelemetry SDK is integrated.
     *
     * @return TracerProvider instance (placeholder)
     */
    public Object tracerProvider() {
        return tracerProvider;
    }

    @Override
    public void onStop(Application<?> app) {
        // Graceful shutdown of OpenTelemetry SDK
        if (!shutdown) {
            shutdown = true;
            // Shutdown is handled by the OpenTelemetrySdk placeholder
            // When actual OpenTelemetry SDK is integrated, call sdk.shutdown() here
        }
    }

    /**
     * Placeholder for OpenTelemetry SDK.
     *
     * <p>This will be replaced with actual io.opentelemetry.sdk.OpenTelemetrySdk once the
     * dependency is added to pom.xml.
     */
    private static final class OpenTelemetrySdk {
        private final OtelConfiguration config;

        OpenTelemetrySdk(OtelConfiguration config) {
            this.config = config;
        }

        Object resource() {
            // Placeholder for Resource
            return new Object() {
                @Override
                public String toString() {
                    return "Resource(serviceName=" + config.serviceName() + ")";
                }
            };
        }

        Object meterProvider() {
            // Placeholder for MeterProvider
            return new Object() {
                @Override
                public String toString() {
                    return "MeterProvider(enabled=" + config.enableMetrics() + ")";
                }
            };
        }

        Object tracerProvider() {
            // Placeholder for TracerProvider
            return new Object() {
                @Override
                public String toString() {
                    return "TracerProvider(enabled=" + config.enableTracing() + ")";
                }
            };
        }
    }

    @Override
    public String toString() {
        return "OpenTelemetryService[name=" + name + ", shutdown=" + shutdown + "]";
    }
}
