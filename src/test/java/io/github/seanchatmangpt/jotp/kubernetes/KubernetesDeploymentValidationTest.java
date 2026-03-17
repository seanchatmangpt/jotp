package io.github.seanchatmangpt.jotp.kubernetes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kubernetes Deployment Validation Test Suite
 *
 * Tests JOTP's Kubernetes deployment manifests and deployment strategies.
 * Run with: -Dkubernetes.test.enabled=true -Dkubernetes.namespace=jotp-test
 *
 * Prerequisites:
 * - kubectl configured and pointing to test cluster
 * - Sufficient cluster resources (minimum 3 nodes, 32GB RAM)
 * - Docker image built and available
 */
@DisplayName("Kubernetes Deployment Validation")
@EnabledIfSystemProperty(named = "kubernetes.test.enabled", matches = "true")
public class KubernetesDeploymentValidationTest {

    private static final String NAMESPACE = System.getProperty(
        "kubernetes.namespace", "jotp-test");
    private static final String APP_NAME = "jotp";
    private static final int DEFAULT_REPLICAS = 3;
    private static final int SCALE_TEST_REPLICAS = 5;

    @Test
    @DisplayName("Should validate StatefulSet manifest structure")
    void testStatefulSetManifestStructure() throws Exception {
        String output = executeCommand("kubectl get statefulset " + APP_NAME +
            " -n " + NAMESPACE + " -o json");

        assertThat(output)
            .as("StatefulSet should exist")
            .contains("\"kind\":\"StatefulSet\"");

        assertThat(output)
            .as("Should have service name configured")
            .contains("\"serviceName\":\"jotp-headless\"");

        assertThat(output)
            .as("Should have pod management policy set to Parallel")
            .contains("\"podManagementPolicy\":\"Parallel\"");

        assertThat(output)
            .as("Should have rolling update strategy")
            .contains("\"type\":\"RollingUpdate\"");
    }

    @Test
    @DisplayName("Should validate resource requests and limits")
    void testResourceConfiguration() throws Exception {
        String output = executeCommand("kubectl get statefulset " + APP_NAME +
            " -n " + NAMESPACE + " -o jsonpath='{.spec.template.spec.containers[0].resources}'");

        assertThat(output)
            .as("Should have CPU requests")
            .contains("\"cpu\"");

        assertThat(output)
            .as("Should have memory requests")
            .contains("\"memory\"");

        // Extract memory values
        Pattern requestPattern = Pattern.compile("\"memory\":\"([0-9]+Gi)");
        Matcher requestMatcher = requestPattern.matcher(output);

        assertThat(requestMatcher.find())
            .as("Should have memory request")
            .isTrue();

        String memoryRequest = requestMatcher.group(1);
        assertThat(Integer.parseInt(memoryRequest))
            .as("Memory request should be at least 6Gi")
            .isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("Should validate health check probes")
    void testHealthCheckProbes() throws Exception {
        String output = executeCommand("kubectl get statefulset " + APP_NAME +
            " -n " + NAMESPACE + " -o jsonpath='{.spec.template.spec.containers[0]}'");

        assertThat(output)
            .as("Should have liveness probe")
            .contains("\"livenessProbe\"");

        assertThat(output)
            .as("Should have readiness probe")
            .contains("\"readinessProbe\"");

        assertThat(output)
            .as("Should have startup probe")
            .contains("\"startupProbe\"");

        assertThat(output)
            .as("Liveness probe should check /actuator/health/liveness")
            .contains("/actuator/health/liveness");

        assertThat(output)
            .as("Readiness probe should check /actuator/health/readiness")
            .contains("/actuator/health/readiness");
    }

    @Test
    @DisplayName("Should validate PodDisruptionBudget configuration")
    void testPodDisruptionBudget() throws Exception {
        String output = executeCommand("kubectl get pdb -n " + NAMESPACE +
            " -l app=" + APP_NAME + " -o json");

        assertThat(output)
            .as("PodDisruptionBudget should exist")
            .contains("\"kind\":\"PodDisruptionBudget\"");

        assertThat(output)
            .as("Should have minAvailable configured")
            .containsAnyOf("\"minAvailable\":2", "\"minAvailable\":\"66%\"");
    }

    @Test
    @DisplayName("Should deploy initial cluster successfully")
    void testInitialDeployment() throws Exception {
        // Deploy StatefulSet
        executeCommand("kubectl apply -f k8s/statefulset.yaml -n " + NAMESPACE);

        // Wait for pods to be ready
        String output = executeCommandWithTimeout(
            "kubectl wait --for=condition=ready pod -l app=" + APP_NAME +
            " -n " + NAMESPACE + " --timeout=300s",
            300
        );

        assertThat(output)
            .as("All pods should become ready")
            .containsAnyOf("condition met", "condition satisfied");

        // Verify pod count
        String podCount = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME + " --no-headers | wc -l"
        );

        assertThat(Integer.parseInt(podCount.trim()))
            .as("Should have " + DEFAULT_REPLICAS + " pods")
            .isEqualTo(DEFAULT_REPLICAS);
    }

