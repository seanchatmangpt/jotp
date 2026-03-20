package io.github.seanchatmangpt.jotp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Application configuration holder — OTP's {@code application:get_env/2} in Java.
 *
 * <p>Joe Armstrong: "Configuration should be external to the code. OTP applications read their
 * configuration from the application environment at startup."
 *
 * <p>Provides hierarchical configuration with:
 *
 * <ul>
 *   <li>Type-safe accessors with defaults
 *   <li>Environment variable interpolation
 *   <li>Immutable snapshot semantics
 * </ul>
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * // Builder pattern with explicit build()
 * ApplicationConfig config = ApplicationConfig.create()
 *     .environment("production")
 *     .set("kafka.bootstrap.servers", "kafka:9092")
 *     .set("kafka.consumer.group", "telemetry-processor")
 *     .build();
 *
 * // Fluent pattern without build() — create() returns ApplicationConfig directly
 * ApplicationConfig config = ApplicationConfig.create()
 *     .environment("production")
 *     .set("server.port", 8080)
 *     .set("cache.enabled", true);
 *
 * String kafkaServers = config.getString("kafka.bootstrap.servers", "localhost:9092");
 * int healthInterval = config.getInt("health.check.interval", 5000);
 * }</pre>
 *
 * @see Application
 */
public final class ApplicationConfig {

    private final Map<String, String> values;
    private String environment;

    private ApplicationConfig(Map<String, String> values, String environment) {
        this.values = new HashMap<>(values);
        this.environment = environment;
    }

    // ── Factory methods ────────────────────────────────────────────────────────

    /** Create an empty configuration (fluent builder — returns ApplicationConfig directly). */
    public static ApplicationConfig create() {
        return new ApplicationConfig(new HashMap<>(), "development");
    }

    /** Create an empty configuration. */
    public static ApplicationConfig empty() {
        return new ApplicationConfig(Map.of(), "development");
    }

    /** Create configuration from existing map. */
    public static ApplicationConfig of(Map<String, String> values) {
        return new ApplicationConfig(values, "development");
    }

    /** Create configuration with environment. */
    public static ApplicationConfig of(Map<String, String> values, String environment) {
        return new ApplicationConfig(values, environment);
    }

    // ── Fluent builder methods (on ApplicationConfig itself) ───────────────────

    /** Set the environment name (fluent). */
    public ApplicationConfig environment(String env) {
        this.environment = env;
        return this;
    }

    /** Set a configuration value (fluent). */
    public ApplicationConfig set(String key, String value) {
        this.values.put(key, value);
        return this;
    }

    /** Set an integer value (stored as string, fluent). */
    public ApplicationConfig set(String key, int value) {
        return set(key, String.valueOf(value));
    }

    /** Set a long value (stored as string, fluent). */
    public ApplicationConfig set(String key, long value) {
        return set(key, String.valueOf(value));
    }

    /** Set a boolean value (stored as string, fluent). */
    public ApplicationConfig set(String key, boolean value) {
        return set(key, String.valueOf(value));
    }

    /** Set a double value (stored as string, fluent). */
    public ApplicationConfig set(String key, double value) {
        return set(key, String.valueOf(value));
    }

    /** Merge all values from a map. */
    public ApplicationConfig setAll(Map<String, String> values) {
        this.values.putAll(values);
        return this;
    }

    /** Terminal build (returns this, for compatibility with builder().build() pattern). */
    public ApplicationConfig build() {
        return this;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    /** Get a string value with default. */
    public String getString(String key, String defaultValue) {
        return Optional.ofNullable(values.get(key))
                .or(() -> Optional.ofNullable(System.getenv(envKey(key))))
                .orElse(defaultValue);
    }

    /** Get an integer value with default. */
    public int getInt(String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) {
            value = System.getenv(envKey(key));
        }
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Get a long value with default. */
    public long getLong(String key, long defaultValue) {
        String value = values.get(key);
        if (value == null) {
            value = System.getenv(envKey(key));
        }
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Get a boolean value with default. */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) {
            value = System.getenv(envKey(key));
        }
        if (value == null) return defaultValue;
        return Boolean.parseBoolean(value);
    }

    /** Get a double value with default. */
    public double getDouble(String key, double defaultValue) {
        String value = values.get(key);
        if (value == null) {
            value = System.getenv(envKey(key));
        }
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Check if a key exists. */
    public boolean has(String key) {
        return values.containsKey(key) || System.getenv(envKey(key)) != null;
    }

    /** Get the current environment name. */
    public String environment() {
        return environment;
    }

    /** Get all configuration keys. */
    public java.util.Set<String> keys() {
        return values.keySet();
    }

    /** Get a snapshot of all values (immutable). */
    public Map<String, String> asMap() {
        return Map.copyOf(values);
    }

    /** Convert dotted key to environment variable format. */
    private String envKey(String key) {
        return key.toUpperCase().replace('.', '_').replace('-', '_');
    }

    // ── Derived configurations ──────────────────────────────────────────────────

    /** Create a new config with additional values. */
    public ApplicationConfig with(String key, String value) {
        Map<String, String> newValues = new HashMap<>(values);
        newValues.put(key, value);
        return new ApplicationConfig(newValues, environment);
    }

    /** Create a new config with additional values merged in. */
    public ApplicationConfig withAll(Map<String, String> additional) {
        Map<String, String> newValues = new HashMap<>(values);
        newValues.putAll(additional);
        return new ApplicationConfig(newValues, environment);
    }

    /** Load configuration from properties. */
    public ApplicationConfig fromProperties(java.util.Properties props) {
        for (String key : props.stringPropertyNames()) {
            values.put(key, props.getProperty(key));
        }
        return this;
    }

    @Override
    public String toString() {
        return "ApplicationConfig{environment=" + environment + ", keys=" + values.size() + "}";
    }
}
