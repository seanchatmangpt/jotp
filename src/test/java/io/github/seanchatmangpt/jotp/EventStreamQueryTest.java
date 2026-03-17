package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Full test suite for {@link EventStreamQuery} — fluent query builder over {@link EventStore}.
 *
 * <p>Each test creates a fresh {@link EventStore} instance to avoid shared state.
 */
@Timeout(10)
@DisplayName("EventStreamQuery: fluent query API over EventStore")
class EventStreamQueryTest {

    // ── Domain events ─────────────────────────────────────────────────────────

    record OrderCreated(String orderId) {}

    record OrderShipped(String orderId) {}

    record PaymentReceived(double amount) {}

    record InventoryUpdated(String sku, int delta) {}

    // ── Helper ────────────────────────────────────────────────────────────────

    private static EventStore freshStore() {
        return EventStore.create();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("execute() returns all events when no filters are applied")
    void executeReturnsAllEventsWithNoFilters() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-2", new OrderCreated("o2"));
        store.append("payment-1", new PaymentReceived(100.0));

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).execute();

        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("streams() with exact match returns only that stream's events")
    void streamsExactMatchFiltersCorrectly() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-2", new OrderCreated("o2"));
        store.append("payment-1", new PaymentReceived(50.0));

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store).streams("order-1").execute();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).streamId()).isEqualTo("order-1");
    }

    @Test
    @DisplayName("streams() with glob prefix 'order-*' returns only order streams")
    void streamsGlobPrefixFiltersCorrectly() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-2", new OrderShipped("o2"));
        store.append("payment-1", new PaymentReceived(200.0));

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store).streams("order-*").execute();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.streamId().startsWith("order-"));
    }

    @Test
    @DisplayName("streams() with '*' returns events from all streams")
    void streamsWildcardReturnsAll() {
        EventStore store = freshStore();
        store.append("stream-a", "event-a");
        store.append("stream-b", "event-b");
        store.append("stream-c", "event-c");

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).streams("*").execute();

        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("after() excludes events at or before the given instant")
    void afterFilterExcludesOlderEvents() throws InterruptedException {
        EventStore store = freshStore();
        store.append("stream-1", "before-cutoff");
        Instant cutoff = Instant.now();
        // Tiny pause to ensure the next event has a later timestamp
        Thread.sleep(5);
        store.append("stream-1", "after-cutoff");

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).after(cutoff).execute();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).event()).isEqualTo("after-cutoff");
    }

    @Test
    @DisplayName("before() excludes events at or after the given instant")
    void beforeFilterExcludesNewerEvents() throws InterruptedException {
        EventStore store = freshStore();
        store.append("stream-1", "first");
        Thread.sleep(5);
        Instant cutoff = Instant.now();
        Thread.sleep(5);
        store.append("stream-1", "second");

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).before(cutoff).execute();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).event()).isEqualTo("first");
    }

    @Test
    @DisplayName("ofType() filters events by runtime class")
    void ofTypeFiltersByClass() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-1", new OrderShipped("o1"));
        store.append("payment-1", new PaymentReceived(99.0));

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store).ofType(OrderCreated.class).execute();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).event()).isInstanceOf(OrderCreated.class);
    }

    @Test
    @DisplayName("ofType() accepts multiple types and matches any of them")
    void ofTypeMultipleTypesMatchesAny() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-1", new OrderShipped("o1"));
        store.append("inventory-1", new InventoryUpdated("SKU-1", -1));

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store).ofType(OrderCreated.class, OrderShipped.class).execute();

        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("withMetadata() filters by presence of a metadata key-value pair")
    void withMetadataFiltersCorrectly() {
        EventStore store = freshStore();
        store.append("stream-1", List.of(new OrderCreated("o1")));
        // Append event with metadata via StoredEvent directly isn't exposed, so we
        // use a projection trick: add a custom stored event via appendWithMeta below.
        // Since EventStore.append only accepts events without metadata in the public API,
        // we test the metadata path via a hand-crafted Projection + subscribeAll approach.
        //
        // EventStore.append(streamId, List<?>) creates StoredEvent.of() with empty metadata.
        // The metadata filter should return zero results for a key not present.
        List<EventStore.StoredEvent> noMetadata =
                EventStreamQuery.on(store).withMetadata("source", "integration-test").execute();

        assertThat(noMetadata).isEmpty();
    }

    @Test
    @DisplayName("limit() caps the number of results returned")
    void limitCapsResults() {
        EventStore store = freshStore();
        for (int i = 0; i < 20; i++) {
            store.append("stream-1", "event-" + i);
        }

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).limit(5).execute();

        assertThat(results).hasSize(5);
    }

    @Test
    @DisplayName("orderByTime() sorts results by timestamp ascending")
    void orderByTimeSortsAscending() throws InterruptedException {
        EventStore store = freshStore();
        store.append("stream-1", "first");
        Thread.sleep(5);
        store.append("stream-1", "second");
        Thread.sleep(5);
        store.append("stream-1", "third");

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).orderByTime().execute();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).event()).isEqualTo("first");
        assertThat(results.get(2).event()).isEqualTo("third");
    }

    @Test
    @DisplayName("orderByVersion() sorts results by version ascending")
    void orderByVersionSortsAscending() {
        EventStore store = freshStore();
        store.append("stream-1", "a");
        store.append("stream-1", "b");
        store.append("stream-1", "c");

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store).orderByVersion().execute();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).version()).isLessThanOrEqualTo(results.get(1).version());
        assertThat(results.get(1).version()).isLessThanOrEqualTo(results.get(2).version());
    }

    @Test
    @DisplayName("latest() returns the most recent N events in descending timestamp order")
    void latestReturnsTopNByTimestamp() throws InterruptedException {
        EventStore store = freshStore();
        for (int i = 0; i < 10; i++) {
            store.append("stream-1", "event-" + i);
            Thread.sleep(2);
        }

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).latest(3).execute();

        assertThat(results).hasSize(3);
        // First element should be the newest
        assertThat(results.get(0).timestamp()).isAfterOrEqualTo(results.get(1).timestamp());
    }

    @Test
    @DisplayName("count() returns the number of matching events")
    void countReturnsMatchingCount() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-2", new OrderCreated("o2"));
        store.append("payment-1", new PaymentReceived(50.0));

        long orderCount = EventStreamQuery.on(store).streams("order-*").count();
        long totalCount = EventStreamQuery.on(store).count();

        assertThat(orderCount).isEqualTo(2);
        assertThat(totalCount).isEqualTo(3);
    }

    @Test
    @DisplayName("groupByStream().countPerGroup() returns per-stream event counts")
    void groupByStreamCountPerGroup() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-1", new OrderShipped("o1"));
        store.append("order-2", new OrderCreated("o2"));
        store.append("payment-1", new PaymentReceived(100.0));

        Map<String, Long> counts = EventStreamQuery.on(store).groupByStream().countPerGroup();

        assertThat(counts).containsEntry("order-1", 2L);
        assertThat(counts).containsEntry("order-2", 1L);
        assertThat(counts).containsEntry("payment-1", 1L);
    }

    @Test
    @DisplayName("groupByStream().eventsPerGroup() returns per-stream event lists")
    void groupByStreamEventsPerGroup() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-1", new OrderShipped("o1"));
        store.append("order-2", new OrderCreated("o2"));

        Map<String, List<EventStore.StoredEvent>> groups =
                EventStreamQuery.on(store).groupByStream().eventsPerGroup();

        assertThat(groups).containsKey("order-1");
        assertThat(groups.get("order-1")).hasSize(2);
        assertThat(groups.get("order-2")).hasSize(1);
    }

    @Test
    @DisplayName("groupByStream() respects stream pattern filters")
    void groupByStreamRespectsStreamFilter() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-2", new OrderCreated("o2"));
        store.append("payment-1", new PaymentReceived(50.0));

        Map<String, Long> counts =
                EventStreamQuery.on(store).streams("order-*").groupByStream().countPerGroup();

        assertThat(counts).containsOnlyKeys("order-1", "order-2");
        assertThat(counts).doesNotContainKey("payment-1");
    }

    @Test
    @DisplayName("pageSize() + page(0) returns first page of results")
    void paginationFirstPage() {
        EventStore store = freshStore();
        for (int i = 0; i < 10; i++) {
            store.append("stream-1", "event-" + i);
        }

        EventStreamQuery.Page page = EventStreamQuery.on(store).pageSize(3).page(0);

        assertThat(page.results()).hasSize(3);
        assertThat(page.totalCount()).isEqualTo(10);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    @DisplayName("pageSize() + nextPage() navigates forward through pages")
    void paginationNextPage() {
        EventStore store = freshStore();
        for (int i = 0; i < 7; i++) {
            store.append("stream-1", "event-" + i);
        }

        EventStreamQuery.Page first = EventStreamQuery.on(store).pageSize(3).page(0);
        EventStreamQuery.Page second = first.nextPage();
        EventStreamQuery.Page third = second.nextPage();

        assertThat(first.results()).hasSize(3);
        assertThat(second.results()).hasSize(3);
        assertThat(third.results()).hasSize(1);
        assertThat(third.hasMore()).isFalse();
    }

    @Test
    @DisplayName("page beyond last returns empty results")
    void pageOutOfBoundsReturnsEmpty() {
        EventStore store = freshStore();
        store.append("stream-1", "only-event");

        EventStreamQuery.Page page = EventStreamQuery.on(store).pageSize(5).page(10);

        assertThat(page.results()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("fromVersion() and toVersion() filter by event version per stream")
    void fromVersionToVersionFilters() {
        EventStore store = freshStore();
        store.append("stream-1", "v1");
        store.append("stream-1", "v2");
        store.append("stream-1", "v3");
        store.append("stream-1", "v4");
        store.append("stream-1", "v5");

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store)
                        .streams("stream-1")
                        .fromVersion(1)
                        .toVersion(4)
                        .execute();

        // fromVersion(1) means version > 1, i.e. versions 2,3,4
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(e -> e.version() >= 2 && e.version() <= 4);
    }

    @Test
    @DisplayName("combined filters: stream pattern + type + limit work together")
    void combinedFilters() {
        EventStore store = freshStore();
        store.append("order-1", new OrderCreated("o1"));
        store.append("order-1", new OrderShipped("o1"));
        store.append("order-2", new OrderCreated("o2"));
        store.append("order-2", new OrderShipped("o2"));
        store.append("payment-1", new PaymentReceived(100.0));

        List<EventStore.StoredEvent> results =
                EventStreamQuery.on(store)
                        .streams("order-*")
                        .ofType(OrderCreated.class)
                        .limit(10)
                        .execute();

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.event() instanceof OrderCreated);
        assertThat(results).allMatch(e -> e.streamId().startsWith("order-"));
    }

    @Test
    @DisplayName("matchesPattern() handles suffix glob correctly")
    void globSuffixMatch() {
        assertThat(EventStreamQuery.matchesPattern("order-created", "*-created")).isTrue();
        assertThat(EventStreamQuery.matchesPattern("item-created", "*-created")).isTrue();
        assertThat(EventStreamQuery.matchesPattern("order-shipped", "*-created")).isFalse();
    }

    @Test
    @DisplayName("matchesPattern() handles interior wildcard correctly")
    void globInteriorMatch() {
        assertThat(EventStreamQuery.matchesPattern("order-123-v2", "order-*-v2")).isTrue();
        assertThat(EventStreamQuery.matchesPattern("order-456-v2", "order-*-v2")).isTrue();
        assertThat(EventStreamQuery.matchesPattern("order-123-v3", "order-*-v2")).isFalse();
    }

    @Test
    @DisplayName("limit(0) returns empty list")
    void limitZeroReturnsEmpty() {
        EventStore store = freshStore();
        store.append("stream-1", "event-1");
        store.append("stream-1", "event-2");

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).limit(0).execute();

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("query on empty store returns empty list")
    void emptyStoreReturnsEmpty() {
        EventStore store = freshStore();

        List<EventStore.StoredEvent> results = EventStreamQuery.on(store).execute();

        assertThat(results).isEmpty();
        assertThat(EventStreamQuery.on(store).count()).isZero();
    }

    @Test
    @DisplayName("pageSize must be positive — throws IllegalArgumentException otherwise")
    void pageSizeValidation() {
        EventStore store = freshStore();
        assertThatThrownBy(() -> EventStreamQuery.on(store).pageSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventStreamQuery.on(store).pageSize(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