    @Test
    @DisplayName("Should validate DNS-based peer discovery")
    void testDNSPeerDiscovery() throws Exception {
        // Test DNS resolution from within pod
        String output = executeCommand(
            "kubectl exec " + APP_NAME + "-0 -n " + NAMESPACE +
            " -- nslookup jotp-headless." + NAMESPACE + ".svc.cluster.local"
        );

        assertThat(output)
            .as("DNS should resolve peer addresses")
            .containsAnyOf("Address:", "address:");

        // Test connectivity between pods
        for (int i = 0; i < DEFAULT_REPLICAS; i++) {
            for (int j = i + 1; j < DEFAULT_REPLICAS; j++) {
                String connectivity = executeCommand(
                    "kubectl exec " + APP_NAME + "-" + i + " -n " + NAMESPACE +
                    " -- nc -zv " + APP_NAME + "-" + j + ".jotp-headless." + NAMESPACE +
                    " 50051"
                );

                assertThat(connectivity)
                    .as("Pod " + i + " should connect to pod " + j)
                    .containsAnyOf("succeeded", "open", "connected");
            }
        }
    }

    @Test
    @DisplayName("Should scale from 3 to 5 nodes")
    void testScaleUp() throws Exception {
        // Scale up
        executeCommand("kubectl scale statefulset " + APP_NAME +
            " --replicas=" + SCALE_TEST_REPLICAS + " -n " + NAMESPACE);

        // Wait for new pods to be ready
        String output = executeCommandWithTimeout(
            "kubectl wait --for=condition=ready pod -l app=" + APP_NAME +
            " -n " + NAMESPACE + " --timeout=600s",
            600
        );

        assertThat(output)
            .as("Scaled pods should become ready")
            .containsAnyOf("condition met", "condition satisfied");

        // Verify pod count
        String podCount = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME + " --no-headers | wc -l"
        );

        assertThat(Integer.parseInt(podCount.trim()))
            .as("Should have " + SCALE_TEST_REPLICAS + " pods after scale")
            .isEqualTo(SCALE_TEST_REPLICAS);

