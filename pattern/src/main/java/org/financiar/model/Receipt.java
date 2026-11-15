package org.financiar.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Receipt(
        String requestId,
        String status,
        BigDecimal amount,
        BigDecimal fee,
        String reason,
        Instant timestamp) {
}
