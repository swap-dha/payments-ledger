package dev.swapnil.ledger.domain.model;

import java.util.Currency;
import java.util.Objects;

public record Account(AccountId id, String code, String name, Currency currency, AccountType type) {

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
        return new Account(AccountId.newId(), code, name, currency, type);
    }
}
