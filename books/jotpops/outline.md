# Engineering Java Applications
## Navigate Each Stage of Software Delivery with JOTP
*by [Author Name]*

---

The days of separate dev and ops teams are over — knowledge silos and the "throw it over the fence" culture they create are the enemy of progress. As a Java engineer, you need to confidently own each stage of the software delivery process. This book introduces a new paradigm, **JOTPOps**, that helps you build, test, deploy, and debug Java applications with the same fault-tolerance guarantees that have powered Erlang/OTP systems for 30 years.

Create effective development and deployment strategies; leverage continuous improvement pipelines; and ensure environment integrity. Combine operational orchestrators such as Docker Swarm with the distribution, fault tolerance, and scalability of JOTP to create robust and reliable Java applications.

---

## eBook & Print Formats

- PDF for desktop/tablets
- epub for Apple Books, e-readers
- mobi for Kindle readers

---

## What This Book Covers

JOTPOps starts by building a solid foundation for your project. On the infrastructure side, see how to use Terraform before you even start coding to automate your deployment and operations. On the project management side, use issues and milestones to simplify tracking — you'll use this foundation as you go through the book, implementing each of the steps required to deploy a scalable Java application.

Now that you have a foundation, you can start building. Create a Spring Boot + JOTP application and explore Maven releases. Make your deployments reliable with Docker. Continuously improve your codebase by implementing an efficient CI/CD pipeline with GitHub Actions. Scale the TaskFlow application and operate a distributed JOTP system in production using AWS EC2 nodes, AWS load balancers, and a remote Docker Swarm. Recognize which application metrics should be collected and monitored, and set alerts when certain thresholds are met. Ship less code more often — and ensure it works.

The pragmatic JOTPOps approach — an extension of the well-known DevOps paradigm — will help you become a multidisciplinary Java developer who is empowered to own each stage of the software delivery process. After reading this book, you'll understand how to apply JOTPOps principles in your daily work, creating reliable, scalable, and easy-to-understand applications in a cooperative team environment.

---

## Introducing the JOTPOps Paradigm

> "Do I get Erlang's reliability or Java's ecosystem?"
> **Answer: Both.**

JOTPOps is the synthesis of two proven disciplines:

- **OTP** (Open Telecom Platform) — Erlang's 30-year-old blueprint for building systems that self-heal, scale to millions of concurrent processes, and achieve five-nines uptime
- **DevOps** — The cultural and technical movement that collapses the wall between development and operations, giving engineers end-to-end ownership

JOTP brings all 15 OTP primitives to Java 26, using virtual threads, sealed types, and pattern matching. JOTPOps combines that library with the full delivery pipeline: infrastructure-as-code, containerization, CI/CD, distributed deployment, and production observability.

**Why Java 26?** Virtual threads make 10M+ concurrent actors practical. Sealed types make exhaustive message handling a compile-time guarantee. Pattern matching eliminates the ceremony that made Akka feel heavy.

---

## What You Need

- Java 26 (OpenJDK 26 — `bash .claude/setup.sh` installs it)
- mvnd 2.0.0-rc-3 (Maven Daemon — faster than `mvn`)
- Rust 1.78+ (to compile the `dx-guard` quality gate binary)
- Docker v24+, Docker Compose v2.21+
- Terraform v1.7+, Packer v1.9+
- SOPS 3.8+, Age 1.1+
- An AWS account + AWS CLI v2.15+
- A GitHub account + GitHub CLI v2.42+

---

## The Sample Application: TaskFlow

Throughout this book, we build **TaskFlow** — a real-time Kanban board. TaskFlow is deliberately not a toy: it has user authentication, persistent storage, WebSocket real-time updates, multi-tenancy, and production-grade fault tolerance. By the end of Chapter 13 it runs on a 3-node AWS Docker Swarm cluster with autoscaling, distributed JOTP actors communicating over gRPC, and Grafana dashboards alerting on supervisor restart rates.

Each chapter adds a concrete feature:

