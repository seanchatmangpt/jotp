# Part 1: Shape Your Data

Before you write a single process, before you build a supervisor tree, before you think about fault tolerance at all -- you shape your data.

This is where most concurrency bugs are born. Not in threading code, not in lock ordering, but in the casual decision to make a field mutable, to leave a type hierarchy open, to let an exception fly instead of returning a value. JOTP eliminates these bugs at the design level, not the debugging level.

In this part, you will build the data model for **FleetPulse**, a vehicle telemetry system that tracks thousands of trucks in real time. GPS coordinates stream in. Engine sensors report. Fuel levels change. Dispatchers issue commands. Every one of these interactions is a message, and every message must be safe to pass between virtual threads without coordination.

Five patterns get you there:

1. **Immutable Messages** -- Records as the unit of communication. No defensive copies, no synchronization, no surprises.
2. **Sealed Message Protocols** -- Closed type hierarchies that the compiler can verify exhaustively. Add a variant, and every handler that forgot to handle it fails to compile.
3. **State as Value** -- Process state represented as an immutable record. Old state in, new state out. Crash recovery becomes trivial when state is just a value.
4. **Result Railway** -- Explicit success-or-failure pipelines that replace exceptions with composable values. Chain operations without nested try-catch.
5. **Domain Types Over Primitives** -- Tiny records that make illegal states unrepresentable. A `VehicleId` is not a `String`. A `Position` is not three loose doubles.

By the end of this part, you will have a complete, type-safe data model. No processes yet. No concurrency yet. Just data that is correct by construction -- the foundation everything else builds on.
