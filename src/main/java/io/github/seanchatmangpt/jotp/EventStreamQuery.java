package io.github.seanchatmangpt.jotp;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * EventStreamQuery — fluent query builder over an {@link EventStore} event log.
 *
 * <p>Supports filtering by stream glob pattern, time range, version range, event type, and
 * metadata. Also provides grouping, pagination, and convenience shortcuts.
 *
 * <pre>{@code
 * EventStore store = EventStore.create();
 * EventStreamQuery query = EventStreamQuery.on(store);
 *
 * List<EventStore.StoredEvent> results = query
 *     .streams("order-*")
 *     .after(Instant.now().minusSeconds(60))
 *     .ofType(OrderCreated.class)
 *     .limit(100)
 *     .execute();
 * }</pre>
 */
public final class EventStreamQuery {

    // ── Sort order ────────────────────────────────────────────────────────────

    /** Sort order for query results. */
    public enum SortOrder {
        TIMESTAMP_ASC,
        TIMESTAMP_DESC,
        VERSION_ASC,
        VERSION_DESC
    }

    // ── Internal query state ──────────────────────────────────────────────────

    private final EventStore store;
    private String streamPattern = "*";
    private Instant afterTime = null;
    private Instant beforeTime = null;
    private Long fromVersion = null;
    private Long toVersion = null;
    private final List<Class<?>> eventTypes = new ArrayList<>();
    private String metadataKey = null;
    private String metadataValue = null;
    private int limitCount = Integer.MAX_VALUE;
    private SortOrder sortOrder = SortOrder.TIMESTAMP_ASC;

    // ── Constructor / factory ─────────────────────────────────────────────────

