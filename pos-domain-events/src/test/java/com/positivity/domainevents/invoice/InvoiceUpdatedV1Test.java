package com.positivity.domainevents.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InvoiceUpdatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID INVOICE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000001");
    private static final UUID WORKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000002");

    private static InvoiceUpdatedV1 event(List<TaxBreakdownLine> breakdown) {
        return new InvoiceUpdatedV1(
                INVOICE_ID,
                "INV-2026-000123",
                WORKORDER_ID,
                null,
                null,
                "party-1",
                "FINALIZED",
                new BigDecimal("200.00"),
                new BigDecimal("16.53"),
                new BigDecimal("216.53"),
                BigDecimal.ZERO,
                Instant.parse("2026-07-20T10:00:00Z"),
                Instant.parse("2026-07-20T10:05:00Z"),
                breakdown);
    }

    @Test
    void roundTripPreservesTaxBreakdown() {
        TaxBreakdownLine state = new TaxBreakdownLine(
                "1",
                "STATE",
                "STATE",
                new BigDecimal("0.0725"),
                new BigDecimal("200.00"),
                new BigDecimal("14.50"),
                false,
                null);
        TaxBreakdownLine county = new TaxBreakdownLine(
                "1",
                "COUNTY",
                "COUNTY",
                new BigDecimal("0.0102"),
                new BigDecimal("200.00"),
                new BigDecimal("2.03"),
                false,
                null);
        InvoiceUpdatedV1 evt = event(List.of(state, county));

        String json = MAPPER.writeValueAsString(evt);
        InvoiceUpdatedV1 read = MAPPER.readValue(json, InvoiceUpdatedV1.class);

        assertThat(read).isEqualTo(evt);
        assertThat(read.taxBreakdown()).hasSize(2);
        // Scalar rollup equals the sum of the breakdown taxAmounts (decision D-T6 invariant).
        BigDecimal breakdownSum =
                read.taxBreakdown().stream().map(TaxBreakdownLine::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(read.tax()).isEqualByComparingTo(breakdownSum);
    }

    @Test
    void roundTripToleratesAbsentTaxBreakdown() {
        // Older producers / non-breakdown transitions omit the field entirely.
        String legacyJson = MAPPER.writeValueAsString(event(null));
        assertThat(legacyJson).doesNotContain("\"taxBreakdown\":[");

        InvoiceUpdatedV1 read = MAPPER.readValue(legacyJson, InvoiceUpdatedV1.class);
        assertThat(read.taxBreakdown()).isNull();
        assertThat(read.status()).isEqualTo("FINALIZED");
    }

    @Test
    void schemaStaysAtVersionOne() {
        assertThat(InvoiceUpdatedV1.SCHEMA_VERSION).isEqualTo(1);
        assertThat(InvoiceUpdatedV1.EVENT_TYPE).isEqualTo("invoice.invoice.updated");
    }

    @Test
    void exemptZeroRateRowSurvivesRoundTrip() {
        TaxBreakdownLine exemptRow = new TaxBreakdownLine(
                "2",
                "STATE",
                "STATE",
                new BigDecimal("0.0725"),
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                true,
                "RESALE");
        InvoiceUpdatedV1 read =
                MAPPER.readValue(MAPPER.writeValueAsString(event(List.of(exemptRow))), InvoiceUpdatedV1.class);

        TaxBreakdownLine row = read.taxBreakdown().get(0);
        assertThat(row.exempt()).isTrue();
        assertThat(row.exemptionReasonCode()).isEqualTo("RESALE");
        assertThat(row.taxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
