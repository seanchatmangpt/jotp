# API Reference

Complete API documentation for JOTP's 15 core primitives.

## Processes

- **[Proc<S,M>](./proc.md)** — Core lightweight process with message mailbox
- **[ProcRef<S,M>](./procref.md)** — Opaque process handle (survives restarts)

## Supervision & Reliability

- **[Supervisor](./supervisor.md)** — Hierarchical process management
- **[ProcLink](./proclink.md)** — Bilateral process linking
- **[ProcMonitor](./procmonitor.md)** — Unilateral process monitoring
- **[ExitSignal](./exitsignal.md)** — Exception signaling record

## Concurrency

- **[Parallel](./parallel.md)** — Structured concurrency with fail-fast
- **[Result<T,E>](./result.md)** — Sealed success/failure type
- **[CrashRecovery](./crashrecovery.md)** — Retry logic with backoff

## Behaviors

- **[StateMachine<S,E,D>](./statemachine.md)** — State/event/data separation
- **[EventManager<E>](./eventmanager.md)** — Decoupled event handling
- **[ProcLib](./proclib.md)** — Startup handshake with synchronization

## Utilities

- **[ProcessRegistry](./registry.md)** — Global process name table
- **[ProcTimer](./proctimer.md)** — Timed message delivery
- **[ProcSys](./procsys.md)** — Process introspection and control

## Quick Links

- [Javadoc Index](../../src/main/java/io/github/seanchatmangpt/jotp/package-info.java) — Auto-generated API docs
- [Module Info](../../src/main/java/module-info.java) — JPMS module definition
