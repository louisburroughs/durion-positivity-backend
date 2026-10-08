package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("GoodsReceiptRecordedV1 (goodsreceipt.recorded v1; CAP:550 S41 #2602)")
class GoodsReceiptRecordedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID RECEIPT = UUID.fromString("019a0000-0000-7000-8000-0000000000d1");
    private static final UUID ORDER = UUID.fromString("019a0000-0000-7000-8000-0000000000d2");
    private static final UUID PO_LINE = UUID.fromString("019a0000-0000-7000-8000-0000000000d3");
    private static final UUID RECEIPT_LINE = UUID.fromString("019a0000-0000-7000-8000-0000000000d4");
    private static final UUID PRODUCT = UUID.fromString("019a0000-0000-7000-8000-0000000000d5");
    private static final UUID LEDGER_ENTRY = UUID.fromString("019a0000-0000-7000-8000-0000000000d6");
    private static final Instant OCCURRED = Instant.parse("2026-10-08T09:30:00Z");

    @Test
    @DisplayName("the additive fields round-trip; schemaVersion stays 1 (ADR-0044 §3)")
    void roundTripsTheAdditiveFields() {
        GoodsReceiptRecordedV1 fact = new GoodsReceiptRecordedV1(
                RECEIPT,
                "GR-1",
                ORDER,
                null,
                40_000L,
                OCCURRED,
                List.of(new GoodsReceiptLine(
                        PO_LINE,
                        PRODUCT.toString(),
                        new BigDecimal("4"),
                        40_000L,
                        RECEIPT_LINE,
                        PRODUCT,
                        38_000L,
                        "STANDARD",
                        LEDGER_ENTRY)),
                "USD");

        GoodsReceiptRecordedV1 read = MAPPER.readValue(MAPPER.writeValueAsString(fact), GoodsReceiptRecordedV1.class);

        assertThat(read).isEqualTo(fact);
        assertThat(read.currencyCode()).isEqualTo("USD");
        assertThat(read.lines().getFirst().inventoryValueMinor()).isEqualTo(38_000L);
        assertThat(GoodsReceiptRecordedV1.SCHEMA_VERSION).isEqualTo(1);
        assertThat(GoodsReceiptRecordedV1.EVENT_TYPE).isEqualTo("goodsreceipt.recorded");
    }

    @Test
    @DisplayName("a fact published before the fields existed reads them as null, never 0")
    void readsAnOlderFactWithNulls() {
        String older = """
                {"receiptId":"%s","receiptNumber":"GR-1","purchaseOrderId":"%s","locationId":null,
                 "totalAccruedAmountMinor":40000,"occurredAt":"%s",
                 "lines":[{"poLineId":"%s","sku":"SKU-1","quantityReceived":4,"accruedAmountMinor":40000}]}
                """.formatted(RECEIPT, ORDER, OCCURRED, PO_LINE);

        GoodsReceiptRecordedV1 read = MAPPER.readValue(older, GoodsReceiptRecordedV1.class);

        assertThat(read.currencyCode()).isNull();
        assertThat(read.lines()).singleElement().satisfies(line -> {
            assertThat(line.accruedAmountMinor()).isEqualTo(40_000L);
            assertThat(line.receiptLineId()).isNull();
            assertThat(line.productId()).isNull();
            assertThat(line.inventoryValueMinor()).isNull();
            assertThat(line.costSource()).isNull();
            assertThat(line.ledgerEntryId()).isNull();
        });
    }
}
