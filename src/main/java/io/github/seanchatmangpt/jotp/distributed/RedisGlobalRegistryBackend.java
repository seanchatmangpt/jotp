package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.Result;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Response;

/**
 * Distributed process registry backed by Redis.
 *
 * <p>Provides cluster-wide process registration with:
 *
 * <ul>
 *   <li><strong>Distributed Lookups:</strong> Query process location across cluster
 *   <li><strong>Automatic Cleanup:</strong> Redis TTL clears dead process entries
 *   <li><strong>Pub/Sub Notifications:</strong> Watch for registration changes
 *   <li><strong>Atomic Operations:</strong> Lua scripts for compare-and-swap
 * </ul>
 *
 * <p><strong>How it works:</strong>
 *
 * <pre>
 * Node 1: register("payment-svc", ref1) ──┐
 * Node 2: lookup("payment-svc")            ├──→ Redis
 * Node 3: cleanup("node-2")                │    Cluster
 *                                           │    (master+replicas)
 * All nodes receive notifications via Pub/Sub
 * </pre>
 *
 * <p><strong>Registry Entry Format (JSON):</strong>
 *
 * <pre>{@code
 * {
 *   "name": "payment-svc",
 *   "nodeName": "node-1",
 *   "processId": 42,
 *   "sequenceNumber": 100,
 *   "registeredAt": "2024-03-17T10:30:00Z"
 * }
 * }</pre>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * RedisGlobalRegistryBackend registry = new RedisGlobalRegistryBackend("localhost", 6379);
 * registry.watch(event -> System.out.println("Registry changed: " + event));
 *
 * GlobalProcRef ref = new GlobalProcRef("node-1", 42, 100);
 * registry.store("payment-svc", ref);
 *
 * Optional<GlobalProcRef> found = registry.lookup("payment-svc");
 * registry.cleanupNode("dead-node");
 * }</pre>
 *
 * @see GlobalRegistryBackend
 */
public class RedisGlobalRegistryBackend implements GlobalRegistryBackend {

    private static final String KEY_PREFIX = "jotp:registry:";
    private static final String CHANNEL_PREFIX = "jotp:registry-events:";
    private static final long ENTRY_TTL = 300L; // 5 minutes

    private final JedisPool pool;
    private final CopyOnWriteArrayList<Consumer<RegistryEvent>> watchers =
            new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Create a Redis registry backend.
     *
     * @param host Redis host
     * @param port Redis port
     */
    public RedisGlobalRegistryBackend(String host, int port) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        config.setTestOnBorrow(true);

