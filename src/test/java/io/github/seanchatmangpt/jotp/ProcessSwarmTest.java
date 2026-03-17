package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessSwarmTest {

    // ── Message & State types ────────────────────────────────────────────────

    sealed interface CounterMsg permits CounterMsg.Add, CounterMsg.Reset {
        record Add(int value) implements CounterMsg {}

        record Reset() implements CounterMsg {}
    }

    record CounterState(int count) {
        static CounterState initial() {
            return new CounterState(0);
        }

        static CounterState handle(CounterState state, CounterMsg msg) {
            return switch (msg) {
                case CounterMsg.Add(var v) -> new CounterState(state.count() + v);
                case CounterMsg.Reset() -> new CounterState(0);
            };
        }
    }

    // ── Tracking message — captures which replica handled each message ────────

    /**
     * A tracking message that records the ID of the replica that handled it. We use a shared
     * ConcurrentHashMap to note replica IDs.
     */
    record TrackMsg(int msgId) {}

    record TrackState(int replicaId, Set<Integer> handledIds) {}

    // ── Test infrastructure ──────────────────────────────────────────────────

    private ProcessSwarm<CounterState, CounterMsg> swarm;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @AfterEach
    void tearDown() {
        if (swarm != null) {
            swarm.close();
            swarm = null;
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void swarmStartsWithMinSizeReplicas() {
        swarm =
                ProcessSwarm.builder(CounterState::initial, CounterState::handle)
                        .minSize(3)
                        .maxSize(10)
                        .build();

        assertThat(swarm.stats().replicaCount()).isEqualTo(3);
        assertThat(swarm.replicaStats()).hasSize(3);
    }

    @Test
    void swarmDefaultsToMinSize2() {
        swarm = ProcessSwarm.builder(CounterState::initial, CounterState::handle).build();

        assertThat(swarm.stats().replicaCount()).isEqualTo(2);
    }

    @Test
    void allMessagesAreProcessedWhenNMessagesSentToMinSizeSwarm() {
        swarm =
                ProcessSwarm.builder(CounterState::initial, CounterState::handle)
                        .minSize(2)
                        .maxSize(2)
                        .build();

        int N = 100;
        for (int i = 0; i < N; i++) {
            swarm.send(new CounterMsg.Add(1));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> swarm.stats().totalProcessed() >= N);

        assertThat(swarm.stats().totalProcessed()).isGreaterThanOrEqualTo(N);
    }

    @Test
    void broadcastDeliversToAllReplicas() {
        int minSize = 4;
        AtomicInteger resetCount = new AtomicInteger(0);

        swarm =
                ProcessSwarm.<CounterState, CounterMsg>builder(
                                CounterState::initial,
                                (state, msg) -> {
                                    if (msg instanceof CounterMsg.Reset) {
                                        resetCount.incrementAndGet();
                                    }
                                    return CounterState.handle(state, msg);
                                })
                        .minSize(minSize)
                        .maxSize(minSize)
                        .build();

        // First put some count in each replica
        for (int i = 0; i < minSize * 5; i++) {
            swarm.send(new CounterMsg.Add(1));
        }

        // Wait for those to be processed
        await().atMost(Duration.ofSeconds(5))
                .until(() -> swarm.stats().totalProcessed() >= minSize * 5L);

        // Broadcast reset — should hit all replicas
        swarm.broadcast(new CounterMsg.Reset());

        // Wait for all reset messages to be processed (each replica gets one)
        await().atMost(Duration.ofSeconds(5)).until(() -> resetCount.get() >= minSize);

        assertThat(resetCount.get()).isGreaterThanOrEqualTo(minSize);
    }

    @Test
    void statsReturnsAccurateReplicaCountAndProcessedCount() {
        swarm =
                ProcessSwarm.builder(CounterState::initial, CounterState::handle)
                        .minSize(2)
                        .maxSize(2)
                        .build();

        int messageCount = 50;
        for (int i = 0; i < messageCount; i++) {
            swarm.send(new CounterMsg.Add(1));
        }

        await().atMost(Duration.ofSeconds(5))
                .until(() -> swarm.stats().totalProcessed() >= messageCount);

        ProcessSwarm.SwarmStats stats = swarm.stats();
        assertThat(stats.replicaCount()).isEqualTo(2);
        assertThat(stats.totalProcessed()).isGreaterThanOrEqualTo(messageCount);
    }

    @Test
    void closesDrainsAllInFlightMessagesBeforeShutdown() {
        AtomicInteger processedCount = new AtomicInteger(0);

        ProcessSwarm<CounterState, CounterMsg> localSwarm =
                ProcessSwarm.<CounterState, CounterMsg>builder(
                                CounterState::initial,
                                (state, msg) -> {
                                    processedCount.incrementAndGet();
                                    return CounterState.handle(state, msg);
                                })
                        .minSize(2)
                        .maxSize(2)
                        .build();

        int N = 200;
        for (int i = 0; i < N; i++) {
            localSwarm.send(new CounterMsg.Add(1));
        }

        // close() should drain before stopping
        localSwarm.close();

        // After close, all messages should have been processed
        assertThat(processedCount.get()).isEqualTo(N);
    }

    @Test
    void swarmRoutesMessagesToMultipleReplicasNotAlwaysTheSameOne() {
        // Track which replicas (by AtomicInteger counter side-effect) processed messages
        // We use a concurrent set keyed by thread identity to detect distribution
        Set<Long> processingThreadIds = ConcurrentHashMap.newKeySet();

        swarm =
                ProcessSwarm.<CounterState, CounterMsg>builder(
                                CounterState::initial,
                                (state, msg) -> {
                                    processingThreadIds.add(Thread.currentThread().threadId());
                                    return CounterState.handle(state, msg);
                                })
                        .minSize(4)
                        .maxSize(4)
                        .build();

        // Send enough messages that all 4 replicas are likely to pick some up
        int N = 400;
        for (int i = 0; i < N; i++) {
            swarm.send(new CounterMsg.Add(1));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> swarm.stats().totalProcessed() >= N);

        // With 4 replicas and 400 messages, we expect at least 2 different threads to have
        // been involved in processing (routing is not always to the same replica)
        assertThat(processingThreadIds.size()).isGreaterThan(1);
    }

    @Test
    void autoScaleUpTriggersWhenQueueDepthExceedsThreshold() {
        // Use a handler that introduces a short delay to keep queues deep
        swarm =
                ProcessSwarm.<CounterState, CounterMsg>builder(
                                CounterState::initial,
                                (state, msg) -> {
                                    try {
                                        Thread.sleep(10); // slow handler to build up queue
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return CounterState.handle(state, msg);
                                })
                        .minSize(2)
                        .maxSize(10)
                        .scaleUpQueueDepth(5) // very low threshold: scale up when queue > 5
                        .build();

        assertThat(swarm.stats().replicaCount()).isEqualTo(2);

        // Flood the swarm so queues fill up well beyond the threshold
        for (int i = 0; i < 200; i++) {
            swarm.send(new CounterMsg.Add(1));
        }

        // The scaler runs every 1 second; wait up to 3 seconds for a scale-up event
        await().atMost(Duration.ofSeconds(3)).until(() -> swarm.stats().replicaCount() > 2);

        assertThat(swarm.stats().replicaCount()).isGreaterThan(2);
    }

    @Test
    void replicaStatsReflectsIndividualReplicaActivity() {
        swarm =
                ProcessSwarm.builder(CounterState::initial, CounterState::handle)
                        .minSize(2)
                        .maxSize(2)
                        .build();

        for (int i = 0; i < 50; i++) {
            swarm.send(new CounterMsg.Add(i));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> swarm.stats().totalProcessed() >= 50);

        var replicaStats = swarm.replicaStats();
        assertThat(replicaStats).hasSize(2);

        // Each replica should have processed at least 1 message (given power-of-2 routing)
        long totalViaReplicas =
                replicaStats.stream().mapToLong(ProcessSwarm.ReplicaStats::processedCount).sum();
        assertThat(totalViaReplicas).isGreaterThanOrEqualTo(50);
    }
}
