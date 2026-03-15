/*
 * Copyright 2026 Sean Chat Mangpt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.seanchatmangpt.jotp.observability.otel;
import static org.assertj.core.api.Assertions.assertThat;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.DistributedTracer;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
/** Tests for DistributedTracerBridge. */
@DisplayName("DistributedTracerBridge Tests")
class DistributedTracerBridgeTest {
    private DistributedTracer tracer;
    private TracerProvider tracerProvider;
    private DistributedTracerBridge bridge;
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        tracer = DistributedTracer.create("test-service");
        tracerProvider = TestTracerProvider.create();
        bridge = DistributedTracerBridge.create(tracer, tracerProvider);
    }
    @Test
    @DisplayName("Create bridge with valid parameters")
    void createBridgeWithValidParameters() {
        assertThat(bridge).isNotNull();
    @DisplayName("Export empty spans")
    void exportEmptySpans() {
        // Export with no spans should not throw
        bridge.export();
    @DisplayName("Export single completed span")
    void exportSingleCompletedSpan() {
        var span =
                tracer.spanBuilder("test-operation")
                        .setAttribute("operation.type", "test")
                        .startSpan();
        span.end();
    @DisplayName("Export span with events")
    void exportSpanWithEvents() {
        var span = tracer.spanBuilder("operation-with-events").startSpan();
        span.addEvent("event1");
        span.addEvent("event2");
    @DisplayName("Export span with attributes")
    void exportSpanWithAttributes() {
                tracer.spanBuilder("operation-with-attributes")
                        .setAttribute("string.attr", "value")
                        .setAttribute("long.attr", 42L)
                        .setAttribute("double.attr", 3.14)
                        .setAttribute("boolean.attr", true)
    @DisplayName("Export span with status")
    void exportSpanWithStatus() {
        var span = tracer.spanBuilder("operation-with-status").startSpan();
        span.setStatus(DistributedTracer.StatusCode.OK);
    @DisplayName("Export span with error status")
    void exportSpanWithErrorStatus() {
        var span = tracer.spanBuilder("operation-with-error").startSpan();
        span.setStatus(DistributedTracer.StatusCode.ERROR, "Test error");
    @DisplayName("Export span with exception")
    void exportSpanWithException() {
        var span = tracer.spanBuilder("operation-with-exception").startSpan();
        span.recordException(new RuntimeException("Test exception"));
    @DisplayName("Export parent-child spans")
    void exportParentChildSpans() {
        var parentSpan = tracer.spanBuilder("parent-operation").startSpan();
        try (var scope = parentSpan.makeCurrent()) {
            var childSpan = tracer.spanBuilder("child-operation").setParent(parentSpan).startSpan();
            childSpan.end();
        }
        parentSpan.end();
    @DisplayName("Export multiple spans")
    void exportMultipleSpans() {
        for (int i = 0; i < 5; i++) {
            var span = tracer.spanBuilder("operation-" + i).setAttribute("index", i).startSpan();
            span.end();
    @DisplayName("Export clears spans from tracer")
    void exportClearsSpansFromTracer() {
        var span = tracer.spanBuilder("test-operation").startSpan();
        List<Map<String, Object>> beforeExport = tracer.exportSpans();
        assertThat(beforeExport).hasSize(1);
        List<Map<String, Object>> afterExport = tracer.exportSpans();
        assertThat(afterExport).isEmpty();
    @DisplayName("Span kind is preserved")
    void spanKindIsPreserved() {
        var serverSpan =
                tracer.spanBuilder("server-operation")
                        .setKind(DistributedTracer.SpanKind.SERVER)
        serverSpan.end();
        var clientSpan =
                tracer.spanBuilder("client-operation")
                        .setKind(DistributedTracer.SpanKind.CLIENT)
        clientSpan.end();
    @DisplayName("Tracer stats are updated after export")
    void tracerStatsAreUpdatedAfterExport() {
        var statsBefore = tracer.stats();
        var statsAfter = tracer.stats();
        assertThat(statsAfter.get("spansEnded")).isGreaterThan(statsBefore.get("spansEnded"));
    // Test TracerProvider that returns test Tracer
    private static final class TestTracerProvider implements TracerProvider {
        private final TestTracer tracer = new TestTracer();
        static TestTracerProvider create() {
            return new TestTracerProvider();
        @Override
        public Tracer get(String instrumentationScopeName) {
            return tracer;
        public TracerBuilder tracerBuilder(String instrumentationScopeName) {
            return new TracerBuilder() {
                @Override
                public Tracer build() {
                    return tracer;
                }
            };
    private static final class TestTracer implements Tracer {
        private final TestSpanBuilder spanBuilder = new TestSpanBuilder();
        public io.opentelemetry.api.trace.SpanBuilder spanBuilder(String spanName) {
            return new io.opentelemetry.api.trace.SpanBuilder() {
                public io.opentelemetry.api.trace.SpanBuilder setParent(
                        io.opentelemetry.context.Context context) {
                    return this;
                public io.opentelemetry.api.trace.SpanBuilder setSpanKind(
                        io.opentelemetry.api.trace.SpanKind kind) {
                public io.opentelemetry.api.trace.SpanBuilder setStartTimestamp(long epochNanos) {
                public io.opentelemetry.api.trace.Span startSpan() {
                    return new io.opentelemetry.api.trace.Span() {
                        @Override
                        public io.opentelemetry.context.Context storeInContext(
                                io.opentelemetry.context.Context context) {
                            return context;
                        }
                        public io.opentelemetry.api.trace.Span setStatus(
                                io.opentelemetry.api.trace.StatusCode statusCode,
                                String description) {
                            return this;
                        public io.opentelemetry.api.trace.Span recordException(
                                Throwable exception,
                                io.opentelemetry.api.common.Attributes additionalAttributes) {
                        public io.opentelemetry.api.trace.Span addEvent(
                                String name,
                                long epochNanos,
                                io.opentelemetry.api.common.Attributes attributes) {
                        public io.opentelemetry.api.trace.Span end(long epochNanos) {}
                        public io.opentelemetry.api.trace.Span end() {}
                        public io.opentelemetry.context.Context makeCurrent() {
                            return io.opentelemetry.context.Context.root();
                        public io.opentelemetry.api.trace.SpanBuilder toSpanBuilder() {
                        public io.opentelemetry.api.trace.SpanContext getSpanContext() {
                            return io.opentelemetry.api.trace.SpanContext.getInvalid();
                        public boolean isRecording() {
                            return false;
                    };
    private static final class TestSpanBuilder {
        // Test implementation
    private interface TracerBuilder {
        Tracer build();
}
