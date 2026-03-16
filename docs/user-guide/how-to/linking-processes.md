# Linking Processes

## Overview

In JOTP, **links** create a bilateral crash relationship between two processes: if one crashes, the other is terminated with an exit signal.

Links are fundamental to building resilient systems in Erlang/OTP. Unlike supervised restart (which is unilateral), links propagate crashes in both directions, ensuring dependent processes don't continue running with a dead peer.

## The Link Concept

**Link:** A bidirectional relationship that says *"if I crash, crash my peer; if my peer crashes, crash me."*

- **Bilateral:** If A links to B, and B crashes, then A crashes too (and vice versa).
- **Transitive:** If A links to B, and B links to C, and C crashes, then B crashes, then A crashes.
- **Asymmetric to Supervisor:** Unlike supervisor restart, crashed processes are NOT automatically restarted (links are not restarts — that's the supervisor's job).

## Basic Usage: spawn_link

```java
import io.github.seanchatmangpt.jotp.*;

sealed interface Message {
    record Work(String task) implements Message {}
    record Stop() implements Message {}
}

// Parent process
var parent = Proc.spawn(
    new ParentState(),
    (state, msg) -> switch (msg) {
        case Message.Work(var task) -> {
            System.out.println("Parent got work: " + task);
            yield state;
        }
        case ExitSignal exit -> {
            System.out.println("Child exited: " + exit.from() + " reason: " + exit.reason());
            // Parent crashed because child crashed
            yield state;
        }
        default -> state;
    }
);

// Spawn a child linked to parent
var child = Proc.spawnLink(
    "child-state",
    (state, msg) -> switch (msg) {
        case Message.Work(var task) -> {
            System.out.println("Child working on: " + task);
            if (task.equals("fail")) {
                throw new RuntimeException("Simulated failure!");
            }
            yield state;
        }
        default -> state;
    },
    parent  // linked to parent
);

// Send work to child
child.tell(new Message.Work("normal task"));   // succeeds
child.tell(new Message.Work("fail"));           // crashes child

// Parent also crashed (received ExitSignal)
Thread.sleep(100);
System.out.println("Parent thread alive: " + parent.thread().isAlive());  // false
```

## Trapping Exits

By default, an exit signal crashes the process. To handle exit signals without crashing, **trap exits**:

```java
sealed interface Msg {
    record Work(String task) implements Msg {}
}

var parent = Proc.spawn(
    new ParentState(),
    (state, msg) -> switch (msg) {
        case Msg.Work(var task) -> {
            System.out.println("Processing: " + task);
            yield state;
        }
        case ExitSignal exit -> {
            // Trap exit: don't crash, just log it
            System.out.println("Child " + exit.from() + " died: " + exit.reason());
            yield state.withDeadChild(exit.from());
        }
        default -> state;
    }
);

// Enable exit trapping for this process
parent.trapExits(true);

// Spawn child linked to parent
var child = Proc.spawnLink("child-state", (state, msg) -> {
    if (msg.equals("crash")) throw new RuntimeException("oops");
    return state;
}, parent);

// Kill child
child.tell("crash");

// Parent traps the exit signal, logs it, and continues running
Thread.sleep(100);
System.out.println("Parent alive: " + parent.thread().isAlive());  // true
```

## Manual Linking with link() and unlink()

Processes can be linked after creation:

```java
var proc1 = Proc.spawn(...);
var proc2 = Proc.spawn(...);

// Link them now
ProcLink.link(proc1, proc2);

// If proc1 crashes, proc2 crashes
// If proc2 crashes, proc1 crashes

// Unlink to break the relationship
ProcLink.unlink(proc1, proc2);

// Now they're independent again
```

## Common Pattern: Supervisor with Exit Trapping

The most reliable pattern: a supervisor that traps exits and handles child restarts:

```java
sealed interface SupervisorMsg {
    record Start(String name, Supplier<?> factory) implements SupervisorMsg {}
    record GetChild(String name) implements SupervisorMsg {}
}

var mySupervisor = Proc.spawn(
    new SupervisorState(Map.of()),
    (state, msg) -> switch (msg) {
        case SupervisorMsg.Start(var name, var factory) -> {
            var child = Proc.spawnLink(
                factory.get(),
                (childState, childMsg) -> switch (childMsg) {
                    // child logic...
                    default -> childState;
                },
                ???  // Need parent reference here
            );
            yield state.withChild(name, child);
        }
        case ExitSignal exit -> {
            // A child crashed
            System.out.println("Child crashed: " + exit.from() + ", restarting...");
            // Restart logic here
            yield state;
        }
        default -> state;
    }
);

mySupervisor.trapExits(true);
```

**Note:** For production use, prefer the built-in `Supervisor` class, which handles this pattern correctly.

## When to Use Links vs. Supervisor

| Scenario | Use |
|----------|-----|
| Dependent services that should crash together | **Link** with exit trapping |
| Services that should be restarted independently | **Supervisor** with ONE_FOR_ONE |
| Services with ordered startup dependencies | **Supervisor** with REST_FOR_ONE |
| Peer-to-peer process networks | **Link** (symmetric crash propagation) |

## Exit Signal Details

An `ExitSignal` received when trapping exits contains:

```java
record ExitSignal(
    ProcRef from,      // Which process died
    Throwable reason   // Why it died (exception or normal exit)
) implements Message {}
```

Example reasons:
- `RuntimeException("oops")` — process threw an uncaught exception
- `InterruptedException` — process was interrupted
- `ProcessKilledException` — process was stopped via `stop()`

## Pattern: Linking in a Pipeline

```java
// Stage 1: parse -> Stage 2: validate -> Stage 3: transform
var stage1 = Proc.spawn(...);  // parser
var stage2 = Proc.spawnLink(stage1, ...);  // validator linked to parser
var stage3 = Proc.spawnLink(stage2, ...);  // transformer linked to validator

// If stage3 crashes:
// - stage2 receives ExitSignal(stage3, reason)
// - stage2 crashes (or handles it)
// - stage1 receives ExitSignal(stage2, reason)
// - entire pipeline shuts down
```

## Troubleshooting

**Q: I link processes but one crash doesn't kill the other.**
A: Make sure both processes are running and the link is bidirectional. Use:
```java
// Verify link exists
System.out.println("Proc1 alive: " + proc1.thread().isAlive());
System.out.println("Proc2 alive: " + proc2.thread().isAlive());
```

**Q: A process receives an ExitSignal but I didn't call trapExits(true).**
A: This shouldn't happen. ExitSignals are only delivered if trapping is enabled. Check your code for explicit `trapExits(true)` calls.

**Q: I want selective crash propagation (A crashes → restart B, but B crashes → don't crash A).**
A: Use `Supervisor` instead of links. Links are symmetric by design.

## Next Steps

- **[Handling Process Crashes](handling-process-crashes.md)** — Supervisor-based restart strategies
- **[Monitoring Without Killing](monitoring-without-killing.md)** — ProcMonitor (unilateral observation)
- **API Reference** — `ProcLink`, `ExitSignal`, `Proc.trapExits()`
