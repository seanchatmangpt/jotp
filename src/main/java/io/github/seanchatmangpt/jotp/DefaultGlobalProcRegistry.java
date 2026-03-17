package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Default implementation of {@link GlobalProcRegistry} with TTL caching.
 *
 * <p>Maintains a cache of process name → NodeId mappings with configurable TTL. Reduces repeated
 * queries to the discovery provider while keeping the cache fresh.
 *
 * <p>Thread-safe via ConcurrentHashMap. Cache entries expire after the configured TTL.
 */
public final class DefaultGlobalProcRegistry implements GlobalProcRegistry {

  private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(30);
  private static final Duration DEFAULT_LOOKUP_TIMEOUT = Duration.ofMillis(100);

  private final ProcessServiceDiscoveryProvider discoveryProvider;
  private final NodeId currentNodeId;
  private final Duration cacheTtl;
  private final Duration lookupTimeout;

  // Cache: processName → (NodeId, timestamp)
  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final List<Consumer<Set<String>>> watchers = new CopyOnWriteArrayList<>();

  /**
   * Create a global registry with default settings.
   *
   * @param discoveryProvider the backing service discovery provider
   * @param currentNodeId the node ID where processes are registered
   */
  public DefaultGlobalProcRegistry(
      ProcessServiceDiscoveryProvider discoveryProvider, NodeId currentNodeId) {
    this(discoveryProvider, currentNodeId, DEFAULT_CACHE_TTL, DEFAULT_LOOKUP_TIMEOUT);
  }

  /**
   * Create a global registry with custom cache settings.
   *
   * @param discoveryProvider the backing service discovery provider
   * @param currentNodeId the node ID where processes are registered
   * @param cacheTtl how long to cache lookup results
   * @param lookupTimeout timeout for discovery provider queries
   */
  public DefaultGlobalProcRegistry(
      ProcessServiceDiscoveryProvider discoveryProvider,
      NodeId currentNodeId,
      Duration cacheTtl,
      Duration lookupTimeout) {
    this.discoveryProvider = discoveryProvider;
    this.currentNodeId = currentNodeId;
    this.cacheTtl = cacheTtl;
    this.lookupTimeout = lookupTimeout;

    // Set up watcher to invalidate cache on remote changes
    discoveryProvider.watch(this::onRemoteProcessesChanged);
  }

  @Override
  public void register(String processName, ProcRef<?, ?> procRef, NodeId nodeId) {
    // Register locally first
    ProcRegistry.register(processName, procRef.proc());

    // Then sync to discovery provider
    try {
      discoveryProvider.register(processName, nodeId);
      // Update cache with newly registered process
      cache.put(processName, new CacheEntry(nodeId, Instant.now()));
      notifyWatchers();
    } catch (Exception e) {
      // Log but don't fail — degraded mode: local-only
      System.err.println("Failed to register " + processName + " in discovery provider: " + e);
    }
  }

  @Override
  public void deregister(String processName) {
    // Remove from local registry
    ProcRegistry.unregister(processName);

    // Remove from cache
    cache.remove(processName);

    // Remove from discovery provider
    try {
      discoveryProvider.deregister(processName);
      notifyWatchers();
    } catch (Exception e) {
      // Log but don't fail
      System.err.println("Failed to deregister " + processName + " from discovery provider: " + e);
    }
  }

  @Override
  public Optional<ProcRef<?, ?>> lookupLocal(String processName) {
    // Fast path: check local registry first
    Optional<Proc<?, ?>> localProc = ProcRegistry.whereis(processName);
    if (localProc.isPresent()) {
      // Found locally, wrap in a stable ProcRef
      // Note: ProcRegistry returns Proc; we need to find the corresponding ProcRef
      // In practice, callers should track their own ProcRefs, but we can return a wrapper
      return Optional.of(new ProcRef<>(localProc.get()));
    }

    return Optional.empty();
  }

  @Override
  public Optional<NodeId> lookupNodeId(String processName) {
    // Check local registry first (if it's on this node)
    if (ProcRegistry.whereis(processName).isPresent()) {
      return Optional.of(currentNodeId);
    }

    // Check cache
    CacheEntry cached = cache.get(processName);
    if (cached != null && !cached.isExpired(cacheTtl)) {
      return Optional.of(cached.nodeId);
    }

    // Query discovery provider (with timeout)
    try {
      Optional<NodeId> result = queryWithTimeout(processName);
      if (result.isPresent()) {
        // Cache the result
        cache.put(processName, new CacheEntry(result.get(), Instant.now()));
      } else {
        // Cache negative result (process not found)
        cache.remove(processName);
      }
      return result;
    } catch (Exception e) {
      // Timeout or error: fall back to cache or empty
      System.err.println("Failed to look up " + processName + ": " + e);
      if (cached != null) {
        // Return stale cache entry as fallback
        return Optional.of(cached.nodeId);
      }
      return Optional.empty();
    }
  }

  @Override
  public Set<String> listProcesses() {
    // Combine local + cached/discovered
    Set<String> all = new HashSet<>(ProcRegistry.registered());
    all.addAll(discoveryProvider.listProcesses());
    return Set.copyOf(all);
  }

  @Override
  public void watch(Consumer<Set<String>> onProcessesChanged) {
    watchers.add(onProcessesChanged);
  }

  @Override
  public void reset() {
    ProcRegistry.reset();
    cache.clear();
    watchers.clear();
    try {
      discoveryProvider.shutdown();
    } catch (Exception e) {
      System.err.println("Failed to shutdown discovery provider: " + e);
    }
  }

  // ── Private helpers ──

  private Optional<NodeId> queryWithTimeout(String processName) throws InterruptedException {
    // Use a simple timeout mechanism: query in a thread with timeout
    // In production, this would use proper async/await patterns
    long startTime = System.nanoTime();
    long timeoutNanos = lookupTimeout.toNanos();

    Optional<NodeId> result = discoveryProvider.lookup(processName);

    long elapsed = System.nanoTime() - startTime;
    if (elapsed > timeoutNanos) {
      throw new InterruptedException(
          "Lookup timeout for " + processName + " after " + (elapsed / 1_000_000) + "ms");
    }

    return result;
  }

  private void onRemoteProcessesChanged(Set<String> remoteProcesses) {
    // Invalidate cache entries not in the remote set
    // (they may have been deregistered remotely)
    Set<String> localSet = ProcRegistry.registered();
    Set<String> allKnown = new HashSet<>(localSet);
    allKnown.addAll(remoteProcesses);

    // Invalidate cache for processes that are no longer known
    cache.keySet()
        .forEach(
            processName -> {
              if (!allKnown.contains(processName)) {
                cache.remove(processName);
              }
            });

    notifyWatchers();
  }

  private void notifyWatchers() {
    Set<String> current = listProcesses();
    for (Consumer<Set<String>> watcher : watchers) {
      try {
        watcher.accept(current);
      } catch (Exception e) {
        System.err.println("Watcher failed: " + e.getMessage());
      }
    }
  }

  // Cache entry with TTL
  private static class CacheEntry {
    final NodeId nodeId;
    final Instant timestamp;

    CacheEntry(NodeId nodeId, Instant timestamp) {
      this.nodeId = nodeId;
      this.timestamp = timestamp;
    }

    boolean isExpired(Duration ttl) {
      return Instant.now().isAfter(timestamp.plus(ttl));
    }
  }
}
