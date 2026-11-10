package dev.swapnil.ledger.application.service;

import dev.swapnil.ledger.application.port.in.AccountUseCases;
import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.service.TransferService.AccountNotFoundException;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;

import java.util.Currency;

public class AccountService implements AccountUseCases {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Account openAccount(OpenAccountCommand cmd) {
        Account account = Account.open(cmd.code(), cmd.name(), Currency.getInstance(cmd.currency()), cmd.type(),
                cmd.allowNegative());
        accounts.save(account); // throws DuplicateAccountCodeException if the code is taken
        return account;
    }

    @Override
    public Balance balanceOf(AccountId id) {
        Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        long signed = accounts.signedPostingSum(id);
        return new Balance(account, signed, account.type().naturalBalance(signed));
    }
}
