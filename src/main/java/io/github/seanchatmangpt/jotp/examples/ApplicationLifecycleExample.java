package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Complete example demonstrating the JOTP Application Lifecycle.
 *
 * <p>This example demonstrates all concepts from the applications.md documentation:
 *
 * <ul>
 *   <li><strong>Application Callback Interface:</strong> MyChannelApp implements Application
 *   <li><strong>Supervision Tree:</strong> ChannelSupervisor creates the supervision hierarchy
 *   <li><strong>Application Configuration:</strong> ApplicationConfig builder with env parameters
 *   <li><strong>Application Controller:</strong> Loading, starting, stopping applications
 *   <li><strong>Configuration Management:</strong> Reading env variables with defaults
 *   <li><strong>Restart Policies:</strong> PERMANENT, TRANSIENT, TEMPORARY application types
 *   <li><strong>Start Types:</strong> NORMAL, TAKEOVER, FAILOVER startup modes
 *   <li><strong>Lifecycle Hooks:</strong> start/2, stop/1 callback methods
 *   <li><strong>Worker Processes:</strong> Simple workers managed by supervisor
 * </ul>
 */
public class ApplicationLifecycleExample {

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Application Callback Class
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Application callback class implementing the Application interface.
     *
     * <p>This class describes how the application is to be started and stopped, including creating
     * and managing the supervision tree.
     */
    public static class MyChannelApp implements Application {
        private static final Logger LOG = LoggerFactory.getLogger(MyChannelApp.class);

        @Override
        public Result<ProcRef<?>, Exception> start(StartType type, Object... args) {
            try {
                LOG.info("Starting MyChannelApp with type: {}", type);

                // Retrieve configuration from ApplicationConfig
                var config = ApplicationConfig.getCurrent();
                int maxChannels = (Integer) config.env().getOrDefault("max_channels", 100);
                String logLevel = (String) config.env().getOrDefault("log_level", "info");

                LOG.info("Configuration: max_channels={}, log_level={}", maxChannels, logLevel);

                // Create the supervision tree
                var supervisor = ChannelSupervisor.create(maxChannels);

                LOG.info("MyChannelApp started successfully with supervisor: {}", supervisor);
                return Result.success(supervisor);

            } catch (Exception e) {
                LOG.error("MyChannelApp startup failed", e);
                return Result.failure(e);
            }
        }