| Chapter | TaskFlow Feature Added |
|---------|----------------------|
| 1 | Project skeleton + first `Proc<BoardState, BoardMsg>` |
| 2 | GitHub issues/milestones via Terraform |
| 3 | Supervisor tree + `StateMachine` task lifecycle |
| 4 | Dockerized image with health checks |
| 5 | CI pipeline with quality gates |
| 6 | Docker Compose dev stack + real-time `EventManager` |
| 7 | AWS production environment + AMI baking |
| 8 | Continuous deployment + secrets management |
| 9 | Multi-node Docker Swarm |
| 10 | Cross-JVM gRPC actor bridges |
| 11 | Auto Scaling Group + Application Load Balancer |
| 12 | Prometheus + Grafana observability |
| 13 | Custom metrics, alerts, full production deploy |

---

## Contents & Extracts

### Foreword
### Acknowledgments

---

### Introduction: The Journey to JOTPOps

- Introducing the JOTPOps paradigm
- Why the DevOps and OTP worlds belong together
- The false choice: Erlang reliability OR Java ecosystem
- The book's tech stack at a glance
- Getting to know TaskFlow

---

### Chapter 1: Get to Know JOTP and Set Up Your Environment

**TaskFlow:** Project skeleton + first actor

The actor model is not a new idea — Erlang proved it in 1986. This chapter shows you why Java 26's virtual threads finally make it practical at JVM scale, and gets your development environment running from scratch.

- From Erlang's `GenServer` to `Proc<S,M>`: behavioral equivalence
- Why virtual threads, sealed types, and pattern matching change everything
- Install JDK 26, mvnd, and the `dx-guard` quality gate system
- Bootstrap with `bash .claude/setup.sh` and verify with `./dx.sh all`
- Maven JPMS module structure (`module-info.java`)
- Guard enforcement: H_TODO, H_MOCK, H_STUB — what they are and why they exist
- Write your first `Proc<BoardState, BoardMsg>` — fire-and-forget with `tell()`
- **What Have You Learned?**

---

### Chapter 2: Use Terraform to Create GitHub Issues and Milestones

**TaskFlow:** Infrastructure-as-code foundation

Before writing a line of TaskFlow code, we automate the project management scaffolding that will track our work. Terraform isn't just for cloud infrastructure — it's the universal language of repeatable, reviewable configuration.

- Get to know Terraform
- Create your first resource using the GitHub provider
- Examine the Terraform state
- Terraform variables *[extract]*
- Resource dependencies — modeling your TaskFlow AWS VPC *[extract]*
- **What Have You Learned?**

---

### Chapter 3: Build and Package the TaskFlow Application

**TaskFlow:** Spring Boot + JOTP supervisor tree, core domain model

TaskFlow takes shape. We integrate JOTP's supervision tree into Spring Boot, model task state as a `StateMachine`, and write the first property-based tests.

- Maven releases and the `mvnd package` command
- Spring Boot integration: wiring `Proc` and `Supervisor` as `@Bean`
- Supervisor strategies: ONE_FOR_ONE for independent board workers
- `ProcRef<S,M>` — stable handles that survive child restarts
- `StateMachine<TaskState, TaskEvent, TaskData>` — task lifecycle (Todo → InProgress → Done → Archived)
- `Result<S,F>` — railway-oriented error handling without exceptions
- Property-based testing with jqwik `@Property`/`@ForAll`
- Integration testing with Maven Failsafe (`*IT.java`)
- **What Have You Learned?**

---

### Chapter 4: Dockerize Your JOTP Application

**TaskFlow:** Production-ready Docker image

A Java application that only runs on your laptop isn't a product. This chapter containerizes TaskFlow with a multi-stage Dockerfile optimized for Java 26, adds health checks backed by JOTP's `ProcessMonitor`, and introduces JVM container tuning.

- Docker fundamentals for Java developers
- Multi-stage Dockerfiles: `mvnd` build stage + minimal JRE runtime stage
- Run your first Docker container: `docker run taskflow`
- Write multi-stage Dockerfiles *[extract]*
- Container health checks using `ProcessMonitor` DOWN notifications
- JVM flags for containers: `-XX:MaxRAMPercentage`, virtual thread settings
- **What Have You Learned?**

---

### Chapter 5: Set Up Integration Pipelines with GitHub Actions

**TaskFlow:** CI pipeline with automated quality gates

Code that only passes on your machine isn't tested. This chapter builds a GitHub Actions CI pipeline that enforces Spotless formatting, runs all tests in parallel, validates guards, and publishes Docker images.

