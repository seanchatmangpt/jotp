package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReplicationLagTest {

    private RocksDBLog localLog;
    private ReplicatedLog replicatedLog;
    private LogReplicationController controller;
    private List<NodeId> remoteNodes;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        localLog = new RocksDBLog("test-process", tempDir);
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
    void totalReplicatedMessagesCountsSuccessfulReplications() {
        var msg = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        replicatedLog.append(msg);

        controller.recordReplication(remoteNodes.get(0), 0, 50);

        assertThat(controller.totalReplicatedMessages()).isEqualTo(1);

        controller.recordReplication(remoteNodes.get(1), 0, 55);
        assertThat(controller.totalReplicatedMessages()).isEqualTo(2);
    }

    @Test
    void averageReplicationLatencyIsCalculatedCorrectly() {
        controller.recordReplication(remoteNodes.get(0), 0, 100);
        controller.recordReplication(remoteNodes.get(1), 0, 200);
        controller.recordReplication(remoteNodes.get(2), 0, 300);

        double avgLatency = controller.averageReplicationLatency();

        assertThat(avgLatency).isCloseTo(200.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void replicationLagPerNodeIsZeroAfterSuccessfulReplication() {
        var msg = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        replicatedLog.append(msg);

        controller.recordReplication(remoteNodes.get(0), 0, 50);

        Map<NodeId, Long> lag = controller.replicationLagPerNode();

        assertThat(lag.get(remoteNodes.get(0))).isEqualTo(0);
        assertThat(lag.get(remoteNodes.get(1))).isGreaterThan(0);
    }

    @Test
    void averageLatencyReturnsZeroWhenNoReplications() {
        assertThat(controller.averageReplicationLatency()).isEqualTo(0);
    }
}
