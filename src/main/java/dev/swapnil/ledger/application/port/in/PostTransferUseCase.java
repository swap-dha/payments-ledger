package dev.swapnil.ledger.application.port.in;

import dev.swapnil.ledger.domain.model.AccountId;

import java.time.LocalDate;
import java.util.UUID;

/** Inbound port, called by the REST adapter. */
public interface PostTransferUseCase {

    /** Retrying with the same idempotency key returns the original entry with {@code replayed = true}. */
    TransferResult postTransfer(TransferCommand command);

    record TransferCommand(String idempotencyKey, AccountId from, AccountId to,
                           long amountMinor, String currency, LocalDate effectiveDate, String description) {
    }

    /**
     * @param replayed true when no new entry was written because this key was already used for
     *                 the same transfer. Lets the API answer 200 instead of 201 on a retry.
     */
    record TransferResult(UUID entryId, boolean replayed) {
    }
}
