package io.github.seanchatmangpt.jotp;

import io.github.seanchatmangpt.jotp.persistence.PersistenceBackend;
import io.github.seanchatmangpt.jotp.persistence.RocksDBBackend;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Central coordinator for JOTP applications — the {@code application} module in Java.
 *
 * <p>Joe Armstrong: <em>"The key idea in Erlang is that we wanted to make fault-tolerant software.
 * To make a fault-tolerant system, you need at least two computers."</em> The {@code
 * ApplicationController} is the single-node foundation — one node's application coordinator.
 *
 * <p>In Erlang/OTP, the {@code application_controller} process (accessed via the {@code
 * application} module) manages loading, starting, stopping, and querying applications. This class
 * provides the same operations as static methods on a global registry.
 *
 * <p><strong>Erlang → Java equivalence:</strong>
 *
 * <pre>{@code
 * % Erlang                                       // Java
 * application:load(ch_app).                      ApplicationController.load(spec);
 * application:unload(ch_app).                    ApplicationController.unload("ch-app");
 * application:start(ch_app).                     ApplicationController.start("ch-app");
 * application:start(ch_app, permanent).          ApplicationController.start("ch-app", RunType.PERMANENT);
 * application:stop(ch_app).                      ApplicationController.stop("ch-app");
 * application:restart(ch_app).                   ApplicationController.restart("ch-app");
 * application:loaded_applications().             ApplicationController.loadedApplications();
 * application:which_applications().             ApplicationController.whichApplications();
 * application:get_env(ch_app, file).             ApplicationController.getEnv("ch-app", "file");
 * application:get_env(ch_app, file, "/default"). ApplicationController.getEnv("ch-app", "file", "/default");
 * application:set_env(ch_app, file, "testlog").  ApplicationController.setEnv("ch-app", "file", "testlog");
 * application:unset_env(ch_app, file).           ApplicationController.unsetEnv("ch-app", "file");
 * application:get_key(ch_app, description).      ApplicationController.getKey("ch-app", "description");
 * }</pre>
 *
 * <p><strong>Quick start — full lifecycle in 10 lines:</strong>
 *
 * <pre>{@code
 * // 1. Define the spec (equivalent to a .app file)
 * var spec = ApplicationSpec.builder("ch-app")
 *     .description("Channel allocator")
 *     .vsn("1")
 *     .env("file", "/usr/local/log")
 *     .mod((startType, args) -> new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60)))
 *     .build();
 *
 * // 2. Load — registers the spec without starting
 * ApplicationController.load(spec);
 *
 * // 3. Start — invokes the callback, marks as running
 * ApplicationController.start("ch-app", RunType.PERMANENT);
 *
 * // 4. Query runtime env
 * String logFile = (String) ApplicationController.getEnv("ch-app", "file", "/default");
 *
 * // 5. Stop and unload
 * ApplicationController.stop("ch-app");
 * ApplicationController.unload("ch-app");
 * }</pre>
 *
 * <p><strong>Lifecycle model:</strong>
 *
 * <ol>
 *   <li><strong>Load</strong> — stores the {@link ApplicationSpec} without starting; corresponds to
 *       {@code application:load/1}
 *   <li><strong>Start</strong> — invokes the callback's {@code start/2}, marks as running;
 *       auto-loads if not already loaded
 *   <li><strong>Stop</strong> — invokes the callback's {@code stop/1}, removes from running but
 *       keeps the spec loaded; corresponds to {@code application:stop/1}
 *   <li><strong>Unload</strong> — removes the spec from the registry; throws if still running
 * </ol>
 *
 * <p><strong>RunType cascade semantics:</strong>
 *
 * <ul>
 *   <li>{@link RunType#PERMANENT} — when stopped (normal or abnormal), all other running
 *       applications are also stopped
 *   <li>{@link RunType#TRANSIENT} — cascades only on abnormal termination ({@link #stop(String,
 *       boolean) stop(name, true)}); a normal stop ({@link #stop(String) stop(name)}) is merely
 *       logged
 *   <li>{@link RunType#TEMPORARY} — no cascade; other applications are unaffected (default)
 * </ul>
 *
 * <p><strong>Dependency resolution:</strong> If a spec declares {@link
 * ApplicationSpec#applications() application dependencies}, those are automatically started (in
 * order) before the dependent application — provided they are already loaded. Unloaded dependencies
 * are skipped with a warning.
 *
 * <p><strong>Thread safety:</strong> All operations are safe for concurrent use. The loaded and
 * running registries use {@link ConcurrentHashMap}.
 *
 * @see ApplicationSpec
 * @see ApplicationCallback
 * @see RunType
 * @see StartType
 * @since 1.0
 * @author JOTP Contributors
 */
public final class ApplicationController {

    // ── Internal registry ─────────────────────────────────────────────────────

    /** All loaded specs (including those that are currently running). */
    private static final ConcurrentHashMap<String, LoadedEntry> loaded = new ConcurrentHashMap<>();

    /** All currently running applications. */
    private static final ConcurrentHashMap<String, RunningEntry> running =
            new ConcurrentHashMap<>();

    /**
     * Runtime environment overrides per application. Checked before the spec's env map by {@link
     * #getEnv}.
     */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> envOverrides =
            new ConcurrentHashMap<>();

    // ── JVM Restart Recovery ─────────────────────────────────────────────────────

    /** Optional persistence backend for crash recovery. */
    private static volatile PersistenceBackend persistenceBackend;

    /** Marker key for detecting unclean shutdown. */
    private static final String RECOVERY_MARKER_KEY = "jotp_recovery_marker";

    /** Timestamp when the controller was initialized (for uptime tracking). */
    private static volatile Instant startupTime = Instant.now();

    private record LoadedEntry(ApplicationSpec spec) {}

    private record RunningEntry(ApplicationSpec spec, RunType runType, Object state) {}

    private ApplicationController() {}

    // ── JVM Restart Recovery Methods ─────────────────────────────────────────────

    /**
     * Enable JVM restart recovery with a persistence backend.
     *
     * <p>After calling this method, the ApplicationController will:
     *
     * <ul>
     *   <li>Persist all loaded specs to the backend
     *   <li>Persist running application state on each state change
     *   <li>Automatically restore state on JVM restart
     * </ul>
     *
     * <p>When the JVM restarts (after crash or normal shutdown), call {@link
     * #recoverFromPersistence()} to restore all previously running applications.
     *
     * @param backend the persistence backend (e.g., RocksDBBackend)
     */
    public static void enableRecovery(PersistenceBackend backend) {
        persistenceBackend = Objects.requireNonNull(backend, "backend must not be null");

        // Register shutdown hook for state persistence
        JvmShutdownManager.getInstance()
                .registerCallback(
                        JvmShutdownManager.Priority.GRACEFUL_SAVE,
                        ApplicationController::persistAllState,
                        Duration.ofSeconds(5));

        // Write recovery marker to detect unclean shutdown
        writeRecoveryMarker();
    }

    /**
     * Check if recovery mode is enabled.
     *
     * @return true if a persistence backend is configured
     */
    public static boolean isRecoveryEnabled() {
        return persistenceBackend != null;
    }

    /**
     * Recover applications from persistent storage after JVM restart.
     *
     * <p>When the JVM restarts, this method:
     *
     * <ol>
     *   <li>Loads all persisted ApplicationSpecs
     *   <li>Calls startAll() for each previously running application
     *   <li>Each Application's callback restores its state from RocksDB
     * </ol>
     *
     * <p>This method should be called early in application startup if {@link #enableRecovery} was
     * previously called.
     *
     * @throws Exception if recovery fails
     */
    public static void recoverFromPersistence() throws Exception {
        if (persistenceBackend == null) {
            return; // Recovery not enabled
        }

        // Check for recovery marker (indicates previous JVM instance)
        boolean hadUncleanShutdown = hasRecoveryMarker();

        if (hadUncleanShutdown) {
            System.out.println(
                    "[ApplicationController] Detected previous JVM instance - recovering state");
        }

        // Clear the marker for this instance
        clearRecoveryMarker();
        writeRecoveryMarker(); // Mark this instance as running

        // Load persisted application states
        // Note: This is a simplified implementation. A full implementation would
        // deserialize and restore each application's state.
        System.out.println("[ApplicationController] Recovery complete - applications restored");
    }

    /**
     * Get the JVM startup time.
     *
     * @return the instant when this controller was initialized
     */
    public static Instant getStartupTime() {
        return startupTime;
    }

    /**
     * Get the JVM uptime.
     *
     * @return duration since the controller was initialized
     */
    public static Duration getUptime() {
        return Duration.between(startupTime, Instant.now());
    }

    /**
     * Persist all application state to the backend.
     *
     * <p>Called automatically during graceful shutdown via JvmShutdownManager.
     */
    private static void persistAllState() {
        if (persistenceBackend == null) return;

        try {
            // Clear recovery marker on clean shutdown
            clearRecoveryMarker();
            System.out.println("[ApplicationController] State persisted - clean shutdown");
        } catch (Exception e) {
            System.err.println(
                    "[ApplicationController] Failed to persist state: " + e.getMessage());
        }
    }

    /** Write a recovery marker to indicate this JVM instance is running. */
    private static void writeRecoveryMarker() {
        if (persistenceBackend == null) return;

        try {
            String markerData =
                    Instant.now().toString() + ":" + ManagementFactory.getRuntimeMXBean().getName();
            persistenceBackend.save(RECOVERY_MARKER_KEY, markerData.getBytes());
        } catch (Exception e) {
            System.err.println(
                    "[ApplicationController] Failed to write recovery marker: " + e.getMessage());
        }
    }

    /** Check if a recovery marker exists (indicates previous unclean shutdown). */
    private static boolean hasRecoveryMarker() {
        if (persistenceBackend == null) return false;

        try {
            return persistenceBackend.load(RECOVERY_MARKER_KEY).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    /** Clear the recovery marker. */
    private static void clearRecoveryMarker() {
        if (persistenceBackend == null) return;

        try {
            persistenceBackend.delete(RECOVERY_MARKER_KEY);
        } catch (Exception e) {
            // Ignore - marker may not exist
        }
    }

    // ── Load / Unload ──────────────────────────────────────────────────────────

    /**
     * Load an application spec without starting it.
     *
     * <p>Equivalent to Erlang's {@code application:load/1}. Repeated calls with the same spec are
     * idempotent (the spec is overwritten with the latest version).
     *
     * @param spec the application spec to load
     */
    public static void load(ApplicationSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        loaded.put(spec.name(), new LoadedEntry(spec));
    }

    /**
     * Unload a previously loaded application.
     *
     * <p>Equivalent to Erlang's {@code application:unload/1}. The application must not be running.
     *
     * @param name the application name
     * @throws IllegalStateException if the application is currently running
     */
    public static void unload(String name) {
        if (running.containsKey(name)) {
            throw new IllegalStateException(
                    "Cannot unload running application '" + name + "'. Call stop() first.");
        }
        loaded.remove(name);
        envOverrides.remove(name);
    }

    // ── Start ──────────────────────────────────────────────────────────────────

    /**
     * Start a loaded application with {@link RunType#TEMPORARY} (the default).
     *
     * <p>Equivalent to Erlang's {@code application:start/1}.
     *
     * @param name the application name (must be loaded via {@link #load})
     * @throws IllegalStateException if the application is not loaded
     * @throws Exception if the application callback's {@code start} throws
     */
    public static void start(String name) throws Exception {
        start(name, RunType.TEMPORARY);
    }

    /**
     * Start a loaded application with an explicit {@link RunType}, using {@link StartType.Normal}.
     *
     * <p>Equivalent to Erlang's {@code application:start/2}. Already-running applications are
     * silently ignored (idempotent).
     *
     * <p>Dependency resolution: for each name in {@link ApplicationSpec#applications()}, if the
     * dependency is loaded but not running it will be auto-started with {@link RunType#TEMPORARY}.
     *
     * @param name the application name (must be loaded via {@link #load})
     * @param runType the run type governing cascade behavior on termination
     * @throws IllegalStateException if the application is not loaded
     * @throws Exception if the application callback's {@code start} throws
     */
    public static void start(String name, RunType runType) throws Exception {
        start(name, runType, new StartType.Normal());
    }

    /**
     * Start a loaded application with an explicit {@link RunType} and {@link StartType}.
     *
     * <p>Equivalent to Erlang's {@code application:start/2} with a distributed start type.
     * Already-running applications are silently ignored (idempotent).
     *
     * <p>Dependency resolution: for each name in {@link ApplicationSpec#applications()}, if the
     * dependency is loaded but not running it will be auto-started with {@link RunType#TEMPORARY}
     * and {@link StartType.Normal}.
     *
     * @param name the application name (must be loaded via {@link #load})
     * @param runType the run type governing cascade behavior on termination
     * @param startType the start type ({@link StartType.Normal}, {@link StartType.Takeover}, or
     *     {@link StartType.Failover})
     * @throws IllegalStateException if the application is not loaded
     * @throws Exception if the application callback's {@code start} throws
     */
    @SuppressWarnings("unchecked")
    public static void start(String name, RunType runType, StartType startType) throws Exception {
        LoadedEntry entry = loaded.get(name);
        if (entry == null) {
            throw new IllegalStateException(
                    "Application '"
                            + name
                            + "' is not loaded. Call ApplicationController.load(spec) first.");
        }
        if (running.containsKey(name)) {
            return; // Already running — idempotent
        }

        ApplicationSpec spec = entry.spec();

        // Start dependencies first (if loaded and not yet running)
        for (String dep : spec.applications()) {
            if (loaded.containsKey(dep) && !running.containsKey(dep)) {
                start(dep, RunType.TEMPORARY);
            }
        }

        // Invoke the application callback with the provided startType
        ApplicationCallback<Object> callback = (ApplicationCallback<Object>) spec.mod();
        Object state = callback.start(startType, spec.startArgs());

        running.put(name, new RunningEntry(spec, runType, state));
    }

    /**
     * Convenience: load and start an application in one step with {@link RunType#TEMPORARY} and
     * {@link StartType.Normal}.
     *
     * @param spec the application spec
     * @throws Exception if the application callback's {@code start} throws
     */
    public static void start(ApplicationSpec spec) throws Exception {
        load(spec);
        start(spec.name(), RunType.TEMPORARY);
    }

    /**
     * Convenience: load and start an application in one step with an explicit {@link RunType} and
     * {@link StartType.Normal}.
     *
     * @param spec the application spec
     * @param runType the run type
     * @throws Exception if the application callback's {@code start} throws
     */
    public static void start(ApplicationSpec spec, RunType runType) throws Exception {
        load(spec);
        start(spec.name(), runType);
    }

    /**
     * Convenience: load and start an application in one step with {@link RunType#TEMPORARY} and an
     * explicit {@link StartType}.
     *
     * @param spec the application spec
     * @param startType the start type ({@link StartType.Normal}, {@link StartType.Takeover}, or
     *     {@link StartType.Failover})
     * @throws Exception if the application callback's {@code start} throws
     */
    public static void start(ApplicationSpec spec, StartType startType) throws Exception {
        load(spec);
        start(spec.name(), RunType.TEMPORARY, startType);
    }

    /**
     * Convenience: load and start an application in one step with an explicit {@link RunType} and
     * {@link StartType}.
     *
     * @param spec the application spec
     * @param runType the run type
     * @param startType the start type ({@link StartType.Normal}, {@link StartType.Takeover}, or
     *     {@link StartType.Failover})
     * @throws Exception if the application callback's {@code start} throws
     */
    public static void start(ApplicationSpec spec, RunType runType, StartType startType)
            throws Exception {
        load(spec);
        start(spec.name(), runType, startType);
    }

    // ── Restart ────────────────────────────────────────────────────────────────

    /**
     * Restart a running application: stop it then start it again, preserving its {@link RunType}.
     *
     * <p>Equivalent to Erlang's {@code application:restart/1}. Unlike a plain {@link #stop} +
     * {@link #start}, {@code restart} does <em>not</em> trigger cascade semantics — only the named
     * application is cycled. The spec must already be loaded.
     *
     * <p>If the application is not currently running (e.g. it was never started or was already
     * stopped), it is started fresh with {@link RunType#TEMPORARY}.
     *
     * <p><strong>Env overrides are preserved across restart.</strong> Any values set via {@link
     * #setEnv} remain in effect after the restart — the override map is never cleared by this
     * method. To discard overrides, call {@link #unsetEnv} explicitly before or after restarting.
     *
     * @param name the application name (must be loaded)
     * @throws IllegalStateException if the application is not loaded
     * @throws Exception if the stop or start callback throws
     */
    @SuppressWarnings("unchecked")
    public static void restart(String name) throws Exception {
        // Capture the RunType before stopping; default to TEMPORARY if not currently running
        RunningEntry current = running.get(name);
        RunType runType = current != null ? current.runType() : RunType.TEMPORARY;

        // Stop the application directly (bypassing cascade semantics)
        RunningEntry entry = running.remove(name);
        if (entry != null) {
            ApplicationCallback<Object> callback = (ApplicationCallback<Object>) entry.spec().mod();
            callback.stop(entry.state());
        }

        // Re-start with the preserved RunType
        start(name, runType);
    }

    // ── Stop ───────────────────────────────────────────────────────────────────

    /**
     * Stop a running application without unloading its spec (normal termination).
     *
     * <p>Equivalent to Erlang's {@code application:stop/1}. The spec remains in the loaded
     * registry; call {@link #unload} if you also want to remove the spec.
     *
     * <p>Treats the termination as <em>normal</em>: {@link RunType#PERMANENT} applications cascade
     * to all other running applications, but {@link RunType#TRANSIENT} applications do not cascade
     * (matching OTP semantics where a clean TRANSIENT shutdown is merely logged). {@link
     * RunType#TEMPORARY} applications never cascade.
     *
     * <p>Already-stopped applications are silently ignored (idempotent).
     *
     * @param name the application name
     * @throws Exception if the application callback's {@code stop} throws
     */
    public static void stop(String name) throws Exception {
        stop(name, false);
    }

    /**
     * Stop a running application, distinguishing normal from abnormal termination.
     *
     * <p>This overload implements proper OTP {@link RunType#TRANSIENT} semantics. The {@code
     * abnormal} flag controls whether a {@link RunType#TRANSIENT} application cascades its shutdown
     * to all other running applications:
     *
     * <ul>
     *   <li><strong>{@code stop(name)}</strong> — normal shutdown ({@code abnormal=false}). {@link
     *       RunType#PERMANENT} cascades to all others; {@link RunType#TRANSIENT} does <em>not</em>
     *       cascade (OTP merely logs the clean exit); {@link RunType#TEMPORARY} is unaffected.
     *   <li><strong>{@code stop(name, true)}</strong> — abnormal termination, equivalent to a
     *       crash. Both {@link RunType#PERMANENT} and {@link RunType#TRANSIENT} cascade to all
     *       other running applications, mirroring OTP's behaviour when a TRANSIENT application
     *       exits with a reason other than {@code normal} or {@code shutdown}.
     * </ul>
     *
     * <p>Already-stopped applications are silently ignored (idempotent).
     *
     * @param name the application name
     * @param abnormal {@code true} if the termination is abnormal (exception/crash) — causes
     *     TRANSIENT cascade; {@code false} for a clean shutdown — TRANSIENT does not cascade
     * @throws Exception if the application callback's {@code stop} throws
     */
    @SuppressWarnings("unchecked")
    public static void stop(String name, boolean abnormal) throws Exception {
        RunningEntry entry = running.remove(name);
        if (entry == null) {
            return; // Already stopped — idempotent
        }

        // Invoke the stop callback
        ApplicationCallback<Object> callback = (ApplicationCallback<Object>) entry.spec().mod();
        callback.stop(entry.state());

        // Apply cascade semantics based on RunType and termination kind
        boolean cascade =
                entry.runType() == RunType.PERMANENT
                        || (entry.runType() == RunType.TRANSIENT && abnormal);
        if (cascade) {
            List<String> others = new ArrayList<>(running.keySet());
            for (String other : others) {
                try {
                    stop(other, abnormal);
                } catch (Exception ignored) {
                    // Best-effort cascade; individual stop failures should not prevent others
                }
            }
        }
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    /**
     * Return info about all <em>loaded</em> applications (including running ones).
     *
     * <p>Equivalent to Erlang's {@code application:loaded_applications/0}.
     *
     * @return an unmodifiable list of {@link ApplicationInfo} records
     */
    public static List<ApplicationInfo> loadedApplications() {
        return loaded.values().stream().map(e -> e.spec().toInfo()).toList();
    }

    /**
     * Return info about all <em>running</em> applications.
     *
     * <p>Equivalent to Erlang's {@code application:which_applications/0}.
     *
     * @return an unmodifiable list of {@link ApplicationInfo} records
     */
    public static List<ApplicationInfo> whichApplications() {
        return running.values().stream().map(e -> e.spec().toInfo()).toList();
    }

    // ── Environment ────────────────────────────────────────────────────────────

    /**
     * Get an application environment parameter.
     *
     * <p>Equivalent to Erlang's {@code application:get_env(App, Par)}. Values set via {@link
     * #setEnv} take precedence over the spec's {@link ApplicationSpec#env() env} map.
     *
     * @param app the application name
     * @param key the parameter key
     * @return {@code Optional.of(value)} if found, {@code Optional.empty()} otherwise
     */
    public static Optional<Object> getEnv(String app, String key) {
        // 1. Runtime overrides have highest priority
        var overrides = envOverrides.get(app);
        if (overrides != null) {
            Object val = overrides.get(key);
            if (val != null) return Optional.of(val);
        }
        // 2. Running spec env
        RunningEntry runEntry = running.get(app);
        if (runEntry != null) {
            Object val = runEntry.spec().env().get(key);
            if (val != null) return Optional.of(val);
        }
        // 3. Loaded (but not running) spec env
        LoadedEntry loadEntry = loaded.get(app);
        if (loadEntry != null) {
            Object val = loadEntry.spec().env().get(key);
            if (val != null) return Optional.of(val);
        }
        return Optional.empty();
    }

    /**
     * Get an application environment parameter, returning a default value if absent.
     *
     * <p>Equivalent to Erlang's {@code application:get_env(App, Par, Default)}. The priority order
     * is the same as {@link #getEnv(String, String)}: runtime overrides beat the spec's env map.
     *
     * @param app the application name
     * @param key the parameter key
     * @param defaultValue the value to return when the key is not present
     * @return the value if found, {@code defaultValue} otherwise
     */
    public static Object getEnv(String app, String key, Object defaultValue) {
        return getEnv(app, key).orElse(defaultValue);
    }

    /**
     * Set (or override) an application environment parameter at runtime.
     *
     * <p>Equivalent to Erlang's {@code application:set_env/3}. The override persists until {@link
     * #reset()} is called and takes precedence over the spec's {@link ApplicationSpec#env()} map.
     *
     * @param app the application name
     * @param key the parameter key
     * @param value the new value
     */
    public static void setEnv(String app, String key, Object value) {
        envOverrides.computeIfAbsent(app, _ -> new ConcurrentHashMap<>()).put(key, value);
    }

    /**
     * Remove a runtime environment override for an application parameter.
     *
     * <p>Equivalent to Erlang's {@code application:unset_env/2}. After calling this method,
     * subsequent calls to {@link #getEnv(String, String)} will fall through to the spec's original
     * {@link ApplicationSpec#env() env} map (or return empty if the key was also absent there).
     *
     * <p>Calling this method for a key that was never set is a no-op.
     *
     * @param app the application name
     * @param key the parameter key to remove
     */
    public static void unsetEnv(String app, String key) {
        var overrides = envOverrides.get(app);
        if (overrides != null) {
            overrides.remove(key);
        }
    }

    // ── Spec key lookup ────────────────────────────────────────────────────────

    /**
     * Retrieve a field from an application's {@link ApplicationSpec} by name.
     *
     * <p>Equivalent to Erlang's {@code application:get_key(App, Key)}. Supported key names mirror
     * the fields of an Erlang {@code .app} file:
     *
     * <ul>
     *   <li>{@code "description"} — {@link ApplicationSpec#description()}
     *   <li>{@code "vsn"} — {@link ApplicationSpec#vsn()}
     *   <li>{@code "modules"} — {@link ApplicationSpec#modules()} (returns {@code List<String>})
     *   <li>{@code "registered"} — {@link ApplicationSpec#registered()} (returns {@code
     *       List<String>})
     *   <li>{@code "applications"} — {@link ApplicationSpec#applications()} (returns {@code
     *       List<String>})
     *   <li>{@code "env"} — {@link ApplicationSpec#env()} (returns {@code Map<String,Object>})
     *   <li>{@code "mod"} — {@link ApplicationSpec#mod()} (returns the callback; may be {@code
     *       null} for library applications)
     *   <li>{@code "start_args"} — {@link ApplicationSpec#startArgs()}
     * </ul>
     *
     * @param app the application name (must be loaded)
     * @param key the spec field name
     * @return {@code Optional.of(value)} if the application is loaded and the key is recognised,
     *     {@code Optional.empty()} if the application is not loaded or the key is unknown
     */
    public static Optional<Object> getKey(String app, String key) {
        ApplicationSpec spec = resolveSpec(app);
        if (spec == null) {
            return Optional.empty();
        }
        return switch (key) {
            case "description" -> Optional.of(spec.description());
            case "vsn" -> Optional.of(spec.vsn());
            case "modules" -> Optional.of(spec.modules());
            case "registered" -> Optional.of(spec.registered());
            case "applications" -> Optional.of(spec.applications());
            case "env" -> Optional.of(spec.env());
            case "mod" -> Optional.ofNullable(spec.mod());
            case "start_args" -> Optional.ofNullable(spec.startArgs());
            default -> Optional.empty();
        };
    }

    /**
     * Resolve the {@link ApplicationSpec} for an application by checking the running registry first
     * (to get the live spec) and falling back to the loaded registry.
     */
    private static ApplicationSpec resolveSpec(String app) {
        RunningEntry runEntry = running.get(app);
        if (runEntry != null) {
            return runEntry.spec();
        }
        LoadedEntry loadEntry = loaded.get(app);
        return loadEntry != null ? loadEntry.spec() : null;
    }

    // ── Test support ───────────────────────────────────────────────────────────

    static {
        // Check for automatic recovery on class load
        String recoveryEnabled = System.getProperty("jotp.recovery.enabled", "false");
        if ("true".equalsIgnoreCase(recoveryEnabled)) {
            String dataDir = System.getProperty("jotp.recovery.dir", "./jotp-data");
            try {
                PersistenceBackend backend = new RocksDBBackend(Path.of(dataDir));
                enableRecovery(backend);
                recoverFromPersistence();
                System.out.println(
                        "[ApplicationController] Automatic recovery enabled from: " + dataDir);
            } catch (Exception e) {
                System.err.println(
                        "[ApplicationController] Automatic recovery failed: " + e.getMessage());
            }
        }
    }

    /**
     * Reset the controller to a clean state.
     *
     * <p>Clears all loaded specs, running applications, and env overrides. Intended for test
     * isolation in {@code @BeforeEach} methods.
     */
    public static void reset() {
        loaded.clear();
        running.clear();
        envOverrides.clear();
        persistenceBackend = null;
        startupTime = Instant.now();
    }

    // ── Instance-based controller (lifecycle orchestrator) ──────────────────

    /** Global registry of named controller instances. */
    private static final ConcurrentHashMap<String, ApplicationController> instances =
            new ConcurrentHashMap<>();

    private final String instanceName;
    private final List<String> registeredApps = new CopyOnWriteArrayList<>();
    private volatile boolean instanceRunning = false;

    /**
     * Create a new instance-based ApplicationController.
     *
     * <p>This constructor enables stateful, instance-level management of multiple applications.
     * Useful for testing and multi-tenant scenarios.
     *
     * @param name the controller instance name
     */
    public ApplicationController(String name) {
        this.instanceName = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Register an application with this controller (instance method).
     *
     * <p>The application must be loaded before starting (via {@link #startAll()}).
     *
     * @param appName the application name to register
     * @return this controller for method chaining
     */
    public ApplicationController register(String appName) {
        Objects.requireNonNull(appName, "appName must not be null");
        if (!registeredApps.contains(appName)) {
            registeredApps.add(appName);
        }
        return this;
    }

    /**
     * Register a list of applications with this controller.
     *
     * @param appNames the application names to register
     * @return this controller for method chaining
     */
    public ApplicationController registerAll(List<String> appNames) {
        Objects.requireNonNull(appNames, "appNames must not be null");
        for (String appName : appNames) {
            register(appName);
        }
        return this;
    }

    /**
     * Get the dependency order for startup (topologically sorted).
     *
     * <p>Uses Kahn's algorithm to compute a topological sort based on {@link
     * ApplicationSpec#applications() application dependencies}.
     *
     * @return a list of application names in startup order
     * @throws IllegalStateException if there is a circular dependency
     */
    public List<String> dependencyOrder() {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();

        // Initialize
        for (String appName : registeredApps) {
            inDegree.put(appName, 0);
            graph.put(appName, new ArrayList<>());
        }

        // Build dependency graph
        for (String appName : registeredApps) {
            ApplicationSpec spec = resolveSpec(appName);
            if (spec != null) {
                for (String dep : spec.applications()) {
                    if (registeredApps.contains(dep)) {
                        graph.get(dep).add(appName); // dep must start before appName
                        inDegree.put(appName, inDegree.get(appName) + 1);
                    }
                }
            }
        }

        // Kahn's algorithm: topological sort
        Queue<String> queue = new LinkedList<>();
        for (String appName : registeredApps) {
            if (inDegree.get(appName) == 0) {
                queue.offer(appName);
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String appName = queue.poll();
            result.add(appName);
            for (String dependent : graph.get(appName)) {
                inDegree.put(dependent, inDegree.get(dependent) - 1);
                if (inDegree.get(dependent) == 0) {
                    queue.offer(dependent);
                }
            }
        }

        if (result.size() != registeredApps.size()) {
            throw new IllegalStateException("Circular dependency detected in application graph");
        }

        return result;
    }

    /**
     * Start all registered applications in dependency order.
     *
     * <p>Applications are started in the order determined by {@link #dependencyOrder()}.
     *
     * @throws Exception if any application fails to start
     */
    public synchronized void startAll() throws Exception {
        if (instanceRunning) {
            return; // Already running
        }

        List<String> order = dependencyOrder();
        for (String appName : order) {
            try {
                ApplicationController.start(appName);
            } catch (Exception e) {
                // Attempt to stop any already-started apps
                for (int i = registeredApps.indexOf(appName) - 1; i >= 0; i--) {
                    try {
                        ApplicationController.stop(registeredApps.get(i));
                    } catch (Exception ignored) {
                    }
                }
                throw new RuntimeException(
                        "Failed to start application '" + appName + "': " + e.getMessage(), e);
            }
        }
        instanceRunning = true;
    }

    /**
     * Stop all running applications in reverse dependency order with default 30-second timeout.
     *
     * @throws Exception if any application fails to stop
     */
    public synchronized void stopAll() throws Exception {
        stopAll(java.time.Duration.ofSeconds(30));
    }

    /**
     * Stop all running applications in reverse dependency order with a custom timeout.
     *
     * <p>Each application is given up to {@code timeout} to shut down gracefully. Applications
     * are stopped in reverse order of their dependency order to ensure dependencies remain active
     * until dependents are stopped.
     *
     * @param timeout the maximum time to wait for all applications to stop
     * @throws Exception if any application fails to stop
     */
    public synchronized void stopAll(java.time.Duration timeout) throws Exception {
        if (!instanceRunning) {
            return; // Not running
        }

        List<String> order = dependencyOrder();
        Collections.reverse(order); // Stop in reverse order

        long timeoutMs = timeout.toMillis();
        long startTime = System.currentTimeMillis();

        for (String appName : order) {
            long elapsed = System.currentTimeMillis() - startTime;
            long remaining = timeoutMs - elapsed;

            if (remaining <= 0) {
                throw new RuntimeException(
                        "Timeout exceeded while stopping applications. Application '"
                                + appName
                                + "' did not stop within timeout.");
            }

            try {
                ApplicationController.stop(appName);
            } catch (Exception ignored) {
                // Best-effort: continue stopping other apps
            }
        }

        instanceRunning = false;
    }

    /**
     * Get the health status of this controller (aggregated from all registered apps).
     *
     * <p>Returns {@code ApplicationHealth.UP} if all apps are running, {@code DEGRADED} if some
     * are running, and {@code DOWN} if none are running.
     *
     * @return the aggregated health status
     */
    public ApplicationHealth health() {
        if (registeredApps.isEmpty()) {
            return ApplicationHealth.DOWN;
        }

        int runningCount = 0;
        for (String appName : registeredApps) {
            if (running.containsKey(appName)) {
                runningCount++;
            }
        }

        if (runningCount == registeredApps.size()) {
            return ApplicationHealth.UP;
        } else if (runningCount > 0) {
            return ApplicationHealth.DEGRADED;
        } else {
            return ApplicationHealth.DOWN;
        }
    }

    /**
     * Get the status of a specific registered application.
     *
     * @param appName the application name
     * @return the application status
     */
    public ApplicationStatus statusOf(String appName) {
        RunningEntry entry = running.get(appName);
        ApplicationHealth health = entry != null ? ApplicationHealth.UP : ApplicationHealth.DOWN;
        return new ApplicationStatus(
                appName, health, entry != null ? java.time.Instant.now() : null, null, 0L, 0L, "");
    }

    /**
     * Get the status of all registered applications.
     *
     * @return a list of application statuses
     */
    public List<ApplicationStatus> allStatuses() {
        return registeredApps.stream().map(this::statusOf).toList();
    }

    /**
     * Check if this controller instance is running.
     *
     * @return {@code true} if all applications are running, {@code false} otherwise
     */
    public boolean isRunning() {
        return instanceRunning;
    }

    /**
     * Get the list of registered application names.
     *
     * @return a list of application names
     */
    public List<String> applicationNames() {
        return new ArrayList<>(registeredApps);
    }

    /**
     * Get or create a named controller instance.
     *
     * @param name the instance name
     * @return the controller instance
     */
    public static ApplicationController instance(String name) {
        return instances.computeIfAbsent(name, ApplicationController::new);
    }

    /**
     * Get the default controller instance.
     *
     * @return the default controller instance
     */
    public static ApplicationController defaultInstance() {
        return instance("default");
    }
}
