package org.financiar.model;

import java.math.BigDecimal;

public record OpenAccountRequest(
        String accountId,
        String type,
        String currency,
        BigDecimal initialBalance,
        String owner) {
}
