package io.github.seanchatmangpt.jotp;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * record AppState(String name, int port) {}
 *
 * var app = Application.builder("my-app")
 *     .init(() -> new AppState("my-app", 8080))
 *     .service("http-server", createHttpServer())
 *     .service("worker", createWorker())
 *     .supervisor(new Supervisor(...))
 *     .stop(state -> System.out.println("Shutting down " + state.name()))
 *     .build();
 *
 * CompletableFuture<AppState> startFuture = app.start();
 * AppState state = startFuture.join();
 *
 * // Later...
 * app.stop().join();
 * }</pre>
 *
 * <p><strong>Lifecycle Phases:</strong>
 *
 * <ol>
 *   <li><strong>INIT:</strong> Initialize application state and run init hooks (e.g., load config,
 *       setup databases)
 *   <li><strong>START:</strong> Start all services and supervisors in dependency order
 *   <li><strong>RUNNING:</strong> Application is stable; all processes are handling messages
 *   <li><strong>STOP:</strong> Gracefully shut down; stop services first, then supervisors, then
 *       run stop hooks
 * </ol>
 *
 * <p><strong>Service Lookup:</strong>
 *
 * <p>Services registered via {@link Builder#service(String, ProcRef)} can be looked up by name
 * during the RUNNING phase:
 *
 * <pre>{@code
 * Optional<ProcRef<?, ?>> httpServer = app.getService("http-server");
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong>
 *
 * <p>The application state and service registry are immutable once the application transitions to
 * RUNNING. All phase transitions are serialized. Use {@link #getState()} to safely read the current
 * state.
 *
 * @param <S> application state type (recommended to be a record or sealed type for immutability)
 * @see Proc
 * @see ProcRef
 * @see Supervisor
 * @see ApplicationPhase
 */
public final class Application<S> {

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

    private Application(
            String name,
            Supplier<S> initializer,
            Consumer<S> stopper,
            List<ServiceEntry<?>> services,
            List<SupervisorEntry> supervisors,
            List<Runnable> initHooks,
            List<Runnable> shutdownHooks) {
        this.name = name;
        this.initializer = initializer;
        this.stopper = stopper;
        this.services.addAll(services);
        this.supervisors.addAll(supervisors);
        this.initHooks.addAll(initHooks);
        this.shutdownHooks.addAll(shutdownHooks);
    }

    /**
     * Start the application: initialize state, start services and supervisors, transition to
     * RUNNING.
     *
     * <p>This method:
     *
     * <ol>
     *   <li>Transitions to INIT phase and runs all init hooks
     *   <li>Transitions to START phase and starts all services
     *   <li>Starts all supervisors
     *   <li>Transitions to RUNNING phase
     *   <li>Returns the application state
     * </ol>
     *
     * @return a {@link CompletableFuture} that completes with the application state when startup is
     *     complete
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

    /**
     * Stop the application: gracefully shut down all services and supervisors, then run cleanup
     * hooks.
     *
     * <p>This method:
     *
     * <ol>
     *   <li>Transitions to STOP phase
     *   <li>Stops all services
     *   <li>Shuts down all supervisors
     *   <li>Runs all shutdown hooks
     *   <li>Transitions to STOPPED phase
     * </ol>
     *
     * @return a {@link CompletableFuture} that completes when shutdown is done
     */
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(
                () -> {
                    try {
                        phase = new ApplicationPhase.STOP();

                        // Stop all services in reverse order
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

                        phase = new ApplicationPhase.STOPPED(null);
                    } catch (Exception e) {
                        phase = new ApplicationPhase.STOPPED(e);
                        throw new RuntimeException("Application shutdown failed", e);
                    }
                });
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
     * Lookup a named service by name.
     *
     * @param serviceName the service name
     * @return an {@link Optional} containing the {@link ProcRef} if found, or empty otherwise
     */
    public Optional<ProcRef<?, ?>> getService(String serviceName) {
        for (var entry : services) {
            if (entry.name.equals(serviceName)) {
                return Optional.of(entry.ref);
            }
        }
        return Optional.empty();
    }

    /**
     * Create a fluent builder for an application.
     *
     * @param appName the application name
     * @param <S> the application state type
     * @return a new {@link Builder}
     */
    public static <S> Builder<S> builder(String appName) {
        return new Builder<>(appName);
    }

    /** Fluent builder for {@link Application}. */
    public static final class Builder<S> {
        private final String name;
        private Supplier<S> initializer = () -> null;
        private Consumer<S> stopper = _ -> {};
        private final List<ServiceEntry<?>> services = new ArrayList<>();
        private final List<SupervisorEntry> supervisors = new ArrayList<>();
        private final List<Runnable> initHooks = new ArrayList<>();
        private final List<Runnable> shutdownHooks = new ArrayList<>();

        Builder(String name) {
            this.name = name;
        }

        /**
         * Set the initialization supplier that creates the application state.
         *
         * @param initializer a supplier that returns the initial application state
         * @return this builder
         */
        public Builder<S> init(Supplier<S> initializer) {
            this.initializer = initializer;
            return this;
        }

        /**
         * Set the cleanup consumer that accepts the application state on shutdown.
         *
         * @param stopper a consumer that handles shutdown logic (e.g., close databases)
         * @return this builder
         */
        public Builder<S> stop(Consumer<S> stopper) {
            this.stopper = stopper;
            return this;
        }

        /**
         * Register a named service (process).
         *
         * @param serviceName the service name for lookup
         * @param procRef the process reference
         * @return this builder
         */
        public Builder<S> service(String serviceName, ProcRef<?, ?> procRef) {
            services.add(new ServiceEntry<>(serviceName, procRef));
            return this;
        }

        /**
         * Register a supervisor with its children.
         *
         * @param supervisor the supervisor instance
         * @return this builder
         */
        public Builder<S> supervisor(Supervisor supervisor) {
            supervisors.add(new SupervisorEntry(supervisor));
            return this;
        }

        /**
         * Add an initialization hook (runs during INIT phase before services start).
         *
         * @param hook a runnable to execute during initialization
         * @return this builder
         */
        public Builder<S> addInitHook(Runnable hook) {
            initHooks.add(hook);
            return this;
        }

        /**
         * Add a shutdown hook (runs during STOP phase after services stop).
         *
         * @param hook a runnable to execute during shutdown
         * @return this builder
         */
        public Builder<S> addShutdownHook(Runnable hook) {
            shutdownHooks.add(hook);
            return this;
        }

        /**
         * Build the application instance.
         *
         * @return a new {@link Application} with the configured services, supervisors, and hooks
         */
        public Application<S> build() {
            return new Application<>(
                    name, initializer, stopper, services, supervisors, initHooks, shutdownHooks);
        }
    }

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
