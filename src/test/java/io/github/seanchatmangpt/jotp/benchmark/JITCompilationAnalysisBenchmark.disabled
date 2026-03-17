package io.github.seanchatmangpt.jotp.benchmark;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JIT Compilation Analysis Benchmark - studies JIT warmup effects.
 *
 * <p>This benchmark runs with different warmup configurations to understand:
 *
 * <ul>
 *   <li>At what iteration do results stabilize?
 *   <li>Which methods get C2 compiled?
 *   <li>How does inlining affect performance?
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class JITCompilationAnalysisBenchmark {

    private Proc<Integer, Integer> counterProc;
    private Proc<String, String> echoProc;

    @Setup(Level.Iteration)
    public void setup() {
        counterProc = new Proc<>(0, (state, msg) -> state + msg);
        echoProc = new Proc<>("", (state, msg) -> msg);
    }

    @TearDown(Level.Iteration)
    public void teardown() throws InterruptedException {
        counterProc.stop();
        echoProc.stop();
    }

    /** Simple tell() - minimal overhead, should be inlined early. */
    @Benchmark
    public void tell_simple(Blackhole bh) {
        counterProc.tell(1);
        bh.consume(counterProc);
    }

    /** Ask with immediate response - tests mailbox + virtual thread overhead. */
    @Benchmark
    public String ask_simple() throws Exception {
        return echoProc.ask("test").get();
    }

    /** Chained tells - tests inlining budget. */
    @Benchmark
    public void tell_chained(Blackhole bh) {
        counterProc.tell(1);
        counterProc.tell(1);
        counterProc.tell(1);
        counterProc.tell(1);
        counterProc.tell(1);
        bh.consume(counterProc);
    }
}
