package io.github.seanchatmangpt.jotp.examples;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Blue Ocean Innovation Swarm — 10 specialist agents discover, score, and rank innovations using
 * 80/20 Pareto analysis + Blue Ocean Strategy principles.
 *
 * <p><strong>Architecture</strong>
 *
 * <pre>
 *   MasterSupervisor (ONE_FOR_ALL, 5 restarts / 60 s)
 *   ├── HealthTech-Agent    ─┐
 *   ├── EdTech-Agent         │
 *   ├── FinTech-Agent        │  Each agent:
 *   ├── ClimaTech-Agent      │   • receives Discover(context)
 *   ├── AgriTech-Agent       │   • returns innovations via ask()
 *   ├── RetailTech-Agent     │   • state = List&lt;Innovation&gt; found
 *   ├── MobilityTech-Agent  ─┘
 *   ├── SpaceTech-Agent
 *   ├── BioTech-Agent
 *   └── CyberSec-Agent
 *
 *   Coordinator:
 *     Parallel.all()  → concurrent scoring via CrashRecovery.retry()
 *     80/20 filter    → top 20 % by composite score
 *     Blue Ocean gate → blueOceanScore ≥ 7.0 (novelty × competition ≥ threshold)
 * </pre>
 *
 * <p><strong>Scoring Model</strong>
 *
 * <ul>
 *   <li>Pareto score = impact×0.6 + feasibility×0.4
 *   <li>Blue Ocean score = novelty×0.5 + competition×0.5 (10 = zero competition)
 *   <li>Composite = Pareto×0.5 + BlueOcean×0.5
 *   <li>80/20 cut: top 20% of composite score
 *   <li>Blue Ocean: composite ≥ 7.0 AND blueOceanScore ≥ 7.0
 * </ul>
 */
public class BlueOceanInnovationSwarm {

    // ── Sector taxonomy ──────────────────────────────────────────────────────

    enum Sector {
        HEALTH_TECH,
        ED_TECH,
        FIN_TECH,
        CLIMA_TECH,
        AGRI_TECH,
        RETAIL_TECH,
        MOBILITY_TECH,
        SPACE_TECH,
        BIO_TECH,
        CYBER_SEC
    }

    // ── Innovation value object ──────────────────────────────────────────────

    record Innovation(
            String id,
            Sector sector,
            String title,
            String description,
            double impact, // 0-10: revenue / societal impact potential
            double feasibility, // 0-10: technical & execution feasibility
            double novelty, // 0-10: originality vs existing solutions
            double competition // 0-10: 10 = no competitors, 0 = saturated
            ) {

        double paretoScore() {
            return impact * 0.6 + feasibility * 0.4;
        }

        double blueOceanScore() {
            return novelty * 0.5 + competition * 0.5;
        }

        double compositeScore() {
            return paretoScore() * 0.5 + blueOceanScore() * 0.5;
        }

        boolean isBlueOcean() {
            return blueOceanScore() >= 7.0 && compositeScore() >= 7.0;
        }
    }

    // ── Agent messages ────────────────────────────────────────────────────────

    sealed interface AgentMsg {
        record Discover(String context) implements AgentMsg {}
    }

    // ── Agent state ───────────────────────────────────────────────────────────

    record AgentState(Sector sector, List<Innovation> discoveries) {
        static AgentState empty(Sector s) {
            return new AgentState(s, List.of());
        }

        AgentState withDiscoveries(List<Innovation> found) {
            return new AgentState(sector, List.copyOf(found));
        }
    }

    // ── Swarm event bus ───────────────────────────────────────────────────────

    sealed interface SwarmEvent {
        record AgentCompleted(String agentId, int innovationCount) implements SwarmEvent {}

        record ScoringComplete(int totalScored) implements SwarmEvent {}

        record ReportReady(int paretoCount, int blueOceanCount) implements SwarmEvent {}
    }

    // ── Innovation knowledge base per sector ──────────────────────────────────

