package io.github.seanchatmangpt.jotp;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * High-level lifecycle orchestrator for coordinating multiple processes and supervisors.
 *
 * <p>An {@code Application} manages the startup and shutdown of a hierarchical system of processes
 * and supervisors. It provides:
 *
 * <ul>
 *   <li><strong>Application State:</strong> A single immutable state object (type {@code S}) shared
 *       across the application lifecycle
 *   <li><strong>Lifecycle Phases:</strong> INIT → START → RUNNING → STOP with hooks at each phase
 *   <li><strong>Named Services:</strong> Register processes by name for easy lookup
 *   <li><strong>Supervision Trees:</strong> Integrate supervisors with restart strategies
 *   <li><strong>Graceful Shutdown:</strong> Stop services first, then supervisors, with cleanup
 *       hooks
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 *
 * <pre>{@code
 * Application app = Application.builder("my-app")
 *     .supervisorStrategy(Supervisor.Strategy.ONE_FOR_ONE)
 *     .maxRestarts(5)
 *     .restartWindow(Duration.ofMinutes(1))
 *     .service("processor", () -> new ProcessorState(), this::handleMessage)
 *     .infrastructure(messageBus)
 *     .config(ApplicationConfig.create().environment("test").build())
 *     .build();
 *
 * app.start();
 * Optional<ProcRef<State, Msg>> svc = app.service("processor");
 * app.stop();
 * }</pre>
 *
 * @see Proc
 * @see ProcRef
 * @see Supervisor
 */
public final class Application<S> {

    /**
     * Marker interface for infrastructure components that integrate with the application lifecycle.
     *
     * <p>Implement this interface to register infrastructure components (e.g., message buses,
     * connection pools) that need a name and graceful shutdown callback.
     */
    public interface Infrastructure {
        /** Returns the name of this infrastructure component. */
        String name();

        /**
         * Called when the application stops.
         *
         * @param app the application that is stopping
         */
        void onStop(Application<?> app);
    }

    /** Sealed hierarchy of application lifecycle phases. */
    public sealed interface ApplicationPhase {
        /** Initializing: running init hooks and preparing state. */
        record INIT() implements ApplicationPhase {}

        /** Starting services and supervisors. */
        record START() implements ApplicationPhase {}

        /** Running normally; all services active. */
        record RUNNING() implements ApplicationPhase {}

        /** Stopping: shutting down services and supervisors. */
        record STOP() implements ApplicationPhase {}

        /**
         * Stopped: shutdown complete.
         *
         * @param error non-null if shutdown failed unexpectedly
         */
        record STOPPED(Throwable error) implements ApplicationPhase {}
    }

    private final String name;
    private final Supplier<S> initializer;
    private final Consumer<S> stopper;
    private final List<ServiceEntry<?>> services = new CopyOnWriteArrayList<>();
    private final List<SupervisorEntry> supervisors = new CopyOnWriteArrayList<>();
    private final List<Runnable> initHooks = new CopyOnWriteArrayList<>();
    private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
    private volatile ApplicationPhase phase = new ApplicationPhase.INIT();
    private volatile S state;

    // Enterprise fields
    private final Supervisor.Strategy supervisorStrategy;
    private final int maxRestarts;
    private final Duration restartWindow;
    private final List<DeferredService<?, ?>> deferredServices;
    private final List<Infrastructure> infrastructureComponents;
    private final ApplicationConfig config;
    private final List<HealthChecker> healthCheckers;
    private volatile Supervisor rootSupervisor;
    private final ConcurrentHashMap<String, ProcRef<?, ?>> startedServices =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Infrastructure> infrastructureMap =
            new ConcurrentHashMap<>();

