package io.github.seanchatmangpt.jotp;

/**
 * OTP application callback module — the {@code -behaviour(application)} interface in Java.
 *
 * <p>An {@code ApplicationCallback} describes how an application is started and stopped. It is the
 * Java equivalent of an Erlang module that implements the {@code application} behaviour:
 *
 * <pre>{@code
 * % Erlang equivalent
 * -module(ch_app).
 * -behaviour(application).
 * start(_Type, _Args) -> ch_sup:start_link().
 * stop(_State)        -> ok.
 * }</pre>
 *
 * <p><strong>Java usage:</strong>
 *
 * <pre>{@code
 * // Lambda form — for applications that return a supervision tree
 * ApplicationCallback<Supervisor> chApp = (startType, args) -> {
 *     var supervisor = new Supervisor(Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));
 *     supervisor.supervise("worker", 0, handler);
 *     return supervisor;
 * };
 *
 * // Full form — with cleanup logic
 * class MyApp implements ApplicationCallback<AppState> {
 *     public AppState start(StartType type, Object args) {
 *         return new AppState(connectDb(), startHttpServer());
 *     }
 *
 *     public void stop(AppState state) {
 *         state.db().close();
 *         state.http().shutdown();
 *     }
 * }
 * }</pre>
 *
 * <p>The value returned by {@link #start} is passed as-is to {@link #stop} when the application is
 * stopped — equivalent to OTP's optional {@code State} in {@code {ok, Pid, State}}.
 *
 * @param <S> the application state type passed from {@code start} to {@code stop}
 * @see ApplicationSpec
 * @see ApplicationController
 * @see StartType
 */
@FunctionalInterface
public interface ApplicationCallback<S> {

    /**
     * Start the application.
     *
     * <p>Called by {@link ApplicationController} when the application is started. Implementations
     * should create and return the top-level supervision tree or process. The returned state is
     * stored and passed to {@link #stop} when the application terminates.
     *
     * <p>Erlang equivalent: {@code start(StartType, StartArgs) -> {ok, Pid} | {ok, Pid, State}}.
     *
     * @param startType the start type ({@link StartType.Normal}, {@link StartType.Takeover}, or
     *     {@link StartType.Failover})
     * @param startArgs the start arguments defined by {@link ApplicationSpec#startArgs()}
     * @return the application state (e.g., a {@link Supervisor}, a config object, or any value that
     *     {@link #stop} needs to clean up)
     * @throws Exception if startup fails
     */
    S start(StartType startType, Object startArgs) throws Exception;

    /**
     * Stop the application.
     *
     * <p>Called <em>after</em> the application has been stopped. This is for cleanup only; the
     * actual shutdown of processes and supervision trees is handled automatically by {@link
     * ApplicationController}.
     *
     * <p>Default implementation is a no-op, suitable for library applications that need no cleanup.
     *
     * <p>Erlang equivalent: {@code stop(State) -> ok}.
     *
     * @param state the value returned by {@link #start}
     * @throws Exception if cleanup fails
     */
    default void stop(S state) throws Exception {
        // No-op default — library applications need no cleanup
    }
}
