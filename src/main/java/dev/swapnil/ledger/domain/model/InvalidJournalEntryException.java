package dev.swapnil.ledger.domain.model;

/** Raised when a journal entry would break a ledger invariant. Never persisted, never partially applied. */
public class InvalidJournalEntryException extends RuntimeException {

    public InvalidJournalEntryException(String message) {
        super(message);
    }
}
