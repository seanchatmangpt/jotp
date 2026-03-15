## 2. Literature Review

This chapter surveys the theoretical foundations and prior art that inform this thesis. We examine (1) the actor model's mathematical formalization, (2) Erlang/OTP's historical development and design principles, (3) the JVM's evolution toward virtual threads and structured concurrency, (4) competitive concurrency technologies, and (5) the gaps that previous Java actor implementations failed to address. The 48 references compiled in `/docs/phd-thesis-bibliography.md` provide the foundation for this review.

### 2.1 Actor Model Theory

The actor model originated in Hewitt, Bishop, and Steiger's 1973 paper [2], which defined actors as self-contained concurrent primitives that communicate exclusively through asynchronous message passing. Unlike shared-memory concurrency, actors enforce isolation by design: each actor maintains private state and processes messages sequentially, eliminating entire classes of race conditions. Hewitt's work was motivated by artificial intelligence systems requiring massive parallelism, but the abstraction proved universally applicable to distributed computing.

Agha's 1986 monograph *ACTORS: A Model of Concurrent Computation in Distributed Systems* [11] provided the first rigorous mathematical formalization of actor semantics. Agha defined the actor as a computational entity with three fundamental operations: (1) *send* — asynchronous message passing to another actor, (2) *create* — spawning new actors, and (3) *become* — replacing the actor's behavior with new computation. This algebraic treatment enabled formal reasoning about actor system properties, including deadlock freedom, liveness, and behavioral equivalence — capabilities previously limited to process calculi like CSP and π-calculus [38].

Agha, Mason, Smith, and Talcott later extended this work in their 1997 *Journal of Functional Programming* paper [12], establishing a foundation for actor computation as a formal system. This work introduced *actor bisimulation* — a proof technique for demonstrating that two actor systems exhibit equivalent behavior under all possible message orderings. The formalism supports compositional reasoning: if components A and A' are equivalent, and B and B' are equivalent, then the composite systems A⊕B and A'⊕B' are also equivalent. This compositional property is critical for supervising actor trees: we can prove a child process correct in isolation, then prove the supervision strategy correct, and the entire tree inherits correctness by composition.

**Actor implementations compared.** Four production actor implementations merit comparison:

1. **Erlang/OTP** (1986–present). As detailed in §2.2, Erlang implements actors as *processes* with preemptive scheduling, per-process heaps, and message copying. The implementation prioritizes fault tolerance over raw throughput [1].

2. **Akka** (2009–present). Haller and Odersky's Scala Actors [13] evolved into Akka, a JVM-native actor framework. Akka's early design used hybrid thread-based and event-based execution: actors were backed by a fork-join pool, with mailbox processing modeled as event handlers. While innovative for 2009, Akka lacked Erlang's supervision tree primitives until 2013, and even then, supervision remained a library pattern rather than a language-integrated feature. The key distinction from JOTP is that Akka requires explicit `ActorRef` lifetime management and provides no compiler enforcement of message protocol exhaustiveness.

3. **Pony** (2014–present). Pony incorporates *reference capabilities* — a type system proving that actors cannot share mutable state (alias control enforced at compile time) [13]. Pony's innovation is preventing data races statically: the compiler rejects any program where an actor receives a mutable reference to data owned by another actor. While theoretically elegant, Pony's type system is complex, and the language lacks Java's ecosystem reach. JOTP achieves similar isolation via sealed types and records (message protocols) but with a simpler type system compatible with Java's mainstream adoption.

4. **Orc** (2006–present). Orc is a concurrent orchestration language treating actors as *combinators* rather than explicit processes. The language provides `|>` (parallel composition), `x <- f()` (binding), and `f <|> g` (timeout). Orc is elegant for research but lacks production tooling. JOTP's `Parallel` primitive (§3.7) incorporates Orc-style combinators into Java via `StructuredTaskScope`.

**Synthesis.** The actor model provides the theoretical foundation for OTP's process abstraction. However, theory alone is insufficient: Erlang's success derives from the integration of actors with supervision trees, "let it crash" philosophy, and runtime support. JOTP's contribution is to demonstrate that Java 26's language features — virtual threads, sealed types, pattern matching, and structured concurrency — are sufficient to express the full actor model *including* these integrated capabilities, without requiring a specialized runtime.

### 2.2 Erlang/OTP Foundations

