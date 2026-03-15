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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration record for OpenTelemetry service.
 *
 * <p>Defines all configuration options for OpenTelemetry integration including service name, OTLP
 * endpoint settings, export intervals, and feature flags for metrics, tracing, and logging.
 *
 * <p><strong>Default values:</strong>
 *
 * <ul>
 *   <li>serviceName: "jotp-service"
 *   <li>otlpEndpoint: "http://localhost:4318"
 *   <li>exportInterval: 60 seconds
 *   <li>exportTimeout: 30 seconds
 *   <li>enableMetrics: true
 *   <li>enableTracing: true
 *   <li>enableLogging: false
 * </ul>
 *
 * @see OpenTelemetryService
 * @since 1.0
 */
public record OtelConfiguration(
        String serviceName,
        String otlpEndpoint,
        Duration exportInterval,
        Duration exportTimeout,
        boolean enableMetrics,
        boolean enableTracing,
        boolean enableLogging) {

    /** Default configuration values. */
    private static final String DEFAULT_SERVICE_NAME = "jotp-service";

    private static final String DEFAULT_OTLP_ENDPOINT = "http://localhost:4318";
    private static final Duration DEFAULT_EXPORT_INTERVAL = Duration.ofSeconds(60);
    private static final Duration DEFAULT_EXPORT_TIMEOUT = Duration.ofSeconds(30);
    private static final boolean DEFAULT_ENABLE_METRICS = true;
    private static final boolean DEFAULT_ENABLE_TRACING = true;
    private static final boolean DEFAULT_ENABLE_LOGGING = false;

    /**
     * Creates a configuration with default values.
     *
     * @return configuration with all defaults set
     */
    public static OtelConfiguration defaults() {
        return new OtelConfiguration(
                DEFAULT_SERVICE_NAME,
                DEFAULT_OTLP_ENDPOINT,
                DEFAULT_EXPORT_INTERVAL,
                DEFAULT_EXPORT_TIMEOUT,
                DEFAULT_ENABLE_METRICS,
                DEFAULT_ENABLE_TRACING,
                DEFAULT_ENABLE_LOGGING);
    }

    /**
     * Creates a new builder for constructing OtelConfiguration instances.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Converts this configuration to an immutable map.
     *
     * @return immutable map containing all configuration values
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("serviceName", serviceName);
        map.put("otlpEndpoint", otlpEndpoint);
        map.put("exportInterval", exportInterval);
        map.put("exportTimeout", exportTimeout);
        map.put("enableMetrics", enableMetrics);
        map.put("enableTracing", enableTracing);
        map.put("enableLogging", enableLogging);
        return Map.copyOf(map);
    }

    /**
     * Builder for OtelConfiguration.
     *
     * <p>Provides fluent API for constructing configuration with selective overrides:
     *
     * <pre>{@code
     * OtelConfiguration config = OtelConfiguration.builder()
     *     .serviceName("my-service")
     *     .otlpEndpoint("http://collector:4317")
     *     .enableMetrics(false)
     *     .build();
     * }</pre>
     */
    public static final class Builder {
        private String serviceName = DEFAULT_SERVICE_NAME;
        private String otlpEndpoint = DEFAULT_OTLP_ENDPOINT;
        private Duration exportInterval = DEFAULT_EXPORT_INTERVAL;
        private Duration exportTimeout = DEFAULT_EXPORT_TIMEOUT;
        private boolean enableMetrics = DEFAULT_ENABLE_METRICS;
        private boolean enableTracing = DEFAULT_ENABLE_TRACING;
        private boolean enableLogging = DEFAULT_ENABLE_LOGGING;

        private Builder() {}

        /**
         * Sets the service name for OpenTelemetry resource detection.
         *
         * @param serviceName service name to use in telemetry
         * @return this builder
         */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /**
         * Sets the OTLP endpoint for telemetry export.
         *
         * @param otlpEndpoint OTLP endpoint URL (e.g., "http://localhost:4318")
         * @return this builder
         */
        public Builder otlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
            return this;
        }

        /**
         * Sets the interval between metric exports.
         *
         * @param exportInterval duration between exports
         * @return this builder
         */
        public Builder exportInterval(Duration exportInterval) {
            this.exportInterval = exportInterval;
            return this;
        }

        /**
         * Sets the timeout for individual export operations.
         *
         * @param exportTimeout export timeout duration
         * @return this builder
         */
        public Builder exportTimeout(Duration exportTimeout) {
            this.exportTimeout = exportTimeout;
            return this;
        }

        /**
         * Enables or disables metrics collection and export.
         *
         * @param enableMetrics true to enable metrics
         * @return this builder
         */
        public Builder enableMetrics(boolean enableMetrics) {
            this.enableMetrics = enableMetrics;
            return this;
        }

        /**
         * Enables or disables distributed tracing.
         *
         * @param enableTracing true to enable tracing
         * @return this builder
         */
        public Builder enableTracing(boolean enableTracing) {
            this.enableTracing = enableTracing;
            return this;
        }

        /**
         * Enables or disables OpenTelemetry logging integration.
         *
         * @param enableLogging true to enable logging
         * @return this builder
         */
        public Builder enableLogging(boolean enableLogging) {
            this.enableLogging = enableLogging;
            return this;
        }

        /**
         * Builds the OtelConfiguration with the specified values.
         *
         * @return new OtelConfiguration instance
         */
        public OtelConfiguration build() {
            return new OtelConfiguration(
                    serviceName,
                    otlpEndpoint,
                    exportInterval,
                    exportTimeout,
                    enableMetrics,
                    enableTracing,
                    enableLogging);
        }
    }
}
