# How-To Guides

> "Programming is not about typing. It's about thinking. The best programs are the ones where every line was earned."
> — Joe Armstrong

Task-focused solutions for common JOTP patterns. Each guide solves a specific problem — start with the guide that matches your task.

---

## Core Process Patterns

| Guide | What It Solves |
|-------|----------------|
| [Creating Your First Process](./creating-your-first-process.md) | `Proc<S,M>` basics — spawn, send, receive |
| [Create Lightweight Processes](./create-lightweight-processes.md) | Patterns for spawning and managing many processes |
| [Send and Receive Messages](./send-receive-messages.md) | `tell()` vs `ask()`, request-reply patterns |
| [Process Communication](./process-communication.md) | Multi-process coordination and message routing |

## Fault Tolerance

| Guide | What It Solves |
|-------|----------------|
| [Build Supervision Trees](./build-supervision-trees.md) | ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE strategies |
| [Supervision and Fault Tolerance](./supervision-and-fault-tolerance.md) | Full fault tolerance patterns with `Supervisor` |
| [Handling Process Crashes](./handling-process-crashes.md) | Crash detection, restart policies, crash callbacks |
| [Handle Process Failures](./handle-process-failures.md) | `Result<T,E>`, error propagation, failure recovery |
| [Error Handling](./error-handling.md) | Railway-oriented patterns, `Result` composition |

## Process Relationships

| Guide | What It Solves |
|-------|----------------|
| [Linking Processes](./linking-processes.md) | Bilateral crash propagation with `ProcLink` |
| [Monitoring Without Killing](./monitoring-without-killing.md) | Unilateral DOWN notifications with `ProcMonitor` |

## Advanced Patterns

| Guide | What It Solves |
|-------|----------------|
| [State Machine Workflow](./state-machine-workflow.md) | `StateMachine<S,E,D>` for complex multi-state workflows |
| [Concurrent Pipelines](./concurrent-pipelines.md) | Fan-out/fan-in with `Parallel`, backpressure handling |
| [Test Concurrent Code](./test-concurrent-code.md) | Awaitility, virtual thread testing, deterministic assertions |

## Migration

| Guide | What It Solves |
|-------|----------------|
| [Migrate from Erlang](./migrate-from-erlang.md) | Pattern-by-pattern Erlang/OTP → JOTP translation |

---

## Choosing the Right Guide

**"I want to spawn a process"** → [Create Lightweight Processes](./create-lightweight-processes.md)

**"My process crashed in production"** → [Handling Process Crashes](./handling-process-crashes.md)

**"I need a multi-step workflow"** → [State Machine Workflow](./state-machine-workflow.md)

**"I'm coming from Erlang/Elixir"** → [Migrate from Erlang](./migrate-from-erlang.md)

**"I need parallel fan-out"** → [Concurrent Pipelines](./concurrent-pipelines.md)

---

## How These Guides Are Organized

Each guide follows the format:
1. **Problem statement** — what situation you're in
2. **Solution** — working code that solves it
3. **Explanation** — why this approach, trade-offs, alternatives
4. **Common mistakes** — what to watch out for

Guides assume familiarity with JOTP basics from the [tutorials](../tutorials/).
For API details, see the [reference](../reference/) section.

---

**See Also:**
- [Tutorials](../tutorials/) — Learn JOTP step by step
- [Reference](../reference/) — Complete API documentation
- [Explanations](../explanations/) — Design decisions and architecture

---

**Previous:** [Tutorials](../tutorials/) | **Next:** [Explanations](../explanations/)
