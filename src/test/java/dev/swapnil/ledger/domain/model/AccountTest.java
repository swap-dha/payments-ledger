package dev.swapnil.ledger.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    private static final Currency INR = Currency.getInstance("INR");

    @Test
    void assetAccountCannotBeCreditedBelowZeroByDefault() {
        Account cash = Account.open("CASH", "Cash", INR, AccountType.ASSET);

        assertThat(cash.permits(0)).isTrue();
        assertThat(cash.permits(500)).isTrue();
        assertThat(cash.permits(-1)).isFalse();   // signed -1 on an asset = natural balance of -1
    }

    @Test
    void liabilityAccountCannotBeDebitedBelowZeroByDefault() {
        Account wallet = Account.open("WALLET", "Customer wallet", INR, AccountType.LIABILITY);

        assertThat(wallet.permits(-500)).isTrue(); // credit balance: we owe the customer 500
        assertThat(wallet.permits(1)).isFalse();   // debit balance: the customer would owe us
    }

    @Test
    void overdraftIsOptIn() {
        Account funding = Account.open("FUNDING", "Bank funding", INR, AccountType.ASSET, true);

        assertThat(funding.permits(-1_000_000)).isTrue();
    }
}
