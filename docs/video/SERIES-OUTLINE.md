# JOTP Video Tutorial Series - Complete Outline

**Series Goal:** Teach developers how to build fault-tolerant, concurrent systems using JOTP (Java OTP Framework) with Java 26 virtual threads and sealed types.

**Target Audience:** Java developers (intermediate to advanced) interested in:
- Building concurrent, fault-tolerant systems
- Learning the Actor model and OTP patterns
- Migrating from Spring Boot to more resilient architectures
- Understanding production-ready scalability patterns

**Prerequisites:**
- Java 26 with preview features (--enable-preview)
- Maven 4
- Basic understanding of concurrency concepts
- Familiarity with lambda expressions and functional programming

---

## Playlist 1: JOTP Fundamentals (5 videos, 10-15 min each)

### Video 1: Introduction to OTP and JOTP
**Duration:** 12 minutes
**Target Audience:** Java developers exploring fault-tolerant systems

**Learning Objectives:**
- Understand the problem JOTP solves (concurrency + fault tolerance)
- Compare JOTP with traditional Java concurrency approaches
- See the competitive landscape vs. Erlang, Akka, Go
- Get a high-level overview of the 15 OTP primitives

**Sections:**
1. **The Concurrency Problem (2 min)**
   - Traditional Java threading limitations
   - The "gorilla holding the banana" problem
   - Real-world failure scenarios

2. **Enter OTP: Let It Crash (3 min)**
   - Origins in Erlang/OTP
   - Core philosophy: failure is inevitable
   - Supervisor trees and fault containment
   - Message passing over shared state

3. **JOTP: OTP for Java (4 min)**
   - Why not just use Erlang? (ecosystem, talent pool)
   - Why not Akka? (licensing, complexity, type safety)
   - Java 26 features: virtual threads, sealed types, pattern matching
   - Production-ready performance benchmarks

4. **The 15 Primitives Overview (2 min)**
   - Proc, Supervisor, StateMachine
   - EventManager, ProcRef, ProcLink
   - Crash recovery and Result types

5. **What You'll Learn (1 min)**
   - Series roadmap preview
   - Practical examples you'll build
   - Production deployment patterns

**Visual Aids:**
- Architecture diagrams comparing threading models
- Performance benchmark charts
- Supervision tree animation
- Code comparison table (traditional vs. JOTP)

**Code Demo:** None (conceptual introduction)

**Resources:**
- Links to ARCHITECTURE.md
- Performance benchmark details
- Java 26 feature overview

---

### Video 2: Your First Process (Proc Basics)
**Duration:** 15 minutes
**Target Audience:** Developers new to the Actor model

**Learning Objectives:**
- Understand the Proc<S,M> abstraction
- Create your first supervised process
- Learn tell vs. ask messaging patterns
- Build a simple counter process

**Sections:**
1. **What is a Process? (3 min)**
   - Lightweight virtual thread (~3.9KB memory)
   - Isolated state with message mailbox
   - The BiFunction handler: (State, Message) → State
   - Process lifecycle: spawn → process messages → terminate

2. **Defining State and Messages (3 min)**
   - Using sealed interfaces for type-safe messages
   - Record classes for immutable state
   - Pattern matching with switch expressions
   - Compiler-enforced exhaustive handling

3. **Creating Your First Process (4 min)**
   - Proc.spawn() factory method
   - The handler function explained
   - Sending messages with tell()
   - Querying state with ask()

4. **Building a Counter Example (4 min)**
   - Live coding: Counter process
   - Increment, Reset, Snapshot messages
   - Running and testing the process
   - Understanding process isolation

5. **Supervision Basics (1 min)**
   - Why every process needs a supervisor
   - ONE_FOR_ONE strategy explained
   - Automatic restart on crashes

**Visual Aids:**
- Process architecture diagram (mailbox, handler, state)
- Message flow animation (tell vs. ask)
- Code execution visualization
- Sealed type pattern matching diagram

**Code Demo:**
- Complete Counter process from scratch
- Demonstrate message sending
- Show crash and restart behavior

**Quiz Questions:**
1. What's the difference between tell() and ask()?
2. Why use sealed interfaces for messages?
3. How much memory does a typical process use?

---

### Video 3: Messaging Patterns (tell/ask)
**Duration:** 14 minutes
**Target Audience:** Developers building inter-process communication

**Learning Objectives:**
- Master fire-and-forget messaging with tell()
- Understand request-reply patterns with ask()
- Learn timeout handling and backpressure
- Build multi-process workflows

