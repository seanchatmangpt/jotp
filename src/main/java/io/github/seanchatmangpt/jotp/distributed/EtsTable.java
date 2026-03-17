package io.github.seanchatmangpt.jotp.distributed;

import java.io.Serializable;
import java.util.*;

/**
 * ETS (Erlang Term Storage) table type — sealed interface for in-memory table variants.
 *
 * <p>Maps Erlang/OTP's ETS table types to Java sealed types:
 *
 * <ul>
 *   <li><strong>Set</strong>: Unique keys (last write wins)
 *   <li><strong>Bag</strong>: Duplicate keys allowed (same key, different values)
 *   <li><strong>OrderedSet</strong>: Unique keys with sorted iteration
 * </ul>
 *
 * <p>Each variant provides pattern matching capabilities for queries.
 */
public sealed interface EtsTable {

    /**
     * Put a tuple into the table. Semantics depend on table type:
     *
     * <ul>
     *   <li><strong>Set:</strong> Overwrites existing key
     *   <li><strong>Bag:</strong> Appends new value (duplicates allowed)
     *   <li><strong>OrderedSet:</strong> Overwrites existing key, maintains order
     * </ul>
     *
     * @param key the key
     * @param value the value
     */
    void put(String key, byte[] value);

    /**
     * Get value(s) for a key. Returns collection because bags can have multiple values per key.
     *
     * @param key the key
     * @return list of values (empty if not found)
     */
    List<byte[]> get(String key);

    /**
     * Delete a key and all its values.
     *
     * @param key the key
     * @return number of objects deleted
     */
    int delete(String key);

    /**
     * Match tuples against a pattern. Pattern syntax:
     *
     * <ul>
     *   <li><code>"$1:*"</code> — prefix match on first part
     *   <li><code>"key:*:end"</code> — sandwich match
     *   <li><code>"exact-key"</code> — exact match (alias for get)
     * </ul>
     *
     * @param pattern the match pattern
     * @return list of matching keys
     */
    List<String> match(String pattern);

    /**
     * Select tuples matching a predicate. Returns keys where predicate returns true.
     *
     * @param predicate filter function
     * @return list of matching keys
     */
    List<String> select(java.util.function.Predicate<String> predicate);

    /**
     * Get all keys in the table.
     *
     * @return list of keys
     */
    List<String> keys();

    /**
     * Get all values in the table.
     *
     * @return list of values
     */
    List<byte[]> values();

    /**
     * Check if table contains a key.
     *
     * @param key the key
     * @return true if key exists
     */
    boolean contains(String key);

    /**
     * Get table statistics.
     *
     * @return metadata about the table
     */
    TableStats stats();

    /**
     * Clear all entries from the table.
     */
    void clear();

    /**
     * Get the table type (set/bag/ordered_set).
     *
     * @return table type
     */
    TableType type();

    /**
     * Set-type ETS table: unique keys, last write wins.
     */
    final class Set implements EtsTable, Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, byte[]> data;
        private final String name;
        private volatile long lastWriteTime = 0;

        public Set(String name) {
            this.name = name;
            this.data = new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Override
        public void put(String key, byte[] value) {
            data.put(key, value);
            this.lastWriteTime = System.currentTimeMillis();
        }

        @Override
        public List<byte[]> get(String key) {
            byte[] val = data.get(key);
            return val != null ? List.of(val) : Collections.emptyList();
        }

        @Override
        public int delete(String key) {
            return data.remove(key) != null ? 1 : 0;
        }

        @Override
        public List<String> match(String pattern) {
            return patternMatch(data.keySet(), pattern);
        }

        @Override
        public List<String> select(java.util.function.Predicate<String> predicate) {
            return data.keySet().stream().filter(predicate).toList();
        }

        @Override
        public List<String> keys() {
            return new ArrayList<>(data.keySet());
        }

        @Override
        public List<byte[]> values() {
            return new ArrayList<>(data.values());
        }

        @Override
        public boolean contains(String key) {
            return data.containsKey(key);
        }

        @Override
        public TableStats stats() {
            return new TableStats(name, TableType.SET, data.size(), lastWriteTime);
        }

        @Override
        public void clear() {
            data.clear();
        }

        @Override
        public TableType type() {
            return TableType.SET;
        }
    }