    private EventStreamQuery(EventStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /** Create a new query builder over the given {@link EventStore}. */
    public static EventStreamQuery on(EventStore store) {
        return new EventStreamQuery(store);
    }

    // ── Copy constructor for immutable-ish chaining ───────────────────────────

    private EventStreamQuery copy() {
        EventStreamQuery q = new EventStreamQuery(this.store);
        q.streamPattern = this.streamPattern;
        q.afterTime = this.afterTime;
        q.beforeTime = this.beforeTime;
        q.fromVersion = this.fromVersion;
        q.toVersion = this.toVersion;
        q.eventTypes.addAll(this.eventTypes);
        q.metadataKey = this.metadataKey;
        q.metadataValue = this.metadataValue;
        q.limitCount = this.limitCount;
        q.sortOrder = this.sortOrder;
        return q;
    }

    // ── Filter builders ───────────────────────────────────────────────────────

    /**
     * Filter by stream id glob pattern.
     *
     * <ul>
     *   <li>{@code "*"} — all streams
     *   <li>{@code "order-*"} — streams whose id starts with {@code "order-"}
     *   <li>{@code "order-123"} — exact match
     * </ul>
     */
    public EventStreamQuery streams(String pattern) {
        EventStreamQuery q = copy();
        q.streamPattern = Objects.requireNonNull(pattern, "pattern must not be null");
        return q;
    }

    /** Include only events whose timestamp is strictly after {@code instant}. */
    public EventStreamQuery after(Instant instant) {
        EventStreamQuery q = copy();
        q.afterTime = Objects.requireNonNull(instant, "instant must not be null");
        return q;
    }

    /** Include only events whose timestamp is strictly before {@code instant}. */
    public EventStreamQuery before(Instant instant) {
        EventStreamQuery q = copy();
        q.beforeTime = Objects.requireNonNull(instant, "instant must not be null");
        return q;
    }

    /** Include only events with version strictly greater than {@code version}. */
    public EventStreamQuery fromVersion(long version) {
        EventStreamQuery q = copy();
        q.fromVersion = version;
        return q;
    }

    /** Include only events with version less than or equal to {@code version}. */
    public EventStreamQuery toVersion(long version) {
        EventStreamQuery q = copy();
        q.toVersion = version;
        return q;
    }

    /** Include only events whose runtime type is one of the supplied types. */
    @SafeVarargs
    public final EventStreamQuery ofType(Class<?>... types) {
        EventStreamQuery q = copy();
        q.eventTypes.addAll(Arrays.asList(types));
        return q;
    }

    /** Include only events that have the given metadata entry. */
    public EventStreamQuery withMetadata(String key, String value) {
        EventStreamQuery q = copy();
        q.metadataKey = Objects.requireNonNull(key, "key must not be null");
        q.metadataValue = Objects.requireNonNull(value, "value must not be null");
        return q;
    }

    /** Limit the number of results returned. */
    public EventStreamQuery limit(int n) {
        if (n < 0) throw new IllegalArgumentException("limit must be non-negative");
        EventStreamQuery q = copy();
        q.limitCount = n;
        return q;
    }

    /** Sort results by timestamp ascending (default). */
    public EventStreamQuery orderByTime() {
        EventStreamQuery q = copy();
        q.sortOrder = SortOrder.TIMESTAMP_ASC;
        return q;
    }

    /** Sort results by version ascending. */
    public EventStreamQuery orderByVersion() {
        EventStreamQuery q = copy();
        q.sortOrder = SortOrder.VERSION_ASC;
        return q;
    }

    /** Set an explicit sort order. */
    public EventStreamQuery orderBy(SortOrder order) {
        EventStreamQuery q = copy();
        q.sortOrder = Objects.requireNonNull(order, "order must not be null");
        return q;
    }

    // ── Terminal operations ───────────────────────────────────────────────────

    /** Execute the query and return all matching {@link EventStore.StoredEvent} records. */
    public List<EventStore.StoredEvent> execute() {
        return buildStream().collect(Collectors.toList());
    }

    /** Return the count of matching events without materialising the full list. */
    public long count() {
        return buildStream().count();
    }

    /**
     * Shortcut: return the latest {@code n} events across all matching streams, ordered by
     * timestamp descending.
     */
    public EventStreamQuery latest(int n) {
        return orderBy(SortOrder.TIMESTAMP_DESC).limit(n);
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    /**
     * Return a {@link GroupedQuery} that groups results by their {@code streamId}.
     *
     * <p>All filters defined on this query are forwarded to the grouped query.
     */
    public GroupedQuery groupByStream() {
        return new GroupedQuery(this);
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    /**
     * Return a {@link Page} for the first page of results with the given page size.
     *
     * <p>All filters defined on this query are forwarded to the page.
     */
    public Page pageSize(int size) {
        return new Page(this, size, 0);
    }

    // ── Internal stream builder ───────────────────────────────────────────────

    /** Build the filtered, sorted, limited event stream. */
    Stream<EventStore.StoredEvent> buildStream() {
        Stream<EventStore.StoredEvent> raw = allMatchingEvents();

        // Time filters
        if (afterTime != null) {
            raw = raw.filter(e -> e.timestamp().isAfter(afterTime));
        }
        if (beforeTime != null) {
            raw = raw.filter(e -> e.timestamp().isBefore(beforeTime));
        }

        // Event type filter
        if (!eventTypes.isEmpty()) {
            raw = raw.filter(e -> matchesType(e.event()));
        }

        // Metadata filter
        if (metadataKey != null) {
            raw = raw.filter(e -> metadataValue.equals(e.metadata().get(metadataKey)));
        }

        // Sort
        raw =
                switch (sortOrder) {
                    case TIMESTAMP_ASC ->
                            raw.sorted(Comparator.comparing(EventStore.StoredEvent::timestamp));
                    case TIMESTAMP_DESC ->
                            raw.sorted(
                                    Comparator.comparing(EventStore.StoredEvent::timestamp)
                                            .reversed());
                    case VERSION_ASC ->
                            raw.sorted(Comparator.comparingLong(EventStore.StoredEvent::version));
                    case VERSION_DESC ->
                            raw.sorted(
                                    Comparator.comparingLong(EventStore.StoredEvent::version)
                                            .reversed());
                };

        // Limit
        if (limitCount != Integer.MAX_VALUE) {
            raw = raw.limit(limitCount);
        }

        return raw;
    }

    private Stream<EventStore.StoredEvent> allMatchingEvents() {
        EventStore.Stats stats = store.stats();
        // Use the store's internal load mechanics by iterating known stream ids.
        // EventStore exposes load(streamId) per stream; we identify matching streams
        // through the stats and stream existence checks via a combined approach.
        // Because EventStore does not expose a list-streams API directly we collect
        // candidates by attempting to load known stream ids from subscriptions —
        // instead we rely on the fact that subscribeAll gives us a snapshot-less view.
        // The cleanest approach: build a union stream using the subscribeAll snapshot
        // pattern is not available; however EventStore.load() returns per-stream data.
        // We use the private-friendly workaround of querying via loadAllStreams helper.
        return loadAllMatchingStreams();
    }

    private Stream<EventStore.StoredEvent> loadAllMatchingStreams() {
        // EventStore does not expose a listStreams() API.  We use the subscribeAll
        // capture trick: register a temporary subscription that collects all current
        // events by replaying — but EventStore subscriptions are prospective only.
        //
        // The only public load API is load(streamId), loadFrom, loadUpTo.
        // We therefore need to discover stream ids.  Because the class is final with
        // package-private state, we use a known-stream-tracking approach:
        // We register a subscribeAll handler to collect stream ids as events arrive,
        // but that only works for future events.
        //
        // For existing events we use a projection-based discovery or introspection.
        // Since neither is available without altering EventStore, we leverage the fact
        // that EventStore.stats() gives us a stream count, and we can retrieve known
        // stream ids by maintaining a tracker projection we register opportunistically.
        //
        // The most pragmatic approach given the public API constraints: use
        // EventStreamQuery's own stream-tracking projection that is registered on
        // EventStore.create() — but we cannot inject that retroactively.
        //
        // Resolution: the EventStore API *does* expose load(streamId) and exists(streamId).
        // We maintain a Set<String> of known stream ids inside EventStreamQuery by
        // subscribing to the store at construction time and also scanning via a
        // discoverable projection.
        //
        // Given the constraints, the cleanest correct solution is to add a StreamTracker
        // projection to the EventStore at query-construction time and use it for
        // subsequent queries, but also snapshot existing streams via a one-time scan.
        //
        // We accomplish the initial scan using the StreamTrackerProjection below, which
        // is added to the store once.

        StreamTrackerProjection tracker = getOrCreateTracker();
        Set<String> streamIds = tracker.streamIds();

        return streamIds.stream()
                .filter(id -> matchesPattern(id, streamPattern))
                .flatMap(
                        id -> {
                            Stream<EventStore.StoredEvent> s = store.load(id);
                            if (fromVersion != null) {
                                s = store.loadFrom(id, fromVersion);
                            }
                            if (toVersion != null) {
                                final Stream<EventStore.StoredEvent> sv = s;
                                final long tv = toVersion;
                                s = sv.filter(e -> e.version() <= tv);
                            }
                            return s;
                        });
    }

    // ── StreamTrackerProjection ───────────────────────────────────────────────

    private static final String TRACKER_NAME = "__EventStreamQuery.StreamTracker__";

    private StreamTrackerProjection getOrCreateTracker() {
        Optional<StreamTrackerProjection> existing = store.getProjection(TRACKER_NAME);
        if (existing.isPresent()) {
            return existing.get();
        }
        StreamTrackerProjection tracker = new StreamTrackerProjection();
        store.addProjection(tracker);
        // Rebuild to pick up all events already in the store
        store.rebuildProjection(TRACKER_NAME);
        return tracker;
    }

    /** Internal projection that collects known stream ids. */
    private static final class StreamTrackerProjection implements EventStore.Projection {
        private final Set<String> ids = Collections.synchronizedSet(new LinkedHashSet<>());

        @Override
        public String name() {
            return TRACKER_NAME;
        }

        @Override
        public void apply(EventStore.StoredEvent event) {
            ids.add(event.streamId());
        }

        @Override
        public void reset() {
            ids.clear();
        }

        Set<String> streamIds() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        }
    }

    // ── Glob matching ─────────────────────────────────────────────────────────

    /**
     * Match a stream id against a glob pattern.
     *
     * <ul>
     *   <li>{@code "*"} — matches everything
     *   <li>{@code "order-*"} — prefix match
     *   <li>{@code "*-created"} — suffix match
     *   <li>{@code "order-*-v2"} — single-wildcard interior match
     *   <li>exact string otherwise
     * </ul>
     */
    static boolean matchesPattern(String streamId, String pattern) {
        if ("*".equals(pattern)) return true;
        if (!pattern.contains("*")) return pattern.equals(streamId);

        // Convert glob to a simple regex equivalent by splitting on '*'
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) {
                // Must match at the start
                if (!streamId.startsWith(part)) return false;
                pos = part.length();
            } else if (i == parts.length - 1) {
                // Must match at the end
                if (!streamId.endsWith(part)) return false;
                // Ensure the suffix does not overlap with earlier consumed chars
                if (streamId.length() - part.length() < pos) return false;
            } else {
                int idx = streamId.indexOf(part, pos);
                if (idx < 0) return false;
                pos = idx + part.length();
            }
        }
        return true;
    }