    static List<Innovation> innovationsFor(Sector sector) {
        return switch (sector) {
            case HEALTH_TECH ->
                    List.of(
                            new Innovation(
                                    "HT-1",
                                    sector,
                                    "AI Skin-Patch Diagnostics",
                                    "Continuous biomarker monitoring + ML diagnosis via wearable patch",
                                    9.2,
                                    7.5,
                                    8.8,
                                    8.5),
                            new Innovation(
                                    "HT-2",
                                    sector,
                                    "Longevity-as-a-Service",
                                    "Personalized aging-intervention subscriptions (epigenetics + lifestyle)",
                                    8.7,
                                    6.8,
                                    9.1,
                                    9.2),
                            new Innovation(
                                    "HT-3",
                                    sector,
                                    "Microbiome-Personalized Nutrition",
                                    "Stool-analysis AI generates precision probiotic protocols",
                                    7.9,
                                    8.2,
                                    7.3,
                                    6.1));
            case ED_TECH ->
                    List.of(
                            new Innovation(
                                    "ET-1",
                                    sector,
                                    "EEG-Adaptive Learning Engine",
                                    "Real-time brainwave feedback auto-adjusts curriculum pacing",
                                    8.5,
                                    7.0,
                                    9.0,
                                    9.0),
                            new Innovation(
                                    "ET-2",
                                    sector,
                                    "4-Hour Micro-Credential Platform",
                                    "Employer-validated rapid certifications with instant job matching",
                                    9.0,
                                    8.5,
                                    7.5,
                                    5.5),
                            new Innovation(
                                    "ET-3",
                                    sector,
                                    "Haptic VR Apprenticeships",
                                    "Master-craftsperson muscle memory transferred via haptic VR suits",
                                    7.5,
                                    6.0,
                                    9.5,
                                    9.5));
            case FIN_TECH ->
                    List.of(
                            new Innovation(
                                    "FT-1",
                                    sector,
                                    "Emotion-Aware Spending Guards",
                                    "Behavioral biometrics detect emotional state; auto-pause impulse buys",
                                    8.3,
                                    8.8,
                                    8.2,
                                    7.0),
                            new Innovation(
                                    "FT-2",
                                    sector,
                                    "Micro-Investment Carbon Credits",
                                    "Fractional carbon-credit ownership for retail investors via DeFi",
                                    7.8,
                                    9.0,
                                    8.5,
                                    6.5),
                            new Innovation(
                                    "FT-3",
                                    sector,
                                    "Decentralised Credit Cooperatives",
                                    "P2P credit unions with on-chain governance and community risk pooling",
                                    8.1,
                                    7.2,
                                    7.8,
                                    7.8));
            case CLIMA_TECH ->
                    List.of(
                            new Innovation(
                                    "CT-1",
                                    sector,
                                    "Urban Atmospheric Water Grid",
                                    "Mesh of humidity harvesters provides potable water in arid cities",
                                    9.5,
                                    6.5,
                                    8.0,
                                    9.0),
                            new Innovation(
                                    "CT-2",
                                    sector,
                                    "Carbon-Negative Mycelium Concrete",
                                    "Mycelium-reinforced concrete absorbs CO₂ during and after curing",
                                    8.8,
                                    7.8,
                                    8.5,
                                    8.0),
                            new Innovation(
                                    "CT-3",
                                    sector,
                                    "Wildfire AI Prediction Network",
                                    "Satellite + IoT sensor mesh predicts wildfires 72 h in advance",
                                    9.1,
                                    8.3,
                                    7.5,
                                    7.2));
            case AGRI_TECH ->
                    List.of(
                            new Innovation(
                                    "AG-1",
                                    sector,
                                    "Vertical Ocean Farming",
                                    "Offshore multi-level seaweed / shellfish cultivation platforms",
                                    8.0,
                                    7.0,
                                    8.8,
                                    9.3),
                            new Innovation(
                                    "AG-2",
                                    sector,
                                    "Soil Microbiome Banking",
                                    "Living soil cultures traded as agricultural regeneration assets",
                                    7.5,
                                    7.5,
                                    9.2,
                                    9.5),
                            new Innovation(
                                    "AG-3",
                                    sector,
                                    "Pollinator Drone Swarms",
                                    "Autonomous micro-drones replicate precision bee pollination at scale",
                                    8.5,
                                    7.8,
                                    8.0,
                                    7.5));
            case RETAIL_TECH ->
                    List.of(
                            new Innovation(
                                    "RT-1",
                                    sector,
                                    "Ownership-as-a-Service Fashion",
                                    "Rotating wardrobe subscriptions with full circular economy model",
                                    7.5,
                                    9.0,
                                    7.0,
                                    5.0),
                            new Innovation(
                                    "RT-2",
                                    sector,
                                    "In-Store 3-D Print-on-Demand",
                                    "Fabricators print products to customer spec in under 10 minutes",
                                    8.8,
                                    7.2,
                                    8.8,
                                    8.5),
                            new Innovation(
                                    "RT-3",
                                    sector,
                                    "Emotional Commerce AI",
                                    "Real-time mood detection personalises the entire shopping journey",
                                    7.2,
                                    8.5,
                                    8.0,
                                    6.0));
            case MOBILITY_TECH ->
                    List.of(
                            new Innovation(
                                    "MT-1",
                                    sector,
                                    "Aerial Urban Bicycle Lanes",
                                    "Cable-supported elevated bicycle highways above street level",
                                    8.2,
                                    6.5,
                                    9.5,
                                    9.8),
                            new Innovation(
                                    "MT-2",
                                    sector,
                                    "Hyperlocal Pneumatic Cargo Tunnels",
                                    "Underground pneumatic delivery for last-100 m urban logistics",
                                    8.0,
                                    7.0,
                                    8.5,
                                    8.8),
                            new Innovation(
                                    "MT-3",
                                    sector,
                                    "Universal Mobility Credits",
                                    "Cross-modal transit currency usable across all transport providers",
                                    7.8,
                                    8.5,
                                    7.5,
                                    6.5));
            case SPACE_TECH ->
                    List.of(
                            new Innovation(
                                    "ST-1",
                                    sector,
                                    "GEO Solar Power Beaming",
                                    "Geostationary solar arrays beam energy to ground receivers 24/7",
                                    9.8,
                                    5.5,
                                    7.5,
                                    9.5),
                            new Innovation(
                                    "ST-2",
                                    sector,
                                    "Asteroid Mining Futures Market",
                                    "Financial instruments on extraterrestrial mineral reserves",
                                    8.5,
                                    6.0,
                                    9.8,
                                    9.8),
                            new Innovation(
                                    "ST-3",
                                    sector,
                                    "Microgravity Semiconductor Fab",
                                    "ISS-class stations produce ultra-pure crystals for next-gen chips",
                                    8.0,
                                    5.8,
                                    8.8,
                                    9.0));
            case BIO_TECH ->
                    List.of(
                            new Innovation(
                                    "BT-1",
                                    sector,
                                    "Living Medicine Factories",
                                    "Engineered gut bacteria synthesise personalised drugs in-body",
                                    9.5,
                                    5.5,
                                    9.5,
                                    9.5),
                            new Innovation(
                                    "BT-2",
                                    sector,
                                    "Xenotransplant-as-a-Service",
                                    "Immunogenically personalised pig-organ farms for transplant patients",
                                    9.0,
                                    6.5,
                                    8.0,
                                    8.5),
                            new Innovation(
                                    "BT-3",
                                    sector,
                                    "DNA Data Storage Platform",
                                    "Commercial-scale cold archival storage using synthetic DNA strands",
                                    8.5,
                                    7.5,
                                    8.5,
                                    7.8));
            case CYBER_SEC ->
                    List.of(
                            new Innovation(
                                    "CS-1",
                                    sector,
                                    "Behavioural Biometric Zero-Trust",
                                    "Continuous auth via typing rhythm, gait, and mouse-movement entropy",
                                    8.5,
                                    9.0,
                                    7.8,
                                    5.5),
                            new Innovation(
                                    "CS-2",
                                    sector,
                                    "Deception-Grid Security Fabric",
                                    "Entire fake infrastructure layer lures, fingerprints, and studies attackers",
                                    9.0,
                                    8.0,
                                    8.5,
                                    8.0),
                            new Innovation(
                                    "CS-3",
                                    sector,
                                    "Quantum-Ready Key Migration SaaS",
                                    "Automated PQC migration service for legacy enterprise PKI systems",
                                    9.2,
                                    8.8,
                                    7.0,
                                    5.8));
        };
    }

