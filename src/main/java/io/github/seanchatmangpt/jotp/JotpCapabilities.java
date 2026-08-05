package io.github.seanchatmangpt.jotp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ontology-generated capability admission catalog.
 *
 * <p>Do not edit this projection. Edit {@code ontology/jotp-capabilities.ttl} and run
 * {@code ggen sync run}.
 */
public final class JotpCapabilities {
    public enum Standing {
        UNKNOWN,
        PARTIAL_ALIVE,
        ALIVE,
        BLOCKED,
        BUILD_BROKEN,
        UNSUPPORTED
    }

    public record Capability(
            int ordinal,
            String id,
            String javaSymbol,
            String domain,
            Standing standing,
            String evidence,
            String sourcePath,
            String testPath,
            String legacyContract,
            String reason) {}

    private static final String DATA =
            """
            1|proc|Proc|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/Proc.java|src/test/java/io/github/seanchatmangpt/jotp/ProcTest.java|otp.process.mailbox.virtual-thread|Exact source and test surfaces are admitted; exact-base execution is not yet observed in this receipt.
            2|supervisor|Supervisor|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/Supervisor.java|src/test/java/io/github/seanchatmangpt/jotp/SupervisorTest.java|otp.supervisor.restart-strategies|ONE_FOR_ONE, ONE_FOR_ALL, and REST_FOR_ONE are admitted; current exact-base execution remains pending.
            3|state-machine|StateMachine|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/StateMachine.java|src/test/java/io/github/seanchatmangpt/jotp/StateMachineTest.java|otp.gen-statem.transition|Typed transition contract is admitted; exact-base Maven execution is pending.
            4|proc-ref|ProcRef|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcRef.java|src/test/java/io/github/seanchatmangpt/jotp/ProcRefTest.java|otp.stable-process-reference|Stable reference contract is admitted; exact-base execution is pending.
            5|proc-monitor|ProcMonitor|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcMonitor.java|src/test/java/io/github/seanchatmangpt/jotp/ProcMonitorTest.java|otp.monitor.down-signal|Monitor contract is admitted; exact-base execution is pending.
            6|proc-link|ProcLink|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcLink.java|src/test/java/io/github/seanchatmangpt/jotp/ProcLinkTest.java|otp.link.exit-propagation|Bidirectional link contract is admitted; exact-base execution is pending.
            7|proc-registry|ProcRegistry|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcRegistry.java|src/test/java/io/github/seanchatmangpt/jotp/ProcRegistryTest.java|otp.registry.whereis|Named-process lookup is admitted; exact-base execution is pending.
            8|proc-timer|ProcTimer|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcTimer.java|src/test/java/io/github/seanchatmangpt/jotp/ProcTimerTest.java|otp.timer.scheduled-message|Scheduled-delivery contract is admitted; exact-base execution is pending.
            9|proc-sys|ProcSys|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcSys.java|src/test/java/io/github/seanchatmangpt/jotp/ProcSysTest.java|otp.system.introspection|Suspend, resume, and statistics contract is admitted; exact-base execution is pending.
            10|proc-lib|ProcLib|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ProcLib.java|src/test/java/io/github/seanchatmangpt/jotp/ProcLibTest.java|otp.proc-lib.init-ack|Initialization handshake contract is admitted; exact-base execution is pending.
            11|crash-recovery|CrashRecovery|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/CrashRecovery.java|src/test/java/io/github/seanchatmangpt/jotp/CrashRecoveryTest.java|otp.crash-isolation.result|Crash isolation is admitted; exact-base execution is pending.
            12|parallel|Parallel|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/Parallel.java|src/test/java/io/github/seanchatmangpt/jotp/ParallelTest.java|otp.structured-concurrency|Structured-concurrency contract is admitted; exact-base execution is pending.
            13|event-manager|EventManager|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/EventManager.java|src/test/java/io/github/seanchatmangpt/jotp/EventManagerTest.java|otp.event-manager.handler-isolation|Typed event broadcast is admitted; exact-base execution is pending.
            14|result|Result|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/Result.java|src/test/java/io/github/seanchatmangpt/jotp/ResultTest.java|otp.result.railway|Sealed railway result is admitted; exact-base execution is pending.
            15|exit-signal|ExitSignal|core|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ExitSignal.java|src/test/java/io/github/seanchatmangpt/jotp/ExitSignalTest.java|otp.exit-signal.trap-exit|Exit reason transport is admitted; exact-base execution is pending.
            16|application-lifecycle|ApplicationController|runtime|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/ApplicationController.java|src/test/java/io/github/seanchatmangpt/jotp/ApplicationControllerTest.java|otp.application.lifecycle|Application start and stop surfaces are admitted; exact-base execution is pending.
            17|service-discovery|ServiceDiscoveryProvider|distributed|PARTIAL_ALIVE|MERGED_IMPLEMENTATION|src/main/java/io/github/seanchatmangpt/jotp/discovery/ServiceDiscoveryProvider.java|src/test/java/io/github/seanchatmangpt/jotp/discovery/StaticNodeProviderTest.java|distributed.discovery.spi|Static, Consul, etcd, and Kubernetes providers were merged; exact-base execution is pending.
            18|global-registry|GlobalProcRegistry|distributed|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/distributed/GlobalProcRegistry.java|src/test/java/io/github/seanchatmangpt/jotp/distributed/GlobalProcRegistryTest.java|distributed.global-registry|Distributed registration surface is admitted; partition behavior requires exact execution.
            19|distributed-saga|DistributedSagaCoordinator|enterprise|PARTIAL_ALIVE|SOURCE_AND_TEST_NAMED|src/main/java/io/github/seanchatmangpt/jotp/distributed/DistributedSagaCoordinator.java|src/test/java/io/github/seanchatmangpt/jotp/distributed/DistributedSagaCoordinatorTest.java|enterprise.saga.compensation|Saga coordination is admitted; infrastructure-dependent closure remains pending.
            20|circuit-breaker|CircuitBreaker|resilience|PARTIAL_ALIVE|SOURCE_NAMED|src/main/java/io/github/seanchatmangpt/jotp/CircuitBreaker.java|src/test/java/io/github/seanchatmangpt/jotp/CircuitBreakerTest.java|resilience.circuit-breaker|Circuit-breaker surface is admitted; exact-base execution is pending.
            21|bulkhead|BulkheadIsolation|resilience|PARTIAL_ALIVE|SOURCE_NAMED|src/main/java/io/github/seanchatmangpt/jotp/BulkheadIsolation.java|src/test/java/io/github/seanchatmangpt/jotp/BulkheadIsolationTest.java|resilience.bulkhead|Bulkhead surface is admitted; exact-base execution is pending.
            22|crash-dump|CrashDumpCollector|evidence|PARTIAL_ALIVE|MERGED_IMPLEMENTATION|src/main/java/io/github/seanchatmangpt/jotp/supervision/crash/CrashDumpCollector.java|src/test/java/io/github/seanchatmangpt/jotp/supervision/crash/CrashDumpCollectorTest.java|evidence.crash-dump|Crash-dump null handling was repaired on main; exact-base execution is pending.
            23|message-replay|MessageRecorder|evidence|PARTIAL_ALIVE|MERGED_IMPLEMENTATION|src/main/java/io/github/seanchatmangpt/jotp/inspection/MessageRecorder.java|src/test/java/io/github/seanchatmangpt/jotp/inspection/MessageRecorderTest.java|evidence.message-replay|Message audit and replay error semantics were repaired on main; exact-base execution is pending.
            24|telemetry|JOTP telemetry|operations|PARTIAL_ALIVE|SOURCE_NAMED|src/main/java/io/github/seanchatmangpt/jotp/observability|src/test/java/io/github/seanchatmangpt/jotp/observability|operations.otel-jmx|OTel and JMX surfaces are admitted as a package; exact-base execution is pending.
            25|spring-boot-bridge|SpringBootIntegration|integration|PARTIAL_ALIVE|RUNNABLE_EXAMPLE_NAMED|src/main/java/io/github/seanchatmangpt/jotp/examples/SpringBootIntegration.java|src/test/java/io/github/seanchatmangpt/jotp/examples/SpringBootIntegrationTest.java|integration.spring-boot|Migration example is admitted; external Spring runtime execution is pending.
            26|failover-state-recovery|FailoverMigrationController.loadProcessStateFromLog|distributed|BLOCKED|TYPED_RUNTIME_REFUSAL|src/main/java/io/github/seanchatmangpt/jotp/failover/FailoverMigrationController.java|src/test/java/io/github/seanchatmangpt/jotp/failover/FailoverMigrationControllerTest.java|distributed.failover.log-replay|Main intentionally throws UnsupportedOperationException; no state-recovery ALIVE claim is admitted.
            27|messaging-system|io.github.seanchatmangpt.jotp.messaging|messaging|UNSUPPORTED|BUILD_EXCLUSION|src/main/java/io/github/seanchatmangpt/jotp/messaging|src/test/java/io/github/seanchatmangpt/jotp/messaging|messaging.transport|pom.xml excludes messaging tests because referenced classes are unimplemented.
            28|message-patterns|io.github.seanchatmangpt.jotp.messagepatterns|messaging|UNSUPPORTED|BUILD_EXCLUSION|src/main/java/io/github/seanchatmangpt/jotp/messagepatterns|src/test/java/io/github/seanchatmangpt/jotp/messagepatterns|messaging.enterprise-patterns|pom.xml excludes message-pattern tests pending the messaging system.
            29|connection-pool|io.github.seanchatmangpt.jotp.pool|resource|UNSUPPORTED|BUILD_EXCLUSION|src/main/java/io/github/seanchatmangpt/jotp/pool|src/test/java/io/github/seanchatmangpt/jotp/pool|resource.connection-pool|pom.xml explicitly excludes pool tests because connection pooling is not implemented.
            30|capability-admission|JotpCapabilities|evidence|ALIVE|GENERATED_EXECUTED_REPLAYED|ontology/jotp-capabilities.ttl|tools/generated/JotpCapabilitiesProbe.java|ggen-legacy.observable-contract|RDF authority deterministically generates the runtime catalog, probe, matrix, and manifest; local replay and negative controls execute this exact subject.
            """;

