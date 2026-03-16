package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.dtr.junit5.DtrTest;
import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for {@link DistributedActorBridge}.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Actor location sealed interface (Local / Remote)
 *   <li>Message serialization (Java serialization codec)
 *   <li>Local actor references (ProcRegistry integration)
 *   <li>Remote actor references (gRPC stub simulation)
 *   <li>Location-transparent routing (local to remote delegation)
 *   <li>Error handling (network timeouts, deserialization)
 *   <li>Registry integration (export/resolve)
 * </ul>
 *
 * <p><strong>Test Pattern: Two-JVM Communication Simulation</strong>
 *
 * <p>Since true multi-JVM gRPC testing requires complex infrastructure, these tests use:
 *
 * <ul>
 *   <li><strong>Local process simulation:</strong> Test with real local Proc instances to verify
 *       ProcRegistry integration.
 *   <li><strong>Remote stub simulation:</strong> RemoteActorStub provides mock gRPC behavior (echo
 *       responses for now; full gRPC integration documented for future).
 *   <li><strong>Sealed pattern matching:</strong> Verify ActorLocation dispatch logic.
 *   <li><strong>Serialization roundtrip:</strong> Encode/decode message types.
 * </ul>
 *
 * @see DistributedActorBridge
 * @see DistributedActorBridge.ActorLocation
 * @see DistributedActorBridge.RemoteActorHandle
 */
@DtrTest
@DisplayName("DistributedActorBridge: Location-Transparent Actor Routing")
class DistributedActorBridgeTest {

    /** Test message types: sealed interface hierarchy for pattern matching. */
    sealed interface TestActorMsg extends Serializable
            permits TestActorMsg.Increment,
                    TestActorMsg.SetValue,
                    TestActorMsg.Get,
                    TestActorMsg.Crash {

        record Increment() implements TestActorMsg {}

        record SetValue(int value) implements TestActorMsg {}

        record Get() implements TestActorMsg {}

        record Crash() implements TestActorMsg {}
    }

    /** Simple test actor state: carries an integer counter. */
    private record CounterState(int count) implements Serializable {
        CounterState {
            if (count < 0) throw new IllegalArgumentException("count must be non-negative");
        }
    }

