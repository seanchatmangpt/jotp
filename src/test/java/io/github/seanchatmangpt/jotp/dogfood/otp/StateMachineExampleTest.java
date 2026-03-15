package io.github.seanchatmangpt.jotp.dogfood.otp;

import static org.assertj.core.api.Assertions.*;
import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.dogfood.otp.StateMachineExample.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
/**
 * Test suite for {@link StateMachineExample}.
 *
 * <p>Verifies correct state transitions, event handling, data updates, and the distinction between
 * async send() and sync call() operations in the turnstile FSM.
 */
class StateMachineExampleTest {
    @BeforeEach
    void setup() {
        ApplicationController.reset();
        // Each test gets a fresh state machine
    }
    @Test
    @DisplayName("Initial state should be Locked with zero balance")
    void testInitialState() throws InterruptedException {
        var sm =
                io.github.seanchatmangpt.jotp.StateMachine.of(
                        new TurnstileState.Locked(),
                        new TurnstileData(0, 0),
                        StateMachineExample::handleEvent);
        assertThat(sm.state()).isInstanceOf(TurnstileState.Locked.class);
        assertThat(sm.data()).isEqualTo(new TurnstileData(0, 0));
        assertThat(sm.isRunning()).isTrue();
        sm.stop();
    @DisplayName("Coin event should accumulate balance in Locked state")
    void testCoinAccumulation() throws InterruptedException {
        // Send two coins asynchronously
        sm.send(new TurnstileEvent.Coin(25));
        Thread.sleep(100); // Allow processing
        // State should remain Locked but balance should be 50¢
        assertThat(sm.data().balance()).isEqualTo(50);
        assertThat(sm.data().totalCoins()).isEqualTo(50);
    @DisplayName("Pass with insufficient balance should keep state Locked")
    void testPassWithInsufficientBalance() throws InterruptedException {
                        new TurnstileData(25, 25),
        // Send pass event (insufficient balance)
        var data = sm.call(new TurnstileEvent.Pass()).join();
        assertThat(data.balance()).isEqualTo(25);
    @DisplayName("Pass with sufficient balance should transition to Unlocked")
    void testPassWithSufficientBalance() throws InterruptedException {
                        new TurnstileData(50, 100),
        // Send pass event (sufficient balance)
        assertThat(sm.state()).isInstanceOf(TurnstileState.Unlocked.class);
        assertThat(data.balance()).isEqualTo(0); // Balance reset after unlock
        assertThat(data.totalCoins()).isEqualTo(100); // Total preserved
    @DisplayName("Pass in Unlocked state should return to Locked")
    void testPassInUnlockedState() throws InterruptedException {
                        new TurnstileState.Unlocked(),
                        new TurnstileData(0, 50),
        // Send pass event from unlocked state
        assertThat(data.balance()).isEqualTo(0);
        assertThat(data.totalCoins()).isEqualTo(50);
    @DisplayName("Coin in Unlocked state should be ignored")
    void testCoinInUnlockedStateIgnored() throws InterruptedException {
        // Send coin event while unlocked
        Thread.sleep(100);
        // State remains unlocked, data unchanged
        assertThat(sm.data().balance()).isEqualTo(0);
    @DisplayName("send() should be async (fire-and-forget)")
    void testSendIsAsync() throws InterruptedException {
        // send() returns immediately without waiting for processing
        var data = sm.data();
        // At this point, the event may or may not have been processed
        // data snapshot should be unchanged
        assertThat(sm.data().balance()).isEqualTo(0); // Initially unchanged
        Thread.sleep(100); // Wait for processing
        assertThat(sm.data().balance()).isEqualTo(25); // Now updated
    @DisplayName("call() should be sync (request-reply)")
    void testCallIsSync() throws InterruptedException {
                        new TurnstileData(40, 0),
        // call() returns a future that completes after event is processed
        var future = sm.call(new TurnstileEvent.Coin(25));
        // At this point, the event has been processed
        var data = future.join();
        assertThat(data.balance()).isEqualTo(65);
        assertThat(sm.data().balance()).isEqualTo(65);
    @DisplayName("Concurrent sends should maintain FIFO ordering")
    void testFifoOrderingOfSends() throws InterruptedException {
        // Send multiple coins in sequence
        for (int i = 1; i <= 5; i++) {
            sm.send(new TurnstileEvent.Coin(10));
        }
        Thread.sleep(200); // Wait for all events to be processed
        // Balance should be sum of all coins in order: 10+10+10+10+10 = 50
    @DisplayName("Complete FSM cycle: coins -> unlock -> lock")
    void testCompleteCycle() throws InterruptedException {
        // Deposit coins
        sm.send(new TurnstileEvent.Coin(30));
        sm.send(new TurnstileEvent.Coin(20));
        // Verify locked with sufficient balance
        // Pass to unlock
        var data1 = sm.call(new TurnstileEvent.Pass()).join();
        assertThat(data1.balance()).isEqualTo(0);
        // Pass to lock
        var data2 = sm.call(new TurnstileEvent.Pass()).join();
        assertThat(data2.balance()).isEqualTo(0);
        assertThat(data2.totalCoins()).isEqualTo(50);
    @DisplayName("Call after shutdown should fail")
    @Timeout(5)
    void testCallAfterShutdown() throws InterruptedException {
        // Attempting to call should fail
        assertThatThrownBy(future::join).hasCauseInstanceOf(IllegalStateException.class);
    @DisplayName("Data immutability: original data not modified")
    void testDataImmutability() throws InterruptedException {
        var initialData = new TurnstileData(0, 0);
                        new TurnstileState.Locked(), initialData, StateMachineExample::handleEvent);
        // Send coin event
        // Original data should be unchanged
        assertThat(initialData).isEqualTo(new TurnstileData(0, 0));
        // State machine should have new data
        assertThat(sm.data()).isEqualTo(new TurnstileData(25, 25));
    @DisplayName("Multiple pass attempts at threshold")
    void testMultiplePassesAtThreshold() throws InterruptedException {
                        new TurnstileData(50, 150),
        // First pass: should unlock and reset balance
        // Second pass: should lock
        // Deposit enough and pass again
        sm.send(new TurnstileEvent.Coin(50));
        var data3 = sm.call(new TurnstileEvent.Pass()).join();
        assertThat(data3.balance()).isEqualTo(0);
        assertThat(data3.totalCoins()).isEqualTo(200);
    @DisplayName("TurnstileData validation")
    void testTurnstileDataValidation() {
        assertThatThrownBy(() -> new TurnstileData(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance cannot be negative");
        assertThatThrownBy(() -> new TurnstileData(0, -1))
                .hasMessageContaining("totalCoins cannot be negative");
        // Valid data should not throw
        assertThat(new TurnstileData(0, 0)).isNotNull();
        assertThat(new TurnstileData(100, 200)).isNotNull();
    @DisplayName("Coin event validation")
    void testCoinEventValidation() {
        assertThatThrownBy(() -> new TurnstileEvent.Coin(0))
                .hasMessageContaining("coin amount must be positive");
        assertThatThrownBy(() -> new TurnstileEvent.Coin(-10))
        // Valid coins should not throw
        assertThat(new TurnstileEvent.Coin(1)).isNotNull();
        assertThat(new TurnstileEvent.Coin(100)).isNotNull();
}
