/**
 * JOTP: Production-ready Java 26 framework implementing Erlang/OTP concurrency primitives.
 *
 * <h2>Module Overview</h2>
 *
 * <p>JOTP brings battle-tested enterprise concurrency patterns from Erlang/OTP to the JVM using
 * Java 26 features: virtual threads, sealed types, pattern matching, and records.
 *
 * <p>This module provides 15 foundational OTP primitives:
 *
 * <ul>
 *   <li>{@code Proc} - Lightweight process with virtual thread mailbox and pure state handler
 *   <li>{@code ProcRef} - Stable process identifier (opaque Pid) surviving supervisor restarts
 *   <li>{@code Supervisor} - Fault tolerance tree with ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE
 *       strategies
 *   <li>{@code CrashRecovery} - "Let it crash" with supervised retry logic
 *   <li>{@code StateMachine} - gen_statem: state/event/data separation with sealed transitions
 *   <li>{@code ProcLink} - Bilateral crash propagation between processes
 *   <li>{@code Parallel} - Structured fan-out with fail-fast semantics (OTP: pmap)
 *   <li>{@code ProcMonitor} - Unilateral DOWN notifications without killing the observer
 *   <li>{@code ProcRegistry} - Global name table: register/whereis/unregister/registered
 *   <li>{@code ProcTimer} - Timed message delivery: send_after, send_interval, cancel
 *   <li>{@code ExitSignal} - Exit signal messages when trapping exits
 *   <li>{@code ProcSys} - Process introspection: get_state, suspend, resume, statistics
 *   <li>{@code ProcLib} - proc_lib startup handshake: start_link with blocking initialization
 *   <li>{@code EventManager} - gen_event: typed event manager with isolated handler crashes
 *   <li>{@code Result} - Railway-oriented programming: sealed Success/Failure with map/fold
 * </ul>
 *
 * <h2>Export Groups</h2>
 *
 * <h3>Core OTP Primitives (STABLE)</h3>
 *
 * <p>Package: {@code io.github.seanchatmangpt.jotp}
 *
 * <p>15 production-ready OTP primitives. These exports are STABLE and follow semantic versioning.
 *
 * <h3>Reactive Foundation (STABLE)</h3>
 *
 * <p>Package: {@code io.github.seanchatmangpt.jotp.reactive}
 *
 * <p>Reactive stream adapters and Publisher bindings. API and behavior STABLE.
 *
 * <h3>Dogfood Examples (EXPERIMENTAL)</h3>
 *
 * <p>Packages: {@code io.github.seanchatmangpt.jotp.dogfood.*}
 *
 * <p>Real Java code generated from templates (core, concurrency, patterns, API, error-handling,
 * security, innovation, mclaren, messaging, reactive). These demonstrate template-generated
 * patterns and are primarily for internal validation. No stability guarantees.
 *
 * <h3>Message Patterns (STABLE)</h3>
 *
 * <p>Packages: {@code io.github.seanchatmangpt.jotp.messagepatterns.*}
 *
 * <p>Vaughn Vernon's Reactive Messaging Patterns ported to Java 26 OTP primitives:
 *
 * <ul>
 *   <li>channel - Message channels and pipes
 *   <li>construction - Constructing messages
 *   <li>routing - Message routing strategies
 *   <li>transformation - Message transformations
 *   <li>endpoint - Message endpoints
 *   <li>management - Channel and endpoint management
 * </ul>
 *
 * <h2>Requirements</h2>
 *
 * <ul>
 *   <li><strong>Java 26+</strong> - Virtual threads, sealed types, pattern matching, records
 *       required
 *   <li><strong>Preview Features Enabled</strong> - Compile/run with {@code --enable-preview}
 *   <li><strong>java.management module</strong> - Required for ThreadMXBean introspection
 * </ul>
 *
 * <h2>Comparison to Erlang/OTP</h2>
 *
 * <p>JOTP provides formal equivalence with Erlang/OTP:
 *
 * <table border="1">
 *   <tr><th>OTP Concept</th><th>JOTP Class</th><th>Notes</th></tr>
 *   <tr><td>spawn/3</td><td>{@code Proc}</td><td>Virtual thread mailbox + state handler</td></tr>
 *   <tr><td>gen_server</td><td>{@code Proc}</td><td>Virtual thread mailbox + pure state handler</td></tr>
 *   <tr><td>gen_statem</td><td>{@code StateMachine}</td><td>Sealed state/event/data transitions</td></tr>
 *   <tr><td>supervisor</td><td>{@code Supervisor}</td><td>Sliding window restart strategies</td></tr>
 *   <tr><td>gen_event</td><td>{@code EventManager}</td><td>Typed event bus with isolated handlers</td></tr>
 *   <tr><td>timer:send_after</td><td>{@code ProcTimer}</td><td>Timed message delivery</td></tr>
 *   <tr><td>process_flag(trap_exit)</td><td>{@code ExitSignal}</td><td>Exit trapping as mailbox messages</td></tr>
 *   <tr><td>sys:get_state</td><td>{@code ProcSys}</td><td>Live state introspection</td></tr>
 * </table>
 *
 * @see <a href="https://erlang.org/doc/man/otp_introduction.html">Erlang/OTP Introduction</a>
 * @see io.github.seanchatmangpt.jotp.Proc
 * @see io.github.seanchatmangpt.jotp.Supervisor
 * @see io.github.seanchatmangpt.jotp.StateMachine
 * @see io.github.seanchatmangpt.jotp.Result
 */
module io.github.seanchatmangpt.jotp {
    requires java.management;

    // Local exports
    exports io.github.seanchatmangpt.jotp;
    exports io.github.seanchatmangpt.jotp.reactive;
    exports io.github.seanchatmangpt.jotp.dogfood.core;
    exports io.github.seanchatmangpt.jotp.dogfood.concurrency;
    exports io.github.seanchatmangpt.jotp.dogfood.patterns;
    exports io.github.seanchatmangpt.jotp.dogfood.api;
    exports io.github.seanchatmangpt.jotp.dogfood.errorhandling;
    exports io.github.seanchatmangpt.jotp.dogfood.otp;
    exports io.github.seanchatmangpt.jotp.dogfood.security;
    exports io.github.seanchatmangpt.jotp.dogfood.innovation;
    exports io.github.seanchatmangpt.jotp.dogfood.reactive;

    // Enterprise Patterns
    exports io.github.seanchatmangpt.jotp.enterprise.health;
    exports io.github.seanchatmangpt.jotp.enterprise.backpressure;
    exports io.github.seanchatmangpt.jotp.enterprise.recovery;
    exports io.github.seanchatmangpt.jotp.enterprise.bulkhead;
    exports io.github.seanchatmangpt.jotp.enterprise.multitenancy;
    exports io.github.seanchatmangpt.jotp.enterprise.eventbus;
    exports io.github.seanchatmangpt.jotp.enterprise.saga;

    // Reactive Messaging Patterns (Vaughn Vernon port)
    exports io.github.seanchatmangpt.jotp.messagepatterns.channel;
    exports io.github.seanchatmangpt.jotp.messagepatterns.construction;
    exports io.github.seanchatmangpt.jotp.messagepatterns.routing;
    exports io.github.seanchatmangpt.jotp.messagepatterns.transformation;
    exports io.github.seanchatmangpt.jotp.messagepatterns.endpoint;
    exports io.github.seanchatmangpt.jotp.messagepatterns.management;
}