- The mandatory CI checklist: format → test → guard → build → push
- `mvnd spotless:check`: zero-tolerance formatting enforcement
- `mvnd test` with Surefire + `mvnd verify` with Failsafe in CI
- `./dx.sh validate`: H_TODO, H_MOCK, H_STUB gates in CI
- Build a Docker image and push to GitHub Container Registry
- Amend GitHub workflow triggers: push, pull_request, schedule *[extract]*
- ArchUnit: architectural constraints as executable tests
- JMH benchmark regression detection: `ActorBenchmark`, `ParallelBenchmark`
- **What Have You Learned?**

---

### Chapter 6: The Dev Environment and Docker Compose

**TaskFlow:** Full local dev stack with persistence and real-time updates

A single-container app is not a real application. TaskFlow needs a database, a cache, and real-time WebSocket updates. Docker Compose makes the full stack reproducible for every developer on your team.

- Rebuild TaskFlow with persistence: Spring Data + PostgreSQL
- Run the multiservice application manually to understand dependencies
- Get to know Docker Compose
- Create your first Docker Compose file (TaskFlow + PostgreSQL + Redis) *[extract]*
- `EventManager<TaskEvent>` for real-time WebSocket board updates (pub-sub)
- `ProcTimer` for scheduled board digest notifications (`send_after` equivalent)
- Get to know Docker Swarm — where we're headed
- **What Have You Learned?**

---

### Chapter 7: The Production Environment and Packer

**TaskFlow:** AWS infrastructure + pre-baked machine images

Production is not Docker Compose. This chapter provisions real AWS infrastructure: VPC, EC2, RDS, and an ALB. We bake JOTP and the JDK into an Amazon Machine Image so instances boot in seconds, not minutes.

- Create your AWS production environment manually: EC2, RDS, security groups, ALB
- Import an existing infrastructure resource with Terraform *[extract]*
- Get to know Packer
- Build your first Amazon Machine Image (AMI) with JOTP pre-installed
- Putting Packer and Terraform together
- `CrashRecovery.retry()` for transient AWS API calls (exponential backoff)
- **What Have You Learned?**

---

### Chapter 8: Continuous Deployment and Repository Secrets

**TaskFlow:** Automated production deployments, zero plaintext secrets

Continuous deployment without secret management is reckless. This chapter secures TaskFlow's credentials with SOPS + Age, then automates the full path from commit to production.

- Handle sensitive data with Docker Secrets (DB passwords, JWT keys)
- Encrypt secret data with SOPS + Age key pairs
- Deploy manually to understand requirements before automating
- Turn deploy commands into a convenience script (`./dx.sh deploy`)
- Enable continuous deployment: GitHub Actions → Docker Swarm rolling update
- `ProcRegistry` for service discovery: `register("board-coordinator", ref)`
- **What Have You Learned?**

---

### Chapter 9: Scale with a Multi-Node Docker Swarm

**TaskFlow:** Multi-instance horizontal scaling

One node is a single point of failure. This chapter scales TaskFlow to a 3-node Docker Swarm cluster, introducing JOTP's `ONE_FOR_ALL` strategy for atomic service groups and `Parallel` for concurrent board computations.

- Create and distribute multiple EC2 instances via Terraform
- Create a multi-node Swarm: manager + 2 workers
- Automate initial application deployment from GitHub Actions
- Supervisor `ONE_FOR_ALL`: restart all children when one fails (atomic groups)
- `Parallel` (structured concurrency): fan-out board search across nodes
- **What Have You Learned?**

---

### Chapter 10: Distributed JOTP

**TaskFlow:** Cross-JVM actor communication via gRPC

Docker Swarm runs multiple JVMs. This chapter connects them: JOTP actors on JVM-1 send messages to actors on JVM-2 via gRPC + Protobuf. We implement the distributed supervisor pattern and the bulkhead pattern for multi-tenant isolation.

- Get to know distributed actor systems (the cross-JVM challenge)
- gRPC bridges: serialize `ProcRef` handles over Protobuf
- Manually implement a local distributed JOTP cluster
- Automate cross-node supervision trees in Docker Swarm *[extract]*
- Bulkhead pattern: per-tenant supervisor trees → 99.99% SLA isolation
- `REST_FOR_ONE` for dependency-ordered restarts across services
- **What Have You Learned?**

