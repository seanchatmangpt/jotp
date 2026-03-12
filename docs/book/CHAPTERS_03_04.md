# Engineering Java Applications: Navigate Each Stage of Software Delivery with JOTP

## Chapter 3: Build and Package the TaskFlow Application

You have a domain. You have primitives. Now you have to wire them together into something that compiles, tests cleanly, and ships. This chapter takes the TaskFlow Kanban board from concept to packaged artifact. Along the way you will encounter four patterns that appear in nearly every JOTP application — and nearly every serious Java application whether it uses JOTP or not. Master them here and you will recognize them in production systems you never wrote.

TaskFlow is a real-time Kanban board. Cards live in columns. Users drag cards across columns. Under the hood, each board is a supervised actor, each card is a state machine, and every failure is handled as data rather than as a thrown exception. By the end of this chapter you will have a packaged JAR built with `mvnd package`, verified by property-based tests, and structured so that a container can run it in a single command.

---

### Pattern: THE SUPERVISION TREE AS ARCHITECTURE

**Problem**

You have a set of stateful components — board workers, card processors, event publishers — and you want to ensure that when one of them fails it does not cascade into the others. But you also want the system to recover automatically, without an operator paging through logs at 3 AM.

**Context**

Object-oriented design gives you classes. Dependency injection gives you a graph of collaborators. Neither tells you what happens when a collaborator crashes. In a traditional Spring Boot application, an uncaught exception in a `@Service` propagates up the call stack. If that service holds a thread from the shared executor, the thread is gone. If it holds a database connection, the connection may be leaked. You paper over this with `@Retryable` annotations and exponential backoff wrappers, but you are describing a wish list, not a topology.

JOTP's `Supervisor` makes the restart topology explicit. It is not an annotation on a method. It is a first-class object in your configuration, and it is part of your architecture diagram.

**Solution**

Model your system as a supervision tree. Each logical grouping of actors lives under a `Supervisor`. Use `ONE_FOR_ONE` when the children are independent — a failure in the board worker for tenant A should not restart the board worker for tenant B. Use `ONE_FOR_ALL` when the children must stay in sync — if the cache actor dies, the data actor that depends on it should restart too.

Wire the supervisor as a Spring `@Bean` so that the application context owns its lifecycle.

**Code Example**

```java
// taskflow/config/TaskFlowConfig.java
package io.github.seanchatmangpt.taskflow.config;

import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Strategy;
import io.github.seanchatmangpt.taskflow.board.BoardHandler;
import io.github.seanchatmangpt.taskflow.board.BoardMsg;
import io.github.seanchatmangpt.taskflow.board.BoardState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class TaskFlowConfig {

    /**
     * Root supervisor for all board workers.
     *
     * ONE_FOR_ONE: a crash in board-A does not affect board-B.
     * maxRestarts=5 within 60 seconds — after the fifth crash the
     * supervisor itself terminates and Spring's context-closed event
     * fires, giving you a clean shutdown rather than a zombie process.
     */
    @Bean
    public Supervisor boardSupervisor() {
        return Supervisor.create(
            "board-supervisor",
            Strategy.ONE_FOR_ONE,
            5,
            Duration.ofSeconds(60)
        );
    }
}
```

```java
// taskflow/board/BoardService.java
package io.github.seanchatmangpt.taskflow.board;

import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Supervisor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BoardService {

    private final Supervisor supervisor;
    private final ConcurrentHashMap<String, ProcRef<BoardState, BoardMsg>> boards
            = new ConcurrentHashMap<>();

    public BoardService(Supervisor boardSupervisor) {
        this.supervisor = boardSupervisor;
    }

    /**
     * Create a new board actor and register it under the supervisor.
     * The returned ProcRef is a stable handle: even if the underlying
     * virtual thread restarts, the ref keeps pointing to the new process.
     */
    public ProcRef<BoardState, BoardMsg> getOrCreateBoard(String boardId) {
        return boards.computeIfAbsent(boardId, id -> {
            var initial = new BoardState(id, java.util.List.of());
            return supervisor.supervise(
                "board-" + id,
                initial,
                new BoardHandler()
            );
        });
    }

    public ProcRef<BoardState, BoardMsg> boardRef(String boardId) {
        var ref = boards.get(boardId);
        if (ref == null) {
            throw new IllegalArgumentException("No board: " + boardId);
        }
        return ref;
    }
}
```

The supervision tree is your architecture made executable. The names you choose for supervised children (`"board-" + id`) appear in logs, in health checks, and in restart metrics. They are not implementation details — they are the vocabulary of your system.

**Consequences**

Failure isolation is now structural. A board worker that throws a `RuntimeException` on a malformed message will restart within microseconds. Its sibling boards are untouched. The `ProcRef` that clients hold continues to work after the restart because it addresses the logical process, not the underlying virtual thread.

The cost is visibility: you need to read the supervision tree to understand the restart topology. There is no topology buried in `@Retryable(maxAttempts=3)` spread across fifty classes. That is a feature, not a limitation.

---

### Pattern: THE STATE MACHINE AS DOMAIN MODEL

**Problem**

A Kanban card moves through a defined lifecycle: it starts in `Todo`, someone picks it up and it enters `InProgress`, it gets completed and moves to `Done`, and eventually it gets `Archived`. Not every transition is legal. You cannot complete a card that is already done. You cannot archive a card that is in progress without completing it first.

You could enforce these rules with `if` statements scattered across service methods. But then the rules live in behavior rather than in structure, and the next developer who adds a `Reopen` transition will miss half the guards.

**Context**

