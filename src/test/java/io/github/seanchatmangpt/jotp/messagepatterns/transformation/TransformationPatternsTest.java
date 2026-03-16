package io.github.seanchatmangpt.jotp.messagepatterns.transformation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Transformation patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 *
 * <p>Enterprise Integration Patterns (EIP) transformation patterns modify message content, format,
 * or structure to enable communication between incompatible systems.
 */
@DisplayName("Transformation Patterns")
class TransformationPatternsTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Nested
    @DisplayName("MessageTranslator")
    class MessageTranslatorTests {

        @Test
        @DisplayName("translates message format")
        void translates(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Message Translator");
            ctx.say(
                    "Converts messages from one format to another, enabling communication between systems with different data representations.");
            ctx.sayCode(
                    """
                    var translator = new MessageTranslator<Integer, String>(
                        i -> "num:" + i,
                        s -> { result.set(s); latch.countDown(); });
                    translator.translate(42);
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[System A] -->|Integer| B[Message Translator]
                        B -->|Translation|
                        B -->|String| C[System B]
                    """);
            ctx.sayNote(
                    "Use when integrating legacy systems, external APIs, or services with incompatible message formats.");

            var latch = new CountDownLatch(1);
            var result = new AtomicReference<String>();

            var translator =
                    new MessageTranslator<Integer, String>(
                            i -> "num:" + i,
                            s -> {
                                result.set(s);
                                latch.countDown();
                            });

            translator.translate(42);
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get()).isEqualTo("num:42");
            translator.stop();
        }

        @Test
        @DisplayName("apply returns translation synchronously")
        void syncApply(DtrContext ctx) throws Exception {
            ctx.sayNextSection("Message Translator: Synchronous Translation");
            ctx.say(
                    "The apply method provides a synchronous way to translate messages without async channels.");
            ctx.sayCode(
                    """
                    var translator = new MessageTranslator<Integer, String>(i -> "num:" + i, s -> {});
                    String result = translator.apply(7);
                    """,
                    "java");
            ctx.sayNote(
                    "Synchronous translation is useful for request-reply patterns where immediate response is required.");

            var translator = new MessageTranslator<Integer, String>(i -> "num:" + i, s -> {});
            assertThat(translator.apply(7)).isEqualTo("num:7");
            try {
                translator.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    @DisplayName("ContentFilter")
    class ContentFilterTests {

        record Full(String name, String ssn, String address, int age) {}

        record Filtered(String name, int age) {}

        @Test
        @DisplayName("filters message to essential fields")
        void filters(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content Filter");
            ctx.say(
                    "Removes unnecessary data from a message, keeping only the information required by the consumer. Reduces payload size and hides sensitive information.");
            ctx.sayCode(
                    """
                    var filter = new ContentFilter<Full, Filtered>(
                        f -> new Filtered(f.name(), f.age()),
                        r -> { result.set(r); latch.countDown(); });
                    filter.filter(new Full("Alice", "123-45-6789", "123 Main St", 30));
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Full Message] --> B[Content Filter]
                        B -->|remove fields| C[Filtered Message]
                    """);
            ctx.sayNote(
                    "Use for data minimization, GDPR compliance, or when downstream systems only need specific fields.");

            var latch = new CountDownLatch(1);
            var result = new AtomicReference<Filtered>();

            var filter =
                    new ContentFilter<Full, Filtered>(
                            f -> new Filtered(f.name(), f.age()),
                            r -> {
                                result.set(r);
                                latch.countDown();
                            });

            filter.filter(new Full("Alice", "123-45-6789", "123 Main St", 30));
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().name()).isEqualTo("Alice");
            assertThat(result.get().age()).isEqualTo(30);
            filter.stop();
        }
    }

    @Nested
    @DisplayName("ContentEnricher")
    class ContentEnricherTests {

        record Sparse(String patientId, String date) {}

        record PatientDetails(String lastName, String carrier) {}

        record Enriched(String patientId, String date, String lastName, String carrier) {}

        @Test
        @DisplayName("enriches message with external data")
        void enriches(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Content Enricher");
            ctx.say(
                    "Augments a message with additional information from external sources, creating a richer message for downstream processing.");
            ctx.sayCode(
                    """
                    var enricher = new ContentEnricher<Sparse, PatientDetails, Enriched>(
                        sparse -> new PatientDetails("Smith", "BlueCross"),
                        (sparse, details) -> new Enriched(
                            sparse.patientId(), sparse.date(),
                            details.lastName(), details.carrier()),
                        r -> { result.set(r); latch.countDown(); });
                    enricher.enrich(new Sparse("P001", "2024-01-15"));
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Sparse Message] --> B[Content Enricher]
                    C[External Data] --> B
                    B -->|enrich| D[Enriched Message]
                    """);
            ctx.sayNote(
                    "Use when you need to add missing information from databases, external APIs, or reference data stores.");

            var latch = new CountDownLatch(1);
            var result = new AtomicReference<Enriched>();

            var enricher =
                    new ContentEnricher<Sparse, PatientDetails, Enriched>(
                            sparse -> new PatientDetails("Smith", "BlueCross"),
                            (sparse, details) ->
                                    new Enriched(
                                            sparse.patientId(),
                                            sparse.date(),
                                            details.lastName(),
                                            details.carrier()),
                            r -> {
                                result.set(r);
                                latch.countDown();
                            });

            enricher.enrich(new Sparse("P001", "2024-01-15"));
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get().lastName()).isEqualTo("Smith");
            assertThat(result.get().carrier()).isEqualTo("BlueCross");
            enricher.stop();
        }
    }
}
