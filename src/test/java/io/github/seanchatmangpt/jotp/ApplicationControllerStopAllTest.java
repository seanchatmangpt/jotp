package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ApplicationController.stopAll: Graceful shutdown in reverse dependency order")
class ApplicationControllerStopAllTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ── Helper: create an ApplicationCallback with a custom stop action ────────────

    private static <S> ApplicationCallback<S> callback(S state, Runnable onStop) {
        return new ApplicationCallback<>() {
            @Override
            public S start(StartType startType, Object args) {
                return state;
            }

            @Override
            public void stop(S s) {
                onStop.run();
            }
        };
    }

    // ── Basic stopAll ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stopAll: single app is stopped")
    void stopAll_singleApp_isStopped() throws Exception {
        var stopped = new ArrayList<String>();

        var spec =
                ApplicationSpec.builder("app-a")
                        .mod(callback("state-a", () -> stopped.add("app-a")))
                        .build();

        ApplicationController.start(spec);
        assertThat(ApplicationController.whichApplications()).hasSize(1);

        ApplicationController.stopAll();

        assertThat(ApplicationController.whichApplications()).isEmpty();
        assertThat(stopped).containsExactly("app-a");
    }

    @Test
    @DisplayName("stopAll: no-op when no apps are running")
    void stopAll_noApps_isIdempotent() throws Exception {
        assertThatCode(() -> ApplicationController.stopAll()).doesNotThrowAnyException();
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    // ── Dependency ordering ───────────────────────────────────────────────────────

    @Test
    @DisplayName("stopAll: dependent app stopped before its dependency")
    void stopAll_dependentBeforeDependency() throws Exception {
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var specB =
                ApplicationSpec.builder("lib-b")
                        .mod(callback("b-state", () -> stopOrder.add("lib-b")))
                        .build();

        // A depends on B: A must start after B and stop before B
        var specA =
                ApplicationSpec.builder("app-a")
                        .applications("lib-b")
                        .mod(callback("a-state", () -> stopOrder.add("app-a")))
                        .build();

        ApplicationController.load(specB);
        ApplicationController.load(specA);
        ApplicationController.start("app-a"); // auto-starts lib-b first

        assertThat(ApplicationController.whichApplications()).hasSize(2);

        ApplicationController.stopAll();

        assertThat(stopOrder).hasSize(2);
        // app-a must appear before lib-b in stop order
        assertThat(stopOrder.indexOf("app-a")).isLessThan(stopOrder.indexOf("lib-b"));
    }

    @Test
    @DisplayName("stopAll: chain A→B→C stops in order A, B, C")
    void stopAll_chain_stopsInOrder() throws Exception {
        var stopOrder = Collections.synchronizedList(new ArrayList<String>());

        var specC =
                ApplicationSpec.builder("c").mod(callback("c", () -> stopOrder.add("c"))).build();
        var specB =
                ApplicationSpec.builder("b")
                        .applications("c")
                        .mod(callback("b", () -> stopOrder.add("b")))
                        .build();
        var specA =
                ApplicationSpec.builder("a")
                        .applications("b")
                        .mod(callback("a", () -> stopOrder.add("a")))
                        .build();

        ApplicationController.load(specC);
        ApplicationController.load(specB);
        ApplicationController.load(specA);
        ApplicationController.start("a");

        assertThat(ApplicationController.whichApplications()).hasSize(3);

        ApplicationController.stopAll();

        assertThat(stopOrder).containsExactly("a", "b", "c");
    }

    // ── With timeout ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stopAll(Duration): stops app within timeout")
    void stopAll_withTimeout_stopsApp() throws Exception {
        var stopped = new ArrayList<String>();

        var spec =
                ApplicationSpec.builder("quick-app")
                        .mod(callback("state", () -> stopped.add("quick-app")))
                        .build();

        ApplicationController.start(spec);

        ApplicationController.stopAll(Duration.ofSeconds(10));

        assertThat(stopped).containsExactly("quick-app");
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    @Test
    @DisplayName("stopAll(Duration): slow app times out but remaining apps still stop")
    void stopAll_slowApp_timeoutContinuesWithOthers() throws Exception {
        var stopped = Collections.synchronizedList(new ArrayList<String>());

        // Slow app: stop callback takes 500ms
        var specSlow =
                ApplicationSpec.builder("slow-app")
                        .mod(
                                new ApplicationCallback<String>() {
                                    @Override
                                    public String start(StartType startType, Object args) {
                                        return "slow";
                                    }

                                    @Override
                                    public void stop(String s) throws Exception {
                                        try {
                                            Thread.sleep(500);
                                        } catch (InterruptedException ignored) {
                                        }
                                        stopped.add("slow-app");
                                    }
                                })
                        .build();

        // Fast app — stops normally
        var specFast =
                ApplicationSpec.builder("fast-app")
                        .mod(callback("fast", () -> stopped.add("fast-app")))
                        .build();

        ApplicationController.start(specSlow);
        ApplicationController.start(specFast);

        // 100ms timeout — shorter than slow-app's 500ms
        ApplicationController.stopAll(Duration.ofMillis(100));

        // fast-app should have been stopped
        assertThat(stopped).contains("fast-app");
        // Both apps removed from running map (stop() removes before calling callback)
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }

    // ── Independent apps ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("stopAll: multiple independent apps all get stopped")
    void stopAll_independentApps_allStopped() throws Exception {
        var stopped = Collections.synchronizedList(new ArrayList<String>());

        for (String name : List.of("alpha", "beta", "gamma")) {
            var spec =
                    ApplicationSpec.builder(name)
                            .mod(callback(name, () -> stopped.add(name)))
                            .build();
            ApplicationController.start(spec);
        }

        assertThat(ApplicationController.whichApplications()).hasSize(3);

        ApplicationController.stopAll();

        assertThat(stopped).containsExactlyInAnyOrder("alpha", "beta", "gamma");
        assertThat(ApplicationController.whichApplications()).isEmpty();
    }
}
