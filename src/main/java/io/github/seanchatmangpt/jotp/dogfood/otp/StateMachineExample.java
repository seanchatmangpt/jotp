package io.github.seanchatmangpt.jotp.dogfood.otp;

import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;

/**
 * Dogfood: StateMachine demonstration for finite state machine patterns.
 *
 * <p>This example implements a turnstile FSM (finite state machine) using {@code
 * StateMachine<S,E,D>}, demonstrating the separation of state, event, and data concerns.
 *
 * <p><b>States:</b> locked (barrier closed) or unlocked (barrier open)
 *
 * <p><b>Events:</b> coin deposited (with amount) or pass attempt
 *
 * <p><b>Data:</b> accumulated balance and total coins collected
 *
 * @see StateMachine
 * @see Transition
 */
public final class StateMachineExample {

    // ── Sealed State Hierarchy ────────────────────────────────────────────────

    /**
     * Turnstile state.
     *
     * <p>Either locked (barrier closed) or unlocked (barrier open), permitting exhaustive pattern
     * matching.
     */
    sealed interface TurnstileState permits TurnstileState.Locked, TurnstileState.Unlocked {

        /** Barrier is closed; no one can pass without sufficient payment. */
        record Locked() implements TurnstileState {
            @Override
            public String toString() {
                return "Locked";
            }
        }

        /** Barrier is open; one person can pass; returns to Locked after pass or timeout. */
        record Unlocked() implements TurnstileState {
            @Override
            public String toString() {
                return "Unlocked";
            }
        }
    }

    // ── Sealed Event Hierarchy ────────────────────────────────────────────────

    /**
     * External event delivered to the turnstile.
     *
     * <p>Either a coin deposit or a pass attempt, permitting exhaustive pattern matching.
     */
    sealed interface TurnstileEvent permits TurnstileEvent.Coin, TurnstileEvent.Pass {

        /** User deposits a coin with the given amount (in cents). */
        record Coin(int amount) implements TurnstileEvent {
            public Coin {
                if (amount <= 0) {
                    throw new IllegalArgumentException(
                            "coin amount must be positive, got " + amount);
                }
            }

            @Override
            public String toString() {
                return "Coin(" + amount + "¢)";
            }
        }

        /** User attempts to pass through the turnstile. */
        record Pass() implements TurnstileEvent {
            @Override
            public String toString() {
                return "Pass";
            }
        }
    }

    // ── Data Type ─────────────────────────────────────────────────────────────

    /**
     * Mutable context carried across state transitions.
     *
     * <p>Tracks balance accumulated from coins and total coins collected.
     */
    record TurnstileData(int balance, int totalCoins) {
        public TurnstileData {
            if (balance < 0) {
                throw new IllegalArgumentException("balance cannot be negative, got " + balance);
            }
            if (totalCoins < 0) {
                throw new IllegalArgumentException(
                        "totalCoins cannot be negative, got " + totalCoins);
            }
        }

        /**
         * Adds a coin deposit to the balance and total coins count.
         *
         * @param amount coin amount in cents
         * @return new data with updated balance and totalCoins
         */
        public TurnstileData addCoin(int amount) {
            return new TurnstileData(balance + amount, totalCoins + amount);
        }

        /**
         * Resets the balance after a successful pass.
         *
         * @return new data with balance reset to 0
         */
        public TurnstileData passThrough() {
            return new TurnstileData(0, totalCoins);
        }

        @Override
        public String toString() {
            return String.format("TurnstileData(balance=%d¢, totalCoins=%d¢)", balance, totalCoins);
        }
    }

    // ── Transition Handler ────────────────────────────────────────────────────

    /**
     * Pure transition function implementing turnstile FSM logic.
     *
     * <p><b>Rules:</b>
     *
     * <ul>
     *   <li>Locked + Coin → accumulate balance, stay Locked
     *   <li>Locked + Pass → if balance >= 50¢, transition to Unlocked; else stay Locked
     *   <li>Unlocked + Pass → reset balance, return to Locked
     *   <li>Unlocked + Coin → stay Unlocked (ignore; barrier is already open)
     * </ul>
     *
     * @param state current state (Locked or Unlocked)
     * @param event incoming event (Coin or Pass)
     * @param data accumulated balance and total coins
     * @return transition action (nextState, keepState, or stop)
     */
    static Transition<TurnstileState, TurnstileData> handleEvent(
            TurnstileState state, TurnstileEvent event, TurnstileData data) {
        return switch (state) {
            case TurnstileState.Locked _ ->
                    switch (event) {
                        case TurnstileEvent.Coin(var amount) -> {
                            // Accumulate coin, stay locked
                            var newData = data.addCoin(amount);
                            yield Transition.keepState(newData);
                        }
                        case TurnstileEvent.Pass _ -> {
                            // Check if balance exceeds threshold (50¢)
                            if (data.balance() >= 50) {
                                // Unlock and reset balance for next cycle
                                var newData = data.passThrough();
                                yield Transition.nextState(new TurnstileState.Unlocked(), newData);
                            } else {
                                // Not enough; stay locked
                                yield Transition.keepState(data);
                            }
                        }
                    };
            case TurnstileState.Unlocked _ ->
                    switch (event) {
                        case TurnstileEvent.Pass _ -> {
                            // Person passed; return to locked
                            yield Transition.nextState(new TurnstileState.Locked(), data);
                        }
                        case TurnstileEvent.Coin(var amount) -> {
                            // Barrier is open; ignore coins until locked again
                            yield Transition.keepState(data);
                        }
                    };
        };
    }

