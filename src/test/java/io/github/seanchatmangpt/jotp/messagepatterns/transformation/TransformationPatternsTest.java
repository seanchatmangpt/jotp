package io.github.seanchatmangpt.jotp.messagepatterns.transformation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for Transformation patterns ported from Vaughn Vernon's Reactive Messaging Patterns. */
@DisplayName("Transformation Patterns")
class TransformationPatternsTest implements WithAssertions {

    @Nested
    @DisplayName("MessageTranslator")
    class MessageTranslatorTests {

        @Test
        @DisplayName("translates message format")
        void translates() throws InterruptedException {
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
        void syncApply() throws Exception {
            var translator = new MessageTranslator<Integer, String>(i -> "num:" + i, s -> {});
            assertThat(translator.apply(7)).isEqualTo("num:7");
            translator.stop();
        }
    }

    @Nested
    @DisplayName("ContentFilter")
    class ContentFilterTests {

        record Full(String name, String ssn, String address, int age) {}

        record Filtered(String name, int age) {}

        @Test
        @DisplayName("filters message to essential fields")
        void filters() throws InterruptedException {
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
        void enriches() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var result = new AtomicReference<Enriched>();

            var enricher =
                    new ContentEnricher<Sparse, PatientDetails, Enriched>(
                            sparse -> new PatientDetails("Smith", "BlueCross"),
                            (sparse, details) ->
                                    new Enriched(
                                            sparse.patientId(), sparse.date(),
                                            details.lastName(), details.carrier()),
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
