package org.financiar.model;

import org.financiar.model.enums.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Conversie la cursurile fixe din Currency (prin RON), rotunjită la 2 zecimale. */
    public Money convertTo(Currency target) {
        if (target == currency) {
            return this;
        }
        BigDecimal converted = amount.multiply(currency.rateToRon()).divide(target.rateToRon(), 2, RoundingMode.HALF_UP);
        return new Money(converted, target);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (currency != other.currency) {
            throw new IllegalArgumentException("Monede diferite: " + currency + " și " + other.currency);
        }
    }
}
