package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.EventManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for {@link SessionEventBus} — the OTP {@code gen_event} implementation for SQL Race
 * session events.
 *
 * <p>Tests cover: handler registration and delivery, crashing-handler isolation (bus continues),
 * {@code syncNotify} ordering, {@code deleteHandler}, and the forwarding-handler factory.
 */
class SessionEventBusTest implements WithAssertions {
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }
    private SessionEventBus bus;
    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.stop();
        }
    private static SqlRaceLap outLap() {
        return SqlRaceLap.outLap(1_000_000_000L);
    private static SqlRaceSessionEvent.AddLap addLapEvent() {
        return new SqlRaceSessionEvent.AddLap(outLap());
    // ── Basic registration + delivery ─────────────────────────────────────────
    @Test
    void registeredHandlerReceivesEvent() throws InterruptedException {
        bus = SessionEventBus.start();
        var received = new CopyOnWriteArrayList<SqlRaceSessionEvent>();
        var latch = new CountDownLatch(1);
        bus.addHandler(
                new EventManager.Handler<>() {
                    @Override
                    public void handleEvent(SqlRaceSessionEvent event) {
                        received.add(event);
                        latch.countDown();
                    }
                });
        bus.notify(addLapEvent());
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(SqlRaceSessionEvent.AddLap.class);
    void multipleHandlersAllReceiveEvent() throws InterruptedException {
        var counter = new AtomicInteger(0);
        var latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            bus.addHandler(
                    new EventManager.Handler<>() {
                        @Override
                        public void handleEvent(SqlRaceSessionEvent event) {
                            counter.incrementAndGet();
                            latch.countDown();
                        }
                    });
        assertThat(counter.get()).isEqualTo(3);
    // ── Crash isolation ───────────────────────────────────────────────────────
    void crashingHandlerIsRemovedButBusContinues() throws InterruptedException {
        var stableReceived = new CopyOnWriteArrayList<SqlRaceSessionEvent>();
        var stableLatch = new CountDownLatch(1);
        // Handler that throws on every event
                        throw new RuntimeException("simulated display handler crash");
        // Stable handler that should still receive events after the crash
                        stableReceived.add(event);
                        stableLatch.countDown();
        // syncNotify ensures both handlers have been called before returning
        bus.syncNotify(addLapEvent());
        // The stable handler must have received the event
        assertThat(stableReceived).hasSize(1);
    void busRemainsUsableAfterHandlerCrash() throws InterruptedException {
        // Crashing handler — will be removed after first event
                        throw new RuntimeException("crash on first event");
        // Send first event (crashes the handler, removes it)
        // Register a new stable handler after the crash
        // Second event should still be delivered to the new handler
        var lap2 = SqlRaceLap.flyingLap(2_000_000_000L, 1);
        bus.notify(new SqlRaceSessionEvent.AddLap(lap2));
    // ── syncNotify ────────────────────────────────────────────────────────────
    void syncNotifyBlocksUntilAllHandlersComplete() throws InterruptedException {
        var order = new ArrayList<String>();
                        order.add("handler");
        order.add("after-sync");
        // syncNotify must have finished before "after-sync" is added
        assertThat(order).containsExactly("handler", "after-sync");
    void syncNotifyWithTwoHandlersWaitsForBoth() throws InterruptedException {
        var received = new AtomicInteger(0);
        for (int i = 0; i < 2; i++) {
                            received.incrementAndGet();
        // After syncNotify returns, both handlers must have completed
        assertThat(received.get()).isEqualTo(2);
    // ── deleteHandler ─────────────────────────────────────────────────────────
    void deleteHandlerRemovesItFromBus() throws InterruptedException {
        var handler =
                new EventManager.Handler<SqlRaceSessionEvent>() {
                        received.incrementAndGet();
                };
        bus.addHandler(handler);
        // Deliver first event — handler receives it
        assertThat(received.get()).isEqualTo(1);
        // Remove the handler
        boolean removed = bus.removeHandler(handler);
        assertThat(removed).isTrue();
        // Deliver second event — handler must NOT receive it
        assertThat(received.get()).isEqualTo(1); // still 1, not 2
    void deleteHandlerReturnsFalseIfNotRegistered() {
        var unregistered =
                    public void handleEvent(SqlRaceSessionEvent event) {}
        boolean removed = bus.removeHandler(unregistered);
        assertThat(removed).isFalse();
    // ── Forwarding handler ────────────────────────────────────────────────────
    void forwardingHandlerRoutesEventsToSession() throws InterruptedException {
        // Construct a live session and wire it to the bus via the forwarding handler
        var session = SqlRaceSession.create("forwarding-test");
        var params = List.of(SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph"));
        var channels =
                List.of(
                        SqlRaceChannel.periodic(
                                1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit));
        var conversions = List.<RationalConversion>of();
        // Configure session: Initializing → Live (Configure is the "open" event in SQL Race)
        session.send(new SqlRaceSessionEvent.Configure(params, channels, conversions));
        // Register forwarding handler
        var fwd = SessionEventBus.forwardingHandler(session);
        bus.addHandler(fwd);
        // Publish AddLap via the bus — forwarding handler routes to the session
        bus.syncNotify(new SqlRaceSessionEvent.AddLap(outLap()));
        // Give the session time to process the forwarded event
        Thread.sleep(30);
        // Session should now have a lap in its data
        assertThat(session.data().laps()).hasSize(1);
        session.stop();
    // ── Configure + DataItem events ───────────────────────────────────────────
    void configureEventDeliveredToHandler() throws InterruptedException {
        var event =
                new SqlRaceSessionEvent.Configure(
                        List.of(SqlRaceParameter.of("vCar", "Chassis", 1L, 0.0, 400.0, "kph")),
                        List.of(
                                SqlRaceChannel.periodic(
                                        1L, "vCar", 200.0, FrequencyUnit.Hz, DataType.Signed16Bit)),
                        List.of());
        bus.syncNotify(event);
        assertThat(received.get(0)).isInstanceOf(SqlRaceSessionEvent.Configure.class);
}
