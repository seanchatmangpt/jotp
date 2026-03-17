package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DistributedLogIntegrationTest {

    private RocksDBLog localLog;
    private ReplicatedLog replicatedLog;
    private LogReplicationController controller;
    private List<NodeId> remoteNodes;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        localLog = new RocksDBLog("integration-test", tempDir);
        remoteNodes = List.of(
                new NodeId("node2", "localhost", 9001),
                new NodeId("node3", "localhost", 9002),
                new NodeId("node4", "localhost", 9003));
        replicatedLog = new ReplicatedLog(localLog, remoteNodes);
        controller = new LogReplicationController(replicatedLog, remoteNodes);
    }

    @AfterEach
    void teardown() throws Exception {
        controller.close();
        replicatedLog.close();
    }

    @Test
    void completeWorkflowAppendReplicateAndReplay() throws InterruptedException {
        var messages = new ArrayList<DistributedLog.LogMessage>();
        for (int i = 0; i < 5; i++) {
            var msg = new DistributedLog.LogMessage(
                    "msg-" + i, "Event " + i, System.currentTimeMillis());
            long seq = replicatedLog.append(msg);
            messages.add(msg);
            assertThat(seq).isEqualTo(i);
        }

        Thread.sleep(100);

        assertThat(replicatedLog.lastSequence()).isEqualTo(4);
        List<DistributedLog.LogMessage> retrieved = replicatedLog.getRange(0, 4);
        assertThat(retrieved).hasSize(5).containsExactlyElementsOf(messages);

        for (NodeId node : remoteNodes) {
            controller.recordReplication(node, 4, 50);
        }

        assertThat(controller.totalReplicatedMessages()).isEqualTo(3);
        assertThat(controller.averageReplicationLatency()).isCloseTo(50, org.assertj.core.data.Offset.offset(0.1));

        Map<NodeId, Long> lag = controller.replicationLagPerNode();
        for (NodeId node : remoteNodes) {
            assertThat(lag.get(node)).isEqualTo(0);
        }
    }

    @Test
    void concurrentAppendAndReplication() throws InterruptedException {
        ExecutorService appender = Executors.newVirtualThreadPerTaskExecutor();
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 20; i++) {
            int idx = i;
            appender.submit(
                    () -> {
                        var msg = new DistributedLog.LogMessage(
                                "msg-" + idx, "Content-" + idx, System.currentTimeMillis());
                        long seq = replicatedLog.append(msg);
                        sequences.add(seq);
                    });
        }

        appender.shutdown();
        assertThat(appender.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        Thread.sleep(100);

        assertThat(replicatedLog.lastSequence()).isEqualTo(19);
        assertThat(sequences).hasSize(20);

        List<DistributedLog.LogMessage> all = replicatedLog.getRange(0, 19);
        assertThat(all).hasSize(20);
    }

    @Test
    void replayReconstructs State() throws InterruptedException {
        AtomicInteger state = new AtomicInteger(0);

        for (int i = 1; i <= 5; i++) {
            var msg = new DistributedLog.LogMessage(
                    "event-" + i, "state-increment-" + i, System.currentTimeMillis());
            replicatedLog.append(msg);
        }

        long lastSeq = replicatedLog.lastSequence();

        List<DistributedLog.LogMessage> replayed = replicatedLog.getRange(0, lastSeq);
        assertThat(replayed).hasSize(5);

        for (var msg : replayed) {
            state.incrementAndGet();
        }

        assertThat(state.get()).isEqualTo(5);
    }
}
