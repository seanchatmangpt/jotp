package io.github.seanchatmangpt.jotp.dogfood.concurrency;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/** Dogfood: tests for VirtualThreadPatterns generated from concurrency/virtual-thread.tera. */
@DisplayName("VirtualThreadPatterns")
class VirtualThreadPatternsTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    @Test
    @DisplayName("startNamed creates a virtual thread with the given name")
    void startNamedCreatesVirtualThread() throws InterruptedException {
        var counter = new AtomicInteger();
        var thread = VirtualThreadPatterns.startNamed("test-thread", counter::incrementAndGet);
        thread.join(Duration.ofSeconds(2));
        assertThat(counter.get()).isEqualTo(1);
        assertThat(thread.isVirtual()).isTrue();
    @DisplayName("startFireAndForget runs the task")
    void startFireAndForgetRunsTask() throws InterruptedException {
        var thread = VirtualThreadPatterns.startFireAndForget(counter::incrementAndGet);
    @DisplayName("startAll creates multiple virtual threads")
    void startAllCreatesMultipleThreads() throws InterruptedException {
        List<Runnable> tasks = List.of(counter::incrementAndGet, counter::incrementAndGet);
        var threads = VirtualThreadPatterns.startAll("worker-", tasks);
        for (var thread : threads) {
            thread.join(Duration.ofSeconds(2));
        }
        assertThat(counter.get()).isEqualTo(2);
        assertThat(threads).allMatch(Thread::isVirtual);
    @DisplayName("processAllConcurrently processes all items")
    void processAllConcurrentlyProcessesAllItems() throws InterruptedException {
        var items = List.of(1, 2, 3, 4, 5);
        var results = VirtualThreadPatterns.processAllConcurrently(items, i -> i * 2);
        assertThat(results).hasSize(5).containsExactlyInAnyOrder(2, 4, 6, 8, 10);
    @DisplayName("handleRequests dispatches to virtual threads")
    void handleRequestsDispatchesToVirtualThreads() throws InterruptedException {
        var requests = List.of("a", "b", "c");
        VirtualThreadPatterns.handleRequests(requests, r -> counter.incrementAndGet());
        assertThat(counter.get()).isEqualTo(3);
    @DisplayName("runWithTimeout completes within timeout")
    void runWithTimeoutCompletesWithinTimeout() throws InterruptedException {
        var result =
                VirtualThreadPatterns.runWithTimeout(
                        () -> {
                            // fast task
                        },
                        Duration.ofSeconds(2));
        assertThat(result).isTrue();
    @DisplayName("runWithTimeout returns false when task exceeds timeout")
    void runWithTimeoutReturnsFalseOnTimeout() throws InterruptedException {
                            try {
                                Thread.sleep(Duration.ofSeconds(10));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        Duration.ofMillis(100));
        assertThat(result).isFalse();
}
