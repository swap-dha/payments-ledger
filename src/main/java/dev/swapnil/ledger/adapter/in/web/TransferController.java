package dev.swapnil.ledger.adapter.in.web;

import dev.swapnil.ledger.application.port.in.PostTransferUseCase;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferCommand;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferResult;
import dev.swapnil.ledger.domain.model.AccountId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code POST /transfers} with a mandatory {@code Idempotency-Key} header.
 * <ul>
 *   <li>201 Created — a new journal entry was written</li>
 *   <li>200 OK + {@code Idempotent-Replayed: true} — same key, same transfer: nothing new written</li>
 *   <li>409 Conflict — same key, different transfer</li>
 * </ul>
 * Semantics: {@code fromAccountId} is credited and {@code toAccountId} is debited. For asset
 * accounts that reads naturally (money leaves "from"); for liability accounts it is the reverse,
 * so multi-leg entries with explicit debit/credit sides are planned rather than overloading from/to.
 */
@RestController
@RequestMapping("/transfers")
@Validated
class TransferController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final PostTransferUseCase transfers;

    TransferController(PostTransferUseCase transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    ResponseEntity<TransferResponse> post(
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 128) String idempotencyKey,
            @Valid @RequestBody TransferRequest body) {

        TransferResult result = transfers.postTransfer(new TransferCommand(
                idempotencyKey,
                new AccountId(body.fromAccountId()),
                new AccountId(body.toAccountId()),
                body.amountMinor(),
                body.currency(),
                body.effectiveDate(),
                body.description()));

        var response = new TransferResponse(result.entryId(), result.replayed());
        return result.replayed()
                ? ResponseEntity.ok().header(REPLAYED, "true").body(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @Positive long amountMinor,
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR") String currency,
            LocalDate effectiveDate,
            @Size(max = 512) String description) {
    }

    record TransferResponse(UUID entryId, boolean replayed) {
    }
}
