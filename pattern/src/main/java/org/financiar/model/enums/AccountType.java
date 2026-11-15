package org.financiar.model.enums;

import java.math.BigDecimal;
import java.util.Optional;

public enum AccountType {
    CURRENT("10000"),
    SAVINGS("5000"),
    BUSINESS("10000");

    private final BigDecimal dailyLimit;

    AccountType(String dailyLimit) {
        this.dailyLimit = new BigDecimal(dailyLimit);
    }

    public BigDecimal dailyLimit() {
        return dailyLimit;
    }

    public static Optional<AccountType> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (AccountType type : values()) {
            if (type.name().equals(code)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
