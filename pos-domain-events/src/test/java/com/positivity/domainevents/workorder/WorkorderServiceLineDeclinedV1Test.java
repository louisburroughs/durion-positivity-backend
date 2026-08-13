package com.positivity.domainevents.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WorkorderServiceLineDeclinedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID WORKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000020");
    private static final UUID LINE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000021");
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000022");
    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000023");
    private static final UUID SERVICE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000024");

    private static WorkorderServiceLineDeclinedV1 event() {
        return new WorkorderServiceLineDeclinedV1(
                WORKORDER_ID,
                "WO-2026-1001",
                LINE_ID,
                PARTY_ID,
                VEHICLE_ID,
                SERVICE_ID,
                "Brake pad replacement",
                "Customer will defer to next visit",
                Instant.parse("2026-07-20T10:00:00Z"));
    }

    @Test
    void roundTripPreservesFields() {
        WorkorderServiceLineDeclinedV1 evt = event();

        String json = MAPPER.writeValueAsString(evt);
        WorkorderServiceLineDeclinedV1 back = MAPPER.readValue(json, WorkorderServiceLineDeclinedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(back.declineReason()).isEqualTo("Customer will defer to next visit");
        assertThat(WorkorderServiceLineDeclinedV1.EVENT_TYPE).isEqualTo("workorder.service-line.declined.v1");
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new WorkorderServiceLineDeclinedV1(
                        null, null, LINE_ID, PARTY_ID, VEHICLE_ID, SERVICE_ID, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkorderServiceLineDeclinedV1(
                        WORKORDER_ID, null, null, PARTY_ID, VEHICLE_ID, SERVICE_ID, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkorderServiceLineDeclinedV1(
                        WORKORDER_ID, null, LINE_ID, PARTY_ID, VEHICLE_ID, SERVICE_ID, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
