package dev.swapnil.ledger.adapter.in.web;

import dev.swapnil.ledger.application.port.in.AccountUseCases;
import dev.swapnil.ledger.application.port.in.AccountUseCases.Balance;
import dev.swapnil.ledger.application.port.in.AccountUseCases.OpenAccountCommand;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.AccountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountUseCases accounts;

    AccountController(AccountUseCases accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest body) {
        Account account = accounts.openAccount(
                new OpenAccountCommand(body.code(), body.name(), body.currency(), body.type(),
                        Boolean.TRUE.equals(body.allowNegative())));
        return ResponseEntity
                .created(URI.create("/accounts/" + account.id() + "/balance"))
                .body(AccountResponse.of(account));
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@PathVariable UUID id) {
        Balance b = accounts.balanceOf(new AccountId(id));
        return new BalanceResponse(b.account().id().value(), b.account().code(),
                b.account().currency().getCurrencyCode(), b.account().type(),
                b.naturalMinor(), b.signedMinor());
    }

    record OpenAccountRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 255) String name,
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR") String currency,
            @NotNull AccountType type,
            Boolean allowNegative) {  // optional, defaults to false: overdraft is opt-in
    }

    record AccountResponse(UUID id, String code, String name, String currency, AccountType type,
                           boolean allowNegative) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.id().value(), a.code(), a.name(), a.currency().getCurrencyCode(), a.type(),
                    a.allowNegative());
        }
    }

    /**
     * @param balanceMinor the balance as an accountant reads it: positive for a normal balance
     * @param signedMinor  raw posting sum, + debit / - credit, for reconciliation
     */
    record BalanceResponse(UUID accountId, String code, String currency, AccountType type,
                           long balanceMinor, long signedMinor) {
    }
}
