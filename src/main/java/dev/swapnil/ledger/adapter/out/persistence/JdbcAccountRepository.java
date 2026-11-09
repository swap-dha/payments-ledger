package dev.swapnil.ledger.adapter.out.persistence;

import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.port.out.DuplicateAccountCodeException;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.AccountType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAccountRepository implements AccountRepository {

    private final JdbcClient jdbc;

    JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Account account) {
        try {
            insert(account);
        } catch (DuplicateKeyException e) {
            throw new DuplicateAccountCodeException(account.code(), e);
        }
    }

    private void insert(Account account) {
        jdbc.sql("""
                INSERT INTO account (id, code, name, currency, type)
                VALUES (:id, :code, :name, :currency, :type)
                """)
                .param("id", account.id().value())
                .param("code", account.code())
                .param("name", account.name())
                .param("currency", account.currency().getCurrencyCode())
                .param("type", account.type().name())
                .update();
    }

    @Override
    public Optional<Account> findById(AccountId id) {
        return jdbc.sql("SELECT id, code, name, currency, type FROM account WHERE id = :id")
                .param("id", id.value())
                .query((rs, n) -> new Account(
                        new AccountId(rs.getObject("id", UUID.class)),
                        rs.getString("code"),
                        rs.getString("name"),
                        Currency.getInstance(rs.getString("currency")),
                        AccountType.valueOf(rs.getString("type"))))
                .optional();
    }

    @Override
    public long signedPostingSum(AccountId id) {
        Long sum = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM posting WHERE account_id = :id")
                .param("id", id.value())
                .query(Long.class)
                .single();
        return sum;
    }
}
