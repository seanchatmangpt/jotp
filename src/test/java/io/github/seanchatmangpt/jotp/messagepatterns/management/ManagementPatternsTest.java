package io.github.seanchatmangpt.jotp.messagepatterns.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.jotp.messagepatterns.construction.CorrelationIdentifier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for Management patterns ported from Vaughn Vernon's Reactive Messaging Patterns. */
@DisplayName("Management Patterns")
class ManagementPatternsTest implements WithAssertions {

    @Nested
    @DisplayName("PipesAndFilters")
    class PipesAndFiltersTests {

        @Test
        @DisplayName("processes message through filter chain")
        void filterChain() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var result = new AtomicReference<String>();

            var pipeline =
                    PipesAndFilters.<String>builder()
                            .filter("decrypt", s -> s.replace("(encrypted)", ""))
                            .filter("authenticate", s -> s.replace("(cert)", ""))
                            .filter("trim", String::trim)
                            .endpoint(
                                    s -> {
                                        result.set(s);
                                        latch.countDown();
                                    })
                            .build();

            pipeline.process("(encrypted) Hello World (cert) ");
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.get()).isEqualTo("Hello World");
            pipeline.stop();
        }
    }

    @Nested
    @DisplayName("WireTap")
    class WireTapTests {

        @Test
        @DisplayName("taps messages without affecting primary flow")
        void tapMessages() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var tapped = new CopyOnWriteArrayList<String>();
            var primary = new AtomicReference<String>();

            var wireTap =
                    new WireTap<String>(
                            msg -> {
                                primary.set(msg);
                                latch.countDown();
                            },
                            tapped::add);

            wireTap.send("message-1");
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(primary.get()).isEqualTo("message-1");
            assertThat(tapped).containsExactly("message-1");
            wireTap.stop();
        }

        @Test
        @DisplayName("deactivated tap does not receive messages")
        void deactivatedTap() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var tapped = new CopyOnWriteArrayList<String>();

            var wireTap = new WireTap<String>(msg -> latch.countDown(), tapped::add);

            wireTap.deactivate();
            wireTap.send("message-1");
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(tapped).isEmpty();
            wireTap.stop();
        }
    }

    @Nested
    @DisplayName("SmartProxy")
    class SmartProxyTests {

        record Request(CorrelationIdentifier id, String service) {}

        record Reply(CorrelationIdentifier id, String result) {}

        @Test
        @DisplayName("tracks and routes replies to requesters")
        void tracksReplies() throws Exception {
            var serviceReceived = new CopyOnWriteArrayList<Request>();
            var proxy =
                    new SmartProxy<Request, Reply>(Request::id, Reply::id, serviceReceived::add);

            var replyResult = new AtomicReference<Reply>();
            var corrId = CorrelationIdentifier.create();

            proxy.sendRequest(new Request(corrId, "lookup"), replyResult::set);
            assertThat(proxy.pendingCount()).isEqualTo(1);

            boolean matched = proxy.deliverReply(new Reply(corrId, "found"));
            assertThat(matched).isTrue();
            assertThat(proxy.pendingCount()).isZero();
            assertThat(replyResult.get().result()).isEqualTo("found");
            proxy.stop();
        }
    }

    @Nested
    @DisplayName("TransactionalActor")
    class TransactionalActorTests {

        record OrderState(String customerId, double total, boolean placed) {
            static OrderState empty() {
                return new OrderState(null, 0, false);
            }
        }

        sealed interface OrderEvent {}

        record OrderStarted(String customerId) implements OrderEvent {}

        record ItemAdded(double price) implements OrderEvent {}

        record OrderPlaced() implements OrderEvent {}

        @Test
        @DisplayName("commit persists state changes")
        void commit() throws InterruptedException {
            var actor =
                    new TransactionalActor<OrderState, OrderEvent>(
                            OrderState.empty(),
                            (state, event) ->
                                    switch (event) {
                                        case OrderStarted e ->
                                                new OrderState(
                                                        e.customerId(), state.total(), false);
                                        case ItemAdded e ->
                                                new OrderState(
                                                        state.customerId(),
                                                        state.total() + e.price(),
                                                        false);
                                        case OrderPlaced e ->
                                                new OrderState(
                                                        state.customerId(), state.total(), true);
                                    });

            actor.apply(new OrderStarted("C001"));
            actor.apply(new ItemAdded(29.99));
            Thread.sleep(100); // let async processing complete
            actor.commit();

            assertThat(actor.committedState().customerId()).isEqualTo("C001");
            assertThat(actor.committedState().total()).isEqualTo(29.99);
            assertThat(actor.uncommittedEvents()).isEmpty();
            actor.stop();
        }

        @Test
        @DisplayName("rollback reverts to last committed state")
        void rollback() throws InterruptedException {
            var actor =
                    new TransactionalActor<OrderState, OrderEvent>(
                            OrderState.empty(),
                            (state, event) ->
                                    switch (event) {
                                        case OrderStarted e ->
                                                new OrderState(
                                                        e.customerId(), state.total(), false);
                                        case ItemAdded e ->
                                                new OrderState(
                                                        state.customerId(),
                                                        state.total() + e.price(),
                                                        false);
                                        case OrderPlaced e ->
                                                new OrderState(
                                                        state.customerId(), state.total(), true);
                                    });

            actor.apply(new OrderStarted("C001"));
            Thread.sleep(100);
            actor.commit();

            actor.apply(new ItemAdded(99.99));
            Thread.sleep(100);
            actor.rollback();

            assertThat(actor.committedState().customerId()).isEqualTo("C001");
            assertThat(actor.tentativeState().total()).isEqualTo(0.0);
            actor.stop();
        }
    }
}
