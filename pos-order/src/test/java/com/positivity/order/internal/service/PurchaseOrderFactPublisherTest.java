package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.domainevents.order.PurchaseOrderLine;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PurchaseOrderFactPublisher — published line cost basis (#2203)")
class PurchaseOrderFactPublisherTest {

    @Test
    @DisplayName("a line keyed in base publishes conversion factor 1")
    void baseKeyedLine_publishesFactorOne() {
        PurchaseOrderLine line = publishedLine(lineEntity(null, null));

        assertThat(line.unitCostMinor()).isEqualTo(1_000L);
        assertThat(line.conversionFactor()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a line keyed in a document UoM publishes the factor its price is per")
    void documentKeyedLine_publishesItsFactor() {
        assertThat(publishedLine(lineEntity("CASE", new BigDecimal("12"))).conversionFactor())
                .isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("a document UoM without a recorded factor publishes no factor rather than a guess")
    void documentKeyedLineWithoutFactor_publishesNull() {
        assertThat(publishedLine(lineEntity("CASE", null)).conversionFactor()).isNull();
    }

    private static PurchaseOrderLine publishedLine(PurchaseOrderLineEntity line) {
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .purchaseOrderId(UUID.randomUUID())
                .poNumber("PO-2203")
                .vendorId(UUID.randomUUID())
                .status(PurchaseOrderStatus.APPROVED)
                .currency("USD")
                .build();
        return PurchaseOrderFactPublisher.toFact(order, List.of(line), Instant.parse("2026-09-25T12:00:00Z"))
                .lines()
                .getFirst();
    }

    private static PurchaseOrderLineEntity lineEntity(String documentUom, BigDecimal conversionFactor) {
        return PurchaseOrderLineEntity.builder()
                .lineId(UUID.randomUUID())
                .lineNumber(1)
                .skuId(UUID.randomUUID())
                .quantityDecimal(new BigDecimal("12"))
                .unitCostMinor(1_000L)
                .documentUom(documentUom)
                .conversionFactor(conversionFactor)
                .build();
    }
}
