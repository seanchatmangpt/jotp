package io.github.seanchatmangpt.jotp.discovery;

import io.github.seanchatmangpt.jotp.distributed.NodeId;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Metadata about a registered service instance.
 *
 * @param processName human-readable name of the process (e.g., "user_service")
 * @param nodeId the NodeId hosting this process
 * @param grpcPort gRPC port for inter-process communication
 * @param metadata additional key-value metadata (e.g., version, region, tags)
 * @param registeredAt timestamp when this instance was registered
 */
public record ServiceInstance(
    String processName, NodeId nodeId, int grpcPort, Map<String, String> metadata, Instant registeredAt) {

  /**
   * Convenience factory: create a ServiceInstance with empty metadata.
   *
   * @param processName name of the process
   * @param nodeId the hosting node
   * @param grpcPort gRPC port
   * @return new ServiceInstance with empty metadata and current timestamp
   */
  public static ServiceInstance of(String processName, NodeId nodeId, int grpcPort) {
    return new ServiceInstance(processName, nodeId, grpcPort, Collections.emptyMap(), Instant.now());
  }

  /**
   * Convenience factory: create a ServiceInstance with custom metadata.
   *
   * @param processName name of the process
   * @param nodeId the hosting node
   * @param grpcPort gRPC port
   * @param metadata custom metadata map
   * @return new ServiceInstance with provided metadata and current timestamp
   */
  public static ServiceInstance of(
      String processName, NodeId nodeId, int grpcPort, Map<String, String> metadata) {
    return new ServiceInstance(processName, nodeId, grpcPort, metadata, Instant.now());
  }
}
