package dev.swapnil.ledger.application.port.out;

/**
 * Thrown by {@link JournalEntryRepository#save} when another request committed an entry with the
 * same idempotency key first. Lets the application layer handle the race without knowing SQL.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String idempotencyKey, Throwable cause) {
        super("idempotency key already used: " + idempotencyKey, cause);
    }
}
