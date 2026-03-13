# Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP

---

## Introduction: The Journey to JOTPOps

### The 3 AM Outage That Shouldn't Have Happened

It is 3:17 AM on a Tuesday. Your phone lights up with PagerDuty. The payment service is down — not degraded, not slow, completely down. You log in to find that a single NPE in the invoice formatting code, buried four stack frames deep in a thread that shares state with everything else, has taken out the entire JVM. The thread pool is exhausted. The supervisor is a load balancer that doesn't know what a supervisor is. The retry logic is a for-loop that assumes the state from the previous failed attempt is clean.

You restart the service. It comes back up. You write an incident report. You add a catch block. You go back to sleep.

Six weeks later, it happens again. Different exception, same architecture, same 3 AM.

The frustrating part is that this problem was solved in 1987. Ericsson engineers building AXD301 telephone exchanges knew that hardware would fail, software would crash, and humans would not be available at 3 AM to fix it. They invented Erlang and its OTP framework to encode the answer directly into the runtime: every process is isolated, every process has a supervisor, every supervisor knows how to restart its children, and failure in one process cannot corrupt another. The result was a system that achieved nine nines of availability — roughly 32 milliseconds of downtime per year — in production telephone exchanges.

That architecture is called BEAMOps, named after the Erlang virtual machine. It is not a configuration practice or a deployment strategy. It is a way of thinking about software in which fault tolerance is structural, not bolted on.

### What Elixir Engineers Figured Out

Elixir brought BEAMOps thinking to a broader audience. Elixir engineers routinely structure their applications as supervision trees before they write a single line of business logic. They ask: what are the processes in this system? What is each process's restart strategy? What is the data that a fresh instance of this process needs to start working? They reach for `GenServer` and `Supervisor` not because they are interesting primitives but because they are the answer to the 3 AM outage.

The problem with adopting Elixir wholesale is not the language — Elixir is elegant. The problem is everything around the language: the ecosystem, the tooling, the hiring market, the Spring ecosystem integrations, the JDBC drivers, the AWS SDKs, and the 20 years of Java infrastructure your organization has already built. Rewriting in Elixir is a bet-the-company migration. Most organizations cannot make that bet.

But you can bring the thinking to Java.

### The JOTPOps Manifesto

JOTPOps is a practice, not a product. It is the application of OTP-style fault-tolerance thinking to Java 26 applications, using JOTP as the primitive layer. It has four principles:

**Own your processes.** Every stateful component in your system is a named `Proc`. It has an explicit state type, an explicit message type, and an explicit handler. There are no shared mutable objects. There is no ambient global state. When you look at a process, you can see everything it knows and everything it can respond to.

**Own your supervision.** Every process lives under a supervisor. The supervisor defines what happens when the process crashes: restart it (ONE_FOR_ONE), restart everything in the group (ONE_FOR_ALL), or restart the process and everything that depends on it (REST_FOR_ONE). This is not retry logic in a catch block. It is topology.

**Own your types.** Java 26 sealed types give you exhaustive switching. `Result<S, F>` gives you railway-oriented error handling without exceptions leaking across process boundaries. `StateMachine<S, E, D>` gives you state transitions that the compiler verifies are total. If your code compiles, your state model is consistent.

**Own every stage.** Infrastructure is code. Issues are specifications. Deployments are reproducible. The guard system makes sure you never ship a TODO into production. This book walks you through each stage: design, implement, test, deploy, and operate.

### The Tech Stack

| Layer | Tool | Purpose |
|-------|------|---------|
| Language | Java 26 + preview features | Virtual threads, sealed types, pattern matching, records |
| Framework | JOTP `io.github.seanchatmangpt.jotp` | OTP primitives: Proc, Supervisor, StateMachine, Result |
| Build | mvnd 2.0.0-rc-3 (Maven Daemon) | Persistent daemon for fast incremental builds |
| Guard system | dx-guard + `./dx.sh validate` | Enforces H_TODO, H_MOCK, H_STUB rules on every edit |
| Infrastructure | Terraform + GitHub provider | Repo setup, issues, milestones as code |
| Cloud | AWS (ECS Fargate, RDS Aurora, ElastiCache) | Production deployment target |
| Testing | JUnit 5, AssertJ, jqwik, Awaitility | Unit, property-based, and integration tests |
| Formatting | Spotless (Google Java Format AOSP) | Auto-applied on every `.java` edit via PostToolUse hook |

### TaskFlow: What We're Building

Throughout this book you will build **TaskFlow**, a real-time Kanban board service. TaskFlow is not a toy. It has genuine requirements that stress every part of the stack:

- Boards have columns (Backlog, In Progress, Done, Archived).
- Cards move between columns. Each move is an event that must be persisted and broadcast to connected clients.
- Multiple users can move cards simultaneously. The system must resolve conflicts without losing data.
- If the board service crashes, it must recover in under 200 milliseconds with state consistent to the last persisted event.
- The system must support 10,000 concurrent boards without degrading latency at the p99.

These requirements are not invented to make the examples harder. They are representative of every real-time collaborative application: Jira, Trello, Linear, Notion. By the time you finish this book, you will know how to build systems like these with the confidence that comes from structural fault tolerance.

### Chapter Roadmap

**Introduction** (this section): The JOTPOps philosophy and what you are going to build.

**Chapter 1** gets your environment running — JDK 26, mvnd, dx-guard — and introduces the three foundational JOTP patterns: THE ACTOR AS PROCESS, THE MAILBOX GUARANTEE, and THE GUARD CONTRACT. You write your first `Proc<BoardState, BoardMsg>` and run the full guard validation suite.

**Chapter 2** introduces infrastructure as code using Terraform and the GitHub provider. You create the TaskFlow repository, issues, and milestones programmatically. The patterns are INFRASTRUCTURE AS EXECUTABLE SPECIFICATION and STATE AS SINGLE SOURCE OF TRUTH.

Later chapters (not covered in this sample) build out the full supervision tree, the card state machine, WebSocket broadcasting, AWS deployment with ECS Fargate, and the operational runbooks that make 3 AM survivable.

