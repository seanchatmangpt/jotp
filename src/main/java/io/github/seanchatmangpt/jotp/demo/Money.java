package io.github.seanchatmangpt.jotp.demo;

import java.math.BigDecimal;

/**
 * Immutable monetary amount with currency.
 *
 * <p>Example demonstrating precise financial calculations in JOTP applications. Money objects are
 * immutable and use BigDecimal for accurate decimal arithmetic.
 */
public record Money(BigDecimal amount, String currency) {

    /** Compact constructor — validates all inputs. */
    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Amount cannot have more than 2 decimal places");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency cannot be blank");
        }
    }

    /** Creates a new money amount in USD. */
    public static Money usd(double amount) {
        return new Money(BigDecimal.valueOf(amount), "USD");
    }

    /** Creates a new money amount in EUR. */
    public static Money eur(double amount) {
        return new Money(BigDecimal.valueOf(amount), "EUR");
    }

    /** Creates a new money amount with specified currency. */
    public static Money of(double amount, String currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    /** Adds this money amount to another. */
    public Money add(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add different currencies");
        }
        return new Money(amount.add(other.amount), currency);
    }

    /** Multiplies this money amount by a factor. */
    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }
}
