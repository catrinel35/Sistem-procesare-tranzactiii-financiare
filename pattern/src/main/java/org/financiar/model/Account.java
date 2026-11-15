package org.financiar.model;

import org.financiar.model.enums.AccountType;
import org.financiar.model.enums.Currency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Account {
    private final String id;
    private final AccountType type;
    private final Currency currency;
    private Money balance;
    private final List<TransactionRecord> outgoing = new ArrayList<>();

    public Account(String id, AccountType type, Currency currency, Money initialBalance) {
        this.id = id;
        this.type = type;
        this.currency = currency;
        this.balance = initialBalance;
    }

    public String id() {
        return id;
    }

    public AccountType type() {
        return type;
    }

    public Currency currency() {
        return currency;
    }

    public Money balance() {
        return balance;
    }

    public void credit(Money amount) {
        balance = balance.plus(amount);
    }

    public void debit(Money amount) {
        balance = balance.minus(amount);
    }

    public boolean canCover(Money amount) {
        return !balance.isLessThan(amount);
    }

    public void recordOutgoing(TransactionRecord record) {
        outgoing.add(record);
    }

    public List<TransactionRecord> outgoing() {
        return Collections.unmodifiableList(outgoing);
    }
}