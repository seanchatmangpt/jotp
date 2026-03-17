package io.github.seanchatmangpt.jotp.management;

/** JMX MBean for monitoring GlobalSequenceService. */
public interface GlobalSequenceServiceMXBean {
    long getCurrentSequence();

    long getCurrentHighWaterMark();

    long getSequencesGenerated();

    String getNodeId();

    int getPeerCount();
}
