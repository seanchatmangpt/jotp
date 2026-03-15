package io.github.seanchatmangpt.jotp.dogfood.toyota;

import io.github.seanchatmangpt.jotp.ApplicationCallback;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.ApplicationSpec;
import io.github.seanchatmangpt.jotp.RunType;
import io.github.seanchatmangpt.jotp.StartType;

/**
 * OTP application entry point for the Toyota Production System AGI agent suite.
 *
 * <p><strong>Joe Armstrong:</strong> "An application is a component. It has a name, a version, a
 * callback module, and a supervision tree. Everything else is plumbing." This class is the {@code
 * -behaviour(application)} module for all ten Blue Ocean Toyota AGI agents.
 *
 * <p>The {@link ObeiyaAgent} acts as the top-level supervisor root — it owns and starts all nine
 * sub-agents (Kanban, Jidoka, Andon, Kaizen, Heijunka, Poka-yoke, Genchi Genbutsu, Takt Time, VSM)
 * and exposes a unified {@link ObeiyaAgent#healthReport} view.
 *
 * <h2>Usage via ApplicationController (OTP lifecycle):</h2>
 *
 * <pre>{@code
 * ApplicationController.load(ToyotaProductionSystemApp.spec());
 * ApplicationController.start("toyota-production-system");
 *
 * // Query health
 * // (Cast required — ApplicationController erases the state type)
 *
 * ApplicationController.stop("toyota-production-system");
 * }</pre>
 *
 * <h2>Erlang equivalence:</h2>
 *
 * <pre>{@code
 * % toyota.app
 * {application, 'toyota-production-system',
 *  [{description, "Blue Ocean Toyota AGI agents — OTP supervision tree"},
 *   {vsn, "1.0.0"},
 *   {mod, {toyota_app, []}}
 *  ]}.
 * }</pre>
 *
 * @see ObeiyaAgent
 * @see ApplicationController
 */
public final class ToyotaProductionSystemApp implements ApplicationCallback<ObeiyaAgent> {

    // ── ApplicationCallback ───────────────────────────────────────────────────

    /**
     * Start the Toyota Production System — launches the {@link ObeiyaAgent} war-room coordinator
     * which in turn starts all nine sub-agents.
     *
     * <p>The {@link StartType} exhaustive switch is compiler-enforced by the sealed interface; all
     * three variants produce the same {@link ObeiyaAgent} (distributed takeover/failover are
     * transparent to the internal agent graph for this implementation).
     *
     * @param startType Normal / Takeover / Failover — handled exhaustively
     * @param startArgs unused; configuration is embedded in {@link ObeiyaAgent#start()}
     * @return the running ObeiyaAgent (the application state passed to {@link #stop})
     * @throws Exception if the ObeiyaAgent fails to start
     */
    @Override
    public ObeiyaAgent start(StartType startType, Object startArgs) throws Exception {
        return switch (startType) {
            case StartType.Normal() -> ObeiyaAgent.start();
            case StartType.Takeover(var node) -> {
                // Distributed takeover: start fresh — the new node takes ownership.
                // Armstrong: "Let the supervisor tree handle state; the process is stateless."
                yield ObeiyaAgent.start();
            }
            case StartType.Failover(var node) -> {
                // Failover: start fresh — prior node is gone, resume from clean state.
                yield ObeiyaAgent.start();
            }
        };
    }

    /**
     * Stop the Toyota Production System — shuts down all agents in dependency order.
     *
     * @param obeiya the running ObeiyaAgent returned by {@link #start}
     * @throws Exception if any sub-agent fails to stop cleanly
     */
    @Override
    public void stop(ObeiyaAgent obeiya) throws Exception {
        obeiya.stop();
    }

    // ── ApplicationSpec factory ───────────────────────────────────────────────

    /**
     * Build the {@link ApplicationSpec} for the Toyota Production System.
     *
     * <p>Equivalent to the Erlang {@code toyota.app} resource file. Use with:
     *
     * <pre>{@code
     * ApplicationController.load(ToyotaProductionSystemApp.spec());
     * ApplicationController.start("toyota-production-system");
     * }</pre>
     *
     * @return fully-configured ApplicationSpec ready to load
     */
    public static ApplicationSpec spec() {
        return ApplicationSpec.builder("toyota-production-system")
                .description("Blue Ocean Toyota AGI agents — OTP supervision tree")
                .vsn("1.0.0")
                .mod(new ToyotaProductionSystemApp())
                .env("jidoka.threshold", 3)
                .env("andon.window.seconds", 30)
                .env("takt.time.ms", 500)
                .env("kaizen.metric", "quality-rate")
                .env("kaizen.target", 0.95)
                .build();
    }

    // ── Convenience entry point ───────────────────────────────────────────────

    /**
     * Load, start, and return a running {@link ObeiyaAgent} via the {@link ApplicationController}.
     *
     * <p>Equivalent to:
     *
     * <pre>{@code
     * application:load(toyota_production_system),
     * application:start(toyota_production_system, permanent).
     * }</pre>
     *
     * @return the loaded ApplicationSpec name for use with ApplicationController queries
     * @throws Exception if load or start fails
     */
    public static String launch() throws Exception {
        ApplicationController.load(spec());
        ApplicationController.start("toyota-production-system", RunType.PERMANENT);
        return "toyota-production-system";
    }
}
