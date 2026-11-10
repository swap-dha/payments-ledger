package dev.swapnil.ledger.application.port.in;

import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.AccountType;

/** Inbound ports for account management, called by the REST adapter. */
public interface AccountUseCases {

    Account openAccount(OpenAccountCommand command);

    Balance balanceOf(AccountId id);

    record OpenAccountCommand(String code, String name, String currency, AccountType type, boolean allowNegative) {
    }

    /**
     * @param signedMinor  raw sum of postings: + debit, - credit
     * @param naturalMinor the balance as an accountant reads it (positive for a normal balance)
     */
    record Balance(Account account, long signedMinor, long naturalMinor) {
    }
}
