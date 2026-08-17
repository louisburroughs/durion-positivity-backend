package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ReservationOutcomeV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID RESERVATION_ID = UUID.fromString("01980a58-0000-7000-8000-000000000061");
    private static final UUID WORKORDER_LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000062");
    private static final UUID SALES_ORDER_LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000063");
    private static final UUID BACKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000064");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-18T10:00:00Z");

    @Test
    void roundTripsCoveredForAWorkorderLine() {
        ReservationOutcomeV1 evt =
                new ReservationOutcomeV1(RESERVATION_ID, WORKORDER_LINE_ID, null, "SKU-1", 3, true, null, OCCURRED_AT);

        String json = MAPPER.writeValueAsString(evt);
        ReservationOutcomeV1 back = MAPPER.readValue(json, ReservationOutcomeV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(ReservationOutcomeV1.EVENT_TYPE).isEqualTo("inventory.reservation.outcome.recorded");
        assertThat(ReservationOutcomeV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void roundTripsUncoveredForASalesOrderLine() {
        ReservationOutcomeV1 evt = new ReservationOutcomeV1(
                RESERVATION_ID, null, SALES_ORDER_LINE_ID, "SKU-1", 3, false, BACKORDER_ID, OCCURRED_AT);

        String json = MAPPER.writeValueAsString(evt);
        ReservationOutcomeV1 back = MAPPER.readValue(json, ReservationOutcomeV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(back.workorderLineId()).isNull();
        assertThat(back.salesOrderLineId()).isEqualTo(SALES_ORDER_LINE_ID);
        assertThat(back.backorderId()).isEqualTo(BACKORDER_ID);
    }

    @Test
    void rejectsNeitherDemandLine() {
        assertThatThrownBy(
                        () -> new ReservationOutcomeV1(RESERVATION_ID, null, null, "SKU-1", 3, true, null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsBothDemandLines() {
        assertThatThrownBy(() -> new ReservationOutcomeV1(
                        RESERVATION_ID, WORKORDER_LINE_ID, SALES_ORDER_LINE_ID, "SKU-1", 3, true, null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void rejectsUncoveredWithoutBackorderId() {
        assertThatThrownBy(() -> new ReservationOutcomeV1(
                        RESERVATION_ID, WORKORDER_LINE_ID, null, "SKU-1", 3, false, null, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backorderId");
    }
}
