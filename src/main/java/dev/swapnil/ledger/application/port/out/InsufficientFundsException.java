package dev.swapnil.ledger.application.port.out;

/** Thrown by {@link JournalEntryRepository#save} when a posting would overdraw an account that forbids it. */
public class InsufficientFundsException extends RuntimeException {

    private final String accountCode;
    private final long availableMinor;
    private final long requestedMinor;

    public InsufficientFundsException(String accountCode, long availableMinor, long requestedMinor) {
        super("insufficient funds in account " + accountCode + ": available " + availableMinor
                + ", requested " + requestedMinor + " minor units");
        this.accountCode = accountCode;
        this.availableMinor = availableMinor;
        this.requestedMinor = requestedMinor;
    }

    public String accountCode() { return accountCode; }
    public long availableMinor() { return availableMinor; }
    public long requestedMinor() { return requestedMinor; }
}