Domain models that have explicit state and legal transitions are state machines. Java gives you enums for states and sealed interfaces for events, but it does not give you a transition function with compile-time guarantees. JOTP's `StateMachine<S,E,D>` does. The transition function is a pure `BiFunction<S, E, Transition<S,D>>` — it takes the current state and the incoming event and returns a `Transition` sealed type that tells the machine what to do next. No side effects. No exceptions. Just data.

**Solution**

Model `TaskState` as a sealed interface (or enum — for simple cases an enum is fine). Model `TaskEvent` as a sealed interface so pattern matching is exhaustive. The transition function is a `switch` expression that covers every `(state, event)` pair you care about and returns `NextState`, `KeepState`, or `Stop`.

**Code Example**

```java
// taskflow/task/TaskState.java
package io.github.seanchatmangpt.taskflow.task;

public enum TaskState {
    TODO, IN_PROGRESS, DONE, ARCHIVED
}
```

```java
// taskflow/task/TaskEvent.java
package io.github.seanchatmangpt.taskflow.task;

public sealed interface TaskEvent {
    record Start(String assignee)   implements TaskEvent {}
    record Complete(String note)    implements TaskEvent {}
    record Archive()                implements TaskEvent {}
    record Reopen()                 implements TaskEvent {}
}
```

```java
// taskflow/task/TaskData.java
package io.github.seanchatmangpt.taskflow.task;

import java.time.Instant;
import java.util.Optional;

public record TaskData(
    String id,
    String title,
    String description,
    Optional<String> assignee,
    Optional<String> completionNote,
    Instant createdAt,
    Instant updatedAt
) {
    public TaskData withAssignee(String a) {
        return new TaskData(id, title, description,
            Optional.of(a), completionNote, createdAt, Instant.now());
    }

    public TaskData withNote(String n) {
        return new TaskData(id, title, description,
            assignee, Optional.of(n), createdAt, Instant.now());
    }

    public TaskData clearAssignee() {
        return new TaskData(id, title, description,
            Optional.empty(), completionNote, createdAt, Instant.now());
    }
}
```

```java
// taskflow/task/TaskTransitions.java
package io.github.seanchatmangpt.taskflow.task;

import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import io.github.seanchatmangpt.jotp.StateMachine.Transition.NextState;
import io.github.seanchatmangpt.jotp.StateMachine.Transition.KeepState;
import io.github.seanchatmangpt.jotp.StateMachine.Transition.Stop;

import java.util.function.BiFunction;

public final class TaskTransitions {

    /** Pure transition function. No I/O. No side effects. */
    public static final BiFunction<TaskState, TaskEvent, Transition<TaskState, TaskData>>
        FUNCTION = (state, event) -> switch (state) {

        case TODO -> switch (event) {
            case TaskEvent.Start(var assignee) ->
                new NextState<>(TaskState.IN_PROGRESS, data -> data.withAssignee(assignee));
            case TaskEvent.Archive() ->
                new Stop<>("archived-from-todo");
            default ->
                new KeepState<>();  // Ignore Complete, Reopen in TODO
        };

        case IN_PROGRESS -> switch (event) {
            case TaskEvent.Complete(var note) ->
                new NextState<>(TaskState.DONE, data -> data.withNote(note));
            default ->
                new KeepState<>();  // Cannot re-start an in-progress task
        };

        case DONE -> switch (event) {
            case TaskEvent.Archive() ->
                new NextState<>(TaskState.ARCHIVED, data -> data);
            case TaskEvent.Reopen() ->
                new NextState<>(TaskState.TODO, data -> data.clearAssignee());
            default ->
                new KeepState<>();
        };

        case ARCHIVED ->
            new KeepState<>();  // Terminal: archived tasks accept no events
    };

    private TaskTransitions() {}
}
```

```java
// taskflow/task/TaskMachine.java
package io.github.seanchatmangpt.taskflow.task;

import io.github.seanchatmangpt.jotp.StateMachine;

public final class TaskMachine {

    public static StateMachine<TaskState, TaskEvent, TaskData> create(TaskData data) {
        return StateMachine.create(TaskState.TODO, data, TaskTransitions.FUNCTION);
    }

    private TaskMachine() {}
}
```

The transition function reads like a specification. Read it aloud: "When the task is TODO and we receive Start, move to IN_PROGRESS and record the assignee." That sentence is the code. The switch expression enforces exhaustiveness at compile time — add a new `TaskState.BLOCKED` and the compiler tells you every event handler that needs updating.

**Consequences**

The domain rules live in one place. The transition function is a pure function and can be unit tested without a Spring context, without a database, and without a running actor. Pass it a state and an event, assert on the returned `Transition`. It is the fastest test in your suite.

The trade-off is discipline. Every mutation of task data must go through the state machine. If a service method reaches directly into `TaskData` to set the assignee field without sending a `Start` event, the state machine is bypassed and the invariants break. Enforce this with package-private constructors and architecture tests.

---

### Pattern: THE RAILWAY (Result<S,F> Error Handling)

**Problem**

Creating a task can fail for many reasons: the board does not exist, the title is blank, the actor's mailbox is full, the database insert fails. In a traditional Spring service you throw exceptions. The caller either catches them or lets them propagate to a `@ExceptionHandler`. The failure path is invisible in the method signature.

**Context**

Actors should not throw exceptions for domain failures. A thrown exception unwinds the call stack and, in an actor context, may cause the process to terminate — triggering a supervisor restart for what was really just a validation error. Validation errors are not crashes. Distinguish between "this input was wrong" (a `Result.failure`) and "the system is in an unexpected state" (a genuine crash worth restarting over).