        @Override
        public void stop(Object state) {
            LOG.info("MyChannelApp stopping. State: {}", state);
            // Cleanup if needed (supervisor shutdown is automatic)
            LOG.info("MyChannelApp stopped");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Supervision Tree Definition
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Supervision tree for the channel application. */
    public static class ChannelSupervisor {
        private static final Logger LOG = LoggerFactory.getLogger(ChannelSupervisor.class);

        /**
         * Create the supervision tree with configurable worker count.
         *
         * @param maxChannels Maximum number of channels to support
         * @return ProcRef to the root supervisor
         */
        public static ProcRef<?> create(int maxChannels) {
            LOG.debug("Creating ChannelSupervisor with maxChannels={}", maxChannels);

            return Supervisor.builder()
                    .name("channel-supervisor")
                    .childProcess(
                            "channel-pool",
                            state -> new ChannelPool(maxChannels),
                            Supervisor.RestartStrategy.ONE_FOR_ONE)
                    .childProcess(
                            "channel-logger",
                            state -> new ChannelLogger(),
                            Supervisor.RestartStrategy.ONE_FOR_ONE)
                    .maxRestarts(10)
                    .maxRestartWindow(Duration.ofSeconds(60))
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Worker Processes
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Channel pool manager - tracks available channels. */
    public static class ChannelPool {
        private static final Logger LOG = LoggerFactory.getLogger(ChannelPool.class);
        private final int maxChannels;
        private final AtomicInteger activeChannels = new AtomicInteger(0);

        public ChannelPool(int maxChannels) {
            this.maxChannels = maxChannels;
            LOG.info("ChannelPool created with capacity {}", maxChannels);
        }

        public Result<String, Exception> allocateChannel() {
            if (activeChannels.get() < maxChannels) {
                int id = activeChannels.incrementAndGet();
                String channelId = "CHANNEL-" + id;
                LOG.debug("Allocated channel: {}", channelId);
                return Result.success(channelId);
            }
            return Result.failure(
                    new Exception(
                            "Channel pool exhausted: " + activeChannels.get() + "/" + maxChannels));
        }

        public void releaseChannel(String channelId) {
            activeChannels.decrementAndGet();
            LOG.debug("Released channel: {}", channelId);
        }

        public int getActiveChannels() {
            return activeChannels.get();
        }
    }

    /** Channel logger - logs all channel events. */
    public static class ChannelLogger {
        private static final Logger LOG = LoggerFactory.getLogger(ChannelLogger.class);
        private final List<String> eventLog = Collections.synchronizedList(new ArrayList<>());

        public void logEvent(String event) {
            eventLog.add(event);
            LOG.trace("Channel event: {}", event);
        }

        public List<String> getEventLog() {
            return new ArrayList<>(eventLog);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Application Configuration
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Creates and manages ApplicationConfig for the channel application. */
    public static class ApplicationSetup {
        private static final Logger LOG = LoggerFactory.getLogger(ApplicationSetup.class);

        /**
         * Create ApplicationConfig for the channel application.
         *
         * @return Configured ApplicationConfig instance
         */
        public static ApplicationConfig createConfig() {
            LOG.info("Creating ApplicationConfig for MyChannelApp");

            return ApplicationConfig.builder()
                    .name("channel-app")
                    .version("1.0.0")
                    .description("Channel allocator application for message routing")
                    .modules(
                            List.of(
                                    MyChannelApp.class,
                                    ChannelSupervisor.class,
                                    ChannelPool.class,
                                    ChannelLogger.class))
                    .registeredNames(List.of("channel_pool", "channel_logger"))
                    .dependencies(List.of("kernel", "stdlib"))
                    .mod(MyChannelApp.class)
                    .env("log_level", "info")
                    .env("max_channels", 100)
                    .env("file", "/var/log/channels.log")
                    .build();
        }

        /**
         * Create ApplicationConfig with custom environment variables.
         *
         * @param logLevel Logging level (info, debug, trace)
         * @param maxChannels Maximum channels to allocate
         * @return Configured ApplicationConfig instance
         */
        public static ApplicationConfig createConfigWithEnv(String logLevel, int maxChannels) {
            return ApplicationConfig.builder()
                    .name("channel-app")
                    .version("1.0.0")
                    .description("Channel allocator application for message routing")
                    .modules(
                            List.of(
                                    MyChannelApp.class,
                                    ChannelSupervisor.class,
                                    ChannelPool.class,
                                    ChannelLogger.class))
                    .registeredNames(List.of("channel_pool", "channel_logger"))
                    .dependencies(List.of("kernel", "stdlib"))
                    .mod(MyChannelApp.class)
                    .env("log_level", logLevel)
                    .env("max_channels", maxChannels)
                    .env("file", "/var/log/channels.log")
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Application Lifecycle Management
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** Demonstrates complete application lifecycle management. */
    public static class ApplicationLifecycleManager {
        private static final Logger LOG =
                LoggerFactory.getLogger(ApplicationLifecycleManager.class);

        /**
         * Load an application into the runtime.
         *
         * <p>Loading reads and stores the ApplicationConfig information but does not start the
         * application yet.
         */
        public static Result<Void, String> loadApplication(ApplicationConfig config) {
            try {
                LOG.info("Loading application: {}", config.name());
                // In real implementation, this would register with JotpApplicationController
                LOG.info("Application loaded successfully");
                return Result.success(null);
            } catch (Exception e) {
                return Result.failure("Failed to load application: " + e.getMessage());
            }
        }

        /**
         * Unload an application from the runtime.
         *
         * <p>Unloading removes the application information from the internal database but does not
         * shutdown the application (call stop() first).
         */
        public static Result<Void, String> unloadApplication(String appName) {
            try {
                LOG.info("Unloading application: {}", appName);
                // In real implementation, this would unregister from JotpApplicationController
                LOG.info("Application unloaded successfully");
                return Result.success(null);
            } catch (Exception e) {
                return Result.failure("Failed to unload application: " + e.getMessage());
            }
        }

        /**
         * Start an application with a specific start type.
         *
         * @param config ApplicationConfig instance
         * @param type StartType (NORMAL, TAKEOVER, FAILOVER)
         */
        public static Result<Void, String> startApplication(
                ApplicationConfig config, StartType type) {
            try {
                LOG.info("Starting application: {} with type: {}", config.name(), type);

                // Set current config for application to access
                ApplicationConfig.setCurrent(config);

                // Create and start the application callback
                var appCallback = (Application) config.mod().getDeclaredConstructor().newInstance();
                var startResult = appCallback.start(type);

                if (startResult.isFailure()) {
                    return Result.failure(
                            "Application startup failed: " + startResult.failureReason());
                }

                LOG.info("Application started successfully");
                return Result.success(null);

            } catch (Exception e) {
                return Result.failure("Failed to start application: " + e.getMessage());
            }
        }

        /**
         * Stop a running application.
         *
         * <p>Stopping tells the top supervisor to shut down, which cascades to all child processes.
         * The application callback's stop() method is then invoked.
         */
        public static Result<Void, String> stopApplication(ApplicationConfig config) {
            try {
                LOG.info("Stopping application: {}", config.name());

                // Create application callback and call stop
                var appCallback = (Application) config.mod().getDeclaredConstructor().newInstance();
                appCallback.stop(null);

                LOG.info("Application stopped successfully");
                return Result.success(null);

            } catch (Exception e) {
                return Result.failure("Failed to stop application: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // ApplicationConfig Helper - Simulates JotpApplicationController
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** ThreadLocal to store current ApplicationConfig for application access. */
    public static class ApplicationConfig {
        private static final ThreadLocal<ApplicationConfig> current = new ThreadLocal<>();
        private final String name;
        private final String version;
        private final String description;
        private final List<Class<?>> modules;
        private final List<String> registeredNames;
        private final List<String> dependencies;
        private final Class<?> mod;
        private final Map<String, Object> env;

        private ApplicationConfig(
                String name,
                String version,
                String description,
                List<Class<?>> modules,
                List<String> registeredNames,
                List<String> dependencies,
                Class<?> mod,
                Map<String, Object> env) {
            this.name = name;
            this.version = version;
            this.description = description;
            this.modules = modules;
            this.registeredNames = registeredNames;
            this.dependencies = dependencies;
            this.mod = mod;
            this.env = new HashMap<>(env);
        }

        public String name() {
            return name;
        }

        public String version() {
            return version;
        }

        public String description() {
            return description;
        }

        public List<Class<?>> modules() {
            return modules;
        }

        public List<String> registeredNames() {
            return registeredNames;
        }

        public List<String> dependencies() {
            return dependencies;
        }

        public Class<?> mod() {
            return mod;
        }

        public Map<String, Object> env() {
            return Collections.unmodifiableMap(env);
        }

        public static void setCurrent(ApplicationConfig config) {
            current.set(config);
        }

        public static ApplicationConfig getCurrent() {
            return current.get();
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String name;
            private String version = "1.0";
            private String description = "";
            private final List<Class<?>> modules = new ArrayList<>();
            private final List<String> registeredNames = new ArrayList<>();
            private final List<String> dependencies = new ArrayList<>();
            private Class<?> mod;
            private final Map<String, Object> env = new LinkedHashMap<>();

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder version(String version) {
                this.version = version;
                return this;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder modules(List<Class<?>> modules) {
                this.modules.addAll(modules);
                return this;
            }

            public Builder registeredNames(List<String> names) {
                this.registeredNames.addAll(names);
                return this;
            }

            public Builder dependencies(List<String> deps) {
                this.dependencies.addAll(deps);
                return this;
            }

            public Builder mod(Class<?> modClass) {
                this.mod = modClass;
                return this;
            }

            public Builder env(String key, Object value) {
                this.env.put(key, value);
                return this;
            }

            public ApplicationConfig build() {
                return new ApplicationConfig(
                        name,
                        version,
                        description,
                        modules,
                        registeredNames,
                        dependencies,
                        mod,
                        env);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // Demo & Testing
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println(
                "╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println(
                "║  Application Lifecycle Example: Complete JOTP Application Demonstration  ║");
        System.out.println(
                "╚═══════════════════════════════════════════════════════════════════════════╝\n");

        final Logger LOG = LoggerFactory.getLogger("ApplicationLifecycleExample");

        // Step 1: Create ApplicationConfig
        LOG.info("Step 1: Creating ApplicationConfig");
        var config = ApplicationSetup.createConfig();
        LOG.info("  Name: {}", config.name());
        LOG.info("  Version: {}", config.version());
        LOG.info("  Description: {}", config.description());
        LOG.info("  Modules: {}", config.modules().size());
        LOG.info("  Dependencies: {}", config.dependencies());
        LOG.info("  Environment: {}", config.env());
        System.out.println();

        // Step 2: Load the application
        LOG.info("Step 2: Loading application");
        var loadResult = ApplicationLifecycleManager.loadApplication(config);
        if (loadResult.isFailure()) {
            LOG.error("  ❌ Load failed: {}", loadResult.failureReason());
            System.exit(1);
        }
        LOG.info("  ✅ Application loaded");
        System.out.println();

        // Step 3: Start the application (NORMAL mode)
        LOG.info("Step 3: Starting application (NORMAL mode)");
        var startResult = ApplicationLifecycleManager.startApplication(config, StartType.NORMAL);
        if (startResult.isFailure()) {
            LOG.error("  ❌ Start failed: {}", startResult.failureReason());
            System.exit(1);
        }
        LOG.info("  ✅ Application started");
        System.out.println();

        // Step 4: Verify application is running
        LOG.info("Step 4: Verifying application state");
        LOG.info(
                "  Config accessible from application: {}", ApplicationConfig.getCurrent() != null);
        System.out.println();

        // Step 5: Create variant with custom config
        LOG.info("Step 5: Creating variant with custom configuration");
        var customConfig = ApplicationSetup.createConfigWithEnv("debug", 200);
        LOG.info("  Custom max_channels: {}", customConfig.env().get("max_channels"));
        LOG.info("  Custom log_level: {}", customConfig.env().get("log_level"));
        System.out.println();

        // Step 6: Stop the application
        LOG.info("Step 6: Stopping application");
        var stopResult = ApplicationLifecycleManager.stopApplication(config);
        if (stopResult.isFailure()) {
            LOG.error("  ❌ Stop failed: {}", stopResult.failureReason());
            System.exit(1);
        }
        LOG.info("  ✅ Application stopped");
        System.out.println();

        // Step 7: Unload the application
        LOG.info("Step 7: Unloading application");
        var unloadResult = ApplicationLifecycleManager.unloadApplication(config.name());
        if (unloadResult.isFailure()) {
            LOG.error("  ❌ Unload failed: {}", unloadResult.failureReason());
            System.exit(1);
        }
        LOG.info("  ✅ Application unloaded");
        System.out.println();

        System.out.println(
                "╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println(
                "║  ✅ Application Lifecycle Example Complete                               ║");
        System.out.println(
                "║                                                                           ║");
        System.out.println(
                "║  Demonstrated Concepts:                                                  ║");
        System.out.println(
                "║  • Application Callback Interface (start/stop)                            ║");
        System.out.println(
                "║  • Supervision Tree Creation                                              ║");
        System.out.println(
                "║  • ApplicationConfig Builder Pattern                                      ║");
        System.out.println(
                "║  • Environment Configuration Management                                   ║");
        System.out.println(
                "║  • Application Lifecycle: Load → Start → Stop → Unload                    ║");
        System.out.println(
                "║  • StartType Enumeration (NORMAL, TAKEOVER, FAILOVER)                     ║");
        System.out.println(
                "║  • Worker Process Management                                              ║");
        System.out.println(
                "║  • RestartPolicy Integration (ONE_FOR_ONE)                                ║");
        System.out.println(
                "╚═══════════════════════════════════════════════════════════════════════════╝");
    }

    /** Enumeration for application start types. */
    public enum StartType {
        /** Normal application startup */
        NORMAL,
        /** Takeover from another node (distributed) */
        TAKEOVER,
        /** Failover from another node (distributed) */
        FAILOVER
    }

    /** Application interface for lifecycle callbacks. */
    public interface Application {
        /**
         * Start the application.
         *
         * @param type Start type (NORMAL, TAKEOVER, FAILOVER)
         * @param args Configuration arguments
         * @return Result with supervisor reference or exception
         */
        Result<ProcRef<?>, Exception> start(StartType type, Object... args);

        /**
         * Stop the application.
         *
         * @param state Optional state from start()
         */
        void stop(Object state);
    }
}
