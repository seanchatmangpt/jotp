package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.BeforeEach;
class ProcTest implements WithAssertions {
    // Sealed message hierarchy — exhaustive pattern matching, zero virtual dispatch
    sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.Reset, CounterMsg.Noop {
        record Increment(int by) implements CounterMsg {}
        record Reset() implements CounterMsg {}
        record Noop() implements CounterMsg {}
    }
    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    private static int handleCounter(int state, CounterMsg msg) {
        return switch (msg) {
            case CounterMsg.Increment(var by) -> state + by;
            case CounterMsg.Reset() -> 0;
            case CounterMsg.Noop() -> state;
        };
    @Test
    void tellAndAsk() throws Exception {
        var proc = new Proc<>(0, ProcTest::handleCounter);
        proc.tell(new CounterMsg.Increment(10));
        proc.tell(new CounterMsg.Increment(5));
        var state = proc.ask(new CounterMsg.Noop()).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(15);
        proc.stop();
    void resetReturnsToZero() throws Exception {
        proc.tell(new CounterMsg.Increment(100));
        proc.tell(new CounterMsg.Reset());
        assertThat(state).isZero();
    void manyMessagesFromMultipleThreads() throws Exception {
        var senders = new Thread[10];
        for (int i = 0; i < senders.length; i++) {
            senders[i] =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        for (int j = 0; j < 100; j++) {
                                            proc.tell(new CounterMsg.Increment(1));
                                        }
                                    });
        }
        for (var s : senders) s.join();
        var state = proc.ask(new CounterMsg.Noop()).get(2, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1000);
}