`Result<S,F>` is a sum type: either a success value of type `S` or a failure value of type `F`. You chain operations on the success path with `.map()`. At the boundary (the REST controller) you unfold it with `.fold(ok -> ..., err -> ...)`. The failure path is visible in every method signature. There are no surprises.

**Solution**

Return `Result<Task, TaskError>` from every operation that can fail for a domain reason. Use `Result.of(supplier)` to wrap operations that may throw checked exceptions (database calls, JSON parsing). Let the actor's handler return the result as part of its reply message.

**Code Example**

```java
// taskflow/task/TaskError.java
package io.github.seanchatmangpt.taskflow.task;

public sealed interface TaskError {
    record BoardNotFound(String boardId)     implements TaskError {}
    record BlankTitle()                      implements TaskError {}
    record IllegalTransition(
        TaskState from, TaskEvent event)     implements TaskError {}
    record PersistenceFailed(String reason)  implements TaskError {}
}
```

```java
// taskflow/board/BoardMsg.java
package io.github.seanchatmangpt.taskflow.board;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.taskflow.task.TaskData;
import io.github.seanchatmangpt.taskflow.task.TaskError;
import io.github.seanchatmangpt.taskflow.task.TaskEvent;

import java.util.concurrent.CompletableFuture;

public sealed interface BoardMsg {

    record CreateTask(
        String title,
        String description,
        CompletableFuture<Result<TaskData, TaskError>> reply
    ) implements BoardMsg {}

    record SendEvent(
        String taskId,
        TaskEvent event,
        CompletableFuture<Result<TaskData, TaskError>> reply
    ) implements BoardMsg {}

    record GetState(
        CompletableFuture<BoardState> reply
    ) implements BoardMsg {}
}
```

```java
// taskflow/board/BoardHandler.java
package io.github.seanchatmangpt.taskflow.board;

import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.taskflow.task.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

public class BoardHandler
        implements BiFunction<BoardState, BoardMsg, BoardState> {

    @Override
    public BoardState apply(BoardState state, BoardMsg msg) {
        return switch (msg) {

            case BoardMsg.CreateTask(var title, var desc, var reply) -> {
                if (title == null || title.isBlank()) {
                    reply.complete(Result.failure(new TaskError.BlankTitle()));
                    yield state;
                }
                var data = new TaskData(
                    UUID.randomUUID().toString(),
                    title,
                    desc,
                    Optional.empty(),
                    Optional.empty(),
                    Instant.now(),
                    Instant.now()
                );
                var machine = TaskMachine.create(data);
                var tasks = new ArrayList<>(state.tasks());
                tasks.add(machine);
                reply.complete(Result.success(data));
                yield new BoardState(state.boardId(), tasks);
            }

            case BoardMsg.SendEvent(var taskId, var event, var reply) -> {
                var found = state.tasks().stream()
                    .filter(m -> m.data().id().equals(taskId))
                    .findFirst();

                if (found.isEmpty()) {
                    reply.complete(Result.failure(
                        new TaskError.IllegalTransition(null, event)));
                    yield state;
                }

                var machine = found.get();
                var transition = TaskTransitions.FUNCTION.apply(
                    machine.currentState(), event);

                var updated = machine.applyTransition(transition);
                var tasks = new ArrayList<>(state.tasks());
                tasks.replaceAll(m ->
                    m.data().id().equals(taskId) ? updated : m);
                reply.complete(Result.success(updated.data()));
                yield new BoardState(state.boardId(), tasks);
            }

            case BoardMsg.GetState(var reply) -> {
                reply.complete(state);
                yield state;
            }
        };
    }
}
```

```java
// taskflow/api/BoardController.java
package io.github.seanchatmangpt.taskflow.api;

import io.github.seanchatmangpt.taskflow.board.BoardMsg;
import io.github.seanchatmangpt.taskflow.board.BoardService;
import io.github.seanchatmangpt.taskflow.task.TaskData;
import io.github.seanchatmangpt.taskflow.task.TaskError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/boards/{boardId}/tasks")
public class BoardController {

    private final BoardService boards;

    public BoardController(BoardService boards) {
        this.boards = boards;
    }

    @PostMapping
    public ResponseEntity<?> createTask(
            @PathVariable String boardId,
            @RequestBody CreateTaskRequest req) {

        var ref = boards.getOrCreateBoard(boardId);
        var reply = new CompletableFuture<
            io.github.seanchatmangpt.jotp.Result<TaskData, TaskError>>();

        ref.tell(new BoardMsg.CreateTask(req.title(), req.description(), reply));

        return reply.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .thenApply(result -> result.fold(
                task -> ResponseEntity.ok(task),
                error -> switch (error) {
                    case TaskError.BlankTitle() ->
                        ResponseEntity.badRequest().body("Title must not be blank");
                    case TaskError.BoardNotFound(var id) ->
                        ResponseEntity.notFound().<Object>build();
                    case TaskError.IllegalTransition(var from, var event) ->
                        ResponseEntity.unprocessableEntity()
                            .body("Cannot apply " + event + " in state " + from);
                    case TaskError.PersistenceFailed(var reason) ->
                        ResponseEntity.internalServerError().body(reason);
                }
            ))
            .join();
    }

    public record CreateTaskRequest(String title, String description) {}
}
```

The controller is a thin boundary. It converts HTTP request to actor message, waits for the reply, and folds the `Result` into an HTTP response. The domain logic — what constitutes a valid task, what transitions are legal — lives entirely in the actor and the state machine. The controller has no `if (task == null)` guards. It has no `try/catch`. The failure cases are exhaustively enumerated at compile time.

**Consequences**