---

### Chapter 11: Autoscaling and Optimizing Your Deployment Strategy

**TaskFlow:** Auto Scaling Group + ALB + zero-downtime rolling deploys

Traffic spikes happen. This chapter adds AWS Auto Scaling with CloudWatch alarms, an Application Load Balancer for session affinity, and demonstrates how `ProcRef` stability enables zero-downtime rolling deployments.

- Autoscale TaskFlow with AWS Auto Scaling Group
- Add an Application Load Balancer (ALB) with sticky sessions
- Minimize downtime with automatic rollbacks on health check failure
- `ProcRef` stability: handles survive supervisor restarts across rolling deploys
- Backpressure: `ask(msg, Duration.ofMillis(500))` as natural flow control
- Circuit breaker: supervisor restart limits (3/min → fail-fast, prevent cascade)
- Clean up dangling Docker resources
- **What Have You Learned?**

---

### Chapter 12: Instrument Your Application with Logs and Metrics

**TaskFlow:** Prometheus + Grafana observability stack

You cannot improve what you cannot measure. This chapter wires Micrometer into TaskFlow's JOTP actors, ships structured logs to an aggregator, and builds the first Grafana dashboards.

- Design your instrumentation architecture (metrics vs. logs vs. traces)
- Collect structured JSON application logs with SLF4J + Logback
- Configure default monitoring metrics with Micrometer + Prometheus
- Supervisor restart rate: the leading reliability indicator for JOTP systems
- `ProcessMonitor` for liveness probes — health checks that don't kill the patient
- **What Have You Learned?**

---

### Chapter 13: Create Custom Metrics and Grafana Alerts

**TaskFlow:** Custom dashboards, PagerDuty alerts, full production deploy

The final chapter delivers the complete JOTPOps TaskFlow system to AWS production. We implement custom Micrometer metrics, build dashboards for mailbox depth and StateMachine throughput, and configure alerts that page when things go wrong.

- Implement your own Micrometer metrics: `Proc` mailbox depth, `ask()` latency p99
- `StateMachine` transition rate dashboard (task throughput KPI)
- Add Grafana alerts: supervisor restart rate > 5/min → PagerDuty webhook
- Deploy the complete JOTPOps TaskFlow image to AWS in production
- **What Have You Learned?**

---

### Wrapping Up the Journey

- Review: how JOTPOps changed the way you own software delivery
- Migration path: Spring Boot → JOTP step by step (using `/simplify`)
- Gaps and roadmap: distributed ProcRef (Q2 2026), GraalVM native (Q1 2026), hot code reload (Q3 2026)
- The 12M Java developer talent pool: hiring and onboarding JOTPOps teams
- Community, resources, and next steps

---

## Author

**[Author Name]** is a Java platform engineer and OTP enthusiast who has spent [X] years building distributed systems at [companies]. After discovering that Erlang's fault-tolerance guarantees were achievable in Java using virtual threads and sealed types, they created the JOTP library and developed the JOTPOps approach to help Java teams escape the false choice between ecosystem and reliability. They live in [location] and are on a mission to make five-nines uptime the default for Java applications.

---

## Releases

- **2026/03/12** — B1.0: First beta draft, content outline complete.

---

## Resources

- Source code: [github.com/seanchatmangpt/jotp](https://github.com/seanchatmangpt/jotp)
- JOTP library: `io.github.seanchatmangpt:jotp:1.0`
- Errata and suggestions: GitHub Issues

---

## Competitive Positioning

| Attribute | *Engineering Elixir Applications* | *Engineering Java Applications (this book)* |
|-----------|----------------------------------|---------------------------------------------|
| **Language** | Elixir / BEAM | Java 26 / JVM |
| **Fault tolerance model** | OTP native | OTP via JOTP library |
| **Target audience** | Elixir developers | 12M Java developers |
| **Spring Boot integration** | None | Native |
| **Talent pool** | 0.5M | 12M |
| **Paradigm** | BEAMOps | **JOTPOps** |
| **Sample app** | BEAMOps Kanban | **TaskFlow Kanban** |

This book does not replace "Engineering Elixir Applications" — it brings the same delivery philosophy to the Java world, where it has never existed before.
