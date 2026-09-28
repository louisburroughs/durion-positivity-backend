package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.internal.entity.OrderPaymentRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-intent net settlement the cancellation and return sagas refund from
 * (spec R4.6, R5.3), and the settled currency it carries for each intent (ADR-0067 DF-3, #2311).
 */
@DisplayName("NetSettledPayments — net settled funds and currency per payment intent")
class NetSettledPaymentsTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INTENT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID INTENT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static OrderPaymentRecord entry(
            UUID intentId, OrderPaymentRecord.RecordType type, String amount, String currencyCode) {
        return OrderPaymentRecord.builder()
                .orderId(ORDER_ID)
                .recordType(type)
                .paymentIntentId(intentId)
                .methodType("CARD")
                .amount(new BigDecimal(amount))
                .currencyCode(currencyCode)
                .build();
    }

    private static OrderPaymentRecord settled(UUID intentId, String amount, String currencyCode) {
        return entry(intentId, OrderPaymentRecord.RecordType.SETTLED, amount, currencyCode);
    }

    private static OrderPaymentRecord reversed(UUID intentId, String amount, String currencyCode) {
        return entry(intentId, OrderPaymentRecord.RecordType.REVERSED, amount, currencyCode);
    }

    @Test
    @DisplayName("NSP-001: net = Σ SETTLED − Σ REVERSED per intent, in the currency the entries share")
    void netsSettledMinusReversed_keepingTheSharedCurrency() {
        Map<UUID, NetSettledPayments.NetSettlement> net = NetSettledPayments.byIntent(
                List.of(settled(INTENT_A, "100.00", "CAD"), reversed(INTENT_A, "30.00", "CAD")));

        assertThat(net).containsOnlyKeys(INTENT_A);
        assertThat(net.get(INTENT_A).amount()).isEqualByComparingTo("70.00");
        assertThat(net.get(INTENT_A).currencyCode()).isEqualTo("CAD");
    }

    @Test
    @DisplayName("NSP-002: entries of one intent in different currencies → no single currency (null)")
    void mixedCurrenciesOnOneIntent_yieldNoCurrency() {
        Map<UUID, NetSettledPayments.NetSettlement> net = NetSettledPayments.byIntent(
                List.of(settled(INTENT_A, "100.00", "CAD"), reversed(INTENT_A, "30.00", "USD")));

        assertThat(net.get(INTENT_A).amount()).isEqualByComparingTo("70.00");
        assertThat(net.get(INTENT_A).currencyCode()).isNull();
    }

    @Test
    @DisplayName("NSP-003: an entry stating no currency → no single currency, even if the others agree")
    void entryWithoutCurrency_yieldsNoCurrency() {
        Map<UUID, NetSettledPayments.NetSettlement> net = NetSettledPayments.byIntent(List.of(
                settled(INTENT_A, "60.00", "CAD"),
                settled(INTENT_A, "40.00", null),
                reversed(INTENT_A, "10.00", "CAD")));

        assertThat(net.get(INTENT_A).amount()).isEqualByComparingTo("90.00");
        assertThat(net.get(INTENT_A).currencyCode()).isNull();
    }

    @Test
    @DisplayName("NSP-004: an intent whose net is not positive is left out")
    void fullyReversedIntent_isOmitted() {
        Map<UUID, NetSettledPayments.NetSettlement> net = NetSettledPayments.byIntent(List.of(
                settled(INTENT_A, "50.00", "CAD"),
                reversed(INTENT_A, "50.00", "CAD"),
                settled(INTENT_B, "20.00", "CAD")));

        assertThat(net).containsOnlyKeys(INTENT_B);
        assertThat(net.get(INTENT_B).amount()).isEqualByComparingTo("20.00");
        assertThat(net.get(INTENT_B).currencyCode()).isEqualTo("CAD");
    }

    @Test
    @DisplayName("NSP-005: intents are independent — one intent's mixed currencies do not taint another")
    void currenciesAreTrackedPerIntent() {
        Map<UUID, NetSettledPayments.NetSettlement> net = NetSettledPayments.byIntent(List.of(
                settled(INTENT_A, "40.00", "CAD"),
                settled(INTENT_B, "20.00", "USD"),
                reversed(INTENT_B, "5.00", "CAD")));

        assertThat(net).containsOnlyKeys(INTENT_A, INTENT_B);
        assertThat(net.get(INTENT_A).currencyCode()).isEqualTo("CAD");
        assertThat(net.get(INTENT_B).amount()).isEqualByComparingTo("15.00");
        assertThat(net.get(INTENT_B).currencyCode()).isNull();
    }

    @Test
    @DisplayName("NSP-006: an entry with no payment intent (ON_ACCOUNT) is skipped")
    void entryWithoutIntent_isSkipped() {
        Map<UUID, NetSettledPayments.NetSettlement> net =
                NetSettledPayments.byIntent(List.of(settled(null, "25.00", null), settled(INTENT_A, "10.00", "CAD")));

        assertThat(net).containsOnlyKeys(INTENT_A);
    }
}
