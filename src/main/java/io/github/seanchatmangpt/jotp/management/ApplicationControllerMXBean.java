package io.github.seanchatmangpt.jotp.management;

import java.util.List;

/** JMX MBean for monitoring ApplicationController. */
public interface ApplicationControllerMXBean {
    int getLoadedApplicationCount();

    int getRunningApplicationCount();

    String getUptime();

    boolean isRecoveryEnabled();

    String getStartupTime();

    List<String> getLoadedApplications();

    List<String> getRunningApplications();
}