Every chapter ends with a "What Have You Learned?" section. Every code example compiles. Every guard violation is shown and fixed. By the end, you will have built something real.

Let's start with a working development environment.

---

## Chapter 1: Get to Know JOTP and Set Up Your Environment

You cannot think clearly about fault tolerance in the abstract. You need to write a process, crash it, watch it restart, and understand why that is different from wrapping a method call in a try-catch. This chapter gets your environment running and introduces the three patterns that underpin everything JOTP does.

By the end of this chapter, you will have a running `Proc<BoardState, BoardMsg>` that accepts messages, updates state, and survives the guard system's scrutiny. You will understand what a process is, why its mailbox matters, and why the guard contract exists.

---

### **Pattern: THE ACTOR AS PROCESS**

**Problem**

You need a component that maintains state across multiple requests, handles those requests one at a time without data races, and can be restarted cleanly without corrupting other components. A singleton with synchronized methods technically works, but it ties restart to JVM restart, mixes error handling with business logic, and makes testing require a running container.

**Context**

You are building the board component of TaskFlow. A board has a list of columns. Each column has cards. Users add cards, move cards, and archive columns. These operations must be serialized per board — you cannot let two card moves interleave and produce an inconsistent column state — but boards must be independent of each other. A crash in Board A must not affect Board B.

**Solution**

Model the board as a `Proc<BoardState, BoardMsg>`. The `Proc` primitive runs its message handler on a dedicated virtual thread. All state transitions happen inside that thread, so there are no races. The state is immutable between transitions, so a snapshot is always consistent. When the process crashes, the supervisor restarts it with the initial state — or, as you will see in later chapters, with state replayed from the event log.

```java
// src/main/java/io/github/taskflow/board/BoardMsg.java
package io.github.taskflow.board;

import java.util.UUID;

public sealed interface BoardMsg permits
    BoardMsg.AddCard,
    BoardMsg.MoveCard,
    BoardMsg.ArchiveColumn,
    BoardMsg.GetSnapshot {

  record AddCard(UUID columnId, String title, String assignee) implements BoardMsg {}
  record MoveCard(UUID cardId, UUID fromColumn, UUID toColumn) implements BoardMsg {}
  record ArchiveColumn(UUID columnId) implements BoardMsg {}
  record GetSnapshot() implements BoardMsg {}
}
```

The sealed interface is load-bearing. Every `BoardMsg` variant is listed in `permits`. When you write the handler, the compiler enforces exhaustiveness: add a new message type and every switch that pattern-matches on `BoardMsg` must handle it or the build fails. This is the type system working as architecture documentation.

The state is a plain Java record:

```java
// src/main/java/io/github/taskflow/board/BoardState.java
package io.github.taskflow.board;

import java.util.List;
import java.util.UUID;

public record BoardState(
    UUID boardId,
    String name,
    List<Column> columns
) {
  public static BoardState empty(UUID boardId, String name) {
    return new BoardState(boardId, name, List.of(
        new Column(UUID.randomUUID(), "Backlog", List.of()),
        new Column(UUID.randomUUID(), "In Progress", List.of()),
        new Column(UUID.randomUUID(), "Done", List.of())
    ));
  }

  public record Column(UUID id, String name, List<Card> cards) {}
  public record Card(UUID id, String title, String assignee) {}
}
```

Records are immutable by default. Every field is `final`. `List.of()` returns an immutable list. When the handler returns a new `BoardState`, it is replacing the entire state object — there is no mutation, so there is no race even if you could somehow share the reference.

The handler wires the two together:

```java
// src/main/java/io/github/taskflow/board/BoardHandler.java
package io.github.taskflow.board;

import io.github.seanchatmangpt.jotp.Proc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

public final class BoardHandler
    implements BiFunction<BoardState, BoardMsg, BoardState> {

  @Override
  public BoardState apply(BoardState state, BoardMsg msg) {
    return switch (msg) {
      case BoardMsg.AddCard(var columnId, var title, var assignee) ->
          addCard(state, columnId, title, assignee);
      case BoardMsg.MoveCard(var cardId, var fromColumn, var toColumn) ->
          moveCard(state, cardId, fromColumn, toColumn);
      case BoardMsg.ArchiveColumn(var columnId) ->
          archiveColumn(state, columnId);
      case BoardMsg.GetSnapshot() -> state;
    };
  }

  private BoardState addCard(
      BoardState state, UUID columnId, String title, String assignee) {
    var newCard = new BoardState.Card(UUID.randomUUID(), title, assignee);
    var updatedColumns = state.columns().stream()
        .map(col -> col.id().equals(columnId)
            ? new BoardState.Column(col.id(), col.name(),
                append(col.cards(), newCard))
            : col)
        .toList();
    return new BoardState(state.boardId(), state.name(), updatedColumns);
  }

  private BoardState moveCard(
      BoardState state, UUID cardId, UUID fromColumnId, UUID toColumnId) {
    var cardOpt = state.columns().stream()
        .filter(col -> col.id().equals(fromColumnId))
        .flatMap(col -> col.cards().stream())
        .filter(card -> card.id().equals(cardId))
        .findFirst();

    if (cardOpt.isEmpty()) return state;
    var card = cardOpt.get();

    var updatedColumns = state.columns().stream()
        .map(col -> {
          if (col.id().equals(fromColumnId)) {
            return new BoardState.Column(col.id(), col.name(),
                col.cards().stream()
                    .filter(c -> !c.id().equals(cardId))
                    .toList());
          }
          if (col.id().equals(toColumnId)) {
            return new BoardState.Column(col.id(), col.name(),
                append(col.cards(), card));
          }
          return col;
        })
        .toList();
    return new BoardState(state.boardId(), state.name(), updatedColumns);
  }

  private BoardState archiveColumn(BoardState state, UUID columnId) {
    var updatedColumns = state.columns().stream()
        .filter(col -> !col.id().equals(columnId))
        .toList();
    return new BoardState(state.boardId(), state.name(), updatedColumns);
  }

  private <T> List<T> append(List<T> list, T item) {
    var mutable = new ArrayList<>(list);
    mutable.add(item);
    return List.copyOf(mutable);
  }
}
```

