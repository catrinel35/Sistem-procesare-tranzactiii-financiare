package org.financiar.model;

import java.math.BigDecimal;

public record AccountView(
        String accountId,
        String type,
        String currency,
        BigDecimal balance) {
}