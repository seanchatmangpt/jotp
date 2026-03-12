package io.github.seanchatmangpt.jotp.messagepatterns.construction;

import io.github.seanchatmangpt.jotp.Proc;

/**
 * Command Message pattern: a message that tells the receiver to perform an action.
 *
 * <p>Enterprise Integration Pattern: <em>Command Message</em> (EIP §6.3). Erlang analog: a tagged
 * tuple sent to a gen_server's {@code handle_call/3} or {@code handle_cast/2} — the atom tag names
 * the operation, the payload carries the arguments.
 *
 * <p>Ported from Vaughn Vernon's Reactive Messaging Patterns (Scala/Akka) to Java 26 JOTP. In the
 * original Akka implementation, command messages are case classes dispatched via pattern matching
 * in an actor's {@code receive} block. Here we use sealed interfaces and records with Java 26
 * pattern matching.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * sealed interface TradingCommand extends CommandMessage permits
 *     ExecuteBuyOrder, ExecuteSellOrder {}
 *
 * record ExecuteBuyOrder(String portfolioId, String symbol, int quantity, double price)
 *     implements TradingCommand {}
 * record ExecuteSellOrder(String portfolioId, String symbol, int quantity, double price)
 *     implements TradingCommand {}
 *
 * var trader = new Proc<>(TraderState.empty(), (state, msg) -> switch (msg) {
 *     case ExecuteBuyOrder buy -> state.withBuy(buy);
 *     case ExecuteSellOrder sell -> state.withSell(sell);
 * });
 * trader.tell(new ExecuteBuyOrder("p1", "AAPL", 100, 150.0));
 * }</pre>
 */
public interface CommandMessage {

    /**
     * Executes this command against the given processor.
     *
     * <p>Processors receive command messages and perform side-effecting operations. This
     * corresponds to Erlang's {@code handle_cast} — fire-and-forget command execution.
     *
     * @param processor the Proc that will handle this command
     * @param <S> the processor's state type
     */
    default <S> void executeOn(Proc<S, ? super CommandMessage> processor) {
        processor.tell(this);
    }
}
