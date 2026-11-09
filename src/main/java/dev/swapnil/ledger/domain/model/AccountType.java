package dev.swapnil.ledger.domain.model;

/**
 * Standard accounting types. The normal side decides how a raw posting sum is shown as a
 * balance: asset and expense accounts grow with debits, the rest grow with credits.
 */
public enum AccountType {
    ASSET(NormalSide.DEBIT),
    EXPENSE(NormalSide.DEBIT),
    LIABILITY(NormalSide.CREDIT),
    EQUITY(NormalSide.CREDIT),
    INCOME(NormalSide.CREDIT);

    public enum NormalSide { DEBIT, CREDIT }

    private final NormalSide normalSide;

    AccountType(NormalSide normalSide) {
        this.normalSide = normalSide;
    }

    public NormalSide normalSide() {
        return normalSide;
    }

    /** Converts a signed posting sum (+ debit, - credit) into this account's natural balance. */
    public long naturalBalance(long signedSumMinor) {
        return normalSide == NormalSide.DEBIT ? signedSumMinor : Math.negateExact(signedSumMinor);
    }
}
