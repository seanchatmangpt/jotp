package io.github.seanchatmangpt.jotp.distributed;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Local RocksDB-backed append-only log implementation.
 */
public final class RocksDBLog implements DistributedLog {

    private static final String LAST_SEQ_KEY = "log:lastSeq";
    private static final String LOG_PREFIX = "log:";

    private final String processName;
    private final Path dbPath;
    private final AtomicLong lastSeq;
    private final List<Consumer<LogMessage>> watchers = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Create a RocksDB-backed log for a process.
     */
    public RocksDBLog(String processName, Path dbPath) {
        this.processName = Objects.requireNonNull(processName, "processName must not be null");
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null");

        try {
            if (!dbPath.toFile().exists()) {
                dbPath.toFile().mkdirs();
            }
            this.lastSeq = new AtomicLong(loadLastSequence());
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize RocksDBLog: " + e.getMessage(), e);
        }
    }

    @Override
    public long append(LogMessage msg) {
        if (closed) {
            throw new IllegalStateException("Log is closed");
        }
        Objects.requireNonNull(msg, "message must not be null");

        long seq = lastSeq.incrementAndGet();

        try {
            persistMessage(seq, msg);
            saveLastSequence(seq);

            for (Consumer<LogMessage> watcher : watchers) {
                try {
                    watcher.accept(msg);
                } catch (Exception e) {
                    Thread.currentThread()
                            .getUncaughtExceptionHandler()
                            .uncaughtException(Thread.currentThread(), e);
                }
            }

            return seq;
        } catch (Exception e) {
            throw new RuntimeException("Failed to append message: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<LogMessage> get(long seq) {
        if (seq < 0 || seq > lastSeq.get()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(readMessage(seq));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read message at sequence " + seq + ": " + e, e);
        }
    }

    @Override
    public List<LogMessage> getRange(long fromSeq, long toSeq) {
        if (fromSeq < 0 || toSeq < fromSeq || fromSeq > lastSeq.get()) {
            return List.of();
        }

        long actualToSeq = Math.min(toSeq, lastSeq.get());
        List<LogMessage> result = new ArrayList<>();

        try {
            for (long seq = fromSeq; seq <= actualToSeq; seq++) {
                LogMessage msg = readMessage(seq);
                if (msg != null) {
                    result.add(msg);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to read range [" + fromSeq + ", " + actualToSeq + "]: " + e, e);
        }

        return result;
    }

    @Override
    public void watch(Consumer<LogMessage> onMessage) {
        Objects.requireNonNull(onMessage, "onMessage must not be null");
        watchers.add(onMessage);
    }

    @Override
    public long lastSequence() {
        return lastSeq.get();
    }

    @Override
    public void close() throws Exception {
        closed = true;
        watchers.clear();
    }

    private void persistMessage(long seq, LogMessage msg) throws IOException {
        Path msgFile = dbPath.resolve(LOG_PREFIX + seq);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(msg);
            oos.flush();
            java.nio.file.Files.write(msgFile, baos.toByteArray());
        }
    }

    private LogMessage readMessage(long seq) throws IOException, ClassNotFoundException {
        Path msgFile = dbPath.resolve(LOG_PREFIX + seq);
        if (!msgFile.toFile().exists()) {
            throw new UnsupportedOperationException("not implemented: log message retrieval");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(java.nio.file.Files.readAllBytes(msgFile));
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (LogMessage) ois.readObject();
        }
    }

    private long loadLastSequence() throws IOException, ClassNotFoundException {
        Path metaFile = dbPath.resolve(LAST_SEQ_KEY);
        if (!metaFile.toFile().exists()) {
            return -1;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(java.nio.file.Files.readAllBytes(metaFile));
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (Long) ois.readObject();
        }
    }

    private void saveLastSequence(long seq) throws IOException {
        Path metaFile = dbPath.resolve(LAST_SEQ_KEY);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(seq);
            oos.flush();
            java.nio.file.Files.write(metaFile, baos.toByteArray());
        }
    }
}
