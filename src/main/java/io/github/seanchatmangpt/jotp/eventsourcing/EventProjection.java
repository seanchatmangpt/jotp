package io.github.seanchatmangpt.jotp.eventsourcing;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Projection builder for creating read models from events.
 *
 * <p>Projections materialize views from event streams for fast querying.
 */
public abstract class EventProjection implements Serializable {

  private static final long serialVersionUID = 1L;

  protected final String name;
  protected final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
  protected final ReadWriteLock lock = new ReentrantReadWriteLock();
  protected volatile long projectionVersion = 0;
  protected volatile Instant lastUpdated = Instant.now();

  public EventProjection(String name) {
    this.name = name;
  }

  public String name() {
    return name;
  }

  /**
   * Apply an event to the projection.
   *
   * @param event the event to apply
   */
  public final void apply(Event event) {
    lock.writeLock().lock();
    try {
      handleEvent(event);
      projectionVersion++;
      lastUpdated = Instant.now();
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Handle event application. Subclasses override this.
   *
   * @param event the event to process
   */
  protected abstract void handleEvent(Event event);

  /**
   * Reset projection to initial state.
   */
  public void reset() {
    lock.writeLock().lock();
    try {
      state.clear();
      projectionVersion = 0;
      lastUpdated = Instant.now();
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Query the projection state.
   *
   * @param key the state key
   * @return the state value
   */
  public Optional<Object> query(String key) {
    lock.readLock().lock();
    try {
      return Optional.ofNullable(state.get(key));
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all state as snapshot.
   *
   * @return immutable copy of state
   */
  public Map<String, Object> getState() {
    lock.readLock().lock();
    try {
      return new HashMap<>(state);
    } finally {
      lock.readLock().unlock();
    }
  }

  public long version() {
    return projectionVersion;
  }

  public Instant lastUpdated() {
    return lastUpdated;
  }

  /**
   * Projection metadata for versioning and replay.
   */
  public record ProjectionMetadata(String name, long version, Instant createdAt, Instant lastUpdated) {
    public static ProjectionMetadata of(EventProjection projection) {
      return new ProjectionMetadata(
          projection.name(), projection.version(), projection.lastUpdated(), projection.lastUpdated());
    }
  }

  /**
   * Example: Customer projection tracking customer aggregates.
   */
  public static class CustomerProjection extends EventProjection {

    public CustomerProjection() {
      super("customer-projection");
    }

    @Override
    protected void handleEvent(Event event) {
      if (event instanceof Event.DomainEvent de) {
        String aggregateId = de.aggregateId();
        String eventType = de.eventType();

        if ("CustomerCreated".equals(eventType)) {
          state.put(aggregateId + ":status", "active");
          state.put(aggregateId + ":created", Instant.now().toString());
        } else if ("CustomerDeactivated".equals(eventType)) {
          state.put(aggregateId + ":status", "inactive");
        } else if ("CustomerEmailChanged".equals(eventType)) {
          state.put(aggregateId + ":email", de.payload().toString());
        }
      }
    }
  }

  /**
   * Example: Order projection tracking order aggregates.
   */
  public static class OrderProjection extends EventProjection {

    public OrderProjection() {
      super("order-projection");
    }

    @Override
    protected void handleEvent(Event event) {
      if (event instanceof Event.DomainEvent de) {
        String aggregateId = de.aggregateId();
        String eventType = de.eventType();

        if ("OrderCreated".equals(eventType)) {
          state.put(aggregateId + ":status", "pending");
          state.put(aggregateId + ":items_count", 0);
          state.put(aggregateId + ":total", "0.00");
        } else if ("ItemAdded".equals(eventType)) {
          Integer count = (Integer) state.getOrDefault(aggregateId + ":items_count", 0);
          state.put(aggregateId + ":items_count", count + 1);
        } else if ("OrderSubmitted".equals(eventType)) {
          state.put(aggregateId + ":status", "submitted");
        } else if ("OrderCancelled".equals(eventType)) {
          state.put(aggregateId + ":status", "cancelled");
        }
      }
    }
  }

  /**
   * Count projection for metrics.
   */
  public static class CountProjection extends EventProjection {

    public CountProjection(String name) {
      super(name);
    }

    @Override
    protected void handleEvent(Event event) {
      String eventType = event.eventType();
      Long count = (Long) state.getOrDefault(eventType, 0L);
      state.put(eventType, count + 1);
    }
  }
}
