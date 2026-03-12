package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Message Construction patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 */
@DisplayName("Message Construction Patterns")
class ConstructionPatternsTest implements WithAssertions {

    @Nested
    @DisplayName("CorrelationIdentifier")
    class CorrelationIdentifierTests {

        @Test
        @DisplayName("unique IDs do not collide")
        void uniqueIds() {
            var id1 = CorrelationIdentifier.create();
            var id2 = CorrelationIdentifier.create();
            assertThat(id1.matches(id2)).isFalse();
        }

        @Test
        @DisplayName("same ID matches itself")
        void sameIdMatches() {
            var id = CorrelationIdentifier.create();
            var copy = CorrelationIdentifier.of(id.id());
            assertThat(id.matches(copy)).isTrue();
        }
    }

    @Nested
    @DisplayName("MessageExpiration")
    class MessageExpirationTests {

        record TestOrder(String id, Instant occurredOn, Duration timeToLive)
                implements MessageExpiration {}

        @Test
        @DisplayName("message with long TTL is not expired")
        void notExpired() {
            var order = new TestOrder("o1", Instant.now(), Duration.ofHours(1));
            assertThat(order.isExpired()).isFalse();
        }

        @Test
        @DisplayName("message with zero TTL is expired")
        void expired() {
            var order = new TestOrder("o1", Instant.now().minusSeconds(1), Duration.ZERO);
            assertThat(order.isExpired()).isTrue();
        }

        @Test
        @DisplayName("expiresAt returns correct instant")
        void expiresAt() {
            var now = Instant.now();
            var ttl = Duration.ofMinutes(5);
            var order = new TestOrder("o1", now, ttl);
            assertThat(order.expiresAt()).isEqualTo(now.plus(ttl));
        }
    }

    @Nested
    @DisplayName("MessageMetadata")
    class MessageMetadataTests {

        @Test
        @DisplayName("empty metadata has no entries")
        void empty() {
            var metadata = MessageMetadata.empty();
            assertThat(metadata.size()).isZero();
            assertThat(metadata.lastEntry()).isNull();
        }

        @Test
        @DisplayName("addEntry accumulates audit trail")
        void accumulates() {
            var metadata =
                    MessageMetadata.empty()
                            .addEntry("OrderService", "validated", "Router", "business rules")
                            .addEntry("InventoryService", "reserved", "StockMgr", "allocated");

            assertThat(metadata.size()).isEqualTo(2);
            assertThat(metadata.lastEntry().who()).isEqualTo("InventoryService");
            assertThat(metadata.entries().getFirst().who()).isEqualTo("OrderService");
        }

        @Test
        @DisplayName("metadata is immutable")
        void immutable() {
            var m1 = MessageMetadata.empty();
            var m2 = m1.addEntry("A", "B", "C", "D");
            assertThat(m1.size()).isZero();
            assertThat(m2.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("EnvelopeWrapper")
    class EnvelopeWrapperTests {

        record Payload(String data) {}

        @Test
        @DisplayName("wraps and unwraps payload")
        void wrapUnwrap() {
            var payload = new Payload("test");
            var envelope = EnvelopeWrapper.wrap(payload, Map.of("content-type", "json"));

            assertThat(envelope.unwrap()).isEqualTo(payload);
            assertThat(envelope.header("content-type")).isEqualTo("json");
            assertThat(envelope.messageId()).isNotNull();
            assertThat(envelope.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("withHeader adds header without mutating")
        void withHeader() {
            var envelope = EnvelopeWrapper.wrap(new Payload("x"));
            var updated = envelope.withHeader("auth", "token123");

            assertThat(envelope.header("auth")).isNull();
            assertThat(updated.header("auth")).isEqualTo("token123");
        }
    }

    @Nested
    @DisplayName("ClaimCheck")
    class ClaimCheckTests {

        @Test
        @DisplayName("check-in and check-out round trips")
        void roundTrip() {
            var store = new ClaimCheck<String>();
            var token = store.checkIn("large-payload");

            assertThat(store.size()).isEqualTo(1);
            assertThat(store.claim(token)).contains("large-payload");
            assertThat(store.checkOut(token)).contains("large-payload");
            assertThat(store.size()).isZero();
        }

        @Test
        @DisplayName("claim without checkout preserves item")
        void claimPreserves() {
            var store = new ClaimCheck<String>();
            var token = store.checkIn("data");

            store.claim(token);
            assertThat(store.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("checkout unknown token returns empty")
        void unknownToken() {
            var store = new ClaimCheck<String>();
            assertThat(store.checkOut(ClaimCheck.CheckToken.create())).isEmpty();
        }
    }

    @Nested
    @DisplayName("RequestReply")
    class RequestReplyTests {

        sealed interface Msg permits Echo {}

        record Echo(String text) implements Msg {}

        @Test
        @DisplayName("request returns reply synchronously")
        void syncRequest() throws InterruptedException {
            var server =
                    RequestReply.server(
                            "",
                            (state, msg) ->
                                    switch (msg) {
                                        case Echo e -> "RE: " + e.text();
                                        default -> "";
                                    });

            String reply = server.request(new Echo("Hello"), Duration.ofSeconds(2));
            assertThat(reply).isEqualTo("RE: Hello");
            try {
                server.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    @DisplayName("ReturnAddress")
    class ReturnAddressTests {

        @Test
        @DisplayName("return address wraps message with reply-to")
        void wrapsMessage() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var received = new AtomicReference<String>();

            var client =
                    new io.github.seanchatmangpt.jotp.Proc<String, String>(
                            null,
                            (state, msg) -> {
                                received.set(msg);
                                latch.countDown();
                                return msg;
                            });

            var returnAddr = ReturnAddress.of("Hello", client);
            returnAddr.reply("RE: Hello");

            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(received.get()).isEqualTo("RE: Hello");
            client.stop();
        }
    }
}