    private boolean matchesType(Object event) {
        for (Class<?> type : eventTypes) {
            if (type.isInstance(event)) return true;
        }
        return false;
    }

    // ── GroupedQuery ──────────────────────────────────────────────────────────

    /**
     * A query view that groups results by {@code streamId}.
     *
     * <p>Obtain via {@link EventStreamQuery#groupByStream()}.
     */
    public static final class GroupedQuery {

        private final EventStreamQuery query;

        private GroupedQuery(EventStreamQuery query) {
            this.query = query;
        }

        /** Count of events per stream id. */
        public Map<String, Long> countPerGroup() {
            return query.buildStream()
                    .collect(
                            Collectors.groupingBy(
                                    EventStore.StoredEvent::streamId, Collectors.counting()));
        }

        /** All events per stream id, preserving the query sort order within each group. */
        public Map<String, List<EventStore.StoredEvent>> eventsPerGroup() {
            return query.buildStream()
                    .collect(
                            Collectors.groupingBy(
                                    EventStore.StoredEvent::streamId, Collectors.toList()));
        }
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    /**
     * A paginated view of query results.
     *
     * <p>Obtain via {@link EventStreamQuery#pageSize(int)}.
     */
    public static final class Page {

        private final EventStreamQuery baseQuery;
        private final int size;
        private final int pageIndex;