The method signatures tell you everything about failure modes. A reviewer reading `Result<TaskData, TaskError>` knows immediately that this operation can fail and what kinds of failures to expect. The caller cannot accidentally ignore the failure case — `.fold()` requires handling both branches.

The ergonomic cost is the `CompletableFuture` plumbing in the controller. This is the price of asynchronous actors in a synchronous-oriented framework like Spring MVC. If you adopt Spring WebFlux or virtual thread–based Spring MVC (available since Spring Boot 3.2), the plumbing simplifies considerably.

---

### Pattern: PROPERTY AS SPECIFICATION (jqwik)

**Problem**

Example-based tests verify that specific inputs produce specific outputs. But a state machine has a combinatorial input space: states, events, and data fields can be combined in ways that example tests will never cover. A property test lets you say "for any valid input, this invariant must hold" — and lets the framework find the inputs that break it.

**Context**

jqwik is a property-based testing library for Java. It integrates with JUnit 5. You annotate test methods with `@Property` and annotate parameters with `@ForAll`. jqwik generates hundreds of random values, shrinks failures to minimal counterexamples, and reports the exact input that caused the failure. For state machines, properties express the business invariants directly: "a task can be started exactly once," "an archived task accepts no further events," "completing a TODO task without starting it first returns KeepState."

**Solution**

Write properties for the transition function in isolation. The transition function is pure, so properties test it with no setup and no teardown. Then write an integration property that uses Awaitility to assert on the live actor state.

**Code Example**

```java
// test: taskflow/task/TaskTransitionPropertyTest.java
package io.github.seanchatmangpt.taskflow.task;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;
import org.assertj.core.api.WithAssertions;

import java.time.Instant;
import java.util.Optional;

class TaskTransitionPropertyTest implements WithAssertions {

    // ------------------------------------------------------------------ //
    //  A TODO task started with any non-blank assignee reaches IN_PROGRESS
    // ------------------------------------------------------------------ //
    @Property
    void startingATodoTaskAlwaysMovesToInProgress(
            @ForAll @NotBlank String assignee) {

        var transition = TaskTransitions.FUNCTION.apply(
            TaskState.TODO,
            new TaskEvent.Start(assignee)
        );

        assertThat(transition).isInstanceOf(
            io.github.seanchatmangpt.jotp.StateMachine.Transition.NextState.class);

        @SuppressWarnings("unchecked")
        var next = (io.github.seanchatmangpt.jotp.StateMachine.Transition.NextState<
            TaskState, TaskData>) transition;
        assertThat(next.state()).isEqualTo(TaskState.IN_PROGRESS);
    }

    // ------------------------------------------------------------------ //
    //  Completing a TODO task (without starting) is always a no-op
    // ------------------------------------------------------------------ //
    @Property
    void completingATodoTaskIsANoOp(
            @ForAll @NotBlank String note) {

        var transition = TaskTransitions.FUNCTION.apply(
            TaskState.TODO,
            new TaskEvent.Complete(note)
        );

        assertThat(transition).isInstanceOf(
            io.github.seanchatmangpt.jotp.StateMachine.Transition.KeepState.class);
    }

    // ------------------------------------------------------------------ //
    //  An archived task ignores every possible event
    // ------------------------------------------------------------------ //
    @Property
    void archivedTaskIgnoresAllEvents(
            @ForAll("anyTaskEvent") TaskEvent event) {

        var transition = TaskTransitions.FUNCTION.apply(
            TaskState.ARCHIVED,
            event
        );

        assertThat(transition).isInstanceOf(
            io.github.seanchatmangpt.jotp.StateMachine.Transition.KeepState.class);
    }

    @Provide
    Arbitrary<TaskEvent> anyTaskEvent() {
        return Arbitraries.oneOf(
            Arbitraries.strings().alpha().ofMinLength(1)
                .map(TaskEvent.Start::new),
            Arbitraries.strings().alpha().ofMinLength(1)
                .map(TaskEvent.Complete::new),
            Arbitraries.just(new TaskEvent.Archive()),
            Arbitraries.just(new TaskEvent.Reopen())
        );
    }

    // ------------------------------------------------------------------ //
    //  A task started with assignee A records assignee A in its data
    // ------------------------------------------------------------------ //
    @Property
    void startRecordsCorrectAssignee(
            @ForAll @NotBlank String assignee) {

        var data = sampleData("t-1");
        @SuppressWarnings("unchecked")
        var next = (io.github.seanchatmangpt.jotp.StateMachine.Transition.NextState<
            TaskState, TaskData>)
            TaskTransitions.FUNCTION.apply(
                TaskState.TODO, new TaskEvent.Start(assignee));

        var updatedData = next.dataTransform().apply(data);
        assertThat(updatedData.assignee()).contains(assignee);
    }

    private TaskData sampleData(String id) {
        return new TaskData(
            id, "Sample Task", "Description",
            Optional.empty(), Optional.empty(),
            Instant.now(), Instant.now()
        );
    }
}
```