    private Application(
            String name,
            Supplier<S> initializer,
            Consumer<S> stopper,
            List<ServiceEntry<?>> services,
            List<SupervisorEntry> supervisors,
            List<Runnable> initHooks,
            List<Runnable> shutdownHooks,
            Supervisor.Strategy supervisorStrategy,
            int maxRestarts,
            Duration restartWindow,
            List<DeferredService<?, ?>> deferredServices,
            List<Infrastructure> infrastructureComponents,
            ApplicationConfig config,
            List<HealthChecker> healthCheckers) {
        this.name = name;
        this.initializer = initializer;
        this.stopper = stopper;
        this.services.addAll(services);
        this.supervisors.addAll(supervisors);
        this.initHooks.addAll(initHooks);
        this.shutdownHooks.addAll(shutdownHooks);
        this.supervisorStrategy = supervisorStrategy;
        this.maxRestarts = maxRestarts;
        this.restartWindow = restartWindow;
        this.deferredServices = deferredServices;
        this.infrastructureComponents = infrastructureComponents;
        this.config = config;
        this.healthCheckers = healthCheckers;
    }

    // ── Enterprise lifecycle methods ─────────────────────────────────────────────

    /** Get the application name. */
    public String name() {
        return name;
    }

    /** Check if the application has been started (and not yet stopped). */
    public boolean isStarted() {
        return phase instanceof ApplicationPhase.RUNNING;
    }

    /** Check if the application is currently running. */
    public boolean isRunning() {
        return phase instanceof ApplicationPhase.RUNNING;
    }

