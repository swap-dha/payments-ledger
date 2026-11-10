package dev.swapnil.ledger.adapter.out.persistence;

import dev.swapnil.ledger.application.port.out.DuplicateIdempotencyKeyException;
import dev.swapnil.ledger.application.port.out.InsufficientFundsException;
import dev.swapnil.ledger.application.port.out.JournalEntryRepository;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.JournalEntry;
import dev.swapnil.ledger.domain.model.Posting;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcJournalEntryRepository implements JournalEntryRepository {

    private final JdbcClient jdbc;

    JdbcJournalEntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One transaction for the entry and every posting. Order matters:
     * <ol>
     *   <li>Insert the entry first. A concurrent request with the same idempotency key blocks on the
     *       unique index and then fails with a duplicate key, so a retry is recognised as a retry
     *       before any balance is checked. (Checking funds first would make the loser of a retry race
     *       see the winner's debit and report "insufficient funds" for money that did move.)</li>
     *   <li>Lock every affected account with {@code SELECT ... FOR UPDATE}, always in account-id
     *       order, so transfers A&rarr;B and B&rarr;A running at once cannot deadlock.</li>
     *   <li>Check each projected balance against the account's overdraft rule while holding the lock.</li>
     *   <li>Insert the postings. The V3 trigger updates the cached balance and re-checks the rule;
     *       V1 and V2 re-check zero-sum and "at least two postings" at commit.</li>
     * </ol>
     */
    @Override
    @Transactional
    public void save(JournalEntry entry) {
        try {
            jdbc.sql("""
                    INSERT INTO journal_entry (id, idempotency_key, description, currency, effective_date)
                    VALUES (:id, :key, :description, :currency, :effectiveDate)
                    """)
                    .param("id", entry.id())
                    .param("key", entry.idempotencyKey())
                    .param("description", entry.description())
                    .param("currency", entry.currency().getCurrencyCode())
                    .param("effectiveDate", entry.effectiveDate())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(entry.idempotencyKey(), e);
        }

        Map<AccountId, LockedAccount> locked = lockInIdOrder(entry.postings());

        List<Posting> ordered = entry.postings().stream()
                .sorted(Comparator.comparing(p -> p.accountId().value()))
                .toList();
        for (Posting p : ordered) {
            LockedAccount acc = locked.get(p.accountId());
            if (acc == null) {
                throw new IllegalStateException("account " + p.accountId() + " does not exist");
            }
            long after = Math.addExact(acc.signedBalance(), p.amountMinor());
            if (!acc.account().permits(after)) {
                throw new InsufficientFundsException(acc.account().code(),
                        acc.account().type().naturalBalance(acc.signedBalance()), Math.abs(p.amountMinor()));
            }
        }

        for (Posting p : ordered) {
            jdbc.sql("""
                    INSERT INTO posting (id, journal_entry_id, account_id, amount_minor)
                    VALUES (:id, :entryId, :accountId, :amount)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("entryId", entry.id())
                    .param("accountId", p.accountId().value())
                    .param("amount", p.amountMinor())
                    .update();
        }
    }

    private Map<AccountId, LockedAccount> lockInIdOrder(List<Posting> postings) {
        List<UUID> ids = postings.stream().map(p -> p.accountId().value()).sorted().toList();
        Map<AccountId, LockedAccount> locked = new HashMap<>();
        jdbc.sql("""
                        SELECT id, code, name, currency, type, allow_negative, balance_minor
                        FROM account WHERE id IN (:ids)
                        ORDER BY id
                        FOR UPDATE
                        """)
                .param("ids", ids)
                .query((rs, n) -> new LockedAccount(AccountRows.map(rs), rs.getLong("balance_minor")))
                .list()
                .forEach(a -> locked.put(a.account().id(), a));
        return locked;
    }

    private record LockedAccount(Account account, long signedBalance) {
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("""
                        SELECT id, idempotency_key, description, currency, effective_date
                        FROM journal_entry WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, n) -> new EntryRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("description"),
                        Currency.getInstance(rs.getString("currency")),
                        rs.getObject("effective_date", LocalDate.class)))
                .optional()
                .map(row -> JournalEntry.rehydrate(row.id(), row.key(), row.currency(), row.effectiveDate(),
                        row.description(), postingsOf(row.id())));
    }

    private List<Posting> postingsOf(UUID entryId) {
        return jdbc.sql("""
                        SELECT account_id, amount_minor FROM posting
                        WHERE journal_entry_id = :entryId ORDER BY account_id
                        """)
                .param("entryId", entryId)
                .query((rs, n) -> new Posting(
                        new AccountId(rs.getObject("account_id", UUID.class)),
                        rs.getLong("amount_minor")))
                .list();
    }

    private record EntryRow(UUID id, String key, String description, Currency currency, LocalDate effectiveDate) {
    }
}
