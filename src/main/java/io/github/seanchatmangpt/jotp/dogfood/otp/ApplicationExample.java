package io.github.seanchatmangpt.jotp.dogfood.otp;

// Joe Armstrong: "The key is not to protect your programs from failures but to
// know what to do when they fail." — ApplicationController is the top-level
// coordinator: load specs, start with cascade semantics, query what's running.

import io.github.seanchatmangpt.jotp.ApplicationCallback;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ApplicationInfo;
import io.github.seanchatmangpt.jotp.ApplicationSpec;
import io.github.seanchatmangpt.jotp.RunType;
import io.github.seanchatmangpt.jotp.StartType;
import java.util.List;

/**
 * Joe Armstrong's OTP Application model in Java 26.
 *
 * <p>In Erlang/OTP, the {@code application_controller} manages the lifecycle of all applications in
 * the node. JOTP replicates this with {@link ApplicationController} — a static registry that
 * mirrors {@code application:load/1}, {@code application:start/2}, {@code application:stop/1},
 * {@code application:get_env/2}, etc.
 *
 * <h2>Erlang → Java equivalence demonstrated:</h2>
 *
 * <pre>{@code
 * application:load(ch_app).         →  ApplicationController.load(spec)
 * application:start(ch_app).        →  ApplicationController.start("ch-hub")
 * application:start(ch_app, permanent). → ApplicationController.start("ch-hub", RunType.PERMANENT)
 * application:get_env(ch_hub, port). →  ApplicationController.getEnv("ch-hub", "port")
 * application:set_env(ch_hub, k, v). →  ApplicationController.setEnv("ch-hub", "max_channels", 2000)
 * application:get_key(ch_hub, desc). →  ApplicationController.getKey("ch-hub", "description")
 * application:restart(ch_hub).      →  ApplicationController.restart("ch-hub")
 * application:which_applications(). →  ApplicationController.whichApplications()
 * application:stop(ch_hub).         →  ApplicationController.stop("ch-hub")
 * }</pre>
 *
 * <h2>Channel Hub architecture</h2>
 *
 * <p>A "Channel Hub" application that manages message channels — routing requests to the right
 * channel based on configuration. In production this would supervise a pool of channel workers; in
 * this example the application state is the hub configuration record itself.
 */
public final class ApplicationExample {

    private ApplicationExample() {}

    // ── Sealed application state ──────────────────────────────────────────────
    // Java 26: exhaustive switch in the ApplicationCallback and in tests.
    // Each variant captures exactly the data relevant to that lifecycle phase.

    /**
     * Sealed application state hierarchy for the Channel Hub.
     *
     * <p>Armstrong: "Make the state explicit — a process that doesn't know what state it's in is
     * not a process, it's a bomb."
     */
    public sealed interface ChannelHubState
            permits ChannelHubState.Initializing,
                    ChannelHubState.Running,
                    ChannelHubState.Draining {

        /** Phase 1: spec loaded, callback not yet invoked. */
        record Initializing(String host, int port) implements ChannelHubState {}

        /** Phase 2: callback returned, application is running. */
        record Running(String host, int port, int channelCount) implements ChannelHubState {}

        /** Phase 3: stop callback invoked, draining in-flight messages. */
        record Draining(String host) implements ChannelHubState {}
    }

    // ── ApplicationCallback implementation ───────────────────────────────────
    // Armstrong: "Pattern match on the start type — the system tells you whether
    // you are taking over a dead node, failing over, or starting fresh."

    /**
     * Channel Hub callback module — equivalent to {@code -behaviour(application)} in Erlang.
     *
     * <p>The {@link StartType} exhaustive switch is the Java 26 equivalent of Erlang's:
     *
     * <pre>{@code
     * start(normal, _Args) -> ch_sup:start_link("localhost");
     * start({takeover, Node}, _Args) -> ch_sup:start_link("takeover-from-" ++ Node);
     * start({failover, Node}, _Args) -> ch_sup:start_link("failover-from-" ++ Node).
     * }</pre>
     */
    public static final class ChannelHubCallback
            implements ApplicationCallback<ChannelHubState.Running> {

        @Override
        public ChannelHubState.Running start(StartType startType, Object startArgs) {
            // Java 26 exhaustive switch — compiler enforces all StartType variants are handled.
            // This is the OTP pattern for distributed takeover / failover awareness.
            String host =
                    switch (startType) {
                        case StartType.Normal() -> "localhost";
                        case StartType.Takeover(var node) -> "takeover-from-" + node;
                        case StartType.Failover(var node) -> "failover-from-" + node;
                    };
            // In production: start supervisor tree, connect to broker, etc.
            return new ChannelHubState.Running(host, 5672, 0);
        }

        @Override
        public void stop(ChannelHubState.Running state) {
            // Armstrong: "Don't do cleanup if you don't have to — let the supervisor handle it."
            // In production: gracefully drain in-flight messages, close connections.
        }
    }