**Sections:**
1. **Tell: Fire-and-Forget Messaging (3 min)**
   - When to use tell() (notifications, events)
   - Non-blocking semantics
   - Message ordering guarantees
   - Tell performance benchmarks

2. **Ask: Request-Reply Pattern (4 min)**
   - When to use ask() (queries, responses)
   - CompletableFuture return type
   - Timeout handling and backpressure
   - Ask performance benchmarks

3. **Building a Payment Processor (5 min)**
   - Live coding: Payment workflow
   - Multiple processes: Order → Payment → Audit
   - Chaining ask() calls
   - Error handling with Result types

4. **Timeout and Backpressure (2 min)**
   - Setting appropriate timeouts
   - Preventing mailbox overflow
   - Flow control in practice
   - Monitoring queue depth

**Visual Aids:**
- Message sequence diagrams
- Ask timeout animation
- Backpressure visualization
- Multi-process workflow diagram

**Code Demo:**
- Payment processor with multiple processes
- Demonstrate timeout handling
- Show backpressure in action
- Error recovery patterns

**Quiz Questions:**
1. When should you use tell() vs. ask()?
2. What happens if an ask() times out?
3. How does JOTP provide backpressure?

---

### Video 4: Fault Tolerance (Supervisor)
**Duration:** 15 minutes
**Target Audience:** Developers building production systems

**Learning Objectives:**
- Understand the "Let It Crash" philosophy
- Learn three restart strategies (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE)
- Build supervision trees for fault containment
- Configure restart limits and windows

**Sections:**
1. **The Let It Crash Philosophy (3 min)**
   - Why defensive programming fails
   - Embrace failure, design for recovery
   - Supervisor as fault containment boundary
   - Real-world failure scenarios

2. **Three Restart Strategies (4 min)**
   - ONE_FOR_ONE: Restart only failed child
   - ONE_FOR_ALL: Restart all children
   - REST_FOR_ONE: Restart failed + later children
   - When to use each strategy

3. **Building a Supervision Tree (5 min)**
   - Live coding: E-commerce supervision
   - Hierarchical supervisors
   - ChildSpec and process registration
   - Crash isolation between branches

4. **Restart Limits and Circuit Breaking (3 min)**
   - maxRestarts parameter
   - Duration window (sliding window)
   - Fail-fast when limits exceeded
   - Supervisor shutdown behavior

**Visual Aids:**
- Supervision tree diagram (animated)
- Restart strategy comparison table
- Crash propagation animation
- Circuit breaker state diagram

**Code Demo:**
- Complete supervision tree for e-commerce
- Simulate crashes and show restarts
- Demonstrate fault isolation
- Show circuit breaker in action

**Quiz Questions:**
1. What's the difference between ONE_FOR_ONE and ONE_FOR_ALL?
2. How do restart limits prevent crash loops?
3. Why use hierarchical supervision trees?

---

### Video 5: Building a Simple Application
**Duration:** 15 minutes
**Target Audience:** Developers ready to build real applications

**Learning Objectives:**
- Design a complete application architecture
- Implement multi-process coordination
- Add observability and monitoring
- Deploy and test the application

**Sections:**
1. **Application Design (3 min)**
   - Chat server architecture
   - Process-per-user pattern
   - Message broadcasting
   - State persistence strategy

2. **Implementing Core Processes (5 min)**
   - Live coding: SessionManager process
   - Live coding: UserSession processes
   - Message routing and broadcasting
   - State management with records

3. **Adding Observability (4 min)**
   - ProcSys for introspection
   - Logging and metrics
   - Monitoring process health
   - Debugging tips

4. **Testing and Deployment (3 min)**
   - Unit testing processes
   - Integration testing
   - Running the application
   - Performance considerations

**Visual Aids:**
- Application architecture diagram
- Process interaction sequence
- Monitoring dashboard mockup
- Deployment flow diagram

**Code Demo:**
- Complete chat server from scratch
- Show multi-user scenario
- Demonstrate fault tolerance
- Run load test

**Quiz Questions:**
1. How do you design process-per-user systems?
2. What observability tools does JOTP provide?
3. How do you test concurrent processes?

---

## Playlist 2: Advanced Primitives (8 videos, 15-20 min each)

### Video 6: State Machines
**Duration:** 18 minutes
**Target Audience:** Developers building complex workflows

**Learning Objectives:**
- Understand StateMachine<S,E,D> vs. Proc<S,M>
- Build complex stateful workflows
- Handle state transitions with events
- Use postponed events and timeouts

**Sections:**
1. **When to Use State Machines (3 min)**
   - Complex workflows vs. simple processes
   - StateMachine vs. Proc comparison
   - Real-world use cases (orders, workflows)
   - gen_statem equivalence

