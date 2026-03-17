package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReplicatedLogTest {

    private ReplicatedLog replicatedLog;
    private RocksDBLog localLog;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        localLog = new RocksDBLog("test-process", tempDir);
        var remoteNodes = List.of(
                new NodeId("node2", "localhost", 9001),
                new NodeId("node3", "localhost", 9002));
        replicatedLog = new ReplicatedLog(localLog, remoteNodes);
    }

    @AfterEach
    void teardown() throws Exception {
        replicatedLog.close();
    }

    @Test
    void appendWritesToLocalLogImmediately() {
        var msg = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());

        long seq = replicatedLog.append(msg);

        assertThat(seq).isEqualTo(0);
        assertThat(replicatedLog.get(seq)).contains(msg);
    }

    @Test
    void appendReturnsMonotonicSequenceNumbers() {
        var msg1 = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        var msg2 = new DistributedLog.LogMessage("id-2", "content-2", System.currentTimeMillis());

        long seq1 = replicatedLog.append(msg1);
        long seq2 = replicatedLog.append(msg2);

        assertThat(seq1).isEqualTo(0);
        assertThat(seq2).isEqualTo(1);
    }

    @Test
    void getAndGetRangeDelegateToLocalLog() {
        var msg1 = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        var msg2 = new DistributedLog.LogMessage("id-2", "content-2", System.currentTimeMillis());

        replicatedLog.append(msg1);
        replicatedLog.append(msg2);

        assertThat(replicatedLog.get(0)).contains(msg1);
        assertThat(replicatedLog.get(1)).contains(msg2);
        assertThat(replicatedLog.getRange(0, 1)).containsExactly(msg1, msg2);
    }

    @Test
    void lastSequenceDelegatesToLocalLog() {
        assertThat(replicatedLog.lastSequence()).isEqualTo(-1);

        var msg = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        replicatedLog.append(msg);

        assertThat(replicatedLog.lastSequence()).isEqualTo(0);
    }
}
