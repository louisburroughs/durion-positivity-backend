package com.positivity.domainevents.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WorkorderServiceCompletedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID WORKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000010");
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000011");
    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000012");
    private static final UUID LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000013");
    private static final UUID SERVICE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000014");

    private static WorkorderServiceCompletedV1 event() {
        return new WorkorderServiceCompletedV1(
                WORKORDER_ID,
                "WO-2026-1001",
                PARTY_ID,
                VEHICLE_ID,
                null,
                null,
                Instant.parse("2026-07-20T10:00:00Z"),
                new BigDecimal("149.99"),
                List.of(new WorkorderServiceCompletedV1.PerformedService(
                        LINE_ID,
                        SERVICE_ID,
                        "Oil change",
                        new BigDecimal("1.0"),
                        new BigDecimal("149.99"),
                        new BigDecimal("149.99"))));
    }

    @Test
    void roundTripPreservesFields() {
        WorkorderServiceCompletedV1 evt = event();

        String json = MAPPER.writeValueAsString(evt);
        WorkorderServiceCompletedV1 back = MAPPER.readValue(json, WorkorderServiceCompletedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(back.services()).hasSize(1);
        assertThat(back.services().get(0).description()).isEqualTo("Oil change");
        assertThat(WorkorderServiceCompletedV1.EVENT_TYPE).isEqualTo("workorder.service.completed.v1");
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new WorkorderServiceCompletedV1(
                        null, null, PARTY_ID, VEHICLE_ID, null, null, Instant.now(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkorderServiceCompletedV1(
                        WORKORDER_ID, null, PARTY_ID, VEHICLE_ID, null, null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkorderServiceCompletedV1(
                        WORKORDER_ID, null, PARTY_ID, VEHICLE_ID, null, null, Instant.now(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
