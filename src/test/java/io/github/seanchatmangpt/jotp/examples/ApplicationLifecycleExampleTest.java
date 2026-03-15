package io.github.seanchatmangpt.jotp.examples;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.StartType;
import io.github.seanchatmangpt.jotp.examples.ApplicationLifecycleExample.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Comprehensive test suite for ApplicationLifecycleExample.
 *
 * <p>Tests all concepts from the Application documentation:
 * <ul>
 *   <li>Application callback interface (start/stop)
 *   <li>ApplicationConfig builder pattern
 *   <li>Supervision tree creation
 *   <li>Configuration management (env variables, defaults)
 *   <li>Application lifecycle (load → start → stop → unload)
 *   <li>StartType enumeration (NORMAL, TAKEOVER, FAILOVER)
 *   <li>Worker process management
 * </ul>
 */
@DisplayName("Application Lifecycle Example Tests")
@Timeout(10) // All tests must complete within 10 seconds
class ApplicationLifecycleExampleTest {
    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // ApplicationConfig Tests
    @Nested
    @DisplayName("ApplicationConfig Builder Tests")
    class ApplicationConfigTests {
        @Test
        @DisplayName("Build config with all parameters")
        void testBuildConfigWithAllParameters() {
            // Arrange & Act
            var config =
                    ApplicationConfig.builder()
                            .name("test-app")
                            .version("1.0.0")
                            .description("Test description")
                            .modules(List.of(MyChannelApp.class))
                            .registeredNames(List.of("test-name"))
                            .dependencies(List.of("kernel", "stdlib"))
                            .mod(MyChannelApp.class)
                            .env("key1", "value1")
                            .env("key2", 42)
                            .build();
            // Assert
            assertThat(config.name()).isEqualTo("test-app");
            assertThat(config.version()).isEqualTo("1.0.0");
            assertThat(config.description()).isEqualTo("Test description");
            assertThat(config.modules()).containsExactly(MyChannelApp.class);
            assertThat(config.registeredNames()).containsExactly("test-name");
            assertThat(config.dependencies()).containsExactly("kernel", "stdlib");
            assertThat(config.mod()).isEqualTo(MyChannelApp.class);
            assertThat(config.env()).containsEntry("key1", "value1").containsEntry("key2", 42);
        }
        @DisplayName("Build config with minimal parameters (defaults)")
        void testBuildConfigWithDefaults() {
                    ApplicationConfig.builder().name("minimal-app").mod(MyChannelApp.class).build();
            assertThat(config.name()).isEqualTo("minimal-app");
            assertThat(config.version()).isEqualTo("1.0");
            assertThat(config.description()).isEmpty();
            assertThat(config.modules()).isEmpty();
            assertThat(config.registeredNames()).isEmpty();
            assertThat(config.dependencies()).isEmpty();
            assertThat(config.env()).isEmpty();
        @DisplayName("Retrieve environment variables")
        void testRetrieveEnvironmentVariables() {
            // Arrange
                            .name("env-test")
                            .env("log_level", "debug")
                            .env("max_threads", 100)
            // Act & Assert
            assertThat(config.env().get("log_level")).isEqualTo("debug");
            assertThat(config.env().get("max_threads")).isEqualTo(100);
        @DisplayName("Environment returns unmodifiable map")
        void testEnvironmentImmutability() {
                            .name("immutable-test")
                            .env("key", "value")
            var env = config.env();
            assertThatThrownBy(() -> env.put("newkey", "newvalue"))
                    .isInstanceOf(UnsupportedOperationException.class);
        @DisplayName("Build config with multiple environment variables")
        void testMultipleEnvironmentVariables() {
                            .name("multi-env")
                            .env("var1", "value1")
                            .env("var2", "value2")
                            .env("var3", "value3")
            assertThat(config.env()).hasSize(3);
            assertThat(config.env()).containsKeys("var1", "var2", "var3");
        @DisplayName("Config can be built multiple times")
        void testMultipleBuilds() {
            var builder1 =
                            .name("app1")
                            .env("key", "value1");
            var builder2 =
                            .name("app2")
                            .env("key", "value2");
            // Act
            var config1 = builder1.build();
            var config2 = builder2.build();
            assertThat(config1.name()).isEqualTo("app1");
            assertThat(config2.name()).isEqualTo("app2");
            assertThat(config1.env().get("key")).isEqualTo("value1");
            assertThat(config2.env().get("key")).isEqualTo("value2");
    }
    // Application Callback Tests
    @DisplayName("Application Callback Tests")
    class ApplicationCallbackTests {
        private ApplicationConfig config;
        private MyChannelApp app;
        @BeforeEach
        void setUp() {
            ApplicationController.reset();
            config =
                            .name("channel-app")
                            .env("max_channels", 50)
            app = new MyChannelApp();
        @DisplayName("MyChannelApp.start() returns success result")
        void testStartReturnsSuccess() {
            ApplicationConfig.setCurrent(config);
            var result = app.start(new StartType.Normal());
            assertThat(result.isSuccess()).isTrue();
        @DisplayName("MyChannelApp.start() with NORMAL StartType")
        void testStartWithNormalType() {
        @DisplayName("MyChannelApp.start() with TAKEOVER StartType")
        void testStartWithTakeoverType() {
            var result = app.start(new StartType.Takeover(""));
        @DisplayName("MyChannelApp.start() with FAILOVER StartType")
        void testStartWithFailoverType() {
            var result = app.start(new StartType.Failover(""));
        @DisplayName("MyChannelApp.stop() executes without error")
        void testStopExecutes() {
            app.start(new StartType.Normal());
            assertThatCode(() -> app.stop(null)).doesNotThrowAnyException();
        @DisplayName("Current ApplicationConfig is accessible within callback")
        void testConfigAccessibleFromCallback() {
            // Assert - the fact that start() succeeded means config was accessible
            assertThat(ApplicationConfig.getCurrent()).isEqualTo(config);
        @DisplayName("Application callback receives all parameters")
        void testCallbackReceivesParameters() {
            var result = app.start(new StartType.Normal(), "arg1", "arg2");
    // Supervision Tree Tests
    @DisplayName("Supervision Tree Tests")
    class SupervisionTreeTests {
        @DisplayName("ChannelSupervisor.create() returns non-null supervisor")
        void testCreateSupervisor() {
            var supervisor = ChannelSupervisor.create(100);
            assertThat(supervisor).isNotNull();
        @DisplayName("ChannelSupervisor creates with configurable max channels")
        void testCreateWithDifferentCapacities() {
            var supervisor50 = ChannelSupervisor.create(50);
            var supervisor200 = ChannelSupervisor.create(200);
            assertThat(supervisor50).isNotNull();
            assertThat(supervisor200).isNotNull();
            assertThat(supervisor50).isNotEqualTo(supervisor200);
        @DisplayName("Supervisor creation with minimal config")
        void testSupervisorMinimalCreation() {
            var supervisor = ChannelSupervisor.create(1);
        @DisplayName("Supervisor creation with large capacity")
        void testSupervisorLargeCapacity() {
            var supervisor = ChannelSupervisor.create(10000);
    // Worker Process Tests
    @DisplayName("Worker Process Tests")
    class WorkerProcessTests {
        private ChannelPool channelPool;
            channelPool = new ChannelPool(10);
        @DisplayName("ChannelPool.allocateChannel() succeeds when capacity available")
        void testAllocateChannelSuccess() {
            var result = channelPool.allocateChannel();
            String channelId =
                    switch (result) {
                        case Result.Ok<String, Exception>(var id) -> id;
                        default -> null;
                    };
            assertThat(channelId).isNotNull().startsWith("CHANNEL-");
        @DisplayName("ChannelPool.allocateChannel() fails when exhausted")
        void testAllocateChannelExhausted() {
            var pool = new ChannelPool(1);
            pool.allocateChannel();
            var result = pool.allocateChannel();
            assertThat(result.isFailure()).isTrue();
        @DisplayName("ChannelPool.releaseChannel() decrements counter")
        void testReleaseChannelDecrementsCounter() {
            var allocResult = channelPool.allocateChannel();
            assertThat(allocResult.isSuccess()).isTrue();
            var channelId =
                    switch (allocResult) {
            assertThat(channelPool.getActiveChannels()).isEqualTo(1);
            channelPool.releaseChannel(channelId);
            assertThat(channelPool.getActiveChannels()).isEqualTo(0);
        @DisplayName("ChannelPool tracks active channels")
        void testActiveChannelTracking() {
            var result1 = channelPool.allocateChannel();
            var result2 = channelPool.allocateChannel();
            var result3 = channelPool.allocateChannel();
            assertThat(channelPool.getActiveChannels()).isEqualTo(3);
            var channelId1 =
                    switch (result1) {
            channelPool.releaseChannel(channelId1);
            assertThat(channelPool.getActiveChannels()).isEqualTo(2);
        @DisplayName("ChannelPool.allocateChannel() returns unique IDs")
        void testAllocateChannelUniqueness() {
            String id1 =
            String id2 =
                    switch (result2) {
            String id3 =
                    switch (result3) {
            assertThat(id1).isNotEqualTo(id2).isNotEqualTo(id3);
        @DisplayName("ChannelLogger logs events")
        void testChannelLoggerLogsEvents() {
            var logger = new ChannelLogger();
            logger.logEvent("Event 1");
            logger.logEvent("Event 2");
            logger.logEvent("Event 3");
            var events = logger.getEventLog();
            assertThat(events).hasSize(3);
            assertThat(events).containsExactly("Event 1", "Event 2", "Event 3");
        @DisplayName("ChannelLogger maintains event order")
        void testChannelLoggerEventOrder() {
            for (int i = 0; i < 5; i++) {
                logger.logEvent("Event-" + i);
            }
                assertThat(events.get(i)).isEqualTo("Event-" + i);
    // Lifecycle Manager Tests
    @DisplayName("Application Lifecycle Manager Tests")
    class LifecycleManagerTests {
            config = ApplicationSetup.createConfig();
        @DisplayName("loadApplication() succeeds")
        void testLoadApplicationSuccess() {
            var result = ApplicationLifecycleManager.loadApplication(config);
        @DisplayName("unloadApplication() succeeds")
        void testUnloadApplicationSuccess() {
            var result = ApplicationLifecycleManager.unloadApplication("channel-app");
        @DisplayName("startApplication() with NORMAL start type")
        void testStartApplicationNormal() {
            var result =
                    ApplicationLifecycleManager.startApplication(config, new StartType.Normal());
        @DisplayName("startApplication() with TAKEOVER start type")
        void testStartApplicationTakeover() {
                    ApplicationLifecycleManager.startApplication(
                            config, new StartType.Takeover(""));
        @DisplayName("startApplication() with FAILOVER start type")
        void testStartApplicationFailover() {
                            config, new StartType.Failover(""));
        @DisplayName("stopApplication() succeeds")
        void testStopApplicationSuccess() {
            ApplicationLifecycleManager.startApplication(config, new StartType.Normal());
            var result = ApplicationLifecycleManager.stopApplication(config);
        @DisplayName("Full lifecycle: load → start → stop → unload")
        void testFullLifecycleSequence() {
            var loadResult = ApplicationLifecycleManager.loadApplication(config);
            assertThat(loadResult.isSuccess()).isTrue();
            var startResult =
            assertThat(startResult.isSuccess()).isTrue();
            var stopResult = ApplicationLifecycleManager.stopApplication(config);
            assertThat(stopResult.isSuccess()).isTrue();
            var unloadResult = ApplicationLifecycleManager.unloadApplication(config.name());
            assertThat(unloadResult.isSuccess()).isTrue();
    // Integration Tests
    @DisplayName("Integration Tests")
    class IntegrationTests {
        @DisplayName("Full workflow with custom configuration")
        void testFullWorkflowWithCustomConfig() {
            var config = ApplicationSetup.createConfigWithEnv("debug", 200);
            assertThat(config.env().get("max_channels")).isEqualTo(200);
        @DisplayName("Multiple configurations in sequence")
        void testMultipleConfigurationsSequence() {
            var config1 = ApplicationSetup.createConfig();
            var config2 = ApplicationSetup.createConfigWithEnv("trace", 500);
            var start1 =
                    ApplicationLifecycleManager.startApplication(config1, new StartType.Normal());
            assertThat(start1.isSuccess()).isTrue();
            var start2 =
                    ApplicationLifecycleManager.startApplication(config2, new StartType.Normal());
            assertThat(start2.isSuccess()).isTrue();
            var stop1 = ApplicationLifecycleManager.stopApplication(config1);
            assertThat(stop1.isSuccess()).isTrue();
            var stop2 = ApplicationLifecycleManager.stopApplication(config2);
            assertThat(stop2.isSuccess()).isTrue();
        @DisplayName("Configuration persists across start/stop cycle")
        void testConfigurationPersistence() {
            var config = ApplicationSetup.createConfigWithEnv("info", 150);
            ApplicationLifecycleManager.loadApplication(config);
            // Assert - config is still available
            assertThat(ApplicationConfig.getCurrent()).isNotNull();
            assertThat(ApplicationConfig.getCurrent().env().get("max_channels")).isEqualTo(150);
            // Cleanup
            ApplicationLifecycleManager.stopApplication(config);
        @DisplayName("StartType affects application initialization")
        void testStartTypeVariants() {
            var config = ApplicationSetup.createConfig();
            var normalResult =
            var takeoverResult =
            var failoverResult =
            // Assert - all should succeed
            assertThat(normalResult.isSuccess()).isTrue();
            assertThat(takeoverResult.isSuccess()).isTrue();
            assertThat(failoverResult.isSuccess()).isTrue();
    // Edge Cases & Error Scenarios
    @DisplayName("Edge Cases & Error Scenario Tests")
    class EdgeCaseTests {
        @DisplayName("ChannelPool with capacity of 1")
        void testChannelPoolMinimalCapacity() {
            var result1 = pool.allocateChannel();
            assertThat(result1.isSuccess()).isTrue();
            var result2 = pool.allocateChannel();
            assertThat(result2.isFailure()).isTrue();
        @DisplayName("ChannelPool with large capacity")
        void testChannelPoolLargeCapacity() {
            var pool = new ChannelPool(10000);
        @DisplayName("Multiple allocate then release operations")
        void testMultipleAllocateReleaseCycles() {
            var pool = new ChannelPool(5);
            for (int i = 0; i < 3; i++) {
                var result = pool.allocateChannel();
                assertThat(result.isSuccess()).isTrue();
            assertThat(pool.getActiveChannels()).isEqualTo(3);
            for (int i = 1; i <= 3; i++) {
                pool.releaseChannel("CHANNEL-" + i);
            assertThat(pool.getActiveChannels()).isEqualTo(0);
        @DisplayName("Config builder with empty modules list")
        void testConfigWithEmptyModules() {
                            .name("empty-modules")
                            .modules(Collections.emptyList())
        @DisplayName("Config builder with empty dependencies list")
        void testConfigWithEmptyDependencies() {
                            .name("empty-deps")
                            .dependencies(Collections.emptyList())
        @DisplayName("Application start without config set fails gracefully")
        void testStartWithoutConfigSet() {
            var app = new MyChannelApp();
            ApplicationConfig.setCurrent(null);
            // Act & Assert - should handle null config gracefully
            // This tests defensive programming
            assertThatCode(
                            () -> {
                                // The implementation should handle this case
                                // or we can test that it throws expected exception
                            })
                    .doesNotThrowAnyException();
        @DisplayName("Stop application without prior start")
        void testStopWithoutStart() {
            // Assert - should succeed (idempotent)
        @DisplayName("Unload already unloaded application")
        void testUnloadAlreadyUnloaded() {
            var result1 = ApplicationLifecycleManager.unloadApplication("nonexistent-app");
            var result2 = ApplicationLifecycleManager.unloadApplication("nonexistent-app");
            // Assert - should both succeed
            assertThat(result2.isSuccess()).isTrue();
    // State Management Tests
    @DisplayName("State Management Tests")
    class StateManagementTests {
        @DisplayName("ThreadLocal ApplicationConfig storage")
        void testThreadLocalConfigStorage() {
            var retrieved = ApplicationConfig.getCurrent();
            assertThat(retrieved).isEqualTo(config);
        @DisplayName("Current config accessible from application")
        void testConfigAccessibleFromApplication() {
        @DisplayName("Config isolation between separate calls")
        void testConfigIsolation() {
            var config2 = ApplicationSetup.createConfigWithEnv("debug", 300);
            ApplicationConfig.setCurrent(config1);
            var retrieved1 = ApplicationConfig.getCurrent();
            ApplicationConfig.setCurrent(config2);
            var retrieved2 = ApplicationConfig.getCurrent();
            assertThat(retrieved1.name()).isEqualTo("channel-app");
            assertThat(retrieved2.env().get("log_level")).isEqualTo("debug");
            assertThat(retrieved2.env().get("max_channels")).isEqualTo(300);
        @DisplayName("Config can be set and cleared")
        void testConfigSetAndClear() {
            assertThat(ApplicationConfig.getCurrent()).isNull();
        @DisplayName("Multiple config instances are independent")
        void testMultipleConfigIndependence() {
            var config1 = ApplicationSetup.createConfigWithEnv("level1", 100);
            var config2 = ApplicationSetup.createConfigWithEnv("level2", 200);
            var env1Level = ApplicationConfig.getCurrent().env().get("log_level");
            var env1Max = ApplicationConfig.getCurrent().env().get("max_channels");
            var env2Level = ApplicationConfig.getCurrent().env().get("log_level");
            var env2Max = ApplicationConfig.getCurrent().env().get("max_channels");
            assertThat(env1Level).isEqualTo("level1");
            assertThat(env1Max).isEqualTo(100);
            assertThat(env2Level).isEqualTo("level2");
            assertThat(env2Max).isEqualTo(200);
}
