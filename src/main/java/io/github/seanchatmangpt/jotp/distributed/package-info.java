/**
 * Distributed computing primitives for multi-node JOTP clusters.
 *
 * <h2>Overview</h2>
 *
 * <p>This package provides the building blocks for running JOTP applications across multiple JVM
 * nodes. It implements Erlang/OTP's distributed application controller ({@code dist_ac}) and global
 * registry ({@code global}) semantics with JVM crash survival and automatic failover.
 *
 * <h2>Key Components</h2>
 *
 * <h3>Node Discovery & Health Monitoring</h3>
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.NodeDiscovery} — Cluster membership and
 *       health tracking
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.StaticNodeDiscovery} — Static
 *       configuration implementation
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.NodeInfo} — Node metadata and health
 *       status
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.NodeDiscoveryBackend} — Pluggable storage
 *       backends (in-memory, RocksDB, etc.)
 * </ul>
 *
 * <h3>Global Process Registry</h3>
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.GlobalProcRegistry} — Cluster-wide process
 *       naming and discovery
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.GlobalProcRef} — Stable reference with
 *       sequence numbers for idempotence
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.GlobalRegistryBackend} — Pluggable
 *       registry storage
 * </ul>
 *
 * <h3>Failover & Process Migration</h3>
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.FailoverController} — Automatic process
 *       migration on node failure
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.DistributedNode} — Named node with leader
 *       election and failover
 * </ul>
 *
 * <h3>Distributed Applications</h3>
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.DistributedAppSpec} — Application
 *       configuration with priority lists
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.ApplicationCallbacks} — Lifecycle hooks
 *       for distributed apps
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.StartMode} — Normal, failover, and
 *       takeover startup modes
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <h3>Cluster Membership</h3>
 *
 * <p>Nodes discover each other via {@link io.github.seanchatmangpt.jotp.distributed.NodeDiscovery}.
 * Each node:
 *
 * <ol>
 *   <li>Registers with its name and address
 *   <li>Sends periodic heartbeats to indicate liveness
 *   <li>Monitors peer nodes via health checks
 *   <li>Triggers callbacks when nodes go down or come up
 * </ol>
 *
 * <h3>Leader Election</h3>
 *
 * <p>Distributed applications run on only one node at a time — the highest-priority live node.
 * {@link io.github.seanchatmangpt.jotp.distributed.DistributedNode} implements deterministic leader
 * election:
 *
 * <ol>
 *   <li>Scan priority list from highest to lowest
 *   <li>First node found alive becomes the leader
 *   <li>Lower-priority nodes monitor the leader
 *   <li>On leader failure, next live node takes over after timeout
 * </ol>
 *
 * <h3>Failover Process Migration</h3>
 *
 * <p>When a node fails, {@link io.github.seanchatmangpt.jotp.distributed.FailoverController}
 * migrates its processes:
 *
 * <ol>
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.NodeDiscovery#onNodeDown(String)} is
 *       triggered
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.FailoverController#handleNodeDown(String)}
 *       finds all processes on the failed node
 *   <li>Processes are distributed across healthy nodes (round-robin)
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.GlobalProcRegistry} is updated with new
 *       locations
 * </ul>
 *
 * <h2>JVM Crash Survival</h2>
 *
 * <p>All critical state is persisted via pluggable backends:
 *
 * <ul>
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.RocksDBGlobalRegistryBackend} — Persistent
 *       process registry
 *   <li>{@link io.github.seanchatmangpt.jotp.distributed.RocksDBNodeDiscoveryBackend} — Persistent
 *       node registry
 * </ul>
 *
 * <p>Sequence numbers in {@link io.github.seanchatmangpt.jotp.distributed.GlobalProcRef} enable
 * idempotent recovery. On restart, nodes reconcile their state with the persisted registry to
 * detect and recover from partial writes.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create a 3-node cluster
 * var nodes = List.of(
 *     new DistributedNode("node1", "localhost", 0, NodeConfig.defaults()),
 *     new DistributedNode("node2", "localhost", 0, NodeConfig.defaults()),
 *     new DistributedNode("node3", "localhost", 0, NodeConfig.defaults())
 * );
 *
 * // Configure node discovery
 * var backend = new InMemoryNodeDiscoveryBackend();
 * var discovery = new StaticNodeDiscovery(
 *     "node1",
 *     List.of("node1", "node2", "node3"),
 *     Map.of("node1", "localhost:8080", "node2", "localhost:8081", "node3", "localhost:8082"),
 *     backend
 * );
 * discovery.startHealthChecks();
 *
 * // Configure failover controller
 * var registry = GlobalProcRegistry.getInstance();
 * var failover = new FailoverController(registry, discovery);
 * discovery.addNodeDownListener(failover::handleNodeDown);
 *
 * // Register distributed application
 * var spec = new DistributedAppSpec("myapp",
 *     List.of(List.of(nodes.get(0).nodeId(), nodes.get(1).nodeId(), nodes.get(2).nodeId())),
 *     Duration.ZERO
 * );
 *
 * for (var node : nodes) {
 *     node.register(spec, new ApplicationCallbacks() {
 *         public void onStart(StartMode mode) {
 *             System.out.println(node.nodeId() + " starting as " + mode);
 *         }
 *         public void onStop() {
 *             System.out.println(node.nodeId() + " stopping");
 *         }
 *     });
 * }
 *
 * // Start application on all nodes (only highest-priority runs)
 * for (var node : nodes) {
 *     node.start("myapp");
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All public APIs in this package are thread-safe unless otherwise noted. Internal state is
 * protected by {@link java.util.concurrent.ConcurrentHashMap}, {@link
 * java.util.concurrent.CopyOnWriteArrayList}, and atomic references.
 *
 * <h2>Comparison to Erlang/OTP</h2>
 *
 * <table border="1">
 *   <tr><th>Erlang/OTP</th><th>JOTP</th></tr>
 *   <tr><td>global:register_name/2</td><td>{@link io.github.seanchatmangpt.jotp.distributed.GlobalProcRegistry#registerGlobal(String, io.github.seanchatmangpt.jotp.ProcRef, String)}</td></tr>
 *   <tr><td>global:whereis_name/1</td><td>{@link io.github.seanchatmangpt.jotp.distributed.GlobalProcRegistry#findGlobal(String)}</td></tr>
 *   <tr><td>application:start/1</td><td>{@link io.github.seanchatmangpt.jotp.distributed.DistributedNode#start(String)}</td></tr>
 *   <tr><td>dist_ac</td><td>{@link io.github.seanchatmangpt.jotp.distributed.DistributedNode}</td></tr>
 *   <tr><td>net_adm:ping/1</td><td>{@link io.github.seanchatmangpt.jotp.distributed.NodeDiscovery#getHealthyNodes()}</td></tr>
 * </table>
 *
 * @see io.github.seanchatmangpt.jotp.distributed.NodeDiscovery
 * @see io.github.seanchatmangpt.jotp.distributed.GlobalProcRegistry
 * @see io.github.seanchatmangpt.jotp.distributed.DistributedNode
 */
package io.github.seanchatmangpt.jotp.distributed;
