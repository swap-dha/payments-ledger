package dev.swapnil.ledger.application.port.in;

import dev.swapnil.ledger.domain.model.AccountId;

import java.time.LocalDate;
import java.util.UUID;

/** Inbound port, called by the REST adapter (Days 5-6). */
public interface PostTransferUseCase {

    /** Returns the journal entry id. Retrying with the same idempotency key returns the same id. */
    UUID postTransfer(TransferCommand command);

    record TransferCommand(String idempotencyKey, AccountId from, AccountId to,
                           long amountMinor, String currency, LocalDate effectiveDate, String description) {
    }
}
