package io.github.seanchatmangpt.jotp.dogfood.core;

import io.github.seanchatmangpt.jotp.ApplicationController;
import io.github.seanchatmangpt.jotp.dogfood.core.PatternMatchingPatterns.Payment;
import java.util.Map;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Java 26 pattern matching patterns in {@link PatternMatchingPatterns}.
 *
 * <p>Pattern matching in Java 26 provides: - Exhaustive switch with record destructuring - Guarded
 * patterns (when clause) - instanceof pattern matching - Switch expressions as values - Null-safe
 * switch
 *
 * <p>These features enable concise, type-safe code that's verified at compile time.
 */
@DisplayName("PatternMatchingPatterns - Java 26 Pattern Matching")
class PatternMatchingPatternsTest implements WithAssertions {


    @BeforeEach
    void setUp() {
        ApplicationController.reset();
    }

    @Test
    @DisplayName("describe masks credit card number")
    void describe_creditCard_masksNumber() {
                "Java 26 switch expressions can deconstruct records directly, extracting fields in"
                        + " the case clause. Combined with sealed types, the compiler enforces exhaustive"
                        + " handling — all variants must be covered.");

                new String[][] {
                    {"Feature", "Compile-time verification", "Verbose casting"},
                    {"Traditional Switch", "Exhaustiveness", "Concise destructuring"},
                    {"Pattern Matching Switch", "Manual checking", "Null Safety"},
                    {"Type Safety", "Compiler enforced", "NPE risk"},
                    {"Runtime errors", "Code", "Null case handling"}
                });

                """
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
            """,
                "java");

        var cc = new Payment.CreditCard("1234567890123456", "Alice", 123, 5000);
        var desc = PatternMatchingPatterns.describe(cc);

        assertThat(desc).contains("Credit card");
        assertThat(desc).contains("Alice");
        assertThat(desc).contains("************3456");
        assertThat(desc).doesNotContain("1234567890123456");

                Map.of(
                        "Payment Type",
                        "Credit Card",
                        "Cardholder",
                        "Alice",
                        "Number Display",
                        "Masked (************3456)",
                        "Full Number",
                        "Not exposed (security)"));

                "Record destructuring eliminates the need for getters and casting. The var keyword"
                        + " infers types from the record components, making the code concise yet type-safe.");
    }

    @Test
    @DisplayName("riskLevel uses guarded patterns")
    void riskLevel_creditCardHighLimit_isHigh() {
                "Guarded patterns (when clause) add conditional logic to pattern matching. A case"
                        + " only matches if both the pattern matches AND the when condition is true.");

                """
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
            """,
                "java");

        var cc = new Payment.CreditCard("1234", "Bob", 456, 15_000);
        assertThat(PatternMatchingPatterns.riskLevel(cc)).isEqualTo("HIGH");

        var ccMedium = new Payment.CreditCard("1234", "Bob", 456, 5_000);
        assertThat(PatternMatchingPatterns.riskLevel(ccMedium)).isEqualTo("MEDIUM");

        var ccLow = new Payment.CreditCard("1234", "Bob", 456, 500);
        assertThat(PatternMatchingPatterns.riskLevel(ccLow)).isEqualTo("LOW");

                Map.of(
                        "High Limit Card",
                        "Risk: HIGH",
                        "Medium Limit Card",
                        "Risk: MEDIUM",
                        "Low Limit Card",
                        "Risk: LOW",
                        "Pattern",
                        "Guarded with when clause"));

                "Guarded patterns enable sophisticated business logic without nested if statements."
                        + " The conditions are evaluated in order, so put specific cases before general ones.");
    }

    @Test
    @DisplayName("extractFee uses instanceof pattern matching")
    void extractFee_fromDouble_returnsValue() {
                "Java 26 enhances instanceof with pattern matching: test and cast in one operation."
                        + " No more tedious casting after type checks.");

                new String[][] {
                    {"Operation", "1 line", "Verbose"},
                    {"Traditional instanceof", "Type Safety", "Concise"},
                    {"Pattern Matching instanceof", "Manual casting", "Null Safety"},
                    {"Code Lines", "Automatic extraction", "NPE risk"},
                    {"3-4 (test + cast + assign)", "Readability", "Pattern match fail"}
                });

                """
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
            """,
                "java");

        var result = PatternMatchingPatterns.extractFee(25.5);
        assertThat(result).contains(25.5);

        var resultInt = PatternMatchingPatterns.extractFee(42);
        assertThat(resultInt).contains(42.0);

        var resultString = PatternMatchingPatterns.extractFee("  123.45  ");
        assertThat(resultString).contains(123.45);

                Map.of(
                        "Double Input",
                        "25.5 → Optional(25.5)",
                        "Integer Input",
                        "42 → Optional(42.0)",
                        "String Input",
                        "'  123.45  ' → Optional(123.45)",
                        "Pattern",
                        "Test, cast, and guard in one"));

                "Pattern matching with instanceof supports guards (&& condition) for additional"
                        + " filtering. The pattern variable (d, i, etc.) is only in scope when the pattern"
                        + " matches.");
    }

    @Test
    @DisplayName("processingFee uses switch as expression")
    void processingFee_creditCardHighLimit_lowerRate() {
                "Switch expressions return values, eliminating the need for temporary variables and"
                        + " break statements. They're expressions, not statements.");

                """
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
            """,
                "java");

        var cc = new Payment.CreditCard("1234", "Alice", 123, 10_000);
        var fee = PatternMatchingPatterns.processingFee(cc, 1000);
        assertThat(fee).isEqualTo(25.0); // 2.5% rate

        var ccNormal = new Payment.CreditCard("1234", "Alice", 123, 1000);
        var feeNormal = PatternMatchingPatterns.processingFee(ccNormal, 1000);
        assertThat(feeNormal).isEqualTo(30.0); // 3% rate

        var bt = new Payment.BankTransfer("IBAN", "BIC", "Name");
        var feeBT = PatternMatchingPatterns.processingFee(bt, 1000);
        assertThat(feeBT).isEqualTo(1.0); // 0.1% rate

                Map.of(
                        "High-Limit Card Fee",
                        "2.5% ($25 on $1000)",
                        "Normal Card Fee",
                        "3% ($30 on $1000)",
                        "Bank Transfer Fee",
                        "0.1% ($1 on $1000)",
                        "Expression",
                        "Returns value directly"));

                "Switch expressions must be exhaustive — the compiler ensures all cases are covered."
                        + " This prevents missing case bugs that plagued traditional switch statements.");
    }

    @Test
    @DisplayName("route handles null payment")
    void route_nullPayment_rejected() {
                "Switch expressions in Java 26 handle null explicitly with a separate null case. No"
                        + " more NullPointerException at runtime — the null handling is codified in the"
                        + " switch.");

                """
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
            """,
                "java");

        assertThat(PatternMatchingPatterns.route(null)).isEqualTo("rejected: null payment");

        var cc = new Payment.CreditCard("1234", "Alice", 123, 1000);
        assertThat(PatternMatchingPatterns.route(cc)).isEqualTo("card-processor");

        var bt = new Payment.BankTransfer("IBAN", "BIC", "Name");
        assertThat(PatternMatchingPatterns.route(bt)).isEqualTo("sepa-gateway");

                Map.of(
                        "Null Payment",
                        "Routed to: rejected",
                        "Credit Card",
                        "Routed to: card-processor",
                        "Bank Transfer",
                        "Routed to: sepa-gateway",
                        "Null Safety",
                        "Explicit null case"));

                "The null case must come first (or compiler warns). This explicit null handling makes"
                        + " the API contract clear — null inputs are rejected with a specific error message.");
    }
}
