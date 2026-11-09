package dev.swapnil.ledger.application.port.out;

public class DuplicateAccountCodeException extends RuntimeException {

    public DuplicateAccountCodeException(String code, Throwable cause) {
        super("account code already exists: " + code, cause);
    }
}
