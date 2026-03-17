package io.github.seanchatmangpt.jotp.eventsourcing;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventSourcingTest {

  private EventStoreInterface eventStore;
  private EventProjection.OrderProjection orderProjection;

  @BeforeEach
  public void setUp() {
    eventStore = new InMemoryEventStore();
    orderProjection = new EventProjection.OrderProjection();
  }

  @Test
  public void appendAndReplayEvents() {
    // Create test events
    String aggregateId = "order-123";
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, "Created with 2 items");

    Event.EventMetadata meta2 =
        Event.EventMetadata.create(aggregateId, 1, "OrderSubmitted", "corr-1");
    Event event2 = new Event.DomainEvent(meta2, "Payment processed");

    // Append events
    long version = eventStore.append(aggregateId, List.of(event1, event2));

    assertThat(version).isEqualTo(2);
    assertThat(eventStore.getCurrentVersion(aggregateId)).isEqualTo(2);

    // Replay events
    List<Event> events = eventStore.getEvents(aggregateId);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).eventType()).isEqualTo("OrderCreated");
    assertThat(events.get(1).eventType()).isEqualTo("OrderSubmitted");
  }

  @Test
  public void optimisticConcurrencyControl() {
    String aggregateId = "order-456";
    Event.EventMetadata meta =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    Event event = new Event.DomainEvent(meta, "Created");

    // First append succeeds
    eventStore.append(aggregateId, List.of(event));

    // Second append with wrong expected version fails
    assertThatThrownBy(
            () -> eventStore.append(aggregateId, List.of(event), 5))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void snapshotOptimizesReplay() {
    String aggregateId = "order-789";
    Event.EventMetadata meta =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    Event event = new Event.DomainEvent(meta, "Created");

    eventStore.append(aggregateId, List.of(event));

    // Save snapshot
    Snapshot snapshot = Snapshot.of(aggregateId, 1, "OrderState{status=completed, total=100.00}");
    eventStore.saveSnapshot(aggregateId, snapshot);

    // Retrieve snapshot
    var retrieved = eventStore.getSnapshot(aggregateId);
    assertThat(retrieved).isPresent();
    assertThat(retrieved.get().version()).isEqualTo(1);
    assertThat(retrieved.get().state()).isEqualTo("OrderState{status=completed, total=100.00}");
  }

  @Test
  public void projectionTracksOrderState() {
    String aggregateId = "order-proj-1";
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, null);

    Event.EventMetadata meta2 =
        Event.EventMetadata.create(aggregateId, 1, "ItemAdded", "corr-1");
    Event event2 = new Event.DomainEvent(meta2, null);

    Event.EventMetadata meta3 =
        Event.EventMetadata.create(aggregateId, 2, "OrderSubmitted", "corr-1");
    Event event3 = new Event.DomainEvent(meta3, null);

    // Apply events to projection
    orderProjection.apply(event1);
    orderProjection.apply(event2);
    orderProjection.apply(event3);

    // Verify projection state
    assertThat(orderProjection.query(aggregateId + ":status")).isPresent();
    assertThat(orderProjection.query(aggregateId + ":status").get()).isEqualTo("submitted");
    assertThat(orderProjection.query(aggregateId + ":items_count").get()).isEqualTo(1);
  }

  @Test
  public void projectionReplayFromEvents() {
    String aggregateId = "order-replay-1";
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, null);

    Event.EventMetadata meta2 =
        Event.EventMetadata.create(aggregateId, 1, "ItemAdded", "corr-1");
    Event event2 = new Event.DomainEvent(meta2, null);

    // Store events
    eventStore.append(aggregateId, List.of(event1, event2));

    // Replay to projection
    eventStore.getEvents(aggregateId).forEach(orderProjection::apply);

    assertThat(orderProjection.version()).isEqualTo(2);
    assertThat(orderProjection.query(aggregateId + ":items_count").get()).isEqualTo(1);
  }

  @Test
  public void projectionReset() {
    String aggregateId = "order-reset-1";
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, null);

    orderProjection.apply(event1);
    assertThat(orderProjection.version()).isEqualTo(1);

    // Reset projection
    orderProjection.reset();
    assertThat(orderProjection.version()).isEqualTo(0);
    assertThat(orderProjection.getState()).isEmpty();
  }

  @Test
  public void multipleAggregateStreams() {
    String agg1 = "order-1";
    String agg2 = "order-2";

    Event.EventMetadata meta1 = Event.EventMetadata.create(agg1, 0, "OrderCreated", "corr-1");
    Event.EventMetadata meta2 = Event.EventMetadata.create(agg2, 0, "OrderCreated", "corr-2");

    Event event1 = new Event.DomainEvent(meta1, "Order 1");
    Event event2 = new Event.DomainEvent(meta2, "Order 2");

    eventStore.append(agg1, List.of(event1));
    eventStore.append(agg2, List.of(event2));

    assertThat(eventStore.getCurrentVersion(agg1)).isEqualTo(1);
    assertThat(eventStore.getCurrentVersion(agg2)).isEqualTo(1);
    assertThat(eventStore.getEvents(agg1).get(0).aggregateId()).isEqualTo(agg1);
    assertThat(eventStore.getEvents(agg2).get(0).aggregateId()).isEqualTo(agg2);
  }

  @Test
  public void eventPartialReplay() {
    String aggregateId = "order-partial";
    List<Event> events = new java.util.ArrayList<>();

    for (int i = 0; i < 5; i++) {
      Event.EventMetadata meta =
          Event.EventMetadata.create(aggregateId, i, "Event" + i, "corr-1");
      events.add(new Event.DomainEvent(meta, "payload-" + i));
    }

    eventStore.append(aggregateId, events);

    // Replay from version 2
    List<Event> replayed = eventStore.getEvents(aggregateId, 2);
    assertThat(replayed).hasSize(3);
  }

  @Test
  public void snapshotDeletion() {
    String aggregateId = "order-snap-del";
    Snapshot snapshot = Snapshot.of(aggregateId, 1, "State");
    eventStore.saveSnapshot(aggregateId, snapshot);

    assertThat(eventStore.getSnapshot(aggregateId)).isPresent();

    eventStore.deleteSnapshot(aggregateId);
    assertThat(eventStore.getSnapshot(aggregateId)).isEmpty();
  }

  @Test
  public void aggregateExistenceCheck() {
    String aggregateId = "order-exists";
    assertThat(eventStore.exists(aggregateId)).isFalse();

    Event.EventMetadata meta =
        Event.EventMetadata.create(aggregateId, 0, "OrderCreated", "corr-1");
    eventStore.append(aggregateId, List.of(new Event.DomainEvent(meta, null)));

    assertThat(eventStore.exists(aggregateId)).isTrue();
  }

  @Test
  public void countProjection() {
    EventProjection.CountProjection projection = new EventProjection.CountProjection("events");

    Event.EventMetadata meta1 =
        Event.EventMetadata.create("agg1", 0, "Created", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, null);

    Event.EventMetadata meta2 =
        Event.EventMetadata.create("agg2", 0, "Created", "corr-2");
    Event event2 = new Event.DomainEvent(meta2, null);

    Event.EventMetadata meta3 =
        Event.EventMetadata.create("agg3", 0, "Updated", "corr-3");
    Event event3 = new Event.DomainEvent(meta3, null);

    projection.apply(event1);
    projection.apply(event2);
    projection.apply(event3);

    assertThat(projection.query("Created").get()).isEqualTo(2L);
    assertThat(projection.query("Updated").get()).isEqualTo(1L);
  }

  @Test
  public void customerProjection() {
    EventProjection.CustomerProjection projection = new EventProjection.CustomerProjection();

    Event.EventMetadata meta1 =
        Event.EventMetadata.create("cust-1", 0, "CustomerCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, null);

    projection.apply(event1);
    assertThat(projection.query("cust-1:status").get()).isEqualTo("active");

    Event.EventMetadata meta2 =
        Event.EventMetadata.create("cust-1", 1, "CustomerDeactivated", "corr-1");
    Event event2 = new Event.DomainEvent(meta2, null);

    projection.apply(event2);
    assertThat(projection.query("cust-1:status").get()).isEqualTo("inactive");
  }

  @Test
  public void emptyEventStore() {
    String aggregateId = "nonexistent";
    assertThat(eventStore.getEvents(aggregateId)).isEmpty();
    assertThat(eventStore.getCurrentVersion(aggregateId)).isZero();
    assertThat(eventStore.exists(aggregateId)).isFalse();
  }
}
