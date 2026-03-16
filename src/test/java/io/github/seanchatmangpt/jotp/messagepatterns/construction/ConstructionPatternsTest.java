package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Message Construction patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 *
 * <p>Enterprise Integration Patterns (EIP) construction patterns define how messages are created,
 * structured, and documented for reliable communication.
 */
@DisplayName("Message Construction Patterns")
class ConstructionPatternsTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Nested
    @DisplayName("CorrelationIdentifier")
    class CorrelationIdentifierTests {

        @Test
        @DisplayName("unique IDs do not collide")
        void uniqueIds(DtrContext ctx) {
            ctx.sayNextSection("Correlation Identifier");
            ctx.say(
                    "Uniquely identifies related messages, enabling request-reply correlation and distributed transaction tracking.");
            ctx.sayCode(
                    """
                    var id1 = CorrelationIdentifier.create();
                    var id2 = CorrelationIdentifier.create();
                    assertThat(id1.matches(id2)).isFalse();
                    """,
                    "java");
            ctx.sayNote(
                    "Essential for async messaging where requests and responses are decoupled in time.");

            var id1 = CorrelationIdentifier.create();
            var id2 = CorrelationIdentifier.create();
            assertThat(id1.matches(id2)).isFalse();
        }

        @Test
        @DisplayName("same ID matches itself")
        void sameIdMatches(DtrContext ctx) {
            ctx.sayNextSection("Correlation Identifier: ID Matching");
            ctx.say("Correlation IDs can be reconstructed and compared for equality.");
            ctx.sayCode(
                    """
                    var id = CorrelationIdentifier.create();
                    var copy = CorrelationIdentifier.of(id.id());
                    assertThat(id.matches(copy)).isTrue();
                    """,
                    "java");

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
        void notExpired(DtrContext ctx) {
            ctx.sayNextSection("Message Expiration");
            ctx.say(
                    "Messages carry their own expiration time, allowing systems to discard stale messages automatically.");
            ctx.sayCode(
                    """
                    var order = new TestOrder("o1", Instant.now(), Duration.ofHours(1));
                    assertThat(order.isExpired()).isFalse();
                    """,
                    "java");
            ctx.sayNote(
                    "Use for time-sensitive data like quotes, offers, or cache invalidation notifications.");

            var order = new TestOrder("o1", Instant.now(), Duration.ofHours(1));
            assertThat(order.isExpired()).isFalse();
        }

        @Test
        @DisplayName("message with zero TTL is expired")
        void expired(DtrContext ctx) {
            ctx.sayNextSection("Message Expiration: Expired Messages");
            ctx.say("Messages with zero or negative TTL are immediately considered expired.");
            ctx.sayCode(
                    """
                    var order = new TestOrder("o1", Instant.now().minusSeconds(1), Duration.ZERO);
                    assertThat(order.isExpired()).isTrue();
                    """,
                    "java");

            var order = new TestOrder("o1", Instant.now().minusSeconds(1), Duration.ZERO);
            assertThat(order.isExpired()).isTrue();
        }

        @Test
        @DisplayName("expiresAt returns correct instant")
        void expiresAt(DtrContext ctx) {
            ctx.sayNextSection("Message Expiration: Expiration Calculation");
            ctx.say("The expiresAt method calculates the exact expiration timestamp.");
            ctx.sayCode(
                    """
                    var now = Instant.now();
                    var ttl = Duration.ofMinutes(5);
                    var order = new TestOrder("o1", now, ttl);
                    assertThat(order.expiresAt()).isEqualTo(now.plus(ttl));
                    """,
                    "java");

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
        void empty(DtrContext ctx) {
            ctx.sayNextSection("Message Metadata");
            ctx.say(
                    "Immutable audit trail that tracks message processing history through multiple systems.");
            ctx.sayCode(
                    """
                    var metadata = MessageMetadata.empty();
                    assertThat(metadata.size()).isZero();
                    var metadata2 = metadata.addEntry("OrderService", "validated", "Router", "business rules");
                    assertThat(metadata2.size()).isEqualTo(1);
                    """,
                    "java");
            ctx.sayNote(
                    "Use for debugging, compliance, and understanding message flow through distributed systems.");

            var metadata = MessageMetadata.empty();
            assertThat(metadata.size()).isZero();
            assertThat(metadata.lastEntry()).isNull();
        }

        @Test
        @DisplayName("addEntry accumulates audit trail")
        void accumulates(DtrContext ctx) {
            ctx.sayNextSection("Message Metadata: Audit Trail");
            ctx.say(
                    "Each entry records who processed the message, what action was taken, and why.");
            ctx.sayCode(
                    """
                    var metadata = MessageMetadata.empty()
                        .addEntry("OrderService", "validated", "Router", "business rules")
                        .addEntry("InventoryService", "reserved", "StockMgr", "allocated");
                    """,
                    "java");

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
        void immutable(DtrContext ctx) {
            ctx.sayNextSection("Message Metadata: Immutability");
            ctx.say("Metadata is immutable - each addEntry returns a new instance.");
            ctx.sayCode(
                    """
                    var m1 = MessageMetadata.empty();
                    var m2 = m1.addEntry("A", "B", "C", "D");
                    assertThat(m1.size()).isZero();
                    assertThat(m2.size()).isEqualTo(1);
                    """,
                    "java");

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
        void wrapUnwrap(DtrContext ctx) {
            ctx.sayNextSection("Envelope Wrapper");
            ctx.say(
                    "Encapsulates a message with metadata headers, providing a standardized container for message transport.");
            ctx.sayCode(
                    """
                    var envelope = EnvelopeWrapper.wrap(payload, Map.of("content-type", "json"));
                    Payload unwrapped = envelope.unwrap();
                    String contentType = envelope.header("content-type");
                    """,
                    "java");
            ctx.sayNote(
                    "Use for adding cross-cutting concerns like authentication, tracing, or routing information to messages.");

            var payload = new Payload("test");
            var envelope = EnvelopeWrapper.wrap(payload, Map.of("content-type", "json"));

            assertThat(envelope.unwrap()).isEqualTo(payload);
            assertThat(envelope.header("content-type")).isEqualTo("json");
            assertThat(envelope.messageId()).isNotNull();
            assertThat(envelope.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("withHeader adds header without mutating")
        void withHeader(DtrContext ctx) {
            ctx.sayNextSection("Envelope Wrapper: Immutable Headers");
            ctx.say("Headers can be added without mutating the original envelope.");
            ctx.sayCode(
                    """
                    var envelope = EnvelopeWrapper.wrap(new Payload("x"));
                    var updated = envelope.withHeader("auth", "token123");
                    """,
                    "java");

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
        void roundTrip(DtrContext ctx) {
            ctx.sayNextSection("Claim Check");
            ctx.say(
                    "Stores large message payloads externally and passes only a token through the messaging system, reducing channel load.");
            ctx.sayCode(
                    """
                    var store = new ClaimCheck<String>();
                    var token = store.checkIn("large-payload");
                    Optional<String> claimed = store.claim(token);
                    Optional<String> checkedOut = store.checkOut(token);
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Large Payload] --> B[Claim Check]
                        B -->|Token| C[Messaging Channel]
                        C -->|Token| D[Consumer]
                        D -->|Token| B
                        B -->|Payload| D
                    """);
            ctx.sayNote(
                    "Use for large messages (files, documents) that would otherwise overwhelm the messaging system.");

            var store = new ClaimCheck<String>();
            var token = store.checkIn("large-payload");

            assertThat(store.size()).isEqualTo(1);
            assertThat(store.claim(token)).contains("large-payload");
            assertThat(store.checkOut(token)).contains("large-payload");
            assertThat(store.size()).isZero();
        }

        @Test
        @DisplayName("claim without checkout preserves item")
        void claimPreserves(DtrContext ctx) {
            ctx.sayNextSection("Claim Check: Claim vs Checkout");
            ctx.say(
                    "Claim returns the item without removing it; checkout removes it from storage.");
            ctx.sayCode(
                    """
                    var store = new ClaimCheck<String>();
                    var token = store.checkIn("data");
                    store.claim(token); // still in storage
                    """,
                    "java");

            var store = new ClaimCheck<String>();
            var token = store.checkIn("data");

            store.claim(token);
            assertThat(store.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("checkout unknown token returns empty")
        void unknownToken(DtrContext ctx) {
            ctx.sayNextSection("Claim Check: Unknown Tokens");
            ctx.say("Checking out an unknown or already-claimed token returns empty.");
            ctx.sayCode(
                    """
                    var store = new ClaimCheck<String>();
                    Optional<String> result = store.checkOut(ClaimCheck.CheckToken.create());
                    assertThat(result).isEmpty();
                    """,
                    "java");

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
        void syncRequest(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Request-Reply");
            ctx.say(
                    "Synchronous communication pattern where a client sends a request and blocks until receiving a reply.");
            ctx.sayCode(
                    """
                    var server = RequestReply.server("", (state, msg) ->
                        switch (msg) {
                            case Echo e -> "RE: " + e.text();
                            default -> "";
                        });
                    String reply = server.request(new Echo("Hello"), Duration.ofSeconds(2));
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Client] -->|Request| B[Server]
                        B -->|Reply| A
                    """);
            ctx.sayNote(
                    "Use for query operations or when immediate response is required. Avoid for long-running operations.");

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
        void wrapsMessage(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Return Address");
            ctx.say(
                    "Encapsulates the destination for reply messages, enabling bidirectional communication over asynchronous channels.");
            ctx.sayCode(
                    """
                    var client = new Proc<String, String>(null, (state, msg) -> {
                        received.set(msg);
                        latch.countDown();
                        return msg;
                    });
                    var returnAddr = ReturnAddress.of("Hello", client);
                    returnAddr.reply("RE: Hello");
                    """,
                    "java");
            ctx.sayNote(
                    "Essential for async request-reply over message channels where the reply destination must be explicitly specified.");

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
