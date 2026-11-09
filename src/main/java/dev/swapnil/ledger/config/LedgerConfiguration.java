package dev.swapnil.ledger.config;

import dev.swapnil.ledger.application.port.in.AccountUseCases;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase;
import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.port.out.JournalEntryRepository;
import dev.swapnil.ledger.application.service.AccountService;
import dev.swapnil.ledger.application.service.TransferService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free application services into Spring. */
@Configuration
class LedgerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PostTransferUseCase postTransferUseCase(AccountRepository accounts, JournalEntryRepository entries, Clock clock) {
        return new TransferService(accounts, entries, clock);
    }

    @Bean
    AccountUseCases accountUseCases(AccountRepository accounts) {
        return new AccountService(accounts);
    }
}
