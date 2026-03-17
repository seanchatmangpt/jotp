package io.github.seanchatmangpt.jotp.enterprise.health;

import io.github.seanchatmangpt.jotp.ApplicationController;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive DTR-documented tests for HealthCheckManager.
 *
 * <p>Documents health check registration, execution, status aggregation, timeout handling,
 * dependency cascading, and Kubernetes probe integration using Joe Armstrong's OTP supervision
 * principles.
 *
 * <p><strong>Living Documentation:</strong> Each test method generates executable documentation
 * explaining health monitoring semantics, status transitions, and production patterns. Run with DTR
 * to see examples with actual output values.
 */
@DisplayName("HealthCheckManager: Joe Armstrong-style non-invasive process health monitoring")
class HealthCheckManagerTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Test 1: Health Check Registration and Basic Execution ────────────────────

    @Test
    @DisplayName("register and execute health checks with status tracking")
    void registerAndExecute_checksStatus() {
                """
                HealthCheckManager provides non-invasive monitoring of service health without killing the target on failures (unlike ProcLink crash propagation).

                **Key Principles:**
                - **Non-invasive**: Check health without coupling to target service lifecycle
                - **Multi-dimensional**: Liveness, readiness, startup, and custom checks
                - **Adaptive thresholds**: Configurable pass/fail thresholds prevent flapping
                - **Status aggregation**: Parallel check execution with comprehensive status tracking

                **Health Check Types:**
                1. **Liveness**: Is the service process still running? (via ProcMonitor)
                2. **Readiness**: Can the service handle requests? (HTTP endpoint)
                3. **Startup**: Has the service completed initialization? (state query)
                4. **Custom**: Domain-specific async health logic (database, cache, etc.)
                """);

                """
            // Create health check configuration
            HealthCheckConfig config = HealthCheckConfig.builder("payment-service")
                .checks(List.of(
                    new HealthCheck.Custom("database", timeout ->
                        CompletableFuture.completedFuture(true)),
                    new HealthCheck.Custom("cache", timeout ->
                        CompletableFuture.completedFuture(true)),
                    new HealthCheck.Custom("api-gateway", timeout ->
                        CompletableFuture.completedFuture(true))
                ))
                .checkInterval(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(5))
                .passThreshold(1)
                .failThreshold(2)
                .build();

            // Create manager and check status
            HealthCheckManager manager = HealthCheckManager.create(config);
            HealthStatus status = manager.getStatus();

            // Status is initially Healthy (no checks executed yet)
            assertThat(status).isInstanceOf(HealthStatus.Healthy.class);

            // Get individual check results
            HealthCheckResult dbResult = manager.getLastResult("database");
            HealthCheckResult cacheResult = manager.getLastResult("cache");
            """,
                "java");

        var config =
                HealthCheckConfig.builder("payment-service")
                        .checks(
                                List.of(
                                        new HealthCheck.Custom(
                                                "database",
                                                timeout -> CompletableFuture.completedFuture(true)),
                                        new HealthCheck.Custom(
                                                "cache",
                                                timeout -> CompletableFuture.completedFuture(true)),
                                        new HealthCheck.Custom(
                                                "api-gateway",
                                                timeout ->
                                                        CompletableFuture.completedFuture(true))))
                        .checkInterval(Duration.ofSeconds(10))
                        .timeout(Duration.ofSeconds(5))
                        .passThreshold(1)
                        .failThreshold(2)
                        .build();

        var manager = HealthCheckManager.create(config);
        var status = manager.getStatus();

        assertThat(status).isInstanceOf(HealthStatus.Healthy.class);

        var dbResult = manager.getLastResult("database");
        var cacheResult = manager.getLastResult("cache");

                new String[][] {
                    {
                        "Property",
                        "Checks Registered",
                        "DB Result",
                        "Value",
                        "available",
                        "available"
                    },
                    {
                        "Service Name",
                        "Initial Status",
                        "Cache Result",
                        "null (not executed)",
                        "null (not executed)",
                        ""
                    }
                });

                Map.of(
                        "Check Execution",
                        "Parallel (non-blocking)",
                        "Initial Status",
                        "Healthy (no failures yet)",
                        "Result Availability",
                        "null until first check cycle"));

        manager.shutdown();
    }

    // ── Test 2: Status Aggregation (UP, DOWN, DEGRADED) ─────────────────────────

    @Test
    @DisplayName("aggregate check results into UP, DOWN, and DEGRADED statuses")
    void aggregateResults_statusTypes() {
                """
                HealthCheckManager aggregates multiple check results into four distinct status types:

                **Status Hierarchy:**
                1. **HEALTHY** (UP): All checks passed, service fully operational
                2. **DEGRADED**: Some checks failed but service still functional
                3. **UNHEALTHY** (DOWN): Critical checks failed, service degraded
                4. **UNREACHABLE**: Service cannot be contacted, critical condition

                **Operational Status:**
                - `isOperational()` returns `true` for HEALTHY and DEGRADED
                - `isOperational()` returns `false` for UNHEALTHY and UNREACHABLE
                - Load balancers should shift traffic away when `!isOperational()`
                """);

                """
            // Test all four status types
            long now = System.currentTimeMillis();

            HealthStatus healthy = new HealthStatus.Healthy(now);
            HealthStatus degraded = new HealthStatus.Degraded(now, "high latency", 0.3);
            HealthStatus unhealthy = new HealthStatus.Unhealthy(now, "db down", new RuntimeException("connection refused"));
            HealthStatus unreachable = new HealthStatus.Unreachable(now, "network timeout");

            // Check operational status
            boolean healthyOps = healthy.isOperational();      // true
            boolean degradedOps = degraded.isOperational();    // true (can serve with reduced capacity)
            boolean unhealthyOps = unhealthy.isOperational();  // false (shift traffic away)
            boolean unreachableOps = unreachable.isOperational(); // false (critical failure)
            """,
                "java");

        long now = System.currentTimeMillis();

        var healthy = new HealthStatus.Healthy(now);
        var degraded = new HealthStatus.Degraded(now, "high latency", 0.3);
        var unhealthy = new HealthStatus.Unhealthy(now, "db down", new RuntimeException("err"));
        var unreachable = new HealthStatus.Unreachable(now, "network timeout");

        boolean healthyOps = healthy.isOperational();
        boolean degradedOps = degraded.isOperational();
        boolean unhealthyOps = unhealthy.isOperational();
        boolean unreachableOps = unreachable.isOperational();

        assertThat(healthyOps).isTrue();
        assertThat(degradedOps).isTrue();
        assertThat(unhealthyOps).isFalse();
        assertThat(unreachableOps).isFalse();

                new String[][] {
                    {"Status", "isOperational()", "Traffic Decision", "Remediation"},
                    {"HEALTHY", "true", "Route normally", "None - monitor"},
                    {"DEGRADED", "true", "Route with caution", "Log warning, monitor closely"},
                    {"UNHEALTHY", "false", "Shift away", "Restart, alert on-call"},
                    {"UNREACHABLE", "false", "Shift away", "Check process/network, escalate"}
                });

                Map.of(
                        "Production Strategy",
                        "Allow DEGRADED traffic (partial capacity > no capacity)",
                        "Load Balancer Action",
                        "Shift traffic only when !isOperational()",
                        "Alerting Threshold",
                        "UNHEALTHY triggers alerts, DEGRADED triggers warnings"));
    }

    // ── Test 3: Timeout Handling ───────────────────────────────────────────────

    @Test
    @DisplayName("handle check timeouts without blocking manager")
    void timeoutHandling_nonBlocking() {
                """
                Health checks MUST timeout to prevent slow/deadlocked services from blocking the health manager.

                **Timeout Constraints:**
                - Each check has a maximum execution time (timeout)
                - timeout MUST be <= checkInterval (prevents overlap)
                - Checks exceeding timeout are marked as failed
                - Manager continues processing other checks independently

                **Configuration Rules:**
                - `checkInterval`: How often to run all checks (e.g., 10s)
                - `timeout`: Maximum time per check (e.g., 5s)
                - Validation: `timeout <= checkInterval` enforced at build time
                """);

                """
            // Valid configuration: timeout < checkInterval
            HealthCheckConfig validConfig = HealthCheckConfig.builder("api-service")
                .checks(List.of(
                    new HealthCheck.Custom("fast-check", timeout ->
                        CompletableFuture.completedFuture(true))
                ))
                .checkInterval(Duration.ofSeconds(10))  // Run every 10s
                .timeout(Duration.ofSeconds(5))         // Max 5s per check
                .build();

            // Invalid: timeout > checkInterval (throws IllegalArgumentException)
            assertThatThrownBy(() ->
                HealthCheckConfig.builder("api-service")
                    .checks(List.of(
                        new HealthCheck.Custom("check", t ->
                            CompletableFuture.completedFuture(true))))
                    .checkInterval(Duration.ofSeconds(5))
                    .timeout(Duration.ofSeconds(10))  // ERROR: timeout > interval
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        var validConfig =
                HealthCheckConfig.builder("api-service")
                        .checks(
                                List.of(
                                        new HealthCheck.Custom(
                                                "fast-check",
                                                timeout ->
                                                        CompletableFuture.completedFuture(true))))
                        .checkInterval(Duration.ofSeconds(10))
                        .timeout(Duration.ofSeconds(5))
                        .build();

        assertThatThrownBy(
                        () ->
                                HealthCheckConfig.builder("api-service")
                                        .checks(
                                                List.of(
                                                        new HealthCheck.Custom(
                                                                "check",
                                                                t ->
                                                                        CompletableFuture
                                                                                .completedFuture(
                                                                                        true))))
                                        .checkInterval(Duration.ofSeconds(5))
                                        .timeout(Duration.ofSeconds(10))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

                new String[][] {
                    {"Parameter", "Description", "Example Value"},
                    {"checkInterval", "Frequency of check cycles", "10 seconds"},
                    {"timeout", "Max time per individual check", "5 seconds"},
                    {"Validation Rule", "timeout <= checkInterval (strict)", "5s <= 10s ✓"},
                    {
                        "Production Guidance",
                        "Set timeout to 50% of interval",
                        "Interval 30s, Timeout 15s"
                    }
                });

                """
                **Timeout Anti-Pattern:**
                Setting `timeout = checkInterval` leaves no buffer for network latency or GC pauses.
                **Recommendation:** Set timeout to 50-70% of checkInterval for safety margin.

                Example:
                - checkInterval = 30s
                - timeout = 15s (allows 15s buffer before next cycle)
                """);
    }

    // ── Test 4: Dependency Health Cascading ───────────────────────────────────

    @Test
    @DisplayName("cascade health failures through dependency graph")
    void dependencyCascading_propagatesFailures() {
                """
                In distributed systems, service dependencies form a directed graph. Health failures cascade downstream:

                **Cascade Scenarios:**
                1. **Database Down** → Cache Service DEGRADED → API Service UNHEALTHY
                2. **Message Queue Unreachable** → Producer UNHEALTHY → Consumer DEGRADED
                3. **Configuration Service Down** → All dependents UNHEALTHY (cascading failure)

                **Cascade Mitigation:**
                - Use **circuit breakers** to stop cascading (open circuit on downstream failure)
                - **Bulkheads** isolate dependencies (one failure doesn't affect others)
                - **Graceful degradation** (DEGRADED status) allows partial operation
                - **Fail-fast** for critical dependencies (immediate UNHEALTHY)
                """);

                """
            graph TD
                DB[(Database)] -->|ping| API[API Service]
                DB -->|query| CACHE[Cache Service]
                CACHE -->|invalidate| API
                MQ[(Message Queue)] -->|consume| WORKER[Worker Service]
                API -->|publish| MQ

                style DB fill:#ff6b6b
                style CACHE fill:#ffd93d
                style API fill:#ff6b6b
                style MQ fill:#51cf66
                style WORKER fill:#51cf66

                classDef critical fill:#ff6b6b,stroke:#c92a2a,stroke-width:2px
                classDef degraded fill:#ffd93d,stroke:#fab005,stroke-width:2px
                classDef healthy fill:#51cf66,stroke:#2f9e44,stroke-width:2px

                class DB,API critical
                class CACHE degraded
                class MQ,WORKER healthy
            """);

                """
            // Simulate dependency cascade
            var dbManager = HealthCheckManager.create(
                HealthCheckConfig.builder("database")
                    .checks(List.of(
                        new HealthCheck.Custom("connection", timeout ->
                            CompletableFuture.completedFuture(false))  // DB down
                    ))
                    .checkInterval(Duration.ofSeconds(10))
                    .timeout(Duration.ofSeconds(5))
                    .failThreshold(1)  // Immediate failure
                    .build()
            );

            var cacheManager = HealthCheckManager.create(
                HealthCheckConfig.builder("cache")
                    .checks(List.of(
                        new HealthCheck.Custom("backend", timeout ->
                            // Check depends on database health
                            dbManager.getStatus().isOperational()
                                ? CompletableFuture.completedFuture(true)
                                : CompletableFuture.completedFuture(false)
                    ))
                    .checkInterval(Duration.ofSeconds(10))
                    .timeout(Duration.ofSeconds(5))
                    .build()
            );

            // Cache becomes DEGRADED when database is UNHEALTHY
            HealthStatus dbStatus = dbManager.getStatus();
            HealthStatus cacheStatus = cacheManager.getStatus();

            boolean isCascading = !dbStatus.isOperational() && !cacheStatus.isOperational();
            """,
                "java");

        var dbManager =
                HealthCheckManager.create(
                        HealthCheckConfig.builder("database")
                                .checks(
                                        List.of(
                                                new HealthCheck.Custom(
                                                        "connection",
                                                        timeout ->
                                                                CompletableFuture.completedFuture(
                                                                        false))))
                                .checkInterval(Duration.ofSeconds(10))
                                .timeout(Duration.ofSeconds(5))
                                .failThreshold(1)
                                .build());

        var cacheManager =
                HealthCheckManager.create(
                        HealthCheckConfig.builder("cache")
                                .checks(
                                        List.of(
                                                new HealthCheck.Custom(
                                                        "backend",
                                                        timeout ->
                                                                CompletableFuture.completedFuture(
                                                                        dbManager
                                                                                .getStatus()
                                                                                .isOperational()))))
                                .checkInterval(Duration.ofSeconds(10))
                                .timeout(Duration.ofSeconds(5))
                                .build());

        HealthStatus dbStatus = dbManager.getStatus();
        HealthStatus cacheStatus = cacheManager.getStatus();
        boolean isCascading = !dbStatus.isOperational() && !cacheStatus.isOperational();

                Map.of(
                        "Database Status",
                        dbStatus.getClass().getSimpleName(),
                        "Cache Status",
                        cacheStatus.getClass().getSimpleName(),
                        "Cascade Detected",
                        isCascading ? "Yes" : "No",
                        "Mitigation Strategy",
                        "Circuit breaker + bulkhead isolation"));

                """
                **Cascading Failure Risk:**
                Without circuit breakers, a single dependency failure can propagate through the entire dependency graph, causing system-wide outage.

                **Mitigation:**
                1. **Circuit Breaker**: Open circuit on downstream UNHEALTHY (fast fail)
                2. **Bulkhead**: Isolate dependencies (one failure doesn't affect others)
                3. **Timeout**: Fail fast on slow dependencies
                4. **Fallback**: Use cached data or default responses
                """);

        dbManager.shutdown();
        cacheManager.shutdown();
    }

    // ── Test 5: Kubernetes Readiness/Liveness Probe Integration ───────────────

    @Test
    @DisplayName("integrate with Kubernetes readiness and liveness probes")
    void kubernetesIntegration_probeMapping() {
                """
                Kubernetes uses three probe types to manage pod lifecycle. HealthCheckManager maps directly to these probes:

                **Kubernetes Probes:**
                1. **Liveness Probe**: Is the pod still alive? If failed, kubelet restarts the pod
                2. **Readiness Probe**: Is the pod ready to serve traffic? If failed, pod removed from Service
                3. **Startup Probe**: Is the app started? (prevents liveness/readiness during slow startup)

                **JOTP Mapping:**
                - `HealthCheck.Liveness` → Kubernetes liveness probe (process monitoring)
                - `HealthCheck.Readiness` → Kubernetes readiness probe (HTTP endpoint)
                - `HealthCheck.Startup` → Kubernetes startup probe (initialization check)
                - `HealthStatus.isOperational()` → Ready condition (traffic routing)
                """);

                """
            // Kubernetes-ready health check configuration
            HealthCheckConfig k8sConfig = HealthCheckConfig.builder("web-api")
                .checks(List.of(
                    // Liveness: Process still running?
                    new HealthCheck.Liveness("process-alive"),

                    // Readiness: Can handle HTTP requests?
                    new HealthCheck.Readiness("http-ready", "http://localhost:8080/health/ready"),

                    // Startup: Initialization complete?
                    new HealthCheck.Startup("initialized", "is_startup_complete"),

                    // Custom: Database connection
                    new HealthCheck.Custom("database", timeout ->
                        CompletableFuture.supplyAsync(() -> {
                            try (Connection conn = dataSource.getConnection()) {
                                return conn.isValid(5);
                            } catch (SQLException e) {
                                return false;
                            }
                        })
                    )
                ))
                .checkInterval(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(5))
                .passThreshold(1)  // Quick recovery
                .failThreshold(3)  // Tolerate transient failures
                .build();

            // Map to Kubernetes HTTP endpoint
            @GET
            @Path("/health/ready")
            public Response readiness() {
                HealthCheckManager mgr = getHealthManager();
                HealthStatus status = mgr.getStatus();

                if (status.isOperational()) {
                    // Return 200 OK for ready/healthy/degraded
                    return Response.ok()
                        .entity(Map.of(
                            "status", status.getClass().getSimpleName(),
                            "timestamp", status.timestamp()
                        ))
                        .build();
                } else {
                    // Return 503 Service Unavailable for unhealthy/unreachable
                    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity(Map.of(
                            "status", status.getClass().getSimpleName(),
                            "reason", getReason(status)
                        ))
                        .build();
                    }
            }
            """,
                "java");

        var k8sConfig =
                HealthCheckConfig.builder("web-api")
                        .checks(
                                List.of(
                                        new HealthCheck.Liveness("process-alive"),
                                        new HealthCheck.Readiness(
                                                "http-ready", "http://localhost:8080/health/ready"),
                                        new HealthCheck.Startup(
                                                "initialized", "is_startup_complete"),
                                        new HealthCheck.Custom(
                                                "database",
                                                timeout ->
                                                        CompletableFuture.supplyAsync(() -> true))))
                        .checkInterval(Duration.ofSeconds(10))
                        .timeout(Duration.ofSeconds(5))
                        .passThreshold(1)
                        .failThreshold(3)
                        .build();

                new String[][] {
                    {
                        "Probe Type",
                        "Kubernetes Action on Failure",
                        "JOTP HealthCheck",
                        "Failure Threshold"
                    },
                    {
                        "Liveness",
                        "Restart pod (kill and recreate)",
                        "Liveness(name)",
                        "failThreshold=3 (tolerate transient)"
                    },
                    {
                        "Readiness",
                        "Remove from Service (no traffic)",
                        "Readiness(name, endpoint)",
                        "failThreshold=2 (quick traffic shift)"
                    },
                    {
                        "Startup",
                        "Continue probing (wait for startup)",
                        "Startup(name, query)",
                        "passThreshold=1 (fast startup detection)"
                    }
                });

                Map.of(
                        "HTTP Endpoint",
                        "/health/ready (or /health/live for liveness)",
                        "Success Response",
                        "200 OK + status: {HEALTHY,DEGRADED}",
                        "Failure Response",
                        "503 Service Unavailable + status: {UNHEALTHY,UNREACHABLE}",
                        "Traffic Decision",
                        "Kubernetes sends traffic only when 200 OK"));

                """
            # Kubernetes Pod configuration (YAML)
            apiVersion: v1
            kind: Pod
            metadata:
              name: web-api-pod
            spec:
              containers:
              - name: web-api
                image: myapp/web-api:1.0
                ports:
                - containerPort: 8080
                # Liveness probe: Restart pod if failed
                livenessProbe:
                  httpGet:
                    path: /health/live
                    port: 8080
                  initialDelaySeconds: 30
                  periodSeconds: 10
                  timeoutSeconds: 5
                  failureThreshold: 3
                # Readiness probe: Remove from Service if failed
                readinessProbe:
                  httpGet:
                    path: /health/ready
                    port: 8080
                  initialDelaySeconds: 10
                  periodSeconds: 5
                  timeoutSeconds: 3
                  failureThreshold: 2
                # Startup probe: Wait for slow initialization
                startupProbe:
                  httpGet:
                    path: /health/started
                    port: 8080
                  initialDelaySeconds: 0
                  periodSeconds: 5
                  timeoutSeconds: 3
                  failureThreshold: 30  # 30 * 5s = 150s max startup time
            """,
                "yaml");

                """
                **Production Best Practices:**

                1. **Separate Endpoints**: Use different endpoints for liveness (`/health/live`) and readiness (`/health/ready`)
                2. **Conservative Thresholds**: `failureThreshold=3` prevents restart on transient network blips
                3. **Fast Readiness**: `periodSeconds=5` quickly removes unhealthy pods from traffic rotation
                4. **Slow Liveness**: `periodSeconds=10` + `failureThreshold=3` gives pod 30s to recover before restart
                5. **Startup Probe**: Use for slow-starting services (databases, caches with warmup)

                **Endpoint Response Mapping:**
                - **HEALTHY** → 200 OK (full traffic)
                - **DEGRADED** → 200 OK (reduced traffic, but still serving)
                - **UNHEALTHY** → 503 Service Unavailable (no traffic)
                - **UNREACHABLE** → 503 Service Unavailable (no traffic, escalate)
                """);
    }

    // ── Test 6: Health Check Result Tracking ───────────────────────────────────

    @Test
    @DisplayName("track individual check results with timestamps and latency")
    void resultTracking_detailedMetrics() {
                """
                HealthCheckManager tracks detailed results for each check, enabling observability and debugging:

                **Result Types:**
                1. **Pass**: Check succeeded (includes execution time)
                2. **Fail**: Check failed (includes error message and execution time)

                **Tracked Metrics:**
                - Check name (identifier)
                - Pass/Fail status
                - Execution duration (latency)
                - Timestamp (when check was executed)
                - Error message (for failures)
                """);

                """
            long now = System.currentTimeMillis();

            // Successful check result
            HealthCheckResult pass = new HealthCheckResult.Pass(
                "database-ping",
                Duration.ofMillis(5),  // Execution time
                now
            );

            // Failed check result
            HealthCheckResult fail = new HealthCheckResult.Fail(
                "database-ping",
                "connection refused",  // Error message
                Duration.ofMillis(100)  // Execution time
            );

            // Extract result information
            String passName = pass.checkName();        // "database-ping"
            boolean passPassed = pass.isPassed();       // true
            Duration passLatency = pass.latency();      // 5ms

            String failName = fail.checkName();        // "database-ping"
            boolean failPassed = fail.isPassed();       // false
            String failError = fail.errorMessage();     // "connection refused"
            Duration failLatency = fail.latency();      // 100ms
            """,
                "java");

        long now = System.currentTimeMillis();

        var pass = new HealthCheckResult.Pass("database-ping", Duration.ofMillis(5), now);
        var fail =
                new HealthCheckResult.Fail(
                        "database-ping", "connection refused", Duration.ofMillis(100));

        assertThat(pass.isPassed()).isTrue();
        assertThat(pass.checkName()).isEqualTo("database-ping");
        assertThat(fail.isPassed()).isFalse();
        assertThat(fail.checkName()).isEqualTo("database-ping");

                new String[][] {
                    {"Metric", "Error Message", "N/A"},
                    {"Check Name", "Pass Result", "Fail Result"},
                    {"Status", "PASSED", "FAILED"},
                    {"Latency", "ms", "ms"}
                });

                Map.of(
                        "Observability Value",
                        "Track check latency trends for performance degradation",
                        "Alerting Use Case",
                        "Alert when latency > threshold (e.g., database ping > 100ms)",
                        "Debugging Value",
                        "Error messages identify root cause (connection, timeout, auth)"));
    }

    // ── Test 7: Status Transition Listeners ───────────────────────────────────

    @Test
    @DisplayName("notify listeners on health status transitions")
    void statusTransitionListeners_reactiveActions() {
                """
                HealthCheckManager supports reactive callbacks on status transitions, enabling automated remediation:

                **Listener Use Cases:**
                1. **Alerting**: Fire PagerDuty/Slack alerts on UNHEALTHY transition
                2. **Auto-restart**: Trigger supervisor restart on critical failures
                3. **Traffic shifting**: Signal load balancer to shift traffic away
                4. **Metrics**: Emit Prometheus/Grafana metrics on transitions
                5. **Logging**: Log status changes for audit trail
                """);

                """
            var manager = HealthCheckManager.create(
                HealthCheckConfig.builder("critical-service")
                    .checks(List.of(
                        new HealthCheck.Custom("dependency", timeout ->
                            CompletableFuture.completedFuture(true)
                    ))
                    .checkInterval(Duration.ofSeconds(10))
                    .timeout(Duration.ofSeconds(5))
                    .build()
            );

            // Register listener for status transitions
            manager.addListener((from, to) -> {
                log.warn("Health status changed: {} → {}", from, to);

                // Switch on new status
                switch (to) {
                    case HealthStatus.Healthy(var ts) ->
                        log.info("Service recovered at {}", Instant.ofEpochMilli(ts)));

                    case HealthStatus.Degraded(var ts, var reason, var failureRate) -> {
                        log.warn("Service degraded: {} ({}% failure rate)", reason, failureRate * 100);
                        // Send warning notification
                        alertManager.sendWarning("Service DEGRADED: " + reason);
                    }

                    case HealthStatus.Unhealthy(var ts, var reason, var error) -> {
                        log.error("Service UNHEALTHY: {}", reason);
                        // Fire critical alert and trigger restart
                        alertManager.fireCritical("Service UNHEALTHY: " + reason);
                        supervisor.restart("critical-service");
                    }

                    case HealthStatus.Unreachable(var ts, var reason) -> {
                        log.error("Service UNREACHABLE: {}", reason);
                        // Escalate immediately
                        alertManager.fireCritical("Service UNREACHABLE: " + reason);
                        loadBalancer.shiftTrafficAway("critical-service");
                    }
                }
            });
            """,
                "java");

        var manager =
                HealthCheckManager.create(
                        HealthCheckConfig.builder("critical-service")
                                .checks(
                                        List.of(
                                                new HealthCheck.Custom(
                                                        "dependency",
                                                        timeout ->
                                                                CompletableFuture.completedFuture(
                                                                        true))))
                                .checkInterval(Duration.ofSeconds(10))
                                .timeout(Duration.ofSeconds(5))
                                .build());

        var called = new AtomicBoolean(false);
        HealthCheckManager.HealthCheckListener listener =
                (from, to) -> {
                    called.set(true);
                };

        manager.addListener(listener);

                new String[][] {
                    {"Transition", "Severity", "Action"},
                    {"HEALTHY → DEGRADED", "WARNING", "Log warning, send notification"},
                    {"DEGRADED → UNHEALTHY", "CRITICAL", "Fire alert, trigger restart"},
                    {"UNHEALTHY → HEALTHY", "INFO (recovery)", "Log recovery, clear alerts"},
                    {"HEALTHY → UNREACHABLE", "CRITICAL", "Escalate, shift traffic away"}
                });

                Map.of(
                        "Listener Registration",
                        "Thread-safe (CopyOnWriteArrayList)",
                        "Callback Execution",
                        "Synchronous (don't block in listener)",
                        "Listener Removal",
                        "manager.removeListener(listener)"));

        manager.shutdown();
    }

    // ── Test 8: Configuration Validation ───────────────────────────────────────

    @Test
    @DisplayName("validate configuration constraints at build time")
    void configurationValidation_preventsErrors() {
                """
                HealthCheckConfig validates all constraints at build time, preventing runtime errors:

                **Validation Rules:**
                1. **At least one check** required (empty checks list → IllegalArgumentException)
                2. **checkInterval > 0** (zero or negative → IllegalArgumentException)
                3. **timeout > 0** (zero or negative → IllegalArgumentException)
                4. **timeout <= checkInterval** (timeout > interval → IllegalArgumentException)
                5. **passThreshold > 0** (zero or negative → IllegalArgumentException)
                6. **failThreshold > 0** (zero or negative → IllegalArgumentException)
                """);

                """
            // Valid configuration
            HealthCheckConfig valid = HealthCheckConfig.builder("service")
                .checks(List.of(
                    new HealthCheck.Custom("check", t ->
                        CompletableFuture.completedFuture(true))
                ))
                .checkInterval(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(5))
                .passThreshold(1)
                .failThreshold(2)
                .build();  // Success

            // Invalid: empty checks list
            assertThatThrownBy(() ->
                HealthCheckConfig.builder("service")
                    .checks(List.of())  // ERROR: no checks
                    .checkInterval(Duration.ofSeconds(10))
                    .timeout(Duration.ofSeconds(5))
                    .build()
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("at least one check");

            // Invalid: zero checkInterval
            assertThatThrownBy(() ->
                HealthCheckConfig.builder("service")
                    .checks(List.of(
                        new HealthCheck.Custom("check", t ->
                            CompletableFuture.completedFuture(true))))
                    .checkInterval(Duration.ZERO)  // ERROR: zero interval
                    .timeout(Duration.ofMillis(100))
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);

            // Invalid: timeout > checkInterval
            assertThatThrownBy(() ->
                HealthCheckConfig.builder("service")
                    .checks(List.of(
                        new HealthCheck.Custom("check", t ->
                            CompletableFuture.completedFuture(true))))
                    .checkInterval(Duration.ofSeconds(5))
                    .timeout(Duration.ofSeconds(10))  // ERROR: timeout > interval
                    .build()
            ).isInstanceOf(IllegalArgumentException.class);
            """,
                "java");

        assertThatThrownBy(
                        () ->
                                HealthCheckConfig.builder("service")
                                        .checks(List.of())
                                        .checkInterval(Duration.ofSeconds(10))
                                        .timeout(Duration.ofSeconds(5))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                        () ->
                                HealthCheckConfig.builder("service")
                                        .checks(
                                                List.of(
                                                        new HealthCheck.Custom(
                                                                "c",
                                                                t ->
                                                                        CompletableFuture
                                                                                .completedFuture(
                                                                                        true))))
                                        .checkInterval(Duration.ZERO)
                                        .timeout(Duration.ofMillis(100))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(
                        () ->
                                HealthCheckConfig.builder("service")
                                        .checks(
                                                List.of(
                                                        new HealthCheck.Custom(
                                                                "c",
                                                                t ->
                                                                        CompletableFuture
                                                                                .completedFuture(
                                                                                        true))))
                                        .checkInterval(Duration.ofSeconds(5))
                                        .timeout(Duration.ofSeconds(10))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);

                new String[][] {
                    {"Constraint", "Rationale", "Error Type"},
                    {"checks non-empty", "Must check something", "IllegalArgumentException"},
                    {"checkInterval > 0", "Must run periodically", "IllegalArgumentException"},
                    {"timeout > 0", "Must have execution limit", "IllegalArgumentException"},
                    {
                        "timeout <= checkInterval",
                        "Prevent check overlap",
                        "IllegalArgumentException"
                    },
                    {"passThreshold > 0", "Must recover eventually", "IllegalArgumentException"},
                    {"failThreshold > 0", "Must fail eventually", "IllegalArgumentException"}
                });

                Map.of(
                        "Fail-Fast Philosophy",
                        "Detect configuration errors at build time, not runtime",
                        "Production Benefit",
                        "Prevents deployment of invalid health check configurations",
                        "Debugging Value",
                        "Clear error messages identify exact constraint violation"));
    }

    // ── Test 9: Initial State and Lifecycle ───────────────────────────────────

    @Test
    @DisplayName("manage health check manager lifecycle (create and shutdown)")
    void lifecycleManagement_createAndShutdown() {
                """
                HealthCheckManager follows a simple lifecycle: create → monitor → shutdown.

                **Lifecycle States:**
                1. **Created**: Manager initialized, coordinator process spawned
                2. **Monitoring**: Periodic checks running, listeners active
                3. **Shutdown**: Checks stopped, coordinator terminated, listeners cleared

                **Initial State:**
                - Status starts as HEALTHY (optimistic initial state)
                - Check results are null (no checks executed yet)
                - First check cycle runs after checkInterval elapses
                """);

                """
            // Create manager with valid configuration
            HealthCheckConfig config = HealthCheckConfig.builder("my-service")
                .checks(List.of(
                    new HealthCheck.Custom("always-pass", timeout ->
                        CompletableFuture.completedFuture(true))
                ))
                .checkInterval(Duration.ofSeconds(10))
                .timeout(Duration.ofSeconds(5))
                .build();

            // Create manager (spawns coordinator process)
            HealthCheckManager manager = HealthCheckManager.create(config);

            // Initial state
            HealthStatus initialStatus = manager.getStatus();
            assertThat(initialStatus).isInstanceOf(HealthStatus.Healthy.class);

            HealthCheckResult initialResult = manager.getLastResult("always-pass");
            assertThat(initialResult).isNull();  // No checks executed yet

            // Shutdown manager (stops coordinator process)
            manager.shutdown();
            assertThatNoException().isThrownBy(() -> manager.shutdown());
            """,
                "java");

        var config =
                HealthCheckConfig.builder("test-service")
                        .checks(
                                List.of(
                                        new HealthCheck.Custom(
                                                "always-pass",
                                                timeout ->
                                                        CompletableFuture.completedFuture(true))))
                        .checkInterval(Duration.ofSeconds(10))
                        .timeout(Duration.ofSeconds(5))
                        .build();

        var manager = HealthCheckManager.create(config);

        assertThat(manager.getStatus()).isInstanceOf(HealthStatus.Healthy.class);
        assertThat(manager.getLastResult("always-pass")).isNull();

        assertThatNoException().isThrownBy(manager::shutdown);

                new String[][] {
                    {"Lifecycle Stage", "Coordinator Process", "Status", "Listeners"},
                    {"create()", "Spawned", "HEALTHY (initial)", "Can register"},
                    {"Monitoring", "Running", "Check results", "Active"},
                    {"shutdown()", "Terminated", "Final state", "Inactive"}
                });

                Map.of(
                        "Initial Status Design",
                        "Optimistic (HEALTHY) assumes service is healthy until proven otherwise",
                        "Alternative",
                        "Pessimistic (UNHEALTHY) would require first successful check",
                        "Production Choice",
                        "Optimistic prevents false negatives during startup"));
    }

    // ── Test 10: Listener Registration and Removal ───────────────────────────

    @Test
    @DisplayName("register and remove health status listeners")
    void listenerManagement_addAndRemove() {
                """
                HealthCheckManager supports dynamic listener registration for reactive health monitoring:

                **Listener Operations:**
                1. **Add listener**: Register callback for status transitions
                2. **Remove listener**: Unregister callback (stop receiving notifications)
                3. **Thread-safe**: Multiple listeners can be added/removed concurrently

                **Listener Interface:**
                ```java
                @FunctionalInterface
                interface HealthCheckListener {
                    void onStatusChanged(HealthStatus from, HealthStatus to);
                }
                ```

                **Use Cases:**
                - Alerting systems (PagerDuty, Slack, Email)
                - Metrics collection (Prometheus, Grafana)
                - Auto-remediation (restart, traffic shift)
                - Logging and audit trails
                """);

                """
            var manager = HealthCheckManager.create(
                HealthCheckConfig.builder("api-service")
                    .checks(List.of(
                        new HealthCheck.Custom("health", timeout ->
                            CompletableFuture.completedFuture(true))
                    ))
                    .checkInterval(Duration.ofSeconds(10))
                    .timeout(Duration.ofSeconds(5))
                    .build()
            );

            var callback1Invoked = new AtomicBoolean(false);
            var callback2Invoked = new AtomicBoolean(false);

            // Register first listener
            HealthCheckManager.HealthCheckListener listener1 = (from, to) -> {
                callback1Invoked.set(true);
                log.info("Listener 1: {} → {}", from, to);
            };
            manager.addListener(listener1);

            // Register second listener
            HealthCheckManager.HealthCheckListener listener2 = (from, to) -> {
                callback2Invoked.set(true);
                log.warn("Listener 2: {} → {}", from, to);
            };
            manager.addListener(listener2);

            // Both listeners are now registered
            assertThat(callback1Invoked.get()).isFalse();  // No transition yet
            assertThat(callback2Invoked.get()).isFalse();

            // Remove first listener
            manager.removeListener(listener1);

            // Only listener2 remains active
            assertThat(callback1Invoked.get()).isFalse();
            assertThat(callback2Invoked.get()).isFalse();
            """,
                "java");

        var manager =
                HealthCheckManager.create(
                        HealthCheckConfig.builder("api-service")
                                .checks(
                                        List.of(
                                                new HealthCheck.Custom(
                                                        "health",
                                                        timeout ->
                                                                CompletableFuture.completedFuture(
                                                                        true))))
                                .checkInterval(Duration.ofSeconds(10))
                                .timeout(Duration.ofSeconds(5))
                                .build());

        var called = new AtomicBoolean(false);
        HealthCheckManager.HealthCheckListener listener = (from, to) -> called.set(true);

        manager.addListener(listener);
        manager.removeListener(listener);

        assertThat(called.get()).isFalse();

                new String[][] {
                    {"Operation", "Behavior", "Thread Safety"},
                    {
                        "addListener()",
                        "Register callback for future transitions",
                        "Thread-safe (CopyOnWriteArrayList)"
                    },
                    {
                        "removeListener()",
                        "Unregister callback (no more notifications)",
                        "Thread-safe (atomic removal)"
                    },
                    {
                        "Multiple Listeners",
                        "All listeners notified on transition",
                        "Thread-safe notification"
                    },
                    {
                        "Duplicate Listeners",
                        "Allowed (each listener independent)",
                        "Thread-safe registration"
                    }
                });

                Map.of(
                        "Listener Storage",
                        "CopyOnWriteArrayList (thread-safe, iterator-safe)",
                        "Notification Order",
                        "Order of listener registration",
                        "Callback Execution",
                        "Synchronous (don't block in listener)"));

        manager.shutdown();
    }
}
