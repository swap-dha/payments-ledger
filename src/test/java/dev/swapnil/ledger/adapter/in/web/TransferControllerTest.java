package dev.swapnil.ledger.adapter.in.web;

import dev.swapnil.ledger.application.port.in.PostTransferUseCase;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferCommand;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferResult;
import dev.swapnil.ledger.application.port.out.InsufficientFundsException;
import dev.swapnil.ledger.application.service.TransferService.AccountNotFoundException;
import dev.swapnil.ledger.application.service.TransferService.CurrencyMismatchException;
import dev.swapnil.ledger.application.service.TransferService.IdempotencyConflictException;
import dev.swapnil.ledger.domain.model.AccountId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of POST /transfers: status codes, headers, problem details. No database. */
class TransferControllerTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    private final AtomicReference<TransferCommand> received = new AtomicReference<>();
    private PostTransferUseCase useCase = cmd -> {
        received.set(cmd);
        return new TransferResult(ENTRY, false);
    };

    private ResultActions postTransfer(String key, String json) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransferController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        var request = post("/transfers").contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static String body(long amount, String currency) {
        return """
                {"fromAccountId":"%s","toAccountId":"%s","amountMinor":%d,"currency":"%s",
                 "effectiveDate":"2026-11-09","description":"rent"}
                """.formatted(FROM, TO, amount, currency);
    }

    @Test
    void newTransferIs201WithEntryId() throws Exception {
        postTransfer("key-1", body(10_000, "INR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryId").value(ENTRY.toString()))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        TransferCommand cmd = received.get();
        assertThat(cmd.idempotencyKey()).isEqualTo("key-1");
        assertThat(cmd.from()).isEqualTo(new AccountId(FROM));
        assertThat(cmd.amountMinor()).isEqualTo(10_000);
    }

    @Test
    void replayIs200WithReplayHeader() throws Exception {
        useCase = cmd -> new TransferResult(ENTRY, true);

        postTransfer("key-1", body(10_000, "INR"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.entryId").value(ENTRY.toString()));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        postTransfer(null, body(10_000, "INR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("required header 'Idempotency-Key' is not present"));
        assertThat(received.get()).isNull();
    }

    @Test
    void invalidBodyIs400WithFieldErrors() throws Exception {
        postTransfer("key-1", body(-5, "rupees"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists())
                .andExpect(jsonPath("$.errors.currency").exists());
        assertThat(received.get()).isNull();
    }

    @Test
    void malformedJsonIs400() throws Exception {
        postTransfer("key-1", "{not json")
                .andExpect(status().isBadRequest());
    }

    @Test
    void reusedKeyWithDifferentTransferIs409() throws Exception {
        useCase = cmd -> { throw new IdempotencyConflictException(cmd.idempotencyKey()); };

        postTransfer("key-1", body(10_000, "INR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/idempotency-key-reused")));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        useCase = cmd -> { throw new AccountNotFoundException(cmd.from()); };

        postTransfer("key-1", body(10_000, "INR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Account not found"));
    }

    @Test
    void insufficientFundsIs422WithAmounts() throws Exception {
        useCase = cmd -> { throw new InsufficientFundsException("OPERATING", 1_000, 10_000); };

        postTransfer("key-1", body(10_000, "INR"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/insufficient-funds")))
                .andExpect(jsonPath("$.accountCode").value("OPERATING"))
                .andExpect(jsonPath("$.availableMinor").value(1_000))
                .andExpect(jsonPath("$.requestedMinor").value(10_000));
    }

    @Test
    void currencyMismatchIs422() throws Exception {
        useCase = cmd -> {
            throw new CurrencyMismatchException(cmd.to(), Currency.getInstance("INR"), Currency.getInstance("EUR"));
        };

        postTransfer("key-1", body(10_000, "EUR"))
                .andExpect(status().isUnprocessableContent());
    }
}