    /**
     * Bag-type ETS table: duplicate keys allowed, all values stored.
     */
    final class Bag implements EtsTable, Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, List<byte[]>> data;
        private final String name;
        private volatile long lastWriteTime = 0;

        public Bag(String name) {
            this.name = name;
            this.data = new java.util.concurrent.ConcurrentHashMap<>();
        }

        @Override
        public void put(String key, byte[] value) {
            data.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(value);
            this.lastWriteTime = System.currentTimeMillis();
        }

        @Override
        public List<byte[]> get(String key) {
            List<byte[]> values = data.get(key);
            return values != null ? new ArrayList<>(values) : Collections.emptyList();
        }

        @Override
        public int delete(String key) {
            List<byte[]> removed = data.remove(key);
            return removed != null ? removed.size() : 0;
        }

        @Override
        public List<String> match(String pattern) {
            return patternMatch(data.keySet(), pattern);
        }

        @Override
        public List<String> select(java.util.function.Predicate<String> predicate) {
            return data.keySet().stream().filter(predicate).toList();
        }

        @Override
        public List<String> keys() {
            return new ArrayList<>(data.keySet());
        }

        @Override
        public List<byte[]> values() {
            return data.values().stream()
                    .flatMap(List::stream)
                    .toList();
        }

        @Override
        public boolean contains(String key) {
            return data.containsKey(key);
        }

        @Override
        public TableStats stats() {
            long objectCount = data.values().stream().mapToLong(List::size).sum();
            return new TableStats(name, TableType.BAG, objectCount, lastWriteTime);
        }

        @Override
        public void clear() {
            data.clear();
        }

        @Override
        public TableType type() {
            return TableType.BAG;
        }
    }

    /**
     * OrderedSet-type ETS table: unique keys with sorted iteration.
     */
    final class OrderedSet implements EtsTable, Serializable {
        private static final long serialVersionUID = 1L;
        private final Map<String, byte[]> data;
        private final String name;
        private volatile long lastWriteTime = 0;

        public OrderedSet(String name) {
            this.name = name;
            this.data = new java.util.concurrent.ConcurrentSkipListMap<>();
        }

        @Override
        public void put(String key, byte[] value) {
            data.put(key, value);
            this.lastWriteTime = System.currentTimeMillis();
        }

        @Override
        public List<byte[]> get(String key) {
            byte[] val = data.get(key);
            return val != null ? List.of(val) : Collections.emptyList();
        }

        @Override
        public int delete(String key) {
            return data.remove(key) != null ? 1 : 0;
        }

        @Override
        public List<String> match(String pattern) {
            return patternMatch(data.keySet(), pattern);
        }

        @Override
        public List<String> select(java.util.function.Predicate<String> predicate) {
            return data.keySet().stream().filter(predicate).toList();
        }

        @Override
        public List<String> keys() {
            return new ArrayList<>(data.keySet());
        }

        @Override
        public List<byte[]> values() {
            return new ArrayList<>(data.values());
        }

        @Override
        public boolean contains(String key) {
            return data.containsKey(key);
        }

        @Override
        public TableStats stats() {
            return new TableStats(name, TableType.ORDERED_SET, data.size(), lastWriteTime);
        }

        @Override
        public void clear() {
            data.clear();
        }

        @Override
        public TableType type() {
            return TableType.ORDERED_SET;
        }
    }

    /** Pattern matching helper for ETS queries. */
    static List<String> patternMatch(Set<String> keys, String pattern) {
        if (!pattern.contains("*")) {
            // Exact match
            return keys.contains(pattern) ? List.of(pattern) : Collections.emptyList();
        }

        String[] parts = pattern.split("\\*");
        return keys.stream()
                .filter(key -> {
                    if (parts.length == 1) {
                        // Just prefix match
                        return key.startsWith(parts[0]);
                    }
                    // Multi-part pattern: "prefix:*:suffix"
                    if (!key.startsWith(parts[0])) {
                        return false;
                    }
                    if (parts.length > 1 && !key.endsWith(parts[parts.length - 1])) {
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    /** Table type enumeration. */
    enum TableType {
        SET,
        BAG,
        ORDERED_SET
    }

    /** Table statistics snapshot. */
    record TableStats(String name, TableType type, long objectCount, long lastWriteTime) {
        public long ageMillis() {
            return System.currentTimeMillis() - lastWriteTime;
        }
    }
}