    private static final List<Capability> ALL =
            DATA.lines().map(JotpCapabilities::parse).toList();

    private static final Map<String, Capability> BY_ID =
            ALL.stream()
                    .collect(Collectors.toUnmodifiableMap(Capability::id, Function.identity()));

    private JotpCapabilities() {}

    private static Capability parse(String line) {
        var fields = line.split("\\|", -1);
        if (fields.length != 10) {
            throw new IllegalStateException("CAPABILITY_ROW_REFUSED:" + line);
        }
        return new Capability(
                Integer.parseInt(fields[0]),
                fields[1],
                fields[2],
                fields[3],
                Standing.valueOf(fields[4]),
                fields[5],
                fields[6],
                fields[7],
                fields[8],
                fields[9]);
    }

    public static List<Capability> all() {
        return ALL;
    }

    public static Optional<Capability> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static List<Capability> gaps() {
        return ALL.stream()
                .filter(
                        capability ->
                                switch (capability.standing()) {
                                    case BLOCKED, BUILD_BROKEN, UNSUPPORTED -> true;
                                    default -> false;
                                })
                .toList();
    }

    public static boolean admitsAlive(String id) {
        return byId(id)
                .map(capability -> capability.standing() == Standing.ALIVE)
                .orElse(false);
    }

    public static void main(String[] args) {
        if (ALL.size() != 30) {
            throw new AssertionError("CAPABILITY_COUNT_MISMATCH:" + ALL.size());
        }
        if (BY_ID.size() != ALL.size()) {
            throw new AssertionError("DUPLICATE_CAPABILITY_ID");
        }
        var expectedGaps =
                List.of(
                        "failover-state-recovery",
                        "messaging-system",
                        "message-patterns",
                        "connection-pool");
        var actualGaps = gaps().stream().map(Capability::id).toList();
        if (!actualGaps.equals(expectedGaps)) {
            throw new AssertionError("GAP_SET_MISMATCH:" + actualGaps);
        }
        if (!admitsAlive("capability-admission")) {
            throw new AssertionError("GENERATED_ADMISSION_NOT_ALIVE");
        }
        System.out.println("SUBJECT_ALIVE capabilities=" + ALL.size() + " gaps=" + actualGaps.size());
    }
}