The handler is a pure function. Given the same state and the same message, it always produces the same new state. There are no I/O calls, no side effects, no exceptions (if a card is not found, it returns the unchanged state). This purity has a practical consequence: you can test every transition in isolation with a plain unit test, no running JVM infrastructure required.

**Consequences**

Every board is an isolated process. A NullPointerException in Board A's handler will crash Board A but not Board B. The supervisor restarts Board A. Board B never knew anything happened. You have traded some memory (one virtual thread stack per board) for complete isolation.

The handler's purity means the state transition graph is fully testable offline. You can feed a sequence of messages into a `new BoardHandler()` and assert the resulting state without starting the actor system.

The sealed `BoardMsg` interface means you cannot add a new operation without updating every switch in the codebase. The compiler guides your refactoring.

---

### **Pattern: THE MAILBOX GUARANTEE**

**Problem**

Sending messages to a process must be safe from any number of concurrent callers. If two threads call `tell()` at the same moment, both messages must be delivered, in some order, and neither must be lost or corrupted. At the same time, the process must be able to use `ask()` for request-reply semantics when the caller needs a response.

**Context**

TaskFlow's HTTP layer will receive concurrent card-move requests. The WebSocket layer will receive concurrent board-snapshot requests. Both must reach the board process safely. Some operations — GetSnapshot — need a synchronous reply. Others — AddCard, MoveCard — are fire-and-forget.

**Solution**

`Proc` uses a `LinkedTransferQueue` as its mailbox. `LinkedTransferQueue` is a lock-free multi-producer multi-consumer queue: multiple callers can enqueue messages concurrently without blocking each other. The process's virtual thread consumes from the head of the queue in a loop, calling the handler for each message.

The `tell()` method enqueues and returns immediately. The `ask()` method enqueues the message paired with a `CompletableFuture`, waits for the future to complete (with a timeout), and returns the result:

```java
// How you use Proc in production code
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;

import java.time.Duration;
import java.util.UUID;

public class BoardRegistry {
  private final Supervisor supervisor;

  public BoardRegistry() {
    var boardId = UUID.randomUUID();
    var initState = BoardState.empty(boardId, "TaskFlow Demo");
    var handler = new BoardHandler();

    this.supervisor = Supervisor.builder()
        .strategy(Supervisor.Strategy.ONE_FOR_ONE)
        .supervise("board-" + boardId, initState, handler)
        .build();
  }

  public void addCard(ProcRef<BoardState, BoardMsg> boardRef,
                      UUID columnId, String title, String assignee) {
    // Fire-and-forget: returns immediately
    boardRef.tell(new BoardMsg.AddCard(columnId, title, assignee));
  }

  public BoardState getSnapshot(ProcRef<BoardState, BoardMsg> boardRef) {
    // Synchronous request-reply: blocks until handler returns updated state
    return boardRef.ask(new BoardMsg.GetSnapshot(), Duration.ofSeconds(5));
  }
}
```

Notice that `boardRef` is a `ProcRef<BoardState, BoardMsg>`, not a raw `Proc`. This distinction matters. `Proc` is the running process; `ProcRef` is a stable handle that the supervisor maintains. When the board process crashes and the supervisor restarts it, the `ProcRef` is updated to point to the new process. Callers holding a `ProcRef` do not notice the restart. Callers holding a raw `Proc` reference would be holding a dead process.

The `ask()` timeout deserves attention. It is not optional. A call to `ask()` without a timeout is a potential deadlock — if the process crashes before it processes your message, you wait forever. JOTP's API enforces this: `ask()` requires a `Duration`. If the timeout elapses, it throws a `TimeoutException` wrapped in a `RuntimeException`. You handle it or it propagates. Either way, the caller does not block forever.

Here is a test that exercises the mailbox guarantee directly:

```java
// src/test/java/io/github/taskflow/board/BoardHandlerTest.java
package io.github.taskflow.board;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class BoardHandlerTest implements WithAssertions {

  private final BoardHandler handler = new BoardHandler();

  @Test
  void addCard_appendsToCorrectColumn() {
    var boardId = UUID.randomUUID();
    var state = BoardState.empty(boardId, "Test Board");
    var backlogColumn = state.columns().get(0);

    var msg = new BoardMsg.AddCard(backlogColumn.id(), "Write tests", "alice");
    var newState = handler.apply(state, msg);

    assertThat(newState.columns().get(0).cards())
        .hasSize(1)
        .first()
        .satisfies(card -> {
          assertThat(card.title()).isEqualTo("Write tests");
          assertThat(card.assignee()).isEqualTo("alice");
        });
  }

  @Test
  void moveCard_transfersCardBetweenColumns() {
    var boardId = UUID.randomUUID();
    var state = BoardState.empty(boardId, "Test Board");
    var backlog = state.columns().get(0);
    var inProgress = state.columns().get(1);

    // Add a card to Backlog first
    var addMsg = new BoardMsg.AddCard(backlog.id(), "Implement auth", "bob");
    var stateWithCard = handler.apply(state, addMsg);
    var cardId = stateWithCard.columns().get(0).cards().get(0).id();

    // Now move it to In Progress
    var moveMsg = new BoardMsg.MoveCard(cardId, backlog.id(), inProgress.id());
    var finalState = handler.apply(stateWithCard, moveMsg);

    assertThat(finalState.columns().get(0).cards()).isEmpty();
    assertThat(finalState.columns().get(1).cards()).hasSize(1);
    assertThat(finalState.columns().get(1).cards().get(0).title())
        .isEqualTo("Implement auth");
  }

  @Test
  void moveCard_unknownCard_returnsUnchangedState() {
    var boardId = UUID.randomUUID();
    var state = BoardState.empty(boardId, "Test Board");
    var backlog = state.columns().get(0);
    var inProgress = state.columns().get(1);

    var msg = new BoardMsg.MoveCard(UUID.randomUUID(), backlog.id(), inProgress.id());
    var newState = handler.apply(state, msg);

    assertThat(newState).isEqualTo(state);
  }

  @Test
  void archiveColumn_removesColumnFromState() {
    var boardId = UUID.randomUUID();
    var state = BoardState.empty(boardId, "Test Board");
    var columnToArchive = state.columns().get(2); // Done column

    var msg = new BoardMsg.ArchiveColumn(columnToArchive.id());
    var newState = handler.apply(state, msg);

    assertThat(newState.columns()).hasSize(2);
    assertThat(newState.columns())
        .noneMatch(col -> col.id().equals(columnToArchive.id()));
  }
}
```

