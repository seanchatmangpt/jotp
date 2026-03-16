# State Management in JOTP

## Learning Objectives

By the end of this tutorial, you will be able to:
- Design immutable state using records
- Implement state transition patterns
- Manage complex state with nested records
- Handle state updates in message handlers
- Build a todo list application with state management

## Prerequisites

Before starting this tutorial, you should have:
- Completed [Message Passing](message-passing.md) tutorial
- Understanding of Java records and immutability
- Familiarity with message handlers and pattern matching
- Knowledge of functional programming concepts (map, filter)

## Table of Contents

1. [Why Immutable State?](#why-immutable-state)
2. [State Design with Records](#state-design-with-records)
3. [State Transition Patterns](#state-transition-patterns)
4. [Complex State Management](#complex-state-management)
5. [State Update Best Practices](#state-update-best-practices)
6. [Building a Todo List](#building-a-todo-list)
7. [What You Learned](#what-you-learned)
8. [Next Steps](#next-steps)
9. [Exercise](#exercise)

---

## Why Immutable State?

JOTP processes use **immutable state** - state that never changes after creation. Instead of modifying state, you create new state objects.

### Mutable State (Traditional)

```java
// Traditional approach: Mutable state
class Counter {
    private int count = 0;  // Mutable field

    public void increment() {
        this.count++;  // Modifies state
    }
}
```

**Problems with mutable state:**
- Race conditions in concurrent code
- Hard to reason about (state changes over time)
- Difficult to test (depends on execution order)
- Unexpected side effects

### Immutable State (JOTP)

```java
// JOTP approach: Immutable state
record Counter(int count) {}  // Immutable record

// State transition
Counter oldState = new Counter(0);
Counter newState = new Counter(oldState.count() + 1);  // Create new object
```

**Benefits of immutable state:**
- **Thread-safe**: No race conditions
- **Predictable**: State doesn't change unexpectedly
- **Easy to test**: Same input = same output
- **Time-travel debugging**: Keep history of all states

### Immutability in Message Handlers

```java
Proc<CounterState, Message> counter = Proc.spawn(
    executor,
    new CounterState(0),  // Initial immutable state
    (state, msg) -> {
        switch (msg) {
            case Increment() -> {
                // Return NEW state, don't modify old state
                return new CounterState(state.value() + 1);
            }
        }
    }
);
```

**Key principles:**
1. Never modify state in place
2. Always return new state objects
3. Use records for immutable data
4. Let the garbage collector clean up old state

---

## State Design with Records

Java records are perfect for immutable state:
- Immutable by default
- Compact syntax
- Automatic getters
- Built-in `equals()`, `hashCode()`, `toString()`

### Simple State

```java
// Counter with single value
record CounterState(int value) {}
```

### State with Multiple Fields

```java
// User session state
record SessionState(
    String username,
    int loginCount,
    java.time.Instant lastActivity
) {}
```

### State with Collections

```java
import java.util.List;

// Todo list state
record TodoListState(List<TodoItem> todos) {
    TodoListState {
        // Defensive copy in compact constructor
        todos = List.copyOf(todos);
    }
}

record TodoItem(int id, String title, boolean completed) {}
```

**Important:** Use `List.copyOf()` or `List.of()` for immutable collections:
```java
// Good: Immutable list
record State(List<String> items) {
    State {
        items = List.copyOf(items);  // Creates unmodifiable list
    }
}

// Bad: Mutable list
record State(List<String> items) {}  // Caller can modify list
```

### State with Nested Records

```java
// Address component
record Address(String street, String city, String zipCode) {}

// User state with nested address
record UserState(
    int id,
    String name,
    Address address,
    List<String> preferences
) {
    UserState {
        preferences = List.copyOf(preferences);
    }
}
```

### State with Validation

```java
record CounterState(int value) {
    CounterState {
        if (value < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
    }
}

// Usage
try {
    var state = new CounterState(-1);  // Throws exception
} catch (IllegalArgumentException e) {
    System.err.println("Invalid state: " + e.getMessage());
}
```

---

## State Transition Patterns

State transitions transform old state into new state based on messages.

### Pattern 1: Simple Update

```java
record CounterState(int value) {}

sealed interface CounterMessage permits Increment, Decrement {}

CounterState newState = switch (msg) {
    case Increment() -> new CounterState(state.value() + 1);
    case Decrement() -> new CounterState(state.value() - 1);
};
```

### Pattern 2: Conditional Update

```java
record CounterState(int value) {
    CounterState {
        if (value < 0) value = 0;  // Clamp to minimum
    }
}

CounterState newState = switch (msg) {
    case Increment() -> new CounterState(state.value() + 1);
    case Decrement() -> new CounterState(Math.max(0, state.value() - 1));
};
```

### Pattern 3: Collection Update

```java
record TodoListState(List<TodoItem> todos) {
    TodoListState {
        todos = List.copyOf(todos);
    }
}

sealed interface TodoMessage permits Add, Remove, Toggle {}

TodoState newState = switch (msg) {
    case Add(var title) -> {
        var newTodo = new TodoItem(nextId(), title, false);
        var updatedTodos = new ArrayList<>(state.todos());
        updatedTodos.add(newTodo);
        yield new TodoListState(updatedTodos);
    }

    case Remove(int id) -> {
        var updatedTodos = state.todos().stream()
            .filter(todo -> todo.id() != id)
            .toList();
        yield new TodoListState(updatedTodos);
    }

    case Toggle(int id) -> {
        var updatedTodos = state.todos().stream()
            .map(todo -> todo.id() == id
                ? new TodoItem(todo.id(), todo.title(), !todo.completed())
                : todo)
            .toList();
        yield new TodoListState(updatedTodos);
    }
};
```

### Pattern 4: State Machine Transitions

```java
sealed interface ConnectionState
    permits Disconnected, Connecting, Connected {}

record Disconnected() implements ConnectionState {}
record Connecting(String host, int port) implements ConnectionState {}
record Connected(String host, int port, Instant since) implements ConnectionState {}

sealed interface ConnectionMessage permits Connect, Disconnect, Success, Fail {}

ConnectionState newState = switch (state, msg) {
    case Disconnected(), Connect(var host, var port) ->
        new Connecting(host, port);

    case Connecting(var host, var port), Success() ->
        new Connected(host, port, Instant.now());

    case Connecting(), Fail() ->
        new Disconnected();

    case Connected(), Disconnect() ->
        new Disconnected();

    // Default: no change
    default -> state;
};
```

### Pattern 5: History-Aware State

```java
record StateWithHistory<T>(
    T current,
    List<T> history
) {
    StateWithHistory {
        history = List.copyOf(history);
    }

    StateWithHistory<T> withNewState(T newState) {
        var newHistory = new ArrayList<>(history);
        newHistory.add(current);
        return new StateWithHistory<>(newState, newHistory);
    }
}

// Usage
StateWithHistory<Counter> state = new StateWithHistory<>(
    new Counter(0),
    List.of()
);

state = state.withNewState(new Counter(1));
state = state.withNewState(new Counter(2));
// history: [Counter(0), Counter(1)]
// current: Counter(2)
```

---

## Complex State Management

Real-world applications often need complex state structures.

### Composed State

```java
// Component states
record UserState(String username, boolean active) {}
record SettingsState(String theme, String language) {}
record CacheState(int size, Map<String, String> data) {}

// Composed application state
record AppState(
    UserState user,
    SettingsState settings,
    CacheState cache
) {}
```

### State Builders

For complex state, use builders for convenience:

```java
record AppState(
    String username,
    String email,
    List<String> roles,
    Map<String, String> preferences
) {
    static Builder builder() {
        return new Builder();
    }

    static class Builder {
        private String username;
        private String email;
        private List<String> roles = List.of();
        private Map<String, String> preferences = Map.of();

        Builder username(String username) {
            this.username = username;
            return this;
        }

        Builder email(String email) {
            this.email = email;
            return this;
        }

        Builder roles(List<String> roles) {
            this.roles = List.copyOf(roles);
            return this;
        }

        Builder preferences(Map<String, String> preferences) {
            this.preferences = Map.copyOf(preferences);
            return this;
        }

        AppState build() {
            return new AppState(username, email, roles, preferences);
        }
    }
}

// Usage
AppState state = AppState.builder()
    .username("alice")
    .email("alice@example.com")
    .roles(List.of("user", "admin"))
    .preferences(Map.of("theme", "dark"))
    .build();
```

### State Update Helpers

Create helper methods for common updates:

```java
record TodoListState(List<TodoItem> todos, int nextId) {
    TodoListState {
        todos = List.copyOf(todos);
    }

    // Helper: Add new todo
    TodoListState addTodo(String title) {
        var newTodo = new TodoItem(nextId, title, false);
        var updatedTodos = new ArrayList<>(todos);
        updatedTodos.add(newTodo);
        return new TodoListState(List.copyOf(updatedTodos), nextId + 1);
    }

    // Helper: Remove todo
    TodoListState removeTodo(int id) {
        var updatedTodos = todos.stream()
            .filter(todo -> todo.id() != id)
            .toList();
        return new TodoListState(updatedTodos, nextId);
    }

    // Helper: Toggle todo
    TodoListState toggleTodo(int id) {
        var updatedTodos = todos.stream()
            .map(todo -> todo.id() == id
                ? new TodoItem(todo.id(), todo.title(), !todo.completed())
                : todo)
            .toList();
        return new TodoListState(updatedTodos, nextId);
    }

    // Helper: Find todo
    Optional<TodoItem> findById(int id) {
        return todos.stream()
            .filter(todo -> todo.id() == id)
            .findFirst();
    }
}
```

---

## State Update Best Practices

### 1. Always Return New State

```java
// Good: Return new state
(state, msg) -> {
    return new CounterState(state.value() + 1);
}

// Bad: Modify state in place
(state, msg) -> {
    state.value++;  // Compiler error - records are immutable
    return state;
}
```

### 2. Use Pattern Matching

```java
// Good: Pattern matching
switch (msg) {
    case Add(var item) -> { /* ... */ }
    case Remove(var id) -> { /* ... */ }
}

// Bad: if-else chains
if (msg instanceof Add) {
    Add addMsg = (Add) msg;
    // ...
} else if (msg instanceof Remove) {
    Remove removeMsg = (Remove) msg;
    // ...
}
```

### 3. Validate State Transitions

```java
record BankAccountState(decimal balance) {
    BankAccountState {
        if (balance < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
    }
}

// In handler
case Withdraw(var amount) -> {
    decimal newBalance = state.balance().subtract(amount);
    if (newBalance < 0) {
        System.out.println("Insufficient funds");
        return state;  // Return unchanged state
    }
    return new BankAccountState(newBalance);
};
```

### 4. Use Defensive Copies

```java
// Good: Defensive copy
record State(List<Item> items) {
    State {
        items = List.copyOf(items);  // Creates immutable list
    }
}

// Bad: Exposes internal state
record State(List<Item> items) {}  // Mutable!
```

### 5. Keep State Small

```java
// Good: Focused state
record CounterState(int value) {}

// Bad: God object
record AppState(
    List<User> users,
    List<Product> products,
    List<Order> orders,
    Map<String, Object> cache,
    // ... 50 more fields
) {}
```

### 6. Use Smart Constructors

```java
record CounterState(int value) {
    // Smart constructor with validation
    static CounterState of(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be non-negative");
        }
        return new CounterState(value);
    }

    // Factory for initial state
    static CounterState initial() {
        return new CounterState(0);
    }
}

// Usage
CounterState state = CounterState.initial();
state = CounterState.of(42);  // Validates input
```

---

## Building a Todo List

Let's build a complete todo list application with proper state management.

### Step 1: Define State

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Todo list application demonstrating state management in JOTP.
 */
public class TodoListExample {

    // Immutable todo item
    record TodoItem(
        int id,
        String title,
        String description,
        boolean completed,
        Instant createdAt,
        Optional<Instant> completedAt
    ) {
        TodoItem {
            // Defensive copy for Optional
            completedAt = completedAt.orElse(null);
        }

        // Factory methods
        static TodoItem create(int id, String title, String description) {
            return new TodoItem(
                id,
                title,
                description,
                false,
                Instant.now(),
                Optional.empty()
            );
        }

        TodoItem complete() {
            return new TodoItem(
                id(),
                title(),
                description(),
                true,
                createdAt(),
                Optional.of(Instant.now())
            );
        }

        TodoItem uncomplete() {
            return new TodoItem(
                id(),
                title(),
                description(),
                false,
                createdAt(),
                Optional.empty()
            );
        }
    }

    // Todo list state
    record TodoListState(
        List<TodoItem> todos,
        int nextId,
        Optional<String> filter  // "all", "active", "completed"
    ) {
        TodoListState {
            todos = List.copyOf(todos);
            filter = filter.orElse(null);
        }

        // Initial state
        static TodoListState initial() {
            return new TodoListState(
                List.of(),
                1,
                Optional.of("all")
            );
        }

        // Helper methods
        TodoListState addTodo(String title, String description) {
            var newTodo = TodoItem.create(nextId, title, description);
            var updatedTodos = new ArrayList<>(todos);
            updatedTodos.add(newTodo);
            return new TodoListState(
                List.copyOf(updatedTodos),
                nextId + 1,
                filter
            );
        }

        TodoListState removeTodo(int id) {
            var updatedTodos = todos.stream()
                .filter(todo -> todo.id() != id)
                .toList();
            return new TodoListState(updatedTodos, nextId, filter);
        }

        TodoListState toggleTodo(int id) {
            var updatedTodos = todos.stream()
                .map(todo -> todo.id() == id
                    ? todo.completed() ? todo.uncomplete() : todo.complete()
                    : todo)
                .toList();
            return new TodoListState(updatedTodos, nextId, filter);
        }

        TodoListState setFilter(String filterType) {
            return new TodoListState(todos, nextId, Optional.of(filterType));
        }

        List<TodoItem> filteredTodos() {
            return switch (filter.orElse("all")) {
                case "active" -> todos.stream()
                    .filter(todo -> !todo.completed())
                    .toList();
                case "completed" -> todos.stream()
                    .filter(TodoItem::completed)
                    .toList();
                default -> todos;
            };
        }
    }
```

### Step 2: Define Messages

```java
    // Message types
    sealed interface TodoMessage permits
        Add,
        Remove,
        Toggle,
        ListTodos,
        SetFilter,
        GetStats {

        record Add(String title, String description) implements TodoMessage {}

        record Remove(int id) implements TodoMessage {}

        record Toggle(int id) implements TodoMessage {}

        record ListTodos() implements TodoMessage {}

        record SetFilter(String filter) implements TodoMessage {}  // "all", "active", "completed"

        record GetStats() implements TodoMessage {}
    }
```

### Step 3: Implement Message Handler

```java
    private static TodoListState handleMessage(
        TodoListState state,
        TodoMessage msg
    ) {
        switch (msg) {
            case TodoMessage.Add(var title, var description) -> {
                var newState = state.addTodo(title, description);
                System.out.println("✓ Added: " + title);
                return newState;
            }

            case TodoMessage.Remove(int id) -> {
                var todo = state.todos().stream()
                    .filter(t -> t.id() == id)
                    .findFirst();
                if (todo.isPresent()) {
                    System.out.println("✓ Removed: " + todo.get().title());
                    return state.removeTodo(id);
                } else {
                    System.out.println("✗ Todo not found: " + id);
                    return state;
                }
            }

            case TodoMessage.Toggle(int id) -> {
                var todo = state.todos().stream()
                    .filter(t -> t.id() == id)
                    .findFirst();
                if (todo.isPresent()) {
                    var newState = state.toggleTodo(id);
                    var updatedTodo = newState.todos().stream()
                        .filter(t -> t.id() == id)
                        .findFirst()
                        .get();
                    System.out.println(
                        "✓ " + (updatedTodo.completed() ? "Completed" : "Uncompleted") +
                        ": " + updatedTodo.title()
                    );
                    return newState;
                } else {
                    System.out.println("✗ Todo not found: " + id);
                    return state;
                }
            }

            case TodoMessage.ListTodos() -> {
                System.out.println("\n=== Todo List (" + state.filter().orElse("all") + ") ===");
                var filtered = state.filteredTodos();
                if (filtered.isEmpty()) {
                    System.out.println("No todos");
                } else {
                    filtered.forEach(todo -> {
                        String status = todo.completed() ? "[✓]" : "[ ]";
                        System.out.println(
                            status + " " + todo.id() + ": " + todo.title() +
                            (todo.description().isEmpty() ? "" : " - " + todo.description())
                        );
                    });
                }
                System.out.println();
                return state;
            }

            case TodoMessage.SetFilter(var filter) -> {
                var newState = state.setFilter(filter);
                System.out.println("✓ Filter set to: " + filter);
                return newState;
            }

            case TodoMessage.GetStats() -> {
                long total = state.todos().size();
                long completed = state.todos().stream().filter(TodoItem::completed).count();
                long active = total - completed;
                System.out.println("\n=== Statistics ===");
                System.out.println("Total: " + total);
                System.out.println("Active: " + active);
                System.out.println("Completed: " + completed);
                System.out.println();
                return state;
            }
        }
    }
```

### Step 4: Main Application

```java
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create todo list process
        Proc<TodoListState, TodoMessage> todoList = Proc.spawn(
            executor,
            TodoListState.initial(),
            TodoListExample::handleMessage
        );

        System.out.println("=== JOTP Todo List ===\n");

        // Add todos
        todoList.tell(new TodoMessage.Add(
            "Learn JOTP",
            "Complete the beginner tutorial series"
        ));
        Thread.sleep(50);

        todoList.tell(new TodoMessage.Add(
            "Build a project",
            "Create a real application using JOTP"
        ));
        Thread.sleep(50);

        todoList.tell(new TodoMessage.Add(
            "Read documentation",
            "Study the JOTP book and examples"
        ));
        Thread.sleep(50);

        // List all todos
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Complete a todo
        todoList.tell(new TodoMessage.Toggle(1));
        Thread.sleep(50);

        // Filter active todos
        todoList.tell(new TodoMessage.SetFilter("active"));
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Filter completed todos
        todoList.tell(new TodoMessage.SetFilter("completed"));
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Show all todos again
        todoList.tell(new TodoMessage.SetFilter("all"));
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Show statistics
        todoList.tell(new TodoMessage.GetStats());
        Thread.sleep(50);

        // Remove a todo
        todoList.tell(new TodoMessage.Remove(2));
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Final statistics
        todoList.tell(new TodoMessage.GetStats());
        Thread.sleep(50);

        // Cleanup
        executor.shutdown();
    }
}
```

### Expected Output

```
=== JOTP Todo List ===

✓ Added: Learn JOTP
✓ Added: Build a project
✓ Added: Read documentation

=== Todo List (all) ===
[ ] 1: Learn JOTP - Complete the beginner tutorial series
[ ] 2: Build a project - Create a real application using JOTP
[ ] 3: Read documentation - Study the JOTP book and examples

✓ Completed: Learn JOTP
✓ Filter set to: active

=== Todo List (active) ===
[ ] 2: Build a project - Create a real application using JOTP
[ ] 3: Read documentation - Study the JOTP book and examples

✓ Filter set to: completed

=== Todo List (completed) ===
[✓] 1: Learn JOTP - Complete the beginner tutorial series

✓ Filter set to: all

=== Todo List (all) ===
[ ] 2: Build a project - Create a real application using JOTP
[ ] 3: Read documentation - Study the JOTP book and examples
[✓] 1: Learn JOTP - Complete the beginner tutorial series

=== Statistics ===
Total: 3
Active: 2
Completed: 1

✓ Removed: Build a project

=== Todo List (all) ===
[ ] 3: Read documentation - Study the JOTP book and examples
[✓] 1: Learn JOTP - Complete the beginner tutorial series

=== Statistics ===
Total: 2
Active: 1
Completed: 1
```

---

## What You Learned

In this tutorial, you:
- Understood why immutable state is critical for concurrent systems
- Designed state using records and immutable collections
- Implemented various state transition patterns
- Managed complex state with nested records and helpers
- Built a complete todo list application with proper state management

**Key Takeaways:**
- **Immutability** eliminates race conditions and makes reasoning easier
- **Records** provide compact, immutable state objects
- **State transitions** create new state objects instead of modifying existing ones
- **Helper methods** make state updates more readable and maintainable
- **Defensive copies** prevent accidental mutation of collections
- **Smart constructors** validate state and provide factory methods

---

## Next Steps

Continue your JOTP journey:
→ **[Error Handling](error-handling.md)** - Learn about Result<T,E> and "Let It Crash" philosophy

---

## Exercise

**Task:** Enhance the todo list with:

1. **Priority levels**: Add priority field (HIGH, MEDIUM, LOW) to TodoItem
2. **Due dates**: Add optional due date field to TodoItem
3. **Sort by priority**: Add message `SortByPriority()` that reorders todos
4. **Overdue detection**: Add message `ListOverdue()` that shows todos past due date
5. **Statistics**: Add priority breakdown to stats (how many HIGH/MEDIUM/LOW)

**Hints:**
- Add `enum Priority { HIGH, MEDIUM, LOW }`
- Add `Optional<Instant> dueDate` to TodoItem
- Create helper method `isOverdue()` in TodoItem
- Use `Stream.sorted()` with comparator for sorting
- Group todos by priority: `Collectors.groupingBy()`

**Expected behavior:**
```java
todoList.tell(new Add("Task 1", "Desc", Priority.HIGH, dueDate));
todoList.tell(new Add("Task 2", "Desc", Priority.LOW, Optional.empty()));
todoList.tell(new SortByPriority());
todoList.tell(new ListTodos());
// Output: HIGH priority tasks first, then MEDIUM, then LOW

todoList.tell(new ListOverdue());
// Output: Only overdue tasks

todoList.tell(new GetStats());
// Output: Priority breakdown
```

<details>
<summary>Click to see solution</summary>

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import io.github.seanchatmangpt.jotp.Proc;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class EnhancedTodoList {

    enum Priority { HIGH, MEDIUM, LOW }

    record TodoItem(
        int id,
        String title,
        String description,
        Priority priority,
        boolean completed,
        Instant createdAt,
        Optional<Instant> dueDate,
        Optional<Instant> completedAt
    ) {
        TodoItem {
            dueDate = dueDate.orElse(null);
            completedAt = completedAt.orElse(null);
        }

        static TodoItem create(
            int id,
            String title,
            String description,
            Priority priority,
            Optional<Instant> dueDate
        ) {
            return new TodoItem(
                id, title, description, priority,
                false, Instant.now(), dueDate, Optional.empty()
            );
        }

        boolean isOverdue() {
            return dueDate.isPresent() &&
                   !completed &&
                   dueDate.get().isBefore(Instant.now());
        }

        TodoItem complete() {
            return new TodoItem(
                id, title, description, priority,
                true, createdAt, dueDate, Optional.of(Instant.now())
            );
        }

        TodoItem uncomplete() {
            return new TodoItem(
                id, title, description, priority,
                false, createdAt, dueDate, Optional.empty()
            );
        }
    }

    record TodoListState(
        List<TodoItem> todos,
        int nextId,
        Optional<String> filter,
        boolean sortByPriority
    ) {
        TodoListState {
            todos = List.copyOf(todos);
            filter = filter.orElse(null);
        }

        static TodoListState initial() {
            return new TodoListState(
                List.of(), 1, Optional.of("all"), false
            );
        }

        private List<TodoItem> getSortedTodos() {
            var stream = todos.stream();
            if (sortByPriority) {
                stream = stream.sorted(Comparator
                    .comparing((TodoItem t) -> t.priority())
                    .thenComparing(TodoItem::createdAt)
                );
            }
            return stream.collect(Collectors.toList());
        }

        List<TodoItem> filteredTodos() {
            var filtered = getSortedTodos().stream();
            return switch (filter.orElse("all")) {
                case "active" -> filtered.filter(t -> !t.completed()).toList();
                case "completed" -> filtered.filter(TodoItem::completed).toList();
                case "overdue" -> filtered.filter(TodoItem::isOverdue).toList();
                default -> filtered.toList();
            };
        }

        TodoListState addTodo(
            String title,
            String description,
            Priority priority,
            Optional<Instant> dueDate
        ) {
            var newTodo = TodoItem.create(nextId, title, description, priority, dueDate);
            var updated = new ArrayList<>(todos);
            updated.add(newTodo);
            return new TodoListState(List.copyOf(updated), nextId + 1, filter, sortByPriority);
        }

        TodoListState removeTodo(int id) {
            var updated = todos.stream().filter(t -> t.id() != id).toList();
            return new TodoListState(updated, nextId, filter, sortByPriority);
        }

        TodoListState toggleTodo(int id) {
            var updated = todos.stream()
                .map(t -> t.id() == id
                    ? t.completed() ? t.uncomplete() : t.complete()
                    : t)
                .toList();
            return new TodoListState(updated, nextId, filter, sortByPriority);
        }

        TodoListState setFilter(String f) {
            return new TodoListState(todos, nextId, Optional.of(f), sortByPriority);
        }

        TodoListState setSortByPriority(boolean sort) {
            return new TodoListState(todos, nextId, filter, sort);
        }

        Map<Priority, Long> getPriorityStats() {
            return todos.stream()
                .collect(Collectors.groupingBy(
                    TodoItem::priority,
                    Collectors.counting()
                ));
        }
    }

    sealed interface TodoMessage permits
        Add, Remove, Toggle, ListTodos, SetFilter,
        SortByPriority, ListOverdue, GetStats {

        record Add(
            String title,
            String description,
            Priority priority,
            Optional<Instant> dueDate
        ) implements TodoMessage {}

        record Remove(int id) implements TodoMessage {}
        record Toggle(int id) implements TodoMessage {}
        record ListTodos() implements TodoMessage {}
        record SetFilter(String filter) implements TodoMessage {}
        record SortByPriority() implements TodoMessage {}
        record ListOverdue() implements TodoMessage {}
        record GetStats() implements TodoMessage {}
    }

    private static TodoListState handleMessage(
        TodoListState state,
        TodoMessage msg
    ) {
        switch (msg) {
            case TodoMessage.Add(var title, var desc, var priority, var due) -> {
                var newState = state.addTodo(title, desc, priority, due);
                System.out.println("✓ Added: " + title + " [" + priority + "]");
                return newState;
            }

            case TodoMessage.Remove(int id) -> {
                var todo = state.todos().stream().filter(t -> t.id() == id).findFirst();
                if (todo.isPresent()) {
                    System.out.println("✓ Removed: " + todo.get().title());
                    return state.removeTodo(id);
                }
                System.out.println("✗ Todo not found: " + id);
                return state;
            }

            case TodoMessage.Toggle(int id) -> {
                var todo = state.todos().stream().filter(t -> t.id() == id).findFirst();
                if (todo.isPresent()) {
                    var newState = state.toggleTodo(id);
                    var updated = newState.todos().stream().filter(t -> t.id() == id).findFirst().get();
                    System.out.println("✓ " + (updated.completed() ? "Completed" : "Uncompleted") + ": " + updated.title());
                    return newState;
                }
                System.out.println("✗ Todo not found: " + id);
                return state;
            }

            case TodoMessage.ListTodos() -> {
                System.out.println("\n=== Todo List (" + state.filter().orElse("all") +
                    (state.sortByPriority() ? ", sorted by priority" : "") + ") ===");
                var filtered = state.filteredTodos();
                if (filtered.isEmpty()) {
                    System.out.println("No todos");
                } else {
                    filtered.forEach(todo -> {
                        String status = todo.completed() ? "[✓]" : "[ ]";
                        String priority = "[" + todo.priority() + "]";
                        String due = todo.dueDate()
                            .map(d -> " (due: " + d.atZone(ZoneId.systemDefault()).toLocalDate() + ")")
                            .orElse("");
                        String overdue = todo.isOverdue() ? " ⚠️ OVERDUE" : "";
                        System.out.println(status + " " + priority + " " + todo.id() + ": " + todo.title() + due + overdue);
                    });
                }
                System.out.println();
                return state;
            }

            case TodoMessage.SetFilter(var f) -> {
                var newState = state.setFilter(f);
                System.out.println("✓ Filter set to: " + f);
                return newState;
            }

            case TodoMessage.SortByPriority() -> {
                var newState = state.setSortByPriority(true);
                System.out.println("✓ Sorting enabled by priority");
                return newState;
            }

            case TodoMessage.ListOverdue() -> {
                System.out.println("\n=== Overdue Todos ===");
                var overdue = state.todos().stream()
                    .filter(TodoItem::isOverdue)
                    .toList();
                if (overdue.isEmpty()) {
                    System.out.println("No overdue todos");
                } else {
                    overdue.forEach(todo -> {
                        String due = todo.dueDate()
                            .map(d -> d.atZone(ZoneId.systemDefault()).toLocalDate().toString())
                            .orElse("?");
                        System.out.println("⚠️ " + todo.title() + " (was due: " + due + ")");
                    });
                }
                System.out.println();
                return state;
            }

            case TodoMessage.GetStats() -> {
                long total = state.todos().size();
                long completed = state.todos().stream().filter(TodoItem::completed).count();
                long active = total - completed;
                long overdue = state.todos().stream().filter(TodoItem::isOverdue).count();
                var priorityStats = state.getPriorityStats();

                System.out.println("\n=== Statistics ===");
                System.out.println("Total: " + total);
                System.out.println("Active: " + active);
                System.out.println("Completed: " + completed);
                System.out.println("Overdue: " + overdue);
                System.out.println("By Priority:");
                System.out.println("  HIGH: " + priorityStats.getOrDefault(Priority.HIGH, 0L));
                System.out.println("  MEDIUM: " + priorityStats.getOrDefault(Priority.MEDIUM, 0L));
                System.out.println("  LOW: " + priorityStats.getOrDefault(Priority.LOW, 0L));
                System.out.println();
                return state;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        Proc<TodoListState, TodoMessage> todoList = Proc.spawn(
            executor,
            TodoListState.initial(),
            EnhancedTodoList::handleMessage
        );

        System.out.println("=== Enhanced JOTP Todo List ===\n");

        var now = Instant.now();
        var yesterday = now.minus(1, ChronoUnit.DAYS);
        var tomorrow = now.plus(1, ChronoUnit.DAYS);

        // Add todos with different priorities and due dates
        todoList.tell(new TodoMessage.Add(
            "Fix critical bug",
            "Production is down",
            Priority.HIGH,
            Optional.of(yesterday)  // Overdue!
        ));
        Thread.sleep(50);

        todoList.tell(new TodoMessage.Add(
            "Write documentation",
            "Update README",
            Priority.MEDIUM,
            Optional.of(tomorrow)
        ));
        Thread.sleep(50);

        todoList.tell(new TodoMessage.Add(
            "Code review",
            "Review PR #42",
            Priority.LOW,
            Optional.empty()
        ));
        Thread.sleep(50);

        // Show all todos
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Enable sorting by priority
        todoList.tell(new TodoMessage.SortByPriority());
        todoList.tell(new TodoMessage.ListTodos());
        Thread.sleep(50);

        // Show overdue
        todoList.tell(new TodoMessage.ListOverdue());
        Thread.sleep(50);

        // Complete high-priority task
        todoList.tell(new TodoMessage.Toggle(1));
        Thread.sleep(50);

        // Show stats
        todoList.tell(new TodoMessage.GetStats());
        Thread.sleep(50);

        executor.shutdown();
    }
}
```

</details>

---

## Additional Resources

- [Java Records Documentation](https://openjdk.org/jeps/395)
- [Immutable Collections in Java](https://openjdk.org/jeps/269)
- [Functional Programming in Java](https://docs.oracle.com/en/java/javase/21/language/pattern-matching.html)
- [State Management Best Practices](https://martinfowler.com/articles/refactoring-2nd-ed.html#ReplacingTypeCodeWithClass)
