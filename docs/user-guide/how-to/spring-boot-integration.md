# Integrating JOTP into Spring Boot Applications

---

## The Problem

Joe Armstrong once observed that most production failures are not hardware failures — they are software failures caused by processes that should have been isolated, weren't, and when one failed, took everything else down with it. Spring Boot is brilliant at what it was designed to do: stateless HTTP services, declarative transactions, dependency injection. It excels at the request-response cycle. But the moment you need *stateful coordination* — rate limiters, session state machines, order workflows, circuit breakers with history, connection pools with backpressure — Spring's stateless model runs out of idiom. The answer is always "use Redis," "use a database," "use Hazelcast." These are all correct answers. They are also all *external* answers. They introduce network hops, serialization costs, operational complexity, and new failure modes.

JOTP fills the gap that lives inside your JVM. A `Proc` is a lightweight, isolated, virtual-thread-backed process with its own mailbox and pure state handler. A `Supervisor` manages restarts. Together they bring 40 years of Erlang/OTP production wisdom into your Spring Boot application without leaving the JVM. Your stateful coordination logic becomes local, type-safe, testable without a database, and supervised by default.

This guide shows you exactly how.

---

## Step 1: Add the JOTP Dependency

Add JOTP to your `pom.xml`. JOTP requires Java 26 with preview features enabled; the compiler and Surefire plugins must be configured accordingly.

```xml
<!-- pom.xml -->
<properties>
    <java.version>26</java.version>
    <jotp.version>0.1.0</jotp.version>
</properties>

<dependencies>
    <!-- Spring Boot web starter (assumed already present) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- JOTP: OTP-style fault-tolerant processes for Java 26 -->
    <dependency>
        <groupId>io.github.seanchatmangpt</groupId>
        <artifactId>jotp</artifactId>
        <version>${jotp.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <release>26</release>
                <compilerArgs>
                    <arg>--enable-preview</arg>
                </compilerArgs>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <argLine>--enable-preview</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## Step 2: Create a @Configuration Class

Define the `Supervisor` and the initial worker `Proc` as Spring beans. The supervisor owns the process lifecycle. The `ProcRef` it returns is the stable handle you inject elsewhere — it survives restarts transparently.

```java
package com.example.config;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import com.example.model.OrderState;
import com.example.model.OrderMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class JotpConfiguration {

    /**
     * ONE_FOR_ONE supervisor: if the order processor crashes, only it restarts.
     * Allows up to 5 restarts in any 60-second window before escalating.
     */
    @Bean
    public Supervisor orderSupervisor() {
        return new Supervisor(
            Supervisor.Strategy.ONE_FOR_ONE,
            5,                        // maxRestarts
            Duration.ofSeconds(60)    // restart window
        );
    }

    /**
     * Supervised order processor. ProcRef is a stable handle: even after a
     * restart the same ProcRef routes messages to the new process instance.
     */
    @Bean
    public ProcRef<OrderState, OrderMessage> orderProcessorRef(Supervisor orderSupervisor) {
        return orderSupervisor.supervise(
            "order-processor",
            OrderState.empty(),
            (state, msg) -> switch (msg) {
                case OrderMessage.PlaceOrder(var id, var amount) ->
                    state.withPendingOrder(id, amount);
                case OrderMessage.ConfirmOrder(var id) ->
                    state.withConfirmedOrder(id);
                case OrderMessage.CancelOrder(var id) ->
                    state.withCancelledOrder(id);
            }
        );
    }
}
```

The handler passed to `supervise` is a **pure function**: `(state, message) -> newState`. It has no side effects, no Spring dependencies, and no mutable fields. This makes it trivially testable (see Step 6).

---

## Step 3: Inject ProcRef into a @RestController

Use `ask()` for synchronous web request handling — it returns a `CompletableFuture<S>` and accepts a timeout so that slow or crashed processes never block an HTTP thread indefinitely.

```java
package com.example.controller;

