# Introduction: Build Your Project in Layers

You know how to code in Java. Now learn to *think* in processes.

This book teaches you to design systems using the same fault-tolerance architecture that has kept telephone networks running at 99.9999999% uptime for over 30 years — Erlang/OTP — but in Java 26, with the type safety, ecosystem depth, and 12 million developers that Java brings to the table.

JOTP is not a port of Erlang. It's a *synthesis* — the 20% of OTP patterns responsible for 80% of production reliability, distilled into 15 Java primitives that compose like LEGO bricks. Each primitive is a single class. Each class does one thing. Together, they build systems that heal themselves.

## The Layered Philosophy

Every pattern in this book belongs to a layer. Layers compose upward — each one builds on the one below:

```
┌─────────────────────────────────────────┐
│  Part V: Workers & Assembly             │  StateMachine, EventManager,
│  Assemble components into applications  │  ProcTimer, Parallel
├─────────────────────────────────────────┤
│  Part IV: Lifecycle                     │  Supervisor, ProcLib,
│  Plan fault tolerance and recovery      │  ProcLink, ProcMonitor
├─────────────────────────────────────────┤
│  Part III: Process Boundaries           │  Proc, ProcRef, ProcRegistry,
│  Wrap pure logic in concurrent actors   │  ExitSignal
├─────────────────────────────────────────┤
│  Part II: Functional Core               │  BiFunction<S, M, S>,
│  Pure functions, testable logic         │  Result<S, F>
├─────────────────────────────────────────┤
│  Part I: Shape Your Data                │  Records, sealed interfaces,
│  Immutable types and message protocols  │  pattern matching
└─────────────────────────────────────────┘
```

**Start at the bottom.** Shape your data with records and sealed types. Build pure functions that transform state. Wrap those functions in processes. Supervise those processes into fault-tolerant trees. Assemble trees into applications.

Each layer has one job: protect the layer below it from the complexity above.

## The Running Example: FleetPulse

Throughout this book, we build **FleetPulse** — a real-time IoT fleet management platform. FleetPulse monitors 100,000+ vehicles, processing GPS telemetry, engine diagnostics, fuel levels, and maintenance schedules. Each vehicle is a supervised process with its own state, message queue, and lifecycle.

FleetPulse is the right example because it exercises every pattern:
- **Data layer**: Telemetry messages (GPS, engine, fuel) as sealed record hierarchies
- **Functional core**: Pure state handlers that update vehicle position, evaluate alerts
- **Process boundaries**: One Proc per vehicle, tell() for telemetry, ask() for queries
- **Lifecycle**: Supervision trees per region, restart strategies, crash recovery
- **Workers**: State machines for vehicle lifecycle, event broadcasting for alerts, timers for heartbeats

By the end, you'll have a complete mental model for building systems that scale to millions of processes and recover from failures without human intervention.

## A Pattern Language

This book is organized as a *pattern language* — 30 named patterns, each describing a recurring design problem and its solution. Each pattern follows the same structure:

- **Context**: When you encounter this situation
- **Problem**: The tension you face
- **Forces**: Competing concerns that make it hard
- **Therefore**: The solution, with code
- **Resulting Context**: What you gain, and what new patterns to apply next

Patterns reference each other. *Immutable Messages* leads to *Sealed Message Protocols*. *Pure State Handlers* leads to *Process as Boundary*. *Process as Boundary* leads to *Supervision Trees*. Read them in order the first time. Then use them as a reference — jump to the pattern you need.

## What You Need

- Java 26 with `--enable-preview` (virtual threads, sealed types, pattern matching, structured concurrency)
- JOTP library (`io.github.seanchatmangpt:jotp`)
- mvnd (Maven Daemon) for fast builds
- Curiosity about building systems that never stop

Let's begin.
