package dev.swapnil.ledger.application.service;

import dev.swapnil.ledger.application.port.in.PostTransferUseCase;
import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.port.out.DuplicateIdempotencyKeyException;
import dev.swapnil.ledger.application.port.out.JournalEntryRepository;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.JournalEntry;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

/**
 * Posts a transfer exactly once per idempotency key.
 * <ul>
 *   <li>New key: validate, then store entry and postings in one transaction.</li>
 *   <li>Key already used for the same transfer (a client retry): return the original entry id.</li>
 *   <li>Key already used for a different transfer: reject. Silently returning the old result
 *       would tell the client money moved when it did not.</li>
 *   <li>Two identical requests racing: the database's unique constraint picks a winner; the
 *       loser reads the winner's entry and returns its id.</li>
 * </ul>
 * Deliberately free of Spring: wiring and transactions live in the adapters and configuration.
 */
public class TransferService implements PostTransferUseCase {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final Clock clock;

    public TransferService(AccountRepository accounts, JournalEntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.clock = clock;
    }

    @Override
    public TransferResult postTransfer(TransferCommand cmd) {
        Currency currency = Currency.getInstance(cmd.currency());
        LocalDate date = cmd.effectiveDate() != null ? cmd.effectiveDate() : LocalDate.now(clock);

        JournalEntry requested = JournalEntry.transfer(cmd.idempotencyKey(), cmd.from(), cmd.to(),
                cmd.amountMinor(), currency, date, cmd.description());

        Optional<JournalEntry> existing = entries.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), requested);
        }

        requireAccountIn(cmd.from(), currency);
        requireAccountIn(cmd.to(), currency);

        try {
            entries.save(requested);
            return new TransferResult(requested.id(), false);
        } catch (DuplicateIdempotencyKeyException raceLost) {
            JournalEntry winner = entries.findByIdempotencyKey(cmd.idempotencyKey())
                    .orElseThrow(() -> raceLost);
            return replay(winner, requested);
        }
    }

    private TransferResult replay(JournalEntry stored, JournalEntry requested) {
        if (!stored.hasSameEffectAs(requested)) {
            throw new IdempotencyConflictException(requested.idempotencyKey());
        }
        return new TransferResult(stored.id(), true);
    }

    private void requireAccountIn(AccountId id, Currency currency) {
        Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (!account.currency().equals(currency)) {
            throw new CurrencyMismatchException(id, account.currency(), currency);
        }
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(AccountId id) {
            super("account not found: " + id);
        }
    }

    public static class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(AccountId id, Currency accountCurrency, Currency requested) {
            super("account " + id + " is in " + accountCurrency + ", transfer is in " + requested);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("idempotency key " + key + " was already used for a different transfer");
        }
    }
}
