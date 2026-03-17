package io.github.seanchatmangpt.jotp.management;

/** JMX MBean for monitoring DistributedMessageLog. */
public interface DistributedMessageLogMXBean {
    long getLastCommittedSequence();

    long getPendingReplicationCount();

    int getActiveSubscriberCount();

    long getTotalMessagesAppended();

    long getFailedReplications();

    void clearMetrics();
}