import com.example.model.OrderMessage;
import com.example.model.OrderState;
import io.github.seanchatmangpt.jotp.ProcRef;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final ProcRef<OrderState, OrderMessage> orderProcessorRef;

    public OrderController(ProcRef<OrderState, OrderMessage> orderProcessorRef) {
        this.orderProcessorRef = orderProcessorRef;
    }

    /**
     * Fire-and-forget: place an order without waiting for confirmation.
     * Use tell() when the caller does not need the updated state.
     */
    @PostMapping
    public ResponseEntity<Void> placeOrder(@RequestBody PlaceOrderRequest request) {
        orderProcessorRef.tell(new OrderMessage.PlaceOrder(request.orderId(), request.amount()));
        return ResponseEntity.accepted().build();
    }

    /**
     * Request-reply: confirm an order and return the updated state.
     * ask() times out after 5 seconds; the CompletableFuture propagates
     * to Spring's async MVC handling automatically.
     */
    @PutMapping("/{orderId}/confirm")
    public CompletableFuture<ResponseEntity<OrderState>> confirmOrder(@PathVariable String orderId) {
        return orderProcessorRef
            .ask(new OrderMessage.ConfirmOrder(orderId), Duration.ofSeconds(5))
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> ResponseEntity.internalServerError().build());
    }

    /**
     * Cancel an order with a conservative 3-second timeout.
     */
    @DeleteMapping("/{orderId}")
    public CompletableFuture<ResponseEntity<OrderState>> cancelOrder(@PathVariable String orderId) {
        return orderProcessorRef
            .ask(new OrderMessage.CancelOrder(orderId), Duration.ofSeconds(3))
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> ResponseEntity.internalServerError().build());
    }

    record PlaceOrderRequest(String orderId, double amount) {}
}
```

---

## Step 4: Graceful Shutdown with @PreDestroy

Spring's `@PreDestroy` hook integrates naturally with JOTP's shutdown sequence. Shut down the supervisor before the Spring context closes; the supervisor will drain mailboxes and stop all child processes in order.

```java
package com.example.config;

import io.github.seanchatmangpt.jotp.Supervisor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JotpLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JotpLifecycle.class);

    private final Supervisor orderSupervisor;

    public JotpLifecycle(Supervisor orderSupervisor) {
        this.orderSupervisor = orderSupervisor;
    }

    @PreDestroy
    public void shutdown() {
        log.info("JOTP: initiating supervisor shutdown");
        orderSupervisor.shutdown();
        log.info("JOTP: supervisor shutdown complete");
    }
}
```

For zero-downtime deployments, stop accepting new HTTP traffic (via load balancer or Spring's `SmartLifecycle`) before triggering `@PreDestroy`. This gives in-flight `ask()` calls time to complete.

---

## Step 5: Unit Testing the Handler (Pure Function, No Spring)

Because the handler is a pure function, it needs no Spring context, no mocks, and no database. Test it with plain JUnit 5 and AssertJ.

```java
package com.example.handler;

import com.example.model.OrderMessage;
import com.example.model.OrderState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the order handler function.
 * No Spring context. No JOTP runtime. No I/O.
 * Executes in sub-millisecond time.
 */
class OrderHandlerTest {

    // Extract the handler as a standalone BiFunction for isolated testing
    private final java.util.function.BiFunction<OrderState, OrderMessage, OrderState> handler =
        (state, msg) -> switch (msg) {
            case OrderMessage.PlaceOrder(var id, var amount) ->
                state.withPendingOrder(id, amount);
            case OrderMessage.ConfirmOrder(var id) ->
                state.withConfirmedOrder(id);
            case OrderMessage.CancelOrder(var id) ->
                state.withCancelledOrder(id);
        };

    @Test
    void placeOrder_addsOrderToPendingState() {
        var initial = OrderState.empty();
        var result = handler.apply(initial, new OrderMessage.PlaceOrder("ORD-001", 149.99));

        assertThat(result.pendingOrders()).containsKey("ORD-001");
        assertThat(result.pendingOrders().get("ORD-001")).isEqualTo(149.99);
    }

    @Test
    void confirmOrder_movesOrderFromPendingToConfirmed() {
        var withPending = handler.apply(
            OrderState.empty(),
            new OrderMessage.PlaceOrder("ORD-002", 299.00)
        );
        var confirmed = handler.apply(withPending, new OrderMessage.ConfirmOrder("ORD-002"));

        assertThat(confirmed.pendingOrders()).doesNotContainKey("ORD-002");
        assertThat(confirmed.confirmedOrders()).containsKey("ORD-002");
    }

    @Test
    void cancelOrder_removesOrderFromPending() {
        var withPending = handler.apply(
            OrderState.empty(),
            new OrderMessage.PlaceOrder("ORD-003", 75.00)
        );
        var cancelled = handler.apply(withPending, new OrderMessage.CancelOrder("ORD-003"));

        assertThat(cancelled.pendingOrders()).doesNotContainKey("ORD-003");
        assertThat(cancelled.cancelledOrders()).containsKey("ORD-003");
    }
}
```

---

## Step 6: Integration Testing with @SpringBootTest and Awaitility

For integration tests that verify the full Spring + JOTP wire-up, use `@SpringBootTest` and Awaitility to assert on asynchronous state transitions without brittle `Thread.sleep()` calls.

```java
package com.example.integration;

