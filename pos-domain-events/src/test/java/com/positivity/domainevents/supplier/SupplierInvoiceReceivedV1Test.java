package com.positivity.domainevents.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("SupplierInvoiceReceivedV1 additive provenance and terms fields (#2516, ADR-0044 §3)")
class SupplierInvoiceReceivedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID PROFILE_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c1");
    private static final UUID VENDOR_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c2");
    private static final UUID EXCHANGE_ID = UUID.fromString("01980a58-0000-7000-8000-0000000000c3");

    @Test
    @DisplayName("round-trips the new fields")
    void roundTripsNewFields() {
        SupplierInvoiceReceivedV1 fact = new SupplierInvoiceReceivedV1(
                PROFILE_ID,
                "michelin-us",
                "INV-1",
                LocalDate.parse("2026-10-01"),
                SupplierInvoiceReceivedV1.Type.INVOICE,
                "USD",
                new BigDecimal("100.00"),
                new BigDecimal("13.00"),
                new BigDecimal("113.00"),
                null,
                Instant.parse("2026-10-02T00:00:00Z"),
                List.of(),
                VENDOR_ID,
                "EDIWHEEL_B",
                EXCHANGE_ID,
                LocalDate.parse("2026-10-31"),
                "NET30",
                List.of(new SupplierInvoiceTax("HST", new BigDecimal("13.00"))));

        SupplierInvoiceReceivedV1 read =
                MAPPER.readValue(MAPPER.writeValueAsString(fact), SupplierInvoiceReceivedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.vendorId()).isEqualTo(VENDOR_ID);
        assertThat(read.taxes())
                .singleElement()
                .extracting(SupplierInvoiceTax::taxType)
                .isEqualTo("HST");
    }

    @Test
    @DisplayName("a payload published before the fields existed still parses, with every new field null")
    void legacyPayloadStillParses() {
        String legacyJson = """
                {"vendorProfileId":"01980a58-0000-7000-8000-0000000000c1","supplierRef":"michelin-us",
                 "vendorInvoiceNumber":"INV-1","invoiceDate":"2026-10-01","type":"INVOICE","currency":"USD",
                 "totalNetAmount":100.00,"totalTaxAmount":13.00,"totalGrossAmount":113.00,
                 "vendorOrderReference":null,"occurredAt":"2026-10-02T00:00:00Z","lines":[]}
                """;

        SupplierInvoiceReceivedV1 read = MAPPER.readValue(legacyJson, SupplierInvoiceReceivedV1.class);

        assertThat(read.vendorInvoiceNumber()).isEqualTo("INV-1");
        assertThat(read.vendorId()).isNull();
        assertThat(read.channel()).isNull();
        assertThat(read.exchangeId()).isNull();
        assertThat(read.dueDate()).isNull();
        assertThat(read.paymentTerms()).isNull();
        assertThat(read.taxes()).isNull();
    }

    @Test
    @DisplayName("a consumer built on the old schema ignores the new fields")
    void oldConsumerIgnoresNewFields() {
        SupplierInvoiceReceivedV1 fact = new SupplierInvoiceReceivedV1(
                PROFILE_ID,
                "michelin-us",
                "INV-1",
                LocalDate.parse("2026-10-01"),
                SupplierInvoiceReceivedV1.Type.INVOICE,
                "USD",
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-02T00:00:00Z"),
                List.of(),
                VENDOR_ID,
                "EDIWHEEL_B",
                EXCHANGE_ID,
                null,
                null,
                null);

        OldSchemaView old = JsonMapper.builder()
                .findAndAddModules()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readValue(MAPPER.writeValueAsString(fact), OldSchemaView.class);

        assertThat(old.vendorProfileId()).isEqualTo(PROFILE_ID);
        assertThat(old.vendorInvoiceNumber()).isEqualTo("INV-1");
    }

    /** The fields a consumer written against the original v1 payload reads. */
    record OldSchemaView(UUID vendorProfileId, String supplierRef, String vendorInvoiceNumber, LocalDate invoiceDate) {}
}
