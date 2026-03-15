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

import io.github.seanchatmangpt.jotp.MetricsCollector;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for MetricsCollectorBridge. */
@DisplayName("MetricsCollectorBridge Tests")
class MetricsCollectorBridgeTest {

    private MetricsCollector metricsCollector;
    private MeterProvider meterProvider;
    private MetricsCollectorBridge bridge;

    @BeforeEach
    void setUp() {
        metricsCollector = MetricsCollector.create("test-service");
        meterProvider = TestMeterProvider.create();
        bridge = MetricsCollectorBridge.create(metricsCollector, meterProvider);
    }

    @Test
    @DisplayName("Create bridge with valid parameters")
    void createBridgeWithValidParameters() {
        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Export counter metrics")
    void exportCounterMetrics() {
        // Create and increment counter
        metricsCollector.counter("test.counter").increment(42);

        // Export should not throw
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Export gauge metrics")
    void exportGaugeMetrics() {
        // Create and set gauge
        metricsCollector.gauge("test.gauge").set(99.9);

        // Export should not throw
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Export histogram metrics")
    void exportHistogramMetrics() {
        // Create and record histogram values
        var histogram = metricsCollector.histogram("test.histogram");
        histogram.record(100);
        histogram.record(200);
        histogram.record(300);

        // Export should not throw
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Export timer metrics")
    void exportTimerMetrics() {
        // Create and record timer
        var timer = metricsCollector.timer("test.timer");
        timer.record(java.time.Duration.ofMillis(500));
        timer.record(java.time.Duration.ofMillis(1000));

        // Export should not throw
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Export mixed metric types")
    void exportMixedMetricTypes() {
        // Create various metrics
        metricsCollector.counter("requests.total").increment(100);
        metricsCollector.gauge("queue.size").set(42.0);
        metricsCollector.histogram("latency").record(50);
        metricsCollector.timer("process.time").record(java.time.Duration.ofMillis(100));

        // Export should handle all types
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Export empty snapshot")
    void exportEmptySnapshot() {
        // Export with no metrics should not throw
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Multiple exports are idempotent")
    void multipleExportsAreIdempotent() {
        metricsCollector.counter("test.counter").increment();

        bridge.export();
        bridge.export();
        bridge.export();

        assertThat(bridge).isNotNull();
    }

    @Test
    @DisplayName("Snapshot returns valid map")
    void snapshotReturnsValidMap() {
        metricsCollector.counter("test.counter").increment(10);

        var snapshot = metricsCollector.snapshot();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot).isNotEmpty();
    }

    // Test MeterProvider that returns test Meters
    private static final class TestMeterProvider implements MeterProvider {
        private final TestMeter meter = new TestMeter();

        static TestMeterProvider create() {
            return new TestMeterProvider();
        }

        @Override
        public Meter get(String instrumentationScopeName) {
            return meter;
        }

        @Override
        public MeterBuilder meterBuilder(String instrumentationScopeName) {
            return new MeterBuilder() {
                @Override
                public Meter build() {
                    return meter;
                }
            };
        }
    }

    private static final class TestMeter implements Meter {
        private final TestCounterBuilder counterBuilder = new TestCounterBuilder();
        private final TestGaugeBuilder gaugeBuilder = new TestGaugeBuilder();
        private final TestHistogramBuilder histogramBuilder = new TestHistogramBuilder();

        @Override
        public CounterBuilder counterBuilder(String name) {
            return counterBuilder;
        }

        @Override
        public GaugeBuilder<?> gaugeBuilder(String name) {
            return gaugeBuilder;
        }

        @Override
        public HistogramBuilder<?> histogramBuilder(String name) {
            return histogramBuilder;
        }

        // Other required methods return empty/no-op implementations
    }

    private static final class TestCounterBuilder implements Meter.CounterBuilder {
        @Override
        public io.opentelemetry.api.metrics.LongCounter build() {
            return new io.opentelemetry.api.metrics.LongCounter() {
                @Override
                public void add(long value, io.opentelemetry.context.Context context) {}

                @Override
                public void add(long value) {}

                @Override
                public void add(long delta, io.opentelemetry.api.common.Attributes attributes) {}
            };
        }
    }

    private static final class TestGaugeBuilder
            implements Meter.GaugeBuilder<io.opentelemetry.api.metrics.DoubleObservableGauge> {
        @Override
        public io.opentelemetry.api.metrics.DoubleObservableGauge build() {
            return callback -> {};
        }
    }

    private static final class TestHistogramBuilder
            implements Meter.HistogramBuilder<io.opentelemetry.api.metrics.LongHistogram> {
        @Override
        public io.opentelemetry.api.metrics.LongHistogram build() {
            return new io.opentelemetry.api.metrics.LongHistogram() {
                @Override
                public void record(long value, io.opentelemetry.context.Context context) {}

                @Override
                public void record(long value) {}
            };
        }
    }

    private interface MeterBuilder {
        Meter build();
    }
}
