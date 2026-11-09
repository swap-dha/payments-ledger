package dev.swapnil.ledger;

import dev.swapnil.ledger.application.port.in.PostTransferUseCase;
import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferCommand;
import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.port.out.JournalEntryRepository;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountType;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end through the real adapters and PostgreSQL. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    private static final Currency INR = Currency.getInstance("INR");

    @Autowired PostTransferUseCase transfers;
    @Autowired AccountRepository accounts;
    @Autowired JournalEntryRepository entries;
    @Autowired JdbcTemplate jdbc;

    private Account cash;
    private Account customer;

    @BeforeEach
    void accounts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        cash = Account.open("CASH-" + suffix, "Cash", INR, AccountType.ASSET);
        customer = Account.open("CUST-" + suffix, "Customer wallet", INR, AccountType.LIABILITY);
        accounts.save(cash);
        accounts.save(customer);
    }

    @Test
    void transferIsStoredAndBalancesFollow() {
        String key = key();
        UUID id = transfers.postTransfer(cmd(key, 25_000));

        assertThat(entries.findByIdempotencyKey(key)).hasValueSatisfying(e -> {
            assertThat(e.id()).isEqualTo(id);
            assertThat(e.postings()).hasSize(2);
        });
        assertThat(accounts.signedPostingSum(cash.id())).as("credited").isEqualTo(-25_000);
        assertThat(accounts.signedPostingSum(customer.id())).as("debited").isEqualTo(25_000);
    }

    @Test
    void storedEntryRoundTripsIntoTheDomainUnchanged() {
        String key = key();
        UUID id = transfers.postTransfer(cmd(key, 1_234));

        var stored = entries.findByIdempotencyKey(key).orElseThrow();
        assertThat(stored.id()).isEqualTo(id);
        assertThat(stored.currency()).isEqualTo(INR);
        assertThat(stored.effectiveDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(stored.description()).isEqualTo("integration test");
    }

    @Test
    void tenConcurrentIdenticalRequestsPostExactlyOnce() throws Exception {
        String key = key();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Callable<UUID> call = () -> {
                    start.await();          // release all threads at once to force the race
                    return transfers.postTransfer(cmd(key, 5_000));
                };
                results.add(pool.submit(call));
            }
            start.countDown();

            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> f : results) {
                ids.add(f.get());
            }

            assertThat(ids).as("every caller gets the same entry id").hasSize(1);
            Integer stored = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM journal_entry WHERE idempotency_key = ?", Integer.class, key);
            assertThat(stored).as("exactly one entry in the database").isEqualTo(1);
            assertThat(accounts.signedPostingSum(customer.id())).as("money moved once").isEqualTo(5_000);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private TransferCommand cmd(String key, long amount) {
        return new TransferCommand(key, cash.id(), customer.id(), amount, "INR",
                LocalDate.of(2026, 11, 9), "integration test");
    }
}