```java
// test: taskflow/board/BoardActorAwaitilityTest.java
package io.github.seanchatmangpt.taskflow.board;

import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Strategy;
import io.github.seanchatmangpt.taskflow.task.TaskError;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

class BoardActorAwaitilityTest implements WithAssertions {

    private BoardService service;

    @BeforeEach
    void setUp() {
        var supervisor = Supervisor.create(
            "test-supervisor",
            Strategy.ONE_FOR_ONE,
            3,
            Duration.ofSeconds(10)
        );
        service = new BoardService(supervisor);
    }

    @Test
    void creatingATaskAppearsInBoardStateWithinOneSecond() {
        var ref = service.getOrCreateBoard("board-1");
        var reply = new CompletableFuture<
            io.github.seanchatmangpt.jotp.Result<
                io.github.seanchatmangpt.taskflow.task.TaskData, TaskError>>();

        ref.tell(new BoardMsg.CreateTask("Ship it", "Deploy to prod", reply));

        // Assert the reply arrives and is a success
        await().atMost(1, TimeUnit.SECONDS).until(reply::isDone);
        assertThat(reply.join().isSuccess()).isTrue();

        // Assert the board state reflects the new task
        var stateReply = new CompletableFuture<BoardState>();
        ref.tell(new BoardMsg.GetState(stateReply));

        await().atMost(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                var state = stateReply.join();
                assertThat(state.tasks()).hasSize(1);
                assertThat(state.tasks().get(0).data().title())
                    .isEqualTo("Ship it");
            });
    }

    @Test
    void blankTitleReturnsFailureResult() {
        var ref = service.getOrCreateBoard("board-2");
        var reply = new CompletableFuture<
            io.github.seanchatmangpt.jotp.Result<
                io.github.seanchatmangpt.taskflow.task.TaskData, TaskError>>();

        ref.tell(new BoardMsg.CreateTask("  ", "Blank title task", reply));

        await().atMost(1, TimeUnit.SECONDS).until(reply::isDone);
        assertThat(reply.join().isFailure()).isTrue();
        assertThat(reply.join().failure()).isInstanceOf(TaskError.BlankTitle.class);
    }
}
```

Run the full suite:

```bash
mvnd test
```

Run only the property tests:

```bash
mvnd test -Dtest=TaskTransitionPropertyTest
```

jqwik runs each `@Property` 1000 times by default. When it finds a failure it shrinks the input — if `assignee = "a very long name that causes a bug"` it will find that `assignee = "a"` reproduces it and report that minimal case. This is substantially more useful than a stack trace from a random failing example.

**Consequences**

Properties express invariants in the same vocabulary as your domain: "starting a task always produces IN_PROGRESS." They survive refactoring — rename `TaskState.IN_PROGRESS` to `TaskState.ACTIVE` and the property still expresses the same intent. Example tests would need updating everywhere.

The cost is the learning curve. Writing good arbitraries takes practice. Start with the simplest properties — "this input shape always produces this output shape" — and add constraint properties as bugs are discovered. Every bug that slips through is a property you did not write.

---

### Packaging with mvnd

Once the tests pass, build the deployable artifact:

```bash
# Full build: compile, test, package
mvnd package

# Skip tests for a fast local package (not for CI)
mvnd package -DskipTests

# Produce a fat JAR for deployment
mvnd package -Pshade
```

The Maven release plugin manages version bumping and tagging. Configure it in `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-release-plugin</artifactId>
    <version>3.1.1</version>
    <configuration>
        <tagNameFormat>v@{project.version}</tagNameFormat>
        <preparationGoals>clean compile test-compile</preparationGoals>
        <goals>deploy</goals>
        <autoVersionSubmodules>true</autoVersionSubmodules>
    </configuration>
</plugin>
```

To cut a release:

```bash
# Bump version, tag, push to SCM, deploy artifact
mvnd release:prepare release:perform
```

The release plugin runs `clean compile test-compile` during preparation. If any test fails, the release is aborted before the tag is created. This is the right behaviour: a version tag in git should always correspond to a green build.

---

### What Have You Learned?

- The **Supervision Tree** is your runtime architecture diagram. Make it explicit in Spring `@Bean` configuration rather than hiding restart logic in annotations scattered across service classes.
- The **State Machine** encodes domain rules as a pure transition function. Sealed `TaskEvent` with pattern-matching `switch` gives you compile-time exhaustiveness on every event.
- The **Railway** pattern (`Result<S,F>`) makes failure first-class. Domain failures are data, not exceptions. The method signature tells you everything about what can go wrong.
- **Property tests** with jqwik express invariants, not examples. Write one property per business rule and let the framework find the counterexamples you would never think to write.
- Awaitility's `await().atMost()` makes async assertions readable and precise. Avoid `Thread.sleep()` in tests — it either waits too long or fails on a slow CI machine.
- `mvnd package` produces the deployable artifact. `mvnd release:prepare release:perform` manages versioning and tagging atomically.

---

## Chapter 4: Dockerize Your JOTP Application

You packaged a JAR. Now you need to run it in a container that behaves identically on your laptop, in CI, and in production. This chapter covers the three patterns that matter when containerising a JOTP application: making the image a true immutable deployment unit, monitoring the actor system from outside so a health probe never accidentally kills what it is checking, and tuning the JVM for the memory constraints of a container.

The application is TaskFlow. The container image we build will be a multi-stage Docker build that compiles with mvnd in stage one and runs on a minimal JRE 26 image in stage two. The health endpoint is backed by a `ProcessMonitor` so it reflects the actual state of the supervision tree, not just whether the HTTP server accepted a TCP connection.

---

### Pattern: THE IMMUTABLE DEPLOYMENT UNIT

**Problem**

A Docker image built on one machine must behave identically on every other machine that runs it. If the image depends on network resources at startup — downloading dependencies, running migrations, fetching configuration — it will behave differently in different environments. The image must be self-contained.

**Context**

Maven (and mvnd) downloads dependencies at build time. If the build runs inside the Docker build context, dependencies are resolved once and baked into the image layer. This is the correct model. But a naive Dockerfile that runs `mvnd package` as a single step rebuilds the entire dependency layer every time a source file changes. Docker layer caching means that only layers *after* the first changed layer are rebuilt. Structure your Dockerfile to copy the `pom.xml` and resolve dependencies before copying source code.

