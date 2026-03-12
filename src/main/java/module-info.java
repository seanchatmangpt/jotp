/**
 * Java 26 implementation of Joe Armstrong's Erlang/OTP framework primitives.
 *
 * <p>JOTP brings battle-tested concurrency patterns, supervision trees, and fault tolerance to the
 * JVM using Java 26 virtual threads, sealed types, and pattern matching. This module provides a
 * production-ready implementation of the 15 core OTP (Open Telecom Platform) primitives that
 * power Erlang's legendary reliability and scalability.
 *
 * <h2>Core OTP Primitives</h2>
 *
 * <p>The framework provides the following 15 primitives:
 *
 * <dl>
 *   <dt><strong>Process Management</strong>
 *   <dd>
 *     <ul>
 *       <li>{@link io.github.seanchatmangpt.jotp.Proc Proc&lt;S,M&gt;} — Lightweight process:
 *           virtual-thread mailbox + pure state handler (OTP: {@code spawn/3}). Spawns a new
 *           virtual thread with an isolated mailbox and state transformation logic.
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcRef ProcRef&lt;S,M&gt;} — Stable process
 *           identifier (Pid equivalent): opaque handle that survives supervisor restarts and
 *           provides crash-safe process references.
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcessLink ProcessLink} — Bilateral crash
 *           propagation: when one linked process crashes, linked processes receive exit signals
 *           (OTP: {@code link/1}, {@code spawn_link/3}). Implements mutual fault propagation.
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcessMonitor ProcessMonitor} — Unilateral
 *           process monitoring: receive DOWN notifications on process exit without killing the
 *           monitor (OTP: {@code monitor/2}, {@code demonitor/1}). Fires on normal or abnormal
 *           exit; does NOT propagate crashes.
 *     </ul>
 *   <dt><strong>Supervision & Fault Tolerance</strong>
 *   <dd>
 *     <ul>
 *       <li>{@link io.github.seanchatmangpt.jotp.Supervisor Supervisor} — Supervision tree:
 *           implements ONE_FOR_ONE, ONE_FOR_ALL, and REST_FOR_ONE restart strategies with
 *           configurable sliding restart windows. Automatically manages child process lifecycle.
 *       <li>{@link io.github.seanchatmangpt.jotp.CrashRecovery CrashRecovery} — "Let it crash"
 *           semantics + supervised retry via isolated virtual threads. Crashes are caught by
 *           supervisors and handled according to restart policy.
 *       <li>{@link io.github.seanchatmangpt.jotp.ExitSignal ExitSignal} — Exit signal record
 *           delivered as a mailbox message when a process traps exits ({@code
 *           process_flag(trap_exit, true)}). Carries reason, from, and exit signal details.
 *     </ul>
 *   <dt><strong>Concurrency & Parallelism</strong>
 *   <dd>
 *     <ul>
 *       <li>{@link io.github.seanchatmangpt.jotp.Parallel Parallel} — Structured fan-out with
 *           fail-fast semantics using StructuredTaskScope. Maps operations across many items
 *           (OTP: {@code pmap}); cancels all on first failure.
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcTimer ProcTimer} — Timed message delivery:
 *           {@code send_after/3}, {@code send_interval/3}, {@code cancel/1}. Schedule messages
 *           to arrive in a process mailbox after a delay or repeatedly.
 *     </ul>
 *   <dt><strong>State Machines & Event Handling</strong>
 *   <dd>
 *     <ul>
 *       <li>{@link io.github.seanchatmangpt.jotp.StateMachine StateMachine&lt;S,E,D&gt;} —
 *           Generic state machine (OTP: {@code gen_statem}): separates state, event, and data
 *           with sealed {@code Transition} hierarchy. Supports all callback modes.
 *       <li>{@link io.github.seanchatmangpt.jotp.EventManager EventManager&lt;E&gt;} —
 *           Typed event manager (OTP: {@code gen_event}): dynamically add/remove handlers,
 *           notify listeners synchronously or asynchronously. Crashes handlers without killing
 *           the manager.
 *     </ul>
 *   <dt><strong>Process Registry & Introspection</strong>
 *   <dd>
 *     <ul>
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcessRegistry ProcessRegistry} — Global
 *           process name table: {@code register/2}, {@code whereis/1}, {@code unregister/1},
 *           {@code registered/0}. Auto-deregisters when a process terminates.
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcSys ProcSys} — Process introspection (OTP:
 *           {@code sys} module): {@code get_state/0}, {@code suspend/0}, {@code resume/0},
 *           {@code statistics/0}. Query and control process state without stopping it.
 *       <li>{@link io.github.seanchatmangpt.jotp.ProcLib ProcLib} — Process library startup:
 *           {@code start_link/3} blocks until child calls {@code initAck()}, returning {@code
 *           StartResult.Ok | Err}. Synchronizes parent and child initialization.
 *     </ul>
 *   <dt><strong>Error Handling & Railways</strong>
 *   <dd>
 *     <ul>
 *       <li>{@link io.github.seanchatmangpt.jotp.Result Result&lt;T,E&gt;} — Sealed
 *           railway-oriented type with {@code Success} and {@code Failure} variants. Supports
 *           {@code map}, {@code flatMap}, {@code fold}, {@code recover}, {@code peek}, {@code
 *           orElseThrow}. Wrap throwing code with {@code Result.of(supplier)}.
 *     </ul>
 * </dl>
 *
 * <h2>Key Features</h2>
 *
 * <ul>
 *   <li><strong>Virtual Thread Foundation:</strong> All processes use Java 26 virtual threads
 *       (lightweight, scalable, no thread pool limits).
 *   <li><strong>Supervision Trees:</strong> Automatic parent-child monitoring with configurable
 *       restart policies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE) and sliding window recovery.
 *   <li><strong>Fault Tolerance:</strong> "Let it crash" philosophy: crashes are caught by
 *       supervisors, not hidden. Processes recover automatically based on restart strategy.
 *   <li><strong>Railway-Oriented Programming:</strong> {@link io.github.seanchatmangpt.jotp.Result
 *       Result&lt;T,E&gt;} type eliminates {@code null} and unchecked exceptions; chain operations
 *       fluently.
 *   <li><strong>Type-Safe Messaging:</strong> Generic {@code Proc&lt;S,M&gt;} preserves message
 *       types; sealed message hierarchies catch invalid patterns at compile time.
 *   <li><strong>Java 26 Language Features:</strong> Pattern matching, sealed types, records,
 *       virtual threads, and text blocks for readable, maintainable code.
 * </ul>
 *
 * <h2>Public API vs. Internal Testing Packages</h2>
 *
 * <p>This module strictly separates public API from internal testing examples:
 *
 * <ul>
 *   <li><strong>Public API:</strong> {@code io.github.seanchatmangpt.jotp.*} and {@code
 *       io.github.seanchatmangpt.jotp.messagepatterns.*} are exported for production use.
 *   <li><strong>Internal Testing:</strong> {@code io.github.seanchatmangpt.jotp.dogfood.*}
 *       packages (template-rendered code examples) are NOT exported. They are for compilation
 *       validation only and must not be referenced by external code.
 *   <li><strong>Messaging Patterns:</strong> Enterprise message patterns from Vaughn Vernon's
 *       reactive messaging architecture are exported as public API.
 * </ul>
 *
 * <h2>Usage Example: Supervising a Process with Crash Recovery</h2>
 *
 * <pre>
 * import io.github.seanchatmangpt.jotp.*;
 *
 * // 1. Define process state and messages
 * record Counter(int value) {}
 * sealed interface CounterMsg permits Increment, Reset {}
 * record Increment() implements CounterMsg {}
 * record Reset() implements CounterMsg {}
 *
 * // 2. Create a process using Proc.spawn() factory
 * Proc&lt;Counter, CounterMsg&gt; counter =
 *     Proc.spawn(
 *         new Counter(0),
 *         (state, msg) -&gt;
 *             switch (msg) {
 *               case Increment _ -&gt; new Counter(state.value() + 1);
 *               case Reset _ -&gt; new Counter(0);
 *             });
 *
 * // 3. Create a supervisor to manage the process
 * Supervisor supervisor =
 *     Supervisor.create(
 *         "counter-supervisor",
 *         Supervisor.Strategy.ONE_FOR_ONE,
 *         5,
 *         Duration.ofSeconds(60));
 *
 * // 4. Supervise the counter process
 * ProcRef&lt;Counter, CounterMsg&gt; supervised =
 *     supervisor.supervise(
 *         "counter",
 *         new Counter(0),
 *         (state, msg) -&gt;
 *             switch (msg) {
 *               case Increment _ -&gt; new Counter(state.value() + 1);
 *               case Reset _ -&gt; new Counter(0);
 *             });
 *
 * // 5. Send messages asynchronously
 * supervised.tell(new Increment());
 * supervised.tell(new Increment());
 * supervised.tell(new Reset());
 *
 * // 6. If counter crashes, supervisor restarts it automatically
 * supervisor.shutdown();  // Graceful shutdown
 * </pre>
 *
 * <h2>References</h2>
 *
 * @see io.github.seanchatmangpt.jotp.Proc
 * @see io.github.seanchatmangpt.jotp.ProcRef
 * @see io.github.seanchatmangpt.jotp.Supervisor
 * @see io.github.seanchatmangpt.jotp.CrashRecovery
 * @see io.github.seanchatmangpt.jotp.StateMachine
 * @see io.github.seanchatmangpt.jotp.ProcessLink
 * @see io.github.seanchatmangpt.jotp.Parallel
 * @see io.github.seanchatmangpt.jotp.ProcessMonitor
 * @see io.github.seanchatmangpt.jotp.ProcessRegistry
 * @see io.github.seanchatmangpt.jotp.ProcTimer
 * @see io.github.seanchatmangpt.jotp.ExitSignal
 * @see io.github.seanchatmangpt.jotp.ProcSys
 * @see io.github.seanchatmangpt.jotp.ProcLib
 * @see io.github.seanchatmangpt.jotp.EventManager
 * @see io.github.seanchatmangpt.jotp.Result
 * @author Sean Chat Mangpt
 */
module io.github.seanchatmangpt.jotp {
    // Core API: All 15 OTP primitives and base interfaces
    exports io.github.seanchatmangpt.jotp;
    exports io.github.seanchatmangpt.jotp.reactive;

    // Reactive Messaging Patterns (Vaughn Vernon port)
    // These are public API for enterprise messaging scenarios
    exports io.github.seanchatmangpt.jotp.messagepatterns.channel;
    exports io.github.seanchatmangpt.jotp.messagepatterns.construction;
    exports io.github.seanchatmangpt.jotp.messagepatterns.routing;
    exports io.github.seanchatmangpt.jotp.messagepatterns.transformation;
    exports io.github.seanchatmangpt.jotp.messagepatterns.endpoint;
    exports io.github.seanchatmangpt.jotp.messagepatterns.management;

    // Internal: dogfood.* packages (template-rendered examples, test-only) are NOT exported
    // See CLAUDE.md "Dogfood (Eating Our Own Dog Food)" for details on template validation

    // java.management is required for:
    // - JVM monitoring (OperatingSystemMXBean, ThreadMXBean for process statistics)
    // - Process introspection: ProcSys module needs MXBean access
    // - Supervisor diagnostics: querying thread state, memory usage, thread counts
    requires java.management;
}
