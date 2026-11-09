package dev.swapnil.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the database itself refuses to hold an invalid ledger, independent of the Java code.
 * Runs against real PostgreSQL via Testcontainers; skipped when Docker is not available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SchemaInvariantsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private UUID cash;
    private UUID customer;

    @BeforeEach
    void accounts() {
        cash = insertAccount("ASSET");
        customer = insertAccount("LIABILITY");
    }

    @Test
    void balancedEntryCommits() {
        UUID entry = tx.execute(s -> {
            UUID id = insertEntry();
            insertPosting(id, cash, 10_000);
            insertPosting(id, customer, -10_000);
            return id;
        });

        Long sum = jdbc.queryForObject(
                "SELECT SUM(amount_minor) FROM posting WHERE journal_entry_id = ?", Long.class, entry);
        assertThat(sum).isZero();
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            UUID id = insertEntry();
            insertPosting(id, cash, 10_000);
            insertPosting(id, customer, -9_999);
        })).rootCause().hasMessageContaining("unbalanced");
    }

    @Test
    void postingsAreAppendOnly() {
        UUID entry = tx.execute(s -> {
            UUID id = insertEntry();
            insertPosting(id, cash, 500);
            insertPosting(id, customer, -500);
            return id;
        });

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE posting SET amount_minor = 1 WHERE journal_entry_id = ?", entry))
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        String key = "dup-" + UUID.randomUUID(); // unique per run, so reruns on the same DB don't collide
        tx.executeWithoutResult(s -> balancedEntryWithKey(key));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> balancedEntryWithKey(key)))
                .rootCause().hasMessageContaining("idempotency_key");
    }

    @Test
    void entryWithoutPostingsIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertEntry()))
                .rootCause().hasMessageContaining("at least 2 are required");
    }

    @Test
    void entryWithASinglePostingIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> insertPosting(insertEntry(), cash, 100)))
                .rootCause().hasMessageMatching("(?s).*(has 1 posting|unbalanced).*");
    }

    private void balancedEntryWithKey(String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entry (id, idempotency_key, currency, effective_date) "
                + "VALUES (?, ?, 'INR', CURRENT_DATE)", id, key);
        insertPosting(id, cash, 100);
        insertPosting(id, customer, -100);
    }

    private UUID insertAccount(String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO account (id, code, name, currency, type) VALUES (?, ?, ?, 'INR', ?)",
                id, "ACC-" + id, "test " + type, type);
        return id;
    }

    private UUID insertEntry() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entry (id, idempotency_key, currency, effective_date) "
                + "VALUES (?, ?, 'INR', CURRENT_DATE)", id, "key-" + id);
        return id;
    }

    private void insertPosting(UUID entry, UUID account, long amount) {
        jdbc.update("INSERT INTO posting (id, journal_entry_id, account_id, amount_minor) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), entry, account, amount);
    }
}
