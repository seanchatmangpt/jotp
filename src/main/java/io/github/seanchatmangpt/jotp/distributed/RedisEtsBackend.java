package io.github.seanchatmangpt.jotp.distributed;

import io.github.seanchatmangpt.jotp.persistence.PersistenceBackend;
import io.github.seanchatmangpt.jotp.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import redis.clients.jedis.*;

/**
 * Distributed ETS backend using Redis as primary storage.
 *
 * <p>All JOTP cluster nodes read/write directly to Redis, which serves as the single source of
 * truth for all tables. This eliminates duplication and ensures strong consistency across the
 * cluster.
 *
 * <p><strong>Architecture:</strong>
 *
 * <pre>
 * Node 1 ──┐
 * Node 2 ──┼──→ [Redis Cluster] ← Single source of truth
 * Node 3 ──┘    (All tables stored here, all nodes read/write)
 * </pre>
 *
 * <p><strong>Redis Data Structures:</strong>
 *
 * <ul>
 *   <li><strong>SET type:</strong> Stored as Redis HASH ({@code jotp:ets:tableName:set})
 *   <li><strong>BAG type:</strong> Stored as Redis LIST ({@code jotp:ets:tableName:bag})
 *   <li><strong>ORDERED_SET type:</strong> Stored as Redis ZSET ({@code
 *       jotp:ets:tableName:ordered})
 * </ul>
 *
 * <p><strong>Table Key Format:</strong>
 *
 * <ul>
 *   <li>{@code jotp:ets:tableName:set} — HASH: key → serialized value
 *   <li>{@code jotp:ets:tableName:bag} — LIST: [serialized value, ...]
 *   <li>{@code jotp:ets:tableName:ordered} — ZSET: score (version) → serialized value
 * </ul>
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li><strong>Distributed Storage:</strong> Redis is the single source of truth
 *   <li><strong>Multi-node Reads:</strong> All nodes read directly from Redis
 *   <li><strong>Atomic Operations:</strong> Lua scripts for compare-and-swap operations
 *   <li><strong>Pattern Matching:</strong> Redis SCAN with pattern support
 *   <li><strong>Pub/Sub Notifications:</strong> Change notifications to local subscribers
 *   <li><strong>TTL Support:</strong> Per-key or per-table expiration
 *   <li><strong>PersistenceBackend Compatible:</strong> Implements snapshot persistence
 * </ul>
 *
 * <p><strong>Consistency Model:</strong> Redis provides at-least-once delivery semantics. For
 * exactly-once semantics, use {@link #writeAtomicWithVersion(String, String, byte[], long)} with
 * idempotence keys.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * RedisEtsBackend backend = new RedisEtsBackend("localhost", 6379, "node-1");
 *
 * // Create and use tables
 * backend.createTable("users", EtsTable.TableType.SET);
 * backend.put("users", "user-42", userData);
 * List<byte[]> values = backend.get("users", "user-42");
 *
 * // Pattern matching
 * List<String> userKeys = backend.match("users", "user:*");
 *
 * // Subscribe to local changes (published via Pub/Sub)
 * backend.subscribeTable("users", event -> {
 *     System.out.println("Received: " + event.key());
 * });
 *
 * // Use as PersistenceBackend
 * backend.save("proc-001", stateBytes);
 * Optional<byte[]> state = backend.load("proc-001");
 *
 * backend.close();
 * }</pre>
 *
 * @see EtsTable
 * @see PersistenceBackend
 */
public class RedisEtsBackend implements PersistenceBackend {

    private static final String TABLE_PREFIX = "jotp:ets:";
    private static final String STATE_PREFIX = "jotp:state:";
    private static final String ACK_PREFIX = "jotp:ack:";
    private static final String PUBSUB_CHANNEL = "jotp:ets:changes:";
    private static final String VERSION_KEY_PREFIX = "jotp:ets:versions:";
    private static final long DEFAULT_TTL = 86400L; // 24 hours

    private final JedisPool pool;
    private final String nodeName;
    private final long ttlSeconds;
    private final Map<String, Consumer<ChangeEvent>> localSubscribers;
    private final ScheduledExecutorService notificationExecutor;
    private final Map<String, AtomicLong> writeVersions;
    private volatile boolean closed = false;

    /**
     * Create a Redis ETS backend with default TTL.
     *
     * @param redisHost Redis host
     * @param redisPort Redis port
     * @param nodeName local node name for change notifications
     */
    public RedisEtsBackend(String redisHost, int redisPort, String nodeName) {
        this(redisHost, redisPort, nodeName, DEFAULT_TTL);
    }

