package io.github.seanchatmangpt.jotp;

import java.util.List;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Supplier;

/**
 * Parallel fan-out with fail-fast semantics, inspired by Erlang's supervisor philosophy.
 *
 * <p>Joe Armstrong: "If any process fails, fail fast. Don't mask errors — let a supervisor handle
 * them." Mirrors {@code erlang:pmap/2}: parallel map with all-or-nothing semantics.
 *
 * <p>Uses Java 26 {@link StructuredTaskScope#open(StructuredTaskScope.Joiner)}: all tasks run
 * concurrently on virtual threads, and on the <em>first</em> failure the scope cancels all
 * remaining tasks immediately (Armstrong: crash one, crash all). Returns {@code Result<List<T>,
 * Exception>} integrating with the existing railway-oriented {@link Result} type.
 */
public final class Parallel {

    private Parallel() {}

    /**
     * Run all {@code tasks} concurrently on virtual threads with fail-fast semantics.
     *
     * <p>Returns {@code Success(results)} if every task succeeds (in fork order), or {@code
     * Failure(firstException)} if any task fails — immediately cancelling the remaining tasks.
     *
     * <p>Armstrong: "If any process fails, fail fast. Don't mask errors — let a supervisor handle
     * them." This implements Erlang's {@code pmap/2} pattern with structured concurrency.
     *
     * <p><b>Usage Example:</b>
     *
     * <pre>{@code
     * var tasks = List.of(
     *     () -> service.fetchUser(1),
     *     () -> service.fetchUser(2),
     *     () -> service.fetchUser(3)
     * );
     *
     * var result = Parallel.all(tasks);
     *
     * switch (result) {
     *     case Result.Success(var users) ->
     *         users.forEach(u -> System.out.println("Got: " + u));
     *     case Result.Failure(var ex) ->
     *         System.err.println("Fetch failed: " + ex);
     * }
     * }</pre>
     *
     * @param <T> result type of each task
     * @param tasks list of suppliers to run in parallel (each runs on a virtual thread)
     * @return {@code Success(List)} with results in fork order, or {@code Failure(Exception)} with
     *     the first failure
     * @throws NullPointerException if {@code tasks} or any task supplier is null
     * @see Result for handling success/failure
     * @see CrashRecovery#retry(int, Supplier) for resilient single-task execution
     */
    public static <T> Result<List<T>, Exception> all(List<Supplier<T>> tasks) {
        try (var scope =
                StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.<T>awaitAllSuccessfulOrThrow())) {
            var subtasks =
                    tasks.stream()
                            .<StructuredTaskScope.Subtask<T>>map(t -> scope.fork(t::get))
                            .toList();
            scope.join();
            return Result.success(subtasks.stream().map(StructuredTaskScope.Subtask::get).toList());
        } catch (Exception e) {
            return Result.failure(e);
        }
    }
}