Run the tests:

```
mvnd test -Dtest=BoardHandlerTest
```

All four tests pass. The handler is correct, tested, and ready to be composed into a supervised process.

**Consequences**

The mailbox serializes access to the board's state without locks visible to application code. There are no `synchronized` blocks, no `ReentrantLock` acquisitions, no volatile fields. The serialization happens in the queue.

Fire-and-forget (`tell`) is appropriate for state mutations. The caller does not need confirmation; the supervisor guarantees eventual delivery as long as the process is alive. Request-reply (`ask`) is appropriate for reads. You need the current snapshot to render a UI, so you wait for it.

The timeout on `ask()` gives you a natural circuit-breaker at the call site. If the board process is overwhelmed and taking more than 5 seconds to respond to a snapshot request, you surface that as a TimeoutException rather than queuing infinitely.

---

### **Pattern: THE GUARD CONTRACT**

**Problem**

Development pressure causes deferred work to accumulate in production code. `// TODO: implement retry`, `return null; // stub`, class names like `MockBoardService` — these are time bombs. They pass reviews because each one is small. They fail in production because there are hundreds of them.

**Context**

TaskFlow will be deployed to production. You have a guard system — `dx-guard` and `./dx.sh validate` — that scans every `.java` file in `src/main/` on every edit. It enforces three rules: no H_TODO comments, no H_MOCK class or method names, no H_STUB empty return values. The PostToolUse hook in `.claude/settings.json` runs this automatically. You cannot ship without passing the guards.

**Solution**

The guard contract is simple: production code must be complete. If you cannot implement something, either leave it out entirely or throw `UnsupportedOperationException` with a message that describes what needs to be done. A `UnsupportedOperationException` at runtime is better than a `NullPointerException` three stack frames later with no indication of what was missing.

Let's see what a guard violation looks like and how to fix it. Here is a naive first draft of a notification service:

```java
// WRONG: This will fail the guard scan
package io.github.taskflow.notify;

public class CardMovedNotifier {
  public void notify(String boardId, String cardTitle) {
    // TODO: send WebSocket broadcast
    return; // stub
  }
}
```

When you save this file, the PostToolUse hook runs `./dx.sh validate`. Output:

```
[H_TODO] src/main/java/io/github/taskflow/notify/CardMovedNotifier.java:4
  Found deferred work marker: // TODO: send WebSocket broadcast

[H_STUB] src/main/java/io/github/taskflow/notify/CardMovedNotifier.java:5
  Found stub return: return; // stub

Guard validation FAILED. Fix violations before proceeding.
```

Two violations. The correct fix depends on context. If WebSocket broadcasting is not yet implemented, the right answer is to throw `UnsupportedOperationException`:

```java
// CORRECT: UnsupportedOperationException is not a stub, it is a contract
package io.github.taskflow.notify;

public class CardMovedNotifier {
  public void notify(String boardId, String cardTitle) {
    throw new UnsupportedOperationException(
        "WebSocket broadcast not yet implemented for board: " + boardId);
  }
}
```

This passes the guard. It is honest: calling `notify()` before the implementation is ready will throw immediately at the call site with a clear message. You cannot accidentally ship a silent no-op.

The full guard setup requires the `dx-guard` binary and a `dx.sh` script. Your project root should have:

```
jotp/
  dx.sh           # Guard runner script
  .claude/
    hooks/
      simple-guards.sh
    settings.json   # PostToolUse hook configuration
```

The `settings.json` hook configuration:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "mvnd spotless:apply -q && bash .claude/hooks/simple-guards.sh"
          }
        ]
      }
    ]
  }
}
```

Every time you save a `.java` file, Spotless formats it (Google Java Format AOSP style), then the guard scanner runs. You get immediate feedback on violations. You never accumulate a backlog of style debt or stub debt.

Run the guards manually at any time:

```
./dx.sh validate
```

Run the full validation suite:

```
./dx.sh all
```

The `all` target runs: Spotless check, guard validation, `mvnd verify` (unit tests + integration tests + PMD + Checkstyle). If any step fails, the build stops.

**Consequences**

The guard contract enforces discipline at the point of authorship, not at the point of code review. You cannot commit a TODO without the guard scan flagging it immediately. This has two effects.

First, it eliminates the slow accumulation of stub debt. Every file in `src/main/` is either implemented or explicitly throwing `UnsupportedOperationException`. There is no ambiguity about whether a method does something.

Second, it changes how you write code. When you know the guard will run on save, you stop writing skeleton implementations with empty bodies. You write either a real implementation or a clear exception. The architecture stays honest.

---

### Setting Up Your Environment

Before you write any more TaskFlow code, you need a working environment. This section walks through the complete setup.

**Install JDK 26**

JOTP requires Java 26 with preview features enabled. The recommended distribution is OpenJDK 26 EA (Early Access):

```bash
# Download and install OpenJDK 26
curl -L https://download.java.net/java/GA/jdk26/openjdk-26_linux-x64_bin.tar.gz \
    -o /tmp/openjdk-26.tar.gz
