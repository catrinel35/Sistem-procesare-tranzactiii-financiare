package org.financiar.model;

import java.time.Instant;

public record HistoryEntry(
        Instant timestamp,
        String requestId,
        String accountId,
        String event,
        String detail) {
}
