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
        assertThat(ScrapPostedV1.SCHEMA_VERSION).isEqualTo(1);
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
