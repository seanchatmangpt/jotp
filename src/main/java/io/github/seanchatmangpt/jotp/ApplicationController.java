package io.github.seanchatmangpt.jotp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central coordinator for JOTP applications — the {@code application} module in Java.
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

    private record LoadedEntry(ApplicationSpec spec) {}

    private record RunningEntry(ApplicationSpec spec, RunType runType, Object state) {}

    private ApplicationController() {}

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
     * <p>This overload implements proper OTP {@link RunType#TRANSIENT} semantics:
     *
     * <ul>
     *   <li>{@code abnormal=false} (normal stop): {@link RunType#PERMANENT} cascades; {@link
     *       RunType#TRANSIENT} is logged only (no cascade); {@link RunType#TEMPORARY} is
     *       unaffected.
     *   <li>{@code abnormal=true} (abnormal stop): both {@link RunType#PERMANENT} and {@link
     *       RunType#TRANSIENT} cascade to all other running applications.
     * </ul>
     *
     * <p>Already-stopped applications are silently ignored (idempotent).
     *
     * @param name the application name
     * @param abnormal {@code true} if the termination is abnormal (exception/crash); {@code false}
     *     for a clean shutdown
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
    }
}
