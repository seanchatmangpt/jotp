package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for Endpoint patterns ported from Vaughn Vernon's Reactive Messaging Patterns. */
@DisplayName("Endpoint Patterns")
class EndpointPatternsTest implements WithAssertions {

    @Nested
    @DisplayName("CompetingConsumer")
    class CompetingConsumerTests {

        @Test
        @DisplayName("distributes work across workers")
        void distributesWork() throws InterruptedException {
            var latch = new CountDownLatch(10);
            var processed = new AtomicInteger(0);

            var pool =
                    new CompetingConsumer<String>(
                            3,
                            msg -> {
                                processed.incrementAndGet();
                                latch.countDown();
                            });

            for (int i = 0; i < 10; i++) {
                pool.submit("work-" + i);
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(processed.get()).isEqualTo(10);
            assertThat(pool.workerCount()).isEqualTo(3);
            pool.stop();
        }
    }

    @Nested
    @DisplayName("SelectiveConsumer")
    class SelectiveConsumerTests {

        @Test
        @DisplayName("routes messages to matching consumers")
        void selectiveRouting() throws InterruptedException {
            var strings = new CopyOnWriteArrayList<Object>();
            var numbers = new CopyOnWriteArrayList<Object>();
            var rejected = new CopyOnWriteArrayList<Object>();

            var latch = new CountDownLatch(3);

            var consumer =
                    SelectiveConsumer.<Object>builder()
                            .accept(
                                    msg -> msg instanceof String,
                                    msg -> {
                                        strings.add(msg);
                                        latch.countDown();
                                    })
                            .accept(
                                    msg -> msg instanceof Integer,
                                    msg -> {
                                        numbers.add(msg);
                                        latch.countDown();
                                    })
                            .reject(
                                    msg -> {
                                        rejected.add(msg);
                                        latch.countDown();
                                    })
                            .build();

            consumer.send("hello");
            consumer.send(42);
            consumer.send(3.14);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(strings).containsExactly("hello");
            assertThat(numbers).containsExactly(42);
            assertThat(rejected).containsExactly(3.14);
            consumer.stop();
        }
    }

    @Nested
    @DisplayName("IdempotentReceiver")
    class IdempotentReceiverTests {

        record Transaction(String txId, double amount) {}

        @Test
        @DisplayName("processes each message only once")
        void deduplicates() throws InterruptedException {
            var latch = new CountDownLatch(2);
            var processed = new CopyOnWriteArrayList<String>();

            var receiver =
                    new IdempotentReceiver<Transaction, String>(
                            Transaction::txId,
                            tx -> {
                                processed.add(tx.txId());
                                latch.countDown();
                            });

            receiver.send(new Transaction("tx-1", 100.0));
            receiver.send(new Transaction("tx-1", 100.0)); // duplicate
            receiver.send(new Transaction("tx-2", 200.0));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100); // ensure duplicate isn't processed
            assertThat(processed).containsExactly("tx-1", "tx-2");
            receiver.stop();
        }
    }
}