    /**
     * Start the application: create supervisor, start services, transition to RUNNING.
     *
     * <p>This creates a root supervisor with the configured strategy and starts all registered
     * services under it. Infrastructure components are indexed for lookup.
     */
    public CompletableFuture<S> start() {
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        // INIT phase
                        phase = new ApplicationPhase.INIT();
                        for (var hook : initHooks) {
                            hook.run();
                        }

                        // Initialize state
                        state = initializer.get();

                        // START phase
                        phase = new ApplicationPhase.START();

                        // Create root supervisor for deferred services
                        if (!deferredServices.isEmpty()) {
                            rootSupervisor =
                                    Supervisor.create(
                                            name + "-sup",
                                            supervisorStrategy,
                                            maxRestarts,
                                            restartWindow);

                            for (DeferredService<?, ?> ds : deferredServices) {
                                ProcRef<?, ?> ref = startDeferredService(ds);
                                startedServices.put(ds.name(), ref);
                            }
                        }

                        // Index infrastructure components
                        for (Infrastructure infra : infrastructureComponents) {
                            infrastructureMap.put(infra.name(), infra);
                        }

                        // Start supervisors (they manage their own children)
                        for (var supervisor : supervisors) {
                            // Supervisor is already running (started in builder), just track it
                        }

                        // Transition to RUNNING
                        phase = new ApplicationPhase.RUNNING();

                        return state;
                    } catch (Exception e) {
                        phase = new ApplicationPhase.STOPPED(e);
                        throw new RuntimeException("Application startup failed", e);
                    }
                });
    }

    /** Start the application synchronously (blocks until started). */
    public void startSync() {
        start().join();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <SS, MM> ProcRef<SS, MM> startDeferredService(DeferredService<SS, MM> ds) {
        return rootSupervisor.supervise(ds.name(), (SS) ds.stateFactory().get(), ds.handler());
    }

    /**
     * Stop the application: gracefully shut down all services and supervisors.
     *
     * <p>This method stops services, infrastructure, supervisors, and runs cleanup hooks.
     */
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        phase = new ApplicationPhase.STOP();

                        // Stop all started services via supervisor
                        if (rootSupervisor != null) {
                            try {
                                rootSupervisor.shutdown();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        // Stop infrastructure
                        for (Infrastructure infra : infrastructureComponents) {
                            try {
                                infra.onStop(this);
                            } catch (Exception e) {
                                // Log but continue shutdown
                            }
                        }

                        // Stop health checkers
                        for (HealthChecker hc : healthCheckers) {
                            try {
                                hc.onStop(this);
                            } catch (Exception e) {
                                // Log but continue shutdown
                            }
                        }

                        // Stop all legacy services in reverse order
                        List<ServiceEntry<?>> servicesCopy = new ArrayList<>(services);
                        Collections.reverse(servicesCopy);
                        for (var serviceEntry : servicesCopy) {
                            try {
                                serviceEntry.ref.stop();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        // Shutdown all supervisors in reverse order
                        List<SupervisorEntry> supervisorsCopy = new ArrayList<>(supervisors);
                        Collections.reverse(supervisorsCopy);
                        for (var supervisorEntry : supervisorsCopy) {
                            try {
                                supervisorEntry.supervisor.shutdown();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }

                        // Run shutdown hooks
                        if (stopper != null && state != null) {
                            stopper.accept(state);
                        }
                        for (var hook : shutdownHooks) {
                            hook.run();
                        }

                        startedServices.clear();
                        infrastructureMap.clear();

                        phase = new ApplicationPhase.STOPPED(null);
                    } catch (Exception e) {
                        phase = new ApplicationPhase.STOPPED(e);
                        throw new RuntimeException("Application shutdown failed", e);
                    }
                });
    }

    /** Stop the application synchronously (blocks until stopped). */
    public void stopSync() {
        stop().join();
    }

    /**
     * Get the current application state.
     *
     * @return the state object, or {@code null} if not yet initialized
     */
    public S getState() {
        return state;
    }

    /**
     * Get the current application phase.
     *
     * @return the current {@link ApplicationPhase}
     */
    public ApplicationPhase getPhase() {
        return phase;
    }

    /**
     * Lookup a named service by name (typed).
     *
     * @param serviceName the service name
     * @return an {@link Optional} containing the typed {@link ProcRef} if found
     */
    @SuppressWarnings("unchecked")
    public <SS, MM> Optional<ProcRef<SS, MM>> service(String serviceName) {
        ProcRef<?, ?> ref = startedServices.get(serviceName);
        if (ref != null) {
            return Optional.of((ProcRef<SS, MM>) ref);
        }
        // Fall back to legacy services
        for (var entry : services) {
            if (entry.name.equals(serviceName)) {
                return Optional.of((ProcRef<SS, MM>) entry.ref);
            }
        }
        return Optional.empty();
    }

    /**
     * Lookup a named service by name (untyped, for backward compatibility).
     *
     * @param serviceName the service name
     * @return an {@link Optional} containing the {@link ProcRef} if found, or empty otherwise
     */
    public Optional<ProcRef<?, ?>> getService(String serviceName) {
        return service(serviceName);
    }

    /**
     * Lookup an infrastructure component by name.
     *
     * @param infraName the infrastructure component name
     * @return an {@link Optional} containing the {@link Infrastructure} if found
     */
    public Optional<Infrastructure> infrastructure(String infraName) {
        return Optional.ofNullable(infrastructureMap.get(infraName));
    }

    /**
     * Get the application configuration.
     *
     * @return the configuration, or a default empty config
     */
    public ApplicationConfig config() {
        return config != null ? config : ApplicationConfig.empty();
    }

    // ── Builder factory methods ─────────────────────────────────────────────────

    /**
     * Create a fluent builder for an application (non-generic enterprise mode).
     *
     * @param appName the application name
     * @return a new {@link EnterpriseBuilder}
     */
    public static EnterpriseBuilder builder(String appName) {
        return new EnterpriseBuilder(appName);
    }

    // ── Enterprise Builder ──────────────────────────────────────────────────────

    /** Fluent builder for enterprise {@link Application} instances. */
    public static final class EnterpriseBuilder {
        private final String name;
        private Supervisor.Strategy strategy = Supervisor.Strategy.ONE_FOR_ONE;
        private int maxRestarts = 5;
        private Duration restartWindow = Duration.ofMinutes(1);
        private final List<DeferredService<?, ?>> deferredServices = new ArrayList<>();
        private final List<Infrastructure> infrastructureComponents = new ArrayList<>();
        private ApplicationConfig config = ApplicationConfig.empty();
        private final List<HealthChecker> healthCheckers = new ArrayList<>();
        private final List<ServiceEntry<?>> legacyServices = new ArrayList<>();
        private final List<SupervisorEntry> legacySupervisors = new ArrayList<>();
        private final List<Runnable> initHooks = new ArrayList<>();
        private final List<Runnable> shutdownHooks = new ArrayList<>();

        EnterpriseBuilder(String name) {
            this.name = name;
        }

        /** Set the supervisor restart strategy. */
        public EnterpriseBuilder supervisorStrategy(Supervisor.Strategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /** Set maximum restarts before the supervisor gives up. */
        public EnterpriseBuilder maxRestarts(int maxRestarts) {
            this.maxRestarts = maxRestarts;
            return this;
        }

        /** Set the restart window for counting restarts. */
        public EnterpriseBuilder restartWindow(Duration window) {
            this.restartWindow = window;
            return this;
        }

        /**
         * Register a service with a state factory and message handler.
         *
         * <p>The service will be started under the root supervisor when the application starts.
         *
         * @param serviceName unique service name
         * @param stateFactory supplier creating the initial state
         * @param handler message handler function
         * @return this builder
         */
        public <SS, MM> EnterpriseBuilder service(
                String serviceName,
                Supplier<SS> stateFactory,
                BiFunction<SS, MM, SS> handler) {
            deferredServices.add(new DeferredService<>(serviceName, stateFactory, handler));
            return this;
        }

        /**
         * Register a named service (already-created process reference).
         *
         * @param serviceName the service name for lookup
         * @param procRef the process reference
         * @return this builder
         */
        public EnterpriseBuilder service(String serviceName, ProcRef<?, ?> procRef) {
            legacyServices.add(new ServiceEntry<>(serviceName, procRef));
            return this;
        }

        /** Register an infrastructure component (message bus, event store, etc.). */
        public EnterpriseBuilder infrastructure(Infrastructure infra) {
            infrastructureComponents.add(infra);
            return this;
        }

        /** Set the application configuration. */
        public EnterpriseBuilder config(ApplicationConfig config) {
            this.config = config;
            return this;
        }

        /** Register a health checker. */
        public EnterpriseBuilder healthCheck(HealthChecker healthChecker) {
            healthCheckers.add(healthChecker);
            return this;
        }

        /** Register a supervisor. */
        public EnterpriseBuilder supervisor(Supervisor supervisor) {
            legacySupervisors.add(new SupervisorEntry(supervisor));
            return this;
        }

        /** Add an initialization hook. */
        public EnterpriseBuilder addInitHook(Runnable hook) {
            initHooks.add(hook);
            return this;
        }

        /** Add a shutdown hook. */
        public EnterpriseBuilder addShutdownHook(Runnable hook) {
            shutdownHooks.add(hook);
            return this;
        }

        /**
         * Build the application instance.
         *
         * @return a new {@link Application} configured with the builder settings
         */
        @SuppressWarnings("unchecked")
        public Application<Void> build() {
            return new Application<>(
                    name,
                    () -> null,
                    _ -> {},
                    legacyServices,
                    legacySupervisors,
                    initHooks,
                    shutdownHooks,
                    strategy,
                    maxRestarts,
                    restartWindow,
                    deferredServices,
                    infrastructureComponents,
                    config,
                    healthCheckers);
        }
    }

    // ── Deferred service record ─────────────────────────────────────────────────

    /** A service definition that will be started when the application starts. */
    private record DeferredService<S, M>(
            String name, Supplier<S> stateFactory, BiFunction<S, M, S> handler) {}

    /** Internal entry for named services. */
    private static final class ServiceEntry<M> {
        final String name;
        final ProcRef<?, M> ref;

        ServiceEntry(String name, ProcRef<?, M> ref) {
            this.name = name;
            this.ref = ref;
        }
    }

    /** Internal entry for supervisors. */
    private static final class SupervisorEntry {
        final Supervisor supervisor;

        SupervisorEntry(Supervisor supervisor) {
            this.supervisor = supervisor;
        }
    }
}
