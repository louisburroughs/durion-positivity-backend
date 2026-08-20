package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class BackorderResolvedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID BACKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000051");
    private static final UUID WORKORDER_LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000052");
    private static final UUID SALES_ORDER_LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000053");
    private static final UUID LOCATION_ID = UUID.fromString("01980a58-0000-7000-8000-000000000054");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-18T10:00:00Z");

    @Test
    void roundTripsForAWorkorderLine() {
        BackorderResolvedV1 evt = new BackorderResolvedV1(
                BACKORDER_ID,
                WORKORDER_LINE_ID,
                null,
                "SKU-1",
                new BigDecimal("3"),
                "AVAILABILITY",
                LOCATION_ID,
                OCCURRED_AT);

        String json = MAPPER.writeValueAsString(evt);
        BackorderResolvedV1 back = MAPPER.readValue(json, BackorderResolvedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(BackorderResolvedV1.EVENT_TYPE).isEqualTo("inventory.backorder.resolved");
        assertThat(BackorderResolvedV1.SCHEMA_VERSION).isEqualTo(2);
    }

    @Test
    void roundTripsForASalesOrderLine() {
        BackorderResolvedV1 evt = new BackorderResolvedV1(
                BACKORDER_ID,
                null,
                SALES_ORDER_LINE_ID,
                "SKU-1",
                new BigDecimal("3"),
                "REPLENISHMENT_RECEIPT",
                LOCATION_ID,
                OCCURRED_AT);

        String json = MAPPER.writeValueAsString(evt);
        BackorderResolvedV1 back = MAPPER.readValue(json, BackorderResolvedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(back.workorderLineId()).isNull();
        assertThat(back.salesOrderLineId()).isEqualTo(SALES_ORDER_LINE_ID);
    }

    @Test
    void rejectsNeitherDemandLine() {
        assertThatThrownBy(() -> new BackorderResolvedV1(
                        BACKORDER_ID, null, null, "SKU-1", new BigDecimal("3"), "MANUAL", LOCATION_ID, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsBothDemandLines() {
        assertThatThrownBy(() -> new BackorderResolvedV1(
                        BACKORDER_ID,
                        WORKORDER_LINE_ID,
                        SALES_ORDER_LINE_ID,
                        "SKU-1",
                        new BigDecimal("3"),
                        "MANUAL",
                        LOCATION_ID,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }
}
