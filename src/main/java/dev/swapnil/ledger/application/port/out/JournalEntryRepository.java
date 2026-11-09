package dev.swapnil.ledger.application.port.out;

import dev.swapnil.ledger.domain.model.JournalEntry;

import java.util.Optional;

/** Outbound port. Implemented by the persistence adapter; the domain never sees JDBC. */
public interface JournalEntryRepository {

    /**
     * Persists the entry and all its postings atomically: either everything is stored or nothing is.
     *
     * @throws DuplicateIdempotencyKeyException if the key was already used by a committed entry
     */
    void save(JournalEntry entry);

    /** Lookup used to return the original result when a request is retried with the same key. */
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);
}
