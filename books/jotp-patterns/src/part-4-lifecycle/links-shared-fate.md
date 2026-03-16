# Pattern 21: Links for Shared Fate

## Context

In FleetPulse, each vehicle has a telemetry decoder process that parses raw binary data and a WebSocket handler process that pushes updates to the dashboard. These two processes are meaningless without each other. If the decoder crashes, the WebSocket handler has no data source. If the WebSocket handler crashes, the decoder is doing work nobody consumes. They should live and die together.

## Problem

Without explicit coupling, a crash in one process leaves its partner running in a degraded state. The WebSocket handler keeps an open connection to the browser, sending stale data because the decoder is dead. The decoder keeps parsing telemetry, building up a mailbox that nobody reads. You need a way to say: "these two processes share a fate."

## Solution

`ProcLink.link()` creates a bilateral crash connection between two processes. If either process crashes (terminates abnormally), the other receives an exit signal and is terminated. Normal shutdown via `stop()` does not propagate -- only crashes.

```java
var decoder = new Proc<>(DecoderState.empty(), (state, msg) ->
    switch (msg) {
        case RawTelemetry(var bytes) -> {
            var parsed = TelemetryParser.decode(bytes);
            wsHandler.tell(new DisplayUpdate(parsed));
            yield state.withLatest(parsed);
        }
    }
);

var wsHandler = new Proc<>(WsState.empty(), (state, msg) ->
    switch (msg) {
        case DisplayUpdate(var data) -> {
            state.session().sendText(data.toJson());
            yield state.withLastSent(data);
        }
    }
);

// Link them: if either crashes, the other is terminated.
ProcLink.link(decoder, wsHandler);
```

Now when the decoder encounters a corrupt binary frame and throws, the wsHandler is automatically interrupted. Both processes are dead. The supervisor that manages this pair can restart them together (using `ONE_FOR_ALL` strategy), giving both fresh state.

For the common case where you are creating a new process specifically to link with an existing one, `ProcLink.spawnLink()` does both atomically:

```java
// Atomically spawn a new decoder and link it to the existing wsHandler.
// No window between creation and link installation -- identical to
// Erlang's spawn_link/3 guarantee.
Proc<DecoderState, RawTelemetry> decoder = ProcLink.spawnLink(
    wsHandler,                    // parent to link with
    DecoderState.empty(),         // child's initial state
    decoderHandler                // child's message handler
);
```

The atomicity matters. If you create the decoder first and then call `link()` in a separate step, there is a window where the decoder could crash before the link is installed. `spawnLink()` eliminates that window -- the link is in place before the child processes its first message.

Here is how links compose with supervision in the FleetPulse tree:

```
region-west (ONE_FOR_ONE)
 ├── vehicle-42-supervisor (ONE_FOR_ALL)
 │    ├── decoder-42  ←──link──→  ws-handler-42
 │    └── (both restart together via ONE_FOR_ALL)
 ├── vehicle-43-supervisor (ONE_FOR_ALL)
 │    ├── decoder-43  ←──link──→  ws-handler-43
 │    └── ...
```

The link ensures immediate crash propagation. The `ONE_FOR_ALL` supervisor ensures both are restarted together with fresh state. The link and the supervisor are complementary: the link provides fast notification, the supervisor provides restart.

## Forces

- Links are bilateral. Both processes are affected. If you want one-way crash notification without killing the observer, use a monitor instead (Pattern 22).
- Links do not propagate normal `stop()`. Calling `proc.stop()` is a graceful shutdown and does not trigger the link. Only unhandled exceptions trigger crash propagation.
- `spawnLink()` is preferred over `link()` when creating a new process. It is atomic -- no race condition between spawn and link.
- Links compose with supervision. A supervised child can also be linked to a peer. The supervisor's crash callback and the link's crash callback fire independently.

## Therefore

When two processes are meaningless without each other, link them with `ProcLink.link()` or create one with `ProcLink.spawnLink()`. A crash in either process immediately terminates the other. Combine links with a `ONE_FOR_ALL` supervisor to restart both with fresh state. Use links for shared fate; use monitors for observation.
