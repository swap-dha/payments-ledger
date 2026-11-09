package dev.swapnil.ledger.domain.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final Currency INR = Currency.getInstance("INR");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    private final AccountId cash = AccountId.newId();
    private final AccountId customer = AccountId.newId();
    private final AccountId fees = AccountId.newId();

    @Nested
    class Balanced {

        @Test
        void transferCreatesOneCreditAndOneEqualDebit() {
            var entry = JournalEntry.transfer("key-1", customer, cash, 10_000, INR, TODAY, "top-up");

            assertThat(entry.postings()).containsExactly(
                    new Posting(customer, -10_000),
                    new Posting(cash, 10_000));
            assertThat(entry.postings().stream().mapToLong(Posting::amountMinor).sum()).isZero();
        }

        @Test
        void multiLegEntryWithFeeIsAccepted() {
            // Customer pays 1,000.00; merchant-side cash receives 990.00; fee income 10.00
            var entry = JournalEntry.create("key-2", INR, TODAY, "card payment with fee", List.of(
                    Posting.credit(customer, 100_000),
                    Posting.debit(cash, 99_000),
                    Posting.debit(fees, 1_000)));

            assertThat(entry.postings()).hasSize(3);
        }

        @Test
        void postingsCannotBeModifiedAfterCreation() {
            var postings = new ArrayList<>(List.of(Posting.credit(customer, 500), Posting.debit(cash, 500)));
            var entry = JournalEntry.create("key-3", INR, TODAY, null, postings);

            postings.add(Posting.debit(fees, 1)); // mutating the caller's list must not leak in
            assertThat(entry.postings()).hasSize(2);
            assertThatThrownBy(() -> entry.postings().add(Posting.debit(fees, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Rejected {

        @Test
        void unbalancedEntry() {
            assertThatThrownBy(() -> JournalEntry.create("k", INR, TODAY, null, List.of(
                    Posting.credit(customer, 10_000),
                    Posting.debit(cash, 9_999))))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("off by -1");
        }

        @Test
        void singlePosting() {
            assertThatThrownBy(() -> JournalEntry.create("k", INR, TODAY, null,
                    List.of(Posting.debit(cash, 100))))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("at least two postings");
        }

        @Test
        void sameAccountTwice() {
            assertThatThrownBy(() -> JournalEntry.create("k", INR, TODAY, null, List.of(
                    Posting.credit(cash, 100),
                    Posting.debit(cash, 100))))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("more than once");
        }

        @Test
        void transferToSelf() {
            assertThatThrownBy(() -> JournalEntry.transfer("k", cash, cash, 100, INR, TODAY, null))
                    .isInstanceOf(InvalidJournalEntryException.class);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1, -10_000})
        void nonPositiveAmountInDebitOrCredit(long amount) {
            assertThatThrownBy(() -> Posting.debit(cash, amount)).isInstanceOf(InvalidJournalEntryException.class);
            assertThatThrownBy(() -> Posting.credit(cash, amount)).isInstanceOf(InvalidJournalEntryException.class);
        }

        @Test
        void zeroValuePosting() {
            assertThatThrownBy(() -> new Posting(cash, 0)).isInstanceOf(InvalidJournalEntryException.class);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void missingIdempotencyKey(String key) {
            assertThatThrownBy(() -> JournalEntry.transfer(key, customer, cash, 100, INR, TODAY, null))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("idempotency key");
        }

        @Test
        void sumOverflowIsDetectedNotWrapped() {
            // Long.MAX_VALUE + 1 would silently wrap to Long.MIN_VALUE without addExact
            assertThatThrownBy(() -> JournalEntry.create("k", INR, TODAY, null, List.of(
                    Posting.debit(cash, Long.MAX_VALUE),
                    Posting.debit(fees, 1),
                    Posting.credit(customer, 1))))
                    .isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class NaturalBalance {

        @Test
        void assetGrowsWithDebitsLiabilityWithCredits() {
            assertThat(AccountType.ASSET.naturalBalance(5_000)).isEqualTo(5_000);
            assertThat(AccountType.LIABILITY.naturalBalance(-5_000)).isEqualTo(5_000);
            assertThat(AccountType.INCOME.naturalBalance(-1_000)).isEqualTo(1_000);
        }
    }
}
