package dev.swapnil.ledger.domain.model;

import java.util.Currency;
import java.util.Objects;

/**
 * @param allowNegative whether the account may go below zero (as an accountant reads the balance).
 *                      False by default: overdraft is opt-in, for accounts such as a bank's own
 *                      funding account or a customer with an approved credit line.
 */
public record Account(AccountId id, String code, String name, Currency currency, AccountType type,
                      boolean allowNegative) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(type, "type");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("account code must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("account name must not be blank");
        }
    }

    public static Account open(String code, String name, Currency currency, AccountType type) {
        return open(code, name, currency, type, false);
    }

    public static Account open(String code, String name, Currency currency, AccountType type, boolean allowNegative) {
        return new Account(AccountId.newId(), code, name, currency, type, allowNegative);
    }

    /** Whether this account may hold {@code signedBalanceMinor} (+ debit, - credit) after a posting. */
    public boolean permits(long signedBalanceMinor) {
        return allowNegative || type.naturalBalance(signedBalanceMinor) >= 0;
    }
}
