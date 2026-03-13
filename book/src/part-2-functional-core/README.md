# Part 2: The Functional Core

Every JOTP process runs a handler: `BiFunction<S, M, S>`. State in, message in, new state out. That is the entire contract. No annotations, no dependency injection, no framework lifecycle. Just a function.

This is the functional core. It is where 90% of your business logic lives, and it never touches I/O, never spawns threads, never reads from a database. It receives a state and a message, and it returns the next state. Pure computation.

Why does this matter? Because pure functions are trivially testable. You call `handler.apply(state, msg)` and assert on the result. No Spring context to boot. No mocks to wire. No containers to start. Your tests run in milliseconds, not seconds.

The imperative shell -- the `Proc`, the `Supervisor`, the mailbox -- lives outside this core. It handles concurrency, crash recovery, and message routing. But the logic that decides what happens when a vehicle reports its GPS coordinates, or when a maintenance request needs validation? That is a pure function. You can run it at a whiteboard.

The patterns in this part show you how to write these handlers well:

- **Pure State Handlers** -- the basic shape of a `BiFunction<S, M, S>`
- **Compose by Purpose** -- keeping large handlers readable by delegating to focused methods
- **Railway Composition** -- chaining fallible operations with `Result<S, F>` instead of try/catch
- **Test Without a Framework** -- why pure handlers need only AssertJ and jqwik, not `@SpringBootTest`
- **Skinny Left Margin** -- keeping functional code flat and readable

Master these five patterns and you will write handlers that are easy to test, easy to read, and easy to trust in production.
