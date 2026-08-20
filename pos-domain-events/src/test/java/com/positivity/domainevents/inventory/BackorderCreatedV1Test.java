package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class BackorderCreatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID BACKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000041");
    private static final UUID WORKORDER_LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000042");
    private static final UUID SALES_ORDER_LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000043");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-18T10:00:00Z");

    @Test
    void roundTripsForAWorkorderLine() {
        BackorderCreatedV1 evt = new BackorderCreatedV1(
                BACKORDER_ID, WORKORDER_LINE_ID, null, "SKU-1", new BigDecimal("3"), null, OCCURRED_AT);

        String json = MAPPER.writeValueAsString(evt);
        BackorderCreatedV1 back = MAPPER.readValue(json, BackorderCreatedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(BackorderCreatedV1.EVENT_TYPE).isEqualTo("inventory.backorder.created");
        assertThat(BackorderCreatedV1.SCHEMA_VERSION).isEqualTo(2);
    }

    @Test
    void roundTripsForASalesOrderLine() {
        BackorderCreatedV1 evt = new BackorderCreatedV1(
                BACKORDER_ID, null, SALES_ORDER_LINE_ID, "SKU-1", new BigDecimal("3"), null, OCCURRED_AT);

        String json = MAPPER.writeValueAsString(evt);
        BackorderCreatedV1 back = MAPPER.readValue(json, BackorderCreatedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(back.workorderLineId()).isNull();
        assertThat(back.salesOrderLineId()).isEqualTo(SALES_ORDER_LINE_ID);
    }

    @Test
    void rejectsNeitherDemandLine() {
        assertThatThrownBy(() -> new BackorderCreatedV1(
                        BACKORDER_ID, null, null, "SKU-1", new BigDecimal("3"), null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsBothDemandLines() {
        assertThatThrownBy(() -> new BackorderCreatedV1(
                        BACKORDER_ID,
                        WORKORDER_LINE_ID,
                        SALES_ORDER_LINE_ID,
                        "SKU-1",
                        new BigDecimal("3"),
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }
}
