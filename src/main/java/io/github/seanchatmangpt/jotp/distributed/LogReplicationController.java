package io.github.seanchatmangpt.jotp.distributed;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages replication metrics and coordinates log synchronization across a cluster.
 */
public final class LogReplicationController {

    private final ReplicatedLog replicatedLog;
    private final List<NodeId> remoteNodes;
    private final AtomicLong totalReplicatedMessages = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> lastReplicationTimestamp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastReplicatedSeq = new ConcurrentHashMap<>();
    private final List<Long> replicationLatencies = new CopyOnWriteArrayList<>();
    private final ExecutorService metricsExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Create a replication controller for a replicated log.
     */
    public LogReplicationController(ReplicatedLog replicatedLog, List<NodeId> remoteNodes) {
        this.replicatedLog = Objects.requireNonNull(replicatedLog, "replicatedLog must not be null");
        this.remoteNodes = List.copyOf(Objects.requireNonNull(remoteNodes, "remoteNodes must not be null"));

        for (NodeId node : remoteNodes) {
            lastReplicationTimestamp.put(nodeKey(node), 0L);
            lastReplicatedSeq.put(nodeKey(node), -1L);
        }

        startReplicationMonitoring();
    }

    /**
     * Returns the total number of messages successfully replicated to quorum.
     */
    public long totalReplicatedMessages() {
        return totalReplicatedMessages.get();
    }

    /**
     * Returns the average replication latency in milliseconds.
     */
    public double averageReplicationLatency() {
        if (replicationLatencies.isEmpty()) {
            return 0;
        }
        return replicationLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /**
     * Returns the replication lag (in message count) for each remote node.
     */
    public Map<NodeId, Long> replicationLagPerNode() {
        long localLastSeq = replicatedLog.lastSequence();
        Map<NodeId, Long> result = new LinkedHashMap<>();

        for (NodeId node : remoteNodes) {
            long nodeLastSeq = lastReplicatedSeq.getOrDefault(nodeKey(node), -1L);
            long lag = Math.max(0, localLastSeq - nodeLastSeq);
            result.put(node, lag);
        }

        return result;
    }

    /**
     * Returns the timestamp of the last successful replication to a node.
     */
    public long lastReplicationTimestampFor(NodeId node) {
        return lastReplicationTimestamp.getOrDefault(nodeKey(node), 0L);
    }

    /**
     * Record a successful replication.
     */
    public void recordReplication(NodeId node, long seq, long latencyMs) {
        totalReplicatedMessages.incrementAndGet();
        lastReplicationTimestamp.put(nodeKey(node), System.currentTimeMillis());
        lastReplicatedSeq.put(nodeKey(node), seq);
        replicationLatencies.add(latencyMs);

        if (replicationLatencies.size() > 1000) {
            replicationLatencies.remove(0);
        }
    }

    /**
     * Shutdown the controller and cleanup resources.
     */
    public void close() throws Exception {
        metricsExecutor.shutdown();
        if (!metricsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            metricsExecutor.shutdownNow();
        }
        replicationLatencies.clear();
        lastReplicationTimestamp.clear();
        lastReplicatedSeq.clear();
    }

    private String nodeKey(NodeId node) {
        return node.name() + "@" + node.host() + ":" + node.port();
    }

    private void startReplicationMonitoring() {
        metricsExecutor.submit(
                () -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
    }
}
