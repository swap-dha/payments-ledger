package dev.swapnil.ledger.adapter.in.web;

import dev.swapnil.ledger.application.port.out.DuplicateAccountCodeException;
import dev.swapnil.ledger.application.port.out.InsufficientFundsException;
import dev.swapnil.ledger.application.service.TransferService.AccountNotFoundException;
import dev.swapnil.ledger.application.service.TransferService.CurrencyMismatchException;
import dev.swapnil.ledger.application.service.TransferService.IdempotencyConflictException;
import dev.swapnil.ledger.domain.model.InvalidJournalEntryException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps failures to RFC 9457 problem details. Status codes follow one rule:
 * 400 = the request is malformed, 404 = it names something that does not exist,
 * 409 = it collides with what is already stored, 422 = well-formed but breaks a ledger rule.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final String TYPE_BASE = "https://github.com/swap-dha/payments-ledger/problems/";

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found", "Account not found", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency key reused", e.getMessage());
    }

    @ExceptionHandler(DuplicateAccountCodeException.class)
    ProblemDetail duplicateAccount(DuplicateAccountCodeException e) {
        return problem(HttpStatus.CONFLICT, "account-code-taken", "Account code already exists", e.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch", "Currency mismatch", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "Insufficient funds", e.getMessage());
        p.setProperty("accountCode", e.accountCode());
        p.setProperty("availableMinor", e.availableMinor());
        p.setProperty("requestedMinor", e.requestedMinor());
        return p;
    }

    @ExceptionHandler(InvalidJournalEntryException.class)
    ProblemDetail invalidEntry(InvalidJournalEntryException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-journal-entry", "Ledger rule violated", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> fields = new TreeMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "request body failed validation");
        p.setProperty("errors", fields);
        return p;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "missing-header", "Missing header",
                "required header '" + e.getHeaderName() + "' is not present");
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof HttpMessageNotReadableException
                ? "request body is not valid JSON or has a wrong field type"
                : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(TYPE_BASE + type));
        p.setTitle(title);
        return p;
    }
}