Joe Armstrong's 2003 Ph.D. thesis, *Making reliable distributed systems in the presence of software errors* [1], established the formal principles underlying OTP. Armstrong's key insight was that *software errors are inevitable* and should be embraced rather than prevented through defensive programming. He formalized the "let it crash" philosophy: processes should fail fast when encountering unexpected state, and a supervisor process should restart them with clean initial state. This approach eliminates error handling code paths (which are rarely tested) and replaces them with restart strategies (which can be exhaustively tested and proven correct).

Armstrong defined three supervision strategies, which remain canonical in OTP 28:

1. **one_for_one** — If a child process crashes, restart only that child. Use case: stateless workers where failures are independent.

2. **one_for_all** — If a child crashes, terminate and restart all children. Use case: tightly coupled processes where a crash in one indicates corrupted state in others.

3. **rest_for_one** — If a child crashes, terminate it and all children started *after* it, then restart them in order. Use case: pipelines where earlier processes' state must be consistent with later processes'.

These strategies are formalized as *restart specifications* — mappings from process identifiers to (max restarts, time window) tuples. If a child restarts more than N times within T seconds, the supervisor itself terminates, propagating failure up the tree. This *escalation* property prevents restart storms (infinite crash-restart cycles).

**Historical context: AXD 301.** Armstrong's 2007 HOPL III paper [3] documents the engineering context that led to OTP. The AXD 301 ATM switch, developed at Ericsson in the mid-1990s, targeted 99.9999999% availability (nine nines) — equivalent to 32ms downtime per year. Traditional approaches (hardware redundancy, defensive programming, exception handling) failed to achieve this. Armstrong's team discovered that *process isolation* + *supervision trees* + *hot code loading* delivered the required reliability. The AXD 301 ran continuously for years without downtime, with software upgrades deployed via hot code loading (OTP's `sys:change_code/3` primitive, not covered in this thesis).

Svensson and Sagonas's 2006 formal semantics paper [4] provided the operational semantics for Core Erlang, establishing the mathematical properties of process communication. Their work defines a *reduction relation* for message sending: if process P sends message M to process Q, and Q's mailbox is empty, the transition is (P, Q, []) → (P, Q, [M]). If Q's mailbox contains messages [M₁, ..., Mₙ], the transition appends M to the end: (P, Q, [M₁, ..., Mₙ]) → (P, Q, [M₁, ..., Mₙ, M]). This formalism guarantees *causality preservation* — messages are processed in FIFO order per sender, though interleaving across senders is nondeterministic.

Fredlund's 2001 thesis [5] introduced McErlang, a model checker for verifying Erlang programs. McErlang can exhaustively explore all possible message interleavings and process failure combinations, proving that a supervision tree satisfies liveness properties (e.g., "after any crash, the system returns to a known state within 5 seconds"). JOTP's formal equivalence proof (Chapter 3) adopts McErlang's verification approach: we define a transition system for Java virtual threads with mailboxes, then prove bisimulation with the Erlang semantics defined by Svensson and Sagonas.

**OTP design principles.** Three principles distinguish OTP from other actor systems:

1. **Processes, not actors.** Armstrong deliberately chose the term "process" (not "actor") to emphasize isolation semantics. Each Erlang process has a private heap, a mailbox queue, and a reduction counter for preemptive scheduling. The process abstraction maps closely to OS processes, but at language-level granularity.

2. **Behaviors, not libraries.** OTP's `gen_server`, `gen_statem`, and `supervisor` are *behaviors* — formally specified protocols implemented by the runtime. Application code provides callback functions (`init/1`, `handle_call/3`, `handle_cast/2`), and the runtime orchestrates the lifecycle. This inversion of control enables the runtime to guarantee invariants (e.g., a supervisor always restarts children after a crash).

3. **Let it crash.** Rather than catching exceptions and attempting recovery, Erlang code allows processes to terminate. Supervisors observe termination signals (`{'EXIT', Pid, Reason}`) and decide whether to restart. This eliminates complex error handling logic in favor of declarative restart strategies.

JOTP directly incorporates all three principles: (1) `Proc<S,M>` is a *process* with a private mailbox and pure state handler, (2) `Supervisor`, `StateMachine<S,E,D>`, and `Proc<S,M>` are *behaviors* with callback-based APIs, and (3) `CrashRecovery` and `Result<T,E>` implement "let it crash" via functional error handling rather than exceptions.

### 2.3 JVM Concurrency Evolution

Java's concurrency model has evolved through four distinct paradigms:

**Phase 1: Platform threads (Java 1.0, 1996).** The original `java.lang.Thread` class mapped 1:1 to OS threads (kernel-level threads on Unix, Windows threads on Windows). Creating a thread incurred ~1MB stack allocation and OS scheduling overhead. This model was acceptable for server applications with 10–100 concurrent connections but failed to scale to the millions of concurrent operations required by modern cloud-native services.

**Phase 2: Executors and thread pools (Java 5, 2004).** JSR 166 introduced `ExecutorService`, `Future`, and the fork-join framework. Executors reused threads from a pool, amortizing thread creation overhead. However, thread pools required manual tuning (pool size, queue depth, rejection policies), and blocking operations (I/O, locks) could exhaust the pool, causing thread starvation. The *golden rule of thread pools* — "threads should be CPU-bound, not I/O-bound" — was widely violated in production, leading to cascading failures under load.

**Phase 3: CompletableFutures and async pipelines (Java 8, 2014).** `CompletableFuture<T>` introduced a composable async API: `future.thenApply(fn).thenCompose(another).exceptionally(recover)`. This enabled callback-based async programming but at the cost of *callback hell* — nested chains of transformations obscuring control flow. Async code became difficult to debug (stack traces spanned multiple threads) and difficult to reason about (no local reasoning about execution order). Additionally, `CompletableFuture` did not eliminate blocking: a `future.join()` call still blocked a thread.

**Phase 4: Virtual threads and structured concurrency (Java 21–26, 2023–2026).** Project Loom, led by Brian Goetz and the OpenJDK team, introduced virtual threads in Java 21 (JEP 444) [7] and structured concurrency in Java 26 (JEP 453) [8]. Virtual threads are `java.lang.Thread` instances scheduled by the JVM rather than the OS. They cost ~1KB initial allocation (vs. 1MB for platform threads) and can be created in millions without resource exhaustion. Structured concurrency, via `StructuredTaskScope`, enforces *parent-child task lifecycles* — a parent scope cannot complete until all child tasks terminate or are cancelled, preventing orphaned concurrent operations.

**Virtual threads under the hood.** Goetz's JEP 444 specifies that virtual threads are implemented on *continuations* — captured stack frames that can be suspended and resumed. When a virtual thread calls `BlockingQueue.take()` (or any blocking operation), the JVM parks the continuation, freeing the carrier thread (a platform thread backing many virtual threads). When the queue becomes non-empty, the JVM schedules the continuation on a carrier thread and resumes execution. This *M:N scheduling* (M virtual threads mapped to N carrier threads) was pioneered by Erlang BEAM and Go goroutines, but Java's implementation is unique in using continuations rather than green threads (Java 1.0's "green threads" were an earlier failed attempt at user-mode threading, removed in Java 1.3).

Pressler's Project Loom design documents [9] articulate the trade-offs between virtual and platform threads: virtual threads excel at I/O-bound workloads (waiting for network, disk, or external services) but offer no advantage for CPU-bound workloads (since all virtual threads compete for the same CPU cores). For OTP-style systems — which are overwhelmingly I/O-bound (messaging, coordination, external service calls) — virtual threads are architecturally aligned.

**Structured concurrency formalism.** Sústrik's 2016 essay [10] popularized the term "structured concurrency," drawing analogy to structured programming (Dijkstra, 1968). The core principle: *concurrent operations should be lexically scoped*, with cleanup (cancellation, resource release) handled automatically when the scope exits. Kwon and Tardieu's 2018 ASE paper [10] formalized this principle for distributed systems, proving that structured concurrency eliminates *orphan tasks* (concurrent operations that outlive their parent) and *resource leaks* (file handles, sockets held after scope exit).

JOTP's `Parallel` primitive (§3.7) uses Java 26's `StructuredTaskScope` to implement structured concurrency. The API guarantees that all child processes complete (successfully or exceptionally) before the parent scope returns, mirroring Erlang's `spawn_link` semantics where linked processes terminate together.

**Why previous Java actor systems failed.** Akka (2009), Vert.x (2011), and Quasar (2014) all attempted to bring actors to the JVM. Each failed to achieve mainstream adoption for three reasons:

1. **Library-only approach.** Actors were implemented as a library, with no language integration. Message protocols were untyped (Akka's `Object`-based messages) or required complex serialization (Vert.x's `JSON`/`MessagePack`). Supervision was a pattern, not a primitive.

2. **Thread pool tuning.** Actors were backed by `ForkJoinPool` or custom `ExecutorService` instances, requiring manual tuning of pool sizes, queue capacities, and rejection policies. Production outages resulted from misconfigured thread pools.

3. **Debugging complexity.** Callback-heavy actor code produced stack traces spanning 20+ layers of `ExecutorService.submit`, `Future.get`, and reflection proxies. Diagnosing failures in production was exponentially harder than debugging synchronous code.

JOTP avoids all three pitfalls: (1) Java 26's sealed types provide compile-time message protocol safety, (2) virtual threads eliminate thread pool tuning entirely, and (3) structured concurrency preserves causal stack traces (a virtual thread's stack is continuous, not fragmented across callbacks).

### 2.4 Competitive Technologies

**Go: Goroutines and channels.** Go introduced goroutines in 2009 [16] — lightweight, multiplexed green threads scheduled by the Go runtime. Like Erlang, Go targets massive concurrency (millions of goroutines) with low overhead (~2KB stack per goroutine). Channels — typed, buffered or unbounded queues — enable communication between goroutines. However, Go lacks supervision trees: goroutines that crash (panic) terminate the entire process unless recovered via `defer/recover`. This is a *defensive* approach compared to Erlang's *declarative* supervision: Go developers must manually insert panic recovery in every goroutine, whereas Erlang supervisors automatically restart crashed children. JOTP provides supervision trees similar to Erlang, with Java's type safety and ecosystem advantages.

**Rust: Ownership as supervision.** Rust's ownership system — compile-time enforced rules preventing mutable aliasing — provides static isolation guarantees [18]. A Rust `async` task cannot share mutable state with another task without passing ownership through an MPSC channel or `Arc<Mutex<T>>`. This eliminates data races at compile time, achieving what Erlang achieves at runtime (per-process heaps) through static analysis. However, Rust lacks supervision trees as a first-class primitive: Tokio (Rust's async runtime) provides `tokio::spawn` and `join!`, but supervision must be implemented manually. JOTP's contribution is to combine Rust's *static* safety (via sealed types) with Erlang's *dynamic* supervision strategies, delivered on the JVM.

