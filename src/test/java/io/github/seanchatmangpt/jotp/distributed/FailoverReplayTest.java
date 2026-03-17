package io.github.seanchatmangpt.jotp.distributed;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FailoverReplayTest {

    private RocksDBLog log;
    private Path tempDir;

    @BeforeEach
    void setup(@TempDir Path tmpDir) {
        this.tempDir = tmpDir;
        log = new RocksDBLog("test-process", tmpDir);
    }

    @AfterEach
    void teardown() throws Exception {
        log.close();
    }

    @Test
    void replayRestoresAllMessagesFromPersistentStorage() throws Exception {
        var messages = new ArrayList<DistributedLog.LogMessage>();
        for (int i = 0; i < 5; i++) {
            var msg = new DistributedLog.LogMessage("id-" + i, "content-" + i, System.currentTimeMillis());
            log.append(msg);
            messages.add(msg);
        }

        long lastSeq = log.lastSequence();
        log.close();

        log = new RocksDBLog("test-process", tempDir);

        assertThat(log.lastSequence()).isEqualTo(lastSeq);

        List<DistributedLog.LogMessage> replayed = log.getRange(0, lastSeq);
        assertThat(replayed).hasSize(5).containsExactlyElementsOf(messages);
    }

    @Test
    void messageReplayCanRecoverStateAfterCrash() throws Exception {
        var stateLog = new ArrayList<String>();
        Consumer<DistributedLog.LogMessage> replayHandler =
                msg -> {
                    stateLog.add((String) msg.content());
                };

        var msg1 = new DistributedLog.LogMessage("id-1", "state-A", System.currentTimeMillis());
        var msg2 = new DistributedLog.LogMessage("id-2", "state-B", System.currentTimeMillis());
        var msg3 = new DistributedLog.LogMessage("id-3", "state-C", System.currentTimeMillis());

        log.append(msg1);
        log.append(msg2);
        log.append(msg3);

        long lastSeq = log.lastSequence();

        log.close();

        log = new RocksDBLog("test-process", tempDir);
        stateLog.clear();

        List<DistributedLog.LogMessage> replayed = log.getRange(0, lastSeq);
        replayed.forEach(replayHandler);

        assertThat(stateLog).containsExactly("state-A", "state-B", "state-C");
    }
}
