package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductValueChangedV1Test {

    private static final UUID REVALUATION_ID = UUID.fromString("01980001-0000-7000-8000-000000000030");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-24T12:00:00Z");

    @Test
    void carriesSchemaIdentity() {
        assertThat(ProductValueChangedV1.EVENT_TYPE).isEqualTo("inventory.product-value.changed");
        assertThat(ProductValueChangedV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void carriesOldNewCostAndValueDelta() {
        // AVERAGE write-up: 4.00 -> 5.00 over 10 on-hand => +10.00 delta.
        ProductValueChangedV1 fact = new ProductValueChangedV1(
                REVALUATION_ID,
                "OIL-5W30-5QT",
                "AVERAGE",
                new BigDecimal("4.0000"),
                new BigDecimal("5.0000"),
                10L,
                new BigDecimal("10.0000"),
                "Q3 physical recount correction",
                "cost-admin",
                OCCURRED_AT);

        assertThat(fact.previousUnitCost()).isEqualByComparingTo("4.00");
        assertThat(fact.newUnitCost()).isEqualByComparingTo("5.00");
        assertThat(fact.onHandQuantity()).isEqualTo(10L);
        assertThat(fact.totalValueDelta()).isEqualByComparingTo("10.00");
        assertThat(fact.costingMethod()).isEqualTo("AVERAGE");
    }

    @Test
    void acceptsNullPreviousCostForUncostedSku() {
        ProductValueChangedV1 fact = new ProductValueChangedV1(
                REVALUATION_ID,
                "SKU-NEW",
                "STANDARD",
                null,
                new BigDecimal("6.0000"),
                0L,
                BigDecimal.ZERO,
                "Initial standard price",
                "cost-admin",
                OCCURRED_AT);

        assertThat(fact.previousUnitCost()).isNull();
        assertThat(fact.costingMethod()).isEqualTo("STANDARD");
    }

    @Test
    void rejectsBlankSkuMethodReasonActor() {
        assertThatThrownBy(() -> new ProductValueChangedV1(
                        REVALUATION_ID,
                        " ",
                        "AVERAGE",
                        null,
                        BigDecimal.ONE,
                        0L,
                        BigDecimal.ZERO,
                        "r",
                        "a",
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
        assertThatThrownBy(() -> new ProductValueChangedV1(
                        REVALUATION_ID, "SKU-1", " ", null, BigDecimal.ONE, 0L, BigDecimal.ZERO, "r", "a", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costingMethod");
        assertThatThrownBy(() -> new ProductValueChangedV1(
                        REVALUATION_ID,
                        "SKU-1",
                        "AVERAGE",
                        null,
                        BigDecimal.ONE,
                        0L,
                        BigDecimal.ZERO,
                        " ",
                        "a",
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> new ProductValueChangedV1(
                        REVALUATION_ID,
                        "SKU-1",
                        "AVERAGE",
                        null,
                        BigDecimal.ONE,
                        0L,
                        BigDecimal.ZERO,
                        "r",
                        " ",
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void rejectsNullNewCostAndDelta() {
        assertThatThrownBy(() -> new ProductValueChangedV1(
                        REVALUATION_ID, "SKU-1", "AVERAGE", null, null, 0L, BigDecimal.ZERO, "r", "a", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newUnitCost");
        assertThatThrownBy(() -> new ProductValueChangedV1(
                        REVALUATION_ID, "SKU-1", "AVERAGE", null, BigDecimal.ONE, 0L, null, "r", "a", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalValueDelta");
    }
}
