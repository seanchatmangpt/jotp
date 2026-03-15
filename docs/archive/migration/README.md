# JOTP Migration Guides

Comprehensive guides for developers migrating to JOTP from other frameworks and languages.

## Overview

JOTP (Java 26 implementation of OTP primitives) brings Erlang/OTP's battle-tested concurrency patterns to the JVM. These guides help developers from various backgrounds transition to JOTP's message-passing, fault-tolerant architecture.

**Key Concepts:**
- **Message Passing:** Async communication via immutable messages
- **Supervision Trees:** Hierarchical fault tolerance with automatic restart
- **Virtual Threads:** Lightweight concurrency (~1 KB per process)
- **Let It Crash:** Fail-fast with supervisor recovery
- **Type Safety:** Sealed interfaces and pattern matching

---

## Available Guides

### 1. [From Erlang/OTP](./from-erlang-otp.md)
**Target Audience:** Erlang/OTP developers

**Coverage:**
- Process spawning and messaging
- gen_server, gen_statem, gen_event patterns
- Supervisor strategies and restart policies
- Pattern matching translation
- Process registry and monitoring

**Key Differences:**
- Dynamic vs static typing
- BEAM processes vs virtual threads
- Hot code loading alternatives
- Configuration differences

**Estimated Migration Time:** 8-10 weeks

---

### 2. [From Akka](./from-akka.md)
**Target Audience:** Akka/Scala developers

**Coverage:**
- Actor model to Proc<S,M> migration
- Supervisor strategies
- State machine (FSM) translation
- Configuration (HOCON) removal
- Testing patterns

**Key Differences:**
- No ActorSystem required
- Functional vs object-oriented style
- No dispatcher configuration
- Virtual threads vs custom dispatchers

**Estimated Migration Time:** 8-10 weeks

---

### 3. [From Spring Framework](./from-spring.md)
**Target Audience:** Spring Boot developers

**Coverage:**
- Service bean migration
- Dependency injection alternatives
- @Async to message passing
- @Transactional to saga pattern
- Event handling patterns

**Key Differences:**
- Message-driven vs method calls
- Supervision vs exception handling
- No container management
- Manual dependency wiring

**Estimated Migration Time:** 10-12 weeks

---

### 4. [From Quarkus](./from-quarkus.md)
**Target Audience:** Quarkus/Mutiny developers

**Coverage:**
- Reactive patterns migration
- Uni<T> and Multi<T> translation
- Fault tolerance patterns
- Reactive messaging conversion
- Scheduled tasks

**Key Differences:**
- Message passing vs reactive streams
- Virtual threads vs event loops
- Supervision vs reactive error handling
- No Mutiny types

**Estimated Migration Time:** 9-11 weeks

---

### 5. [From Vert.x](./from-vertx.md)
**Target Audience:** Vert.x developers

**Coverage:**
- Verticle to process migration
- Event bus patterns
- Async composition
- Worker verticle handling
- Scheduled operations

**Key Differences:**
- Direct messaging vs event bus
- No deployment model
- Virtual threads vs event loop
- CompletableFuture vs Future<T>

**Estimated Migration Time:** 8-10 weeks

---

## Quick Reference

### Common Patterns Across Frameworks

#### Fire-and-Forget Messaging
```java
// All frameworks use this pattern
proc.tell(new Message(data));
```

#### Request-Reply
```java
// All frameworks use this pattern
var future = proc.ask(new Request(data));
var response = future.get(timeout, unit);
```

#### Error Handling
```java
// All frameworks: Let supervisor handle crashes
case ProcessMsg _ -> {
    try {
        return riskyOperation();
    } catch (Exception e) {
        throw e;  // Supervisor will restart
    }
}
```

---

## Migration Strategy

### Phase 1: Assessment (Week 1)
- [ ] Review existing codebase
- [ ] Identify critical components
- [ ] Assess team expertise
- [ ] Choose migration guide
- [ ] Estimate effort

### Phase 2: Learning (Week 2-3)
- [ ] Study selected migration guide
- [ ] Build proof-of-concept
- [ ] Practice message passing
- [ ] Learn supervision patterns
- [ ] Understand virtual threads

