# Part 5: Advanced Workers and Application Assembly

You have the fundamentals. Processes, supervisors, crash recovery, registries. You can build isolated services that restart on failure and find each other by name. That is a solid foundation, but it is not yet an application.

Real systems have protocols with multiple phases. They broadcast events to unknown subscribers. They need timeouts, heartbeats, and scheduled work. They fan out to fifty backends and need the answer fast -- or not at all. And when something goes wrong at 3 AM, you need to look inside a running process without restarting it.

This part introduces four advanced worker patterns that handle these concerns:

- **StateMachine** models complex protocols where behavior depends on which phase you are in. A vehicle is idle, then tracking, then in maintenance. Each phase accepts different events and transitions to different next phases. The compiler enforces that you handle every combination.

- **EventManager** decouples publishers from subscribers. A fleet event fires once; alert handlers, analytics handlers, and maintenance handlers all receive it independently. If one handler crashes, the others keep running.

- **ProcTimer** schedules delayed and periodic messages. Heartbeats, timeouts, retry intervals -- all expressed as messages arriving in the process mailbox, not as callback spaghetti.

- **Parallel** fans out work across virtual threads with fail-fast semantics. Query fifty vehicles at once. If one fails, cancel the rest immediately and report the error.

After covering these primitives individually, we assemble them into a complete application: supervision trees managing worker lifecycles, event buses connecting services, named registries enabling discovery, and integration tests proving the whole thing holds together under failure.

By the end of Part 5, you will have built a running system from the ground up.