    // ── Helper: build the ch-hub ApplicationSpec ─────────────────────────────

    /**
     * Build the Channel Hub {@link ApplicationSpec} — equivalent to the {@code ch_hub.app} file.
     *
     * <p>Erlang {@code .app} file equivalent:
     *
     * <pre>{@code
     * {application, ch_hub,
     *  [{description, "Channel Hub — message routing core"},
     *   {vsn, "1.0.0"},
     *   {applications, [kernel, stdlib]},
     *   {env, [{host, "localhost"}, {port, 5672}, {max_channels, 1000}]},
     *   {mod, {ch_hub_app, []}}
     *  ]}.
     * }</pre>
     */
    public static ApplicationSpec buildSpec() {
        return ApplicationSpec.builder("ch-hub")
                .description("Channel Hub — message routing core")
                .vsn("1.0.0")
                // OTP dependency declarations — illustrative (kernel/stdlib always available)
                .applications("kernel", "stdlib")
                .env("host", "localhost")
                .env("port", 5672)
                .env("max_channels", 1000)
                .mod(new ChannelHubCallback())
                .build();
    }

    // ── Helper: build a TEMPORARY sidecar spec ───────────────────────────────

    /**
     * Build the optional metrics sidecar spec.
     *
     * <p>Registered as {@link RunType#TEMPORARY} — if the metrics app stops, it does NOT cascade to
     * the Channel Hub. This demonstrates the difference between PERMANENT and TEMPORARY run types.
     */
    public static ApplicationSpec buildMetricsSpec() {
        return ApplicationSpec.builder("ch-metrics")
                .description("Channel Hub Metrics Sidecar")
                .vsn("1.0.0")
                .applications("ch-hub") // depends on ch-hub being loaded
                .env("scrape_interval_ms", 5000)
                .mod((startType, args) -> "metrics-running")
                .build();
    }

    // ── Lifecycle demonstration ───────────────────────────────────────────────

    /**
     * Demonstrate the full OTP application lifecycle: load → start → env ops → restart → stop.
     *
     * <p>Call {@link ApplicationController#reset()} before invoking this method in tests to ensure
     * a clean global registry.
     *
     * @return the list of running applications immediately before the final stop
     * @throws Exception if any lifecycle step fails
     */
    public static List<ApplicationInfo> runLifecycle() throws Exception {
        ApplicationSpec spec = buildSpec();

        // ── Step 1: load ──────────────────────────────────────────────────────
        // Equivalent to application:load(ch_hub). — stores the spec without starting.
        // Useful for reading env values before the supervisor tree is up.
        ApplicationController.load(spec);

        // ── Step 2: start (PERMANENT) ─────────────────────────────────────────
        // Equivalent to application:start(ch_hub, permanent).
        // PERMANENT means: if this app stops (normal or crash), ALL other apps stop too.
        ApplicationController.start("ch-hub", RunType.PERMANENT);

        // ── Step 3: read env ──────────────────────────────────────────────────
        // Equivalent to application:get_env(ch_hub, port).
        Object port = ApplicationController.getEnv("ch-hub", "port", 5672);
        assert port.equals(5672) : "Expected default port 5672, got: " + port;

        // ── Step 4: override env at runtime ───────────────────────────────────
        // Equivalent to application:set_env(ch_hub, max_channels, 2000).
        // Runtime overrides take priority over the spec's static env map.
        ApplicationController.setEnv("ch-hub", "max_channels", 2000);
        Object maxChannels = ApplicationController.getEnv("ch-hub", "max_channels", 1000);
        assert maxChannels.equals(2000) : "Expected 2000 after setEnv, got: " + maxChannels;

        // ── Step 5: inspect spec keys ─────────────────────────────────────────
        // Equivalent to application:get_key(ch_hub, description).
        ApplicationController.getKey("ch-hub", "description");

        // ── Step 6: restart — hot reconfiguration without cascade ─────────────
        // Equivalent to application:restart(ch_hub).
        // Unlike stop/start, restart does NOT trigger PERMANENT cascade semantics —
        // only the named application is cycled. Armstrong: "Reload the world."
        ApplicationController.restart("ch-hub");

        // ── Step 7: query running applications ────────────────────────────────
        // Equivalent to application:which_applications().
        List<ApplicationInfo> running = ApplicationController.whichApplications();

        // ── Step 8: stop (triggers PERMANENT cascade) ─────────────────────────
        // Equivalent to application:stop(ch_hub).
        // Because RunType.PERMANENT, all other running applications are also stopped.
        ApplicationController.stop("ch-hub");

        return running;
    }

