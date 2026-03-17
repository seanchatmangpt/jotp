package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RocksDBLogTest {

    private RocksDBLog log;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        log = new RocksDBLog("test-process", tempDir);
    }

    @AfterEach
    void teardown() throws Exception {
        log.close();
    }

    @Test
    void appendReturnsMonotonicSequenceNumbers() {
        var msg1 = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        var msg2 = new DistributedLog.LogMessage("id-2", "content-2", System.currentTimeMillis());

        long seq1 = log.append(msg1);
        long seq2 = log.append(msg2);

        assertThat(seq1).isEqualTo(0);
        assertThat(seq2).isEqualTo(1);
    }

    @Test
    void getReturnsMessageBySequence() {
        var msg = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());

        long seq = log.append(msg);
        Optional<DistributedLog.LogMessage> retrieved = log.get(seq);

        assertThat(retrieved).isPresent().contains(msg);
    }

    @Test
    void getRangeReturnsAllMessagesInRange() {
        var msgs = new ArrayList<DistributedLog.LogMessage>();
        for (int i = 0; i < 10; i++) {
            msgs.add(new DistributedLog.LogMessage("id-" + i, "content-" + i, System.currentTimeMillis()));
        }

        for (var msg : msgs) {
            log.append(msg);
        }

        List<DistributedLog.LogMessage> range = log.getRange(2, 5);

        assertThat(range).hasSize(4).containsExactly(msgs.get(2), msgs.get(3), msgs.get(4), msgs.get(5));
    }

    @Test
    void watchIsInvokedOnAppend() throws InterruptedException {
        var received = new ArrayList<DistributedLog.LogMessage>();
        log.watch(received::add);

        var msg1 = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        var msg2 = new DistributedLog.LogMessage("id-2", "content-2", System.currentTimeMillis());

        log.append(msg1);
        log.append(msg2);

        Thread.sleep(100);

        assertThat(received).containsExactly(msg1, msg2);
    }

    @Test
    void lastSequenceReturnsHighestSequenceNumber() {
        assertThat(log.lastSequence()).isEqualTo(-1);

        var msg1 = new DistributedLog.LogMessage("id-1", "content-1", System.currentTimeMillis());
        log.append(msg1);
        assertThat(log.lastSequence()).isEqualTo(0);

        var msg2 = new DistributedLog.LogMessage("id-2", "content-2", System.currentTimeMillis());
        log.append(msg2);
        assertThat(log.lastSequence()).isEqualTo(1);
    }
}
