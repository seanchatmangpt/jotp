package io.github.seanchatmangpt.jotp.eventsourcing;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CQRSTest {

  private CQRS.CommandBus commandBus;
  private CQRS.QueryBus queryBus;

  @BeforeEach
  public void setUp() {
    commandBus = new CQRS.CommandBus();
    queryBus = new CQRS.QueryBus();
  }

  @Test
  public void registerAndExecuteCommand() {
    // Register handler
    commandBus.register(
        CQRS.Command.CreateOrder.class,
        cmd -> {
          // Simulate command execution
          return "Order " + cmd.orderId() + " created";
        });

    // Execute command
    String result =
        commandBus.execute(new CQRS.Command.CreateOrder("order-123", "cust-456", java.util.List.of("item1")));

    assertThat(result).isEqualTo("Order order-123 created");
  }

  @Test
  public void registerAndExecuteQuery() {
    // Register handler
    queryBus.register(
        CQRS.Query.GetOrder.class,
        query -> {
          // Simulate query execution
          return new CQRS.QueryResult.OrderResult(
              query.orderId(), "pending", java.util.List.of("item1"), 99.99);
        });

    // Execute query
    CQRS.QueryResult.OrderResult result =
        queryBus.execute(new CQRS.Query.GetOrder("order-123"));

    assertThat(result.orderId()).isEqualTo("order-123");
    assertThat(result.status()).isEqualTo("pending");
    assertThat(result.items()).hasSize(1);
    assertThat(result.total()).isEqualTo(99.99);
  }

  @Test
  public void multipleCommandHandlers() {
    commandBus.register(
        CQRS.Command.CreateOrder.class,
        cmd -> "Created: " + cmd.orderId());

    commandBus.register(
        CQRS.Command.CancelOrder.class,
        cmd -> "Cancelled: " + cmd.orderId());

    String createResult =
        commandBus.execute(new CQRS.Command.CreateOrder("order-1", "cust-1", java.util.List.of()));

    String cancelResult =
        commandBus.execute(new CQRS.Command.CancelOrder("order-1", "Duplicate"));

    assertThat(createResult).contains("Created");
    assertThat(cancelResult).contains("Cancelled");
  }

  @Test
  public void multipleQueryHandlers() {
    queryBus.register(
        CQRS.Query.GetOrder.class,
        query -> new CQRS.QueryResult.OrderResult(query.orderId(), "active", java.util.List.of(), 0));

    queryBus.register(
        CQRS.Query.GetCustomerOrders.class,
        query -> new CQRS.QueryResult.CustomerOrdersResult(java.util.List.of())
    );

    CQRS.QueryResult.OrderResult orderResult =
        queryBus.execute(new CQRS.Query.GetOrder("order-1"));

    assertThat(orderResult).isNotNull();
    assertThat(orderResult.orderId()).isEqualTo("order-1");
  }

  @Test
  public void commandHandlerNotFound() {
    assertThatThrownBy(
            () -> commandBus.execute(new CQRS.Command.CreateOrder("o1", "c1", java.util.List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No handler");
  }

  @Test
  public void queryHandlerNotFound() {
    assertThatThrownBy(
            () -> queryBus.execute(new CQRS.Query.GetOrder("order-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No handler");
  }

  @Test
  public void orderResultCreation() {
    CQRS.QueryResult.OrderResult result =
        new CQRS.QueryResult.OrderResult(
            "order-789", "completed", java.util.List.of("item-a", "item-b"), 199.99);

    assertThat(result.orderId()).isEqualTo("order-789");
    assertThat(result.status()).isEqualTo("completed");
    assertThat(result.items()).hasSize(2);
    assertThat(result.total()).isEqualTo(199.99);
  }

  @Test
  public void customerOrdersResult() {
    java.util.List<CQRS.QueryResult.OrderResult> orders =
        java.util.List.of(
            new CQRS.QueryResult.OrderResult("o1", "active", java.util.List.of(), 50),
            new CQRS.QueryResult.OrderResult("o2", "completed", java.util.List.of(), 75)
        );

    CQRS.QueryResult.CustomerOrdersResult result =
        new CQRS.QueryResult.CustomerOrdersResult(orders);

    assertThat(result.orders()).hasSize(2);
  }

  @Test
  public void statsResult() {
    CQRS.QueryResult.StatsResult result =
        new CQRS.QueryResult.StatsResult(100, 75, 5000.0);

    assertThat(result.totalOrders()).isEqualTo(100);
    assertThat(result.processedOrders()).isEqualTo(75);
    assertThat(result.totalRevenue()).isEqualTo(5000.0);
  }

  @Test
  public void createOrderCommand() {
    CQRS.Command.CreateOrder cmd =
        new CQRS.Command.CreateOrder(
            "order-123", "customer-456", java.util.List.of("item1", "item2"));

    assertThat(cmd.orderId()).isEqualTo("order-123");
    assertThat(cmd.customerId()).isEqualTo("customer-456");
    assertThat(cmd.items()).hasSize(2);
  }

  @Test
  public void cancelOrderCommand() {
    CQRS.Command.CancelOrder cmd =
        new CQRS.Command.CancelOrder("order-789", "Out of stock");

    assertThat(cmd.orderId()).isEqualTo("order-789");
    assertThat(cmd.reason()).isEqualTo("Out of stock");
  }

  @Test
  public void processPaymentCommand() {
    CQRS.Command.ProcessPayment cmd =
        new CQRS.Command.ProcessPayment("order-555", "credit_card", 499.99);

    assertThat(cmd.orderId()).isEqualTo("order-555");
    assertThat(cmd.paymentMethod()).isEqualTo("credit_card");
    assertThat(cmd.amount()).isEqualTo(499.99);
  }

  @Test
  public void cqrsFacade() {
    CQRS.Facade facade = new CQRS.Facade();

    facade.commands().register(
        CQRS.Command.CreateOrder.class,
        cmd -> "Created");

    facade.queries().register(
        CQRS.Query.GetOrder.class,
        query -> new CQRS.QueryResult.OrderResult(query.orderId(), "pending", java.util.List.of(), 0)
    );

    String cmdResult =
        facade.commands().execute(new CQRS.Command.CreateOrder("o1", "c1", java.util.List.of()));

    CQRS.QueryResult.OrderResult queryResult =
        facade.queries().execute(new CQRS.Query.GetOrder("o1"));

    assertThat(cmdResult).isEqualTo("Created");
    assertThat(queryResult.orderId()).isEqualTo("o1");
  }

  @Test
  public void commandWithSideEffect() {
    java.util.List<String> sideEffects = new java.util.ArrayList<>();

    commandBus.register(
        CQRS.Command.CreateOrder.class,
        cmd -> {
          sideEffects.add("Email sent to " + cmd.customerId());
          sideEffects.add("Inventory reserved");
          return "Success";
        });

    commandBus.execute(new CQRS.Command.CreateOrder("o1", "c1", java.util.List.of()));

    assertThat(sideEffects).hasSize(2);
    assertThat(sideEffects.get(0)).contains("Email sent");
  }

  @Test
  public void queryWithComplexLogic() {
    queryBus.register(
        CQRS.Query.GetOrderStats.class,
        query -> {
          // Simulate complex query logic
          return new CQRS.QueryResult.StatsResult(
              1000, // total orders
              950, // processed
              250000.0 // revenue
          );
        });

    CQRS.QueryResult.StatsResult stats =
        queryBus.execute(new CQRS.Query.GetOrderStats());

    assertThat(stats.processedOrders()).isGreaterThan(0);
    assertThat(stats.totalRevenue()).isGreaterThan(0);
  }
}