### Phase 3: Pilot (Week 4-6)
- [ ] Select non-critical service
- [ ] Implement JOTP version
- [ ] Test thoroughly
- [ ] Measure performance
- [ ] Document learnings

### Phase 4: Migration (Week 7+)
- [ ] Migrate core services
- [ ] Update testing infrastructure
- [ ] Modify deployment pipelines
- [ ] Train team members
- [ ] Establish best practices

### Phase 5: Optimization (Ongoing)
- [ ] Performance tuning
- [ ] Memory optimization
- [ ] Supervision tree refinement
- [ ] Error handling improvements
- [ ] Documentation updates

---

## Common Challenges

### 1. Mindset Shift
**Challenge:** Moving from synchronous to asynchronous thinking.

**Solution:** Start with simple fire-and-forget patterns, gradually add request-reply and complex workflows.

### 2. State Management
**Challenge:** Managing state without shared mutable objects.

**Solution:** Use immutable records and explicit state transitions in handler functions.

### 3. Error Handling
**Challenge:** Giving up try-catch for supervision.

**Solution:** Trust supervisors to handle crashes; design state to be restartable.

### 4. Testing
**Challenge:** Testing concurrent, message-passing systems.

**Solution:** Use CompletableFuture-based assertions; test supervision trees with crash scenarios.

### 5. Performance
**Challenge:** Understanding virtual thread behavior.

**Solution:** Monitor with ProcSys; profile message throughput; avoid blocking in virtual threads.

---

## Decision Matrix

### When to Choose JOTP

**Choose JOTP if:**
- Need fault tolerance with supervision
- Building concurrent systems
- Want type-safe message passing
- Value "Let It Crash" philosophy
- Need high concurrency (millions of processes)

**Consider alternatives if:**
- Simple CRUD applications (Spring MVC)
- Event-driven I/O focus (Vert.x)
- Reactive streams focus (Quarkus)
- Existing Akka investment

---

## Resources

### Official Documentation
- [Architecture Overview](../explanations/architecture-overview.md)
- [OTP Equivalence](../explanations/otp-equivalence.md)
- [Let It Crash Philosophy](../explanations/let-it-crash-philosophy.md)

### How-To Guides
- [Build Supervision Trees](../how-to/build-supervision-trees.md)
- [Create Lightweight Processes](../how-to/create-lightweight-processes.md)
- [State Machine Workflows](../how-to/state-machine-workflow.md)

### Examples
- [Basic Process Example](../examples/basic-process-example.md)
- [Supervision Tree Example](../examples/supervision-tree-example.md)
- [Spring Boot Integration](../examples/spring-boot-integration.md)

### External Resources
- [Erlang/OTP Documentation](https://www.erlang.org/doc/)
- [Java 26 Virtual Threads Guide](https://openjdk.org/jeps/444)
- [Project Loom](https://openjdk.org/projects/loom/)

---

## Support

### Getting Help
- **GitHub Issues:** Report bugs and request features
- **Discussions:** Ask questions and share knowledge
- **Documentation:** PRs welcome for improvements

### Contributing
We welcome contributions! Areas of interest:
- Additional migration guides
- Example applications
- Performance optimizations
- Documentation improvements

---

## Quick Start

```java
// 1. Define message protocol
sealed interface Msg permits Hello, Goodbye {}
record Hello(String name) implements Msg {}
record Goodbye() implements Msg {}

// 2. Define state
record State(int greetingCount) {}

// 3. Spawn process
var proc = Proc.spawn(
    new State(0),
    (state, msg) -> switch (msg) {
        case Hello(var name) -> {
            System.out.println("Hello, " + name + "!");
            yield new State(state.greetingCount() + 1);
        }
        case Goodbye _ -> {
            System.out.println("Goodbye!");
            yield state;
        }
    }
);

// 4. Send messages
proc.tell(new Hello("World"));
proc.tell(new Goodbye());
```

---

**Next Steps:** Choose your migration guide above and start your journey to JOTP!
