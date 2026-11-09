package dev.swapnil.ledger.application.port.out;

import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;

import java.util.Optional;

public interface AccountRepository {

    void save(Account account);

    Optional<Account> findById(AccountId id);

    /** Signed sum of all postings (+ debit, - credit) in minor units. */
    long signedPostingSum(AccountId id);
}
