package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScrapPostedV1Test {

    private static final UUID SCRAP_ID = UUID.fromString("01980001-0000-7000-8000-000000000010");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void carriesSchemaIdentity() {
        assertThat(ScrapPostedV1.EVENT_TYPE).isEqualTo("inventory.scrap.posted");
        // odoo-parity J3 (#1053): engine-derived cost bumps the schema to v2 (additive semantics).
        assertThat(ScrapPostedV1.SCHEMA_VERSION).isEqualTo(2);
    }

    @Test
    void acceptsNullableCostWorkorderAndLocations() {
        ScrapPostedV1 fact =
                new ScrapPostedV1(SCRAP_ID, "OIL-5W30-5QT", null, null, 3, "DAMAGED", null, "NONE", null, OCCURRED_AT);

        assertThat(fact.unitCost()).isNull();
        assertThat(fact.costSource()).isEqualTo("NONE");
        assertThat(fact.workorderId()).isNull();
    }

    @Test
    void carriesEngineDerivedCostAndMethodCostSource() {
        // odoo-parity J3 (#1053): a costed scrap carries the engine method-derived unit cost and
        // labels its costSource with the resolved costing method (AVERAGE / STANDARD).
        ScrapPostedV1 avco = new ScrapPostedV1(
                SCRAP_ID,
                "OIL-5W30-5QT",
                null,
                null,
                2,
                "DAMAGED",
                new BigDecimal("4.2500"),
                "AVERAGE",
                null,
                OCCURRED_AT);
        assertThat(avco.unitCost()).isEqualByComparingTo("4.25");
        assertThat(avco.costSource()).isEqualTo("AVERAGE");

        ScrapPostedV1 std = new ScrapPostedV1(
                SCRAP_ID,
                "OIL-5W30-5QT",
                null,
                null,
                2,
                "DAMAGED",
                new BigDecimal("6.0000"),
                "STANDARD",
                null,
                OCCURRED_AT);
        assertThat(std.costSource()).isEqualTo("STANDARD");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() ->
                        new ScrapPostedV1(SCRAP_ID, "SKU-1", null, null, 0, "DAMAGED", null, "NONE", null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void rejectsBlankSkuReasonAndCostSource() {
        assertThatThrownBy(() ->
                        new ScrapPostedV1(SCRAP_ID, " ", null, null, 1, "DAMAGED", null, "NONE", null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
        assertThatThrownBy(
                        () -> new ScrapPostedV1(SCRAP_ID, "SKU-1", null, null, 1, " ", null, "NONE", null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> new ScrapPostedV1(
                        SCRAP_ID, "SKU-1", null, null, 1, "DAMAGED", BigDecimal.ONE, " ", null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("costSource");
    }
}