    // ── Main swarm orchestration ─────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        printBanner();

        // 1. Event bus for swarm-wide notifications
        EventManager<SwarmEvent> eventBus = EventManager.start();
        eventBus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SwarmEvent e) {
                        switch (e) {
                            case SwarmEvent.AgentCompleted(var id, var count) ->
                                    System.out.printf(
                                            "  [event] %-28s completed  → %d innovations%n",
                                            id, count);
                            case SwarmEvent.ScoringComplete(var n) ->
                                    System.out.printf(
                                            "  [event] Parallel scoring done        → %d scored%n",
                                            n);
                            case SwarmEvent.ReportReady(var pareto, var bo) ->
                                    System.out.printf(
                                            "  [event] Report ready                 → pareto=%d  blueOcean=%d%n%n",
                                            pareto, bo);
                        }
                    }
                });

        // 2. Master supervisor — ONE_FOR_ALL: if any agent crashes, restart all
        var supervisor =
                Supervisor.create(
                        "innovation-swarm",
                        Supervisor.Strategy.ONE_FOR_ALL,
                        5,
                        Duration.ofMinutes(1));

        System.out.println("» Launching 10-agent specialist swarm\n");

        // 3. Spawn one agent per sector
        var agentRefs = new ArrayList<ProcRef<AgentState, AgentMsg>>();
        for (var sector : Sector.values()) {
            String agentId = sector.name().toLowerCase().replace('_', '-') + "-agent";
            ProcRef<AgentState, AgentMsg> ref =
                    supervisor.supervise(
                            agentId,
                            AgentState.empty(sector),
                            (state, msg) ->
                                    switch (msg) {
                                        case AgentMsg.Discover(var ctx) -> {
                                            var found = innovationsFor(state.sector());
                                            eventBus.notify(
                                                    new SwarmEvent.AgentCompleted(
                                                            agentId, found.size()));
                                            yield state.withDiscoveries(found);
                                        }
                                    });
            agentRefs.add(ref);
        }

        // 4. Broadcast discovery task — ask() returns CompletableFuture<AgentState>
        System.out.println("» Broadcasting discovery task to all agents...\n");
        List<CompletableFuture<AgentState>> futures =
                agentRefs.stream()
                        .map(a -> a.ask(new AgentMsg.Discover("global-markets-2026")))
                        .toList();

        // 5. Collect all innovations (join futures, flatten discoveries)
        List<Innovation> allInnovations =
                futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(state -> state.discoveries().stream())
                        .toList();

        System.out.printf(
                "» Swarm collected %d innovation candidates from %d agents%n%n",
                allInnovations.size(), Sector.values().length);

        // 6. Score every candidate in parallel — each score wrapped in CrashRecovery.retry()
        System.out.println("» Running parallel composite scoring (Pareto + Blue Ocean)...\n");
        List<Supplier<ScoredInnovation>> scoreTasks =
                allInnovations.stream()
                        .<Supplier<ScoredInnovation>>map(
                                inn ->
                                        () -> {
                                            Result<Double, Exception> scoreResult =
                                                    CrashRecovery.retry(2, inn::compositeScore);
                                            double score =
                                                    switch (scoreResult) {
                                                        case Result.Ok(var s) -> s;
                                                        case Result.Err(var ex) -> 0.0;
                                                    };
                                            return new ScoredInnovation(inn, score);
                                        })
                        .toList();

        Result<List<ScoredInnovation>, Exception> scoringResult = Parallel.all(scoreTasks);
        List<ScoredInnovation> scored =
                switch (scoringResult) {
                    case Result.Ok(var s) -> s;
                    case Result.Err(var ex) ->
                            throw new RuntimeException("Parallel scoring failed", ex);
                };

        eventBus.notify(new SwarmEvent.ScoringComplete(scored.size()));

        // 7. Rank all by composite score
        List<ScoredInnovation> ranked =
                scored.stream()
                        .sorted(Comparator.comparingDouble(ScoredInnovation::finalScore).reversed())
                        .toList();

        // 8. Apply 80/20 Pareto cut — top 20 % of candidates
        int paretoN = Math.max(1, (int) Math.ceil(ranked.size() * 0.20));
        List<ScoredInnovation> paretoElite = ranked.subList(0, paretoN);

        // 9. Blue Ocean gate — high novelty + low competition within the elite
        List<ScoredInnovation> blueOcean =
                paretoElite.stream().filter(s -> s.innovation().isBlueOcean()).toList();

        eventBus.notify(new SwarmEvent.ReportReady(paretoElite.size(), blueOcean.size()));

        // 10. Print final synthesis report
        printReport(ranked, paretoElite, blueOcean);

        eventBus.stop();
        supervisor.shutdown();
    }

    // ── Supporting types ─────────────────────────────────────────────────────

    record ScoredInnovation(Innovation innovation, double finalScore) {}

    // ── Reporting ────────────────────────────────────────────────────────────

    static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  BLUE OCEAN INNOVATION SWARM  ◆  10-Agent 80/20 Pareto Engine   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    static void printReport(
            List<ScoredInnovation> ranked,
            List<ScoredInnovation> paretoElite,
            List<ScoredInnovation> blueOcean) {

        String bar = "═".repeat(70);

        // Full ranking
        System.out.println(bar);
        System.out.printf(" ALL %d CANDIDATES — ranked by composite score%n", ranked.size());
        System.out.println(bar);
        for (int i = 0; i < ranked.size(); i++) {
            var s = ranked.get(i);
            var inn = s.innovation();
            boolean isElite = i < (int) Math.ceil(ranked.size() * 0.20);
            System.out.printf(
                    " %2d. %-44s [%-11s] %s %.2f%n",
                    i + 1, inn.title(), inn.sector(), isElite ? "★" : " ", s.finalScore());
        }

        // Pareto elite
        System.out.println();
        System.out.println(bar);
        System.out.printf(
                " 80/20 PARETO ELITE — top %d candidates (~80%% of total value)%n",
                paretoElite.size());
        System.out.println(bar);
        for (int i = 0; i < paretoElite.size(); i++) {
            var s = paretoElite.get(i);
            var inn = s.innovation();
            System.out.printf(" %d. %s%n", i + 1, inn.title());
            System.out.printf(
                    "    Sector: %-14s  Impact: %.1f  Feasibility: %.1f%n",
                    inn.sector(), inn.impact(), inn.feasibility());
            System.out.printf(
                    "    Novelty: %.1f   No-Competition: %.1f   Composite: %.2f   Blue Ocean: %s%n%n",
                    inn.novelty(),
                    inn.competition(),
                    s.finalScore(),
                    inn.isBlueOcean() ? "YES ★" : "no");
        }

        // Blue Ocean hits
        System.out.println(bar);
        System.out.printf(
                " BLUE OCEAN HITS — %d uncontested market opportunities%n", blueOcean.size());
        System.out.println(bar);
        if (blueOcean.isEmpty()) {
            System.out.println(" (none in this run — loosen thresholds or expand the domain set)");
        } else {
            for (var s : blueOcean) {
                var inn = s.innovation();
                System.out.printf(" ★ %s  [%s]%n", inn.title(), inn.sector());
                System.out.printf("   %s%n", inn.description());
                System.out.printf(
                        "   Pareto: %.2f  |  Blue Ocean: %.2f  |  Composite: %.2f%n%n",
                        inn.paretoScore(), inn.blueOceanScore(), s.finalScore());
            }
        }

        // Summary
        System.out.println(bar);
        System.out.println(" SWARM SYNTHESIS");
        System.out.println(bar);
        System.out.printf(" Agents deployed              : %d%n", Sector.values().length);
        System.out.printf(" Total innovations analysed   : %d%n", ranked.size());
        System.out.printf(" Pareto elite (top 20%%)       : %d%n", paretoElite.size());
        System.out.printf(" Blue Ocean opportunities     : %d%n", blueOcean.size());
        System.out.printf(
                " Primary recommendation       : %s%n",
                blueOcean.isEmpty()
                        ? paretoElite.get(0).innovation().title()
                        : blueOcean.get(0).innovation().title());
        System.out.println(bar);
        System.out.println();
    }
}