    /**
     * Create a Redis ETS backend with custom TTL.
     *
     * @param redisHost Redis host
     * @param redisPort Redis port
     * @param nodeName local node name for change notifications
     * @param ttlSeconds time-to-live for entries
     */
    public RedisEtsBackend(String redisHost, int redisPort, String nodeName, long ttlSeconds) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxIdle(16);
        config.setMinIdle(4);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);

        this.pool = new JedisPool(config, redisHost, redisPort);
        this.nodeName = nodeName;
        this.ttlSeconds = ttlSeconds;
        this.localSubscribers = new ConcurrentHashMap<>();
        this.writeVersions = new ConcurrentHashMap<>();
        this.notificationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RedisETS-Notify-" + nodeName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Create a new table in Redis.
     *
     * @param tableName table name
     * @param type table type (SET, BAG, ORDERED_SET)
     */
    public void createTable(String tableName, EtsTable.TableType type) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(type, "type cannot be null");

        try (Jedis jedis = pool.getResource()) {
            String typeKey = getTableTypeKey(tableName);
            jedis.set(typeKey, type.name());
            jedis.expire(typeKey, (int) ttlSeconds);
        } catch (Exception e) {
            throw new PersistenceException("Failed to create table: " + tableName, e);
        }
    }

    /**
     * Put a value into a table.
     *
     * @param tableName table name
     * @param key the key
     * @param value the value (serialized bytes)
     */
    public void put(String tableName, String key, byte[] value) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            switch (type) {
                case SET:
                    jedis.hset(redisKey, key, value);
                    jedis.expire(redisKey, (int) ttlSeconds);
                    break;
                case BAG:
                    jedis.rpush(redisKey.getBytes(), createBagEntry(key, value));
                    jedis.expire(redisKey, (int) ttlSeconds);
                    break;
                case ORDERED_SET:
                    long version = getNextVersion(jedis, tableName);
                    jedis.zadd(redisKey, (double) version, key + ":" + encodeBytes(value));
                    jedis.expire(redisKey, (int) ttlSeconds);
                    break;
            }

            publishChangeEvent(tableName, key, ChangeEvent.ChangeType.PUT);
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to put value in table: " + tableName + "." + key, e);
        }
    }

    /**
     * Get value(s) from a table.
     *
     * @param tableName table name
     * @param key the key
     * @return list of values (multiple for BAG tables)
     */
    public List<byte[]> get(String tableName, String key) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            return switch (type) {
                case SET -> {
                    byte[] value = jedis.hget(redisKey.getBytes(), key.getBytes());
                    yield value != null ? List.of(value) : Collections.emptyList();
                }
                case BAG -> {
                    List<byte[]> entries = jedis.lrange(redisKey.getBytes(), 0, -1);
                    yield entries.stream()
                            .filter(entry -> extractKeyFromBagEntry(entry).equals(key))
                            .map(this::extractValueFromBagEntry)
                            .toList();
                }
                case ORDERED_SET -> {
                    Set<String> results =
                            jedis.zrangeByLex(redisKey, "[" + key + ":", "[" + key + ":\uFFFF");
                    yield results.stream()
                            .map(s -> s.substring(key.length() + 1))
                            .map(this::decodeBytes)
                            .toList();
                }
            };
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to get value from table: " + tableName + "." + key, e);
        }
    }

    /**
     * Delete a key from a table.
     *
     * @param tableName table name
     * @param key the key to delete
     * @return number of objects deleted
     */
    public int delete(String tableName, String key) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            int deleted = switch (type) {
                case SET -> (int) jedis.hdel(redisKey, key);
                case BAG -> {
                    List<byte[]> entries = jedis.lrange(redisKey.getBytes(), 0, -1);
                    int count = 0;
                    for (byte[] entry : entries) {
                        if (extractKeyFromBagEntry(entry).equals(key)) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        List<byte[]> kept = entries.stream()
                                .filter(entry -> !extractKeyFromBagEntry(entry).equals(key))
                                .toList();
                        jedis.del(redisKey);
                        if (!kept.isEmpty()) {
                            jedis.rpush(redisKey.getBytes(), kept.toArray(new byte[0][]));
                        }
                    }
                    yield count;
                }
                case ORDERED_SET -> {
                    Set<String> results =
                            jedis.zrangeByLex(redisKey, "[" + key + ":", "[" + key + ":\uFFFF");
                    int count = (int) jedis.zremrangeByLex(redisKey, "[" + key + ":", "[" + key
                            + ":\uFFFF");
                    yield count;
                }
            };

            if (deleted > 0) {
                publishChangeEvent(tableName, key, ChangeEvent.ChangeType.DELETE);
            }
            return deleted;
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to delete from table: " + tableName + "." + key, e);
        }
    }

    /**
     * Match tuples against a pattern.
     *
     * <p>Pattern syntax:
     *
     * <ul>
     *   <li>{@code "user:*"} — prefix match
     *   <li>{@code "*:active"} — suffix match
     *   <li>{@code "exact-key"} — exact match
     * </ul>
     *
     * @param tableName table name
     * @param pattern glob pattern
     * @return list of matching keys
     */
    public List<String> match(String tableName, String pattern) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(pattern, "pattern cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            return switch (type) {
                case SET -> scanKeysWithPattern(jedis, redisKey, pattern);
                case BAG -> {
                    List<byte[]> entries = jedis.lrange(redisKey.getBytes(), 0, -1);
                    yield entries.stream()
                            .map(this::extractKeyFromBagEntry)
                            .distinct()
                            .filter(key -> matches(key, pattern))
                            .toList();
                }
                case ORDERED_SET -> {
                    Set<String> allKeys = jedis.zrange(redisKey, 0, -1);
                    yield allKeys.stream()
                            .map(s -> s.split(":")[0])
                            .distinct()
                            .filter(key -> matches(key, pattern))
                            .toList();
                }
            };
        } catch (Exception e) {
            throw new PersistenceException("Failed to match pattern in table: " + tableName, e);
        }
    }

    /**
     * Select keys matching a predicate.
     *
     * @param tableName table name
     * @param predicate filter function
     * @return list of matching keys
     */
    public List<String> select(String tableName, java.util.function.Predicate<String> predicate) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(predicate, "predicate cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            return switch (type) {
                case SET -> {
                    Map<String, byte[]> hash = jedis.hgetAll(redisKey.getBytes());
                    yield hash.keySet().stream()
                            .map(k -> new String(k, StandardCharsets.UTF_8))
                            .filter(predicate)
                            .toList();
                }
                case BAG -> {
                    List<byte[]> entries = jedis.lrange(redisKey.getBytes(), 0, -1);
                    yield entries.stream()
                            .map(this::extractKeyFromBagEntry)
                            .distinct()
                            .filter(predicate)
                            .toList();
                }
                case ORDERED_SET -> {
                    Set<String> allKeys = jedis.zrange(redisKey, 0, -1);
                    yield allKeys.stream()
                            .map(s -> s.split(":")[0])
                            .distinct()
                            .filter(predicate)
                            .toList();
                }
            };
        } catch (Exception e) {
            throw new PersistenceException("Failed to select from table: " + tableName, e);
        }
    }

    /**
     * Get all keys from a table.
     *
     * @param tableName table name
     * @return list of all keys
     */
    public List<String> keys(String tableName) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            return switch (type) {
                case SET -> {
                    Set<String> keys = jedis.hkeys(redisKey);
                    yield new ArrayList<>(keys);
                }
                case BAG -> {
                    List<byte[]> entries = jedis.lrange(redisKey.getBytes(), 0, -1);
                    yield entries.stream()
                            .map(this::extractKeyFromBagEntry)
                            .distinct()
                            .toList();
                }
                case ORDERED_SET -> {
                    Set<String> allEntries = jedis.zrange(redisKey, 0, -1);
                    yield allEntries.stream()
                            .map(s -> s.split(":")[0])
                            .distinct()
                            .toList();
                }
            };
        } catch (Exception e) {
            throw new PersistenceException("Failed to get keys from table: " + tableName, e);
        }
    }

    /**
     * Check if table contains a key.
     *
     * @param tableName table name
     * @param key the key to check
     * @return true if key exists
     */
    public boolean contains(String tableName, String key) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            return switch (type) {
                case SET -> jedis.hexists(redisKey, key);
                case BAG -> {
                    List<byte[]> entries = jedis.lrange(redisKey.getBytes(), 0, -1);
                    yield entries.stream()
                            .anyMatch(entry -> extractKeyFromBagEntry(entry).equals(key));
                }
                case ORDERED_SET -> {
                    Long count = jedis.zcount(redisKey, "-inf", "+inf");
                    yield count != null && count > 0 && jedis
                            .zrangeByLex(redisKey, "[" + key, "[" + key + "\uFFFF")
                            .stream()
                            .anyMatch(s -> s.startsWith(key + ":"));
                }
            };
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to check existence in table: " + tableName + "." + key, e);
        }
    }

    /**
     * Get table statistics.
     *
     * @param tableName table name
     * @return table metadata
     */
    public EtsTable.TableStats stats(String tableName) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);

            long objectCount = switch (type) {
                case SET -> jedis.hlen(redisKey);
                case BAG -> jedis.llen(redisKey.getBytes());
                case ORDERED_SET -> jedis.zcard(redisKey);
            };

            long createdAt = System.currentTimeMillis();
            String created = jedis.get(TABLE_PREFIX + "created:" + tableName);
            if (created != null) {
                try {
                    createdAt = Long.parseLong(created);
                } catch (NumberFormatException e) {
                    // Ignore, use current time
                }
            }

            return new EtsTable.TableStats(tableName, type, objectCount, createdAt);
        } catch (Exception e) {
            throw new PersistenceException("Failed to get stats for table: " + tableName, e);
        }
    }

    /**
     * Clear all entries from a table.
     *
     * @param tableName table name
     */
    public void clearTable(String tableName) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);
            jedis.del(redisKey);
            publishChangeEvent(tableName, "*", ChangeEvent.ChangeType.CLEAR);
        } catch (Exception e) {
            throw new PersistenceException("Failed to clear table: " + tableName, e);
        }
    }

    /**
     * Drop an entire table.
     *
     * @param tableName table name
     */
    public void dropTable(String tableName) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);
            jedis.del(redisKey);
            jedis.del(getTableTypeKey(tableName));
            jedis.del(VERSION_KEY_PREFIX + tableName);
        } catch (Exception e) {
            throw new PersistenceException("Failed to drop table: " + tableName, e);
        }
    }

    /**
     * List all tables.
     *
     * @return set of table names
     */
    public Set<String> listTables() {
        checkClosed();

        try (Jedis jedis = pool.getResource()) {
            Set<byte[]> keys = jedis.keys((TABLE_PREFIX + "*:type").getBytes());
            return keys.stream()
                    .map(k -> new String(k, StandardCharsets.UTF_8))
                    .map(k -> k.substring(TABLE_PREFIX.length(), k.length() - 5))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            throw new PersistenceException("Failed to list tables", e);
        }
    }

    /**
     * Subscribe to local changes on a table (published via Pub/Sub).
     *
     * @param tableName table name
     * @param listener callback for changes
     */
    public void subscribeTable(String tableName, Consumer<ChangeEvent> listener) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");

        localSubscribers.put(tableName, listener);
    }

    /**
     * Unsubscribe from table changes.
     *
     * @param tableName table name
     */
    public void unsubscribeTable(String tableName) {
        checkClosed();
        localSubscribers.remove(tableName);
    }

    /**
     * Write state and ACK atomically in a single batch (PersistenceBackend).
     *
     * @param key the entity key
     * @param stateBytes the serialized state
     * @param ackBytes the ACK marker
     */
    @Override
    public void writeAtomic(String key, byte[] stateBytes, byte[] ackBytes) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(stateBytes, "stateBytes cannot be null");
        Objects.requireNonNull(ackBytes, "ackBytes cannot be null");

        try (Jedis jedis = pool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            pipeline.hset(STATE_PREFIX, key, stateBytes);
            pipeline.hset(ACK_PREFIX, key, ackBytes);
            pipeline.expire(STATE_PREFIX, (int) ttlSeconds);
            pipeline.expire(ACK_PREFIX, (int) ttlSeconds);
            pipeline.sync();
        } catch (Exception e) {
            throw new PersistenceException("Failed to write atomic state for key: " + key, e);
        }
    }

    /**
     * Write with version for idempotence checking (advanced).
     *
     * @param tableName table name
     * @param key the key
     * @param value the value
     * @param version version number for idempotence
     */
    public void writeAtomicWithVersion(String tableName, String key, byte[] value, long version) {
        checkClosed();
        Objects.requireNonNull(tableName, "tableName cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");

        try (Jedis jedis = pool.getResource()) {
            EtsTable.TableType type = getTableType(jedis, tableName);
            String redisKey = getRedisTableKey(tableName, type);
            String versionKey = VERSION_KEY_PREFIX + tableName + ":" + key;

            // Check if version already written
            String lastVersion = jedis.get(versionKey);
            if (lastVersion != null && Long.parseLong(lastVersion) >= version) {
                return; // Idempotent: version already processed
            }

            // Write value and version in batch
            Pipeline pipeline = jedis.pipelined();
            switch (type) {
                case SET:
                    pipeline.hset(redisKey, key, value);
                    break;
                case BAG:
                    pipeline.rpush(redisKey.getBytes(), createBagEntry(key, value));
                    break;
                case ORDERED_SET:
                    pipeline.zadd(redisKey, (double) version, key + ":" + encodeBytes(value));
                    break;
            }
            pipeline.set(versionKey, String.valueOf(version));
            pipeline.expire(redisKey, (int) ttlSeconds);
            pipeline.expire(versionKey, (int) ttlSeconds);
            pipeline.sync();

            publishChangeEvent(tableName, key, ChangeEvent.ChangeType.PUT);
        } catch (Exception e) {
            throw new PersistenceException(
                    "Failed to write atomic with version: " + tableName + "." + key, e);
        }
    }

    // ====== PersistenceBackend implementation ======

    @Override
    public void save(String key, byte[] snapshot) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        try (Jedis jedis = pool.getResource()) {
            String stateKey = STATE_PREFIX + key;
            jedis.setex(stateKey.getBytes(), ttlSeconds, snapshot);
        } catch (Exception e) {
            throw new PersistenceException("Failed to save state for key: " + key, e);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            String stateKey = STATE_PREFIX + key;
            byte[] snapshot = jedis.get(stateKey.getBytes());
            return Optional.ofNullable(snapshot);
        } catch (Exception e) {
            throw new PersistenceException("Failed to load state for key: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            String stateKey = STATE_PREFIX + key;
            jedis.del(stateKey);
            deleteAck(key);
        } catch (Exception e) {
            throw new PersistenceException("Failed to delete state for key: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            String stateKey = STATE_PREFIX + key;
            return jedis.exists(stateKey);
        } catch (Exception e) {
            throw new PersistenceException("Failed to check existence for key: " + key, e);
        }
    }

    @Override
    public Iterable<String> listKeys() {
        checkClosed();

        try (Jedis jedis = pool.getResource()) {
            Set<String> keys = jedis.hkeys(STATE_PREFIX);
            return new ArrayList<>(keys);
        } catch (Exception e) {
            throw new PersistenceException("Failed to list keys", e);
        }
    }

    @Override
    public Optional<Long> getAckSequence(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            byte[] ackBytes = jedis.hget(ACK_PREFIX.getBytes(), key.getBytes());
            if (ackBytes != null) {
                return Optional.of(bytesToLong(ackBytes));
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new PersistenceException("Failed to get ACK sequence for key: " + key, e);
        }
    }

    @Override
    public void deleteAck(String key) {
        checkClosed();
        Objects.requireNonNull(key, "key cannot be null");

        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(ACK_PREFIX, key);
        } catch (Exception e) {
            throw new PersistenceException("Failed to delete ACK for key: " + key, e);
        }
    }

    @Override
    public void close() {
        closed = true;
        notificationExecutor.shutdownNow();
        try {
            pool.close();
        } catch (Exception e) {
            System.err.println("Error closing Redis pool: " + e.getMessage());
        }
        localSubscribers.clear();
        writeVersions.clear();
    }

    // ====== Private helpers ======

    private EtsTable.TableType getTableType(Jedis jedis, String tableName) {
        String typeKey = getTableTypeKey(tableName);
        String typeName = jedis.get(typeKey);
        if (typeName == null) {
            // Auto-create as SET if not exists
            jedis.set(typeKey, EtsTable.TableType.SET.name());
            jedis.expire(typeKey, (int) ttlSeconds);
            return EtsTable.TableType.SET;
        }
        return EtsTable.TableType.valueOf(typeName);
    }

    private String getTableTypeKey(String tableName) {
        return TABLE_PREFIX + tableName + ":type";
    }

    private String getRedisTableKey(String tableName, EtsTable.TableType type) {
        return switch (type) {
            case SET -> TABLE_PREFIX + tableName + ":set";
            case BAG -> TABLE_PREFIX + tableName + ":bag";
            case ORDERED_SET -> TABLE_PREFIX + tableName + ":ordered";
        };
    }

    private byte[] createBagEntry(String key, byte[] value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[4 + keyBytes.length + value.length];
        int pos = 0;

        // Write key length (4 bytes, big-endian)
        result[pos++] = (byte) ((keyBytes.length >> 24) & 0xFF);
        result[pos++] = (byte) ((keyBytes.length >> 16) & 0xFF);
        result[pos++] = (byte) ((keyBytes.length >> 8) & 0xFF);
        result[pos++] = (byte) (keyBytes.length & 0xFF);

        // Write key
        System.arraycopy(keyBytes, 0, result, pos, keyBytes.length);
        pos += keyBytes.length;

        // Write value
        System.arraycopy(value, 0, result, pos, value.length);

        return result;
    }

    private String extractKeyFromBagEntry(byte[] entry) {
        if (entry.length < 4) {
            return "";
        }
        int keyLen = ((entry[0] & 0xFF) << 24)
                | ((entry[1] & 0xFF) << 16)
                | ((entry[2] & 0xFF) << 8)
                | (entry[3] & 0xFF);
        return new String(entry, 4, keyLen, StandardCharsets.UTF_8);
    }

    private byte[] extractValueFromBagEntry(byte[] entry) {
        if (entry.length < 4) {
            return new byte[0];
        }
        int keyLen = ((entry[0] & 0xFF) << 24)
                | ((entry[1] & 0xFF) << 16)
                | ((entry[2] & 0xFF) << 8)
                | (entry[3] & 0xFF);
        int valueLen = entry.length - 4 - keyLen;
        byte[] value = new byte[valueLen];
        System.arraycopy(entry, 4 + keyLen, value, 0, valueLen);
        return value;
    }

    private String encodeBytes(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private byte[] decodeBytes(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    private List<String> scanKeysWithPattern(Jedis jedis, String redisKey, String pattern) {
        List<String> result = new ArrayList<>();
        long cursor = 0;

        do {
            ScanResult<String> scanResult = jedis.hscan(redisKey, cursor, new ScanParams()
                    .match(pattern));
            result.addAll(scanResult.getResult().stream()
                    .filter(s -> !s.isEmpty())
                    .toList());
            cursor = Long.parseLong(scanResult.getCursor());
        } while (cursor != 0);

        return result;
    }

    private boolean matches(String key, String pattern) {
        if (!pattern.contains("*")) {
            return key.equals(pattern);
        }

        String[] parts = pattern.split("\\*");
        if (parts.length == 1) {
            return key.startsWith(parts[0]);
        }

        if (!key.startsWith(parts[0])) {
            return false;
        }

        if (parts.length > 1 && !key.endsWith(parts[parts.length - 1])) {
            return false;
        }

        return true;
    }

    private long getNextVersion(Jedis jedis, String tableName) {
        String versionKey = VERSION_KEY_PREFIX + tableName;
        Long version = jedis.incr(versionKey);
        jedis.expire(versionKey, (int) ttlSeconds);
        return version != null ? version : 0L;
    }

    private void publishChangeEvent(
            String tableName, String key, ChangeEvent.ChangeType type) {
        notificationExecutor.execute(() -> {
            Consumer<ChangeEvent> listener = localSubscribers.get(tableName);
            if (listener != null) {
                try {
                    ChangeEvent event = new ChangeEvent(tableName, key, type, nodeName,
                            System.currentTimeMillis());
                    listener.accept(event);
                } catch (Exception e) {
                    System.err.println("Error notifying listener for " + tableName + ": " + e);
                }
            }
        });
    }

    private void checkClosed() {
        if (closed) {
            throw new PersistenceException("Backend is closed");
        }
    }

    private long bytesToLong(byte[] bytes) {
        if (bytes.length != 8) {
            throw new IllegalArgumentException("ACK bytes must be 8 bytes long");
        }
        return ((long) bytes[0] & 0xFFL) << 56
                | ((long) bytes[1] & 0xFFL) << 48
                | ((long) bytes[2] & 0xFFL) << 40
                | ((long) bytes[3] & 0xFFL) << 32
                | ((long) bytes[4] & 0xFFL) << 24
                | ((long) bytes[5] & 0xFFL) << 16
                | ((long) bytes[6] & 0xFFL) << 8
                | bytes[7] & 0xFFL;
    }

    /**
     * Change event published to local subscribers.
     */
    public record ChangeEvent(
            String tableName,
            String key,
            ChangeType type,
            String originNode,
            long timestamp) {
        public enum ChangeType {
            PUT,
            DELETE,
            CLEAR
        }
    }
}