tar -xzf /tmp/openjdk-26.tar.gz -C /usr/lib/jvm/
export JAVA_HOME=/usr/lib/jvm/jdk-26
export PATH=$JAVA_HOME/bin:$PATH
java --version
# openjdk 26 ...
```

Or run the project's setup script, which handles detection and download:

```bash
bash .claude/setup.sh
```

The setup script installs JDK 26 to `/usr/lib/jvm/openjdk-26`, installs mvnd to `/root/.mvnd/mvnd-2.0.0-rc-3/`, configures Maven proxy settings if an enterprise proxy is detected, and creates symlinks at `/usr/local/bin/mvnd` and `/opt/jdk`.

**Install mvnd**

mvnd (Maven Daemon) keeps the Maven build process alive between invocations. Cold build times on a large JOTP project can be 40 seconds; warm mvnd builds run in 3-5 seconds. Always use `mvnd`, never `mvn`:

```bash
curl -L https://github.com/apache/maven-mvnd/releases/download/1.0.0-m7/maven-mvnd-1.0.0-m7-linux-amd64.tar.gz \
    -o /tmp/mvnd.tar.gz
tar -xzf /tmp/mvnd.tar.gz -C /root/.mvnd/
ln -sf /root/.mvnd/maven-mvnd-1.0.0-m7/bin/mvnd /usr/local/bin/mvnd
mvnd --version
# Maven Daemon ...
```

**Maven Module Setup**

TaskFlow uses JPMS (Java Platform Module System). Your `module-info.java` declares the module name and its dependencies on the JOTP framework:

```java
// src/main/java/module-info.java
module io.github.taskflow {
  requires io.github.seanchatmangpt.jotp;
  requires java.net.http;
  requires java.logging;

  exports io.github.taskflow.board;
  exports io.github.taskflow.notify;
}
```

Your `pom.xml` enables preview features in both compilation and testing:

```xml
<properties>
  <maven.compiler.release>26</maven.compiler.release>
  <maven.compiler.enablePreview>true</maven.compiler.enablePreview>
</properties>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <release>26</release>
    <compilerArgs>
      <arg>--enable-preview</arg>
    </compilerArgs>
  </configuration>
</plugin>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <version>3.5.2</version>
  <configuration>
    <argLine>--enable-preview</argLine>
  </configuration>
</plugin>
```

**Running `./dx.sh all`**

The full validation suite:

```
./dx.sh all
```

You should see output like:

```
[spotless] Checking formatting... OK
[guards]   Scanning src/main/... OK (0 violations)
[build]    mvnd verify...
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[dx] All checks passed.
```

If Spotless reports a violation, run `mvnd spotless:apply -q` to auto-fix it. If a guard violation is reported, fix the offending file. If tests fail, read the failure message — it will point to an exact line in your handler.

**Putting It Together: Your First Supervised Board**

Here is the minimal wiring that creates a supervised board process and exercises it end-to-end:

```java
// src/test/java/io/github/taskflow/board/BoardIntegrationIT.java
package io.github.taskflow.board;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

class BoardIntegrationIT implements WithAssertions {

  private Supervisor supervisor;
  private ProcRef<BoardState, BoardMsg> boardRef;
  private UUID boardId;

  @BeforeEach
  void setUp() {
    boardId = UUID.randomUUID();
    var initState = BoardState.empty(boardId, "Integration Test Board");
    var handler = new BoardHandler();

    supervisor = Supervisor.builder()
        .strategy(Supervisor.Strategy.ONE_FOR_ONE)
        .supervise("board-" + boardId, initState, handler)
        .build();

    boardRef = supervisor.ref("board-" + boardId);
  }

  @AfterEach
  void tearDown() {
    supervisor.shutdown();
  }

  @Test
  void board_acceptsTellMessages_updatesState() {
    var snapshot = boardRef.ask(new BoardMsg.GetSnapshot(), Duration.ofSeconds(5));
    var backlogId = snapshot.columns().get(0).id();

    boardRef.tell(new BoardMsg.AddCard(backlogId, "First card", "alice"));
    boardRef.tell(new BoardMsg.AddCard(backlogId, "Second card", "bob"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> {
          var updated = boardRef.ask(
              new BoardMsg.GetSnapshot(), Duration.ofSeconds(1));
          assertThat(updated.columns().get(0).cards()).hasSize(2);
        });
  }

  @Test
  void board_handlesConcurrentTells_withoutDataRace() throws InterruptedException {
    var snapshot = boardRef.ask(new BoardMsg.GetSnapshot(), Duration.ofSeconds(5));
    var backlogId = snapshot.columns().get(0).id();

    int threadCount = 20;
    var latch = new CountDownLatch(threadCount);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          latch.countDown();
          try {
            latch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          boardRef.tell(
              new BoardMsg.AddCard(backlogId, "Card " + index, "user" + index));
        });
      }
    }

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          var updated = boardRef.ask(
              new BoardMsg.GetSnapshot(), Duration.ofSeconds(1));
          assertThat(updated.columns().get(0).cards()).hasSize(threadCount);
        });
  }
}
```

The second test is the one that matters. Twenty virtual threads all fire `tell()` at the same moment. The `LinkedTransferQueue` serializes delivery. The handler processes them one at a time. When all twenty have been processed, the snapshot shows exactly twenty cards — no duplicates, no lost messages. Try the same test with a shared `ArrayList` and a synchronized method and you will see either exceptions or missing cards. The mailbox guarantee is structural.

Run the integration tests:

```
mvnd verify -Dit.test=BoardIntegrationIT
```

---

### What Have You Learned?

- **THE ACTOR AS PROCESS**: A `Proc<S, M>` models a stateful component as an isolated process with a sealed message interface and an immutable state type. Sealed types enforce exhaustiveness in the handler switch; records enforce immutability in the state.

- **THE MAILBOX GUARANTEE**: `LinkedTransferQueue` serializes concurrent `tell()` calls without application-level locks. `ask()` provides request-reply semantics with a mandatory timeout, preventing deadlocks. `ProcRef` is a stable handle that survives supervisor restarts; never hold a raw `Proc`.

- **THE GUARD CONTRACT**: The guard system scans every file in `src/main/` on every save. H_TODO, H_MOCK, and H_STUB patterns are build failures. Production code is either implemented or throws `UnsupportedOperationException`. There is no third option.

- **Environment**: JDK 26 preview features are enabled in `pom.xml` for both compilation and Surefire. `mvnd` keeps the build daemon warm. `./dx.sh all` runs Spotless, guards, and full test suite in sequence.

- **Pure handlers are testable**: Because `BoardHandler` is a `BiFunction<BoardState, BoardMsg, BoardState>` with no side effects, every state transition can be tested with a plain unit test — no actor system, no supervisor, no running threads required.

---

## Chapter 2: Use Terraform to Create GitHub Issues and Milestones

You have a working board process. Now you need a project. Specifically, you need a GitHub repository for TaskFlow, a set of milestones tracking the development stages, and a set of issues tracking the work items. You could create all of this by clicking through the GitHub UI. You should not.

The GitHub UI creates configuration that lives in GitHub's database and nowhere else. You cannot review it in a pull request. You cannot reproduce it from scratch after an accidental deletion. You cannot diff it between environments. You cannot test that it is correct before applying it.

Terraform solves this. Infrastructure as code is not just for AWS resources. GitHub issues, milestones, branch protection rules, and repository settings are all infrastructure, and all of them should live in a `.tf` file checked into your repository.

---

### **Pattern: INFRASTRUCTURE AS EXECUTABLE SPECIFICATION**

**Problem**

Your project's structure — milestones, issue templates, repository settings, branch protection — needs to be consistent, reproducible, and reviewable. Creating it manually means no audit trail, no reproducibility, and no way to catch errors before they reach the team.

**Context**

TaskFlow is a multi-chapter project. You need milestones for each development phase (Environment Setup, Core Actors, WebSocket Layer, AWS Deployment, Operations), issues for the work items within each phase, and a repository configured with sensible defaults. This configuration will be read by every new team member who joins the project.

**Solution**

Define the entire GitHub project structure in Terraform HCL files. A `.tf` file is an executable specification: it describes what should exist, not how to create it. Terraform's plan step shows you what will be created, modified, or destroyed before you apply anything. The apply step makes reality match the specification.

Start with the provider configuration:

```hcl
# terraform/github/versions.tf
terraform {
  required_version = ">= 1.7.0"

  required_providers {
    github = {
      source  = "integrations/github"
      version = "~> 6.0"
    }
  }

  backend "s3" {
    bucket         = "taskflow-terraform-state"
    key            = "github/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "taskflow-terraform-locks"
  }
}

