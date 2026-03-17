# io.github.seanchatmangpt.jotp.dogfood.core.PatternMatchingPatternsTest

## Table of Contents

- [Exhaustive Switch with Record Destructuring](#exhaustiveswitchwithrecorddestructuring)
- [Guarded Patterns with When Clauses](#guardedpatternswithwhenclauses)
- [Switch Expressions as Values](#switchexpressionsasvalues)
- [instanceof Pattern Matching](#instanceofpatternmatching)
- [Null-Safe Switch with Null Case](#nullsafeswitchwithnullcase)


## Exhaustive Switch with Record Destructuring

Java 26 switch expressions can deconstruct records directly, extracting fields in the case clause. Combined with sealed types, the compiler enforces exhaustive handling — all variants must be covered.

| Feature | Compile-time verification | Verbose casting |
| --- | --- | --- |
| Traditional Switch | Exhaustiveness | Concise destructuring |
| Pattern Matching Switch | Manual checking | Null Safety |
| Type Safety | Compiler enforced | NPE risk |
| Runtime errors | Code | Null case handling |

```java
// Sealed hierarchy ensures exhaustive switch
public sealed interface Payment permits
    Payment.CreditCard, Payment.BankTransfer, Payment.CryptoPay, Payment.Voucher {}

// Record destructuring in switch
String describe(Payment payment) {
    return switch (payment) {
        case CreditCard(var number, var holder, var cvv, var limit) ->
            "Credit card ending in " + number.substring(number.length() - 4);
        case BankTransfer(var iban, var bic, var account) ->
            "Bank transfer: " + iban;
        // ... other cases
    };
}
```

| Key | Value |
| --- | --- |
| `Cardholder` | `Alice` |
| `Payment Type` | `Credit Card` |
| `Number Display` | `Masked (************3456)` |
| `Full Number` | `Not exposed (security)` |

> [!NOTE]
> Record destructuring eliminates the need for getters and casting. The var keyword infers types from the record components, making the code concise yet type-safe.

## Guarded Patterns with When Clauses

Guarded patterns (when clause) add conditional logic to pattern matching. A case only matches if both the pattern matches AND the when condition is true.

```java
// Guarded pattern: only matches high-limit cards
String riskLevel(Payment payment) {
    return switch (payment) {
        case CreditCard(var number, var holder, var cvv, var limit)
            when limit > 10_000 -> "HIGH";
        case CreditCard(var number, var holder, var cvv, var limit)
            when limit > 1_000 -> "MEDIUM";
        case CreditCard(_, _, _, _) -> "LOW";
        // ... other payment types
    };
}
```

| Key | Value |
| --- | --- |
| `Low Limit Card` | `Risk: LOW` |
| `Medium Limit Card` | `Risk: MEDIUM` |
| `High Limit Card` | `Risk: HIGH` |
| `Pattern` | `Guarded with when clause` |

> [!NOTE]
> Guarded patterns enable sophisticated business logic without nested if statements. The conditions are evaluated in order, so put specific cases before general ones.

## Switch Expressions as Values

Switch expressions return values, eliminating the need for temporary variables and break statements. They're expressions, not statements.

```java
// Switch expression returns a value
double processingFee(Payment payment, double amount) {
    return switch (payment) {
        case CreditCard(_, _, _, var limit) when limit > 10_000 ->
            amount * 0.025;  // 2.5% for high-limit cards
        case CreditCard(_, _, _, _) ->
            amount * 0.030;  // 3% for normal cards
        case BankTransfer(_, _, _) ->
            amount * 0.001;  // 0.1% for bank transfer
        // ... other cases
    };
}
```

| Key | Value |
| --- | --- |
| `Expression` | `Returns value directly` |
| `Normal Card Fee` | `3% ($30 on $1000)` |
| `High-Limit Card Fee` | `2.5% ($25 on $1000)` |
| `Bank Transfer Fee` | `0.1% ($1 on $1000)` |

> [!NOTE]
> Switch expressions must be exhaustive — the compiler ensures all cases are covered. This prevents missing case bugs that plagued traditional switch statements.

## instanceof Pattern Matching

Java 26 enhances instanceof with pattern matching: test and cast in one operation. No more tedious casting after type checks.

| Operation | 1 line | Verbose |
| --- | --- | --- |
| Traditional instanceof | Type Safety | Concise |
| Pattern Matching instanceof | Manual casting | Null Safety |
| Code Lines | Automatic extraction | NPE risk |
| 3-4 (test + cast + assign) | Readability | Pattern match fail |

```java
// Old way: verbose and error-prone
if (obj instanceof Double) {
    Double d = (Double) obj;  // manual cast
    return Optional.of(d);
} else if (obj instanceof Integer) {
    Integer i = (Integer) obj;
    return Optional.of(i.doubleValue());
}

// New way: pattern matching
if (obj instanceof Double d && d > 0) {
    return Optional.of(d);  // d is already extracted
} else if (obj instanceof Integer i) {
    return Optional.of(i.doubleValue());
}
```

| Key | Value |
| --- | --- |
| `Pattern` | `Test, cast, and guard in one` |
| `Integer Input` | `42 → Optional(42.0)` |
| `String Input` | `'  123.45  ' → Optional(123.45)` |
| `Double Input` | `25.5 → Optional(25.5)` |

> [!NOTE]
> Pattern matching with instanceof supports guards (&& condition) for additional filtering. The pattern variable (d, i, etc.) is only in scope when the pattern matches.

## Null-Safe Switch with Null Case

Switch expressions in Java 26 handle null explicitly with a separate null case. No more NullPointerException at runtime — the null handling is codified in the switch.

```java
// Null-safe switch
String route(Payment payment) {
    return switch (payment) {
        case null -> "rejected: null payment";
        case CreditCard(_, _, _, _) -> "card-processor";
        case BankTransfer(_, _, _) -> "sepa-gateway";
        case CryptoPay(_, _, _) -> "crypto-exchange";
        case Voucher(_, _, _) -> "voucher-service";
    };
}
```

| Key | Value |
| --- | --- |
| `Bank Transfer` | `Routed to: sepa-gateway` |
| `Credit Card` | `Routed to: card-processor` |
| `Null Payment` | `Routed to: rejected` |
| `Null Safety` | `Explicit null case` |

> [!NOTE]
> The null case must come first (or compiler warns). This explicit null handling makes the API contract clear — null inputs are rejected with a specific error message.

---
*Generated by [DTR](http://www.dtr.org)*
