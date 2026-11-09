package dev.swapnil.ledger.application.service;

import dev.swapnil.ledger.application.port.in.PostTransferUseCase.TransferCommand;
import dev.swapnil.ledger.application.port.out.AccountRepository;
import dev.swapnil.ledger.application.port.out.DuplicateIdempotencyKeyException;
import dev.swapnil.ledger.application.port.out.JournalEntryRepository;
import dev.swapnil.ledger.application.service.TransferService.AccountNotFoundException;
import dev.swapnil.ledger.application.service.TransferService.CurrencyMismatchException;
import dev.swapnil.ledger.application.service.TransferService.IdempotencyConflictException;
import dev.swapnil.ledger.domain.model.Account;
import dev.swapnil.ledger.domain.model.AccountId;
import dev.swapnil.ledger.domain.model.AccountType;
import dev.swapnil.ledger.domain.model.JournalEntry;
import dev.swapnil.ledger.domain.model.Posting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final InMemoryEntries entries = new InMemoryEntries();
    private final TransferService service = new TransferService(accounts, entries, CLOCK);

    private Account cash;
    private Account customer;

    @BeforeEach
    void setUp() {
        cash = Account.open("CASH", "Cash", INR, AccountType.ASSET);
        customer = Account.open("CUST-1", "Customer wallet", INR, AccountType.LIABILITY);
        accounts.save(cash);
        accounts.save(customer);
    }

    @Test
    void postsATransferAndMovesTheMoney() {
        var result = service.postTransfer(transfer("k1", 10_000));

        assertThat(result.replayed()).isFalse();
        assertThat(entries.byKey.get("k1").id()).isEqualTo(result.entryId());
        assertThat(accounts.signedPostingSum(cash.id())).isEqualTo(-10_000);
        assertThat(accounts.signedPostingSum(customer.id())).isEqualTo(10_000);
    }

    @Test
    void defaultsEffectiveDateToTodayFromTheClock() {
        service.postTransfer(new TransferCommand("k1", cash.id(), customer.id(), 100, "INR", null, null));
        assertThat(entries.byKey.get("k1").effectiveDate()).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void retryWithSameKeyAndSamePayloadReturnsOriginalIdAndPostsOnce() {
        var first = service.postTransfer(transfer("k1", 10_000));
        var retry = service.postTransfer(transfer("k1", 10_000));

        assertThat(retry.entryId()).isEqualTo(first.entryId());
        assertThat(retry.replayed()).isTrue();
        assertThat(entries.saves).isEqualTo(1);
        assertThat(accounts.signedPostingSum(customer.id())).isEqualTo(10_000);
    }

    @Test
    void sameKeyWithDifferentAmountIsAConflictNotASilentSuccess() {
        service.postTransfer(transfer("k1", 10_000));

        assertThatThrownBy(() -> service.postTransfer(transfer("k1", 99_999)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(entries.saves).isEqualTo(1);
    }

    @Test
    void racingDuplicateFallsBackToTheWinnersEntry() {
        // Simulates two requests passing the "key unused?" check at the same moment:
        // the other request commits first, so our save hits the unique constraint.
        JournalEntry winner = JournalEntry.transfer("k1", cash.id(), customer.id(), 10_000, INR,
                LocalDate.of(2026, 11, 9), null);
        entries.commitBehindOurBack = winner;

        var result = service.postTransfer(transfer("k1", 10_000));

        assertThat(result.entryId()).isEqualTo(winner.id());
        assertThat(result.replayed()).isTrue();
    }

    @Test
    void unknownAccountIsRejectedBeforeAnythingIsStored() {
        var cmd = new TransferCommand("k1", AccountId.newId(), customer.id(), 100, "INR", null, null);

        assertThatThrownBy(() -> service.postTransfer(cmd)).isInstanceOf(AccountNotFoundException.class);
        assertThat(entries.saves).isZero();
    }

    @Test
    void currencyMustMatchBothAccounts() {
        var cmd = new TransferCommand("k1", cash.id(), customer.id(), 100, "EUR", null, null);

        assertThatThrownBy(() -> service.postTransfer(cmd)).isInstanceOf(CurrencyMismatchException.class);
        assertThat(entries.saves).isZero();
    }

    private TransferCommand transfer(String key, long amount) {
        return new TransferCommand(key, cash.id(), customer.id(), amount, "INR", LocalDate.of(2026, 11, 9), "test");
    }

    // ---- in-memory adapters: the port interfaces make the service testable without a database ----

    private class InMemoryAccounts implements AccountRepository {
        final Map<AccountId, Account> byId = new HashMap<>();

        public void save(Account a) { byId.put(a.id(), a); }

        public Optional<Account> findById(AccountId id) { return Optional.ofNullable(byId.get(id)); }

        public long signedPostingSum(AccountId id) {
            return entries.byKey.values().stream()
                    .flatMap(e -> e.postings().stream())
                    .filter(p -> p.accountId().equals(id))
                    .mapToLong(Posting::amountMinor)
                    .sum();
        }
    }

    private static class InMemoryEntries implements JournalEntryRepository {
        final Map<String, JournalEntry> byKey = new HashMap<>();
        int saves;
        JournalEntry commitBehindOurBack;

        public void save(JournalEntry e) {
            if (commitBehindOurBack != null) {
                byKey.put(commitBehindOurBack.idempotencyKey(), commitBehindOurBack);
                commitBehindOurBack = null;
            }
            if (byKey.containsKey(e.idempotencyKey())) {
                throw new DuplicateIdempotencyKeyException(e.idempotencyKey(), null);
            }
            byKey.put(e.idempotencyKey(), e);
            saves++;
        }

        public Optional<JournalEntry> findByIdempotencyKey(String key) {
            return Optional.ofNullable(byKey.get(key));
        }
    }
}