provider "github" {
  token = var.github_token
  owner = var.github_owner
}
```

The S3 backend stores Terraform state remotely. Multiple engineers can apply changes from different machines without stepping on each other, because the state file is locked via DynamoDB during applies. This is essential for team use.

Variables make the configuration portable:

```hcl
# terraform/github/variables.tf
variable "github_token" {
  type        = string
  description = "GitHub personal access token with repo and admin:org scopes"
  sensitive   = true
}

variable "github_owner" {
  type        = string
  description = "GitHub organization or user name owning the repository"
}

variable "repository_name" {
  type        = string
  default     = "taskflow"
  description = "Name of the GitHub repository to create"
}

variable "repository_description" {
  type        = string
  default     = "Real-time Kanban board built with JOTP on Java 26"
}

variable "default_branch" {
  type        = string
  default     = "main"
}
```

The `github_token` variable is marked `sensitive = true`. Terraform will not print it in plan or apply output. You pass it via an environment variable:

```bash
export TF_VAR_github_token="ghp_..."
export TF_VAR_github_owner="your-org-name"
```

Never put tokens in `.tf` files or `terraform.tfvars` files checked into source control.

The repository resource:

```hcl
# terraform/github/main.tf
resource "github_repository" "taskflow" {
  name        = var.repository_name
  description = var.repository_description
  visibility  = "private"

  has_issues   = true
  has_projects = false
  has_wiki     = false

  auto_init          = true
  gitignore_template = "Java"
  license_template   = "apache-2.0"

  default_branch = var.default_branch

  allow_merge_commit     = false
  allow_squash_merge     = true
  allow_rebase_merge     = false
  delete_branch_on_merge = true
}
```

This is a complete specification of the repository's settings. `allow_merge_commit = false` and `allow_rebase_merge = false` enforce squash merges only, which produces a clean linear history. `delete_branch_on_merge = true` keeps the repository tidy. These are opinions baked into the specification, visible in code review, enforced by Terraform.

Branch protection ensures the main branch cannot be pushed to directly:

```hcl
resource "github_branch_protection" "main" {
  repository_id = github_repository.taskflow.node_id
  pattern       = var.default_branch

  required_status_checks {
    strict   = true
    contexts = ["build", "test", "guard-validation"]
  }

  required_pull_request_reviews {
    required_approving_review_count = 1
    dismiss_stale_reviews           = true
    require_code_owner_reviews      = true
  }

  enforce_admins = true
}
```

`enforce_admins = true` means even repository administrators cannot bypass the branch protection. This is controversial in some organizations, but for a production system it is the right default. Admins bypassing protection are the most common source of "one quick fix" incidents.

**Consequences**

Anyone with access to the repository and the Terraform state bucket can recreate the entire GitHub project structure from scratch with three commands:

```bash
terraform init
terraform plan
terraform apply
```

New team members see the project structure in code before they look at GitHub. Changes to branch protection or repository settings go through pull request review. Accidental deletions are recoverable.

The tradeoff is that Terraform adds a dependency: you need an S3 bucket and DynamoDB table before you can initialize the backend. Chapter 3 covers creating these AWS resources with Terraform as well — the infrastructure bootstraps itself, layer by layer.

---

### **Pattern: STATE AS SINGLE SOURCE OF TRUTH**

**Problem**

GitHub milestones and issues get created, modified, and sometimes deleted by team members through the UI. After a few weeks, the Terraform state diverges from reality. Plans show unexpected diffs. Applies fail because resources exist that Terraform does not know about. You lose confidence in the specification.

**Context**

TaskFlow development spans multiple phases. The milestones and issues need to track real work, which means they will be updated as the project progresses. You need a strategy that keeps Terraform as the authoritative source while allowing the natural evolution of project state.

**Solution**

Define milestones and issues in Terraform, but use `lifecycle` blocks to prevent Terraform from destroying issues that have been closed or updated:

```hcl
# terraform/github/milestones.tf
locals {
  milestones = {
    "v0.1-environment" = {
      title       = "v0.1: Environment & Core Actors"
      description = "JDK 26, mvnd, dx-guard, first Proc<BoardState, BoardMsg>"
      due_date    = "2026-04-01"
    }
    "v0.2-state-machine" = {
      title       = "v0.2: Card State Machine"
      description = "StateMachine<CardState, CardEvent, CardData> with full supervision"
      due_date    = "2026-04-15"
    }
    "v0.3-websocket" = {
      title       = "v0.3: WebSocket Broadcasting"
      description = "Real-time card move events via EventManager"
      due_date    = "2026-05-01"
    }
    "v0.4-aws" = {
      title       = "v0.4: AWS Deployment"
      description = "ECS Fargate, RDS Aurora, ElastiCache, Route 53"
      due_date    = "2026-05-15"
    }
    "v0.5-ops" = {
      title       = "v0.5: Operations"
      description = "Observability, runbooks, chaos testing"
      due_date    = "2026-06-01"
    }
  }
}