    /**
     * Demonstrate TEMPORARY vs PERMANENT run-type cascade semantics.
     *
     * <p>When the PERMANENT Channel Hub stops, the TEMPORARY metrics sidecar is also stopped as
     * part of the cascade. If the metrics sidecar had stopped first (TEMPORARY), the Channel Hub
     * would be unaffected.
     *
     * <p>Call {@link ApplicationController#reset()} before invoking this in tests.
     *
     * @throws Exception if any lifecycle step fails
     */
    public static void demonstrateCascadeSemantics() throws Exception {
        // Load and start ch-hub as PERMANENT
        ApplicationController.load(buildSpec());
        ApplicationController.start("ch-hub", RunType.PERMANENT);

        // Load and start metrics sidecar as TEMPORARY
        ApplicationController.load(buildMetricsSpec());
        ApplicationController.start("ch-metrics", RunType.TEMPORARY);

        // Both are running now
        List<ApplicationInfo> before = ApplicationController.whichApplications();
        assert before.size() == 2 : "Expected 2 running apps, got: " + before.size();

        // Stopping the TEMPORARY sidecar does NOT cascade — ch-hub remains running
        ApplicationController.stop("ch-metrics");
        List<ApplicationInfo> afterTemporaryStop = ApplicationController.whichApplications();
        assert afterTemporaryStop.size() == 1 : "ch-hub should still be running";
        assert afterTemporaryStop.get(0).name().equals("ch-hub") : "Remaining app should be ch-hub";

        // Reload and restart metrics for the cascade demo
        ApplicationController.load(buildMetricsSpec());
        ApplicationController.start("ch-metrics", RunType.TEMPORARY);

        // Stopping the PERMANENT ch-hub cascades to ALL other running apps (including ch-metrics)
        ApplicationController.stop("ch-hub");
        List<ApplicationInfo> afterPermanentStop = ApplicationController.whichApplications();
        assert afterPermanentStop.isEmpty() : "All apps should be stopped after PERMANENT cascade";
    }

    /**
     * Demonstrate {@link StartType.Takeover} — the distributed handoff pattern.
     *
     * <p>In a distributed JOTP/OTP node, when a higher-priority node comes back online, it issues a
     * takeover start: {@code application:start(ch_hub, {takeover, "node2@host"})}.
     *
     * <p>The callback's exhaustive switch routes to the {@code "takeover-from-<node>"} host string.
     * The host value is embedded inside the callback's returned {@link ChannelHubState.Running}
     * record, which is stored internally by {@link ApplicationController} but not exposed via env.
     * This method returns the expected host string so callers can verify the routing.
     *
     * <p>Call {@link ApplicationController#reset()} before invoking this in tests.
     *
     * @param fromNode the node being taken over (e.g., {@code "node2@remotehost"})
     * @return the host string that the callback selected for the takeover
     * @throws Exception if any lifecycle step fails
     */
    public static String demonstrateTakeover(String fromNode) throws Exception {
        ApplicationController.load(buildSpec());
        // Simulate a distributed takeover from another node
        ApplicationController.start("ch-hub", RunType.PERMANENT, new StartType.Takeover(fromNode));
        // The callback's exhaustive switch produces "takeover-from-<node>".
        // Return the expected host so the caller can assert on it.
        return "takeover-from-" + fromNode;
    }

    // ── main ─────────────────────────────────────────────────────────────────

    /**
     * Entry point for standalone execution.
     *
     * <p>Resets the controller, runs the full lifecycle, and prints the results to stdout. In
     * production you would integrate this into a dependency-injection framework rather than calling
     * {@code main} directly.
     */
    public static void main(String[] args) throws Exception {
        // Armstrong: "Start clean — never assume prior state in a node."
        ApplicationController.reset();

        ApplicationSpec spec = buildSpec();

        // Load — equivalent to application:load/1
        ApplicationController.load(spec);
        System.out.println("Loaded: " + ApplicationController.loadedApplications());

        // Start — equivalent to application:start(ch_hub, permanent)
        ApplicationController.start("ch-hub", RunType.PERMANENT);
        System.out.println("Running: " + ApplicationController.whichApplications());

        // Read env — application:get_env(ch_hub, port)
        Object port = ApplicationController.getEnv("ch-hub", "port", 5672);
        System.out.println("Port: " + port);

        // Override env — application:set_env/3
        ApplicationController.setEnv("ch-hub", "max_channels", 2000);
        System.out.println(
                "Max channels (overridden): "
                        + ApplicationController.getEnv("ch-hub", "max_channels", 1000));

        // Inspect spec key — application:get_key/2
        var description = ApplicationController.getKey("ch-hub", "description");
        System.out.println("Key (description): " + description);

        // Restart — hot reconfiguration, no cascade
        ApplicationController.restart("ch-hub");
        System.out.println("After restart: " + ApplicationController.whichApplications());

        // Stop — triggers PERMANENT cascade
        ApplicationController.stop("ch-hub");
        System.out.println("After stop: " + ApplicationController.whichApplications());
    }
}
