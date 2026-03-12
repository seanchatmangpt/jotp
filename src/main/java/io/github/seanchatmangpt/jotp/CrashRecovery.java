package io.github.seanchatmangpt.jotp;

import java.util.function.Supplier;

/**
 * Joe Armstrong "let it crash" philosophy applied to Java.
 *
 * <p>"The key to building reliable systems is to design for failure, not to try to prevent it. If
 * you can crash and recover, you're reliable." — Joe Armstrong
 *
 * <p>Each attempt runs in an isolated virtual thread (no shared state between attempts), mirroring
 * Erlang's lightweight process model: crash the process, supervisor retries with a fresh one.
 */
public final class CrashRecovery {

    private CrashRecovery() {}

    /**
     * Attempt {@code supplier} up to {@code maxAttempts} times, each in an isolated virtual thread.
     *
     * <p>Returns the first {@code Success}, or a {@code Failure} containing the last exception if
     * all attempts are exhausted.
     *
     * <p>Armstrong principle: "Processes share nothing" — each virtual thread is a fresh "process"
     * with no state carried over from a previous crash. Use this for resilient single-task
     * execution; use {@link Supervisor} for persistent process management.
     *
     * <p><b>Usage Example:</b>
     *
     * <pre>{@code
     * var result = CrashRecovery.retry(3, () -> {
     *     var response = http.get("https://api.example.com/data");
     *     if (response.status() >= 500) {
     *         throw new RuntimeException("Server error");
     *     }
     *     return response.body();
     * });
     *
     * switch (result) {
     *     case Result.Success(var body) -> System.out.println("Got: " + body);
     *     case Result.Failure(var ex) -> System.err.println("Failed after 3 attempts: " + ex);
     * }
     * }</pre>
     *
     * @param <T> result type
     * @param maxAttempts maximum number of attempts (must be >= 1)
     * @param supplier work to attempt (runs in a fresh virtual thread each time)
     * @return {@code Success} with the first successful result, or {@code Failure} with the last
     *     exception if all attempts fail
     * @throws NullPointerException if {@code supplier} is null
     * @throws IllegalArgumentException if {@code maxAttempts < 1}
     * @see Supervisor for persistent process supervision and automatic restart
     * @see Result for handling success/failure
     */
    public static <T> Result<T, Exception> retry(int maxAttempts, Supplier<T> supplier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Result<T, Exception> last = Result.failure(new IllegalStateException("no attempts made"));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            last = runInVirtualThread(supplier);
            if (last.isSuccess()) {
                return last;
            }
        }
        return last;
    }

    private static <T> Result<T, Exception> runInVirtualThread(Supplier<T> supplier) {
        var ref =
                new Object() {
                    Result<T, Exception> result;
                };
        var thread = Thread.ofVirtual().start(() -> ref.result = Result.of(supplier::get));
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(e);
        }
        return ref.result;
    }
}