    // ── Demo ──────────────────────────────────────────────────────────────────

    /**
     * Demonstrates asynchronous events ({@code send}) and synchronous request-reply ({@code call}).
     *
     * <p>Turnstile state machine with:
     *
     * <ul>
     *   <li>Initial state: Locked
     *   <li>Initial balance: 0¢
     *   <li>Pass threshold: 50¢
     * </ul>
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Turnstile FSM Demo ===\n");

        // Create state machine: initial state Locked, initial data (0¢ balance, 0¢ total collected)
        var turnstile =
                StateMachine.create(
                        new TurnstileState.Locked(),
                        new TurnstileData(0, 0),
                        StateMachineExample::handleEvent);

        System.out.println("Initial state: " + turnstile.state());
        System.out.println("Initial data:  " + turnstile.data());
        System.out.println();

        // Fire-and-forget: deposit coins (asynchronous)
        System.out.println("--- Fire-and-forget: deposit coins ---");
        turnstile.send(new TurnstileEvent.Coin(25));
        System.out.println("Sent: Coin(25¢)");
        turnstile.send(new TurnstileEvent.Coin(25));
        System.out.println("Sent: Coin(25¢)");
        // Brief pause to allow mailbox processing
        Thread.sleep(100);
        System.out.println(
                "After deposits: state=" + turnstile.state() + ", data=" + turnstile.data());
        System.out.println();

        // Request-reply: attempt pass with insufficient balance
        System.out.println("--- Request-reply: pass attempt (insufficient balance) ---");
        var data1 = turnstile.call(new TurnstileEvent.Pass()).join();
        System.out.println("After Pass: state=" + turnstile.state() + ", data=" + data1);
        System.out.println();

        // Fire-and-forget: add another coin (sufficient for pass)
        System.out.println("--- Fire-and-forget: deposit additional coin ---");
        turnstile.send(new TurnstileEvent.Coin(25));
        System.out.println("Sent: Coin(25¢)");
        Thread.sleep(100);
        System.out.println(
                "After deposit: state=" + turnstile.state() + ", data=" + turnstile.data());
        System.out.println();

        // Request-reply: attempt pass with sufficient balance
        System.out.println("--- Request-reply: pass attempt (sufficient balance) ---");
        var data2 = turnstile.call(new TurnstileEvent.Pass()).join();
        System.out.println("After Pass: state=" + turnstile.state() + ", data=" + data2);
        System.out.println(
                "Transitioned to Unlocked? "
                        + (turnstile.state() instanceof TurnstileState.Unlocked));
        System.out.println();

        // Fire-and-forget: deposit coin while unlocked (should be ignored)
        System.out.println("--- Fire-and-forget: deposit coin while unlocked ---");
        turnstile.send(new TurnstileEvent.Coin(25));
        System.out.println("Sent: Coin(25¢) while unlocked");
        Thread.sleep(100);
        System.out.println("State: " + turnstile.state() + ", data=" + turnstile.data());
        System.out.println();

        // Request-reply: pass through to return to locked
        System.out.println("--- Request-reply: pass through (return to locked) ---");
        var data3 = turnstile.call(new TurnstileEvent.Pass()).join();
        System.out.println("After Pass: state=" + turnstile.state() + ", data=" + data3);
        System.out.println(
                "Transitioned to Locked? " + (turnstile.state() instanceof TurnstileState.Locked));
        System.out.println();

        // Verify total coins collected
        System.out.println("--- Summary ---");
        System.out.println("Final state: " + turnstile.state());
        System.out.println("Final data:  " + turnstile.data());
        System.out.println("Total collected: " + turnstile.data().totalCoins() + "¢");
        System.out.println("Running: " + turnstile.isRunning());

        // Graceful shutdown
        turnstile.stop();
        System.out.println("\nShutdown complete. Running: " + turnstile.isRunning());
    }
}