**Akka: Location transparency and clustering.** Akka's innovation is *location transparency* — actors can run locally or remotely without code changes. Akka Cluster uses a gossip protocol to maintain membership state and consistent hashing to route messages to remote nodes. This enables distributed actor systems spanning multiple JVMs. However, Akka's focus on distribution comes at the cost of complexity: configuring Akka Cluster requires understanding split-brain resolution, downing strategies, and failure detectors. JOTP does not address distributed messaging in this thesis (future work, §8). Our focus is *local* fault tolerance — supervision trees, crash recovery, and "let it crash" — which provides 80% of OTP's value for the vast majority of applications.

**Synthesis.** No competitive technology combines (1) compile-time type safety, (2) runtime supervision trees, (3) "let it crash" philosophy, and (4) mainstream language ecosystem. Go has lightweight threads but no supervision. Rust has compile-time safety but no supervision primitives. Akka has supervision but requires runtime type casting and manual thread pool tuning. JOTP is the first system to integrate all four properties.

### 2.5 Gap Analysis

The literature survey reveals a critical gap: **no prior work has demonstrated formal equivalence between Erlang/OTP and Java 26**. Previous actor implementations on the JVM (Akka, Vert.x, Quasar) were library-only solutions lacking language integration. Java 26's new features (virtual threads, sealed types, pattern matching, structured concurrency) enable a *language-integrated* OTP implementation for the first time.

