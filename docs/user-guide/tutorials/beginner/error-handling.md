# Error Handling in JOTP

## Learning Objectives

By the end of this tutorial, you will be able to:
- Understand railway-oriented programming with `Result<T,E>`
- Apply the "Let It Crash" philosophy
- Distinguish between process crashes and exceptions
- Implement robust error handling patterns
- Build a fault-tolerant system with proper error handling

## Prerequisites

Before starting this tutorial, you should have:
- Completed [State Management](state-management.md) tutorial
- Understanding of try-catch exception handling
- Familiarity with functional programming concepts
- Knowledge of process message passing

## Table of Contents

1. [Two Error Handling Philosophies](#two-error-handling-philosophies)
2. [Railway-Oriented Programming with Result<T,E>](#railway-oriented-programming-with-resultte)
3. [The Let It Crash Philosophy](#the-let-it-crash-philosophy)
4. [Process Crashes vs Exceptions](#process-crashes-vs-exceptions)
5. [Error Handling Patterns](#error-handling-patterns)
6. [Building a Robust System](#building-a-robust-system)
7. [What You Learned](#what-you-learned)
8. [Next Steps](#next-steps)
9. [Exercise](#exercise)

---

## Two Error Handling Philosophies

JOTP embraces two complementary approaches to error handling:

### 1. Railway-Oriented Programming (Expected Errors)

For **anticipated errors** (validation failures, missing data, business logic errors), use `Result<T,E>`:

```java
Result<User, ValidationError> result = validateUser(data);
if (result.isSuccess()) {
    User user = result.getSuccess();
    // Proceed with user
} else {
    ValidationError error = result.getError();
    // Handle error
}
```

### 2. Let It Crash (Unexpected Errors)

For **unanticipated errors** (bugs, network failures, corruption), let processes crash:

```java
// If this crashes due to unexpected error, supervisor restarts it
Proc<State, Message> proc = Proc.spawn(executor, initialState, handler);
```

---

## Railway-Oriented Programming with Result<T,E>

`Result<T,E>` represents either success (`T`) or failure (`E`). It's like an exception-free `Optional`.

### Result Type

```java
sealed interface Result<T, E> permits Success, Failure {
    record Success<T, E>(T value) implements Result<T, E> {}
    record Failure<T, E>(E error) implements Result<T, E> {}

    boolean isSuccess();
    boolean isFailure();
    T getSuccess();
    E getError();
}
```

### Creating Results

```java
// Success
Result<Integer, String> success = Result.success(42);

// Failure
Result<Integer, String> failure = Result.failure("Invalid input");
```

### Pattern Matching Results

```java
Result<User, ValidationError> result = parseUser(input);

switch (result) {
    case Result.Success(var user) -> {
        System.out.println("Valid user: " + user.name());
    }
    case Result.Failure(var error) -> {
        System.err.println("Error: " + error.message());
    }
}
```

### Chaining Results

Use `map()` and `flatMap()` to chain operations:

```java
Result<Integer, String> result = Result.success(5)
    .map(x -> x * 2)          // Success(10)
    .map(x -> x + 3)          // Success(13)
    .flatMap(x -> validate(x)); // Maybe Success(13) or Failure("too large")

// If any step returns Failure, the chain stops
```

### Combining Results

```java
// Combine multiple results
Result<String, String> name = validateName(nameInput);
Result<Integer, String> age = validateAge(ageInput);
Result<String, String> email = validateEmail(emailInput);

// All must succeed
Result<User, String> user = Result.combine(name, age, email)
    .map(parts -> new User(parts.get(0), parts.get(1), parts.get(2)));
```

### Example: Input Validation

```java
sealed interface ValidationError permits InvalidName, InvalidAge {
    record InvalidName(String message) implements ValidationError {}
    record InvalidAge(String message) implements ValidationError {}
}

Result<String, ValidationError> validateName(String name) {
    if (name == null || name.isBlank()) {
        return Result.failure(new ValidationError.InvalidName("Name cannot be empty"));
    }
    if (name.length() > 50) {
        return Result.failure(new ValidationError.InvalidName("Name too long"));
    }
    return Result.success(name);
}

Result<Integer, ValidationError> validateAge(String ageStr) {
    try {
        int age = Integer.parseInt(ageStr);
        if (age < 0 || age > 150) {
            return Result.failure(new ValidationError.InvalidAge("Invalid age range"));
        }
        return Result.success(age);
    } catch (NumberFormatException e) {
        return Result.failure(new ValidationError.InvalidAge("Age must be a number"));
    }
}

// Usage
Result<String, ValidationError> nameResult = validateName("Alice");
Result<Integer, ValidationError> ageResult = validateAge("25");

if (nameResult.isSuccess() && ageResult.isSuccess()) {
    System.out.println("Valid: " + nameResult.getSuccess() + ", " + ageResult.getSuccess());
} else {
    if (nameResult.isFailure()) {
        System.err.println("Name error: " + nameResult.getError());
    }
    if (ageResult.isFailure()) {
        System.err.println("Age error: " + ageResult.getError());
    }
}
```

---

## The Let It Crash Philosophy

Erlang and JOTP follow the "Let It Crash" philosophy: **don't defend against errors, let them happen and recover**.

### Traditional: Defensive Programming

```java
// Traditional approach: Catch everything
try {
    processOrder(order);
} catch (NullPointerException e) {
    log.error("NPE in processOrder", e);
    // Try to recover...
} catch (IllegalArgumentException e) {
    log.error("Invalid argument", e);
    // Try to recover...
} catch (Exception e) {
    log.error("Unknown error", e);
    // Try to recover...
}
```

**Problems:**
- Complex error handling code
- Hard to predict all error cases
- Recovery code might be buggy
- Errors get swallowed

### JOTP: Let It Crash

```java
// JOTP approach: Let it crash, supervisor restarts
Proc<OrderState, OrderMessage> orderProcessor = Proc.spawn(
    executor,
    new OrderState(),
    (state, msg) -> {
        // If this throws an exception, process crashes
        // Supervisor detects crash and restarts process
        // Process starts fresh with clean state
        return processOrder(state, msg);
    }
);
```

**Benefits:**
- Clean, simple code
- Predictable recovery (restart from initial state)
- No hidden error states
- Bugs are surfaced immediately

### When to Let It Crash

**Let it crash for:**
- Unexpected exceptions (null pointer, index out of bounds)
- Programming errors (logic bugs)
- Corrupted state
- Network failures during critical operations

**Don't let it crash for:**
- Expected business logic errors (invalid input)
- Validation failures
- Recoverable conditions (retry-able network errors)

---

## Process Crashes vs Exceptions

### Exception Handling in Message Handlers

Exceptions in message handlers **crash the process**:

```java
Proc<State, Message> proc = Proc.spawn(
    executor,
    initialState,
    (state, msg) -> {
        switch (msg) {
            case Process(var data) -> {
                // If this throws NullPointerException:
                // 1. Process terminates abruptly
                // 2. Mailbox is discarded
                // 3. Supervisor is notified (if supervised)
                // 4. Supervisor may restart the process
                return processData(data);  // Potential NPE
            }
        }
    }
);
```

### What Happens on Crash?

```
Process crashes (unhandled exception)
    ↓
Process terminates
    ↓
Supervisor notified (via DOWN message)
    ↓
Supervisor decides what to do:
    - Restart process (with fresh state)
    - Stop process
    - Escalate to its supervisor
```

### Crash Example

```java
// Process that will crash
Proc<Integer, Message> crasher = Proc.spawn(
    executor,
    0,
    (state, msg) -> {
        switch (msg) {
            case Crash() -> {
                // This will crash the process
                throw new RuntimeException("Intentional crash!");
            }
            case Get() -> {
                System.out.println("State: " + state);
                return state;
            }
        }
    }
);

crasher.tell(new Crash());  // Process crashes

// This message is lost (process is dead)
crasher.tell(new Get());    // Never delivered
```

### Handling Crashes with Supervisors

```java
// Supervisor will restart crashed processes
Supervisor supervisor = Supervisor.spawn(
    executor,
    Supervisor.Strategy.ONE_FOR_ONE,
    List.of(
        new Supervisor.ChildSpec(
            "crasher",
            () -> Proc.spawn(executor, 0, handler)
        )
    )
);

// When "crasher" crashes, supervisor restarts it
```

---

## Error Handling Patterns

### Pattern 1: Result for Validation

```java
sealed interface CreateUserMessage permits CreateUser {}

sealed interface CreateUserResponse permits UserCreated, UserFailed {
    record UserCreated(User user) implements CreateUserResponse {}
    record UserFailed(ValidationError error) implements CreateUserResponse {}
}

Proc<UserRegistryState, CreateUserMessage> userRegistry = Proc.spawn(
    executor,
    new UserRegistryState(),
    (state, msg) -> {
        switch (msg) {
            case CreateUser(var name, var email, var callback) -> {
                Result<User, ValidationError> result = validateAndCreate(name, email);

                switch (result) {
                    case Result.Success(var user) -> {
                        callback.tell(new UserCreated(user));
                        return state.withNewUser(user);
                    }
                    case Result.Failure(var error) -> {
                        callback.tell(new UserFailed(error));
                        return state;  // No change
                    }
                }
            }
        }
    }
);
```

### Pattern 2: Let It Crash for Bugs

```java
// No validation - let it crash if there's a bug
Proc<CalculatorState, CalcMessage> calculator = Proc.spawn(
    executor,
    new CalculatorState(),
    (state, msg) -> {
        switch (msg) {
            case Divide(var a, var b) -> {
                // If b is 0, this crashes (ArithmeticException)
                // Supervisor restarts process
                return new CalculatorState(a / b);
            }
        }
    }
);

// Alternative: Handle expected errors with Result
Proc<CalculatorState, CalcMessage> safeCalculator = Proc.spawn(
    executor,
    new CalculatorState(),
    (state, msg) -> {
        switch (msg) {
            case Divide(var a, var b) -> {
                if (b == 0) {
                    // Expected error - return error result
                    return new CalculatorState(Result.failure("Division by zero"));
                }
                return new CalculatorState(Result.success(a / b));
            }
        }
    }
);
```

### Pattern 3: Retry with Backoff

```java
record RetryState<T>(
    int attempts,
    T lastResult,
    Optional<Exception> lastError
) {}

Proc<RetryState<String>, RetryMessage> retrier = Proc.spawn(
    executor,
    new RetryState<>(0, "", Optional.empty()),
    (state, msg) -> {
        switch (msg) {
            case Retry(var operation) when state.attempts() < 3 -> {
                try {
                    String result = operation.execute();
                    System.out.println("Success after " + state.attempts() + " attempts");
                    return new RetryState<>(state.attempts() + 1, result, Optional.empty());
                } catch (Exception e) {
                    System.out.println("Attempt " + state.attempts() + " failed: " + e.getMessage());
                    // Exponential backoff
                    Thread.sleep((long) Math.pow(2, state.attempts()) * 100);
                    // Retry by sending message to self
                    // (implementation detail)
                    return new RetryState<>(state.attempts() + 1, "", Optional.of(e));
                }
            }

            case Retry(var _) -> {
                System.out.println("Max retries exceeded");
                return state;
            }
        }
    }
);
```

### Pattern 4: Circuit Breaker

```java
sealed interface CircuitState permits Closed, Open, HalfOpen {}

record CircuitBreakerState(
    CircuitState state,
    int failureCount,
    Instant lastFailureTime,
    int threshold = 5,
    Duration timeout = Duration.ofSeconds(30)
) {}

Proc<CircuitBreakerState, CircuitMessage> breaker = Proc.spawn(
    executor,
    new CircuitBreakerState(new Closed(), 0, Instant.now()),
    (state, msg) -> {
        switch (msg) {
            case Call(var operation, var callback) when state.state() instanceof Closed -> {
                try {
                    Object result = operation.execute();
                    // Reset failure count on success
                    return new CircuitBreakerState(
                        new Closed(),
                        0,
                        state.lastFailureTime()
                    );
                } catch (Exception e) {
                    int newFailures = state.failureCount() + 1;
                    if (newFailures >= state.threshold()) {
                        System.out.println("Circuit breaker opened");
                        return new CircuitBreakerState(
                            new Open(),
                            newFailures,
                            Instant.now()
                        );
                    }
                    return new CircuitBreakerState(
                        new Closed(),
                        newFailures,
                        Instant.now()
                    );
                }
            }

            case Call(var operation, var callback) when state.state() instanceof Open -> {
                if (Instant.now().isAfter(state.lastFailureTime().plus(state.timeout()))) {
                    System.out.println("Circuit breaker half-open");
                    return new CircuitBreakerState(
                        new HalfOpen(),
                        state.failureCount(),
                        state.lastFailureTime()
                    );
                }
                System.out.println("Circuit breaker open - call rejected");
                callback.tell(new CallFailed("Circuit breaker is open"));
                return state;
            }

            default -> {
                return state;
            }
        }
    }
);
```

---

## Building a Robust System

Let's build a fault-tolerant payment processing system demonstrating both error handling approaches.

### Step 1: Define Error Types

```java
package io.github.seanchatmangpt.jotp.examples.tutorial;

import io.github.seanchatmangpt.jotp.Proc;
import java.math.BigDecimal;
import java.util.*;

/**
 * Fault-tolerant payment processing system.
 * Demonstrates Result<T,E> for expected errors and Let It Crash for unexpected errors.
 */
public class PaymentProcessingExample {

    // Expected business errors
    sealed interface PaymentError permits
        InsufficientFunds,
        InvalidAmount,
        AccountNotFound,
        DailyLimitExceeded {

        record InsufficientFunds(BigDecimal available, BigDecimal required)
            implements PaymentError {}

        record InvalidAmount(String message) implements PaymentError {}

        record AccountNotFound(int accountId) implements PaymentError {}

        record DailyLimitExceeded(BigDecimal limit, BigDecimal attempted)
            implements PaymentError {}
    }

    // Result type for operations
    type PaymentResult = Result<PaymentSuccess, PaymentError>;

    record PaymentSuccess(
        int transactionId,
        BigDecimal amount,
        Instant timestamp
    ) {}
```

### Step 2: Validation with Result

```java
    // Validation functions using Result
    static Result<BigDecimal, PaymentError> validateAmount(BigDecimal amount) {
        if (amount == null) {
            return Result.failure(new PaymentError.InvalidAmount("Amount cannot be null"));
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.failure(new PaymentError.InvalidAmount("Amount must be positive"));
        }
        if (amount.compareTo(new BigDecimal("1000000")) > 0) {
            return Result.failure(new PaymentError.InvalidAmount("Amount exceeds maximum"));
        }
        return Result.success(amount);
    }

    static Result<Account, PaymentError> validateAccount(
        Map<Integer, Account> accounts,
        int accountId
    ) {
        Account account = accounts.get(accountId);
        if (account == null) {
            return Result.failure(new PaymentError.AccountNotFound(accountId));
        }
        return Result.success(account);
    }

    static Result<Void, PaymentError> checkSufficientFunds(
        Account account,
        BigDecimal amount
    ) {
        if (account.balance().compareTo(amount) < 0) {
            return Result.failure(
                new PaymentError.InsufficientFunds(account.balance(), amount)
            );
        }
        return Result.success(null);
    }

    static Result<Void, PaymentError> checkDailyLimit(
        Account account,
        BigDecimal amount,
        BigDecimal dailyTotal
    ) {
        BigDecimal newTotal = dailyTotal.add(amount);
        if (newTotal.compareTo(account.dailyLimit()) > 0) {
            return Result.failure(
                new PaymentError.DailyLimitExceeded(account.dailyLimit(), newTotal)
            );
        }
        return Result.success(null);
    }
```

### Step 3: State and Messages

```java
    // Account state
    record Account(
        int id,
        String owner,
        BigDecimal balance,
        BigDecimal dailyLimit
    ) {}

    // Payment processor state
    record PaymentProcessorState(
        Map<Integer, Account> accounts,
        Map<Integer, BigDecimal> dailyTotals,  // accountId -> total spent today
        int nextTransactionId
    ) {
        PaymentProcessorState {
            accounts = Map.copyOf(accounts);
            dailyTotals = Map.copyOf(dailyTotals);
        }

        static PaymentProcessorState initial() {
            return new PaymentProcessorState(
                Map.of(
                    1, new Account(1, "Alice", new BigDecimal("1000.00"), new BigDecimal("500.00")),
                    2, new Account(2, "Bob", new BigDecimal("500.00"), new BigDecimal("300.00")),
                    3, new Account(3, "Charlie", new BigDecimal("2000.00"), new BigDecimal("1000.00"))
                ),
                Map.of(),
                1
            );
        }
    }

    // Messages
    sealed interface PaymentMessage permits
        ProcessPayment,
        CheckBalance,
        ResetDailyTotals {

        record ProcessPayment(
            int fromAccountId,
            BigDecimal amount,
            Proc<PaymentCallbackState, PaymentCallbackMessage> callback
        ) implements PaymentMessage {}

        record CheckBalance(int accountId) implements PaymentMessage {}

        record ResetDailyTotals() implements PaymentMessage {}
    }

    // Callback messages
    sealed interface PaymentCallbackMessage permits
        PaymentSuccess,
        PaymentFailed {

        record PaymentSuccess(int transactionId, BigDecimal amount)
            implements PaymentCallbackMessage {}

        record PaymentFailed(PaymentError error) implements PaymentCallbackMessage {}
    }

    record PaymentCallbackState() {}
```

### Step 4: Message Handler

```java
    private static PaymentProcessorState handlePayment(
        PaymentProcessorState state,
        PaymentMessage msg
    ) {
        switch (msg) {
            case PaymentMessage.ProcessPayment(
                var fromAccountId,
                var amount,
                var callback
            ) -> {
                System.out.println("\n=== Processing Payment ===");
                System.out.println("From: Account " + fromAccountId);
                System.out.println("Amount: $" + amount);

                // Chain validation using Result
                PaymentResult result = validateAmount(amount)
                    .flatMap(validAmount ->
                        validateAccount(state.accounts(), fromAccountId)
                            .flatMap(account ->
                                checkSufficientFunds(account, validAmount)
                                    .flatMap(_ ->
                                        checkDailyLimit(
                                            account,
                                            validAmount,
                                            state.dailyTotals().getOrDefault(fromAccountId, BigDecimal.ZERO)
                                        )
                                    )
                                    .map(_ -> account)
                            )
                    )
                    .map(account -> {
                        // Success - perform payment
                        int transactionId = state.nextTransactionId();
                        BigDecimal newBalance = account.balance().subtract(amount);
                        BigDecimal newDailyTotal = state.dailyTotals()
                            .getOrDefault(fromAccountId, BigDecimal.ZERO)
                            .add(amount);

                        // Update state
                        var updatedAccounts = new HashMap<>(state.accounts());
                        updatedAccounts.put(fromAccountId,
                            new Account(account.id(), account.owner(), newBalance, account.dailyLimit()));

                        var updatedTotals = new HashMap<>(state.dailyTotals());
                        updatedTotals.put(fromAccountId, newDailyTotal);

                        callback.tell(new PaymentCallbackMessage.PaymentSuccess(transactionId, amount));

                        System.out.println("✓ Payment successful!");
                        System.out.println("  Transaction ID: " + transactionId);
                        System.out.println("  New balance: $" + newBalance);

                        return new PaymentProcessorState(
                            Map.copyOf(updatedAccounts),
                            Map.copyOf(updatedTotals),
                            transactionId + 1
                        );
                    });

                // Handle failure
                if (result.isFailure()) {
                    PaymentError error = result.getError();
                    callback.tell(new PaymentCallbackMessage.PaymentFailed(error));
                    System.out.println("✗ Payment failed: " + formatError(error));
                    return state;  // State unchanged
                }

                return (PaymentProcessorState) result.getSuccess();
            }

            case PaymentMessage.CheckBalance(var accountId) -> {
                Account account = state.accounts().get(accountId);
                if (account != null) {
                    BigDecimal dailyTotal = state.dailyTotals().getOrDefault(accountId, BigDecimal.ZERO);
                    System.out.println("\n=== Account " + accountId + " ===");
                    System.out.println("Owner: " + account.owner());
                    System.out.println("Balance: $" + account.balance());
                    System.out.println("Daily spent: $" + dailyTotal + " / $" + account.dailyLimit());
                } else {
                    System.out.println("Account not found: " + accountId);
                }
                return state;
            }

            case PaymentMessage.ResetDailyTotals() -> {
                System.out.println("\n=== Resetting Daily Totals ===");
                return new PaymentProcessorState(
                    state.accounts(),
                    Map.of(),  // Reset all daily totals
                    state.nextTransactionId()
                );
            }
        }
    }

    private static String formatError(PaymentError error) {
        return switch (error) {
            case PaymentError.InsufficientFunds(var avail, var req) ->
                "Insufficient funds (available: $" + avail + ", required: $" + req + ")";
            case PaymentError.InvalidAmount(var msg) -> "Invalid amount: " + msg;
            case PaymentError.AccountNotFound(var id) -> "Account not found: " + id;
            case PaymentError.DailyLimitExceeded(var limit, var attempted) ->
                "Daily limit exceeded (limit: $" + limit + ", attempted: $" + attempted + ")";
        };
    }
```

### Step 5: Callback Process

```java
    private static PaymentCallbackState handleCallback(
        PaymentCallbackState state,
        PaymentCallbackMessage msg
    ) {
        switch (msg) {
            case PaymentCallbackMessage.PaymentSuccess(var txnId, var amount) -> {
                System.out.println("[CALLBACK] Payment successful: TXN" + txnId + " ($" + amount + ")");
                return state;
            }

            case PaymentCallbackMessage.PaymentFailed(var error) -> {
                System.out.println("[CALLBACK] Payment failed: " + formatError(error));
                return state;
            }
        }
    }
```

### Step 6: Main Application

```java
    public static void main(String[] args) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Create payment processor
        Proc<PaymentProcessorState, PaymentMessage> processor = Proc.spawn(
            executor,
            PaymentProcessorState.initial(),
            PaymentProcessingExample::handlePayment
        );

        // Create callback process
        Proc<PaymentCallbackState, PaymentCallbackMessage> callback = Proc.spawn(
            executor,
            new PaymentCallbackState(),
            PaymentProcessingExample::handleCallback
        );

        System.out.println("=== JOTP Payment Processing System ===");

        // Check initial balances
        processor.tell(new PaymentMessage.CheckBalance(1));
        processor.tell(new PaymentMessage.CheckBalance(2));
        Thread.sleep(100);

        // Successful payment
        processor.tell(new PaymentMessage.ProcessPayment(1, new BigDecimal("100.00"), callback));
        Thread.sleep(100);

        // Check balance after payment
        processor.tell(new PaymentMessage.CheckBalance(1));
        Thread.sleep(100);

        // Attempt payment exceeding balance
        processor.tell(new PaymentMessage.ProcessPayment(2, new BigDecimal("600.00"), callback));
        Thread.sleep(100);

        // Attempt payment exceeding daily limit
        processor.tell(new PaymentMessage.ProcessPayment(1, new BigDecimal("450.00"), callback));
        Thread.sleep(100);

        // Another successful payment
        processor.tell(new PaymentMessage.ProcessPayment(3, new BigDecimal("200.00"), callback));
        Thread.sleep(100);

        // Check all balances
        processor.tell(new PaymentMessage.CheckBalance(1));
        processor.tell(new PaymentMessage.CheckBalance(2));
        processor.tell(new PaymentMessage.CheckBalance(3));
        Thread.sleep(100);

        // Reset daily totals
        processor.tell(new PaymentMessage.ResetDailyTotals());
        Thread.sleep(100);

        // Now the large payment should work
        processor.tell(new PaymentMessage.ProcessPayment(1, new BigDecimal("450.00"), callback));
        Thread.sleep(100);

        processor.tell(new PaymentMessage.CheckBalance(1));
        Thread.sleep(100);

        // Cleanup
        Thread.sleep(500);
        executor.shutdown();
    }
}
```

### Expected Output

```
=== JOTP Payment Processing System ===

=== Account 1 ===
Owner: Alice
Balance: $1000.00
Daily spent: $0.00 / $500.00

=== Account 2 ===
Owner: Bob
Balance: $500.00
Daily spent: $0.00 / $300.00

=== Processing Payment ===
From: Account 1
Amount: $100.00
✓ Payment successful!
  Transaction ID: 1
  New balance: $900.00
[CALLBACK] Payment successful: TXN1 ($100.00)

=== Account 1 ===
Owner: Alice
Balance: $900.00
Daily spent: $100.00 / $500.00

=== Processing Payment ===
From: Account 2
Amount: $600.00
✗ Payment failed: Insufficient funds (available: $500.00, required: $600.00)
[CALLBACK] Payment failed: Insufficient funds (available: $500.00, required: $600.00)

=== Processing Payment ===
From: Account 1
Amount: $450.00
✗ Payment failed: Daily limit exceeded (limit: $500.00, attempted: $550.00)
[CALLBACK] Payment failed: Daily limit exceeded (limit: $500.00, attempted: $550.00)

=== Processing Payment ===
From: Account 3
Amount: $200.00
✓ Payment successful!
  Transaction ID: 2
  New balance: $1800.00
[CALLBACK] Payment successful: TXN2 ($200.00)

=== Account 1 ===
Owner: Alice
Balance: $900.00
Daily spent: $100.00 / $500.00

=== Account 2 ===
Owner: Bob
Balance: $500.00
Daily spent: $0.00 / $300.00

=== Account 3 ===
Owner: Charlie
Balance: $1800.00
Daily spent: $200.00 / $1000.00

=== Resetting Daily Totals ===

=== Processing Payment ===
From: Account 1
Amount: $450.00
✓ Payment successful!
  Transaction ID: 3
  New balance: $450.00
[CALLBACK] Payment successful: TXN3 ($450.00)

=== Account 1 ===
Owner: Alice
Balance: $450.00
Daily spent: $450.00 / $500.00
```

---

## What You Learned

In this tutorial, you:
- Learned railway-oriented programming with `Result<T,E>`
- Understood the "Let It Crash" philosophy
- Distinguished between process crashes and exceptions
- Implemented robust error handling patterns
- Built a fault-tolerant payment processing system

**Key Takeaways:**
- **Result<T,E>** for expected errors (validation, business logic)
- **Let It Crash** for unexpected errors (bugs, corruption)
- **Railway-oriented programming** chains operations without exceptions
- **Supervisors** restart crashed processes for fault tolerance
- **Pattern matching** makes error handling elegant and exhaustive
- **State immutability** prevents error propagation

---

## Next Steps

Continue your JOTP journey:
→ [Supervision Trees](../intermediate/supervision.md) - Learn how supervisors restart crashed processes

---

## Exercise

**Task:** Enhance the payment system with:

1. **Transfer between accounts**: Add `Transfer(fromId, toId, amount, callback)` message
2. **Transaction history**: Track all transactions in state (successful and failed)
3. **Replay capability**: Add `GetTransactions(accountId)` message to show history
4. **Idempotency**: Detect and prevent duplicate transaction IDs
5. **Rollback support**: Add ability to rollback a transaction

**Hints:**
- Transfer is two payments (debit from, credit to) - use `flatMap` to chain
- Store transactions: `record Transaction(int id, int accountId, BigDecimal amount, Instant time, boolean success)`
- Use `Map<Integer, Transaction> transactions` in state
- Check `transactions.containsKey(id)` before processing
- Rollback reverses a successful transaction (credit back)

**Expected behavior:**
```java
processor.tell(new Transfer(1, 2, new BigDecimal("50.00"), callback));
// Debit $50 from account 1, credit $50 to account 2

processor.tell(new GetTransactions(1));
// Show all transactions for account 1

processor.tell(new ProcessPayment(1, new BigDecimal("100.00"), callback));
// Transaction ID: 4

processor.tell(new ProcessPayment(1, new BigDecimal("100.00"), callback));
// Duplicate! Return error instead of processing

processor.tell(new Rollback(4));
// Reverse transaction 4
```

<details>
<summary>Click to see solution (partial - transfer logic)</summary>

```java
// Add to message types
record Transfer(
    int fromAccountId,
    int toAccountId,
    BigDecimal amount,
    Proc<PaymentCallbackState, PaymentCallbackMessage> callback
) implements PaymentMessage {}

record GetTransactions(int accountId) implements PaymentMessage {}

record Rollback(int transactionId) implements PaymentMessage {}

// Update state to track transactions
record PaymentProcessorState(
    Map<Integer, Account> accounts,
    Map<Integer, BigDecimal> dailyTotals,
    Map<Integer, Transaction> transactions,  // NEW
    int nextTransactionId
) {
    // ... compact constructor updates
}

record Transaction(
    int id,
    int accountId,
    BigDecimal amount,
    Instant timestamp,
    boolean success,
    Optional<PaymentError> error
) {
    Transaction {
        error = error.orElse(null);
    }

    static Transaction success(
        int id,
        int accountId,
        BigDecimal amount
    ) {
        return new Transaction(
            id, accountId, amount, Instant.now(), true, Optional.empty()
        );
    }

    static Transaction failure(
        int id,
        int accountId,
        BigDecimal amount,
        PaymentError error
    ) {
        return new Transaction(
            id, accountId, amount, Instant.now(), false, Optional.of(error)
        );
    }
}

// Add transfer handler
case PaymentMessage.Transfer(
    var fromId,
    var toId,
    var amount,
    var callback
) -> {
    System.out.println("\n=== Processing Transfer ===");
    System.out.println("From: Account " + fromId);
    System.out.println("To: Account " + toId);
    System.out.println("Amount: $" + amount);

    int txnId = state.nextTransactionId();

    // Validate debit
    var result = validateAmount(amount)
        .flatMap(validAmount ->
            validateAccount(state.accounts(), fromId)
                .flatMap(fromAccount ->
                    validateAccount(state.accounts(), toId)
                        .flatMap(toAccount ->
                            checkSufficientFunds(fromAccount, validAmount)
                                .flatMap(_ ->
                                    checkDailyLimit(
                                        fromAccount,
                                        validAmount,
                                        state.dailyTotals().getOrDefault(fromId, BigDecimal.ZERO)
                                    )
                                )
                        )
                )
        );

    if (result.isFailure()) {
        var error = result.getError();
        var failedTxn = Transaction.failure(txnId, fromId, amount, error);

        var updatedTransactions = new HashMap<>(state.transactions());
        updatedTransactions.put(txnId, failedTxn);

        callback.tell(new PaymentCallbackMessage.PaymentFailed(error));
        System.out.println("✗ Transfer failed: " + formatError(error));

        return new PaymentProcessorState(
            state.accounts(),
            state.dailyTotals(),
            Map.copyOf(updatedTransactions),
            txnId + 1
        );
    }

    // Perform transfer
    var fromAccount = state.accounts().get(fromId);
    var toAccount = state.accounts().get(toId);

    var updatedAccounts = new HashMap<>(state.accounts());
    updatedAccounts.put(fromId,
        new Account(
            fromAccount.id(),
            fromAccount.owner(),
            fromAccount.balance().subtract(amount),
            fromAccount.dailyLimit()
        )
    );
    updatedAccounts.put(toId,
        new Account(
            toAccount.id(),
            toAccount.owner(),
            toAccount.balance().add(amount),
            toAccount.dailyLimit()
        )
    );

    var updatedTotals = new HashMap<>(state.dailyTotals());
    updatedTotals.merge(fromId, amount, BigDecimal::add);

    var successTxn = Transaction.success(txnId, fromId, amount);

    var updatedTransactions = new HashMap<>(state.transactions());
    updatedTransactions.put(txnId, successTxn);

    callback.tell(new PaymentCallbackMessage.PaymentSuccess(txnId, amount));
    System.out.println("✓ Transfer successful!");
    System.out.println("  Transaction ID: " + txnId);

    return new PaymentProcessorState(
        Map.copyOf(updatedAccounts),
        Map.copyOf(updatedTotals),
        Map.copyOf(updatedTransactions),
        txnId + 1
    );
}
```

</details>

---

## Additional Resources

- [Railway-Oriented Programming](https://fsharpforfunandprofit.com/rop/)
- [Result Type Pattern](https://medium.com/@jasonleach/writing-f-p-code-in-java-part-2-the-result-type-89c7e64b2c1c)
- [Let It Crash Philosophy](https://www.erlang.org/doc/design_principles/des_princ.html#id84168)
- [Error Handling Best Practices](https://martinfowler.com/articles/replaceThrowWithNotification.html)
