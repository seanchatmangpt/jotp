package io.github.seanchatmangpt.jotp.management;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

/**
 * Registers JOTP MBeans with the platform MBean server.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // Register all built-in MBeans
 * JotpManagement.registerAll();
 *
 * // Register custom distributed components
 * JotpManagement.registerDistributedMessageLog(myLogMBean);
 * JotpManagement.registerGlobalSequenceService(mySeqMBean);
 *
 * // Cleanup on shutdown
 * JotpManagement.unregisterAll();
 * }</pre>
 */
public final class JotpManagement {

    private static final String DOMAIN = "io.github.seanchatmangpt.jotp";
    private static final MBeanServer MBEAN_SERVER = ManagementFactory.getPlatformMBeanServer();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private static ObjectName appControllerName;
    private static ObjectName distMessageLogName;
    private static ObjectName globalSeqServiceName;

    static {
        try {
            appControllerName = new ObjectName(DOMAIN + ":type=ApplicationController");
            distMessageLogName = new ObjectName(DOMAIN + ":type=DistributedMessageLog");
            globalSeqServiceName = new ObjectName(DOMAIN + ":type=GlobalSequenceService");
        } catch (MalformedObjectNameException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private JotpManagement() {
        // Utility class
    }

    /**
     * Registers all built-in JOTP MBeans with the platform MBean server.
     *
     * <p>Currently registers:
     *
     * <ul>
     *   <li>ApplicationController - application lifecycle metrics
     * </ul>
     *
     * @throws IllegalStateException if registration fails
     */
    public static void registerAll() {
        if (REGISTERED.compareAndSet(false, true)) {
            registerApplicationController();
        }
    }

    /**
     * Registers the ApplicationController MBean.
     *
     * <p>Provides metrics about loaded/running applications, uptime, and recovery status.
     */
    public static void registerApplicationController() {
        try {
            var mbean = new ApplicationControllerImpl();
            MBEAN_SERVER.registerMBean(mbean, appControllerName);
        } catch (InstanceAlreadyExistsException e) {
            // Already registered, ignore
        } catch (MBeanRegistrationException | NotCompliantMBeanException e) {
            throw new IllegalStateException("Failed to register ApplicationController MBean", e);
        }
    }

    /**
     * Registers a DistributedMessageLog MBean implementation.
     *
     * @param mbean the DistributedMessageLog metrics implementation
     * @throws IllegalStateException if registration fails
     */
    public static void registerDistributedMessageLog(DistributedMessageLogMXBean mbean) {
        try {
            MBEAN_SERVER.registerMBean(mbean, distMessageLogName);
        } catch (InstanceAlreadyExistsException e) {
            // Already registered, unregister and re-register
            try {
                MBEAN_SERVER.unregisterMBean(distMessageLogName);
                MBEAN_SERVER.registerMBean(mbean, distMessageLogName);
            } catch (InstanceNotFoundException
                    | InstanceAlreadyExistsException
                    | MBeanRegistrationException
                    | NotCompliantMBeanException ex) {
                throw new IllegalStateException(
                        "Failed to re-register DistributedMessageLog MBean", ex);
            }
        } catch (MBeanRegistrationException | NotCompliantMBeanException e) {
            throw new IllegalStateException("Failed to register DistributedMessageLog MBean", e);
        }
    }

    /**
     * Registers a GlobalSequenceService MBean implementation.
     *
     * @param mbean the GlobalSequenceService metrics implementation
     * @throws IllegalStateException if registration fails
     */
    public static void registerGlobalSequenceService(GlobalSequenceServiceMXBean mbean) {
        try {
            MBEAN_SERVER.registerMBean(mbean, globalSeqServiceName);
        } catch (InstanceAlreadyExistsException e) {
            // Already registered, unregister and re-register
            try {
                MBEAN_SERVER.unregisterMBean(globalSeqServiceName);
                MBEAN_SERVER.registerMBean(mbean, globalSeqServiceName);
            } catch (InstanceNotFoundException
                    | InstanceAlreadyExistsException
                    | MBeanRegistrationException
                    | NotCompliantMBeanException ex) {
                throw new IllegalStateException(
                        "Failed to re-register GlobalSequenceService MBean", ex);
            }
        } catch (MBeanRegistrationException | NotCompliantMBeanException e) {
            throw new IllegalStateException("Failed to register GlobalSequenceService MBean", e);
        }
    }

    /**
     * Unregisters all JOTP MBeans from the platform MBean server.
     *
     * <p>Should be called during application shutdown for clean cleanup.
     */
    public static void unregisterAll() {
        unregisterMBean(appControllerName);
        unregisterMBean(distMessageLogName);
        unregisterMBean(globalSeqServiceName);
        REGISTERED.set(false);
    }

    private static void unregisterMBean(ObjectName name) {
        try {
            if (MBEAN_SERVER.isRegistered(name)) {
                MBEAN_SERVER.unregisterMBean(name);
            }
        } catch (InstanceNotFoundException | MBeanRegistrationException e) {
            // Ignore during cleanup
        }
    }

    /**
     * Checks if JOTP MBeans are currently registered.
     *
     * @return true if MBeans have been registered
     */
    public static boolean isRegistered() {
        return REGISTERED.get();
    }

    /**
     * Gets the JMX domain used for JOTP MBeans.
     *
     * @return the JMX domain name
     */
    public static String getDomain() {
        return DOMAIN;
    }

    // --- Internal Implementation ---

    /** Internal ApplicationController MBean implementation. */
    private static final class ApplicationControllerImpl implements ApplicationControllerMXBean {
        private final Instant startupTime = Instant.now();

        @Override
        public int getLoadedApplicationCount() {
            return ApplicationController.loadedApplications().size();
        }

        @Override
        public int getRunningApplicationCount() {
            return ApplicationController.whichApplications().size();
        }

        @Override
        public String getUptime() {
            var duration = Duration.between(startupTime, Instant.now());
            long hours = duration.toHours();
            long minutes = duration.toMinutesPart();
            long seconds = duration.toSecondsPart();
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }

        @Override
        public boolean isRecoveryEnabled() {
            return ApplicationController.isRecoveryEnabled();
        }

        @Override
        public String getStartupTime() {
            return startupTime.toString();
        }

        @Override
        public List<String> getLoadedApplications() {
            return ApplicationController.loadedApplications().stream()
                    .map(info -> info.name())
                    .toList();
        }

        @Override
        public List<String> getRunningApplications() {
            return ApplicationController.whichApplications().stream()
                    .map(info -> info.name())
                    .toList();
        }
    }
}
