package io.github.seanchatmangpt.jotp.messagepatterns.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.seanchatmangpt.dtr.junit5.DtrContext;
import io.github.seanchatmangpt.jotp.ApplicationController;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Endpoint patterns ported from Vaughn Vernon's Reactive Messaging Patterns.
 *
 * <p>Enterprise Integration Patterns (EIP) endpoint patterns define how applications connect to
 * messaging channels, including consumer and producer patterns.
 */
@DisplayName("Endpoint Patterns")
class EndpointPatternsTest implements WithAssertions {

    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Nested
    @DisplayName("CompetingConsumer")
    class CompetingConsumerTests {

        @Test
        @DisplayName("distributes work across workers")
        void distributesWork(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Competing Consumer");
            ctx.say(
                    "Multiple consumers compete for messages on the same channel, enabling parallel processing and load distribution.");
            ctx.sayCode(
                    """
                    var pool = new CompetingConsumer<String>(3, msg -> {
                        processed.incrementAndGet();
                        latch.countDown();
                    });
                    for (int i = 0; i < 10; i++) {
                        pool.submit("work-" + i);
                    }
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Channel] -->|Message 1| B[Worker 1]
                        A -->|Message 2| C[Worker 2]
                        A -->|Message 3| D[Worker 3]
                        A -->|Message N| E[Worker N]
                    """);
            ctx.sayNote(
                    "Use for horizontal scaling. Add more workers to increase throughput. Each message is processed by exactly one worker.");

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
        void selectiveRouting(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Selective Consumer");
            ctx.say(
                    "Routes messages to different handlers based on type or content, enabling specialized processing within a single consumer.");
            ctx.sayCode(
                    """
                    var consumer = SelectiveConsumer.<Object>builder()
                        .accept(msg -> msg instanceof String, stringHandler)
                        .accept(msg -> msg instanceof Integer, intHandler)
                        .reject(rejectHandler)
                        .build();
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Message] --> B{Selective Consumer}
                        B -->|String| C[String Handler]
                        B -->|Integer| D[Int Handler]
                        B -->|Other| E[Reject Handler]
                    """);
            ctx.sayNote(
                    "Use when a single consumer needs to handle different message types with specialized logic, reducing the number of channels needed.");

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
        void deduplicates(DtrContext ctx) throws InterruptedException {
            ctx.sayNextSection("Idempotent Receiver");
            ctx.say(
                    "Ensures that duplicate messages are not processed multiple times by tracking message IDs and ignoring duplicates.");
            ctx.sayCode(
                    """
                    var receiver = new IdempotentReceiver<Transaction, String>(
                        Transaction::txId,
                        tx -> { processed.add(tx.txId()); latch.countDown(); });
                    receiver.send(new Transaction("tx-1", 100.0));
                    receiver.send(new Transaction("tx-1", 100.0)); // duplicate
                    """,
                    "java");
            ctx.sayMermaid(
                    """
                    graph LR
                        A[Message] --> B{Seen Before?}
                        B -->|Yes| C[Discard]
                        B -->|No| D[Process]
                        D --> E[Track ID]
                    """);
            ctx.sayNote(
                    "Critical for at-least-once delivery semantics where duplicates may occur due to retries or network issues.");

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