        // Lazy cache of totalCount and results
        private List<EventStore.StoredEvent> cachedAll = null;

        private Page(EventStreamQuery baseQuery, int size, int pageIndex) {
            if (size <= 0) throw new IllegalArgumentException("pageSize must be positive");
            if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be non-negative");
            this.baseQuery = baseQuery;
            this.size = size;
            this.pageIndex = pageIndex;
        }

        /** Navigate to the requested page (zero-based index). */
        public Page page(int index) {
            return new Page(baseQuery, size, index);
        }

        /** Navigate to the next page. */
        public Page nextPage() {
            return new Page(baseQuery, size, pageIndex + 1);
        }

        /** Navigate to the previous page (never below page 0). */
        public Page previousPage() {
            return new Page(baseQuery, size, Math.max(0, pageIndex - 1));
        }

        /** Results for the current page. */
        public List<EventStore.StoredEvent> results() {
            List<EventStore.StoredEvent> all = all();
            int from = pageIndex * size;
            if (from >= all.size()) return List.of();
            int to = Math.min(from + size, all.size());
            return Collections.unmodifiableList(all.subList(from, to));
        }

        /** Whether there are results beyond the current page. */
        public boolean hasMore() {
            return (long) (pageIndex + 1) * size < totalCount();
        }

        /** Total number of matching events across all pages. */
        public long totalCount() {
            return all().size();
        }

        /** Current page index (zero-based). */
        public int pageIndex() {
            return pageIndex;
        }

        /** Page size. */
        public int pageSize() {
            return size;
        }

        // Materialise once per Page instance (pages are immutable)
        private List<EventStore.StoredEvent> all() {
            if (cachedAll == null) {
                cachedAll = baseQuery.execute();
            }
            return cachedAll;
        }
    }
}
