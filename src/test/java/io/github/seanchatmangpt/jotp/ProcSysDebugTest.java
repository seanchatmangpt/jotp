package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.BeforeEach;
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
    // ------------------------------------------------------------------
    // Shared test message type
    sealed interface Msg permits Msg.Inc, Msg.Noop {
        record Inc() implements Msg {}
        record Noop() implements Msg {}
    }
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    private static Proc<Integer, Msg> counterProc() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Inc() -> state + 1;
                            case Msg.Noop() -> state;
                        });
    // Gap 1a: sys:trace external API
    @Test
    @DisplayName("trace(proc, true) prints In and Out events to output stream")
    void traceToCustomWriter() throws Exception {
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
    @DisplayName("trace(proc, false) detaches observer — subsequent messages produce no output")
    void detachTrace() throws Exception {
        var outputAfterAttach = sw.toString();
        assertThat(outputAfterAttach).isNotEmpty();
        ProcSys.trace(proc, false);
        // Reset the writer and send more messages — should produce no new output
        sw.getBuffer().setLength(0);
        assertThat(sw.toString()).isEmpty();
    // Gap 1b: sys:get_log
    @DisplayName("getLog returns In and Out events for each message processed")
    void getLogReturnsEvents() throws Exception {
        ProcSys.trace(proc, true);
        for (int i = 0; i < 5; i++) {
            proc.ask(new Msg.Inc()).get(2, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(2)).until(() -> ProcSys.getLog(proc).size() >= 10);
        var log = ProcSys.getLog(proc);
        assertThat(log).hasSize(10); // 5 In + 5 Out
        assertThat(log.stream().filter(e -> e instanceof DebugEvent.In<?>).count()).isEqualTo(5);
        assertThat(log.stream().filter(e -> e instanceof DebugEvent.Out<?>).count()).isEqualTo(5);
    @DisplayName("getLog returns empty list when no observer is installed")
    void getLogNoObserver() throws Exception {
        assertThat(ProcSys.getLog(proc)).isEmpty();
    @DisplayName("getLog returns empty list after trace is detached")
    void getLogAfterDetach() throws Exception {
        // After detach, getLog returns empty (observer removed)
    // Gap 1c: sys:handle_debug (process-internal API)
    @DisplayName("debugOptions() returns empty DebugOptions with tracing off and no log")
    void debugOptionsNone() {
        DebugOptions<Msg> deb = ProcSys.debugOptions();
        assertThat(deb.tracing()).isFalse();
        assertThat(deb.maxLog()).isEqualTo(0);
        assertThat(deb.log()).isEmpty();
    @DisplayName("debugOptions(true, 10) creates options with trace on and log capacity 10")
    void debugOptionsWithTraceAndLog() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(true, 10);
        assertThat(deb.tracing()).isTrue();
        assertThat(deb.maxLog()).isEqualTo(10);
    @DisplayName("handleDebug appends events to the log and returns updated DebugOptions")
    void handleDebugBuildsLog() {
        DebugFormatter<Msg> fmt = (dev, event, info) -> dev.println(info + " -> " + event);
        deb = ProcSys.handleDebug(deb, fmt, "ch4", new DebugEvent.In<>(new Msg.Inc()), pw);
        deb = ProcSys.handleDebug(deb, fmt, "ch4", new DebugEvent.Out<>(new Msg.Inc(), 1), pw);
        deb = ProcSys.handleDebug(deb, fmt, "ch4", new DebugEvent.Custom<>("state_timeout"), pw);
        assertThat(deb.log()).hasSize(3);
        assertThat(deb.log().get(0)).isInstanceOf(DebugEvent.In.class);
        assertThat(deb.log().get(1)).isInstanceOf(DebugEvent.Out.class);
        assertThat(deb.log().get(2)).isInstanceOf(DebugEvent.Custom.class);
        assertThat(sw.toString()).contains("ch4 -> ");
    @DisplayName("handleDebug does not append to log when maxLog is 0")
    void handleDebugNoLog() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(false);
        deb =
                ProcSys.handleDebug(
                        deb,
                        DebugFormatter.defaultFormatter(),
                        "proc",
                        new DebugEvent.In<>(new Msg.Inc()));
    @DisplayName("handleDebug log is capped at maxLog — oldest event is evicted")
    void handleDebugLogCap() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(false, 3);
            deb =
                    ProcSys.handleDebug(
                            deb,
                            DebugFormatter.defaultFormatter(),
                            "p",
                            new DebugEvent.Custom<>("event-" + i),
                            pw);
        // Oldest (0, 1) evicted — last 3 remain
        assertThat(((DebugEvent.Custom<?>) deb.log().get(0)).event()).isEqualTo("event-2");
        assertThat(((DebugEvent.Custom<?>) deb.log().get(2)).event()).isEqualTo("event-4");
    // Gap 2: System message protocol (SysRequest via sysQueue)
    @DisplayName("getState resolves via sysQueue with priority over user mailbox")
    void getStateViaSystemChannel() throws Exception {
        // Flood mailbox with 200 messages to ensure queue depth
        for (int i = 0; i < 200; i++) {
            proc.tell(new Msg.Inc());
        // getState enqueues a SysRequest — should be served before all 200 messages
        var stateFuture = ProcSys.getState(proc);
        // State should be available well within 2 seconds
        var state = stateFuture.get(2, TimeUnit.SECONDS);
        assertThat(state).isNotNull().isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(200);
    @DisplayName("getState on terminated process completes exceptionally")
    void getStateTerminatedProcess() throws Exception {
        // Give the thread time to finish
        await().atMost(Duration.ofSeconds(1)).until(() -> !proc.thread().isAlive());
        var future = ProcSys.getState(proc);
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    // Gap 3: system_code_change — hot state transformation
    @DisplayName("codeChange applies transformer atomically between messages")
    void codeChangeTransformsState() throws Exception {
        // Advance to state = 5
            proc.ask(new Msg.Inc()).get(1, TimeUnit.SECONDS);
        // Hot transform: multiply state by 10 (e.g., unit migration)
        var newState = ProcSys.codeChange(proc, s -> s * 10);
        assertThat(newState).isEqualTo(50);
        // Subsequent messages continue from new state
        var stateAfter = proc.ask(new Msg.Inc()).get(1, TimeUnit.SECONDS);
        assertThat(stateAfter).isEqualTo(51);
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
        // Send 5 messages before codeChange, 5 after
        for (int i = 0; i < 5; i++) proc.tell(new Msg.Inc());
        var newState = ProcSys.codeChange(proc, s -> s + 100);
        // All 10 Inc messages must be processed
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        // Final state: 5 (pre) + 100 (codeChange) + 5 (post) = 110
        var finalState = ProcSys.getState(proc).get(1, TimeUnit.SECONDS);
        assertThat(finalState).isEqualTo(110);
    @DisplayName("multiple codeChanges can be applied sequentially")
    void sequentialCodeChanges() throws Exception {
        ProcSys.codeChange(proc, s -> s + 10);
        ProcSys.codeChange(proc, s -> s * 2);
        var state = ProcSys.codeChange(proc, s -> s - 1);
        // 0 → +10 → *2 → -1 = 19
        assertThat(state).isEqualTo(19);
    // Combined: trace + getState coexist
    @DisplayName("trace and getState can be used simultaneously on same process")
    void traceAndGetStateCoexist() throws Exception {
        ProcSys.trace(proc, true, DebugFormatter.defaultFormatter(), pw, "proc");
        var state = ProcSys.getState(proc).get(2, TimeUnit.SECONDS);
        assertThat(state).isGreaterThanOrEqualTo(1);
        assertThat(sw.toString()).isNotEmpty();
    @DisplayName("DebugEvent.Custom is recorded by handleDebug with user-defined term")
    void customDebugEvent() {
        DebugOptions<Msg> deb = ProcSys.debugOptions(true, 5);
        record StateTimeout(String name) {}
                        new DebugEvent.Custom<>(new StateTimeout("lock")),
                        pw);
        assertThat(deb.log()).hasSize(1);
        var evt = (DebugEvent.Custom<?>) deb.log().get(0);
        assertThat(evt.event()).isInstanceOf(StateTimeout.class);
        assertThat(sw.toString()).contains("StateTimeout");
    // Statistics still works after migration to sysQueue
    @DisplayName("statistics reports correct message counts after sysQueue migration")
    void statisticsAfterSysQueueMigration() throws Exception {
        for (int i = 0; i < 7; i++) {
        ProcSys.codeChange(proc, s -> s); // no-op transform — exercises sysQueue
        var stats = ProcSys.statistics(proc);
        assertThat(stats.messagesIn()).isEqualTo(7);
        assertThat(stats.messagesOut()).isEqualTo(7);
}