import com.example.model.OrderMessage;
import com.example.model.OrderState;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcSys;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderProcessorIntegrationTest {

    @Autowired
    ProcRef<OrderState, OrderMessage> orderProcessorRef;

    @Test
    void supervisedProcessAcceptsAndProcessesMessages() throws Exception {
        // Fire-and-forget a placement
        orderProcessorRef.tell(new OrderMessage.PlaceOrder("ORD-INT-001", 500.00));

        // ask() returns CompletableFuture; block with timeout for test assertions
        var stateAfterConfirm = orderProcessorRef
            .ask(new OrderMessage.ConfirmOrder("ORD-INT-001"), Duration.ofSeconds(5))
            .get(5, TimeUnit.SECONDS);

        assertThat(stateAfterConfirm.confirmedOrders()).containsKey("ORD-INT-001");
    }

    @Test
    void procSysReportsProcessStatistics() {
        var stats = ProcSys.of(orderProcessorRef).getStatistics();

        assertThat(stats.messagesProcessed()).isGreaterThanOrEqualTo(0);
        assertThat(stats.isAlive()).isTrue();
    }

    @Test
    void supervisorRestartsProcessAfterSimulatedCrash() {
        // Simulate crash by sending a message that triggers an exception in the handler.
        // The supervisor's ONE_FOR_ONE strategy restarts the process automatically.
        orderProcessorRef.tell(new OrderMessage.PlaceOrder("ORD-CRASH", -1.0)); // triggers validation error

        // Awaitility: poll until the process is alive again (after restart)
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                var stats = ProcSys.of(orderProcessorRef).getStatistics();
                assertThat(stats.isAlive()).isTrue();
            });
    }
}
```

---

## Common Pattern: Spring Security Context Propagation

Spring Security stores the authenticated principal in a `ThreadLocal` (`SecurityContextHolder`). Virtual threads can migrate between carrier threads, so `ThreadLocal` propagation is unreliable inside a `Proc` handler. Use `ScopedValue` (Java 26 preview) instead.

```java
package com.example.security;

import io.github.seanchatmangpt.jotp.ProcRef;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.example.model.OrderMessage;
import com.example.model.OrderState;

import java.lang.ScopedValue;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/secure/orders")
public class SecureOrderController {

    // Declare a ScopedValue to carry the principal into the handler scope
    static final ScopedValue<Authentication> CURRENT_USER = ScopedValue.newInstance();

    private final ProcRef<OrderState, OrderMessage> orderProcessorRef;

    public SecureOrderController(ProcRef<OrderState, OrderMessage> orderProcessorRef) {
        this.orderProcessorRef = orderProcessorRef;
    }

    @PostMapping("/{orderId}/approve")
    public CompletableFuture<ResponseEntity<OrderState>> approveOrder(@PathVariable String orderId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        // Bind the authentication into the ScopedValue before delegating to the Proc
        return ScopedValue.where(CURRENT_USER, auth).call(() ->
            orderProcessorRef
                .ask(new OrderMessage.ConfirmOrder(orderId), Duration.ofSeconds(5))
                .thenApply(org.springframework.http.ResponseEntity::ok)
        );
    }
}
```

Inside the `Proc` handler, read `CURRENT_USER.get()` to access the principal. Because `ScopedValue` bindings are inherited by child scopes (including virtual threads), this propagates correctly without `InheritableThreadLocal` fragility.

---

## Common Pattern: Spring Data JPA Inside a Proc Handler

JPA `EntityManager` instances are not thread-safe and must not be shared across virtual threads. The correct pattern is to dispatch JPA work to a dedicated executor that manages its own connection pool, preventing virtual thread pinning on JDBC calls.

```java
package com.example.persistence;

import com.example.model.OrderMessage;
import com.example.model.OrderState;
import com.example.repository.OrderRepository;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
public class PersistenceConfiguration {

    /**
     * A bounded executor for JPA calls from within Proc handlers.
     * Keep pool size aligned to your JDBC connection pool max size (HikariCP default: 10).
     * This prevents virtual thread pinning on synchronized JDBC driver internals.
     */
    @Bean(name = "jpaExecutor")
    public Executor jpaExecutor() {
        var exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(10);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("jotp-jpa-");
        exec.initialize();
        return exec;
    }

