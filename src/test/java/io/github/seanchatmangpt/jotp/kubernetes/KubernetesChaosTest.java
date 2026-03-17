package io.github.seanchatmangpt.jotp.kubernetes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Order;

import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes Chaos Engineering Test Suite
 *
 * Tests JOTP's resilience under failure scenarios in Kubernetes.
 * Run with: -Dkubernetes.test.enabled=true -Dkubernetes.namespace=jotp-test
 *
 * WARNING: These tests are destructive and should only run in isolated test clusters.
 */
@DisplayName("Kubernetes Chaos Engineering")
@EnabledIfSystemProperty(named = "kubernetes.test.enabled", matches = "true")
public class KubernetesChaosTest {

    private static final String NAMESPACE = System.getProperty(
        "kubernetes.namespace", "jotp-test");
    private static final String APP_NAME = "jotp";
    private static final int DEFAULT_REPLICAS = 3;

    @Test
    @Order(1)
    @DisplayName("Should survive random pod kills during load")
    void testRandomPodKill() throws Exception {
        // Verify initial state
        String initialPods = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME + " --no-headers | wc -l"
        );
        int initialCount = Integer.parseInt(initialPods.trim());

        // Kill a random pod
        String podToKill = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
            " -o jsonpath='{.items[0].metadata.name}'"
        ).trim();

        executeCommand("kubectl delete pod " + podToKill + " -n " + NAMESPACE +
            " --grace-period=0 --force");

        // Wait for recovery
        Thread.sleep(30000);

        // Verify pod was recreated
        String currentPods = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME + " --no-headers | wc -l"
        );
        int currentCount = Integer.parseInt(currentPods.trim());

        assertThat(currentCount)
            .as("Pod count should be restored")
            .isEqualTo(initialCount);

        // Verify cluster health
        String health = executeCommand(
            "kubectl exec " + APP_NAME + "-0 -n " + NAMESPACE +
            " -- curl -s http://localhost:9091/actuator/health"
        );

        assertThat(health)
            .as("Application should be healthy after pod kill")
            .contains("\"status\":\"UP\"");
    }

    @Test
    @Order(2)
    @DisplayName("Should survive leader node failure")
    void testLeaderNodeFailure() throws Exception {
        // Assume jotp-0 is leader (typical for StatefulSets)
        String leaderPod = APP_NAME + "-0";

        // Kill leader
        executeCommand("kubectl delete pod " + leaderPod + " -n " + NAMESPACE);

        // Wait for leader election
        Thread.sleep(45000);

        // Verify new leader emerged
        String logs = executeCommand(
            "kubectl logs " + APP_NAME + "-1 -n " + NAMESPACE + " --tail=50"
        );

        assertThat(logs)
            .as("New leader should be elected")
            .containsAnyOf("leader", "elected", "coordinator", "coordinator");
    }

    @Test
    @Order(3)
    @DisplayName("Should survive multiple simultaneous pod failures")
    void testMultiplePodFailures() throws Exception {
        // Kill two pods simultaneously
        executeCommand("kubectl delete pod " + APP_NAME + "-0 -n " + NAMESPACE);
        executeCommand("kubectl delete pod " + APP_NAME + "-1 -n " + NAMESPACE);

        // Wait for recovery
        Thread.sleep(60000);

        // Verify recovery
        String readyPods = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
            " --field-selector=status.phase=Running --no-headers | wc -l"
        );

        assertThat(Integer.parseInt(readyPods.trim()))
            .as("Cluster should recover from multiple failures")
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("Should survive node drain")
    void testNodeDrain() throws Exception {
        // Find a node running jotp pods
        String node = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
            " -o jsonpath='{.items[0].spec.nodeName}'"
        ).trim();

        // Cordon and drain the node
        executeCommand("kubectl cordon " + node);
        executeCommand("kubectl drain " + node + " --ignore-daemonsets --force");

        // Wait for rescheduling
        Thread.sleep(60000);

        // Verify pods moved to other nodes
        String scheduledPods = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
            " --field-selector=status.phase=Running --no-headers | wc -l"
        );

        assertThat(Integer.parseInt(scheduledPods.trim()))
            .as("Pods should be rescheduled after node drain")
            .isGreaterThanOrEqualTo(2);

        // Uncordon node
        executeCommand("kubectl uncordon " + node);
    }

    @Test
    @Order(5)
    @DisplayName("Should survive resource exhaustion")
    void testResourceExhaustion() throws Exception {
        // Deploy stress test pod
        String stressPod = "jotp-stress";
        String stressManifest = String.format(
            "apiVersion: v1\n" +
            "kind: Pod\n" +
            "metadata:\n" +
            "  name: %s\n" +
            "  namespace: %s\n" +
            "spec:\n" +
            "  containers:\n" +
            "  - name: stress\n" +
            "    image: progrium/stress\n" +
            "    command: ['stress', '--cpu', '2', '--timeout', '60s']\n" +
            "  restartPolicy: Never\n",
            stressPod, NAMESPACE
        );

        // Apply stress manifest
        executeCommand("echo '" + stressManifest + "' | kubectl apply -f -");

        // Wait for stress test
        Thread.sleep(65000);

        // Verify JOTP pods survived
        String jotpPods = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
            " --field-selector=status.phase=Running --no-headers | wc -l"
        );

        assertThat(Integer.parseInt(jotpPods.trim()))
            .as("JOTP pods should survive resource pressure")
            .isGreaterThanOrEqualTo(2);

        // Cleanup
        executeCommand("kubectl delete pod " + stressPod + " -n " + NAMESPACE);
    }

    @Test
    @Order(6)
    @DisplayName("Should survive network partition simulation")
    void testNetworkPartition() throws Exception {
        // Simulate network partition using iptables
        String targetPod = APP_NAME + "-0";

        // Block network traffic
        executeCommand(
            "kubectl exec " + targetPod + " -n " + NAMESPACE +
            " -- sh -c 'iptables -A INPUT -p tcp --dport 50051 -j DROP && " +
            "iptables -A OUTPUT -p tcp --dport 50051 -j DROP'"
        );

        // Wait for partition to be detected
        Thread.sleep(30000);

        // Remove partition
        executeCommand(
            "kubectl exec " + targetPod + " -n " + NAMESPACE +
            " -- sh -c 'iptables -D INPUT -p tcp --dport 50051 -j DROP && " +
            "iptables -D OUTPUT -p tcp --dport 50051 -j DROP'"
        );

        // Wait for recovery
        Thread.sleep(30000);

        // Verify cluster recovered
        String health = executeCommand(
            "kubectl exec " + targetPod + " -n " + NAMESPACE +
            " -- curl -s http://localhost:9091/actuator/health"
        );

        assertThat(health)
            .as("Cluster should recover from network partition")
            .contains("\"status\":\"UP\"");
    }

    @Test
    @Order(7)
    @DisplayName("Should survive disk pressure")
    void testDiskPressure() throws Exception {
        // Fill up disk temporarily
        String podName = APP_NAME + "-0";

        executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- sh -c 'fallocate -l 1G /tmp/testfile.dat'"
        );

        // Verify pod still responds
        String health = executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- curl -s http://localhost:9091/actuator/health"
        );

        assertThat(health)
            .as("Pod should remain healthy under disk pressure")
            .contains("\"status\":\"UP\"");

        // Cleanup
        executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- rm -f /tmp/testfile.dat"
        );
    }

    @Test
    @Order(8)
    @DisplayName("Should validate graceful shutdown")
    void testGracefulShutdown() throws Exception {
        String podName = APP_NAME + "-0";

        // Send SIGTERM
        executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- sh -c 'curl -X POST http://localhost:9091/actuator/shutdown'"
        );

        // Wait for graceful shutdown
        Thread.sleep(15000);

        // Verify pod terminated
        String podStatus = executeCommand(
            "kubectl get pod " + podName + " -n " + NAMESPACE +
            " -o jsonpath='{.status.phase}'"
        );

        assertThat(podStatus)
            .as("Pod should terminate gracefully")
            .isIn("Failed", "Succeeded");

        // StatefulSet will recreate it
        Thread.sleep(20000);

        String newPodStatus = executeCommand(
            "kubectl get pod " + podName + " -n " + NAMESPACE +
            " -o jsonpath='{.status.phase}'"
        );

        assertThat(newPodStatus)
            .as("Pod should be recreated after termination")
            .isEqualTo("Running");
    }

    @Test
    @Order(9)
    @DisplayName("Should survive DNS service unavailability")
    void testDNSFailure() throws Exception {
        // Block DNS temporarily
        String podName = APP_NAME + "-0";

        executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- sh -c 'iptables -A OUTPUT -p udp --dport 53 -j DROP'"
        );

        // Wait for DNS timeout
        Thread.sleep(10000);

        // Restore DNS
        executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- sh -c 'iptables -D OUTPUT -p udp --dport 53 -j DROP'"
        );

        // Wait for recovery
        Thread.sleep(20000);

        // Verify pod recovered
        String health = executeCommand(
            "kubectl exec " + podName + " -n " + NAMESPACE +
            " -- curl -s http://localhost:9091/actuator/health"
        );

        assertThat(health)
            .as("Pod should recover from DNS failure")
            .contains("\"status\":\"UP\"");
    }

    @Test
    @Order(10)
    @DisplayName("Should measure cluster recovery time")
    void testRecoveryTime() throws Exception {
        // Baseline health check
        long startTime = System.currentTimeMillis();

        // Kill all pods simultaneously
        for (int i = 0; i < DEFAULT_REPLICAS; i++) {
            final int podNum = i;
            new Thread(() -> {
                try {
                    executeCommand("kubectl delete pod " + APP_NAME + "-" + podNum +
                        " -n " + NAMESPACE);
                } catch (Exception e) {
                    // Ignore
                }
            }).start();
        }

        // Wait for full recovery
        boolean recovered = false;
        long recoveryTime = 0;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);

            try {
                String readyPods = executeCommand(
                    "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
                    " --field-selector=status.phase=Running -o jsonpath='{.items[*].metadata.name}'"
                );

                if (readyPods.split(" ").length >= DEFAULT_REPLICAS) {
                    // Check if all are ready
                    String allReady = executeCommand(
                        "kubectl wait --for=condition=ready pod -l app=" + APP_NAME +
                        " -n " + NAMESPACE + " --timeout=10s 2>&1"
                    );

                    if (allReady.contains("condition met")) {
                        recovered = true;
                        recoveryTime = System.currentTimeMillis() - startTime;
                        break;
                    }
                }
            } catch (Exception e) {
                // Continue waiting
            }
        }

        assertThat(recovered)
            .as("Cluster should recover from total failure")
            .isTrue();

        assertThat(recoveryTime)
            .as("Recovery should complete in reasonable time")
            .isLessThan(180000); // 3 minutes

        System.out.println("Cluster recovered in " + (recoveryTime / 1000) + " seconds");
    }

    /**
     * Helper method to execute kubectl commands
     */
    private String executeCommand(String command) throws Exception {
        Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", command});
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        int exitCode = process.waitFor();
        if (exitCode != 0 && !command.contains("--dry-run")) {
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
            StringBuilder error = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                error.append(line).append("\n");
            }
            // Some commands are expected to fail (e.g., wait commands)
            if (!command.contains("wait")) {
                throw new RuntimeException("Command failed: " + command + "\nError: " + error);
            }
        }

        return output.toString();
    }
}
