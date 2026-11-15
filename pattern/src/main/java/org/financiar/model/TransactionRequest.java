package org.financiar.model;

import java.math.BigDecimal;

public record TransactionRequest(
        String requestId,
        String type,
        String from,
        String to,
        BigDecimal amount,
        String currency) {
}