resource "github_repository_milestone" "milestones" {
  for_each = local.milestones

  owner      = var.github_owner
  repository = github_repository.taskflow.name
  title      = each.value.title
  description = each.value.description
  due_date   = each.value.due_date
}
```

Using `for_each` with a local map means adding a new milestone is a one-line change to the `locals` block. Terraform plans the creation of the new resource and leaves existing milestones untouched.

Issues are more complex because they evolve — they get assigned, labeled, commented on, and closed. Define the foundational issues in Terraform, but accept that their state will drift:

```hcl
# terraform/github/issues.tf
locals {
  issues = {
    "setup-jdk26" = {
      title     = "Set up JDK 26 development environment"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] JDK 26 EA installed at `/usr/lib/jvm/openjdk-26`
        - [ ] `java --version` reports 26
        - [ ] `--enable-preview` flag configured in pom.xml
        - [ ] `mvnd verify` passes on clean checkout

        ## Notes
        Use `.claude/setup.sh` for automated installation.
        Verify with `./dx.sh all`.
      EOT
      milestone = "v0.1-environment"
      labels    = ["setup", "environment"]
    }
    "first-proc" = {
      title     = "Implement Proc<BoardState, BoardMsg>"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] `BoardMsg` sealed interface with AddCard, MoveCard, ArchiveColumn, GetSnapshot
        - [ ] `BoardState` record with Column and Card nested records
        - [ ] `BoardHandler` pure BiFunction passing all unit tests
        - [ ] `BoardIntegrationIT` passing with concurrent tell() test
        - [ ] Guard scan clean (no H_TODO, H_MOCK, H_STUB)

        ## Definition of Done
        `./dx.sh all` passes with zero violations.
      EOT
      milestone = "v0.1-environment"
      labels    = ["jotp", "actors", "core"]
    }
    "supervision-tree" = {
      title     = "Wire BoardRegistry with ONE_FOR_ONE Supervisor"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] `BoardRegistry` creates Supervisor with ONE_FOR_ONE strategy
        - [ ] Each board has a named process: `board-{boardId}`
        - [ ] `ProcRef` used throughout (no raw Proc references)
        - [ ] Board crash does not affect other boards (integration test)
        - [ ] Restart count verified in supervisor test

        ## References
        See ARCHITECTURE.md Pattern 2: Bulkhead
      EOT
      milestone = "v0.1-environment"
      labels    = ["jotp", "supervision", "core"]
    }
    "card-state-machine" = {
      title     = "Implement StateMachine<CardState, CardEvent, CardData>"
      body      = <<-EOT
        ## Acceptance Criteria
        - [ ] `CardState` sealed interface: Backlog, InProgress, Done, Archived
        - [ ] `CardEvent` sealed interface: Assign, Start, Complete, Archive, Reopen
        - [ ] All legal transitions defined; illegal transitions return Stop(reason)
        - [ ] `CardStateMachineTest` property-based tests with jqwik

        ## Design Notes
        Illegal transitions should produce `Stop("Invalid transition: {state} -> {event}")`
        not exceptions. The state machine is a process; it should fail safely.
      EOT
      milestone = "v0.2-state-machine"
      labels    = ["jotp", "state-machine", "core"]
    }
  }
}

resource "github_issue" "issues" {
  for_each = local.issues

  repository = github_repository.taskflow.name
  title      = each.value.title
  body       = each.value.body
  labels     = each.value.labels

  # Resolve milestone number from the milestone resource
  milestone_number = github_repository_milestone.milestones[each.value.milestone].number

  lifecycle {
    # Ignore changes to state (open/closed) and assignments
    # These change naturally as work progresses
    ignore_changes = [
      state,
      assignees,
    ]
  }
}
```

The `lifecycle { ignore_changes }` block is the key to making STATE AS SINGLE SOURCE OF TRUTH practical. Terraform created the issue with the correct title, body, and milestone. If a team member closes the issue or reassigns it, Terraform will not reopen it or remove the assignee on the next apply. The issue's current state is owned by the team. The issue's definition — title, body, milestone — is owned by Terraform.

This is a deliberate design decision. Terraform owns the specification; humans own the execution state.

Labels also need to be defined before they can be applied to issues:

```hcl
# terraform/github/labels.tf
locals {
  labels = {
    "setup"        = { color = "0075ca", description = "Development environment setup" }
    "environment"  = { color = "e4e669", description = "Build tooling and infrastructure" }
    "jotp"         = { color = "7057ff", description = "JOTP framework primitives" }
    "actors"       = { color = "008672", description = "Proc and actor model patterns" }
    "supervision"  = { color = "b60205", description = "Supervisor trees and restart strategies" }
    "state-machine"= { color = "d93f0b", description = "StateMachine pattern" }
    "core"         = { color = "0e8a16", description = "Core TaskFlow business logic" }
    "aws"          = { color = "ff9f1a", description = "AWS infrastructure resources" }
    "ops"          = { color = "1d76db", description = "Operational concerns and runbooks" }
  }
}

