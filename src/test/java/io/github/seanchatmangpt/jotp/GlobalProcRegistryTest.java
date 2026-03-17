package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GlobalProcRegistry: Node-wide process name registry")
class GlobalProcRegistryTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
        GlobalProcRegistry.reset();
    }

    private static final Duration AWAIT = Duration.ofSeconds(3);

    sealed interface Msg permits Msg.Inc, Msg.Ping {
        record Inc() implements Msg {}

        record Ping() implements Msg {}
    }

    // ── register / whereis ────────────────────────────────────────────────────────

    @Test
    @DisplayName("register and whereis: basic lookup returns the registered process")
    void registerAndWhereIs_returnsProcess() {
        var proc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("my-proc", proc);

        var found = GlobalProcRegistry.<Integer, Msg>whereis("my-proc");
        assertThat(found).isPresent();
        assertThat(found.get()).isSameAs(proc);

        proc.thread().interrupt();
    }

    @Test
    @DisplayName("whereis: returns empty for unknown name")
    void whereIs_unknownName_returnsEmpty() {
        assertThat(GlobalProcRegistry.whereis("no-such-proc")).isEmpty();
    }

    @Test
    @DisplayName("register: throws if name already registered to a live process")
    void register_duplicateName_throws() {
        var proc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("dup", proc);

        assertThatThrownBy(() -> GlobalProcRegistry.register("dup", proc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");

        proc.thread().interrupt();
    }

    // ── unregister ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unregister: explicit removal makes whereis return empty")
    void unregister_removesName() {
        var proc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("removable", proc);
        assertThat(GlobalProcRegistry.whereis("removable")).isPresent();

        GlobalProcRegistry.unregister("removable");
        assertThat(GlobalProcRegistry.whereis("removable")).isEmpty();

        proc.thread().interrupt();
    }

    @Test
    @DisplayName("unregister: safe to call for unknown name (no exception)")
    void unregister_unknownName_isIdempotent() {
        assertThatCode(() -> GlobalProcRegistry.unregister("ghost")).doesNotThrowAnyException();
    }

    // ── auto-deregister on termination ────────────────────────────────────────────

    @Test
    @DisplayName("auto-deregister: process removed from registry when it stops normally")
    void autoDeregister_onNormalStop() throws InterruptedException {
        var proc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("auto-stop", proc);
        assertThat(GlobalProcRegistry.whereis("auto-stop")).isPresent();

        proc.stop(); // normal termination

        await().atMost(AWAIT).until(() -> GlobalProcRegistry.whereis("auto-stop").isEmpty());
        assertThat(GlobalProcRegistry.whereis("auto-stop")).isEmpty();
    }

    @Test
    @DisplayName("auto-deregister: process removed from registry when it crashes")
    void autoDeregister_onCrash() {
        var proc =
                Proc.spawn(
                        0,
                        (state, msg) -> {
                            throw new RuntimeException("intentional crash");
                        });
        GlobalProcRegistry.register("crash-proc", proc);
        assertThat(GlobalProcRegistry.whereis("crash-proc")).isPresent();

        proc.tell("boom"); // crash the process

        await().atMost(AWAIT).until(() -> !proc.thread().isAlive());
        await().atMost(AWAIT).until(() -> GlobalProcRegistry.whereis("crash-proc").isEmpty());
    }

    // ── allNames / localNames ─────────────────────────────────────────────────────

    @Test
    @DisplayName("allNames: returns node-prefixed names")
    void allNames_returnsNodePrefixedNames() {
        var proc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("counter", proc);

        var names = GlobalProcRegistry.allNames();
        String expected = GlobalProcRegistry.nodeName() + "/counter";
        assertThat(names).contains(expected);

        proc.thread().interrupt();
    }

    @Test
    @DisplayName("localNames: returns names without node prefix")
    void localNames_returnsUnprefixedNames() {
        var p1 = Proc.spawn(0, (state, msg) -> state);
        var p2 = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("p1", p1);
        GlobalProcRegistry.register("p2", p2);

        var names = GlobalProcRegistry.localNames();
        assertThat(names).containsExactlyInAnyOrder("p1", "p2");

        p1.thread().interrupt();
        p2.thread().interrupt();
    }

    // ── RemoteResolver ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setRemoteResolver: called when local lookup fails")
    void remoteResolver_calledWhenLocalMisses() {
        var remoteProc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.setRemoteResolver(
                new GlobalProcRegistry.RemoteResolver() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <S, M> java.util.Optional<Proc<S, M>> resolve(String globalName) {
                        if ("remote-worker".equals(globalName)) {
                            return java.util.Optional.of((Proc<S, M>) remoteProc);
                        }
                        return java.util.Optional.empty();
                    }
                });

        var found = GlobalProcRegistry.<Integer, Msg>whereis("remote-worker");
        assertThat(found).isPresent();
        assertThat(found.get()).isSameAs(remoteProc);

        // Local names not affected
        assertThat(GlobalProcRegistry.whereis("other-name")).isEmpty();

        remoteProc.thread().interrupt();
        GlobalProcRegistry.setRemoteResolver(null);
    }

    @Test
    @DisplayName("setRemoteResolver(null): disables remote resolution")
    void setRemoteResolver_null_disablesRemote() {
        GlobalProcRegistry.setRemoteResolver(
                new GlobalProcRegistry.RemoteResolver() {
                    @Override
                    public <S, M> java.util.Optional<Proc<S, M>> resolve(String globalName) {
                        return java.util.Optional.empty();
                    }
                });
        GlobalProcRegistry.setRemoteResolver(null);

        assertThat(GlobalProcRegistry.whereis("anything")).isEmpty();
    }

    // ── reset ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reset: clears all registrations and remote resolver")
    void reset_clearsEverything() {
        var proc = Proc.spawn(0, (state, msg) -> state);
        GlobalProcRegistry.register("temp", proc);
        GlobalProcRegistry.setRemoteResolver(
                new GlobalProcRegistry.RemoteResolver() {
                    @Override
                    public <S, M> java.util.Optional<Proc<S, M>> resolve(String globalName) {
                        return java.util.Optional.empty();
                    }
                });

        GlobalProcRegistry.reset();

        assertThat(GlobalProcRegistry.localNames()).isEmpty();
        assertThat(GlobalProcRegistry.remoteResolver()).isNull();

        proc.thread().interrupt();
    }
}
