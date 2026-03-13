# Introduction: The Journey to JOTPOps

### The 3 AM Outage That Shouldn't Have Happened

It is 3:17 AM on a Tuesday. Your phone lights up with PagerDuty. The payment service is down — not degraded, not slow, completely down. You log in to find that a single NPE in the invoice formatting code, buried four stack frames deep in a thread that shares state with everything else, has taken out the entire JVM. The thread pool is exhausted. The supervisor is a load balancer that doesn't know what a supervisor is. The retry logic is a for-loop that assumes the state from the previous failed attempt is clean.

You restart the service. It comes back up. You write an incident report. You add a catch block. You go back to sleep.

Six weeks later, it happens again. Different exception, same architecture, same 3 AM.

The frustrating part is that this problem was solved in 1987. Ericsson engineers building AXD301 telephone exchanges knew that hardware would fail, software would crash, and humans would not be available at 3 AM to fix it. They invented Erlang and its OTP framework to encode the answer directly into the runtime: every process is isolated, every process has a supervisor, every supervisor knows how to restart its children, and failure in one process cannot corrupt another. The result was a system that achieved nine nines of availability — roughly 32 milliseconds of downtime per year — in production telephone exchanges.

That architecture is called BEAMOps, named after the Erlang virtual machine. It is not a configuration practice or a deployment strategy. It is a way of thinking about software in which fault tolerance is structural, not bolted on.

### What Elixir Engineers Figured Out

Elixir brought BEAMOps thinking to a broader audience. Elixir engineers routinely structure their applications as supervision trees before they write a single line of business logic. They ask: what are the processes in this system? What is each process's restart strategy? What is the data that a fresh instance of this process needs to start working? They reach for `GenServer` and `Supervisor` not because they are interesting primitives but because they are the answer to the 3 AM outage.

The problem with adopting Elixir wholesale is not the language — Elixir is elegant. The problem is everything around the language: the ecosystem, the tooling, the hiring market, the Spring ecosystem integrations, the JDBC drivers, the AWS SDKs, and the 20 years of Java infrastructure your organization has already built. Rewriting in Elixir is a bet-the-company migration. Most organizations cannot make that bet.

But you can bring the thinking to Java.

### The JOTPOps Manifesto

JOTPOps is a practice, not a product. It is the application of OTP-style fault-tolerance thinking to Java 26 applications, using JOTP as the primitive layer. It has four principles:

**Own your processes.** Every stateful component in your system is a named `Proc`. It has an explicit state type, an explicit message type, and an explicit handler. There are no shared mutable objects. There is no ambient global state. When you look at a process, you can see everything it knows and everything it can respond to.

**Own your supervision.** Every process lives under a supervisor. The supervisor defines what happens when the process crashes: restart it (ONE_FOR_ONE), restart everything in the group (ONE_FOR_ALL), or restart the process and everything that depends on it (REST_FOR_ONE). This is not retry logic in a catch block. It is topology.

**Own your types.** Java 26 sealed types give you exhaustive switching. `Result<S, F>` gives you railway-oriented error handling without exceptions leaking across process boundaries. `StateMachine<S, E, D>` gives you state transitions that the compiler verifies are total. If your code compiles, your state model is consistent.

**Own every stage.** Infrastructure is code. Issues are specifications. Deployments are reproducible. The guard system makes sure you never ship a TODO into production. This book walks you through each stage: design, implement, test, deploy, and operate.

### The Tech Stack

| Layer | Tool | Purpose |
|-------|------|---------|
| Language | Java 26 + preview features | Virtual threads, sealed types, pattern matching, records |
| Framework | JOTP `io.github.seanchatmangpt.jotp` | OTP primitives: Proc, Supervisor, StateMachine, Result |
| Build | mvnd 2.0.0-rc-3 (Maven Daemon) | Persistent daemon for fast incremental builds |
| Guard system | dx-guard + `./dx.sh validate` | Enforces H_TODO, H_MOCK, H_STUB rules on every edit |
| Infrastructure | Terraform + GitHub provider | Repo setup, issues, milestones as code |
| Cloud | AWS (ECS Fargate, RDS Aurora, ElastiCache) | Production deployment target |
| Testing | JUnit 5, AssertJ, jqwik, Awaitility | Unit, property-based, and integration tests |
| Formatting | Spotless (Google Java Format AOSP) | Auto-applied on every `.java` edit via PostToolUse hook |

### TaskFlow: What We're Building

Throughout this book you will build **TaskFlow**, a real-time Kanban board service. TaskFlow is not a toy. It has genuine requirements that stress every part of the stack:

- Boards have columns (Backlog, In Progress, Done, Archived).
- Cards move between columns. Each move is an event that must be persisted and broadcast to connected clients.
- Multiple users can move cards simultaneously. The system must resolve conflicts without losing data.
- If the board service crashes, it must recover in under 200 milliseconds with state consistent to the last persisted event.
- The system must support 10,000 concurrent boards without degrading latency at the p99.

These requirements are not invented to make the examples harder. They are representative of every real-time collaborative application: Jira, Trello, Linear, Notion. By the time you finish this book, you will know how to build systems like these with the confidence that comes from structural fault tolerance.

### Chapter Roadmap

**Introduction** (this section): The JOTPOps philosophy and what you are going to build.

**Chapter 1** gets your environment running — JDK 26, mvnd, dx-guard — and introduces the three foundational JOTP patterns: THE ACTOR AS PROCESS, THE MAILBOX GUARANTEE, and THE GUARD CONTRACT. You write your first `Proc<BoardState, BoardMsg>` and run the full guard validation suite.

**Chapter 2** introduces infrastructure as code using Terraform and the GitHub provider. You create the TaskFlow repository, issues, and milestones programmatically. The patterns are INFRASTRUCTURE AS EXECUTABLE SPECIFICATION and STATE AS SINGLE SOURCE OF TRUTH.

Later chapters (not covered in this sample) build out the full supervision tree, the card state machine, WebSocket broadcasting, AWS deployment with ECS Fargate, and the operational runbooks that make 3 AM survivable.

Every chapter ends with a "What Have You Learned?" section. Every code example compiles. Every guard violation is shown and fixed. By the end, you will have built something real.

Let's start with a working development environment.