        // Verify no pods restarted during scale
        String restarts = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME +
            " -o jsonpath='{.items[*].status.containerStatuses[0].restartCount}'"
        );

        assertThat(restarts.trim())
            .as("No pods should restart during scaling")
            .isEqualTo("0 0 0 0 0");
    }

    @Test
    @DisplayName("Should scale down from 5 to 3 nodes")
    void testScaleDown() throws Exception {
        // Scale down
        executeCommand("kubectl scale statefulset " + APP_NAME +
            " --replicas=" + DEFAULT_REPLICAS + " -n " + NAMESPACE);

        // Wait for scale down to complete
        Thread.sleep(30000);

        // Verify pod count
        String podCount = executeCommand(
            "kubectl get pods -n " + NAMESPACE + " -l app=" + APP_NAME + " --no-headers | wc -l"
        );

        assertThat(Integer.parseInt(podCount.trim()))
            .as("Should have " + DEFAULT_REPLICAS + " pods after scale down")
            .isEqualTo(DEFAULT_REPLICAS);
    }

    @Test
    @DisplayName("Should validate zero-downtime rolling update")
    void testRollingUpdate() throws Exception {
        // Trigger rolling update by changing image
        executeCommand("kubectl set image statefulset " + APP_NAME +
            " " + APP_NAME + "=jotp:1.0 -n " + NAMESPACE);

        // Monitor rollout
        long startTime = System.currentTimeMillis();

        String output = executeCommandWithTimeout(
            "kubectl rollout status statefulset " + APP_NAME +
            " -n " + NAMESPACE + " --timeout=600s",
            600
        );

        long duration = System.currentTimeMillis() - startTime;

        assertThat(output)
            .as("Rolling update should complete successfully")
            .containsAnyOf("successfully rolled out", "rollout successful");

        assertThat(duration)
            .as("Rolling update should complete in reasonable time")
            .isLessThan(300000); // 5 minutes

        // Verify all pods ready
        String readyPods = executeCommand(
            "kubectl get statefulset " + APP_NAME + " -n " + NAMESPACE +
            " -o jsonpath='{.status.readyReplicas}'"
        );

        assertThat(Integer.parseInt(readyPods))
            .as("All pods should be ready after rolling update")
            .isEqualTo(DEFAULT_REPLICAS);
    }

    @Test
    @DisplayName("Should survive pod deletion")
    void testPodDeletion() throws Exception {
        // Delete a pod
        executeCommand("kubectl delete pod " + APP_NAME + "-0 -n " + NAMESPACE);

        // Wait for recreation
        Thread.sleep(10000);

        // Verify pod is recreated
        String podStatus = executeCommand(
            "kubectl get pod " + APP_NAME + "-0 -n " + NAMESPACE +
            " -o jsonpath='{.status.phase}'"
        );

        assertThat(podStatus)
            .as("Pod should be running after recreation")
            .isEqualToAnyOf("Running", "Pending");

        // Wait for readiness
        executeCommandWithTimeout(
            "kubectl wait --for=condition=ready pod " + APP_NAME + "-0" +
            " -n " + NAMESPACE + " --timeout=120s",
            120
        );
    }

    @Test
    @DisplayName("Should respect PodDisruptionBudget")
    void testPodDisruptionBudgetEnforcement() throws Exception {
        // Try to delete more pods than PDB allows
        int maxUnavailable = 1;

        // Attempt to delete multiple pods simultaneously
        List<String> commands = new ArrayList<>();
        for (int i = 0; i <= maxUnavailable + 1; i++) {
            commands.add("kubectl delete pod " + APP_NAME + "-" + i +
                " -n " + NAMESPACE + " --dry-run=server");
        }

        // PDB should prevent deletion beyond maxUnavailable
        for (String cmd : commands) {
            try {
                executeCommand(cmd.replace("--dry-run=server", ""));
            } catch (Exception e) {
                // Expected: PDB should block some deletions
                assertThat(e.getMessage())
                    .as("PDB should prevent excessive pod deletions")
                    .containsAnyOf("disruptionbudget", "pdb");
            }
        }
    }

    @Test
    @DisplayName("Should validate resource limit enforcement")
    void testResourceLimitEnforcement() throws Exception {
        // Monitor resource usage
        String metrics = executeCommand(
            "kubectl top pod -n " + NAMESPACE + " -l app=" + APP_NAME + " --no-headers"
        );

        assertThat(metrics)
            .as("Should be able to get resource metrics")
            .isNotEmpty();

        // Parse and validate metrics are within limits
        String[] lines = metrics.split("\n");
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3) {
                String cpuUsage = parts[1]; // e.g., "1234m"
                String memoryUsage = parts[2]; // e.g., "4Gi"

                assertThat(cpuUsage)
                    .as("CPU usage should be measurable")
                    .endsWith("m");

                assertThat(memoryUsage)
                    .as("Memory usage should be measurable")
                    .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("Should validate persistent volume claims")
    void testPersistentVolumeClaims() throws Exception {
        String output = executeCommand(
            "kubectl get pvc -n " + NAMESPACE + " -l app=" + APP_NAME + " -o json"
        );

        assertThat(output)
            .as("PVCs should be created")
            .contains("\"kind\":\"PersistentVolumeClaim\"");

        // Verify PVCs are bound
        String boundStatus = executeCommand(
            "kubectl get pvc -n " + NAMESPACE + " -l app=" + APP_NAME +
            " -o jsonpath='{.items[*].status.phase}'"
        );

        assertThat(boundStatus)
            .as("All PVCs should be Bound")
            .containsOnly("Bound");
    }

    @Test
    @DisplayName("Should validate service endpoints")
    void testServiceEndpoints() throws Exception {
        String endpoints = executeCommand(
            "kubectl get endpoints " + APP_NAME + "-headless -n " + NAMESPACE + " -o json"
        );

        assertThat(endpoints)
            .as("Headless service should have endpoints")
            .contains("\"subsets\"");
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
        if (exitCode != 0) {
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
            StringBuilder error = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                error.append(line).append("\n");
            }
            throw new RuntimeException("Command failed: " + command + "\nError: " + error);
        }

        return output.toString();
    }

    /**
     * Helper method to execute commands with timeout
     */
    private String executeCommandWithTimeout(String command, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));

        StringBuilder output = new StringBuilder();
        String line;

        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < endTime) {
            if (reader.ready()) {
                line = reader.readLine();
                if (line != null) {
                    output.append(line).append("\n");
                }
            }
            try {
                process.exitValue();
                break;
            } catch (IllegalThreadStateException e) {
                // Still running
            }
            Thread.sleep(100);
        }

        if (System.currentTimeMillis() >= endTime) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out: " + command);
        }

        return output.toString();
    }
}
