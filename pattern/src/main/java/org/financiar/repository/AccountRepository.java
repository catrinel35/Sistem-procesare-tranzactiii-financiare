package org.financiar.repository;

import org.financiar.model.Account;

import java.util.Optional;

public interface AccountRepository {

    Optional<Account> find(String accountId);

    boolean exists(String accountId);

    void add(Account account);
}
