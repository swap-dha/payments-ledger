package dev.swapnil.ledger.adapter.out.persistence;

import dev.swapnil.ledger.application.port.out.DuplicateIdempotencyKeyException;
import dev.swapnil.ledger.application.port.out.JournalEntryRepository;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.JournalEntry;
import dev.swapnil.ledger.domain.model.Posting;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcJournalEntryRepository implements JournalEntryRepository {

    private final JdbcClient jdbc;

    JdbcJournalEntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One transaction for the entry and every posting. The database re-checks zero-sum and
     * "at least two postings" at commit (V1, V2), so a bug here cannot store an invalid entry.
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

        for (Posting p : entry.postings()) {
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
