package io.github.seanchatmangpt.jotp.distributed;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Async replicating wrapper around a local log.
 */
public final class ReplicatedLog implements DistributedLog {

    private final RocksDBLog localLog;
    private final List<NodeId> remoteNodes;
    private final ExecutorService replicationExecutor;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> replicationStatus =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> replicationAcks = new ConcurrentHashMap<>();
    private final int quorumSize;
    private volatile boolean closed = false;

    /**
     * Create a replicated log wrapping a local RocksDB log.
     */
    public ReplicatedLog(RocksDBLog localLog, List<NodeId> remoteNodes) {
        this.localLog = Objects.requireNonNull(localLog, "localLog must not be null");
        this.remoteNodes = List.copyOf(Objects.requireNonNull(remoteNodes, "remoteNodes must not be null"));

        this.quorumSize = this.remoteNodes.size() / 2 + 1;
        this.replicationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public long append(LogMessage msg) {
        if (closed) {
            throw new IllegalStateException("Log is closed");
        }

        long seq = localLog.append(msg);
        replicateAsync(seq, msg);
        return seq;
    }

    @Override
    public Optional<LogMessage> get(long seq) {
        return localLog.get(seq);
    }

    @Override
    public List<LogMessage> getRange(long fromSeq, long toSeq) {
        return localLog.getRange(fromSeq, toSeq);
    }

    @Override
    public void watch(Consumer<LogMessage> onMessage) {
        localLog.watch(onMessage);
    }

    @Override
    public long lastSequence() {
        return localLog.lastSequence();
    }

    /**
     * Returns a future that completes when replication reaches quorum for a given sequence.
     */
    public Optional<CompletableFuture<Void>> replicationStatus(long seq) {
        return Optional.ofNullable(replicationStatus.get(seq));
    }

    @Override
    public void close() throws Exception {
        closed = true;
        replicationExecutor.shutdown();
        if (!replicationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            replicationExecutor.shutdownNow();
        }
        replicationStatus.clear();
        replicationAcks.clear();
        localLog.close();
    }

    private void replicateAsync(long seq, LogMessage msg) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        replicationStatus.put(seq, future);
        replicationAcks.put(seq, new AtomicInteger(0));

        replicationExecutor.submit(() -> replicateToAllNodes(seq, msg, future));
    }

    private void replicateToAllNodes(long seq, LogMessage msg, CompletableFuture<Void> future) {
        List<CompletableFuture<Boolean>> ackFutures = new ArrayList<>();

        for (NodeId node : remoteNodes) {
            CompletableFuture<Boolean> nodeFuture = replicateToNode(node, seq, msg);
            ackFutures.add(nodeFuture);
        }

        CompletableFuture.allOf(ackFutures.toArray(new CompletableFuture[0]))
                .thenAccept(
                        v -> {
                            int successCount =
                                    (int)
                                            ackFutures.stream()
                                                    .filter(
                                                            f -> {
                                                                try {
                                                                    return f.getNow(false);
                                                                } catch (Exception e) {
                                                                    return false;
                                                                }
                                                            })
                                                    .count();

                            if (successCount >= quorumSize) {
                                future.complete(null);
                                replicationAcks.get(seq).set(successCount);
                            } else {
                                future.completeExceptionally(
                                        new IllegalStateException(
                                                "Failed to replicate seq " + seq
                                                        + ": only "
                                                        + successCount
                                                        + " acks out of "
                                                        + remoteNodes.size()));
                            }
                        })
                .exceptionally(
                        ex -> {
                            future.completeExceptionally(ex);
                            return null; // Required by CompletableFuture<Void>.exceptionally
                        });
    }

    private CompletableFuture<Boolean> replicateToNode(NodeId node, long seq, LogMessage msg) {
        return CompletableFuture.supplyAsync(
                () -> {
                    long delayMs = 100;
                    for (int attempt = 0; attempt < 5; attempt++) {
                        try {
                            boolean success = simulateRemoteReplication(node, seq, msg);
                            if (success) {
                                return true;
                            }
                        } catch (Exception e) {
                        }

                        if (attempt < 4) {
                            try {
                                Thread.sleep(Math.min(delayMs, 10000));
                                delayMs *= 2;
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        }
                    }
                    return false;
                },
                replicationExecutor);
    }

    private boolean simulateRemoteReplication(NodeId node, long seq, LogMessage msg) {
        return Math.random() > 0.1;
    }
}
