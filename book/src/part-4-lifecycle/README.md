# Part 4: Process Lifecycle and Supervision

This is where OTP earns its reputation. Everything up to now -- processes, messages, state machines -- is just table setting. Supervision is the main course.

The core idea is radical if you come from defensive Java programming: **design for failure, not prevention.** You will never anticipate every way a process can fail. Network timeouts, corrupt data, resource exhaustion, race conditions you never imagined. Traditional Java says wrap everything in try/catch and hope. OTP says: let the process crash, and let something smarter decide what to do next.

That something smarter is a **supervisor**. Supervisors start child processes, watch them, and restart them with fresh state when they crash. The restarted process comes back in a known-good configuration -- no corrupt state carried over from the failure. The crash is the recovery mechanism.

Supervisors organize into **trees**. A root supervisor watches region supervisors, which watch individual workers. Failures propagate upward only when a level cannot handle them. Restart intensity -- a maximum restart count within a time window -- acts as an automatic circuit breaker. If a child crashes too fast, the supervisor itself gives up and escalates.

Below the supervisor sits the wiring: **links** for shared fate (two processes that must live and die together) and **monitors** for observation (watch a process without being dragged down when it crashes). Together with `CrashRecovery.retry()` for one-shot operations, these primitives give you a self-healing system where failures are routine events, not emergencies.

The patterns in this part are numbered 17 through 23. By the end, you will stop fearing crashes and start designing around them.