2. **Defining States and Events (4 min)**
   - Sealed state types
   - Sealed event types
   - State data (D parameter)
   - Transition function signature

3. **Building an Order State Machine (6 min)**
   - Live coding: Order workflow
   - States: Pending, Paid, Shipped, Delivered
   - Events: Pay, Ship, ConfirmDelivery
   - State transitions and actions

4. **Advanced Features (5 min)**
   - Postponing events
   - State timeouts
   - Event timeouts
   - Enter/exit callbacks

**Visual Aids:**
- State machine diagram
- State transition table
- Event flow animation
- Timeout visualization

**Code Demo:**
- Complete order state machine
- Demonstrate state transitions
- Show postponed events
- Handle timeouts

**Quiz Questions:**
1. When should you use StateMachine vs. Proc?
2. How do postponed events work?
3. What's the difference between state and event timeouts?

---

### Video 7: Event-Driven Architecture
**Duration:** 17 minutes
**Target Audience:** Developers building loosely coupled systems

**Learning Objectives:**
- Use EventManager for pub-sub messaging
- Build event-driven architectures
- Handle event handler failures
- Design event schemas

**Sections:**
1. **EventManager Overview (3 min)**
   - Pub-sub pattern explained
   - EventManager vs. direct messaging
   - Use cases for event broadcasting
   - gen_event equivalence

2. **Publishing Events (4 min)**
   - Creating typed event hierarchies
   - notify() for fire-and-forget
   - Event handler registration
   - Performance considerations

