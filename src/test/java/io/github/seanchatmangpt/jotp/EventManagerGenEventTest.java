package io.github.seanchatmangpt.jotp;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/**
 * Tests verifying JOTP {@link EventManager} achieves full OTP {@code gen_event} parity.
 *
 * <p>Each test corresponds to a documented OTP gen_event capability:
 * <ul>
 *   <li>Named event manager — {@code gen_event:start({local, Name})}
 *   <li>Supervised handler — {@code gen_event:add_sup_handler/3}
 *   <li>Info messages — {@code handle_info/2}
 *   <li>Code change — {@code code_change/3}
 *   <li>Stateful handlers — equivalent to OTP's {@code {Module, State}} pairs
 * </ul>
 */
@DisplayName("EventManager OTP gen_event parity")
class EventManagerGenEventTest implements WithAssertions {
    record ErrorMsg(String text) {}
    @AfterEach
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    void resetRegistry() {
        ProcRegistry.reset();
    // -------------------------------------------------------------------------
    // Named Event Manager — gen_event:start({local, Name}) / start_link({local, Name})
    @Nested
    @DisplayName("Named event manager — gen_event:start({local, Name})")
    class NamedEventManager {
        @Test
        @DisplayName("start(name) registers manager in ProcRegistry")
        void startNameRegistersInRegistry() {
            EventManager<ErrorMsg> mgr = EventManager.start("error_man");
            Optional<?> found = ProcRegistry.whereis("error_man");
            assertThat(found).isPresent();
            mgr.stop();
        }
        @DisplayName("start(name, timeout) registers manager with custom timeout")
        void startNameWithTimeoutRegisters() {
            EventManager<ErrorMsg> mgr = EventManager.start("error_man", Duration.ofSeconds(10));
            assertThat(ProcRegistry.whereis("error_man")).isPresent();
        @DisplayName("startLink(name) registers manager in ProcRegistry")
        void startLinkNameRegisters() {
            EventManager<ErrorMsg> mgr = EventManager.startLink("error_man");
        @DisplayName("stop() on named manager de-registers it from ProcRegistry")
        void stopDeregistersFromRegistry() throws InterruptedException {
            // Give the virtual thread time to clean up
            Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> ProcRegistry.whereis("error_man").isEmpty());
        @DisplayName("named manager handles notify the same as anonymous manager")
        void namedManagerHandlesEvents() throws InterruptedException {
            AtomicInteger count = new AtomicInteger();
            mgr.addHandler(event -> count.incrementAndGet());
            mgr.syncNotify(new ErrorMsg("hello"));
            assertThat(count.get()).isEqualTo(1);
    // Supervised Handler — gen_event:add_sup_handler/3
    @DisplayName("Supervised handler — gen_event:add_sup_handler/3")
    class SupervisedHandler {
        @DisplayName("addSupHandler registers the handler immediately")
        void addSupHandlerRegistersHandler() throws InterruptedException {
            EventManager<ErrorMsg> mgr = EventManager.start();
            mgr.addSupHandler(event -> count.incrementAndGet());
            mgr.syncNotify(new ErrorMsg("test"));
        @DisplayName("supervised handler is removed when its owning thread terminates")
        void handlerRemovedWhenOwningThreadDies() throws InterruptedException {
            // Register handler from a short-lived virtual thread
            Thread owner =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        mgr.addSupHandler(event -> count.incrementAndGet());
                                        // thread exits immediately after registration
                                    });
            owner.join();
            // Wait for the monitor thread to detect the dead owner and remove the handler
                    .atMost(Duration.ofSeconds(3))
                    .pollInterval(Duration.ofMillis(50))
                    .untilAsserted(
                            () -> {
                                count.set(0);
                                try {
                                    mgr.syncNotify(new ErrorMsg("probe"));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                // After removal, handler should no longer receive events
                                assertThat(count.get()).isZero();
                            });
    // handle_info/2 — non-event messages
    @DisplayName("handle_info/2 — non-event messages")
    class HandleInfo {
        @DisplayName("info() delivers message to all handlers via handleInfo")
        void infoDeliveredToAllHandlers() throws InterruptedException {
            AtomicReference<Object> received1 = new AtomicReference<>();
            AtomicReference<Object> received2 = new AtomicReference<>();
            mgr.addHandler(
                    new EventManager.Handler<>() {
                        public void handleEvent(ErrorMsg e) {}
                        public void handleInfo(Object info) {
                            received1.set(info);
                        }
                    });
                            received2.set(info);
            mgr.info("exit_signal");
            // info() is async — wait for delivery
                    .until(() -> received1.get() != null && received2.get() != null);
            assertThat(received1.get()).isEqualTo("exit_signal");
            assertThat(received2.get()).isEqualTo("exit_signal");
        @DisplayName("crashing handleInfo does NOT remove the handler (unlike handleEvent)")
        void crashingHandleInfoDoesNotRemoveHandler() throws InterruptedException {
            AtomicInteger eventCount = new AtomicInteger();
                        public void handleEvent(ErrorMsg e) {
                            eventCount.incrementAndGet();
                            throw new RuntimeException("simulated handle_info crash");
            // Crash the handleInfo — handler should survive
            mgr.info("crash_me");
            // Give the info time to be processed
            Thread.sleep(100);
            // Handler must still be registered and receive events
            mgr.syncNotify(new ErrorMsg("after_crash"));
            assertThat(eventCount.get()).isEqualTo(1);
        @DisplayName("info() passes any Object type to handleInfo")
        void infoAcceptsArbitraryObjects() throws InterruptedException {
            record ExitSignal(int pid, String reason) {}
            AtomicReference<Object> received = new AtomicReference<>();
                            received.set(info);
            var signal = new ExitSignal(42, "normal");
            mgr.info(signal);
            Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> received.get() != null);
            assertThat(received.get()).isEqualTo(signal);
    // code_change/3 — hot code upgrade
    @DisplayName("code_change/3 — hot code upgrade")
    class CodeChange {
        @DisplayName("codeChange() calls all handlers' codeChange callback synchronously")
        void codeChangeNotifiesAllHandlers() {
            AtomicReference<Object> vsn1 = new AtomicReference<>();
            AtomicReference<Object> vsn2 = new AtomicReference<>();
                        public void codeChange(Object oldVsn, Object extra) {
                            vsn1.set(oldVsn);
                            vsn2.set(oldVsn);
            mgr.codeChange("1.0.0", null);
            // codeChange() is synchronous — results available immediately
            assertThat(vsn1.get()).isEqualTo("1.0.0");
            assertThat(vsn2.get()).isEqualTo("1.0.0");
        @DisplayName("codeChange() passes extra data to handlers")
        void codeChangePassesExtraData() {
            record UpgradeInfo(String target, boolean dryRun) {}
            AtomicReference<Object> extraReceived = new AtomicReference<>();
                            extraReceived.set(extra);
            var upgrade = new UpgradeInfo("2.0.0", false);
            mgr.codeChange("1.0.0", upgrade);
            assertThat(extraReceived.get()).isEqualTo(upgrade);
        @DisplayName("crashing codeChange does not remove handler or abort other handlers")
        void crashingCodeChangeDoesNotRemoveHandler() throws InterruptedException {
            AtomicInteger crashHandler = new AtomicInteger();
            AtomicInteger goodHandler = new AtomicInteger();
                            crashHandler.incrementAndGet();
                            throw new RuntimeException("code_change crash");
                            goodHandler.incrementAndGet();
            // Upgrade applied to good handler; crash handler still registered
            assertThat(goodHandler.get()).isEqualTo(1); // codeChange was called once
            mgr.syncNotify(new ErrorMsg("after_upgrade"));
            assertThat(crashHandler.get()).isEqualTo(1); // still receives events
            assertThat(goodHandler.get()).isEqualTo(2); // handleEvent called
    // Stateful handlers — OTP {Module, State} equivalent
    @DisplayName("Stateful handlers — Java OOP equivalent of OTP {Module, State}")
    class StatefulHandlers {
        @DisplayName("handler instance fields accumulate state across events (OTP equivalent)")
        void handlerInstanceMaintainsState() throws InterruptedException {
            // In OTP: handle_event(Event, State) -> {ok, State+1}
            // In Java: instance field plays the role of State
            var handler =
                    new EventManager.Handler<ErrorMsg>() {
                        int count = 0;
                            count++;
                    };
            mgr.addHandler(handler);
            mgr.syncNotify(new ErrorMsg("one"));
            mgr.syncNotify(new ErrorMsg("two"));
            mgr.syncNotify(new ErrorMsg("three"));
            assertThat(handler.count).isEqualTo(3);
        @DisplayName("file_logger example: stateful handler accumulates log entries")
        void fileLoggerEquivalent() throws InterruptedException {
            // Mirrors the OTP file_logger example: handler stores a resource and uses it per event
            var logLines = new java.util.ArrayList<String>();
                        // "Fd" equivalent — handler holds a resource (the list)
                        final java.util.List<String> log = logLines;
                            log.add("***Error*** " + e.text());
                        public void terminate(Throwable reason) {
                            // "file:close(Fd)" equivalent — cleanup on removal
                            log.add("[logger closed]");
            mgr.syncNotify(new ErrorMsg("no_reply"));
            mgr.syncNotify(new ErrorMsg("timeout"));
            assertThat(logLines).containsExactly("***Error*** no_reply", "***Error*** timeout");
            // terminate() was called — logger closed
            assertThat(logLines).last().isEqualTo("[logger closed]");
}
