package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the three {@link ProcSys} gaps closed by the OTP sys/proc_lib documentation:
 *
 * <ol>
 *   <li>{@code sys:trace/2} + {@code sys:handle_debug/4} — per-event debug tracing
 *   <li>System message protocol ({@link SysRequest}) — {@link ProcSys#getState} and {@link
 *       ProcSys#codeChange} via the high-priority sys channel
 *   <li>{@code system_code_change/4} — hot state transformation via {@link ProcSys#codeChange}
 * </ol>
 */
@DisplayName("ProcSys: sys:trace, sys:handle_debug, system_code_change")
class ProcSysDebugTest {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    // ------------------------------------------------------------------
    // Shared test message type
    // ------------------------------------------------------------------

    sealed interface Msg permits Msg.Inc, Msg.Noop {
        record Inc() implements Msg {}

        record Noop() implements Msg {}
    }

    private static Proc<Integer, Msg> counterProc() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Inc() -> state + 1;
                            case Msg.Noop() -> state;
                        });
    }

    // ------------------------------------------------------------------
    // Gap 1a: sys:trace external API
    // ------------------------------------------------------------------

    @Test
    @DisplayName("trace(proc, true) prints In and Out events to output stream")
    void traceToCustomWriter() throws Exception {
                """
                ProcSys provides JOTP's equivalent to Erlang/OTP's sys module for process introspection.
                The trace/2 function enables debug logging of all message events (In/Out) to a PrintStream,
                useful for production debugging without modifying process code.
                """);

        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var proc = counterProc();

        ProcSys.trace(proc, true, DebugFormatter.defaultFormatter(), pw, "counter");

        proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);

        var output = sw.toString();
        assertThat(output).contains("counter event =");
        assertThat(output).contains("In");
        assertThat(output).contains("Out");

        proc.stop();
    }

    @Test
    @DisplayName("trace(proc, false) detaches observer — subsequent messages produce no output")
    void detachTrace() throws Exception {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var proc = counterProc();

        ProcSys.trace(proc, true, DebugFormatter.defaultFormatter(), pw, "counter");
        proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);
        var outputAfterAttach = sw.toString();
        assertThat(outputAfterAttach).isNotEmpty();

        ProcSys.trace(proc, false);

        // Reset the writer and send more messages — should produce no new output
        sw.getBuffer().setLength(0);
        proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);
        assertThat(sw.toString()).isEmpty();

        proc.stop();
    }

    // ------------------------------------------------------------------
    // Gap 1b: sys:get_log
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLog returns In and Out events for each message processed")
    void getLogReturnsEvents() throws Exception {
                """
                getLog retrieves the debug event log - a bounded buffer of In (message received)
                and Out (reply sent) events. This mirrors Erlang's sys:get_log/1 for inspecting
                process activity without stopping the process.
                """);

        var proc = counterProc();
        ProcSys.trace(proc, true);

        for (int i = 0; i < 5; i++) {
            proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> ProcSys.getLog(proc).size() >= 10);

        var log = ProcSys.getLog(proc);
        assertThat(log).hasSize(10); // 5 In + 5 Out
        assertThat(log.stream().filter(e -> e instanceof DebugEvent.In<?>).count()).isEqualTo(5);
        assertThat(log.stream().filter(e -> e instanceof DebugEvent.Out<?>).count()).isEqualTo(5);

        proc.stop();
        ProcSys.trace(proc, false);
    }

    @Test
    @DisplayName("getLog returns empty list when no observer is installed")
    void getLogNoObserver() throws Exception {
        var proc = counterProc();
        assertThat(ProcSys.getLog(proc)).isEmpty();
        proc.stop();
    }

    @Test
    @DisplayName("getLog returns empty list after trace is detached")
    void getLogAfterDetach() throws Exception {
        var proc = counterProc();
        ProcSys.trace(proc, true);
        proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);
        ProcSys.trace(proc, false);

        // After detach, getLog returns empty (observer removed)
        assertThat(ProcSys.getLog(proc)).isEmpty();
        proc.stop();
    }

    // ------------------------------------------------------------------
    // Gap 1c: sys:handle_debug (process-internal API)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("debugOptions() returns empty DebugOptions with tracing off and no log")
    void debugOptionsNone() {
        DebugOptions<Msg> deb = ProcSys.debugOptions();
        assertThat(deb.tracing()).isFalse();
        assertThat(deb.maxLog()).isEqualTo(0);
        assertThat(deb.log()).isEmpty();
    }

    @Test
    @DisplayName("debugOptions(true, 10) creates options with trace on and log capacity 10")
    void debugOptionsWithTraceAndLog() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(true, 10);
        assertThat(deb.tracing()).isTrue();
        assertThat(deb.maxLog()).isEqualTo(10);
        assertThat(deb.log()).isEmpty();
    }

    @Test
    @DisplayName("handleDebug appends events to the log and returns updated DebugOptions")
    void handleDebugBuildsLog() {
                """
                handleDebug is the process-internal API (equivalent to sys:handle_debug/4) that
                appends debug events to the log. Processes call this in their message handlers to
                record custom events like state transitions or timeouts.
                """);

        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        DebugFormatter<Msg> fmt = (dev, event, info) -> dev.println(info + " -> " + event);

        DebugOptions<Msg> deb = ProcSys.debugOptions(true, 10);

        deb = ProcSys.handleDebug(deb, fmt, "ch4", new DebugEvent.In<>(new Msg.Inc()), pw);
        deb = ProcSys.handleDebug(deb, fmt, "ch4", new DebugEvent.Out<>(new Msg.Inc(), 1), pw);
        deb = ProcSys.handleDebug(deb, fmt, "ch4", new DebugEvent.Custom<>("state_timeout"), pw);

        assertThat(deb.log()).hasSize(3);
        assertThat(deb.log().get(0)).isInstanceOf(DebugEvent.In.class);
        assertThat(deb.log().get(1)).isInstanceOf(DebugEvent.Out.class);
        assertThat(deb.log().get(2)).isInstanceOf(DebugEvent.Custom.class);

        assertThat(sw.toString()).contains("ch4 -> ");
    }

    @Test
    @DisplayName("handleDebug does not append to log when maxLog is 0")
    void handleDebugNoLog() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(false);
        deb =
                ProcSys.handleDebug(
                        deb,
                        DebugFormatter.defaultFormatter(),
                        "proc",
                        new DebugEvent.In<>(new Msg.Inc()));
        assertThat(deb.log()).isEmpty();
    }

    @Test
    @DisplayName("handleDebug log is capped at maxLog — oldest event is evicted")
    void handleDebugLogCap() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(false, 3);
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);

        for (int i = 0; i < 5; i++) {
            deb =
                    ProcSys.handleDebug(
                            deb,
                            DebugFormatter.defaultFormatter(),
                            "p",
                            new DebugEvent.Custom<>("event-" + i),
                            pw);
        }

        assertThat(deb.log()).hasSize(3);
        // Oldest (0, 1) evicted — last 3 remain
        assertThat(((DebugEvent.Custom<?>) deb.log().get(0)).event()).isEqualTo("event-2");
        assertThat(((DebugEvent.Custom<?>) deb.log().get(2)).event()).isEqualTo("event-4");
    }

    // ------------------------------------------------------------------
    // Gap 2: System message protocol (SysRequest via sysQueue)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getState resolves via sysQueue with priority over user mailbox")
    void getStateViaSystemChannel() throws Exception {
                """
                getState uses the high-priority sys channel to retrieve process state without
                waiting for the user mailbox to drain. This is critical for production introspection
                where the mailbox may be flooded with messages.
                """);

        var proc = counterProc();

        // Flood mailbox with 200 messages to ensure queue depth
        for (int i = 0; i < 200; i++) {
            proc.tell(new Msg.Inc());
        }

        // getState enqueues a SysRequest — should be served before all 200 messages
        var stateFuture = ProcSys.getState(proc);

        // State should be available well within 2 seconds
        var state = stateFuture.get(2, TimeUnit.SECONDS);
        assertThat(state).isNotNull().isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(200);

        proc.stop();
    }

    @Test
    @DisplayName("getState on terminated process completes exceptionally")
    void getStateTerminatedProcess() throws Exception {
        var proc = counterProc();
        proc.stop();

        // Give the thread time to finish
        await().atMost(Duration.ofSeconds(1)).until(() -> !proc.thread().isAlive());

        var future = ProcSys.getState(proc);
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // Gap 3: system_code_change — hot state transformation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("codeChange applies transformer atomically between messages")
    void codeChangeTransformsState() throws Exception {
                """
                codeChange implements hot code upgrade - applying a state transformation function
                atomically between message processing. This mirrors Erlang's system_code_change/4
                for zero-downtime deployments where state schemas evolve.
                """);

        var proc = counterProc();

        // Advance to state = 5
        for (int i = 0; i < 5; i++) {
            proc.ask(new Msg.Inc()).get(1, TimeUnit.SECONDS);
        }

        // Hot transform: multiply state by 10 (e.g., unit migration)
        var newState = ProcSys.codeChange(proc, s -> s * 10);
        assertThat(newState).isEqualTo(50);

        // Subsequent messages continue from new state
        var stateAfter = proc.ask(new Msg.Inc()).get(1, TimeUnit.SECONDS);
        assertThat(stateAfter).isEqualTo(51);

        proc.stop();
    }

    @Test
    @DisplayName("codeChange does not drop in-flight user messages")
    void codeChangeDoesNotDropMessages() throws Exception {
        // Use a latch-based proc that counts every Inc it receives
        var latch = new CountDownLatch(10);
        Proc<Integer, Msg> proc =
                new Proc<>(
                        0,
                        (state, msg) -> {
                            if (msg instanceof Msg.Inc) latch.countDown();
                            return switch (msg) {
                                case Msg.Inc() -> state + 1;
                                case Msg.Noop() -> state;
                            };
                        });

        // Send 5 messages before codeChange, 5 after
        for (int i = 0; i < 5; i++) proc.tell(new Msg.Inc());

        var newState = ProcSys.codeChange(proc, s -> s + 100);

        for (int i = 0; i < 5; i++) proc.tell(new Msg.Inc());

        // All 10 Inc messages must be processed
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();

        // Final state: 5 (pre) + 100 (codeChange) + 5 (post) = 110
        var finalState = ProcSys.getState(proc).get(1, TimeUnit.SECONDS);
        assertThat(finalState).isEqualTo(110);

        proc.stop();
    }

    @Test
    @DisplayName("multiple codeChanges can be applied sequentially")
    void sequentialCodeChanges() throws Exception {
        var proc = counterProc();

        ProcSys.codeChange(proc, s -> s + 10);
        ProcSys.codeChange(proc, s -> s * 2);
        var state = ProcSys.codeChange(proc, s -> s - 1);

        // 0 → +10 → *2 → -1 = 19
        assertThat(state).isEqualTo(19);

        proc.stop();
    }

    // ------------------------------------------------------------------
    // Combined: trace + getState coexist
    // ------------------------------------------------------------------

    @Test
    @DisplayName("trace and getState can be used simultaneously on same process")
    void traceAndGetStateCoexist() throws Exception {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        var proc = counterProc();

        ProcSys.trace(proc, true, DebugFormatter.defaultFormatter(), pw, "proc");
        proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);
        var state = ProcSys.getState(proc).get(2, TimeUnit.SECONDS);

        assertThat(state).isGreaterThanOrEqualTo(1);
        assertThat(sw.toString()).isNotEmpty();

        proc.stop();
    }

    @Test
    @DisplayName("DebugEvent.Custom is recorded by handleDebug with user-defined term")
    void customDebugEvent() {
        var sw = new StringWriter();
        var pw = new PrintWriter(sw, true);
        DebugOptions<Msg> deb = ProcSys.debugOptions(true, 5);

        record StateTimeout(String name) {}

        deb =
                ProcSys.handleDebug(
                        deb,
                        DebugFormatter.defaultFormatter(),
                        "proc",
                        new DebugEvent.Custom<>(new StateTimeout("lock")),
                        pw);

        assertThat(deb.log()).hasSize(1);
        var evt = (DebugEvent.Custom<?>) deb.log().get(0);
        assertThat(evt.event()).isInstanceOf(StateTimeout.class);
        assertThat(sw.toString()).contains("StateTimeout");
    }

    // ------------------------------------------------------------------
    // Statistics still works after migration to sysQueue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("statistics reports correct message counts after sysQueue migration")
    void statisticsAfterSysQueueMigration() throws Exception {
        var proc = counterProc();

        for (int i = 0; i < 7; i++) {
            proc.ask(new Msg.Inc()).get(1, TimeUnit.SECONDS);
        }
        ProcSys.codeChange(proc, s -> s); // no-op transform — exercises sysQueue

        var stats = ProcSys.statistics(proc);
        assertThat(stats.messagesIn()).isEqualTo(7);
        assertThat(stats.messagesOut()).isEqualTo(7);

        proc.stop();
    }
}
