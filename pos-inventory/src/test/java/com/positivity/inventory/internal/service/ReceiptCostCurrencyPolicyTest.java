package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReceiptCostCurrencyPolicy - receipt cost only in the functional currency (ADR-0067 DF-6, #2314)")
class ReceiptCostCurrencyPolicyTest {

    private final ReceiptCostCurrencyPolicy policy = new ReceiptCostCurrencyPolicy("USD");

    @Test
    @DisplayName("the functional currency, in any case or padding, carries no hold")
    void functionalCurrency_noHold() {
        assertThat(policy.awaitingCostReason("USD")).isEmpty();
        assertThat(policy.awaitingCostReason(" usd ")).isEmpty();
    }

    @Test
    @DisplayName("another currency, a blank one or none at all is held with a currency reason")
    void otherCurrency_held() {
        assertThat(policy.awaitingCostReason("EUR"))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .startsWith("AWAITING_COST")
                        .contains("EUR")
                        .contains("USD"));
        assertThat(policy.awaitingCostReason(null)).isPresent();
        assertThat(policy.awaitingCostReason(" ")).isPresent();
        assertThat(policy.awaitingCostReason("US Dollars")).isPresent();
    }

    @Test
    @DisplayName("a configured functional currency that is not ISO 4217 fails fast")
    void invalidFunctionalCurrency_rejected() {
        assertThatThrownBy(() -> new ReceiptCostCurrencyPolicy("dollars")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ReceiptCostCurrencyPolicy(" ")).isInstanceOf(IllegalStateException.class);
    }
}
