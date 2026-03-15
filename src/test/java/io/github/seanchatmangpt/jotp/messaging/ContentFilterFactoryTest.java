package io.github.seanchatmangpt.jotp.messaging;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.messagepatterns.transformation.ContentFilter;
import java.util.ArrayList;
import java.util.List;

/** Test for ContentFilter factory method in Messaging. */
class ContentFilterFactoryTest {

    record FullCustomer(String id, String name, String email, String phone, String address) {}

    record LeanCustomer(String id, String name) {}

    @org.junit.jupiter.api.Test
    void testContentFilterFactory() throws InterruptedException {
        // Given
        List<LeanCustomer> results = new ArrayList<>();

        // When - using the factory method
        ContentFilter<FullCustomer, LeanCustomer> filter =
                Messaging.contentFilter(
                        full -> new LeanCustomer(full.id(), full.name()), results::add);

        // Then
        filter.filter(
                new FullCustomer(
                        "cust-1", "Alice", "alice@example.com", "555-0100", "123 Main St"));
        filter.filter(
                new FullCustomer("cust-2", "Bob", "bob@example.com", "555-0200", "456 Oak Ave"));

        Thread.sleep(100); // Allow async processing

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(new LeanCustomer("cust-1", "Alice"));
        assertThat(results.get(1)).isEqualTo(new LeanCustomer("cust-2", "Bob"));

        filter.stop();
    }

    @org.junit.jupiter.api.Test
    void testContentFilterApplySynchronous() {
        // Given
        ContentFilter<FullCustomer, LeanCustomer> filter =
                Messaging.contentFilter(
                        full -> new LeanCustomer(full.id(), full.name()),
                        leanCustomer -> {} // no-op consumer
                        );

        // When - using apply() for synchronous filtering
        LeanCustomer result =
                filter.apply(
                        new FullCustomer(
                                "cust-1", "Alice", "alice@example.com", "555-0100", "123 Main St"));

        // Then
        assertThat(result).isEqualTo(new LeanCustomer("cust-1", "Alice"));

        filter.stop();
    }
}