3. **Building an Event Bus (6 min)**
   - Live coding: Analytics event bus
   - Multiple subscribers
   - Handler isolation (crashes don't kill bus)
   - Event filtering and routing

4. **Production Patterns (4 min)**
   - Event versioning
   - Event schemas
   - Monitoring and observability
   - Testing event flows

**Visual Aids:**
- Pub-sub architecture diagram
- Event flow animation
- Handler isolation visualization
- Event versioning diagram

**Code Demo:**
- Complete event-driven system
- Multiple handlers for different events
- Demonstrate handler isolation
- Show event monitoring

**Quiz Questions:**
1. What's the difference between EventManager and Proc messaging?
2. Why are event handler crashes isolated?
3. How do you version events?

---

### Video 8: Distributed Systems
**Duration:** 20 minutes
**Target Audience:** Developers building distributed applications

**Learning Objectives:**
- Understand distributed challenges
- Use ProcRegistry for process discovery
- Implement distributed messaging patterns
- Handle network partitions and failures

**Sections:**
1. **Distributed Challenges (4 min)**
   - CAP theorem implications
   - Network partitions
   - Message ordering in distributed systems
   - Failure detection

2. **Process Discovery with ProcRegistry (5 min)**
   - Registering processes by name
   - Looking up processes across JVMs
   - Name collision handling
   - Global registry patterns

3. **Distributed Messaging Patterns (6 min)**
   - Live coding: Distributed cache
   - Inter-JVM messaging
   - Request-reply across nodes
   - Timeout and retry handling

4. **Failure Handling (5 min)**
   - Network partition detection
   - ProcLink for cross-node monitoring
   - Automatic retry with exponential backoff
   - Degraded modes

**Visual Aids:**
- Distributed architecture diagram
- Message flow across nodes
- Partition scenario animation
- Failure handling flowchart

**Code Demo:**
- Distributed cache implementation
- Multi-node setup
- Demonstrate partition handling
- Show automatic recovery

**Quiz Questions:**
1. How does ProcRegistry enable process discovery?
2. What challenges do network partitions introduce?
3. How do you handle cross-node failures?

---

### Video 9: Performance Tuning
**Duration:** 18 minutes
**Target Audience:** Developers optimizing production systems

**Learning Objectives:**
- Understand JOTP performance characteristics
- Optimize process memory usage
- Tune garbage collection for virtual threads
- Profile and benchmark applications

**Sections:**
1. **Performance Fundamentals (3 min)**
   - Memory per process (~3.9KB)
   - Message latency (tell vs. ask)
   - Throughput benchmarks (4.6M msg/sec)
   - Virtual thread scheduling

2. **Memory Optimization (5 min)**
   - Process state design (immutable records)
   - Message size impact on throughput
   - Mailbox overflow prevention
   - Heap sizing guidelines

3. **GC Tuning for Virtual Threads (5 min)**
   - G1GC vs. ZGC vs. Shenandoah
   - GC pause impact on latency
   - Heap sizing for 1M+ processes
   - Monitoring GC behavior

4. **Profiling and Benchmarking (5 min)**
   - JMH benchmark setup
   - ProcSys for introspection
   - JFR profiling
   - Performance testing patterns

**Visual Aids:**
- Performance benchmark charts
- Memory usage visualization
- GC pause timeline
- Profiling screenshot

**Code Demo:**
- Run JMH benchmarks
- Profile with JFR
- Tune GC settings
- Demonstrate optimization impact

**Quiz Questions:**
1. How does message size affect throughput?
2. Which GC algorithm is best for 1M processes?
3. How do you profile JOTP applications?

---

### Video 10: Production Deployment
**Duration:** 17 minutes
**Target Audience:** DevOps and platform engineers

**Learning Objectives:**
- Deploy JOTP applications to production
- Configure JVM for virtual threads
- Monitor and alert on system health
- Handle rolling deployments

**Sections:**
1. **JVM Configuration (4 min)**
   - Java 26 with --enable-preview
   - Heap sizing guidelines
   - GC selection (G1GC, ZGC)
   - JVM flags for performance

2. **Deployment Strategies (5 min)**
   - Blue-green deployments
   - Rolling updates
   - Canary releases
   - State migration strategies

3. **Monitoring and Observability (5 min)**
   - ProcSys for live introspection
   - Metrics and dashboards
   - Log aggregation
   - Distributed tracing

4. **Runbook: Common Issues (3 min)**
   - Process crash loops
   - Memory leaks
   - GC pressure
   - Network partitions

**Visual Aids:**
- Deployment architecture diagram
- Monitoring dashboard mockup
- Rolling deployment animation
- Troubleshooting flowchart

**Code Demo:**
- Docker container setup
- Kubernetes deployment
- Monitoring configuration
- Rolling deployment demo

**Quiz Questions:**
1. What JVM flags are essential for JOTP?
2. How do you handle state during rolling deployments?
3. What metrics should you monitor?

---

### Video 11: Testing Strategies
**Duration:** 16 minutes
**Target Audience:** Developers building reliable applications

**Learning Objectives:**
- Unit test processes and state machines
- Integration test multi-process systems
- Property-based testing with jqwik
- Chaos testing for fault tolerance

**Sections:**
1. **Unit Testing Processes (4 min)**
   - Testing handler functions in isolation
   - Mocking dependencies
   - Asserting state transitions
   - Testing error cases

2. **Integration Testing (5 min)**
   - Testing process communication
   - Supervisor restart testing
   - Timeout and failure scenarios
   - Test isolation with ProcRef

3. **Property-Based Testing (4 min)**
   - Introduction to jqwik
   - Generating random messages
   - Invariant testing
   - Shrinking failures

4. **Chaos Testing (3 min)**
   - Simulating process crashes
   - Network partition injection
   - Failure recovery validation
   - Stress testing

**Visual Aids:**
- Test architecture diagram
- Property-based testing flow
- Chaos testing scenario
- Test coverage report

**Code Demo:**
- Unit test examples
- Integration test setup
- jqwik property tests
- Chaos test demonstration

**Quiz Questions:**
1. How do you unit test a process handler?
2. What's property-based testing?
3. How do you test fault tolerance?

---

### Video 12: Observability
**Duration:** 15 minutes
**Target Audience:** Developers and SREs

**Learning Objectives:**
- Use ProcSys for live introspection
- Monitor process health and metrics
- Debug distributed systems
- Implement distributed tracing

**Sections:**
1. **ProcSys Introspection (4 min)**
   - getProcessCount()
   - getMailboxDepth()
   - getStateSnapshot()
   - Performance implications

2. **Metrics and Monitoring (5 min)**
   - Key metrics to track
   - Custom metrics integration
   - Dashboard design
   - Alerting strategies

3. **Logging Best Practices (3 min)**
   - Structured logging
   - Message tracing
   - Log aggregation
   - Log level tuning

4. **Distributed Tracing (3 min)**
   - Trace context propagation
   - OpenTelemetry integration
   - Span creation
   - Performance analysis

**Visual Aids:**
- Monitoring dashboard mockup
- Trace timeline visualization
- Metric charts
- Log aggregation diagram

**Code Demo:**
- ProcSys usage examples
- Metrics collection setup
- Distributed tracing demo
- Debugging session

**Quiz Questions:**
1. What information does ProcSys provide?
2. How do you implement distributed tracing?
3. What metrics should you alert on?

---

### Video 13: Migration from Actor Models
**Duration:** 16 minutes
**Target Audience:** Developers migrating from Akka or Erlang

**Learning Objectives:**
- Compare JOTP with Akka and Erlang/OTP
- Migrate Akka applications to JOTP
- Migrate Erlang/OTP to JOTP
- Choose the right migration strategy

**Sections:**
1. **JOTP vs. Akka Comparison (4 min)**
   - API simplicity (JOTP wins)
   - Type safety (JOTP wins)
   - Licensing (JOTP: Apache 2.0, Akka: BSL)
   - Feature parity table

2. **JOTP vs. Erlang/OTP (4 min)**
   - Feature equivalence mapping
   - Type safety (Java wins)
   - Ecosystem (Java wins)
   - Performance comparison

3. **Migration Strategies (5 min)**
   - Strangler fig pattern
   - Parallel architecture
   - Gradual refactoring
   - Code generation tools

4. **Live Migration Example (3 min)**
   - Before: Akka code
   - After: JOTP code
   - Side-by-side comparison
   - Testing strategy

**Visual Aids:**
- Feature comparison table
- Migration roadmap
- Code comparison screenshot
- Architecture evolution diagram

**Code Demo:**
- Migrate Akka example to JOTP
- Show code reduction
- Demonstrate type safety
- Run comparison test

**Quiz Questions:**
1. What are the key differences between JOTP and Akka?
2. How do you migrate gradually from Akka?
3. When should you choose Erlang vs. JOTP?

---

## Production Guide

### Video Production Checklist
- [ ] Script reviewed by technical experts
- [ ] Code demos tested and working
- [ ] Visual aids created (diagrams, animations)
- [ ] Recording environment prepared
- [ ] Microphone and lighting tested
- [ ] Screen recording software configured
- [ ] Backup recording planned

### Recording Guidelines
- **Format:** 1920x1080 (1080p) minimum
- **Audio:** High-quality microphone, noise-free environment
- **Screen:** Clear, readable code (18pt+ font)
- **Pacing:** Allow time for viewers to read code
- **Editing:** Remove mistakes, add zoom for important code

### Post-Production
- [ ] Add intro/outro music
- [ ] Insert visual aids at appropriate points
- [ ] Add captions/subtitles
- [ ] Create thumbnail images
- [ ] Optimize for platform (YouTube, etc.)

### Distribution Strategy
- **Primary Platform:** YouTube (unlisted playlist for early access)
- **Backup Platform:** PeerTube (decentralized alternative)
- **Accompanying Materials:**
  - Code examples in GitHub repo
  - Written transcripts
  - Quiz answers
  - Discussion forum (GitHub Discussions)

### Community Engagement
- **Q&A:** Answer questions in comments
- **Office Hours:** Live Q&A sessions
- **Contributions:** Accept community improvements
- **Updates:** Add videos for new features

---

## Success Metrics

### Viewing Metrics
- Target: 10,000 views per video within 6 months
- Engagement rate: >5% (likes, comments, shares)
- Completion rate: >40% watch full video
- Subscriber growth: >1,000 new subscribers

### Learning Outcomes
- Quiz completion rate: >60%
- Code demo attempts: >500 GitHub clones
- Community contributions: >50 PRs to examples
- Success stories: >10 production deployments

### Feedback Collection
- Post-video surveys
- GitHub issue tracking
- Community calls
- Conference talks

---

## Timeline

### Phase 1: Content Creation (Months 1-3)
- Month 1: Script writing for all 13 videos
- Month 2: Code demo development and testing
- Month 3: Visual aids creation

### Phase 2: Recording (Months 4-5)
- Month 4: Record Playlist 1 (5 videos)
- Month 5: Record Playlist 2 (8 videos)

### Phase 3: Post-Production (Month 6)
- Editing, captions, thumbnails
- Quality assurance review
- Beta testing with select users

### Phase 4: Launch (Month 7)
- Public release
- Marketing push
- Community engagement

---

## Resources

### Documentation
- [JOTP README](/README.md)
- [Architecture Guide](/docs/ARCHITECTURE.md)
- [Performance Benchmarks](/docs/JOTP-PERFORMANCE-REPORT.md)

### Code Examples
- [Example Code](/src/main/java/io/github/seanchatmangpt/jotp/examples/)
- [Test Suite](/src/test/java/io/github/seanchatmangpt/jotp/)

### Community
- [GitHub Issues](https://github.com/seanchatmangpt/jotp/issues)
- [GitHub Discussions](https://github.com/seanchatmangpt/jotp/discussions)

### Tools
- **Recording:** OBS Studio, Camtasia
- **Editing:** DaVinci Resolve, Final Cut Pro
- **Diagrams:** draw.io, PlantUML
- **Screen Recording:** macOS built-in, OBS

---

**Series Status:** In Planning
**Last Updated:** 2026-03-16
**Maintainer:** JOTP Community
