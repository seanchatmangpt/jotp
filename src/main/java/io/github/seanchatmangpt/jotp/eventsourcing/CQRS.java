package io.github.seanchatmangpt.jotp.eventsourcing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CQRS (Command Query Responsibility Segregation) framework.
 *
 * <p>Separates read and write models for scalability and independent optimization.
 */
public class CQRS {

  /**
   * Command handler interface.
   *
   * @param <C> command type
   * @param <R> result type
   */
  public interface CommandHandler<C, R> {
    R handle(C command);
  }

  /**
   * Query handler interface.
   *
   * @param <Q> query type
   * @param <R> result type
   */
  public interface QueryHandler<Q, R> {
    R handle(Q query);
  }

  /**
   * Command bus for dispatching commands.
   */
  public static class CommandBus {
    private final ConcurrentHashMap<Class<?>, CommandHandler<?, ?>> handlers =
        new ConcurrentHashMap<>();

    /**
     * Register a command handler.
     *
     * @param commandType the command class
     * @param handler the handler for this command
     */
    @SuppressWarnings("unchecked")
    public <C> void register(Class<C> commandType, CommandHandler<C, ?> handler) {
      handlers.put(commandType, handler);
    }

    /**
     * Execute a command.
     *
     * @param command the command to execute
     * @return the command result
     */
    @SuppressWarnings("unchecked")
    public <R> R execute(Object command) {
      CommandHandler<Object, R> handler =
          (CommandHandler<Object, R>) handlers.get(command.getClass());
      if (handler == null) {
        throw new IllegalArgumentException(
            "No handler for command: " + command.getClass().getSimpleName());
      }
      return handler.handle(command);
    }
  }

  /**
   * Query bus for dispatching queries.
   */
  public static class QueryBus {
    private final ConcurrentHashMap<Class<?>, QueryHandler<?, ?>> handlers =
        new ConcurrentHashMap<>();

    /**
     * Register a query handler.
     *
     * @param queryType the query class
     * @param handler the handler for this query
     */
    @SuppressWarnings("unchecked")
    public <Q> void register(Class<Q> queryType, QueryHandler<Q, ?> handler) {
      handlers.put(queryType, handler);
    }

    /**
     * Execute a query.
     *
     * @param query the query to execute
     * @return the query result
     */
    @SuppressWarnings("unchecked")
    public <R> R execute(Object query) {
      QueryHandler<Object, R> handler =
          (QueryHandler<Object, R>) handlers.get(query.getClass());
      if (handler == null) {
        throw new IllegalArgumentException(
            "No handler for query: " + query.getClass().getSimpleName());
      }
      return handler.handle(query);
    }
  }

  /**
   * Example command types.
   */
  public sealed interface Command {
    record CreateOrder(String orderId, String customerId, List<String> items) implements Command {}

    record CancelOrder(String orderId, String reason) implements Command {}

    record ProcessPayment(String orderId, String paymentMethod, double amount)
        implements Command {}
  }

  /**
   * Example query types.
   */
  public sealed interface Query {
    record GetOrder(String orderId) implements Query {}

    record GetCustomerOrders(String customerId) implements Query {}

    record GetOrderStats() implements Query {}
  }

  /**
   * Example query result types.
   */
  public sealed interface QueryResult {
    record OrderResult(String orderId, String status, List<String> items, double total)
        implements QueryResult {}

    record CustomerOrdersResult(List<OrderResult> orders) implements QueryResult {}

    record StatsResult(long totalOrders, long processedOrders, double totalRevenue)
        implements QueryResult {}
  }

  /**
   * CQRS facade combining command and query buses.
   */
  public static class Facade {
    private final CommandBus commandBus = new CommandBus();
    private final QueryBus queryBus = new QueryBus();

    public CommandBus commands() {
      return commandBus;
    }

    public QueryBus queries() {
      return queryBus;
    }
  }
}