**Solution**

Use a multi-stage Dockerfile. Stage one uses a build image with mvnd and JDK 26. It copies `pom.xml` first, resolves dependencies into a cached layer, then copies source and compiles. Stage two uses a minimal JRE 26 runtime image and copies only the assembled JAR.

**Code Example**

```dockerfile
# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:26-jdk AS builder

# Install mvnd
ARG MVND_VERSION=2.0.0-rc-3
RUN apt-get update -q && apt-get install -y -q curl unzip \
    && curl -fsSL \
       "https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/mvnd-${MVND_VERSION}-linux-amd64.zip" \
       -o /tmp/mvnd.zip \
    && unzip -q /tmp/mvnd.zip -d /opt \
    && ln -s "/opt/mvnd-${MVND_VERSION}-linux-amd64/bin/mvnd" /usr/local/bin/mvnd \
    && rm /tmp/mvnd.zip

WORKDIR /build

# ── Layer 1: Dependency resolution ──────────────────────────────────────────
# Copy only the POM first. This layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvnd dependency:go-offline -q --no-transfer-progress

# ── Layer 2: Compile + test + package ────────────────────────────────────────
# This layer rebuilds whenever any source file changes.
COPY src ./src
RUN mvnd package --no-transfer-progress \
    && ls -lh target/*.jar

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:26-jre AS runtime

LABEL org.opencontainers.image.title="TaskFlow"
LABEL org.opencontainers.image.description="Real-time Kanban board powered by JOTP"
LABEL org.opencontainers.image.version="1.0"

WORKDIR /app

# Non-root user for security
RUN groupadd --system taskflow && useradd --system --gid taskflow taskflow
USER taskflow

# Copy only the fat JAR from the builder stage
COPY --from=builder --chown=taskflow:taskflow \
    /build/target/taskflow-1.0.jar \
    /app/taskflow.jar

# JVM tuning — see THE RESOURCE BUDGET pattern below
ENV JAVA_OPTS="\
    --enable-preview \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active=production"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/taskflow.jar"]
```

```text
# .dockerignore
.git
.claude
target/
*.md
docs/
*.sh
.mvn/
```

Build and run:

```bash
# Build the image
docker build -t taskflow:latest .

# Run with 512 MB memory limit (see THE RESOURCE BUDGET)
docker run \
    --memory=512m \
    --cpus=1 \
    -p 8080:8080 \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/taskflow \
    taskflow:latest
```

Tag and push to a registry:

```bash
docker tag taskflow:latest ghcr.io/seanchatmangpt/taskflow:1.0
docker push ghcr.io/seanchatmangpt/taskflow:1.0
```

**Consequences**

The dependency-resolution layer is cached. On a typical development machine where `pom.xml` rarely changes, a full source rebuild takes the time of `mvnd package` alone — perhaps thirty seconds — rather than the time of `mvnd package` plus downloading a hundred megabytes of JARs. On CI, where the Docker layer cache is warmer than the Maven local repository, the build is faster still.

The non-root user is not optional. Container runtimes in Kubernetes default to running as root unless you configure otherwise, which is a security liability. The `taskflow` user created in the Dockerfile costs nothing and reduces the blast radius of a container compromise.

---

### Pattern: THE HEALTH SENTINEL (ProcessMonitor)

**Problem**

Kubernetes and Docker healthchecks probe your application periodically. If the probe fails, the container is restarted. But if the health check itself crashes the actor it is interrogating — because it calls an internal method that throws an exception, or because it shares state with a fragile component — the probe becomes a source of failures rather than a detector of them.

**Context**

JOTP's `ProcessMonitor` implements the "linked process" pattern from Erlang OTP. You attach a monitor to a `ProcRef`. When the monitored process terminates — for any reason, including normal shutdown and abnormal crash — the monitor receives a `DownReason` value: `Shutdown`, `ExitSignal`, or `Timeout`. The monitor and the monitored process are connected in one direction: if the monitor crashes, the monitored process is unaffected. If the monitored process crashes, the monitor's callback fires.

This makes `ProcessMonitor` the right building block for a health endpoint. The monitor runs in a separate virtual thread. It does not share mutable state with the actors it watches. It cannot accidentally kill what it is checking.

**Solution**

Create a `ProcessMonitor` that watches the root supervisor ref. The monitor maintains a `AtomicBoolean healthy` flag. When a `DownReason` arrives, it flips the flag to false and logs the reason. The Spring Boot Actuator `/health` endpoint calls a `HealthIndicator` that reads the flag.

**Code Example**

```java
// taskflow/health/SupervisorHealthMonitor.java
package io.github.seanchatmangpt.taskflow.health;

import io.github.seanchatmangpt.jotp.DownReason;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.ProcessMonitor;
import io.github.seanchatmangpt.taskflow.board.BoardState;
import io.github.seanchatmangpt.taskflow.board.BoardMsg;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SupervisorHealthMonitor {

    private static final Logger log =
        LoggerFactory.getLogger(SupervisorHealthMonitor.class);

    private final io.github.seanchatmangpt.jotp.Supervisor boardSupervisor;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicReference<String> lastDownReason =
        new AtomicReference<>("none");

    public SupervisorHealthMonitor(
            io.github.seanchatmangpt.jotp.Supervisor boardSupervisor) {
        this.boardSupervisor = boardSupervisor;
    }

    @PostConstruct
    public void startMonitoring() {
        ProcessMonitor.monitor(boardSupervisor.ref(), reason -> {
            switch (reason) {
                case DownReason.Shutdown(var message) -> {
                    log.info("Board supervisor shut down gracefully: {}", message);
                    // Graceful shutdown is not a health failure
                }
                case DownReason.ExitSignal(var cause) -> {
                    log.error("Board supervisor crashed: {}", cause);
                    healthy.set(false);
                    lastDownReason.set("ExitSignal: " + cause);
                }
                case DownReason.Timeout(var duration) -> {
                    log.warn("Board supervisor timed out after {}", duration);
                    healthy.set(false);
                    lastDownReason.set("Timeout after " + duration);
                }
            }
        });
        log.info("ProcessMonitor attached to board supervisor");
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public String lastDownReason() {
        return lastDownReason.get();
    }
}
```

