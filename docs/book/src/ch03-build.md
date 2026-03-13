# Chapter 3: Build and Package the TaskFlow Application

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
            Supervisor.Strategy.ONE_FOR_ONE,
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

Domain models that have explicit state and legal transitions are state machines. Java gives you enums for states and sealed interfaces for events, but it does not give you a transition function with compile-time guarantees. JOTP's `StateMachine<S,E,D>` does. The transition function is a pure `TransitionFn<S,E,D>` — it takes the current state, the incoming event, and the current data, and returns a `Transition` sealed type that tells the machine what to do next. No side effects. No exceptions. Just data.

**Solution**

Model `TaskState` as a sealed interface (or enum — for simple cases an enum is fine). Model `TaskEvent` as a sealed interface so pattern matching is exhaustive. The transition function is a `switch` expression that covers every `(state, event)` pair you care about and returns `Transition.nextState(...)`, `Transition.keepState(...)`, or `Transition.stop(...)`.

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
import io.github.seanchatmangpt.jotp.StateMachine.TransitionFn;

public final class TaskTransitions {

    /** Pure transition function. No I/O. No side effects. */
    public static final TransitionFn<TaskState, TaskEvent, TaskData>
        FUNCTION = (state, event, data) -> switch (state) {

        case TODO -> switch (event) {
            case TaskEvent.Start(var assignee) ->
                Transition.nextState(TaskState.IN_PROGRESS, data.withAssignee(assignee));
            case TaskEvent.Archive() ->
                Transition.stop("archived-from-todo");
            default ->
                Transition.keepState(data);  // Ignore Complete, Reopen in TODO
        };

        case IN_PROGRESS -> switch (event) {
            case TaskEvent.Complete(var note) ->
                Transition.nextState(TaskState.DONE, data.withNote(note));
            default ->
                Transition.keepState(data);  // Cannot re-start an in-progress task
        };

        case DONE -> switch (event) {
            case TaskEvent.Archive() ->
                Transition.nextState(TaskState.ARCHIVED, data);
            case TaskEvent.Reopen() ->
                Transition.nextState(TaskState.TODO, data.clearAssignee());
            default ->
                Transition.keepState(data);
        };

        case ARCHIVED ->
            Transition.keepState(data);  // Terminal: archived tasks accept no events
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

The domain rules live in one place. The transition function is a pure function and can be unit tested without a Spring context, without a database, and without a running actor. Pass it a state, an event, and data, then assert on the returned `Transition`. It is the fastest test in your suite.

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
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
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
                // Send the event synchronously and wait for the updated data
                TaskData updatedData = machine.call(event).join();
                reply.complete(Result.success(updatedData));
                yield state;
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

        var data = sampleData("t-1");
        var transition = TaskTransitions.FUNCTION.apply(
            TaskState.TODO,
            new TaskEvent.Start(assignee),
            data
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

        var data = sampleData("t-2");
        var transition = TaskTransitions.FUNCTION.apply(
            TaskState.TODO,
            new TaskEvent.Complete(note),
            data
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

        var data = sampleData("t-3");
        var transition = TaskTransitions.FUNCTION.apply(
            TaskState.ARCHIVED,
            event,
            data
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

        var data = sampleData("t-4");
        @SuppressWarnings("unchecked")
        var next = (io.github.seanchatmangpt.jotp.StateMachine.Transition.NextState<
            TaskState, TaskData>)
            TaskTransitions.FUNCTION.apply(
                TaskState.TODO, new TaskEvent.Start(assignee), data);

        assertThat(next.data().assignee()).contains(assignee);
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
            Supervisor.Strategy.ONE_FOR_ONE,
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
        assertThat(reply.join().orElse(null)).isNull();
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
