package org.financiar.repository;

import org.financiar.model.Account;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accounts = new HashMap<>();

    @Override
    public Optional<Account> find(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public boolean exists(String accountId) {
        return accounts.containsKey(accountId);
    }

    @Override
    public void add(Account account) {
        accounts.put(account.id(), account);
    }
}