```java
// taskflow/health/BoardSupervisorHealthIndicator.java
package io.github.seanchatmangpt.taskflow.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("boardSupervisor")
public class BoardSupervisorHealthIndicator implements HealthIndicator {

    private final SupervisorHealthMonitor monitor;

    public BoardSupervisorHealthIndicator(SupervisorHealthMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        if (monitor.isHealthy()) {
            return Health.up()
                .withDetail("supervisor", "board-supervisor")
                .withDetail("status", "running")
                .build();
        }
        return Health.down()
            .withDetail("supervisor", "board-supervisor")
            .withDetail("reason", monitor.lastDownReason())
            .build();
    }
}
```

Configure Actuator in `application.properties`:

```properties
# Expose health endpoint with full details
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=always
management.endpoint.health.show-components=always

# The supervisor health indicator participates in the overall status
management.health.boardSupervisor.enabled=true
```

The `/actuator/health` endpoint now returns:

```json
{
  "status": "UP",
  "components": {
    "boardSupervisor": {
      "status": "UP",
      "details": {
        "supervisor": "board-supervisor",
        "status": "running"
      }
    },
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  }
}
```

Configure the Docker healthcheck to use this endpoint:

```dockerfile
# Add to the runtime stage in the Dockerfile
HEALTHCHECK \
    --interval=15s \
    --timeout=5s \
    --start-period=30s \
    --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health | \
        python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d['status']=='UP' else 1)"
```

For Kubernetes, use a separate HTTP liveness and readiness probe rather than the Docker `HEALTHCHECK`:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
```

Add liveness and readiness groups to `application.properties`:

```properties
management.endpoint.health.group.liveness.include=boardSupervisor,ping
management.endpoint.health.group.readiness.include=boardSupervisor,diskSpace
```

**Consequences**

The health probe and the production system are decoupled. The `ProcessMonitor` callback fires asynchronously when the supervisor terminates. The health indicator reads an `AtomicBoolean`. Neither operation can cause a crash that triggers a supervisor restart. If the health check HTTP handler itself panics — say, because of an NPE in the JSON serialiser — the board actors are completely unaffected.

The subtlety is the `Shutdown` case. A graceful shutdown of the supervisor (triggered by `SIGTERM` during a rolling deploy) must not flip `healthy` to `false`. If it did, the readiness probe would fail, Kubernetes would mark the pod as unhealthy during the shutdown window, and the termination would look like a crash in your metrics. Handle `Shutdown` as a no-op or a log-only case.

---

### Pattern: THE RESOURCE BUDGET (Container JVM Tuning)

**Problem**

The JVM was designed for bare-metal servers with gigabytes of heap and dozens of cores. A container is a different environment: it has explicit memory limits and CPU quotas. An untuned JVM inside a 512 MB container will attempt to size its heap based on the host machine's total memory, allocate too much, and be killed by the OOM killer — which looks, from the outside, like a crash.

**Context**

Java 26 is container-aware. It reads `cgroups` limits and adjusts heap sizing accordingly. But the defaults still need tuning for JOTP's virtual thread workload. Virtual threads use carrier threads from `ForkJoinPool.commonPool()`. The pool size defaults to the number of available processors as seen by the JVM — but in a container, this may be fractional CPUs. ZGC with generational mode (`-XX:+ZGenerational`) is the right garbage collector for latency-sensitive JOTP workloads: it runs concurrently, avoids stop-the-world pauses longer than a millisecond, and scales well to the small heaps typical of containers.

**Solution**

Set `-XX:MaxRAMPercentage` rather than `-Xmx`. This tells the JVM to use a percentage of the container's memory limit as its maximum heap. Set it to 75 — leave 25% for the JVM's non-heap memory (Metaspace, code cache, virtual thread stacks, and the OS itself). Enable preview features with `--enable-preview` because Java 26 preview features — including some virtual thread enhancements — are not available by default. Use ZGC with generational mode.

**Code Example**

Set the JVM flags in the Dockerfile `ENV` (shown in the Dockerfile above) and in a `docker-compose.yml` for local development:

```yaml
# docker-compose.yml
version: "3.9"

services:
  taskflow:
    image: taskflow:latest
    build:
      context: .
      target: runtime
    ports:
      - "8080:8080"
    environment:
      JAVA_OPTS: >-
        --enable-preview
        -XX:MaxRAMPercentage=75.0
        -XX:+UseZGC
        -XX:+ZGenerational
        -Djava.security.egd=file:/dev/./urandom
        -Dspring.profiles.active=development
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/taskflow
    deploy:
      resources:
        limits:
          memory: 512m
          cpus: "1.0"
    depends_on:
      db:
        condition: service_healthy
    healthcheck:
      test: >-
        curl -sf http://localhost:8080/actuator/health ||
        exit 1
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 30s

  db:
    image: postgres:16
    environment:
      POSTGRES_DB: taskflow
      POSTGRES_USER: taskflow
      POSTGRES_PASSWORD: taskflow_dev
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U taskflow"]
      interval: 5s
      timeout: 3s
      retries: 5