resource "github_issue_label" "labels" {
  for_each = local.labels

  repository  = github_repository.taskflow.name
  name        = each.key
  color       = each.value.color
  description = each.value.description
}
```

Outputs make it easy to find created resources:

```hcl
# terraform/github/outputs.tf
output "repository_url" {
  value       = github_repository.taskflow.html_url
  description = "URL of the created GitHub repository"
}

output "repository_clone_url" {
  value       = github_repository.taskflow.ssh_clone_url
  description = "SSH clone URL"
}

output "milestone_numbers" {
  value = {
    for k, v in github_repository_milestone.milestones :
    k => v.number
  }
  description = "Map of milestone key to GitHub milestone number"
}

output "issue_numbers" {
  value = {
    for k, v in github_issue.issues :
    k => v.number
  }
  description = "Map of issue key to GitHub issue number"
}
```

**Consequences**

Running `terraform plan` after any change shows you exactly what GitHub state will be modified before you commit to it. A new team member can run `terraform plan` without the `github_token` to read the structure (plan will fail on API calls, but the local validation passes). The specification is self-documenting.

The `lifecycle { ignore_changes }` pattern accepts a tradeoff: if you change an issue's title in Terraform, Terraform will update it. But if a team member closes the issue, Terraform will not reopen it. This is the right tradeoff for project management resources.

---

### Running Terraform

**Initialize the backend**:

```bash
cd terraform/github
terraform init
```

Output:

```
Initializing the backend...
Successfully configured the backend "s3"!

Initializing provider plugins...
- Finding integrations/github versions matching "~> 6.0"...
- Installing integrations/github v6.3.1...

Terraform has been successfully initialized!
```

**Preview changes**:

```bash
terraform plan -out=tfplan
```

Output (abbreviated):

```
Terraform will perform the following actions:

  # github_repository.taskflow will be created
  + resource "github_repository" "taskflow" {
      + name        = "taskflow"
      + description = "Real-time Kanban board built with JOTP on Java 26"
      + visibility  = "private"
      ...
    }

  # github_repository_milestone.milestones["v0.1-environment"] will be created
  + resource "github_repository_milestone" "milestones" {
      + title    = "v0.1: Environment & Core Actors"
      + due_date = "2026-04-01"
    }

  # ... 4 more milestones, 4 issues, 9 labels, 1 branch protection

Plan: 20 to add, 0 to change, 0 to destroy.
```

Review the plan. Every resource in the `+` list should be there. Nothing should be in the `-` (destroy) list on a first apply.

**Apply**:

```bash
terraform apply tfplan
```

```
github_repository.taskflow: Creating...
github_repository.taskflow: Creation complete after 3s
github_issue_label.labels["jotp"]: Creating...
...
github_repository_milestone.milestones["v0.1-environment"]: Creating...
...
github_issue.issues["setup-jdk26"]: Creating...
...

Apply complete! Resources: 20 added, 0 changed, 0 destroyed.

Outputs:

repository_url = "https://github.com/your-org/taskflow"
milestone_numbers = {
  "v0.1-environment" = 1
  "v0.2-state-machine" = 2
  ...
}
issue_numbers = {
  "card-state-machine" = 4
  "first-proc" = 2
  "setup-jdk26" = 1
  "supervision-tree" = 3
}
```

Your project structure now exists in GitHub and in Terraform state. Clone the repository, open issue #1, assign it to yourself, and start working through Chapter 1's setup steps. When you close the issue, `terraform plan` will show no changes to that issue because `state` is in `ignore_changes`.

**Adding a new milestone**

When you reach the AWS deployment phase, add to the `locals` block in `milestones.tf`:

```hcl
"v0.6-performance" = {
  title       = "v0.6: Performance Benchmarking"
  description = "JMH benchmarks for ActorBenchmark, ParallelBenchmark, ResultBenchmark"
  due_date    = "2026-06-15"
}
```

Run `terraform plan`. You see:

```
  # github_repository_milestone.milestones["v0.6-performance"] will be created
  + resource "github_repository_milestone" "milestones" {
      + title = "v0.6: Performance Benchmarking"
    }

Plan: 1 to add, 0 to change, 0 to destroy.
```

One addition, zero modifications to existing resources. The plan is surgical. Apply it.

---

### What Have You Learned?

- **INFRASTRUCTURE AS EXECUTABLE SPECIFICATION**: Terraform HCL files describe what should exist, not how to create it. `terraform plan` previews changes; `terraform apply` makes them real. GitHub repositories, milestones, branch protection, and issues are all infrastructure that belongs in version control.

- **STATE AS SINGLE SOURCE OF TRUTH**: Terraform owns the definition of project structure; team members own the execution state (open/closed, assigned/unassigned). The `lifecycle { ignore_changes }` block makes this split explicit and prevents Terraform from clobbering human decisions.

- **Provider and backend configuration**: The GitHub Terraform provider authenticates via a personal access token passed as `TF_VAR_github_token`. State is stored in S3 with DynamoDB locking to support team use. These are standard patterns for any Terraform workspace.

- **`for_each` and locals**: Defining collections of similar resources (milestones, labels, issues) as `for_each` maps keeps the configuration DRY. Adding a resource is a one-line change to a `locals` block.

- **Outputs**: Terraform outputs make it easy to reference created resource identifiers (milestone numbers, issue numbers, repository URLs) without navigating the GitHub UI.

- **Sensitive variables**: Tokens and secrets are `sensitive = true` in variable declarations and passed via environment variables (`TF_VAR_*`). They never appear in `.tf` files or Terraform plan output.

With your environment configured, your guard system running, and your project structured in GitHub, you are ready to build the TaskFlow supervision tree in Chapter 3.
