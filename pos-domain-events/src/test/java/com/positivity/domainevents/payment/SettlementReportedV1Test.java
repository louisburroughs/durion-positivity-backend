package com.positivity.domainevents.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.positivity.domainevents.payment.SettlementReportedV1.Line;
import com.positivity.domainevents.payment.SettlementReportedV1.LineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SettlementReportedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID EVENT_ID = UUID.fromString("01980a58-0000-7000-8000-000000000001");

    private static Line charge() {
        return new Line(
                "ch_ref_1",
                LineType.CHARGE,
                new BigDecimal("100.00"),
                new BigDecimal("3.00"),
                new BigDecimal("97.00"),
                "pay-ref-1");
    }

    private static SettlementReportedV1 settlement(List<Line> lines) {
        return new SettlementReportedV1(
                EVENT_ID,
                SettlementReportedV1.SCHEMA_VERSION,
                "po_stripe_123",
                "STRIPE",
                LocalDate.parse("2026-07-19"),
                "USD",
                new BigDecimal("100.00"),
                new BigDecimal("3.00"),
                new BigDecimal("97.00"),
                lines,
                Map.of("balanceTransaction", "txn_1"));
    }

    @Test
    void serializationRoundTripPreservesAllFields() {
        SettlementReportedV1 event = settlement(List.of(charge()));

        String json = MAPPER.writeValueAsString(event);
        SettlementReportedV1 read = MAPPER.readValue(json, SettlementReportedV1.class);

        assertThat(read).isEqualTo(event);
        assertThat(json)
                .contains("\"providerCode\":\"STRIPE\"")
                .contains("\"settlementDate\":\"2026-07-19\"")
                .contains("\"lineType\":\"CHARGE\"")
                .contains("\"schemaVersion\":1");
    }

    @Test
    void roundTripPreservesNullableLineFeeAndNet() {
        // Header-only provider: fee/net absent per line, correlation refs still present.
        Line headerOnly = new Line("ch_ref_2", LineType.CHARGE, new BigDecimal("50.00"), null, null, "pay-ref-2");
        SettlementReportedV1 event = new SettlementReportedV1(
                EVENT_ID,
                1,
                "po_x",
                "SQUARE",
                LocalDate.parse("2026-07-19"),
                "USD",
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                List.of(headerOnly),
                null);

        SettlementReportedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(event), SettlementReportedV1.class);

        assertThat(read).isEqualTo(event);
        assertThat(read.lines().get(0).feeAmount()).isNull();
        assertThat(read.lines().get(0).netAmount()).isNull();
        assertThat(read.extension()).isNull();
    }

    @Test
    void enforcesHeaderBalanceInvariant() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SettlementReportedV1(
                        EVENT_ID,
                        1,
                        "po_x",
                        "STRIPE",
                        LocalDate.parse("2026-07-19"),
                        "USD",
                        new BigDecimal("100.00"),
                        new BigDecimal("5.00"),
                        new BigDecimal("97.00"),
                        List.of(),
                        null))
                .withMessageContaining("header invariant");
    }

    @Test
    void headerBalanceIsScaleIndependent() {
        // 100 == 3.000 + 97.00 despite differing scales; compareTo, not equals.
        SettlementReportedV1 event = new SettlementReportedV1(
                EVENT_ID,
                1,
                "po_x",
                "STRIPE",
                LocalDate.parse("2026-07-19"),
                "USD",
                new BigDecimal("100"),
                new BigDecimal("3.000"),
                new BigDecimal("97.00"),
                List.of(),
                null);

        assertThat(event.grossAmount()).isEqualByComparingTo("100");
    }

    @Test
    void requiresCorrelationRefsForChargeRefundChargeback() {
        for (LineType type : List.of(LineType.CHARGE, LineType.REFUND, LineType.CHARGEBACK)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Line(null, type, new BigDecimal("10.00"), null, null, "pay-ref"))
                    .withMessageContaining("providerLineRef");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Line("ref", type, new BigDecimal("10.00"), null, null, "  "))
                    .withMessageContaining("paymentReference");
        }
    }

    @Test
    void allowsMissingRefsForNonMatchingLineTypes() {
        for (LineType type : List.of(
                LineType.FEE,
                LineType.ADJUSTMENT,
                LineType.RESERVE_HOLD,
                LineType.RESERVE_RELEASE,
                LineType.PAYOUT_CORRECTION)) {
            Line line = new Line(null, type, new BigDecimal("1.00"), null, null, null);
            assertThat(line.lineType()).isEqualTo(type);
            assertThat(Line.requiresCorrelationRefs(type)).isFalse();
        }
    }

    @Test
    void rejectsBlankOrNonIsoCurrency() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> settlementWithCurrency("US"))
                .withMessageContaining("currency");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> settlementWithCurrency("usd"))
                .withMessageContaining("currency");
    }

    @Test
    void rejectsBlankSettlementIdAndProviderCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SettlementReportedV1(
                        EVENT_ID,
                        1,
                        " ",
                        "STRIPE",
                        LocalDate.parse("2026-07-19"),
                        "USD",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        List.of(),
                        null))
                .withMessageContaining("settlementId");
    }

    @Test
    void linesListIsDefensivelyCopiedAndImmutable() {
        SettlementReportedV1 event = settlement(List.of(charge()));
        assertThat(event.lines()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> event.lines().add(charge()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static SettlementReportedV1 settlementWithCurrency(String currency) {
        return new SettlementReportedV1(
                EVENT_ID,
                1,
                "po_x",
                "STRIPE",
                LocalDate.parse("2026-07-19"),
                currency,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                null);
    }
}
