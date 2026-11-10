package dev.swapnil.ledger;

import dev.swapnil.ledger.application.port.in.PostTransferUseCase;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferCommand;
import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.port.out.InsufficientFundsException;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Days 7-8 guarantee: concurrent debits never overdraw an account.
 * Without row locks, all ten threads in the race test would read "balance 1,000" and all ten
 * would pass the check, leaving the account at -2,000.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OverdraftConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    private static final Currency INR = Currency.getInstance("INR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired PostTransferUseCase transfers;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    private Account capital;   // EQUITY: where the money comes from
    private Account operating; // ASSET: the account being drained, overdraft not allowed
    private Account vendors;   // EXPENSE: where the money goes

    @BeforeEach
    void fundOperatingWith1000() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        capital = Account.open("CAPITAL-" + s, "Owner capital", INR, AccountType.EQUITY);
        operating = Account.open("OPERATING-" + s, "Operating account", INR, AccountType.ASSET);
        vendors = Account.open("VENDORS-" + s, "Vendor payments", INR, AccountType.EXPENSE);
        accounts.save(capital);
        accounts.save(operating);
        accounts.save(vendors);

        // owner injects 1,000.00: credit capital, debit operating
        transfers.postTransfer(cmd(capital.id(), operating.id(), 100_000));
    }

    @Test
    void spendingMoreThanTheBalanceIsRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> transfers.postTransfer(cmd(operating.id(), vendors.id(), 100_001)))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("available 100000, requested 100001");

        assertThat(naturalBalance(operating)).isEqualTo(100_000);
        assertThat(accounts.signedPostingSum(vendors.id())).isZero();
    }

    @Test
    void spendingExactlyTheBalanceIsAllowed() {
        transfers.postTransfer(cmd(operating.id(), vendors.id(), 100_000));

        assertThat(naturalBalance(operating)).isZero();
    }

    @RepeatedTest(3)
    void tenConcurrentPaymentsOf300CannotOverdrawABalanceOf1000() throws Exception {
        int threads = 10;
        long payment = 30_000; // 300.00 each; only 3 fit into 1,000.00
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return transfers.postTransfer(cmd(operating.id(), vendors.id(), payment));
                }));
            }
            start.countDown();

            AtomicInteger paid = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            for (Future<?> f : results) {
                try {
                    f.get();
                    paid.incrementAndGet();
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(InsufficientFundsException.class);
                    refused.incrementAndGet();
                }
            }

            assertThat(paid.get()).as("payments that went through").isEqualTo(3);
            assertThat(refused.get()).as("payments refused").isEqualTo(7);
            assertThat(naturalBalance(operating)).as("remaining balance").isEqualTo(10_000);
            assertThat(accounts.signedPostingSum(vendors.id())).as("vendors received").isEqualTo(90_000);
            assertThat(cachedBalance(operating)).as("cached balance matches postings")
                    .isEqualTo(accounts.signedPostingSum(operating.id()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void opposingTransfersBetweenTwoAccountsDoNotDeadlock() throws Exception {
        // A->B and B->A at once is the classic deadlock: each holds one row and waits for the other.
        // Locking in account-id order makes it impossible.
        Account second = Account.open("SECOND-" + UUID.randomUUID().toString().substring(0, 8),
                "Second operating", INR, AccountType.ASSET);
        accounts.save(second);
        transfers.postTransfer(cmd(capital.id(), second.id(), 100_000));

        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> forward = pool.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) transfers.postTransfer(cmd(operating.id(), second.id(), 1_000));
                return null;
            });
            Future<?> backward = pool.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) transfers.postTransfer(cmd(second.id(), operating.id(), 1_000));
                return null;
            });
            start.countDown();
            forward.get();   // a deadlock would surface here as an ExecutionException from PostgreSQL
            backward.get();

            assertThat(naturalBalance(operating)).isEqualTo(100_000);
            assertThat(naturalBalance(second)).isEqualTo(100_000);
        } finally {
            pool.shutdownNow();
        }
    }

    private long naturalBalance(Account account) {
        return account.type().naturalBalance(accounts.signedPostingSum(account.id()));
    }

    private long cachedBalance(Account account) {
        return jdbc.queryForObject("SELECT balance_minor FROM account WHERE id = ?", Long.class, account.id().value());
    }

    private static TransferCommand cmd(AccountId from, AccountId to, long amount) {
        return new TransferCommand("od-" + UUID.randomUUID(), from, to, amount, "INR", DAY, null);
    }
}
