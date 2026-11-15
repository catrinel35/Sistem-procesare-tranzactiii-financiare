package org.financiar.model.enums;

import java.math.BigDecimal;
import java.util.Optional;

public enum Currency {
    RON("1"),
    EUR("5.3"),
    USD("4.6");

    private final BigDecimal rateToRon;

    Currency(String rateToRon) {
        this.rateToRon = new BigDecimal(rateToRon);
    }

    public BigDecimal rateToRon() {
        return rateToRon;
    }

    public static Optional<Currency> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (Currency currency : values()) {
            if (currency.name().equals(code)) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }
}
