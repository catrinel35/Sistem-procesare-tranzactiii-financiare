package org.financiar.model.enums;


import java.util.Optional;

public enum TransactionType {
    DEPOSIT(false),
    WITHDRAWAL(true),
    TRANSFER(true),
    EXCHANGE(true);

    private final boolean outgoing;

    TransactionType(boolean outgoing) {
        this.outgoing = outgoing;
    }

    public boolean isOutgoing() {
        return outgoing;
    }

    public static Optional<TransactionType> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (TransactionType type : values()) {
            if (type.name().equals(code)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}