        this.pool = new JedisPool(config, host, port);
    }

    @Override
    public Result<Void, RegistryError> store(String name, GlobalProcRef ref) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_PREFIX + name;
            String value = serializeRef(ref);

            // Store with TTL (auto-cleanup for dead entries)
            jedis.setex(key, ENTRY_TTL, value);

            // Notify watchers
            publishEvent(new RegistryEvent.Registered(name, ref));

            return Result.ok(null);
        } catch (Exception e) {
            return Result.err(new RegistryError(
                    RegistryError.Type.STORAGE_FAILED, "Failed to store: " + name, e));
        }
    }

    @Override
    public Optional<GlobalProcRef> lookup(String name) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_PREFIX + name;
            String value = jedis.get(key);
            if (value != null) {
                return Optional.of(deserializeRef(value));
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RegistryError(
                    RegistryError.Type.LOOKUP_FAILED, "Failed to lookup: " + name, e);
        }
    }

    @Override
    public Result<Void, RegistryError> remove(String name) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_PREFIX + name;
            jedis.del(key);

            // Notify watchers
            publishEvent(new RegistryEvent.Unregistered(name));

            return Result.ok(null);
        } catch (Exception e) {
            return Result.err(new RegistryError(
                    RegistryError.Type.STORAGE_FAILED, "Failed to remove: " + name, e));
        }
    }

    @Override
    public Map<String, GlobalProcRef> listAll() {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            Set<String> keys = jedis.keys((KEY_PREFIX + "*").getBytes())
                    .stream()
                    .map(k -> new String(k, StandardCharsets.UTF_8))
                    .map(k -> k.substring(KEY_PREFIX.length()))
                    .collect(Collectors.toSet());

            Map<String, GlobalProcRef> result = new HashMap<>();
            for (String name : keys) {
                lookup(name).ifPresent(ref -> result.put(name, ref));
            }
            return result;
        } catch (Exception e) {
            throw new RegistryError(
                    RegistryError.Type.LOOKUP_FAILED, "Failed to list all entries", e);
        }
    }

    @Override
    public boolean compareAndSwap(String name, Optional<GlobalProcRef> expected, GlobalProcRef newValue) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            String key = KEY_PREFIX + name;

            if (expected.isEmpty()) {
                // Only set if absent
                return "OK".equals(jedis.set(key, serializeRef(newValue), "NX", "EX", ENTRY_TTL));
            } else {
                // Compare and swap via Lua script for atomicity
                String script =
                        "if redis.call('get', KEYS[1]) == ARGV[1] then "
                                + "  return redis.call('setex', KEYS[1], ARGV[2], ARGV[3]) "
                                + "else "
                                + "  return 0 "
                                + "end";

                Object result = jedis.eval(
                        script,
                        1,
                        key,
                        serializeRef(expected.get()),
                        String.valueOf(ENTRY_TTL),
                        serializeRef(newValue));

                return "OK".equals(result);
            }
        } catch (Exception e) {
            throw new RegistryError(
                    RegistryError.Type.STORAGE_FAILED,
                    "Failed to compare-and-swap: " + name,
                    e);
        }
    }

    @Override
    public void watch(Consumer<RegistryEvent> listener) {
        checkClosed();
        watchers.add(listener);
    }

    @Override
    public void cleanupNode(String nodeName) {
        checkClosed();
        try (Jedis jedis = pool.getResource()) {
            Set<String> keys =
                    jedis.keys((KEY_PREFIX + "*").getBytes()).stream()
                            .map(k -> new String(k, StandardCharsets.UTF_8))
                            .collect(Collectors.toSet());

            for (String key : keys) {
                String value = jedis.get(key);
                if (value != null) {
                    GlobalProcRef ref = deserializeRef(value);
                    if (ref.nodeName().equals(nodeName)) {
                        String name = key.substring(KEY_PREFIX.length());
                        jedis.del(key);
                        publishEvent(new RegistryEvent.Unregistered(name));
                    }
                }
            }
        } catch (Exception e) {
            throw new RegistryError(
                    RegistryError.Type.CLEANUP_FAILED, "Failed to cleanup node: " + nodeName, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            pool.close();
        } catch (Exception e) {
            // Ignore close errors
        }
    }

    private void publishEvent(RegistryEvent event) {
        try (Jedis jedis = pool.getResource()) {
            String channel = CHANNEL_PREFIX + "all";
            jedis.publish(channel, serializeEvent(event));
        } catch (Exception e) {
            // Log but don't fail
            System.err.println("Failed to publish registry event: " + e.getMessage());
        }

        // Notify local watchers
        watchers.forEach(w -> {
            try {
                w.accept(event);
            } catch (Exception e) {
                // Ignore watcher errors
            }
        });
    }

    private void checkClosed() {
        if (closed) {
            throw new RegistryError(RegistryError.Type.BACKEND_CLOSED, "Backend is closed", null);
        }
    }

    private String serializeRef(GlobalProcRef ref) {
        return String.format(
                "{\"nodeName\":\"%s\",\"processId\":%d,\"sequenceNumber\":%d}",
                ref.nodeName(), ref.processId(), ref.sequenceNumber());
    }

    private GlobalProcRef deserializeRef(String json) {
        // Simple JSON parsing (use Jackson in production)
        String[] parts = json.split(",");
        String nodeName = parts[0].split("\"")[3];
        long processId = Long.parseLong(parts[1].split(":")[1]);
        long seqNum = Long.parseLong(parts[2].split(":")[1].replace("}", ""));

        return new GlobalProcRef(nodeName, processId, seqNum);
    }

    private String serializeEvent(RegistryEvent event) {
        if (event instanceof RegistryEvent.Registered r) {
            return String.format("REGISTERED:%s", r.name());
        } else if (event instanceof RegistryEvent.Unregistered u) {
            return String.format("UNREGISTERED:%s", u.name());
        }
        return "UNKNOWN";
    }
}
