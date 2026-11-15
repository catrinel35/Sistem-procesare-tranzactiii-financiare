package org.financiar.model;

import org.financiar.model.enums.Status;
import org.financiar.model.enums.TransactionType;

import java.time.Instant;

public class TransactionRecord {
    private final String requestId;
    private final TransactionType type;
    private final String fromId;
    private final String toId;
    private final Money amount;
    private final Money fee;
    private final Money received;
    private final Instant time;
    private Status status = Status.COMPLETED;

    public TransactionRecord(String requestId, TransactionType type, String fromId, String toId,
                             Money amount, Money fee, Money received, Instant time) {
        this.requestId = requestId;
        this.type = type;
        this.fromId = fromId;
        this.toId = toId;
        this.amount = amount;
        this.fee = fee;
        this.received = received;
        this.time = time;
    }

    public String requestId() {
        return requestId;
    }

    public TransactionType type() {
        return type;
    }

    public String fromId() {
        return fromId;
    }

    public String toId() {
        return toId;
    }

    public Money amount() {
        return amount;
    }

    public Money fee() {
        return fee;
    }

    public Money received() {
        return received;
    }

    public Instant time() {
        return time;
    }

    public Status status() {
        return status;
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
    }
}
