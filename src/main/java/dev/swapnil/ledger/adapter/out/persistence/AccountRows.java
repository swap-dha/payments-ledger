package dev.swapnil.ledger.adapter.out.persistence;

import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.AccountType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.UUID;

/** Maps an {@code account} row to the domain object; shared by both repositories. */
final class AccountRows {

    static final String COLUMNS = "id, code, name, currency, type, allow_negative";

    private AccountRows() {
    }

    static Account map(ResultSet rs) throws SQLException {
        return new Account(
                new AccountId(rs.getObject("id", UUID.class)),
                rs.getString("code"),
                rs.getString("name"),
                Currency.getInstance(rs.getString("currency")),
                AccountType.valueOf(rs.getString("type")),
                rs.getBoolean("allow_negative"));
    }
}
