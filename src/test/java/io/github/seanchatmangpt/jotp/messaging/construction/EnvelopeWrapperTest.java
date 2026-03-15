package io.github.seanchatmangpt.jotp.messaging.construction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Timeout;
/**
 * Tests for {@link EnvelopeWrapper} — header envelope pattern.
 *
 * <p>EnvelopeWrapper enables stateless routing, distributed tracing, SLA enforcement, and
 * idempotent retry semantics in loosely-coupled services.
 */
@Timeout(5)
class EnvelopeWrapperTest implements WithAssertions {
    record TestPayload(String id, String data) {}
    // ── Envelope creation ────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    void wrapCreatesEnvelopeWithPayload() {
        String payload = "test data";
        EnvelopeWrapper.Envelope<String> envelope = EnvelopeWrapper.wrap(payload);
        assertThat(envelope.id()).isNotNull();
        assertThat(envelope.timestamp()).isGreaterThan(0);
        assertThat(envelope.payload()).isEqualTo(payload);
        assertThat(envelope.headers()).isEmpty();
    void wrapWithHeadersIncludesMetadata() {
        String payload = "test";
        Map<String, String> headers =
                Map.of(
                        "correlation-id", "trace-123",
                        "priority", "high");
        EnvelopeWrapper.Envelope<String> envelope = EnvelopeWrapper.wrap(payload, headers);
        assertThat(envelope.getHeader("correlation-id")).isEqualTo("trace-123");
        assertThat(envelope.getHeader("priority")).isEqualTo("high");
    void wrapWithHeaderBuilderFunctional() {
        EnvelopeWrapper.Envelope<String> envelope =
                EnvelopeWrapper.wrap(
                        payload,
                        headers -> {
                            headers.put("source", "order-service");
                            headers.put("version", "1.0");
                        });
        assertThat(envelope.getHeader("source")).isEqualTo("order-service");
        assertThat(envelope.getHeader("version")).isEqualTo("1.0");
    // ── Header access ───────────────────────────────────────────────────
    void getHeaderReturnsValue() {
                EnvelopeWrapper.wrap("payload", Map.of("key1", "value1"));
        assertThat(envelope.getHeader("key1")).isEqualTo("value1");
    void getHeaderReturnsNullForMissing() {
        EnvelopeWrapper.Envelope<String> envelope = EnvelopeWrapper.wrap("payload");
        assertThat(envelope.getHeader("missing")).isNull();
    void getHeaderOrDefaultReturnsFallback() {
        String value = envelope.getHeaderOrDefault("missing", "fallback");
        assertThat(value).isEqualTo("fallback");
    void hasHeaderChecksPresence() {
        assertThat(envelope.hasHeader("key1")).isTrue();
        assertThat(envelope.hasHeader("missing")).isFalse();
    // ── Payload access ──────────────────────────────────────────────────
    void unwrapReturnsPayload() {
        TestPayload payload = new TestPayload("id-1", "data-1");
        EnvelopeWrapper.Envelope<TestPayload> envelope = EnvelopeWrapper.wrap(payload);
        TestPayload result = envelope.unwrap();
        assertThat(result).isEqualTo(payload);
    void staticUnwrapReturnsPayload() {
        String payload = "data";
        String result = EnvelopeWrapper.unwrap(envelope);
    // ── Header manipulation ──────────────────────────────────────────────
    void withHeadersAddsNewHeaders() {
                EnvelopeWrapper.wrap("payload", Map.of("h1", "v1"));
        EnvelopeWrapper.Envelope<String> updated = envelope.withHeaders(Map.of("h2", "v2"));
        assertThat(updated.getHeader("h1")).isEqualTo("v1");
        assertThat(updated.getHeader("h2")).isEqualTo("v2");
    void withHeaderSingleHeaderAddsOne() {
        EnvelopeWrapper.Envelope<String> updated = envelope.withHeader("h2", "v2");
    void withHeadersPreservesPayloadAndId() {
        UUID originalId = envelope.id();
        EnvelopeWrapper.Envelope<String> updated = envelope.withHeader("new", "value");
        assertThat(updated.id()).isEqualTo(originalId);
        assertThat(updated.payload()).isEqualTo(payload);
    void replaceHeadersSubstitutesAllHeaders() {
                        "payload",
                        Map.of(
                                "h1", "v1",
                                "h2", "v2"));
        Map<String, String> newHeaders = Collections.unmodifiableMap(Map.of("h3", "v3"));
        EnvelopeWrapper.Envelope<String> updated =
                EnvelopeWrapper.replaceHeaders(envelope, newHeaders);
        assertThat(updated.hasHeader("h1")).isFalse();
        assertThat(updated.hasHeader("h2")).isFalse();
        assertThat(updated.getHeader("h3")).isEqualTo("v3");
    // ── Special headers (tracing, idempotency, SLA) ───────────────────────
    void getCorrelationIdFromHeader() {
                EnvelopeWrapper.wrap("payload", Map.of("correlation-id", "trace-abc123"));
        assertThat(envelope.getCorrelationId()).isEqualTo("trace-abc123");
    void getCorrelationIdFallsBackToEnvelopeId() {
        String correlationId = envelope.getCorrelationId();
        assertThat(correlationId).isEqualTo(envelope.id().toString());
    void getRequestIdExtractsIdempotencyKey() {
                EnvelopeWrapper.wrap("payload", Map.of("request-id", "req-xyz789"));
        assertThat(envelope.getRequestId()).isEqualTo("req-xyz789");
    void getDeadlineExtractsTimestamp() {
        long deadline = System.currentTimeMillis() + 60000; // 60 seconds from now
                EnvelopeWrapper.wrap("payload", Map.of("deadline", String.valueOf(deadline)));
        assertThat(envelope.getDeadline()).isEqualTo(String.valueOf(deadline));
    void getPriorityExtractsPriorityLevel() {
                EnvelopeWrapper.wrap("payload", Map.of("priority", "high"));
        assertThat(envelope.getPriority()).isEqualTo("high");
    // ── Type safety ──────────────────────────────────────────────────────
    void envelopeHandlesGenericTypes() {
        TestPayload payload = new TestPayload("id-1", "data");
        TestPayload unwrapped = envelope.unwrap();
        assertThat(unwrapped).isEqualTo(payload);
        assertThat(unwrapped.id()).isEqualTo("id-1");
    void envelopeHandlesComplexGenericHierarchy() {
        java.util.List<String> payload = java.util.List.of("a", "b", "c");
        EnvelopeWrapper.Envelope<java.util.List<String>> envelope = EnvelopeWrapper.wrap(payload);
        java.util.List<String> unwrapped = envelope.unwrap();
        assertThat(unwrapped).containsExactly("a", "b", "c");
    // ── Immutability ─────────────────────────────────────────────────────
    void headersAreImmutable() {
                EnvelopeWrapper.wrap("payload", Map.of("key", "value"));
        assertThatThrownBy(() -> envelope.headers().put("new", "bad"))
                .isInstanceOf(UnsupportedOperationException.class);
    void withHeadersDoesNotMutateOriginal() {
        envelope.withHeader("h2", "v2");
        assertThat(envelope.hasHeader("h2")).isFalse();
    // ── Error handling and validation ────────────────────────────────────
    void wrapRejectsNullPayload() {
        assertThatThrownBy(() -> EnvelopeWrapper.wrap(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be null");
    void compactConstructorValidatesId() {
        UUID id = UUID.randomUUID();
        Map<String, String> headers = Collections.unmodifiableMap(Map.of());
        assertThatThrownBy(
                        () ->
                                new EnvelopeWrapper.Envelope<>(
                                        null, System.currentTimeMillis(), headers, "payload"))
                .hasMessageContaining("id must not be null");
    void compactConstructorValidatesHeaders() {
                                        id, System.currentTimeMillis(), null, "payload"))
                .hasMessageContaining("headers must not be null");
    void compactConstructorValidatesPayload() {
                                        id, System.currentTimeMillis(), headers, null))
    // ── Integration scenarios ────────────────────────────────────────────
    void distributedTracingScenario() {
        // Simulate request flowing through multiple services
        EnvelopeWrapper.Envelope<String> request =
                        "order-data",
                            headers.put("correlation-id", "req-123");
                            headers.put("source-service", "api-gateway");
        String correlationId = request.getCorrelationId();
        String sourceService = request.getHeader("source-service");
        assertThat(correlationId).isEqualTo("req-123");
        assertThat(sourceService).isEqualTo("api-gateway");
    void slaEnforcementScenario() {
        long now = System.currentTimeMillis();
        long deadlineMs = now + 5000; // 5 seconds
                        "data",
                            headers.put("deadline", String.valueOf(deadlineMs));
                            headers.put("priority", "high");
        assertThat(request.getDeadline()).isEqualTo(String.valueOf(deadlineMs));
        assertThat(request.getPriority()).isEqualTo("high");
    void idempotencyScenario() {
        String idempotencyKey = "idempotency-key-xyz";
                        "payment-request",
                            headers.put("request-id", idempotencyKey);
        assertThat(request.getRequestId()).isEqualTo(idempotencyKey);
        // In reality, the system would check if this request-id was already processed
}