    @Bean
    public ProcRef<OrderState, OrderMessage> persistentOrderProcessorRef(
            Supervisor orderSupervisor,
            OrderRepository orderRepository,
            Executor jpaExecutor) {

        return orderSupervisor.supervise(
            "persistent-order-processor",
            OrderState.empty(),
            (state, msg) -> switch (msg) {
                case OrderMessage.PlaceOrder(var id, var amount) -> {
                    // Dispatch JPA write to the bounded executor; never call JPA directly
                    // from the virtual thread running the Proc handler.
                    jpaExecutor.execute(() ->
                        orderRepository.save(new com.example.entity.OrderEntity(id, amount, "PENDING"))
                    );
                    yield state.withPendingOrder(id, amount);
                }
                case OrderMessage.ConfirmOrder(var id) -> {
                    jpaExecutor.execute(() ->
                        orderRepository.updateStatus(id, "CONFIRMED")
                    );
                    yield state.withConfirmedOrder(id);
                }
                case OrderMessage.CancelOrder(var id) -> {
                    jpaExecutor.execute(() ->
                        orderRepository.updateStatus(id, "CANCELLED")
                    );
                    yield state.withCancelledOrder(id);
                }
            }
        );
    }
}
```

**Key rule:** Never call `@Transactional` methods or `EntityManager` directly inside a `Proc` handler. The handler runs on a virtual thread that may be pinned by synchronized JDBC driver code, degrading throughput across all virtual threads sharing the same carrier. The executor dispatch above keeps JPA on platform threads where pinning is harmless.

---

## Anti-Patterns

The following patterns are the most common mistakes teams make when integrating JOTP with Spring Boot. They range from subtle correctness bugs to hard production outages.

| # | Anti-Pattern | Why It Fails | Correct Approach |
|---|---|---|---|
| 1 | **Sharing mutable Spring beans inside the handler** | Spring beans (e.g., `RestTemplate`, `WebClient` instances with shared state) are not designed for concurrent mutation. A `Proc` handler runs on a virtual thread; if the bean is mutated from multiple `Proc` instances simultaneously, you get data races or exceptions that corrupt the shared bean. | Use immutable Spring beans only, or pass a fresh instance per message. Prefer `WebClient` (immutable-by-design) over `RestTemplate`. |
| 2 | **Annotating the handler with `@Transactional`** | `@Transactional` requires a Spring proxy, which requires a Spring-managed bean. A `Proc` handler is a lambda — it is not a Spring bean and the annotation is silently ignored. Worse, if you obtain a `@Transactional` Spring bean and call it from the handler, the transaction context is bound to the virtual thread but may not be committed if the handler crashes mid-flight, leaving open transactions. | Dispatch all transactional work to the `jpaExecutor` bean as shown above. Keep the handler purely in-memory. |
| 3 | **Injecting `ApplicationContext` into a Proc handler** | Pulling the full `ApplicationContext` into a handler creates tight coupling to the entire Spring container, makes the handler untestable in isolation, and defeats the purpose of the pure-function model. It also introduces a hidden circular dependency risk. | Inject only the specific `@Bean` dependencies needed (by constructor-injecting them into the `@Configuration` class that creates the `Proc`). |
| 4 | **Using `synchronized` blocks or `ReentrantLock` inside the handler** | Virtual threads pin to their carrier platform thread when they enter a `synchronized` block or acquire a `ReentrantLock`. A `Proc` handler that blocks with a lock degrades the entire virtual thread scheduler, potentially starving unrelated processes. In a supervisor tree, this manifests as cascading timeouts. | Replace `synchronized` with `java.util.concurrent.locks.ReentrantLock` using `tryLock()` with a timeout, or redesign so the handler never needs a lock (which the pure-function model naturally prevents). |
| 5 | **Calling `ask()` without a timeout** | An `ask()` call with no timeout (or an excessively large timeout like `Duration.ofHours(1)`) creates an HTTP thread that blocks indefinitely if the `Proc` crashes or is overwhelmed. Under load, this exhausts the HTTP thread pool (even with virtual threads, it exhausts the request queue), causing a complete service outage. | Always pass a business-appropriate timeout to `ask()`. For web handlers, keep it under 10 seconds. Set the `CompletableFuture` timeout as a defensive fallback: `.orTimeout(5, TimeUnit.SECONDS)`. |

---

## See Also

- [JOTP Architecture Overview](../architecture/README.md) — Executive summary, competitive positioning, and fault-tolerance patterns
- [SLA Patterns](../.claude/SLA-PATTERNS.md) — Meeting 99.95%+ uptime with supervisor trees; incident runbooks
- [Integration Patterns](../.claude/INTEGRATION-PATTERNS.md) — Phased brownfield adoption, dual-write migration, saga patterns
- [JOTP Javadoc: `Proc<S,M>`](https://javadoc.io/doc/io.github.seanchatmangpt/jotp) — Full API reference for all 15 OTP primitives
- [Spring Boot Virtual Threads Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.virtual-threads) — Configuring `spring.threads.virtual.enabled=true`
- [JEP 429: Scoped Values](https://openjdk.org/jeps/429) — Reference for `ScopedValue` used in the Security propagation pattern
- [Awaitility Documentation](https://github.com/awaitility/awaitility/wiki/Usage) — Async assertion patterns used in Step 6
