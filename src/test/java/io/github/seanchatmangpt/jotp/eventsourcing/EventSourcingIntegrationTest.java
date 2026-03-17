package io.github.seanchatmangpt.jotp.eventsourcing;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventSourcingIntegrationTest {

  private EventStoreInterface eventStore;
  private EventProjection.OrderProjection orderProjection;
  private EventProjection.CountProjection countProjection;

  @BeforeEach
  public void setUp() {
    eventStore = new InMemoryEventStore();
    orderProjection = new EventProjection.OrderProjection();
    countProjection = new EventProjection.CountProjection("event-counts");
  }

  @Test
  public void fullOrderLifecycleWithSnapshot() {
    String orderId = "order-complete-1";

    // Create order
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(orderId, 0, "OrderCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, "Order for customer-123");

    eventStore.append(orderId, List.of(event1));

    // Add items
    Event.EventMetadata meta2 =
        Event.EventMetadata.create(orderId, 1, "ItemAdded", "corr-1");
    Event event2 = new Event.DomainEvent(meta2, "Item: laptop");

    Event.EventMetadata meta3 =
        Event.EventMetadata.create(orderId, 2, "ItemAdded", "corr-1");
    Event event3 = new Event.DomainEvent(meta3, "Item: monitor");

    eventStore.append(orderId, List.of(event2, event3));

    // Save snapshot
    Snapshot snapshot = Snapshot.of(
        orderId,
        2,
        "Order{status=pending, items=2, total=2000.00}"
    );
    eventStore.saveSnapshot(orderId, snapshot);

    // Submit order
    Event.EventMetadata meta4 =
        Event.EventMetadata.create(orderId, 3, "OrderSubmitted", "corr-1");
    Event event4 = new Event.DomainEvent(meta4, "Payment processed");

    eventStore.append(orderId, List.of(event4));

    // Verify state
    assertThat(eventStore.getCurrentVersion(orderId)).isEqualTo(3);
    assertThat(eventStore.getSnapshot(orderId)).isPresent();
    assertThat(eventStore.getEvents(orderId)).hasSize(4);
  }

  @Test
  public void projectionConsistencyAfterFailure() {
    String orderId = "order-resilient-1";

    // Original events
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(orderId, 0, "OrderCreated", "corr-1");
    Event event1 = new Event.DomainEvent(meta1, null);

    eventStore.append(orderId, List.of(event1));
    orderProjection.apply(event1);

    // Simulate projection failure and recovery
    orderProjection.reset();
    assertThat(orderProjection.version()).isZero();

    // Replay events
    List<Event> allEvents = eventStore.getEvents(orderId);
    allEvents.forEach(orderProjection::apply);

    // Projection recovered
    assertThat(orderProjection.version()).isEqualTo(1);
  }

  @Test
  public void multipleProjectionsSameSources() {
    String agg1 = "order-multi-1";
    String agg2 = "order-multi-2";

    List<Event> events1 = createOrderEvents(agg1, 3);
    List<Event> events2 = createOrderEvents(agg2, 2);

    eventStore.append(agg1, events1);
    eventStore.append(agg2, events2);

    // Apply to both projections
    events1.forEach(orderProjection::apply);
    events1.forEach(countProjection::apply);
    events2.forEach(orderProjection::apply);
    events2.forEach(countProjection::apply);

    assertThat(orderProjection.version()).isEqualTo(5);
    assertThat(countProjection.query("OrderCreated")).isPresent();
  }

  @Test
  public void versionedSnapshots() {
    String orderId = "order-snapshot-versions";

    // Version 1
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(orderId, 0, "OrderCreated", "corr-1");
    eventStore.append(orderId, List.of(new Event.DomainEvent(meta1, null)));

    Snapshot snap1 = Snapshot.of(orderId, 1, "State-v1");
    eventStore.saveSnapshot(orderId, snap1);

    // Version 2
    Event.EventMetadata meta2 =
        Event.EventMetadata.create(orderId, 1, "ItemAdded", "corr-1");
    eventStore.append(orderId, List.of(new Event.DomainEvent(meta2, null)));

    Snapshot snap2 = Snapshot.of(orderId, 2, "State-v2");
    eventStore.saveSnapshot(orderId, snap2);

    // Latest snapshot
    var latest = eventStore.getSnapshot(orderId);
    assertThat(latest.get().version()).isEqualTo(2);
  }

  @Test
  public void eventStreamBranching() {
    String agg1 = "order-branch-1";
    String agg2 = "order-branch-2";
    String agg3 = "order-branch-3";

    // Same initial event for all
    Event.EventMetadata commonMeta =
        Event.EventMetadata.create("common", 0, "OrderCreated", "corr-1");
    Event commonEvent = new Event.DomainEvent(commonMeta, "Created");

    // Different continuations
    Event.EventMetadata meta1 =
        Event.EventMetadata.create(agg1, 1, "OrderSubmitted", "corr-1");
    Event.EventMetadata meta2 =
        Event.EventMetadata.create(agg2, 1, "OrderCancelled", "corr-1");
    Event.EventMetadata meta3 =
        Event.EventMetadata.create(agg3, 1, "OrderPending", "corr-1");

    eventStore.append(agg1, List.of(new Event.DomainEvent(meta1, null)));
    eventStore.append(agg2, List.of(new Event.DomainEvent(meta2, null)));
    eventStore.append(agg3, List.of(new Event.DomainEvent(meta3, null)));

    // Verify branches
    assertThat(eventStore.getEvents(agg1).get(0).eventType()).isEqualTo("OrderSubmitted");
    assertThat(eventStore.getEvents(agg2).get(0).eventType()).isEqualTo("OrderCancelled");
    assertThat(eventStore.getEvents(agg3).get(0).eventType()).isEqualTo("OrderPending");
  }

  @Test
  public void eventProjectionIncremental() {
    String orderId = "order-incremental";

    // Apply events one by one
    for (int i = 0; i < 10; i++) {
      Event.EventMetadata meta =
          Event.EventMetadata.create(orderId, i, "Event" + i, "corr-1");
      Event event = new Event.DomainEvent(meta, "payload-" + i);

      eventStore.append(orderId, List.of(event));
      countProjection.apply(event);

      // Verify projection updated
      assertThat(countProjection.version()).isEqualTo(i + 1);
    }

    assertThat(countProjection.version()).isEqualTo(10);
  }

  @Test
  public void snapshotInvalidation() {
    String orderId = "order-invalid-snap";

    Event.EventMetadata meta =
        Event.EventMetadata.create(orderId, 0, "OrderCreated", "corr-1");
    eventStore.append(orderId, List.of(new Event.DomainEvent(meta, null)));

    // Save snapshot
    Snapshot snap = Snapshot.of(orderId, 1, "old-state");
    eventStore.saveSnapshot(orderId, snap);
    assertThat(eventStore.getSnapshot(orderId)).isPresent();

    // Delete snapshot
    eventStore.deleteSnapshot(orderId);
    assertThat(eventStore.getSnapshot(orderId)).isEmpty();
  }

  @Test
  public void eventOrderingGuarantees() {
    String orderId = "order-ordered";

    java.util.List<Event> events = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Event.EventMetadata meta =
          Event.EventMetadata.create(orderId, i, "Event" + i, "corr-1");
      events.add(new Event.DomainEvent(meta, i));
    }

    eventStore.append(orderId, events);

    // Verify order preservation
    List<Event> retrieved = eventStore.getEvents(orderId);
    for (int i = 0; i < retrieved.size(); i++) {
      assertThat(retrieved.get(i).eventType()).isEqualTo("Event" + i);
    }
  }

  @Test
  public void projectionThreadSafety() throws InterruptedException {
    String orderId = "order-concurrent";

    // Create events
    List<Event> events = createOrderEvents(orderId, 100);

    // Apply events concurrently
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(4);

    for (Event event : events) {
      executor.submit(() -> orderProjection.apply(event));
    }

    executor.shutdown();
    assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Verify final state
    assertThat(orderProjection.version()).isEqualTo(100);
  }

  @Test
  public void snaphotFastPath() {
    String orderId = "order-fast-path";

    // Create 1000 events
    java.util.List<Event> events = new java.util.ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      Event.EventMetadata meta =
          Event.EventMetadata.create(orderId, i, "Event" + i, "corr-1");
      events.add(new Event.DomainEvent(meta, null));
    }

    eventStore.append(orderId, events);

    // Save snapshot at v500
    Snapshot snapshot = Snapshot.of(orderId, 500, "state-at-500");
    eventStore.saveSnapshot(orderId, snapshot);

    // Fast path: start from snapshot, replay from v500
    var snap = eventStore.getSnapshot(orderId);
    assertThat(snap.get().version()).isEqualTo(500);

    // Only need to replay last 500 events
    List<Event> remainingEvents = eventStore.getEvents(orderId, 500);
    assertThat(remainingEvents).hasSize(500);
  }

  private List<Event> createOrderEvents(String aggregateId, int count) {
    java.util.List<Event> events = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      Event.EventMetadata meta =
          Event.EventMetadata.create(aggregateId, i, "OrderEvent" + i, "corr-1");
      events.add(new Event.DomainEvent(meta, null));
    }
    return events;
  }
}