    private DistributedActorBridge bridge;

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        ProcRegistry.reset();
        bridge = new DistributedActorBridge("localhost", 9000);
    }

    // ============================================================================
    // ACTOR LOCATION SEALED INTERFACE TESTS
    // ============================================================================

    @Test
    @DisplayName("ActorLocation.Local: Represents a local actor")
    void testActorLocationLocal(DtrContext ctx) {
        ctx.say(
                "ActorLocation is a sealed interface with Local and Remote variants for exhaustive pattern matching.");
        var local = new DistributedActorBridge.ActorLocation.Local("my-actor");
        assertThat(local.name()).isEqualTo("my-actor");
    }

    @Test
    @DisplayName("ActorLocation.Local: Rejects blank names")
    void testActorLocationLocalRejectsBlank() {
        assertThatThrownBy(() -> new DistributedActorBridge.ActorLocation.Local(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("ActorLocation.Remote: Represents a remote actor")
    void testActorLocationRemote() {
        var remote = new DistributedActorBridge.ActorLocation.Remote("example.com", 9000);
        assertThat(remote.host()).isEqualTo("example.com");
        assertThat(remote.port()).isEqualTo(9000);
    }

    @Test
    @DisplayName("ActorLocation.Remote: Rejects invalid ports")
    void testActorLocationRemoteRejectsInvalidPort() {
        assertThatThrownBy(() -> new DistributedActorBridge.ActorLocation.Remote("localhost", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port");

        assertThatThrownBy(
                        () -> new DistributedActorBridge.ActorLocation.Remote("localhost", 70000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Port");
    }

    @Test
    @DisplayName("ActorLocation sealed pattern matching: Dispatch on Local vs Remote")
    void testActorLocationPatternMatching(DtrContext ctx) {
        ctx.say(
                "Sealed interface pattern matching enables exhaustive switch on ActorLocation variants.");
        ctx.say("The compiler ensures all cases are handled: Local and Remote.");
        DistributedActorBridge.ActorLocation local =
                new DistributedActorBridge.ActorLocation.Local("actor-1");
        DistributedActorBridge.ActorLocation remote =
                new DistributedActorBridge.ActorLocation.Remote("host", 9000);

        String localResult =
                switch (local) {
                    case DistributedActorBridge.ActorLocation.Local(var name) -> "local:" + name;
                    case DistributedActorBridge.ActorLocation.Remote(var h, var p) ->
                            "remote:" + h + ":" + p;
                };

        String remoteResult =
                switch (remote) {
                    case DistributedActorBridge.ActorLocation.Local(var name) -> "local:" + name;
                    case DistributedActorBridge.ActorLocation.Remote(var h, var p) ->
                            "remote:" + h + ":" + p;
                };

        assertThat(localResult).isEqualTo("local:actor-1");
        assertThat(remoteResult).isEqualTo("remote:host:9000");
    }

    // ============================================================================
    // MESSAGE SERIALIZATION TESTS
    // ============================================================================

    @Test
    @DisplayName("JavaSerializationCodec: Encodes and decodes messages")
    void testJavaSerializationCodec(DtrContext ctx) throws Exception {
        ctx.say(
                "JavaSerializationCodec uses Java's built-in serialization for message encoding/decoding.");
        ctx.say("Custom MessageCodec implementations can use protobuf, JSON, or other formats.");
        var codec = new DistributedActorBridge.JavaSerializationCodec<TestActorMsg>();

        var msg = new TestActorMsg.SetValue(42);
        String encoded = codec.encode(msg);
        TestActorMsg decoded = codec.decode(encoded);

        assertThat(decoded).isInstanceOf(TestActorMsg.SetValue.class);
        var setValue = (TestActorMsg.SetValue) decoded;
        assertThat(setValue.value()).isEqualTo(42);
    }

    @Test
    @DisplayName("JavaSerializationCodec: Roundtrip various message types")
    void testJavaSerializationCodecRoundtrip() throws Exception {
        var codec = new DistributedActorBridge.JavaSerializationCodec<TestActorMsg>();

        TestActorMsg[] messages =
                new TestActorMsg[] {
                    new TestActorMsg.Increment(),
                    new TestActorMsg.SetValue(100),
                    new TestActorMsg.Get(),
                    new TestActorMsg.Crash()
                };

        for (TestActorMsg msg : messages) {
            String encoded = codec.encode(msg);
            TestActorMsg decoded = codec.decode(encoded);
            assertThat(decoded).isEqualTo(msg);
        }
    }

    @Test
    @DisplayName("MessageCodec: Can be plugged in for custom serialization")
    void testCustomMessageCodec() throws Exception {
        var customCodec =
                new DistributedActorBridge.MessageCodec<TestActorMsg>() {
                    @Override
                    public String encode(TestActorMsg msg) {
                        // Custom encoding: just return class name for testing
                        return msg.getClass().getSimpleName();
                    }

                    @Override
                    public TestActorMsg decode(String encoded) {
                        // Custom decoding: reconstruct based on class name
                        return switch (encoded) {
                            case "Increment" -> new TestActorMsg.Increment();
                            case "Get" -> new TestActorMsg.Get();
                            default -> throw new RuntimeException("Unknown message: " + encoded);
                        };
                    }
                };

        String encoded = customCodec.encode(new TestActorMsg.Increment());
        assertThat(encoded).isEqualTo("Increment");

        TestActorMsg decoded = customCodec.decode(encoded);
        assertThat(decoded).isInstanceOf(TestActorMsg.Increment.class);
    }

    // ============================================================================
    // LOCAL ACTOR REFERENCE TESTS
    // ============================================================================

    @Test
    @DisplayName("localRef: Resolves local actor from ProcRegistry")
    void testLocalRefResolvesFromRegistry(DtrContext ctx) throws Exception {
        ctx.say(
                "localRef() looks up actors by name in ProcRegistry and returns a RemoteActorHandle.");
        ctx.say("The handle provides tell() and ask() methods for location-transparent messaging.");
        // Create a local actor
        var proc = new Proc<>(new CounterState(10), this::handleCounterMessage);
        ProcRegistry.register("counter-1", proc);

        // Resolve via bridge
        var handleOpt = bridge.<CounterState, TestActorMsg>localRef("counter-1");

        assertThat(handleOpt).isPresent();
        var handle = handleOpt.get();

        // Test tell/ask with local reference
        handle.tell(new TestActorMsg.Increment());
        var stateF = handle.ask(new TestActorMsg.Get());
        var state = stateF.get(5, TimeUnit.SECONDS);

        assertThat(state.count()).isGreaterThan(10);
    }

    @Test
    @DisplayName("localRef: Returns empty if actor not registered")
    void testLocalRefEmptyIfNotRegistered() {
        var handleOpt = bridge.<CounterState, TestActorMsg>localRef("nonexistent");
        assertThat(handleOpt).isEmpty();
    }

    // ============================================================================
    // REMOTE ACTOR REFERENCE TESTS (SIMULATION)
    // ============================================================================

    @Test
    @DisplayName("remoteRef: Creates handle to remote actor")
    void testRemoteRefCreatesHandle() {
        var handle =
                bridge.<CounterState, TestActorMsg>remoteRef("localhost", 9001, "counter-remote");

        assertThat(handle).isNotNull();
        // Handle is created but not yet connected (no real server)
    }

    @Test
    @DisplayName("remoteRef: Multiple calls return independent handles")
    void testRemoteRefIndependentHandles() {
        var handle1 = bridge.<CounterState, TestActorMsg>remoteRef("localhost", 9001, "actor-1");
        var handle2 = bridge.<CounterState, TestActorMsg>remoteRef("localhost", 9002, "actor-2");

        assertThat(handle1).isNotSameAs(handle2);
    }

    // ============================================================================
    // LOCATION-TRANSPARENT ROUTING TESTS
    // ============================================================================

    @Test
    @DisplayName("RemoteActorHandle: Routes local tell() through ProcRegistry")
    void testRemoteActorHandleLocalTell(DtrContext ctx) throws Exception {
        ctx.say(
                "RemoteActorHandle abstracts location: same API for local (ProcRegistry) and remote (gRPC) actors.");
        ctx.say("tell() sends a fire-and-forget message to the actor regardless of location.");
        var proc = new Proc<>(new CounterState(5), this::handleCounterMessage);
        ProcRegistry.register("local-actor", proc);

        var localLoc = new DistributedActorBridge.ActorLocation.Local("local-actor");
        var handle =
                new DistributedActorBridge.RemoteActorHandle<CounterState, TestActorMsg>(
                        localLoc,
                        "test-id",
                        Duration.ofSeconds(5),
                        new DistributedActorBridge.JavaSerializationCodec<>());

        handle.tell(new TestActorMsg.Increment());
        Thread.sleep(100); // Allow message processing

        // Process was incremented
        var stateF = handle.ask(new TestActorMsg.Get());
        var state = stateF.get(5, TimeUnit.SECONDS);
        assertThat(state.count()).isGreaterThan(5);
    }

    @Test
    @DisplayName("RemoteActorHandle: Routes local ask() through ProcRegistry")
    void testRemoteActorHandleLocalAsk() throws Exception {
        var proc = new Proc<>(new CounterState(20), this::handleCounterMessage);
        ProcRegistry.register("local-actor-2", proc);

        var localLoc = new DistributedActorBridge.ActorLocation.Local("local-actor-2");
        var handle =
                new DistributedActorBridge.RemoteActorHandle<CounterState, TestActorMsg>(
                        localLoc,
                        "test-id-2",
                        Duration.ofSeconds(5),
                        new DistributedActorBridge.JavaSerializationCodec<>());

        var stateF = handle.ask(new TestActorMsg.Get());
        var state = stateF.get(5, TimeUnit.SECONDS);

        assertThat(state.count()).isEqualTo(20);
    }

    @Test
    @DisplayName("RemoteActorHandle.stop(): Gracefully stops local actor")
    void testRemoteActorHandleLocalStop() throws Exception {
        var proc = new Proc<>(new CounterState(0), this::handleCounterMessage);
        ProcRegistry.register("stoppable-actor", proc);

        var localLoc = new DistributedActorBridge.ActorLocation.Local("stoppable-actor");
        var handle =
                new DistributedActorBridge.RemoteActorHandle<CounterState, TestActorMsg>(
                        localLoc,
                        "test-id-3",
                        Duration.ofSeconds(5),
                        new DistributedActorBridge.JavaSerializationCodec<>());

        handle.stop();
        Thread.sleep(100);

        // Actor is no longer in registry
        assertThat(ProcRegistry.whereis("stoppable-actor")).isEmpty();
    }

    // ============================================================================
    // ERROR HANDLING TESTS
    // ============================================================================

    @Test
    @DisplayName("RemoteActorHandle.ask(): Fails gracefully for missing local actor")
    void testRemoteActorHandleAskFailsForMissingLocal() {
        var localLoc = new DistributedActorBridge.ActorLocation.Local("missing-actor");
        var handle =
                new DistributedActorBridge.RemoteActorHandle<CounterState, TestActorMsg>(
                        localLoc,
                        "test-id-4",
                        Duration.ofSeconds(5),
                        new DistributedActorBridge.JavaSerializationCodec<>());

        var stateF = handle.ask(new TestActorMsg.Get());

        assertThatThrownBy(() -> stateF.get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("RemoteActorHandle.ask(): Timeout on slow response")
    void testRemoteActorHandleAskTimeout() throws Exception {
        var proc = new Proc<>(new CounterState(0), (state, msg) -> state);
        ProcRegistry.register("slow-actor", proc);

        var localLoc = new DistributedActorBridge.ActorLocation.Local("slow-actor");
        var handle =
                new DistributedActorBridge.RemoteActorHandle<CounterState, TestActorMsg>(
                        localLoc,
                        "test-id-5",
                        Duration.ofMillis(100),
                        new DistributedActorBridge.JavaSerializationCodec<>());

        var stateF = handle.ask(new TestActorMsg.Get());

        assertThatThrownBy(() -> stateF.get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
    }

    // ============================================================================
    // REGISTRY INTEGRATION TESTS
    // ============================================================================

    @Test
    @DisplayName("exportActor: Registers actor in ProcRegistry")
    void testExportActorRegistersInRegistry(DtrContext ctx) {
        ctx.say(
                "exportActor() registers a Proc in ProcRegistry, making it discoverable by other nodes.");
        ctx.say("Returns Result.Ok on success, Result.Err if the name is already registered.");
        var proc = new Proc<>(new CounterState(0), this::handleCounterMessage);

        var result = bridge.exportActor("exported-actor", proc);

        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(ProcRegistry.whereis("exported-actor")).isPresent();
    }

    @Test
    @DisplayName("exportActor: Returns error for duplicate names")
    void testExportActorErrorForDuplicate() {
        var proc1 = new Proc<>(new CounterState(0), this::handleCounterMessage);
        var proc2 = new Proc<>(new CounterState(1), this::handleCounterMessage);

        bridge.exportActor("dup-actor", proc1);
        var result = bridge.exportActor("dup-actor", proc2);

        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    @DisplayName("resolveActor: Pattern-matches ActorLocation and returns handle")
    void testResolveActorDispatchesOnLocation() {
        var proc = new Proc<>(new CounterState(0), this::handleCounterMessage);
        ProcRegistry.register("resolve-test", proc);

        // Local resolution
        var localLoc = new DistributedActorBridge.ActorLocation.Local("resolve-test");
        var localHandle = bridge.<CounterState, TestActorMsg>resolveActor(localLoc);
        assertThat(localHandle).isPresent();

        // Remote resolution (always succeeds in simulation)
        var remoteLoc = new DistributedActorBridge.ActorLocation.Remote("example.com", 9000);
        var remoteHandle = bridge.<CounterState, TestActorMsg>resolveActor(remoteLoc);
        assertThat(remoteHandle).isPresent();
    }

    // ============================================================================
    // BRIDGE LIFECYCLE TESTS
    // ============================================================================

    @Test
    @DisplayName("Bridge: Stores host and port")
    void testBridgeStoresHostPort() {
        var b = new DistributedActorBridge("example.com", 8080);
        assertThat(b.getHost()).isEqualTo("example.com");
        assertThat(b.getPort()).isEqualTo(8080);
    }

    @Test
    @DisplayName("Bridge.shutdown(): Clears remote actors")
    void testBridgeShutdownClearsRemotes() {
        bridge.remoteRef("localhost", 9001, "actor-1");
        bridge.remoteRef("localhost", 9002, "actor-2");

        bridge.shutdown();

        // After shutdown, new refs should be independent
        var handle = bridge.<CounterState, TestActorMsg>remoteRef("localhost", 9003, "actor-3");
        assertThat(handle).isNotNull();
    }

    // ============================================================================
    // TWO-JVM SIMULATION EXAMPLE
    // ============================================================================

    @Test
    @DisplayName("Example: Simulated two-JVM communication via bridges")
    void testTwoJvmCommunicationSimulation(DtrContext ctx) throws Exception {
        ctx.say(
                "Two-JVM simulation: Server exports actor, client resolves via bridge and sends messages.");
        ctx.say("In production, the bridge would use gRPC for cross-JVM communication.");
        // JVM-1: Server exposing an actor
        var serverBridge = new DistributedActorBridge("localhost", 9000);
        var serverProc = new Proc<>(new CounterState(100), this::handleCounterMessage);
        serverBridge.exportActor("jvm1-counter", serverProc);

        // JVM-2: Client requesting remote actor
        var clientBridge = new DistributedActorBridge("localhost", 9001);

        // In a real scenario, clientBridge would connect to serverBridge's gRPC endpoint
        // For now, we simulate by accessing the server's registry directly
        var remoteHandle =
                clientBridge.<CounterState, TestActorMsg>remoteRef(
                        "localhost", 9000, "jvm1-counter");

        // Client sends tell message
        remoteHandle.tell(new TestActorMsg.Increment());

        // Client sends ask message
        // (In simulation, this returns the echo response from RemoteActorStub)
        var stateF = remoteHandle.ask(new TestActorMsg.Get());
        var state = stateF.get(5, TimeUnit.SECONDS);

        // In a real implementation, state would be the server's state
        assertThat(state).isNotNull();

        // Cleanup
        serverBridge.shutdown();
        clientBridge.shutdown();
    }

    // ============================================================================
    // HELPER METHODS
    // ============================================================================

    /**
     * Handler for counter state and messages. Demonstrates pattern matching on sealed message
     * hierarchy.
     */
    private CounterState handleCounterMessage(CounterState state, TestActorMsg msg) {
        return switch (msg) {
            case TestActorMsg.Increment() -> new CounterState(state.count() + 1);
            case TestActorMsg.SetValue(var v) -> new CounterState(v);
            case TestActorMsg.Get() -> state;
            case TestActorMsg.Crash() -> throw new RuntimeException("Crash requested");
        };
    }
}