Three specific gaps this thesis addresses:

1. **Formal equivalence proof.** No published work defines a bijection between Erlang processes and Java virtual threads, or between OTP supervision strategies and Java 26 structured concurrency primitives. Chapter 3 provides this proof, using Agha's actor bisimulation framework [12].

2. **Sealed message protocols.** Erlang's dynamically typed messages are a common source of runtime errors. JOTP introduces sealed message hierarchies with compile-time exhaustiveness checking, preventing an entire class of bugs (unhandled message types) that Erlang cannot detect statically.

3. **Migration toolchain.** No prior work provides an automated migration path from legacy Java or from alternative languages (Elixir, Go, Rust) to an OTP-style Java 26 codebase. Chapters 5 and 6 introduce the `jgen` toolchain, enabling one-command codebase analysis and transformation.

### 2.6 Chapter Summary

This chapter reviewed the theoretical foundations (actor model, process calculi), prior art (Erlang/OTP, Akka, Go, Rust), and enabling technologies (Project Loom, structured concurrency). The key takeaway: **Java 26 is the first JVM release with sufficient language features to express OTP primitives idiomatically**. Virtual threads provide lightweight processes, sealed types provide type-safe message protocols, pattern matching enables exhaustiveness checking, and structured concurrency enforces supervision lifecycle invariants.

Chapter 3 builds on this foundation by providing formal equivalence proofs for ten OTP primitives in Java 26.
