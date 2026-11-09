package dev.swapnil.ledger.domain.model;

import java.util.Objects;

/**
 * One leg of a journal entry. Signed amount in minor units: positive is a debit,
 * negative is a credit. Zero-value postings are meaningless and rejected.
 */
public record Posting(AccountId accountId, long amountMinor) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        if (amountMinor == 0) {
            throw new InvalidJournalEntryException("posting amount must not be zero");
        }
    }

    public static Posting debit(AccountId accountId, long amountMinor) {
        requirePositive(amountMinor);
        return new Posting(accountId, amountMinor);
    }

    public static Posting credit(AccountId accountId, long amountMinor) {
        requirePositive(amountMinor);
        return new Posting(accountId, Math.negateExact(amountMinor));
    }

    public boolean isDebit() {
        return amountMinor > 0;
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new InvalidJournalEntryException("amount must be positive, was " + amountMinor);
        }
    }
}
