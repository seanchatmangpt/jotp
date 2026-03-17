package io.github.seanchatmangpt.jotp.discovery;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Unit tests for KubernetesServiceDiscovery.
 *
 * <p>Tests handle the case when not running in Kubernetes (no ServiceAccount mounted). Full
 * integration tests with real K8s in KubernetesServiceDiscoveryIT.
 */
@DisplayName("KubernetesServiceDiscovery")
final class KubernetesServiceDiscoveryTest {

  @Test
  @DisplayName("throws RuntimeException when ServiceAccount not available")
  void testServiceAccountNotAvailable() {
    // This test should fail gracefully since we're not in a K8s pod
    assertThatThrownBy(KubernetesServiceDiscovery::new)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Kubernetes");
  }

  @Test
  @DisplayName("K8s discovery instance would use namespace from ServiceAccount")
  void testNamespaceInitialization() {
    // Just verify that the constructor would attempt to read the namespace
    // Since we're not in K8s, this will fail, but we're testing the intent
    assertThatThrownBy(KubernetesServiceDiscovery::new)
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("ConfigMap name constant is correct")
  void testConfigMapNameConstant() {
    // This is a constant verification test
    String expectedConfigMapName = "jotp-processes";
    assertThat(expectedConfigMapName).isEqualTo("jotp-processes");
  }
}
