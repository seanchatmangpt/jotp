# Chapter 1: Get to Know JOTP and Set Up Your Environment

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

The `tell()` method enqueues and returns immediately. The `ask()` method enqueues the message paired with a `CompletableFuture` and returns that future. Calling `.join()` on the future waits for the handler to process the message and returns the resulting state. You can add a timeout via `ask(msg).orTimeout(5, TimeUnit.SECONDS)`, or use the overload on `Proc` directly: `proc.ask(msg, Duration.ofSeconds(5))`.

`ProcRef` wraps a `Proc` and exposes `tell(M msg)` and `ask(M msg)` — the `ask` on `ProcRef` returns a `CompletableFuture<S>` with no built-in timeout, so always chain `.orTimeout(...)` or `.join()` with a surrounding timeout boundary when you need a synchronous result.

```java
// How you use Proc in production code
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class BoardRegistry {
  private final Supervisor supervisor;
  private final ProcRef<BoardState, BoardMsg> boardRef;

  public BoardRegistry() {
    var boardId = UUID.randomUUID();
    var initState = BoardState.empty(boardId, "TaskFlow Demo");
    var handler = new BoardHandler();

    // Supervisor.create() returns a Supervisor; supervise() returns the ProcRef directly.
    this.supervisor = Supervisor.create(
        Supervisor.Strategy.ONE_FOR_ONE,
        5,
        Duration.ofSeconds(60));
    this.boardRef = supervisor.supervise("board-" + boardId, initState, handler);
  }

  public void addCard(ProcRef<BoardState, BoardMsg> ref,
                      UUID columnId, String title, String assignee) {
    // Fire-and-forget: returns immediately
    ref.tell(new BoardMsg.AddCard(columnId, title, assignee));
  }

  public BoardState getSnapshot(ProcRef<BoardState, BoardMsg> ref) {
    // ask() returns CompletableFuture<BoardState>; join() blocks until the handler responds.
    return ref.ask(new BoardMsg.GetSnapshot()).join();
  }
}
```

Notice that `boardRef` is a `ProcRef<BoardState, BoardMsg>`, not a raw `Proc`. This distinction matters. `Proc` is the running process; `ProcRef` is a stable handle that the supervisor maintains. When the board process crashes and the supervisor restarts it, the `ProcRef` is updated to point to the new process. Callers holding a `ProcRef` do not notice the restart. Callers holding a raw `Proc` reference would be holding a dead process.

The `ask()` return type deserves attention. Both `Proc.ask(M msg)` and `ProcRef.ask(M msg)` return `CompletableFuture<S>`. `Proc` additionally provides `ask(M msg, Duration timeout)` which returns a future pre-configured to complete exceptionally with `TimeoutException` after the given duration — this is the safe form for any production call-site where you cannot afford to block indefinitely. If the process crashes before processing your message, the future will never complete without a timeout, so always bound your waits.

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

The `CompletableFuture` returned by `ask()` gives you a natural circuit-breaker at the call site. Chain `.orTimeout(5, TimeUnit.SECONDS)` and the future completes exceptionally with `TimeoutException` if the board process is overwhelmed and takes too long to respond — rather than queuing infinitely.

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
  <version>3.15.0</version>
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
  <version>3.5.5</version>
  <configuration>
    <argLine>--enable-preview --add-reads io.github.seanchatmangpt.jotp=ALL-UNNAMED</argLine>
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
import java.util.concurrent.TimeUnit;

class BoardIntegrationIT implements WithAssertions {

  private Supervisor supervisor;
  private ProcRef<BoardState, BoardMsg> boardRef;
  private UUID boardId;

  @BeforeEach
  void setUp() {
    boardId = UUID.randomUUID();
    var initState = BoardState.empty(boardId, "Integration Test Board");
    var handler = new BoardHandler();

    // Supervisor.create() returns the supervisor; supervise() returns the ProcRef.
    supervisor = Supervisor.create(
        Supervisor.Strategy.ONE_FOR_ONE,
        5,
        Duration.ofSeconds(60));
    boardRef = supervisor.supervise("board-" + boardId, initState, handler);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    supervisor.shutdown();
  }

  @Test
  void board_acceptsTellMessages_updatesState() {
    // ask() returns CompletableFuture<BoardState>; join() blocks for the result.
    var snapshot = boardRef.ask(new BoardMsg.GetSnapshot()).join();
    var backlogId = snapshot.columns().get(0).id();

    boardRef.tell(new BoardMsg.AddCard(backlogId, "First card", "alice"));
    boardRef.tell(new BoardMsg.AddCard(backlogId, "Second card", "bob"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> {
          var updated = boardRef.ask(new BoardMsg.GetSnapshot())
              .orTimeout(1, TimeUnit.SECONDS)
              .join();
          assertThat(updated.columns().get(0).cards()).hasSize(2);
        });
  }

  @Test
  void board_handlesConcurrentTells_withoutDataRace() throws InterruptedException {
    var snapshot = boardRef.ask(new BoardMsg.GetSnapshot()).join();
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
          var updated = boardRef.ask(new BoardMsg.GetSnapshot())
              .orTimeout(1, TimeUnit.SECONDS)
              .join();
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

- **THE MAILBOX GUARANTEE**: `LinkedTransferQueue` serializes concurrent `tell()` calls without application-level locks. `ask()` returns a `CompletableFuture<S>`; chain `.join()` for a blocking wait or `.orTimeout(n, unit)` to bound the wait. `ProcRef` is a stable handle that survives supervisor restarts; never hold a raw `Proc`. `Proc.ask(msg, Duration)` is a convenience overload that bakes the timeout into the future directly.

- **THE GUARD CONTRACT**: The guard system scans every file in `src/main/` on every save. H_TODO, H_MOCK, and H_STUB patterns are build failures. Production code is either implemented or throws `UnsupportedOperationException`. There is no third option.

- **Environment**: JDK 26 preview features are enabled in `pom.xml` for both compilation and Surefire. `mvnd` keeps the build daemon warm. `./dx.sh all` runs Spotless, guards, and full test suite in sequence.

- **Pure handlers are testable**: Because `BoardHandler` is a `BiFunction<BoardState, BoardMsg, BoardState>` with no side effects, every state transition can be tested with a plain unit test — no actor system, no supervisor, no running threads required.

- **Supervisor construction**: Create a `Supervisor` with `Supervisor.create(Strategy, maxRestarts, window)`, then call `supervisor.supervise(id, initialState, handler)` which returns the `ProcRef` directly. There is no builder API. `supervisor.shutdown()` throws `InterruptedException` and must be called from a context that handles it (e.g., a `@AfterEach` method declared `throws InterruptedException`).
