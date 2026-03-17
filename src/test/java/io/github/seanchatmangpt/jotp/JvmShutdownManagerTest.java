package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link JvmShutdownManager}.
 *
 * <p>Verifies JVM shutdown hook registration, execution, and lifecycle management.
 */
@DisplayName("JvmShutdownManager Tests")
class JvmShutdownManagerTest {

    private JvmShutdownManager shutdownManager;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        shutdownManager = JvmShutdownManager.getInstance();
        shutdownManager.clearCallbacks();
    }

    @AfterEach
    void tearDown() {
        if (shutdownManager != null) {
            shutdownManager.clearCallbacks();
        }
    }

    @Test
    @DisplayName("Should register and execute single shutdown callback")
    void registerCallback_executesSingleCallback() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var executed = new AtomicInteger(0);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    latch.countDown();
                });

        shutdownManager.triggerGraceful();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should register and execute multiple shutdown callbacks")
    void registerCallback_executesMultipleCallbacks() throws InterruptedException {
        var latch = new CountDownLatch(3);
        var executed = new AtomicInteger(0);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    latch.countDown();
                });

        shutdownManager.triggerGraceful();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should execute callbacks in priority order")
    void registerCallback_executesCallbacksInPriorityOrder() throws InterruptedException {
        var order = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        var latch = new CountDownLatch(3);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.BEST_EFFORT_SAVE,
                () -> {
                    order.add(2);
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    order.add(1);
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.EMERGENCY_FLUSH,
                () -> {
                    order.add(3);
                    latch.countDown();
                });

        shutdownManager.triggerGraceful();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("Should not execute callbacks multiple times")
    void triggerGraceful_doesNotExecuteCallbacksMultipleTimes() throws InterruptedException {
        var executed = new AtomicInteger(0);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE, executed::incrementAndGet);

        shutdownManager.triggerGraceful();
        Thread.sleep(100); // Give time for any potential duplicates
        shutdownManager.triggerGraceful();

        // Wait a bit to ensure no delayed execution
        Thread.sleep(500);

        assertThat(executed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should handle exceptions in callbacks gracefully")
    void registerCallback_handlesExceptionsGracefully() throws InterruptedException {
        var latch = new CountDownLatch(2);
        var executed = new AtomicInteger(0);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    throw new RuntimeException("Callback 1 failed");
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    throw new RuntimeException("Callback 3 failed");
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    latch.countDown();
                });

        shutdownManager.triggerGraceful();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should support concurrent callback registration")
    void registerCallback_handlesConcurrentRegistration() throws InterruptedException {
        var latch = new CountDownLatch(10);
        var executed = new AtomicInteger(0);
        var executor = Executors.newFixedThreadPool(5);

        for (int i = 0; i < 10; i++) {
            executor.submit(
                    () -> {
                        shutdownManager.registerCallback(
                                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                                () -> {
                                    executed.incrementAndGet();
                                    latch.countDown();
                                });
                    });
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        shutdownManager.triggerGraceful();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should clear all callbacks")
    void clearCallbacks_clearsAllCallbacks() throws InterruptedException {
        var executed = new AtomicInteger(0);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE, executed::incrementAndGet);
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE, executed::incrementAndGet);
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE, executed::incrementAndGet);

        shutdownManager.clearCallbacks();

        shutdownManager.triggerGraceful();

        // Wait to ensure callbacks don't execute
        Thread.sleep(500);

        assertThat(executed.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle null callback gracefully")
    void registerCallback_handlesNullCallback() {
        assertThatThrownBy(
                        () ->
                                shutdownManager.registerCallback(
                                        JvmShutdownManager.Priority.GRACEFUL_SAVE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle empty callback list")
    void triggerGraceful_handlesEmptyCallbackList() {
        // Should not throw
        assertThatCode(() -> shutdownManager.triggerGraceful()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should support callbacks with custom timeouts")
    void registerCallback_handlesCallbacksWithCustomTimeouts() throws InterruptedException {
        var fastCallbackLatch = new CountDownLatch(1);
        var slowCallbackLatch = new CountDownLatch(1);
        var executed = new AtomicInteger(0);

        // Fast callback should complete
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    executed.incrementAndGet();
                    fastCallbackLatch.countDown();
                },
                Duration.ofSeconds(1));

        // Slow callback (simulated with sleep)
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    executed.incrementAndGet();
                    slowCallbackLatch.countDown();
                },
                Duration.ofSeconds(1));

        shutdownManager.triggerGraceful();

        assertThat(fastCallbackLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(slowCallbackLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should track callback count")
    void callbackCount_returnsCorrectCount() {
        assertThat(shutdownManager.callbackCount()).isEqualTo(0);

        shutdownManager.registerCallback(JvmShutdownManager.Priority.GRACEFUL_SAVE, () -> {});
        assertThat(shutdownManager.callbackCount()).isEqualTo(1);

        shutdownManager.registerCallback(JvmShutdownManager.Priority.BEST_EFFORT_SAVE, () -> {});
        assertThat(shutdownManager.callbackCount()).isEqualTo(2);

        shutdownManager.clearCallbacks();
        assertThat(shutdownManager.callbackCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should detect shutdown state")
    void isShuttingDown_returnsCorrectState() {
        assertThat(shutdownManager.isShuttingDown()).isFalse();

        shutdownManager.triggerGraceful();

        assertThat(shutdownManager.isShuttingDown()).isTrue();
    }

    @Test
    @DisplayName("Should configure custom timeouts")
    void configureTimeouts_setsCustomTimeouts() {
        // Configure custom timeouts
        shutdownManager.configureTimeouts(
                Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(5));

        // Register callbacks with different priorities - they should use the custom timeouts
        var executed = new AtomicInteger(0);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                executed::incrementAndGet); // Uses 20 second timeout
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.BEST_EFFORT_SAVE,
                executed::incrementAndGet); // Uses 10 second timeout
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.EMERGENCY_FLUSH,
                executed::incrementAndGet); // Uses 5 second timeout

        assertThat(shutdownManager.callbackCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle same priority callbacks")
    void registerCallback_handlesSamePriority() throws InterruptedException {
        var order = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        var latch = new CountDownLatch(3);

        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    order.add(1);
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    order.add(2);
                    latch.countDown();
                });
        shutdownManager.registerCallback(
                JvmShutdownManager.Priority.GRACEFUL_SAVE,
                () -> {
                    order.add(3);
                    latch.countDown();
                });

        shutdownManager.triggerGraceful();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Same priority callbacks maintain registration order
        assertThat(order).containsExactly(1, 2, 3);
    }
}
