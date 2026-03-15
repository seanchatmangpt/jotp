# Part 3: Process Boundaries

Everything up to this point has been pure logic. Functions that take values and return values. No threads, no locks, no queues. That changes here.

The process boundary is where pure logic meets concurrency. It is the single most important architectural decision you will make in a JOTP system, because it determines what runs sequentially (safe, simple, testable) and what runs concurrently (fast, isolated, resilient).

The rule is deceptively simple: **one Proc per entity**. One vehicle, one process. One user session, one process. One order, one process. The Proc wraps your pure handler function in a virtual thread with its own mailbox, giving you sequential processing of messages without locks and concurrent execution across entities without thread pools.

Communication between processes follows two patterns. `tell()` is fire-and-forget -- the caller drops a message into the mailbox and moves on. `ask()` is request-reply -- the caller gets a `CompletableFuture` that completes when the message is processed. Default to `tell()`. Use `ask()` only when you need the answer.

`ProcRef` gives you location transparency. Clients hold a stable reference that survives supervisor restarts. `ProcRegistry` gives you named lookup so processes can find each other without passing references through every constructor.

Finally, `trapExits()` lets a process catch crash signals from linked processes instead of dying with them. This is how coordinators supervise workers without being dragged down by their failures.

Six patterns. One boundary. Everything else follows from getting this right.
