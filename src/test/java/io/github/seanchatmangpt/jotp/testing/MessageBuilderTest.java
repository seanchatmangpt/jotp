package io.github.seanchatmangpt.jotp.testing;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.testing.util.MessageBuilder;
import io.github.seanchatmangpt.jotp.testing.util.MessageBuilder.TestMessage;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
@DisplayName("MessageBuilder")
class MessageBuilderTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Sealed type fixture for validate() tests ──────────────────────────────

    sealed interface SealedMsg permits SealedMsg.Cmd, SealedMsg.Doc {
        record Cmd(String payload) implements SealedMsg {}

        record Doc(String content) implements SealedMsg {}
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("command() creates CommandMessage type")
        void commandFactory() {
            var builder = MessageBuilder.command();
            assertThat(builder.toString()).contains("CommandMessage");
        }

        @Test
        @DisplayName("document() creates DocumentMessage type")
        void documentFactory() {
            var builder = MessageBuilder.document();
            assertThat(builder.toString()).contains("DocumentMessage");
        }

        @Test
        @DisplayName("claimCheck() creates ClaimCheck type")
        void claimCheckFactory() {
            var builder = MessageBuilder.claimCheck();
            assertThat(builder.toString()).contains("ClaimCheck");
        }

        @Test
        @DisplayName("envelope() creates EnvelopeWrapper type")
        void envelopeFactory() {
            var builder = MessageBuilder.envelope();
            assertThat(builder.toString()).contains("EnvelopeWrapper");
        }

        @Test
        @DisplayName("custom() creates builder with given type name")
        void customFactory() {
            var builder = MessageBuilder.custom("MyCustomMessage");
            assertThat(builder.toString()).contains("MyCustomMessage");
        }
    }

    // ── Fluent setters ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fluent setters")
    class FluentSetters {

        @Test
        @DisplayName("withCorrelationId(String) returns same builder instance")
        void withCorrelationIdStringReturnsSelf() {
            var builder = MessageBuilder.command();
            assertThat(builder.withCorrelationId("abc-123")).isSameAs(builder);
        }

        @Test
        @DisplayName("withCorrelationId(UUID) returns same builder instance")
        void withCorrelationIdUuidReturnsSelf() {
            var builder = MessageBuilder.command();
            assertThat(builder.withCorrelationId(UUID.randomUUID())).isSameAs(builder);
        }

        @Test
        @DisplayName("withReplyTo returns same builder instance")
        void withReplyToReturnsSelf() {
            var builder = MessageBuilder.command();
            assertThat(builder.withReplyTo("pid-1")).isSameAs(builder);
        }

        @Test
        @DisplayName("withPriority returns same builder instance")
        void withPriorityReturnsSelf() {
            var builder = MessageBuilder.command();
            assertThat(builder.withPriority("HIGH")).isSameAs(builder);
        }

        @Test
        @DisplayName("withPayload returns same builder instance")
        void withPayloadReturnsSelf() {
            var builder = MessageBuilder.document();
            assertThat(builder.withPayload("some data")).isSameAs(builder);
        }

        @Test
        @DisplayName("withHeaders returns same builder instance")
        void withHeadersReturnsSelf() {
            var builder = MessageBuilder.envelope();
            assertThat(builder.withHeaders(Map.of("key", "value"))).isSameAs(builder);
        }

        @Test
        @DisplayName("withHeader returns same builder instance")
        void withHeaderReturnsSelf() {
            var builder = MessageBuilder.envelope();
            assertThat(builder.withHeader("trace-id", "abc")).isSameAs(builder);
        }

        @Test
        @DisplayName("withClaimId returns same builder instance")
        void withClaimIdReturnsSelf() {
            var builder = MessageBuilder.claimCheck();
            assertThat(builder.withClaimId("claim-001")).isSameAs(builder);
        }

        @Test
        @DisplayName("withField returns same builder instance")
        void withFieldReturnsSelf() {
            var builder = MessageBuilder.custom("X");
            assertThat(builder.withField("key", "value")).isSameAs(builder);
        }

        @Test
        @DisplayName("getField returns stored value")
        void getFieldReturnsStoredValue() {
            var builder = MessageBuilder.command().withField("foo", 42);
            assertThat(builder.getField("foo")).isEqualTo(42);
        }

        @Test
        @DisplayName("getField returns null for missing key")
        void getFieldReturnsNullForMissing() {
            assertThat(MessageBuilder.command().getField("missing")).isNull();
        }
    }

    // ── build() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("build()")
    class Build {

        @Test
        @DisplayName("returns TestMessage instance")
        void returnsTestMessage() {
            var msg = MessageBuilder.command().build();
            assertThat(msg).isInstanceOf(TestMessage.class);
        }

        @Test
        @DisplayName("TestMessage has correct type")
        void testMessageHasCorrectType() {
            var msg = (TestMessage) MessageBuilder.document().build();
            assertThat(msg.type).isEqualTo("DocumentMessage");
        }

        @Test
        @DisplayName("TestMessage contains all set fields")
        void testMessageContainsAllFields() {
            var msg =
                    (TestMessage)
                            MessageBuilder.command()
                                    .withCorrelationId("corr-1")
                                    .withPriority("HIGH")
                                    .withReplyTo("pid-2")
                                    .build();

            assertThat(msg.getField("correlationId")).isEqualTo("corr-1");
            assertThat(msg.getField("priority")).isEqualTo("HIGH");
            assertThat(msg.getField("replyTo")).isEqualTo("pid-2");
        }

        @Test
        @DisplayName("TestMessage fields map is unmodifiable")
        void testMessageFieldsAreUnmodifiable() {
            var msg = (TestMessage) MessageBuilder.command().withField("x", 1).build();
            assertThatThrownBy(() -> msg.fields.put("y", 2))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("consecutive build() calls produce independent copies")
        void consecutiveBuildsAreIndependent() {
            var builder = MessageBuilder.command().withCorrelationId("id-1");
            var msg1 = (TestMessage) builder.build();
            builder.withField("extra", "value");
            var msg2 = (TestMessage) builder.build();

            assertThat(msg1.fields).doesNotContainKey("extra");
            assertThat(msg2.fields).containsKey("extra");
        }
    }

    // ── UUID overload ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UUID overload")
    class UuidOverload {

        @Test
        @DisplayName("withCorrelationId(UUID) stores UUID.toString()")
        void storesUuidAsString() {
            var uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            var msg = (TestMessage) MessageBuilder.command().withCorrelationId(uuid).build();
            assertThat(msg.getField("correlationId")).isEqualTo(uuid.toString());
        }
    }

    // ── Header methods ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Header methods")
    class HeaderMethods {

        @Test
        @DisplayName("withHeaders stores all provided headers")
        void withHeadersStoresAll() {
            var headers = Map.of("trace-id", "t1", "span-id", "s1");
            var msg = (TestMessage) MessageBuilder.envelope().withHeaders(headers).build();

            @SuppressWarnings("unchecked")
            var stored = (Map<String, String>) msg.getField("headers");
            assertThat(stored).containsAllEntriesOf(headers);
        }

        @Test
        @DisplayName("withHeader creates and populates header map")
        void withHeaderCreatesMap() {
            var msg =
                    (TestMessage)
                            MessageBuilder.envelope().withHeader("x-request-id", "req-99").build();

            @SuppressWarnings("unchecked")
            var stored = (Map<String, String>) msg.getField("headers");
            assertThat(stored).containsEntry("x-request-id", "req-99");
        }

        @Test
        @DisplayName("multiple withHeader calls accumulate headers")
        void multipleWithHeaderAccumulate() {
            var msg =
                    (TestMessage)
                            MessageBuilder.envelope()
                                    .withHeader("h1", "v1")
                                    .withHeader("h2", "v2")
                                    .build();

            @SuppressWarnings("unchecked")
            var stored = (Map<String, String>) msg.getField("headers");
            assertThat(stored).containsEntry("h1", "v1").containsEntry("h2", "v2");
        }
    }

    // ── validate() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("non-sealed class passes silently")
        void nonSealedClassPasses() {
            var builder = MessageBuilder.custom("String");
            assertThatCode(() -> builder.validate(String.class)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("sealed class with matching permitted type passes")
        void sealedMatchingTypePasses() {
            // SealedMsg permits Cmd and Doc — "Cmd" matches
            var builder = MessageBuilder.custom("Cmd");
            assertThatCode(() -> builder.validate(SealedMsg.class)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("sealed class with non-matching type throws IllegalStateException")
        void sealedNonMatchingTypeThrows() {
            var builder = MessageBuilder.custom("NotPermitted");
            assertThatThrownBy(() -> builder.validate(SealedMsg.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a permitted subclass");
        }
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toString()")
    class ToStringMethod {

        @Test
        @DisplayName("includes type and field count")
        void includesTypeAndFieldCount() {
            var builder = MessageBuilder.command().withCorrelationId("c1").withPriority("LOW");
            assertThat(builder.toString()).contains("CommandMessage").contains("fields=2");
        }

        @Test
        @DisplayName("TestMessage toString includes type and fields")
        void testMessageToString() {
            var msg = (TestMessage) MessageBuilder.command().withField("k", "v").build();
            assertThat(msg.toString()).contains("CommandMessage");
        }
    }
}