```

For production Kubernetes, set resource requests and limits in the `Deployment`:

```yaml
# k8s/taskflow-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: taskflow
spec:
  replicas: 2
  selector:
    matchLabels:
      app: taskflow
  template:
    metadata:
      labels:
        app: taskflow
    spec:
      containers:
        - name: taskflow
          image: ghcr.io/seanchatmangpt/taskflow:1.0
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "1000m"
          env:
            - name: JAVA_OPTS
              value: >-
                --enable-preview
                -XX:MaxRAMPercentage=75.0
                -XX:+UseZGC
                -XX:+ZGenerational
                -Djava.security.egd=file:/dev/./urandom
          ports:
            - containerPort: 8080
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 15
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
```

**Understanding the flags in detail:**

`--enable-preview` — Required for Java 26 preview features. Virtual thread enhancements and certain pattern matching refinements are preview in Java 26. The flag must appear on both the compiler invocation (in `pom.xml`, already configured with `<arg>--enable-preview</arg>`) and the runtime invocation.

`-XX:MaxRAMPercentage=75.0` — In a 512 MB container, this allocates approximately 384 MB to the JVM heap. The remaining 128 MB covers Metaspace (typically 50–80 MB for a Spring Boot application), code cache, virtual thread stacks (each virtual thread uses at most a few KB on the carrier thread stack while parked), and the OS page cache.

`-XX:+UseZGC -XX:+ZGenerational` — ZGC is a concurrent garbage collector that moves objects while application threads are running. Stop-the-world pauses are bounded at under one millisecond regardless of heap size. Generational ZGC (available since Java 21, production-ready in Java 23+) separates short-lived and long-lived objects, substantially reducing the volume of work in each collection cycle. For JOTP applications, where virtual threads live for microseconds and board state records live for the session duration, generational GC delivers significantly better throughput.

`-Djava.security.egd=file:/dev/./urandom` — `SecureRandom` seeding on Linux defaults to `/dev/random`, which can block for several seconds in a container with limited entropy (common in CI and in freshly started VMs). The `./urandom` path (note the dot) still uses the urandom pool but bypasses the blocking behaviour of `/dev/random` while satisfying the JCA security provider's path checks.

**Consequences**

`-XX:MaxRAMPercentage` scales correctly as you resize the container. If you change the memory limit from 512 MB to 1 GB, the heap grows to 768 MB without any flag change. This makes the Dockerfile portable across different deployment sizes.

The penalty for misconfigured memory is severe: the OOM killer terminates the container without a Java stack trace. If your container keeps restarting with exit code 137 (killed by signal 9), the first thing to check is whether `MaxRAMPercentage` is set and whether the container memory limit is large enough for your workload.

Virtual thread count does not map to container memory the way traditional thread count does. In a JOTP application, you may have 10,000 virtual threads active simultaneously. Each parked virtual thread uses a few hundred bytes on the heap for its continuation. 10,000 virtual threads ≈ a few megabytes of heap. This is categorically different from 10,000 platform threads (which would require roughly 80 GB of stack space). Size your container for heap and Metaspace, not for thread stacks.

---

### A Note on the Build Cache

The multi-stage Dockerfile's dependency-resolution layer is only useful if Docker reuses it. Docker reuses a layer if the instruction that created it — and every instruction before it — is identical to a previous build. Since the dependency-resolution layer copies only `pom.xml`, it is invalidated only when `pom.xml` changes. In a project where dependencies change infrequently, this means the vast majority of CI builds skip the network download entirely.

If you use GitHub Actions or another CI system with Docker layer caching, configure the cache:

```yaml
# .github/workflows/build.yml (excerpt)
- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v3

- name: Build and push
  uses: docker/build-push-action@v5
  with:
    context: .
    push: true
    tags: ghcr.io/seanchatmangpt/taskflow:${{ github.sha }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

The `type=gha` cache persists Docker layer cache between workflow runs. Combined with the two-layer Dockerfile structure, this reduces a cold CI build from four minutes to under ninety seconds once dependencies are cached.

---

### What Have You Learned?

- The **Immutable Deployment Unit** is a multi-stage Dockerfile with dependency resolution in its own layer. Copy `pom.xml` first, run `mvnd dependency:go-offline`, then copy source. Layer caching turns expensive Maven downloads into a one-time cost.
- The **Health Sentinel** uses `ProcessMonitor` to watch the supervision tree without sharing mutable state with the actors it monitors. Graceful `Shutdown` events must not trigger a health failure — distinguish them from `ExitSignal` and `Timeout`.
- Spring Boot Actuator's `HealthIndicator` interface is the right integration point. Wire it to an `AtomicBoolean` that the `ProcessMonitor` callback sets. The health endpoint is then a non-blocking read of a flag — it cannot cause a crash.
- The **Resource Budget** starts with `-XX:MaxRAMPercentage=75.0`. Forget `-Xmx` in containers — it does not scale with the memory limit. ZGC with `-XX:+ZGenerational` keeps GC pauses below one millisecond and is the right collector for virtual thread workloads.
- `--enable-preview` must appear in both the compiler configuration (already set in `pom.xml`) and the runtime JVM flags. Missing it at runtime produces a `java.lang.UnsupportedClassVersionError` that is confusing until you see it once.
- Exit code 137 means OOM. Exit code 1 means your application crashed. Know the difference before you spend an hour reading application logs for a JVM that was simply given too little memory.
- `.dockerignore` keeps the build context small. Excluding `.git`, `target/`, and documentation directories can reduce the context from hundreds of megabytes to a few kilobytes — which matters when the Docker daemon is remote (as it is in many CI configurations).
